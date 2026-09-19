package be.dda.catalogimport.domain;

/**
 * Het functionele domein waar een {@link ImportRowIssue} thuishoort (ontwerp fase 3, par. 1).
 * <p>
 * Het domein zegt <b>waarover</b> het probleem gaat, niet hoe erg het is ({@link RowIssueSeverity})
 * en niet op welk controleniveau het vastgesteld is ({@link ControlLevel}). Het staat gedenormaliseerd
 * op elke issuerij (R-ISS-02): een latere wijziging van de foutcatalogus mag nooit met terugwerkende
 * kracht veranderen hoe een reeds vastgestelde levering beoordeeld werd.
 */
public enum IssueDomain {

    /** De levering zelf: transport, volledigheid, byte-/recordaantallen, manifest. */
    DELIVERY_SOURCE,

    /** Structuur van het bestand of de dataset: header, kolommen, regelopbouw. */
    STRUCTURE_DATASET,

    /** Aanbiedingsidentiteit en kritieke referenties (EAN, PIM-ID, CAB-ID, ...). */
    IDENTITY_REFERENCE,

    /** Mapping, datatypes, verplichte velden, lengtes en transformaties. */
    MAPPING_VALIDATION,

    /** Alles wat een bedrag, percentage of prijsafwijking raakt (financieel). */
    PRICE,

    /** Volledigheid en verwijderingen (MOGELIJK_VERWIJDERD-logica; nog niet in fase 3 gebruikt). */
    COMPLETENESS_DELETE,

    /** Technische fouten in de verwerkings- of publicatieketen. */
    PUBLICATION_TECHNICAL,

    /** Configuratie van definitie/revisie en de rechten daarop. */
    AUTHORISATION_CONFIG,

    /**
     * Supplementen op een aanbieding. Bewust gedeclareerd maar in fase 3 <b>ongebruikt</b> (ontwerp
     * fase 3, par. 0): zo hoeft een latere fase de enum niet te verbreden.
     */
    SUPPLEMENT
}
