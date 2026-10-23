package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.TemplateMaterialisationService;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationMode;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialisationView;
import be.dda.catalogimport.service.TemplateMaterialisationService.MaterialiseRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Additieve velden op {@code GET /definitions/{id}/revisions} ({@code RevisionRow}, punt 2) en {@code GET /definitions}
 * ({@code DefinitionRow}, punt 5) volgens beslissingslog 2026-10-23 "API-uitbreiding voor de inrichtingsschermen
 * (ontbrekende gegevens)", slice B1.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Een revisie toont wanneer en door wie ze gemaakt en goedgekeurd werd (een DRAFT is nooit goedgekeurd), haar
 *       wijzigingsreden, haar herkomstrevisie en of haar inhoud door een legacy-overname geschreven werd
 *       ({@code fromLegacy}: een eigen {@code legacy_config_revision_origin}-rij; een handmatige opvolger niet).</li>
 *   <li>Een definitie toont haar actieve en haar concept-versie (id + nummer), of de bronorganisatie actief is, op welke
 *       definitie ze gebaseerd is ({@code import_definition.based_on_definition_id}) en haar legacy-herkomst
 *       ({@code legacy_config_definition_origin}: gedeelde definitie, sjabloon, losgekoppelde kopie of geen).</li>
 * </ul>
 * Opbouw via de echte APPLY-, activatie-, opvolger-, loskoppel- en materialisatiepaden (geen rijen met de hand), zodat
 * de test leest wat {@code LegacyConfigApplyService} werkelijk vastlegt.
 */
class SetupQueryLegacyFieldsHttpTest extends LegacyConfigS2TestBase {

    private static final String API = "/api/catalog-import";
    private static final String DETACH = "detachFromShared";
    private static final String MANUAL_REASON = "Handmatige wijziging (test B1)";
    private static final ActorIdentity ACTOR = new ActorIdentity(USER, "test-sub-" + USER);

    @Autowired
    private SourceOrganisationRepository organisationRepository;
    @Autowired
    private ImportDefinitionRepository definitionRepository;
    @Autowired
    private TemplateMaterialisationService materialisation;

