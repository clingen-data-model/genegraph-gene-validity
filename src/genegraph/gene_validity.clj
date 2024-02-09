(ns genegraph.gene-validity
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.processor :as processor]
            [genegraph.framework.event :as event]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.env :as env]
            [genegraph.gene-validity.gci-model :as gci-model]
            [genegraph.gene-validity.sepio-model :as sepio-model]
            [genegraph.gene-validity.base :as base]
            [genegraph.gene-validity.graphql.schema :as gql-schema]
            [genegraph.gene-validity.versioning :as versioning]
            [com.walmartlabs.lacinia.pedestal2 :as lacinia-pedestal]
            [com.walmartlabs.lacinia.pedestal.internal :as internal]
            [com.walmartlabs.lacinia.resolve :as resolve]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn])
  (:import [org.apache.jena.sparql.core Transactional]
           [org.apache.jena.query ReadWrite]
           [org.apache.kafka.clients.producer KafkaProducer]
           [java.util.concurrent LinkedBlockingQueue ThreadPoolExecutor TimeUnit Executor Executors]
           [com.google.cloud.secretmanager.v1 AccessSecretVersionResponse ProjectName Replication Secret SecretManagerServiceClient SecretPayload SecretVersion SecretName]
           [com.google.protobuf ByteString])
  (:gen-class))

(def gcs-handle
  {:type :gcs
   :bucket "genegraph-framework-dev"})

(def gcs-base-fs-handle
  (assoc gcs-handle :base "base/"))

(def fs-handle
  {:type :file
   :base "data/"})

(def default-env
  {:kafka-user "User:2189780"
   :kafka-consumer-group "genegraph-gene-validity-dev-3"
   :base-fs-handle gcs-base-fs-handle
   #_{:type :file
      :base "data/base/"}
   :local-data-path "data/"})

(def local-env
  (case (System/getenv "GENEGRAPH_PLATFORM")
    "gcp" (assoc (env/build-environment "522856288592" ["dev-genegraph-dev-dx-jaas"])
                 :function (System/getenv "GENEGRAPH_FUNCTION")
                 :base-fs-handle gcs-base-fs-handle
                 :local-data-path "/data")
    {:dev-genegraph-dev-dx-jaas (System/getenv "DX_JAAS_CONFIG_DEV")}))

;; for trying out 
(defonce local-cloud-test-env
  (merge default-env
         (env/build-environment "522856288592" ["dev-genegraph-dev-dx-jaas"])
         {:function "fetch-base"
          :base-fs-handle gcs-base-fs-handle
          :local-data-path "data/"}))

(def env
  (merge default-env local-env))

#_(def env local-cloud-test-env)

(def prop-query
  (rdf/create-query "select ?x where {?x a ?type}"))

(defn add-iri-fn [event]
  (assoc event
         ::event/iri
         (-> (prop-query
              (:gene-validity/model event)
              {:type :sepio/GeneValidityProposition})
             first
             str)))

(def add-iri
  (interceptor/interceptor
   {:name ::add-iri
    :enter (fn [e] (add-iri-fn e))}))

(defn add-publish-actions-fn [event]
  (event/publish event
                 (-> event
                     (set/rename-keys {::event/iri ::event/key
                                       :gene-validity/model ::event/data})
                     (select-keys [::event/key ::event/data])
                     (assoc ::event/topic :gene-validity-sepio))))

(def add-publish-actions
  (interceptor/interceptor
   {:name ::add-publish-actions
    :enter (fn [e] (add-publish-actions-fn e))}))

(defn has-publish-action [m]
  (< 0 (count ((rdf/create-query "select ?x where { ?x :bfo/realizes :cg/PublisherRole } ") m))))

(defn store-curation-fn [event]
  (if (has-publish-action (::event/data event))
    (event/store event :gv-tdb (::event/key event) (::event/data event))
    (event/delete event :gv-tdb (::event/key event))))

(def store-curation
  (interceptor/interceptor
   {:name ::store-curation
    :enter (fn [e] (store-curation-fn e))}))

(def jena-transaction-interceptor
  (interceptor/interceptor
   {:name ::jena-transaction-interceptor
    :enter (fn [context]
             (let [gv-tdb (get-in context [::storage/storage :gv-tdb])]
               (.begin gv-tdb ReadWrite/READ)
               (assoc-in context [:request :lacinia-app-context :db] gv-tdb)))
    :leave (fn [context]
             (.end (get-in context [:request :lacinia-app-context :db]))
             context)}))

;; stuff to make sure Lacinia recieves an executor which can bookend
;; database transactions

