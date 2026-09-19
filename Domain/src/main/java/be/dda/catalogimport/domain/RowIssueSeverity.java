package be.dda.catalogimport.domain;

/**
 * Ernst van een {@link ImportRowIssue} (ontwerp fase 3, par. 1).
 * <p>
 * Fase 2 kende enkel {@link #ERROR} en {@link #WARNING}; fase 3 breidt uit tot een <b>superset</b>
 * met {@link #CRITICAL}, {@link #BLOCKING} en {@link #INFO}. De betekenis van de twee bestaande
 * waarden blijft exact gelijk, zodat reeds bewaarde issuerijen hun oordeel behouden.
 * <p>
 * De volgorde van de constanten loopt van zwaar naar licht, maar wordt nergens als ordinal bewaard:
 * de databasekolom is de naam ({@code EnumType.STRING}).
 */
public enum RowIssueSeverity {

    /**
     * Onbetrouwbare identiteit of kritieke referentie: het record wordt vastgehouden en er gaat
     * niets door zonder menselijke beoordeling. Wordt pas vanaf bouwstap 3f gezet.
     */
    CRITICAL,

    /** De volledige levering is onbruikbaar: contract-, structuur-, configuratie- of drempelfout. */
    BLOCKING,

    /** De regel wordt verworpen of de batch geblokkeerd. */
    ERROR,

    /** Informatief; de regel blijft geldig (bv. verwijderde BOM). */
    WARNING,

    /** Puur informatief; verandert niets aan de bruikbaarheid van de levering of de regel. */
    INFO;

    /**
     * Een ernst die het eindoordeel van de batch op {@link ValidationResult#BLOCKING} zet
     * (R-THR-06), ongeacht of de verwerking zelf netjes afgerond is.
     */
    public boolean isBlockingForBatch() {
        return this == CRITICAL || this == BLOCKING;
    }
}
