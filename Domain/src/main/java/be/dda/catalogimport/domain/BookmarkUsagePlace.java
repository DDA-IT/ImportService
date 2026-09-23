package be.dda.catalogimport.domain;

/**
 * De configuratieplaatsen waar een sjabloon-bookmark toegepast mag worden (businessanalyse
 * §14.16-§14.19, beslissingslog 23/09 keuze 3).
 * <p>
 * Dit is een <b>witte lijst</b>, geen catalogus van mogelijkheden: een bookmark mag enkel terechtkomen
 * op een plaats die via {@code import_definition_bookmark_usage} uitdrukkelijk toegelaten is. Een
 * bookmark die overal mag landen is bij het materialiseren niet meer te overzien.
 * <p>
 * <b>{@code DELIVERY_FILE_SELECTION} ontbreekt hier bewust.</b> Het BA1-voorbeeld
 * {@code BESTANDS_PREFIX} hoort bij een Leveringsconfiguratie ({@code ConnectionProfile}/
 * {@code DeliveryConfiguration}, §14.17) die nog niet bestaat — geen entiteit, geen tabel, geen
 * endpoint (zie {@code docs/design/fase3-rules-design.md} §2, "Important technical constraint
 * discovered"). Die waarde toelaten zou een invulveld opleveren dat de gebruiker moet invullen zonder
 * dat het ooit ergens toegepast wordt. Ze komt er additief bij zodra Leveringsconfiguratie gebouwd is.
 */
public enum BookmarkUsagePlace {

    /**
     * De vaste waarde van een veldmapping ({@code import_field_mapping.fixed_value}). Bij
     * materialisatie wordt de bookmarkwaarde letterlijk weggeschreven met
     * {@link FieldValueKind#FIXED_VALUE}; {@link FieldValueKind#BOOKMARK} verschijnt nooit in een
     * actieve revisie.
     */
    FIELD_MAPPING_FIXED_VALUE,

    /**
     * De vergelijkingswaarde van een recordfilter ({@code import_record_filter.compare_value}). Die
     * kolom is {@code not null} en kent geen verwijzingskolom, dus materialisatie is hier niet één van
     * twee opties maar de enige (fase3-rules-design.md §2, aanvulling bij 004-3).
     */
    RECORD_FILTER_COMPARE_VALUE,

    /** Een identiteitsveld van de revisie ({@code identity_*_field}). */
    REVISION_IDENTITY_FIELD,

    /** Het prijs-/drempelbeleid van de revisie (afwijkingsgrens, tolerantie, vensters). */
    REVISION_PRICE_POLICY,

    /** De doelbibliotheek van de koppeling ({@link ImportLink#getLibraryCode()}). */
    LINK_LIBRARY_CODE,

    /** De bibliotheekzoekleverancier van de koppeling; filter op bibliotheekniveau, nooit identiteit (§15.2 punt 3). */
    LINK_SEARCH_SUPPLIER,

    /** De leveranciersorganisatie van de koppeling. */
    LINK_SUPPLIER_ORGANISATION
}
