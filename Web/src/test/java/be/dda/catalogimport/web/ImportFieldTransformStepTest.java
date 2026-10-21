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
 * Legacy-config S8c: de transformatieketen {@code import_field_transform_step} (changeset 024-1;
 * {@code docs/design/legacy-config-s8-design.md} par. 2c en 3).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Schema:</b> stapnummer 1..3 en uniek per mapping, nooit een keten als stap, configuratie hoogstens 1000
 *       tekens, geen subject zonder naam, een mapping met stappen is niet zomaar te verwijderen (geen cascade).</li>
 *   <li><b>Setup-API (additief):</b> {@code steps[{kind, config}]} op de mapping wordt bewaard (in volgorde, met de
 *       tekening van de mapping) en komt terug in het antwoord en in het revisiedetail; grammaticafouten zijn 400
 *       {@code CONFIG_TRANSFORM_INVALID}, onbruikbare stappen 400 {@code STEP*}; in elk foutgeval is niets geschreven.
 *       Een mapping verwijderen verwijdert haar stappen.</li>
 *   <li><b>Kopie:</b> een opvolgende DRAFT en een sjabloonmaterialisatie dragen de stappen mee (RevisionCopier).</li>
 *   <li><b>Screening:</b> prefix + suffix + vertaling geeft het legacy-resultaat (opzoeking op de waarde mét prefix en
 *       suffix), een lege cel blijft leeg, substring + prefix + hoofdletters volgt de legacy-volgorde; een keten die de
 *       grammatica schendt blokkeert de levering in stap B' ({@code CONFIG_TRANSFORM_INVALID}).</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is gedeeld en blijft tussen runs bestaan.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "catalogimport.screening.recovery-on-startup=false"})
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=3")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ImportFieldTransformStepTest {

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
    void theSchemaEnforcesTheStepRules() throws Exception {
        Draft draft = draft("SCHEMA", "OWN_DEFINITION");
        long mappingId = id(addMapping(draft.revisionId(), chainMapping("E_SUPPLIER", "eenheid", null,
                "UPPER", null)).andExpect(status().isCreated()));

        insert(mappingId, 2, "AFFIX", "prefix=X", USER, null);
        insert(mappingId, 3, "MAP", "C".repeat(1000), USER, "sub"); // grens: precies 1000 tekens
        assertThatThrownBy(() -> insert(mappingId, 1, "SUBSTRING", null, USER, null))
                .as("hetzelfde stapnummer twee keer").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(mappingId, 4, "UPPER", null, USER, null))
                .as("stap 4").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(mappingId, 0, "UPPER", null, USER, null))
                .as("stap 0").isInstanceOf(DataIntegrityViolationException.class);
        Draft other = draft("SCHEMA2", "OWN_DEFINITION");
        long otherMapping = id(addMapping(other.revisionId(), chainMapping("E_SUPPLIER", "eenheid", null,
                "UPPER", null)).andExpect(status().isCreated()));
        assertThatThrownBy(() -> insert(otherMapping, 2, "CHAIN", null, USER, null))
                .as("een keten als stap").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(otherMapping, 2, "AFFIX", "prefix=X", null, "sub"))
                .as("subject zonder naam").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(otherMapping, 2, "AFFIX", "D".repeat(1001), USER, null))
                .as("configuratie langer dan 1000").isInstanceOf(DataIntegrityViolationException.class);
        // Geen cascade: de mapping verwijderen zonder haar stappen faalt (de applicatie ruimt eerst op).
        assertThatThrownBy(() -> jdbc.update("delete from import_field_mapping where id = ?", mappingId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(steps(mappingId)).containsExactly("1:UPPER:", "2:AFFIX:prefix=X", "3:MAP:" + "C".repeat(1000));
    }

    // --- Setup-API ---------------------------------------------------------------------------------------------

    @Test
    void aChainIsStoredInOrderSignedAndReturned() throws Exception {
        Draft draft = draft("API", "OWN_DEFINITION");

        String body = addMapping(draft.revisionId(), chainMapping("E_SUPPLIER", "eenheid", null,
                "SUBSTRING", " start=2;length=3 ", "AFFIX", "prefix=A-;suffix=-Z", "UPPER", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].kind").value("SUBSTRING"))
                .andExpect(jsonPath("$.steps[0].config").value("start=2;length=3"))
                .andExpect(jsonPath("$.steps[1].kind").value("AFFIX"))
                .andExpect(jsonPath("$.steps[2].kind").value("UPPER"))
                .andExpect(jsonPath("$.valueMap").isEmpty())
                .andReturn().getResponse().getContentAsString();
        long mappingId = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertThat(steps(mappingId)).containsExactly("1:SUBSTRING:start=2;length=3", "2:AFFIX:prefix=A-;suffix=-Z",
                "3:UPPER:");
        assertThat(jdbc.queryForList("select created_by from import_field_transform_step where mapping_id = ?",
                String.class, mappingId)).containsOnly(USER);
        assertThat(jdbc.queryForList("select created_by_subject from import_field_transform_step where mapping_id = ?",
                String.class, mappingId)).hasSize(3).doesNotContainNull();

        // Een mapping zonder keten geeft een lege lijst.
        addMapping(draft.revisionId(), mapping("SUPPLIER_BARCODE", "referentie", "AFFIX", "prefix=B-"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.steps").isEmpty());

        mockMvc.perform(get("/api/catalog-import/definitions/{d}/revisions/{r}", draft.definitionId(),
                        draft.revisionId()).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappings[?(@.targetFieldCode == 'E_SUPPLIER')].steps[*].kind")
                        .value(org.hamcrest.Matchers.contains("SUBSTRING", "AFFIX", "UPPER")))
                .andExpect(jsonPath("$.mappings[?(@.targetFieldCode == 'SUPPLIER_BARCODE')].steps[*]").isEmpty());
        activate(draft.revisionId()).andExpect(status().isOk());
    }

    @Test
    void aChainOutsideTheGrammarOrWithUnusableStepsIsRefusedAndNothingIsWritten() throws Exception {
        Draft draft = draft("REFUSE", "OWN_DEFINITION");
        long revisionId = draft.revisionId();

        // De gesloten grammatica: dezelfde validatie als de screening (400 CONFIG_TRANSFORM_INVALID).
        refused(revisionId, chainMapping("E_SUPPLIER", "eenheid", null, "UPPER", null, "AFFIX", "prefix=X"),
                "CONFIG_TRANSFORM_INVALID", "step 2 (AFFIX) cannot follow step 1 (UPPER)");
        refused(revisionId, chainMapping("E_SUPPLIER", "eenheid", null, "FIXED_VALUE", "value=X"),
                "CONFIG_TRANSFORM_INVALID", "can never be a step of a chain");
        refused(revisionId, chainMapping("E_SUPPLIER", "eenheid", null, "CONCAT", "sources=groep"),
                "CONFIG_TRANSFORM_INVALID", "can never be a step of a chain");
        refused(revisionId, chainMapping("E_SUPPLIER", "eenheid", null), "CONFIG_TRANSFORM_INVALID",
                "a chain needs 1 to 3 steps but has 0");
        refused(revisionId, chainMapping("E_SUPPLIER", "eenheid", "prefix=X", "UPPER", null),
                "CONFIG_TRANSFORM_INVALID", "setting 'prefix' is not used");
        refused(revisionId, chainMapping("E_SUPPLIER", "eenheid", null, "AFFIX", null), "CONFIG_TRANSFORM_INVALID",
                "step 1 (AFFIX): an affix transformation needs");
        ObjectNode onPrefix = chainMapping("E_SUPPLIER", "eenheid", "prefix=X", "UPPER", null);
        onPrefix.put("transformKind", "PREFIX");
        refused(revisionId, onPrefix, "CONFIG_TRANSFORM_INVALID", "is not a CHAIN; they would silently have no effect");
        ObjectNode rowsWithoutMap = chainMapping("E_SUPPLIER", "eenheid", null, "UPPER", null);
        rowsWithoutMap.putArray("valueMap").addObject().put("source", "A").put("target", "1");
        refused(revisionId, rowsWithoutMap, "CONFIG_TRANSFORM_INVALID", "no step of its chain uses a translation table");
        // Onbruikbare stappen: vóór het schrijven (anders een databasefout).
        refused(revisionId, chainMapping("E_SUPPLIER", "eenheid", null, "SUBSTRING", null, "AFFIX", "prefix=X",
                "UPPER", null, "MAP", "values=A>B"), "STEPS_TOO_MANY", "at most 3 steps");
        refused(revisionId, chainMapping("E_SUPPLIER", "eenheid", null, "CHAIN", null), "STEP_KIND_INVALID",
                "never a CHAIN itself");
        ObjectNode withoutKind = chainMapping("E_SUPPLIER", "eenheid", null, "UPPER", null);
        ((ObjectNode) withoutKind.get("steps").get(0)).remove("kind");
        refused(revisionId, withoutKind, "STEP_KIND_REQUIRED", "stepKind");
        ObjectNode emptyEntry = chainMapping("E_SUPPLIER", "eenheid", null);
        ((ArrayNode) emptyEntry.get("steps")).addNull();
        refused(revisionId, emptyEntry, "STEPS_INVALID", "empty entry");
        refused(revisionId, chainMapping("E_SUPPLIER", "eenheid", null, "AFFIX", "prefix=" + "X".repeat(1000)),
                "STEP_CONFIG_TOO_LONG", "at most 1000");
        // Een onbekende soort (MAP_CONTAINS bestaat pas vanaf S8d) wordt geweigerd zoals een onbekende transformKind.
        addMapping(revisionId, chainMapping("E_SUPPLIER", "eenheid", null, "MAP_CONTAINS", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_BODY_UNREADABLE"));

        assertThat(count("select count(*) from import_field_mapping where definition_revision_id = ?", revisionId))
                .isZero();
        assertThat(count("select count(*) from import_field_transform_step s join import_field_mapping m on m.id = "
                + "s.mapping_id where m.definition_revision_id = ?", revisionId)).isZero();
    }

    @Test
    void deletingAMappingDeletesItsSteps() throws Exception {
        Draft draft = draft("DELETE", "OWN_DEFINITION");
        long mappingId = id(addMapping(draft.revisionId(), chainMapping("E_SUPPLIER", "eenheid", null,
                "AFFIX", "prefix=X", "UPPER", null)).andExpect(status().isCreated()));

        mockMvc.perform(delete(SETUP + "/revisions/{r}/mappings/{m}", draft.revisionId(), mappingId).with(as(USER)))
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from import_field_mapping where id = ?", mappingId)).isZero();
        assertThat(count("select count(*) from import_field_transform_step where mapping_id = ?", mappingId)).isZero();
    }

    // --- Kopie (RevisionCopier.copyMappingChildren) ------------------------------------------------------------

    @Test
    void aSuccessorDraftAndAMaterialisationCarryTheSteps() throws Exception {
        // Opvolgende DRAFT: stappen en vertaalrijen van dezelfde keten gaan mee.
        Draft own = draft("COPY", "OWN_DEFINITION");
        ObjectNode withMap = chainMapping("E_SUPPLIER", "eenheid", null, "AFFIX", "prefix=A-", "MAP",
                "caseSensitive=true");
        withMap.putArray("valueMap").addObject().put("source", "A-1").put("target", "EEN");
        addMapping(own.revisionId(), withMap).andExpect(status().isCreated());
        activate(own.revisionId()).andExpect(status().isOk());
        long successorId = id(mockMvc.perform(post(SETUP + "/revisions/{id}/successor", own.revisionId())
                        .with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeReason\":\"opvolger met keten\"}"))
                .andExpect(status().isCreated()));
        assertThat(stepsOfRevision(successorId)).containsExactly("E_SUPPLIER:1:AFFIX:prefix=A-",
                "E_SUPPLIER:2:MAP:caseSensitive=true").isEqualTo(stepsOfRevision(own.revisionId()));
        assertThat(count("select count(*) from import_field_value_map v join import_field_mapping m on m.id = "
                + "v.mapping_id where m.definition_revision_id = ?", successorId)).isEqualTo(1);
        // De kopie is een geldige configuratie (zonder stappen zou de CHAIN CONFIG_TRANSFORM_INVALID zijn).
        activate(successorId).andExpect(status().isOk());

        // Sjabloonmaterialisatie.
        Draft template = draft("TPL", "REUSABLE_TEMPLATE");
        addMapping(template.revisionId(), chainMapping("E_SUPPLIER", "eenheid", null, "SUBSTRING", "start=2",
                "UPPER", null)).andExpect(status().isCreated());
        activate(template.revisionId()).andExpect(status().isOk());
        MaterialisationView view = materialisation.materialise(template.definitionId(),
                new MaterialiseRequest(null, MaterialisationMode.NEW_DEFINITION, null, template.code() + "-AFG",
                        "Afgeleide definitie", null, template.code() + "-LNK", "Afgeleide koppeling",
                        template.code(), library(template.code()), null, List.of(), USER));
        assertThat(stepsOfRevision(view.definitionRevisionId()))
                .containsExactly("E_SUPPLIER:1:SUBSTRING:start=2", "E_SUPPLIER:2:UPPER:");
        assertThat(jdbc.queryForList("select s.created_by from import_field_transform_step s join import_field_mapping "
                + "m on m.id = s.mapping_id where m.definition_revision_id = ?", String.class,
                view.definitionRevisionId())).containsOnly(USER);
    }

    // --- Screening ---------------------------------------------------------------------------------------------

    /**
     * Legacy CALC 7 op een regel met prefix en suffix: de opzoeking gebeurt op {@code Trim(prefix) & Trim(v) &
     * Trim(suffix)}; opvulspaties vallen weg, een lege cel blijft leeg (geen miss), een onbekende sleutel verwerpt enkel
     * haar regel met de volledige opzoekwaarde. Byte voor byte dezelfde artikelvingerafdrukken als dezelfde waarden
     * zonder keten.
     */
    @Test
    void prefixSuffixAndMapScreenLikeLegacyAndAnEmptyCellStaysEmpty() throws Exception {
        ObjectNode chained = chainMapping("E_SUPPLIER", "eenheid", null, "AFFIX", "prefix=A-;suffix=-Z", "MAP",
                "caseSensitive=true");
        ArrayNode rows = chained.putArray("valueMap");
        rows.addObject().put("source", "A-1-Z").put("target", "EEN");
        rows.addObject().put("source", "A-2-Z").put("target", "TWEE");
        Chain chain = chain("SCRMAP", chained);
        Chain plain = chain("SCRMAPREF", mapping("E_SUPPLIER", "eenheid", "NONE", null));

        long chainBatch = batchId(upload(chain.taskId(), "REF-C", HEADER
                + "ACME;G1;R1;1,50;Boormachine;EUR;1\n"
                + "ACME;G1;R2;2,25;Schroevendraaier;EUR;  2   \n"
                + "ACME;G1;R3;3,00;Hamer;EUR;\n"
                + "ACME;G1;R4;4,00;Zaag;EUR;9\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.validRecordCount").value(3))
                .andExpect(jsonPath("$.rejectedRecordCount").value(1)));
        long plainBatch = batchId(upload(plain.taskId(), "REF-P", HEADER
                + "ACME;G1;R1;1,50;Boormachine;EUR;EEN\n"
                + "ACME;G1;R2;2,25;Schroevendraaier;EUR;TWEE\n"
                + "ACME;G1;R3;3,00;Hamer;EUR;\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validRecordCount").value(3)));

        assertThat(jdbc.queryForList("select issue_code || ':' || source_value || ':' || row_number from "
                + "import_row_issue where batch_id = ? and issue_code = ?", String.class, chainBatch,
                FieldTransform.CODE_MAPPING_VALUE_UNKNOWN)).containsExactly("MAPPING_VALUE_UNKNOWN:A-9-Z:5");
        List<String> chainFingerprints = fingerprints(chainBatch);
        assertThat(chainFingerprints).hasSize(3).doesNotContainNull();
        assertThat(chainFingerprints).isEqualTo(fingerprints(plainBatch));
    }

    /** Substring → trim → prefix → hoofdletters, zoals legacy; een deel met enkel spaties blijft leeg. */
    @Test
    void substringAffixAndUpperScreenInTheLegacyOrder() throws Exception {
        Chain chain = chain("SCRSUB", chainMapping("E_SUPPLIER", "eenheid", null, "SUBSTRING", "start=3;length=4",
                "AFFIX", "prefix=x-", "UPPER", null));
        Chain plain = chain("SCRSUBREF", mapping("E_SUPPLIER", "eenheid", "NONE", null));

        long chainBatch = batchId(upload(chain.taskId(), "REF-C", HEADER
                + "ACME;G1;R1;1,50;Boormachine;EUR;abcdefgh\n"
                + "ACME;G1;R2;2,25;Schroevendraaier;EUR;abcd    \n"
                + "ACME;G1;R3;3,00;Hamer;EUR;ab    gh\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validRecordCount").value(3)));
        long plainBatch = batchId(upload(plain.taskId(), "REF-P", HEADER
                + "ACME;G1;R1;1,50;Boormachine;EUR;X-CDEF\n"
                + "ACME;G1;R2;2,25;Schroevendraaier;EUR;X-CD\n"
                + "ACME;G1;R3;3,00;Hamer;EUR;\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validRecordCount").value(3)));

        assertThat(fingerprints(chainBatch)).hasSize(3).isEqualTo(fingerprints(plainBatch));
    }

    /**
     * Een keten die de grammatica schendt (hier rechtstreeks in de database gezet, buiten de setup-API om) blokkeert de
     * levering in stap B' met {@code CONFIG_TRANSFORM_INVALID}, vóór er één regel ingelezen is.
     */
    @Test
    void aChainOutsideTheGrammarBlocksTheDeliveryInStepBPrime() throws Exception {
        Chain chain = chain("SCRBAD", chainMapping("E_SUPPLIER", "eenheid", null, "UPPER", null));
        long mappingId = jdbc.queryForObject("select id from import_field_mapping where definition_revision_id = ?",
                Long.class, chain.revisionId());
        insert(mappingId, 2, "SUBSTRING", "start=2", USER, null);

        upload(chain.taskId(), "REF-BAD", HEADER + "ACME;G1;R1;1,50;Boormachine;EUR;abc\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.blockedCode").value("CONFIG_TRANSFORM_INVALID"));
    }

    // --- Hulp --------------------------------------------------------------------------------------------------

    private record Draft(String code, long definitionId, long revisionId) {
    }

    private record Chain(String code, long revisionId, long taskId) {
    }

    /** Organisatie, definitie en een DRAFT-revisie (header, drie-delige identiteit, canonicalisatieversie 2). */
    private Draft draft(String prefix, String usageType) throws Exception {
        String unique = "TS" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + prefix;
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

    /** Een CHAIN-mapping met {@code steps} uit paren soort, config ({@code null} = geen config). */
    private static ObjectNode chainMapping(String target, String source, String config, String... kindsAndConfigs) {
        ObjectNode mapping = mapping(target, source, "CHAIN", config);
        ArrayNode steps = mapping.putArray("steps");
        for (int i = 0; i < kindsAndConfigs.length; i += 2) {
            ObjectNode step = steps.addObject().put("kind", kindsAndConfigs[i]);
            if (kindsAndConfigs[i + 1] != null) {
                step.put("config", kindsAndConfigs[i + 1]);
            }
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

    private void insert(long mappingId, int stepNumber, String kind, String config, String createdBy,
                        String subject) {
        jdbc.update("insert into import_field_transform_step (mapping_id, step_number, transform_kind, "
                + "transform_config, created_at, created_by, created_by_subject) values (?, ?, ?, ?, current_timestamp, "
                + "?, ?)", mappingId, stepNumber, kind, config, createdBy, subject);
    }

    private List<String> steps(long mappingId) {
        return jdbc.queryForList("select step_number || ':' || transform_kind || ':' || coalesce(transform_config, '') "
                + "from import_field_transform_step where mapping_id = ? order by step_number", String.class, mappingId);
    }

    private List<String> stepsOfRevision(long revisionId) {
        return jdbc.queryForList("select m.target_field_code || ':' || s.step_number || ':' || s.transform_kind || ':' "
                + "|| coalesce(s.transform_config, '') from import_field_transform_step s join import_field_mapping m "
                + "on m.id = s.mapping_id where m.definition_revision_id = ? order by m.target_field_code, "
                + "s.step_number", String.class, revisionId);
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
