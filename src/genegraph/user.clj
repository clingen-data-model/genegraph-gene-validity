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
            [clojure.data.csv :as csv]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor]
            [hato.client :as hc]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as spec])
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
           [java.io StringWriter PushbackReader File]
           [java.util.concurrent Semaphore]))

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

  (storage/restore-snapshot (get-in gv-test-app [:storage :gv-tdb]))

  (get-in gv-test-app [:storage :gv-tdb])

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
  (time (get-events-from-topic gv/gene-validity-complete-topic))
  (get-events-from-topic gv/gene-validity-raw-topic)
  (time (get-events-from-topic gv/gene-validity-legacy-complete-topic))
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
        ::event/completion-promise (promise)})
      :gene-validity/model
      rdf/pp-model)
  (tap> gv-w-cv-evidence)

  
  )

(comment
  (def a2ml1
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-07-16.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"d910a9d8"
                             (::event/value %)))
           (into []))))

  (count a2ml1)

  (-> (last a2ml1)
      event/deserialize
      tap>)
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

  (defn write-json-ld [event]
    )
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-05-13.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (map #(-> %
                   event/deserialize
                   gci-model2/add-gci-model-fn
                   gvs/add-model-fn
                   add-jsonld-fn))
         (map #(assoc % ::parsed-json-ld (json/read-str (:gene-validity/json-ld %))))
         tap>))
  
  (def cc-example
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-05-13.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"ab28e09c"
                             (::event/value %)))
           last)))


  (-> cc-example
      event/deserialize
      gci-model2/add-gci-model-fn
      gvs/add-model-fn
      add-jsonld-fn
      :gene-validity/json-ld
      json/read-str
      tap>)
  
  (portal/clear)
  ;; 34edc78f-ee22-4393-aceb-70f26ed8f35b_cc_evidence_item
  (-> cc-example
      event/deserialize
      gci-model2/add-gci-model-fn
      gvs/add-model-fn
      :gene-validity/model
      rdf/to-turtle
      println)

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-05-13.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (mapv #(-> %
                    event/deserialize
                    gci-model2/add-gci-model-fn
                    gvs/add-model-fn
                    :gene-validity/model
                    .size))))


  (def cc-assertions
    (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])
          q (rdf/create-query "
select ?c where { 
?c a :sepio/GeneValidityEvidenceLevelAssertion ;
:sepio/has-evidence * / a :sepio/0004039 .
}
")]
      (rdf/tx tdb
        (into [] (q tdb)))))

  (count cc-assertions)

  (str (first cc-assertions))

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

  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])
        query (rdf/create-query
               "
select ?source where {
?assertion :sepio/has-evidence * / :dc/source ?source
}")]
    (rdf/tx tdb
      (->> (take 1 erins-list)
           (mapv #(query tdb {:assertion %}))
           clojure.pprint/pprint)))


  (+ 1 1)
  )


;; Publishing test data to stage topic

(def test-data-publish-app-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange gv/data-exchange}
   :topics {:gene-validity-complete
            (assoc gv/gene-validity-complete-topic
                   :type :kafka-producer-topic
                   :create-producer true)
            :gene-validity-legacy-complete
            (assoc gv/gene-validity-legacy-complete-topic
                   :type :kafka-producer-topic
                   :create-producer true)}})

