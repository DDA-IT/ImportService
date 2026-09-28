package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.IssueCaseStatus;
import be.dda.catalogimport.domain.IssueIncidentKind;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.ImportValueRules;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Bouwstap S2-B1b (docs/design/issue-case-design.md par. 2 en par. 3): het <b>behandelgeval</b> dat
 * tijdens de screening aan de overgebleven issuegroepen gekoppeld wordt, en de heropeningsregel
 * (R-CASE-01 t/m R-CASE-04). Tegen de echte services, DAO's, het archief en de echte database.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li>De eerste waarneming maakt één geval in {@code AWAITING_REVIEW} met één {@code CREATED}-event.</li>
 *   <li>Een tweede levering met hetzelfde probleem maakt <b>geen</b> tweede geval (R-CASE-01): de
 *       tellers en {@code last_seen_*} van het bestaande geval schuiven op.</li>
 *   <li>Dezelfde batch twee keer synchroniseren verandert niets: geen dubbele telling, geen dubbel
 *       event.</li>
 *   <li>{@code CORRECTED} + een nieuwe waarneming heropent altijd, met een {@code SYSTEM}-event en een
 *       zichtbare reden náást de eerdere menselijke reden (R-CASE-03/R-CASE-04).</li>
 *   <li>{@code REJECTED} blijft onderdrukt zolang dezelfde revisie geldt, maar de tellers lopen wél
 *       door: de onderdrukking is nooit onzichtbaar (R-CASE-02).</li>
 *   <li>{@code REJECTED} onder een <b>andere</b> revisie heropent (R-CASE-03).</li>
 *   <li>Ook een geblokkeerde levering krijgt haar behandelgevallen: het ene hookpunt dekt beide
 *       screeningpaden.</li>
 *   <li>Het herstelpad verwijdert een geval dat nooit een geldige waarneming gehad heeft en waaraan
 *       nog nooit een mens geraakt heeft, en laat een geval met een menselijke beslissing staan.</li>
 *   <li><b>D3:</b> de synchronisatie raakt {@code validation_result}, {@code import_batch}-tellers,
 *       {@code import_mutation.status} en de drempels niet aan.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de database is over de testklassen heen gedeeld.
 * {@code max_critical_share_percent} staat per fixture op 100 (patroon {@link IssueGroupingTest}):
 * deze test gaat niet over de leveringsdrempel.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=25",
        "catalogimport.screening.mutation-chunk-size=25",
        "catalogimport.screening.price-control-chunk-size=3",
        "catalogimport.screening.reference-control-chunk-size=3",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class IssueCaseSyncTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";

    /** Boven {@code IssueAggregationService.GROUP_MIN_OCCURRENCES}: anders bestaat er geen groep. */
    private static final int REPEATS = 12;

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private IssueCaseSyncService issueCaseSync;
    @Autowired
    private ScreeningRecoveryService recovery;
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
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Eerste waarneming -----------------------------------------------------------------------

    /**
     * De eerste vaststelling van een nieuw probleem: één geval in {@code AWAITING_REVIEW}, met de
     * classificatie gedenormaliseerd uit de groep en met precies één {@code CREATED}-event dat naar de
     * waarnemende batch wijst.
     */
    @Test
    void createsOneAwaitingReviewCaseWithACreatedEventOnTheFirstObservation() {
        Fixture fixture = fixture("FIRST");
        long batchId = screen(fixture, "REF-1", unreadablePrices(1));

        Map<String, Object> group = group(batchId, ImportValueRules.CODE_PRICE_UNREADABLE);
        Map<String, Object> issueCase = singleCase(fixture);
        assertThat(issueCase.get("issue_code")).isEqualTo(ImportValueRules.CODE_PRICE_UNREADABLE);
        // De identiteit van het geval is die van de groep (D1); de signatuur wordt niet verzonnen.
        assertThat(issueCase.get("signature")).isEqualTo(group.get("signature"));
        assertThat(issueCase.get("status")).isEqualTo(IssueCaseStatus.AWAITING_REVIEW.name());
        assertThat(issueCase.get("severity")).isEqualTo(group.get("severity"));
        assertThat(issueCase.get("issue_domain")).isEqualTo(group.get("issue_domain"));
        assertThat(issueCase.get("control_level")).isEqualTo(group.get("control_level"));
        assertThat(issueCase.get("impact_scope")).isEqualTo(group.get("impact_scope"));
        assertThat(issueCase.get("incident_kind")).isEqualTo(IssueIncidentKind.GENERIC.name());
        assertThat(number(issueCase, "observation_count")).isEqualTo(1L);
        assertThat(number(issueCase, "total_occurrence_count")).isEqualTo(REPEATS);
        assertThat(number(issueCase, "first_seen_batch_id")).isEqualTo(batchId);
        assertThat(number(issueCase, "last_seen_batch_id")).isEqualTo(batchId);
        assertThat(number(issueCase, "last_seen_revision_id")).isEqualTo(fixture.revisionId());
        assertThat(number(issueCase, "reopen_count")).isZero();
        // Nog nooit een mens aan te pas gekomen: geen reden, geen tijdstip, geen beslisser.
        assertThat(issueCase.get("status_reason")).isNull();
        assertThat(issueCase.get("status_changed_at")).isNull();
        assertThat(issueCase.get("status_changed_by")).isNull();
        assertThat(issueCase.get("decision_revision_id")).isNull();

        long caseId = number(issueCase, "id");
        // De groeprij zélf is de waarneming: geen aparte koppeltabel, geen tweede waarheid.
        assertThat(number(group, "issue_case_id")).isEqualTo(caseId);
        assertThat(events(caseId)).singleElement().satisfies(event -> {
            assertThat(event.get("event_kind")).isEqualTo("CREATED");
            assertThat(event.get("previous_status")).isNull();
            assertThat(event.get("new_status")).isEqualTo(IssueCaseStatus.AWAITING_REVIEW.name());
            assertThat(event.get("source")).isEqualTo("SYSTEM");
            assertThat(event.get("changed_by")).isNull();
            assertThat(event.get("changed_by_subject")).isNull();
            assertThat(number(event, "observation_batch_id")).isEqualTo(batchId);
            assertThat((String) event.get("reason")).contains(String.valueOf(batchId));
        });
    }

    // --- Herhaling: nooit een tweede geval -------------------------------------------------------

    /**
     * R-CASE-01: dezelfde {@code (import_link_id, issue_code, signature)} in een tweede levering
     * verhoogt het aantal waarnemingen van het bestaande geval. {@code first_seen_*} blijft bij de
     * eerste batch, {@code last_seen_*} schuift op naar de tweede. Er komt geen event bij: de groeprij
     * zelf is de audit van die waarneming (par. 2, rij {@code AWAITING_REVIEW}).
     */
    @Test
    void countsASecondDeliveryOfTheSameIssueOnTheExistingCaseWithoutCreatingASecondOne() {
        Fixture fixture = fixture("AGAIN");
        long first = screen(fixture, "REF-1", unreadablePrices(1));
        long caseId = number(singleCase(fixture), "id");

        long second = screen(fixture, "REF-2", unreadablePrices(2));

        assertThat(cases(fixture)).hasSize(1);
        Map<String, Object> issueCase = singleCase(fixture);
        assertThat(number(issueCase, "id")).isEqualTo(caseId);
        assertThat(number(issueCase, "observation_count")).isEqualTo(2L);
        assertThat(number(issueCase, "total_occurrence_count")).isEqualTo(2L * REPEATS);
        assertThat(number(issueCase, "first_seen_batch_id")).isEqualTo(first);
        assertThat(number(issueCase, "last_seen_batch_id")).isEqualTo(second);
        assertThat(issueCase.get("status")).isEqualTo(IssueCaseStatus.AWAITING_REVIEW.name());
        assertThat(number(issueCase, "reopen_count")).isZero();
        assertThat(lastSeenAtLeast(caseId, "first_seen_at")).isTrue();
        // Beide waarnemingen wijzen naar hetzelfde geval.
        assertThat(number(group(first, ImportValueRules.CODE_PRICE_UNREADABLE), "issue_case_id"))
                .isEqualTo(caseId);
        assertThat(number(group(second, ImportValueRules.CODE_PRICE_UNREADABLE), "issue_case_id"))
                .isEqualTo(caseId);
        assertThat(events(caseId)).hasSize(1);
    }

    // --- Idempotentie ----------------------------------------------------------------------------

    /**
     * Dezelfde batch twee keer synchroniseren - de garantie waarop elke hervatte verwerking steunt.
     * Geen tweede geval, geen dubbele telling, geen tweede event, en niets in de bestaande
     * screeningtabellen.
     */
    @Test
    void changesNothingWhenTheSameBatchIsSynchronisedTwice() {
        Fixture fixture = fixture("IDEM");
        long batchId = screen(fixture, "REF-1", unreadablePrices(1));
        List<Map<String, Object>> before = cases(fixture);
        assertThat(before).hasSize(1);
        long caseId = number(before.get(0), "id");

        issueCaseSync.sync(batchId, fixture.linkId(), fixture.revisionId());
        issueCaseSync.sync(batchId, fixture.linkId(), fixture.revisionId());

        assertThat(cases(fixture)).hasSize(1);
        Map<String, Object> after = singleCase(fixture);
        // updated_at mag bijgewerkt zijn (er is opnieuw gemeten); elke meting moet identiek zijn.
        assertThat(number(after, "id")).isEqualTo(caseId);
        assertThat(number(after, "observation_count")).isEqualTo(number(before.get(0), "observation_count"));
        assertThat(number(after, "total_occurrence_count"))
                .isEqualTo(number(before.get(0), "total_occurrence_count"));
        assertThat(number(after, "first_seen_batch_id")).isEqualTo(number(before.get(0), "first_seen_batch_id"));
        assertThat(number(after, "last_seen_batch_id")).isEqualTo(number(before.get(0), "last_seen_batch_id"));
        assertThat(number(after, "reopen_count")).isZero();
        assertThat(events(caseId)).hasSize(1);
        assertThat(groupCount(batchId)).isEqualTo(1L);
    }

    // --- De heropeningsregel (par. 2) ------------------------------------------------------------

    /**
     * R-CASE-03: een correctie die door een nieuwe vaststelling tegengesproken wordt, is per definitie
     * niet afgehandeld. Het geval gaat terug naar {@code AWAITING_REVIEW} met {@code reopen_count + 1}
     * en een {@code SYSTEM}-event; de eerdere menselijke reden blijft onaangeroerd in haar eigen event
     * staan (R-CASE-04).
     */
    @Test
    void reopensACorrectedCaseOnANewObservationAndKeepsTheEarlierHumanReason() {
        Fixture fixture = fixture("CORR");
        screen(fixture, "REF-1", unreadablePrices(1));
        long caseId = number(singleCase(fixture), "id");
        decide(caseId, IssueCaseStatus.CORRECTED, fixture.revisionId());

        long second = screen(fixture, "REF-2", unreadablePrices(2));

        Map<String, Object> issueCase = singleCase(fixture);
        assertThat(issueCase.get("status")).isEqualTo(IssueCaseStatus.AWAITING_REVIEW.name());
        assertThat(number(issueCase, "reopen_count")).isEqualTo(1L);
        assertThat((String) issueCase.get("status_reason")).startsWith("REOPENED_RECURRENCE")
                .contains(String.valueOf(second));
        assertThat(issueCase.get("status_changed_at")).isNotNull();
        // Een systeemheropening draagt nooit een naam (par. 1).
        assertThat(issueCase.get("status_changed_by")).isNull();
        assertThat(issueCase.get("status_changed_by_subject")).isNull();
        // De revisie van de laatste MENSELIJKE beslissing blijft staan: een systeemheropening is er geen.
        assertThat(number(issueCase, "decision_revision_id")).isEqualTo(fixture.revisionId());
        // En de tellers lopen gewoon door.
        assertThat(number(issueCase, "observation_count")).isEqualTo(2L);
        assertThat(number(issueCase, "total_occurrence_count")).isEqualTo(2L * REPEATS);

        List<Map<String, Object>> history = events(caseId);
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("event_kind")).isEqualTo("CREATED");
        assertThat(history.get(1).get("source")).isEqualTo("HUMAN");
        assertThat(history.get(1).get("reason")).isEqualTo(HUMAN_REASON);
        assertThat(history.get(2)).satisfies(reopening -> {
            assertThat(reopening.get("event_kind")).isEqualTo("STATUS_CHANGE");
            assertThat(reopening.get("source")).isEqualTo("SYSTEM");
            assertThat(reopening.get("previous_status")).isEqualTo(IssueCaseStatus.CORRECTED.name());
            assertThat(reopening.get("new_status")).isEqualTo(IssueCaseStatus.AWAITING_REVIEW.name());
            assertThat(reopening.get("changed_by")).isNull();
            assertThat(number(reopening, "observation_batch_id")).isEqualTo(second);
            assertThat((String) reopening.get("reason")).startsWith("REOPENED_RECURRENCE");
        });
    }

    /**
     * R-CASE-02: een afwijzing onderdrukt een identieke herlevering zolang dezelfde regels gelden. Het
     * geval blijft {@code REJECTED} en er komt geen event bij - maar de tellers en {@code last_seen_at}
     * lopen wél door, zodat {@code last_seen_at > status_changed_at} de onderdrukking zichtbaar maakt
     * (par. 2, het kenmerk waarop S2-B3/F1 moet filteren).
     */
    @Test
    void keepsARejectedCaseSuppressedUnderTheSameRevisionButStillUpdatesTheCounters() {
        Fixture fixture = fixture("REJSAME");
        screen(fixture, "REF-1", unreadablePrices(1));
        long caseId = number(singleCase(fixture), "id");
        decide(caseId, IssueCaseStatus.REJECTED, fixture.revisionId());

        long second = screen(fixture, "REF-2", unreadablePrices(2));

        Map<String, Object> issueCase = singleCase(fixture);
        assertThat(issueCase.get("status")).isEqualTo(IssueCaseStatus.REJECTED.name());
        assertThat(number(issueCase, "reopen_count")).isZero();
        assertThat(issueCase.get("status_reason")).isEqualTo(HUMAN_REASON);
        assertThat(issueCase.get("status_changed_by")).isEqualTo(HUMAN_NAME);
        assertThat(number(issueCase, "observation_count")).isEqualTo(2L);
        assertThat(number(issueCase, "total_occurrence_count")).isEqualTo(2L * REPEATS);
        assertThat(number(issueCase, "last_seen_batch_id")).isEqualTo(second);
        assertThat(lastSeenAtLeast(caseId, "status_changed_at")).isTrue();
        assertThat(events(caseId)).hasSize(2);
    }

    /**
     * R-CASE-03: een andere {@code import_definition_revision} dan waaronder de afwijzing genomen is,
     * heft de onderdrukking op. De reden noemt beide revisies, zodat achteraf te zien is welke
     * regelwijziging de heropening veroorzaakte.
     */
    @Test
    void reopensARejectedCaseWhenTheObservationRunsUnderAnotherRevision() {
        Fixture fixture = fixture("REJNEW");
        screen(fixture, "REF-1", unreadablePrices(1));
        long caseId = number(singleCase(fixture), "id");
        long otherRevisionId = extraRevision(fixture);
        decide(caseId, IssueCaseStatus.REJECTED, otherRevisionId);

        long second = screen(fixture, "REF-2", unreadablePrices(2));

        Map<String, Object> issueCase = singleCase(fixture);
        assertThat(issueCase.get("status")).isEqualTo(IssueCaseStatus.AWAITING_REVIEW.name());
        assertThat(number(issueCase, "reopen_count")).isEqualTo(1L);
        assertThat((String) issueCase.get("status_reason")).startsWith("REOPENED_RULES_CHANGED")
                .contains(String.valueOf(otherRevisionId))
                .contains(String.valueOf(fixture.revisionId()));
        assertThat(issueCase.get("status_changed_by")).isNull();
        assertThat(number(issueCase, "decision_revision_id")).isEqualTo(otherRevisionId);
        assertThat(number(issueCase, "last_seen_batch_id")).isEqualTo(second);
        assertThat(number(issueCase, "last_seen_revision_id")).isEqualTo(fixture.revisionId());

        List<Map<String, Object>> history = events(caseId);
        assertThat(history).hasSize(3);
        assertThat(history.get(1).get("reason")).isEqualTo(HUMAN_REASON);
        assertThat(history.get(2).get("previous_status")).isEqualTo(IssueCaseStatus.REJECTED.name());
        assertThat(history.get(2).get("source")).isEqualTo("SYSTEM");
        assertThat((String) history.get(2).get("reason")).startsWith("REOPENED_RULES_CHANGED");
    }

    // --- Het geblokkeerde pad --------------------------------------------------------------------

    /**
     * Eén hookpunt dekt beide screeningpaden (ontwerp par. 0 bevinding 3): ook een levering die op een
     * dubbele identiteit strandt, krijgt haar behandelgevallen. Zonder die stap zou het zwaarste soort
     * levering - een geblokkeerde - juist geen geval opleveren.
     */
    @Test
    void alsoCreatesCasesForABlockedDelivery() {
        Fixture fixture = fixture("BLOCK");
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= REPEATS; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        // Twee keer dezelfde aanbiedingsidentiteit: nooit "laatste wint", de levering blokkeert.
        csv.append("ACME;G1;DUP;1,50;Hamer\n").append("ACME;G1;DUP;1,50;Hamer\n");
        long batchId = screen(fixture, "REF-1", csv.toString());

        ImportBatch batch = batches.findById(batchId).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(batch.getBlockedCode()).isNotNull();

        Map<String, Object> issueCase = singleCase(fixture);
        assertThat(issueCase.get("issue_code")).isEqualTo(ImportValueRules.CODE_PRICE_UNREADABLE);
        assertThat(issueCase.get("status")).isEqualTo(IssueCaseStatus.AWAITING_REVIEW.name());
        assertThat(number(issueCase, "observation_count")).isEqualTo(1L);
        assertThat(number(issueCase, "total_occurrence_count")).isEqualTo(REPEATS);
        assertThat(number(issueCase, "last_seen_batch_id")).isEqualTo(batchId);
        assertThat(events(number(issueCase, "id"))).hasSize(1);
    }

    // --- Herstelpad ------------------------------------------------------------------------------

    /**
     * Herstel van een onderbroken screening (ontwerp par. 3, laatste alinea): met de groepen verdwijnen
     * de waarnemingen. Een geval dat nooit een geldige waarneming gehad heeft en waaraan nog nooit een
     * mens geraakt heeft, verdwijnt mee. Een geval waar al een mens aan raakte, blijft staan met
     * {@code observation_count = 0} - zijn beslissing mag nooit door een technische onderbreking
     * verdwijnen.
     */
    @Test
    void recoveryRemovesAnUntouchedCaseAndKeepsACaseAHumanAlreadyDecidedOn() {
        Fixture fixture = fixture("REC");
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= REPEATS; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        for (int i = REPEATS + 1; i <= 2 * REPEATS; i++) {
            csv.append("ACME;;R").append(i).append(";1,50;Boormachine\n");
        }
        long batchId = screen(fixture, "REF-1", csv.toString());
        assertThat(cases(fixture)).hasSize(2);
        long untouched = caseIdOf(fixture, ImportValueRules.CODE_PRICE_UNREADABLE);
        long decided = caseIdOf(fixture, CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY);
        decide(decided, IssueCaseStatus.CORRECTED, fixture.revisionId());

        // De toestand na een process-kill halverwege het stagen laat zich niet nabootsen; ze wordt
        // rechtstreeks klaargezet (patroon ScreeningRecoveryServiceTest).
        ImportBatch batch = batches.findById(batchId).orElseThrow();
        batch.setStatus(ImportBatchStatus.SCREENING);
        batches.saveAndFlush(batch);

        assertThat(recovery.recover().failedBatchIds()).contains(batchId);

        assertThat(batches.findById(batchId).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(groupCount(batchId)).isZero();
        assertThat(caseExists(untouched)).isFalse();
        assertThat(eventCount(untouched)).isZero();
        Map<String, Object> kept = caseById(decided);
        assertThat(number(kept, "observation_count")).isZero();
        assertThat(number(kept, "total_occurrence_count")).isZero();
        assertThat(kept.get("status")).isEqualTo(IssueCaseStatus.CORRECTED.name());
        assertThat(kept.get("status_reason")).isEqualTo(HUMAN_REASON);
        // first_seen_at/last_seen_at zijn not null: de laatst bekende meting blijft staan in plaats van
        // dat de hertelling afbreekt of een tijdstip verzint.
        assertThat(kept.get("first_seen_at")).isNotNull();
        assertThat(kept.get("last_seen_at")).isNotNull();
        assertThat(cases(fixture)).hasSize(1);
    }

    // --- D3: geen enkel effect op de screening ---------------------------------------------------

    /**
     * D3 en ontwerp par. 4, laatste punt: het behandelgeval is administratief. De synchronisatie mag
     * {@code import_batch} (status, {@code validation_result}, élke teller) en élke
     * {@code import_mutation.status} niet aanraken. Bewezen door de volledige rij en alle
     * mutatiestatussen vóór en ná twee extra synchronisaties te vergelijken.
     */
    @Test
    void leavesTheBatchAndEveryMutationStatusUntouched() {
        Fixture fixture = fixture("D3");
        long batchId = screen(fixture, "REF-1", unreadablePrices(1));
        List<Map<String, String>> batchBefore = batchRow(batchId);
        List<Map<String, String>> mutationsBefore = mutationRows(batchId);
        assertThat(mutationsBefore).isNotEmpty();
        assertThat(cases(fixture)).hasSize(1);

        issueCaseSync.sync(batchId, fixture.linkId(), fixture.revisionId());
        issueCaseSync.sync(batchId, fixture.linkId(), fixture.revisionId());

        assertThat(batchRow(batchId)).isEqualTo(batchBefore);
        assertThat(mutationRows(batchId)).isEqualTo(mutationsBefore);
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private static final String HUMAN_REASON = "beoordeeld door een mens";
    private static final String HUMAN_NAME = "beoordelaar";

    /**
     * Twaalf identieke onleesbare prijzen (dus één groep) plus vijf geldige regels (dus wél mutaties).
     * De omschrijving verschilt per levering, zodat twee leveringen niet byte-identiek zijn; de
     * foutsignatuur blijft exact dezelfde.
     */
    private static String unreadablePrices(int delivery) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= REPEATS; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine-").append(delivery)
                    .append('\n');
        }
        for (int i = REPEATS + 1; i <= REPEATS + 5; i++) {
            csv.append("ACME;G1;R").append(i).append(";1,50;Hamer-").append(delivery).append('\n');
        }
        return csv.toString();
    }

    /** Bootst de menselijke beslissing van S2-B2 na; die service bestaat in deze bouwstap nog niet. */
    private void decide(long caseId, IssueCaseStatus status, Long decisionRevisionId) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update("update issue_case set status = ?, status_reason = ?, status_changed_at = ?, "
                        + "status_changed_by = ?, decision_revision_id = ?, updated_at = ? where id = ?",
                status.name(), HUMAN_REASON, now, HUMAN_NAME, decisionRevisionId, now, caseId);
        jdbc.update("insert into issue_case_event (issue_case_id, event_kind, previous_status, new_status, "
                        + "reason, source, changed_by, changed_at) "
                        + "values (?, 'STATUS_CHANGE', 'AWAITING_REVIEW', ?, ?, 'HUMAN', ?, ?)",
                caseId, status.name(), HUMAN_REASON, HUMAN_NAME, now);
    }

    private static final String CASE_COLUMNS = "id, import_link_id, issue_code, signature, severity, "
            + "issue_domain, control_level, impact_scope, incident_kind, price_component_code, "
            + "reference_type, status, observation_count, total_occurrence_count, first_seen_at, "
            + "last_seen_at, first_seen_batch_id, last_seen_batch_id, last_seen_revision_id, "
            + "reopen_count, status_reason, status_changed_at, status_changed_by, "
            + "status_changed_by_subject, decision_revision_id";

    private List<Map<String, Object>> cases(Fixture fixture) {
        return jdbc.queryForList("select " + CASE_COLUMNS + " from issue_case where import_link_id = ? "
                + "order by issue_code, signature", fixture.linkId());
    }

    private Map<String, Object> singleCase(Fixture fixture) {
        List<Map<String, Object>> found = cases(fixture);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private long caseIdOf(Fixture fixture, String issueCode) {
        List<Map<String, Object>> found = cases(fixture).stream()
                .filter(row -> issueCode.equals(row.get("issue_code"))).toList();
        assertThat(found).hasSize(1);
        return number(found.get(0), "id");
    }

    private Map<String, Object> caseById(long caseId) {
        return jdbc.queryForMap("select " + CASE_COLUMNS + " from issue_case where id = ?", caseId);
    }

    private boolean caseExists(long caseId) {
        Long count = jdbc.queryForObject("select count(*) from issue_case where id = ?", Long.class, caseId);
        return count != null && count > 0;
    }

    private List<Map<String, Object>> events(long caseId) {
        return jdbc.queryForList("select id, event_kind, previous_status, new_status, reason, source, "
                + "changed_by, changed_by_subject, observation_batch_id from issue_case_event "
                + "where issue_case_id = ? order by id", caseId);
    }

    private long eventCount(long caseId) {
        Long count = jdbc.queryForObject("select count(*) from issue_case_event where issue_case_id = ?",
                Long.class, caseId);
        return count == null ? 0L : count;
    }

    /** Vergelijken in SQL: zo hoeft geen enkele timestamp naar Java gemapt te worden. */
    private boolean lastSeenAtLeast(long caseId, String column) {
        Boolean later = jdbc.queryForObject("select last_seen_at >= " + column + " from issue_case "
                + "where id = ?", Boolean.class, caseId);
        return Boolean.TRUE.equals(later);
    }

    private Map<String, Object> group(long batchId, String issueCode) {
        return jdbc.queryForMap("select id, issue_code, signature, severity, issue_domain, control_level, "
                + "impact_scope, incident_kind, occurrence_count, issue_case_id from import_issue_group "
                + "where batch_id = ? and issue_code = ?", batchId, issueCode);
    }

    private long groupCount(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_issue_group where batch_id = ?",
                Long.class, batchId);
        return count == null ? 0L : count;
    }

    /**
     * Élke kolom van de batch die de screening vaststelt: status, eindoordeel, blokkade en alle
     * tellers. Wordt vóór en ná de synchronisatie vergeleken (D3).
     */
    private List<Map<String, String>> batchRow(long batchId) {
        return snapshot("select status, validation_result, blocked_code, blocked_reason, "
                + "raw_record_count, valid_record_count, rejected_record_count, duplicate_identity_count, "
                + "new_count, changed_count, unchanged_count, content_mutation_count, staged_row_count, "
                + "mutation_progress_row_number, bulk_incident_count, critical_issue_count, warning_count, "
                + "critical_line_count, identity_incident_count, awaiting_approval_count, "
                + "creation_candidate_count, creation_scope_count, creation_outcome, filtered_out_count, "
                + "error_before_filter_count from import_batch where id = ?", batchId);
    }

    private List<Map<String, String>> mutationRows(long batchId) {
        return snapshot("select id, status, status_reason, action_type, target_domain, issue_group_id "
                + "from import_mutation where batch_id = ? order by id", batchId);
    }

    /**
     * Elke waarde als tekst in een gewone {@link LinkedHashMap}: dan is de vergelijking vóór/ná
     * onafhankelijk van hoe het stuurprogramma een kolomtype naar Java afbeeldt.
     */
    private List<Map<String, String>> snapshot(String sql, Object... arguments) {
        return jdbc.queryForList(sql, arguments).stream().map(row -> {
            Map<String, String> copy = new LinkedHashMap<String, String>();
            row.forEach((column, value) ->
                    copy.put(column.toLowerCase(Locale.ROOT), String.valueOf(value)));
            return copy;
        }).toList();
    }

    private static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private long screen(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        return screening.screen(view.batch().batchId()).batchId();
    }

    /**
     * Een tweede, niet-actieve revisie van dezelfde definitie: enkel nodig als
     * {@code decision_revision_id} van een afwijzing, om "de regels zijn gewijzigd" te kunnen
     * vaststellen. Ze blijft {@code DRAFT}, zodat de intake nog steeds revisie 1 kiest.
     */
    private long extraRevision(Fixture fixture) {
        ImportDefinition definition = definitions.findById(fixture.definitionId()).orElseThrow();
        return revisions.saveAndFlush(newRevision(definition, 2)).getId();
    }

    private static ImportDefinitionRevision newRevision(ImportDefinition definition, int revisionNumber) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, revisionNumber,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
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
        // Deze test gaat niet over de leveringsdrempel (patroon IssueGroupingTest).
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        return revision;
    }

    private Fixture fixture(String prefix) {
        String unique = "IC" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = newRevision(definition, 1);
        revision.setStatus(RevisionStatus.ACTIVE);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        String libraryCode = unique.substring(0, Math.min(unique.length(), 20));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, libraryCode));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId(), definition.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId, long definitionId) {
    }
}
