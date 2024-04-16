(ns genegraph.gv-setup
  "Functions and procedures generally intended to be run from the REPL
  for the purpose of configuring and maintaining a genegraph gene validity
  instance."
  (:require [genegraph.gene-validity :as gv]
            [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.event :as event]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.storage :as storage]
            [io.pedestal.interceptor :as interceptor]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.time Instant OffsetDateTime Duration]))


;; Step 1
;; Select the environment to configure

;; The gv/admin-env var specifies the platform to target and the
;; data exchange credentials to use. Set at least the :platform
;; and :dataexchange-genegraph keys for the correct environment
;; and kafka instance. This should be done in genegraph/gene_validity.clj

;; Step 2
;; Set up kafka topics, configure permissions

;; The kafka-admin/configure-kafka-for-app! function accepts an
;; (initialized) Genegraph app and creates the topics and necessary
;; permissions for those topics.

;; There are four Genegraph instances that need to be set up to create a
;; working installation:

;; gv-base-app-def: listents to fetch topic, retrieves base data and notifies gql endpoint
;; gv-transformer-def: Transforms gene validity curations to SEPIO format, publishes to Kafka
;; gv-graphql-endpoint-def: Ingest curations from various sources, publish via GraphQL endpoint
;; gv-appender-def: Append topics that require pre-seeding (gene_validity_raw, gene_validity)
;;                  with data produced by GCI.

(comment
  (run! #(kafka-admin/configure-kafka-for-app! (p/init %))
        [gv/gv-base-app-def
         gv/gv-transformer-def
         gv/gv-graphql-endpoint-def
         gv/gv-appender-def])
  )

;; Step 3
;; Seed newly created topics with initialization data. Use the local Genegraph
;; app definitions and rich comments to set up these topics.

;; Three topics need to be seeded with initialization data:

;; Step 3.1
;; :fetch-base-events: requests to update the base data supporting Genegraph
;; The data needed to seed this topic is stored in version control with
;;    genegraph-gene-validity in resources/base.edn

(defn seed-base-fn [event]
  (event/publish event (assoc (select-keys event [::event/data])
                              ::event/topic :fetch-base-events)))

(def seed-base-interceptor
  (interceptor/interceptor
   {:name ::seed-base-interceptor
    :enter (fn [e]
             (seed-base-fn e))}))

(def gv-seed-base-event-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange gv/data-exchange}
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
                 :interceptors [seed-base-interceptor]}}})

(comment
  (def gv-seed-base-event
    (p/init gv-seed-base-event-def))

  (p/start gv-seed-base-event)
  (p/stop gv-seed-base-event)

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (run! #(p/publish (get-in gv-seed-base-event
                                 [:topics :initiate-fetch])
                         {::event/data %})))  
  )

;; Step 3.2
;; :gene-validity-legacy: old 'summary' format for gene validity data, needs to be seeded with some
;;                        early data that needed to be recovered due to a misconfiguration.
;; The data needed to seed this topic is stored in Google Cloud Storage

;; This step has been flaky on occasion. Remember to verfiy that all historic curations have been
;; successfully written to the topic before moving on.

(defn publish-legacy-curation-fn [e]
  (let [id (get-in e [::event/data :id])
        score-string (get-in e [::event/data :score-string])]
    (event/publish e
                   {::event/topic :gene-validity-legacy-complete-v1
                    ::event/key id
                    ::event/data {:iri id
                                  :scoreJson score-string}})))

(def publish-legacy-curation
  (interceptor/interceptor
   {:name ::publish-legacy-curation
    :enter (fn [e] (publish-legacy-curation-fn e))}))

(def upload-gv-neo4j-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange gv/data-exchange}
   :topics {:gene-validity-legacy-complete-v1
            {:type :kafka-producer-topic
             :name :gene-validity-legacy-complete-v1
             :kafka-topic "gene-validity-legacy-complete-v1"
             :serialization :json
             :kafka-cluster :data-exchange}}
   :processors {:publish-legacy-curations-processor
                {:type :processor
                 :name :publish-legacy-curations-processor
                 :kafka-cluster :data-exchange
                 :interceptors [publish-legacy-curation]}}})