    @Test
    void legacyDefinitionsShowTheirOriginKindCodesBasisAndRevisionsTheirAuditAndLegacyFlag() throws Exception {
        Instant before = Instant.now().minusSeconds(5);
        // Groep van twee interfaces op één gedeelde definitie (S7): sjabloon VROOAM2-SJ + gedeelde definitie VROOAM2.
        ObjectNode a = applicable();
        ObjectNode b = a.deepCopy();
        interfaceCode(b, interfaceCode(a) + "B");
        previewAndApply(receive(a), "libraryCode", "LIB-A").andExpect(status().isOk());
        previewAndApply(receive(b), "libraryCode", "LIB-B").andExpect(status().isOk());
        String supplier = supplier(a);
        long template = definitionId(supplier, "VROOAM2-SJ");
        long shared = definitionId(supplier, "VROOAM2");
        long templateRevision = ((Number) revisionRows(template).get(0).get("id")).longValue();
        long legacyRevision = ((Number) revisionRows(shared).get(0).get("id")).longValue();

        // Vóór activatie: de revisie uit de APPLY is een DRAFT, nooit goedgekeurd, wel fromLegacy.
        String draftList = revisionsBody(shared);
        Map<String, Object> draftRow = row(draftList, legacyRevision);
        assertThat(draftRow.get("status")).isEqualTo("DRAFT");
        assertThat(Instant.parse((String) draftRow.get("createdAt"))).isAfter(before);
        assertThat(draftRow.get("createdBy")).isEqualTo(USER);
        assertThat(draftRow.get("approvedAt")).isNull();
        assertThat(draftRow.get("approvedBy")).isNull();
        assertThat(draftRow.get("changeReason")).isEqualTo(REASON);
        assertThat(((Number) draftRow.get("basedOnRevisionId")).longValue()).isEqualTo(templateRevision);
        assertThat(draftRow.get("fromLegacy")).isEqualTo(true);

        // De mens activeert; daarna een nieuwe afwijkende interface die een eigen definitie krijgt (G2a).
        setup.activateRevision(legacyRevision, ACTOR);
        ObjectNode other = a.deepCopy();
        interfaceCode(other, interfaceCode(a) + "S");
        detail(other, "Separator", "Comma");
        long diverging = receive(other);
        String detachPreview = previewBody(diverging, "libraryCode", "LIB-S", DETACH, "true");
        assertThat(blockingCodes(detachPreview)).isEmpty();
        apply(diverging, JsonPath.read(detachPreview, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S",
                DETACH, "true").andExpect(status().isOk());
        long detached = definitionId(supplier, "LEGACY-" + interfaceCode(other));

        // Handmatige opvolger op de gedeelde definitie: geen legacy-herkomst.
        long manualSuccessor = successors.createSuccessor(legacyRevision, MANUAL_REASON, ACTOR).id();

        // Materialisatie uit het (geactiveerde) sjabloon: een nieuwe, handmatige definitie based_on het sjabloon.
        setup.activateRevision(templateRevision, ACTOR);
        MaterialisationView materialised = materialisation.materialise(template, new MaterialiseRequest(null,
                MaterialisationMode.NEW_DEFINITION, null, "MAT-" + interfaceCode(a), "Gematerialiseerd (test B1)",
                MANUAL_REASON, "MAT-" + interfaceCode(a), "Koppeling via de wizard", supplier, "LIB-M", null,
                List.of(), USER), ACTOR);
        assertThat(materialised.definitionCreated()).isTrue();

        // --- Revisielijst: ACTIVE goedgekeurd, opvolger handmatig ------------------------------------------------
        String revisions = revisionsBody(shared);
        Map<String, Object> activeRow = row(revisions, legacyRevision);
        assertThat(activeRow.get("status")).isEqualTo("ACTIVE");
        assertThat(Instant.parse((String) activeRow.get("approvedAt"))).isAfter(before);
        assertThat(activeRow.get("approvedBy")).isEqualTo(USER);
        assertThat(activeRow.get("fromLegacy")).isEqualTo(true);
        Map<String, Object> successorRow = row(revisions, manualSuccessor);
        assertThat(successorRow.get("status")).isEqualTo("DRAFT");
        assertThat(successorRow.get("revisionNumber")).isEqualTo(2);
        assertThat(successorRow.get("createdBy")).isEqualTo(USER);
        assertThat(successorRow.get("createdAt")).isNotNull();
        assertThat(successorRow.get("approvedAt")).isNull();
        assertThat(successorRow.get("approvedBy")).isNull();
        assertThat(successorRow.get("changeReason")).isEqualTo(MANUAL_REASON);
        assertThat(((Number) successorRow.get("basedOnRevisionId")).longValue()).isEqualTo(legacyRevision);
        assertThat(successorRow.get("fromLegacy")).isEqualTo(false);

        // --- Definitielijst -------------------------------------------------------------------------------------
        long organisationId = organisationRepository.findByCode(supplier).orElseThrow().getId();
        String definitions = definitionsBody(organisationId);

        Map<String, Object> sharedRow = row(definitions, shared);
        assertThat(((Number) sharedRow.get("activeRevisionId")).longValue()).isEqualTo(legacyRevision);
        assertThat(sharedRow.get("activeRevisionNumber")).isEqualTo(1);
        assertThat(((Number) sharedRow.get("draftRevisionId")).longValue()).isEqualTo(manualSuccessor);
        assertThat(sharedRow.get("draftRevisionNumber")).isEqualTo(2);
        assertThat(sharedRow.get("sourceOrganisationActive")).isEqualTo(true);
        assertThat(((Number) sharedRow.get("basedOnDefinitionId")).longValue()).isEqualTo(template);
        assertThat(sharedRow.get("legacyOriginKind")).isEqualTo("MIGRATED_SHARED");
        assertThat(sharedRow.get("legacyDefinitionCodes")).isEqualTo(List.of("VROOAM2"));

        Map<String, Object> templateRow = row(definitions, template);
        assertThat(templateRow.get("usageType")).isEqualTo("REUSABLE_TEMPLATE");
        assertThat(((Number) templateRow.get("activeRevisionId")).longValue()).isEqualTo(templateRevision);
        assertThat(templateRow.get("draftRevisionId")).isNull();
        assertThat(templateRow.get("draftRevisionNumber")).isNull();
        assertThat(templateRow.get("basedOnDefinitionId")).isNull();
        assertThat(templateRow.get("legacyOriginKind")).isEqualTo("MIGRATED_TEMPLATE");
        assertThat(templateRow.get("legacyDefinitionCodes")).isEqualTo(List.of("VROOAM2"));

        Map<String, Object> detachedRow = row(definitions, detached);
        assertThat(detachedRow.get("activeRevisionId")).isNull();
        assertThat(detachedRow.get("activeRevisionNumber")).isNull();
        assertThat(detachedRow.get("draftRevisionNumber")).isEqualTo(1);
        assertThat(((Number) detachedRow.get("basedOnDefinitionId")).longValue()).isEqualTo(shared);
        assertThat(detachedRow.get("legacyOriginKind")).isEqualTo("MIGRATED_DETACHED");
        assertThat(detachedRow.get("legacyDefinitionCodes")).isEqualTo(List.of("VROOAM2"));
        // De eigen kopie draagt haar eigen 020-4-rij: haar enige revisie komt uit de legacy-overname.
        long detachedRevision = ((Number) detachedRow.get("draftRevisionId")).longValue();
        assertThat(row(revisionsBody(detached), detachedRevision).get("fromLegacy")).isEqualTo(true);

        Map<String, Object> materialisedRow = row(definitions, materialised.definitionId());
        assertThat(((Number) materialisedRow.get("basedOnDefinitionId")).longValue()).isEqualTo(template);
        assertThat(materialisedRow.get("legacyOriginKind")).isNull();
        assertThat(materialisedRow.get("legacyDefinitionCodes")).isEqualTo(List.of());
        assertThat(((Number) materialisedRow.get("draftRevisionId")).longValue())
                .isEqualTo(materialised.definitionRevisionId());
        assertThat(row(revisionsBody(materialised.definitionId()), materialised.definitionRevisionId())
                .get("fromLegacy")).isEqualTo(false);

        // De bulkopzoekingen gelden per pagina, ook bij een filter en bij één rij per pagina.
        mockMvc.perform(read(get(API + "/definitions").param("sourceOrganisationId", String.valueOf(organisationId))
                        .param("usageType", "REUSABLE_TEMPLATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(template))
                .andExpect(jsonPath("$.content[0].legacyOriginKind").value("MIGRATED_TEMPLATE"));
        String sizeOne = mockMvc.perform(read(get(API + "/definitions")
                        .param("sourceOrganisationId", String.valueOf(organisationId)).param("size", "1")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int totalPages = JsonPath.read(sizeOne, "$.totalPages");
        assertThat(totalPages).isEqualTo(4);
        for (int page = 0; page < totalPages; page++) {
            String onePage = mockMvc.perform(read(get(API + "/definitions")
                            .param("sourceOrganisationId", String.valueOf(organisationId)).param("size", "1")
                            .param("page", String.valueOf(page))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            long id = ((Number) JsonPath.read(onePage, "$.content[0].id")).longValue();
            assertThat((Map<String, Object>) JsonPath.read(onePage, "$.content[0]")).isEqualTo(row(definitions, id));
        }

        // Herhaald lezen geeft hetzelfde (alleen-lezen, geen bijwerkingen).
        assertThat(definitionsBody(organisationId)).isEqualTo(definitions);
        assertThat(revisionsBody(shared)).isEqualTo(revisions);
    }

    @Test
    void aManualDefinitionOfAnInactiveOrganisationWithoutRevisionsHasOnlyEmptyNewFields() throws Exception {
        String unique = "B1" + Long.toString(System.nanoTime(), 36).toUpperCase();
        SourceOrganisation inactive = new SourceOrganisation(unique + "-ORG", unique + " inactief",
                SourceOrganisationType.SUPPLIER);
        inactive.setActive(false);
        inactive = organisationRepository.saveAndFlush(inactive);
        ImportDefinition manual = definitionRepository.saveAndFlush(
                new ImportDefinition(inactive, unique + "-DEF", unique + " handmatig", USER));

        String definitions = definitionsBody(inactive.getId());
        Map<String, Object> row = row(definitions, manual.getId());
        assertThat(row.get("activeRevisionId")).isNull();
        assertThat(row.get("activeRevisionNumber")).isNull();
        assertThat(row.get("draftRevisionId")).isNull();
        assertThat(row.get("draftRevisionNumber")).isNull();
        assertThat(row.get("sourceOrganisationActive")).isEqualTo(false);
        assertThat(row.get("basedOnDefinitionId")).isNull();
        assertThat(row.get("legacyOriginKind")).isNull();
        assertThat(row.get("legacyDefinitionCodes")).isEqualTo(List.of());
        // Alle nieuwe velden staan in de JSON, ook als ze leeg zijn (vaste vorm voor de frontend).
        assertThat(row).containsKeys("activeRevisionNumber", "draftRevisionId", "draftRevisionNumber",
                "sourceOrganisationActive", "basedOnDefinitionId", "legacyOriginKind", "legacyDefinitionCodes");

        // Lege revisielijst en een lege pagina: geen fout (de bulkopzoekingen slaan een lege pagina over).
        mockMvc.perform(read(get(API + "/definitions/{id}/revisions", manual.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(read(get(API + "/definitions").param("sourceOrganisationId", String.valueOf(inactive.getId()))
                        .param("page", "5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    // --- Hulp -----------------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder read(MockHttpServletRequestBuilder request) {
        return request.with(as(USER, Permission.READ));
    }

    private String definitionsBody(long organisationId) throws Exception {
        return mockMvc.perform(read(get(API + "/definitions")
                        .param("sourceOrganisationId", String.valueOf(organisationId)).param("size", "200")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String revisionsBody(long definitionId) throws Exception {
        return mockMvc.perform(read(get(API + "/definitions/{id}/revisions", definitionId).param("size", "200")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** De rij met dit id uit {@code content}; precies één. */
    private static Map<String, Object> row(String body, long id) {
        List<Map<String, Object>> rows = JsonPath.read(body, "$.content[?(@.id == " + id + ")]");
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
