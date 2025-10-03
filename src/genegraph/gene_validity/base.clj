(ns genegraph.gene-validity.base
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage.gcs :as gcs]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.app :as app]
            [genegraph.framework.processor :as processor]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hato.client :as hc]
            ;; may not need instance
            [genegraph.framework.storage.rdf.instance :as tdb-instance]
            [genegraph.gene-validity.base.gene :as gene]
            [genegraph.gene-validity.base.affiliations]
            [genegraph.gene-validity.base.features]
            [genegraph.gene-validity.base.ucsc-cytoband]
            [genegraph.gene-validity.base.gci-express]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log])
  (:import [java.io File InputStream OutputStream]
           [java.nio.channels Channels]))

;; formats
;; RDF: RDFXML, Turtle, JSON-LD
;; ucsc-cytoband
;; affiliations
;; loss-intolerance
;; hi-index
;; features
;; genes
;;

(def base-files-to-load
  #{"http://dataexchange.clinicalgenome.org/gci-express"
    "http://purl.obolibrary.org/obo/so.owl"
    "http://www.ebi.ac.uk/efo"
    "http://www.w3.org/ns/sparql-service-description#"
    "http://purl.org/dc/terms/"
    "http://dataexchange.clinicalgenome.org/missing-dosage-curations"
    "http://purl.obolibrary.org/obo/iao/ontology-metadata.owl"
    "https://www.genenames.org/"
    "http://purl.obolibrary.org/obo/mondo.owl"
    "http://www.w3.org/2004/02/skos/core"
    #_"https://affils.clinicalgenome.org/"
    "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    "http://www.w3.org/2011/content#"
    "http://purl.obolibrary.org/obo/bfo.owl"
    "http://dataexchange.clinicalgenome.org/affiliations"
    "http://purl.obolibrary.org/obo/hp.owl"
    "https://www.w3.org/2002/07/owl"
    "http://purl.obolibrary.org/obo/ro.owl"
    "http://www.w3.org/2000/01/rdf-schema#"})

(defn output-handle [event]
  (-> event
      ::handle
      (assoc :path (get-in event [::event/data :target]))))

(defn success? [{status ::http-status}]
  (and (<= 200 status) (< status 400)))

(defn fetch-file-fn [event]
  (let [response (hc/get (get-in event [::event/data :source])
                         {:http-client (hc/build-http-client {:redirect-policy :always})
                          :as :stream})]
    (when (instance? InputStream (:body response))
      (with-open [os (io/output-stream (storage/as-handle (output-handle event)))]
        (.transferTo (:body response) os)))
    (assoc event
           ::http-status (:status response))))

(def fetch-file
  (interceptor/interceptor
   {:name ::fetch-file
    :enter (fn [e] (fetch-file-fn e))}))

(defn publish-base-file-fn [event]
  (event/publish event {::event/topic :base-data
                        ::event/key (get-in event [::event/data :name])
                        ::event/data (-> event
                                         ::event/data
                                         (assoc :source (output-handle event)))}))

(def publish-base-file
  (interceptor/interceptor
   {:name ::publish-base-file
    :enter (fn [e] (publish-base-file-fn e))}))

(def fs-handle
  {:type :file
   :base "/users/tristan/data/genegraph-neo/new-base/"})

(def gcs-handle
  {:type :gcs
   :bucket "genegraph-framework-dev"
   :base "new-base/"})

(defn read-base-data-fn [event]
  (log/info :fn ::read-base-data-fn :key (::event/key event))
  (if (base-files-to-load (::event/key event))
    (assoc event ::model (rdf/as-model (::event/data event)))
    event))

(def read-base-data
  (interceptor/interceptor
   {:name ::read-base-data
    :enter (fn [e] (read-base-data-fn e))}))

(defn store-model-fn [event]
  (if (::model event)
    (do
      (log/info :fn ::store-model-fn :key (::event/key event))
      (event/store event
                   :gv-tdb
                   (get-in event [::event/data :name])
                   (::model event)))
    event))

