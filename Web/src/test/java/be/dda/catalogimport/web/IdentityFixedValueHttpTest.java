package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Legacy-config S6 over HTTP, met een geconfigureerde Prodis-leesbron (H2, {@link LegacyConfigProdisTestBase}):
 * vaste leverancier/groep en hoofdletters in de setup-API, de screening, de opvolgrevisie en de migratie
 * ({@code docs/design/legacy-config-bundle-design.md} par. 4, 5, 8; beslissingslog 2026-10-20 M3 en optie iii).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Setup: een vaste waarde moet in Prodis bestaan (leverancier precies één keer, groep bij de leverancier van de
 *       identiteit), gecontroleerd zoals de screening ze gebruikt (in hoofdletters als {@code identityUppercase});
 *       onbekend → 400, onbereikbaar → 503, en er wordt dan niets bewaard. Precies één van kolom of vaste waarde.</li>
 *   <li>Screening: de vaste leverancier/groep geldt voor elke regel; met hoofdletters zijn {@code abc} en {@code ABC}
 *       één identiteit (dubbel in dezelfde levering → blokkade, zoals elke dubbele identiteit).</li>
 *   <li>De opvolgrevisie neemt vaste waarden en hoofdletters over; overstappen naar een kolom is een
 *       identiteitswijziging (R-REV-X3).</li>
 *   <li>Migratie: PSIMPDEF 10 met een vaste waarde wordt een vaste groep (gecontroleerd in Prodis), elke gemigreerde
 *       revisie krijgt {@code identity_uppercase = true}; de gelezen groepen zitten in de fingerprint.</li>
 * </ul>
 */
class IdentityFixedValueHttpTest extends LegacyConfigProdisTestBase {

    private static final String SETUP = "/api/catalog-import/setup";

    // --- Setup-API -------------------------------------------------------------------------------------------------

    @Test
    void fixedValuesThatExistInProdisAreAcceptedAndEveryRowGetsThemInUpperCase() throws Exception {
        Chain chain = chainWithFixedValues(true);

        assertThat(chain.revisionBody()).isNotNull();
        assertThat((String) JsonPath.read(chain.revisionBody(), "$.supplierFixedValue"))
                .isEqualTo(chain.supplier().toLowerCase());
        assertThat((String) JsonPath.read(chain.revisionBody(), "$.supplierGroupFixedValue")).isEqualTo("grp-a");
        assertThat((Object) JsonPath.read(chain.revisionBody(), "$.supplierField")).isNull();
        assertThat((Boolean) JsonPath.read(chain.revisionBody(), "$.identityUppercase")).isTrue();
        assertThat((List<?>) JsonPath.read(chain.revisionBody(), "$.warnings")).isEmpty();

        // Een bestand zonder leveranciers- en groepskolom; referenties in kleine letters.
        long batchId = batchId(upload(chain.taskId(), "REF-S6-1", "referentie;omschrijving;prijs\n"
                + "abc-1;Boormachine;1,50\nxyz-2;Zaag;2,00\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCREENED"))
                .andExpect(jsonPath("$.validRecordCount").value(2))
                .andReturn().getResponse().getContentAsString());