(comment
  (def scv-raw (-> "/users/tristan/Desktop/scv-publish-raw.json"
                   slurp
                   json/read-str))
  (def scv-legacy (-> "/users/tristan/Desktop/scv-publish.json"
                      slurp
                      json/read-str))

  (tap> scv-raw)

 

  (def test-data-publish-app (p/init test-data-publish-app-def))

  (p/start test-data-publish-app)
  (p/stop test-data-publish-app)

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_legacy_complete-2024-06-28.edn.gz"]
    (->> (event-store/event-seq r)
         (map event/deserialize)
         (run! #(p/publish (get-in test-data-publish-app [:topics :gene-validity-legacy-complete]) %))))
  (time
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-06-28.edn.gz"]
     (->> (event-store/event-seq r)
          (map event/deserialize)
          (run! #(p/publish (get-in test-data-publish-app [:topics :gene-validity-complete]) %)))))
  
  (p/publish (get-in test-data-publish-app [:topics :gene-validity-legacy-complete])
             {::event/data scv-legacy
              ::event/key "scv-test-data-1"})

  (p/publish (get-in test-data-publish-app [:topics :gene-validity-complete])
             {::event/data scv-raw
              ::event/key "scv-test-data-1"})

  
  (get-in test-data-publish-app [:topics :gene-validity-complete])
  
  

  (tap> scv-raw)
  
  

  )

;; Select test data for sepio test set

(def output-dir "/Users/tristan/data/genegraph-neo/gv-snapshot/")

(def has-assertion-query
  (rdf/create-query "select ?x where { ?x a :cg/EvidenceStrengthAssertion }"))

(defn add-jsonld-with-frame-fn [event frame]
  (assoc event
         :gene-validity/json-ld
         (jsonld/model->json-ld (:gene-validity/model event) frame)))

(defn event->jsonld [event frame]
  (-> event
      event/deserialize
      gci-model2/add-gci-model-fn
      gvs/add-model-fn
      (add-jsonld-with-frame-fn frame)))

(defn write-json-ld [event frame dir sem]
  (.acquire sem)
  (Thread/startVirtualThread
   #(let [path (str dir (::event/key event) ".json")
          processed-event (event->jsonld event frame)]
      (when (seq (has-assertion-query
                      (:gene-validity/model processed-event)))
              (spit path (:gene-validity/json-ld processed-event)))
      (.release sem))))

;; generate full snapshot
(comment
  (time
   (let [gv-jsonld-frame (jsonld/json-file->doc (io/resource "frame.json"))
         sem (Semaphore. 20)]
     (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-06-04.edn.gz"]
       (->> (event-store/event-seq r)
            (run! #(write-json-ld % gv-jsonld-frame output-dir sem))
            #_(run! #(-> %
                       (event->jsonld gv-jsonld-frame)
                       :gene-validity/model
                       rdf/to-turtle
                       println))))))

  (+ 1 1)

  )


(comment
  ;; SOP 6/7 data, SOP 8/9/10 data
  ;; All modes of inheritance for each SOP set
  ;; All different kinds of experimental data
  ;; Case control
  ;; Segregation

  ;; F5, SOP6 AD, incl CC data
  (def f5-example
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-05-13.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"ab28e09c"
                             (::event/value %)))
           last)))

  ;; ZEB2



  (def f5-example
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-06-04.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"ab28e09c"
                             (::event/value %)))
           last)))


  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-06-04.edn.gz"]
    (->> (event-store/event-seq r)
         (filter #(re-find #"ab28e09c"
                           (::event/value %)))
         last))

  (defn add-jsonld-with-frame-fn [event frame]
    (assoc event
           :gene-validity/json-ld
           (jsonld/model->json-ld (:gene-validity/model event) frame)))

  (defn event->jsonld [event frame]
    (-> event
        event/deserialize
        gci-model2/add-gci-model-fn
        gvs/add-model-fn
        (add-jsonld-with-frame-fn frame)))

  (defn write-json-ld [event frame dir]
    (let [path (str output-dir (::event/key event) ".json")]
      (println path)
      (spit (str output-dir (::event/key event) ".json")
            (event->jsonld event frame))))


  
  (let [gv-jsonld-frame (jsonld/json-file->doc (io/resource "frame.json"))]
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-06-04.edn.gz"]
      (->> (event-store/event-seq r)
           (take 3)
           (run! #(write-json-ld % gv-jsonld-frame output-dir)))))

  

  
  (->> (file-seq (io/file output-dir))
       (filter #(.isFile %))
       (map slurp)
       (filter #(or (re-find #"gain of function" %)
                    (re-find #"dominant negative" %)))
       (map #(str "https://search.clinicalgenome.org/kb/gene-validity/" (-> % json/read-str (get "id"))))
       (run! println))

  
  )

;; figure out what curations have been unpublished and never republished

(def publish-contrib-query
  (rdf/create-query "select ?x where { ?x :cg/contributions / :cg/role :cg/Unpublisher } "))

(defn add-is-publish-event [event]
  (assoc event
         :is-publish
         (not (nil? (seq (publish-contrib-query (:gene-validity/model event)))))))

(def assertion-query
  (rdf/create-query "select ?x where { ?x a :cg/EvidenceStrengthAssertion } "))

(def unpublisher-query
  (rdf/create-query "
select ?agent where {
 ?x :cg/agent ?agent ; :cg/role :cg/Unpublisher .
} "))

(defn add-gdm [event]
  (if-let [a (first (assertion-query (:gene-validity/model event)))]
    (assoc event
           :id (str (rdf/ld1-> a [:cg/subject]))
           :gene (str (rdf/ld1-> a [:cg/subject :cg/gene]))
           :disease (str (rdf/ld1-> a [:cg/subject :cg/disease]))
           :mode-of-inheritance (str (rdf/ld1-> a [:cg/subject :cg/modeOfInheritance]))
           :unpublisher (str (first (unpublisher-query (:gene-validity/model event)))))
    (assoc event :null-model (:gene-validity/model event))))

(comment
  (def publish-unpublish-results-2
    (let [gv-jsonld-frame (jsonld/json-file->doc (io/resource "frame.json"))]
      (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gv-prod-3-2024-06-04.edn.gz"]
        (->> (event-store/event-seq r)
             #_(mapv #(-> %
                          (event->jsonld gv-jsonld-frame)
                          :gene-validity/json-ld
                          json/read-str))
             (pmap #(-> %
                        (event->jsonld gv-jsonld-frame)
                        add-is-publish-event
                        add-gdm
                        (select-keys [:id :gene :disease :mode-of-inheritance :is-publish :unpublisher])))
             (into [])))))

  (count (filter :null-model publish-unpublish-results-2))

  (-> (filter :null-model publish-unpublish-results-2)
      first
      :null-model
      rdf/to-turtle
      println)

  (def last-record
    (reduce (fn [a x] (assoc a (:id x) x)) {} publish-unpublish-results-2))
  (count last-record)
  
  (def last-unpublish (filter #(:is-publish (val %))  last-record))

  (count last-unpublish)
  (first last-unpublish)

  (with-open [w (io/writer "/Users/tristan/desktop/unpublished-curations.csv")]
    (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])]
      (rdf/tx tdb
        (->> last-unpublish
             (mapv (fn [[k v]]
                     (assoc v
                            :gene-label (rdf/ld1-> (rdf/resource (:gene v) tdb)
                                                   [[:owl/sameAs :<]
                                                    :skos/prefLabel])
                            :disease-label (rdf/ld1-> (rdf/resource (:disease v) tdb)
                                                      [:rdfs/label])
                            :moi-label  (rdf/ld1-> (rdf/resource (:mode-of-inheritance v) tdb)
                                                   [:rdfs/label])
                            :ep (rdf/ld1-> (rdf/resource (:unpublisher v) tdb)
                                           [:rdfs/label]))))
             (mapv (fn [{:keys [disease
                                disease-label
                                gene
                                gene-label
                                mode-of-inheritance
                                moi-label
                                ep
                                id]}]
                     [disease
                      disease-label
                      gene
                      gene-label
                      mode-of-inheritance
                      moi-label
                      ep
                      (str "https://curation.clinicalgenome.org/curation-central/"
                           (subs id 43))]))
             (csv/write-csv w)))))

  
  (count "e1002cfc-5e0c-4311-81d8-dffee7394021")
  (count "http://dataexchange.clinicalgenome.org/gci/e1002cfc-5e0c-4311-81d8-dffee7394021")
  (- 79 36)
  (str
   "https://curation.clinicalgenome.org/curation-central/"
   (subs "http://dataexchange.clinicalgenome.org/gci/e1002cfc-5e0c-4311-81d8-dffee7394021" 43))
  )


;; Troubleshooting GV-Legacy data missing
(comment
  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])
        q (rdf/create-query "
select ?x where { 
?x :bfo/has-part / :cnt/chars ?c .
}
")]
    (rdf/tx tdb
      (into [] (q tdb))))

  (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])
        q (rdf/create-query "
select ?x where { 
?x :cnt/chars ?c .
}
")]
    (rdf/tx tdb
      (count (q tdb))))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gvl-stage-3-2024-06-07.edn.gz"]
    (->> (event-store/event-seq r)
         (map event/deserialize)
         (run! #(p/publish (get-in gv-test-app [:topics :gene-validity-legacy-complete]) %))))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-gvl-stage-3-2024-06-07.edn.gz"]
    (->> (event-store/event-seq r)
         (take 5)
         (mapv event/deserialize)
         tap>))
  
  )

(defn gene-set [result]
  (->> (-> result
          :body
          (json/read-str :key-fn keyword)
          :data
          :genes
          :gene_list)
       set))

;; troubleshooting gene list discrepancy
(comment
  (def c (hc/build-http-client {:connect-timeout 100
                                :redirect-policy :always
                                :timeout (* 1000 60 10)}))

  (def genes-query
    "
{
  genes(curation_activity: ALL, limit: null) {
    count
    gene_list {
      label
      curie
    }
  }
}")
  (def prod-result
    (hc/post "https://genegraph.prod.clingen.app/api"
             {:http-client c
              :content-type :json
              :body (json/write-str {:query genes-query})}))

  (def stage-result
    (hc/post "https://genegraph-gene-validity.stage.clingen.app/api"
             {:http-client c
              :content-type :json
              :body (json/write-str {:query genes-query})}))

  (clojure.pprint/pprint
   (set/difference (gene-set prod-result)
                   (gene-set stage-result)))

    (def gv-query
    "
{
  genes(curation_activity: GENE_VALIDITY, limit: null) {
    count
    gene_list {
      label
      curie
    }
  }
}")

    (def prod-gv-result
      (hc/post "https://genegraph.prod.clingen.app/api"
               {:http-client c
                :content-type :json
                :body (json/write-str {:query gv-query})}))

    (def stage-gv-result
      (hc/post "https://genegraph-gene-validity.stage.clingen.app/api"
               {:http-client c
                :content-type :json
                :body (json/write-str {:query gv-query})}))

    (tap>
     (set/difference (gene-set stage-gv-result)
                     (gene-set prod-gv-result)))

    (tap> prod-result)
  )



(comment
  (spit "/users/tristan/Desktop/missing-dosage-curations.ttl"
        (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])
              q (rdf/create-query "
select ?x where { 
?x a :sepio/GeneDosageReport .
} 
")]
          (rdf/tx tdb
            (let [in-db (->> (q tdb) (map #(re-find #"ISCA-\d+"(str %))) set)]
              (->> "/Users/tristan/data/genegraph/2023-11-07T1617/events/:gene-dosage-restored"
                   io/file
                   file-seq
                   (filter #(and (.isFile %)
                                 (not (in-db (re-find #"ISCA-\d+"(.getName %))))))
                   #_(take 1)
                   (mapv (fn [f]
                           (with-open [r (PushbackReader. (io/reader f))]
                             (:genegraph.sink.event/value (edn/read r)))))
                   (reduce str ""))))))

  (def missing-gd
    (let [tdb @(get-in gv-test-app [:storage :gv-tdb :instance])
          q (rdf/create-query "
select ?x where { 
?x a :sepio/GeneDosageReport .
} 
")]
      (rdf/tx tdb
        (let [in-db (->> (q tdb) (map #(re-find #"ISCA-\d+"(str %))) set)]
          (->> "/Users/tristan/data/genegraph/2023-11-07T1617/events/:gene-dosage-restored"
               io/file
               file-seq
               (filter #(.isFile %))
               (map #(re-find #"ISCA-\d+"(.getName %)))
               (remove in-db)
               set)))))

  (def missing-gd-events
   (->> (tree-seq
         #(.isDirectory %)
         #(.listFiles %)
         (io/file "/Users/tristan/data/gene-dosage-topic-data"))
        (filter #(and (.isFile %)
                      (re-find #"json$" (.getName %))))
        #_(take 50)
        (mapcat (fn [f]
                  (with-open [r (io/reader f)]
                    (mapv #(json/read-str % :key-fn keyword) (line-seq r)))))
        (filter #(missing-gd (:key %)))
        (into [])))

  (defn process-gd-event [e]
    (p/process (get-in gv-test-app [:processors :import-dosage-curations])
               {::event/data e
                ::event/completion-promise (promise)
                ::event/skip-local-effects true
                ::event/skip-publish-effects true}))

  #_(->> missing-gd-events
       (map process-gd-event)
       (remove ::spec/invalid)
       count)

  
  
  )
