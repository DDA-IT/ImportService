package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Legacy-config S6 zonder Prodis-leesbron (functie uit; zelfde context als {@link LegacyConfigS2TestBase}): een vaste
 * leverancier/groep wordt dan aanvaard met een waarschuwing {@code PRODIS_REFERENCE_NOT_VERIFIED}, nooit stil, en de
 * migratie zet de vaste waarde en de hoofdletters op de nieuwe revisie.
 */
class IdentityFixedValueNotConfiguredHttpTest extends LegacyConfigS2TestBase {

    private static final String SETUP = "/api/catalog-import/setup";

    @Test
    void withoutProdisAFixedValueIsAcceptedWithAWarningPerValue() throws Exception {
        String supplier = "S6N" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        mockMvc.perform(post(SETUP + "/source-organisations").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + supplier + "\",\"name\":\"" + supplier + " BV\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isCreated());
        long definitionId = ((Number) JsonPath.read(mockMvc.perform(post(SETUP + "/definitions").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + supplier + "\",\"code\":\"" + supplier
                                + "-DEF\",\"name\":\"" + supplier + "\",\"usageType\":\"OWN_DEFINITION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();

        String body = mockMvc.perform(post(SETUP + "/definitions/{id}/revisions", definitionId).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixedValueHttpTest.fixed(supplier, "GRP-A", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierFixedValue").value(supplier))
                .andExpect(jsonPath("$.identityUppercase").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat((List<String>) JsonPath.read(body, "$.warnings[*].code"))
                .containsExactly("PRODIS_REFERENCE_NOT_VERIFIED", "PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat((List<String>) JsonPath.read(body, "$.warnings[*].field"))
                .containsExactly("supplierFixedValue", "supplierGroupFixedValue");
        Map<String, Object> row = jdbc.queryForMap("select identity_supplier_field, identity_supplier_fixed_value, "
                + "identity_supplier_group_fixed_value, identity_uppercase from import_definition_revision "
                + "where import_definition_id = ?", definitionId);
        assertThat(row.get("identity_supplier_field")).isNull();
        assertThat(row.get("identity_supplier_fixed_value")).isEqualTo(supplier);
        assertThat(row.get("identity_supplier_group_fixed_value")).isEqualTo("GRP-A");
        assertThat(row.get("identity_uppercase")).isEqualTo(true);
    }

    @Test
    void theMigrationWritesTheFixedGroupAndUppercaseWithoutProdis() throws Exception {
        ObjectNode bundle = applicable();
        ObjectNode group = line(bundle, 10);
        group.put("column", 0);
        group.put("value", "GRP-A");
        long id = receive(bundle);

        String body = previewBody(id, "libraryCode", "LIB-S6");
        assertThat(blockingCodes(body)).isEmpty();
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.element == 'PSIMPDEF#10')].code"))
                .containsExactly("IDENTITY_FIXED_VALUE", "PRODIS_REFERENCE_NOT_VERIFIED");

        apply(id, JsonPath.read(body, "$.previewFingerprint"), REASON, "libraryCode", "LIB-S6")
                .andExpect(status().isOk());
        Map<String, Object> revision = revisionRows(definitionId(supplier(bundle), "VROOAM2")).get(0);
        assertThat(revision.get("identity_supplier_group_field")).isNull();
        assertThat(revision.get("identity_supplier_group_fixed_value")).isEqualTo("GRP-A");
        assertThat(revision.get("identity_uppercase")).isEqualTo(true);
        // Dezelfde inhoud nog eens: de geschreven revisie (met de S6-velden) is precies wat gepland was.
        String again = previewBody(id, "libraryCode", "LIB-S6");
        assertThat((List<String>) JsonPath.read(again, "$.report.items[?(@.code == 'DEFINITION_UNCHANGED')].status"))
                .containsExactly("MIGRATED");
    }

    @Test
    void anApplicableBundleWithColumnsAlsoGetsUppercase() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);

        previewAndApply(id, "libraryCode", "LIB-S6C").andExpect(status().isOk());

        Map<String, Object> revision = revisionRows(definitionId(supplier(bundle), "VROOAM2")).get(0);
        assertThat(revision.get("identity_supplier_field")).isEqualTo("3");
        assertThat(revision.get("identity_supplier_group_field")).isEqualTo("4");
        assertThat(revision.get("identity_supplier_fixed_value")).isNull();
        assertThat(revision.get("identity_uppercase")).isEqualTo(true);
    }
}
