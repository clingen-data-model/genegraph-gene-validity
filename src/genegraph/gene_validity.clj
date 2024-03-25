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
            [genegraph.gene-validity.actionability :as actionability]
            [genegraph.gene-validity.gene-validity-legacy-report :as legacy-report]
            [genegraph.gene-validity.dosage :as dosage] 
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
            [clojure.edn :as edn]
            ;; testing
            [genegraph.gene-validity.graphql.common.curation :as curation]
            )
  (:import [org.apache.jena.sparql.core Transactional]
           [org.apache.jena.query ReadWrite]
           [org.apache.jena.rdf.model Model]
           [org.apache.kafka.clients.producer KafkaProducer]
           [java.time Instant]
           [java.util.concurrent LinkedBlockingQueue ThreadPoolExecutor TimeUnit Executor Executors])
  (:gen-class))


;; Environments

(def admin-env
  (if (or (System/getenv "DX_JAAS_CONFIG_DEV")
          (System/getenv "DX_JAAS_CONFIG")) ; prevent this in cloud deployments
    {:platform "local"
     :dataexchange-genegraph (System/getenv "DX_JAAS_CONFIG")
     :local-data-path "data/"}
    {}))

(def local-env
  (case (or (:platform admin-env) (System/getenv "GENEGRAPH_PLATFORM"))
    "local" {:fs-handle {:type :file :base "data/base/"}
             :local-data-path "data/"}
    "dev" (assoc (env/build-environment "522856288592" ["dataexchange-genegraph"])
                 :function (System/getenv "GENEGRAPH_FUNCTION")
                 :kafka-user "User:2189780"
                 :kafka-consumer-group "genegraph-gene-validity-dev-13"
                 :fs-handle {:type :gcs
                             :bucket "genegraph-framework-dev"}
                 :local-data-path "/data")
    "stage" (assoc (env/build-environment "583560269534" ["dataexchange-genegraph"])
                   :function (System/getenv "GENEGRAPH_FUNCTION")
                   :kafka-user "User:2592237"
                   :kafka-consumer-group "genegraph-gene-validity-stage-1"
                   :fs-handle {:type :gcs
                               :bucket "genegraph-gene-validity-stage-1"}
                   :local-data-path "/data")
    {}))

(def env
  (merge local-env admin-env))

;; Interceptors for reader

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

(def prop-query
  (rdf/create-query "select ?prop where { ?prop a :sepio/GeneValidityProposition } "))

(def same-as-query
  (rdf/create-query "select ?x where { ?x :owl/sameAs ?y }"))

;; Jena methods mutate the model, will use this behavior 😱
(defn replace-hgnc-with-ncbi-gene-fn [event]
  (rdf/tx (get-in event [::storage/storage :gv-tdb])
      (let [m (::event/data event)
            prop (first (prop-query m))
            hgnc-gene (rdf/ld1-> prop [:sepio/has-subject])
            ncbi-gene (first (same-as-query (get-in event [::storage/storage :gv-tdb])
                                            {:y hgnc-gene}))]
        (.remove m (rdf/construct-statement [prop :sepio/has-subject hgnc-gene]))
        (.add m (rdf/construct-statement [prop :sepio/has-subject ncbi-gene]))))
  event)

(def replace-hgnc-with-ncbi-gene
  (interceptor/interceptor
   {:name ::replace-hgnc-with-ncbi-gene
    :enter (fn [e] (replace-hgnc-with-ncbi-gene-fn e))}))

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
             (.end (get-in context [::storage/storage :gv-tdb]))
             context)
    :error (fn [context ex]
             (.end (get-in context [::storage/storage :gv-tdb]))
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
            (fn []
              (gql-schema/merged-schema
               {:executor direct-executor}))))

(defn fn->schema [fn-or-schema]
  (if (fn? fn-or-schema)
    (fn-or-schema)
    fn-or-schema))

;; Adapted from version in lacinia-pedestal
;; need to get compiled schema from context, not
;; already passed into interceptor

