package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Slice CD-B2: de vlag "standaarden overschrijven" op de importdefinitie (beslissingslog 2026-10-23 "Vlag 'standaarden
 * overschrijven' op de importdefinitie").
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Standaard uit; {@code PUT /setup/definitions/{id}/overwrite-creation-defaults {enabled}} zet en wist de vlag met
 *       de audit van de laatste wijziging (naam, subject, tijdstip). {@code enabled} is verplicht.</li>
 *   <li>MANAGE vereist, {@code system} mag niet tekenen, onbekende definitie → 404; bij een fout verandert niets.</li>
 *   <li>{@code DefinitionRow} en {@code RevisionDetail} tonen de vlag van de definitie.</li>
 *   <li>G2a: een losgekoppelde kopie erft de vlag van de gedeelde definitie, zonder audit; de gedeelde definitie blijft
 *       ongewijzigd.</li>
 * </ul>
 */
class DefinitionOverwriteFlagHttpTest extends LegacyConfigS2TestBase {

    private static final String SETUP = "/api/catalog-import/setup";
    private static final String PATH = SETUP + "/definitions/{id}/overwrite-creation-defaults";

    @Test
    void theFlagIsOffByDefaultAndCanBeSetAndUnsetWithAnAudit() throws Exception {
        Definition definition = definition();
        assertThat(flagRow(definition.id()).get("overwrite_creation_defaults")).isEqualTo(false);

        String on = putFlag(definition.id(), "{\"enabled\":true}", as(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definitionId").value(definition.id()))
                .andExpect(jsonPath("$.overwriteCreationDefaults").value(true))
                .andExpect(jsonPath("$.overwriteCreationDefaultsChangedBy").value(USER))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(on, "$.overwriteCreationDefaultsChangedAt")).isNotBlank();
        Map<String, Object> row = flagRow(definition.id());
        assertThat(row.get("overwrite_creation_defaults")).isEqualTo(true);
        assertThat(row.get("overwrite_creation_defaults_changed_by")).isEqualTo(USER);
        assertThat(row.get("overwrite_creation_defaults_changed_by_subject")).isEqualTo("test-sub-" + USER);
        Object firstChange = row.get("overwrite_creation_defaults_changed_at");
        assertThat(firstChange).isNotNull();

        putFlag(definition.id(), "{\"enabled\":false}", as(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overwriteCreationDefaults").value(false));
        Map<String, Object> off = flagRow(definition.id());
        assertThat(off.get("overwrite_creation_defaults")).isEqualTo(false);
        assertThat(off.get("overwrite_creation_defaults_changed_by")).isEqualTo(USER);
        assertThat(off.get("overwrite_creation_defaults_changed_at")).isNotNull();
    }

    @Test
    void enabledIsRequiredAndNothingChangesOnAnError() throws Exception {
        Definition definition = definition();
        Map<String, Object> before = flagRow(definition.id());

        putFlag(definition.id(), "{}", as(USER)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENABLED_REQUIRED"));
        putFlag(definition.id(), "{\"enabled\":null}", as(USER)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ENABLED_REQUIRED"));
        putFlag(definition.id(), "{\"enabled\":\"misschien\"}", as(USER)).andExpect(status().isBadRequest());

        assertThat(flagRow(definition.id())).isEqualTo(before);
    }

    @Test
    void anUnknownDefinitionIs404AndWithoutManageOrAsSystemNothingIsWritten() throws Exception {
        Definition definition = definition();
        Map<String, Object> before = flagRow(definition.id());

        putFlag(999_999_999L, "{\"enabled\":true}", as(USER)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEFINITION_NOT_FOUND"));
        putFlag(definition.id(), "{\"enabled\":true}", as(USER, Permission.READ)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        putFlag(definition.id(), "{\"enabled\":true}", withoutPermissions(USER)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        putFlag(definition.id(), "{\"enabled\":true}", withoutPermissions("system")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(flagRow(definition.id())).isEqualTo(before);
    }

    @Test
    void theDefinitionListAndTheRevisionDetailShowTheFlagOfTheDefinition() throws Exception {
        Definition definition = definition();
        long revisionId = id(mockMvc.perform(post(SETUP + "/definitions/{id}/revisions", definition.id())
                        .with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixedValueHttpTest.fixed(null, null, false)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(listedFlag(definition)).isFalse();
        assertThat(detailFlag(definition.id(), revisionId)).isFalse();

        putFlag(definition.id(), "{\"enabled\":true}", as(USER)).andExpect(status().isOk());

        assertThat(listedFlag(definition)).isTrue();
        assertThat(detailFlag(definition.id(), revisionId)).isTrue();
    }

    @Test
    void aDetachedCopyInheritsTheFlagOfTheSharedDefinitionWithoutAudit() throws Exception {
        ObjectNode a = applicable();
        ObjectNode b = a.deepCopy();
        interfaceCode(b, interfaceCode(a) + "B");
        previewAndApply(receive(a), "libraryCode", "LIB-A").andExpect(status().isOk());
        previewAndApply(receive(b), "libraryCode", "LIB-B").andExpect(status().isOk());
        long shared = definitionId(supplier(a), "VROOAM2");
        putFlag(shared, "{\"enabled\":true}", as(USER)).andExpect(status().isOk());
        Map<String, Object> sharedBefore = flagRow(shared);

        // A wijkt af van de groep en wordt losgekoppeld (zonder ACTIVE revisie: revisie 1 van de kopie is een DRAFT).
        line(a, 26).put("column", 17);
        previewAndApply(receive(a), "detachFromShared", "true").andExpect(status().isOk());
        long copy = definitionId(supplier(a), "LEGACY-" + interfaceCode(a));

        Map<String, Object> copyRow = flagRow(copy);
        assertThat(copyRow.get("overwrite_creation_defaults")).isEqualTo(true);
        assertThat(copyRow.get("overwrite_creation_defaults_changed_at")).isNull();
        assertThat(copyRow.get("overwrite_creation_defaults_changed_by")).isNull();
        assertThat(copyRow.get("overwrite_creation_defaults_changed_by_subject")).isNull();
        assertThat(flagRow(shared)).isEqualTo(sharedBefore);
    }

    @Test
    void aDetachedCopyOfASharedDefinitionWithTheFlagOffHasItOff() throws Exception {
        ObjectNode a = applicable();
        ObjectNode b = a.deepCopy();
        interfaceCode(b, interfaceCode(a) + "B");
        previewAndApply(receive(a), "libraryCode", "LIB-A").andExpect(status().isOk());
        previewAndApply(receive(b), "libraryCode", "LIB-B").andExpect(status().isOk());

        line(a, 26).put("column", 17);
        previewAndApply(receive(a), "detachFromShared", "true").andExpect(status().isOk());
        long copy = definitionId(supplier(a), "LEGACY-" + interfaceCode(a));

        assertThat(flagRow(copy).get("overwrite_creation_defaults")).isEqualTo(false);
        // De migratie zet de vlag nooit, ook niet op de gedeelde definitie.
        assertThat(flagRow(definitionId(supplier(a), "VROOAM2")).get("overwrite_creation_defaults")).isEqualTo(false);
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private record Definition(long id, long sourceOrganisationId) {
    }

    private Definition definition() throws Exception {
        String supplier = "CDF" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        long organisationId = id(mockMvc.perform(post(SETUP + "/source-organisations").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + supplier + "\",\"name\":\"" + supplier + " BV\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long definitionId = id(mockMvc.perform(post(SETUP + "/definitions").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + supplier + "\",\"code\":\"" + supplier
                                + "-DEF\",\"name\":\"" + supplier + "\",\"usageType\":\"OWN_DEFINITION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Definition(definitionId, organisationId);
    }

    private ResultActions putFlag(long definitionId, String body, OidcLoginRequestPostProcessor actor) throws Exception {
        return mockMvc.perform(put(PATH, definitionId).with(actor).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private boolean listedFlag(Definition definition) throws Exception {
        String body = mockMvc.perform(get("/api/catalog-import/definitions")
                        .param("sourceOrganisationId", String.valueOf(definition.sourceOrganisationId()))
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Boolean> flags = JsonPath.read(body, "$.content[?(@.id == " + definition.id()
                + ")].overwriteCreationDefaults");
        assertThat(flags).hasSize(1);
        return flags.get(0);
    }

    private boolean detailFlag(long definitionId, long revisionId) throws Exception {
        String body = mockMvc.perform(get("/api/catalog-import/definitions/{d}/revisions/{r}", definitionId, revisionId)
                        .with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.overwriteCreationDefaults");
    }

    private Map<String, Object> flagRow(long definitionId) {
        return jdbc.queryForMap("select overwrite_creation_defaults, overwrite_creation_defaults_changed_at, "
                + "overwrite_creation_defaults_changed_by, overwrite_creation_defaults_changed_by_subject "
                + "from import_definition where id = ?", definitionId);
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }
}
