(ns genegraph.gene-validity.actionability
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage :as storage]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor])
  (:import [org.apache.jena.rdf.model Model]))

(spec/def :condition/iri #(re-matches #"http://purl\.obolibrary\.org/obo/MONDO_\d+" %))

(spec/def ::iri #(re-find #"^https://actionability\.clinicalgenome\.org/ac" %))

#_(spec/def ::gene #(re-matches #"HGNC:\d+" %))

(spec/def ::statusFlag #(#{"Released" "Released - Under Revision" "Retracted"} %))

(spec/def ::condition
  (spec/keys :req-un [:condition/iri #_::gene]))

(spec/def ::conditions
  (spec/coll-of ::condition))

(spec/def ::name string?)

(spec/def ::affiliation
  (spec/keys :req-un [::name]))

(spec/def ::affiliations
  (spec/coll-of ::affiliation))

(spec/def ::curation
  (spec/keys :req-un [::statusFlag ::conditions ::affiliations]))

(def vocab
  {"Definitive Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003535"
   "Strong Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003536"
   "Moderate Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003537"
   "Limited Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003538"
   "Insufficient Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003539"
   "Insufficient Evidence" "http://purl.obolibrary.org/obo/SEPIO_0003539"
   "N/A - Insufficient evidence: expert review" "http://purl.obolibrary.org/obo/SEPIO_0003542"
   "N/A - Insufficient evidence: early rule-out" "http://purl.obolibrary.org/obo/SEPIO_0003539"
   "No Actionability" "http://purl.obolibrary.org/obo/SEPIO_0003540"
   "Assertion Pending" "http://purl.obolibrary.org/obo/SEPIO_0003541"
   "Pediatric AWG" "http://dataexchange.clinicalgenome.org/terms/PediatricActionabilityWorkingGroup"
   "Adult AWG" "http://dataexchange.clinicalgenome.org/terms/AdultActionabilityWorkingGroup"})

#_(defn gene-resource [gene-str]
  (rdf/resource gene-str))

;; http://reg.genome.network/allele/CA321211
(defn allele-resource [id]
  (rdf/resource (str "http://reg.genome.network/allele/" id)))

(defn variant-iri [{:keys [variantId id]}]
  (cond
    (seq id) (allele-resource id)
    (seq variantId) (allele-resource variantId)
    :else (rdf/blank-node)))

(defn variant-record [assertion iri]
  [[iri :rdf/type :so/Allele]
   [iri :rdfs/label (first (:descriptor assertion))]])

(comment
  (mapv
   variant-record
   [{:description  "Lorem Ipsum",
     :descriptor ["GRCh38 Xp22.31(chrX:7195267-7337218)x2"],
     :ontology "MONDO",
     :type "Copy Number Variant",
     :curie "MONDO:0016287",
     :id "CACN1537911790",
     :iri "http://purl.obolibrary.org/obo/MONDO_0016287",
     :uri "MONDO0016287",
     :assertion "Strong Actionability"}
    {:description "Other test",
     :variantId "CA012732",
     :descriptor ["NC_000014.9:g.23424840G>A"],
     :ontology "MONDO",
     :type "Other",
     :curie "MONDO:0016297",
     :iri "http://purl.obolibrary.org/obo/MONDO_0016297",
     :uri "MONDO0016297",
     :assertion "Strong Actionability"}
    {:curie "MONDO:0016298",
     :description "test",
     :descriptor ["GRCh38 (chrX:add)"],
     :iri "http://purl.obolibrary.org/obo/MONDO_0016298",
     :ontology "MONDO",
     :type "Aneuploidy", 
     :uri "MONDO0016298", 
     :variantId ""}
    {:description "Aneuploidy test",
     :descriptor ["47,XXY"],
     :ontology "MONDO",
     :type "Aneuploidy",
     :curie "MONDO:0016298",
     :id "",
     :iri "http://purl.obolibrary.org/obo/MONDO_0016298",
     :uri "MONDO0016298",
     :assertion "Moderate Actionability"}]))

(defn genetic-condition [curation-iri condition]
  (if-let [condition-resource (rdf/resource (:iri condition))]
    (let [gc-node (rdf/blank-node)
          condition-triples [[curation-iri :sepio/is-about-condition gc-node]
                             [gc-node :rdf/type :sepio/GeneticCondition]
                             [gc-node :rdf/type :cg/ActionabilityGeneticCondition]
                             [gc-node :rdfs/subClassOf condition-resource]]]
      (if-let [gene (:gene condition)]
        (conj condition-triples [gc-node :sepio/is-about-gene (rdf/resource gene)])
        (let [v-iri (variant-iri condition)]
          (concat condition-triples
                  (conj (variant-record condition v-iri)
                        [gc-node :sepio/is-about-gene v-iri])))))
    nil))

(defn search-contributions [curation-iri search-date agent-iri]
  (let [contrib-iri (rdf/blank-node)]
    [[curation-iri :sepio/qualified-contribution contrib-iri]
     [contrib-iri :sepio/activity-date search-date]
     [contrib-iri :bfo/realizes :sepio/EvidenceRole]
     [contrib-iri :sepio/has-agent agent-iri]]))

(defn is-preferred-condition [curation condition]
  (let [preferred-conditions
        (->> (:preferred_conditions curation)
             (map #(vector (:iri %) (:gene %)))
             (into #{}))]
    (preferred-conditions [(:iri condition) (:gene condition)])))

;; TODO add variant here too
(defn assertion [curation assertion-map]
  (let [assertion-iri (rdf/blank-node)
        curation-iri (:iri curation)
        preferred-condition
        (if (is-preferred-condition curation assertion-map)
          [[assertion-iri :rdf/type :cg/ActionabilityAssertionForPreferredCondition]]
          [])
        assertion-record (concat
                          [[curation-iri :bfo/has-part assertion-iri]
                           [assertion-iri :rdf/type :sepio/ActionabilityAssertion]
                           #_[assertion-iri :sepio/has-subject (-> assertion-map :gene gene-resource)]
                           [assertion-iri
                            :sepio/has-predicate
                            (-> assertion-map :assertion vocab rdf/resource)]
                           [assertion-iri
                            :sepio/has-object
                            (-> assertion-map :iri rdf/resource)]]
                          preferred-condition)]
    (if-let [gene (:gene assertion-map)]
      (conj assertion-record [assertion-iri :sepio/has-subject (rdf/resource gene)])
      (let [variant-iri (variant-iri assertion)]
        (concat assertion-record
                (conj (variant-record assertion-map variant-iri)
                      [assertion-iri :sepio/has-subject variant-iri]))))))

(defn total-scores [curation]
  (->> curation
       :scores
       (map :ScoringGroups)
       flatten
       (map :Interventions)
       flatten
       (map :ScoringGroups)
       flatten
       (map :Total)
       flatten
       (remove nil?)
       (map #(or (re-find #"\d+" %) "0"))
       (map #(Integer/parseInt %))
       (map #(vector (:iri curation) :cg/has-total-actionability-score %))))

(defn assertions [curation]
  (let [assertion-set
        (cond 
          (:assertions curation)
          (into #{} (:assertions curation))
          
          (= "Failed" (:earlyRuleOutStatus curation))
          (into #{} (map #(assoc % :assertion "Insufficient Evidence")
                         (:conditions curation)))
          :else
          (into #{} (map #(assoc % :assertion "Assertion Pending")
                         (:conditions curation))))]
    (->> assertion-set
         (mapcat #(assertion curation %)))))

(defn curation->statements [curation]
  (if (spec/valid? ::curation curation)
    (let [curation-iri (:iri curation)
          contrib-iri (rdf/blank-node)
          agent-iri (-> curation :affiliations first :id vocab rdf/resource)]
      (concat 
       [[curation-iri :rdf/type :sepio/ActionabilityReport]
        [curation-iri :sepio/qualified-contribution contrib-iri]
        [curation-iri :dc/source (:scoreDetails curation)]
        [curation-iri :dc/hasVersion (:curationVersion curation)]
        [curation-iri :rdfs/label (:title curation)]
        [contrib-iri :sepio/activity-date (:dateISO8601 curation)]
        [contrib-iri :bfo/realizes :sepio/ApproverRole]
        [contrib-iri :sepio/has-agent agent-iri]
        ;; [agent-iri :rdfs/label (-> curation :affiliations first :name)]
        ]
       (mapcat #(genetic-condition curation-iri %) (:conditions curation))
       (mapcat #(search-contributions curation-iri % agent-iri)
               (:searchDates curation))
       (assertions curation)
       (total-scores curation)))
    []))

(def same-as-query
  (rdf/create-query "select ?x where { ?x :owl/sameAs ?y }"))

(defn event->model [event]
  (let [model (get-in event [::storage/storage :gv-tdb])
        statements (curation->statements (::event/data event))]
    (rdf/tx model
      (rdf/statements->model
       (map
        (fn [[s p o]]
          (if (re-find #"https://identifiers\.org/hgnc:" (str o))
            [s p (first (same-as-query model {:y o}))]
            [s p o]))
        statements)))))

(defn add-actionability-model-fn [event]
  (assoc event ::model (event->model event)))

(def add-actionability-model
  (interceptor/interceptor
   {:name ::add-actionability-model
    :enter (fn [e] (add-actionability-model-fn e))}))

(defn write-actionability-model-to-db-fn [event]
  (event/store event
               :gv-tdb
               (get-in event [::event/data :iri])
               (::model event)))

(def write-actionability-model-to-db
  (interceptor/interceptor
   {:name ::write-actionability-model-to-db
    :enter (fn [e] (write-actionability-model-to-db-fn e))}))
