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
     * Een ernst die zwaar genoeg is om de levering als geheel te kunnen raken.
     * <p>
     * <b>Sinds bouwstap 3h-5 bepaalt deze methode {@code validation_result} niet meer</b> (ontwerp
     * fase 3 par. 15.3). Het eindoordeel volgt nu uit het <i>effect per foutcode</i>
     * ({@code DeliveryEffect} in de Service-laag) in plaats van uit de ernst: een bulkincident of een
     * vastgehouden kritieke referentie is zwaar, maar vraagt een <b>beoordeling</b> en maakt de
     * levering niet onbruikbaar. De ernst zelf is ongewijzigd gebleven.
     * <p>
     * De methode blijft bestaan als publieke eigenschap van de ernst — ze is nog steeds waar wat ze
     * zegt — en wordt niet verwijderd of hernoemd; wie een <b>oordeel</b> zoekt, gebruikt
     * {@code ValidationResultEvaluator}.
     */
    public boolean isBlockingForBatch() {
        return this == CRITICAL || this == BLOCKING;
    }
}
