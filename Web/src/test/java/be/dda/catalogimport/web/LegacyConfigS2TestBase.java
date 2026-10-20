package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.LegacyConfigReceiptService;
import be.dda.catalogimport.service.RevisionSuccessorService;
import be.dda.catalogimport.service.SetupService;
import be.dda.catalogimport.service.support.LegacyBundleCanonicalHash;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Gedeelde opbouw voor {@link LegacyConfigPreviewTest} en {@link LegacyConfigApplyTest} (slice S2 van
 * {@code docs/design/legacy-config-bundle-design.md}): één Spring-context (zelfde annotaties en propertybron), kleine
 * pool (de lokale PostgreSQL heeft 50 verbindingen), ontvangsten rechtstreeks via {@link LegacyConfigReceiptService}
 * (de push zelf is S1 en daar getest).
 * <p>
 * Testbundels zijn de golden fixture zonder secret, of daarvan afgeleid; geen echte gegevens. Elke test krijgt een eigen
 * dossier, interfacecode en leveranciercode: de database is gedeeld en blijft tussen runs bestaan. De golden fixture zelf
 * wordt enkel met leverancier 9999/interface GOLDEN_ART gebruikt voor een PREVIEW (nooit toegepast).
 */
@SpringBootTest(properties = "catalogimport.screening.recovery-on-startup=false")
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=3")
@AutoConfigureMockMvc
@ActiveProfiles("local")
abstract class LegacyConfigS2TestBase {

    static final String RECEIPTS = "/api/catalog-import/legacy-config/receipts";
    static final String USER = "beheerder@example.test";
    static final String REASON = "Overname legacy-interface (test S2)";
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ActorIdentity CLIENT = new ActorIdentity("svc:legacy-config-test", "svc-sub");

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> {
            try {
                return Files.createDirectories(Path.of("target", "legacy-config-s2-archive")).toAbsolutePath()
                        .toString();
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
        });
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    LegacyConfigReceiptService receipts;
    @Autowired
    SetupService setup;
    @Autowired
    RevisionSuccessorService successors;

    // --- Bundels --------------------------------------------------------------------------------------------------

    /** De onaangepaste golden fixture zonder secret (S1 weigert een secret; het telt niet mee in de hash). */
    static ObjectNode golden() throws IOException {
        ObjectNode bundle = (ObjectNode) fixture("golden-bundle-v1.json");
        bundle.putNull("secret");
        ((ObjectNode) bundle.get("origin")).put("dossier", "S2-" + UUID.randomUUID().toString().substring(0, 13));
        return bundle;
    }

