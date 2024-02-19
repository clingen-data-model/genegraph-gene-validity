(ns genegraph.gene-validity.graphql.legacy-schema.gene
  (:require [genegraph.framework.storage.rdf :as rdf]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
            [genegraph.gene-validity.graphql.common.curation :as curation]
            [clojure.string :as str]))

;; underlying query--probably need to keep
(defn gene-query [context args value]
  (let [gene (rdf/resource (:iri args) (:db context))]
    (if (rdf/is-rdf-type? gene :so/Gene)
      gene
      (first (filter #(rdf/is-rdf-type? % :so/Gene) (get gene [:owl/same-as :<]))))))

;; TODO CURATION -- examine in context of queries in curation
(defn genes [context args value]
  (curation/genes-for-resolver args value))

;; used
;; TODO CURATION -- examine in context of queries in curation
(defn curation-activities [context args value]
  (curation/activities {:gene value}))

(def most-recent-curation-for-gene 
  (rdf/create-query "select ?contribution where {
{ ?validityproposition :sepio/has-subject ?gene .
  ?validityassertion :sepio/has-subject ?validityproposition .
  ?validityassertion :sepio/qualified-contribution ?contribution .  }
 union
{ ?dosagereport :iao/is-about ?gene .
  ?dosagereport a :sepio/GeneDosageReport .
  ?dosagereport :sepio/qualified-contribution ?contribution . }
 union
{ ?actionabilitycondition :sepio/is-about-gene ?gene .
  ?actionabilityreport :sepio/is-about-condition ?actionabilitycondition .
  ?actionabilityreport a :sepio/ActionabilityReport .
  ?actionabilityreport :sepio/qualified-contribution ?contribution .
  ?contribution :bfo/realizes :sepio/EvidenceRole . }
 ?contribution :sepio/activity-date ?activitydate }
 order by desc(?activitydate)
 limit 1"))

;; used
(defn last-curated-date [context args value]
  (some-> (most-recent-curation-for-gene (:db context) {:gene value})
          first
          (rdf/ld1-> [:sepio/activity-date])))

;; used
(defn hgnc-id [context args value]
  (->> (rdf/ld-> value [:owl/same-as])
       (filter #(= (str (rdf/ld1-> % [:dc/source])) "https://www.genenames.org"))
       first
       str))

;; used
;; TODO CURATION -- examine in context of queries in curation
(defn conditions [context args value]
  (curation/curated-genetic-conditions-for-gene  {:gene value}))

;; TODO CURATION -- examine in context of queries in curation
(def dosage-query
  (rdf/create-query
   [:project ['dosage_report] (cons :bgp curation/gene-dosage-bgp)]))

;; used
;; TODO CURATION -- examine in context of queries in curation
(defn dosage-curation [context args value]
  (first (dosage-query {::rdf/params {:limit 1} :gene value})))

;; used
(defn chromosome-band [context args value]
  (rdf/ld1-> value [:so/chromosome-band]))