(def direct-executor
  (reify Executor
    (^void execute [this ^Runnable r]
     (.run r))))

(defn init-graphql-processor [p]
  (assoc-in p
            [::event/metadata ::schema]
            (gql-schema/schema
             {:executor direct-executor})))

;; Adapted from version in lacinia-pedestal
;; need to get compiled schema from context, not
;; already passed into interceptor

(def query-parser-interceptor
  (interceptor/interceptor
   {:name ::query-parser
    :enter (fn [context]
             (internal/on-enter-query-parser
              context
              (::schema context)
              (::query-cache context)
              (get-in context [:request ::timing-start])))
    :leave internal/on-leave-query-parser
    :error internal/on-error-query-parser}))

(defn publish-record-to-system-topic-fn [event]
  (event/publish event
                 (assoc (select-keys event [::event/iri ::event/key])
                     ::event/topic :system
                     :type :event-marker
                     :source (::event/topic event))))

(def publish-record-to-system-topic
  (interceptor/interceptor
   {:name ::publish-record-to-system-topic
    :leave (fn [e] (publish-record-to-system-topic-fn e))}))

;;;; Application config

;; Object store


;; Kafka

(def data-exchange
  {:type :kafka-cluster
   :kafka-user (:kafka-user env)
   :common-config {"ssl.endpoint.identification.algorithm" "https"
                   "sasl.mechanism" "PLAIN"
                   "request.timeout.ms" "20000"
                   "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                   "retry.backoff.ms" "500"
                   "security.protocol" "SASL_SSL"
                   "sasl.jaas.config" (:dev-genegraph-dev-dx-jaas env)}
   :consumer-config {"key.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"
                     "value.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"}
   :producer-config {"key.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"
                     "value.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"}})

;;;; Transform

(def gene-validity-version-store
  {:name :gene-validity-version-store
   :type :rocksdb
   :path "data/version-store"})

(def transform-processor
  {:type :processor
   :name :gene-validity-transform
   :subscribe :gene-validity-gci
   :backing-store :gene-validity-version-store
   :interceptors [publish-record-to-system-topic
                  gci-model/add-gci-model
                  sepio-model/add-model
                  add-iri
                  add-publish-actions
                  versioning/add-version]})

;;;; Base

(def fetch-base-processor
  {:name :fetch-base-file
   :type :processor
   :subscribe :fetch-base-events
   :interceptors [publish-record-to-system-topic
                  base/fetch-file
                  base/publish-base-file]
   ::event/metadata {::base/handle (:base-fs-handle env)}})

;;;; GraphQL

(def gv-tdb
  {:type :rdf
   :name :gv-tdb
   :path "data/gv-tdb"})

(def import-base-processor
  {:name :import-base-file
   :type :processor
   :subscribe :base-data
   :backing-store :gv-tdb
   :interceptors [publish-record-to-system-topic
                  base/read-base-data
                  base/store-model]})

(def import-gv-curations
  {:type :processor
   :subscribe :gene-validity-sepio
   :name :gene-validity-sepio-reader
   :backing-store :gv-tdb
   :interceptors [publish-record-to-system-topic
                  store-curation]})

(def graphql-api
  {:name :graphql-api
   :type :processor
   :interceptors [#_lacinia-pedestal/initialize-tracing-interceptor
                  jena-transaction-interceptor
                  lacinia-pedestal/json-response-interceptor
                  lacinia-pedestal/error-response-interceptor
                  lacinia-pedestal/body-data-interceptor
                  lacinia-pedestal/graphql-data-interceptor
                  lacinia-pedestal/status-conversion-interceptor
                  lacinia-pedestal/missing-query-interceptor
                  query-parser-interceptor
                  lacinia-pedestal/disallow-subscriptions-interceptor
                  lacinia-pedestal/prepare-query-interceptor
                  #_lacinia-pedestal/enable-tracing-interceptor
                  lacinia-pedestal/query-executor-handler]
   :init-fn init-graphql-processor})

(def gv-http-server
  {:gene-validity-server
   {:type :http-server
    :name :gene-validity-server
    :endpoints [{:path "/api"
                 :processor :graphql-api
                 :method :post}]
    ::http/host "0.0.0.0"
    ::http/allowed-origins {:allowed-origins (constantly true)
                            :creds true}
    ::http/routes
    (conj
     (lacinia-pedestal/graphiql-asset-routes "/assets/graphiql")
     ["/ide" :get (lacinia-pedestal/graphiql-ide-handler {})
      :route-name ::lacinia-pedestal/graphql-ide]
     ["/ready"
     :get (fn [_] {:status 200 :body "server is ready"})
     :route-name ::readiness]
     ["/live"
      :get (fn [_] {:status 200 :body "server is live"})
      :route-name ::liveness])
    ::http/type :jetty
    ::http/port 8888
    ::http/join? false
    ::http/secure-headers nil}})

