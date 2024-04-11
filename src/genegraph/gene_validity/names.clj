(ns genegraph.gene-validity.names
  (:require [genegraph.framework.storage.rdf.names :as names :refer [add-prefixes add-keyword-mappings]]))

(add-prefixes
{"dc" "http://purl.org/dc/terms/"
 "hp" "http://purl.obolibrary.org/obo/HP_"
 "sepio" "http://purl.obolibrary.org/obo/SEPIO_"
 "geno" "http://purl.obolibrary.org/obo/GENO_"
 "mondo" "http://purl.obolibrary.org/obo/MONDO_"
 "cg" "http://dataexchange.clinicalgenome.org/terms/"
 "cgdosage" "http://dx.clinicalgenome.org/entities/"
 "cggv" "http://dataexchange.clinicalgenome.org/gci/"
 "cggciex" "http://dataexchange.clinicalgenome.org/gci-express/"
 "foaf" "http://xmlns.com/foaf/0.1/"
 "rdfs" "http://www.w3.org/2000/01/rdf-schema#"
 "rdf" "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
 "owl" "http://www.w3.org/2002/07/owl#"
 "skos" "http://www.w3.org/2004/02/skos/core#"
 "ncbigene" "https://www.ncbi.nlm.nih.gov/gene/"
 "prov" "http://www.w3.org/ns/prov#"
 "void" "http://rdfs.org/ns/void"
 "omim" "https://omim.org/entry/"
 "cnt" "http://www.w3.org/2011/content#"
 "hgnc" "https://identifiers.org/hgnc:"
 "cgagent" "http://dataexchange.clinicalgenome.org/agent/"
 "pmid" "https://pubmed.ncbi.nlm.nih.gov/"
 "ro" "http://purl.obolibrary.org/obo/RO_"
 "ga4gh" "https://terms.ga4gh.org/"
 "oboinowl" "http://www.geneontology.org/formats/oboInOwl#"})