(def query-parser-interceptor
  (interceptor/interceptor
   {:name ::query-parser
    :enter (fn [context]
             (internal/on-enter-query-parser
              context
              (fn->schema (::schema context))
              (::query-cache context)
              (get-in context [:request ::timing-start])))
    :leave internal/on-leave-query-parser
    :error internal/on-error-query-parser}))

(defn publish-record-to-system-topic-fn [event]
  (event/publish event
                 {::event/topic :system
                  :type :event-marker
                  ::event/data (assoc (select-keys event [::event/key])
                                      :source (::event/topic event))}))

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
                   "sasl.jaas.config" (:dataexchange-genegraph env)}
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
   :path (str (:local-data-path env) "version-store")})

(defn report-transform-errors-fn [event]
  (Thread/startVirtualThread
   (fn []
     (case (deref (::event/completion-promise event) (* 1000 5) :timeout)
       :timeout (log/warn :fn ::report-transform-errors
                          :msg "timeout"
                          :offset (::event/offset event)
                          :key (::event/key event))
       false (log/warn :fn ::report-transform-errors
                          :msg "processing error"
                          :offset (::event/offset event)
                          :key (::event/key event))
       true)))
  event)

(def report-transform-errors
  {:name ::report-transform-errors
   :enter (fn [e] (report-transform-errors-fn e))
   :error (fn [e ex] (log/warn :fn ::report-transform-errors
                               :msg "error in interceptors"
                               :offset (::event/offset e)
                               :key (::event/key e)
                               :exception ex)
            e)})




(def transform-processor
  {:type :processor
   :name :gene-validity-transform
   :subscribe :gene-validity-gci
   :backing-store :gene-validity-version-store
   :interceptors [report-transform-errors
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
   :interceptors [base/fetch-file
                  base/publish-base-file]
   ::event/metadata {::base/handle
                     (assoc (:fs-handle env) :path "base/")}})

;;;; GraphQL

(def gv-tdb
  {:type :rdf
   :name :gv-tdb
   :path (str (:local-data-path env) "/gv-tdb")})

(def import-base-processor
  {:name :import-base-file
   :type :processor
   :subscribe :base-data
   :backing-store :gv-tdb
   :interceptors [publish-record-to-system-topic
                  base/read-base-data
                  base/store-model]})

(def genes-graph-name
  "https://www.genenames.org/")

(defn init-await-genes [listener-name]
  (fn [p]
    (let [genes-promise (promise)]
      (p/publish (get-in p [:topics :system])
                 {:type :register-listener
                  :name listener-name
                  :promise genes-promise
                  :predicate #(and (= :base-data (get-in % [::event/data :source]))
                                   (= genes-graph-name
                                      (get-in % [::event/data ::event/key])))})

      (assoc p
             ::event/metadata
             {::genes-promise genes-promise
              ::genes-atom (atom false)}))))

(defn graph-initialized? [e graph-name]
  (let [db (get-in e [::storage/storage :gv-tdb])]
    (rdf/tx db
      (-> (storage/read db graph-name)
          .size
          (> 0)))))

(defn await-genes-fn [{:keys [::genes-promise ::genes-atom ::event/kafka-topic] :as e}]
  (when-not @genes-atom
    (while (not
            (or (graph-initialized? e genes-graph-name)
                (not= :timeout (deref genes-promise (* 1000 30) :timeout))))
      (log/info :fn ::await-genes-fn
                :msg "Awaiting genes load"
                :topic kafka-topic))
    (log/info :fn ::await-genes-fn :msg "Genes loaded")
    (reset! genes-atom true))
  e)

(def await-genes
  (interceptor/interceptor
   {:name ::await-genes
    :enter (fn [e] (await-genes-fn e))}))


(def import-gv-curations
  {:type :processor
   :subscribe :gene-validity-sepio
   :name :gene-validity-sepio-reader
   :backing-store :gv-tdb
   :init-fn (init-await-genes ::import-gv-curations-await-genes)
   :interceptors [await-genes
                  replace-hgnc-with-ncbi-gene
                  store-curation]})

(def import-actionability-curations
  {:type :processor
   :subscribe :actionability
   :name :import-actionability-curations
   :backing-store :gv-tdb
   :init-fn (init-await-genes ::import-actionability-curations-await-genes)
   :interceptors [await-genes
                  actionability/add-actionability-model
                  actionability/write-actionability-model-to-db]})

