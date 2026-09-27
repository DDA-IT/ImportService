package be.dda.catalogimport.web;

import static be.dda.catalogimport.testsupport.TestActors.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.PublicationBundleService;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bouwstap 5A-4 (docs/design/fase5-auth-design.md par. 3, 4 en 7): alle overige schrijfendpoints van de
 * bundel (aanmaken, batches toevoegen/verwijderen, individueel goed-/afkeuren, groepsactie, annuleren)
 * ondertekenen op de <b>geverifieerde identiteit</b>, net als freeze sinds 5A-2 ({@code FreezeActorHttpTest}).
 *
 * <h2>Business rule vs. implementatie</h2>
 * <ul>
 *   <li><b>Regel:</b> wie een bundelactie uitvoert, tekent onder zijn eigen naam. <b>Implementatie:</b>
 *       {@code CurrentActor.signer(requestValue, veld)} in de controller, vóór de service; het requestveld
 *       is optioneel en enkel nog een controle.</li>
 *   <li><b>Regel:</b> een handtekening onder de verkeerde naam gebeurt nooit ongemerkt.
 *       <b>Implementatie:</b> een afwijkende naam is 400 {@code ACTOR_FIELD_MISMATCH}, zonder nevenschrijfactie.</li>
 *   <li><b>Regel:</b> {@code system} tekent nooit. <b>Implementatie:</b> 403 {@code SYSTEM_ACTOR_FORBIDDEN}.</li>
 *   <li><b>Data:</b> naast de bestaande {@code *_by}-naam komt het OIDC-subject in de nieuwe
 *       {@code *_by_subject}-kolommen (007-2 en 007-1); {@code NULL} = geen geverifieerde identiteit
 *       (directe Service-aanroep).</li>
 * </ul>
 * Controlevolgorde: de actorcontrole gaat vóór de service, dus een mismatch is 400 ook op een onbekende bundel
 * (vóór de 404). Het subject staat in geen enkel domeinantwoord (A6) en wordt via JDBC nagekeken.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class BundleActorHttpTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String OTHER = "piet.willems@example.test";
    private static final String DECIDER = "an.janssens@example.test";
    private static final String CANCELLER = "lieve.maes@example.test";
    private static final String BASE = "/api/catalog-import/bundles";

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
    @Autowired
    private PublicationBundleService bundleService;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;

    // --- POST /bundles (createdBy) ------------------------------------------------------------------------

    @Test
    void createWithoutACreatedByStoresTheTokenUsernameAndSubject() throws Exception {
        String reference = reference("CREATE");

        json(post(BASE), CREATOR, "{\"bundleReference\":\"" + reference + "\",\"targetMode\":\"SIMULATION\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value(CREATOR))
                .andExpect(jsonPath("$.createdBySubject").doesNotExist())
                .andExpect(jsonPath("$.subject").doesNotExist());

        assertThat(createdBy(reference)).containsExactly(CREATOR, subjectOf(CREATOR));
    }

    @Test
    void createWithTheSameNameInAnotherCasingIsAcceptedAndTheTokenSpellingIsStored() throws Exception {
        String reference = reference("CASE");

        json(post(BASE), CREATOR, "{\"bundleReference\":\"" + reference + "\",\"targetMode\":\"SIMULATION\","
                + "\"createdBy\":\"  " + CREATOR.toUpperCase(Locale.ROOT) + " \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value(CREATOR));

        assertThat(createdBy(reference)).containsExactly(CREATOR, subjectOf(CREATOR));
    }

    @Test
    void createWithAnotherNameIs400AndNoBundleIsWritten() throws Exception {
        String reference = reference("MISMATCH");

        String body = json(post(BASE), CREATOR, "{\"bundleReference\":\"" + reference
                + "\",\"targetMode\":\"SIMULATION\",\"createdBy\":\"" + OTHER + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("createdBy").doesNotContain(CREATOR).doesNotContain(OTHER);
        assertThat(bundleCount(reference)).isZero();
    }

    @Test
    void createAsSystemIs403AndNoBundleIsWritten() throws Exception {
        String reference = reference("SYSTEM");

        json(post(BASE), "system", "{\"bundleReference\":\"" + reference + "\",\"targetMode\":\"SIMULATION\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(bundleCount(reference)).isZero();
    }

    /** Idempotente hervinding: de oorspronkelijke aanmaker (en diens subject) blijft staan. */
    @Test
    void anIdempotentRecreateKeepsTheOriginalCreatorAndSubject() throws Exception {
        String reference = reference("IDEM");
        String body = "{\"bundleReference\":\"" + reference + "\",\"targetMode\":\"SIMULATION\"}";
        json(post(BASE), CREATOR, body).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true));

        json(post(BASE), OTHER, body).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));

        assertThat(createdBy(reference)).containsExactly(CREATOR, subjectOf(CREATOR));
    }

    // --- POST /bundles/{id}/batches (addedBy) ------------------------------------------------------------

    @Test
    void addBatchesStoresTheTokenUsernameAndSubjectOnTheMembership() throws Exception {
        Scenario s = scenario("ADD", false);

        json(post(BASE + "/{id}/batches", s.bundleId()), CREATOR, "{\"batchIds\":[" + s.batchId() + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].addedBy").value(CREATOR))
                .andExpect(jsonPath("$[0].addedBySubject").doesNotExist());

        assertThat(membership(s)).containsExactly(CREATOR, subjectOf(CREATOR), null, null);
    }

    @Test
    void addBatchesWithAnotherNameIs400AndNothingIsAdded() throws Exception {
        Scenario s = scenario("ADDMIS", false);

        json(post(BASE + "/{id}/batches", s.bundleId()), CREATOR,
                "{\"batchIds\":[" + s.batchId() + "],\"addedBy\":\"" + OTHER + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));

        assertThat(membershipCount(s)).isZero();
    }

    @Test
    void addBatchesAsSystemIs403AndNothingIsAdded() throws Exception {
        Scenario s = scenario("ADDSYS", false);

        json(post(BASE + "/{id}/batches", s.bundleId()), "system", "{\"batchIds\":[" + s.batchId() + "]}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(membershipCount(s)).isZero();
    }

    // --- POST /bundles/{id}/batches/{b}/remove (removedBy) -----------------------------------------------

    @Test
    void removeBatchStoresTheTokenUsernameAndSubject() throws Exception {
        Scenario s = scenario("REMOVE", true);

        json(post(BASE + "/{id}/batches/{b}/remove", s.bundleId(), s.batchId()), OTHER,
                "{\"reason\":\"Verkeerde levering\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removedBy").value(OTHER))
                .andExpect(jsonPath("$.active").value(false));

        assertThat(membership(s)).containsExactly(CREATOR, subjectOf(CREATOR), OTHER, subjectOf(OTHER));
    }

    @Test
    void removeBatchWithAnotherNameIs400AndTheMembershipStaysActive() throws Exception {
        Scenario s = scenario("REMMIS", true);

        json(post(BASE + "/{id}/batches/{b}/remove", s.bundleId(), s.batchId()), OTHER,
                "{\"removedBy\":\"" + CREATOR + "\",\"reason\":\"Verkeerde levering\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));

        assertThat(membership(s)).containsExactly(CREATOR, subjectOf(CREATOR), null, null);
    }

    @Test
    void removeBatchAsSystemIs403AndTheMembershipStaysActive() throws Exception {
        Scenario s = scenario("REMSYS", true);

        json(post(BASE + "/{id}/batches/{b}/remove", s.bundleId(), s.batchId()), "system",
                "{\"reason\":\"Verkeerde levering\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(membership(s)).containsExactly(CREATOR, subjectOf(CREATOR), null, null);
    }

    // --- approve / reject (decidedBy) ----------------------------------------------------------------------

    @Test
    void approveStoresTheTokenUsernameAndSubjectOnTheDecisionAndTheMutation() throws Exception {
        Scenario s = scenario("APPROVE", true);
        long mutationId = firstMutation(s);

        json(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutationId), DECIDER, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mutation.status").value("READY_FOR_PUBLICATION"))
                .andExpect(jsonPath("$.decision.decidedBy").value(DECIDER))
                .andExpect(jsonPath("$.decision.decidedBySubject").doesNotExist());

        assertThat(decisions(s)).containsExactly(DECIDER + "|" + subjectOf(DECIDER));
        assertThat(mutationDecidedBy(mutationId)).isEqualTo(DECIDER);
    }

    @Test
    void approveWithAnotherNameIs400AndNothingIsDecided() throws Exception {
        Scenario s = scenario("APPMIS", true);
        long mutationId = firstMutation(s);

        json(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutationId), DECIDER,
                "{\"decidedBy\":\"" + OTHER + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));

        assertThat(decisions(s)).isEmpty();
        assertThat(mutationDecidedBy(mutationId)).isNull();
    }

    @Test
    void approveAsSystemIs403AndNothingIsDecided() throws Exception {
        Scenario s = scenario("APPSYS", true);
        long mutationId = firstMutation(s);

        json(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutationId), "system", "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(decisions(s)).isEmpty();
    }

    /** Idempotentie blijft op username: dezelfde beslisser opnieuw = 200 idempotent, geen tweede regel. */
    @Test
    void approvingTwiceAsTheSameUserIsIdempotentAndWritesOneDecision() throws Exception {
        Scenario s = scenario("APPTWICE", true);
        long mutationId = firstMutation(s);

        json(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutationId), DECIDER, "{}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotent").value(false));
        json(post(BASE + "/{id}/mutations/{m}/approve", s.bundleId(), mutationId), DECIDER,
                "{\"decidedBy\":\"" + DECIDER + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotent").value(true));

        assertThat(decisions(s)).containsExactly(DECIDER + "|" + subjectOf(DECIDER));
    }

    @Test
    void rejectStoresTheTokenUsernameAndSubject() throws Exception {
        Scenario s = scenario("REJECT", true);
        long mutationId = firstMutation(s);

        json(post(BASE + "/{id}/mutations/{m}/reject", s.bundleId(), mutationId), DECIDER,
                "{\"reason\":\"Prijs klopt niet\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mutation.status").value("REJECTED"))
                .andExpect(jsonPath("$.decision.decidedBy").value(DECIDER));

        assertThat(decisions(s)).containsExactly(DECIDER + "|" + subjectOf(DECIDER));
    }

    @Test
    void rejectWithAnotherNameIs400AndAsSystemIs403AndNothingIsDecided() throws Exception {
        Scenario s = scenario("REJERR", true);
        long mutationId = firstMutation(s);

        json(post(BASE + "/{id}/mutations/{m}/reject", s.bundleId(), mutationId), DECIDER,
                "{\"decidedBy\":\"" + OTHER + "\",\"reason\":\"Nee\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        json(post(BASE + "/{id}/mutations/{m}/reject", s.bundleId(), mutationId), "SYSTEM",
                "{\"reason\":\"Nee\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(decisions(s)).isEmpty();
    }

    // --- POST /bundles/{id}/decisions (groepsactie, decidedBy) -----------------------------------------------

    @Test
    void theGroupDecisionStoresTheTokenUsernameAndSubjectOnTheOneDecisionRow() throws Exception {
        Scenario s = scenario("GROUP", true);

        json(post(BASE + "/{id}/decisions", s.bundleId()), DECIDER,
                "{\"decisionKind\":\"APPROVE\",\"reason\":\"Nagekeken\",\"filter\":{\"batchId\":"
                        + s.batchId() + "}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(3));

        assertThat(decisions(s)).containsExactly(DECIDER + "|" + subjectOf(DECIDER));
    }

    @Test
    void theGroupDecisionWithAnotherNameIs400AndAsSystemIs403AndNothingIsDecided() throws Exception {
        Scenario s = scenario("GRPERR", true);
        String filter = "\"filter\":{\"batchId\":" + s.batchId() + "}";

        json(post(BASE + "/{id}/decisions", s.bundleId()), DECIDER,
                "{\"decisionKind\":\"APPROVE\",\"decidedBy\":\"" + OTHER + "\"," + filter + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        json(post(BASE + "/{id}/decisions", s.bundleId()), "system",
                "{\"decisionKind\":\"APPROVE\"," + filter + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(decisions(s)).isEmpty();
        assertThat(pendingMutations(s)).isPositive();
    }

    /**
     * De C6-whitelist gaat vóór de actorcontrole in de handler: een onbekend veld is 400
     * {@code DECISION_FILTER_UNKNOWN_FIELD}, ook als {@code decidedBy} een andere naam draagt.
     * <p>
     * <b>Gewijzigd in 5B-1</b> (Fase 5-PERM, ontwerp par. 3, keuze mens V1 "recht eerst"): dit endpoint
     * draagt nu {@code @RequiresPermission(APPROVE)}, en die check loopt in een interceptor <b>vóór</b>
     * de handler. Daardoor gaat {@code SYSTEM_ACTOR_FORBIDDEN} nu vóór de whitelist — vroeger kreeg
     * een aangemelde {@code system} met een onbekend veld nog 400. De whitelist zelf is niet gewijzigd.
     */
    @Test
    void theUnknownFieldWhitelistStillRunsBeforeTheActorCheckInTheHandler() throws Exception {
        Scenario s = scenario("GRPWL", true);
        String unknownField = "{\"decisionKind\":\"APPROVE\",\"colour\":\"red\",\"decidedBy\":\"" + OTHER
                + "\",\"filter\":{\"batchId\":" + s.batchId() + "}}";

        json(post(BASE + "/{id}/decisions", s.bundleId()), DECIDER, unknownField)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DECISION_FILTER_UNKNOWN_FIELD"));

        // Nieuwe volgorde sinds 5B-1: de rechtenlaag komt eerst, dus system is 403 en niet meer 400.
        json(post(BASE + "/{id}/decisions", s.bundleId()), "system", unknownField)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertThat(decisions(s)).isEmpty();
    }

    // --- POST /bundles/{id}/cancel (cancelledBy) -----------------------------------------------------------------

    @Test
    void cancelWithoutACancelledByStoresTheTokenUsernameAndSubjectEverywhere() throws Exception {
        Scenario s = scenario("CANCEL", true);

        json(post(BASE + "/{id}/cancel", s.bundleId()), CANCELLER, "{\"reason\":\"Leverancier trok in\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value(CANCELLER))
                .andExpect(jsonPath("$.cancelledBySubject").doesNotExist());

        Map<String, Object> row = jdbc.queryForMap(
                "select cancelled_by, cancelled_by_subject from publication_bundle where id = ?", s.bundleId());
        assertThat(row.get("cancelled_by")).isEqualTo(CANCELLER);
        assertThat(row.get("cancelled_by_subject")).isEqualTo(subjectOf(CANCELLER));
        // De CANCEL-beslissingsregel en het vrijgegeven lidmaatschap dragen hetzelfde subject.
        assertThat(decisions(s)).containsExactly(CANCELLER + "|" + subjectOf(CANCELLER));
        assertThat(membership(s)).containsExactly(CREATOR, subjectOf(CREATOR), CANCELLER, subjectOf(CANCELLER));
    }

    @Test
    void cancelWithAnotherNameIs400AndTheBundleStaysAssembling() throws Exception {
        Scenario s = scenario("CANMIS", true);

        json(post(BASE + "/{id}/cancel", s.bundleId()), CANCELLER,
                "{\"cancelledBy\":\"" + OTHER + "\",\"reason\":\"Nee\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));

        assertUnchanged(s);
    }

    @Test
    void cancelAsSystemIs403AndTheBundleStaysAssembling() throws Exception {
        Scenario s = scenario("CANSYS", true);

        json(post(BASE + "/{id}/cancel", s.bundleId()), "system", "{\"reason\":\"Nee\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));

        assertUnchanged(s);
    }

    // --- Controlevolgorde: de actorcontrole gaat vóór de service (400/403 vóór 404) -------------------------------

    @Test
    void theActorCheckRunsBeforeTheBundleLookup() throws Exception {
        long unknown = 999_999_999L;

        json(post(BASE + "/{id}/batches", unknown), CREATOR, "{\"batchIds\":[1],\"addedBy\":\"" + OTHER + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACTOR_FIELD_MISMATCH"));
        json(post(BASE + "/{id}/cancel", unknown), "system", "{\"reason\":\"Nee\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SYSTEM_ACTOR_FORBIDDEN"));
        // Met een geldige identiteit blijft het gewoon een 404.
        json(post(BASE + "/{id}/cancel", unknown), CANCELLER, "{\"reason\":\"Nee\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BUNDLE_NOT_FOUND"));
    }

    // --- Directe Service-aanroep: NULL = geen geverifieerde identiteit -----------------------------------------

    @Test
    void aDirectServiceCallStoresTheNamesWithoutASubject() throws Exception {
        Scenario s = scenario("DIRECT", false);
        bundleService.addBatches(s.bundleId(), List.of(s.batchId()), CREATOR);

        assertThat(membership(s)).containsExactly(CREATOR, null, null, null);
        String reference = reference("DIRECTB");
        bundleService.createBundle(reference, null, PublicationTargetMode.SIMULATION, null, null, CREATOR);
        assertThat(createdBy(reference)).containsExactly(CREATOR, null);
    }

    // --- Helpers ------------------------------------------------------------------------------------------------

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String actor, String body) throws Exception {
        return mockMvc.perform(request.with(as(actor)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Het subject dat {@code TestActors.as} voor een username afleidt; hardcoded zodat een wijziging opvalt. */
    private static String subjectOf(String username) {
        return "test-sub-" + username;
    }

    private static String reference(String prefix) {
        return "BND-BA-" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
    }

    private List<String> createdBy(String reference) {
        Map<String, Object> row = jdbc.queryForMap(
                "select created_by, created_by_subject from publication_bundle where bundle_reference = ?", reference);
        return Arrays.asList((String) row.get("created_by"), (String) row.get("created_by_subject"));
    }

    private long bundleCount(String reference) {
        Long count = jdbc.queryForObject("select count(*) from publication_bundle where bundle_reference = ?",
                Long.class, reference);
        return count == null ? 0L : count;
    }

    /** added_by, added_by_subject, removed_by, removed_by_subject van het lidmaatschap. */
    private List<String> membership(Scenario s) {
        Map<String, Object> row = jdbc.queryForMap("select added_by, added_by_subject, removed_by, "
                + "removed_by_subject from publication_bundle_batch where bundle_id = ? and batch_id = ?",
                s.bundleId(), s.batchId());
        return Arrays.asList((String) row.get("added_by"), (String) row.get("added_by_subject"),
                (String) row.get("removed_by"), (String) row.get("removed_by_subject"));
    }

    private long membershipCount(Scenario s) {
        Long count = jdbc.queryForObject(
                "select count(*) from publication_bundle_batch where bundle_id = ? and batch_id = ?", Long.class,
                s.bundleId(), s.batchId());
        return count == null ? 0L : count;
    }

    /** "decided_by|decided_by_subject" per beslissingsregel van deze bundel. */
    private List<String> decisions(Scenario s) {
        return jdbc.query("select decided_by, decided_by_subject from publication_decision where bundle_id = ? "
                        + "order by id",
                (rs, n) -> rs.getString("decided_by") + "|" + rs.getString("decided_by_subject"), s.bundleId());
    }

    private long firstMutation(Scenario s) {
        return jdbc.queryForObject("select min(id) from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", Long.class, s.batchId());
    }

    private String mutationDecidedBy(long mutationId) {
        return jdbc.queryForObject("select decided_by from import_mutation where id = ?", String.class, mutationId);
    }

    private long pendingMutations(Scenario s) {
        Long count = jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and status = 'AWAITING_APPROVAL' and decision_id is null", Long.class, s.batchId());
        return count == null ? 0L : count;
    }

    /** Niets geschreven: bundel nog ASSEMBLING zonder annulering, lidmaatschap actief, geen beslissingsregel. */
    private void assertUnchanged(Scenario s) {
        Map<String, Object> row = jdbc.queryForMap(
                "select status, cancelled_by, cancelled_by_subject, cancelled_at from publication_bundle where id = ?",
                s.bundleId());
        assertThat(row.get("status")).isEqualTo("ASSEMBLING");
        assertThat(row.get("cancelled_by")).isNull();
        assertThat(row.get("cancelled_by_subject")).isNull();
        assertThat(row.get("cancelled_at")).isNull();
        assertThat(membership(s)).containsExactly(CREATOR, subjectOf(CREATOR), null, null);
        assertThat(decisions(s)).isEmpty();
    }

    /**
     * Een bundel met (optioneel) één lid: eerste levering van een koppeling, dus drie CREATE's plus de marker,
     * allemaal {@code AWAITING_APPROVAL}. Bundel en lidmaatschap worden aangemaakt via HTTP als {@code CREATOR}.
     */
    private Scenario scenario(String prefix, boolean withMember) throws Exception {
        String unique = "BA" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        CatalogImportTask task = fixture(unique);
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 1; index <= 3; index++) {
            csv.append("ACME;G1;R").append(index).append(";1").append(index).append(",00;Artikel ")
                    .append(index).append('\n');
        }
        String upload = mockMvc.perform(multipart("/api/catalog-import/tasks/{id}/deliveries", task.getId())
                        .file(new MockMultipartFile("file", "levering.csv", "text/csv",
                                csv.toString().getBytes(StandardCharsets.UTF_8)))
                        .param("deliveryReference", unique + "-LEV")
                        .param("uploadedBy", "tester@example.test").with(as("tester@example.test")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long batchId = ((Number) JsonPath.read(upload, "$.batchId")).longValue();

        String created = json(post(BASE), CREATOR, "{\"bundleReference\":\"BND-" + unique
                + "\",\"targetMode\":\"SIMULATION\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long bundleId = ((Number) JsonPath.read(created, "$.id")).longValue();
        if (withMember) {
            json(post(BASE + "/{id}/batches", bundleId), CREATOR, "{\"batchIds\":[" + batchId + "]}")
                    .andExpect(status().isOk());
        }
        return new Scenario(bundleId, batchId);
    }

    private CatalogImportTask fixture(String unique) {
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1, IdentityProfileKind.THREE_PART,
                "beheerder@example.test");
        revision.setAccessConfigHash("1".repeat(64));
        revision.setStructureConfigHash("2".repeat(64));
        revision.setRecordRulesConfigHash("3".repeat(64));
        revision.setCompositeConfigHash("4".repeat(64));
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setStructureDelimiter(";");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        return tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
    }

    private record Scenario(long bundleId, long batchId) {
    }
}
