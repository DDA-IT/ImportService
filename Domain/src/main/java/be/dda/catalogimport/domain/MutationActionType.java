package be.dda.catalogimport.domain;

/** Soort actie van een {@link ImportMutation}. */
public enum MutationActionType {

    /** Nieuwe aanbieding (identiteit onbekend in de bronstaat). */
    CREATE,

    /** Gewijzigde aanbieding; in Fase 2 enkel hash-/domeinniveau plus voor/na-basisprijs. */
    UPDATE,

    /**
     * Een kritieke koppelreferentie (EAN, PIM-ID, CAB-ID, {@code E_MARK+ARTICLE_REFERENCE}) is
     * gewijzigd, verwijderd, hergebruikt of dubbelzinnig geworden (R-REF-02..R-REF-05). Dit is
     * bewust <b>geen</b> {@link #UPDATE}: zo'n wijziging mag nooit als gewone update uitgevoerd
     * worden (businessanalyse par. 14.23.3). De mutatie wijzigt niets; ze legt het incident vast met
     * referentietype, oude en nieuwe waarde, en wacht op een menselijke goedkeuring
     * ({@link MutationStatus#AWAITING_APPROVAL}).
     */
    IDENTITY_REFERENCE_INCIDENT,

    /** Vastlegging dat een screening werd afgerond; geen inhoudelijke mutatie. */
    IMPORT_MARKER
}
