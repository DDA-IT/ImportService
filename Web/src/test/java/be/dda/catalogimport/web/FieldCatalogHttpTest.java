package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.domain.FieldOwner;
import be.dda.catalogimport.domain.IdentityClass;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * EB1 (beslissingslog 2026-10-23 "Editor-API-gaten", fase3-rules-design par. 15.1): {@code GET /field-catalog}.
 * <ul>
 *   <li><b>Inhoud:</b> alle catalogusvelden (de 17 seedrijen, plus inactieve met {@code active=false}), oplopend op
 *       {@code sortOrder} dan {@code code}; per veld de standaardkritiek (referentie CRITICAL en niet instelbaar,
 *       prijscomponent CRITICAL en instelbaar, overige NON_CRITICAL en instelbaar).</li>
 *   <li><b>Revisievelden:</b> de zeven sleutels van {@code RevisionCriticalityField} met hun standaard.</li>
 *   <li><b>Rechten:</b> 403 zonder {@code READ}; 200 met enkel {@code READ}, en dit bij uitgeschakelde setup-vlag.</li>
 * </ul>
 * De vlag staat hier uitdrukkelijk uit: het endpoint hoort er niet achter te staan.
 */
@SpringBootTest(properties = "catalogimport.setup-api.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class FieldCatalogHttpTest {

    private static final String PATH = "/api/catalog-import/field-catalog";
    private static final String USER = "tester@example.test";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SEED_CODES = List.of("BASE_PRICE", "AKP_PCT", "VKP1_PCT", "VKP2_PCT",
            "VKP3_PCT", "VKP4_PCT", "VKP5_PCT", "VKP_GROSS_PCT", "EAN", "PIM_ID", "CAB_ID",
            "E_MARK_ARTICLE_REFERENCE", "E_SUPPLIER", "SUPPLIER_BARCODE", "DESCRIPTION", "BRAND", "UNIT");

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ImportFieldCatalogRepository fieldCatalog;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listsEveryCatalogFieldSortedBySortOrderThenCodeWithoutTheSetupFlag() throws Exception {
        JsonNode fields = read().get("fields");

        List<String> codes = new ArrayList<>();
        fields.forEach(field -> codes.add(field.get("code").asText()));
        assertThat(codes).containsAll(SEED_CODES);
        // Alle rijen van de tabel, ook inactieve. De seed telt 17 rijen; een gedeeld schema kan er niet minder hebben.
        assertThat(fields.size()).isEqualTo(jdbc.queryForObject("select count(*) from import_field_catalog",
                Integer.class));
        assertThat(fields.size()).isGreaterThanOrEqualTo(17);
        for (int i = 1; i < fields.size(); i++) {
            int previousOrder = fields.get(i - 1).get("sortOrder").asInt();
            int order = fields.get(i).get("sortOrder").asInt();
            assertThat(previousOrder).isLessThanOrEqualTo(order);
            if (previousOrder == order) {
                assertThat(fields.get(i - 1).get("code").asText()).isLessThan(fields.get(i).get("code").asText());
            }
        }
        // De seedvolgorde zelf (10..170) blijft behouden.
        assertThat(codes.stream().filter(SEED_CODES::contains).toList()).containsExactlyElementsOf(SEED_CODES);
    }

    @Test
    void eachFieldCarriesItsCatalogueColumnsAndTheDefaultCriticalityPerSection15_1() throws Exception {
        JsonNode fields = read().get("fields");
        Map<String, JsonNode> byCode = new HashMap<>();
        fields.forEach(field -> byCode.put(field.get("code").asText(), field));

        // Referentie: altijd CRITICAL, niet instelbaar.
        for (String code : List.of("EAN", "PIM_ID", "CAB_ID", "E_MARK_ARTICLE_REFERENCE")) {
            JsonNode field = byCode.get(code);
            assertThat(field.get("defaultCriticality").asText()).as(code).isEqualTo("CRITICAL");
            assertThat(field.get("criticalityChangeable").asBoolean()).as(code).isFalse();
            assertThat(field.get("referenceType").asText()).as(code).isEqualTo(code);
            assertThat(field.get("defaultOwner").asText()).as(code).isEqualTo("CRITICAL_REFERENCE");
            assertThat(field.get("identityClass").asText()).as(code).isEqualTo("ARTICLE_REFERENCE");
            assertThat(field.get("ownerChangeable").asBoolean()).as(code).isFalse();
        }
        // Prijscomponent: CRITICAL, wel instelbaar.
        for (String code : List.of("BASE_PRICE", "AKP_PCT", "VKP1_PCT", "VKP2_PCT", "VKP3_PCT", "VKP4_PCT",
                "VKP5_PCT", "VKP_GROSS_PCT")) {
            JsonNode field = byCode.get(code);
            assertThat(field.get("defaultCriticality").asText()).as(code).isEqualTo("CRITICAL");
            assertThat(field.get("criticalityChangeable").asBoolean()).as(code).isTrue();
            assertThat(field.get("priceComponentCode").isNull()).as(code).isFalse();
            assertThat(field.get("referenceType").isNull()).as(code).isTrue();
            assertThat(field.get("dataType").asText()).as(code).isEqualTo("DECIMAL");
        }
        // Overige: NON_CRITICAL en instelbaar.
        for (String code : List.of("E_SUPPLIER", "SUPPLIER_BARCODE", "DESCRIPTION", "BRAND", "UNIT")) {
            JsonNode field = byCode.get(code);
            assertThat(field.get("defaultCriticality").asText()).as(code).isEqualTo("NON_CRITICAL");
            assertThat(field.get("criticalityChangeable").asBoolean()).as(code).isTrue();
            assertThat(field.get("priceComponentCode").isNull()).as(code).isTrue();
            assertThat(field.get("referenceType").isNull()).as(code).isTrue();
        }
        JsonNode akp = byCode.get("AKP_PCT");
        assertThat(akp.get("name").asText()).isEqualTo("Aankoopprijs in procent van de basisprijs");
        assertThat(akp.get("sortOrder").asInt()).isEqualTo(20);
        assertThat(akp.get("active").asBoolean()).isTrue();
        assertThat(akp.get("priceComponentCode").asText()).isEqualTo("AKP");
        assertThat(byCode.get("BRAND").get("defaultOwner").asText()).isEqualTo("PRODIS_USER");
        assertThat(byCode.get("BRAND").get("ownerChangeable").asBoolean()).isFalse();
    }

    @Test
    void anInactiveFieldIsStillListedWithActiveFalse() throws Exception {
        String code = "ZZ_EB1_INACTIVE_" + Long.toString(System.nanoTime(), 36).toUpperCase();
        ImportFieldCatalogEntry entry = new ImportFieldCatalogEntry(code, "Inactief testveld", FieldDataType.TEXT,
                FieldOwner.CATALOG_SOURCE, IdentityClass.NONE, 9999);
        entry.setActive(false);
        fieldCatalog.saveAndFlush(entry);
        try {
            JsonNode fields = read().get("fields");

            JsonNode listed = null;
            for (JsonNode field : fields) {
                if (code.equals(field.get("code").asText())) {
                    listed = field;
                }
            }
            assertThat(listed).as("inactive field is listed").isNotNull();
            assertThat(listed.get("active").asBoolean()).isFalse();
            assertThat(listed.get("defaultCriticality").asText()).isEqualTo("NON_CRITICAL");
            assertThat(listed.get("criticalityChangeable").asBoolean()).isTrue();
        } finally {
            fieldCatalog.deleteById(code);
        }
    }

    @Test
    void theSevenRevisionFieldsCarryTheirDefaults() throws Exception {
        mockMvc.perform(get(PATH).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionFields.length()").value(7))
                .andExpect(jsonPath("$.revisionFields[0].fieldKey").value("SUPPLIER"))
                .andExpect(jsonPath("$.revisionFields[0].identity").value(true))
                .andExpect(jsonPath("$.revisionFields[0].defaultCriticality").value("CRITICAL"))
                .andExpect(jsonPath("$.revisionFields[0].criticalityChangeable").value(false))
                .andExpect(jsonPath("$.revisionFields[3].fieldKey").value("DISCOUNT_CODE"))
                .andExpect(jsonPath("$.revisionFields[3].criticalityChangeable").value(false))
                .andExpect(jsonPath("$.revisionFields[4].fieldKey").value("BASE_PRICE"))
                .andExpect(jsonPath("$.revisionFields[4].identity").value(false))
                .andExpect(jsonPath("$.revisionFields[4].defaultCriticality").value("CRITICAL"))
                .andExpect(jsonPath("$.revisionFields[4].criticalityChangeable").value(true))
                .andExpect(jsonPath("$.revisionFields[5].fieldKey").value("CURRENCY"))
                .andExpect(jsonPath("$.revisionFields[5].defaultCriticality").value("CRITICAL"))
                .andExpect(jsonPath("$.revisionFields[5].criticalityChangeable").value(true))
                .andExpect(jsonPath("$.revisionFields[6].fieldKey").value("DESCRIPTION"))
                .andExpect(jsonPath("$.revisionFields[6].defaultCriticality").value("NON_CRITICAL"))
                .andExpect(jsonPath("$.revisionFields[6].criticalityChangeable").value(true));
    }

    @Test
    void withoutReadItIs403AndWithOnlyReadItIs200() throws Exception {
        mockMvc.perform(get(PATH).with(withoutPermissions(USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(get(PATH).with(as(USER, Permission.READ))).andExpect(status().isOk());
    }

    private JsonNode read() throws Exception {
        String body = mockMvc.perform(get(PATH).with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }
}
