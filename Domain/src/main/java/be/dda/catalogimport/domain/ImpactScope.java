package be.dda.catalogimport.domain;

/**
 * Hoe ver de gevolgen van een {@link ImportRowIssue} reiken (ontwerp fase 3, R-ISS-05).
 * <p>
 * Dit staat los van {@link ControlLevel}: een configuratiefout wordt op niveau
 * {@link ControlLevel#STRUCTURE} vastgesteld, maar de gevolgen ervan treffen de volledige levering.
 */
public enum ImpactScope {

    /** Enkel de betrokken bronregel. */
    RECORD,

    /** De volledige levering: geen enkele aanbieding uit dit bestand is bruikbaar. */
    DELIVERY,

    /** De importdefinitie/revisie zelf moet aangepast worden. */
    DEFINITION,

    /** De bibliotheek: bv. een kritieke referentie die aan een ander artikel hangt. */
    LIBRARY
}
