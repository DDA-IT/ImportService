package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * PREVIEW van slice S2 ({@code docs/design/legacy-config-bundle-design.md} par. 2 en 5; beslissingslog 2026-10-20
 * "Legacy-config S2"). Over HTTP, met MANAGE.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>PREVIEW schrijft niets en is deterministisch (zelfde fingerprint bij dezelfde toestand en keuzes).</li>
 *   <li>De onaangepaste golden fixture is sinds S6 BLOCKED met precies: sleutelprofiel (afwezige {@code ArtImp_Key_*} =
 *       N), prefix op 11, en geen bibliotheekcode; de vaste waarde op 10 wordt een vaste groep (S6).</li>
 *   <li>Een afgeleide bundel is APPLICABLE en plant organisatie, definitie, revisie, koppeling en taak.</li>
 *   <li>Ontbrekende definitie, charset, separator, wisselkoers en libraryCode volgen de vertaalregels.</li>
 *   <li>403 zonder MANAGE vóór 404.</li>
 *   <li>S5, zonder Prodis-configuratie (functie uit): de context start, PREVIEW blijft S2-gedrag
 *       ({@code prodisReference = NOT_CONFIGURED}) en de keuzelijsten geven 409 {@code PRODIS_DB_NOT_CONFIGURED}.</li>
 * </ul>
 */
class LegacyConfigPreviewTest extends LegacyConfigS2TestBase {

