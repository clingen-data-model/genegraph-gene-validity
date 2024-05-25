(ns genegraph.user
  (:require [genegraph.framework.protocol]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.event :as event]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rdf.jsonld :as jsonld]
            [genegraph.framework.event.store :as event-store]
            [genegraph.gene-validity :as gv]
            [genegraph.gene-validity.gci-model :as gci-model]
            [genegraph.gene-validity.gci-model2 :as gci-model2]
            [genegraph.gene-validity.sepio-model2 :as gvs]
            [genegraph.gene-validity.graphql.response-cache :as response-cache]
            [portal.api :as portal]
            [clojure.data.json :as json]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor]
            [hato.client :as hc]
            [clojure.java.io :as io])
  (:import [java.time Instant LocalDate]
           [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [org.apache.jena.riot RDFDataMgr Lang]
           [org.apache.jena.riot.system JenaTitanium]
           [org.apache.jena.rdf.model Model Statement]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.sparql.core DatasetGraph]
           [com.apicatalog.jsonld.serialization RdfToJsonld]
           [com.apicatalog.jsonld.document Document RdfDocument]
           [com.apicatalog.rdf Rdf]
           [com.apicatalog.rdf.spi RdfProvider]
           [jakarta.json JsonObjectBuilder Json]
           [java.io StringWriter]))

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

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-1-2024-05-03.edn.gz"]
    (->> (event-store/event-seq r)
         count))

  ;; 4463

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-05-13.edn.gz"]
    (->> (event-store/event-seq r)
         count))

  ;; 4485

  

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
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv_events_complete_2024-03-12.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"d35ff1da-7306-43ef-9fc4-9841e6c000d7"
                             (::event/value %)))
           (into []))))

  (count smarcb1)

  (def smarcb1-1
    (-> smarcb1
        first
        (assoc ::event/format :json)
        event/deserialize))
  
  
  (defn extract-functional-alteration [e]
    (assoc e ::fa-data
           (->> (get-in (-> e (assoc ::event/format :json) event/deserialize)
                        [::event/data :resourceParent :gdm :annotations])
                (filter :experimentalData)
                (mapcat :experimentalData)
                (filter :functionalAlteration))))

  (tap> (extract-functional-alteration smarcb1-1))

  (def fa-events
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv_events_complete_2024-03-12.edn.gz"]
      (->> (event-store/event-seq r)
           #_(take 100)
           (pmap extract-functional-alteration)
           (filter #(seq (::fa-data %)))
           (into []))))

  (def fa-map
    (->> fa-events
         (mapcat ::fa-data)
         (reduce (fn [m v] (assoc m (or (:PK v) (:uuid v)) v)))))

  (->> fa-map
       vals
       (filter #(get-in % [:functionalAlteration :normalFunctionOfGene]))
       count)

  (->> fa-map
       vals
       (filter #(get-in % [:functionalAlteration :normalFunctionOfGeneFreeText]))
       count)

  (->> fa-map
       vals
       (map #(get-in % [:functionalAlteration :normalFunctionOfGeneFreeText]))
       (remove nil?)
       frequencies
       (filter #(< 2 (val %))))

  (->> fa-map
       vals
       (remove
        #(or (get-in % [:functionalAlteration :normalFunctionOfGeneFreeText])
             (get-in % [:functionalAlteration :normalFunctionOfGene])))
       first
       tap>)

  (first fa-map)

  (portal/clear)
  
  )

;; Clearing unused topics from Kafka clusters

(comment

  (with-open [admin-client (kafka-admin/create-admin-client gv/data-exchange)]
    (run! #(try
             (kafka-admin/delete-topic admin-client %)
             (catch Exception e
               (log/info :msg "Exception deleting topic "
                         :topic %)))
          ["gene_validity_complete-v1"
           "gene_validity_sepio-v1"
           "gene-validity-legacy-complete-v1"
           "genegraph_api_log-v1"
           "genegraph-api-log-stage-v1"
           "genegraph-base-data-stage-v1"
           "geengraph-base-v1"
           "genegraph-fetch-base-events-v1"
           "genegraph-fetch-base-stage-v1"
           "genegraph-gene-validity-complete-stage-v1"
           "genegraph-gene-validity-legacy-complete-stage-v1"
           "genegraph-gene-validity-sepio-stage-v1"
           "gg-apilog-stage-1"
           "gg-base-stage-1"
           "gg-fb-stage-1"
           "gg-gv-stage-1"
           "gg-gv-stage-2"
           "gg-gvl-stage-1"
           "gg-gvl-stage-2"
           "gg-gvs-stage-1"
           "gg-gvs-stage-2"]))

  )


;; Looking at variant info for GV variants

(comment

  ;; consider ranking only strong +
  ;; consider looking at scoring of variants, at least 1+
  ;; consider looking at balance of variants (other vs null)
  
  (def lof-ad-gv
    (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])
          q (rdf/create-query "
select ?c where { 
?c a :sepio/GeneValidityEvidenceLevelAssertion ;
:sepio/has-subject / :sepio/has-qualifier ?moi ;
:sepio/has-subject / :sepio/has-subject ?gene ;

:sepio/has-evidence * ?el .
{ ?c :sepio/has-object :sepio/StrongEvidence }
UNION  
{ ?c :sepio/has-object :sepio/DefinitiveEvidence }
?el :sepio/is-about-allele ?v ;
a :sepio/NullVariantEvidenceItem .
?v a :ga4gh/VariationDescriptor 
FILTER NOT EXISTS 
{
?gdp :geno/has-location ?gene ;
a :geno/FunctionalCopyNumberComplement . }
}
")]
      (rdf/tx tdb
        #_(mapv #(rdf/ld1-> % [:ga4gh/CanonicalReference])
                (q tdb {:moi :hp/AutosomalDominantInheritance}))
        (into [] (q tdb {:moi :hp/AutosomalDominantInheritance})))))

 
  (count lof-ad-gv)

  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (->> lof-ad-gv
           (mapv #(rdf/ld1-> % [:sepio/has-subject
                                :sepio/has-subject
                                :skos/prefLabel]))
           clojure.pprint/pprint)))

  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])
        q (rdf/create-query "
select ?gdp where {
?gdp :geno/has-location ?gene ;
a :geno/FunctionalCopyNumberComplement . }
")]
    (rdf/tx tdb
      #_(mapv #(rdf/ld1-> % [:ga4gh/CanonicalReference])
              (q tdb {:moi :hp/AutosomalDominantInheritance}))
      (count (into [] (q tdb )))))
    

    (count lof-ad-gv)

  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (->> lof-ad-gv
           (map ))
      ))
  
  (count lof-ad-gv)

  (def http-client (hc/build-http-client {}))
  (tap>
   (-> (hc/get "http://reg.clinicalgenome.org/allele?hgvs=NC_000010.11:g.87894077C>T"
              {:http-client http-client})
       :body
       (json/read-str :key-fn keyword))

)
  (tap>
      (-> (hc/get "http://reg.clinicalgenome.org/allele?hgvs=NM_014795.4:c.2761C>T"
               {:http-client http-client})
       :body
       (json/read-str :key-fn keyword)))

  (tap>
   (-> (hc/get "http://reg.genome.network/allele/CA000318"
               {:http-client http-client})
       :body
       (json/read-str :key-fn keyword)))
  (tap>
   (-> (hc/get "https://myvariant.info/v1/variant/chr10:g.87894077C%3ET?assembly=hg38"
               {:http-client http-client})
       :body
       (json/read-str :key-fn keyword)))

  (tap>
   (-> (hc/get "https://rest.ensembl.org/vep/human/hgvs/ENSP00000401091.1:p.Tyr124Cys?content-type=application/json"
               {:http-client http-client})
       :body
       (json/read-str :key-fn keyword)))

  (tap>
   (-> (hc/get "https://rest.ensembl.org/vep/human/hgvs/NM_014795.4:c.2761C>T?content-type=application/json"
               {:http-client http-client})
       :body
       (json/read-str :key-fn keyword)))
  "https://rest.ensembl.org/documentation/info/vep_hgvs_post"

  "NM_014795.4:c.2761C>T"
  "http://reg.test.genome.network/allele?hgvs=NC_000010.11:g.87894077C>T"

 "http://myvariant.info/v1/variant/chr2:g.144398426G>A?assembly=hg38"
  )





