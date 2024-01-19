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


(def prop-query
  (rdf/create-query "select ?x where {?x a ?type}"))

;; Deprecated (but maybe test first)
(defn add-action [event]
  (assoc event
         ::event/action
         (if (re-find #"\"publishClassification\": ?true"
                      (::event/value event))
           :publish
           :unpublish)))

(defn add-iri [event]
  (assoc event
         ::event/iri
         (-> (prop-query
              (:gene-validity/model event)
              {:type :sepio/GeneValidityProposition})
             first
             str)))

(defn add-publish-actions [event]
  (event/publish event
                 (-> event
                     (set/rename-keys {::event/iri ::event/key
                                       :gene-validity/model ::event/data})
                     (select-keys [::event/key ::event/data])
                     (assoc ::event/topic :gene-validity-sepio))))

(defn has-publish-action [m]
  (< 0 (count ((rdf/create-query "select ?x where { ?x :bfo/realizes :cg/PublisherRole } ") m))))

(defn store-curation [event]
  (println
   (str
    (subs (::event/key event) 67)
    " "
    (has-publish-action (::event/data event))))
  (if (has-publish-action (::event/data event))
    (event/store event :gv-tdb (::event/key event) (::event/data event))
    (event/delete event :gv-tdb (::event/key event))))

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

(def gcs-handle
  {:type :gcs
   :bucket "genegraph-framework-dev"})

(def fs-handle
  {:type :file
   :base "/users/tristan/data/genegraph-neo/"})

(def base-fs-handle
  {:type :file
   :base "/users/tristan/data/genegraph-neo/new-base/"})

(def gv-processors
  {:gene-validity-transform
   {:type :parallel-processor
    :name :gene-validity-transform
    :subscribe :gene-validity-gci
    :interceptors `[gci-model/add-gci-model
                    sepio-model/add-model
                    add-iri
                    add-publish-actions]}
   :gene-validity-sepio-reader
   {:type :processor
    :subscribe :gene-validity-sepio
    :name :gene-validity-sepio-reader
    :interceptors `[store-curation]}
   :fetch-base-file
   {:name :fetch-base-file
    :type :processor
    :subscribe :fetch-base-events
    :interceptors `[base/fetch-file
                    base/publish-base-file]
    ::event/metadata {::base/handle base-fs-handle}}
   :import-base-file
   {:name :import-base-file
    :type :processor
    :subscribe :base-data
    :interceptors `[base/read-base-data
                    base/store-model]}
   :graphql-api
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
    :init-fn init-graphql-processor}})

(def gv-http-server
  {:gene-validity-server
   {:type :http-server
    :name :gene-validity-server
    :endpoints [{:path "/api"
                 :processor :graphql-api
                 :method :post}]
    ::http/routes
    (conj
     (lacinia-pedestal/graphiql-asset-routes "/assets/graphiql")
     ["/ide" :get (lacinia-pedestal/graphiql-ide-handler {})
      :route-name ::lacinia-pedestal/graphql-ide])
    ::http/type :jetty
    ::http/port 8888
    ::http/join? false
    ::http/secure-headers nil}})

(def local-cluster
  {:local
   {:common-config {"bootstrap.servers" "localhost:9092"}
    :producer-config {"key.serializer"
                      "org.apache.kafka.common.serialization.StringSerializer",
                      "value.serializer"
                      "org.apache.kafka.common.serialization.StringSerializer"}
    :consumer-config {"key.deserializer"
                      "org.apache.kafka.common.serialization.StringDeserializer"
                      "value.deserializer"
                      "org.apache.kafka.common.serialization.StringDeserializer"}}})

(def dx-ccloud
  {:type :kafka-cluster
   :common-config {"ssl.endpoint.identification.algorithm" "https"
                   "sasl.mechanism" "PLAIN"
                   "request.timeout.ms" "20000"
                   "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                   "retry.backoff.ms" "500"
                   "security.protocol" "SASL_SSL"
                   "sasl.jaas.config" (System/getenv "DX_JAAS_CONFIG")}
   :consumer-config {"key.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"
                     "value.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"}
   :producer-config {"key.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"
                     "value.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"}})