(def import-dosage-curations
  {:type :processor
   :subscribe :dosage
   :name :import-dosage-curations
   :backing-store :gv-tdb
   :interceptors [dosage/add-dosage-model
                  dosage/write-dosage-model-to-db]})

(def gene-validity-legacy-report-processor
  {:type :processor
   :subscribe :gene-validity-legacy
   :name :gene-validity-legacy-report-processor
   :backing-store :gv-tdb
   :interceptors [legacy-report/add-gci-legacy-model
                  legacy-report/write-gci-legacy-model-to-db]})

(def query-timer-interceptor
  (interceptor/interceptor
   {:name ::query-timer-interceptor
    :enter (fn [e] (assoc e ::start-time (.toEpochMilli (Instant/now))))
    :leave (fn [e] (assoc e ::end-time (.toEpochMilli (Instant/now))))}))

(defn publish-result-fn [e]
  (log/info :fn ::publish-result-fn
            :duration (- (::end-time e) (::start-time e)))
  (event/publish
   e
   {::event/data {:start-time (::start-time e)
                  :end-time (::end-time e)
                  :query (get-in e [:request :body])
                  :remote-addr (get-in e [:request :remote-addr])
                  :response-size (count (get-in e [:response :body]))
                  :status (get-in e [:response :status])}
    ::event/key (str (::start-time e))
    ::event/topic :api-log}))

(def publish-result-interceptor
  (interceptor/interceptor
   {:name ::publish-result
    :leave (fn [e] (publish-result-fn e))}))

(def graphql-api
  {:name :graphql-api
   :type :processor
   :interceptors [#_lacinia-pedestal/initialize-tracing-interceptor
                  publish-result-interceptor
                  query-timer-interceptor
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

(defn log-api-event-fn [e]
  (let [data (::event/data e)]
    (log/info :fn ::log-api-event
              :duration (- (:end-time data) (:start-time data))
              :response-size (:response-size data)
              :status (:status data))
    e))

(def log-api-event
  (interceptor/interceptor
   {:name ::log-api-event
    :enter (fn [e] (log-api-event-fn e))}))

(def read-api-log
  {:name :read-api-log
   :type :processor
   :subscribe :api-log
   :interceptors [log-api-event]})

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
             :type :simple-queue-topic}
            :actionability
            {:name :actionability
             :type :simple-queue-topic}
            :dosage
            {:name :dosage
             :type :simple-queue-topic}
            :gene-validity-legacy
            {:name :gene-validity-legacy
             :type :simple-queue-topic}
            :api-log
            {:name :api-log
             :type :simple-queue-topic}}
   :storage {:gv-tdb gv-tdb
             :gene-validity-version-store gene-validity-version-store}
   :processors {:gene-validity-transform transform-processor
                :fetch-base-file fetch-base-processor
                :import-base-file import-base-processor
                :import-gv-curations import-gv-curations
                :graphql-api graphql-api
                :import-actionability-curations import-actionability-curations
                :import-dosage-curations import-dosage-curations
                :read-api-log read-api-log
                :import-gene-validity-legacy-report gene-validity-legacy-report-processor}
   :http-servers gv-http-server})

