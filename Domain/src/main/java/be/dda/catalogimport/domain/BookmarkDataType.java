package be.dda.catalogimport.domain;

/**
 * Het type van een sjabloon-bookmark (businessanalyse §14.16, beslissingslog 23/09).
 * <p>
 * <b>Bewust géén uitbreiding van {@link FieldDataType}.</b> Dat type beschrijft de waarde van een
 * <i>datakolom uit een bronbestand</i> en stuurt de recordvalidatie (R-REC-01..R-REC-06); een bookmark
 * beschrijft een <i>configuratiewaarde die iemand invult</i>. De twee groeien uit elkaar: een bookmark
 * kent verwijzingen naar bestaande stamgegevens ({@link #SUPPLIER_REFERENCE},
 * {@link #LIBRARY_REFERENCE}, {@link #POLICY_PROFILE_REFERENCE}) en een keuzelijst
 * ({@link #ENUM}), die als kolomtype nooit voorkomen. Zou {@link FieldDataType} met die waarden
 * uitgebreid worden, dan zouden ze ook in elke bestaande mapping- en filtercheck geldig worden.
 */
public enum BookmarkDataType {

    /** Vrije tekst; voorloopnullen, lengte en hoofdletters blijven bewaard. */
    TEXT,

    /** Geheel getal. */
    INTEGER,

    /** Decimaal getal; nooit via een float ingelezen. */
    DECIMAL,

    /** Datum zonder tijd. */
    DATE,

    /** Waar/niet waar. */
    BOOLEAN,

    /**
     * Keuze uit een vaste lijst. De lijst is verplicht bij de declaratie: de databasecheck
     * {@code ck_import_definition_bookmark_enum} weigert een ENUM-bookmark zonder toegelaten waarden.
     */
    ENUM,

    /** Verwijzing naar een leverancier/bronorganisatie ({@link SourceOrganisation}). */
    SUPPLIER_REFERENCE,

    /** Verwijzing naar een doelbibliotheek, bv. {@code PSARF012}. */
    LIBRARY_REFERENCE,

    /** Verwijzing naar een prijs-/beleidsprofiel van een revisie. */
    POLICY_PROFILE_REFERENCE
}
