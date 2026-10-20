package be.dda.catalogimport.service;

import be.dda.catalogimport.service.support.LegacyBundleCanonicalHash;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Leest en valideert een legacy-config-bundel v1 exact volgens het normatieve contract
 * {@code docs/design/legacy-config-bundle-v1.md} (par. 1-4 en 6.5) en {@code legacy-config-bundle-design.md} par. 2
 * ("Secret door de request").
 *
 * <h2>Volgorde (contract par. 4; eerste fout wint)</h2>
 * (1) bodygrootte — in de Web-laag, vóór deze klasse; (2) strikte UTF-8 en JSON-parse; (3) {@code schema} en
 * {@code version} (een andere versie geeft {@value #CODE_VERSION_UNSUPPORTED}, ook als de rest voor v1 ongeldig zou
 * zijn); (4) structuur, types en regels; (5) beide hashes herberekend met {@link LegacyBundleCanonicalHash}.
 *
 * <h2>Lekpreventie</h2>
 * <ul>
 *   <li>Een <b>eigen</b> {@link ObjectMapper}, los van die van Spring MVC: {@code INCLUDE_SOURCE_IN_LOCATION} uit,
 *       strikte detectie van dubbele sleutels, geen afsluitende tokens.</li>
 *   <li>Elke fout is een {@link BadRequestException} met <b>enkel</b> een JSON-pad (index in ontvangen volgorde) en een
 *       regelcode, nooit een waarde en nooit een oorzaak-keten (een Jackson-melding kan een stuk invoer citeren).
 *       Onbekende sleutelnamen verschijnen in het pad enkel als ze er uitzien als een veldnaam.</li>
 *   <li>De gedecodeerde tekens worden na het parsen overschreven; de aanroeper overschrijft de bytes.</li>
 *   <li>Het secret komt in een {@link LegacySecret} (zonder waarde in {@code toString}) en wordt uit de bewaarbare boom
 *       gehaald ({@code "secret": null}).</li>
 * </ul>
 * Deze klasse logt niets.
 */
public final class LegacyBundleParser {

    /** 400: de bundel voldoet niet aan het contract. */
    public static final String CODE_INVALID = "LEGACY_BUNDLE_INVALID";
    /** 400: {@code version != 1}. */
    public static final String CODE_VERSION_UNSUPPORTED = "LEGACY_BUNDLE_VERSION_UNSUPPORTED";
    /** 400: een herberekende hash wijkt af. */
    public static final String CODE_HASH_MISMATCH = "LEGACY_BUNDLE_HASH_MISMATCH";

    /** Regelcodes uit contract par. 4, plus {@code SYNTAX} voor onleesbare JSON/UTF-8 (geen pad bekend). */
    static final String REQUIRED = "REQUIRED";
    static final String TYPE = "TYPE";
    static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";
    static final String PATTERN = "PATTERN";
    static final String LENGTH = "LENGTH";
    static final String RANGE = "RANGE";
    static final String DUPLICATE = "DUPLICATE";
    static final String SECRET_IN_DETAILS = "SECRET_IN_DETAILS";
    static final String STATUS_MISMATCH = "STATUS_MISMATCH";
    static final String SYNTAX = "SYNTAX";

    static final String SCHEMA = "legacy-config-bundle";
    static final int SUPPORTED_VERSION = 1;
    static final String LEGACY_MASTER = "PRODIS";
    static final String SECRET_LABEL = "FTP_password";
    static final String PRESENT = "PRESENT";
    static final Set<String> DEFINITION_STATUSES = Set.of(PRESENT, "NOT_REFERENCED", "MISSING", "TABLE_UNAVAILABLE");

    /** Standaardgrens voor strings (contract par. 2: "korter dan 2000 tekens"), en de uitzondering voor vrije tekst. */
    static final int MAX_TEXT = 1999;
    static final int MAX_FREE_TEXT = 4000;

    /** Contract par. 2.1 regel 2: Java-syntaxis, {@code find()} over de labelnaam, ASCII-hoofdletterongevoelig. */
    static final Pattern SECRET_LABEL_PATTERN = Pattern.compile("(?i)pass|secret|apikey|api_key|token|_pw$");
    /** Contract par. 2.1 regel 3: schema van een URL in {@code FTP}/{@code FTP2}. */
    private static final Pattern URL_SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*://)");
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");
    /** Een onbekende sleutel verschijnt enkel in het foutpad als hij eruitziet als een veldnaam. */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final List<String> ROOT_FIELDS = List.of("schema", "version", "origin", "exportedAt", "exporter",
            "contentHash", "definitionContentHash", "interface", "definitionStatus", "definition", "secret");
    private static final List<String> ORIGIN_FIELDS = List.of("environment", "dossier", "legacyMaster");
    private static final List<String> EXPORTER_FIELDS = List.of("job", "appVersion");
    private static final List<String> INTERFACE_FIELDS = List.of("code", "header", "details", "omittedSecretLabels",
            "ftpCredentialsRedacted");
    private static final List<String> HEADER_FIELDS = List.of("direction", "importExportType", "executeAutomatic",
            "executeEvery", "executionTime", "endTime", "weekdays", "dayOfWeek", "dayOfMonth", "monthOfYear",
            "specificUser", "info");
    private static final List<String> DETAIL_FIELDS = List.of("sequence", "label", "value");
    private static final List<String> DEFINITION_FIELDS = List.of("code", "description", "updateFile",
            "replacementCode", "deleteCode", "lines", "maps", "excludes");
    private static final List<String> LINE_FIELDS = List.of("number", "field", "column", "position", "length",
            "includeExclude", "includeExcludeData", "prefix", "suffix", "value", "fill", "calculation",
            "calculationValue", "xmlElementPath");
    private static final List<String> LINE_TEXT_FIELDS = List.of("field", "includeExclude", "includeExcludeData",
            "prefix", "suffix", "value", "fill", "calculation", "calculationValue", "xmlElementPath");
    private static final List<String> MAP_FIELDS = List.of("number", "key", "value", "reserveAlpha");
    private static final List<String> EXCLUDE_FIELDS = List.of("supplier", "supplierGroup", "discountCode",
            "supplierReference");
    private static final List<String> SECRET_FIELDS = List.of("label", "value");

    private final ObjectMapper mapper;

    public LegacyBundleParser() {
        JsonFactory factory = new JsonFactoryBuilder()
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                // De bundel is hoogstens vijf niveaus diep; een diepere structuur is nooit geldig.
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build())
                .build();
        this.mapper = new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Parset, valideert en controleert de hashes.
     *
     * @throws BadRequestException 400 {@value #CODE_INVALID}, {@value #CODE_VERSION_UNSUPPORTED} of
     *                             {@value #CODE_HASH_MISMATCH}; de melding bevat nooit een waarde
     */
    public LegacyBundle parse(byte[] body) {
        ObjectNode root = readTree(body);
        checkSchemaAndVersion(root);
        return validate(root);
    }

    // --- (2) Strikte UTF-8 en JSON ---------------------------------------------------------------------------

    private ObjectNode readTree(byte[] body) {
        if (body == null || body.length == 0) {
            throw invalid("$", REQUIRED);
        }
        if (body.length >= 3 && body[0] == UTF8_BOM[0] && body[1] == UTF8_BOM[1] && body[2] == UTF8_BOM[2]) {
            throw invalid("$", SYNTAX);
        }
        char[] chars;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body));
            chars = decoded.array();
            int length = decoded.limit();
            try {
                JsonNode tree;
                try (JsonParser parser = mapper.getFactory().createParser(chars, 0, length)) {
                    tree = mapper.readTree(parser);
                    if (parser.nextToken() != null) {
                        throw invalid("$", SYNTAX);
                    }
                } catch (com.fasterxml.jackson.core.exc.StreamConstraintsException tooDeep) {
                    throw invalid("$", SYNTAX);
                } catch (com.fasterxml.jackson.core.JsonParseException unreadable) {
                    // Nooit de melding of de oorzaak doorgeven: ze kan een stuk van de invoer citeren.
                    String message = unreadable.getOriginalMessage();
                    boolean duplicate = message != null && message.startsWith("Duplicate field");
                    throw invalid(pathOf(unreadable.getProcessor()), duplicate ? DUPLICATE : SYNTAX);
                } catch (IOException unreadable) {
                    throw invalid("$", SYNTAX);
                }
                if (tree == null || !tree.isObject()) {
                    throw invalid("$", TYPE);
                }
                return (ObjectNode) tree;
            } finally {
                Arrays.fill(chars, '\0');
            }
        } catch (CharacterCodingException notUtf8) {
            throw invalid("$", SYNTAX);
        }
    }

    // --- (3) schema en version -------------------------------------------------------------------------------

    private void checkSchemaAndVersion(ObjectNode root) {
        String schema = text(root, "schema", "$", 1, MAX_TEXT);
        if (!SCHEMA.equals(schema)) {
            throw invalid("$.schema", PATTERN);
        }
        JsonNode version = root.get("version");
        if (version == null) {
            throw invalid("$.version", REQUIRED);
        }
        if (!version.isIntegralNumber()) {
            throw invalid("$.version", TYPE);
        }
        if (!version.canConvertToInt() || version.intValue() != SUPPORTED_VERSION) {
            throw new BadRequestException(CODE_VERSION_UNSUPPORTED,
                    "Unsupported legacy-config bundle version; this receiver accepts version " + SUPPORTED_VERSION);
        }
    }

    // --- (4) Structuur, types en regels; (5) hashes ----------------------------------------------------------

    private LegacyBundle validate(ObjectNode root) {
        onlyKnownFields(root, "$", ROOT_FIELDS);

        ObjectNode origin = object(root, "origin", "$");
        onlyKnownFields(origin, "$.origin", ORIGIN_FIELDS);
        String environment = text(origin, "environment", "$.origin", 1, 40);
        String dossier = text(origin, "dossier", "$.origin", 1, 40);
        if (!LEGACY_MASTER.equals(text(origin, "legacyMaster", "$.origin", 1, MAX_TEXT))) {
            throw invalid("$.origin.legacyMaster", PATTERN);
        }

        OffsetDateTime exportedAt;
        try {
            exportedAt = OffsetDateTime.parse(text(root, "exportedAt", "$", 1, MAX_TEXT),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException notIso) {
            throw invalid("$.exportedAt", PATTERN);
        }

        ObjectNode exporter = object(root, "exporter", "$");
        onlyKnownFields(exporter, "$.exporter", EXPORTER_FIELDS);
        text(exporter, "job", "$.exporter", 1, 100);
        text(exporter, "appVersion", "$.exporter", 1, 50);

        String contentHash = hex64(root, "contentHash", "$");

        String interfaceCode = validateInterface(object(root, "interface", "$"));

        String definitionStatus = text(root, "definitionStatus", "$", 1, MAX_TEXT);
        if (!DEFINITION_STATUSES.contains(definitionStatus)) {
            throw invalid("$.definitionStatus", PATTERN);
        }
        boolean present = PRESENT.equals(definitionStatus);
        String definitionContentHash = null;
        JsonNode givenDefinitionHash = required(root, "definitionContentHash", "$");
        if (present) {
            if (givenDefinitionHash.isNull()) {
                throw invalid("$.definitionContentHash", STATUS_MISMATCH);
            }
            definitionContentHash = hex64(root, "definitionContentHash", "$");
        } else if (!givenDefinitionHash.isNull()) {
            throw invalid("$.definitionContentHash", STATUS_MISMATCH);
        }
        JsonNode definition = required(root, "definition", "$");
        String definitionCode = null;
        if (present) {
            if (definition.isNull()) {
                throw invalid("$.definition", STATUS_MISMATCH);
            }
            definitionCode = validateDefinition(object(root, "definition", "$"));
        } else if (!definition.isNull()) {
            throw invalid("$.definition", STATUS_MISMATCH);
        }

        LegacySecret secret = validateSecret(required(root, "secret", "$"));

        // (5) Hashes: herberekend over de gevalideerde boom (het secret telt niet mee, par. 6.3).
        if (!contentHash.equals(LegacyBundleCanonicalHash.contentHash(root))) {
            throw new BadRequestException(CODE_HASH_MISMATCH,
                    "contentHash does not match the canonical content of the bundle");
        }
        if (present && !definitionContentHash.equals(LegacyBundleCanonicalHash.definitionContentHash(root))) {
            throw new BadRequestException(CODE_HASH_MISMATCH,
                    "definitionContentHash does not match the canonical definition of the bundle");
        }

        ObjectNode withoutSecret = root.deepCopy();
        withoutSecret.putNull("secret");
        return new LegacyBundle(SUPPORTED_VERSION, environment, dossier, interfaceCode, exportedAt, contentHash,
                definitionContentHash, definitionStatus, definitionCode, withoutSecret, secret);
    }

    private String validateInterface(ObjectNode iface) {
        String path = "$.interface";
        onlyKnownFields(iface, path, INTERFACE_FIELDS);
        String code = text(iface, "code", path, 1, 20);

        ObjectNode header = object(iface, "header", path);
        String headerPath = path + ".header";
        onlyKnownFields(header, headerPath, HEADER_FIELDS);
        text(header, "direction", headerPath, 0, 1);
        text(header, "importExportType", headerPath, 0, 20);
        bool(header, "executeAutomatic", headerPath);
        text(header, "executeEvery", headerPath, 0, 1);
        legacyTime(header, "executionTime", headerPath);
        legacyTime(header, "endTime", headerPath);
        JsonNode weekdays = array(header, "weekdays", headerPath);
        if (weekdays.size() != 7) {
            throw invalid(headerPath + ".weekdays", LENGTH);
        }
        for (int i = 0; i < weekdays.size(); i++) {
            if (!weekdays.get(i).isBoolean()) {
                throw invalid(headerPath + ".weekdays[" + i + "]", TYPE);
            }
        }
        text(header, "dayOfWeek", headerPath, 0, 10);
        integer(header, "dayOfMonth", headerPath, 0, 99);
        integer(header, "monthOfYear", headerPath, 0, 99);
        text(header, "specificUser", headerPath, 0, MAX_TEXT);
        text(header, "info", headerPath, 0, MAX_FREE_TEXT);

        JsonNode details = array(iface, "details", path);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < details.size(); i++) {
            String detailPath = path + ".details[" + i + "]";
            ObjectNode detail = element(details, i, detailPath);
            onlyKnownFields(detail, detailPath, DETAIL_FIELDS);
            int sequence = integer(detail, "sequence", detailPath, 0, Integer.MAX_VALUE);
            String label = text(detail, "label", detailPath, 1, MAX_TEXT);
            String value = text(detail, "value", detailPath, 0, MAX_FREE_TEXT);
            // Contract par. 2.1 regel 4: een secretlabel of userinfo in FTP/FTP2 mag nooit in details staan.
            if (SECRET_LABEL_PATTERN.matcher(label).find()) {
                throw invalid(detailPath + ".label", SECRET_IN_DETAILS);
            }
            if (("FTP".equals(label) || "FTP2".equals(label)) && hasUserInfo(value)) {
                throw invalid(detailPath + ".value", SECRET_IN_DETAILS);
            }
            if (!seen.add(sequence + "\u001f" + label)) {
                throw invalid(detailPath, DUPLICATE);
            }
        }

        JsonNode omitted = array(iface, "omittedSecretLabels", path);
        Set<String> omittedSeen = new HashSet<>();
        for (int i = 0; i < omitted.size(); i++) {
            String itemPath = path + ".omittedSecretLabels[" + i + "]";
            String label = textValue(omitted.get(i), itemPath, 0, MAX_TEXT);
            if (!omittedSeen.add(label)) {
                throw invalid(itemPath, DUPLICATE);
            }
        }
        bool(iface, "ftpCredentialsRedacted", path);
        return code;
    }

    private String validateDefinition(ObjectNode definition) {
        String path = "$.definition";
        onlyKnownFields(definition, path, DEFINITION_FIELDS);
        String code = text(definition, "code", path, 1, 10);
        text(definition, "description", path, 0, MAX_TEXT);
        text(definition, "updateFile", path, 0, MAX_TEXT);
        text(definition, "replacementCode", path, 0, MAX_TEXT);
        text(definition, "deleteCode", path, 0, MAX_TEXT);

        JsonNode lines = array(definition, "lines", path);
        for (int i = 0; i < lines.size(); i++) {
            String linePath = path + ".lines[" + i + "]";
            ObjectNode line = element(lines, i, linePath);
            onlyKnownFields(line, linePath, LINE_FIELDS);
            integer(line, "number", linePath, 0, Integer.MAX_VALUE);
            integer(line, "column", linePath, 0, Integer.MAX_VALUE);
            integer(line, "position", linePath, 0, Integer.MAX_VALUE);
            integer(line, "length", linePath, 0, Integer.MAX_VALUE);
            for (String field : LINE_TEXT_FIELDS) {
                text(line, field, linePath, 0, MAX_TEXT);
            }
        }
        JsonNode maps = array(definition, "maps", path);
        for (int i = 0; i < maps.size(); i++) {
            String mapPath = path + ".maps[" + i + "]";
            ObjectNode map = element(maps, i, mapPath);
            onlyKnownFields(map, mapPath, MAP_FIELDS);
            integer(map, "number", mapPath, 0, Integer.MAX_VALUE);
            text(map, "key", mapPath, 0, MAX_TEXT);
            text(map, "value", mapPath, 0, MAX_TEXT);
            text(map, "reserveAlpha", mapPath, 0, MAX_TEXT);
        }
        JsonNode excludes = array(definition, "excludes", path);
        for (int i = 0; i < excludes.size(); i++) {
            String excludePath = path + ".excludes[" + i + "]";
            ObjectNode exclude = element(excludes, i, excludePath);
            onlyKnownFields(exclude, excludePath, EXCLUDE_FIELDS);
            for (String field : EXCLUDE_FIELDS) {
                text(exclude, field, excludePath, 0, MAX_TEXT);
            }
        }
        return code;
    }

    private LegacySecret validateSecret(JsonNode secret) {
        if (secret.isNull()) {
            return null;
        }
        if (!secret.isObject()) {
            throw invalid("$.secret", TYPE);
        }
        ObjectNode object = (ObjectNode) secret;
        onlyKnownFields(object, "$.secret", SECRET_FIELDS);
        String label = text(object, "label", "$.secret", 1, MAX_TEXT);
        if (!SECRET_LABEL.equals(label)) {
            throw invalid("$.secret.label", PATTERN);
        }
        String value = text(object, "value", "$.secret", 1, MAX_TEXT);
        return new LegacySecret(label, value);
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    /** Contract par. 2.1 regel 3: bevat de authority van een URL een {@code @}? */
    static boolean hasUserInfo(String value) {
        java.util.regex.Matcher scheme = URL_SCHEME.matcher(value);
        if (!scheme.find()) {
            return false;
        }
        String rest = value.substring(scheme.end());
        int end = rest.length();
        for (char stop : new char[] {'/', '?', '#'}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        return rest.substring(0, end).indexOf('@') >= 0;
    }

    private static void onlyKnownFields(ObjectNode node, String path, List<String> allowed) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid(path + "." + (SAFE_KEY.matcher(name).matches() ? name : "?"), UNKNOWN_FIELD);
            }
        }
    }

    private static JsonNode required(ObjectNode node, String key, String path) {
        JsonNode value = node.get(key);
        if (value == null) {
            throw invalid(path + "." + key, REQUIRED);
        }
        return value;
    }

    private static ObjectNode object(ObjectNode node, String key, String path) {
        JsonNode value = required(node, key, path);
        if (!value.isObject()) {
            throw invalid(path + "." + key, TYPE);
        }
        return (ObjectNode) value;
    }

    private static JsonNode array(ObjectNode node, String key, String path) {
        JsonNode value = required(node, key, path);
        if (!value.isArray()) {
            throw invalid(path + "." + key, TYPE);
        }
        return value;
    }

    private static ObjectNode element(JsonNode array, int index, String path) {
        JsonNode value = array.get(index);
        if (!value.isObject()) {
            throw invalid(path, TYPE);
        }
        return (ObjectNode) value;
    }

    private static String text(ObjectNode node, String key, String path, int min, int max) {
        return textValue(required(node, key, path), path + "." + key, min, max);
    }

    /** Lengte in Unicode-codepunten (zoals {@code varchar(n)} in PostgreSQL); "niet leeg" = niet blanco. */
    private static String textValue(JsonNode value, String path, int min, int max) {
        if (!value.isTextual()) {
            throw invalid(path, TYPE);
        }
        String text = value.textValue();
        if (hasUnpairedSurrogate(text)) {
            throw invalid(path, PATTERN);
        }
        int length = text.codePointCount(0, text.length());
        if (length > max || length < min || (min > 0 && text.isBlank())) {
            throw invalid(path, LENGTH);
        }
        return text;
    }

    private static void legacyTime(ObjectNode node, String key, String path) {
        String time = text(node, key, path, 0, 6);
        if (!time.isEmpty() && time.length() != 6) {
            throw invalid(path + "." + key, LENGTH);
        }
    }

    private static String hex64(ObjectNode node, String key, String path) {
        String value = text(node, key, path, 1, MAX_TEXT);
        if (!HEX64.matcher(value).matches()) {
            throw invalid(path + "." + key, PATTERN);
        }
        return value;
    }

    private static int integer(ObjectNode node, String key, String path, int min, int max) {
        JsonNode value = required(node, key, path);
        if (!value.isIntegralNumber()) {
            throw invalid(path + "." + key, TYPE);
        }
        if (!value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw invalid(path + "." + key, RANGE);
        }
        return value.intValue();
    }

    private static boolean bool(ObjectNode node, String key, String path) {
        JsonNode value = required(node, key, path);
        if (!value.isBoolean()) {
            throw invalid(path + "." + key, TYPE);
        }
        return value.booleanValue();
    }

    static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** Het JSON-pad waar de parser stond, in de notatie {@code $.a.b[3].c}; onveilige sleutelnamen als {@code ?}. */
    private static String pathOf(Object processor) {
        if (!(processor instanceof JsonParser parser)) {
            return "$";
        }
        StringBuilder path = new StringBuilder();
        for (com.fasterxml.jackson.core.JsonStreamContext context = parser.getParsingContext(); context != null;
             context = context.getParent()) {
            String segment;
            if (context.inArray()) {
                segment = "[" + Math.max(context.getCurrentIndex(), 0) + "]";
            } else if (context.inObject() && context.getCurrentName() != null) {
                String name = context.getCurrentName();
                segment = "." + (SAFE_KEY.matcher(name).matches() ? name : "?");
            } else {
                segment = "";
            }
            path.insert(0, segment);
        }
        return "$" + path;
    }

    private static BadRequestException invalid(String path, String rule) {
        return new BadRequestException(CODE_INVALID, "Invalid legacy-config bundle at " + path + " (" + rule + ")");
    }
}
