package be.dda.catalogimport.domain;

/** Herkomst van een rij in {@code catalog_source_state}. */
public enum SourceStateOrigin {

    /** Ontstaan door het aanvaarden van een gescreende levering als nulmeting. */
    BASELINE_ACCEPTED,

    /** Ontstaan door een gepubliceerde mutatie (Fase 5). */
    PUBLISHED
}
