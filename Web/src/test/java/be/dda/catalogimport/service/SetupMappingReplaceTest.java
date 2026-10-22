package be.dda.catalogimport.service;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.domain.BookmarkDataType;
import be.dda.catalogimport.domain.BookmarkUsagePlace;
import be.dda.catalogimport.domain.BookmarkValueScope;
import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.SetupService.RevisionView;
import be.dda.catalogimport.web.Permission;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * G2c-1 ({@code docs/design/g2-design.md} par. G2c): {@code PUT /setup/revisions/{rid}/mappings/{mid}} vervangt een
 * mapping van een DRAFT (verwijderen + toevoegen in een transactie).
 *
 * <ul>
 *   <li><b>Normaal:</b> het volgnummer blijft, het id is nieuw, de vertaalrijen en ketenstappen zijn die van het
 *       verzoek (de oude zijn weg) en het antwoord en het revisiedetail geven alle aanmaakvelden terug.</li>
 *   <li><b>Fouten, telkens zonder wijziging:</b> ongeldige configuratie 400 {@code CONFIG_TRANSFORM_INVALID}; ACTIVE
 *       revisie 409 {@code REVISION_NOT_EDITABLE}; een bookmarkdeclaratie die op het oude doelveld steunt 409
 *       {@code CONFIG_BOOKMARK_*}; onbekende mapping 404 {@code MAPPING_NOT_FOUND}; 403 zonder {@code MANAGE}.</li>
 * </ul>
 * De rechtentabel staat in {@code PermissionCoverageTest}, het bereik zonder setup-vlag (sinds NT-3 geen vlag meer op
 * de setup-schrijfpaden) in {@code SetupApiDisabledTest}.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupMappingReplaceTest {

    private static final String USER = MaterialisationFixtures.USER;
    private static final String SETUP = "/api/catalog-import/setup";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SetupService setup;
    @Autowired
    private RevisionSuccessorService successors;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private JdbcTemplate jdbc;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    @Test
    void replacingKeepsTheSequenceNumberReplacesTheChildRowsAndReturnsEveryCreateField() throws Exception {
        Draft draft = draft("REPL");
        long secondId = id(postMapping(draft.id(), mapMapping("SUPPLIER_BARCODE", "referentie", "caseSensitive=true",
                "A", "1", "B", "2")).andExpect(status().isCreated()));
        int sequence = jdbc.queryForObject("select sequence_number from import_field_mapping where id = ?",
                Integer.class, secondId);
        assertThat(sequence).isEqualTo(2);

        ObjectNode replacement = mapMapping("SUPPLIER_BARCODE", "referentie", "caseSensitive=true", "X", "9");
        replacement.put("sequenceNumber", 77); // wordt genegeerd: het volgnummer van de oude mapping blijft
        replacement.put("required", true);
        replacement.put("defaultValue", "ONBEKEND");
        replacement.put("maxLength", 20);
        replacement.put("zeroAllowed", true);
        replacement.put("negativeAllowed", true);
        String body = putMapping(draft.id(), secondId, replacement).andExpect(status().isOk())
                .andExpect(jsonPath("$.sequenceNumber").value(2))
                .andExpect(jsonPath("$.targetFieldCode").value("SUPPLIER_BARCODE"))
                .andExpect(jsonPath("$.transformKind").value("MAP"))
                .andExpect(jsonPath("$.transformConfig").value("caseSensitive=true"))
                .andExpect(jsonPath("$.required").value(true))
                .andExpect(jsonPath("$.defaultValue").value("ONBEKEND"))
                .andExpect(jsonPath("$.maxLength").value(20))
                .andExpect(jsonPath("$.zeroAllowed").value(true))
                .andExpect(jsonPath("$.negativeAllowed").value(true))
                .andExpect(jsonPath("$.valueMap.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        long newId = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertThat(newId).isNotEqualTo(secondId);
        assertThat(count("select count(*) from import_field_mapping where id = ?", secondId)).isZero();
        assertThat(count("select count(*) from import_field_value_map where mapping_id = ?", secondId)).isZero();
        assertThat(valueMap(newId)).containsExactly("1:X>9");
        assertThat(jdbc.queryForObject("select sequence_number from import_field_mapping where id = ?",
                Integer.class, newId)).isEqualTo(2);
        assertThat(count("select count(*) from import_field_mapping where definition_revision_id = ?",
                draft.id())).isEqualTo(2);

        // Het revisiedetail geeft dezelfde velden terug.
        mockMvc.perform(get("/api/catalog-import/definitions/{d}/revisions/{r}",
                        draft.template().definition().getId(), draft.id()).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mappings[?(@.id == " + newId + ")].defaultValue").value("ONBEKEND"))
                .andExpect(jsonPath("$.mappings[?(@.id == " + newId + ")].maxLength").value(20))
                .andExpect(jsonPath("$.mappings[?(@.id == " + newId + ")].required").value(true))
                .andExpect(jsonPath("$.mappings[?(@.id == " + newId + ")].transformKind").value("MAP"))
                .andExpect(jsonPath("$.filters[0].caseSensitive").exists())
                .andExpect(jsonPath("$.filters[0].trimBeforeCompare").exists())
                .andExpect(jsonPath("$.filters[0].nullBehaviour").exists())
                .andExpect(jsonPath("$.filters[0].missingColumnBehaviour").exists());
    }

    @Test
    void replacingWithAnotherKindDropsTheOldChildRows() throws Exception {
        Draft draft = draft("KIND");
        long secondId = id(postMapping(draft.id(), mapMapping("SUPPLIER_BARCODE", "referentie", "caseSensitive=true",
                "A", "1")).andExpect(status().isCreated()));

        ObjectNode plain = JSON.createObjectNode();
        plain.put("targetFieldCode", "SUPPLIER_BARCODE");
        plain.put("sourceReference", "referentie");
        putMapping(draft.id(), secondId, plain).andExpect(status().isOk())
                .andExpect(jsonPath("$.valueMap").isEmpty())
                .andExpect(jsonPath("$.sequenceNumber").value(2));

        assertThat(count("select count(*) from import_field_value_map v join import_field_mapping m on m.id = "
                + "v.mapping_id where m.definition_revision_id = ?", draft.id())).isZero();
    }

    @Test
    void anInvalidConfigurationIs400AndNothingIsChanged() throws Exception {
        Draft draft = draft("BAD");
        long secondId = id(postMapping(draft.id(), mapMapping("SUPPLIER_BARCODE", "referentie", "caseSensitive=true",
                "A", "1")).andExpect(status().isCreated()));

        ObjectNode broken = JSON.createObjectNode();
        broken.put("targetFieldCode", "SUPPLIER_BARCODE");
        broken.put("sourceReference", "referentie");
        broken.put("transformKind", "MAP"); // MAP zonder tabel of values=
        putMapping(draft.id(), secondId, broken).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_TRANSFORM_INVALID"));

        assertThat(count("select count(*) from import_field_mapping where id = ?", secondId)).isEqualTo(1);
        assertThat(valueMap(secondId)).containsExactly("1:A>1");
        assertThat(count("select count(*) from import_field_mapping where definition_revision_id = ?",
                draft.id())).isEqualTo(2);
    }

    @Test
    void anActiveRevisionIs409AndAnUnknownMappingIs404() throws Exception {
        Draft draft = draft("STATE");
        long activeMappingId = fieldMappings.findByRevisionIdWithTargetField(draft.sourceRevisionId()).get(0).getId();

        putMapping(draft.sourceRevisionId(), activeMappingId, fixed("E_SUPPLIER", "NIEUW")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));
        assertThat(fieldMappings.findById(activeMappingId)).isPresent();

        putMapping(draft.id(), 999_999_999L, fixed("E_SUPPLIER", "NIEUW")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MAPPING_NOT_FOUND"));
        // Een mapping van een andere revisie is hier geen geldig doel.
        putMapping(draft.id(), activeMappingId, fixed("E_SUPPLIER", "NIEUW")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MAPPING_NOT_FOUND"));
    }

    @Test
    void aBookmarkDeclarationThatRelyesOnTheOldTargetBlocksTheReplacementAndKeepsTheRow() throws Exception {
        Draft draft = draft("BMK");
        ImportDefinitionRevision revision = revisions.findById(draft.id()).orElseThrow();
        fixtures.declareOn(revision, "VASTE_LEVERANCIER", BookmarkValueScope.DEFINITION, true,
                BookmarkDataType.TEXT, 1, BookmarkUsagePlace.FIELD_MAPPING_FIXED_VALUE,
                MaterialisationFixtures.MAPPED_FIELD);
        long mappingId = fieldMappings.findByRevisionIdWithTargetField(draft.id()).get(0).getId();

        putMapping(draft.id(), mappingId, fixed("SUPPLIER_BARCODE", "NIEUW")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFIG_BOOKMARK_PLACE_UNRESOLVED"));
        List<String> targets = jdbc.queryForList("select target_field_code from import_field_mapping "
                + "where definition_revision_id = ?", String.class, draft.id());
        assertThat(targets).containsExactly("E_SUPPLIER");
        assertThat(fieldMappings.findById(mappingId)).isPresent();

        // Hetzelfde doelveld behouden mag wel: de declaratie blijft geldig.
        putMapping(draft.id(), mappingId, fixed("E_SUPPLIER", "NIEUW")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fixedValue").value("NIEUW"))
                .andExpect(jsonPath("$.valueKind").value("FIXED_VALUE"));
    }

    @Test
    void withoutManageThePutIs403() throws Exception {
        Draft draft = draft("DENY");
        long mappingId = fieldMappings.findByRevisionIdWithTargetField(draft.id()).get(0).getId();
        mockMvc.perform(put(SETUP + "/revisions/{r}/mappings/{m}", draft.id(), mappingId)
                        .with(as(USER, Permission.READ)).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(fixed("E_SUPPLIER", "NIEUW"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(fieldMappings.findById(mappingId)).isPresent();
    }

    // --- Hulp --------------------------------------------------------------------------------------------------

    private record Draft(long id, long sourceRevisionId, MaterialisationFixtures.Template template) {
    }

    /** Een ACTIVE revisie met een FIXED_VALUE-mapping op E_SUPPLIER en een filter, met een DRAFT-opvolger. */
    private Draft draft(String prefix) {
        MaterialisationFixtures.Template source =
                fixtures.template(prefix, DefinitionUsageType.OWN_DEFINITION, RevisionStatus.ACTIVE);
        RevisionView successor = successors.createSuccessor(source.revision().getId(),
                "Mapping vervangen (" + prefix + ")", new ActorIdentity(USER, "test-sub-" + USER));
        return new Draft(successor.id(), source.revision().getId(), source);
    }

    private static ObjectNode fixed(String target, String value) {
        ObjectNode mapping = JSON.createObjectNode();
        mapping.put("targetFieldCode", target);
        mapping.put("valueKind", "FIXED_VALUE");
        mapping.put("fixedValue", value);
        return mapping;
    }

    private static ObjectNode mapMapping(String target, String source, String config, String... pairs) {
        ObjectNode mapping = JSON.createObjectNode();
        mapping.put("targetFieldCode", target);
        mapping.put("sourceReference", source);
        mapping.put("transformKind", "MAP");
        mapping.put("transformConfig", config);
        ArrayNode valueMap = mapping.putArray("valueMap");
        for (int i = 0; i < pairs.length; i += 2) {
            valueMap.addObject().put("source", pairs[i]).put("target", pairs[i + 1]);
        }
        return mapping;
    }

    private ResultActions postMapping(long revisionId, ObjectNode mapping) throws Exception {
        return mockMvc.perform(post(SETUP + "/revisions/{id}/mappings", revisionId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(mapping)));
    }

    private ResultActions putMapping(long revisionId, long mappingId, ObjectNode mapping) throws Exception {
        return mockMvc.perform(put(SETUP + "/revisions/{id}/mappings/{m}", revisionId, mappingId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(mapping)));
    }

    private List<String> valueMap(long mappingId) {
        return jdbc.queryForList("select sequence_number || ':' || source_value || '>' || target_value "
                + "from import_field_value_map where mapping_id = ? order by sequence_number", String.class, mappingId);
    }

    private long count(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0L : count;
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
