package be.dda.catalogimport.domain;

/**
 * Wat een ontbrekende of lege <b>bronwaarde</b> betekent voor één filterrij (ontwerp fase 3,
 * R-FLT-01/R-FLT-03; businessanalyse par. 5.5.1 "hoe null, leeg en ontbrekende kolommen worden
 * behandeld").
 * <p>
 * Er is bewust geen waarde die neerkomt op "het filter matcht gewoon niet": dat zou een levering stil
 * anders laten aflopen dan de beheerder bedoelde. Elke mogelijkheid is een expliciete keuze.
 * <p>
 * Let op het onderscheid met {@link MissingColumnBehaviour}: hier bestaat de kolom wél, maar is de
 * waarde leeg of staat ze buiten de breedte van deze regel.
 */
public enum FilterNullBehaviour {

    /** Standaard: de regel valt buiten de importscope en telt in {@code filtered_out_count}. */
    EXCLUDE,

    /** De regel wordt verworpen met een probleem en telt in {@code rejected_record_count}. */
    REJECT,

    /**
     * De lege waarde wordt gewoon vergeleken (als {@code ""}). Zo kan {@code Status <> EOL} een
     * record met lege status bewust binnen de scope houden.
     */
    COMPARE_AS_EMPTY
}
