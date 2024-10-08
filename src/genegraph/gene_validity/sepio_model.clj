(ns genegraph.gene-validity.sepio-model
  (:require [clojure.edn :as edn]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [genegraph.gene-validity.names]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [io.pedestal.interceptor :as interceptor])
  (:import [java.time Instant]))

(def construct-params
  {:gcibase "http://dataexchange.clinicalgenome.org/gci/"
   :legacy_report_base "http://dataexchange.clinicalgenome.org/gci/legacy-report_"
   :arbase "http://reg.genome.network/allele/"
   :cvbase "https://www.ncbi.nlm.nih.gov/clinvar/variation/"
   :scvbase "https://identifiers.org/clinvar.submission:"
   :pmbase "https://pubmed.ncbi.nlm.nih.gov/"
   :affbase "http://dataexchange.clinicalgenome.org/agent/"})

(def gdm-sepio-relationships (rdf/read-rdf (str (io/resource "genegraph/gene_validity/sepio_model/gdm_sepio_relationships.ttl")) :turtle))

(rdf/declare-query construct-proposition
                   construct-evidence-level-assertion
                   construct-experimental-evidence-assertions
                   construct-genetic-evidence-assertion
                   construct-ad-variant-assertions
                   construct-ar-variant-assertions
                   construct-cc-and-seg-assertions
                   construct-proband-score
                   construct-model-systems-evidence
                   construct-functional-alteration-evidence
                   construct-functional-evidence
                   construct-rescue-evidence
                   construct-case-control-evidence
                   construct-proband-segregation-evidence
                   construct-family-segregation-evidence
                   construct-evidence-connections
                   construct-alleles
                   construct-articles
                   construct-earliest-articles
                   construct-secondary-contributions
                   construct-variant-score
                   construct-ar-variant-score
                   construct-unscoreable-evidence
                   unlink-variant-scores-when-proband-scores-exist
                   unlink-segregations-when-no-proband-and-lod-scores
                   add-legacy-website-id
                   unpublish-evidence-level-assertion
                   construct-scv)

(def has-affiliation-query
  "Query that returns a curations full affiliation IRI as a Resource.
  Expects affiliations to have been preprocessed to IRIs from string form."
  (rdf/create-query "prefix gci: <http://dataexchange.clinicalgenome.org/gci/>
                   select ?affiliationIRI where {
                     ?proposition a gci:gdm .
                     OPTIONAL {
                      ?proposition gci:affiliation ?gdmAffiliationIRI .
                     }
                     OPTIONAL {
                      ?classification a gci:provisionalClassification .
                      ?classification gci:affiliation ?classificationAffiliationIRI .
                      ?classification gci:last_modified ?date .
                     }
                     BIND(COALESCE(?classificationAffiliationIRI, ?gdmAffiliationIRI) AS ?affiliationIRI) }
                     ORDER BY DESC(?date) LIMIT 1"))

(def is-publish-action-query
  (rdf/create-query "prefix gci: <http://dataexchange.clinicalgenome.org/gci/>
                      select ?classification where {
                      ?classification gci:publishClassification true }" ))

(def initial-construct-queries
  [construct-proposition
   construct-evidence-level-assertion
   construct-experimental-evidence-assertions
   construct-genetic-evidence-assertion
   construct-ad-variant-assertions
   construct-ar-variant-assertions
   construct-cc-and-seg-assertions
   construct-proband-score
   construct-model-systems-evidence
   construct-functional-alteration-evidence
   construct-functional-evidence
   construct-rescue-evidence
   construct-case-control-evidence
   construct-proband-segregation-evidence
   construct-family-segregation-evidence
   construct-alleles
   construct-articles
   construct-earliest-articles
   construct-secondary-contributions
   construct-variant-score
   construct-ar-variant-score
   construct-unscoreable-evidence
   construct-scv
   unpublish-evidence-level-assertion])

(def approval-activity-query
  (rdf/create-query "select ?activity where
 { ?activity :bfo/realizes  :sepio/ApproverRole }"))

(def assertion-query
  (rdf/create-query
   "select ?assertion where
 { ?assertion a :sepio/GeneValidityEvidenceLevelAssertion }"))

(defn legacy-website-id
  "The website uses a version of the assertion ID that incorporates
  the approval date. Annotate the curation with this ID to retain
  backward compatibility with the legacy schema."
  [model]
  (let [approval-date (some-> (approval-activity-query model)
                              first
                              (rdf/ld1-> [:sepio/activity-date])
                              (s/replace #":" ""))
        
        [_
         assertion-base
         assertion-id]
        (some->> (assertion-query model)                                
                 first
                 str
                 (re-find #"^(.*/)([a-z0-9-]*)$"))]
    (rdf/resource (str assertion-base "assertion_" assertion-id "-" approval-date))))

(defn publish-or-unpublish-role [event]
  (let [res
        (if (seq (is-publish-action-query (:gene-validity/gci-model event)))
          :cg/PublisherRole
          :cg/UnpublisherRole)]
    res))

(defn params-for-construct [event]
  (assoc construct-params
         :affiliation
         (first (has-affiliation-query (:gene-validity/gci-model event)))
         :publishRole
         (publish-or-unpublish-role event)
         :publishTime
         (or (some-> event ::event/timestamp Instant/ofEpochMilli str)
             "2020-05-01")))

(def proband-score-cap-query
  (rdf/create-query "select ?x where { ?x a :sepio/ProbandScoreCapEvidenceLine }"))

(defn add-proband-scores
  "Return model contributing the evidence line scores for proband scores
  when needed in SOPv8 + autosomal recessive variants. May need a mechanism
  to feed a new cap in, should that change."
  [model]
  (let [proband-evidence-lines (proband-score-cap-query model)]
    (rdf/union
     model
     (rdf/statements->model
      (map
       #(vector 
         %
         :sepio/evidence-line-strength-score
         (min 3                ; current cap on sop v8+ proband scores
              (reduce
               + 
               (rdf/ld-> % [:sepio/has-evidence
                            :sepio/evidence-line-strength-score]))))
       proband-evidence-lines)))))

(defn gci-data->sepio-model [gci-data params]
  (let [gci-model (rdf/union gci-data gdm-sepio-relationships)
        unlinked-model (apply
                        rdf/union
                        (map #(% gci-model params)
                             initial-construct-queries))
        unlinked-clean (unlink-segregations-when-no-proband-and-lod-scores
                        unlinked-model)
        linked-model (rdf/union unlinked-clean
                                (construct-evidence-connections
                                 (rdf/union
                                  unlinked-clean
                                  gdm-sepio-relationships))
                                (add-legacy-website-id
                                 unlinked-clean
                                 {:legacy_id (legacy-website-id unlinked-clean)}))]
    (-> linked-model
        add-proband-scores
        unlink-variant-scores-when-proband-scores-exist)))

(defn add-model-fn [event]
  (assoc event
         :gene-validity/model
         (gci-data->sepio-model (:gene-validity/gci-model event)
                                (params-for-construct event))))

(def add-model
  (interceptor/interceptor
   {:name ::add-model
    :enter (fn [e] (add-model-fn e))}))
