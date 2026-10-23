package be.dda.catalogimport.service;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.domain.DefinitionUsageType;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.service.SetupService.RevisionView;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * EB1 (beslissingslog 2026-10-23 "Editor-API-gaten"; fase3-rules-design par. 15.1): een referentieveld is altijd
 * kritiek. Een uitdrukkelijke {@code NON_CRITICAL} bij {@code POST /setup/revisions/{rid}/mappings} of
 * {@code PUT .../mappings/{mid}} geeft vooraf 400 {@code CRITICALITY_INVALID} (voorheen een 500 op
 * {@code ck_import_field_mapping_reference_critical}); er wordt niets bewaard, bij de PUT blijft de oude mapping
 * ongemoeid. {@code null} betekent de standaard; een prijscomponent mag wel {@code NON_CRITICAL} zijn.
 */
@SpringBootTest(properties = {"catalogimport.setup-api.enabled=true",
        "spring.datasource.hikari.maximum-pool-size=4"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupMappingCriticalityHttpTest {

    private static final String USER = MaterialisationFixtures.USER;
    private static final String SETUP = "/api/catalog-import/setup";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RevisionSuccessorService successors;
    @Autowired
    private JdbcTemplate jdbc;

    private MaterialisationFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = MaterialisationFixtures.of(context);
    }

    // --- POST ----------------------------------------------------------------------------------------------------

    @Test
    void aReferenceFieldWithAnExplicitNonCriticalIsRefusedBeforeAnythingIsSaved() throws Exception {
        long revisionId = draft("REFNC");
        long before = mappingCount(revisionId);

        postMapping(revisionId, mapping("EAN", "NON_CRITICAL")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CRITICALITY_INVALID"));

        assertThat(mappingCount(revisionId)).isEqualTo(before);
        assertThat(targets(revisionId)).doesNotContain("EAN");
    }

    @Test
    void aReferenceFieldWithoutACriticalityBecomesCritical() throws Exception {
        long revisionId = draft("REFNULL");

        postMapping(revisionId, mapping("EAN", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.criticality").value("CRITICAL"));

        assertThat(storedCriticality(revisionId, "EAN")).isEqualTo("CRITICAL");
    }

    @Test
    void aReferenceFieldWithAnExplicitCriticalIsAccepted() throws Exception {
        long revisionId = draft("REFCRIT");

        postMapping(revisionId, mapping("PIM_ID", "CRITICAL")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.criticality").value("CRITICAL"));
    }

    @Test
    void aPriceComponentMayBeNonCriticalAndDefaultsToCritical() throws Exception {
        long revisionId = draft("PRICE");

        postMapping(revisionId, mapping("AKP_PCT", "NON_CRITICAL")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.criticality").value("NON_CRITICAL"));
        postMapping(revisionId, mapping("VKP1_PCT", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.criticality").value("CRITICAL"));

        assertThat(storedCriticality(revisionId, "AKP_PCT")).isEqualTo("NON_CRITICAL");
        assertThat(storedCriticality(revisionId, "VKP1_PCT")).isEqualTo("CRITICAL");
    }

    @Test
    void anOrdinaryFieldDefaultsToNonCriticalAndMayBeCritical() throws Exception {
        long revisionId = draft("PLAIN");

        postMapping(revisionId, mapping("SUPPLIER_BARCODE", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.criticality").value("NON_CRITICAL"));
        postMapping(revisionId, mapping("DESCRIPTION", "CRITICAL")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.criticality").value("CRITICAL"));
    }

    // --- PUT (vervangen) -----------------------------------------------------------------------------------------

    @Test
    void replacingWithAReferenceFieldAndNonCriticalIsRefusedAndTheOldMappingStays() throws Exception {
        long revisionId = draft("REPLNC");
        long oldId = id(postMapping(revisionId, mapping("SUPPLIER_BARCODE", null)).andExpect(status().isCreated()));
        long before = mappingCount(revisionId);

        putMapping(revisionId, oldId, mapping("EAN", "NON_CRITICAL")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CRITICALITY_INVALID"));

        assertThat(mappingCount(revisionId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from import_field_mapping where id = ?", Long.class, oldId))
                .isEqualTo(1L);
        assertThat(targets(revisionId)).contains("SUPPLIER_BARCODE").doesNotContain("EAN");
    }

    @Test
    void replacingAReferenceMappingWithoutACriticalityKeepsItCritical() throws Exception {
        long revisionId = draft("REPLNULL");
        long oldId = id(postMapping(revisionId, mapping("EAN", "CRITICAL")).andExpect(status().isCreated()));

        putMapping(revisionId, oldId, mapping("EAN", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.criticality").value("CRITICAL"));

        assertThat(storedCriticality(revisionId, "EAN")).isEqualTo("CRITICAL");
    }

    @Test
    void replacingAPriceComponentWithNonCriticalIsAllowed() throws Exception {
        long revisionId = draft("REPLPRICE");
        long oldId = id(postMapping(revisionId, mapping("AKP_PCT", null)).andExpect(status().isCreated()));

        putMapping(revisionId, oldId, mapping("AKP_PCT", "NON_CRITICAL")).andExpect(status().isOk())
                .andExpect(jsonPath("$.criticality").value("NON_CRITICAL"));

        assertThat(storedCriticality(revisionId, "AKP_PCT")).isEqualTo("NON_CRITICAL");
    }

    // --- Hulp ----------------------------------------------------------------------------------------------------

    /** Een DRAFT-opvolger van een ACTIVE revisie (die enkel een E_SUPPLIER-mapping en een filter heeft). */
    private long draft(String prefix) {
        MaterialisationFixtures.Template source =
                fixtures.template(prefix, DefinitionUsageType.OWN_DEFINITION, RevisionStatus.ACTIVE);
        RevisionView successor = successors.createSuccessor(source.revision().getId(),
                "Kritiek per veld (" + prefix + ")", new ActorIdentity(USER, "test-sub-" + USER));
        return successor.id();
    }

    private static ObjectNode mapping(String target, String criticality) {
        ObjectNode mapping = JSON.createObjectNode();
        mapping.put("targetFieldCode", target);
        mapping.put("sourceReference", "kolom-" + target.toLowerCase());
        if (criticality != null) {
            mapping.put("criticality", criticality);
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

    private long mappingCount(long revisionId) {
        Long count = jdbc.queryForObject("select count(*) from import_field_mapping where definition_revision_id = ?",
                Long.class, revisionId);
        return count == null ? 0L : count;
    }

    private List<String> targets(long revisionId) {
        return jdbc.queryForList("select target_field_code from import_field_mapping "
                + "where definition_revision_id = ?", String.class, revisionId);
    }

    private String storedCriticality(long revisionId, String target) {
        return jdbc.queryForObject("select criticality from import_field_mapping "
                + "where definition_revision_id = ? and target_field_code = ?", String.class, revisionId, target);
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
