package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bouwstap 3h-3 (ontwerp fase 3 par. 15.2/15.3, R-THR-01, beslissingslog 20/09): het
 * <b>creatiebeleid</b> van pass E4b en de mutatiestatussen die eruit volgen.
 *
 * <h2>De regel die hier bewezen wordt</h2>
 * Een import mag niet zomaar een halve catalogus aanmaken. Vóór er één mutatie geschreven wordt,
 * beantwoordt de screening één vraag en legt ze het antwoord vast op de batch:
 * <ul>
 *   <li>geen enkele creatie ⇒ {@code AUTOMATIC} (ook bij een lege bronstaat: er valt niets te
 *       beslissen);</li>
 *   <li>lege bronstaat mét creaties ⇒ {@code INITIAL_LOAD}; <b>alleen</b>
 *       {@code INITIAL_LOAD_REQUIRES_APPROVAL} en nooit ook een bulkcreatie, want zonder bestaande
 *       omvang bestaat er geen percentage;</li>
 *   <li>{@code kandidaten × 100 > creation_threshold_share_percent × omvang} ⇒
 *       {@code THRESHOLD_EXCEEDED} met {@code BULK_CREATION_INCIDENT}. <b>Exact op de grens is niet
 *       overschreden</b>: 100 van 10.000 bij 1% gaat door, 101 niet, en 100 van 9.999 ook niet;</li>
 *   <li>anders {@code AUTOMATIC}.</li>
 * </ul>
 * Er is geen absoluut aantal meer ({@code creation_threshold_absolute} wordt niet gebruikt): elke
 * drempel is altijd een percentage per revisie. Bij een kleine koppeling zet de beheerder dat
 * percentage hoger — ook dat wordt hier bewezen.
 *
 * <h2>Mutatiestatussen en precedentie</h2>
 * Vraagt het beleid goedkeuring, dan wacht élke {@code CREATE} ({@code AWAITING_APPROVAL}, met de
 * foutcode als reden) en blijft élke {@code UPDATE} {@code PLANNED}. Een vastgehouden identiteit
 * blijft {@code BLOCKED}: {@code BLOCKED} > {@code AWAITING_APPROVAL} > {@code PLANNED}.
 *
 * <h2>Hervatten</h2>
 * Het oordeel wordt nooit herberekend zodra het vastligt. Crasht de mutatiegeneratie en wijzigt de
 * bronstaat intussen, dan levert het hervatte deel exact dezelfde statussen op — anders zou de ene
 * helft van een levering wachten en de andere helft doorgaan.
 * <p>
 * De bronstaat wordt met JdbcTemplate gevuld, precies zoals {@code accept-baseline} dat doet. Elke
 * test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CreationThresholdTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String EAN_HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;EAN\n";
    private static final String USER = "tester@example.test";

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
    @Autowired
    private MockMvc mockMvc;

    // --- De grens zelf: exact 1% van 10.000 -------------------------------------------------------

    /**
     * Honderd nieuwe aanbiedingen naast tienduizend bestaande is exact 1%. De vergelijking is
     * {@code >} en niet {@code >=}: precies op de grens gaat de levering gewoon door, met
     * {@code PLANNED}-mutaties zoals vóór bouwstap 3h-3.
     */
    @Test
    void lettingExactlyOnePercentThroughAutomatically() {
        Fixture fixture = fixture("EXACT");
        Delivered delivered = deliver(fixture, "REF-1", newRows(100));
        seedSourceState(fixture, delivered, 10_000);

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.newCount()).isEqualTo(100L);
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.AUTOMATIC);
        assertThat(outcome.creationScopeCount()).isEqualTo(10_000L);
        assertThat(issueCodes(delivered.batchId())).isEmpty();
        assertThat(mutationStatuses(delivered.batchId()))
                .containsOnly(MutationStatus.PLANNED.name());
        assertThat(statusReasons(delivered.batchId())).containsOnly((String) null);
        // Regressie: de inhoudelijke telling verandert niet door het creatiebeleid.
        assertThat(outcome.contentMutationCount()).isEqualTo(100L);
    }

    /** Eén aanbieding meer is 1,01% en dus wél boven de drempel. */
    @Test
    void holdingEveryCreationWhenOneMoreOfferCrossesTheThreshold() {
        Fixture fixture = fixture("OVER");
        Delivered delivered = deliver(fixture, "REF-1", newRows(101));
        seedSourceState(fixture, delivered, 10_000);

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.THRESHOLD_EXCEEDED);
        assertThat(outcome.creationScopeCount()).isEqualTo(10_000L);
        assertThat(issueCodes(delivered.batchId()))
                .containsExactly(ImportIssueCatalog.BULK_CREATION_INCIDENT);
        // De melding draagt de aantallen, niet enkel een oordeel (meldingsstijl par. 15.12).
        Map<String, Object> issue = creationIssue(delivered.batchId());
        assertThat(issue.get("source_value")).isEqualTo("101");
        // De gehanteerde drempel, in de schaal van de kolom (numeric(24,12)).
        assertThat(new BigDecimal((String) issue.get("expected_value"))).isEqualByComparingTo("1");
        assertThat((String) issue.get("message")).contains("101").contains("10000").contains("1.01");
        assertThat(mutationStatuses(delivered.batchId()))
                .containsOnly(MutationStatus.AWAITING_APPROVAL.name());
        assertThat(statusReasons(delivered.batchId()))
                .containsOnly(ImportIssueCatalog.BULK_CREATION_INCIDENT);
        // Een wachtende creatie is en blijft een inhoudelijke mutatie.
        assertThat(outcome.contentMutationCount()).isEqualTo(101L);
        assertThat(outcome.newCount()).isEqualTo(101L);
    }

    /** Dezelfde honderd aanbiedingen tegen een omvang van 9.999: 100 × 100 &gt; 1 × 9.999. */
    @Test
    void holdingEveryCreationWhenTheScopeIsOneOfferSmaller() {
        Fixture fixture = fixture("SCOPE");
        Delivered delivered = deliver(fixture, "REF-1", newRows(100));
        seedSourceState(fixture, delivered, 9_999);

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.THRESHOLD_EXCEEDED);
        assertThat(outcome.creationScopeCount()).isEqualTo(9_999L);
        assertThat(mutationStatuses(delivered.batchId()))
                .containsOnly(MutationStatus.AWAITING_APPROVAL.name());
    }

    // --- Initialisatie ------------------------------------------------------------------------------

    /**
     * De eerste levering van een koppeling: er is geen omvang om een percentage tegen te meten, dus
     * is dit géén bulkcreatie maar een initialisatie. Precies één melding, en nooit allebei.
     */
    @Test
    void treatingTheFirstDeliveryOfALinkAsAnInitialLoadWithExactlyOneIssue() {
        Fixture fixture = fixture("INIT");
        Delivered delivered = deliver(fixture, "REF-1", newRows(3));

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.INITIAL_LOAD);
        assertThat(outcome.creationScopeCount()).isZero();
        assertThat(issueCodes(delivered.batchId()))
                .containsExactly(ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL);
        assertThat(issueCodes(delivered.batchId()))
                .doesNotContain(ImportIssueCatalog.BULK_CREATION_INCIDENT);
        assertThat((String) creationIssue(delivered.batchId()).get("message"))
                .contains("no accepted offer yet");
        assertThat(mutationStatuses(delivered.batchId()))
                .containsOnly(MutationStatus.AWAITING_APPROVAL.name());
        assertThat(statusReasons(delivered.batchId()))
                .containsOnly(ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL);
    }

    /**
     * Een lege bronstaat is op zich geen reden om iets te laten wachten: zonder creatiekandidaten
     * valt er niets goed te keuren. Hier wordt élke regel verworpen (onleesbare prijs), dus er komt
     * geen enkele kandidaat in de staging.
     */
    @Test
    void decidingAutomaticallyWhenThereIsNothingToCreateAtAll() {
        Fixture fixture = fixture("NOCAND");
        Delivered delivered = deliver(fixture, "REF-1", HEADER
                + "ACME;G1;R1;onleesbaar;Boormachine\n"
                + "ACME;G1;R2;onleesbaar;Hamer\n");

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.AUTOMATIC);
        assertThat(outcome.creationScopeCount()).isZero();
        assertThat(issueCodes(delivered.batchId()))
                .doesNotContain(ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL,
                        ImportIssueCatalog.BULK_CREATION_INCIDENT);
        assertThat(outcome.contentMutationCount()).isZero();
    }

    // --- De drempel is een parameter per revisie ----------------------------------------------------

    /**
     * Bij een kleine koppeling is één nieuwe aanbieding naast tien bestaande al 10%. Met de
     * standaard 1% wacht die creatie; zet de beheerder het percentage van díe revisie op 10, dan
     * ligt ze exact op de grens en gaat ze automatisch door (beslissingslog 20/09).
     */
    @Test
    void lettingASmallLinkCreateAutomaticallyWhenItsOwnPercentageIsRaised() {
        Fixture strict = fixture("SMALLDEF");
        Delivered strictDelivery = deliver(strict, "REF-1", newRows(1));
        seedSourceState(strict, strictDelivery, 10);

        assertThat(screening.screen(strictDelivery.batchId()).creationOutcome())
                .isEqualTo(CreationOutcome.THRESHOLD_EXCEEDED);

        Fixture relaxed = fixture("SMALLPCT");
        setCreationThreshold(relaxed, "10");
        Delivered relaxedDelivery = deliver(relaxed, "REF-1", newRows(1));
        seedSourceState(relaxed, relaxedDelivery, 10);

        ScreeningOutcome outcome = screening.screen(relaxedDelivery.batchId());

        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.AUTOMATIC);
        assertThat(outcome.creationScopeCount()).isEqualTo(10L);
        assertThat(mutationStatuses(relaxedDelivery.batchId()))
                .containsOnly(MutationStatus.PLANNED.name());
    }

    // --- Precedentie en de teller van de kandidaten -------------------------------------------------

    /**
     * Drie soorten regels in één levering boven de drempel: een vastgehouden identiteit blijft
     * {@code BLOCKED} (die wint altijd), een gewijzigde bestaande aanbieding blijft {@code PLANNED}
     * (een update is geen creatie) en de nieuwe aanbieding wacht op goedkeuring.
     */
    @Test
    void keepingBlockedAboveAwaitingApprovalAndLeavingUpdatesPlanned() {
        Fixture fixture = fixture("PRECED", true);
        Delivered first = deliver(fixture, "REF-1", EAN_HEADER
                + "ACME;G1;R1;1,50;Boormachine;E-1\n"
                + "ACME;G1;R2;2,25;Hamer;E-2\n");
        screening.screen(first.batchId());
        baseline.acceptBaseline(first.batchId(), USER, "nulmeting");

        // R1 krijgt een andere EAN (incident), R2 een andere prijs (update), R3 is nieuw (creatie).
        Delivered second = deliver(fixture, "REF-2", EAN_HEADER
                + "ACME;G1;R1;1,50;Boormachine;E-9\n"
                + "ACME;G1;R2;9,95;Hamer;E-2\n"
                + "ACME;G1;R3;3,00;Zaag;E-3\n");

        ScreeningOutcome outcome = screening.screen(second.batchId());

        // Eén creatiekandidaat tegen een omvang van twee: 100 > 1 × 2, dus boven de drempel.
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.THRESHOLD_EXCEEDED);
        assertThat(outcome.creationScopeCount()).isEqualTo(2L);
        assertThat(statusOf(second.batchId(), "R1")).isEqualTo(MutationStatus.BLOCKED.name());
        assertThat(reasonOf(second.batchId(), "R1"))
                .isEqualTo(MutationDao.BLOCKED_BY_IDENTITY_REFERENCE_INCIDENT);
        assertThat(statusOf(second.batchId(), "R2")).isEqualTo(MutationStatus.PLANNED.name());
        assertThat(reasonOf(second.batchId(), "R2")).isNull();
        assertThat(statusOf(second.batchId(), "R3"))
                .isEqualTo(MutationStatus.AWAITING_APPROVAL.name());
        assertThat(reasonOf(second.batchId(), "R3"))
                .isEqualTo(ImportIssueCatalog.BULK_CREATION_INCIDENT);

        // accept-baseline sluit de wachtende creatie én de geplande update af, maar raakt de
        // vastgehouden identiteit niet aan (ontwerp par. 15.4, minimale aanpassing van 3h-3).
        baseline.acceptBaseline(second.batchId(), USER, "aanvaarding met wachtende creatie");

        assertThat(statusOf(second.batchId(), "R3")).isEqualTo(MutationStatus.SKIPPED.name());
        assertThat(reasonOf(second.batchId(), "R3"))
                .isEqualTo(SourceStateBaselineService.SKIPPED_REASON);
        assertThat(statusOf(second.batchId(), "R2")).isEqualTo(MutationStatus.SKIPPED.name());
        assertThat(statusOf(second.batchId(), "R1")).isEqualTo(MutationStatus.BLOCKED.name());
    }

    /**
     * Een regel die door de referentiecontrole vastgehouden is, draagt geen {@code NEW} meer, maar
     * wordt na goedkeuring alsnog een creatie. Zolang ze geen bronstaatrij heeft, telt ze dus mee als
     * kandidaat — conservatief, want een bulkcreatie die ongezien doorgaat is erger dan een
     * goedkeuring te veel.
     * <p>
     * Hier: twee regels delen dezelfde EAN (beide vastgehouden, geen van beide bestaat al) plus één
     * gewone nieuwe regel. Met drempel 20% van tien bestaande aanbiedingen (twee toegelaten) is één
     * kandidaat te weinig om te overschrijden en zijn drie kandidaten dat wél.
     */
    @Test
    void countingHeldRowsWithoutASourceStateRowAsCreationCandidates() {
        Fixture fixture = fixture("HELD", true);
        setCreationThreshold(fixture, "20");
        Delivered delivered = deliver(fixture, "REF-1", EAN_HEADER
                + "ACME;G1;R1;1,50;Boormachine;E-1\n"
                + "ACME;G1;R2;2,25;Hamer;E-1\n"
                + "ACME;G1;R3;3,00;Zaag;E-3\n");
        seedSourceState(fixture, delivered, 10);

        ScreeningOutcome outcome = screening.screen(delivered.batchId());

        assertThat(outcome.identityIncidentCount()).isEqualTo(2L);
        assertThat(outcome.newCount()).isEqualTo(1L);
        // Zonder de vastgehouden regels zou 1 × 100 niet boven 20 × 10 liggen en was dit AUTOMATIC.
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.THRESHOLD_EXCEEDED);
        assertThat(creationIssue(delivered.batchId()).get("source_value")).isEqualTo("3");
        // De vastgehouden regels blijven geblokkeerd; enkel de echte creatie wacht op goedkeuring.
        assertThat(statusOf(delivered.batchId(), "R1")).isEqualTo(MutationStatus.BLOCKED.name());
        assertThat(statusOf(delivered.batchId(), "R2")).isEqualTo(MutationStatus.BLOCKED.name());
        assertThat(statusOf(delivered.batchId(), "R3"))
                .isEqualTo(MutationStatus.AWAITING_APPROVAL.name());
    }

    // --- Hervatten en idempotentie ------------------------------------------------------------------

    /**
     * Het oordeel ligt vóór de eerste chunk vast. Crasht de mutatiegeneratie en wijzigt de bronstaat
     * daarna zó dat een herberekening {@code AUTOMATIC} zou opleveren, dan levert het hervatte deel
     * nog steeds dezelfde statussen op. Zonder die regel zou de ene helft van een levering wachten en
     * de andere helft doorgaan.
     */
    @Test
    void keepingTheRecordedOutcomeWhenTheBatchIsResumedAfterTheSourceStateChanged() {
        Fixture fixture = fixture("RESUME");
        Delivered delivered = deliver(fixture, "REF-1", newRows(120));
        AtomicInteger chunk = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (chunk.incrementAndGet() == 2) {
                throw new UncheckedIOException(new IOException("simulated crash halfway the generation"));
            }
            return invocation.callRealMethod();
        }).when(mutations).insertContentMutations(any(), any(), any(), anyLong(), anyLong(), any());

        try {
            screening.screen(delivered.batchId());
            throw new AssertionError("the simulated crash did not propagate");
        } catch (UncheckedIOException expected) {
            assertThat(expected).hasRootCauseInstanceOf(IOException.class);
        }

        ImportBatch interrupted = batches.findById(delivered.batchId()).orElseThrow();
        assertThat(interrupted.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        // Pass E4b draaide vóór E5 en heeft haar oordeel al vastgelegd.
        assertThat(interrupted.getCreationOutcome()).isEqualTo(CreationOutcome.INITIAL_LOAD);
        assertThat(interrupted.getCreationScopeCount()).isZero();
        assertThat(mutationStatuses(delivered.batchId()))
                .containsOnly(MutationStatus.AWAITING_APPROVAL.name());

        // De bronstaat verandert tussen de crash en het hervatten, en de drempel wordt ruim gezet:
        // een herberekening zou nu AUTOMATIC zeggen.
        seedSourceState(fixture, delivered, 500);
        setCreationThreshold(fixture, "1000");
        Mockito.reset(mutations);

        ScreeningOutcome outcome = screening.continueMutating(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.INITIAL_LOAD);
        assertThat(outcome.creationScopeCount()).isZero();
        assertThat(outcome.contentMutationCount()).isEqualTo(120L);
        assertThat(mutationStatuses(delivered.batchId()))
                .containsOnly(MutationStatus.AWAITING_APPROVAL.name());
        assertThat(statusReasons(delivered.batchId()))
                .containsOnly(ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL);
        // En er staat nog steeds precies één melding: het hervatten verdubbelt niets.
        assertThat(issueRowCount(delivered.batchId(), ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL))
                .isEqualTo(1L);
    }

    /**
     * Zou de pass tóch een tweede keer moeten oordelen (het oordeel is gewist, bijvoorbeeld door een
     * herstelactie), dan schrijft ze geen tweede melding: de pass is idempotent, net als elke andere
     * samenvattende melding.
     */
    @Test
    void neverWritingTheSameCreationIssueTwiceWhenThePassRunsAgain() {
        Fixture fixture = fixture("IDEMP");
        Delivered delivered = deliver(fixture, "REF-1", newRows(4));
        screening.screen(delivered.batchId());
        assertThat(issueRowCount(delivered.batchId(), ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL))
                .isEqualTo(1L);

        // Terug naar "nog niet beoordeeld" en de batch terug op MUTATING: het volledige pad E4b → E5
        // draait opnieuw over dezelfde staging.
        jdbc.update("update import_batch set creation_outcome = null, creation_scope_count = null, "
                + "status = 'MUTATING', open_marker = true, finished_at = null, "
                + "mutation_progress_row_number = 0 where id = ?", delivered.batchId());

        ScreeningOutcome outcome = screening.continueMutating(delivered.batchId());

        assertThat(outcome.creationOutcome()).isEqualTo(CreationOutcome.INITIAL_LOAD);
        assertThat(issueRowCount(delivered.batchId(), ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL))
                .isEqualTo(1L);
        assertThat(outcome.contentMutationCount()).isEqualTo(4L);
        assertThat(mutationStatuses(delivered.batchId()))
                .containsOnly(MutationStatus.AWAITING_APPROVAL.name());
    }

    // --- Weergave -------------------------------------------------------------------------------------

    @Test
    void exposingTheOutcomeAndTheScopeOnTheBatchEndpoint() throws Exception {
        Fixture fixture = fixture("HTTP");
        Delivered delivered = deliver(fixture, "REF-1", newRows(2));
        seedSourceState(fixture, delivered, 10);

        // Vóór de screening is er niets beoordeeld: null, nooit stil AUTOMATIC of 0.
        mockMvc.perform(get("/api/catalog-import/batches/{id}", delivered.batchId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creationOutcome").value((Object) null))
                .andExpect(jsonPath("$.creationScopeCount").value((Object) null));

        screening.screen(delivered.batchId());

        mockMvc.perform(get("/api/catalog-import/batches/{id}", delivered.batchId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creationOutcome").value("THRESHOLD_EXCEEDED"))
                .andExpect(jsonPath("$.creationScopeCount").value(10));
        mockMvc.perform(get("/api/catalog-import/deliveries/{id}", delivered.deliveryId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.creationOutcome").value("THRESHOLD_EXCEEDED"))
                .andExpect(jsonPath("$.batch.creationScopeCount").value(10));
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private static String newRows(int count) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= count; i++) {
            csv.append("ACME;G1;N").append(i).append(";1,50;Boormachine\n");
        }
        return csv.toString();
    }

    /**
     * Vult de bronstaat met {@code count} actieve aanbiedingen van deze koppeling, zoals
     * {@code accept-baseline} dat doet. De identiteiten zijn bewust kunstmatig ({@code SEED-i}) en
     * hun hash is een teller: ze mogen nooit botsen met de identiteiten van de levering, want dan
     * zouden de geleverde regels {@code CHANGED} worden in plaats van {@code NEW}.
     */
    private void seedSourceState(Fixture fixture, Delivered delivered, int count) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.batchUpdate("insert into catalog_source_state (import_link_id, identity_hash, "
                        + "identity_supplier, identity_supplier_group, identity_supplier_reference, "
                        + "identity_discount_state, identity_profile_kind, article_fingerprint, "
                        + "price_fingerprint, combined_fingerprint, base_price, base_price_currency, "
                        + "state_origin, last_change_delivery_id, last_change_batch_id, active, "
                        + "accepted_by, accepted_at, created_at, updated_at) "
                        + "values (?, ?, 'ACME', 'G1', ?, 'NOT_MAPPED', 'THREE_PART', ?, ?, ?, 1.5, null, "
                        + "'BASELINE_ACCEPTED', ?, ?, true, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {

                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        byte[] hash = seedHash(index);
                        statement.setLong(1, fixture.linkId());
                        statement.setBytes(2, hash);
                        statement.setString(3, "SEED-" + index);
                        statement.setBytes(4, hash);
                        statement.setBytes(5, hash);
                        statement.setBytes(6, hash);
                        statement.setLong(7, delivered.deliveryId());
                        statement.setLong(8, delivered.batchId());
                        statement.setString(9, USER);
                        statement.setObject(10, now);
                        statement.setObject(11, now);
                        statement.setObject(12, now);
                    }

                    @Override
                    public int getBatchSize() {
                        return count;
                    }
                });
    }

    /** Een kunstmatige 32-byte hash; uniek per rij en nooit gelijk aan een echte SHA-256. */
    private static byte[] seedHash(int index) {
        byte[] hash = new byte[32];
        hash[0] = (byte) (index >>> 24);
        hash[1] = (byte) (index >>> 16);
        hash[2] = (byte) (index >>> 8);
        hash[3] = (byte) index;
        return hash;
    }

    private void setCreationThreshold(Fixture fixture, String percent) {
        ImportDefinitionRevision revision = revisions.findById(fixture.revisionId()).orElseThrow();
        revision.setCreationThresholdSharePercent(new BigDecimal(percent));
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

    private Map<String, Object> creationIssue(long batchId) {
        return jdbc.queryForMap("select issue_code, source_value, expected_value, message, row_number, "
                + "severity, control_level, impact_scope from import_row_issue where batch_id = ? "
                + "and issue_code in (?, ?)", batchId, ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL,
                ImportIssueCatalog.BULK_CREATION_INCIDENT);
    }

    private List<String> mutationStatuses(long batchId) {
        return jdbc.queryForList("select status from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", String.class, batchId);
    }

    private List<String> statusReasons(long batchId) {
        return jdbc.queryForList("select status_reason from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", String.class, batchId);
    }

    private String statusOf(long batchId, String reference) {
        return jdbc.queryForObject("select status from import_mutation where batch_id = ? "
                        + "and action_type in ('CREATE', 'UPDATE') and identity_supplier_reference = ?",
                String.class, batchId, reference);
    }

    private String reasonOf(long batchId, String reference) {
        return jdbc.queryForObject("select status_reason from import_mutation where batch_id = ? "
                        + "and action_type in ('CREATE', 'UPDATE') and identity_supplier_reference = ?",
                String.class, batchId, reference);
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
        String unique = "CT" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        if (withEan) {
            revision.setRecordCanonicalisationVersion(2);
        }
        // Bouwstap 3h-4: deze test gaat niet over de drempel op de records ter beoordeling. Met de
        // productiedefault van 1% zou een kleine fixture met een enkele kritieke lijn of een
        // vastgehouden identiteit nu geblokkeerd worden; het percentage wordt daarom PER TEST op 100
        // gezet, zodat hier exact het gedrag van vóór bouwstap 3h-4 geldt. De productiedefault zelf
        // blijft 1 procent - ThresholdBlockingTest bewijst die.
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
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
