package be.dda.catalogimport.service;

import static be.dda.catalogimport.service.LegacyConfigReport.BLOCKING;
import static be.dda.catalogimport.service.LegacyConfigReport.MIGRATED;
import static be.dda.catalogimport.service.LegacyConfigReport.NOT_MIGRATED;
import static be.dda.catalogimport.service.LegacyConfigReport.WARNING;

import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportFieldTransformStep;
import be.dda.catalogimport.domain.ImportFieldValueMap;
import be.dda.catalogimport.service.LegacyConfigReport.Item;
import be.dda.catalogimport.service.support.LegacyFtpLocation;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Vertaalt één gevalideerde legacy-config-bundel v1 naar een geplande revisie plus rapportitems, volgens de B-rijen van
 * {@code docs/design/legacy-config-bundle-design.md} par. 5 en de beslissingen van 2026-10-20 ("Legacy-config S2").
 * Puur: geen database, geen Prodis-bron, geen klok. Alles wat geen B-rij is (U/N/S5/S6/S7/S8) wordt een rapportitem
 * met een vaste code; niets wordt geraden.
 *
 * <h2>Business rule → technische vertaling</h2>
 * <ul>
 *   <li><b>Identiteit (PSIMPDEF 9/10/11, 23):</b> een bronkolom ({@code Kolom}, 1-gebaseerd, 0 = geen) wordt
 *       {@code identity_*_field}; een vaste "Waarde" op 9/10 wordt {@code identity_*_fixed_value} (S6, met Prodis-controle
 *       in {@code LegacyConfigApplyService}) en elke gemigreerde revisie krijgt {@code identity_uppercase=true} (legacy
 *       {@code Upper()}). Een vaste waarde op 11/23 ({@value #IDENTITY_FIXED_VALUE_UNSUPPORTED}), een
 *       transformatie ({@value #IDENTITY_TRANSFORM_UNSUPPORTED}) of geen kolom ({@value #IDENTITY_FIELD_MISSING})
 *       blokkeert: weglaten zou de identiteit van elke aanbieding veranderen.</li>
 *   <li><b>Sleutelprofiel (INDET {@code ArtImp_Key_*}):</b> een afwezig label telt als {@code N}, zoals
 *       {@code Prg_1232} r.518 ({@code ='Y'}). Zonder leverancier, referentie of groep, of met barcode:
 *       {@value #IDENTITY_PROFILE_UNSUPPORTED} (decisions 2026-09-18). {@code ArtImp_Key_Korting=Y} maakt 23 deel van
 *       de identiteit ({@code FOUR_PART_WITH_DISCOUNT_CODE}).</li>
 *   <li><b>Basisprijs 26 / omschrijving 4:</b> enkel een bronkolom; een transformatie op 26 blokkeert
 *       ({@value #BASE_PRICE_TRANSFORM_UNSUPPORTED}), op 4 vervalt het veld met een waarschuwing.</li>
 *   <li><b>Mappings 33-37, 40, 43, 12, 219, 221:</b> catalogcode uit {@code legacy-config-migratie-design.md} par. 1.
 *       Sinds S8c wordt elke regel met minstens één bewerking een {@code CHAIN} in de legacy-volgorde (Positie/Lengte
 *       → {@code SUBSTRING}, prefix/suffix → {@code AFFIX}, CALC 1-4/A/7/14/15 → één waardebewerking); een vaste
 *       waarde negeert de rest ({@value #LEGACY_IGNORED_WITH_FIXED_VALUE}). CALC {@code '0'} betekent "geen berekening"
 *       ({@code Prg_1170} r.732: {@code CALCULATION<>'' AND <>'0'}). Een andere CALC laat de mapping vervallen met een
 *       waarschuwing.</li>
 *   <li><b>CALC 14:</b> {@code MAP_CONTAINS} op enkel de PSIMPDEFMAP-rijen van de eigen NUMMER in KEY-volgorde
 *       ({@code MapInstr}, {@code Prg_1170} r.1128-1167); een rij met lege VALUE of lege KEY wint in legacy nooit en wordt
 *       niet overgenomen ({@value #MAP_CONTAINS_ROW_NOT_MIGRATED}). Een miss geeft in legacy {@code ''}, bij ons een
 *       fout ({@value #MAP_CONTAINS_MISS_NOT_EMPTY}).</li>
 *   <li><b>CALC 7:</b> PSIMPDEFMAP wordt in legacy enkel op (IMPORT, KEY) gezocht, los van NUMMER ({@code Prg_1170}
 *       r.1009-1054): alle regels vormen één tabel, bij een dubbele sleutel wint de laagste NUMMER
 *       ({@value #MAP_KEY_AMBIGUOUS}). Sinds S8a staat die tabel in {@code import_field_value_map} (geen grens van
 *       1000 tekens, geen onuitdrukbare {@code ;|>} meer), hoofdlettergevoelig ({@code caseSensitive=true}, aanname
 *       design S8 par. 2a). Een miss geeft in legacy de bronwaarde; sinds S8g (V1 = B) zet de
 *       migratie {@code onMiss=KEEP_SOURCE}: de bronwaarde gaat door en wordt per regel gemeld
 *       ({@code MAPPING_VALUE_PASSED_THROUGH}); het rapport noemt dit informatief
 *       ({@value #MAP_MISS_KEEPS_SOURCE}).</li>
 *   <li><b>Include/Exclude 1:</b> filter {@code INCLUDE}/{@code EQUALS} op de kolom van de regel. Meer dan één
 *       include-regel blokkeert: legacy combineert ze (elke regel moet kloppen), onze INCLUDE-filters staan in
 *       OF-verhouding.</li>
 *   <li><b>PSIMPEXCL (S8f):</b> per rij een EN-groep ({@code match_group}) van {@code EXCLUDE EQUALS}-rijen op de
 *       identiteitskolommen en de kolom van regel 23, in de vijf vormen van {@code Prg_1170} r.2640-2686; dode rijen,
 *       niet-uitdrukbare kortingscodes en {@code Skip_Excludes=Y} worden gemeld, nooit stil weggelaten (zie
 *       {@code excludeGroups}).</li>
 *   <li><b>INDET:</b> {@code Separator} Comma/Semicolon/Tab, afwezig → {@code ;} met waarschuwing ({@code Prg_1232}
 *       r.1194); {@code FILE_ENCODING} leeg → {@value #CHARSET_NOT_DECLARED} tenzij {@code charsetWhenUndeclared};
 *       {@code SkipHeaderLines} n ≥ 1 → header op regel n; {@code DecimalComma=Y} → {@code decimalSeparator=,} op de
 *       numerieke mappings.</li>
 * </ul>
 * Financiële velden worden nooit stil aangepast: rekenkundige CALC-bewerkingen krijgen altijd
 * {@value #CALC_ROUNDING_MAY_DIFFER} (legacy rekent met {@code Val(..,'8.2')} en {@code Str(..,'8.3')}).
 */
final class LegacyConfigTranslator {

    // --- Codes ----------------------------------------------------------------------------------------------------

    static final String DEFINITION_NOT_PRESENT = "DEFINITION_NOT_PRESENT";
    static final String LEGACY_CONTROL_CODE_NOT_MIGRATED = "LEGACY_CONTROL_CODE_NOT_MIGRATED";
    static final String SUPPLIER_NOT_DECLARED = "SUPPLIER_NOT_DECLARED";
    static final String SUPPLIER_CODE_TOO_LONG = "SUPPLIER_CODE_TOO_LONG";
    static final String PRODIS_REFERENCE_NOT_VERIFIED = "PRODIS_REFERENCE_NOT_VERIFIED";
    static final String LIBRARY_NOT_DECLARED = "LIBRARY_NOT_DECLARED";
    static final String INTERFACE_SETTING_DUPLICATED = "INTERFACE_SETTING_DUPLICATED";
    static final String SEPARATOR_NOT_DECLARED = "SEPARATOR_NOT_DECLARED";
    static final String STRUCTURE_FORMAT_UNSUPPORTED = "STRUCTURE_FORMAT_UNSUPPORTED";
    static final String CHARSET_NOT_DECLARED = "CHARSET_NOT_DECLARED";
    static final String CHARSET_UNSUPPORTED = "CHARSET_UNSUPPORTED";
    static final String SKIP_HEADER_LINES_INVALID = "SKIP_HEADER_LINES_INVALID";
    static final String DECIMAL_COMMA_BASE_PRICE = "DECIMAL_COMMA_BASE_PRICE";
    static final String IDENTITY_PROFILE_UNSUPPORTED = "IDENTITY_PROFILE_UNSUPPORTED";
    static final String CURRENCY_RATE_NOT_SUPPORTED = "CURRENCY_RATE_NOT_SUPPORTED";
    static final String CONNECTION_NOT_MIGRATED = "CONNECTION_NOT_MIGRATED";
    static final String PUBLICATION_PARAMETER_NOT_MIGRATED = "PUBLICATION_PARAMETER_NOT_MIGRATED";
    static final String INTERFACE_SETTING_NOT_MIGRATED = "INTERFACE_SETTING_NOT_MIGRATED";
    static final String SECRET_LABEL_OMITTED = "SECRET_LABEL_OMITTED";
    static final String SCHEDULE_NOT_MIGRATED = "SCHEDULE_NOT_MIGRATED";
    static final String IDENTITY_FIELD_MISSING = "IDENTITY_FIELD_MISSING";
    static final String IDENTITY_FIXED_VALUE_UNSUPPORTED = "IDENTITY_FIXED_VALUE_UNSUPPORTED";
    // S6: vaste leverancier/groep (PSIMPDEF 9/10 met een vaste "Waarde") en hoofdletters.
    static final String IDENTITY_FIXED_VALUE = "IDENTITY_FIXED_VALUE";
    static final String IDENTITY_FIXED_VALUE_TOO_LONG = "IDENTITY_FIXED_VALUE_TOO_LONG";
    static final String LEGACY_IGNORED_WITH_FIXED_VALUE = "LEGACY_IGNORED_WITH_FIXED_VALUE";
    static final String IDENTITY_UPPERCASE = "IDENTITY_UPPERCASE";
    static final String IDENTITY_TRANSFORM_UNSUPPORTED = "IDENTITY_TRANSFORM_UNSUPPORTED";
    static final String DISCOUNT_CODE_NOT_IN_KEY = "DISCOUNT_CODE_NOT_IN_KEY";
    static final String BASE_PRICE_FIELD_MISSING = "BASE_PRICE_FIELD_MISSING";
    static final String BASE_PRICE_TRANSFORM_UNSUPPORTED = "BASE_PRICE_TRANSFORM_UNSUPPORTED";
    static final String DESCRIPTION_TRANSFORM_NOT_MIGRATED = "DESCRIPTION_TRANSFORM_NOT_MIGRATED";
    static final String LEGACY_FIELD_DUPLICATED = "LEGACY_FIELD_DUPLICATED";
    static final String LEGACY_FIELD_NOT_MIGRATED = "LEGACY_FIELD_NOT_MIGRATED";
    static final String LEGACY_LINE_WITHOUT_SOURCE = "LEGACY_LINE_WITHOUT_SOURCE";
    /** Sinds S8c niet meer gezet (substring naast een vaste waarde: {@value #LEGACY_IGNORED_WITH_FIXED_VALUE}). */
    static final String LEGACY_SUBSTRING_NOT_MIGRATED = "LEGACY_SUBSTRING_NOT_MIGRATED";
    static final String LEGACY_FILL_NOT_MIGRATED = "LEGACY_FILL_NOT_MIGRATED";
    static final String LEGACY_XML_PATH_NOT_MIGRATED = "LEGACY_XML_PATH_NOT_MIGRATED";
    /** Sinds S8c niet meer gezet: elke combinatie wordt een CHAIN; de code blijft herkenbaar in oudere rapporten. */
    static final String TRANSFORM_CHAIN_UNSUPPORTED = "TRANSFORM_CHAIN_UNSUPPORTED";
    /** S8b: Positie/Lengte van een mapping-regel is negatief; geen betekenis in legacy {@code MID}. */
    static final String LEGACY_SUBSTRING_INVALID = "LEGACY_SUBSTRING_INVALID";
    static final String TRANSFORM_VALUE_NOT_EXPRESSIBLE = "TRANSFORM_VALUE_NOT_EXPRESSIBLE";
    static final String LEGACY_CALC_VALUE_INVALID = "LEGACY_CALC_VALUE_INVALID";
    static final String CALC_ROUNDING_MAY_DIFFER = "CALC_ROUNDING_MAY_DIFFER";
    /** Tot S8g: een CALC 7-miss werd verworpen. Blijft als constante voor oudere rapporten; wordt niet meer gemeld. */
    static final String MAP_MISS_NO_FALLBACK = "MAP_MISS_NO_FALLBACK";
    /** S8g (V1 = B): CALC 7 migreert met {@code onMiss=KEEP_SOURCE}; informatief, de bronwaarde gaat bij een miss door. */
    static final String MAP_MISS_KEEPS_SOURCE = "MAP_MISS_KEEPS_SOURCE";
    static final String MAP_EMPTY = "MAP_EMPTY";
    static final String MAP_KEY_AMBIGUOUS = "MAP_KEY_AMBIGUOUS";
    /** S8a: een PSIMPDEFMAP-sleutel of -waarde langer dan de 50 tekens van legacy (DS_0254); nooit afgekapt. */
    static final String MAP_ENTRY_TOO_LONG = "MAP_ENTRY_TOO_LONG";
    static final String LEGACY_MAP_UNUSED = "LEGACY_MAP_UNUSED";
    /** S8d: legacy CALC 14 geeft {@code ''} bij een miss; CatalogImport verwerpt de regel (V1 = B, nooit een stille lege waarde). */
    static final String MAP_CONTAINS_MISS_NOT_EMPTY = "MAP_CONTAINS_MISS_NOT_EMPTY";
    /** S8d: PSIMPDEFMAP-rijen van een CALC 14 zonder sleutel of zonder waarde winnen in legacy nooit; niet overgenomen. */
    static final String MAP_CONTAINS_ROW_NOT_MIGRATED = "MAP_CONTAINS_ROW_NOT_MIGRATED";
    static final String INCLUDE_FILTER_EMPTY_VALUE_NOT_MIGRATED = "INCLUDE_FILTER_EMPTY_VALUE_NOT_MIGRATED";
    static final String INCLUDE_FILTER_WITHOUT_COLUMN = "INCLUDE_FILTER_WITHOUT_COLUMN";
    static final String INCLUDE_EXCLUDE_CODE_NOT_MIGRATED = "INCLUDE_EXCLUDE_CODE_NOT_MIGRATED";
    /** Sinds S8e niet meer gezet (twee includes = EN via De Morgan); de constante blijft voor oudere rapporten. */
    static final String INCLUDE_FILTER_COMBINATION_UNSUPPORTED = "INCLUDE_FILTER_COMBINATION_UNSUPPORTED";
    /** S8e: Positie/Lengte op een Include/Exclude-regel (legacy neemt de MID van de waarde); de filter wordt niet gemaakt. */
    static final String INCLUDE_EXCLUDE_SUBSTRING_NOT_MIGRATED = "INCLUDE_EXCLUDE_SUBSTRING_NOT_MIGRATED";
    /** S8e: een Include/Exclude-regel met vaste Waarde; legacy vergelijkt dan {@code ''}, wij de bronkolom. */
    static final String INCLUDE_EXCLUDE_WITH_FIXED_VALUE = "INCLUDE_EXCLUDE_WITH_FIXED_VALUE";
    /** S8e: codes 3 en 4 vergelijken in legacy ongetrimd op een opgevuld Alpha-veld; de padding is niet geverifieerd. */
    static final String LEGACY_FILTER_PADDING_UNVERIFIED = "LEGACY_FILTER_PADDING_UNVERIFIED";
    /** Sinds S8f niet meer gezet (PSIMPEXCL wordt gemigreerd); blijft voor oudere rapporten. */
    static final String LEGACY_EXCLUDES_NOT_MIGRATED = "LEGACY_EXCLUDES_NOT_MIGRATED";
    /** S8f: INDET {@code Skip_Excludes=Y}: legacy past PSIMPEXCL niet toe ({@code Prg_1170} r.395), wij dus ook niet. */
    static final String LEGACY_EXCLUDES_SKIPPED = "LEGACY_EXCLUDES_SKIPPED";
    /** S8f: een PSIMPEXCL-rij die door de vaste delen van de identiteit elk record zou uitsluiten (BLOCKING). */
    static final String LEGACY_EXCLUDE_EXCLUDES_ALL = "LEGACY_EXCLUDE_EXCLUDES_ALL";
    /** S8f: een PSIMPEXCL-rij die in legacy nooit een record treft (dode rij); niet gemigreerd. */
    static final String LEGACY_EXCLUDE_NEVER_MATCHES = "LEGACY_EXCLUDE_NEVER_MATCHES";
    /** S8f: de rij vergelijkt de kortingscode, maar regel 23 heeft geen bronkolom of een transformatie (WARNING). */
    static final String LEGACY_EXCLUDE_NOT_EXPRESSIBLE = "LEGACY_EXCLUDE_NOT_EXPRESSIBLE";

    // --- Legacy-gegevens ------------------------------------------------------------------------------------------

    static final String LABEL_DEFINITION = "ArtImp_Importsoort";
    static final String LABEL_SUPPLIER = "Supplier";
    static final String LABEL_LIBRARY = "ArtImp_BibNr";
    static final String LABEL_SEPARATOR = "Separator";
    static final String LABEL_ENCODING = "FILE_ENCODING";
    static final String LABEL_SKIP_HEADER = "SkipHeaderLines";
    static final String LABEL_DECIMAL_COMMA = "DecimalComma";
    static final String LABEL_KEY_SUPPLIER = "ArtImp_Key_Lev";
    static final String LABEL_KEY_REFERENCE = "ArtImp_Key_RefLev";
    static final String LABEL_KEY_GROUP = "ArtImp_Key_LevGrp";
    static final String LABEL_KEY_DISCOUNT = "ArtImp_Key_Korting";
    static final String LABEL_KEY_BARCODE = "ArtImp_Key_Barcode";
    static final String LABEL_RATE = "ArtImp_Para_Koers";
    /** S8f: {@code Prg_1232} r.444/518 ({@code [V_Skip_Excludes]='Y'}), {@code Prg_1170} r.395. */
    static final String LABEL_SKIP_EXCLUDES = "Skip_Excludes";
    private static final List<String> CONNECTION_LABELS =
            List.of("FTP", "FTP2", "FTP_login", "FTP_directory", "FTP_filename");
    /** Standaardpoorten in het voorstel voor een FTP-/API-bestemming (LF); SFTP gebruikt {@code DEFAULT_PORT} (22). */
    private static final int FTP_DEFAULT_PORT = 21;
    private static final int HTTP_DEFAULT_PORT = 80;
    private static final int HTTPS_DEFAULT_PORT = 443;
    private static final Set<String> GOVERNED_LABELS = Set.of(LABEL_DEFINITION, LABEL_SUPPLIER, LABEL_LIBRARY,
            LABEL_SEPARATOR, LABEL_ENCODING, LABEL_SKIP_HEADER, LABEL_DECIMAL_COMMA, LABEL_KEY_SUPPLIER,
            LABEL_KEY_REFERENCE, LABEL_KEY_GROUP, LABEL_KEY_DISCOUNT, LABEL_KEY_BARCODE, LABEL_RATE,
            LABEL_SKIP_EXCLUDES, "FTP", "FTP2", "FTP_login", "FTP_directory", "FTP_filename");
    private static final List<String> PUBLICATION_PREFIXES = List.of("ArtImp_", "MS_", "BIB_", "Email_", "RFtp_");

    static final int LINE_SUPPLIER = 9;
    static final int LINE_GROUP = 10;
    static final int LINE_REFERENCE = 11;
    static final int LINE_DISCOUNT = 23;
    static final int LINE_BASE_PRICE = 26;
    static final int LINE_DESCRIPTION = 4;

    /** {@code legacy-config-migratie-design.md} par. 1: legacy-veldnummer → catalogcode (B-rijen). */
    static final Map<Integer, String> MAPPING_TARGETS = Map.ofEntries(
            Map.entry(33, "VKP1_PCT"), Map.entry(34, "VKP2_PCT"), Map.entry(35, "VKP3_PCT"),
            Map.entry(36, "VKP4_PCT"), Map.entry(37, "VKP5_PCT"), Map.entry(40, "AKP_PCT"),
            Map.entry(43, "VKP_GROSS_PCT"), Map.entry(12, "SUPPLIER_BARCODE"), Map.entry(219, "PIM_ID"),
            Map.entry(221, "E_SUPPLIER"));
    /** De numerieke doelvelden (data_type DECIMAL in de veldcatalogus, changeset 004-1b). */
    private static final Set<String> DECIMAL_TARGETS =
            Set.of("VKP1_PCT", "VKP2_PCT", "VKP3_PCT", "VKP4_PCT", "VKP5_PCT", "AKP_PCT", "VKP_GROSS_PCT");

    /** {@code import_field_mapping.fixed_value} varchar(500). */
    private static final int MAX_FIXED_VALUE = 500;
    /** {@code import_record_filter.compare_value} varchar(500). */
    private static final int MAX_COMPARE_VALUE = 500;
    /** {@code source_organisation.code}. */
    private static final int MAX_SUPPLIER_CODE = 50;
    /** {@code identity_supplier_fixed_value} / {@code identity_supplier_group_fixed_value} zijn varchar(50) (022-1). */
    private static final int MAX_IDENTITY_FIXED_VALUE = 50;
    /** {@code import_definition.description}. */
    private static final int MAX_DEFINITION_NAME = 200;

    static final String FIELD_REFERENCE_KIND = "COLUMN_INDEX";

    // --- Uitkomst -------------------------------------------------------------------------------------------------

    /**
     * Eén geplande veldmapping, met precies wat {@code SetupService.addMapping} krijgt.
     *
     * @param valueMap S8a/S8d: de vertaalrijen van een CALC 7- of CALC 14-mapping ({@code import_field_value_map});
     *                 anders leeg
     * @param steps    S8c: de ketenstappen van een CHAIN-mapping ({@code import_field_transform_step}); anders leeg
     */
    record PlannedMapping(int sequence, String targetCode, FieldValueKind valueKind, String sourceReference,
                          String fixedValue, FieldTransformKind transformKind, String transformConfig,
                          List<PlannedValueMapEntry> valueMap, List<PlannedStep> steps) {

        PlannedMapping {
            valueMap = valueMap == null ? List.of() : List.copyOf(valueMap);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }

        /** De vorm van vóór S8c: geen ketenstappen. */
        PlannedMapping(int sequence, String targetCode, FieldValueKind valueKind, String sourceReference,
                       String fixedValue, FieldTransformKind transformKind, String transformConfig,
                       List<PlannedValueMapEntry> valueMap) {
            this(sequence, targetCode, valueKind, sourceReference, fixedValue, transformKind, transformConfig,
                    valueMap, List.of());
        }
    }

    /** S8c: één geplande ketenstap; {@code number} 1-gebaseerd in de vaste legacy-volgorde. */
    record PlannedStep(int number, FieldTransformKind kind, String config) {
    }

    /** S8a: één geplande vertaalrij; {@code sequence} 1-gebaseerd in legacy-volgorde (NUMMER, KEY). */
    record PlannedValueMapEntry(int sequence, String source, String target) {
    }

    /**
     * Eén gepland recordfilter, met precies wat {@code SetupService.addFilter} krijgt.
     *
     * @param matchGroup S8f: de EN-groep van een PSIMPEXCL-rij ({@code import_record_filter.match_group}); anders null
     */
    record PlannedFilter(int sequence, String sourceReference, FilterOperator operator, String compareValue,
                         FilterOutcome outcome, boolean caseSensitive, FilterNullBehaviour nullBehaviour,
                         Integer matchGroup) {

        /** De vorm van vóór S8f: een losse rij. */
        PlannedFilter(int sequence, String sourceReference, FilterOperator operator, String compareValue,
                      FilterOutcome outcome, boolean caseSensitive, FilterNullBehaviour nullBehaviour) {
            this(sequence, sourceReference, operator, compareValue, outcome, caseSensitive, nullBehaviour, null);
        }
    }

    /**
     * De door legacy bepaalde inhoud van een revisie; {@code descriptionField}/{@code discountField} mogen null zijn.
     * Sinds S6: precies één van {@code supplierField}/{@code supplierFixedValue} en van {@code groupField}/
     * {@code groupFixedValue} is gevuld, en {@code identityUppercase} is altijd {@code true} (legacy {@code Upper()}).
     */
    record PlannedRevision(String charset, String delimiter, boolean hasHeader, int headerLineNumber,
                           IdentityProfileKind identityProfileKind, String supplierField, String groupField,
                           String referenceField, String discountField, String basePriceField,
                           String descriptionField, String supplierFixedValue, String groupFixedValue,
                           boolean identityUppercase, List<PlannedMapping> mappings, List<PlannedFilter> filters) {

        PlannedRevision {
            mappings = List.copyOf(mappings);
            filters = List.copyOf(filters);
        }
    }

    /**
     * @param supplierCode       INDET {@code Supplier} (de code van de bronorganisatie), of {@code null}
     * @param libraryNumber      INDET {@code ArtImp_BibNr} (enkel in het rapport; nooit de library_code), of null
     * @param definitionCode     {@code definition.code}, of {@code null} als de definitie ontbreekt
     * @param definitionName     de naam voor {@code import_definition.description}
     * @param revision           de geplande revisie, of {@code null} als ze niet op te bouwen is (dan is er een
     *                           blokkerend item)
     * @param supplierFixedValue de vaste waarde van PSIMPDEF 9 (S6), ook als de revisie elders blokkeert; of null
     * @param groupFixedValue    idem voor PSIMPDEF 10
     */
    record Translation(List<Item> items, String supplierCode, String libraryNumber, String definitionCode,
                       String definitionName, String definitionContentHash, PlannedRevision revision,
                       String supplierFixedValue, String groupFixedValue) {

        boolean blocking() {
            return items.stream().anyMatch(item -> BLOCKING.equals(item.status()));
        }
    }

    private final JsonNode bundle;
    private final String charsetWhenUndeclared;
    private final UUID credentialRef;
    private final List<Item> items = new ArrayList<>();
    private boolean revisionBlocked;
    /** S6: de vaste waarde van PSIMPDEF 9/10 zoals vertaald (ook als de revisie elders blokkeert), of null. */
    private String supplierFixedValue;
    private String groupFixedValue;

    private LegacyConfigTranslator(JsonNode bundle, String charsetWhenUndeclared, UUID credentialRef) {
        this.bundle = bundle;
        this.charsetWhenUndeclared = charsetWhenUndeclared == null || charsetWhenUndeclared.isBlank() ? null
                : charsetWhenUndeclared.trim();
        this.credentialRef = credentialRef;
    }

    /**
     * @param bundle                de bundel zoals bewaard in {@code bundle_json} (gevalideerd bij ontvangst)
     * @param charsetWhenUndeclared de door de mens gekozen tekenset als {@code FILE_ENCODING} leeg is, of null
     */
    static Translation translate(JsonNode bundle, String charsetWhenUndeclared) {
        return translate(bundle, charsetWhenUndeclared, null);
    }

    /**
     * @param credentialRef de credential van de herkomst (slice S4), of null; enkel voor het verbindingsvoorstel in het
     *                      rapport ({@value #CONNECTION_NOT_MIGRATED}), nooit een waarde
     */
    static Translation translate(JsonNode bundle, String charsetWhenUndeclared, UUID credentialRef) {
        return new LegacyConfigTranslator(bundle, charsetWhenUndeclared, credentialRef).run();
    }

    /**
     * S7 ({@code sharedWith}): de INDET {@code Supplier} van een bewaarde bundel, zonder vertaling; {@code null} als het
     * label ontbreekt, leeg is of meer dan eens voorkomt (dezelfde waarde als {@link Translation#supplierCode()} zou
     * geven, of geen).
     */
    static String supplierOf(JsonNode bundle) {
        String found = null;
        for (JsonNode detail : bundle.path("interface").path("details")) {
            if (LABEL_SUPPLIER.equals(detail.path("label").asText())) {
                if (found != null) {
                    return null;
                }
                found = detail.path("value").asText("");
            }
        }
        return found == null || found.isEmpty() || found.length() > MAX_SUPPLIER_CODE ? null : found;
    }

    private Translation run() {
        String status = bundle.path("definitionStatus").asText();
        JsonNode definition = bundle.path("definition");
        Map<String, List<JsonNode>> details = detailsByLabel();

        String supplierCode = supplier(details);
        String libraryNumber = library(details);
        boolean present = "PRESENT".equals(status) && definition.isObject();
        String definitionCode = null;
        String definitionName = null;
        if (!present) {
            add("BUNDLE:definitionStatus", BLOCKING, DEFINITION_NOT_PRESENT, null, "definitionStatus is " + status
                    + "; without a legacy definition there is nothing to migrate");
            revisionBlocked = true;
        } else {
            definitionCode = definition.path("code").asText();
            definitionName = definitionName(definition, definitionCode);
            add("PSIMDHFD", MIGRATED, "DEFINITION", "import_definition.code=" + definitionCode, null);
            for (String control : List.of("updateFile", "replacementCode", "deleteCode")) {
                String value = definition.path(control).asText("");
                if (!value.isEmpty()) {
                    add("PSIMDHFD:" + control, NOT_MIGRATED, LEGACY_CONTROL_CODE_NOT_MIGRATED, null,
                            control + " is a legacy control code (Prg_1176 r.77), not a definition setting");
                }
            }
        }

        // INDET: structuur en sleutelprofiel.
        String delimiter = separator(details);
        String charset = charset(details);
        Integer skipHeader = skipHeaderLines(details);
        boolean decimalComma = "Y".equals(single(details, LABEL_DECIMAL_COMMA));
        IdentityProfileKind profile = identityProfile(details);
        boolean skipExcludes = "Y".equals(single(details, LABEL_SKIP_EXCLUDES));
        rate(details);
        otherDetails(details);
        schedule();

        PlannedRevision revision = null;
        if (present) {
            revision = lines(definition, profile, decimalComma, delimiter, charset, skipHeader, skipExcludes);
        }
        if (revisionBlocked) {
            revision = null;
        }
        return new Translation(List.copyOf(items), supplierCode, libraryNumber, definitionCode, definitionName,
                present ? bundle.path("definitionContentHash").asText(null) : null, revision, supplierFixedValue,
                groupFixedValue);
    }

    // --- INDET ----------------------------------------------------------------------------------------------------

    private Map<String, List<JsonNode>> detailsByLabel() {
        List<JsonNode> sorted = new ArrayList<>();
        bundle.path("interface").path("details").forEach(sorted::add);
        sorted.sort(Comparator.comparingInt((JsonNode d) -> d.path("sequence").asInt())
                .thenComparing(d -> d.path("label").asText()));
        Map<String, List<JsonNode>> byLabel = new LinkedHashMap<>();
        for (JsonNode detail : sorted) {
            byLabel.computeIfAbsent(detail.path("label").asText(), key -> new ArrayList<>()).add(detail);
        }
        return byLabel;
    }

    /**
     * De waarde van een gestuurd label, of {@code null} als het ontbreekt. Een label dat meer dan eens voorkomt, is
     * niet eenduidig ({@code Get Interfaceparameter} leest er één): blokkerend.
     */
    private String single(Map<String, List<JsonNode>> details, String label) {
        List<JsonNode> rows = details.get(label);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (rows.size() > 1) {
            add("INDET:" + label, BLOCKING, INTERFACE_SETTING_DUPLICATED, null, "label " + label + " occurs "
                    + rows.size() + " times; legacy reads only one of them, which one is not defined");
            revisionBlocked = true;
        }
        return rows.get(0).path("value").asText("");
    }

    private String supplier(Map<String, List<JsonNode>> details) {
        String value = single(details, LABEL_SUPPLIER);
        if (value == null || value.isEmpty()) {
            add("INDET:" + LABEL_SUPPLIER, BLOCKING, SUPPLIER_NOT_DECLARED, null,
                    "the interface has no Supplier; the source organisation and the link cannot be determined");
            return null;
        }
        if (value.length() > MAX_SUPPLIER_CODE) {
            add("INDET:" + LABEL_SUPPLIER, BLOCKING, SUPPLIER_CODE_TOO_LONG, null,
                    "Supplier is longer than " + MAX_SUPPLIER_CODE + " characters");
            return null;
        }
        add("INDET:" + LABEL_SUPPLIER, MIGRATED, "SOURCE_ORGANISATION", "source_organisation.code=" + value, null);
        // S5: LegacyConfigApplyService vervangt dit item door de uitkomst van de Prodis-controle als die geconfigureerd is.
        add("INDET:" + LABEL_SUPPLIER, WARNING, PRODIS_REFERENCE_NOT_VERIFIED, null,
                "supplier " + value + " is not verified: the Prodis database is not configured; the name of a new "
                        + "source organisation is a placeholder");
        return value;
    }

    private String library(Map<String, List<JsonNode>> details) {
        String value = single(details, LABEL_LIBRARY);
        if (value == null || value.isEmpty()) {
            add("INDET:" + LABEL_LIBRARY, BLOCKING, LIBRARY_NOT_DECLARED, null,
                    "the interface has no ArtImp_BibNr; the library of the link cannot be determined");
            return null;
        }
        return value;
    }

    private String separator(Map<String, List<JsonNode>> details) {
        String value = single(details, LABEL_SEPARATOR);
        String element = "INDET:" + LABEL_SEPARATOR;
        if (value == null || value.isEmpty()) {
            add(element, WARNING, SEPARATOR_NOT_DECLARED, "revision.structureDelimiter=;",
                    "no Separator declared; legacy Prg_1232 r.1194 then reads the file with a semicolon");
            return ";";
        }
        String delimiter = switch (value) {
            case "Comma" -> ",";
            case "Semicolon" -> ";";
            case "Tab" -> "\t";
            default -> null;
        };
        if (delimiter == null) {
            add(element, BLOCKING, STRUCTURE_FORMAT_UNSUPPORTED, null, "Separator '" + value
                    + "' is not supported; only Comma, Semicolon and Tab (CSV) can be migrated");
            revisionBlocked = true;
            return null;
        }
        add(element, MIGRATED, "DELIMITER", "revision.structureDelimiter=" + ("\t".equals(delimiter) ? "TAB"
                : delimiter), null);
        return delimiter;
    }

    private String charset(Map<String, List<JsonNode>> details) {
        String value = single(details, LABEL_ENCODING);
        String element = "INDET:" + LABEL_ENCODING;
        if (value == null || value.isEmpty()) {
            if (charsetWhenUndeclared == null) {
                add(element, BLOCKING, CHARSET_NOT_DECLARED, null, "no FILE_ENCODING declared; the encoding is "
                        + "never guessed: choose one with charsetWhenUndeclared");
                revisionBlocked = true;
                return null;
            }
            if (!supported(charsetWhenUndeclared)) {
                add(element, BLOCKING, CHARSET_UNSUPPORTED, null,
                        "charsetWhenUndeclared '" + charsetWhenUndeclared + "' is not a known character set");
                revisionBlocked = true;
                return null;
            }
            add(element, WARNING, CHARSET_NOT_DECLARED, "revision.structureCharset=" + charsetWhenUndeclared,
                    "no FILE_ENCODING declared; the character set was chosen by the person applying");
            return charsetWhenUndeclared;
        }
        if (!supported(value)) {
            add(element, BLOCKING, CHARSET_UNSUPPORTED, null, "FILE_ENCODING '" + value
                    + "' is not a character set this application knows; it is never guessed");
            revisionBlocked = true;
            return null;
        }
        add(element, MIGRATED, "CHARSET", "revision.structureCharset=" + value, null);
        return value;
    }

    private static boolean supported(String name) {
        try {
            return Charset.isSupported(name);
        } catch (IllegalArgumentException invalidName) {
            return false;
        }
    }

    private Integer skipHeaderLines(Map<String, List<JsonNode>> details) {
        String value = single(details, LABEL_SKIP_HEADER);
        String element = "INDET:" + LABEL_SKIP_HEADER;
        if (value == null || value.isEmpty()) {
            add(element, MIGRATED, "HEADER", "revision.structureHasHeader=false",
                    "no SkipHeaderLines: legacy reads every line (Prg_1232 r.1198)");
            return 0;
        }
        int lines;
        try {
            lines = Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            lines = -1;
        }
        if (lines < 0 || lines > 1000) {
            add(element, BLOCKING, SKIP_HEADER_LINES_INVALID, null,
                    "SkipHeaderLines '" + value + "' is not a number of lines");
            revisionBlocked = true;
            return null;
        }
        add(element, MIGRATED, "HEADER", lines == 0 ? "revision.structureHasHeader=false"
                : "revision.structureHasHeader=true;headerLineNumber=" + lines, null);
        return lines;
    }

    private IdentityProfileKind identityProfile(Map<String, List<JsonNode>> details) {
        boolean supported = true;
        for (String label : List.of(LABEL_KEY_SUPPLIER, LABEL_KEY_REFERENCE, LABEL_KEY_GROUP)) {
            String value = single(details, label);
            if (!"Y".equals(value)) {
                add("INDET:" + label, BLOCKING, IDENTITY_PROFILE_UNSUPPORTED, null, label + " is "
                        + (value == null ? "absent (legacy reads that as N, Prg_1232 r.518)" : "'" + value + "'")
                        + "; an identity without supplier, supplier group and supplier reference is not supported "
                        + "(decisions 2026-09-18)");
                supported = false;
            }
        }
        if ("Y".equals(single(details, LABEL_KEY_BARCODE))) {
            add("INDET:" + LABEL_KEY_BARCODE, BLOCKING, IDENTITY_PROFILE_UNSUPPORTED, null,
                    "an identity with the barcode is not supported (decisions 2026-09-18)");
            supported = false;
        }
        if (!supported) {
            revisionBlocked = true;
            return null;
        }
        IdentityProfileKind kind = "Y".equals(single(details, LABEL_KEY_DISCOUNT))
                ? IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE : IdentityProfileKind.THREE_PART;
        add("INDET:ArtImp_Key_*", MIGRATED, "IDENTITY_PROFILE", "revision.identityProfileKind=" + kind, null);
        return kind;
    }

    private void rate(Map<String, List<JsonNode>> details) {
        String value = single(details, LABEL_RATE);
        if (value == null || value.isEmpty()) {
            return;
        }
        BigDecimal rate;
        try {
            rate = new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException notANumber) {
            rate = null;
        }
        if (rate == null || (rate.signum() != 0 && rate.compareTo(BigDecimal.ONE) != 0)) {
            add("INDET:" + LABEL_RATE, BLOCKING, CURRENCY_RATE_NOT_SUPPORTED, null, "exchange rate '" + value
                    + "' would convert prices; a financial conversion is not migrated (Q7, assumption A5)");
            revisionBlocked = true;
        }
    }

    private void otherDetails(Map<String, List<JsonNode>> details) {
        StringBuilder connection = new StringBuilder();
        for (String label : CONNECTION_LABELS) {
            // Enkel voor het voorstel in het rapport: een dubbel label blokkeert hier niets.
            for (JsonNode row : details.getOrDefault(label, List.of())) {
                String value = row.path("value").asText("");
                if (!value.isEmpty()) {
                    connection.append(connection.isEmpty() ? "" : ", ").append(label).append('=').append(value);
                }
            }
        }
        if (!connection.isEmpty() || credentialRef != null) {
            boolean datePattern = connection.indexOf("[YYYY]") >= 0 || connection.indexOf("[YY]") >= 0
                    || connection.indexOf("[MM]") >= 0 || connection.indexOf("[DD]") >= 0;
            add("INDET:FTP", NOT_MIGRATED, CONNECTION_NOT_MIGRATED,
                    credentialRef == null ? null : "external_credential.credentialRef=" + credentialRef,
                    "no connection profile or delivery configuration is created (a host key is required, L5); "
                            + "proposal: " + connectionProposal(details) + " (legacy: "
                            + (connection.isEmpty() ? "no FTP settings" : connection) + ")"
                            + (datePattern ? "; the file name contains a date pattern, to be expressed as a file "
                            + "condition" : ""));
        }
        for (Map.Entry<String, List<JsonNode>> entry : details.entrySet()) {
            String label = entry.getKey();
            if (GOVERNED_LABELS.contains(label)) {
                continue;
            }
            boolean publication = PUBLICATION_PREFIXES.stream().anyMatch(label::startsWith);
            add("INDET:" + label, NOT_MIGRATED, publication ? PUBLICATION_PARAMETER_NOT_MIGRATED
                    : INTERFACE_SETTING_NOT_MIGRATED, null, null);
        }
        for (JsonNode omitted : bundle.path("interface").path("omittedSecretLabels")) {
            add("INDET:" + omitted.asText(), NOT_MIGRATED, SECRET_LABEL_OMITTED, null,
                    "a secret label is never exported; enter it by hand if it is needed");
        }
    }

    /**
     * Het voorstel voor een verbindingsprofiel (S4): protocol, host, poort en map uit {@code FTP}+{@code FTP2},
     * {@code FTP/LOCAL/API} en {@code SFTP} ({@link LegacyFtpLocation}, A7), login uit {@code FTP_login}, en de
     * credential van de herkomst. FTP (standaardpoort 21) en API (443 bij {@code https}, 80 bij {@code http}) zijn
     * enkel bewaring van het wachtwoord, nog zonder connector (beslissingslog 2026-10-22 "LF"). Geen waarde van een
     * secret: de bundel heeft er geen.
     */
    private String connectionProposal(Map<String, List<JsonNode>> details) {
        LegacyFtpLocation location = LegacyFtpLocation.of(bundle);
        StringBuilder proposal = new StringBuilder();
        switch (location.protocol()) {
            case SFTP -> {
                if (location.hasValidHost()) {
                    proposal.append("protocol=SFTP, host=").append(location.host()).append(", port=")
                            .append(location.port() == null ? ConnectionProfileService.DEFAULT_PORT + " (default)"
                                    : location.port())
                            .append(", directory=").append(location.directory().isEmpty() ? "(none)"
                                    : location.directory());
                } else {
                    proposal.append("protocol=SFTP, no valid host");
                }
            }
            case FTP -> retainedOnly(proposal, "FTP", location, FTP_DEFAULT_PORT, "no FTP connector yet");
            case API -> retainedOnly(proposal, "API", location,
                    "http".equals(location.scheme()) ? HTTP_DEFAULT_PORT : HTTPS_DEFAULT_PORT, "no API connector yet");
            case LOCAL -> proposal.append("protocol=LOCAL (FTP/LOCAL/API=L, a local file; no connection, no password "
                    + "retained)");
            case UNKNOWN -> proposal.append("protocol unknown (FTP+FTP2, FTP/LOCAL/API and SFTP do not determine one "
                    + "protocol; no password retained)");
            case NONE -> proposal.append("no destination (FTP and FTP2 are empty)");
        }
        String directoryLabel = firstValue(details, "FTP_directory");
        if (!directoryLabel.isEmpty()) {
            proposal.append(", FTP_directory=").append(directoryLabel);
        }
        String login = firstValue(details, "FTP_login");
        proposal.append(", login=").append(login.isEmpty() ? "(none)" : login);
        proposal.append(", credentialRef=").append(credentialRef == null ? "(none)" : credentialRef);
        return proposal.toString();
    }

    /**
     * Voorstel voor een FTP- of API-bestemming: enkel het wachtwoord wordt bewaard (beslissingslog 2026-10-22 "LF"), er
     * bestaat nog geen connector. Nooit {@link ConnectionProfileService#DEFAULT_PORT} (SFTP).
     */
    private static void retainedOnly(StringBuilder proposal, String protocol, LegacyFtpLocation location,
                                     int defaultPort, String note) {
        proposal.append("protocol=").append(protocol);
        if (location.hasValidHost()) {
            proposal.append(", host=").append(location.host()).append(", port=")
                    .append(location.port() == null ? defaultPort + " (default)" : location.port())
                    .append(", directory=").append(location.directory().isEmpty() ? "(none)" : location.directory());
        } else {
            proposal.append(", no valid host");
        }
        proposal.append(" (password retained, ").append(note).append(')');
    }

    private static String firstValue(Map<String, List<JsonNode>> details, String label) {
        List<JsonNode> rows = details.getOrDefault(label, List.of());
        return rows.isEmpty() ? "" : rows.get(0).path("value").asText("");
    }

    private void schedule() {
        JsonNode header = bundle.path("interface").path("header");
        add("INHEAD", NOT_MIGRATED, SCHEDULE_NOT_MIGRATED, "catalog_import_task.triggerType=MANUAL",
                "legacy planning (executeAutomatic=" + header.path("executeAutomatic").asBoolean() + ", every="
                        + header.path("executeEvery").asText() + ", time=" + header.path("executionTime").asText()
                        + ") is not migrated; the task is MANUAL");
    }

    // --- PSIMPDEF -------------------------------------------------------------------------------------------------

    private PlannedRevision lines(JsonNode definition, IdentityProfileKind profile, boolean decimalComma,
                                  String delimiter, String charset, Integer skipHeader, boolean skipExcludes) {
        TreeMap<Integer, List<JsonNode>> byNumber = new TreeMap<>();
        for (JsonNode line : definition.path("lines")) {
            byNumber.computeIfAbsent(line.path("number").asInt(), key -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<Integer, List<JsonNode>> entry : byNumber.entrySet()) {
            int number = entry.getKey();
            if (entry.getValue().size() > 1 && governed(number)) {
                add("PSIMPDEF#" + number, BLOCKING, LEGACY_FIELD_DUPLICATED, null, "field number " + number
                        + " occurs " + entry.getValue().size() + " times; which one legacy applies is not defined");
                revisionBlocked = true;
            }
        }

        IdentitySource supplier = identitySource(byNumber, LINE_SUPPLIER, "identitySupplierField",
                "identitySupplierFixedValue");
        IdentitySource group = identitySource(byNumber, LINE_GROUP, "identitySupplierGroupField",
                "identitySupplierGroupFixedValue");
        String reference = identityColumn(byNumber, LINE_REFERENCE, "identitySupplierReferenceField", true);
        String discount = null;
        if (profile == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE) {
            discount = identityColumn(byNumber, LINE_DISCOUNT, "identityDiscountCodeField", true);
        } else if (byNumber.containsKey(LINE_DISCOUNT)) {
            add("PSIMPDEF#" + LINE_DISCOUNT, NOT_MIGRATED, DISCOUNT_CODE_NOT_IN_KEY, null,
                    "ArtImp_Key_Korting is not Y, so the discount code is not part of the identity");
        }
        // S6: legacy zet leverancier, groep en referentie (9-11) altijd in hoofdletters (Prg_1170 r.733-815, ook een
        // vaste "Waarde"); een gemigreerde revisie krijgt daarom altijd identity_uppercase=true (design par. 5).
        add("PSIMPDEF:identityUppercase", MIGRATED, IDENTITY_UPPERCASE, "revision.identityUppercase=true",
                "legacy puts fields 9-11 in upper case (Upper()); so does CatalogImport before the identity is built");
        String basePrice = basePrice(byNumber);
        String description = description(byNumber);
        if (decimalComma) {
            add("INDET:" + LABEL_DECIMAL_COMMA, WARNING, DECIMAL_COMMA_BASE_PRICE, null,
                    "DecimalComma=Y is set as decimalSeparator=, on the numeric mappings; the base price has no "
                            + "notation setting and reads both comma and point as decimal mark, a thousands "
                            + "separator (1.234,50) is rejected");
        }

        List<PlannedMapping> mappings = new ArrayList<>();
        List<PlannedFilter> filters = new ArrayList<>();
        List<JsonNode> filterLines = new ArrayList<>();
        for (Map.Entry<Integer, List<JsonNode>> entry : byNumber.entrySet()) {
            int number = entry.getKey();
            for (JsonNode line : entry.getValue()) {
                ignoredAttributes(number, line);
                String includeExclude = line.path("includeExclude").asText("");
                if (FILTER_CODES.contains(includeExclude)) {
                    filterLines.add(line);
                } else if (!includeExclude.isEmpty() && !"0".equals(includeExclude)) {
                    add("PSIMPDEF#" + number, WARNING, INCLUDE_EXCLUDE_CODE_NOT_MIGRATED, null,
                            "Include/Exclude code " + includeExclude + " is unknown (legacy knows 1-6) and is not "
                                    + "migrated; records it filtered in legacy are not filtered");
                }
                if (identityOrRecordLine(number)) {
                    continue;
                }
                String target = MAPPING_TARGETS.get(number);
                if (target == null) {
                    add("PSIMPDEF#" + number, NOT_MIGRATED, LEGACY_FIELD_NOT_MIGRATED, null,
                            "legacy field " + number + " (" + line.path("field").asText() + ") has no target here");
                    continue;
                }
                if (entry.getValue().size() > 1) {
                    continue; // al blokkerend gemeld
                }
                PlannedMapping mapping = mapping(number, line, target, decimalComma, definition, mappings.size() + 1);
                if (mapping != null) {
                    mappings.add(mapping);
                }
            }
        }
        filters.addAll(includeExcludeFilters(filterLines));
        filters.addAll(excludeGroups(definition, byNumber, supplier, group, reference, skipExcludes,
                filters.size()));
        mapsUsage(definition, mappings);

        if (delimiter == null || charset == null || skipHeader == null || profile == null || supplier == null
                || group == null || reference == null || basePrice == null
                || (profile == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE && discount == null)) {
            revisionBlocked = true;
            return null;
        }
        boolean hasHeader = skipHeader > 0;
        return new PlannedRevision(charset, delimiter, hasHeader, hasHeader ? skipHeader : 1, profile,
                supplier.column(), group.column(), reference, discount, basePrice, description, supplier.fixedValue(),
                group.fixedValue(), true, mappings, filters);
    }

    /** Leverancier of groep: precies één van bronkolom of vaste waarde (S6). */
    private record IdentitySource(String column, String fixedValue) {
    }

    /**
     * PSIMPDEF 9/10 (S6, design par. 5): een vaste "Waarde" wordt {@code identity_*_fixed_value}; anders zoals
     * {@link #identityColumn}. Legacy negeert bij een vaste waarde de kolom, prefix/suffix en CALC van die regel
     * ({@code Prg_1170}: de kolomtak loopt enkel als de waarde leeg is) - hier ook, maar zichtbaar in het rapport. De
     * Prodis-controle doet {@code LegacyConfigApplyService}; zonder Prodis-bron blijft het item hieronder staan.
     *
     * @return {@code null} als de regel niet te vertalen is (er is dan een blokkerend item)
     */
    private IdentitySource identitySource(Map<Integer, List<JsonNode>> byNumber, int number, String target,
                                          String fixedTarget) {
        List<JsonNode> rows = byNumber.get(number);
        String value = rows == null || rows.size() != 1 ? "" : rows.get(0).path("value").asText("");
        if (value.isEmpty()) {
            String column = identityColumn(byNumber, number, target, true);
            return column == null ? null : new IdentitySource(column, null);
        }
        String element = "PSIMPDEF#" + number;
        String fixed = value.trim();
        if (fixed.isEmpty()) {
            add(element, BLOCKING, IDENTITY_FIELD_MISSING, null, "field " + number + " has a blank fixed value");
            return null;
        }
        if (fixed.length() > MAX_IDENTITY_FIXED_VALUE) {
            add(element, BLOCKING, IDENTITY_FIXED_VALUE_TOO_LONG, null, "the fixed value of field " + number
                    + " is longer than " + MAX_IDENTITY_FIXED_VALUE + " characters");
            return null;
        }
        JsonNode line = rows.get(0);
        if (line.path("column").asInt() > 0 || hasTransform(line) || hasSubstring(line)) {
            add(element, NOT_MIGRATED, LEGACY_IGNORED_WITH_FIXED_VALUE, null, "field " + number + " has a fixed "
                    + "value; legacy ignores its column and transformation then, and so does CatalogImport");
        }
        add(element, MIGRATED, IDENTITY_FIXED_VALUE, "revision." + fixedTarget + "=" + fixed, null);
        if (number == LINE_SUPPLIER) {
            supplierFixedValue = fixed;
        } else {
            groupFixedValue = fixed;
        }
        // Vervangen door LegacyConfigApplyService als de Prodis-bron geconfigureerd is (zoals INDET:Supplier, S5).
        add(element, WARNING, PRODIS_REFERENCE_NOT_VERIFIED, null, "fixed value " + fixed + " of field " + number
                + " is not verified: the Prodis database is not configured");
        return new IdentitySource(null, fixed);
    }

    private static boolean governed(int number) {
        return identityOrRecordLine(number) || MAPPING_TARGETS.containsKey(number);
    }

    private static boolean identityOrRecordLine(int number) {
        return number == LINE_SUPPLIER || number == LINE_GROUP || number == LINE_REFERENCE
                || number == LINE_DISCOUNT || number == LINE_BASE_PRICE || number == LINE_DESCRIPTION;
    }

    /** Een identiteitsregel: enkel een bronkolom, anders blokkerend. */
    private String identityColumn(Map<Integer, List<JsonNode>> byNumber, int number, String target,
                                  boolean blockIfMissing) {
        String element = "PSIMPDEF#" + number;
        List<JsonNode> rows = byNumber.get(number);
        if (rows == null || rows.isEmpty()) {
            if (blockIfMissing) {
                add(element, BLOCKING, IDENTITY_FIELD_MISSING, null, "field " + number
                        + " is not defined; the identity needs a source column for it");
            }
            return null;
        }
        if (rows.size() > 1) {
            return null;
        }
        JsonNode line = rows.get(0);
        String value = line.path("value").asText("");
        if (!value.isEmpty()) {
            add(element, BLOCKING, IDENTITY_FIXED_VALUE_UNSUPPORTED, null, "field " + number
                    + " has the fixed value '" + value + "'; a fixed identity value is only supported for the "
                    + "supplier and the supplier group (fields 9 and 10)");
            return null;
        }
        if (hasTransform(line) || hasSubstring(line)) {
            add(element, BLOCKING, IDENTITY_TRANSFORM_UNSUPPORTED, null, "field " + number + " is transformed ("
                    + transformDescription(line) + "); an identity field can only be a source column, dropping "
                    + "the transformation would change every identity");
            return null;
        }
        int column = line.path("column").asInt();
        if (column <= 0) {
            add(element, BLOCKING, IDENTITY_FIELD_MISSING, null, "field " + number + " has no source column");
            return null;
        }
        add(element, MIGRATED, "IDENTITY_FIELD", "revision." + target + "=" + column, null);
        return String.valueOf(column);
    }

    private String basePrice(Map<Integer, List<JsonNode>> byNumber) {
        String element = "PSIMPDEF#" + LINE_BASE_PRICE;
        List<JsonNode> rows = byNumber.get(LINE_BASE_PRICE);
        if (rows == null || rows.isEmpty()) {
            add(element, BLOCKING, BASE_PRICE_FIELD_MISSING, null, "field 26 (base price) is not defined");
            return null;
        }
        if (rows.size() > 1) {
            return null;
        }
        JsonNode line = rows.get(0);
        if (!line.path("value").asText("").isEmpty() || hasTransform(line) || hasSubstring(line)) {
            add(element, BLOCKING, BASE_PRICE_TRANSFORM_UNSUPPORTED, null, "the base price is transformed or "
                    + "fixed (" + transformDescription(line) + "); it can only be a source column and is never "
                    + "changed silently");
            return null;
        }
        int column = line.path("column").asInt();
        if (column <= 0) {
            add(element, BLOCKING, BASE_PRICE_FIELD_MISSING, null, "field 26 (base price) has no source column");
            return null;
        }
        add(element, MIGRATED, "BASE_PRICE", "revision.recordBasePriceField=" + column, null);
        return String.valueOf(column);
    }

    private String description(Map<Integer, List<JsonNode>> byNumber) {
        String element = "PSIMPDEF#" + LINE_DESCRIPTION;
        List<JsonNode> rows = byNumber.get(LINE_DESCRIPTION);
        if (rows == null || rows.isEmpty() || rows.size() > 1) {
            return null;
        }
        JsonNode line = rows.get(0);
        if (!line.path("value").asText("").isEmpty() || hasTransform(line) || hasSubstring(line)) {
            add(element, WARNING, DESCRIPTION_TRANSFORM_NOT_MIGRATED, null, "the description is transformed or "
                    + "fixed (" + transformDescription(line) + "); the description field is not migrated");
            return null;
        }
        int column = line.path("column").asInt();
        if (column <= 0) {
            add(element, NOT_MIGRATED, LEGACY_LINE_WITHOUT_SOURCE, null, "field 4 has no source column");
            return null;
        }
        add(element, MIGRATED, "DESCRIPTION", "revision.recordDescriptionField=" + column, null);
        return String.valueOf(column);
    }

    /**
     * Eén PSIMPDEF-regel naar een mapping (S8c, design par. 2c). Legacy past per regel een vaste pijplijn toe
     * ({@code Prg_1170} r.730-732, r.1039-1115): {@code MID} op de ruwe waarde → {@code Trim} → enkel indien niet leeg
     * {@code Trim(prefix) & v & Trim(suffix)} → hoogstens één CALC. Elke regel met minstens één bewerking (Positie/Lengte,
     * prefix/suffix of CALC) wordt daarom een {@code CHAIN} met die stappen in die volgorde (aanname S8, beslissing
     * 2026-10-21); de mapping zelf draagt enkel nog de notatie. Een vaste {@code Waarde} negeert de rest, zoals legacy
     * (en S6): {@code FIXED_VALUE} met {@value #LEGACY_IGNORED_WITH_FIXED_VALUE}.
     */
    private PlannedMapping mapping(int number, JsonNode line, String target, boolean decimalComma,
                                   JsonNode definition, int sequence) {
        String element = "PSIMPDEF#" + number;
        String value = line.path("value").asText("");
        // Legacy Trim(prefix)/Trim(suffix): enkel spaties is geen prefix.
        String prefix = line.path("prefix").asText("").trim();
        String suffix = line.path("suffix").asText("").trim();
        String calculation = calculationOf(line);
        int column = line.path("column").asInt();
        boolean substring = hasSubstring(line);
        boolean decimal = DECIMAL_TARGETS.contains(target);
        String notation = decimal && decimalComma ? "decimalSeparator=," : null;
        if (!value.isEmpty()) {
            if (value.length() > MAX_FIXED_VALUE) {
                add(element, WARNING, TRANSFORM_VALUE_NOT_EXPRESSIBLE, null, "the fixed value of field " + number
                        + " is longer than " + MAX_FIXED_VALUE + " characters; the mapping is not created");
                return null;
            }
            if (hasTransform(line) || substring) {
                add(element, NOT_MIGRATED, LEGACY_IGNORED_WITH_FIXED_VALUE, null, "field " + number + " has a fixed "
                        + "value; legacy ignores its " + transformDescription(line).replace("fixed value + ", "")
                        + " then, and so does CatalogImport");
            }
            add(element, MIGRATED, "MAPPING", target + " FIXED_VALUE", null);
            return new PlannedMapping(sequence, target, FieldValueKind.FIXED_VALUE, null, value,
                    FieldTransformKind.NONE, notation, List.of());
        }
        if (column <= 0) {
            add(element, NOT_MIGRATED, LEGACY_LINE_WITHOUT_SOURCE, null, "field " + number
                    + " has no source column and no fixed value");
            return null;
        }
        List<PlannedStep> steps = new ArrayList<>(3);
        List<PlannedValueMapEntry> valueMap = List.of();
        if (substring) {
            int position = line.path("position").asInt();
            int length = line.path("length").asInt();
            if (position < 0 || length < 0) {
                add(element, WARNING, LEGACY_SUBSTRING_INVALID, null, "field " + number + " has a negative position "
                        + position + " or length " + length + "; the mapping to " + target + " is not created");
                return null;
            }
            // S8b: MID(v, pos 0->1, len 0->500) op de ruwe waarde (Prg_1170 r.730); de keten trimt erna.
            steps.add(new PlannedStep(steps.size() + 1, FieldTransformKind.SUBSTRING,
                    "start=" + (position == 0 ? 1 : position) + ";length=" + (length == 0 ? 500 : length)));
        }
        if (!prefix.isEmpty() || !suffix.isEmpty()) {
            if (prefix.indexOf(';') >= 0 || suffix.indexOf(';') >= 0) {
                add(element, WARNING, TRANSFORM_VALUE_NOT_EXPRESSIBLE, null, "the prefix/suffix of field " + number
                        + " contains ';', which a transform configuration cannot express; the mapping is not "
                        + "created");
                return null;
            }
            // S8c: AFFIX met de legacy-semantiek (een lege waarde blijft leeg; PREFIX/SUFFIX zouden er tekst rond zetten).
            String affix = (prefix.isEmpty() ? "" : "prefix=" + prefix)
                    + (prefix.isEmpty() || suffix.isEmpty() ? "" : ";") + (suffix.isEmpty() ? "" : "suffix=" + suffix);
            steps.add(new PlannedStep(steps.size() + 1, FieldTransformKind.AFFIX, affix));
        }
        if (!calculation.isEmpty()) {
            FieldTransformKind kind;
            String config = null;
            switch (calculation) {
                case "15" -> {
                    // S8b: Upper() op de waarde (taalonafhankelijk).
                    kind = FieldTransformKind.UPPER;
                }
                case "1", "2", "3", "4" -> {
                    BigDecimal operand = decimalOrNull(line.path("calculationValue").asText(""));
                    if (operand == null || ("4".equals(calculation) && operand.signum() == 0)) {
                        add(element, WARNING, LEGACY_CALC_VALUE_INVALID, null, "CALC " + calculation
                                + " of field " + number + " has no usable operand; the mapping is not created");
                        return null;
                    }
                    kind = switch (calculation) {
                        case "1" -> FieldTransformKind.ADD;
                        case "2" -> FieldTransformKind.SUBTRACT;
                        case "3" -> FieldTransformKind.MULTIPLY;
                        default -> FieldTransformKind.DIVIDE;
                    };
                    config = "operand=" + operand.toPlainString();
                    add(element, WARNING, CALC_ROUNDING_MAY_DIFFER, null, "legacy computes CALC " + calculation
                            + " with Val(..,'8.2') and Str(..,'8.3'); results can differ in the last decimals, and "
                            + "an empty cell stays empty where legacy computes with 0; shown as an explained "
                            + "difference, never corrected");
                }
                case "A" -> {
                    String text = line.path("calculationValue").asText("");
                    int separator = text.indexOf(';');
                    Integer baseColumn = positiveIntOrNull(separator < 0 ? text : text.substring(0, separator));
                    if (baseColumn == null) {
                        add(element, WARNING, LEGACY_CALC_VALUE_INVALID, null, "CALC A of field " + number
                                + " names no base column; the mapping is not created");
                        return null;
                    }
                    kind = FieldTransformKind.PERCENTAGE;
                    config = "baseField=" + baseColumn;
                    add(element, WARNING, CALC_ROUNDING_MAY_DIFFER, null, "legacy computes CALC A with "
                            + "Val(..,'8.3'); results can differ in the last decimals, and an empty cell stays empty "
                            + "where legacy computes with 0; shown as an explained difference, never corrected");
                }
                case "7" -> {
                    // S8a: de tabel gaat naar import_field_value_map (aan de mapping); de MAP-stap draagt de instelling.
                    List<PlannedValueMapEntry> table = mapTable(element, definition);
                    if (table == null) {
                        return null;
                    }
                    kind = FieldTransformKind.MAP;
                    config = "caseSensitive=true;onMiss=KEEP_SOURCE";
                    valueMap = table;
                    add(element, MIGRATED, MAP_MISS_KEEPS_SOURCE, null, "legacy keeps the source value when a key "
                            + "is not in PSIMPDEFMAP; the mapping has onMiss=KEEP_SOURCE, so the source value is passed "
                            + "through unchanged and every such row is reported (MAPPING_VALUE_PASSED_THROUGH); a "
                            + "non-numeric value on a number field is still rejected");
                }
                case "14" -> {
                    // S8d: enkel de rijen van de eigen NUMMER; legacy geeft '' bij een miss, wij verwerpen (V1 = B).
                    List<PlannedValueMapEntry> table = containsTable(element, definition, number);
                    if (table == null) {
                        return null;
                    }
                    kind = FieldTransformKind.MAP_CONTAINS;
                    valueMap = table;
                    add(element, WARNING, MAP_CONTAINS_MISS_NOT_EMPTY, null, "legacy gives an empty value when no "
                            + "PSIMPDEFMAP key of field " + number + " is contained in the value; CatalogImport rejects "
                            + "the value (MAPPING_VALUE_UNKNOWN)");
                }
                default -> {
                    add(element, WARNING, "LEGACY_CALC_" + calculation + "_NOT_MIGRATED", null, "CALC "
                            + calculation + " of field " + number + " is not supported yet; the mapping to "
                            + target + " is not created");
                    return null;
                }
            }
            steps.add(new PlannedStep(steps.size() + 1, kind, config));
        }
        for (PlannedStep step : steps) {
            if (step.config() != null && step.config().length() > ImportFieldTransformStep.MAX_CONFIG_LENGTH) {
                add(element, WARNING, TRANSFORM_VALUE_NOT_EXPRESSIBLE, null, "the transformation of field " + number
                        + " does not fit in " + ImportFieldTransformStep.MAX_CONFIG_LENGTH + " characters; the "
                        + "mapping is not created");
                return null;
            }
        }
        String source = String.valueOf(column);
        FieldTransformKind kind = steps.isEmpty() ? FieldTransformKind.NONE : FieldTransformKind.CHAIN;
        add(element, MIGRATED, "MAPPING", target + " <- column " + column + (steps.isEmpty() ? ""
                : " CHAIN " + String.join(" > ", steps.stream().map(step -> step.kind().name()).toList())), null);
        return new PlannedMapping(sequence, target, FieldValueKind.SOURCE_FIELD, source, null, kind, notation,
                valueMap, steps);
    }

    /**
     * De volledige PSIMPDEFMAP-tabel als vertaalrijen (S8a, {@code import_field_value_map}): alle NUMMERs samen, de
     * laagste NUMMER wint bij een dubbele sleutel ({@code Prg_1170} r.1009-1054); volgorde (NUMMER, KEY). Sleutel en
     * waarde getrimd zoals {@code SetupService} ze bewaart; een lege sleutel vertaalt niets en valt weg. {@code null} =
     * geen tabel (gemeld).
     */
    private List<PlannedValueMapEntry> mapTable(String element, JsonNode definition) {
        List<JsonNode> maps = new ArrayList<>();
        definition.path("maps").forEach(maps::add);
        maps.sort(Comparator.comparingInt((JsonNode m) -> m.path("number").asInt())
                .thenComparing(m -> m.path("key").asText()));
        Map<String, String> table = new LinkedHashMap<>();
        for (JsonNode map : maps) {
            String key = map.path("key").asText("").trim();
            String value = map.path("value").asText("").trim();
            if (key.isEmpty()) {
                continue;
            }
            if (key.length() > ImportFieldValueMap.MAX_VALUE_LENGTH
                    || value.length() > ImportFieldValueMap.MAX_VALUE_LENGTH) {
                add(element, WARNING, MAP_ENTRY_TOO_LONG, null, "a PSIMPDEFMAP key or value is longer than "
                        + ImportFieldValueMap.MAX_VALUE_LENGTH + " characters (legacy KEY/VALUE, DS_0254); it is "
                        + "never truncated, the mapping is not created");
                return null;
            }
            String previous = table.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                add(element, WARNING, MAP_KEY_AMBIGUOUS, null, "PSIMPDEFMAP key '" + key + "' occurs with "
                        + "different values; like legacy, the one with the lowest NUMMER ('" + previous + "') is used");
            }
        }
        if (table.isEmpty()) {
            add(element, WARNING, MAP_EMPTY, null, "CALC 7 without PSIMPDEFMAP entries; the mapping is not created");
            return null;
        }
        List<PlannedValueMapEntry> entries = new ArrayList<>(table.size());
        table.forEach((key, value) -> entries.add(new PlannedValueMapEntry(entries.size() + 1, key, value)));
        return List.copyOf(entries);
    }

    /**
     * S8d: de PSIMPDEFMAP-rijen van één NUMMER voor CALC 14 ({@code MapInstr}, {@code Prg_1170} r.1128-1167), in
     * KEY-volgorde. Legacy neemt de eerste rij met {@code InStr(Upper(v), Trim(Upper(KEY))) > 0} én een niet-lege VALUE
     * ({@code End condition Trim(V_RESULT)<>''}): een rij met lege VALUE kan dus nooit het resultaat zijn en wordt niet
     * overgenomen; ook een lege sleutel niet (de uitkomst van {@code InStr(x,'')} is niet geverifieerd). Gemeld als
     * {@value #MAP_CONTAINS_ROW_NOT_MIGRATED}. Sleutel en waarde getrimd zoals {@code SetupService} ze bewaart; bij twee
     * rijen met dezelfde getrimde sleutel wint de eerste. {@code null} = geen bruikbare tabel (gemeld).
     */
    private List<PlannedValueMapEntry> containsTable(String element, JsonNode definition, int number) {
        List<JsonNode> maps = new ArrayList<>();
        definition.path("maps").forEach(map -> {
            if (map.path("number").asInt() == number) {
                maps.add(map);
            }
        });
        maps.sort(Comparator.comparing((JsonNode m) -> m.path("key").asText()));
        Map<String, String> table = new LinkedHashMap<>();
        int skipped = 0;
        for (JsonNode map : maps) {
            String key = map.path("key").asText("").trim();
            String value = map.path("value").asText("").trim();
            if (key.isEmpty() || value.isEmpty()) {
                skipped++;
                continue;
            }
            if (key.length() > ImportFieldValueMap.MAX_VALUE_LENGTH
                    || value.length() > ImportFieldValueMap.MAX_VALUE_LENGTH) {
                add(element, WARNING, MAP_ENTRY_TOO_LONG, null, "a PSIMPDEFMAP key or value is longer than "
                        + ImportFieldValueMap.MAX_VALUE_LENGTH + " characters (legacy KEY/VALUE, DS_0254); it is "
                        + "never truncated, the mapping is not created");
                return null;
            }
            String previous = table.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                add(element, WARNING, MAP_KEY_AMBIGUOUS, null, "PSIMPDEFMAP key '" + key + "' of field " + number
                        + " occurs with different values; the first ('" + previous + "') is used");
            }
        }
        if (skipped > 0) {
            add(element, NOT_MIGRATED, MAP_CONTAINS_ROW_NOT_MIGRATED, null, skipped + " PSIMPDEFMAP row(s) of field "
                    + number + " have an empty key or an empty value; legacy never takes such a row as the result, so "
                    + "they are not migrated");
        }
        if (table.isEmpty()) {
            add(element, WARNING, MAP_EMPTY, null, "CALC 14 without usable PSIMPDEFMAP entries for field " + number
                    + "; the mapping is not created");
            return null;
        }
        List<PlannedValueMapEntry> entries = new ArrayList<>(table.size());
        table.forEach((key, value) -> entries.add(new PlannedValueMapEntry(entries.size() + 1, key, value)));
        return List.copyOf(entries);
    }

    private void mapsUsage(JsonNode definition, List<PlannedMapping> mappings) {
        if (definition.path("maps").isEmpty()) {
            return;
        }
        boolean used = mappings.stream().anyMatch(mapping -> isTable(mapping.transformKind())
                || mapping.steps().stream().anyMatch(step -> isTable(step.kind())));
        if (!used) {
            add("PSIMPDEFMAP", NOT_MIGRATED, LEGACY_MAP_UNUSED, null,
                    "PSIMPDEFMAP has entries but no migrated mapping uses CALC 7 or CALC 14");
        } else {
            add("PSIMPDEFMAP", MIGRATED, "MAP_TABLE", "import_field_value_map", null);
        }
    }

    /** Include/Exclude-codes van legacy ({@code Prg_1170} r.18681-18701, expressies 119-124). */
    private static final Set<String> FILTER_CODES = Set.of("1", "2", "3", "4", "5", "6");

    /** Eén vertaalbare Include/Exclude-regel (S8e): code, bronkolom en vergelijkwaarde. */
    private record FilterDraft(int number, String code, int column, String data) {
        boolean include() {
            return "1".equals(code) || "5".equals(code);
        }
    }

    /**
     * Include/Exclude 1-6 (S8e, design par. 2e). Legacy zet per regel {@code Verwerken=FALSE} zodra de code de waarde
     * overslaat ({@code Prg_1170} r.940-941), dus elke regel sluit zelfstandig uit en meerdere includes zijn een EN.
     * Bij één include blijft de include-rij ({@code INCLUDE EQUALS}/{@code CONTAINS}, S2); bij twee of meer wordt elke
     * include via De Morgan een {@code EXCLUDE NOT_EQUALS}/{@code NOT_CONTAINS} met null-gedrag {@code EXCLUDE}
     * (een lege waarde voldoet nooit aan een include). De rijen zijn hoofdlettergevoelig zoals Magic.
     */
    private List<PlannedFilter> includeExcludeFilters(List<JsonNode> lines) {
        List<FilterDraft> drafts = new ArrayList<>();
        for (JsonNode line : lines) {
            FilterDraft draft = filterDraft(line);
            if (draft != null) {
                drafts.add(draft);
            }
        }
        boolean combined = drafts.stream().filter(FilterDraft::include).count() > 1;
        List<PlannedFilter> planned = new ArrayList<>();
        for (FilterDraft draft : drafts) {
            String element = "PSIMPDEF#" + draft.number();
            String column = String.valueOf(draft.column());
            int sequence = planned.size() + 1;
            PlannedFilter filter = switch (draft.code()) {
                case "1" -> combined
                        ? new PlannedFilter(sequence, column, FilterOperator.NOT_EQUALS, draft.data(),
                                FilterOutcome.EXCLUDE, true, FilterNullBehaviour.EXCLUDE)
                        : new PlannedFilter(sequence, column, FilterOperator.EQUALS, draft.data(),
                                FilterOutcome.INCLUDE, true, FilterNullBehaviour.EXCLUDE);
                case "5" -> combined
                        ? new PlannedFilter(sequence, column, FilterOperator.NOT_CONTAINS, draft.data(),
                                FilterOutcome.EXCLUDE, true, FilterNullBehaviour.EXCLUDE)
                        : new PlannedFilter(sequence, column, FilterOperator.CONTAINS, draft.data(),
                                FilterOutcome.INCLUDE, true, FilterNullBehaviour.EXCLUDE);
                case "2" -> new PlannedFilter(sequence, column, FilterOperator.EQUALS, draft.data(),
                        FilterOutcome.EXCLUDE, true, FilterNullBehaviour.COMPARE_AS_EMPTY);
                case "3" -> new PlannedFilter(sequence, column, FilterOperator.BEGINS_WITH, draft.data(),
                        FilterOutcome.EXCLUDE, true, FilterNullBehaviour.COMPARE_AS_EMPTY);
                case "4" -> new PlannedFilter(sequence, column, FilterOperator.ENDS_WITH, draft.data(),
                        FilterOutcome.EXCLUDE, true, FilterNullBehaviour.COMPARE_AS_EMPTY);
                default -> new PlannedFilter(sequence, column, FilterOperator.CONTAINS, draft.data(),
                        FilterOutcome.EXCLUDE, true, FilterNullBehaviour.COMPARE_AS_EMPTY);
            };
            add(element, MIGRATED, "RECORD_FILTER", "import_record_filter " + filter.outcome() + " "
                    + filter.operator() + " column " + column, combined && draft.include()
                            ? "Include/Exclude " + draft.code() + " with several include lines: every line must "
                                    + "match (AND), so each is stored as the negated EXCLUDE"
                            : null);
            if ("3".equals(draft.code()) || "4".equals(draft.code())) {
                add(element, WARNING, LEGACY_FILTER_PADDING_UNVERIFIED, null, "Include/Exclude " + draft.code()
                        + " on field " + draft.number() + " compares untrimmed in legacy on a padded Alpha value; "
                        + "CatalogImport trims both sides, so a value with leading spaces may be filtered "
                        + "differently");
            }
            planned.add(filter);
        }
        return planned;
    }

    /** @return {@code null} als de regel niet te vertalen is (er is dan een waarschuwing en de filter ontbreekt) */
    private FilterDraft filterDraft(JsonNode line) {
        int number = line.path("number").asInt();
        String element = "PSIMPDEF#" + number;
        String code = line.path("includeExclude").asText("");
        String data = line.path("includeExcludeData").asText("");
        int column = line.path("column").asInt();
        if (column <= 0) {
            add(element, WARNING, INCLUDE_FILTER_WITHOUT_COLUMN, null, "Include/Exclude " + code + " on field "
                    + number + " has no source column; the filter is not created and every record is in scope");
            return null;
        }
        if (hasSubstring(line)) {
            add(element, WARNING, INCLUDE_EXCLUDE_SUBSTRING_NOT_MIGRATED, null, "Include/Exclude " + code
                    + " on field " + number + " compares a part (Position/Length) of the value; that is not "
                    + "supported, the filter is not created and every record is in scope");
            return null;
        }
        if (data.isBlank() || data.length() > MAX_COMPARE_VALUE) {
            add(element, WARNING, INCLUDE_FILTER_EMPTY_VALUE_NOT_MIGRATED, null, "Include/Exclude " + code
                    + " on field " + number + " has no usable compare value; the filter is not created and every "
                    + "record is in scope");
            return null;
        }
        if (!line.path("value").asText("").isEmpty()) {
            add(element, WARNING, INCLUDE_EXCLUDE_WITH_FIXED_VALUE, null, "field " + number + " has a fixed value "
                    + "and an Include/Exclude; legacy then compares an empty value, CatalogImport compares source "
                    + "column " + column);
        }
        return new FilterDraft(number, code, column, data);
    }

    private void ignoredAttributes(int number, JsonNode line) {
        if (!line.path("fill").asText("").isEmpty()) {
            add("PSIMPDEF#" + number, NOT_MIGRATED, LEGACY_FILL_NOT_MIGRATED, null,
                    "Fill is not used by the legacy import logic (Prg_1170)");
        }
        if (!line.path("xmlElementPath").asText("").isEmpty()) {
            add("PSIMPDEF#" + number, NOT_MIGRATED, LEGACY_XML_PATH_NOT_MIGRATED, null, "only CSV is migrated");
        }
    }

    // --- PSIMPEXCL (S8f) -------------------------------------------------------------------------------------------

    /** Een identiteitsdeel van een PSIMPEXCL-vergelijking. */
    private enum ExcludePart {
        SUPPLIER("supplier"), GROUP("supplier group"), DISCOUNT("discount code"), REFERENCE("supplier reference");

        private final String label;

        ExcludePart(String label) {
            this.label = label;
        }
    }

    /**
     * De bron van de kortingscode (regel 23) zoals legacy ze in {@code ARIMP_Korting Code} zet: een bronkolom (zonder
     * transformatie), een vaste waarde, altijd leeg (regel 23 ontbreekt: legacy schrijft het veld dan niet), of niet
     * uitdrukbaar (geen kolom, een transformatie of Positie/Lengte).
     */
    private record DiscountSource(String column, String fixedValue, String notExpressible) {
    }

    /**
     * PSIMPEXCL (S8f, design par. 2f; beslissing 2026-10-21 V2 = A). Legacy ({@code Prg_1170} taken "Check Excludes" en
     * "Excludes", r.2582-2687) zoekt na het verwerken van een record vijf keer exact in de uitsluitingen, op de verwerkte
     * waarden (leverancier, groep en referentie in hoofdletters, de kortingscode niet; {@code ''} is letterlijk leeg),
     * en slaat het record over als één opzoeking treft. Per rij {@code (l, g, k, r)} geeft dat vijf vormen:
     * <ol>
     *   <li>{@code l<>''}, {@code g=k=r=''}: leverancier = l;</li>
     *   <li>{@code l=''}, {@code k=r=''}: groep = g;</li>
     *   <li>{@code l=''}, {@code r=''}, {@code k<>''}: groep = g en korting = k;</li>
     *   <li>{@code l=''}, {@code r<>''}: groep = g, korting = k en referentie = r;</li>
     *   <li>{@code l<>''}, {@code r<>''}: alle vier.</li>
     * </ol>
     * Een rij met leverancier zonder referentie (behalve vorm 1) of met kleine letters in l/g/r treft nooit (dode rij,
     * {@value #LEGACY_EXCLUDE_NEVER_MATCHES}). Elke andere rij wordt één EN-groep ({@code match_group}) van 1-4
     * {@code EXCLUDE EQUALS}-rijen met {@code COMPARE_AS_EMPTY}, op de bronkolommen van 9/10/11 (hoofdletterongevoelig:
     * legacy vergelijkt {@code Upper(v)} met een waarde zonder kleine letters) en de kolom van regel 23
     * (hoofdlettergevoelig, ook bij THREE_PART), getrimd. De identiteitsregels hebben geen transformatie (die blokkeert),
     * dus de getrimde bronkolom is de verwerkte legacy-waarde. Een vaste leverancier/groep (S6) wordt hier al
     * geëvalueerd: altijd waar laat de vergelijking weg, altijd onwaar maakt de rij dood; blijft er niets over, dan sluit
     * de rij elk record uit ({@value #LEGACY_EXCLUDE_EXCLUDES_ALL}, blokkerend). {@code Skip_Excludes=Y}: niets
     * gemigreerd ({@value #LEGACY_EXCLUDES_SKIPPED}).
     *
     * @param firstSequence het aantal filterrijen dat al gepland is; de groepen volgen erachter
     */
    private List<PlannedFilter> excludeGroups(JsonNode definition, Map<Integer, List<JsonNode>> byNumber,
                                              IdentitySource supplier, IdentitySource group, String reference,
                                              boolean skipExcludes, int firstSequence) {
        JsonNode rows = definition.path("excludes");
        if (skipExcludes) {
            add("INDET:" + LABEL_SKIP_EXCLUDES, NOT_MIGRATED, LEGACY_EXCLUDES_SKIPPED, null, LABEL_SKIP_EXCLUDES
                    + "=Y: legacy does not apply the " + rows.size() + " PSIMPEXCL row(s) (Prg_1170 r.395), so they are "
                    + "not migrated");
            return List.of();
        }
        if (rows.isEmpty() || supplier == null || group == null || reference == null) {
            return List.of(); // zonder identiteit is de revisie al geblokkeerd
        }
        DiscountSource discount = discountSource(byNumber);
        List<PlannedFilter> planned = new ArrayList<>();
        int groupNumber = 0;
        int index = 0;
        for (JsonNode row : rows) {
            index++;
            String element = "PSIMPEXCL#" + index;
            Map<ExcludePart, String> values = new LinkedHashMap<>();
            values.put(ExcludePart.SUPPLIER, row.path("supplier").asText("").trim());
            values.put(ExcludePart.GROUP, row.path("supplierGroup").asText("").trim());
            values.put(ExcludePart.DISCOUNT, row.path("discountCode").asText("").trim());
            values.put(ExcludePart.REFERENCE, row.path("supplierReference").asText("").trim());
            String shape = "(supplier='" + values.get(ExcludePart.SUPPLIER) + "', group='" + values.get(ExcludePart.GROUP)
                    + "', discount code='" + values.get(ExcludePart.DISCOUNT) + "', reference='"
                    + values.get(ExcludePart.REFERENCE) + "')";
            List<ExcludePart> form = excludeForm(values);
            if (form == null) {
                add(element, NOT_MIGRATED, LEGACY_EXCLUDE_NEVER_MATCHES, null, "PSIMPEXCL row " + shape + " never "
                        + "matches in legacy: a supplier without a reference only matches with an empty group and "
                        + "discount code (Prg_1170 r.2640-2686); not migrated");
                continue;
            }
            ExcludePart lowerCase = form.stream().filter(part -> part != ExcludePart.DISCOUNT)
                    .filter(part -> hasLowerCase(values.get(part))).findFirst().orElse(null);
            if (lowerCase != null) {
                add(element, NOT_MIGRATED, LEGACY_EXCLUDE_NEVER_MATCHES, null, "PSIMPEXCL row " + shape + " never "
                        + "matches in legacy: the " + lowerCase.label + " has lower-case letters and legacy compares it "
                        + "with the upper-case value of the record; not migrated");
                continue;
            }
            List<String> dropped = new ArrayList<>();
            String never = null;
            String notExpressible = null;
            List<String[]> terms = new ArrayList<>(); // {kolom, waarde, caseSensitive}
            for (ExcludePart part : form) {
                String value = values.get(part);
                switch (part) {
                    case SUPPLIER, GROUP -> {
                        IdentitySource source = part == ExcludePart.SUPPLIER ? supplier : group;
                        if (source.column() != null) {
                            terms.add(new String[] {source.column(), value, "false"});
                        } else if (source.fixedValue().toUpperCase(Locale.ROOT).equals(value)) {
                            dropped.add("the fixed " + part.label + " " + source.fixedValue() + " always equals '"
                                    + value + "'");
                        } else if (never == null) {
                            never = "the fixed " + part.label + " " + source.fixedValue() + " never equals '" + value
                                    + "'";
                        }
                    }
                    case REFERENCE -> terms.add(new String[] {reference, value, "false"});
                    case DISCOUNT -> {
                        if (discount.column() != null) {
                            terms.add(new String[] {discount.column(), value, "true"});
                        } else if (discount.notExpressible() != null) {
                            notExpressible = discount.notExpressible();
                        } else {
                            String fixed = discount.fixedValue();
                            String description = fixed.isEmpty() ? "the discount code is always empty (field "
                                    + LINE_DISCOUNT + " is not defined)" : "the fixed discount code " + fixed;
                            if (fixed.equals(value)) {
                                dropped.add(description + (fixed.isEmpty() ? "" : " always equals '" + value + "'"));
                            } else if (never == null) {
                                never = description + (fixed.isEmpty() ? ", so it never equals '" + value + "'"
                                        : " never equals '" + value + "'");
                            }
                        }
                    }
                }
            }
            if (never != null) {
                add(element, NOT_MIGRATED, LEGACY_EXCLUDE_NEVER_MATCHES, null, "PSIMPEXCL row " + shape + " never "
                        + "matches: " + never + "; not migrated");
                continue;
            }
            if (notExpressible != null) {
                add(element, WARNING, LEGACY_EXCLUDE_NOT_EXPRESSIBLE, null, "PSIMPEXCL row " + shape + " compares the "
                        + "discount code, but " + notExpressible + "; the exclusion is not created, so records legacy "
                        + "excluded are imported");
                continue;
            }
            if (terms.isEmpty()) {
                add(element, BLOCKING, LEGACY_EXCLUDE_EXCLUDES_ALL, null, "PSIMPEXCL row " + shape + " excludes every "
                        + "record: " + String.join(" and ", dropped) + "; a definition that imports nothing is not "
                        + "migrated");
                revisionBlocked = true;
                continue;
            }
            groupNumber++;
            List<String> described = new ArrayList<>();
            for (String[] term : terms) {
                planned.add(new PlannedFilter(firstSequence + planned.size() + 1, term[0], FilterOperator.EQUALS,
                        term[1], FilterOutcome.EXCLUDE, Boolean.parseBoolean(term[2]),
                        FilterNullBehaviour.COMPARE_AS_EMPTY, groupNumber));
                described.add("column " + term[0] + " EQUALS '" + term[1] + "'");
            }
            add(element, MIGRATED, "RECORD_FILTER_GROUP", "import_record_filter group " + groupNumber + ": "
                    + String.join(" AND ", described), dropped.isEmpty() ? null
                    : "left out because " + String.join(" and ", dropped));
        }
        return planned;
    }

    /** @return de vergeleken delen in vaste volgorde (leverancier, groep, korting, referentie), of null (dode rij) */
    private static List<ExcludePart> excludeForm(Map<ExcludePart, String> values) {
        boolean supplierEmpty = values.get(ExcludePart.SUPPLIER).isEmpty();
        boolean groupEmpty = values.get(ExcludePart.GROUP).isEmpty();
        boolean discountEmpty = values.get(ExcludePart.DISCOUNT).isEmpty();
        boolean referenceEmpty = values.get(ExcludePart.REFERENCE).isEmpty();
        if (!supplierEmpty && referenceEmpty) {
            return groupEmpty && discountEmpty ? List.of(ExcludePart.SUPPLIER) : null;
        }
        if (supplierEmpty && referenceEmpty) {
            return discountEmpty ? List.of(ExcludePart.GROUP) : List.of(ExcludePart.GROUP, ExcludePart.DISCOUNT);
        }
        if (supplierEmpty) {
            return List.of(ExcludePart.GROUP, ExcludePart.DISCOUNT, ExcludePart.REFERENCE);
        }
        return List.of(ExcludePart.SUPPLIER, ExcludePart.GROUP, ExcludePart.DISCOUNT, ExcludePart.REFERENCE);
    }

    private static boolean hasLowerCase(String value) {
        return !value.equals(value.toUpperCase(Locale.ROOT));
    }

    /**
     * Regel 23 zoals legacy ze in {@code ARIMP_Korting Code} zet (zonder {@code Upper()}, S6). Ontbreekt de regel, dan
     * blijft het veld leeg. Een dubbele regel blokkeert al ({@value #LEGACY_FIELD_DUPLICATED}).
     */
    private static DiscountSource discountSource(Map<Integer, List<JsonNode>> byNumber) {
        List<JsonNode> rows = byNumber.get(LINE_DISCOUNT);
        if (rows == null || rows.isEmpty()) {
            return new DiscountSource(null, "", null);
        }
        if (rows.size() > 1) {
            return new DiscountSource(null, null, "field " + LINE_DISCOUNT + " occurs more than once");
        }
        JsonNode line = rows.get(0);
        String value = line.path("value").asText("");
        if (!value.isEmpty()) {
            return new DiscountSource(null, value.trim(), null); // legacy negeert dan kolom en bewerkingen
        }
        if (hasTransform(line) || hasSubstring(line)) {
            return new DiscountSource(null, null, "field " + LINE_DISCOUNT + " is transformed ("
                    + transformDescription(line) + ")");
        }
        int column = line.path("column").asInt();
        if (column <= 0) {
            return new DiscountSource(null, null, "field " + LINE_DISCOUNT + " has no source column");
        }
        return new DiscountSource(String.valueOf(column), null, null);
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    private static String definitionName(JsonNode definition, String code) {
        String description = definition.path("description").asText("").trim();
        String name = description.isEmpty() ? "Legacy " + code : description;
        return name.length() > MAX_DEFINITION_NAME ? name.substring(0, MAX_DEFINITION_NAME) : name;
    }

    private static boolean isTable(FieldTransformKind kind) {
        return kind == FieldTransformKind.MAP || kind == FieldTransformKind.MAP_CONTAINS;
    }

    /**
     * De CALC van een regel; {@code ""} bij geen berekening. Legacy past een CALC enkel toe bij
     * {@code CALCULATION<>'' AND CALCULATION<>'0'} ({@code Prg_1170} r.732): {@code '0'} betekent dus "geen CALC".
     */
    private static String calculationOf(JsonNode line) {
        String calculation = line.path("calculation").asText("").trim();
        return "0".equals(calculation) ? "" : calculation;
    }

    private static boolean hasTransform(JsonNode line) {
        return !line.path("prefix").asText("").isEmpty() || !line.path("suffix").asText("").isEmpty()
                || !calculationOf(line).isEmpty();
    }

    private static boolean hasSubstring(JsonNode line) {
        return line.path("position").asInt() != 0 || line.path("length").asInt() != 0;
    }

    private static String transformDescription(JsonNode line) {
        List<String> parts = new ArrayList<>();
        if (!line.path("value").asText("").isEmpty()) {
            parts.add("fixed value");
        }
        if (!line.path("prefix").asText("").isEmpty()) {
            parts.add("prefix");
        }
        if (!line.path("suffix").asText("").isEmpty()) {
            parts.add("suffix");
        }
        if (!calculationOf(line).isEmpty()) {
            parts.add("CALC " + calculationOf(line));
        }
        if (hasSubstring(line)) {
            parts.add("position/length");
        }
        return String.join(" + ", parts);
    }

    private static BigDecimal decimalOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text.trim().replace(',', '.'));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Integer positiveIntOrNull(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private void add(String element, String status, String code, String target, String message) {
        items.add(new Item(element, status, code, target, message));
    }
}