(add-keyword-mappings
 {:bfo/realizes "http://purl.obolibrary.org/obo/BFO_0000055"
  :bfo/has-part "http://purl.obolibrary.org/obo/BFO_0000051"
  :cg/website-legacy-id "http://dataexchange.clinicalgenome.org/terms/website_legacy_id"
  :hp/AutosomalRecessiveInheritance "http://purl.obolibrary.org/obo/HP_0000007"
  :geno/AlleleOrigin "http://purl.obolibrary.org/obo/GENO_0000877"
  :geno/DeNovoAlleleOrigin "http://purl.obolibrary.org/obo/GENO_0000880"
  :geno/GermlineAlleleOrigin "http://purl.obolibrary.org/obo/GENO_0000888"
  :geno/Heterozygous "http://purl.obolibrary.org/obo/GENO_0000135"
  :geno/Homozygous "http://purl.obolibrary.org/obo/GENO_0000136"
  :geno/allele-origin "http://purl.obolibrary.org/obo/GENO_0000877"
  :geno/has-interval "http://purl.obolibrary.org/obo/GENO_0000966"
  :geno/has-location "http://purl.obolibrary.org/obo/GENO_0000903"
  :geno/has-reference-sequence "http://purl.obolibrary.org/obo/GENO_0000967"
  :geno/has-zygosity "http://purl.obolibrary.org/obo/GENO_0000608"
  :geno/on-strand "http://purl.obolibrary.org/obo/GENO_0000906"
  :geno/related-condition "http://purl.obolibrary.org/obo/GENO_0000790"
  :geno/start-position "http://purl.obolibrary.org/obo/GENO_0000894"
  :geno/end-position "http://purl.obolibrary.org/obo/GENO_0000895"
  :geno/SequenceFeatureLocation "http://purl.obolibrary.org/obo/GENO_0000815"
  :obi/p-value "http://purl.obolibrary.org/obo/OBI_0000175"
  :pco/Family "http://purl.obolibrary.org/obo/PCO_0000020"
  :ro/IsCausalGermlineMutationIn "http://purl.obolibrary.org/obo/RO_0004013"
  :ro/has-member "http://purl.obolibrary.org/obo/RO_0002351"
  :sepio/DosageSufficientEvidence "http://purl.obolibrary.org/obo/SEPIO_0002006"
  :sepio/DosageModerateEvidence "http://purl.obolibrary.org/obo/SEPIO_0002009"
  :sepio/DosageMinimalEvidence "http://purl.obolibrary.org/obo/SEPIO_0002007"
  :sepio/DosageNoEvidence "http://purl.obolibrary.org/obo/SEPIO_0002008"
  :sepio/GeneAssociatedWithAutosomalRecessivePhenotype "http://purl.obolibrary.org/obo/SEPIO_0002502"
  :sepio/Assertion "http://purl.obolibrary.org/obo/SEPIO_0000001"
  :sepio/Proposition "http://purl.obolibrary.org/obo/SEPIO_0000000"
  :sepio/ApproverRole "http://purl.obolibrary.org/obo/SEPIO_0000155"
  :sepio/EvidenceItem "http://purl.obolibrary.org/obo/SEPIO_0000149"
  :sepio/EvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0000002"
  :sepio/ValueSet "http://purl.obolibrary.org/obo/SEPIO_0000412"
  :sepio/CaseControlEvidenceItem "http://purl.obolibrary.org/obo/SEPIO_0004170"
  :sepio/VariantEvidenceItem "http://purl.obolibrary.org/obo/SEPIO_0004116"
  :sepio/FamilyCosegregation "http://purl.obolibrary.org/obo/SEPIO_0000247"
  :ga4gh/VariationDescriptor "https://terms.ga4gh.org/VariationDescriptor"
  :sepio/GeneValidityEvidenceLevelAssertion "http://purl.obolibrary.org/obo/SEPIO_0004002"
  :sepio/GeneValidityEvidenceLevelAutoClassification "http://purl.obolibrary.org/obo/SEPIO_0004098"
  :sepio/GeneValidityProposition "http://purl.obolibrary.org/obo/SEPIO_0004001"
  :sepio/HasEvidenceLevel "http://purl.obolibrary.org/obo/SEPIO_0000146"
  :sepio/NonNullVariantEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004121"
  :sepio/NullVariantEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004120"
  :sepio/OverallAutosomalDominantDeNovoVariantEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004009"
  :sepio/OverallAutosomalDominantNullVariantEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004010"
  :sepio/OverallAutosomalDominantOtherVariantEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004011"
  :sepio/OverallAutosomalRecessiveVariantEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004008"
  :sepio/OverallCaseControlEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004007"
  :sepio/OverallExperimentalEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004006"
  :sepio/OverallFunctionalAlterationEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004014"
  :sepio/OverallFunctionalEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004013"
  :sepio/OverallGeneticEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004005"
  :sepio/OverallModelAndRescueEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004015"
  :sepio/ProbandScoreCapEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004174"
  :sepio/ProbandWithVariantEvidenceItem "http://purl.obolibrary.org/obo/SEPIO_0004081"
  :sepio/SecondaryContributorRole "http://purl.obolibrary.org/obo/SEPIO_0004099"
  :sepio/SegregationEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004012"
  :sepio/UnscoreableEvidenceLine "http://purl.obolibrary.org/obo/SEPIO_0004127"
  :sepio/VariantFunctionalImpactEvidenceItem "http://purl.obolibrary.org/obo/SEPIO_0004119"
  :sepio/DefinitiveEvidence "http://purl.obolibrary.org/obo/SEPIO_0004504"
  :sepio/StrongEvidence "http://purl.obolibrary.org/obo/SEPIO_0004505"
  :sepio/ModerateEvidence "http://purl.obolibrary.org/obo/SEPIO_0004506"
  :sepio/LimitedEvidence "http://purl.obolibrary.org/obo/SEPIO_0004507"
  :sepio/NoKnownRelationship "http://purl.obolibrary.org/obo/SEPIO_0004508"
  :sepio/NoReportedEvidence "http://purl.obolibrary.org/obo/SEPIO_0004508"
  :sepio/Refuted "http://purl.obolibrary.org/obo/SEPIO_0004510"
  :sepio/Disputed "http://purl.obolibrary.org/obo/SEPIO_0004540"
  :sepio/NoClassification "http://purl.obolibrary.org/obo/SEPIO_0004508"
  :sepio/activity-date "http://purl.obolibrary.org/obo/SEPIO_0000160"
  :sepio/age-type "http://purl.obolibrary.org/obo/SEPIO_0004142"
  :sepio/age-unit "http://purl.obolibrary.org/obo/SEPIO_0004141"
  :sepio/age-value "http://purl.obolibrary.org/obo/SEPIO_0004143"
  :sepio/all-genotyped-sequenced "http://purl.obolibrary.org/obo/SEPIO_0004154"
  :sepio/allele-frequency "http://purl.obolibrary.org/obo/SEPIO_0000297"
  :sepio/calculated-score "http://purl.obolibrary.org/obo/SEPIO_0004128"
  :sepio/detection-method "http://purl.obolibrary.org/obo/SEPIO_0004157"
  :sepio/earliest-article "http://purl.obolibrary.org/obo/SEPIO_0004162"
  :sepio/estimated-lod-score "http://purl.obolibrary.org/obo/SEPIO_0004125"
  :sepio/ethnicity "http://purl.obolibrary.org/obo/SEPIO_0004144"
  :sepio/evidence-line-strength "http://purl.obolibrary.org/obo/SEPIO_0000132"
  :sepio/evidence-line-strength-score "http://purl.obolibrary.org/obo/SEPIO_0000429"
  :sepio/first-testing-method "http://purl.obolibrary.org/obo/SEPIO_0004136"
  :sepio/functional-data-support "http://purl.obolibrary.org/obo/SEPIO_0004135"
  :sepio/functional-alteration-in-non-patient-cells-evidence-line "http://purl.obolibrary.org/obo/SEPIO_0004026"
  :sepio/functional-alteration-in-patient-cells-evidence-line "http://purl.obolibrary.org/obo/SEPIO_0004025"
  :sepio/has-agent "http://purl.obolibrary.org/obo/SEPIO_0000017"
  :sepio/has-case-cohort "http://purl.obolibrary.org/obo/SEPIO_0004150"
  :sepio/has-control-cohort "http://purl.obolibrary.org/obo/SEPIO_0004151"
  :sepio/has-evidence "http://purl.obolibrary.org/obo/SEPIO_0000189"
  :sepio/has-evidence-item "http://purl.obolibrary.org/obo/SEPIO_0000084"
  :sepio/has-mode-of-inheritance "http://purl.obolibrary.org/obo/SEPIO_0004172"
  :sepio/has-object "http://purl.obolibrary.org/obo/SEPIO_0000390"
  :sepio/has-predicate "http://purl.obolibrary.org/obo/SEPIO_0000389"
  :sepio/has-qualifier "http://purl.obolibrary.org/obo/SEPIO_0000144"
  :sepio/has-sex "http://purl.obolibrary.org/obo/SEPIO_0004145"
  :sepio/has-subject "http://purl.obolibrary.org/obo/SEPIO_0000388"
  :sepio/has-textual-part "http://purl.obolibrary.org/obo/SEPIO_0000123"
  :sepio/has-variant "http://purl.obolibrary.org/obo/SEPIO_0004129"
  :sepio/is-about-allele "http://purl.obolibrary.org/obo/SEPIO_0000275"
  :sepio/is-about-condition "http://purl.obolibrary.org/obo/SEPIO_0000276"
  :sepio/is-about-family "http://purl.obolibrary.org/obo/SEPIO_0000282"
  :sepio/is-about-proband "http://purl.obolibrary.org/obo/SEPIO_0000281"
  :sepio/is-about-gene "http://purl.obolibrary.org/obo/SEPIO_0000278"
  :sepio/is-specified-by "http://purl.obolibrary.org/obo/SEPIO_0000041"
  :sepio/meets-inclusion-criteria "http://purl.obolibrary.org/obo/SEPIO_0004126"
  :sepio/multiple-authors "http://purl.obolibrary.org/obo/SEPIO_0004160"
  :sepio/num-with-variant "http://purl.obolibrary.org/obo/SEPIO_0004155"
  :sepio/paternity-maternity-confirmed "http://purl.obolibrary.org/obo/SEPIO_0004161"
  :sepio/phenotype-negative-allele-negative "http://purl.obolibrary.org/obo/SEPIO_0000289"
  :sepio/phenotype-positive-allele-positive "http://purl.obolibrary.org/obo/SEPIO_0000292"
  :sepio/previous-testing "http://purl.obolibrary.org/obo/SEPIO_0004131"
  :sepio/previous-testing-description "http://purl.obolibrary.org/obo/SEPIO_0004132"
  :sepio/proband-with-ad-de-novo-evidence-line "http://purl.obolibrary.org/obo/SEPIO_0004078"
  :sepio/proband-with-ad-other-evidence-line "http://purl.obolibrary.org/obo/SEPIO_0004080"
  :sepio/protein-physical-association-evidence-line "http://purl.obolibrary.org/obo/SEPIO_0004184"
  :sepio/published-lod-score "http://purl.obolibrary.org/obo/SEPIO_0004124"
  :sepio/qualified-contribution "http://purl.obolibrary.org/obo/SEPIO_0000159"
  :sepio/score-status "http://purl.obolibrary.org/obo/SEPIO_0004130"
  :sepio/second-testing-method "http://purl.obolibrary.org/obo/SEPIO_0004137"
  :sepio/sequencing-method "http://purl.obolibrary.org/obo/SEPIO_0004122"
  :sepio/statistical-significance-type "http://purl.obolibrary.org/obo/SEPIO_0004165"
  :sepio/statistical-significance-value "http://purl.obolibrary.org/obo/SEPIO_0004167"
  :sepio/statistical-significance-value-type "http://purl.obolibrary.org/obo/SEPIO_0004166"
  :so/chromosome-band "http://purl.obolibrary.org/obo/SO_0000341"
  :so/assembly "http://purl.obolibrary.org/obo/SO_0001248"
  :so/Assembly "http://purl.obolibrary.org/obo/SO_0001248"
  :so/ChromosomeBand "http://purl.obolibrary.org/obo/SO_0000341"
  :so/Gene "http://purl.obolibrary.org/obo/SO_0000704"
  :so/SequenceFeature "http://purl.obolibrary.org/obo/SO_0000110"
  :stato/Cohort "http://purl.obolibrary.org/obo/STATO_0000203"
  :stato/lower-confidence-limit "http://purl.obolibrary.org/obo/STATO_0000315"
  :stato/upper-confidence-limit "http://purl.obolibrary.org/obo/STATO_0000314"
  :iao/is-about "http://purl.obolibrary.org/obo/IAO_0000136"
  :iao/definition "http://purl.obolibrary.org/obo/IAO_0000115"
  :sepio/ActionabilityAssertion "http://purl.obolibrary.org/obo/SEPIO_0003003"
  :sepio/GeneDosageReport "http://purl.obolibrary.org/obo/SEPIO_0002015"
  :sepio/ActionabilityReport "http://purl.obolibrary.org/obo/SEPIO_0003010"
  :sepio/EvidenceRole "http://purl.obolibrary.org/obo/SEPIO_0000148"
  :sepio/GeneticCondition "http://purl.obolibrary.org/obo/SEPIO_0000219"
  :geno/SequenceInterval "http://purl.obolibrary.org/obo/GENO_0000965"
  :sepio/DosageSensitivityProposition "http://purl.obolibrary.org/obo/SEPIO_0002003"
  :sepio/PropositionScopeAssertion "http://purl.obolibrary.org/obo/SEPIO_0002014"
  :sepio/StudyFinding "http://purl.obolibrary.org/obo/SEPIO_0000173"
  :sepio/InterpreterRole "http://purl.obolibrary.org/obo/SEPIO_0000331"
  :geno/has-member-count "http://purl.obolibrary.org/obo/GENO_0000917"
  :geno/BenignForCondition "http://purl.obolibrary.org/obo/GENO_0000843"
  :sepio/GeneDosageRecord "http://purl.obolibrary.org/obo/SEPIO_0002016"
  :sepio/EvidenceLevelAssertion "http://purl.obolibrary.org/obo/SEPIO_0002001"
  :geno/FunctionalCopyNumberComplement "http://purl.obolibrary.org/obo/GENO_0000963"
  :sepio/DosageScopeAssertion "http://purl.obolibrary.org/obo/SEPIO_0002505"
  :geno/PathogenicForCondition "http://purl.obolibrary.org/obo/GENO_0000840"
  :sepio/DosageSensitivityEvaluationGuideline "http://purl.obolibrary.org/obo/SEPIO_0002002"
  :sepio/has-ordinal-position "http://purl.obolibrary.org/obo/SEPIO_0002506"
  :mondo/Disease "http://purl.obolibrary.org/obo/MONDO_0000001"
  :sepio/DisputingEvidence "http://purl.obolibrary.org/obo/SEPIO_0000404"
  :sepio/NoEvidence "http://purl.obolibrary.org/obo/SEPIO_0004508"
  :sepio/ClinGenGeneValidityEvaluationCriteriaSOP4 "http://purl.obolibrary.org/obo/SEPIO_0004092"
  :sepio/ClinGenGeneValidityEvaluationCriteriaSOP5
  "http://purl.obolibrary.org/obo/SEPIO_0004093"}) ; gci-ex only

;:cg/has-total-actionability-score