(comment
  (def gv-test-app
    (p/init gv-test-app-def))
  (kafka-admin/configure-kafka-for-app! gv-test-app)

  (-> (get-in gv-test-app [:processors :import-gv-curations])
      ::event/metadata)

  (p/start gv-test-app)
  (p/stop gv-test-app)

  (event-store/with-event-reader [r    "/users/tristan/data/genegraph-neo/gene-validity-legacy-complete-2024-03-19"]
    (->> (event-store/event-seq r)
         (run! #(p/publish (get-in gv-test-app [:topics :gene-validity-legacy])
                           %))))
  

  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/actionability_2024-02-12.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (run! #(p/publish (get-in gv-test-app [:topics :actionability]) %))))

  (def wilms-ac
    "https://actionability.clinicalgenome.org/ac/Pediatric/api/sepio/doc/AC003")

  
  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/actionability_2024-02-12.edn.gz"]
    (->> (event-store/event-seq r)
         ;;(map event/deserialize)
         #_(filter (fn [e] (some #(= "HGNC:12796" (:curie %))
                               (get-in e [::event/data :genes]))))
         ;; (map #(get-in % [::event/data :iri]))
         ;; frequencies
         ;; count
         ;; last
         ;; tap>
         (run! #(p/publish (get-in gv-test-app [:topics :actionability]) %))
         ))

  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/gene-dosage_2024-02-13.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (run! #(p/publish (get-in gv-test-app [:topics :dosage]) %))))
  
  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/all_gv_events.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (run! #(p/publish (get-in gv-test-app [:topics :gene-validity-gci]) %))))
  
  ;; Gene names testing

  (let [rdf-publish-promise (promise)]

    (Thread/startVirtualThread #(let [x (deref rdf-publish-promise 5000 :timeout)]
                                  (if (= :timeout x)
                                    (println "timeout")
                                    (println "delivered"))))

    (p/publish (get-in gv-test-app [:topics :system])
               {:type :register-listener
                :name ::rdf-publish-listener
                :promise rdf-publish-promise
                :predicate #(and (= :base-data (get-in % [::event/data :source]))
                                 (= "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                                    (get-in % [::event/data ::event/key])))})

    ;; testing with something smaller and faster first
    (->> (-> "base.edn" io/resource slurp edn/read-string)
         (filter #(= "http://dataexchange.clinicalgenome.org/gci-express" (:name %)))
         (run! #(p/publish (get-in gv-test-app [:topics :fetch-base-events])
                           {::event/data %}))))

  ;; testing curation activities
  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (let [curation
            (storage/read tdb "http://dataexchange.clinicalgenome.org/gci/51e15eba-7b16-4244-912e-2265259e0459")]
        (rdf/pp-model curation))))

  (def gci-express-to-remove
    (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
      (rdf/tx tdb
        ((rdf/create-query "
select ?report where
 { ?report a ?type ;
           :dc/source ?source ;
           :bfo/has-part / :sepio/has-subject ?proposition .
   ?proposition :sepio/has-subject ?gene ;
                :sepio/has-predicate ?moi ;
                :sepio/has-object ?disease .
   ?other_proposition :sepio/has-subject ?gene ;
                      :sepio/has-predicate ?moi ;
                      :sepio/has-object ?disease .
   ?other_report :bfo/has-part / :sepio/has-subject ?other_proposition . 
   FILTER NOT EXISTS { ?other_report :dc/source ?source } .
}

")
         tdb
         {:type :sepio/GeneValidityReport
          :source :cg/GeneCurationExpress}))))

  (second gci-express-to-remove)

  (map
   #(str (first %))
   curation/test-disease-for-activity)

  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (let [t1 (Instant/now)
            result (curation/disease-activities
                    tdb
                    {:disease (rdf/resource "MONDO:0011783" tdb)})]
        {:time (- (.toEpochMilli (Instant/now)) (.toEpochMilli t1))
         :result result})))
  
  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (time
       (curation/activities
        tdb
        {:gene (rdf/resource "NCBIGENE:144568" tdb)}))))
  (int (/ (* 62 2200) 1000))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "https://www.genenames.org/" (:name %)))
       (run! #(p/publish (get-in gv-test-app [:topics :fetch-base-events])
                         {::event/data %})))

  (def gene-publish-event
    (->> (-> "base.edn" io/resource slurp edn/read-string)
         (filter #(= "https://www.genenames.org/" (:name %)))
         (map (fn [e]
                (-> {::event/data e
                     ::base/handle (:fs-handle env)}
                    base/publish-base-file-fn
                    ::event/publish
                    first)))))

  (p/publish (get-in gv-test-app [:topics :base-data])
             gene-publish-event)

  (-> (get-in gv-test-app [:topics :fetch-base-events]) )

  ;; / gene names testing


  ;; legacy id testing
  (def abcb4
    (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/gv_events_complete_2024-03-12.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"51e15eba-7b16-4244-912e-2265259e0459" (::event/value %)))
           (into [])
           #_(take 1)
           #_(mapv (fn [e] (-> (p/process
                              (get-in gv-test-app [:processors :gene-validity-transform])
                              (assoc e
                                     ::event/completion-promise (promise)
                                     ::event/format :json
                                     ::event/skip-publish-effects true
                                     ::event/skip-local-effects true))
                             :gene-validity/model
                             rdf/pp-model))))))

  (->> abcb4
       (mapv (fn [e] (-> (p/process
                          (get-in gv-test-app [:processors :gene-validity-transform])
                          (assoc e
                                 ::event/completion-promise (promise)
                                 ::event/format :json
                                 ::event/skip-publish-effects true
                                 ::event/skip-local-effects true))
                         :gene-validity/model
                         rdf/pp-model))))





  (-> (p/process (get-in gv-test-app [:processors :gene-validity-transform])
                 (assoc (first abcd4)
                        ::event/completion-promise (promise)
                        ::event/format :json
                        ::event/skip-publish-effects true
                        ::event/skip-local-effects true))
      :gene-validity/model
      rdf/pp-model)

  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/gv_events_complete_2024-03-12.edn.gz"]
    (->> (event-store/event-seq r)
         (run! (fn [e] (p/publish (get-in gv-test-app [:topics :gene-validity-gci])
                                  (assoc e ::event/format :json))))))

  (let [gv @(-> gv-test-app :storage :gv-tdb :instance)
        iri "CGGV:7765e2a4-19e4-4b15-9233-4847606fc501"]
    (rdf/tx gv
      (rdf/ld1-> (rdf/resource iri gv) [:cg/website-legacy-id])))

  ;; /legacy id testing
  
  (def eset1
    (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/all_gv_events.edn.gz"]
      (->> (event-store/event-seq r)
           (take 1000)
           (mapv (fn [e] (p/process (get-in gv-test-app [:processors :gene-validity-transform])
                                    (assoc e
                                           ::event/skip-publish-effects true
                                           ::event/completion-promise (promise))))))))

  


  (->> eset1 (remove #(realized? (::event/completion-promise %))) count)

  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/gene-validity-legacy_2024-02-20.edn.gz"]
    (->> (event-store/event-seq r)
         (run! #(p/publish (get-in gv-test-app [:topics :gene-validity-legacy]) %))))

    (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/gene-validity-legacy_2024-02-20.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (map #(p/process (get-in gv-test-app [:processors :import-gene-validity-legacy-report])
                   (assoc %
                          ::event/skip-local-effects true
                          ::event/skip-publish-effects true)))))

  (def b1
    {::event/data
     (->> (-> "base.edn" io/resource slurp edn/read-string)
          first)
     ::event/skip-local-effects true
     ::event/skip-publish-effects true})

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(re-find #"gci-express-with-entrez-ids" (:source %)))
       (run! #(p/publish (get-in gv-test-app [:topics :fetch-base-events])
                         {::event/data %})))

  (p/process (get-in gv-test-app [:processors :fetch-base-file]) b1)


  
  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (->> ((rdf/create-query "select ?x where { ?x a :sepio/GeneDosageReport }") tdb)
           count
           #_(into []))))
  ;; TODO pick up here, dosage score ordinals not working
  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (into []
            ((rdf/create-query "
select ?s where
{ ?s a :sepio/GeneValidityEvidenceLevelAssertion ;
     ^ :bfo/has-part / :bfo/has-part / a :cnt/ContentAsText }")
             tdb
             {::rdf/params {:limit 10}}))
      ))
    ;; "https://identifiers.org/hgnc:46902"

  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (rdf/ld1-> 
       (rdf/resource "http://dataexchange.clinicalgenome.org/gci/cb06ff0d-1cc6-494c-9ce5-f7cb26f34620" tdb)
       [[:bfo/has-part :<]])
      ))

  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (->> ((rdf/create-query
             '[:project [gene]
               [:bgp
                [gene :rdf/type :so/Gene]
                [gene :skos/prefLabel gene_label]]])
            tdb
            {::rdf/params {:limit 10}})
           count
           #_(into []))))

  (def sepio-events-path "/users/tristan/data/genegraph-neo/gv_sepio_2024-01-12.edn.gz")

  (event-store/with-event-reader [r sepio-events-path]
    (->>(event-store/event-seq r)
       (map #(assoc %
                    ::event/format ::rdf/n-triples
                    ::event/skip-local-effects true
                    ::event/skip-publish-effects true))
       (map #(p/process (get-in gv-test-app [:processors :import-gv-curations]) %))
       first
       ::event/data
       rdf/pp-model))

  (event-store/with-event-reader [r sepio-events-path]
    (run! #(p/publish (get-in gv-test-app [:topics :gene-validity-sepio]) %)
          (map #(assoc % ::event/format ::rdf/n-triples) (event-store/event-seq r))))
  
  (event-store/with-event-reader [r sepio-events-path]
    (->> (event-store/event-seq r)
         first
         ::event/key))
  
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
             :kafka-topic "genegraph-fetch-base-events-v1"}
            :base-data
            {:name :base-data
             :type :kafka-producer-topic
             :serialization :edn
             :kafka-cluster :data-exchange
             :kafka-topic "genegraph-base-v1"}}
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
             :kafka-topic "genegraph-fetch-base-events-v1"}
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

  env

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

