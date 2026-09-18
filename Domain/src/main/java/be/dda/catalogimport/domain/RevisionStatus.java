package be.dda.catalogimport.domain;

/**
 * Levenscyclus van één revisie van een importdefinitie.
 * Eén-op-één met de normatieve toestandenlijst in businessanalyse §14.14.
 * De overgangen zelf worden pas in fase 4 afgedwongen; fase 1 legt enkel de toestanden vast.
 */
public enum RevisionStatus {

    /** Concept: bewerkbaar als opvolger, niet inzetbaar voor productie. */
    DRAFT,

    /** Screening bezig: testbestand of serverlevering wordt tegen deze revisie beoordeeld. */
    SCREENING,

    /** Beoordeling nodig: screening klaar, maar issues of wijziging vereisen actie. */
    REVIEW_REQUIRED,

    /** Ter goedkeuring: alle vereiste controles geldig, wacht op bevoegde goedkeuring. */
    PENDING_APPROVAL,

    /** Actief: mag voor nieuwe productieleveringen gebruikt worden. Hoogstens één per definitie. */
    ACTIVE,

    /** Vervangen: was eerder actief, blijft leesbaar en herverwerkbaar, krijgt geen nieuwe leveringen. */
    SUPERSEDED,

    /** Ingetrokken: mag niet geactiveerd worden; reden blijft auditbaar. */
    WITHDRAWN
}
