package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static be.dda.catalogimport.testsupport.TestActors.withoutPermissions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Slice CD-B2: {@code PUT /setup/links/{id}/creation-defaults} met een geconfigureerde Prodis-leesbron (H2,
 * {@link LegacyConfigProdisTestBase}); beslissingslog 2026-10-23 "Btw-code en eenheid: aanmaakstandaard per koppeling"
 * en "Aanmaakstandaard per koppeling: uitwerking (CD)". Zonder Prodis-bron: {@link LinkCreationDefaultsNotConfiguredHttpTest}.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>De aanmaakstandaard is een btw-code en een eenheid uit Prodis (enkel actieve rijen); het verzoek vervangt beide,
 *       blanco wist (ook beide). Waarden worden getrimd en exact vergeleken.</li>
 *   <li>S6-patroon: onbekend → 400, onbereikbaar → 503, in beide gevallen niets bewaard; dubbele code → bewaard met
 *       waarschuwing {@code PRODIS_REFERENCE_AMBIGUOUS}.</li>
 *   <li>Elke geslaagde wijziging: herkomst MANUAL, naam en subject van de aangemelde gebruiker, tijdstip; de koppeling
 *       schuift {@code updated_at} op. Herhalen geeft geen dubbele rij en dezelfde waarden.</li>
 *   <li>MANAGE vereist; {@code system} mag niet tekenen; onbekende koppeling → 404. {@code GET /import-links} toont de
 *       standaard en de audit.</li>
 * </ul>
 */
class LinkCreationDefaultsHttpTest extends LegacyConfigProdisTestBase {

    private static final String SETUP = "/api/catalog-import/setup";
    private static final String PATH = SETUP + "/links/{id}/creation-defaults";

    // --- Zetten en wissen ------------------------------------------------------------------------------------------

    @Test
    void knownCodesAreStoredTrimmedWithOriginManualAndTheSignerAndTheLinkMovesItsUpdatedAt() throws Exception {
        Codes codes = codes();
        Link link = link();
        Instant before = updatedAt(link.id());

        String body = putDefaults(link.id(), "{\"vatCode\":\"  " + codes.vat() + " \",\"unitCode\":\"" + codes.unit()
                + "\"}", as(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value(link.id()))
                .andExpect(jsonPath("$.defaultVatCode").value(codes.vat()))
                .andExpect(jsonPath("$.defaultUnitCode").value(codes.unit()))
                .andExpect(jsonPath("$.creationDefaultsOrigin").value("MANUAL"))
                .andExpect(jsonPath("$.creationDefaultsUpdatedBy").value(USER))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(body, "$.warnings")).isEmpty();
        assertThat((String) JsonPath.read(body, "$.creationDefaultsUpdatedAt")).isNotBlank();

        Map<String, Object> row = row(link.id());
        assertThat(row.get("default_vat_code")).isEqualTo(codes.vat());
        assertThat(row.get("default_unit_code")).isEqualTo(codes.unit());
        assertThat(row.get("creation_defaults_origin")).isEqualTo("MANUAL");
        assertThat(row.get("creation_defaults_updated_by")).isEqualTo(USER);
        assertThat(row.get("creation_defaults_updated_by_subject")).isEqualTo("test-sub-" + USER);
        assertThat(row.get("creation_defaults_updated_at")).isNotNull();
        assertThat(updatedAt(link.id())).isAfter(before);
    }