;; Completing versioning
(comment

  (do
    (def gv-assertion-query
      (rdf/create-query
       "select ?x where { ?x a :sepio/GeneValidityEvidenceLevelAssertion }"))
    
    (defn version-model [{::keys [assertion-iri] :as event}]
      (rdf/statements->model
       [[assertion-iri :cg/majorVersion (get-in event
                                            [:gene-validity/version :major])]
        [assertion-iri :cg/minorVersion (get-in event
                                            [:gene-validity/version :minor])]]))

    (defn source-event [{::keys [assertion-iri] :as event}]
      (rdf/statements->model
       [[assertion-iri
         :cg/sourceTopic
         (rdf/resource (str
                        "http://dataexchange.clinicalgenome.org/topic/"
                        (::event/kafka-topic  event "no-topic")))]
        [assertion-iri :cg/sourceOffset (::event/offset event -1)]]))

    (defn add-version-model [event]
      (let [event-with-assertion
            (assoc event
                   ::assertion-iri
                   (first (gv-assertion-query (:gene-validity/model event))))]
        (assoc event :gene-validity/versioned-model
               (rdf/union (:gene-validity/model event)
                          (version-model event-with-assertion)
                          (source-event event-with-assertion)))))

    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-05-13.edn.gz"]
      (->> (event-store/event-seq r)
           (take 1)
           (mapv #(p/process
                   (get-in gv-test-app [:processors :gene-validity-transform])
                   (assoc %
                          ::event/skip-local-effects true
                          ::event/skip-publish-effects true
                          ::event/completion-promise (promise))))
           (mapv add-version-model)
           tap>)))
  
  

  )


