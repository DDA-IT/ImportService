package be.dda.catalogimport.domain;

/**
 * Status van één uitvoering van een {@link CatalogImportTask}.
 * De statusovergangen zelf worden in fase 4 afgedwongen.
 */
public enum TaskRunStatus {

    /** Aangemaakt, nog niet gestart. Bezet de concurrency-token. */
    PENDING,

    /** Bezig. Bezet de concurrency-token. */
    RUNNING,

    /** Normaal beëindigd. Eindtoestand; laat de concurrency-token los. */
    COMPLETED,

    /** Technisch of functioneel gefaald. Eindtoestand; laat de concurrency-token los. */
    FAILED,

    /** Afgebroken door een gebruiker of door beleid. Eindtoestand; laat de concurrency-token los. */
    CANCELLED
}
