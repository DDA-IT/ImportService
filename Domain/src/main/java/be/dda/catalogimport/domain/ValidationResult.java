package be.dda.catalogimport.domain;

/**
 * Het inhoudelijke eindoordeel over een {@link ImportBatch}, als <b>aparte statusas</b> naast
 * {@link ImportBatchStatus} (ontwerp fase 3, par. 0 afwijking D en R-THR-06).
 * <p>
 * {@code status} zegt hoe ver de verwerking is geraakt (SCREENED/BLOCKED/FAILED), dit zegt wat de
 * inhoud waard is. Die twee vallen niet samen: een batch kan technisch netjes afgerond zijn
 * ({@code SCREENED}) en toch {@link #BLOCKING} zijn omdat er een kritiek incident in zit.
 * <p>
 * Bij een technische fout ({@code FAILED}) blijft het oordeel bewust {@code null}: er is niets
 * vastgesteld, en "onbekend" wordt nooit stil {@link #VALID}.
 */
public enum ValidationResult {

    /** Geen enkel probleem van betekenis. */
    VALID,

    /** Enkel waarschuwingen; de levering is bruikbaar. */
    VALID_WITH_WARNINGS,

    /**
     * Menselijke goedkeuring nodig (bulkincident of wachtende creaties). Wordt pas gezet vanaf
     * bouwstap 3h; tot dan komt deze waarde niet voor.
     */
    REVIEW_REQUIRED,

    /** Minstens één kritiek of blokkerend probleem; er gaat niets door zonder ingreep. */
    BLOCKING
}
