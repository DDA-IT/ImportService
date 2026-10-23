package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * EB2 (beslissingslog 2026-10-23 "Editor-API-gaten", G5): {@code PUT|DELETE
 * /setup/revisions/{rid}/field-criticality/{fieldKey}}.
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> de kritiek van een revisieveld is op een concept vrij te zetten en terug te zetten naar de
 *       standaard; een identiteitsveld is nooit niet-kritiek. <b>Implementatie:</b> {@code SetupService
 *       .setFieldCriticality/deleteFieldCriticality}; vooraf gecontroleerd (400 {@code CRITICALITY_INVALID}), daarna
 *       {@code validateConfiguration} (rollback bij een {@code CONFIG_*}-fout).</li>
 *   <li><b>Regel:</b> "wie zette deze waarde, wanneer", zonder historiek van vorige waarden. <b>Implementatie:</b>
 *       bij een andere waarde wordt de rij vervangen (nieuwe {@code created_at/by/subject}); dezelfde waarde
 *       opnieuw is een no-op en laat de audit ongemoeid.</li>
 *   <li><b>Regel:</b> gelijktijdig wijzigen van dezelfde rij geeft nooit een 500. <b>Implementatie:</b>
 *       409 {@code REVISION_EDIT_CONFLICT} ("herlaad en probeer opnieuw").</li>
 *   <li><b>Uitzondering:</b> {@code POST} blijft add-only (409 {@code FIELD_CRITICALITY_IN_USE}).</li>
 * </ul>
 * Niet achter de setup-vlag (NT-3), enkel MANAGE; de ondertekenaar komt uit {@code currentActor.signer}.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=6")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupFieldCriticalityEditHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    /** Per-run achtervoegsel: het {@code local}-profiel draait tegen een blijvende PostgreSQL. */
    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000_000L, 36).toUpperCase();
    private static final String SETUP = "/api/catalog-import/setup";
    private static final String PATH = SETUP + "/revisions/{id}/field-criticality/{key}";
    private static final String ADMIN = "an.janssens@example.test";
    private static final String OTHER = "piet.willems@example.test";
    private static final long UNKNOWN = 999_999_999L;

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Aanmaken, wijzigen, no-op -----------------------------------------------------------------------

    @Test
    void putCreatesTheOverrideAndStoresTheSignedInUserAndSubject() throws Exception {
        long revisionId = draftRevision("CRE");

        String body = putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "DESCRIPTION")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(revisionId))
                .andExpect(jsonPath("$.fieldKey").value("DESCRIPTION"))
                .andExpect(jsonPath("$.criticality").value("CRITICAL"))
                .andReturn().getResponse().getContentAsString();

        assertThat(criticalityOf(revisionId, "DESCRIPTION")).isEqualTo("CRITICAL");
        assertThat(signatureOf(revisionId, "DESCRIPTION")).containsExactly(ADMIN, "test-sub-" + ADMIN);
        // Het subject staat in geen enkel antwoord (A6).
        assertThat(body).doesNotContain("test-sub-");
    }

    @Test
    void putWithADifferentValueReplacesTheRowAndTheAuditNamesWhoSetTheNewValue() throws Exception {
        long revisionId = draftRevision("CHG");
        putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "BASE_PRICE").andExpect(status().isOk());
        Timestamp first = createdAtOf(revisionId, "BASE_PRICE");

        putAs(OTHER, "{\"criticality\":\"NON_CRITICAL\"}", revisionId, "BASE_PRICE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criticality").value("NON_CRITICAL"));

        assertThat(rowCount(revisionId, "BASE_PRICE")).isEqualTo(1);
        assertThat(criticalityOf(revisionId, "BASE_PRICE")).isEqualTo("NON_CRITICAL");
        assertThat(signatureOf(revisionId, "BASE_PRICE")).containsExactly(OTHER, "test-sub-" + OTHER);
        assertThat(createdAtOf(revisionId, "BASE_PRICE")).isAfterOrEqualTo(first);
    }

    @Test
    void putWithTheSameValueIsANoOpAndLeavesTheAuditUnchanged() throws Exception {
        long revisionId = draftRevision("SAME");
        putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "DESCRIPTION").andExpect(status().isOk());
        Timestamp first = createdAtOf(revisionId, "DESCRIPTION");

        putAs(OTHER, "{\"criticality\":\"CRITICAL\"}", revisionId, "DESCRIPTION")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criticality").value("CRITICAL"));

        assertThat(rowCount(revisionId, "DESCRIPTION")).isEqualTo(1);
        assertThat(signatureOf(revisionId, "DESCRIPTION")).containsExactly(ADMIN, "test-sub-" + ADMIN);
        assertThat(createdAtOf(revisionId, "DESCRIPTION")).isEqualTo(first);
    }

    @Test
    void anIdentityFieldCanBeSetToCriticalButNeverToNonCritical() throws Exception {
        long revisionId = draftRevision("IDENT");

        putAs(ADMIN, "{\"criticality\":\"NON_CRITICAL\"}", revisionId, "SUPPLIER_REFERENCE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CRITICALITY_INVALID"));
        assertThat(rowCount(revisionId, "SUPPLIER_REFERENCE")).isZero();

        putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "SUPPLIER_REFERENCE")
                .andExpect(status().isOk());
        putAs(ADMIN, "{\"criticality\":\"NON_CRITICAL\"}", revisionId, "SUPPLIER_REFERENCE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CRITICALITY_INVALID"));
        // De bestaande rij is ongemoeid gebleven.
        assertThat(criticalityOf(revisionId, "SUPPLIER_REFERENCE")).isEqualTo("CRITICAL");
    }

    // --- Verwijderen --------------------------------------------------------------------------------------

    @Test
    void deleteRemovesTheOverrideAndASecondDeleteIs404() throws Exception {
        long revisionId = draftRevision("DEL");
        putAs(ADMIN, "{\"criticality\":\"NON_CRITICAL\"}", revisionId, "DESCRIPTION").andExpect(status().isOk());

        deleteAs(ADMIN, revisionId, "DESCRIPTION").andExpect(status().isNoContent());
        assertThat(rowCount(revisionId, "DESCRIPTION")).isZero();

        deleteAs(ADMIN, revisionId, "DESCRIPTION")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FIELD_CRITICALITY_NOT_FOUND"));
    }

    @Test
    void deleteWithoutARowIs404FieldCriticalityNotFound() throws Exception {
        long revisionId = draftRevision("DELNONE");

        deleteAs(ADMIN, revisionId, "CURRENCY")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FIELD_CRITICALITY_NOT_FOUND"));
    }

    // --- Ongeldige invoer ---------------------------------------------------------------------------------

    @Test
    void anUnknownFieldKeyIs400FieldKeyInvalidOnPutAndDelete() throws Exception {
        long revisionId = draftRevision("KEY");

        putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "NOT_A_FIELD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIELD_KEY_INVALID"));
        deleteAs(ADMIN, revisionId, "NOT_A_FIELD")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIELD_KEY_INVALID"));
    }

    @Test
    void aMissingCriticalityIs400CriticalityRequired() throws Exception {
        long revisionId = draftRevision("REQ");

        putAs(ADMIN, "{}", revisionId, "DESCRIPTION")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CRITICALITY_REQUIRED"));
        putAs(ADMIN, "{\"criticality\":null}", revisionId, "DESCRIPTION")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CRITICALITY_REQUIRED"));
        assertThat(rowCount(revisionId, "DESCRIPTION")).isZero();
    }

    @Test
    void anUnknownRevisionIs404RevisionNotFoundOnPutAndDelete() throws Exception {
        putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", UNKNOWN, "DESCRIPTION")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
        deleteAs(ADMIN, UNKNOWN, "DESCRIPTION")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_FOUND"));
    }

    @Test
    void aFrozenRevisionIs409NotEditableOnPutAndDeleteAndKeepsItsRow() throws Exception {
        long revisionId = draftRevision("FROZEN");
        putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "DESCRIPTION").andExpect(status().isOk());
        json(ADMIN, post(SETUP + "/revisions/{id}/mappings", revisionId),
                "{\"targetFieldCode\":\"EAN\",\"sourceReference\":\"ean\",\"sequenceNumber\":1}")
                .andExpect(status().isCreated());
        json(ADMIN, post(SETUP + "/revisions/{id}/activate", revisionId), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        putAs(ADMIN, "{\"criticality\":\"NON_CRITICAL\"}", revisionId, "DESCRIPTION")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));
        deleteAs(ADMIN, revisionId, "DESCRIPTION")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));
        putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "BASE_PRICE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_EDITABLE"));

        assertThat(criticalityOf(revisionId, "DESCRIPTION")).isEqualTo("CRITICAL");
        assertThat(rowCount(revisionId, "BASE_PRICE")).isZero();
    }

    @Test
    void postStaysAddOnlyAndRefusesAFieldThatPutAlreadyConfigured() throws Exception {
        long revisionId = draftRevision("POST");
        putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "DESCRIPTION").andExpect(status().isOk());

        json(ADMIN, post(SETUP + "/revisions/{id}/field-criticality", revisionId),
                "{\"fieldKey\":\"DESCRIPTION\",\"criticality\":\"NON_CRITICAL\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIELD_CRITICALITY_IN_USE"));
        assertThat(criticalityOf(revisionId, "DESCRIPTION")).isEqualTo("CRITICAL");
    }

    // --- Ondertekenaar ------------------------------------------------------------------------------------

    @Test
    void aSignatureUnderAnotherNameIs400AndSystemIs403AndNothingIsWritten() throws Exception {
        long revisionId = draftRevision("ACTOR");

        putAs(ADMIN, "{\"criticality\":\"CRITICAL\",\"createdBy\":\"someone.else@example.test\"}", revisionId,
                "DESCRIPTION")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        putAs("system", "{\"criticality\":\"CRITICAL\"}", revisionId, "DESCRIPTION")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        putAs(ADMIN, "{\"criticality\":\"CRITICAL\",\"createdBy\":\"" + ADMIN.toUpperCase() + "\"}", revisionId,
                "DESCRIPTION")
                .andExpect(status().isOk());

        assertThat(signatureOf(revisionId, "DESCRIPTION")).containsExactly(ADMIN, "test-sub-" + ADMIN);
    }

    // --- Configuratiefout: rollback -----------------------------------------------------------------------

    /**
     * Het veldverwijzingstype wordt rechtstreeks op {@code COLUMN_INDEX} gezet terwijl de revisie nog veldnamen
     * ("leverancier", ...) als bron gebruikt: de DB-checks laten dit toe, de configuratievalidatie niet. De PUT moet
     * dan 400 {@code CONFIG_*} geven en niets bewaren.
     */
    @Test
    void aConfigurationErrorIs400ConfigAndRollsTheOverrideBack() throws Exception {
        long revisionId = draftRevision("CFG");
        jdbc.update("update import_definition_revision set structure_field_reference_kind = 'COLUMN_INDEX' "
                + "where id = ?", revisionId);

        String body = putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "DESCRIPTION")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(body, "$.code")).startsWith("CONFIG_");
        assertThat(rowCount(revisionId, "DESCRIPTION")).isZero();
    }

    // --- Gelijktijdigheid ---------------------------------------------------------------------------------

    /**
     * Twee gelijktijdige PUT's met een andere waarde op dezelfde (nog niet bestaande of bestaande) rij. De uitkomst
     * hangt van de timing af, dus de assertie is: nooit een 500 (een uitgelekte exception laat de future falen),
     * elke 409 draagt {@code REVISION_EDIT_CONFLICT}, en er blijft precies één rij met een van de twee waarden.
     */
    @Test
    void concurrentPutsOnTheSameRowNeverGiveA500AndEveryConflictIsRevisionEditConflict() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                long revisionId = draftRevision("RACE" + round);
                if (round % 2 == 1) {
                    putAs(ADMIN, "{\"criticality\":\"CRITICAL\"}", revisionId, "DESCRIPTION")
                            .andExpect(status().isOk());
                }
                CountDownLatch go = new CountDownLatch(1);
                List<Future<int[]>> results = new ArrayList<>();
                for (String value : List.of("CRITICAL", "NON_CRITICAL")) {
                    String user = "CRITICAL".equals(value) ? ADMIN : OTHER;
                    results.add(pool.submit(() -> {
                        go.await();
                        var response = putAs(user, "{\"criticality\":\"" + value + "\"}", revisionId,
                                "DESCRIPTION").andReturn().getResponse();
                        if (response.getStatus() == 409) {
                            assertThat((String) JsonPath.read(response.getContentAsString(), "$.code"))
                                    .isEqualTo("REVISION_EDIT_CONFLICT");
                        }
                        return new int[] {response.getStatus()};
                    }));
                }
                go.countDown();
                for (Future<int[]> result : results) {
                    assertThat(result.get(30, TimeUnit.SECONDS)[0]).isIn(200, 409);
                }
                assertThat(rowCount(revisionId, "DESCRIPTION")).isEqualTo(1);
                assertThat(criticalityOf(revisionId, "DESCRIPTION")).isIn("CRITICAL", "NON_CRITICAL");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDeletesOnTheSameRowGiveOneNoContentAndOtherwiseNotFoundOrEditConflict() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long revisionId = draftRevision("RACEDEL");
            putAs(ADMIN, "{\"criticality\":\"NON_CRITICAL\"}", revisionId, "DESCRIPTION")
                    .andExpect(status().isOk());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return deleteAs(ADMIN, revisionId, "DESCRIPTION").andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            int noContent = 0;
            for (Future<Integer> result : results) {
                int code = result.get(30, TimeUnit.SECONDS);
                assertThat(code).isIn(204, 404, 409);
                if (code == 204) {
                    noContent++;
                }
            }
            assertThat(noContent).isEqualTo(1);
            assertThat(rowCount(revisionId, "DESCRIPTION")).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Helpers ------------------------------------------------------------------------------------------

    private ResultActions putAs(String user, String body, long revisionId, String fieldKey) throws Exception {
        return json(user, put(PATH, revisionId, fieldKey), body);
    }

    private ResultActions deleteAs(String user, long revisionId, String fieldKey) throws Exception {
        return json(user, delete(PATH, revisionId, fieldKey), "");
    }

    private ResultActions json(String user, MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long rowCount(long revisionId, String fieldKey) {
        Long count = jdbc.queryForObject("select count(*) from import_revision_field_criticality "
                + "where definition_revision_id = ? and field_key = ?", Long.class, revisionId, fieldKey);
        return count == null ? 0L : count;
    }

    private String criticalityOf(long revisionId, String fieldKey) {
        return jdbc.queryForObject("select criticality from import_revision_field_criticality "
                + "where definition_revision_id = ? and field_key = ?", String.class, revisionId, fieldKey);
    }

    private Timestamp createdAtOf(long revisionId, String fieldKey) {
        return jdbc.queryForObject("select created_at from import_revision_field_criticality "
                + "where definition_revision_id = ? and field_key = ?", Timestamp.class, revisionId, fieldKey);
    }

    /** {@code created_by} en {@code created_by_subject} van de rij. */
    private List<String> signatureOf(long revisionId, String fieldKey) {
        Map<String, Object> row = jdbc.queryForMap("select created_by, created_by_subject "
                + "from import_revision_field_criticality where definition_revision_id = ? and field_key = ?",
                revisionId, fieldKey);
        return java.util.Arrays.asList((String) row.get("created_by"), (String) row.get("created_by_subject"));
    }

    /** Organisatie + definitie + één {@code DRAFT}-revisie. */
    private long draftRevision(String prefix) throws Exception {
        String unique = "EB2" + SEQUENCE.incrementAndGet() + prefix + RUN;
        json(ADMIN, post(SETUP + "/source-organisations"),
                "{\"code\":\"" + unique + "\",\"name\":\"" + unique + " BV\",\"type\":\"SUPPLIER\"}")
                .andExpect(status().isCreated());
        long definitionId = id(json(ADMIN, post(SETUP + "/definitions"),
                "{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\"" + unique + "-DEF\",\"name\":\""
                        + unique + " catalogus\",\"usageType\":\"OWN_DEFINITION\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return id(json(ADMIN, post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Dezelfde configuratie als {@code SetupApiFlowTest.revisionJson()}. */
    private static String revisionJson() {
        return """
                {"delimiter":";","quoteChar":"\\"","charset":"UTF-8","hasHeader":true,
                 "headerLineNumber":1,"fieldReferenceKind":"HEADER_NAME",
                 "identityProfileKind":"THREE_PART","supplierField":"leverancier",
                 "supplierGroupField":"groep","supplierReferenceField":"referentie",
                 "discountCodeField":null,"basePriceField":"prijs","descriptionField":"omschrijving",
                 "currencyField":"valuta","canonicalisationVersion":2,
                 "creationThresholdSharePercent":10,"maxCriticalSharePercent":25}""";
    }
}