(def dx-ccloud-dev
  {:type :kafka-cluster
   :common-config {"ssl.endpoint.identification.algorithm" "https"
                   "sasl.mechanism" "PLAIN"
                   "request.timeout.ms" "20000"
                   "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                   "retry.backoff.ms" "500"
                   "security.protocol" "SASL_SSL"
                   "sasl.jaas.config" (System/getenv "DX_JAAS_CONFIG_DEV")}
   :consumer-config {"key.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"
                     "value.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"}
   :producer-config {"key.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"
                     "value.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"}})

(def gv-test-app
  (p/init
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
    :storage {:gv-tdb
              {:type :rdf
               :name :gv-tdb
               :path "/users/tristan/data/genegraph-neo/gv_tdb"}}
    :processors gv-processors
    :http-servers gv-http-server}))

(def kafka-consumer-group "genegraph-gene-validity-dev-0")

;; Obviously update to dx-ccloud for real production
(def gv-transformer-prod
  (p/init
   {:type :genegraph-app
    :kafka-clusters {:data-exchange dx-ccloud-dev}
    :topics {:gene-validity-gci
             {:name :gene-validity-gci
              :type :kafka-consumer-group-topic
              :kafka-consumer-group kafka-consumer-group
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

(defn -main [& args]
  (println "Starting genegraph-gene-validity")
  (p/start gv-test-app))

(comment
  (with-open [secrets-client (SecretManagerServiceClient/create)]
    (-> (.accessSecretVersion secrets-client "projects/522856288592/secrets/dev-genegraph-dev-dx-jaas/versions/latest")
        )
    )
  

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

  (let [db @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx db
            (-> (rdf/resource "https://www.ncbi.nlm.nih.gov/gene/55847" db)
                (rdf/ld-> [:skos/prefLabel]))))

  (let [db @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx db
            (-> (rdf/resource "mondo:0100038" db)
                #_(rdf/ld-> [:skos/prefLabel]))))

  (type @(get-in gv-test-app [:storage :gv-tdb :instance]))

  (def moi-and-classification
    (rdf/create-query
     "select distinct ?a where { ?x a ?t ;
:sepio/has-qualifier ?moi .
?a :sepio/has-subject ?x ;
a ?t2 ;
:sepio/has-object ?classification ;
:sepio/has-evidence + ?p .
?p a ?t3}"))

  (let [db @(get-in gv-test-app [:storage :gv-tdb :instance])]
    db
    (rdf/tx db
            (->
             (moi-and-classification
              db
              {:t :sepio/GeneValidityProposition
               :t2 :sepio/GeneValidityEvidenceLevelAssertion
               :moi :hp/AutosomalRecessiveInheritance
               :classification :sepio/ModerateEvidence
               :t3 :sepio/NullVariantEvidenceLine})
             count)))

  (let [db @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx db
            (->
             ((rdf/create-query "select ?p where { ?p a ?t }")
              db
              {:t :sepio/NullVariantEvidenceLine})
             first
             (rdf/ld-> [[:sepio/has-evidence :<]
                        [:sepio/has-evidence :<]]))))
  


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
  
  (kafka/topic->event-file
   (get-in gv-app [:topics :gene-validity-gci])
   "/users/tristan/data/genegraph-neo/all_gv_events.edn.gz")



  (event-store/with-event-reader [r gv-event-path]
    (->> (event-store/event-seq r)
         #_(filter #(re-find #"7d595dc4" (::event/value %)))
         #_(take 1)
         #_(map #(-> %
                     (assoc ::event/skip-local-effects true
                            ::event/skip-publish-effects true)
                     gv-xform
                     ::event/publish))))

  (event-store/with-event-reader [r gv-event-path]
    (->> (event-store/event-seq r)
         (take 1)
         
         #_(filter #(re-find #"\"10\"" (::event/value %)))
         #_count
         
         (map #(-> %
                   (assoc ::event/skip-local-effects true
                          ::event/skip-publish-effects true)
                   gv-xform
                   ::event/publish))))

  ;; zeb 2 example
  (def zeb2
    (event-store/with-event-reader [r gv-event-path]
      (->> (event-store/event-seq r)
           (filter #(re-find #"c16423b1" (::event/value %)))
           last)))
  
  (-> ((rdf/create-query "select ?el where { ?el a ?type }")
       (-> zeb2 gv-xform :gene-validity/model)
       {:type :sepio/OverallAutosomalDominantDeNovoVariantEvidenceLine})
      first
      (rdf/ld-> [:sepio/has-evidence])))


