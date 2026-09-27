package be.dda.catalogimport.domain;

/**
 * Toestand van de omschrijving in een {@link PublicationBundleSnapshot}. Houdt "niet gemapt" en
 * "gemapt maar leeg" uit elkaar (zelfde patroon als {@link DiscountCodeState}).
 */
public enum SnapshotDescriptionState {

    /** Omschrijving gemapt met een niet-lege waarde. */
    VALUE,

    /** Omschrijving gemapt, bronwaarde expliciet leeg. */
    EMPTY,

    /** Omschrijving niet gemapt: geen uitspraak van de bron. */
    NOT_MAPPED
}
