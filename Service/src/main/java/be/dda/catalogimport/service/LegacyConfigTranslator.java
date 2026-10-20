package be.dda.catalogimport.service;

import static be.dda.catalogimport.service.LegacyConfigReport.BLOCKING;
import static be.dda.catalogimport.service.LegacyConfigReport.MIGRATED;
import static be.dda.catalogimport.service.LegacyConfigReport.NOT_MIGRATED;
import static be.dda.catalogimport.service.LegacyConfigReport.WARNING;

import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.service.LegacyConfigReport.Item;
import be.dda.catalogimport.service.support.LegacyFtpLocation;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   <li><b>Identiteit (PSIMPDEF 9/10/11, 23):</b> enkel een bronkolom ({@code Kolom}, 1-gebaseerd, 0 = geen) wordt
 *       {@code identity_*_field}. Een vaste waarde ({@value #IDENTITY_FIXED_VALUE_UNSUPPORTED}, tot S6), een
 *       transformatie ({@value #IDENTITY_TRANSFORM_UNSUPPORTED}) of geen kolom ({@value #IDENTITY_FIELD_MISSING})
 *       blokkeert: weglaten zou de identiteit van elke aanbieding veranderen.</li>
 *   <li><b>Sleutelprofiel (INDET {@code ArtImp_Key_*}):</b> een afwezig label telt als {@code N}, zoals
 *       {@code Prg_1232} r.518 ({@code ='Y'}). Zonder leverancier, referentie of groep, of met barcode:
 *       {@value #IDENTITY_PROFILE_UNSUPPORTED} (decisions 2026-09-18). {@code ArtImp_Key_Korting=Y} maakt 23 deel van
 *       de identiteit ({@code FOUR_PART_WITH_DISCOUNT_CODE}).</li>
 *   <li><b>Basisprijs 26 / omschrijving 4:</b> enkel een bronkolom; een transformatie op 26 blokkeert
 *       ({@value #BASE_PRICE_TRANSFORM_UNSUPPORTED}), op 4 vervalt het veld met een waarschuwing.</li>
 *   <li><b>Mappings 33-37, 40, 43, 12, 219, 221:</b> catalogcode uit {@code legacy-config-migratie-design.md} par. 1;
 *       hoogstens één bewerking (prefix, suffix, CALC 1-4/A/7 of vaste waarde). Een combinatie, een deelstring of een
 *       andere CALC laat de mapping vervallen met een waarschuwing.</li>
 *   <li><b>CALC 7:</b> PSIMPDEFMAP wordt in legacy enkel op (IMPORT, KEY) gezocht, los van NUMMER ({@code Prg_1170}
 *       r.1009-1054): alle regels vormen één tabel, bij een dubbele sleutel wint de laagste NUMMER. Een miss geeft in
 *       legacy de bronwaarde, bij ons een fout: altijd {@value #MAP_MISS_NO_FALLBACK}.</li>
 *   <li><b>Include/Exclude 1:</b> filter {@code INCLUDE}/{@code EQUALS} op de kolom van de regel. Meer dan één
 *       include-regel blokkeert: legacy combineert ze (elke regel moet kloppen), onze INCLUDE-filters staan in
 *       OF-verhouding.</li>
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
    static final String IDENTITY_TRANSFORM_UNSUPPORTED = "IDENTITY_TRANSFORM_UNSUPPORTED";
    static final String DISCOUNT_CODE_NOT_IN_KEY = "DISCOUNT_CODE_NOT_IN_KEY";
    static final String BASE_PRICE_FIELD_MISSING = "BASE_PRICE_FIELD_MISSING";
    static final String BASE_PRICE_TRANSFORM_UNSUPPORTED = "BASE_PRICE_TRANSFORM_UNSUPPORTED";
    static final String DESCRIPTION_TRANSFORM_NOT_MIGRATED = "DESCRIPTION_TRANSFORM_NOT_MIGRATED";
    static final String LEGACY_FIELD_DUPLICATED = "LEGACY_FIELD_DUPLICATED";
    static final String LEGACY_FIELD_NOT_MIGRATED = "LEGACY_FIELD_NOT_MIGRATED";
    static final String LEGACY_LINE_WITHOUT_SOURCE = "LEGACY_LINE_WITHOUT_SOURCE";
    static final String LEGACY_SUBSTRING_NOT_MIGRATED = "LEGACY_SUBSTRING_NOT_MIGRATED";
    static final String LEGACY_FILL_NOT_MIGRATED = "LEGACY_FILL_NOT_MIGRATED";
    static final String LEGACY_XML_PATH_NOT_MIGRATED = "LEGACY_XML_PATH_NOT_MIGRATED";
    static final String TRANSFORM_CHAIN_UNSUPPORTED = "TRANSFORM_CHAIN_UNSUPPORTED";
    static final String TRANSFORM_VALUE_NOT_EXPRESSIBLE = "TRANSFORM_VALUE_NOT_EXPRESSIBLE";
    static final String LEGACY_CALC_VALUE_INVALID = "LEGACY_CALC_VALUE_INVALID";
    static final String CALC_ROUNDING_MAY_DIFFER = "CALC_ROUNDING_MAY_DIFFER";
    static final String MAP_MISS_NO_FALLBACK = "MAP_MISS_NO_FALLBACK";
    static final String MAP_EMPTY = "MAP_EMPTY";
    static final String MAP_KEY_AMBIGUOUS = "MAP_KEY_AMBIGUOUS";
    static final String MAP_TOO_LARGE = "MAP_TOO_LARGE";
    static final String LEGACY_MAP_UNUSED = "LEGACY_MAP_UNUSED";
    static final String INCLUDE_FILTER_EMPTY_VALUE_NOT_MIGRATED = "INCLUDE_FILTER_EMPTY_VALUE_NOT_MIGRATED";
    static final String INCLUDE_FILTER_WITHOUT_COLUMN = "INCLUDE_FILTER_WITHOUT_COLUMN";
    static final String INCLUDE_EXCLUDE_CODE_NOT_MIGRATED = "INCLUDE_EXCLUDE_CODE_NOT_MIGRATED";
    static final String INCLUDE_FILTER_COMBINATION_UNSUPPORTED = "INCLUDE_FILTER_COMBINATION_UNSUPPORTED";
    static final String LEGACY_EXCLUDES_NOT_MIGRATED = "LEGACY_EXCLUDES_NOT_MIGRATED";

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
    private static final List<String> CONNECTION_LABELS =
            List.of("FTP", "FTP2", "FTP_login", "FTP_directory", "FTP_filename");
    private static final Set<String> GOVERNED_LABELS = Set.of(LABEL_DEFINITION, LABEL_SUPPLIER, LABEL_LIBRARY,
            LABEL_SEPARATOR, LABEL_ENCODING, LABEL_SKIP_HEADER, LABEL_DECIMAL_COMMA, LABEL_KEY_SUPPLIER,
            LABEL_KEY_REFERENCE, LABEL_KEY_GROUP, LABEL_KEY_DISCOUNT, LABEL_KEY_BARCODE, LABEL_RATE, "FTP", "FTP2",
            "FTP_login", "FTP_directory", "FTP_filename");
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

    /** {@code import_field_mapping.transform_config} varchar(1000). */
    private static final int MAX_TRANSFORM_CONFIG = 1000;
    /** {@code import_field_mapping.fixed_value} varchar(500). */
    private static final int MAX_FIXED_VALUE = 500;
    /** {@code import_record_filter.compare_value} varchar(500). */
    private static final int MAX_COMPARE_VALUE = 500;
    /** {@code source_organisation.code}. */
    private static final int MAX_SUPPLIER_CODE = 50;
    /** {@code import_definition.description}. */
    private static final int MAX_DEFINITION_NAME = 200;

    static final String FIELD_REFERENCE_KIND = "COLUMN_INDEX";

    // --- Uitkomst -------------------------------------------------------------------------------------------------

    /** Eén geplande veldmapping, met precies wat {@code SetupService.addMapping} krijgt. */
    record PlannedMapping(int sequence, String targetCode, FieldValueKind valueKind, String sourceReference,
                          String fixedValue, FieldTransformKind transformKind, String transformConfig) {
    }

    /** Eén gepland recordfilter, met precies wat {@code SetupService.addFilter} krijgt. */
    record PlannedFilter(int sequence, String sourceReference, FilterOperator operator, String compareValue,
                         FilterOutcome outcome, boolean caseSensitive) {
    }

    /** De door legacy bepaalde inhoud van een revisie; {@code descriptionField}/{@code discountField} mogen null zijn. */
    record PlannedRevision(String charset, String delimiter, boolean hasHeader, int headerLineNumber,
                           IdentityProfileKind identityProfileKind, String supplierField, String groupField,
                           String referenceField, String discountField, String basePriceField,
                           String descriptionField, List<PlannedMapping> mappings, List<PlannedFilter> filters) {

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
     */
    record Translation(List<Item> items, String supplierCode, String libraryNumber, String definitionCode,
                       String definitionName, String definitionContentHash, PlannedRevision revision) {

        boolean blocking() {
            return items.stream().anyMatch(item -> BLOCKING.equals(item.status()));
        }
    }

    private final JsonNode bundle;
    private final String charsetWhenUndeclared;
    private final UUID credentialRef;
    private final List<Item> items = new ArrayList<>();
    private boolean revisionBlocked;

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
        rate(details);
        otherDetails(details);
        schedule();

        PlannedRevision revision = null;
        if (present) {
            revision = lines(definition, profile, decimalComma, delimiter, charset, skipHeader);
            excludes(definition);
        }
        if (revisionBlocked) {
            revision = null;
        }
        return new Translation(List.copyOf(items), supplierCode, libraryNumber, definitionCode, definitionName,
                present ? bundle.path("definitionContentHash").asText(null) : null, revision);
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
        add("INDET:" + LABEL_SUPPLIER, WARNING, PRODIS_REFERENCE_NOT_VERIFIED, null,
                "supplier " + value + " is not verified against the Prodis database (S5); the name of a new source "
                        + "organisation is a placeholder");
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
     * Het voorstel voor een verbindingsprofiel (S4): protocol, host, poort en map uit {@code FTP}+{@code FTP2}
     * ({@link LegacyFtpLocation}, A7), login uit {@code FTP_login}, en de credential van de herkomst. Geen waarde van
     * een secret: de bundel heeft er geen.
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
            case FTP -> proposal.append("protocol=FTP is not supported (SFTP only)");
            case UNKNOWN -> proposal.append("protocol unknown (FTP+FTP2 has no sftp:// scheme)");
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
                                  String delimiter, String charset, Integer skipHeader) {
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

        String supplier = identityColumn(byNumber, LINE_SUPPLIER, "identitySupplierField", true);
        String group = identityColumn(byNumber, LINE_GROUP, "identitySupplierGroupField", true);
        String reference = identityColumn(byNumber, LINE_REFERENCE, "identitySupplierReferenceField", true);
        String discount = null;
        if (profile == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE) {
            discount = identityColumn(byNumber, LINE_DISCOUNT, "identityDiscountCodeField", true);
        } else if (byNumber.containsKey(LINE_DISCOUNT)) {
            add("PSIMPDEF#" + LINE_DISCOUNT, NOT_MIGRATED, DISCOUNT_CODE_NOT_IN_KEY, null,
                    "ArtImp_Key_Korting is not Y, so the discount code is not part of the identity");
        }
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
        List<JsonNode> includeLines = new ArrayList<>();
        for (Map.Entry<Integer, List<JsonNode>> entry : byNumber.entrySet()) {
            int number = entry.getKey();
            for (JsonNode line : entry.getValue()) {
                ignoredAttributes(number, line);
                String includeExclude = line.path("includeExclude").asText("");
                if ("1".equals(includeExclude)) {
                    includeLines.add(line);
                } else if (!includeExclude.isEmpty() && !"0".equals(includeExclude)) {
                    add("PSIMPDEF#" + number, WARNING, INCLUDE_EXCLUDE_CODE_NOT_MIGRATED, null,
                            "Include/Exclude code " + includeExclude + " is not migrated; records it filtered in "
                                    + "legacy are not filtered");
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
        if (includeLines.size() > 1) {
            add("PSIMPDEF:includeExclude", BLOCKING, INCLUDE_FILTER_COMBINATION_UNSUPPORTED, null,
                    includeLines.size() + " lines use Include/Exclude 1; legacy requires each of them to match, "
                            + "CatalogImport include filters match when any one does");
            revisionBlocked = true;
        } else if (includeLines.size() == 1) {
            PlannedFilter filter = includeFilter(includeLines.get(0));
            if (filter != null) {
                filters.add(filter);
            }
        }
        mapsUsage(definition, mappings);

        if (delimiter == null || charset == null || skipHeader == null || profile == null || supplier == null
                || group == null || reference == null || basePrice == null
                || (profile == IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE && discount == null)) {
            revisionBlocked = true;
            return null;
        }
        boolean hasHeader = skipHeader > 0;
        return new PlannedRevision(charset, delimiter, hasHeader, hasHeader ? skipHeader : 1, profile, supplier,
                group, reference, discount, basePrice, description, mappings, filters);
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
                    + " has the fixed value '" + value + "'; a fixed identity value is not supported until S6");
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

    private PlannedMapping mapping(int number, JsonNode line, String target, boolean decimalComma,
                                   JsonNode definition, int sequence) {
        String element = "PSIMPDEF#" + number;
        String value = line.path("value").asText("");
        String prefix = line.path("prefix").asText("");
        String suffix = line.path("suffix").asText("");
        String calculation = line.path("calculation").asText("");
        int column = line.path("column").asInt();
        int operations = (value.isEmpty() ? 0 : 1) + (prefix.isEmpty() ? 0 : 1) + (suffix.isEmpty() ? 0 : 1)
                + (calculation.isEmpty() ? 0 : 1);
        if (operations > 1) {
            add(element, WARNING, TRANSFORM_CHAIN_UNSUPPORTED, null, "field " + number + " combines "
                    + transformDescription(line) + "; only one operation per mapping is supported (S8), the "
                    + "mapping to " + target + " is not created");
            return null;
        }
        if (hasSubstring(line)) {
            add(element, WARNING, LEGACY_SUBSTRING_NOT_MIGRATED, null, "field " + number + " reads position "
                    + line.path("position").asInt() + "/length " + line.path("length").asInt()
                    + "; a substring is not supported yet, the mapping to " + target + " is not created");
            return null;
        }
        boolean decimal = DECIMAL_TARGETS.contains(target);
        String notation = decimal && decimalComma ? "decimalSeparator=," : null;
        if (!value.isEmpty()) {
            if (value.length() > MAX_FIXED_VALUE) {
                add(element, WARNING, TRANSFORM_VALUE_NOT_EXPRESSIBLE, null, "the fixed value of field " + number
                        + " is longer than " + MAX_FIXED_VALUE + " characters; the mapping is not created");
                return null;
            }
            add(element, MIGRATED, "MAPPING", target + " FIXED_VALUE", null);
            return new PlannedMapping(sequence, target, FieldValueKind.FIXED_VALUE, null, value,
                    FieldTransformKind.NONE, notation);
        }
        if (column <= 0) {
            add(element, NOT_MIGRATED, LEGACY_LINE_WITHOUT_SOURCE, null, "field " + number
                    + " has no source column and no fixed value");
            return null;
        }
        String source = String.valueOf(column);
        FieldTransformKind kind = FieldTransformKind.NONE;
        String config = null;
        if (!prefix.isEmpty() || !suffix.isEmpty()) {
            String text = prefix.isEmpty() ? suffix : prefix;
            if (text.indexOf(';') >= 0) {
                add(element, WARNING, TRANSFORM_VALUE_NOT_EXPRESSIBLE, null, "the prefix/suffix of field " + number
                        + " contains ';', which a transform configuration cannot express; the mapping is not "
                        + "created");
                return null;
            }
            kind = prefix.isEmpty() ? FieldTransformKind.SUFFIX : FieldTransformKind.PREFIX;
            config = (prefix.isEmpty() ? "suffix=" : "prefix=") + text;
        } else if (!calculation.isEmpty()) {
            switch (calculation) {
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
                            + " with Val(..,'8.2') and Str(..,'8.3'); results can differ in the last decimals, "
                            + "shown as an explained difference, never corrected");
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
                            + "Val(..,'8.3'); results can differ in the last decimals, shown as an explained "
                            + "difference, never corrected");
                }
                case "7" -> {
                    String table = mapTable(element, definition);
                    if (table == null) {
                        return null;
                    }
                    kind = FieldTransformKind.MAP;
                    config = "values=" + table + ";caseSensitive=true";
                    add(element, WARNING, MAP_MISS_NO_FALLBACK, null, "legacy keeps the source value when a key "
                            + "is not in PSIMPDEFMAP; CatalogImport rejects the value (MAPPING_VALUE_UNKNOWN)");
                }
                default -> {
                    add(element, WARNING, "LEGACY_CALC_" + calculation + "_NOT_MIGRATED", null, "CALC "
                            + calculation + " of field " + number + " is not supported yet; the mapping to "
                            + target + " is not created");
                    return null;
                }
            }
        }
        if (notation != null) {
            config = config == null ? notation : config + ";" + notation;
        }
        if (config != null && config.length() > MAX_TRANSFORM_CONFIG) {
            add(element, WARNING, kind == FieldTransformKind.MAP ? MAP_TOO_LARGE : TRANSFORM_VALUE_NOT_EXPRESSIBLE,
                    null, "the transformation of field " + number + " does not fit in " + MAX_TRANSFORM_CONFIG
                            + " characters (map table: S8); the mapping is not created");
            return null;
        }
        add(element, MIGRATED, "MAPPING", target + " <- column " + column
                + (kind == FieldTransformKind.NONE ? "" : " " + kind), null);
        return new PlannedMapping(sequence, target, FieldValueKind.SOURCE_FIELD, source, null, kind, config);
    }

    /**
     * De volledige PSIMPDEFMAP-tabel als {@code sleutel>waarde|...}: alle NUMMERs samen, de laagste NUMMER wint bij
     * een dubbele sleutel ({@code Prg_1170} r.1009-1054). {@code null} = niet uit te drukken (gemeld).
     */
    private String mapTable(String element, JsonNode definition) {
        List<JsonNode> maps = new ArrayList<>();
        definition.path("maps").forEach(maps::add);
        maps.sort(Comparator.comparingInt((JsonNode m) -> m.path("number").asInt())
                .thenComparing(m -> m.path("key").asText()));
        Map<String, String> table = new LinkedHashMap<>();
        for (JsonNode map : maps) {
            String key = map.path("key").asText("");
            String value = map.path("value").asText("");
            if (key.isEmpty()) {
                continue;
            }
            if (containsAny(key, ";|>") || containsAny(value, ";|")) {
                add(element, WARNING, TRANSFORM_VALUE_NOT_EXPRESSIBLE, null, "a PSIMPDEFMAP key or value contains "
                        + "';', '|' or '>', which a translation table cannot express (S8); the mapping is not "
                        + "created");
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
        StringBuilder text = new StringBuilder();
        table.forEach((key, value) -> text.append(text.isEmpty() ? "" : "|").append(key).append('>').append(value));
        return text.toString();
    }

    private void mapsUsage(JsonNode definition, List<PlannedMapping> mappings) {
        if (definition.path("maps").isEmpty()) {
            return;
        }
        boolean used = mappings.stream().anyMatch(mapping -> mapping.transformKind() == FieldTransformKind.MAP);
        if (!used) {
            add("PSIMPDEFMAP", NOT_MIGRATED, LEGACY_MAP_UNUSED, null,
                    "PSIMPDEFMAP has entries but no migrated mapping uses CALC 7");
        } else {
            add("PSIMPDEFMAP", MIGRATED, "MAP_TABLE", "transform_config values=", null);
        }
    }

    private PlannedFilter includeFilter(JsonNode line) {
        int number = line.path("number").asInt();
        String element = "PSIMPDEF#" + number;
        String data = line.path("includeExcludeData").asText("");
        int column = line.path("column").asInt();
        if (column <= 0) {
            add(element, WARNING, INCLUDE_FILTER_WITHOUT_COLUMN, null, "Include/Exclude 1 on field " + number
                    + " has no source column; the filter is not created and every record is in scope");
            return null;
        }
        if (data.isEmpty() || data.length() > MAX_COMPARE_VALUE) {
            add(element, WARNING, INCLUDE_FILTER_EMPTY_VALUE_NOT_MIGRATED, null, "Include/Exclude 1 on field "
                    + number + " has no usable compare value; the filter is not created and every record is in "
                    + "scope");
            return null;
        }
        add(element, MIGRATED, "RECORD_FILTER", "import_record_filter INCLUDE EQUALS column " + column, null);
        return new PlannedFilter(1, String.valueOf(column), FilterOperator.EQUALS, data, FilterOutcome.INCLUDE, true);
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

    private void excludes(JsonNode definition) {
        int count = definition.path("excludes").size();
        if (count > 0) {
            add("PSIMPEXCL", NOT_MIGRATED, LEGACY_EXCLUDES_NOT_MIGRATED, null, count + " PSIMPEXCL row(s) are not "
                    + "migrated yet (S8); records excluded in legacy are not excluded here");
        }
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    private static String definitionName(JsonNode definition, String code) {
        String description = definition.path("description").asText("").trim();
        String name = description.isEmpty() ? "Legacy " + code : description;
        return name.length() > MAX_DEFINITION_NAME ? name.substring(0, MAX_DEFINITION_NAME) : name;
    }

    private static boolean hasTransform(JsonNode line) {
        return !line.path("prefix").asText("").isEmpty() || !line.path("suffix").asText("").isEmpty()
                || !line.path("calculation").asText("").isEmpty();
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
        if (!line.path("calculation").asText("").isEmpty()) {
            parts.add("CALC " + line.path("calculation").asText());
        }
        if (hasSubstring(line)) {
            parts.add("position/length");
        }
        return String.join(" + ", parts);
    }

    private static boolean containsAny(String text, String characters) {
        for (int i = 0; i < characters.length(); i++) {
            if (text.indexOf(characters.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
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
