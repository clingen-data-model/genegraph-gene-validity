(ns genegraph.user
  (:require [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.set :as s]
            [clojure.data.json :as json]
            [genegraph.framework.app :as gg-app]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event :as event]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.processor :as processor]
            [genegraph.gene-validity :as gv]
            [genegraph.gene-validity.gci-model :as gci-model]
            [genegraph.gene-validity.sepio-model :as sepio-model]
            [genegraph.gene-validity.versioning :as versioning]
            [portal.api :as portal])
  (:import [java.io File PushbackReader FileOutputStream BufferedWriter FileInputStream BufferedReader]
           [java.nio ByteBuffer]
           [java.time Instant OffsetDateTime]
           [java.util.zip GZIPInputStream GZIPOutputStream]
           [java.util.concurrent ThreadPoolExecutor Executor LinkedBlockingQueue TimeUnit]))



(defn event-seq-from-directory [directory]
  (let [files (->> directory
                  io/file
                  file-seq
                  (filter #(re-find #".edn" (.getName %))))]
    (map #(edn/read-string (slurp %)) files)))

(comment
 (def gv-prior-events
   (concat
    (event-seq-from-directory "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-snapshot")
    (event-seq-from-directory "/users/tristan/data/genegraph/2023-11-07T1617/events/:gci-raw-missing-data")))

 (def gv-initial-event-store "/users/tristan/data/genegraph-neo/gene_validity_inital_events.edn.gz")

 (event-store/store-events
  gv-initial-event-store
  (map #(-> (s/rename-keys % {:genegraph.sink.event/key ::event/key
                              :genegraph.sink.event/value ::event/value})
            (select-keys [::event/key ::event/value])) gv-prior-events))


 (event-store/with-event-reader [r gv-initial-event-store]
   (-> (event-store/event-seq r)
       first
       keys))


 (-> gv-prior-events first keys)
 (count gv-prior-events)

 )

(comment
  
 (->> (File. "/Users/tristan/data/genegraph/2023-04-13T1609/events/:gci-raw-snapshot")
      file-seq
      (filter #(re-find #"edn$" (str %)))
      count))

(comment
 (def znfid "1bb8bc84")

 (def znf-events 
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv_events.edn.gz"]
     (->> (event-store/event-seq r)
          (filter #(re-find #"1bb8bc84" (::event/value %)))
          (into []))))

 (def znf-models
   (mapv #(processor/process-event gv/gene-validity-transform %) znf-events))

 (-> znf-models first keys)

 (def published-znf
   (->> znf-models
        (filter #(= :publish (::event/action %)))))

 (rdf/pp-model
  (rdf/difference
   (::event/model (nth mras-events 1))
   (::event/model (nth mras-events 0))))


 (def mras-events
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv_events.edn.gz"]
     (->> (event-store/event-seq r)
          (filter #(re-find #"e4ea022c-a24e-42dd-b7e6-62eccb391a4f" (::event/value %)))
          (map #(processor/process-event gv/gene-validity-transform %))
          (filter #(= :publish (::event/action %)))
          (filter #(= "http://dataexchange.clinicalgenome.org/gci/e4ea022c-a24e-42dd-b7e6-62eccb391a4f"
                      (::event/iri %)))
          (into []))))

 (def pex19-events
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv_events.edn.gz"]
     (->> (event-store/event-seq r)
          (filter #(re-find #"fa073c77" (::event/value %)))
          #_(map #(processor/process-event gv/gene-validity-transform %))
          #_(filter #(= :publish (::event/action %)))
          #_(filter #(= "http://dataexchange.clinicalgenome.org/gci/e4ea022c-a24e-42dd-b7e6-62eccb391a4f"
                      (::event/iri %)))
          (into []))))

 (count pex19-events)

 (def pex19-curation
   (::event/model (processor/process-event gv/gene-validity-transform (last pex19-events))))

 ((rdf/create-query "select ?x where { ?x a :dc/BibliographicResource }") pex19-curation)

 (rdf/pp-model
  ((rdf/create-query "construct { ?s ?p ?o } where { ?s a :dc/BibliographicResource . ?s ?p ?o }") pex19-curation))

 (rdf/pp-model
  ((rdf/create-query "construct { ?s ?p ?o } where { ?s a :dc/BibliographicResource . ?s ?p ?o }") pex19-curation))

 (rdf/pp-model
  ((rdf/create-query "construct { ?s ?p ?o ; :dc/source ?source . } where { ?s :dc/source ?source . ?s ?p ?o . ?source a :dc/BibliographicResource .}")
   pex19-curation))

 ;;allele
 (rdf/pp-model
  ((rdf/create-query "construct { ?s ?p ?o } where { ?s ?p ?o .}")
   pex19-curation
   {:s (rdf/resource "http://dataexchange.clinicalgenome.org/gci/d664b4b5-4e2b-4d91-893c-4bcdeb804da4")}))


 (spit "/users/tristan/desktop/pex19.txt"
       (with-out-str (-> (processor/process-event gv/gene-validity-transform (last pex19-events))
                         ::event/model
                         rdf/pp-model)))

 (println "o")

 (count mras-events)

 ((rdf/create-query "select ?x where { ?x <http://dataexchange.clinicalgenome.org/gci/publishClassification> ?o }")
  (-> mras-events first :gene-validity/gci-model))

 (rdf/pp-model (-> mras-events first :gene-validity/gci-model))
 (rdf/pp-model (-> mras-events first ::event/model))

 (defn processed-model [event]
   (::event/model (processor/process-event gv/gene-validity-transform event)))

 (->  (rdf/difference (processed-model (nth mras-events 4))
                      (processed-model (nth mras-events 3)))
     rdf/pp-model)

 (rdf/pp-model (processed-model (nth mras-events 3)))
 
 (with-open [w (io/writer "/users/tristan/desktop/mras.edn")]
   (pprint (::event/data (first mras-events)) w))

 (rdf/pp-model (->> (nth znf-events 4)
                    (processor/process-event gv/gene-validity-transform)
                    ::event/model))
 (spit
  "/users/tristan/desktop/intermediate.json"
  (->> (nth znf-events 4)
       (processor/process-event gv/gene-validity-transform)
       ::event/data
       genegraph.gene-validity.gci-model/preprocess-json
       genegraph.gene-validity.gci-model/fix-gdm-identifiers))

  (-> (nth znf-events 4)
      ::event/timestamp
      Instant/ofEpochMilli
      str)
 
 (type
  (->> (nth znf-events 4)
       (processor/process-event gv/gene-validity-transform)
       ::event/data
       genegraph.gene-validity.gci-model/preprocess-json
       genegraph.gene-validity.gci-model/fix-gdm-identifiers))

 (map ::event/action znf-models)

 (->> mras-events
      (map gv/add-iri)
      (map ::event/iri)
      frequencies)
 )

(comment
  (p/start gv/gene-validity-transform)
  (keys gv/gene-validity-transform)
  (:state gv/gene-validity-transform)
  (p/stop gv/gene-validity-transform)
  )


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
   :kafka-user "User:2189780"
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

(def local-kafka
  {:common-config {"bootstrap.servers" "localhost:9092"}
   :producer-config {"key.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer",
                     "value.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"}
   :consumer-config {"key.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"
                     "value.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"}})

(defn publisher-fn [event]
  (println "publishing " (get-in event [:payload :key]))
  (event/publish event (assoc (:payload event)
                              ::event/topic :gv-complete)))

(defn transformer-fn [event]
  (println "reading offset: "
           (::event/offset event)
           " size: "
           (count (::event/value event)))
  event)


(def sept-1-2020
  (-> (OffsetDateTime/parse "2020-09-01T00:00Z")
      .toInstant
      .toEpochMilli))

(defn prior-event->publish-fn [e]
  {:payload
   (-> e
       (s/rename-keys {:genegraph.sink.event/key ::event/key
                       :genegraph.sink.event/value ::event/value})
       (select-keys [::event/key ::event/value])
       (assoc ::event/timestamp sept-1-2020))})

(defn current-event->publish-fn [e]
  {:payload (-> e
                (select-keys [::event/key ::event/value ::event/timestamp])
                (s/rename-keys {::event/value ::event/data}))})

(comment
 (def gv
   (p/init
    {:type :genegraph-app
     :kafka-clusters {:local dx-ccloud-dev}
     :topics {:gv-complete
              {:name :gv-complete
               :type :kafka-consumer-group-topic
               :kafka-consumer-group "testcg0"
               :kafka-cluster :local
               :kafka-topic "gene_validity_complete"}
              :publish-gv
              {:name :publish-gv
               :type :simple-queue-topic}}
     :processors {:gv-publisher
                  {:name :gv-publisher
                   :type :processor
                   :kafka-cluster :local
                   :subscribe :publish-gv
                   :interceptors `[publisher-fn]}
                  :gv-transformer
                  {:name :gv-transformer
                   :type :processor
                   :kafka-cluster :local
                   :subscribe :gv-complete
                   :interceptors `[transformer-fn]}}}))

 

 (p/start gv)
 (p/stop gv)
 
 (def gv-setup
   (p/init
    {:type :genegraph-app
     :kafka-clusters {:local dx-ccloud-dev}
     :topics {:gv-complete
              {:name :gv-complete
               :type :kafka-producer-topic
               :kafka-cluster :local
               :kafka-topic "gene_validity_complete"}
              :publish-gv
              {:name :publish-gv
               :type :simple-queue-topic}}
     :processors {:gv-publisher
                  {:name :gv-publisher
                   :type :processor
                   :kafka-cluster :local
                   :subscribe :publish-gv
                   :interceptors `[publisher-fn]}}}))
 (event-store/with-event-writer [w "/Users/tristan/data/genegraph-neo/all_gv_events_2024-01-16.edn.gz"]
   (kafka/topic->event-file (get-in gv [:topics :gv-complete]) w))

 (kafka-admin/configure-kafka-for-app! gv-setup)
 (with-open [admin (kafka-admin/create-admin-client dx-ccloud-dev)]
   (kafka-admin/delete-topic admin "gene_validity_complete"))
  
 (p/start gv-setup)
 (p/stop gv-setup)

 ;; todo start here, bootstrap gv events on local topic

 (-> gv
     :topics
     :gv-complete
     :state
     deref
     :kafka-consumer
     kafka/end-offset)

 (def gv-prior-events
   (concat
    (event-seq-from-directory "/users/tristan/data/genegraph/2023-08-08T1423/events/:gci-raw-snapshot")
    (event-seq-from-directory "/users/tristan/data/genegraph/2023-08-08T1423/events/:gci-raw-missing-data")))

 (run! #(p/publish (get-in gv-setup [:topics :publish-gv])
                   (prior-event->publish-fn %))
       gv-prior-events)

 (-> gv-prior-events
     first
     prior-event->publish-fn
     :payload
     ::event/timestamp
     Instant/ofEpochMilli)

 (kafka/topic->event-file
  {:name :gv-raw
   :type :kafka-reader-topic
   :kafka-cluster dx-ccloud
   :kafka-topic "gene_validity_raw"}
  "/users/tristan/desktop/gv_events_2024-01-12.edn.gz")

 (kafka/topic->event-file
  {:name :gv-raw-complete
   :type :kafka-reader-topic
   :kafka-cluster dx-ccloud-dev
   :kafka-topic "gene_validity_complete"}
  "/users/tristan/data/genegraph-neo/gv_events_complete_2024-01-12.edn.gz")

 (event-store/with-event-reader [r "/users/tristan/desktop/gv_events.edn.gz"]
   (-> (event-store/event-seq r)
       first
       ::event/timestamp
       Instant/ofEpochMilli))


 (event-store/with-event-reader [r "/Users/tristan/Desktop/gv_events_2024-01-12.edn.gz"]
   (-> (event-store/event-seq r)
       first
       ::event/timestamp
       Instant/ofEpochMilli))

 (event-store/with-event-reader [r "/Users/tristan/Desktop/gv_events_2024-01-12.edn.gz"]
   (-> (event-store/event-seq r)
       count))

 
(def sept-1-2020
  (-> (OffsetDateTime/parse "2020-09-01T00:00Z")
      .toInstant
      .toEpochMilli))

 (defn prior-event->publish-fn [e]
   {:payload
    (-> e
        (s/rename-keys {:genegraph.sink.event/key ::event/key
                        :genegraph.sink.event/value ::event/value})
        (select-keys [::event/key ::event/value])
        (assoc ::event/timestamp sept-1-2020))})

 (event-store/with-event-reader [r                             "/users/tristan/data/genegraph-neo/gene_validity_inital_events.edn.gz"]
   (->> (event-store/event-seq r)
        (map #(assoc % ::event/timestamp sept-1-2020))
        (run! #(p/publish (get-in gv-setup [:topics :publish-gv])
                     (current-event->publish-fn %)))))

 (event-store/with-event-reader [r "/users/tristan/desktop/gv_events_2024-01-12.edn.gz"]
   (run! #(p/publish (get-in gv-setup [:topics :publish-gv])
                     (current-event->publish-fn %))
         (event-store/event-seq r)))



 )

 

;; Starting to work with versioning
(comment
  (def event-path "/users/tristan/data/genegraph-neo/gv_events_complete_2024-01-12.edn.gz")

  (def gv-transform-app
    (p/init
     {:type :genegraph-app
      :storage
      {:gene-validity-version-store
       {:type :rocksdb
        :name :gene-validity-version-store
        :path "/Users/tristan/data/genegraph-neo/gv-version-store"}}
      :processors
      {:gene-validity-transform
       {:type :processor
        :name :gene-validity-transform
        :interceptors `[gci-model/add-gci-model
                        sepio-model/add-model
                        gv/add-iri
                        gv/add-publish-actions
                        versioning/add-version]}}}))

  (p/start gv-transform-app)
  (p/stop gv-transform-app)
  (def versions
    (event-store/with-event-reader [r event-path]
      (->> (event-store/event-seq r)
           (map #(p/process (get-in gv-transform-app [:processors :gene-validity-transform])
                            (event/deserialize
                             (assoc %
                                    ::event/format :json
                                    ::event/skip-publish-effects true))))
           (mapv #(select-keys % [::event/iri :gene-validity/version])))))


  (event-store/with-event-reader [r event-path]
    (->> (event-store/event-seq r)
         (take 1)
         (map #(p/process (get-in gv-transform-app [:processors :gene-validity-transform])
                          (assoc %
                                 ::event/format :json
                                 ::event/skip-publish-effects true)))
           (mapv #(select-keys % [::event/iri
                                  :gene-validity/version
                                  :gene-validity/model
                                  :gene-validity/approval-date]))
           #_(mapv #(versioning/has-publish-action (:gene-validity/model %)))))

  (-> versions
      )
  

  (->> versions
       (filter :gene-validity/version)
       (group-by ::event/iri)
       vals
       (map (fn [vs] (apply
                      max
                      (map #(get-in % [:gene-validity/version :major]) vs))))
       frequencies)

  (time
   (def curation-frequency
     (event-store/with-event-reader [r event-path]
       (->> (event-store/event-seq r)
            (map #(-> %
                      (assoc ::event/format :json
                             ::event/skip-local-effects true
                             ::event/skip-publish-effects true)
                      event/deserialize
                      ::event/data
                      :PK))
            frequencies
            (sort-by val)
            reverse
            (into [])))))

  (def top-curation-revisions
    (->> curation-frequency
         (filter #(< 3 (val %)))
         (map key)
         (remove nil?)
         set))

  (time
   (def most-revised-curations
     (event-store/with-event-reader [r event-path]
       (->> (event-store/event-seq r)
            (map #(-> %
                      (assoc ::event/format :json
                             ::event/skip-local-effects true
                             ::event/skip-publish-effects true)
                      event/deserialize))
            (filter #(top-curation-revisions (get-in % [::event/data :PK])))
            (group-by #(get-in % [::event/data :PK]))))))

  (def has-curation-reasons
    (event-store/with-event-reader [r event-path]
      (->> (event-store/event-seq r)
           (map #(-> %
                     (assoc ::event/format :json
                            ::event/skip-local-effects true
                            ::event/skip-publish-effects true)
                     event/deserialize))
           (filter #(seq (get-in % [::event/data :resource :curationReasons])))
           (into []))))

  (.toEpochMilli (Instant/parse "2023-10-01T00:00:00Z"))

  (def recent-events-without-reasons
    (event-store/with-event-reader [r event-path]
      (->> (event-store/event-seq r)
           (filter #(< (.toEpochMilli (Instant/parse "2023-10-01T00:00:00Z"))
                       (::event/timestamp %)))
           (map #(-> %
                     (assoc ::event/format :json
                            ::event/skip-local-effects true
                            ::event/skip-publish-effects true)
                     event/deserialize))
           (map #(p/process (get-in gv-transform-app
                                    [:processors :gene-validity-transform]) %))
           (map (fn [e] [(get-in e [::event/data :PK])
                         (get-in e [::event/data :resource :curationReasons])
                         (str (Instant/ofEpochMilli (::event/timestamp e)))
                         (get-in e [::event/data :resourceParent :gdm :gene :PK])
                         (get-in e [::event/data :resourceParent :gdm :disease :PK])
                         (gv/has-publish-action (:gene-validity/model e))]))
           (remove #(seq (second %)))
           (into []))))

  (tap> (filter
         #(nth % 5)
         recent-events-without-reasons))

  (rdf/pp-model (:gene-validity/model one-without-reasons))
  
  (def one-without-reasons
    (p/process (get-in gv-transform-app [:processors :gene-validity-transform])
               (nth (first recent-events-without-reasons) 5)))

  (rdf/pp-model (:gene-validity/model one-without-reasons))
  
  (clojure.pprint/pprint
   (->> recent-events-without-reasons
        (map #(take 5 %))))

  (tap> (first recent-events-without-reasons))
  (portal/close)
  (portal/open)

  (->> recent-events
       (remove #(seq (second %))))

  (def with-reasons
    (p/process (get-in gv-transform-app [:processors :gene-validity-transform])
               (first has-curation-reasons)))

  (def recuration-info
    (mapv #(versioning/recuration-from-gci-reasons?
           (p/process (get-in gv-transform-app [:processors :gene-validity-transform]) %))
         has-curation-reasons))

  (frequencies recuration-info)
  

  (-> has-curation-reasons
      first
      )
  
  (->> has-curation-reasons
       (map #(get-in % [::event/data :resource :curationReasons]))
       frequencies
       keys
       flatten
       set)
  
  (tap> gv-transform-app)
  
  (run! rdf/pp-model
        (->> has-curation-reasons
             (take 1)
             (map #(p/process (get-in gv-transform-app [:processors :gene-validity-transform]) %))
             (map :gene-validity/model)))
  
  (->> most-revised-curations
       (map (fn [[k v]] [k (Instant/ofEpochMilli
                            (apply max (map ::event/timestamp v)))]))
       (sort-by second)
       reverse)

  (defn apply-versions [curations]
    (into []
          (map #(-> (p/process (get-in gv-transform-app [:processors :gene-validity-transform])
                               (dissoc % ::event/skip-local-effects))
                    :gene-validity/version)
               curations)))

  (def revised-versions
    (update-vals most-revised-curations apply-versions))

  (tap> revised-versions)

  (filter (fn [vs] (some #(< 1 (:major %)) (val vs))) revised-versions)

  (->> (get most-revised-curations "07526c3e-b98a-4343-b40a-27a0575ffd1a")
       (map #(-> (p/process (get-in gv-transform-app [:processors :gene-validity-transform])
                            (dissoc % ::event/skip-local-effects))
                 :gene-validity/version))
       (into []))

  (storage/read @(get-in gv-transform-app [:storage :gene-validity-version-store :instance])
                "http://dataexchange.clinicalgenome.org/gci/296638fd-61a1-4f65-bb5f-b884e76f100a")

  (storage/delete @(get-in gv-transform-app [:storage :gene-validity-version-store :instance])
                  "http://dataexchange.clinicalgenome.org/gci/296638fd-61a1-4f65-bb5f-b884e76f100a")

  (def p (portal/open))
  (add-tap #'portal/submit)
  (portal/clear)
  (tap> :hello)
  (tap> :hi)

  (time
   (def gdm-f1705bb1
     (event-store/with-event-reader [r event-path]
       (->> (event-store/event-seq r)
            #_(map #(-> %
                        (assoc ::event/format :json
                               ::event/skip-local-effects true
                               ::event/skip-publish-effects true)
                        event/deserialize))
            (filter #(re-find #"f1705bb1-c435-4106-ab9b-422ff2dfe4bf"
                              (::event/value %)))
            (into [])))))

  (time
   (def gdm-96d7ec72
     (event-store/with-event-reader [r event-path]
       (->> (event-store/event-seq r)
            #_(map #(-> %
                        (assoc ::event/format :json
                               ::event/skip-local-effects true
                               ::event/skip-publish-effects true)
                        event/deserialize))
            (filter #(re-find #"96d7ec72-ce1d-4a67-aec6-4522ffbc9ae4"
                              (::event/value %)))
            (into [])))))

  ;; should have major revisions
  (def gdm-b372c7f6
    (event-store/with-event-reader [r event-path]
       (->> (event-store/event-seq r)
            (filter #(re-find #"b372c7f6"
                              (::event/value %)))
            (into []))))
  
  (->> gdm-b372c7f6
       (map #(-> %
                 (assoc ::event/format :json
                        ::event/skip-local-effects true
                        ::event/skip-publish-effects true)
                 event/deserialize))
       apply-versions)

  
  (->> gdm-96d7ec72
       (map #(-> % ::event/timestamp Instant/ofEpochMilli)))




  )

(def gdm-test
  (p/init
   {:type :genegraph-app
    :kafka-clusters {:data-exchange dx-ccloud}
    :topics {:gdm-general
             {:name :gdm-general-topic
              :type :kafka-reader-topic
              :kafka-topic "gpm-general-events"
              :serialization :json
              :kafka-cluster :data-exchange}}}))


(comment
  (kafka/topic->event-file
   {:name :gdm-general-topic
    :type :kafka-reader-topic
    :kafka-topic "gpm-general-events"
    :serialization :json
    :kafka-cluster dx-ccloud}
   "/Users/tristan/desktop/gpm_general.edn.gz")

  (event-store/with-event-reader [r "/Users/tristan/desktop/gpm_general.edn.gz"]
    (->> (event-store/event-seq r)
         (map #(-> % event/deserialize ::event/data))
         (filter #(and (= "ep_definition_approved" (:event_type %))
                       (get-in % [:data :scope :statement])))
         (into [])
         tap>))

  (event-store/with-event-reader [r "/Users/tristan/desktop/gpm_general.edn.gz"]
    (->> (event-store/event-seq r)
         (map #(-> % event/deserialize ::event/data :event_type))
         frequencies))
  
  )
