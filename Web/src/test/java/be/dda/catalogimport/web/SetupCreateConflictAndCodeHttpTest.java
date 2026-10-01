package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.hamcrest.Matchers;
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
 * NT-3, punten 2 en 3 (beslissingslog 2026-09-30 "Nieuwe leverancier + taak (NT-spoor)"): gelijktijdig aanmaken
 * geeft 409 in plaats van 500, en een 400 van de setup-API draagt een stabiele {@code code}. Draait met
 * {@code catalogimport.setup-api.enabled} <b>uit</b> (de default): alle paden hier staan sinds NT-3 niet meer
 * achter de vlag.
 *
 * <h2>409 in plaats van 500</h2>
 * <ul>
 *   <li><b>Regel:</b> een code (bronorganisatie, definitie, koppeling), een koppelingsscope en een taaknaam zijn
 *       uniek; een tweede aanmaakpoging is 409 met de bestaande {@code *_IN_USE}-code — ook wanneer beide
 *       verzoeken tegelijk komen. <b>Implementatie:</b> de controle vooraf blijft; de racevariant (beide
 *       passeren de controle, de tweede botst op de unieke sleutel) wordt op constraintnaam vertaald.
 *       <b>Data:</b> er is daarna precies één rij.</li>
 *   <li><b>Bewijs, twee lagen.</b> (a) Twee echte gelijktijdige HTTP-verzoeken: altijd exact één 201 en één
 *       409, nooit 500 — welke van de twee paden (controle vooraf of sleutelbotsing) de 409 levert, hangt af
 *       van de timing. (b) Deterministisch het racepad: een tweede transactie houdt dezelfde sleutel
 *       <i>onbevestigd</i> vast, het verzoek passeert daardoor de controle vooraf, wacht aantoonbaar op die
 *       sleutel ({@code pg_blocking_pids}), en krijgt na de commit van de andere transactie 409 — zelfde
 *       aanpak als de gesimuleerde databasetoestand in {@code RevisionSuccessorTest} (3a), maar hier met een
 *       echte slotwachttijd.</li>
 * </ul>
 *
 * <h2>Stabiele code op een 400</h2>
 * Een configuratiefout draagt haar {@code CONFIG_*}-code nu ook in {@code code}; een veldfout
 * {@code <VELD>_REQUIRED}/{@code <VELD>_TOO_LONG}/{@code <VELD>_INVALID}. De tekst in {@code error} is
 * ongewijzigd (additief contract), en er is niets opgeslagen.
 * <p>
 * Elke test gebruikt eigen unieke codes: de database is gedeeld. Kleine pool (4): hoogstens drie verbindingen
 * tegelijk (vasthoudende transactie, verzoek, peiling).
 */
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SetupCreateConflictAndCodeHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String SETUP = "/api/catalog-import/setup";
    private static final String USER = "an.janssens@example.test";
    private static final int ROUNDS = 3;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private SourceOrganisationRepository organisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportLinkRepository links;

    // --- (a) Twee echte gelijktijdige verzoeken -------------------------------------------------------------

    @Test
    void twoConcurrentCreatesOfTheSameSourceOrganisationCodeGiveOne201AndOne409() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String code = unique("ORG");
            String body = organisationJson(code);

            List<MockHttpServletResponse> responses = concurrently(() -> post(SETUP + "/source-organisations"), body);

            assertOneCreatedOneConflict(responses, "SOURCE_ORGANISATION_CODE_IN_USE");
            assertThat(count("select count(*) from source_organisation where code = ?", code)).isEqualTo(1L);
        }
    }

    @Test
    void twoConcurrentCreatesOfTheSameTaskNameGiveOne201AndOne409() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            ImportLink link = link(unique("TSK"));
            String name = unique("TAAK");
            String body = "{\"linkId\":" + link.getId() + ",\"name\":\"" + name + "\"}";

            List<MockHttpServletResponse> responses = concurrently(() -> post(SETUP + "/tasks"), body);

            assertOneCreatedOneConflict(responses, "TASK_NAME_IN_USE");
            assertThat(count("select count(*) from catalog_import_task where import_link_id = ? and name = ?",
                    link.getId(), name)).isEqualTo(1L);
        }
    }

    // --- (b) Het racepad deterministisch: de controle vooraf ziet de andere rij niet -------------------------

    @Test
    void aSourceOrganisationCodeHeldByAnotherTransactionBecomes409AfterItCommits() throws Exception {
        String code = unique("HORG");

        MockHttpServletResponse response = whileAnotherTransactionHolds(
                "insert into source_organisation (code, name, organisation_type, active, created_at, updated_at) "
                        + "values (?, ?, 'SUPPLIER', true, now(), now())",
                List.of(code, code + " (andere transactie)"),
                post(SETUP + "/source-organisations"), organisationJson(code));

        assertConflict(response, "SOURCE_ORGANISATION_CODE_IN_USE");
        assertThat(count("select count(*) from source_organisation where code = ?", code)).isEqualTo(1L);
    }

    @Test
    void aDefinitionCodeHeldByAnotherTransactionBecomes409AfterItCommits() throws Exception {
        String unique = unique("HDEF");
        SourceOrganisation organisation = organisation(unique);
        String definitionCode = unique + "-DEF";

        MockHttpServletResponse response = whileAnotherTransactionHolds(
                "insert into import_definition (source_organisation_id, code, description, usage_type, created_at, "
                        + "created_by, updated_at) values (?, ?, ?, 'OWN_DEFINITION', now(), 'andere-transactie', now())",
                List.of(organisation.getId(), definitionCode, "andere transactie"),
                post(SETUP + "/definitions"), "{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\""
                        + definitionCode + "\",\"name\":\"" + unique + " catalogus\"}");

        assertConflict(response, "DEFINITION_CODE_IN_USE");
        assertThat(count("select count(*) from import_definition where source_organisation_id = ? and code = ?",
                organisation.getId(), definitionCode)).isEqualTo(1L);
    }

    @Test
    void aLinkCodeHeldByAnotherTransactionBecomes409AfterItCommits() throws Exception {
        String unique = unique("HLNK");
        ImportDefinition definition = definition(unique);
        SourceOrganisation supplier = definition.getSourceOrganisation();
        String linkCode = unique + "-LINK";

        // Andere bibliotheek dan het verzoek: enkel de code botst, niet de scope.
        MockHttpServletResponse response = whileAnotherTransactionHolds(insertLink(),
                List.of(linkCode, "andere transactie", definition.getId(), supplier.getId(), "LIBA"),
                post(SETUP + "/links"), linkJson(definition.getId(), linkCode, supplier.getCode(), "LIBB"));

        assertConflict(response, "LINK_CODE_IN_USE");
        assertThat(count("select count(*) from import_link where code = ?", linkCode)).isEqualTo(1L);
    }

    @Test
    void aLinkScopeHeldByAnotherTransactionBecomes409AfterItCommits() throws Exception {
        String unique = unique("HSCP");
        ImportDefinition definition = definition(unique);
        SourceOrganisation supplier = definition.getSourceOrganisation();

        // Andere code, dezelfde definitie + leverancier + bibliotheek: enkel de scope botst.
        MockHttpServletResponse response = whileAnotherTransactionHolds(insertLink(),
                List.of(unique + "-A", "andere transactie", definition.getId(), supplier.getId(), "LIBS"),
                post(SETUP + "/links"), linkJson(definition.getId(), unique + "-B", supplier.getCode(), "LIBS"));

        assertConflict(response, "LINK_SCOPE_IN_USE");
        assertThat(count("select count(*) from import_link where import_definition_id = ? and library_code = ?",
                definition.getId(), "LIBS")).isEqualTo(1L);
    }

    @Test
    void aTaskNameHeldByAnotherTransactionBecomes409AfterItCommits() throws Exception {
        ImportLink link = link(unique("HTSK"));
        String name = unique("TAAK");

        MockHttpServletResponse response = whileAnotherTransactionHolds(
                "insert into catalog_import_task (import_link_id, name, active, trigger_type, "
                        + "prevent_concurrent_runs, created_at, updated_at) "
                        + "values (?, ?, true, 'MANUAL', true, now(), now())",
                List.of(link.getId(), name),
                post(SETUP + "/tasks"), "{\"linkId\":" + link.getId() + ",\"name\":\"" + name + "\"}");

        assertConflict(response, "TASK_NAME_IN_USE");
        assertThat(count("select count(*) from catalog_import_task where import_link_id = ? and name = ?",
                link.getId(), name)).isEqualTo(1L);
    }

    // --- Stabiele code op een 400 --------------------------------------------------------------------------

    /** Een configuratiefout die pas bij het activeren opvalt: de {@code CONFIG_*}-code staat nu ook in {@code code}. */
    @Test
    void activatingAnInvalidConfigurationIs400WithTheConfigCodeInCodeAndActivatesNothing() throws Exception {
        String unique = unique("CFG");
        long definitionId = chainUpToDefinition(unique);
        // Het aanmaken valideert de configuratie niet (een DRAFT mag onvolledig zijn); activeren wel.
        long revisionId = id(json(post(SETUP + "/definitions/{id}/revisions", definitionId),
                revisionJson().replace("\"charset\":\"UTF-8\"", "\"charset\":\"BESTAAT-NIET\""))
                .andExpect(status().isCreated()));

        json(post(SETUP + "/revisions/{id}/activate", revisionId), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_CHARSET_UNKNOWN"))
                // De tekst is ongewijzigd: de code vooraan, zoals voorheen.
                .andExpect(jsonPath("$.error").value(Matchers.startsWith("CONFIG_CHARSET_UNKNOWN: ")));

        assertThat(jdbc.queryForObject("select status from import_definition_revision where id = ?", String.class,
                revisionId)).isEqualTo("DRAFT");
    }

    /** Dezelfde code op het andere validatiemoment: het toevoegen van een mapping. */
    @Test
    void anInvalidMappingIs400WithTheConfigCodeInCode() throws Exception {
        String unique = unique("CFGM");
        long definitionId = chainUpToDefinition(unique);
        long revisionId = id(json(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson())
                .andExpect(status().isCreated()));

        json(post(SETUP + "/revisions/{id}/mappings", revisionId),
                "{\"targetFieldCode\":\"DESCRIPTION\",\"sourceReference\":\"omschrijving\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONFIG_FIELD_MAPPING_DUPLICATES_REVISION"))
                .andExpect(jsonPath("$.error").value(
                        Matchers.startsWith("CONFIG_FIELD_MAPPING_DUPLICATES_REVISION: ")));
        assertThat(count("select count(*) from import_field_mapping where definition_revision_id = ?", revisionId))
                .isZero();
    }

    @Test
    void fieldErrorsCarryAStableCodeNextToTheUnchangedText() throws Exception {
        String unique = unique("FLD");

        json(post(SETUP + "/source-organisations"), "{\"code\":\"  \",\"name\":\"X\",\"type\":\"SUPPLIER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_REQUIRED"))
                .andExpect(jsonPath("$.error").value("code must not be blank"));
        json(post(SETUP + "/source-organisations"), "{\"code\":\"" + unique + "\",\"name\":\"X\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TYPE_REQUIRED"))
                .andExpect(jsonPath("$.error").value("type must not be null"));
        json(post(SETUP + "/source-organisations"),
                "{\"code\":\"" + "X".repeat(51) + "\",\"name\":\"X\",\"type\":\"SUPPLIER\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_TOO_LONG"))
                .andExpect(jsonPath("$.error").value("code must be at most 50 characters"));
        assertThat(count("select count(*) from source_organisation where code = ?", unique)).isZero();

        long definitionId = chainUpToDefinition(unique);
        json(post(SETUP + "/definitions/{id}/revisions", definitionId),
                revisionJson().replace("\"delimiter\":\";\"", "\"delimiter\":\" \""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIMITER_REQUIRED"))
                .andExpect(jsonPath("$.error").value("delimiter must not be blank"));
        json(post(SETUP + "/definitions/{id}/revisions", definitionId),
                revisionJson().replace("\"delimiter\":\";\"", "\"delimiter\":\";;\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIMITER_TOO_LONG"));
        json(post(SETUP + "/definitions/{id}/revisions", definitionId),
                revisionJson().replace("\"discountCodeField\":null", "\"discountCodeField\":\"korting\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DISCOUNT_CODE_FIELD_INVALID"));
        assertThat(count("select count(*) from import_definition_revision where import_definition_id = ?",
                definitionId)).isZero();

        long revisionId = id(json(post(SETUP + "/definitions/{id}/revisions", definitionId), revisionJson())
                .andExpect(status().isCreated()));
        json(patch(SETUP + "/revisions/{id}", revisionId), "{\"maxCriticalSharePercent\":-1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MAX_CRITICAL_SHARE_PERCENT_INVALID"))
                .andExpect(jsonPath("$.error").value("maxCriticalSharePercent must not be negative"));
        json(post(SETUP + "/revisions/{id}/field-criticality", revisionId),
                "{\"fieldKey\":\"SUPPLIER\",\"criticality\":\"NON_CRITICAL\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CRITICALITY_INVALID"));
        json(post(SETUP + "/revisions/{id}/field-criticality", revisionId),
                "{\"fieldKey\":\"BESTAAT_NIET\",\"criticality\":\"CRITICAL\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIELD_KEY_INVALID"));

        json(post(SETUP + "/tasks"), "{\"name\":\"zonder koppeling\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LINK_ID_REQUIRED"));
        json(post(SETUP + "/links"), linkJson(definitionId, unique + "-LINK", unique, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LIBRARY_CODE_REQUIRED"));
        assertThat(count("select count(*) from import_link where code = ?", unique + "-LINK")).isZero();
    }

    // --- Hulpmiddelen: gelijktijdigheid --------------------------------------------------------------------

    /** Twee identieke verzoeken die samen vertrekken. */
    private List<MockHttpServletResponse> concurrently(Callable<MockHttpServletRequestBuilder> request, String body)
            throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MockHttpServletResponse>> pending = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                pending.add(pool.submit(() -> {
                    go.await();
                    return perform(request.call(), body);
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
     * Een tweede transactie voegt de botsende rij toe <b>zonder te committen</b>. Het verzoek ziet die rij in zijn
     * controle vooraf dus niet (READ COMMITTED), voegt zelf toe en wacht op de unieke sleutel. Pas wanneer
     * {@code pg_blocking_pids} dat wachten aantoont, commit de tweede transactie; het verzoek botst dan op de
     * sleutel. Zo wordt het racepad elke keer doorlopen, niet enkel bij toeval.
     */
    private MockHttpServletResponse whileAnotherTransactionHolds(String insert, List<Object> parameters,
                                                                 MockHttpServletRequestBuilder request, String body)
            throws Exception {
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
                try (PreparedStatement statement = holder.prepareStatement(insert)) {
                    for (int i = 0; i < parameters.size(); i++) {
                        statement.setObject(i + 1, parameters.get(i));
                    }
                    statement.executeUpdate();
                }
                Future<MockHttpServletResponse> pending = pool.submit(() -> perform(request, body));
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

    private static void assertOneCreatedOneConflict(List<MockHttpServletResponse> responses, String code)
            throws Exception {
        List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
        assertThat(statuses).as("never a 500").containsExactlyInAnyOrder(201, 409);
        for (MockHttpServletResponse response : responses) {
            if (response.getStatus() == 409) {
                assertConflict(response, code);
            }
        }
    }

    private static void assertConflict(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(409);
        assertThat((String) JsonPath.read(response.getContentAsString(), "$.code")).isEqualTo(code);
    }

    // --- Hulpmiddelen: gegevens ----------------------------------------------------------------------------

    private ResultActions json(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.with(as(USER)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long chainUpToDefinition(String unique) throws Exception {
        json(post(SETUP + "/source-organisations"), organisationJson(unique)).andExpect(status().isCreated());
        return id(json(post(SETUP + "/definitions"), "{\"sourceOrganisationCode\":\"" + unique + "\",\"code\":\""
                + unique + "-DEF\",\"name\":\"" + unique + " catalogus\"}").andExpect(status().isCreated()));
    }

    private SourceOrganisation organisation(String unique) {
        return organisations.saveAndFlush(new SourceOrganisation(unique, unique + " BV", SourceOrganisationType.SUPPLIER));
    }

    private ImportDefinition definition(String unique) {
        return definitions.saveAndFlush(new ImportDefinition(organisation(unique), unique + "-DEF",
                unique + " catalogus", "beheerder@example.test"));
    }

    private ImportLink link(String unique) {
        ImportDefinition definition = definition(unique);
        return links.saveAndFlush(new ImportLink(unique + "-LINK", unique + " koppeling", definition,
                definition.getSourceOrganisation(), "PSARF050"));
    }

    private static String insertLink() {
        return "insert into import_link (code, name, import_definition_id, supplier_organisation_id, library_code, "
                + "active, created_at, updated_at) values (?, ?, ?, ?, ?, true, now(), now())";
    }

    private static String organisationJson(String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + code + " BV\",\"type\":\"SUPPLIER\"}";
    }

    private static String linkJson(long definitionId, String code, String supplierCode, String libraryCode) {
        return "{\"definitionId\":" + definitionId + ",\"code\":\"" + code + "\",\"name\":\"" + code
                + "\",\"supplierCode\":\"" + supplierCode + "\",\"libraryCode\":\"" + libraryCode + "\"}";
    }

    /** Dezelfde configuratie als {@code SetupApiFlowTest}: puntkomma, header, drie-delige identiteit. */
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

    /** Hoogstens 30 tekens: past in elke codekolom (varchar(50)) ook met een achtervoegsel als {@code -LINK}. */
    private static String unique(String prefix) {
        return "SC" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + prefix;
    }
}
