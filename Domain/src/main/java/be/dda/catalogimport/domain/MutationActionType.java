package be.dda.catalogimport.domain;

/** Soort actie van een {@link ImportMutation}. */
public enum MutationActionType {

    /** Nieuwe aanbieding (identiteit onbekend in de bronstaat). */
    CREATE,

    /** Gewijzigde aanbieding; in Fase 2 enkel hash-/domeinniveau plus voor/na-basisprijs. */
    UPDATE,

    /** Vastlegging dat een screening werd afgerond; geen inhoudelijke mutatie. */
    IMPORT_MARKER
}
