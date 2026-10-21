package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.service.SetupService;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PREVIEW en APPLY met de alleen-lezen Prodis-database (legacy-config S5; {@code docs/design/legacy-config-bundle-design.md}
 * par. 5 rijen {@code Supplier}/{@code ArtImp_BibNr}, A3, par. 6; beslissingslog 2026-10-20 "Legacy-config S2", V1 = c).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Leverancier moet in Prodis bestaan (precies één keer); een nieuwe bronorganisatie krijgt de Prodis-naam, een
 *       bestaande wordt nooit hernoemd.</li>
 *   <li>Bibliotheek automatisch bij precies één {@code scope_key} met {@code legacy_number = ArtImp_BibNr}; 0 of meer →
 *       blokkerend met de kandidaten; een gegeven {@code libraryCode} moet een bibliotheek van die leverancier zijn.</li>
 *   <li>De gebruikte Prodis-gegevens zitten in de fingerprint: wijzigen ze, dan is de preview verouderd (409).</li>
 *   <li>Prodis onbruikbaar: preview "unverified", APPLY met {@code libraryCode} uit de body toegestaan zolang Prodis weg
 *       blijft (design par. 6); is Prodis terug, dan is die preview verouderd - en omgekeerd.</li>
 * </ul>
 */
class LegacyConfigProdisReferenceTest extends LegacyConfigProdisTestBase {

    private static final String LIBRARY = "INDET:ArtImp_BibNr";