    @Test
    void theRequestReplacesBothCodesAndBlankOrMissingClearsThem() throws Exception {
        Codes codes = codes();
        Link link = link();
        putDefaults(link.id(), "{\"vatCode\":\"" + codes.vat() + "\",\"unitCode\":\"" + codes.unit() + "\"}", as(USER))
                .andExpect(status().isOk());

        // Enkel de btw-code meegeven: de eenheid wordt gewist (vervangen, geen PATCH).
        putDefaults(link.id(), "{\"vatCode\":\"" + codes.vat() + "\"}", as(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultVatCode").value(codes.vat()))
                .andExpect(jsonPath("$.defaultUnitCode").doesNotExist());
        assertThat(row(link.id()).get("default_unit_code")).isNull();

        // Blanco en een lege body wissen alles; herkomst en audit blijven als verklaring van de wissing.
        putDefaults(link.id(), "{\"vatCode\":\"  \",\"unitCode\":\"\"}", as(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultVatCode").doesNotExist())
                .andExpect(jsonPath("$.creationDefaultsOrigin").value("MANUAL"));
        putDefaults(link.id(), "{}", as(USER)).andExpect(status().isOk());
        Map<String, Object> row = row(link.id());
        assertThat(row.get("default_vat_code")).isNull();
        assertThat(row.get("default_unit_code")).isNull();
        assertThat(row.get("creation_defaults_origin")).isEqualTo("MANUAL");
        assertThat(row.get("creation_defaults_updated_by")).isEqualTo(USER);
    }

    @Test
    void repeatingTheSameRequestGivesTheSameValuesAndNoExtraRows() throws Exception {
        Codes codes = codes();
        Link link = link();
        String request = "{\"vatCode\":\"" + codes.vat() + "\",\"unitCode\":\"" + codes.unit() + "\"}";
        long links = count("select count(*) from import_link");

        putDefaults(link.id(), request, as(USER)).andExpect(status().isOk());
        Map<String, Object> first = row(link.id());
        putDefaults(link.id(), request, as(USER)).andExpect(status().isOk());
        Map<String, Object> second = row(link.id());

        assertThat(count("select count(*) from import_link")).isEqualTo(links);
        assertThat(second.get("default_vat_code")).isEqualTo(first.get("default_vat_code"));
        assertThat(second.get("default_unit_code")).isEqualTo(first.get("default_unit_code"));
        assertThat(second.get("creation_defaults_origin")).isEqualTo("MANUAL");
    }

    // --- S6-patroon -----------------------------------------------------------------------------------------------

    @Test
    void aCodeThatIsNotAnActiveProdisRowIsRefusedAndNothingIsSaved() throws Exception {
        Codes codes = codes();
        Link link = link();
        String inactiveVat = "V" + random(6);
        String deletedUnit = "U" + random(6);
        PRODIS.insertVatCode(inactiveVat, "Inactief", 6.0, false, false);
        PRODIS.insertUnitOfMeasure(deletedUnit, "Verwijderd", "vw", true, true);
        Map<String, Object> before = row(link.id());

        putDefaults(link.id(), "{\"vatCode\":\"NOPE-" + codes.vat() + "\",\"unitCode\":\"" + codes.unit() + "\"}",
                as(USER)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODIS_VAT_CODE_NOT_FOUND"));
        putDefaults(link.id(), "{\"vatCode\":\"" + codes.vat() + "\",\"unitCode\":\"NOPE\"}", as(USER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODIS_UNIT_NOT_FOUND"));
        putDefaults(link.id(), "{\"vatCode\":\"" + inactiveVat + "\"}", as(USER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODIS_VAT_CODE_NOT_FOUND"));
        putDefaults(link.id(), "{\"unitCode\":\"" + deletedUnit + "\"}", as(USER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODIS_UNIT_NOT_FOUND"));
        // Exacte vergelijking: andere hoofdletters zijn een andere code.
        putDefaults(link.id(), "{\"unitCode\":\"" + codes.unit().toLowerCase() + "\"}", as(USER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODIS_UNIT_NOT_FOUND"));

        assertThat(row(link.id())).isEqualTo(before);
    }

    @Test
    void tooLongCodesAreRefusedWithAFieldCode() throws Exception {
        Link link = link();
        Map<String, Object> before = row(link.id());

        putDefaults(link.id(), "{\"vatCode\":\"" + "V".repeat(51) + "\"}", as(USER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VAT_CODE_TOO_LONG"));
        putDefaults(link.id(), "{\"unitCode\":\"" + "U".repeat(51) + "\"}", as(USER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNIT_CODE_TOO_LONG"));
        putDefaults(link.id(), "{\"vatCode\":3", as(USER)).andExpect(status().isBadRequest());

        assertThat(row(link.id())).isEqualTo(before);
    }

    @Test
    void aCodeThatOccursTwiceInProdisIsStoredWithAnAmbiguityWarning() throws Exception {
        Codes codes = codes();
        Link link = link();
        PRODIS.insertVatCode(codes.vat(), "Dubbel", 21.0, true, false);

        String body = putDefaults(link.id(), "{\"vatCode\":\"" + codes.vat() + "\",\"unitCode\":\"" + codes.unit()
                + "\"}", as(USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultVatCode").value(codes.vat()))
                .andReturn().getResponse().getContentAsString();

        assertThat((List<String>) JsonPath.read(body, "$.warnings[*].code"))
                .containsExactly("PRODIS_REFERENCE_AMBIGUOUS");
        assertThat((List<String>) JsonPath.read(body, "$.warnings[*].field")).containsExactly("vatCode");
        assertThat(row(link.id()).get("default_vat_code")).isEqualTo(codes.vat());
    }

    @Test
    void whileProdisIsUnreachableTheAnswerIs503AndNothingIsSaved() throws Exception {
        Codes codes = codes();
        Link link = link();
        Map<String, Object> before = row(link.id());

        PRODIS.execute("alter table vat_code rename to vat_code_unavailable");
        PRODIS.execute("alter table unit_of_measure rename to unit_of_measure_unavailable");
        try {
            putDefaults(link.id(), "{\"vatCode\":\"" + codes.vat() + "\",\"unitCode\":\"" + codes.unit() + "\"}",
                    as(USER)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PRODIS_DB_UNAVAILABLE"));
            putDefaults(link.id(), "{\"unitCode\":\"" + codes.unit() + "\"}", as(USER))
                    .andExpect(status().isServiceUnavailable());
            assertThat(row(link.id())).isEqualTo(before);
            // Wissen vraagt geen enkele code en dus geen Prodis.
            putDefaults(link.id(), "{}", as(USER)).andExpect(status().isOk());
        } finally {
            PRODIS.execute("alter table vat_code_unavailable rename to vat_code");
            PRODIS.execute("alter table unit_of_measure_unavailable rename to unit_of_measure");
        }
    }

    // --- Rechten en 404 -------------------------------------------------------------------------------------------

    @Test
    void anUnknownLinkIs404() throws Exception {
        Codes codes = codes();
        putDefaults(999_999_999L, "{\"vatCode\":\"" + codes.vat() + "\"}", as(USER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    @Test
    void withoutManageOrAsSystemNothingIsReadOrWritten() throws Exception {
        Codes codes = codes();
        Link link = link();
        Map<String, Object> before = row(link.id());
        String request = "{\"vatCode\":\"" + codes.vat() + "\",\"unitCode\":\"" + codes.unit() + "\"}";

        putDefaults(link.id(), request, as(USER, Permission.READ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        putDefaults(link.id(), request, withoutPermissions(USER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        putDefaults(link.id(), request, withoutPermissions("system"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(row(link.id())).isEqualTo(before);
    }

    // --- GET /import-links ----------------------------------------------------------------------------------------

    @Test
    void theLinkListShowsTheCreationDefaultsAndTheirAudit() throws Exception {
        Codes codes = codes();
        Link link = link();
        String empty = linkRow(link.definitionId());
        assertThat((Object) JsonPath.read(empty, "$.content[0].defaultVatCode")).isNull();
        assertThat((Object) JsonPath.read(empty, "$.content[0].creationDefaultsOrigin")).isNull();
        assertThat((Object) JsonPath.read(empty, "$.content[0].creationDefaultsUpdatedAt")).isNull();

        putDefaults(link.id(), "{\"vatCode\":\"" + codes.vat() + "\",\"unitCode\":\"" + codes.unit() + "\"}", as(USER))
                .andExpect(status().isOk());

        String body = linkRow(link.definitionId());
        assertThat((String) JsonPath.read(body, "$.content[0].defaultVatCode")).isEqualTo(codes.vat());
        assertThat((String) JsonPath.read(body, "$.content[0].defaultUnitCode")).isEqualTo(codes.unit());
        assertThat((String) JsonPath.read(body, "$.content[0].creationDefaultsOrigin")).isEqualTo("MANUAL");
        assertThat((String) JsonPath.read(body, "$.content[0].creationDefaultsUpdatedBy")).isEqualTo(USER);
        assertThat((String) JsonPath.read(body, "$.content[0].creationDefaultsUpdatedAt")).isNotBlank();
        // Het subject blijft intern.
        assertThat(body).doesNotContain("test-sub-" + USER);
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private record Codes(String vat, String unit) {
    }

    private record Link(long id, long definitionId) {
    }

    /** Een actieve btw-code en eenheid met een eigen code (de H2-bron is gedeeld). */
    private static Codes codes() {
        Codes codes = new Codes("V" + random(6), "U" + random(6));
        PRODIS.insertVatCode(codes.vat(), "Btw " + codes.vat(), 21.0, true, false);
        PRODIS.insertUnitOfMeasure(codes.unit(), "Eenheid " + codes.unit(), "e", true, false);
        return codes;
    }

    private Link link() throws Exception {
        String supplier = "CD" + random(10);
        mockMvc.perform(post(SETUP + "/source-organisations").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + supplier + "\",\"name\":\"" + supplier + " BV\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isCreated());
        long definitionId = id(mockMvc.perform(post(SETUP + "/definitions").with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + supplier + "\",\"code\":\"" + supplier
                                + "-DEF\",\"name\":\"" + supplier + "\",\"usageType\":\"OWN_DEFINITION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long linkId = id(mockMvc.perform(post(SETUP + "/links").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":" + definitionId + ",\"code\":\"" + supplier + "-LINK\",\"name\":\""
                                + supplier + "\",\"supplierCode\":\"" + supplier + "\",\"libraryCode\":\"PSARF030\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Link(linkId, definitionId);
    }

    private ResultActions putDefaults(long linkId, String body, OidcLoginRequestPostProcessor actor) throws Exception {
        return mockMvc.perform(put(PATH, linkId).with(actor).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String linkRow(long definitionId) throws Exception {
        return mockMvc.perform(get("/api/catalog-import/import-links").param("importDefinitionId",
                        String.valueOf(definitionId)).with(as(USER, Permission.READ)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> row(long linkId) {
        return jdbc.queryForMap("select default_vat_code, default_unit_code, creation_defaults_origin, "
                + "creation_defaults_updated_at, creation_defaults_updated_by, creation_defaults_updated_by_subject, "
                + "updated_at from import_link where id = ?", linkId);
    }

    private Instant updatedAt(long linkId) {
        return jdbc.queryForObject("select updated_at from import_link where id = ?", Timestamp.class, linkId)
                .toInstant();
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static String random(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length).toUpperCase();
    }
}
