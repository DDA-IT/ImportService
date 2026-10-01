package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NT-13 (beslissingslog 2026-10-01, "Woordkeuzes en openstaande punten NT-spoor"): hoogstens een concept (DRAFT) per
 * importdefinitie, door de server afgedwongen — ook voor {@code SetupService.createRevision}, niet enkel voor het
 * opvolgerpad.
 *
 * <ul>
 *   <li><b>Regel:</b> een tweede DRAFT op dezelfde definitie is 409 {@code REVISION_DRAFT_ALREADY_EXISTS}, dezelfde
 *       code als het opvolgerpad. <b>Implementatie:</b> controle vooraf + unieke sleutel
 *       {@code uk_import_definition_revision_draft} (marker {@code draft_marker}, migratie 016) waarvan de botsing
 *       tot dezelfde 409 vertaald wordt. <b>Data:</b> na afloop precies een DRAFT.</li>
 *   <li><b>Bewijs:</b> (a) twee echte gelijktijdige verzoeken: exact een 201 en een 409, nooit 500; (b) het racepad
 *       deterministisch: een andere transactie houdt een onbevestigde DRAFT vast, het verzoek passeert de controle
 *       vooraf, wacht aantoonbaar op de sleutel ({@code pg_blocking_pids}) en krijgt na de commit 409.</li>
 *   <li>Na het activeren van de DRAFT kan er weer een nieuwe komen (via aanmaken en via opvolger); de marker is dan
 *       weg.</li>
 * </ul>
 * Elke test gebruikt eigen unieke codes: de database is gedeeld.
 */
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupRevisionSingleDraftHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String SETUP = "/api/catalog-import/setup";
    private static final String USER = "an.janssens@example.test";
    private static final String CODE = "REVISION_DRAFT_ALREADY_EXISTS";
    private static final int ROUNDS = 3;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    @Test
    void aSecondCreateRevisionOnADefinitionWithAnOpenDraftIs409WithTheSuccessorCode() throws Exception {
        long definitionId = definition(unique("ONE"));
        long first = createRevision(definitionId);

        json(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("revision 1, id " + first)));

        assertThat(count("select count(*) from import_definition_revision where import_definition_id = ?",
                definitionId)).isEqualTo(1L);
        assertThat(count("select count(*) from import_definition_revision where import_definition_id = ? "
                + "and status = 'DRAFT'", definitionId)).isEqualTo(1L);
    }

    @Test
    void twoConcurrentCreateRevisionsGiveOne201AndOne409() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long definitionId = definition(unique("RACE"));

            List<MockHttpServletResponse> responses = concurrently(definitionId);

            List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
            assertThat(statuses).as("never a 500").containsExactlyInAnyOrder(201, 409);
            for (MockHttpServletResponse response : responses) {
                if (response.getStatus() == 409) {
                    assertThat((String) JsonPath.read(response.getContentAsString(), "$.code")).isEqualTo(CODE);
                }
            }
            assertThat(count("select count(*) from import_definition_revision where import_definition_id = ? "
                    + "and status = 'DRAFT'", definitionId)).isEqualTo(1L);
        }
    }

    /** Het racepad elke keer: de controle vooraf ziet de onbevestigde DRAFT van de andere transactie niet. */
    @Test
    void aDraftHeldByAnotherTransactionBecomes409AfterItCommits() throws Exception {
        long definitionId = definition(unique("HELD"));
        // Een ACTIVE revisie als kopieerbron voor de vasthoudende transactie; ze telt zelf niet als DRAFT.
        json(post(SETUP + "/revisions/{id}/activate", createRevision(definitionId)), "{}")
                .andExpect(status().isOk());

        MockHttpServletResponse response = whileAnotherTransactionHoldsADraft(definitionId);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code")).isEqualTo(CODE);
        assertThat(count("select count(*) from import_definition_revision where import_definition_id = ?",
                definitionId)).as("the ACTIVE source plus the held DRAFT, nothing from the request").isEqualTo(2L);
        assertThat(count("select count(*) from import_definition_revision where import_definition_id = ? "
                + "and status = 'DRAFT'", definitionId)).isEqualTo(1L);
    }

    @Test
    void afterActivatingTheDraftANewDraftCanBeCreatedAndTheSuccessorPathStillGuards() throws Exception {
        long definitionId = definition(unique("NEXT"));
        long first = createRevision(definitionId);
        json(post(SETUP + "/revisions/{id}/activate", first), "{}").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select draft_marker from import_definition_revision where id = ?",
                Boolean.class, first)).as("draft_marker cleared on activation").isNull();

        // Nieuwe DRAFT via aanmaken: weer toegelaten, nummer 2.
        json(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revisionNumber").value(2));

        // Het opvolgerpad ziet die open DRAFT en geeft dezelfde 409 als altijd.
        json(post(SETUP + "/revisions/{id}/successor", first), "{\"changeReason\":\"Nog eens\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CODE));
        assertThat(count("select count(*) from import_definition_revision where import_definition_id = ? "
                + "and status = 'DRAFT'", definitionId)).isEqualTo(1L);
    }

    @Test
    void theSuccessorStillWorksWhenNoDraftIsOpenAndBlocksAfterwardsCreateRevision() throws Exception {
        long definitionId = definition(unique("SUCC"));
        long first = createRevision(definitionId);
        json(post(SETUP + "/revisions/{id}/activate", first), "{}").andExpect(status().isOk());

        json(post(SETUP + "/revisions/{id}/successor", first), "{\"changeReason\":\"Nieuwe drempels\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.revisionNumber").value(2));

        json(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CODE));
    }

    @Test
    void migrationSixteenCreatedTheMarkerColumnAndTheUniqueKey() {
        // Schema-controle op het huidige schema (de tests draaien in een geïsoleerd schema).
        assertThat(count("select count(*) from information_schema.table_constraints "
                + "where table_schema = current_schema() and table_name = 'import_definition_revision' "
                + "and constraint_name = 'uk_import_definition_revision_draft' and constraint_type = 'UNIQUE'"))
                .isEqualTo(1L);
        assertThat(count("select count(*) from information_schema.columns where table_schema = current_schema() "
                + "and table_name = 'import_definition_revision' and column_name = 'draft_marker'")).isEqualTo(1L);
    }

    // --- Hulpmiddelen: gelijktijdigheid --------------------------------------------------------------------

    private List<MockHttpServletResponse> concurrently(long definitionId) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MockHttpServletResponse>> pending = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                pending.add(pool.submit(() -> {
                    go.await();
                    return perform(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson());
                }));
            }
            go.countDown();
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> response : pending) {
                responses.add(response.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Een tweede transactie voegt een DRAFT toe <b>zonder te committen</b>, met een ander revisienummer (99) zodat
     * enkel de draft-sleutel kan botsen. Pas wanneer {@code pg_blocking_pids} het wachten van het verzoek aantoont,
     * commit de tweede transactie.
     */
    private MockHttpServletResponse whileAnotherTransactionHoldsADraft(long definitionId) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                int holderPid;
                try (Statement statement = holder.createStatement();
                     ResultSet result = statement.executeQuery("select pg_backend_pid()")) {
                    result.next();
                    holderPid = result.getInt(1);
                }
                try (PreparedStatement statement = holder.prepareStatement(copyOfRevisionAsDraft99())) {
                    statement.setLong(1, definitionId);
                    statement.executeUpdate();
                }
                Future<MockHttpServletResponse> pending = pool.submit(
                        () -> perform(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson()));
                awaitBlockedBy(holderPid, pending);
                holder.commit();
                return pending.get(60, TimeUnit.SECONDS);
            } catch (Exception | AssertionError failure) {
                holder.rollback();
                throw failure;
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * {@code insert ... select} die de bestaande (ACTIVE) revisie van de definitie kopieert als DRAFT met nummer 99
     * en {@code draft_marker = true}. De kolomlijst komt uit {@code information_schema} van het huidige schema, zodat
     * elke NOT NULL-kolom (ook toekomstige) gevuld is; enkel id, nummer, status en de twee markers wijken af.
     */
    private String copyOfRevisionAsDraft99() {
        List<String> columns = jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_schema = current_schema() and table_name = 'import_definition_revision' "
                + "and column_name <> 'id' order by ordinal_position", String.class);
        List<String> select = new ArrayList<>();
        for (String column : columns) {
            select.add(switch (column) {
                case "revision_number" -> "99";
                case "status" -> "'DRAFT'";
                case "draft_marker" -> "true";
                case "active_marker", "approved_at", "approved_by", "approved_by_subject" -> "null";
                default -> column;
            });
        }
        return "insert into import_definition_revision (" + String.join(", ", columns) + ") select "
                + String.join(", ", select) + " from import_definition_revision "
                + "where import_definition_id = ? and status = 'ACTIVE'";
    }

    private void awaitBlockedBy(int holderPid, Future<MockHttpServletResponse> pending) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Long blocked = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))", Long.class,
                    holderPid);
            if (blocked != null && blocked > 0) {
                return;
            }
            if (pending.isDone()) {
                MockHttpServletResponse early = pending.get();
                fail("The request finished without waiting on the held key: " + early.getStatus() + " "
                        + early.getContentAsString());
            }
            Thread.sleep(25);
        }
        fail("The request never waited on the key held by the other transaction");
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.with(as(USER)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    // --- Hulpmiddelen: gegevens ----------------------------------------------------------------------------

    private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.with(as(USER)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long definition(String unique) throws Exception {
        json(post(SETUP + "/source-organisations"),
                "{\"code\":\"" + unique + "\",\"name\":\"" + unique + " BV\",\"type\":\"SUPPLIER\"}")
                .andExpect(status().isCreated());
        return id(json(post(SETUP + "/definitions"), "{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\""
                + unique + "-DEF\",\"name\":\"" + unique + " catalogus\"}").andExpect(status().isCreated()));
    }

    private long createRevision(long definitionId) throws Exception {
        return id(json(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson())
                .andExpect(status().isCreated()));
    }

    /** Dezelfde configuratie als {@code SetupCreateConflictAndCodeHttpTest}. */
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

    private long count(String sql, Object... arguments) {
        Long count = jdbc.queryForObject(sql, Long.class, arguments);
        return count == null ? 0L : count;
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static String unique(String prefix) {
        return "SD" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + prefix;
    }
}
