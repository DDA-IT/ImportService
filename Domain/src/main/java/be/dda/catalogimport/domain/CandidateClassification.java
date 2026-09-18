package be.dda.catalogimport.domain;

/** Classificatie van een gestagede kandidaat ten opzichte van de bronstaat. */
public enum CandidateClassification {

    /** Identiteit onbekend in de bronstaat. */
    NEW,

    /** Identiteit bekend, gecombineerde fingerprint verschilt. */
    CHANGED,

    /** Identiteit bekend, fingerprint identiek: raakt de bronstaat nooit aan. */
    UNCHANGED,

    /** Identiteit komt meermaals voor in dezelfde levering. */
    DUPLICATE_IN_DELIVERY
}