    @Test
    void theUnchangedGoldenFixtureIsBlockedWithExactlyTheS6Codes() throws Exception {
        ObjectNode golden = golden();
        long id = receive(golden);

        String body = previewBody(id);

        // S6: de vaste waarde GRP-A op lijn 10 blokkeert niet meer; enkel nog sleutelprofiel, prefix op 11 en bibliotheek.
        assertThat((String) JsonPath.read(body, "$.report.outcome")).isEqualTo("BLOCKED");
        assertThat(blockingCodes(body)).containsExactly("IDENTITY_PROFILE_UNSUPPORTED", "IDENTITY_PROFILE_UNSUPPORTED",
                "IDENTITY_PROFILE_UNSUPPORTED", "IDENTITY_TRANSFORM_UNSUPPORTED", "LIBRARY_CODE_NOT_RESOLVED");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.status == 'BLOCKING')].element"))
                .containsExactly("INDET:ArtImp_Key_Lev", "INDET:ArtImp_Key_RefLev", "INDET:ArtImp_Key_LevGrp",
                        "PSIMPDEF#11", "INDET:ArtImp_BibNr");
        // Lijn 10: vaste groep (zonder Prodis-bron niet geverifieerd), en altijd hoofdletters.
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.element == 'PSIMPDEF#10')].code"))
                .containsExactly("IDENTITY_FIXED_VALUE", "PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'IDENTITY_FIXED_VALUE')].target"))
                .containsExactly("revision.identitySupplierGroupFixedValue=GRP-A");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'IDENTITY_UPPERCASE')].target"))
                .containsExactly("revision.identityUppercase=true");
        // Wat wel vertaald wordt, staat in het rapport; nooit het secret.
        assertThat(codes(body, "MIGRATED")).contains("DELIMITER", "CHARSET", "HEADER", "BASE_PRICE", "DESCRIPTION");
        assertThat(codes(body, "WARNING")).contains("PRODIS_REFERENCE_NOT_VERIFIED", "MAP_MISS_NO_FALLBACK",
                "DECIMAL_COMMA_BASE_PRICE");
        assertThat(codes(body, "NOT_MIGRATED")).contains("LEGACY_EXCLUDES_NOT_MIGRATED", "SCHEDULE_NOT_MIGRATED",
                "CONNECTION_NOT_MIGRATED", "SECRET_LABEL_OMITTED", "DISCOUNT_CODE_NOT_IN_KEY");
        assertThat(body).doesNotContain("canary-golden-not-a-secret");

        // Met een bibliotheekcode valt enkel LIBRARY_CODE_NOT_RESOLVED weg.
        String withLibrary = previewBody(id, "libraryCode", "LIB-GOLDEN");
        assertThat(blockingCodes(withLibrary)).doesNotContain("LIBRARY_CODE_NOT_RESOLVED")
                .contains("IDENTITY_TRANSFORM_UNSUPPORTED");
    }

    @Test
    void anApplicableBundlePlansTheChainAndThePreviewWritesNothing() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);
        List<Long> before = footprint(bundle);

        String body = previewBody(id, "libraryCode", "LIB-S2");
        String again = previewBody(id, "libraryCode", "LIB-S2");

        assertThat(footprint(bundle)).isEqualTo(before);
        assertThat(receiptRow(id).get("status")).isEqualTo("RECEIVED");
        assertThat((String) JsonPath.read(body, "$.report.outcome")).isEqualTo("APPLICABLE");
        assertThat(blockingCodes(body)).isEmpty();
        assertThat((String) JsonPath.read(body, "$.previewFingerprint")).matches("[0-9a-f]{64}")
                .isEqualTo(JsonPath.read(again, "$.previewFingerprint"));
        assertThat((String) JsonPath.read(body, "$.report.planned.sourceOrganisation.action")).isEqualTo("CREATE");
        assertThat((String) JsonPath.read(body, "$.report.planned.sourceOrganisation.code"))
                .isEqualTo(supplier(bundle));
        assertThat((String) JsonPath.read(body, "$.report.planned.definition.action")).isEqualTo("CREATE");
        assertThat((String) JsonPath.read(body, "$.report.planned.definition.code")).isEqualTo("VROOAM2");
        assertThat((String) JsonPath.read(body, "$.report.planned.definition.revisionAction")).isEqualTo("CREATE");
        assertThat((String) JsonPath.read(body, "$.report.planned.link.code"))
                .isEqualTo("LEGACY-" + interfaceCode(bundle));
        assertThat((String) JsonPath.read(body, "$.report.planned.link.libraryCode")).isEqualTo("LIB-S2");
        assertThat((String) JsonPath.read(body, "$.report.planned.task.name"))
                .isEqualTo("Legacy " + interfaceCode(bundle));
        assertThat((List<String>) JsonPath.read(body, "$.report.sharedWith")).isEmpty();
        // S7 (A2): de preview plant ook het sjabloon van de gedeelde definitie.
        assertThat((String) JsonPath.read(body, "$.report.planned.template.action")).isEqualTo("CREATE");
        assertThat((String) JsonPath.read(body, "$.report.planned.template.code")).isEqualTo("VROOAM2-SJ");
        assertThat((Object) JsonPath.read(body, "$.report.planned.template.id")).isNull();
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'IDENTITY_FIELD')].target"))
                .containsExactly("revision.identitySupplierField=3", "revision.identitySupplierGroupField=4",
                        "revision.identitySupplierReferenceField=1", "revision.identityDiscountCodeField=5");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'IDENTITY_PROFILE')].target"))
                .containsExactly("revision.identityProfileKind=FOUR_PART_WITH_DISCOUNT_CODE");
        assertThat(codes(body, "WARNING")).contains("PRODIS_REFERENCE_NOT_VERIFIED", "MAP_MISS_NO_FALLBACK");
        // S8a: CALC 7 gaat naar de maptabel; de grens van transform_config bestaat voor maps niet meer.
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'MAP_TABLE')].target"))
                .containsExactly("import_field_value_map");
        assertThat(body).doesNotContain("MAP_TOO_LARGE").doesNotContain("TRANSFORM_VALUE_NOT_EXPRESSIBLE");

        // Een andere keuze geeft een ander rapport en dus een andere fingerprint.
        assertThat(fingerprint(id, "libraryCode", "LIB-OTHER"))
                .isNotEqualTo(JsonPath.read(body, "$.previewFingerprint"));
    }

    @Test
    void withoutLibraryCodeThePreviewIsBlockedAndTheRawLibraryNumberIsNeverUsed() throws Exception {
        ObjectNode bundle = applicable();
        long id = receive(bundle);

        String body = previewBody(id);

        assertThat(blockingCodes(body)).containsExactly("LIBRARY_CODE_NOT_RESOLVED");
        assertThat((Object) JsonPath.read(body, "$.report.planned.link.libraryCode")).isNull();

        String tooLong = previewBody(id, "libraryCode", "X".repeat(21));
        assertThat(blockingCodes(tooLong)).containsExactly("LIBRARY_CODE_INVALID");
    }

    @Test
    void theMissingDefinitionFixtureIsBlocked() throws Exception {
        ObjectNode missing = (ObjectNode) fixture("golden-bundle-v1-missing-definition.json");
        ((ObjectNode) missing.get("origin")).put("dossier", dossier(golden()));
        long id = receive(missing);

        String body = previewBody(id, "libraryCode", "LIB-MISS");

        assertThat((String) JsonPath.read(body, "$.report.outcome")).isEqualTo("BLOCKED");
        assertThat(blockingCodes(body)).contains("DEFINITION_NOT_PRESENT");
        assertThat((String) JsonPath.read(body, "$.report.planned.definition.revisionAction")).isEqualTo("NONE");
    }

    @Test
    void anUndeclaredCharsetBlocksUnlessThePersonChoosesOne() throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "FILE_ENCODING", "");
        long id = receive(bundle);

        assertThat(blockingCodes(previewBody(id, "libraryCode", "LIB"))).containsExactly("CHARSET_NOT_DECLARED");

        String chosen = previewBody(id, "libraryCode", "LIB", "charsetWhenUndeclared", "ISO-8859-1");
        assertThat(blockingCodes(chosen)).isEmpty();
        assertThat((List<String>) JsonPath.read(chosen, "$.report.items[?(@.code == 'CHARSET_NOT_DECLARED')].status"))
                .containsExactly("WARNING");
        assertThat((List<String>) JsonPath.read(chosen, "$.report.items[?(@.code == 'CHARSET_NOT_DECLARED')].target"))
                .containsExactly("revision.structureCharset=ISO-8859-1");

        assertThat(blockingCodes(previewBody(id, "libraryCode", "LIB", "charsetWhenUndeclared", "NOT-A-CHARSET")))
                .containsExactly("CHARSET_UNSUPPORTED");
    }

    @Test
    void anUnknownDeclaredCharsetIsNeverGuessed() throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "FILE_ENCODING", "ANSI-LEGACY");
        long id = receive(bundle);

        assertThat(blockingCodes(previewBody(id, "libraryCode", "LIB", "charsetWhenUndeclared", "UTF-8")))
                .containsExactly("CHARSET_UNSUPPORTED");
    }

    @Test
    void aMissingSeparatorDefaultsToSemicolonWithAWarning() throws Exception {
        ObjectNode bundle = applicable();
        removeDetail(bundle, "Separator");
        long id = receive(bundle);

        String body = previewBody(id, "libraryCode", "LIB");

        assertThat(blockingCodes(body)).isEmpty();
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'SEPARATOR_NOT_DECLARED')].status"))
                .containsExactly("WARNING");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'SEPARATOR_NOT_DECLARED')].target"))
                .containsExactly("revision.structureDelimiter=;");
    }

    @Test
    void anUnsupportedSeparatorBlocksAndTabIsMigrated() throws Exception {
        ObjectNode xml = applicable();
        detail(xml, "Separator", "XML");
        assertThat(blockingCodes(previewBody(receive(xml), "libraryCode", "LIB")))
                .containsExactly("STRUCTURE_FORMAT_UNSUPPORTED");

        ObjectNode tab = applicable();
        detail(tab, "Separator", "Tab");
        String body = previewBody(receive(tab), "libraryCode", "LIB");
        assertThat(blockingCodes(body)).isEmpty();
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'DELIMITER')].target"))
                .containsExactly("revision.structureDelimiter=TAB");
    }

    @Test
    void anExchangeRateOtherThanOneBlocks() throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "ArtImp_Para_Koers", "1.0825");
        assertThat(blockingCodes(previewBody(receive(bundle), "libraryCode", "LIB")))
                .containsExactly("CURRENCY_RATE_NOT_SUPPORTED");

        ObjectNode one = applicable();
        detail(one, "ArtImp_Para_Koers", "1");
        assertThat(blockingCodes(previewBody(receive(one), "libraryCode", "LIB"))).isEmpty();
    }

    @Test
    void transformationsFollowTheTranslationRules() throws Exception {
        ObjectNode bundle = applicable();
        line(bundle, 26).put("prefix", "EUR");                 // basisprijs: blokkeert
        line(bundle, 37).put("suffix", "%").put("calculation", "3").put("calculationValue", "2"); // keten
        line(bundle, 4).put("calculation", "15");              // omschrijving: vervalt
        long id = receive(bundle);

        String body = previewBody(id, "libraryCode", "LIB");

        assertThat(blockingCodes(body)).containsExactly("BASE_PRICE_TRANSFORM_UNSUPPORTED");
        assertThat(codes(body, "WARNING")).contains("CALC_ROUNDING_MAY_DIFFER", "DESCRIPTION_TRANSFORM_NOT_MIGRATED")
                .doesNotContain("TRANSFORM_CHAIN_UNSUPPORTED");
        // S8c: de keten in legacy-volgorde (suffix vóór de CALC).
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'MAPPING')].target"))
                .contains("VKP5_PCT <- column 9 CHAIN AFFIX > MULTIPLY");
    }

    @Test
    void withoutProdisConfigurationThePreviewStaysS2AndTheReferenceListsAre409() throws Exception {
        long id = receive(applicable());
        String body = previewBody(id, "libraryCode", "LIB-OFF");
        assertThat((String) JsonPath.read(body, "$.report.prodisReference")).isEqualTo("NOT_CONFIGURED");
        assertThat((String) JsonPath.read(body, "$.previewFingerprint")).matches("[0-9a-f]{64}");
        assertThat((List<String>) JsonPath.read(body,
                "$.report.items[?(@.code == 'PRODIS_REFERENCE_NOT_VERIFIED')].element"))
                .containsExactly("INDET:Supplier", "INDET:ArtImp_BibNr");
        assertThat((List<?>) JsonPath.read(body, "$.report.planned.link.libraryCandidates")).isEmpty();

        for (String path : List.of("/suppliers?q=x", "/suppliers/1/groups", "/suppliers/1/catalogs")) {
            mockMvc.perform(get("/api/catalog-import/prodis-reference" + path).with(as(USER, Permission.READ)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/catalog-import/prodis-reference" + path).with(as(USER, Permission.MANAGE)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRODIS_DB_NOT_CONFIGURED"));
        }
    }

    @Test
    void readOnlyUsersGet403BeforeAnythingAndUnknownReceiptsAre404() throws Exception {
        mockMvc.perform(get(RECEIPTS + "/{id}/preview", Long.MAX_VALUE).with(as(USER, Permission.READ)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(post(RECEIPTS + "/{id}/apply", Long.MAX_VALUE).with(as(USER, Permission.READ))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        mockMvc.perform(post(RECEIPTS + "/{id}/apply", Long.MAX_VALUE).with(as(USER, Permission.READ))
                        .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsBytes(
                                Map.of("reason", "", "previewFingerprint", "x"))))
                .andExpect(status().isForbidden());

        preview(Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_FOUND"));
        apply(Long.MAX_VALUE, "x", REASON).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEGACY_RECEIPT_NOT_FOUND"));
    }
}