(def store-model
  (interceptor/interceptor
   {:name ::store-model
    :enter (fn [e] (store-model-fn e))}))

(comment
  ;; publish all rdf-serialized
  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(isa? (:format %) ::rdf/rdf-serialization))
       (filter #(= "http://purl.obolibrary.org/obo/sepio.owl" (:name %)))
       (map (fn [x] {::event/data x
                     ::handle fs-handle}))
       (run! #(p/publish (get-in test-app [:topics :fetch-base-events]) %)))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(isa? (:format %) ::hgnc))
       (map (fn [x] {::event/data x
                     ::handle fs-handle}))
       (run! #(p/publish (get-in test-app [:topics :fetch-base-events]) %)))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(isa? (:format %) ::ucsc-cytoband))
       (map (fn [x] {::event/data x
                     ::handle fs-handle}))
       (run! #(p/publish (get-in test-app [:topics :fetch-base-events]) %)))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(isa? (:format %) ::affiliations))
       (map (fn [x] {::event/data x
                     ::handle fs-handle}))
       (run! #(p/publish (get-in test-app [:topics :fetch-base-events]) %)))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(isa? (:format %) ::features))
       (map (fn [x] {::event/data x
                     ::handle fs-handle}))
       (run! #(p/publish (get-in test-app [:topics :fetch-base-events]) %)))

  (def test-app
    (p/init
     {:type :genegraph-app
      :topics {:fetch-base-events
               {:name :fetch-base-events
                :type :simple-queue-topic}
               :base-data
               {:name :base-data
                :type :simple-queue-topic}}
      :storage {:gv-tdb
                {:type :rdf
                 :name :gv-tdb
                 :path "/users/tristan/data/genegraph-neo/gv_tdb"}}
      :processors {:fetch-base-file
                   {:name :fetch-base-file
                    :type :processor
                    :subscribe :fetch-base-events
                    :interceptors `[fetch-file
                                    publish-base-file]}
                   :import-base-file
                   {:name :import-base-file
                    :type :processor
                    :subscribe :base-data
                    :interceptors `[read-base-data
                                    store-model]}}}))

  (p/start test-app)
  (p/stop test-app)

  (def test-event
    {::event/data
     {:name "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
      :source "http://www.w3.org/1999/02/22-rdf-syntax-ns.ttl",
      :target "rdf.ttl",
      :format ::rdf/turtle}
     ::handle fs-handle})

  (def test-base-event
    {::event/data {:name "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                   :source (assoc fs-handle :path "rdf.ttl")
                   :format ::rdf/turtle}})

  (p/publish (get-in test-app [:topics :fetch-base-events]) test-event)

  (.size
   (rdf/as-model {:source (assoc fs-handle :path "ucsc_cytoband_hg38.txt.gz")
                  :format ::ucsc-cytoband
                  :name
                  "http://hgdownload.cse.ucsc.edu/goldenPath/hg19/database/cytoBand.txt.gz"
                  :genegraph.gene-validity.base.ucsc-cytoband/assembly :hg38}))

  (let [db @(get-in test-app [:storage :gv-tdb :instance])]
    db
    (rdf/tx db
            ((rdf/create-query "select ?x where { ?x a :so/Gene } limit 5")
             db)))

  (let [db @(get-in test-app [:storage :gv-tdb :instance])]
    db
    (rdf/tx db
            ((rdf/create-query "select ?x where { ?x a :so/SequenceFeature } limit 5")
             db)))

  (let [db @(get-in test-app [:storage :gv-tdb :instance])]
    db
    (rdf/tx db
            ((rdf/create-query "select ?x where { ?x a :cg/Affiliation } limit 5")
             db)))

  (let [db @(get-in test-app [:storage :gv-tdb :instance])]
    db
    (rdf/tx db
            ((rdf/create-query "select ?x where { ?x a :geno/SequenceFeatureLocation } limit 5")
             db)))

  (processor/process-event (get-in test-app [:processors :import-base-file])
                           test-base-event)

  )