(def gv-ready-server
  {:gene-validity-server
   {:type :http-server
    :name :gv-ready-server
    ::http/host "0.0.0.0"
    ::http/allowed-origins {:allowed-origins (constantly true)
                            :creds true}
    ::http/routes
    [["/ready"
      :get (fn [_] {:status 200 :body "server is ready"})
      :route-name ::readiness]
     ["/live"
      :get (fn [_] {:status 200 :body "server is live"})
      :route-name ::liveness]]
    ::http/type :jetty
    ::http/port 8888
    ::http/join? false
    ::http/secure-headers nil}})

(def gv-test-app-def
  {:type :genegraph-app
   :topics {:gene-validity-gci
            {:name :gene-validity-gci
             :type :simple-queue-topic}
            :gene-validity-sepio
            {:name :gene-validity-sepio
             :type :simple-queue-topic}
            :fetch-base-events
            {:name :fetch-base-events
             :type :simple-queue-topic}
            :base-data
            {:name :base-data
             :type :simple-queue-topic}}
   :storage {:gv-tdb gv-tdb
             :gene-validity-version-store gene-validity-version-store}
   :processors {:gene-validity-transform transform-processor
                :fetch-base-file fetch-base-processor
                :import-base-file import-base-processor
                :import-gv-curations import-gv-curations
                :graphql-api graphql-api}
   :http-servers gv-http-server})

(comment
  (def gv-test-app
    (p/init gv-test-app-def))

  (p/start gv-test-app)
  (p/stop gv-test-app)
  
  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/all_gv_events.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (run! #(p/publish (get-in gv-test-app [:topics :gene-validity-gci]) %))))

  (def b1
    {::event/data
     (->> (-> "base.edn" io/resource slurp edn/read-string)
          first)
     ::event/skip-local-effects true
     ::event/skip-publish-effects true})

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (take 1)
       (run! #(p/publish (get-in gv-test-app [:topics :fetch-base-events])
                         {::event/data %})))

  (p/process (get-in gv-test-app [:processors :fetch-base-file]) b1)
  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
        ((rdf/create-query "select ?x where { ?x a :rdfs/Datatype }") tdb)))

  
  
  )

(def gv-base-app-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange data-exchange}
   :topics {:fetch-base-events
            {:name :fetch-base-events
             :type :kafka-consumer-group-topic
             :serialization :edn
             :kafka-consumer-group (:kafka-consumer-group env)
             :kafka-cluster :data-exchange
             :kafka-topic "genegraph-fetch-base-events"}
            :base-data
            {:name :base-data
             :type :kafka-producer-topic
             :serialization :edn
             :kafka-cluster :data-exchange
             :kafka-topic "genegraph-base"}}
   :processors {:fetch-base (assoc fetch-base-processor
                                   :kafka-cluster :data-exchange)}
   :http-servers gv-ready-server})

(defn seed-base-fn [event]
  (clojure.pprint/pprint event)
  (event/publish event (assoc (select-keys event [::event/data])
                              ::event/topic :fetch-base-events)))

(def seed-base-interceptor
  (interceptor/interceptor
   {:name ::seed-base-interceptor
    :enter (fn [e] (seed-base-fn e))}))

(def gv-seed-base-event-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange data-exchange}
   :topics {:fetch-base-events
            {:name :fetch-base-events
             :type :kafka-producer-topic
             :kafka-cluster :data-exchange
             :serialization :edn
             :kafka-topic "genegraph-fetch-base-events"}
            :initiate-fetch
            {:name :initiate-fetch
             :type :simple-queue-topic}}
   :processors {:initiate-base-file-update
                {:name :initiate-base-file-update
                 :type :processor
                 :subscribe :initiate-fetch
                 :kafka-cluster :data-exchange
                 :interceptors [publish-record-to-system-topic
                                seed-base-interceptor]}}})

;; gv-base admin