(comment
  (def upload-gv-neo4j
    (p/init upload-gv-neo4j-def))
  (p/start upload-gv-neo4j)
  (p/stop upload-gv-neo4j)

  (->> "/Users/tristan/data/genegraph-neo/neo4j-legacy-events"
       io/file
       file-seq
       (filter #(.isFile %))
       (map #(-> %
                 slurp
                 edn/read-string
                 (s/rename-keys {:genegraph.sink.event/value ::event/data})))
       (remove #(gv-legacy-on-stream (get-in % [::event/data :id])))
       (run! #(p/process (get-in upload-gv-neo4j
                                 [:processors :publish-legacy-curations-processor])
                         %)))
  
  )

;; :gene-validity-complete: raw gene validity data, needs to be seeded with gene validity curations
;;                          made prior to release of the GCI
;; The data needed to seed this topic is stored in Google Cloud Storage

;; This is another potentially flaky one. Remember to verfiy that all historic curations have been
;; successfully written to the topic before moving on.


(def gene-validity-raw-publisher
  (interceptor/interceptor
   {:name ::gene-validity-raw-publisher
    :enter (fn [event]
             (event/publish event (assoc (:payload event)
                                         ::event/topic :gv-complete)))}))

(def gv-setup
  (p/init
   {:type :genegraph-app
    :kafka-clusters {:local gv/data-exchange}
    :topics {:gv-complete
             {:name :gv-complete
              :type :kafka-producer-topic
              :kafka-cluster :local
              :kafka-topic "gene_validity_complete-v1"}
             :publish-gv
             {:name :publish-gv
              :type :simple-queue-topic}}
    :processors {:gv-publisher
                 {:name :gv-publisher
                  :type :processor
                  :kafka-cluster :local
                  :subscribe :publish-gv
                  :interceptors [gene-validity-raw-publisher]}}}))

(def sept-1-2020
  (-> (OffsetDateTime/parse "2020-09-01T00:00Z")
      .toInstant
      .toEpochMilli))

(defn prior-event->publish-fn [e]
  {:payload
   (-> e
       (set/rename-keys {:genegraph.sink.event/key ::event/key
                       :genegraph.sink.event/value ::event/data})
       (select-keys [::event/key ::event/data])
       (assoc ::event/timestamp sept-1-2020))})

(defn event-seq-from-directory [directory]
  (let [files (->> directory
                   io/file
                   file-seq
                   (filter #(re-find #".edn" (.getName %))))]
    (map #(edn/read-string (slurp %)) files)))

(defn publisher-fn [event]
  (event/publish event (assoc (:payload event)
                              ::event/topic :gv-complete)))

(def gene-validity-raw-publisher
  (interceptor/interceptor
   {:name ::gene-validity-raw-publisher
    :enter (fn [event]
             (event/publish event (assoc (:payload event)
                                         ::event/topic :gv-complete)))}))

(def gv-setup-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange gv/data-exchange}
   :topics {:gv-complete
            {:name :gv-complete
             :type :kafka-producer-topic
             :kafka-cluster :data-exchange
             :kafka-topic "gene_validity_complete-v1"}
            :publish-gv
            {:name :publish-gv
             :type :simple-queue-topic}}
   :processors {:gv-publisher
                {:name :gv-publisher
                 :type :processor
                 :kafka-cluster :data-exchange
                 :subscribe :publish-gv
                 :interceptors [gene-validity-raw-publisher]}}})


(comment
  (def gv-setup (p/init gv-setup))
  (p/start gv-setup)
  (p/stop gv-setup)

   (run! #(p/publish (get-in gv-setup [:topics :publish-gv])
                   (prior-event->publish-fn %))
           (concat
   (event-seq-from-directory "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-snapshot")
   (event-seq-from-directory "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-missing-data")))
  
  )
