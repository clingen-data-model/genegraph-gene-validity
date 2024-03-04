(ns genegraph.user
  (:require [clojure.edn :as edn]
            [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.set :as s]
            [clojure.string :as string]
            [clojure.data.json :as json]
            [clojure.data :as data]
            [clojure.walk :as walk]
            [clojure.tools.analyzer.jvm :as jvma]
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
            [genegraph.framework.storage.rdf.names :as names]
            [genegraph.gene-validity :as gv]
            [genegraph.gene-validity.gci-model :as gci-model]
            [genegraph.gene-validity.sepio-model :as sepio-model]
            [genegraph.gene-validity.versioning :as versioning]
            [genegraph.gene-validity.actionability :as actionability]
            [genegraph.gene-validity.dosage :as dosage]
            [portal.api :as portal]
            [io.pedestal.log :as log]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.lacinia.parser :as parser]
            [hato.client :as hc])
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

 (def stk11
   (event-store/with-event-reader [r
                                   "/Users/tristan/data/genegraph-neo/gv_events_complete_2024-01-12.edn.gz"]
     (->> (event-store/event-seq r)
          (filter #(re-find #"89685102-ac40" (::event/value %)))
          (into []))))

 (tap> (json/read-str (::event/value (first stk11))))
  
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



(comment
  (kafka/topic->event-file
   {:name :gv-sepio
    :type :kafka-reader-topic
    :kafka-cluster dx-ccloud-dev
    :kafka-topic "gene_validity_sepio"}
   "/users/tristan/data/genegraph-neo/gv_sepio_2024-01-12.edn.gz")

 (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/gv_sepio_2024-01-12.edn.gz"]
   (-> (event-store/event-seq r)
       first
       (assoc ::event/format ::rdf/n-triples)
       event/deserialize
       ::event/data
       rdf/pp-model))
  )


(comment
  (kafka/topic->event-file
   {:name :gv-sepio
    :type :kafka-reader-topic
    :kafka-cluster dx-ccloud
    :serialization :json
    :kafka-topic "actionability"}
   "/users/tristan/data/genegraph-neo/actionability_2024-02-12.edn.gz")

  (let [tdb @(get-in gv/gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (event-store/with-event-reader [r    "/users/tristan/data/genegraph-neo/actionability_2024-02-12.edn.gz"]
        (->> (event-store/event-seq r)
             (map (fn [e]
                    (assoc-in e [::storage/storage :gv-tdb] tdb)))
             first
             event/deserialize
             actionability/add-actionability-model-fn
             ::actionability/model
             rdf/pp-model))))
  
  )


(comment
  (kafka/topic->event-file
   {:name :gv-sepio
    :type :kafka-reader-topic
    :kafka-cluster dx-ccloud
    :serialization :json
    :kafka-topic "gene_dosage_raw"}
   "/users/tristan/data/genegraph-neo/gene-dosage_2024-02-13.edn.gz")


  (let [tdb @(get-in gv/gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/gene-dosage_2024-02-13.edn.gz"]
        (->> (event-store/event-seq r)
             (map (fn [e]
                    (assoc-in e [::storage/storage :gv-tdb] tdb)))
             first
             event/deserialize
             dosage/add-dosage-report-model
             ::dosage/model
             rdf/pp-model))))
  )

(def resolver-map
  {:clinvar/clinical-assertion-single 'cv-clinical-assertion/clinical-assertion-single
   :clinvar/clinical-assertion-subject 'cv-clinical-assertion/subject
   :clinvar/clinical-assertion-predicate 'cv-clinical-assertion/predicate
   :clinvar/clinical-assertion-object 'cv-clinical-assertion/object
   :clinvar/clinical-assertion-version 'cv-clinical-assertion/version
   :clinvar/clinical-assertion-list 'cv-clinical-assertion/clinical-assertion-list
   :clinvar/clinical-assertion-contribution 'cv-clinical-assertion/contribution
   :clinvar/clinical-assertion-review-status 'cv-clinical-assertion/review-status
   :clinvar/clinical-assertion-date-updated 'cv-clinical-assertion/date-updated
   :clinvar/clinical-assertion-release-date 'cv-clinical-assertion/release-date
   :clinvar/clinical-assertion-version-of 'cv-clinical-assertion/version-of
   :clinvar/clinical-assertion-allele-origin 'cv-clinical-assertion/allele-origin
   :clinvar/clinical-assertion-collection-method 'cv-clinical-assertion/collection-method
   :clinvar/clinical-assertion-classification-context 'cv-clinical-assertion/classification-context
   :clinvar/aggregate-assertion-list 'cv-aggregate-assertion/aggregate-assertion-list
   :clinvar/aggregate-assertion-version-of 'cv-aggregate-assertion/version-of
   :clinvar/aggregate-assertion-release-date 'cv-aggregate-assertion/release-date
   :clinvar/aggregate-assertion-review-status 'cv-aggregate-assertion/review-status
   :clinvar/aggregate-assertion-subject 'cv-aggregate-assertion/subject
   :clinvar/aggregate-assertion-predicate 'cv-aggregate-assertion/predicate
   :clinvar/aggregate-assertion-object 'cv-aggregate-assertion/object
   :clinvar/aggregate-assertion-version 'cv-aggregate-assertion/version
   :clinvar/aggregate-assertion-members 'cv-aggregate-assertion/members
   :contribution/contribution-query 'cv-contribution/contribution-single
   :contribution/agent 'cv-contribution/agent
   :contribution/agent-role 'cv-contribution/agent-role
   :contribution/activity-date 'cv-contribution/activity-date
   :variant/variant-single 'cv-variant/variant-single
   :variant/name 'cv-variant/variant-name
   :variant/genes 'cv-variant/variant-genes
   :variant/id 'cv-variant/variant-id
   :clinvar/gene-list 'cv-gene/gene-list
   :clinvar/gene-preferred-label 'cv-gene/gene-preferred-label
   :clinvar/gene-symbol 'cv-gene/gene-symbol

   :actionability/actionability-query 'actionability/actionability-query
   :actionability/classification-description 'actionability/classification-description
   :actionability/conditions 'actionability/conditions
   :actionability/report-date 'actionability/report-date
   :actionability/report-id 'actionability/report-id
   :actionability/source 'actionability/source
   :actionability/wg-label 'actionability/wg-label
   :actionability/tot-actionability-reports 'actionability/tot-actionability-reports
   :actionability/tot-actionability-updated-reports 'actionability/tot-actionability-updated-reports
   :actionability/tot-gene-disease-pairs 'actionability/tot-gene-disease-pairs
   :actionability/tot-adult-gene-disease-pairs 'actionability/tot-adult-gene-disease-pairs
   :actionability/tot-pediatric-gene-disease-pairs 'actionability/tot-pediatric-gene-disease-pairs
   :actionability/tot-outcome-intervention-pairs 'actionability/tot-outcome-intervention-pairs
   :actionability/tot-adult-outcome-intervention-pairs 'actionability/tot-adult-outcome-intervention-pairs
   :actionability/tot-pediatric-outcome-intervention-pairs 'actionability/tot-pediatric-outcome-intervention-pairs
   :actionability/tot-adult-failed-early-rule-out 'actionability/tot-adult-failed-early-rule-out
   :actionability/tot-pediatric-failed-early-rule-out 'actionability/tot-pediatric-failed-early-rule-out
   :actionability/tot-adult-score-counts 'actionability/tot-adult-score-counts
   :actionability/tot-pediatric-score-counts 'actionability/tot-pediatric-score-counts
   :actionability/statistics-query 'actionability/statistics-query
   :ac-assertion/report-date 'ac-assertion/report-date
   :ac-assertion/source 'ac-assertion/source
   :ac-assertion/classification 'ac-assertion/classification
   :ac-assertion/attributed-to 'ac-assertion/attributed-to
   :ac-assertion/report-label 'ac-assertion/report-label
   :affiliation/affiliation-query 'affiliation/affiliation-query
   :affiliation/affiliations 'affiliation/affiliations
   :affiliation/curated-diseases 'affiliation/curated-diseases
   :affiliation/curated-genes 'affiliation/curated-genes
   :affiliation/gene-validity-assertions 'affiliation/gene-validity-assertions
   :assertion/subject 'assertion/subject
   :assertion/predicate 'assertion/predicate
   :assertion/object 'assertion/object
   :assertion/evidence-lines 'assertion/evidence-lines
   :assertion/subject-of 'assertion/subject-of
   :classification/classifications 'classification/classifications
   :condition/condition-query 'condition/condition-query
   :condition/curation-activities 'condition/curation-activities
   :condition/description 'condition/description
   :condition/direct-subclasses 'condition/direct-subclasses
   :condition/direct-superclasses 'condition/direct-superclasses
   :condition/disease-list 'condition/disease-list
   :condition/diseases 'condition/diseases
   :condition/genetic-conditions 'condition/genetic-conditions
   :condition/last-curated-date 'condition/last-curated-date
   :condition/subclasses 'condition/subclasses
   :condition/superclasses 'condition/superclasses
   :condition/synonyms 'condition/synonyms
   :condition/propositions 'condition/propositions
   :gv-contribution/agent 'contribution/agent
   :gv-contribution/realizes 'contribution/realizes
   :gv-contribution/date 'contribution/date
   :coordinate/assembly 'coordinate/assembly
   :coordinate/build 'coordinate/build
   :coordinate/chromosome 'coordinate/chromosome
   :coordinate/end-pos 'coordinate/end-pos
   :coordinate/start-pos 'coordinate/start-pos
   :coordinate/strand 'coordinate/strand
   :criteria/criteria 'criteria/criteria
   :dosage-proposition/assertion-type 'dosage-proposition/assertion-type
   :dosage-proposition/classification-description 'dosage-proposition/classification-description
   :dosage-proposition/comments 'dosage-proposition/comments
   :dosage-proposition/disease 'dosage-proposition/disease
   :dosage-proposition/dosage-classification 'dosage-proposition/dosage-classification
   :dosage-proposition/evidence 'dosage-proposition/evidence
   :dosage-proposition/gene 'dosage-proposition/gene
   :dosage-proposition/phenotypes 'dosage-proposition/phenotypes
   :dosage-proposition/report-date 'dosage-proposition/report-date
   :dosage-proposition/score 'dosage-proposition/score
   :dosage-proposition/wg-label 'dosage-proposition/wg-label
   :drug/aliases 'drug/aliases
   :drug/drug-query 'drug/drug-query
   :drug/drugs 'drug/drugs
   :evidence/description 'evidence/description
   :evidence/source 'evidence/source
   :evidence-line/evidence-items 'evidence-line/evidence-items
   :evidence-line/score 'evidence-line/score
   :evidence-item/evidence-lines 'evidence-item/evidence-lines
   :gene/chromosome-band 'gene/chromosome-band
   :gene/conditions 'gene/conditions
   :gene/curation-activities 'gene/curation-activities
   :gene/dosage-curation 'gene/dosage-curation
   :gene/gene-validity-assertions 'gene/gene-validity-assertions
   :gene/gene-list 'gene/gene-list
   :gene/gene-query 'gene/gene-query
   :gene/genes 'gene/genes
   :gene/hgnc-id 'gene/hgnc-id
   :gene/last-curated-date 'gene/last-curated-date
   :gene-dosage/dosage-list-query 'gene-dosage/dosage-list-query
   :gene-dosage/gene-count 'gene-dosage/gene-count
   :gene-dosage/gene-dosage-query 'gene-dosage/gene-dosage-query
   :gene-dosage/genomic-feature 'gene-dosage/genomic-feature
   :gene-dosage/haplo 'gene-dosage/haplo
   :gene-dosage/haplo-index 'gene-dosage/haplo-index
   :gene-dosage/label 'gene-dosage/label
   :gene-dosage/location-relationship 'gene-dosage/location-relationship
   :gene-dosage/morbid 'gene-dosage/morbid
   :gene-dosage/morbid-phenotypes 'gene-dosage/morbid-phenotypes
   :gene-dosage/omim 'gene-dosage/omim
   :gene-dosage/pli-score 'gene-dosage/pli-score
   :gene-dosage/region-count 'gene-dosage/region-count
   :gene-dosage/report-date 'gene-dosage/report-date
   :gene-dosage/total-count 'gene-dosage/total-count
   :gene-dosage/totals-query 'gene-dosage/totals-query
   :gene-dosage/triplo 'gene-dosage/triplo
   :gene-dosage/wg-label 'gene-dosage/wg-label
   :gene-feature/alias-symbols 'gene-feature/alias-symbols
   :gene-feature/chromosomal-band 'gene-feature/chromosomal-band
   :gene-feature/coordinates 'gene-feature/coordinates
   :gene-feature/function 'gene-feature/function
   :gene-feature/gene-type 'gene-feature/gene-type
   :gene-feature/hgnc-id 'gene-feature/hgnc-id
   :gene-feature/locus-type 'gene-feature/locus-type
   :gene-feature/previous-symbols 'gene-feature/previous-symbols
   :gene-validity/attributed-to 'gene-validity/attributed-to
   :gene-validity/classification 'gene-validity/classification
   :gene-validity/contributions ' gene-validity/contributions
   :gene-validity/disease 'gene-validity/disease
   :gene-validity/gene 'gene-validity/gene
   :gene-validity/gene-validity-assertion-query 'gene-validity/gene-validity-assertion-query
   :gene-validity/gene-validity-curations 'gene-validity/gene-validity-curations
   :gene-validity/gene-validity-list 'gene-validity/gene-validity-list
   :gene-validity/legacy-json 'gene-validity/legacy-json
   :gene-validity/mode-of-inheritance 'gene-validity/mode-of-inheritance
   :gene-validity/report-date 'gene-validity/report-date
   :gene-validity/specified-by 'gene-validity/specified-by
   :gene-validity/has-format 'gene-validity/has-format
   :gene-validity/report-id 'gene-validity/report-id
   :gene-validity/animal-model 'gene-validity/animal-model
   :genetic-condition/actionability-curations 'genetic-condition/actionability-curations
   :genetic-condition/actionability-assertions 'genetic-condition/actionability-assertions
   :genetic-condition/disease 'genetic-condition/disease
   :genetic-condition/gene 'genetic-condition/gene
   :genetic-condition/gene-dosage-curation 'genetic-condition/gene-dosage-curation
   :genetic-condition/gene-validity-curation 'genetic-condition/gene-validity-curation
   :genetic-condition/mode-of-inheritance 'genetic-condition/mode-of-inheritance
   :group/groups 'group/groups
   :mode-of-inheritance/modes-of-inheritance 'mode-of-inheritance/modes-of-inheritance
   :region-feature/chromosomal-band 'region-feature/chromosomal-band
   :region-feature/coordinates 'region-feature/coordinates
   :resource/alternative-label 'resource/alternative-label
   :resource/curie 'resource/curie
   :resource/iri 'resource/iri
   :resource/label 'resource/label
   :resource/website-display-label 'resource/website-display-label
   :resource/type 'resource/type
   :server-status/migration-version 'server-status/migration-version
   :server-status/server-version-query 'server-status/server-version-query
   :suggest/curations 'suggest/curations
   :suggest/curie 'suggest/curie
   :suggest/alternative-curie 'suggest/alternative-curie
   :suggest/highlighted-text 'suggest/highlighted-text
   :suggest/iri 'suggest/iri
   :suggest/suggest 'suggest/suggest
   :suggest/suggest-type 'suggest/suggest-type
   :suggest/text 'suggest/text
   :suggest/weight 'suggest/weight
   :user/user-query 'user/user-query
   :user/current-user 'user/current-user
   :user/email 'user/email
   :user/is-admin 'user/is-admin
   :user/member-of 'user/member-of})

(def resolver-map-identity
  {:clinvar/clinical-assertion-single identity
   :clinvar/clinical-assertion-subject identity
   :clinvar/clinical-assertion-predicate identity
   :clinvar/clinical-assertion-object identity
   :clinvar/clinical-assertion-version identity
   :clinvar/clinical-assertion-list identity
   :clinvar/clinical-assertion-contribution identity
   :clinvar/clinical-assertion-review-status identity
   :clinvar/clinical-assertion-date-updated identity
   :clinvar/clinical-assertion-release-date identity
   :clinvar/clinical-assertion-version-of identity
   :clinvar/clinical-assertion-allele-origin identity
   :clinvar/clinical-assertion-collection-method identity
   :clinvar/clinical-assertion-classification-context identity
   :clinvar/aggregate-assertion-list identity
   :clinvar/aggregate-assertion-version-of identity
   :clinvar/aggregate-assertion-release-date identity
   :clinvar/aggregate-assertion-review-status identity
   :clinvar/aggregate-assertion-subject identity
   :clinvar/aggregate-assertion-predicate identity
   :clinvar/aggregate-assertion-object identity
   :clinvar/aggregate-assertion-version identity
   :clinvar/aggregate-assertion-members identity
   :contribution/contribution-query identity
   :contribution/agent identity
   :contribution/agent-role identity
   :contribution/activity-date identity
   :variant/variant-single identity
   :variant/name identity
   :variant/genes identity
   :variant/id identity
   :clinvar/gene-list identity
   :clinvar/gene-preferred-label identity
   :clinvar/gene-symbol identity

   :actionability/actionability-query identity
   :actionability/classification-description identity
   :actionability/conditions identity
   :actionability/report-date identity
   :actionability/report-id identity
   :actionability/source identity
   :actionability/wg-label identity
   :actionability/tot-actionability-reports identity
   :actionability/tot-actionability-updated-reports identity
   :actionability/tot-gene-disease-pairs identity
   :actionability/tot-adult-gene-disease-pairs identity
   :actionability/tot-pediatric-gene-disease-pairs identity
   :actionability/tot-outcome-intervention-pairs identity
   :actionability/tot-adult-outcome-intervention-pairs identity
   :actionability/tot-pediatric-outcome-intervention-pairs identity
   :actionability/tot-adult-failed-early-rule-out identity
   :actionability/tot-pediatric-failed-early-rule-out identity
   :actionability/tot-adult-score-counts identity
   :actionability/tot-pediatric-score-counts identity
   :actionability/statistics-query identity
   :ac-assertion/report-date identity
   :ac-assertion/source identity
   :ac-assertion/classification identity
   :ac-assertion/attributed-to identity
   :ac-assertion/report-label identity
   :affiliation/affiliation-query identity
   :affiliation/affiliations identity
   :affiliation/curated-diseases identity
   :affiliation/curated-genes identity
   :affiliation/gene-validity-assertions identity
   :assertion/subject identity
   :assertion/predicate identity
   :assertion/object identity
   :assertion/evidence-lines identity
   :assertion/subject-of identity
   :classification/classifications identity
   :condition/condition-query identity
   :condition/curation-activities identity
   :condition/description identity
   :condition/direct-subclasses identity
   :condition/direct-superclasses identity
   :condition/disease-list identity
   :condition/diseases identity
   :condition/genetic-conditions identity
   :condition/last-curated-date identity
   :condition/subclasses identity
   :condition/superclasses identity
   :condition/synonyms identity
   :condition/propositions identity
   :gv-contribution/agent identity
   :gv-contribution/realizes identity
   :gv-contribution/date identity
   :coordinate/assembly identity
   :coordinate/build identity
   :coordinate/chromosome identity
   :coordinate/end-pos identity
   :coordinate/start-pos identity
   :coordinate/strand identity
   :criteria/criteria identity
   :dosage-proposition/assertion-type identity
   :dosage-proposition/classification-description identity
   :dosage-proposition/comments identity
   :dosage-proposition/disease identity
   :dosage-proposition/dosage-classification identity
   :dosage-proposition/evidence identity
   :dosage-proposition/gene identity
   :dosage-proposition/phenotypes identity
   :dosage-proposition/report-date identity
   :dosage-proposition/score identity
   :dosage-proposition/wg-label identity
   :drug/aliases identity
   :drug/drug-query identity
   :drug/drugs identity
   :evidence/description identity
   :evidence/source identity
   :evidence-line/evidence-items identity
   :evidence-line/score identity
   :evidence-item/evidence-lines identity
   :gene/chromosome-band identity
   :gene/conditions identity
   :gene/curation-activities identity
   :gene/dosage-curation identity
   :gene/gene-validity-assertions identity
   :gene/gene-list identity
   :gene/gene-query identity
   :gene/genes identity
   :gene/hgnc-id identity
   :gene/last-curated-date identity
   :gene-dosage/dosage-list-query identity
   :gene-dosage/gene-count identity
   :gene-dosage/gene-dosage-query identity
   :gene-dosage/genomic-feature identity
   :gene-dosage/haplo identity
   :gene-dosage/haplo-index identity
   :gene-dosage/label identity
   :gene-dosage/location-relationship identity
   :gene-dosage/morbid identity
   :gene-dosage/morbid-phenotypes identity
   :gene-dosage/omim identity
   :gene-dosage/pli-score identity
   :gene-dosage/region-count identity
   :gene-dosage/report-date identity
   :gene-dosage/total-count identity
   :gene-dosage/totals-query identity
   :gene-dosage/triplo identity
   :gene-dosage/wg-label identity
   :gene-feature/alias-symbols identity
   :gene-feature/chromosomal-band identity
   :gene-feature/coordinates identity
   :gene-feature/function identity
   :gene-feature/gene-type identity
   :gene-feature/hgnc-id identity
   :gene-feature/locus-type identity
   :gene-feature/previous-symbols identity
   :gene-validity/attributed-to identity
   :gene-validity/classification identity
   :gene-validity/contributions identity
   :gene-validity/disease identity
   :gene-validity/gene identity
   :gene-validity/gene-validity-assertion-query identity
   :gene-validity/gene-validity-curations identity
   :gene-validity/gene-validity-list identity
   :gene-validity/legacy-json identity
   :gene-validity/mode-of-inheritance identity
   :gene-validity/report-date identity
   :gene-validity/specified-by identity
   :gene-validity/has-format identity
   :gene-validity/report-id identity
   :gene-validity/animal-model identity
   :genetic-condition/actionability-curations identity
   :genetic-condition/actionability-assertions identity
   :genetic-condition/disease identity
   :genetic-condition/gene identity
   :genetic-condition/gene-dosage-curation identity
   :genetic-condition/gene-validity-curation identity
   :genetic-condition/mode-of-inheritance identity
   :group/groups identity
   :mode-of-inheritance/modes-of-inheritance identity
   :region-feature/chromosomal-band identity
   :region-feature/coordinates identity
   :resource/alternative-label identity
   :resource/curie identity
   :resource/iri identity
   :resource/label identity
   :resource/website-display-label identity
   :resource/type identity
   :server-status/migration-version identity
   :server-status/server-version-query identity
   :suggest/curations identity
   :suggest/curie identity
   :suggest/alternative-curie identity
   :suggest/highlighted-text identity
   :suggest/iri identity
   :suggest/suggest identity
   :suggest/suggest-type identity
   :suggest/text identity
   :suggest/weight identity
   :user/user-query identity
   :user/current-user identity
   :user/email identity
   :user/is-admin identity
   :user/member-of identity})

(defn read-resource-edn [resource]
  (-> resource io/resource slurp edn/read-string))


;; TODO pickup here
;; analyzing historic queries to see how much of the legacy schema needs to be updated
;; for genegraph 2
;; attempting also to parse the query and see if I can get the fields out as structured data
;; though maybe running some of these queries against genegraph 1 is the smarter play?
;; could get the output and look at the fields in those, possibly with greater ease than
;; understanding Lacinia output requires.
(comment
  (kafka/topic->event-file
   {:name :genegraph-logs
    :type :kafka-reader-topic
    :kafka-cluster dx-ccloud
    :kafka-topic "genegraph_logs"}
   "/users/tristan/data/genegraph-neo/genegraph-logs_2024-02-14.edn.gz")

  

  (data/diff (read-resource-edn "graphql-schema-for-merge.edn")
             (read-resource-edn "graphql-schema.edn"))

  (def schema-for-merge
    (read-resource-edn "graphql-schema-for-merge.edn"))

  (tap> schema-for-merge)
  
  (defn reduce-names [field]
    (if-let [selections (:selections field)] 
      (conj (mapcat reduce-names selections) (:qualified-name field))
      [(:qualified-name field)]))
  
  (def schema
    (-> "graphql-schema.edn"
        io/resource
        slurp
        edn/read-string
        (util/attach-resolvers resolver-map-identity)
        schema/compile))

  (def queries
    (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/genegraph-logs_2024-02-14.edn.gz"]
      (->> (event-store/event-seq r)
           (map (fn [x]
                  (try
                    (-> x
                        ::event/value
                        (subs 59)
                        edn/read-string
                        :servlet-request-body
                        (json/read-str :key-fn keyword)
                        :query)
                    (catch Exception e nil))))
           (remove nil?)
           (mapcat #(try
                      (->> (:selections (parser/parse-query schema %))
                           (map :qualified-name))
                      (catch Exception e [])))
           set)))

  (pprint queries)

  (def used-fields
    (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/genegraph-logs_2024-02-14.edn.gz"]
      (->> (event-store/event-seq r)
           (map (fn [x]
                  (try
                    (-> x
                        ::event/value
                        (subs 59)
                        edn/read-string
                        :servlet-request-body
                        (json/read-str :key-fn keyword)
                        :query)
                    (catch Exception e nil))))
           (remove nil?)
           (take 1)
           (mapcat (fn [s]
                     (try
                       (->> (parser/parse-query schema s))
                       (catch Exception e []))))
           (into #{})
           
           tap>
           #_(run! println)
           )))
  
  (tap> (sort used-fields))

  (update-vals (group-by #(keyword (namespace %)) used-fields)
               (fn [kws] (map #(keyword (name %)) kws)))

  (def schema-fields
    (set (reduce (fn [a [obj m]]
                   (concat (map #(keyword (name obj) (name %))
                                (keys (:fields m)))
                           a))
                 []
                 (:objects schema-for-merge))))

  (tap> (sort (s/difference schema-fields used-fields)))

  (def used-resolvers
    (set (map #(get-in schema-for-merge
                       [:objects
                        (keyword (namespace %))
                        :fields
                        (keyword (name %))
                        :resolve])
              used-fields)))

  (def all-resolvers (set (keys resolver-map)))

  (def unused-resolvers (s/difference all-resolvers used-resolvers))

  (take 5 unused-resolvers)

  (def used-resolvers
    (set (map #(get-in schema-for-merge
                       [:objects
                        (keyword (namespace %))
                        :fields
                        (keyword (name %))
                        :resolve])
              used-fields)))

  (type (first {:a :a}))

  (spit
   "/users/tristan/code/genegraph-gene-validity/resources/new-graphql-schema-for-merge.edn"
   (with-out-str
     (pprint 
      (walk/postwalk
       (fn [x]
         (if (map? x)
           (into {} (remove #(unused-resolvers (:resolve (val %))) x))
           x))
       schema-for-merge))))

  (pprint (sort used-resolvers))
  
  (count (sort (s/difference all-resolvers used-resolvers)))

  (pprint (apply sorted-map (flatten (seq (select-keys resolver-map used-resolvers)))))

  (spit "/users/tristan/data/genegraph-neo/used-fields.edn" (pr-str used-fields))
  (def used-fields (edn/read-string (slurp "/users/tristan/data/genegraph-neo/used-fields.edn")))
  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/genegraph-logs_2024-02-14.edn.gz"]
    (->> (event-store/event-seq r)
         first
         ))
  
  )


(comment
  (kafka/topic->event-file
   {:name :genegraph-logs
    :type :kafka-reader-topic
    :kafka-cluster dx-ccloud
    :serialization :json
    :kafka-topic "gene_validity"}
   "/users/tristan/data/genegraph-neo/gene-validity-legacy_2024-02-20.edn.gz")
  )


(comment
  (def c (hc/build-http-client {:connect-timeout 100
                                :redirect-policy :always
                                :timeout (* 1000 60 10)}))
  (hc/post "https://genegraph.prod.clingen.app/api"
           {:http-client c
            :content-type :json
            :body (json/write-str {:query "
query($gene:String) {
  genes(text: $gene) {
    gene_list {
      label
      curie
    }
  }
}"
                                   :variables {:gene "ZEB2"}})})

  (defn genegraph-request [query]
      (hc/post "https://genegraph.prod.clingen.app/api"
           {:http-client c
            :content-type :json
            :body query}))

  (defn local-request [query]
      (hc/post "http://localhost:8888/api"
           {:http-client c
            :content-type :json
            :body query}))
  
  (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/genegraph-logs_2024-02-14.edn.gz"]
    (->> (event-store/event-seq r)
         #_(drop 11)
         (map (fn [x]
                (try
                  (-> x
                      ::event/value
                      (subs 59)
                      edn/read-string
                      :servlet-request-body)
                  (catch Exception e nil))))
         (remove #(or (nil? %) (re-find #"null" %)))
         (map (fn [x]
                (try
                  {:query x
                   :response (local-request x)}
                  (catch Exception e {:exception e}))))
         (take 1)
         (into [])
         (map #(println (get (json/read-str (:query %)) "query")))))

  

  )


(comment
  (jvma/analyze `(identity [:report :rdf/type :sepio/ActionabilityReport]))
  (jvma/analyze-ns 'genegraph.gene-validity.actionability)
  (s/difference
   (set
    (map (fn [[_ ns n]] (keyword ns n))
         (re-seq
          #":([A-Za-z-]+)/([A-Za-z-]+)"
          (-> "genegraph/gene_validity/dosage.clj"
              io/resource
              slurp))))
   (set
    (keys (:keyword-mappings @names/global-aliases))))
  )


(comment
  (portal/clear)
  (def hgnc->entrez
    (with-open [r (io/reader "/users/tristan/data/genegraph-neo/base/hgnc.json")]
      (->> (get-in (json/read r :key-fn keyword) [:response :docs])
           (reduce (fn [a x] (assoc a (:hgnc_id x) (:entrez_id x))) {}))))
  

  (with-open [r (io/reader "/users/tristan/data/genegraph-neo/ClinGen-Gene-Expess-Data-01092020.json")
              w (io/writer "/users/tristan/data/genegraph-neo/gci-express-with-entrez-ids.json")]
    (binding [*out* w]
      (json/pprint
       (->> (json/read r :key-fn keyword)
            (map (fn [[k v]]
                   [k
                    (assoc v
                           :entrez_id
                           (-> v :genes first second :curie hgnc->entrez))]))
            (into {})))))
  )


(comment

  (def gv-legacy-neo4j
    (->> "/Users/tristan/data/genegraph-neo/neo4j-legacy-events"
         io/file
         file-seq
         (filter #(.isFile %))
         (map #(-> % slurp
                   edn/read-string
                   :genegraph.sink.event/value))
         first
         tap>))

  (count (s/difference gv-legacy-neo4j gv-legacy-on-stream))
  (def gv-legacy-on-stream
    (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/gene-validity-legacy_2024-02-20.edn.gz"]
      (->> (event-store/event-seq r)
           (map #(-> % event/deserialize ::event/data))
           tap>)))

  gv-legacy-on-stream
  
  )


(portal/clear)
(comment
  (def existing-dosage 
    (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/gene-dosage_2024-02-13.edn.gz"]
      (->> (event-store/event-seq r)
           (map ::event/key)
           set)))

  (def restored-dosage
    (->> "/Users/tristan/data/genegraph-neo/gene-dosage-restored"
         io/file
         file-seq
         reverse
         (map #(re-find #"ISCA-\d+"(.getName %)))
         set))

  (s/difference existing-dosage restored-dosage)
  
  )
