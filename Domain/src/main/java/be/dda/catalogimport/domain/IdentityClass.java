package be.dda.catalogimport.domain;

/**
 * Het gewicht van een doelveld in de identiteitsbepaling (ontwerp fase 3, R-ID-08 en R-STR-05).
 * <p>
 * Dit is configuratie, geen code: per bron kan een ander veld sterk identificerend zijn. Wat
 * <b>niet</b> configureerbaar is: prijs en omschrijving zijn nooit identiteitsbeslissend — die staan
 * daarom op {@link #NONE} in de veldcatalogus.
 * <p>
 * Een veld kan nooit tegelijk sterk identificerend en ondersteunend/zwak zijn; een revisie die dat
 * declareert wordt geblokkeerd met {@code CONFIG_IDENTITY_CLASS_CONFLICT} (R-STR-05).
 */
public enum IdentityClass {

    /** Sterk identificerend: bepaalt mee de aanbiedingsidentiteit. */
    STRONG,

    /**
     * Kritieke artikelreferentie (EAN, PIM-ID, CAB-ID, E-merk + artikelreferentie): identificeert het
     * artikel achter de aanbieding, maar vervangt nooit de aanbiedingsidentiteit (R-ID-03).
     */
    ARTICLE_REFERENCE,

    /** Ondersteunend: mag een match bevestigen, nooit alleen een match maken. */
    SUPPORTING,

    /** Zwak: enkel signaalwaarde, nooit beslissend. */
    WEAK,

    /** Speelt geen enkele rol in de identiteit (prijs, omschrijving, merk, eenheid). */
    NONE
}