(comment
  (def gv-base-app
    (p/init gv-base-app-def))

  ;; reset state
  (with-open [client (kafka-admin/create-admin-client data-exchange)]
    (kafka-admin/delete-topic client "genegraph-fetch-base-events")
    (kafka-admin/delete-topic client "genegraph-base")
    (kafka-admin/delete-acls-for-user client (:kafka-user env)))
  
  (kafka-admin/configure-kafka-for-app! gv-base-app)
  (p/start gv-base-app)
  (p/stop gv-base-app)

  (def acls
    (with-open [a (kafka-admin/create-admin-client data-exchange)]
      (kafka-admin/acls a)))

  (->> acls
       (map kafka-admin/acl-binding->map)
       (filter #(= "fetch-base-file" (:name %))))
  
  (def gv-seed-base-event
    (p/init gv-seed-base-event-def))

  (p/start gv-seed-base-event)
  (p/stop gv-seed-base-event)

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (run! #(p/publish (get-in gv-seed-base-event [:topics :initiate-fetch]) {::event/data %})))
  
  
  )

;; Obviously update to dx-ccloud for real production
(def gv-transformer-def
    {:type :genegraph-app
     :kafka-clusters {:data-exchange data-exchange}
     :topics {:gene-validity-gci
              {:name :gene-validity-gci
               :type :kafka-consumer-group-topic
               :kafka-consumer-group (:kafka-consumer-group env)
               :kafka-cluster :data-exchange
               :serialization :json
               :kafka-topic "gene_validity_complete"}
              :gene-validity-sepio
              {:name :gene-validity-sepio
               :type :kafka-producer-topic
               :kafka-cluster :data-exchange
               :serialization ::rdf/n-triples
               :kafka-topic "gene_validity_sepio"}}
     :storage {:gene-validity-version-store gene-validity-version-store}
     :processors {:gene-validity-transform
                  (assoc transform-processor
                         :kafka-cluster :data-exchange)}
     :http-servers gv-ready-server})

(def reporter-interceptor
  (interceptor/interceptor
   {:name ::reporter
    :enter (fn [e]
             (log/info :fn :reporter :key (::event/key e))
             e)}))

(def gv-transformer-test-def
  {:type :genegraph-app
   :topics {:gene-validity-gci
            {:name :gene-validity-gci
             :type :simple-queue-topic}
            :gene-validity-sepio
            {:name :gene-validity-sepio
             :type :simple-queue-topic}}
   :storage {:gene-validity-version-store gene-validity-version-store}
   :processors {:gene-validity-transform transform-processor
                :gene-validity-reporter
                {:name :gene-validity-reporter
                 :type :processor
                 :subscribe :gene-validity-sepio
                 :interceptors [reporter-interceptor]}}})



(comment
  (def gv-transformer-prod
    (p/init gv-transformer-def))
  (kafka-admin/configure-kafka-for-app! gv-transformer-prod)
  (p/start gv-transformer-prod)
  (p/stop gv-transformer-prod)

  
  (event-store/with-event-reader [r
                                  "/Users/tristan/data/genegraph-neo/gv_events_complete_2024-01-12.edn.gz"]
    (->> (event-store/event-seq r)
         count))

  (def gv-transformer-test
    (p/init gv-transformer-test-def))

  (p/start gv-transformer-test)
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv_events_complete_2024-01-12.edn.gz"]
    (->> (event-store/event-seq r)
         (map #(assoc % ::event/format :json))
         first
         event/deserialize
         (p/process (get-in gv-transformer-test
                            [:processors :gene-validity-transform]))))
  
  )

(def gv-graphql-endpoint-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange data-exchange}
   :storage {:gv-tdb gv-tdb}
   :topics {:gene-validity-sepio
            {:name :gene-validity-sepio
             :type :kafka-reader-topic
             :kafka-cluster :data-exchange
             :serialization ::rdf/n-triples
             :kafka-topic "gene_validity_sepio"}
            :base-data
            {:name :base-data
             :type :kafka-reader-topic
             :kafka-cluster :data-exchange
             :serialization :edn
             :kafka-topic "genegraph-base"}}
   :processors {:import-gv-curations import-gv-curations
                :import-base-file import-base-processor
                :graphql-api graphql-api}
   :http-servers gv-http-server})

(comment
  (def gv-graphql-endpoint
    (p/init gv-graphql-endpoint-def))

  (kafka-admin/configure-kafka-for-app! gv-graphql-endpoint)

  (p/start gv-graphql-endpoint)
  (-> gv-graphql-endpoint
      :topics
      :gene-validity-sepio)
  
  (let [gv @(-> gv-graphql-endpoint :storage :gv-tdb :instance)]
    (storage/retrieve-offset gv :gene-validity-sepio))

  )


(def genegraph-function
  {"fetch-base" gv-base-app-def
   "transform-curations" gv-transformer-def
   "graphql-endpoint" gv-graphql-endpoint-def})

(defn -main [& args]
  (log/info :fn ::-main
            :msg "starting genegraph"
            :function (:function env))
  (-> (get genegraph-function (:function env) gv-test-app-def)
      p/init
      p/start))

(comment

  (def producer-conf
    {"ssl.endpoint.identification.algorithm" "https",
     "transactional.id" "fetch-base-file",
     "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092",
     "request.timeout.ms" "20000",
     "retry.backoff.ms" "500",
     "value.serializer" "org.apache.kafka.common.serialization.StringSerializer",
     "key.serializer" "org.apache.kafka.common.serialization.StringSerializer",
     "max.request.size" (int 10485760),
     "sasl.jaas.config"   #_(System/getenv "DX_JAAS_CONFIG_DEV")
     "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"W3STYBUN3GXNKBHM\" password=\"bEgjbHg5iNTi+bACjK084oUMGcGfF/6hKbJGzMQoBG3TRM1tM2nwDNC9AHuMxTM6\";",
     "sasl.mechanism" "PLAIN",
     "security.protocol" "SASL_SSL"})

  (def p (KafkaProducer. producer-conf))
  (.initTransactions p)
  (.close p)
  
  )

(comment

  (def gv-event-path "/users/tristan/data/genegraph-neo/all_gv_events.edn.gz")

  (def gv-app
    (p/init
     {:type :genegraph-app
      :kafka-clusters {:local
                       {:common-config {"bootstrap.servers" "localhost:9092"}
                        :producer-config {"key.serializer"
                                          "org.apache.kafka.common.serialization.StringSerializer",
                                          "value.serializer"
                                          "org.apache.kafka.common.serialization.StringSerializer"}
                        :consumer-config {"key.deserializer"
                                          "org.apache.kafka.common.serialization.StringDeserializer"
                                          "value.deserializer"
                                          "org.apache.kafka.common.serialization.StringDeserializer"}}}
      :topics {:gene-validity-gci
               {:name :gene-validity-gci
                :type :kafka-consumer-group-topic
                :serialization :json
                :kafka-consumer-group "gvt0"
                :kafka-cluster :local
                :kafka-topic "gene_validity_complete"}
               :gene-validity-sepio
               {:name :gene-validity-sepio
                :type :kafka-producer-topic
                :serialization ::rdf/n-triples
                :kafka-cluster :local
                :kafka-topic "gene_validity_sepio"}}
      :processors {:gene-validity-transform
                   {:type :processor
                    :name :gene-validity-processor
                    :interceptors `[gci-model/add-gci-model
                                    sepio-model/add-model
                                    add-iri
                                    add-publish-actions]}}}))

  (p/start gv-test-app)
  (p/stop gv-test-app)


  (time
   (storage/store-snapshot (get-in gv-test-app [:storage :gv-tdb]) gcs-handle))

  (time
   (storage/restore-snapshot (get-in gv-test-app [:storage :gv-tdb]) gcs-handle))



  
  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (take 1)
       #_(filter #(isa? (:format %) ::rdf/rdf-serialization))
       #_(filter #(= "http://purl.obolibrary.org/obo/sepio.owl" (:name %)))
       (map (fn [x] {::event/data x}))
       (run! #(p/publish (get-in gv-test-app [:topics :fetch-base-events]) %)))

  (let [db @(get-in gv-test-app [:storage :gv-tdb :instance])]
    db
    (rdf/tx db
      (-> ((rdf/create-query "select ?x where { ?x a :so/Gene } limit 5")
           (rdf/resource "https://www.ncbi.nlm.nih.gov/gene/55847" db))
          first)))

  ;; Load all gene validity stuff into Genegraph (takes ~20m with parallel execution
  (time
   (event-store/with-event-reader [r gv-event-path]
     (->> (event-store/event-seq r)
          (run! #(p/publish (get-in gv-test-app [:topics :gene-validity-gci]) %)))))

  (def gv-xform #(processor/process-event
                  (get-in gv-test-app [:processors :gene-validity-transform])
                  %))

  (event-store/with-event-reader [r gv-event-path]
    (frequencies
     (map
      #(-> (rdf/ld1-> % [:rdf/type]) rdf/->kw)
      ((rdf/create-query "select ?x where { ?x :sepio/has-evidence ?y }")
       (-> (event-store/event-seq r)
           first
           (assoc ::event/skip-local-effects true
                  ::event/skip-publish-effects true)
           gv-xform
           :gene-validity/model)))))
  

  ;; gcloud builds submit --region=us-east1 --tag us-east1-docker.pkg.dev/clingen-dev/genegraph-docker-repo/genegraph-gene-validity:v2

  
  )


