package be.dda.catalogimport.domain;

/**
 * Levenscyclus van één screening ({@link ImportBatch}) van één levering onder één bevroren
 * revisie: RECEIVED, SCREENING, MUTATING en dan SCREENED, BLOCKED of FAILED; daarna optioneel
 * BASELINE_ACCEPTED. De statusovergangen zelf worden door de screeningservice bewaakt.
 */
public enum ImportBatchStatus {

    /** Batch geregistreerd, nog niet gestart. Open. */
    RECEIVED(false),

    /** Bronbestand wordt gelezen en gestaged. Open. */
    SCREENING(false),

    /** Delta wordt bepaald en de mutatielijst gegenereerd (hervatbaar). Open. */
    MUTATING(false),

    /** Screening afgerond; mutaties en marker geschreven. Terminaal. */
    SCREENED(true),

    /** Contract-/structuurfout: geen inhoudelijke mutaties, wel een marker. Terminaal. */
    BLOCKED(true),

    /** Technische fout of onderbreking: geen marker, geen mutaties. Terminaal. */
    FAILED(true),

    /** De gescreende levering is als nulmeting van de bronstaat aanvaard. Terminaal. */
    BASELINE_ACCEPTED(true);

    private final boolean terminal;

    ImportBatchStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** Terminale batches laten {@code open_marker} los (zie {@link ImportBatch}). */
    public boolean isTerminal() {
        return terminal;
    }
}
