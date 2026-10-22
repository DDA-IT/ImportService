package be.dda.catalogimport.service;

import be.dda.catalogimport.service.ProdisPublicationProjection.Action;
import be.dda.catalogimport.service.ProdisPublicationProjection.AmountField;
import be.dda.catalogimport.service.ProdisPublicationProjection.DescriptionField;
import be.dda.catalogimport.service.ProdisPublicationProjection.Expected;
import be.dda.catalogimport.service.ProdisPublicationProjection.FieldState;
import be.dda.catalogimport.service.ProdisPublicationProjection.Identity;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import be.dda.catalogimport.service.ProdisPublicationProjection.Notice;
import be.dda.catalogimport.service.ProdisPublicationProjection.Severity;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Het bevroren verzoek van één publicatie-item: {@code publication_run_item.request_json} en
 * {@code request_hash} = hex-SHA-256 van de UTF-8-bytes ervan ({@code docs/design/publicatie-prodis-db-direct-design.md}
 * par. 2.4; changeset 027-1 "request_hash = SHA-256 van request_json"). Een retry na {@code RESULT_UNKNOWN} stuurt exact
 * dezelfde bytes: het item wordt nooit opnieuw uit de (intussen mogelijk gewijzigde) bron geprojecteerd.
 *
 * <h2>Vorm ({@value #KIND}, spec {@value #SPEC_VERSION})</h2>
 * Compacte JSON, vaste veldvolgorde, kaarten op code gesorteerd, bedragen als <b>tekst</b> met hun eigen schaal
 * ({@link BigDecimal#toPlainString()}, nooit een JSON-getal), geen Prodis-id's. Velden: {@code kind}, {@code specVersion},
 * {@code payloadSpecVersion}, {@code bundleId}, {@code mutationId}, {@code action}, {@code identity}, {@code currency},
 * {@code basePrice}, {@code salesPercentages}, {@code description}, {@code expected}, {@code sourceAmounts},
 * {@code notices}, {@code requestHash} (de canonieke hash van de projectie).
 *
 * <h2>Integriteit bij het lezen</h2>
 * {@link #read} herberekent de canonieke hash van de projectie ({@link ProdisPublicationProjection#canonical}) en weigert
 * als die niet gelijk is aan het opgeslagen {@code requestHash}; {@link #sha256Hex} laat de aanroeper {@code request_hash}
 * controleren. Een gewijzigd verzoek wordt dus nooit stil verstuurd.
 */
public final class ProdisPublicationRequestJson {

    public static final String KIND = "PRODIS_PUBLICATION_REQUEST";
    public static final int SPEC_VERSION = 1;

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final ObjectMapper MAPPER = JsonMapper.builder(FACTORY).build();

    private ProdisPublicationRequestJson() {
    }

    /** Het verzoek is onleesbaar of klopt niet met zijn eigen hash. */
    public static final class InvalidRequestException extends RuntimeException {
        InvalidRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** De JSON-tekst van het item (deterministisch: hetzelfde item geeft dezelfde bytes). */
    public static String write(Item item) {
        StringWriter out = new StringWriter(1024);
        try (JsonGenerator json = FACTORY.createGenerator(out)) {
            json.writeStartObject();
            json.writeStringField("kind", KIND);
            json.writeNumberField("specVersion", SPEC_VERSION);
            json.writeNumberField("payloadSpecVersion", item.payloadSpecVersion());
            json.writeNumberField("bundleId", item.bundleId());
            json.writeNumberField("mutationId", item.mutationId());
            json.writeStringField("action", item.action() == null ? null : item.action().name());
            Identity identity = item.identity();
            json.writeObjectFieldStart("identity");
            json.writeStringField("supplierNumber", identity.supplierNumber());
            json.writeStringField("supplierGroup", identity.supplierGroup());
            json.writeStringField("discountCode", identity.discountCode());
            json.writeStringField("groupLegacyCode", identity.groupLegacyCode());
            json.writeStringField("supplierReference", identity.supplierReference());
            json.writeStringField("scopeKey", identity.scopeKey());
            json.writeEndObject();
            json.writeStringField("currency", item.currency());
            json.writeFieldName("basePrice");
            writeAmount(json, item.basePrice());
            json.writeObjectFieldStart("salesPercentages");
            for (Map.Entry<Integer, AmountField> entry : item.salesPercentages().entrySet()) {
                json.writeFieldName(Integer.toString(entry.getKey()));
                writeAmount(json, entry.getValue());
            }
            json.writeEndObject();
            if (item.description() == null) {
                json.writeNullField("description");
            } else {
                json.writeObjectFieldStart("description");
                json.writeStringField("state", item.description().state().name());
                json.writeStringField("value", item.description().value());
                json.writeEndObject();
            }
            json.writeObjectFieldStart("expected");
            json.writeStringField("basePrice", plain(item.expected().basePrice()));
            writeDecimals(json, "percentages", item.expected().percentages());
            json.writeEndObject();
            writeDecimals(json, "sourceAmounts", item.sourceAmounts());
            json.writeArrayFieldStart("notices");
            for (Notice notice : item.notices()) {
                json.writeStartObject();
                json.writeStringField("code", notice.code());
                json.writeStringField("severity", notice.severity().name());
                json.writeStringField("field", notice.field());
                json.writeStringField("message", notice.message());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeStringField("requestHash", item.requestHash());
            json.writeEndObject();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return out.toString();
    }

    /**
     * Leest het item terug en controleert de canonieke hash.
     *
     * @throws InvalidRequestException onleesbaar, onbekende soort/versie, of de inhoud past niet bij {@code requestHash}
     */
    public static Item read(String text) {
        JsonNode root;
        try {
            root = MAPPER.readTree(text);
        } catch (JsonProcessingException failure) {
            throw new InvalidRequestException("request_json is not valid JSON", failure);
        }
        try {
            if (root == null || !KIND.equals(root.path("kind").asText(null))
                    || root.path("specVersion").asInt(-1) != SPEC_VERSION) {
                throw new IllegalArgumentException("unknown request kind or spec version");
            }
            JsonNode identityNode = root.path("identity");
            Identity identity = new Identity(text(identityNode, "supplierNumber"), text(identityNode, "supplierGroup"),
                    text(identityNode, "discountCode"), text(identityNode, "groupLegacyCode"),
                    text(identityNode, "supplierReference"), text(identityNode, "scopeKey"));
            String action = text(root, "action");
            Map<Integer, AmountField> percentages = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = root.path("salesPercentages").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                percentages.put(Integer.valueOf(entry.getKey()), amount(entry.getValue()));
            }
            JsonNode descriptionNode = root.get("description");
            DescriptionField description = descriptionNode == null || descriptionNode.isNull() ? null
                    : new DescriptionField(FieldState.valueOf(text(descriptionNode, "state")),
                            text(descriptionNode, "value"));
            JsonNode expectedNode = root.path("expected");
            Expected expected = new Expected(decimal(expectedNode, "basePrice"),
                    decimals(expectedNode.path("percentages")));
            List<Notice> notices = new ArrayList<>();
            for (JsonNode notice : root.path("notices")) {
                notices.add(new Notice(text(notice, "code"), Severity.valueOf(text(notice, "severity")),
                        text(notice, "field"), text(notice, "message")));
            }
            Item item = new Item(root.path("payloadSpecVersion").asInt(), root.path("bundleId").asLong(),
                    root.path("mutationId").asLong(), action == null ? null : Action.valueOf(action), identity,
                    text(root, "currency"), amount(root.path("basePrice")), percentages, description, expected,
                    decimals(root.path("sourceAmounts")), notices, text(root, "requestHash"));
            String recomputed = sha256Hex(ProdisPublicationProjection.canonical(item));
            if (!recomputed.equals(item.requestHash())) {
                throw new IllegalArgumentException("the content does not match its requestHash");
            }
            return item;
        } catch (RuntimeException failure) {
            throw new InvalidRequestException("request_json is not a valid Prodis publication request: "
                    + failure.getMessage(), failure);
        }
    }

    /** Hex-SHA-256 van de UTF-8-bytes ({@code publication_run_item.request_hash}). */
    public static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    // --- hulp --------------------------------------------------------------------------------------------------

    private static void writeAmount(JsonGenerator json, AmountField field) throws IOException {
        json.writeStartObject();
        json.writeStringField("state", field.state().name());
        json.writeStringField("value", plain(field.value()));
        json.writeEndObject();
    }

    private static void writeDecimals(JsonGenerator json, String name, Map<Integer, BigDecimal> values)
            throws IOException {
        json.writeObjectFieldStart(name);
        for (Map.Entry<Integer, BigDecimal> entry : new TreeMap<>(values).entrySet()) {
            json.writeStringField(Integer.toString(entry.getKey()), plain(entry.getValue()));
        }
        json.writeEndObject();
    }

    private static AmountField amount(JsonNode node) {
        return new AmountField(FieldState.valueOf(text(node, "state")), decimal(node, "value"));
    }

    private static Map<Integer, BigDecimal> decimals(JsonNode node) {
        Map<Integer, BigDecimal> values = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            values.put(Integer.valueOf(entry.getKey()), new BigDecimal(entry.getValue().asText()));
        }
        return values;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Met de eigen schaal (geen {@code stripTrailingZeros}): exact wat de projectie gaf. */
    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }
}
