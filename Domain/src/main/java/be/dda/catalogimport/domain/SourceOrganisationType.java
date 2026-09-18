package be.dda.catalogimport.domain;

/**
 * Soort bronorganisatie. Businessanalyse §15.2 punt 2 en §16.1: een bronorganisatie is
 * inhoudelijk iets anders dan de technische levering en kan een leverancier of een
 * aankoopvereniging zijn.
 */
public enum SourceOrganisationType {

    /** Leverancier. */
    SUPPLIER,

    /** Aankoopvereniging (bv. VROOAM), levert bestanden voor meerdere leveranciers aan. */
    PURCHASING_ASSOCIATION
}