(def gv-transformer-def
    {:type :genegraph-app
     :kafka-clusters {:data-exchange data-exchange}
     :topics {:gene-validity-gci
              {:name :gene-validity-gci
               :type :kafka-consumer-group-topic
               :kafka-consumer-group (:kafka-consumer-group env)
               :kafka-cluster :data-exchange
               :serialization :json
               :buffer-size 5
               :kafka-topic "gene_validity_complete-v1"}
              :gene-validity-sepio
              {:name :gene-validity-sepio
               :type :kafka-producer-topic
               :kafka-cluster :data-exchange
               :serialization ::rdf/n-triples
               :kafka-topic "gene_validity_sepio-v1"}}
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

  (def fucked-events
    (->> (get-in gv-transformer-prod [:topics :gene-validity-gci :event-status-queue])
         .iterator
         iterator-seq
         (remove #(realized? (::event/completion-promise %)))
         (into [])))

  (->> (get-in gv-transformer-prod [:topics :gene-validity-gci :event-status-queue])
           .size)
  
  (count fucked-events)

  (map ::event/key fucked-events)

  (-> (get-in gv-transformer-prod [:topics :gene-validity-gci])
      kafka/start-status-queue-monitor)

  
  (def processed-gv-events
    (event-store/with-event-reader [r
                                    "/Users/tristan/data/genegraph-neo/gv_events_complete_2024-01-12.edn.gz"]
      (->> (event-store/event-seq r)
           (take 1)
           (map #(assoc %
                        ::event/format :json
                        ::event/completion-promise (promise)))
           (map (fn [e]
                  (-> (p/process (get-in gv-transformer-prod
                                         [:processors :gene-validity-transform])
                                 e)
                      (dissoc :gene-validity/gci-model :gene-validity/model))))
           (into []))))

  (tap> processed-gv-events)

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
             :kafka-topic "gene_validity_sepio-v1"}
            :api-log
            {:name :api-log
             :type :kafka-producer-topic
             :kafka-cluster :data-exchange
             :serialization :edn
             :kafka-topic "genegraph_api_log-v1"}
            :dosage
            {:name :dosage
             :type :kafka-reader-topic
             :kafka-cluster :data-exchange
             :serialization :json
             :kafka-topic "gene_dosage_raw"}
            :actionability
            {:name :actionability
             :type :kafka-reader-topic
             :kafka-cluster :data-exchange
             :serialization :json
             :kafka-topic "actionability"}
            :gene-validity-legacy
            {:name :gene-validity-legacy
             :type :kafka-reader-topic
             :kafka-cluster :data-exchange
             :serialization :json
             :kafka-topic "gene-validity-legacy-complete-v1"} ; <<change to complete topic when exists>>
            :base-data
            {:name :base-data
             :type :kafka-reader-topic
             :kafka-cluster :data-exchange
             :serialization :edn
             :kafka-topic "genegraph-base-v1"}}
   :processors {:import-gv-curations import-gv-curations
                :import-base-file import-base-processor
                :graphql-api (assoc graphql-api :kafka-cluster :data-exchange)
                :import-actionability-curations import-actionability-curations
                :import-dosage-curations import-dosage-curations
                :import-gene-validity-legacy-report gene-validity-legacy-report-processor}
   :http-servers gv-http-server})

