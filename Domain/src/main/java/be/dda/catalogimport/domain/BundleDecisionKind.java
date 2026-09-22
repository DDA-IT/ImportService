package be.dda.catalogimport.domain;

/** Het soort beslissing dat een {@code publication_decision}-rij vastlegt (ontwerp fase 4 par. 2). */
public enum BundleDecisionKind {

    /** Eén of meerdere mutaties goedgekeurd (individueel of in groep). */
    APPROVE,

    /** Eén of meerdere mutaties afgekeurd (individueel of in groep); vereist altijd een reden. */
    REJECT,

    /** Resterende PLANNED-mutaties automatisch goedgekeurd op naam van de bevriezer (R-FRZ). */
    AUTO_APPROVE_PLANNED,

    /** De bundel is bevroren. */
    FREEZE,

    /** De bundel is geannuleerd. */
    CANCEL
}
