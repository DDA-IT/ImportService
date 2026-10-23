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
 *   <li>LF (beslissingslog 2026-10-22): het verbindingsvoorstel noemt FTP (poort 21) en API (443/80) met host, als
 *       enkel bewaring zonder connector; nooit de SFTP-standaardpoort 22. Een lokaal bestand en een onduidelijk protocol
 *       krijgen hun eigen tekst.</li>
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
        // 2026-10-23: de afwezige Key_*-labels blokkeren niet meer (Lev/RefLev: geen effect; LevGrp=N: THREE_PART),
        // maar zonder ArtImp_Art/Bib=B kan LevGrp=N niet gelezen worden (legacy schrijft dan naar ARTICLES).
        assertThat(blockingCodes(body)).containsExactly("ARTICLE_TARGET_UNSUPPORTED",
                "IDENTITY_TRANSFORM_UNSUPPORTED", "LIBRARY_CODE_NOT_RESOLVED");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.status == 'BLOCKING')].element"))
                .containsExactly("INDET:ArtImp_Art/Bib", "PSIMPDEF#11", "INDET:ArtImp_BibNr");
        // Lijn 10: vaste groep (zonder Prodis-bron niet geverifieerd), en altijd hoofdletters.
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.element == 'PSIMPDEF#10')].code"))
                .containsExactly("IDENTITY_FIXED_VALUE", "PRODIS_REFERENCE_NOT_VERIFIED");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'IDENTITY_FIXED_VALUE')].target"))
                .containsExactly("revision.identitySupplierGroupFixedValue=GRP-A");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'IDENTITY_UPPERCASE')].target"))
                .containsExactly("revision.identityUppercase=true");
        // Wat wel vertaald wordt, staat in het rapport; nooit het secret.
        assertThat(codes(body, "MIGRATED")).contains("DELIMITER", "CHARSET", "HEADER", "BASE_PRICE", "DESCRIPTION");
        assertThat(codes(body, "WARNING")).contains("PRODIS_REFERENCE_NOT_VERIFIED", "DECIMAL_COMMA_BASE_PRICE")
                .doesNotContain("MAP_MISS_NO_FALLBACK");
        // S8g (V1 = B): CALC 7 laat een bronwaarde buiten de tabel door; informatief, geen waarschuwing meer.
        assertThat(codes(body, "MIGRATED")).contains("MAP_MISS_KEEPS_SOURCE");
        assertThat(codes(body, "NOT_MIGRATED")).contains("SCHEDULE_NOT_MIGRATED", "CONNECTION_NOT_MIGRATED",
                "SECRET_LABEL_OMITTED", "DISCOUNT_CODE_NOT_IN_KEY");
        // S8f: PSIMPEXCL wordt gemigreerd (LEGACY_EXCLUDES_NOT_MIGRATED verdwijnt); met een geblokkeerde identiteit
        // (prefix op 11) is er nog geen groep te plannen.
        assertThat(body).doesNotContain("LEGACY_EXCLUDES_NOT_MIGRATED").doesNotContain("RECORD_FILTER_GROUP");
        assertThat(body).doesNotContain("canary-golden-not-a-secret");

        // Met een bibliotheekcode valt enkel LIBRARY_CODE_NOT_RESOLVED weg.
        String withLibrary = previewBody(id, "libraryCode", "LIB-GOLDEN");
        assertThat(blockingCodes(withLibrary)).doesNotContain("LIBRARY_CODE_NOT_RESOLVED")
                .contains("IDENTITY_TRANSFORM_UNSUPPORTED");
    }

    // --- Beslissing 2026-10-23: LevGrp=N, dode sleutelvlaggen, CALC 15 op de identiteit ---------------------------

    @Test
    void aGroupLessKeyWithLibraryTargetIsMigratedAsThreePartWithAWarning() throws Exception {
        for (String groupFlag : new String[] {"N", null}) {
            ObjectNode bundle = applicable();
            detail(bundle, "ArtImp_Art/Bib", "B");
            detail(bundle, "ArtImp_Key_Korting", "N");
            if (groupFlag == null) {
                removeDetail(bundle, "ArtImp_Key_LevGrp");
            } else {
                detail(bundle, "ArtImp_Key_LevGrp", groupFlag);
            }

            String body = previewBody(receive(bundle), "libraryCode", "LIB-N");

            assertThat(blockingCodes(body)).isEmpty();
            assertThat((String) JsonPath.read(body, "$.report.outcome")).isEqualTo("APPLICABLE");
            assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'IDENTITY_PROFILE')].target"))
                    .containsExactly("revision.identityProfileKind=THREE_PART");
            assertThat((List<String>) JsonPath.read(body,
                    "$.report.items[?(@.code == 'IDENTITY_GROUP_INHERITANCE_NOT_MIGRATED')].element"))
                    .containsExactly("INDET:ArtImp_Key_LevGrp");
            assertThat(codes(body, "WARNING")).contains("IDENTITY_GROUP_INHERITANCE_NOT_MIGRATED");
        }
        // Met Key_Korting=Y blijft het FOUR_PART.
        ObjectNode withDiscount = applicable();
        detail(withDiscount, "ArtImp_Art/Bib", "B");
        detail(withDiscount, "ArtImp_Key_LevGrp", "N");
        String body = previewBody(receive(withDiscount), "libraryCode", "LIB-N");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'IDENTITY_PROFILE')].target"))
                .containsExactly("revision.identityProfileKind=FOUR_PART_WITH_DISCOUNT_CODE");
    }

    @Test
    void aGroupLessKeyBlocksWithoutLibraryTargetOrWithoutAGroupLine() throws Exception {
        for (String artBib : new String[] {"A", null}) {
            ObjectNode bundle = applicable();
            detail(bundle, "ArtImp_Key_LevGrp", "N");
            if (artBib != null) {
                detail(bundle, "ArtImp_Art/Bib", artBib);
            }
            String body = previewBody(receive(bundle), "libraryCode", "LIB-N");
            assertThat(blockingCodes(body)).containsExactly("ARTICLE_TARGET_UNSUPPORTED");
            assertThat(codes(body, "WARNING")).doesNotContain("IDENTITY_GROUP_INHERITANCE_NOT_MIGRATED");
        }
        // Zonder groeplijn (PSIMPDEF 10): het bestaande IDENTITY_FIELD_MISSING.
        ObjectNode noLine = applicable();
        detail(noLine, "ArtImp_Art/Bib", "B");
        detail(noLine, "ArtImp_Key_LevGrp", "N");
        line(noLine, 10).put("number", 999);
        assertThat(blockingCodes(previewBody(receive(noLine), "libraryCode", "LIB-N")))
                .containsExactly("IDENTITY_FIELD_MISSING");
    }

    @Test
    void deadKeyFlagsAreReportedButNeverBlock() throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "ArtImp_Key_Lev", "N");
        removeDetail(bundle, "ArtImp_Key_RefLev");
        detail(bundle, "ArtImp_Key_Barcode", "Y");

        String body = previewBody(receive(bundle), "libraryCode", "LIB-DEAD");

        assertThat(blockingCodes(body)).isEmpty();
        assertThat((String) JsonPath.read(body, "$.report.outcome")).isEqualTo("APPLICABLE");
        assertThat((List<String>) JsonPath.read(body,
                "$.report.items[?(@.code == 'IDENTITY_FLAG_WITHOUT_EFFECT' && @.status == 'NOT_MIGRATED')].element"))
                .containsExactly("INDET:ArtImp_Key_Lev", "INDET:ArtImp_Key_RefLev", "INDET:ArtImp_Key_Barcode");
        assertThat(body).doesNotContain("IDENTITY_PROFILE_UNSUPPORTED");
    }

    @Test
    void calcFifteenOnAnIdentityLineIsNoTransformationButEverythingElseStillBlocks() throws Exception {
        ObjectNode upper = applicable();
        line(upper, 9).put("calculation", "15");
        line(upper, 10).put("calculation", "15");
        line(upper, 11).put("calculation", "15");
        String body = previewBody(receive(upper), "libraryCode", "LIB-UP");
        assertThat(blockingCodes(body)).isEmpty();
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.code == 'IDENTITY_FIELD')].target"))
                .contains("revision.identitySupplierField=3", "revision.identitySupplierReferenceField=1");

        ObjectNode withPrefix = applicable();
        line(withPrefix, 9).put("calculation", "15").put("prefix", "X");
        assertThat(blockingCodes(previewBody(receive(withPrefix), "libraryCode", "LIB-UP")))
                .containsExactly("IDENTITY_TRANSFORM_UNSUPPORTED");

        ObjectNode withSubstring = applicable();
        line(withSubstring, 11).put("calculation", "15").put("position", 2).put("length", 3);
        assertThat(blockingCodes(previewBody(receive(withSubstring), "libraryCode", "LIB-UP")))
                .containsExactly("IDENTITY_TRANSFORM_UNSUPPORTED");

        // De leverancierskaart (CALC 7) op een gewone regel 9 blokkeert niet meer (beslissing 2026-10-23, SC:
        // LegacyConfigSupplierCardTest); met prefix of op 10/11 blijft ze blokkeren.
        ObjectNode card = applicable();
        line(card, 9).put("calculation", "7");
        assertThat(blockingCodes(previewBody(receive(card), "libraryCode", "LIB-UP"))).isEmpty();
        ObjectNode cardWithPrefix = applicable();
        line(cardWithPrefix, 9).put("calculation", "7").put("prefix", "X");
        assertThat(blockingCodes(previewBody(receive(cardWithPrefix), "libraryCode", "LIB-UP")))
                .containsExactly("IDENTITY_TRANSFORM_UNSUPPORTED");

        ObjectNode other = applicable();
        line(other, 10).put("calculation", "3").put("calculationValue", "2");
        assertThat(blockingCodes(previewBody(receive(other), "libraryCode", "LIB-UP")))
                .containsExactly("IDENTITY_TRANSFORM_UNSUPPORTED");
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
        assertThat(codes(body, "WARNING")).contains("PRODIS_REFERENCE_NOT_VERIFIED")
                .doesNotContain("MAP_MISS_NO_FALLBACK");
        assertThat(codes(body, "MIGRATED")).contains("MAP_MISS_KEEPS_SOURCE");
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
    void anUndeclaredCharsetDefaultsToUtf8WithANoteUnlessThePersonChoosesOne() throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "FILE_ENCODING", "");
        long id = receive(bundle);

        String defaulted = previewBody(id, "libraryCode", "LIB");
        assertThat(blockingCodes(defaulted)).isEmpty();
        assertThat((List<String>) JsonPath.read(defaulted, "$.report.items[?(@.code == 'CHARSET_DEFAULTED_UTF8')].status"))
                .containsExactly("WARNING");
        assertThat((List<String>) JsonPath.read(defaulted, "$.report.items[?(@.code == 'CHARSET_DEFAULTED_UTF8')].target"))
                .containsExactly("revision.structureCharset=UTF-8");

        String chosen = previewBody(id, "libraryCode", "LIB", "charsetWhenUndeclared", "ISO-8859-1");
        assertThat(blockingCodes(chosen)).isEmpty();
        assertThat((List<String>) JsonPath.read(chosen, "$.report.items[?(@.code == 'CHARSET_DEFAULTED_UTF8')]"))
                .isEmpty();
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

        for (String path : List.of("/suppliers?q=x", "/suppliers/1/groups", "/suppliers/1/catalogs",
                "/vat-codes", "/units-of-measure")) {
            mockMvc.perform(get("/api/catalog-import/prodis-reference" + path).with(as(USER, Permission.READ)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/catalog-import/prodis-reference" + path).with(as(USER, Permission.MANAGE)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PRODIS_DB_NOT_CONFIGURED"));
        }
    }

    @Test
    void theConnectionProposalNamesFtpAndApiWithTheirOwnDefaultPortsAndNeverSftpPort22() throws Exception {
        assertThat(connectionProposal("ftp.vrooamgrossier.be", null, "F"))
                .contains("protocol=FTP", "host=ftp.vrooamgrossier.be", "port=21 (default)",
                        "password retained, no FTP connector yet")
                .doesNotContain("port=22");
        assertThat(connectionProposal("84.199.80.12:2121", "/in/", null))
                .contains("protocol=FTP", "host=84.199.80.12", "port=2121", "directory=/in/");
        assertThat(connectionProposal("https://data.webapi.intercars.eu/customer/P00675/ProductInformation/",
                "ProductInformation_[YYYY]-[MM]-[DD].csv.zip", "A"))
                .contains("protocol=API", "host=data.webapi.intercars.eu", "port=443 (default)",
                        "password retained, no API connector yet", "date pattern")
                .doesNotContain("port=22");
        assertThat(connectionProposal("http://api.example.test/v1", null, "A"))
                .contains("protocol=API", "host=api.example.test", "port=80 (default)");
        assertThat(connectionProposal("C:\\import\\kramp", null, "L"))
                .contains("protocol=LOCAL", "no password retained").doesNotContain("host=");
        assertThat(connectionProposal("https://api.example.test/x", null, "F"))
                .contains("protocol unknown", "no password retained").doesNotContain("host=");
        // Regressie: SFTP houdt poort 22.
        assertThat(connectionProposal("sftp://sftp.example.test/exports/", null, null))
                .contains("protocol=SFTP", "host=sftp.example.test", "port=22 (default)");
    }

    /** Het CONNECTION_NOT_MIGRATED-bericht van een PREVIEW met deze bestemming. */
    private String connectionProposal(String ftp, String ftp2, String mode) throws Exception {
        ObjectNode bundle = applicable();
        detail(bundle, "FTP", ftp);
        if (ftp2 != null) {
            detail(bundle, "FTP2", ftp2);
        }
        if (mode != null) {
            detail(bundle, "FTP/LOCAL/API", mode);
        }
        List<String> messages = JsonPath.read(previewBody(receive(bundle), "libraryCode", "LIB"),
                "$.report.items[?(@.code == 'CONNECTION_NOT_MIGRATED')].message");
        assertThat(messages).hasSize(1);
        return messages.get(0);
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
