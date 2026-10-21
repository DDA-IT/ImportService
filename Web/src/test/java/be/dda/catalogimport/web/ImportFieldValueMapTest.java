package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.service.TemplateMaterialisationService;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
import be.dda.catalogimport.service.support.FieldTransform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Legacy-config S8a: de maptabel {@code import_field_value_map} (changeset 023-1;
 * {@code docs/design/legacy-config-s8-design.md} par. 2a en 3).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Schema:</b> bronwaarde niet blanco en hoogstens 50 tekens, uniek per mapping (bron en volgnummer), volgnummer
 *       &gt; 0, geen subject zonder naam, een mapping met rijen is niet zomaar te verwijderen (geen cascade).</li>
 *   <li><b>Setup-API (additief):</b> {@code valueMap[{source, target}]} op de mapping wordt bewaard (getrimd, in volgorde,
 *       met de tekening van de mapping) en komt terug in het antwoord en in het revisiedetail; {@code values=} blijft
 *       werken; beide, geen van beide, rijen bij een andere soort of hoofdletterconflicten zijn 400
 *       {@code CONFIG_TRANSFORM_INVALID}, onbruikbare rijen 400 {@code VALUE_MAP_*}; in elk foutgeval is niets
 *       geschreven. Een mapping verwijderen verwijdert haar rijen.</li>
 *   <li><b>Kopie:</b> een opvolgende DRAFT en een sjabloonmaterialisatie dragen de rijen mee (RevisionCopier).</li>
 *   <li><b>Screening:</b> 2000 sleutels uit de tabel vertalen; een onbekende sleutel verwerpt enkel die regel met
 *       {@code MAPPING_VALUE_UNKNOWN} (R-REC-08); de uitkomst is byte-identiek aan dezelfde vertaling via
 *       {@code values=}.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld en blijft tussen runs bestaan.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "catalogimport.screening.recovery-on-startup=false"})
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=3")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportFieldValueMapTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String SETUP = "/api/catalog-import/setup";
    private static final String USER = "tester@example.test";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEADER = "leverancier;groep;referentie;prijs;omschrijving;valuta;eenheid\n";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TemplateMaterialisationService materialisation;

    // --- Schema ------------------------------------------------------------------------------------------------

    @Test
    void theSchemaEnforcesTheValueMapRules() throws Exception {
        Draft draft = draft("SCHEMA", "OWN_DEFINITION");
        long mappingId = id(addMapping(draft.revisionId(), mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true",
                "A", "1")).andExpect(status().isCreated()));

        insert(mappingId, 2, "B", "", USER, null); // een lege doelwaarde is toegelaten
        assertThatThrownBy(() -> insert(mappingId, 3, "A", "2", USER, null))
                .as("dezelfde bronwaarde twee keer").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(mappingId, 1, "C", "3", USER, null))
                .as("hetzelfde volgnummer twee keer").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(mappingId, 4, "   ", "4", USER, null))
                .as("blanco bronwaarde").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(mappingId, 0, "D", "5", USER, null))
                .as("volgnummer 0").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(mappingId, 5, "E", "6", null, "sub"))
                .as("subject zonder naam").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(mappingId, 6, "F".repeat(51), "7", USER, null))
                .as("bronwaarde langer dan 50").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(mappingId, 7, "G", "H".repeat(51), USER, null))
                .as("doelwaarde langer dan 50").isInstanceOf(DataIntegrityViolationException.class);
        insert(mappingId, 8, "I".repeat(50), "J".repeat(50), USER, "sub"); // grens: precies 50
        // Geen cascade: de mapping verwijderen zonder haar rijen faalt (de applicatie ruimt eerst op).
        assertThatThrownBy(() -> jdbc.update("delete from import_field_mapping where id = ?", mappingId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(valueMap(mappingId)).containsExactly("1:A>1", "2:B>", "8:" + "I".repeat(50) + ">" + "J".repeat(50));
    }

    // --- Setup-API ---------------------------------------------------------------------------------------------

    @Test
    void aValueMapIsStoredTrimmedInOrderAndReturnedAndValuesKeepsWorking() throws Exception {
        Draft draft = draft("API", "OWN_DEFINITION");

        String body = addMapping(draft.revisionId(), mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true",
                " A;1 ", " ALFA|1 ", "B>2", "", "a;1", "alfa")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.valueMap.length()").value(3))
                .andExpect(jsonPath("$.valueMap[0].source").value("A;1"))
                .andExpect(jsonPath("$.valueMap[0].target").value("ALFA|1"))
                .andExpect(jsonPath("$.valueMap[1].source").value("B>2"))
                .andExpect(jsonPath("$.valueMap[1].target").value(""))
                .andReturn().getResponse().getContentAsString();
        long mappingId = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertThat(valueMap(mappingId)).containsExactly("1:A;1>ALFA|1", "2:B>2>", "3:a;1>alfa");
        assertThat(jdbc.queryForList("select created_by from import_field_value_map where mapping_id = ?",
                String.class, mappingId)).containsOnly(USER);
        assertThat(jdbc.queryForList("select created_by_subject from import_field_value_map where mapping_id = ?",
                String.class, mappingId)).hasSize(3).doesNotContainNull();

        // values= blijft werken, zonder rijen.
        addMapping(draft.revisionId(), mapping("SUPPLIER_BARCODE", "referentie", "MAP", "values=X>Y|Z>W"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.valueMap").isEmpty());

        // Het revisiedetail toont dezelfde rijen; de revisie doorstaat de activatievalidatie.
        mockMvc.perform(get("/api/catalog-import/definitions/{d}/revisions/{r}", draft.definitionId(),
                        draft.revisionId()).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappings[?(@.targetFieldCode == 'E_SUPPLIER')].valueMap[*].source")
                        .value(org.hamcrest.Matchers.contains("A;1", "B>2", "a;1")))
                .andExpect(jsonPath("$.mappings[?(@.targetFieldCode == 'SUPPLIER_BARCODE')].valueMap[*]")
                        .isEmpty());
        activate(draft.revisionId()).andExpect(status().isOk());
    }

    @Test
    void aValueMapThatDoesNotFitIsRefusedAndNothingIsWritten() throws Exception {
        Draft draft = draft("REFUSE", "OWN_DEFINITION");
        long revisionId = draft.revisionId();

        // Configuratiefouten: dezelfde validatie als de screening (400 CONFIG_TRANSFORM_INVALID).
        refused(revisionId, mapMapping("E_SUPPLIER", "eenheid", "values=A>1", "B", "2"), "CONFIG_TRANSFORM_INVALID",
                "it has both values= and 1 value map row(s)");
        refused(revisionId, mapping("E_SUPPLIER", "eenheid", "MAP", null), "CONFIG_TRANSFORM_INVALID",
                "setting 'values' is required");
        ObjectNode onPrefix = mapMapping("E_SUPPLIER", "eenheid", "prefix=X", "B", "2");
        onPrefix.put("transformKind", "PREFIX");
        refused(revisionId, onPrefix, "CONFIG_TRANSFORM_INVALID", "does not use a translation table");
        refused(revisionId, mapMapping("E_SUPPLIER", "eenheid", null, "ab", "1", "AB", "2"),
                "CONFIG_TRANSFORM_INVALID", "differ only in case while caseSensitive=false");
        // S8g: onMiss kent enkel MAP; MAP_CONTAINS (verwerpt altijd) en andere soorten weigeren het, ook een onbekende waarde.
        ObjectNode onMissOnContains = mapMapping("E_SUPPLIER", "eenheid", "onMiss=KEEP_SOURCE", "B", "2");
        onMissOnContains.put("transformKind", "MAP_CONTAINS");
        refused(revisionId, onMissOnContains, "CONFIG_TRANSFORM_INVALID", "setting 'onMiss' is not used");
        ObjectNode onMissOnPrefix = mapping("E_SUPPLIER", "eenheid", "PREFIX", "prefix=X;onMiss=KEEP_SOURCE");
        refused(revisionId, onMissOnPrefix, "CONFIG_TRANSFORM_INVALID", "setting 'onMiss' is not used");
        refused(revisionId, mapMapping("E_SUPPLIER", "eenheid", "onMiss=SKIP", "B", "2"), "CONFIG_TRANSFORM_INVALID",
                "setting 'onMiss' must be REJECT or KEEP_SOURCE");
        // Onbruikbare rijen: vóór het schrijven (anders een databasefout).
        refused(revisionId, mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true", "A", "1", " A ", "2"),
                "VALUE_MAP_SOURCE_DUPLICATE", "more than once");
        refused(revisionId, mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true", "  ", "1"),
                "VALUE_MAP_SOURCE_REQUIRED", "valueMapSource");
        refused(revisionId, mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true", "X".repeat(51), "1"),
                "VALUE_MAP_SOURCE_TOO_LONG", "at most 50");
        refused(revisionId, mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true", "A", "Y".repeat(51)),
                "VALUE_MAP_TARGET_TOO_LONG", "at most 50");
        ObjectNode nullTarget = mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true", "A", "1");
        ((ObjectNode) nullTarget.get("valueMap").get(0)).putNull("target");
        refused(revisionId, nullTarget, "VALUE_MAP_TARGET_REQUIRED", "valueMapTarget");

        assertThat(count("select count(*) from import_field_mapping where definition_revision_id = ?", revisionId))
                .isZero();
        assertThat(count("select count(*) from import_field_value_map v join import_field_mapping m on m.id = "
                + "v.mapping_id where m.definition_revision_id = ?", revisionId)).isZero();
    }

    @Test
    void deletingAMappingDeletesItsValueMap() throws Exception {
        Draft draft = draft("DELETE", "OWN_DEFINITION");
        long mappingId = id(addMapping(draft.revisionId(), mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true",
                "A", "1", "B", "2")).andExpect(status().isCreated()));

        mockMvc.perform(delete(SETUP + "/revisions/{r}/mappings/{m}", draft.revisionId(), mappingId).with(as(USER)))
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from import_field_mapping where id = ?", mappingId)).isZero();
        assertThat(count("select count(*) from import_field_value_map where mapping_id = ?", mappingId)).isZero();
    }

    // --- Kopie (RevisionCopier.copyMappingChildren) ------------------------------------------------------------

    @Test
    void aSuccessorDraftAndAMaterialisationCarryTheValueMap() throws Exception {
        // Opvolgende DRAFT.
        Draft own = draft("COPY", "OWN_DEFINITION");
        addMapping(own.revisionId(), mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true", "A;1", "ALFA",
                "B", "")).andExpect(status().isCreated());
        activate(own.revisionId()).andExpect(status().isOk());
        long successorId = id(mockMvc.perform(post(SETUP + "/revisions/{id}/successor", own.revisionId())
                        .with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeReason\":\"opvolger met maptabel\"}"))
                .andExpect(status().isCreated()));
        assertThat(valueMapOfRevision(successorId)).containsExactly("E_SUPPLIER:1:A;1>ALFA", "E_SUPPLIER:2:B>")
                .isEqualTo(valueMapOfRevision(own.revisionId()));
        // De kopie is een geldige configuratie (zonder rijen zou de MAP CONFIG_TRANSFORM_INVALID zijn).
        activate(successorId).andExpect(status().isOk());

        // Sjabloonmaterialisatie.
        Draft template = draft("TPL", "REUSABLE_TEMPLATE");
        addMapping(template.revisionId(), mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true", "K1", "V1",
                "K2", "V2")).andExpect(status().isCreated());
        activate(template.revisionId()).andExpect(status().isOk());
        MaterialisationView view = materialisation.materialise(template.definitionId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null, template.code() + "-AFG",
                        "Afgeleide definitie", null, template.code() + "-LNK", "Afgeleide koppeling",
                        template.code(), library(template.code()), null, List.of(), USER));
        assertThat(valueMapOfRevision(view.definitionRevisionId()))
                .containsExactly("E_SUPPLIER:1:K1>V1", "E_SUPPLIER:2:K2>V2");
        assertThat(jdbc.queryForList("select v.created_by from import_field_value_map v join import_field_mapping m "
                + "on m.id = v.mapping_id where m.definition_revision_id = ?", String.class,
                view.definitionRevisionId())).containsOnly(USER);
    }

    // --- Screening ---------------------------------------------------------------------------------------------

    /**
     * 2000 sleutels in de tabel (te groot voor {@code values=}): elke gebruikte sleutel vertaalt, een onbekende verwerpt
     * enkel haar regel. Dezelfde vertaling via {@code values=} geeft byte voor byte dezelfde artikelvingerafdrukken.
     */
    @Test
    void screensTwoThousandKeysThroughTheValueMapLikeValuesAndRejectsAnUnknownKey() throws Exception {
        String[] pairs = new String[4000];
        for (int i = 1; i <= 2000; i++) {
            pairs[2 * i - 2] = "K" + i;
            pairs[2 * i - 1] = "V" + i;
        }
        Chain table = chain("SCR2000", mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true", pairs));
        Chain values = chain("SCRVAL", mapping("E_SUPPLIER", "eenheid", "MAP",
                "values=K1>V1|K1000>V1000|K2000>V2000;caseSensitive=true"));
        String rows = "ACME;G1;R1;1,50;Boormachine;EUR;K1\n"
                + "ACME;G1;R2;2,25;Schroevendraaier;EUR;K1000\n"
                + "ACME;G1;R3;3,00;Hamer;EUR;K2000\n";

        long tableBatch = batchId(upload(table.taskId(), "REF-T", HEADER + rows + "ACME;G1;R4;4,00;Zaag;EUR;K9999\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.rawRecordCount").value(4))
                .andExpect(jsonPath("$.validRecordCount").value(3))
                .andExpect(jsonPath("$.rejectedRecordCount").value(1)));
        long valuesBatch = batchId(upload(values.taskId(), "REF-V", HEADER + rows)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validRecordCount").value(3)));

        assertThat(jdbc.queryForList("select issue_code || ':' || source_value || ':' || row_number from "
                + "import_row_issue where batch_id = ? and issue_code = ?", String.class, tableBatch,
                FieldTransform.CODE_MAPPING_VALUE_UNKNOWN)).containsExactly("MAPPING_VALUE_UNKNOWN:K9999:5");
        List<String> tableFingerprints = fingerprints(tableBatch);
        assertThat(tableFingerprints).hasSize(3).doesNotContainNull();
        assertThat(tableFingerprints).isEqualTo(fingerprints(valuesBatch));
    }

    /**
     * S8g (beslissing V1 = B): met {@code onMiss=KEEP_SOURCE} gaat een bronwaarde buiten de tabel ongewijzigd door en geeft
     * die regel een WARNING {@code MAPPING_VALUE_PASSED_THROUGH}; zonder die opt-in verwerpt de regel (R-REC-08).
     */
    @Test
    void screensAMapMissThroughWithAWarningWhenTheMappingKeepsTheSource() throws Exception {
        Chain keeping = chain("SCRKEEP", mapMapping("E_SUPPLIER", "eenheid", "caseSensitive=true;onMiss=KEEP_SOURCE",
                "K1", "V1", "K2", "V2"));
        long batch = batchId(upload(keeping.taskId(), "REF-K", HEADER
                + "ACME;G1;R1;1,50;Boormachine;EUR;K1\n" + "ACME;G1;R2;2,25;Schroevendraaier;EUR;K9999\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.rawRecordCount").value(2))
                .andExpect(jsonPath("$.validRecordCount").value(2))
                .andExpect(jsonPath("$.rejectedRecordCount").value(0)));

        assertThat(jdbc.queryForList("select issue_code || ':' || severity || ':' || source_value || ':' || row_number "
                + "from import_row_issue where batch_id = ? and issue_code = ?", String.class, batch,
                FieldTransform.CODE_MAPPING_VALUE_PASSED_THROUGH)).containsExactly(
                "MAPPING_VALUE_PASSED_THROUGH:WARNING:K9999:3");
    }

    // --- Hulp --------------------------------------------------------------------------------------------------

    private record Draft(String code, long definitionId, long revisionId) {
    }

    private record Chain(String code, long revisionId, long taskId) {
    }

    /** Organisatie, definitie en een DRAFT-revisie (header, drie-delige identiteit, canonicalisatieversie 2). */
    private Draft draft(String prefix, String usageType) throws Exception {
        String unique = "VM" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + prefix;
        mockMvc.perform(post(SETUP + "/source-organisations").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + unique + "\",\"name\":\"" + unique + " BV\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isCreated());
        long definitionId = id(mockMvc.perform(post(SETUP + "/definitions").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\"" + unique
                                + "-DEF\",\"name\":\"" + unique + " catalogus\",\"usageType\":\"" + usageType + "\"}"))
                .andExpect(status().isCreated()));
        long revisionId = id(mockMvc.perform(post(SETUP + "/definitions/{id}/revisions", definitionId).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"delimiter":";","charset":"UTF-8","hasHeader":true,"headerLineNumber":1,
                                 "fieldReferenceKind":"HEADER_NAME","identityProfileKind":"THREE_PART",
                                 "supplierField":"leverancier","supplierGroupField":"groep",
                                 "supplierReferenceField":"referentie","basePriceField":"prijs",
                                 "descriptionField":"omschrijving","currencyField":"valuta",
                                 "canonicalisationVersion":2}"""))
                .andExpect(status().isCreated()));
        return new Draft(unique, definitionId, revisionId);
    }

    /** Een actieve revisie met één mapping, plus koppeling en manuele taak. */
    private Chain chain(String prefix, ObjectNode mapping) throws Exception {
        Draft draft = draft(prefix, "OWN_DEFINITION");
        addMapping(draft.revisionId(), mapping).andExpect(status().isCreated());
        activate(draft.revisionId()).andExpect(status().isOk());
        long linkId = id(mockMvc.perform(post(SETUP + "/links").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":" + draft.definitionId() + ",\"code\":\"" + draft.code()
                                + "-LINK\",\"name\":\"" + draft.code() + " koppeling\",\"supplierCode\":\""
                                + draft.code() + "\",\"libraryCode\":\"" + library(draft.code()) + "\"}"))
                .andExpect(status().isCreated()));
        long taskId = id(mockMvc.perform(post(SETUP + "/tasks").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"linkId\":" + linkId + ",\"name\":\"" + draft.code() + " levering\"}"))
                .andExpect(status().isCreated()));
        return new Chain(draft.code(), draft.revisionId(), taskId);
    }

    private static ObjectNode mapping(String target, String source, String kind, String config) {
        ObjectNode mapping = JSON.createObjectNode();
        mapping.put("targetFieldCode", target);
        mapping.put("sourceReference", source);
        mapping.put("transformKind", kind);
        if (config != null) {
            mapping.put("transformConfig", config);
        }
        return mapping;
    }

    /** Een MAP-mapping met {@code valueMap} uit paren bron, doel. */
    private static ObjectNode mapMapping(String target, String source, String config, String... pairs) {
        ObjectNode mapping = mapping(target, source, "MAP", config);
        ArrayNode valueMap = mapping.putArray("valueMap");
        for (int i = 0; i < pairs.length; i += 2) {
            valueMap.addObject().put("source", pairs[i]).put("target", pairs[i + 1]);
        }
        return mapping;
    }

    private ResultActions addMapping(long revisionId, ObjectNode mapping) throws Exception {
        return mockMvc.perform(post(SETUP + "/revisions/{id}/mappings", revisionId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(mapping)));
    }

    private void refused(long revisionId, ObjectNode mapping, String code, String messagePart) throws Exception {
        String body = addMapping(revisionId, mapping).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains(messagePart);
    }

    private ResultActions activate(long revisionId) throws Exception {
        return mockMvc.perform(post(SETUP + "/revisions/{id}/activate", revisionId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approvedBy\":\"" + USER + "\"}"));
    }

    private ResultActions upload(long taskId, String reference, String content) throws Exception {
        return mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", taskId)
                .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                        content.getBytes(StandardCharsets.UTF_8)))
                .param("deliveryReference", reference)
                .param("uploadedBy", USER)
                .with(as(USER)));
    }

    private void insert(long mappingId, int sequence, String source, String target, String createdBy,
                        String subject) {
        jdbc.update("insert into import_field_value_map (mapping_id, sequence_number, source_value, target_value, "
                + "created_at, created_by, created_by_subject) values (?, ?, ?, ?, current_timestamp, ?, ?)",
                mappingId, sequence, source, target, createdBy, subject);
    }

    private List<String> valueMap(long mappingId) {
        return jdbc.queryForList("select sequence_number || ':' || source_value || '>' || target_value "
                + "from import_field_value_map where mapping_id = ? order by sequence_number", String.class, mappingId);
    }

    private List<String> valueMapOfRevision(long revisionId) {
        return jdbc.queryForList("select m.target_field_code || ':' || v.sequence_number || ':' || v.source_value "
                + "|| '>' || v.target_value from import_field_value_map v join import_field_mapping m "
                + "on m.id = v.mapping_id where m.definition_revision_id = ? "
                + "order by m.target_field_code, v.sequence_number", String.class, revisionId);
    }

    /** De artikelvingerafdrukken van de gestagede regels, op regelnummer (hex). */
    private List<String> fingerprints(long batchId) {
        List<String> result = new ArrayList<>();
        jdbc.query("select article_fingerprint from import_candidate_stage where batch_id = ? order by row_number",
                resultSet -> {
                    byte[] fingerprint = resultSet.getBytes(1);
                    result.add(fingerprint == null ? null : Arrays.toString(fingerprint));
                }, batchId);
        return result;
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static long batchId(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.batchId"))
                .longValue();
    }

    /** {@code import_link.library_code} is varchar(20). */
    private static String library(String unique) {
        return unique.length() <= 20 ? unique : unique.substring(unique.length() - 20);
    }
}
