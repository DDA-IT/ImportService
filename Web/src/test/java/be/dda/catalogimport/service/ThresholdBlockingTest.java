package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.CreationOutcome;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Bouwstap 3h-4 (ontwerp fase 3 par. 15.2/15.3, beslissingslog 20/09): de twee
 * <b>leveringsdrempels</b> van pass E4b en het blokkeerpad dat eruit volgt.
 *
 * <h2>De regels die hier bewezen worden</h2>
 * <ul>
 *   <li><b>Records ter beoordeling</b> = kritieke lijnen + vastgehouden identiteitsincidenten, tegen
 *       {@code max_critical_share_percent} (default 1%) van de records in scope
 *       ({@code raw_record_count − filtered_out_count}). Exact op de grens gaat de levering door
 *       (2 van 200), één record erboven niet (3 van 200).</li>
 *   <li><b>Verworpen regels</b> tegen {@code max_rejected_share_percent}: 50 van 1000 bij 5% gaat
 *       door, 51 niet. Is de drempel niet geconfigureerd ({@code null}, de standaard), dan blokkeert
 *       ze <b>nooit</b> — nooit als 0 gelezen.</li>
 *   <li>Zijn beide overschreden, dan worden <b>beide</b> meldingen geschreven maar draagt
 *       {@code blocked_code} de kritieke drempel (precedentie par. 15.3).</li>
 *   <li>Een geblokkeerde levering heeft <b>nul</b> inhoudelijke mutaties, precies <b>één</b> marker
 *       met {@code outcome=BLOCKED;blockedCode=...}, {@code validation_result = BLOCKING}, en behoudt
 *       haar staging, haar meldingen en haar {@code IDENTITY_REFERENCE_INCIDENT}-mutaties als
 *       bewijs.</li>
 *   <li>De pass is idempotent en hervatbaar: tweemaal draaien levert één melding per code en één
 *       marker op.</li>
 * </ul>
 *
 * <h2>Wat hier bewust NIET gebeurt</h2>
 * Een levering <b>binnen</b> de drempel gedraagt zich exact als vóór deze bouwstap: dezelfde
 * mutaties, dezelfde statussen, geen enkele extra melding. De herziening van {@code validation_result}
 * naar {@code REVIEW_REQUIRED} hoort bij bouwstap 3h-5 en wordt hier niet geraden.
 * <p>
 * De creatiedrempel (pass E4b, eerste deel) draait vóór deze drempels en legt haar oordeel dus óók
 * vast op een levering die daarna blokkeert. Dat is zichtbaar in de asserties en bewust: een
 * vastgelegd oordeel wordt nooit achteraf uitgewist.
 * <p>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class ThresholdBlockingTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String EAN_HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;EAN\n";
    private static final String USER = "tester@example.test";

    /** Een onleesbare prijs verwerpt de regel en is een ERROR op een kritieke kolom (par. 15.1). */
    private static final String UNREADABLE_PRICE = "onleesbaar";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @MockitoSpyBean
    private MutationDao mutations;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private SourceStateBaselineService baseline;
    @Autowired
    private SourceOrganisationRepository sourceOrganisations;
    @Autowired
    private ImportDefinitionRepository definitions;
    @Autowired
    private ImportDefinitionRevisionRepository revisions;
    @Autowired
    private ImportFieldCatalogRepository fieldCatalog;
    @Autowired
    private ImportFieldMappingRepository fieldMappings;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;

    // --- De drempel op de records ter beoordeling ---------------------------------------------

    /**
     * Twee kritieke lijnen op tweehonderd records in scope is exact 1%. De vergelijking is {@code >}
     * en niet {@code >=}: precies op de grens gaat de levering gewoon door, met haar volledige
     * mutatielijst en zonder één extra melding.
     */
    @Test
    void lettingExactlyOnePercentOfRecordsNeedingReviewThrough() {
        Fixture fixture = fixture("EXACT");
        Delivered delivered = deliver(fixture, "REF-1", rows(200, 2));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rawRecordCount()).isEqualTo(200L);
        assertThat(outcome.criticalLineCount()).isEqualTo(2L);
        assertThat(outcome.identityIncidentCount()).isZero();
        assertThat(outcome.blockedCode()).isNull();
        assertThat(issueCodes(delivered.batchId()))
                .doesNotContain(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED,
                        ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED);
        // Regressie: de 198 leesbare regels leveren gewoon hun mutaties op.
        assertThat(outcome.contentMutationCount()).isEqualTo(198L);
        assertThat(markerSummaries(delivered.batchId())).hasSize(1);
        assertThat(markerSummaries(delivered.batchId()).get(0)).contains("outcome=SCREENED");
    }

    /**
     * Eén kritieke lijn meer is 1,5% en dus wél boven de drempel: de levering is als geheel
     * onbetrouwbaar en stopt volledig — nul inhoudelijke mutaties, één marker, en alles wat
     * vastgesteld is blijft bewaard als bewijs.
     */
    @Test
    void blockingTheWholeDeliveryWhenOneMoreRecordNeedsReview() {
        Fixture fixture = fixture("OVER");
        Delivered delivered = deliver(fixture, "REF-1", rows(200, 3));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.BLOCKING);
        assertThat(outcome.blockedCode())
                .isEqualTo(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
        assertThat(outcome.blockedReason())
                .contains(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
        assertThat(outcome.criticalLineCount()).isEqualTo(3L);
        assertThat(outcome.contentMutationCount()).isZero();
        assertThat(contentMutationCount(delivered.batchId())).isZero();

        // De melding draagt de aantallen, het percentage en de scope (meldingsstijl par. 15.12).
        Map<String, Object> issue =
                thresholdIssue(delivered.batchId(), ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
        assertThat(issue.get("source_value")).isEqualTo("3");
        assertThat(new BigDecimal((String) issue.get("expected_value"))).isEqualByComparingTo("1");
        assertThat((String) issue.get("message")).contains("3 critical lines").contains("200")
                .contains("1.5");
        assertThat(issue.get("severity")).isEqualTo("BLOCKING");
        assertThat(issue.get("control_level")).isEqualTo("DELIVERY");
        assertThat(issue.get("impact_scope")).isEqualTo("DELIVERY");

        // Bewijsmateriaal blijft: staging en de individuele meldingen van de verworpen regels.
        assertThat(outcome.stagedRowCount()).isEqualTo(197L);
        assertThat(stagedRowCount(delivered.batchId())).isEqualTo(197L);
        assertThat(issueCodes(delivered.batchId()))
                .contains(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);

        // Precies één marker, met de blokkeercode erin.
        List<String> markers = markerSummaries(delivered.batchId());
        assertThat(markers).hasSize(1);
        assertThat(markers.get(0)).contains("outcome=BLOCKED;blockedCode="
                + ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED)
                .contains("validationResult=BLOCKING");

        // De TaskRun is netjes afgerond: een blokkade is een uitkomst, geen technische storing.
        assertThat(taskRunStatus(delivered.batchId())).isEqualTo("COMPLETED");
        // Het creatiebeleid draaide vóór deze drempel en zijn oordeel blijft vastgelegd staan.
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.INITIAL_LOAD);
    }

    /**
     * Vastgehouden identiteitsincidenten tellen mee in dezelfde drempel: ze zijn geen fout in de
     * regel, maar ze vragen wél beoordeling. Twee incidenten op twintig records is exact 10% en gaat
     * door; komt er één kritieke lijn bij, dan is het 15% en stopt de levering.
     * <p>
     * De vastgehouden regels houden hun {@code IDENTITY_REFERENCE_INCIDENT}-mutatie in
     * {@code AWAITING_APPROVAL}: die is geschreven door pass E2 en blijft als bewijs staan (par. 15.3).
     */
    @Test
    void countingHeldIdentityIncidentsTowardsTheSameThreshold() {
        Fixture fixture = fixture("HELD", true);
        setCriticalThreshold(fixture, "10");
        Delivered first = deliver(fixture, "REF-1", eanRows(20, 0, 0));
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting");

        // Twee gewijzigde EAN's: exact 10% van twintig records, dus precies op de grens.
        Delivered second = deliver(fixture, "REF-2", eanRows(20, 2, 0));
        ScreeningOutcome atTheBoundary = screening.screen(second.batchId());

        assertThat(atTheBoundary.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(atTheBoundary.identityIncidentCount()).isEqualTo(2L);
        assertThat(atTheBoundary.criticalLineCount()).isZero();
        assertThat(issueCodes(second.batchId()))
                .doesNotContain(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);

        // Dezelfde twee incidenten plus één onleesbare prijs: 3 van 20 is 15%.
        Delivered third = deliver(fixture, "REF-3", eanRows(20, 2, 1));
        ScreeningOutcome outcome = screening.screen(third.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
        assertThat(outcome.identityIncidentCount()).isEqualTo(2L);
        assertThat(outcome.criticalLineCount()).isEqualTo(1L);
        assertThat((String) thresholdIssue(third.batchId(),
                ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED).get("message"))
                .contains("1 critical lines").contains("2 held identity incidents").contains("'3'");
        // Nul inhoudelijke mutaties, maar de incidentmutaties van E2 blijven staan als bewijs.
        assertThat(contentMutationCount(third.batchId())).isZero();
        assertThat(incidentMutationStatuses(third.batchId()))
                .hasSize(2).containsOnly(MutationStatus.AWAITING_APPROVAL.name());
    }

    // --- De drempel op de verworpen regels -------------------------------------------------------

    /**
     * Vijftig verworpen regels van duizend in scope is exact 5%: precies op de grens gaat de levering
     * door. De kritieke drempel staat hier op 100%, zodat uitsluitend de verwerpingsdrempel getest
     * wordt — dezelfde regels zijn immers óók kritieke lijnen.
     */
    @Test
    void lettingExactlyTheConfiguredRejectionShareThrough() {
        Fixture fixture = fixture("REJEXACT");
        setCriticalThreshold(fixture, "100");
        setRejectedThreshold(fixture, "5");
        Delivered delivered = deliver(fixture, "REF-1", rows(1000, 50));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(50L);
        assertThat(issueCodes(delivered.batchId()))
                .doesNotContain(ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED);
        assertThat(outcome.contentMutationCount()).isEqualTo(950L);
    }

    /** Eén verworpen regel meer is 5,1% en stopt de volledige levering. */
    @Test
    void blockingWhenOneMoreRejectedLineCrossesTheRejectionThreshold() {
        Fixture fixture = fixture("REJOVER");
        setCriticalThreshold(fixture, "100");
        setRejectedThreshold(fixture, "5");
        Delivered delivered = deliver(fixture, "REF-1", rows(1000, 51));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.BLOCKING);
        assertThat(outcome.contentMutationCount()).isZero();
        Map<String, Object> issue = thresholdIssue(delivered.batchId(),
                ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED);
        assertThat(issue.get("source_value")).isEqualTo("51");
        assertThat((String) issue.get("message")).contains("1000").contains("5.1");
        assertThat(issueCodes(delivered.batchId()))
                .doesNotContain(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
        assertThat(markerSummaries(delivered.batchId())).hasSize(1);
    }

    /**
     * De verwerpingsdrempel is standaard <b>niet</b> geconfigureerd ({@code null}) en wordt dan nooit
     * overschreden — nooit als 0 gelezen, want dan zou één verworpen regel de levering stoppen
     * (aanname A18). Negen van de tien regels verworpen blokkeert hier dus niets.
     */
    @Test
    void neverBlockingOnARejectionThresholdThatIsNotConfigured() {
        Fixture fixture = fixture("REJNULL");
        setCriticalThreshold(fixture, "100");
        Delivered delivered = deliver(fixture, "REF-1", rows(10, 9));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(revisions.findById(fixture.revisionId()).orElseThrow()
                .getMaxRejectedSharePercent()).isNull();
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(9L);
        assertThat(issueCodes(delivered.batchId()))
                .doesNotContain(ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED);
    }

    // --- Allebei overschreden ---------------------------------------------------------------------

    /**
     * Tien onleesbare prijzen op honderd records zijn tegelijk 10% kritieke lijnen (drempel 1%) en 10%
     * verworpen regels (drempel 5%). Allebei waar, dus allebei gemeld; {@code blocked_code} draagt de
     * kritieke drempel, want die gaat over de betrouwbaarheid van de records die wél door de validatie
     * kwamen (precedentie par. 15.3).
     */
    @Test
    void recordingBothThresholdsButBlockingOnTheCriticalOne() {
        Fixture fixture = fixture("BOTH");
        setRejectedThreshold(fixture, "5");
        Delivered delivered = deliver(fixture, "REF-1", rows(100, 10));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
        assertThat(issueCodes(delivered.batchId()))
                .contains(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED,
                        ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED);
        assertThat(issueRowCount(delivered.batchId(),
                ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED)).isEqualTo(1L);
        assertThat(issueRowCount(delivered.batchId(),
                ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED)).isEqualTo(1L);
        assertThat(contentMutationCount(delivered.batchId())).isZero();
    }

    // --- Idempotentie en hervatten ----------------------------------------------------------------

    /**
     * Draait pass E4b een tweede keer over dezelfde staging (een hervatte of herstelde batch), dan
     * komt er geen tweede melding en geen tweede marker bij: elke melding wordt enkel geschreven als
     * haar code er nog niet is, en de markersleutel is uniek.
     */
    @Test
    void neverWritingTheSameThresholdIssueOrMarkerTwiceWhenThePassRunsAgain() {
        Fixture fixture = fixture("IDEMP");
        setRejectedThreshold(fixture, "5");
        Delivered delivered = deliver(fixture, "REF-1", rows(100, 10));
        assertThat(screening.screen(delivered.batchId()).status()).isEqualTo(ImportBatchStatus.BLOCKED);

        // Terug op MUTATING: het volledige pad E4 → E4b → blokkade draait opnieuw.
        jdbc.update("update import_batch set status = 'MUTATING', open_marker = true, "
                + "finished_at = null where id = ?", delivered.batchId());

        ScreeningOutcome outcome = screening.continueMutating(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
        assertThat(issueRowCount(delivered.batchId(),
                ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED)).isEqualTo(1L);
        assertThat(issueRowCount(delivered.batchId(),
                ImportIssueCatalog.REJECTED_RECORD_THRESHOLD_EXCEEDED)).isEqualTo(1L);
        assertThat(markerSummaries(delivered.batchId())).hasSize(1);
        assertThat(contentMutationCount(delivered.batchId())).isZero();
    }

    /**
     * Valt de verwerking weg tussen de drempelmelding en de afrondende blokkeertransactie, dan blijft
     * de batch op {@code MUTATING} staan met haar melding al vastgelegd. {@code continueMutating}
     * levert exact dezelfde blokkade op, zonder iets te verdubbelen — de melding wordt bewust in een
     * eigen transactie geschreven, juist om dit te kunnen.
     */
    @Test
    void reachingTheSameBlockadeAfterACrashBetweenTheIssueAndTheFinalTransaction() {
        Fixture fixture = fixture("RESUME");
        Delivered delivered = deliver(fixture, "REF-1", rows(100, 10));
        Mockito.doThrow(new UncheckedIOException(new IOException("simulated crash before the marker")))
                .when(mutations).insertMarker(any(), any(), any());

        try {
            screening.screen(delivered.batchId());
            throw new AssertionError("the simulated crash did not propagate");
        } catch (UncheckedIOException expected) {
            assertThat(expected).hasRootCauseInstanceOf(IOException.class);
        }

        ImportBatch interrupted = batches.findById(delivered.batchId()).orElseThrow();
        assertThat(interrupted.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        // De drempelmelding staat er al; de blokkade zelf nog niet.
        assertThat(issueRowCount(delivered.batchId(),
                ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED)).isEqualTo(1L);
        assertThat(interrupted.getBlockedCode()).isNull();
        assertThat(markerSummaries(delivered.batchId())).isEmpty();

        Mockito.reset(mutations);
        ScreeningOutcome outcome = screening.continueMutating(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED);
        assertThat(issueRowCount(delivered.batchId(),
                ImportIssueCatalog.CRITICAL_RECORD_THRESHOLD_EXCEEDED)).isEqualTo(1L);
        assertThat(markerSummaries(delivered.batchId())).hasSize(1);
        assertThat(contentMutationCount(delivered.batchId())).isZero();
    }

    // --- Helpers ------------------------------------------------------------------------------------

    /**
     * {@code count} records waarvan de eerste {@code rejected} een onleesbare prijs dragen. Zo'n regel
     * wordt verworpen en is een kritieke lijn: de basisprijs is standaard een kritieke kolom.
     */
    private static String rows(int count, int rejected) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= count; i++) {
            csv.append("ACME;G1;R").append(i).append(';')
                    .append(i <= rejected ? UNREADABLE_PRICE : "1,50")
                    .append(";Boormachine\n");
        }
        return csv.toString();
    }

    /**
     * Dezelfde records mét een EAN-kolom. De eerste {@code changedEans} regels krijgen een andere EAN
     * dan in de aanvaarde nulmeting (elk een vastgehouden identiteitsincident) en de laatste
     * {@code rejected} regels een onleesbare prijs (elk een kritieke lijn) — bewust aan de andere kant
     * van het bestand, zodat de twee verschijnselen nooit op dezelfde regel vallen.
     */
    private static String eanRows(int count, int changedEans, int rejected) {
        StringBuilder csv = new StringBuilder(EAN_HEADER);
        for (int i = 1; i <= count; i++) {
            csv.append("ACME;G1;R").append(i).append(';')
                    .append(i > count - rejected ? UNREADABLE_PRICE : "1,50")
                    .append(";Boormachine;")
                    .append(i <= changedEans ? "E-9" + i : "E-" + i)
                    .append('\n');
        }
        return csv.toString();
    }

    private void setCriticalThreshold(Fixture fixture, String percent) {
        ImportDefinitionRevision revision = revisions.findById(fixture.revisionId()).orElseThrow();
        revision.setMaxCriticalSharePercent(new BigDecimal(percent));
        revisions.saveAndFlush(revision);
    }

    private void setRejectedThreshold(Fixture fixture, String percent) {
        ImportDefinitionRevision revision = revisions.findById(fixture.revisionId()).orElseThrow();
        revision.setMaxRejectedSharePercent(new BigDecimal(percent));
        revisions.saveAndFlush(revision);
    }

    private List<String> issueCodes(long batchId) {
        return jdbc.queryForList("select distinct issue_code from import_row_issue where batch_id = ? "
                + "order by issue_code", String.class, batchId);
    }

    private long issueRowCount(long batchId, String issueCode) {
        Long count = jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and issue_code = ?", Long.class, batchId, issueCode);
        return count == null ? 0L : count;
    }

    private Map<String, Object> thresholdIssue(long batchId, String issueCode) {
        return jdbc.queryForMap("select issue_code, source_value, expected_value, message, row_number, "
                + "severity, control_level, impact_scope from import_row_issue where batch_id = ? "
                + "and issue_code = ?", batchId, issueCode);
    }

    private long contentMutationCount(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", Long.class, batchId);
        return count == null ? 0L : count;
    }

    private List<String> incidentMutationStatuses(long batchId) {
        return jdbc.queryForList("select status from import_mutation where batch_id = ? "
                + "and action_type = 'IDENTITY_REFERENCE_INCIDENT'", String.class, batchId);
    }

    private List<String> markerSummaries(long batchId) {
        return jdbc.queryForList("select result_summary from import_mutation where batch_id = ? "
                + "and action_type = 'IMPORT_MARKER'", String.class, batchId);
    }

    private long stagedRowCount(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_candidate_stage where batch_id = ?",
                Long.class, batchId);
        return count == null ? 0L : count;
    }

    private String taskRunStatus(long batchId) {
        List<String> statuses = jdbc.queryForList("select run.status from task_run run "
                + "join import_batch batch on batch.task_run_id = run.id where batch.id = ?",
                String.class, batchId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private Delivered deliver(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, USER, null, null, "levering.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        return new Delivered(deliveries.findById(view.deliveryId()).orElseThrow().getId(),
                view.batch().batchId());
    }

    private Fixture fixture(String prefix) {
        return fixture(prefix, false);
    }

    /** @param withEan mapt de EAN-kolom als kritieke koppelreferentie (canonicalisatieversie 2) */
    private Fixture fixture(String prefix, boolean withEan) {
        String unique = "TB" + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(organisation,
                unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
        ImportDefinitionRevision revision = new ImportDefinitionRevision(definition, 1,
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
        // Bewust géén eigen drempels: deze test gaat juist over de productiedefaults
        // (max_critical_share_percent = 1, max_rejected_share_percent = null). Waar een test een
        // andere grens nodig heeft, zet ze die expliciet.
        if (withEan) {
            revision.setRecordCanonicalisationVersion(2);
        }
        revision.setStatus(RevisionStatus.ACTIVE);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        if (withEan) {
            ImportFieldCatalogEntry target = fieldCatalog.findById("EAN").orElseThrow();
            ImportFieldMapping mapping = new ImportFieldMapping(stored, 1, target,
                    FieldValueKind.SOURCE_FIELD, target.getDataType(), target.getDefaultOwner(),
                    target.getIdentityClass());
            mapping.setSourceReference("EAN");
            mapping.setReferenceType(target.getReferenceType());
            fieldMappings.saveAndFlush(mapping);
        }

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        // Een eigen bibliotheek per test: een kritieke koppelreferentie is uniek per bibliotheek.
        String libraryCode = unique.substring(0, Math.min(unique.length(), 20));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, libraryCode));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId(), stored.getId());
    }

    private record Fixture(long taskId, long linkId, long revisionId) {
    }

    private record Delivered(long deliveryId, long batchId) {
    }
}