    @Test
    void oneMatchingLibraryIsResolvedAutomaticallyAndApplyUsesItAndTheProdisName() throws Exception {
        Fixture fixture = withProdisSupplier("Prodis Leverancier NV");
        catalog(fixture, "S5A", LEGACY_LIBRARY, "CAT A OUD", "SUPERSEDED");
        catalog(fixture, "S5A", LEGACY_LIBRARY, "CAT A", "ACTIVE");
        catalog(fixture, "S5B", "99", "CAT B", "ACTIVE");
        long id = receive(fixture.bundle());

        String body = previewBody(id);

        assertThat((String) JsonPath.read(body, "$.report.outcome")).isEqualTo("APPLICABLE");
        assertThat((String) JsonPath.read(body, "$.report.prodisReference")).isEqualTo("VERIFIED");
        assertThat((String) JsonPath.read(body, "$.previewFingerprint")).matches("[0-9a-f]{64}");
        assertThat((String) JsonPath.read(body, "$.report.planned.link.libraryCode")).isEqualTo("S5A");
        assertThat((List<?>) JsonPath.read(body, "$.report.planned.link.libraryCandidates")).isEmpty();
        assertThat((String) JsonPath.read(body, "$.report.planned.sourceOrganisation.name"))
                .isEqualTo("Prodis Leverancier NV");
        // Het S2-item wordt op zijn plaats vervangen; nergens nog "niet geverifieerd".
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.element == 'INDET:Supplier')].code"))
                .containsExactly("SOURCE_ORGANISATION", "PRODIS_SUPPLIER");
        assertThat(codes(body, "WARNING")).doesNotContain("PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'LIBRARY_CODE')].target"))
                .containsExactly("import_link.libraryCode=S5A");

        apply(id, JsonPath.read(body, "$.previewFingerprint"), REASON).andExpect(status().isOk())
                .andExpect(jsonPath("$.report.planned.link.libraryCode").value("S5A"));
        assertThat(jdbc.queryForObject("select library_code from import_link where code = ?", String.class,
                "LEGACY-" + interfaceCode(fixture.bundle()))).isEqualTo("S5A");
        assertThat(jdbc.queryForObject("select name from source_organisation where code = ?", String.class,
                fixture.supplier())).isEqualTo("Prodis Leverancier NV");
    }

    @Test
    void noneOrSeveralMatchingLibrariesBlockWithTheCandidates() throws Exception {
        Fixture none = withProdisSupplier("Geen match");
        catalog(none, "X1", "99", "CAT X1", "ACTIVE");
        catalog(none, "X2", "98", "CAT X2", "IMPORTED");
        String noneBody = previewBody(receive(none.bundle()));
        assertThat(blockingCodes(noneBody)).containsExactly("LIBRARY_CODE_NOT_RESOLVED");
        assertThat((List<String>) JsonPath.read(noneBody, "$.report.planned.link.libraryCandidates[*].scopeKey"))
                .containsExactly("X1", "X2");
        assertThat((List<String>) JsonPath.read(noneBody,
                "$.report.planned.link.libraryCandidates[*].catalogName")).containsExactly("CAT X1", "CAT X2");
        assertThat((Object) JsonPath.read(noneBody, "$.report.planned.link.libraryCode")).isNull();

        Fixture several = withProdisSupplier("Twee matches");
        catalog(several, "M1", LEGACY_LIBRARY, "CAT M1", "ACTIVE");
        catalog(several, "M2", LEGACY_LIBRARY, "CAT M2", "ACTIVE");
        catalog(several, "M3", "7", "CAT M3", "ACTIVE");
        long severalId = receive(several.bundle());
        String severalBody = previewBody(severalId);
        assertThat(blockingCodes(severalBody)).containsExactly("LIBRARY_CODE_NOT_RESOLVED");
        assertThat((List<String>) JsonPath.read(severalBody,
                "$.report.planned.link.libraryCandidates[*].scopeKey")).containsExactly("M1", "M2");
        assertThat((List<String>) JsonPath.read(severalBody,
                "$.report.items[?(@.code == 'LIBRARY_CODE_NOT_RESOLVED')].message")).singleElement().asString()
                .contains("2 Prodis libraries", "M1 (CAT M1)", "M2 (CAT M2)").doesNotContain("M3");
        // De mens kiest: een van de kandidaten is dan gewoon toepasbaar.
        String chosen = previewBody(severalId, "libraryCode", "M2");
        assertThat(blockingCodes(chosen)).isEmpty();
        assertThat(codes(chosen, "WARNING")).doesNotContain("LIBRARY_CODE_NOT_LEGACY_MATCH",
                "PRODIS_REFERENCE_NOT_VERIFIED");

        Fixture empty = withProdisSupplier("Geen bibliotheken");
        String emptyBody = previewBody(receive(empty.bundle()));
        assertThat(blockingCodes(emptyBody)).containsExactly("LIBRARY_CODE_NOT_RESOLVED");
        assertThat((List<?>) JsonPath.read(emptyBody, "$.report.planned.link.libraryCandidates")).isEmpty();
        assertThat((List<String>) JsonPath.read(emptyBody,
                "$.report.items[?(@.code == 'LIBRARY_CODE_NOT_RESOLVED')].message")).singleElement().asString()
                .contains("no library in Prodis");
    }

    @Test
    void aGivenLibraryCodeMustBeALibraryOfThatSupplier() throws Exception {
        Fixture fixture = withProdisSupplier("Gegeven code");
        catalog(fixture, "G1", LEGACY_LIBRARY, "CAT G1", "ACTIVE");
        catalog(fixture, "G2", "50", "CAT G2", "ACTIVE");
        Fixture other = withProdisSupplier("Andere leverancier");
        catalog(other, "Z9", LEGACY_LIBRARY, "CAT Z9", "ACTIVE");
        long id = receive(fixture.bundle());

        for (String foreign : List.of("Z9", "NOPE")) {
            String body = previewBody(id, "libraryCode", foreign);
            assertThat(blockingCodes(body)).containsExactly("LIBRARY_CODE_NOT_IN_PRODIS");
            assertThat((List<String>) JsonPath.read(body, "$.report.planned.link.libraryCandidates[*].scopeKey"))
                    .containsExactly("G1");
        }

        String otherLibrary = previewBody(id, "libraryCode", "G2");
        assertThat(blockingCodes(otherLibrary)).isEmpty();
        assertThat(codes(otherLibrary, "WARNING")).contains("LIBRARY_CODE_NOT_LEGACY_MATCH");
        assertThat((String) JsonPath.read(otherLibrary, "$.report.planned.link.libraryCode")).isEqualTo("G2");

        String sameAsAutomatic = previewBody(id, "libraryCode", "G1");
        assertThat(blockingCodes(sameAsAutomatic)).isEmpty();
        assertThat(codes(sameAsAutomatic, "WARNING")).doesNotContain("LIBRARY_CODE_NOT_LEGACY_MATCH");
        // Een gegeven code is een andere keuze dan de automatische: ander rapport, andere fingerprint.
        assertThat(JsonPath.<String>read(sameAsAutomatic, "$.previewFingerprint"))
                .isNotEqualTo(fingerprint(id));

        assertThat(blockingCodes(previewBody(id, "libraryCode", "X".repeat(21))))
                .containsExactly("LIBRARY_CODE_INVALID");
    }

    @Test
    void anUnknownOrAmbiguousSupplierBlocks() throws Exception {
        var unknown = applicable();
        long unknownId = receive(unknown);
        List<Long> before = footprint(unknown);
        String body = previewBody(unknownId);
        assertThat(blockingCodes(body)).containsExactly("PRODIS_SUPPLIER_NOT_FOUND", "LIBRARY_CODE_NOT_RESOLVED");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'PRODIS_SUPPLIER_NOT_FOUND')]"
                + ".element")).containsExactly("INDET:Supplier");
        assertThat(codes(body, "WARNING")).doesNotContain("PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat(blockingCodes(previewBody(unknownId, "libraryCode", "LIB")))
                .containsExactly("PRODIS_SUPPLIER_NOT_FOUND", "LIBRARY_CODE_NOT_IN_PRODIS");
        apply(unknownId, fingerprint(unknownId), REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_APPLY_BLOCKED"));
        assertThat(footprint(unknown)).isEqualTo(before);

        var ambiguous = applicable();
        PRODIS.insertSupplier(supplier(ambiguous), "Eerste");
        PRODIS.insertSupplier(supplier(ambiguous), "Tweede");
        assertThat(blockingCodes(previewBody(receive(ambiguous))))
                .containsExactly("PRODIS_SUPPLIER_AMBIGUOUS", "LIBRARY_CODE_NOT_RESOLVED");
    }

    @Test
    void anExistingOrganisationIsNeverRenamed() throws Exception {
        Fixture fixture = withProdisSupplier("Naam in Prodis");
        catalog(fixture, "E1", LEGACY_LIBRARY, "CAT E1", "ACTIVE");
        setup.createSourceOrganisation(new SetupService.CreateSourceOrganisationCommand(fixture.supplier(),
                "Eigen naam", SourceOrganisationType.SUPPLIER));
        long id = receive(fixture.bundle());

        String body = previewBody(id);
        assertThat(blockingCodes(body)).isEmpty();
        assertThat((String) JsonPath.read(body, "$.report.planned.sourceOrganisation.action")).isEqualTo("REUSE");
        assertThat((String) JsonPath.read(body, "$.report.planned.sourceOrganisation.name")).isEqualTo("Eigen naam");
        assertThat((List<String>) JsonPath.read(body,
                "$.report.items[?(@.code == 'SOURCE_ORGANISATION_NAME_KEPT')].status")).containsExactly("NOT_MIGRATED");

        apply(id, JsonPath.read(body, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select name from source_organisation where code = ?", String.class,
                fixture.supplier())).isEqualTo("Eigen naam");
    }

    @Test
    void theFingerprintFollowsTheProdisData() throws Exception {
        Fixture fixture = withProdisSupplier("Fingerprint");
        catalog(fixture, "F1", LEGACY_LIBRARY, "CAT F1", "ACTIVE");
        long id = receive(fixture.bundle());
        List<Long> before = footprint(fixture.bundle());

        String first = fingerprint(id);
        assertThat(fingerprint(id)).isEqualTo(first);

        // Een bibliotheek voor een ander legacy-nummer verandert het rapport niet, maar wel de gelezen Prodis-gegevens.
        catalog(fixture, "F2", "77", "CAT F2", "ACTIVE");
        String second = fingerprint(id);
        assertThat(second).isNotEqualTo(first);
        apply(id, first, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));
        assertThat(footprint(fixture.bundle())).isEqualTo(before);

        PRODIS.execute("update supplier set name = ? where id = ?", "Fingerprint hernoemd",
                fixture.prodisSupplierId());
        String third = fingerprint(id);
        assertThat(third).isNotEqualTo(second);
        apply(id, second, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));

        apply(id, third, REASON).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select name from source_organisation where code = ?", String.class,
                fixture.supplier())).isEqualTo("Fingerprint hernoemd");
    }

    @Test
    void whileProdisIsUnusableThePreviewIsUnverifiedAndApplyFollowsTheDesign() throws Exception {
        Fixture first = withProdisSupplier("Storing een");
        catalog(first, "U1", LEGACY_LIBRARY, "CAT U1", "ACTIVE");
        Fixture second = withProdisSupplier("Storing twee");
        catalog(second, "U2", LEGACY_LIBRARY, "CAT U2", "ACTIVE");
        long firstId = receive(first.bundle());
        long secondId = receive(second.bundle());
        String verified = fingerprint(firstId);

        boolean broken = true;
        PRODIS.breakSource();
        try {
            // Een geverifieerde preview is niet meer geldig zolang Prodis weg is.
            apply(firstId, verified, REASON).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));

            String withoutCode = previewBody(firstId);
            assertThat((String) JsonPath.read(withoutCode, "$.report.prodisReference")).isEqualTo("UNAVAILABLE");
            assertThat(blockingCodes(withoutCode)).containsExactly("LIBRARY_CODE_NOT_RESOLVED");

            String unverified = previewBody(firstId, "libraryCode", "U1");
            assertThat(blockingCodes(unverified)).isEmpty();
            assertThat((String) JsonPath.read(unverified, "$.previewFingerprint")).startsWith("unverified:")
                    .hasSize("unverified:".length() + 64);
            assertThat((List<String>) JsonPath.read(unverified,
                    "$.report.items[?(@.code == 'PRODIS_REFERENCE_NOT_VERIFIED')].element"))
                    .containsExactly("INDET:Supplier", LIBRARY);
            String secondUnverified = fingerprint(secondId, "libraryCode", "U2");

            // Design par. 6: APPLY mag met de libraryCode uit de body (geen vaste waarden in S5).
            apply(firstId, JsonPath.read(unverified, "$.previewFingerprint"), REASON, "libraryCode", "U1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.report.prodisReference").value("UNAVAILABLE"));
            assertThat(jdbc.queryForObject("select name from source_organisation where code = ?", String.class,
                    first.supplier())).isEqualTo("Leverancier " + first.supplier());

            PRODIS.restore();
            broken = false;
            // Prodis is terug: een niet-geverifieerde preview is verouderd.
            apply(secondId, secondUnverified, REASON, "libraryCode", "U2").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));
            Map<String, Object> receipt = receiptRow(secondId);
            assertThat(receipt.get("status")).isEqualTo("RECEIVED");
        } finally {
            if (broken) {
                PRODIS.restore();
            }
        }
    }
}
