package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ProdisReferenceJdbc;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Een geconfigureerde maar onbereikbare Prodis-database (legacy-config S5; {@code docs/design/legacy-config-bundle-design.md}
 * par. 6): de URL wijst naar een gesloten poort. Eigen context.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>De applicatie start toch (de pool verbindt pas bij de eerste query) en blijft ready.</li>
 *   <li>Keuzelijsten: 503 {@code PRODIS_DB_UNAVAILABLE} (na 403 voor wie geen MANAGE heeft).</li>
 *   <li>PREVIEW: {@code PRODIS_REFERENCE_NOT_VERIFIED}, fingerprint {@code unverified:...}, libraryCode verplicht; APPLY
 *       met de libraryCode uit de body maakt de DRAFT (design par. 6), met de plaatshoudernaam.</li>
 * </ul>
 */
@TestPropertySource(properties = {
        "catalogimport.prodis-db.url=jdbc:postgresql://127.0.0.1:1/Prodis",
        "catalogimport.prodis-db.username=catalogimport_ro",
        "catalogimport.prodis-db.password=not-a-real-password",
        "catalogimport.prodis-db.connect-timeout=PT1S",
        "catalogimport.prodis-db.query-timeout=PT2S"})
class ProdisReferenceUnavailableTest extends LegacyConfigS2TestBase {

    private static final String PRODIS_REFERENCE = "/api/catalog-import/prodis-reference";

    @Autowired
    ProdisReferenceJdbc prodisReferenceJdbc;

    @Test
    void theApplicationStartsAndStaysReadyAndTheListsAre503() throws Exception {
        assertThat(prodisReferenceJdbc.isConfigured()).isTrue();
        for (String path : List.of("/suppliers?q=02", "/suppliers/02079/groups", "/suppliers/02079/catalogs",
                "/vat-codes", "/units-of-measure")) {
            mockMvc.perform(get(PRODIS_REFERENCE + path).with(as(USER, Permission.READ)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get(PRODIS_REFERENCE + path).with(as(USER, Permission.MANAGE)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PRODIS_DB_UNAVAILABLE"));
        }
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void thePreviewIsUnverifiedAndApplyUsesTheGivenLibraryCode() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);

        String withoutCode = previewBody(id);
        assertThat((String) JsonPath.read(withoutCode, "$.report.prodisReference")).isEqualTo("UNAVAILABLE");
        assertThat(blockingCodes(withoutCode)).containsExactly("LIBRARY_CODE_NOT_RESOLVED");

        String body = previewBody(id, "libraryCode", "LIB-U");
        assertThat(blockingCodes(body)).isEmpty();
        String fingerprint = JsonPath.read(body, "$.previewFingerprint");
        assertThat(fingerprint).startsWith("unverified:");
        assertThat((List<String>) JsonPath.read(body,
                "$.report.items[?(@.code == 'PRODIS_REFERENCE_NOT_VERIFIED')].message"))
                .hasSize(2).allSatisfy(message -> assertThat(message).contains("not available"));
        // Andere keuze: ander (niet-geverifieerd) rapport, andere fingerprint.
        assertThat(fingerprint(id, "libraryCode", "LIB-V")).startsWith("unverified:").isNotEqualTo(fingerprint);

        apply(id, fingerprint, REASON, "libraryCode", "LIB-U").andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptStatus").value("APPLIED"));
        assertThat(jdbc.queryForObject("select library_code from import_link where code = ?", String.class,
                "LEGACY-" + interfaceCode(bundle))).isEqualTo("LIB-U");
        assertThat(jdbc.queryForObject("select name from source_organisation where code = ?", String.class,
                supplier(bundle))).isEqualTo("Leverancier " + supplier(bundle));
    }
}
