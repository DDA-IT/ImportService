package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Slice CD-B2 zonder Prodis-leesbron (functie uit; zelfde context als {@link LegacyConfigS2TestBase}): de
 * aanmaakstandaard wordt dan aanvaard met een waarschuwing {@code PRODIS_REFERENCE_NOT_VERIFIED} per gevraagde code,
 * nooit stil, zoals een vaste leverancier/groep in S6 ({@link IdentityFixedValueNotConfiguredHttpTest}).
 */
class LinkCreationDefaultsNotConfiguredHttpTest extends LegacyConfigS2TestBase {

    private static final String SETUP = "/api/catalog-import/setup";

    @Test
    void withoutProdisTheCodesAreStoredWithAWarningPerRequestedCode() throws Exception {
        long linkId = link();

        String body = mockMvc.perform(put(SETUP + "/links/{id}/creation-defaults", linkId).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"vatCode\":\"3\",\"unitCode\":\"ST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultVatCode").value("3"))
                .andExpect(jsonPath("$.defaultUnitCode").value("ST"))
                .andExpect(jsonPath("$.creationDefaultsOrigin").value("MANUAL"))
                .andReturn().getResponse().getContentAsString();

        assertThat((List<String>) JsonPath.read(body, "$.warnings[*].code"))
                .containsExactly("PRODIS_REFERENCE_NOT_VERIFIED", "PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat((List<String>) JsonPath.read(body, "$.warnings[*].field")).containsExactly("vatCode", "unitCode");
        Map<String, Object> row = jdbc.queryForMap("select default_vat_code, default_unit_code, creation_defaults_origin, "
                + "creation_defaults_updated_by from import_link where id = ?", linkId);
        assertThat(row.get("default_vat_code")).isEqualTo("3");
        assertThat(row.get("default_unit_code")).isEqualTo("ST");
        assertThat(row.get("creation_defaults_origin")).isEqualTo("MANUAL");
        assertThat(row.get("creation_defaults_updated_by")).isEqualTo(USER);

        // Enkel de eenheid: één waarschuwing; wissen: geen.
        String unitOnly = mockMvc.perform(put(SETUP + "/links/{id}/creation-defaults", linkId).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unitCode\":\"DS\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(unitOnly, "$.warnings[*].field")).containsExactly("unitCode");
        String cleared = mockMvc.perform(put(SETUP + "/links/{id}/creation-defaults", linkId).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(cleared, "$.warnings")).isEmpty();
    }

    private long link() throws Exception {
        String supplier = "CDN" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        mockMvc.perform(post(SETUP + "/source-organisations").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + supplier + "\",\"name\":\"" + supplier + " BV\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isCreated());
        long definitionId = ((Number) JsonPath.read(mockMvc.perform(post(SETUP + "/definitions").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + supplier + "\",\"code\":\"" + supplier
                                + "-DEF\",\"name\":\"" + supplier + "\",\"usageType\":\"OWN_DEFINITION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        return ((Number) JsonPath.read(mockMvc.perform(post(SETUP + "/links").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":" + definitionId + ",\"code\":\"" + supplier + "-LINK\",\"name\":\""
                                + supplier + "\",\"supplierCode\":\"" + supplier + "\",\"libraryCode\":\"PSARF030\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
