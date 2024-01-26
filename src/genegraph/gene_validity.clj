(ns genegraph.gene-validity
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.processor :as processor]
            [genegraph.framework.event :as event]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
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
           [java.util.concurrent LinkedBlockingQueue ThreadPoolExecutor TimeUnit Executor Executors]
           [com.google.cloud.secretmanager.v1 AccessSecretVersionResponse ProjectName Replication Secret SecretManagerServiceClient SecretPayload SecretVersion SecretName]
           [com.google.protobuf ByteString])
  (:gen-class))

(def env
  (atom
   {:kafka-user "User:2189780"
    :kafka-consumer-group "genegraph-gene-validity-dev-0"
    :kafka-jaas-config (System/getenv "DX_JAAS_CONFIG_DEV")}))

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

;;;; Application config

;; Object store

(def gcs-handle
  {:type :gcs
   :bucket "genegraph-framework-dev"})

(def fs-handle
  {:type :file
   :base "data/"})

;; Kafka

(def data-exchange
  {:type :kafka-cluster
   :kafka-user (:kafka-user @env)
   :common-config {"ssl.endpoint.identification.algorithm" "https"
                   "sasl.mechanism" "PLAIN"
                   "request.timeout.ms" "20000"
                   "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                   "retry.backoff.ms" "500"
                   "security.protocol" "SASL_SSL"
                   "sasl.jaas.config" (:kafka-jaas-config @env)}
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
  {:type :parallel-processor
   :name :gene-validity-transform
   :subscribe :gene-validity-gci
   :backing-store :gene-validity-version-store
   :interceptors [gci-model/add-gci-model
                  sepio-model/add-model
                  add-iri
                  add-publish-actions
                  versioning/add-version]})

;;;; Base

(def fetch-base-processor
  {:name :fetch-base-file
   :type :processor
   :subscribe :fetch-base-events
   :interceptors [base/fetch-file
                  base/publish-base-file]
   ::event/metadata {::base/handle fs-handle}})

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
   :interceptors [base/read-base-data
                  base/store-model]})

(def import-gv-curations
  {:type :processor
   :subscribe :gene-validity-sepio
   :name :gene-validity-sepio-reader
   :backing-store :gv-tdb
   :interceptors [store-curation]})

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

  (def e1
    (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/all_gv_events.edn.gz"]
      (->> (event-store/event-seq r)
           first)))
  
  (p/process (get-in gv-test-app [:processors :gene-validity-transform])
             e1)
  (keys
   (storage/read @(get-in gv-test-app [:storage :gene-validity-version-store :instance])
                 "http://dataexchange.clinicalgenome.org/gci/55ca8d81-f718-428e-ab59-75f7a9182d08"))

  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/all_gv_events.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (run! #(p/publish (get-in gv-test-app [:topics :gene-validity-gci]) %))))
  
  )

;; Obviously update to dx-ccloud for real production
#_(def gv-transformer-prod
  (p/init
   {:type :genegraph-app
    :kafka-clusters {:data-exchange data-exchange}
    :topics {:gene-validity-gci
             {:name :gene-validity-gci
              :type :kafka-consumer-group-topic
              :kafka-consumer-group (:kafka-consumer-group @env)
              :kafka-cluster :data-exchange
              :kafka-topic "gene_validity_complete"}
             :gene-validity-sepio
             {:name :gene-validity-sepio
              :type :kafka-producer-topic
              :kafka-cluster :data-exchange
              :kafka-topic "gene_validity_sepio"}}
    :processors {:gene-validity-transform
                 {:type :parallel-processor
                  :name :gene-validity-transform
                  :subscribe :gene-validity-gci
                  :interceptors `[gci-model/add-gci-model
                                  sepio-model/add-model
                                  add-iri
                                  add-publish-actions]}}}))

(def terminate (promise))

(defn -main [& args]
  (println "Starting genegraph-gene-validity")
  (p/start (p/init gv-test-app-def))
  @terminate)

(comment
  (with-open [secrets-client (SecretManagerServiceClient/create)]
    (-> (.accessSecretVersion secrets-client "projects/522856288592/secrets/dev-genegraph-dev-dx-jaas/versions/latest")
        )
    )

  (p/start gv-deployment-test)
  (p/stop gv-deployment-test)

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

  (defn print-key [event]
    (println (subs (::event/key event) 67))
    (spit (storage/as-handle (assoc (::handle event)
                                    :path (str (subs (::event/key event) 67) ".nt")))
          (::event/value
           (event/serialize
            (assoc event ::event/format ::rdf/n-triples))))
    event)



  (def print-response-interceptor
    {:name ::print-response
     :leave (fn [e]
              (clojure.pprint/pprint e)
              e)})

  (defn print-marker-interceptor [marker]
    {:name ::print-marker
     :enter (fn [e]
              (println "enter " marker)
              e)
     :leave (fn [e]
              (println "leave " marker)
              e)})

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