;; SEPIO Model v2
(comment

  (defn add-jsonld-fn [event]
    (assoc event
           :gene-validity/json-ld
           (jsonld/model->json-ld
            (:gene-validity/model event)
            (jsonld/json-file->doc (io/resource "frame.json")))))


  (portal/clear)
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-05-13.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (mapv #(-> %
                    event/deserialize
                    gci-model2/add-gci-model-fn
                    gvs/add-model-fn
                    add-jsonld-fn
                    :gene-validity/json-ld
                    json/read-str))
         tap>))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-05-13.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (mapv #(-> %
                    event/deserialize
                    gci-model2/add-gci-model-fn
                    gvs/add-model-fn
                    :gene-validity/model))
         (run! rdf/pp-model)))



  )

;; Responding to request from Erin:

;; Tristan: Marina and I are hoping you can help us hone in on some curations that may be useful to us as we continue to think through curation methods for mechanism of disease.

;; Can you identify for us the list of genes that have a Dosage HI score of 1, a Gene-Disease Validity classification of Moderate or higher (for an AD or XL condition), AND at least one item scored in the category "Model Systems: Non-Human Organism"?

;; Let us know if you have any questions and how feasible this may be,
;; Erin


(comment
  (def erins-list
    (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])
          q (rdf/create-query "
select ?c where { 
?c a :sepio/GeneValidityEvidenceLevelAssertion ;
:sepio/has-subject / :sepio/has-subject ?gene ;
:sepio/has-evidence * ?el .
?el a <http://purl.obolibrary.org/obo/SEPIO_0004046> .
{ ?c :sepio/has-object :sepio/StrongEvidence }
UNION  
{ ?c :sepio/has-object :sepio/DefinitiveEvidence }
UNION
{ ?c :sepio/has-object :sepio/ModerateEvidence }
UNION
{ ?c :sepio/has-subject / :sepio/has-qualifier :hp/AutosomalDominantInheritance }
UNION
{ ?c :sepio/has-subject / :sepio/has-qualifier :hp/XLinkedInheritance }
?gdv :geno/has-location ?gene ;
:geno/has-member-count ?count ;
a :geno/FunctionalCopyNumberComplement .
FILTER ( ?count = 1 ) .
?gdp :sepio/has-subject ?gdv .
?gda :sepio/has-subject ?gdp ;
:sepio/has-object :sepio/DosageMinimalEvidence .
}
")]
      (rdf/tx tdb
        (into [] (q tdb )))))

  (count erins-list)

  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (->> erins-list
           (mapv #(rdf/ld1-> % [:sepio/has-subject
                                :sepio/has-subject
                                :skos/prefLabel]))
           clojure.pprint/pprint)))


    (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb
      (->> erins-list
           (mapv #(rdf/ld1-> % [:geno/has-member-count]))
           clojure.pprint/pprint)))

  

  (+ 1 1)
  )