(comment
  (def gv-graphql-endpoint
    (p/init gv-graphql-endpoint-def))

  (kafka-admin/configure-kafka-for-app! gv-graphql-endpoint)

  (p/start gv-graphql-endpoint)
  (p/stop gv-graphql-endpoint)
  (->> (-> gv-graphql-endpoint
           :topics
           :dosage
           :event-status-queue
           .size)
       #_(filter #(realized? (::event/completion-promise %)))
       #_count)
  )

(def append-gene-validity-raw
  {:name ::append-gene-validity-raw
   :enter (fn [e]
            (event/publish
             e
             (assoc
              (select-keys e [::event/data ::event/key ::event/value ::event/timestamp])
              ::event/data (::event/value e)
              ::event/topic :gene-validity-complete)))})

(def append-gene-validity-legacy
  {:name ::append-gene-validity-raw
   :enter (fn [e]
            (event/publish
             e
             (assoc
              (select-keys e [::event/data ::event/key ::event/value ::event/timestamp])
              ::event/data (::event/value e)
              ::event/topic :gene-validity-legacy-complete)))})

(def gv-appender-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange data-exchange}
   :topics {:gene-validity-raw
            {:name :gene-validity-raw
             :type :kafka-consumer-group-topic
             :kafka-consumer-group (:kafka-consumer-group env)
             :kafka-cluster :data-exchange
             :kafka-topic "gene_validity_raw"}
            :gene-validity-complete
            {:name :gene-validity-complete
             :type :kafka-producer-topic
             :kafka-cluster :data-exchange
             :kafka-topic "gene_validity_complete-v1"}
            :gene-validity-legacy
            {:name :gene-validity-legacy
             :type :kafka-consumer-group-topic
             :kafka-consumer-group (:kafka-consumer-group env)
             :kafka-cluster :data-exchange
             :kafka-topic "gene_validity"}
            :gene-validity-legacy-complete
            {:type :kafka-producer-topic
             :name :gene-validity-legacy-complete
             :kafka-topic "gene-validity-legacy-complete-v1"
             :kafka-cluster :data-exchange}}
   :processors {:gene-validity-appender
                {:name :gene-validity-appender
                 :type :processor
                 :subscribe :gene-validity-raw
                 :kafka-cluster :data-exchange
                 :interceptors [append-gene-validity-raw]}
                :gene-validity-legacy-appender
                {:name :gene-validity-legacy-appender
                 :type :processor
                 :subscribe :gene-validity-legacy
                 :kafka-cluster :data-exchange
                 :interceptors [append-gene-validity-legacy]}}
   :http-servers gv-ready-server})

(comment
  (def gv-appender
    (p/init gv-appender-def))

  (:kafka-clusters gv-appender-def)

  (kafka-admin/configure-kafka-for-app! gv-appender)

  (p/start gv-appender)
  (p/stop gv-appender)


  (p/process (get-in gv-appender [:processors :gene-validity-appender])
             {::event/skip-local-effects true
              ::event/skip-publish-effects true
              ::event/value "{\"a\":\"a\"}"
              ::event/key "a"
              ::event/timestamp 1234})
  )


(def genegraph-function
  {"fetch-base" gv-base-app-def
   "transform-curations" gv-transformer-def
   "graphql-endpoint" gv-graphql-endpoint-def
   "appender" gv-appender-def})

(defn -main [& args]
  (log/info :fn ::-main
            :msg "starting genegraph"
            :function (:function env))
  (let [app (p/init (get genegraph-function (:function env) gv-test-app-def))]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (log/info :fn ::-main
                                           :msg "stopping genegraph")
                                 (p/stop app))))
    (p/start app)))
