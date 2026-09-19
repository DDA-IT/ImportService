package be.dda.catalogimport.domain;

/**
 * Wie een doelveld bezit en dus wie het mag wijzigen (ontwerp fase 3, R-REF-08 en par. 2 004-1/004-2).
 * <p>
 * Eigenaarschap is geen technisch detail maar een businessregel: het bepaalt of een importlevering een
 * waarde überhaupt mag aanraken. Een veld dat aan de Prodis-gebruiker of aan de referentiecontrole
 * toebehoort, wordt nooit stil door een leveranciersbestand overschreven.
 */
public enum FieldOwner {

    /** De catalogusbron (de leverancier) levert en bezit deze waarde. */
    CATALOG_SOURCE,

    /** De prijsmodule bezit deze waarde: bedragen, percentages en prijscomponenten. */
    PRICE_CONTROL,

    /**
     * De referentiecontrole bezit deze waarde (EAN, PIM-ID, CAB-ID, E-merk + artikelreferentie). Dit
     * eigenaarschap is <b>niet wisselbaar</b>: een revisie die dat toch probeert wordt geblokkeerd met
     * {@code CONFIG_OWNER_NOT_CHANGEABLE} (R-REF-08).
     */
    CRITICAL_REFERENCE,

    /**
     * De Prodis-gebruiker bezit deze waarde (bv. merk en eenheid). Een import mag ze hoogstens
     * voorstellen, nooit schrijven.
     */
    PRODIS_USER
}
