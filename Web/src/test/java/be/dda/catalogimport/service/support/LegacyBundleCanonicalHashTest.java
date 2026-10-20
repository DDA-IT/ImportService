package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Slice S1 ({@code docs/design/legacy-config-bundle-design.md} par. 8): {@link LegacyBundleCanonicalHash} volgt
 * exact par. 6 van het normatieve contract {@code docs/design/legacy-config-bundle-v1.md}.
 *
 * <ul>
 *   <li><b>Regel:</b> beide implementaties (datasync en CatalogImport) geven byte-identieke hashes.
 *       <b>Implementatie:</b> de golden fixtures uit S0 geven exact {@code expected-hashes.json}.</li>
 *   <li><b>Regel:</b> de hash hangt niet af van sleutelvolgorde, witruimte of de volgorde van de gesorteerde arrays,
 *       wél van de volgorde van {@code weekdays}. <b>Implementatie:</b> herschikte varianten van de fixture.</li>
 *   <li><b>Regel:</b> {@code secret}, {@code origin}, {@code exportedAt}, {@code exporter} tellen niet mee;
 *       {@code definition.code}/{@code description} enkel in {@code contentHash}.</li>
 *   <li><b>Regel:</b> minimale escaping, geen normalisatie; leeg is niet {@code null}.</li>
 * </ul>
 * Databasevrij en zonder Spring-context.
 */
class LegacyBundleCanonicalHashTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GOLDEN = "golden-bundle-v1.json";
    private static final String MISSING = "golden-bundle-v1-missing-definition.json";

    // --- Golden fixtures -------------------------------------------------------------------------------------

    @Test
    void theGoldenFixturesGiveExactlyTheExpectedHashes() throws IOException {
        JsonNode expected = resource("expected-hashes.json");
        for (String fixture : List.of(GOLDEN, MISSING)) {
            JsonNode bundle = resource(fixture);
            JsonNode hashes = expected.get(fixture);

            assertThat(LegacyBundleCanonicalHash.contentHash(bundle)).as(fixture + " contentHash")
                    .isEqualTo(hashes.get("contentHash").textValue())
                    .isEqualTo(bundle.get("contentHash").textValue());
            String definitionHash = LegacyBundleCanonicalHash.definitionContentHash(bundle);
            if (hashes.get("definitionContentHash").isNull()) {
                assertThat(definitionHash).as(fixture + " definitionContentHash").isNull();
            } else {
                assertThat(definitionHash).as(fixture + " definitionContentHash")
                        .isEqualTo(hashes.get("definitionContentHash").textValue());
            }
        }
    }

    @Test
    void theHashIsIndependentOfKeyOrderWhitespaceAndTheOrderOfSortedArrays() throws IOException {
        ObjectNode golden = (ObjectNode) resource(GOLDEN);
        String expectedContent = LegacyBundleCanonicalHash.contentHash(golden);
        String expectedDefinition = LegacyBundleCanonicalHash.definitionContentHash(golden);

        ObjectNode shuffled = (ObjectNode) reverseKeys(golden);
        reverse((ArrayNode) shuffled.get("interface").get("details"));
        reverse((ArrayNode) shuffled.get("definition").get("lines"));
        reverse((ArrayNode) shuffled.get("definition").get("maps"));
        String pretty = MAPPER.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(shuffled);
        JsonNode reparsed = MAPPER.readTree(pretty);

        assertThat(LegacyBundleCanonicalHash.contentHash(reparsed)).isEqualTo(expectedContent);
        assertThat(LegacyBundleCanonicalHash.definitionContentHash(reparsed)).isEqualTo(expectedDefinition);
    }

    @Test
    void theOrderOfWeekdaysIsPartOfTheHash() throws IOException {
        ObjectNode golden = (ObjectNode) resource(GOLDEN);
        String before = LegacyBundleCanonicalHash.contentHash(golden);

        reverse((ArrayNode) golden.get("interface").get("header").get("weekdays"));

        assertThat(LegacyBundleCanonicalHash.contentHash(golden)).isNotEqualTo(before);
    }

    @Test
    void fieldsOutsideTheHashedObjectDoNotChangeIt() throws IOException {
        ObjectNode golden = (ObjectNode) resource(GOLDEN);
        String content = LegacyBundleCanonicalHash.contentHash(golden);
        String definition = LegacyBundleCanonicalHash.definitionContentHash(golden);

        golden.putNull("secret");
        golden.put("exportedAt", "2030-01-01T00:00:00Z");
        ((ObjectNode) golden.get("origin")).put("dossier", "OTHER");
        ((ObjectNode) golden.get("exporter")).put("appVersion", "9.9.9");
        golden.put("contentHash", "0".repeat(64));

        assertThat(LegacyBundleCanonicalHash.contentHash(golden)).isEqualTo(content);
        assertThat(LegacyBundleCanonicalHash.definitionContentHash(golden)).isEqualTo(definition);
    }

    @Test
    void codeAndDescriptionCountForTheContentHashButNotForTheDefinitionHash() throws IOException {
        ObjectNode golden = (ObjectNode) resource(GOLDEN);
        String content = LegacyBundleCanonicalHash.contentHash(golden);
        String definition = LegacyBundleCanonicalHash.definitionContentHash(golden);

        ((ObjectNode) golden.get("definition")).put("code", "OTHER1").put("description", "Andere naam");

        assertThat(LegacyBundleCanonicalHash.contentHash(golden)).isNotEqualTo(content);
        assertThat(LegacyBundleCanonicalHash.definitionContentHash(golden)).isEqualTo(definition);
    }

    @Test
    void anyOtherDefinitionFieldChangesBothHashes() throws IOException {
        ObjectNode golden = (ObjectNode) resource(GOLDEN);
        String content = LegacyBundleCanonicalHash.contentHash(golden);
        String definition = LegacyBundleCanonicalHash.definitionContentHash(golden);

        ((ObjectNode) golden.get("definition").get("maps").get(0)).put("value", "PCS");

        assertThat(LegacyBundleCanonicalHash.contentHash(golden)).isNotEqualTo(content);
        assertThat(LegacyBundleCanonicalHash.definitionContentHash(golden)).isNotEqualTo(definition);
    }

    @Test
    void theDefinitionHashIsNullUnlessTheStatusIsPresent() throws IOException {
        ObjectNode golden = (ObjectNode) resource(GOLDEN);
        golden.put("definitionStatus", "MISSING");

        assertThat(LegacyBundleCanonicalHash.definitionContentHash(golden)).isNull();
    }

    // --- Canonieke tekst (par. 6.1) --------------------------------------------------------------------------

    @Test
    void sortsObjectMembersByUtf16CodeUnitsAndKeepsNullMembers() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("b", 1);
        node.putNull("a");
        node.put("B", true);
        node.put("é", "x");
        node.put("Z", -12);

        assertThat(LegacyBundleCanonicalHash.canonical(node)).isEqualTo("{\"B\":true,\"Z\":-12,\"a\":null,\"b\":1,\"é\":\"x\"}");
    }

    @Test
    void escapesOnlyQuoteBackslashAndControlCharacters() {
        String raw = "\"\\\b\t\n\f\r\u0001\u001f/\u007fé–😀";
        ObjectNode node = JsonNodeFactory.instance.objectNode().put("s", raw);

        assertThat(LegacyBundleCanonicalHash.canonical(node))
                .isEqualTo("{\"s\":\"\\\"\\\\\\b\\t\\n\\f\\r\\u0001\\u001f/\u007fé–😀\"}");
    }

    @Test
    void anEmptyStringIsNotNull() {
        ObjectNode empty = JsonNodeFactory.instance.objectNode().put("v", "");
        ObjectNode nul = JsonNodeFactory.instance.objectNode().putNull("v");

        assertThat(LegacyBundleCanonicalHash.canonical(empty)).isEqualTo("{\"v\":\"\"}");
        assertThat(LegacyBundleCanonicalHash.canonical(nul)).isEqualTo("{\"v\":null}");
    }

    @Test
    void detailsAreSortedOnSequenceThenLabelThenValueAndFinallyTheWholeElement() {
        ObjectNode bundle = JsonNodeFactory.instance.objectNode();
        ArrayNode details = bundle.putObject("interface").putArray("details");
        details.addObject().put("sequence", 10).put("label", "B").put("value", "1");
        details.addObject().put("sequence", 2).put("label", "Z").put("value", "1");
        details.addObject().put("sequence", 10).put("label", "A").put("value", "2");
        details.addObject().put("sequence", 10).put("label", "A").put("value", "1");

        assertThat(LegacyBundleCanonicalHash.canonical(bundle)).isEqualTo("{\"interface\":{\"details\":["
                + "{\"label\":\"Z\",\"sequence\":2,\"value\":\"1\"},"
                + "{\"label\":\"A\",\"sequence\":10,\"value\":\"1\"},"
                + "{\"label\":\"A\",\"sequence\":10,\"value\":\"2\"},"
                + "{\"label\":\"B\",\"sequence\":10,\"value\":\"1\"}]}}");
    }

    @Test
    void numbersAreSortedNumericallyNotAsText() {
        ObjectNode bundle = JsonNodeFactory.instance.objectNode();
        ArrayNode maps = bundle.putObject("definition").putArray("maps");
        maps.addObject().put("number", 10).put("key", "a").put("value", "").put("reserveAlpha", "");
        maps.addObject().put("number", 9).put("key", "b").put("value", "").put("reserveAlpha", "");

        assertThat(LegacyBundleCanonicalHash.canonical(bundle)).startsWith("{\"definition\":{\"maps\":[{\"key\":\"b\"");
    }

    @Test
    void anUnpairedSurrogateCannotBeCanonicalised() {
        ObjectNode node = JsonNodeFactory.instance.objectNode().put("s", "a\uD800b");

        assertThatThrownBy(() -> LegacyBundleCanonicalHash.canonical(node))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFractionalNumberCannotBeCanonicalised() {
        ObjectNode node = JsonNodeFactory.instance.objectNode().put("n", 1.0);

        assertThatThrownBy(() -> LegacyBundleCanonicalHash.canonical(node))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    static JsonNode resource(String name) throws IOException {
        try (InputStream in = LegacyBundleCanonicalHashTest.class.getResourceAsStream("/legacy-config/" + name)) {
            assertThat(in).as(name).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    private static JsonNode reverseKeys(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.reverse(names);
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                copy.set(name, reverseKeys(node.get(name)));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(element -> copy.add(reverseKeys(element)));
            return copy;
        }
        return node;
    }

    private static void reverse(ArrayNode array) {
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        Collections.reverse(elements);
        array.removeAll();
        array.addAll(elements);
    }
}