    /**
     * Afgeleid van de golden fixture zodat ze in S2 toepasbaar is: lijn 10 krijgt kolom 4 (geen vaste waarde), lijn 11
     * verliest haar prefix, alle {@code ArtImp_Key_*}-labels staan op Y (dus FOUR_PART met 23); eigen interface en
     * leverancier.
     */
    static ObjectNode applicable() throws IOException {
        ObjectNode bundle = golden();
        ObjectNode group = line(bundle, 10);
        group.put("column", 4);
        group.put("value", "");
        line(bundle, 11).put("prefix", "");
        detail(bundle, "ArtImp_Key_Lev", "Y");
        detail(bundle, "ArtImp_Key_RefLev", "Y");
        detail(bundle, "ArtImp_Key_LevGrp", "Y");
        detail(bundle, "ArtImp_Key_Korting", "Y");
        detail(bundle, "Supplier", "T" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
        interfaceCode(bundle, "S2" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        return bundle;
    }

    static ObjectNode line(ObjectNode bundle, int number) {
        for (JsonNode line : bundle.get("definition").get("lines")) {
            if (line.get("number").asInt() == number) {
                return (ObjectNode) line;
            }
        }
        throw new IllegalArgumentException("no line " + number);
    }

    /** Zet (of voegt toe) een INDET-label. */
    static void detail(ObjectNode bundle, String label, String value) {
        ArrayNode details = (ArrayNode) bundle.get("interface").get("details");
        int maxSequence = 0;
        for (JsonNode detail : details) {
            if (detail.get("label").asText().equals(label)) {
                ((ObjectNode) detail).put("value", value);
                return;
            }
            maxSequence = Math.max(maxSequence, detail.get("sequence").asInt());
        }
        details.addObject().put("sequence", maxSequence + 1).put("label", label).put("value", value);
    }

    static void removeDetail(ObjectNode bundle, String label) {
        ArrayNode details = (ArrayNode) bundle.get("interface").get("details");
        for (int i = details.size() - 1; i >= 0; i--) {
            if (details.get(i).get("label").asText().equals(label)) {
                details.remove(i);
            }
        }
    }

    static void interfaceCode(ObjectNode bundle, String code) {
        ((ObjectNode) bundle.get("interface")).put("code", code);
    }

    static String interfaceCode(JsonNode bundle) {
        return bundle.get("interface").get("code").asText();
    }

    static String supplier(JsonNode bundle) {
        for (JsonNode detail : bundle.get("interface").get("details")) {
            if (detail.get("label").asText().equals("Supplier")) {
                return detail.get("value").asText();
            }
        }
        return null;
    }

    static String dossier(JsonNode bundle) {
        return bundle.get("origin").get("dossier").asText();
    }

    /** Herberekent beide hashes (contract par. 6). */
    static ObjectNode rehash(ObjectNode bundle) {
        bundle.put("contentHash", LegacyBundleCanonicalHash.contentHash(bundle));
        String definitionHash = LegacyBundleCanonicalHash.definitionContentHash(bundle);
        if (definitionHash == null) {
            bundle.putNull("definitionContentHash");
        } else {
            bundle.put("definitionContentHash", definitionHash);
        }
        return bundle;
    }

    static JsonNode fixture(String name) throws IOException {
        try (InputStream in = LegacyConfigS2TestBase.class.getResourceAsStream("/legacy-config/" + name)) {
            return MAPPER.readTree(in);
        }
    }

    // --- Ontvangen, preview, apply --------------------------------------------------------------------------------

    /** Bewaart de bundel als ontvangst (na herberekening van de hashes) en geeft het id. */
    long receive(ObjectNode bundle) throws IOException {
        return receipts.receive(MAPPER.writeValueAsBytes(rehash(bundle)), CLIENT).receiptId();
    }

    /** {@code params}: paren naam/waarde ({@code libraryCode}, {@code charsetWhenUndeclared}, ...). */
    ResultActions preview(long receiptId, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(RECEIPTS + "/{id}/preview", receiptId)
                .with(as(USER, Permission.MANAGE));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mockMvc.perform(request);
    }

    String previewBody(long receiptId, String... params) throws Exception {
        return preview(receiptId, params).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    String fingerprint(long receiptId, String... params) throws Exception {
        return JsonPath.read(previewBody(receiptId, params), "$.previewFingerprint");
    }

    /** APPLY met een expliciete fingerprint; {@code params} komen als extra velden in de body. */
    ResultActions apply(long receiptId, String fingerprint, String reason, String... params) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("previewFingerprint", fingerprint);
        body.put("reason", reason);
        for (int i = 0; i < params.length; i += 2) {
            body.put(params[i], params[i + 1]);
        }
        return mockMvc.perform(post(RECEIPTS + "/{id}/apply", receiptId).with(as(USER, Permission.MANAGE))
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsBytes(body)));
    }

    /** PREVIEW en daarna APPLY met dezelfde keuzes en de fingerprint van die preview. */
    ResultActions previewAndApply(long receiptId, String... params) throws Exception {
        return apply(receiptId, fingerprint(receiptId, params), REASON, params);
    }

    static List<String> blockingCodes(String body) {
        return JsonPath.read(body, "$.report.items[?(@.status == 'BLOCKING')].code");
    }

    static List<String> codes(String body, String status) {
        return JsonPath.read(body, "$.report.items[?(@.status == '" + status + "')].code");
    }

    // --- Database -------------------------------------------------------------------------------------------------

    Map<String, Object> receiptRow(long id) {
        return jdbc.queryForMap("select * from legacy_config_receipt where id = ?", id);
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** Een momentopname van alles wat APPLY voor deze bundel zou schrijven. */
    List<Long> footprint(JsonNode bundle) {
        String supplier = supplier(bundle);
        String iface = interfaceCode(bundle);
        String dossier = dossier(bundle);
        return List.of(
                count("select count(*) from source_organisation where code = ?", supplier),
                count("select count(*) from import_definition d join source_organisation o on o.id = "
                        + "d.source_organisation_id where o.code = ?", supplier),
                count("select count(*) from import_link where code = ?", "LEGACY-" + iface),
                count("select count(*) from legacy_config_origin where origin_dossier = ?", dossier),
                count("select count(*) from legacy_config_definition_origin where origin_dossier = ?", dossier),
                count("select count(*) from legacy_config_revision_origin ro join legacy_config_receipt r "
                        + "on r.id = ro.receipt_id where r.origin_dossier = ?", dossier),
                count("select count(*) from legacy_config_receipt where origin_dossier = ? and status = 'RECEIVED'",
                        dossier));
    }

    long definitionId(String supplier, String code) {
        return count("select d.id from import_definition d join source_organisation o on o.id = "
                + "d.source_organisation_id where o.code = ? and d.code = ?", supplier, code);
    }

    List<Map<String, Object>> revisionRows(long definitionId) {
        return jdbc.queryForList("select * from import_definition_revision where import_definition_id = ? "
                + "order by revision_number", definitionId);
    }
}