        List<Map<String, Object>> staged = jdbc.queryForList("select identity_supplier, identity_supplier_group, "
                + "identity_supplier_reference from import_candidate_stage where batch_id = ? "
                + "order by identity_supplier_reference", batchId);
        assertThat(staged).extracting(row -> row.get("identity_supplier")).containsOnly(chain.supplier());
        assertThat(staged).extracting(row -> row.get("identity_supplier_group")).containsOnly("GRP-A");
        assertThat(staged).extracting(row -> row.get("identity_supplier_reference")).containsExactly("ABC-1", "XYZ-2");
    }

    @Test
    void withUppercaseLowerAndUpperCaseAreOneIdentityLikeInLegacy() throws Exception {
        Chain chain = chainWithFixedValues(true);

        upload(chain.taskId(), "REF-S6-DUP", "referentie;omschrijving;prijs\nabc;Boor;1,50\nABC;Boor;1,50\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.blockedCode").value("DUPLICATE_IDENTITY_IN_DELIVERY"));
    }

    @Test
    void fixedValuesThatProdisDoesNotKnowAreRefusedAndNothingIsSaved() throws Exception {
        String supplier = prodisSupplierWithGroup("GRP-A");
        long definitionId = definition(supplier);
        String other = prodisSupplierWithGroup("GRP-B");

        createRevision(definitionId, fixed("NOPE-" + supplier, null, false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_FIXED_VALUE_NOT_IN_PRODIS"));
        // De groep van een andere leverancier telt niet; zonder vaste leverancier is de bronorganisatie de leverancier.
        createRevision(definitionId, fixed(null, "GRP-B", false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_GROUP_FIXED_VALUE_NOT_IN_PRODIS"));
        createRevision(definitionId, fixed(supplier, "GRP-B", false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_GROUP_FIXED_VALUE_NOT_IN_PRODIS"));
        // Zonder hoofdletters wordt "grp-a" letterlijk gezocht en dus niet gevonden; met hoofdletters wel (zie hierboven).
        createRevision(definitionId, fixed(supplier, "grp-a", false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_GROUP_FIXED_VALUE_NOT_IN_PRODIS"));
        PRODIS.insertSupplier(other, "Dubbel");
        createRevision(definitionId, fixed(other, null, false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_FIXED_VALUE_AMBIGUOUS_IN_PRODIS"));

        assertThat(revisionRows(definitionId)).isEmpty();
    }

    @Test
    void exactlyOneOfColumnOrFixedValueIsRequired() throws Exception {
        String supplier = prodisSupplierWithGroup("GRP-A");
        long definitionId = definition(supplier);

        createRevision(definitionId, fixed(supplier, null, false).replace("\"supplierField\":null",
                "\"supplierField\":\"leverancier\"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_FIXED_VALUE_INVALID"));
        createRevision(definitionId, fixed(null, null, false).replace("\"supplierGroupField\":\"groep\"",
                "\"supplierGroupField\":null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_GROUP_FIELD_REQUIRED"));
        createRevision(definitionId, fixed("X".repeat(51), null, false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_FIXED_VALUE_TOO_LONG"));

        assertThat(revisionRows(definitionId)).isEmpty();
    }

    @Test
    void whileProdisIsUnreachableTheSetupAnswers503AndSavesNothing() throws Exception {
        String supplier = prodisSupplierWithGroup("GRP-A");
        long definitionId = definition(supplier);

        boolean broken = true;
        PRODIS.breakSource();
        try {
            createRevision(definitionId, fixed(supplier, "GRP-A", false))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PRODIS_DB_UNAVAILABLE"));
            assertThat(revisionRows(definitionId)).isEmpty();
            // Zonder vaste waarde is Prodis niet nodig: de setup werkt gewoon.
            createRevision(definitionId, fixed(null, null, false)).andExpect(status().isCreated());
            PRODIS.restore();
            broken = false;
        } finally {
            if (broken) {
                PRODIS.restore();
            }
        }
    }

    @Test
    void theSuccessorKeepsTheFixedValuesAndSwitchingToAColumnIsAnIdentityChange() throws Exception {
        Chain chain = chainWithFixedValues(true);

        long successor = id(mockMvc.perform(post(SETUP + "/revisions/{id}/successor", chain.revisionId())
                        .with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changeReason\":\"S6 opvolger\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplierFixedValue").value(chain.supplier().toLowerCase()))
                .andExpect(jsonPath("$.supplierGroupFixedValue").value("grp-a"))
                .andExpect(jsonPath("$.identityUppercase").value(true))
                .andReturn().getResponse().getContentAsString());
        Map<String, Object> copy = jdbc.queryForMap("select * from import_definition_revision where id = ?",
                successor);
        Map<String, Object> source = jdbc.queryForMap("select * from import_definition_revision where id = ?",
                chain.revisionId());
        for (String column : List.of("identity_supplier_field", "identity_supplier_fixed_value",
                "identity_supplier_group_field", "identity_supplier_group_fixed_value", "identity_uppercase",
                "record_rules_config_hash", "composite_config_hash")) {
            assertThat(copy.get(column)).as(column).isEqualTo(source.get(column));
        }

        // Overstappen naar een kolom: de vaste waarde uitdrukkelijk wissen, en het is een identiteitswijziging.
        String toColumn = "{\"supplierField\":\"leverancier\",\"supplierFixedValue\":\"\"";
        patchRevision(successor, toColumn + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDENTITY_CHANGE_NOT_ACKNOWLEDGED"));
        patchRevision(successor, "{\"supplierField\":\"leverancier\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SUPPLIER_FIXED_VALUE_INVALID"));
        patchRevision(successor, toColumn + ",\"acknowledgeIdentityChange\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierField").value("leverancier"))
                .andExpect(jsonPath("$.supplierFixedValue").doesNotExist());
        assertThat(jdbc.queryForObject("select record_rules_config_hash from import_definition_revision where id = ?",
                String.class, successor)).isNotEqualTo(source.get("record_rules_config_hash"));
    }

    // --- Migratie --------------------------------------------------------------------------------------------------

    @Test
    void aFixedGroupInProdisIsMigratedAsFixedValueWithUppercase() throws Exception {
        Fixture fixture = fixedGroupFixture("grp-a");
        PRODIS.insertGroup(fixture.prodisSupplierId(), fixture.supplier() + "|GRP-A|", "GRP-A", "");
        catalog(fixture, "S6A", LEGACY_LIBRARY, "CAT S6A", "ACTIVE");
        long id = receive(fixture.bundle());

        String body = previewBody(id);

        assertThat(blockingCodes(body)).isEmpty();
        assertThat((String) JsonPath.read(body, "$.report.prodisReference")).isEqualTo("VERIFIED");
        assertThat((List<String>) JsonPath.read(body, "$.report.items[?(@.element == 'PSIMPDEF#10')].code"))
                .containsExactly("IDENTITY_FIXED_VALUE", "PRODIS_SUPPLIER_GROUP");
        assertThat(codes(body, "WARNING")).doesNotContain("PRODIS_REFERENCE_NOT_VERIFIED", "DISCOUNT_CODE_UPPERCASED");

        apply(id, JsonPath.read(body, "$.previewFingerprint"), REASON).andExpect(status().isOk());
        Map<String, Object> revision = revisionRows(definitionId(fixture.supplier(), "VROOAM2")).get(0);
        assertThat(revision.get("identity_supplier_group_field")).isNull();
        assertThat(revision.get("identity_supplier_group_fixed_value")).isEqualTo("grp-a");
        assertThat(revision.get("identity_supplier_field")).isEqualTo("3");
        assertThat(revision.get("identity_uppercase")).isEqualTo(true);
    }

    @Test
    void anUnknownFixedGroupOrSupplierBlocksTheMigration() throws Exception {
        Fixture unknownGroup = fixedGroupFixture("GRP-X");
        PRODIS.insertGroup(unknownGroup.prodisSupplierId(), unknownGroup.supplier() + "|GRP-A|", "GRP-A", "");
        catalog(unknownGroup, "S6X", LEGACY_LIBRARY, "CAT S6X", "ACTIVE");
        String body = previewBody(receive(unknownGroup.bundle()));
        assertThat(blockingCodes(body)).containsExactly("PRODIS_SUPPLIER_GROUP_NOT_FOUND");
        assertThat((List<String>) JsonPath.read(body,
                "$.report.items[?(@.code == 'PRODIS_SUPPLIER_GROUP_NOT_FOUND')].element")).containsExactly("PSIMPDEF#10");

        Fixture unknownSupplier = withProdisSupplier("Vaste leverancier onbekend");
        catalog(unknownSupplier, "S6Y", LEGACY_LIBRARY, "CAT S6Y", "ACTIVE");
        ObjectNode line = line(unknownSupplier.bundle(), 9);
        line.put("column", 0);
        line.put("value", "ONBEKEND-" + unknownSupplier.supplier());
        String supplierBody = previewBody(receive(unknownSupplier.bundle()));
        assertThat(blockingCodes(supplierBody)).containsExactly("PRODIS_SUPPLIER_NOT_FOUND");
        assertThat((List<String>) JsonPath.read(supplierBody,
                "$.report.items[?(@.code == 'PRODIS_SUPPLIER_NOT_FOUND')].element")).containsExactly("PSIMPDEF#9");
    }

    @Test
    void theFingerprintFollowsTheProdisGroupsAndAnUnavailableProdisLeavesTheValueUnverified() throws Exception {
        Fixture fixture = fixedGroupFixture("GRP-A");
        PRODIS.insertGroup(fixture.prodisSupplierId(), fixture.supplier() + "|GRP-A|", "GRP-A", "");
        catalog(fixture, "S6F", LEGACY_LIBRARY, "CAT S6F", "ACTIVE");
        long id = receive(fixture.bundle());
        String first = fingerprint(id);

        PRODIS.insertGroup(fixture.prodisSupplierId(), fixture.supplier() + "|GRP-B|", "GRP-B", "K1");
        String second = fingerprint(id);
        assertThat(second).isNotEqualTo(first);
        apply(id, first, REASON).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEGACY_PREVIEW_STALE"));

        boolean broken = true;
        PRODIS.breakSource();
        try {
            String unverified = previewBody(id, "libraryCode", "S6F");
            assertThat(blockingCodes(unverified)).isEmpty();
            assertThat((String) JsonPath.read(unverified, "$.previewFingerprint")).startsWith("unverified:");
            assertThat((List<String>) JsonPath.read(unverified,
                    "$.report.items[?(@.code == 'PRODIS_REFERENCE_NOT_VERIFIED')].element"))
                    .contains("PSIMPDEF#10");
            PRODIS.restore();
            broken = false;
        } finally {
            if (broken) {
                PRODIS.restore();
            }
        }
        apply(id, second, REASON).andExpect(status().isOk());
    }

    // --- Hulp ------------------------------------------------------------------------------------------------------

    private record Chain(String supplier, long definitionId, long revisionId, String revisionBody, long taskId) {
    }

    /** Een volledige keten met vaste leverancier (kleine letters) en vaste groep "grp-a", met of zonder hoofdletters. */
    private Chain chainWithFixedValues(boolean uppercase) throws Exception {
        String supplier = prodisSupplierWithGroup("GRP-A");
        long definitionId = definition(supplier);
        String revisionBody = createRevision(definitionId, fixed(supplier.toLowerCase(), "grp-a", uppercase))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long revisionId = id(revisionBody);
        mockMvc.perform(post(SETUP + "/revisions/{id}/activate", revisionId).with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approvedBy\":\"" + USER + "\"}"))
                .andExpect(status().isOk());
        long linkId = id(mockMvc.perform(post(SETUP + "/links").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definitionId\":" + definitionId + ",\"code\":\"" + supplier + "-L\",\"name\":\""
                                + supplier + " koppeling\",\"supplierCode\":\"" + supplier + "\",\"libraryCode\":\""
                                + supplier + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long taskId = id(mockMvc.perform(post(SETUP + "/tasks").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"linkId\":" + linkId + ",\"name\":\"" + supplier + " levering\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new Chain(supplier, definitionId, revisionId, revisionBody, taskId);
    }

    /** Een Prodis-leverancier met één groep; het nummer is ook de code van de bronorganisatie. */
    private static String prodisSupplierWithGroup(String group) {
        String supplier = "S6" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
        long id = PRODIS.insertSupplier(supplier, "Leverancier " + supplier);
        PRODIS.insertGroup(id, supplier + "|" + group + "|", group, "");
        return supplier;
    }

    /** Een toepasbare bundel waarvan lijn 10 de vaste waarde {@code value} heeft in plaats van kolom 4. */
    private static Fixture fixedGroupFixture(String value) throws Exception {
        Fixture fixture = withProdisSupplier("Vaste groep " + value);
        ObjectNode group = line(fixture.bundle(), 10);
        group.put("column", 0);
        group.put("value", value);
        return fixture;
    }

    private long definition(String supplier) throws Exception {
        mockMvc.perform(post(SETUP + "/source-organisations").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + supplier + "\",\"name\":\"" + supplier + " BV\",\"type\":\"SUPPLIER\"}"))
                .andExpect(status().isCreated());
        return id(mockMvc.perform(post(SETUP + "/definitions").with(as(USER)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceOrganisationCode\":\"" + supplier + "\",\"code\":\"" + supplier
                                + "-DEF\",\"name\":\"" + supplier + " catalogus\",\"usageType\":\"OWN_DEFINITION\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** Een revisie: vaste waarde of (bij {@code null}) de kolom {@code leverancier}/{@code groep}. */
    static String fixed(String supplierFixed, String groupFixed, boolean uppercase) {
        return "{\"delimiter\":\";\",\"charset\":\"UTF-8\",\"hasHeader\":true,\"headerLineNumber\":1,"
                + "\"fieldReferenceKind\":\"HEADER_NAME\",\"identityProfileKind\":\"THREE_PART\","
                + (supplierFixed == null ? "\"supplierField\":\"leverancier\","
                : "\"supplierField\":null,\"supplierFixedValue\":\"" + supplierFixed + "\",")
                + (groupFixed == null ? "\"supplierGroupField\":\"groep\","
                : "\"supplierGroupField\":null,\"supplierGroupFixedValue\":\"" + groupFixed + "\",")
                + "\"supplierReferenceField\":\"referentie\",\"basePriceField\":\"prijs\","
                + "\"descriptionField\":\"omschrijving\",\"canonicalisationVersion\":2,"
                + "\"identityUppercase\":" + uppercase + "}";
    }

    private ResultActions createRevision(long definitionId, String json) throws Exception {
        return mockMvc.perform(post(SETUP + "/definitions/{id}/revisions", definitionId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions patchRevision(long revisionId, String json) throws Exception {
        return mockMvc.perform(patch(SETUP + "/revisions/{id}", revisionId).with(as(USER))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions upload(long taskId, String reference, String csv) throws Exception {
        return mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", taskId)
                .file(new MockMultipartFile("file", reference + ".csv", "text/csv",
                        csv.getBytes(StandardCharsets.UTF_8)))
                .param("deliveryReference", reference)
                .param("uploadedBy", USER)
                .with(as(USER)));
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static long batchId(String body) {
        return ((Number) JsonPath.read(body, "$.batchId")).longValue();
    }
}
