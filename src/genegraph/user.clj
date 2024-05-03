(ns genegraph.user
  (:require [genegraph.framework.protocol]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.event :as event]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event.store :as event-store]
            [genegraph.gene-validity :as gv]
            [genegraph.gene-validity.gci-model :as gci-model]
            [genegraph.gene-validity.graphql.response-cache :as response-cache]
            [portal.api :as portal]
            [clojure.data.json :as json]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor])
  (:import [java.time Instant LocalDate]
           [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]))

;; Portal
(comment
  (def p (portal/open))
  (add-tap #'portal/submit)
  (portal/close)
  (portal/clear)
  )

;; Test app

(defn log-api-event-fn [e]
  (let [data (::event/data e)]
    (log/info :fn ::log-api-event
              :duration (- (:end-time data) (:start-time data))
              :response-size (:response-size data)
              :handled-by (:handled-by data)
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
   :kafka-clusters {:data-exchange gv/data-exchange}
   :topics {:gene-validity-complete
            {:name :gene-validity-complete
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
            :gene-validity-legacy-complete
            {:name :gene-validity-legacy-complete
             :type :simple-queue-topic}
            :api-log
            {:name :api-log
             :type :simple-queue-topic}}
   :storage {:gv-tdb gv/gv-tdb
             :gene-validity-version-store gv/gene-validity-version-store
             :response-cache-db gv/response-cache-db}
   :processors {:gene-validity-transform gv/transform-processor
                :fetch-base-file gv/fetch-base-processor
                :import-base-file gv/import-base-processor
                :import-gv-curations gv/import-gv-curations
                :graphql-api (assoc gv/graphql-api
                                    ::event/metadata
                                    {::response-cache/skip-response-cache true})
                :import-actionability-curations gv/import-actionability-curations
                :import-dosage-curations gv/import-dosage-curations
                :read-api-log read-api-log
                :import-gene-validity-legacy-report gv/gene-validity-legacy-report-processor}
   :http-servers gv/gv-http-server})

(comment
  (def gv-test-app (p/init gv-test-app-def))
  (p/start gv-test-app)
  (p/stop gv-test-app)

  )

;; Downloading events

(def root-data-dir "/Users/tristan/data/genegraph-neo/")

(defn get-events-from-topic [topic]
  ;; topic->event-file redirects stdout
  ;; need to supress kafka logs for the duration
  (.setLevel
   (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/ERROR)
  (kafka/topic->event-file
   (assoc topic
          :type :kafka-reader-topic
          :kafka-cluster gv/data-exchange)
   (str root-data-dir
        (:kafka-topic topic)
        "-"
        (LocalDate/now)
        ".edn.gz"))
  (.setLevel (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/INFO))



;; Event Writers

(comment

  (get-events-from-topic gv/actionability-topic)
  (get-events-from-topic gv/gene-validity-complete-topic)
  (get-events-from-topic gv/gene-validity-raw-topic)

)

;; Gene Validity Interrogation

(comment

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-stage-1-2024-04-24.edn.gz"]
    (->> (event-store/event-seq r)
         count))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_raw-2024-05-03.edn.gz"]
    (->> (event-store/event-seq r)
         count))

  

  )

;; Testing processing of data on prod -- troubleshooting issue with transformer

(comment
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-1-2024-05-03.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (run! #(p/publish (get-in gv-test-app [:topics :gene-validity-complete]) %))))
  (time 
   (def fails
     (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-1-2024-05-03.edn.gz"]
       (->> (event-store/event-seq r)
            #_(take 100)
            (pmap #(try
                     (-> %
                         event/deserialize
                         gci-model/add-gci-model-fn)
                     (catch Exception e (assoc % :exception e))))
            (filter :exception)
            (into [])))))

  (count fails)
  (-> fails first event/deserialize tap>)
  (tap> (first fails))

  ;; Discovered that the gene-validity-raw appender was not appending
  ;; JSON, but rather a string of escaped JSON. need to fix this.

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_raw-2024-05-03.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (mapv (fn [e]
                 (-> e
                     event/deserialize
                     (event/publish
                      (assoc
                       (select-keys e [::event/data ::event/key ::event/value ::event/timestamp])
                       ::event/data (::event/value e)
                       ::event/topic :gene-validity-complete)))))
         tap>))  

  (portal/clear)
  
  )


;; New ClinVar data

(def gv-w-cv-evidence-path
  "/users/tristan/Desktop/scv-publish-raw.json")

(comment
  (def gv-w-cv-evidence
    (-> gv-w-cv-evidence-path
        slurp
        (json/read-str :key-fn keyword)))
  (-> (p/process
       (get-in gv-test-app [:processors :gene-validity-transform])
       {::event/value (slurp gv-w-cv-evidence-path)
        ::event/format :json
        ::event/skip-local-effects true
        ::event/skip-publish-effects true
        ::event/completion-promise (promise)})
      :gene-validity/model
      rdf/pp-model)

  (tap> gv-w-cv-evidence)

  
  )

;; GO terms for functional data
(comment
  ;; d35ff1da-7306-43ef-9fc4-9841e6c000d7
  ;; SMARCB1 Coffin Siris Syndrome
  (def smarcb1
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-stage-1-2024-04-24.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"d35ff1da-7306-43ef-9fc4-9841e6c000d7"
                             (::event/value %)))
           (into []))))
  
  )

