package be.dda.catalogimport.domain;

/**
 * Wat een <b>ontbrekende kolom</b> betekent voor één filterrij (ontwerp fase 3, R-FLT-03).
 * <p>
 * De standaard is {@link #BLOCK}, en dat is een businesskeuze: als de kolom waarop de importscope
 * gedefinieerd is niet meer in het bronbestand staat, is niet vast te stellen welke records tot deze
 * import horen. Doorgaan zou ofwel de hele catalogus importeren ofwel niets — allebei stil fout.
 */
public enum MissingColumnBehaviour {

    /**
     * Standaard: de volledige levering wordt geblokkeerd met {@code FILTER_COLUMN_MISSING}, vóór er
     * ook maar één record beoordeeld is.
     */
    BLOCK,

    /** Elke regel valt buiten de importscope zolang de kolom ontbreekt. */
    EXCLUDE,

    /** Elke regel wordt verworpen met een probleem zolang de kolom ontbreekt. */
    REJECT
}
