package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

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
import be.dda.catalogimport.service.support.PriceDeviationEvaluator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
 * Bouwstap 3h-5 (ontwerp fase 3 par. 15.3, R-PRI-14, R-REF-07): wat een <b>bulkincident</b> met de
 * mutaties en met de tellers doet.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>Pass E5b.</b> Draagt een levering een {@code BULK_PRICE_INCIDENT}, dan wacht élke
 *       geplande {@code UPDATE} met een prijsdeel in haar domeinmasker op goedkeuring. Bewust
 *       over-inclusief; een {@code BLOCKED} mutatie blijft {@code BLOCKED} en een update zonder
 *       prijswijziging blijft {@code PLANNED}. Geen enkel bedrag wordt aangeraakt.</li>
 *   <li><b>Hervatten.</b> Een crash vóór of ná E5b levert na {@code continueMutating} exact dezelfde
 *       statussen op, met één marker en zonder dubbele wijziging.</li>
 *   <li><b>Ongecapte tellers.</b> {@code critical_issue_count} en {@code warning_count} tellen het
 *       <b>werkelijke</b> aantal voorvallen uit de issuegroepen. Bij 250 voorvallen en een
 *       voorbeeldcap van 200 staat er 250 en niet 200 — het aantal bewaarde voorbeeldrijen is 200.
 *   </li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class BulkIncidentJudgementTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;EAN\n";
    private static final String USER = "tester@example.test";

    /** De voorbeeldcap per foutcode ({@code max-sample-rows-per-code}), hier de productiedefault. */
    private static final int SAMPLE_CAP = DeliveryScreeningService.DEFAULT_MAX_SAMPLE_ROWS_PER_CODE;

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

    // --- Pass E5b: een bulkprijsincident laat geen prijswijziging ongezien doorgaan --------------

    @Test
    void aBulkPriceIncidentHoldsEveryPlannedPriceUpdateButNothingElse() {
        Fixture fixture = acceptedBaseline("BULKPRICE");

        ScreeningOutcome outcome = screenSecond(fixture, bulkPriceDelivery());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(issueCodes(outcome.batchId())).contains(ImportIssueCatalog.BULK_PRICE_INCIDENT);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);

        // De tien prijswijzigingen wachten, met de bulkcode als reden.
        for (int i = 1; i <= 10; i++) {
            assertThat(statusOf(outcome.batchId(), "R" + i))
                    .as("R%d", i).isEqualTo(MutationStatus.AWAITING_APPROVAL.name());
            assertThat(reasonOf(outcome.batchId(), "R" + i))
                    .as("R%d", i).isEqualTo(ImportIssueCatalog.BULK_PRICE_INCIDENT);
        }
        // Een update zonder prijsdeel blijft gewoon gepland.
        assertThat(statusOf(outcome.batchId(), "R11")).isEqualTo(MutationStatus.PLANNED.name());
        assertThat(reasonOf(outcome.batchId(), "R11")).isNull();
        // Precedentie: een vastgehouden identiteit blijft BLOCKED, nooit AWAITING_APPROVAL.
        assertThat(statusOf(outcome.batchId(), "R12")).isEqualTo(MutationStatus.BLOCKED.name());
        assertThat(reasonOf(outcome.batchId(), "R12"))
                .isEqualTo(MutationDao.BLOCKED_BY_IDENTITY_REFERENCE_INCIDENT);

        assertThat(outcome.awaitingApprovalCount()).isEqualTo(10L);
        // Alle twaalf regels hebben hun mutatie: tien wachtend, één gepland, één geblokkeerd.
        assertThat(contentMutationCount(outcome.batchId())).isEqualTo(12L);
        // R-PRI-12: vasthouden is geen correctie. De tien wachtende mutaties dragen onveranderd de
        // aanvaarde vorige prijs en de geleverde nieuwe prijs; er is niets afgerond of teruggezet.
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                        + "and status = 'AWAITING_APPROVAL' and before_base_price = 1.50 "
                        + "and after_base_price = 3.00", Long.class, outcome.batchId()))
                .isEqualTo(10L);
    }

    /**
     * Valt de verwerking weg ná de mutatiegeneratie maar vóór de marker, dan blijft de batch op
     * {@code MUTATING} staan met haar prijsupdates al op goedkeuring. {@code continueMutating}
     * levert exact dezelfde statussen op: E5b raakt uitsluitend {@code PLANNED} aan, dus een tweede
     * doorloop verandert niets meer.
     */
    @Test
    void reachingTheSameStatusesAfterACrashBetweenTheHoldAndTheMarker() {
        Fixture fixture = acceptedBaseline("RESUMEAFTER");
        Delivered delivered = deliver(fixture, "REF-NEXT", HEADER + bulkPriceDelivery());
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
        assertThat(awaitingApprovalCount(delivered.batchId())).isEqualTo(10L);

        Mockito.reset(mutations);
        ScreeningOutcome outcome = screening.continueMutating(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
        assertThat(outcome.awaitingApprovalCount()).isEqualTo(10L);
        assertThat(statusOf(outcome.batchId(), "R11")).isEqualTo(MutationStatus.PLANNED.name());
        assertThat(statusOf(outcome.batchId(), "R12")).isEqualTo(MutationStatus.BLOCKED.name());
        assertThat(contentMutationCount(outcome.batchId())).isEqualTo(12L);
        assertThat(markerSummaries(outcome.batchId())).hasSize(1);
        assertThat(issueRowCount(outcome.batchId(), ImportIssueCatalog.BULK_PRICE_INCIDENT))
                .isEqualTo(1L);
    }

    /**
     * Dezelfde crash, maar nu <b>vóór</b> E5b: de mutaties bestaan al en staan nog allemaal op
     * {@code PLANNED}. De hervatting past het vasthouden alsnog toe en komt op precies dezelfde
     * eindtoestand uit — dát is waarom E5b een eigen, set-based en idempotente pass is.
     */
    @Test
    void applyingTheHoldAfterACrashBetweenTheMutationsAndTheHold() {
        Fixture fixture = acceptedBaseline("RESUMEBEFORE");
        Delivered delivered = deliver(fixture, "REF-NEXT", HEADER + bulkPriceDelivery());
        Mockito.doThrow(new UncheckedIOException(new IOException("simulated crash before the hold")))
                .when(mutations).holdPlannedPriceUpdates(anyLong(), anyString());

        try {
            screening.screen(delivered.batchId());
            throw new AssertionError("the simulated crash did not propagate");
        } catch (UncheckedIOException expected) {
            assertThat(expected).hasRootCauseInstanceOf(IOException.class);
        }

        assertThat(batches.findById(delivered.batchId()).orElseThrow().getStatus())
                .isEqualTo(ImportBatchStatus.MUTATING);
        assertThat(awaitingApprovalCount(delivered.batchId())).isZero();

        Mockito.reset(mutations);
        ScreeningOutcome outcome = screening.continueMutating(delivered.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.awaitingApprovalCount()).isEqualTo(10L);
        assertThat(contentMutationCount(outcome.batchId())).isEqualTo(12L);
        assertThat(markerSummaries(outcome.batchId())).hasSize(1);
        assertThat(markerSummaries(outcome.batchId()).get(0)).contains(";awaitingApproval=10");
    }

    // --- Ongecapte tellers ------------------------------------------------------------------------

    /**
     * Tweehonderdvijftig gewijzigde EAN's: er worden {@value #SAMPLE_CAP} voorbeeldrijen bewaard,
     * maar de teller is 250. Het bulkpercentage staat hier op 100 (precies op de grens, dus geen
     * bulkincident), zodat uitsluitend de individuele vaststellingen geteld worden.
     */
    @Test
    void countsEveryCriticalOccurrenceEvenAboveTheSampleCap() {
        Fixture fixture = acceptedBaseline("CRITCAP", 250, revision -> {
            revision.setBulkIncidentSharePercent(new BigDecimal("100"));
        });
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 250; i++) {
            csv.append(row("R" + i, "1,50", "Boormachine", "E-9" + i));
        }

        ScreeningOutcome outcome = screenSecond(fixture, csv.toString());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.identityIncidentCount()).isEqualTo(250L);
        assertThat(issueCodes(outcome.batchId()))
                .doesNotContain(ImportIssueCatalog.BULK_IDENTITY_INCIDENT);
        // De bewaarde voorbeeldrijen zijn gecapt...
        assertThat(issueRowCount(outcome.batchId(), ImportIssueCatalog.IDENTITY_REFERENCE_INCIDENT))
                .isEqualTo(SAMPLE_CAP);
        // ...maar de teller telt élk voorval.
        assertThat(outcome.criticalIssueCount()).isEqualTo(250L);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
    }

    /**
     * Een bulkmelding is zelf ook een kritieke vaststelling en telt dus mee: twaalf individuele
     * incidenten plus één {@code BULK_IDENTITY_INCIDENT} is dertien. Dat is geen dubbeltelling — de
     * melding komt <b>naast</b> de individuele meldingen (R-REF-07) en hangt aan de groep van de
     * onderliggende foutcode, niet aan een eigen groep.
     */
    @Test
    void countsTheBulkNoticeItselfAsACriticalFinding() {
        Fixture fixture = acceptedBaseline("BULKCOUNT", 12, revision -> {
        });
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            csv.append(row("R" + i, "1,50", "Boormachine", "E-9" + i));
        }

        ScreeningOutcome outcome = screenSecond(fixture, csv.toString());

        assertThat(issueCodes(outcome.batchId()))
                .contains(ImportIssueCatalog.BULK_IDENTITY_INCIDENT);
        assertThat(outcome.criticalIssueCount()).isEqualTo(13L);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.REVIEW_REQUIRED);
    }

    /** Hetzelfde voor de waarschuwingen: 250 prijsafwijkingen, 200 voorbeelden, teller 250. */
    @Test
    void countsEveryWarningOccurrenceEvenAboveTheSampleCap() {
        Fixture fixture = acceptedBaseline("WARNCAP", 250, revision -> {
            revision.setBulkIncidentSharePercent(new BigDecimal("100"));
        });
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 250; i++) {
            csv.append(row("R" + i, "3,00", "Boormachine", "E-" + i));
        }

        ScreeningOutcome outcome = screenSecond(fixture, csv.toString());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(issueRowCount(outcome.batchId(),
                PriceDeviationEvaluator.CODE_PRICE_DEVIATION_EXCEEDED)).isEqualTo(SAMPLE_CAP);
        assertThat(outcome.warningCount()).isEqualTo(250L);
        assertThat(outcome.criticalIssueCount()).isZero();
        // Geen bulkincident (precies op de grens) en geen kritieke lijn: enkel waarschuwingen.
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID_WITH_WARNINGS);
        assertThat(mutationStatuses(outcome.batchId())).containsOnly(MutationStatus.PLANNED.name());
    }

    // --- Helpers ------------------------------------------------------------------------------------

    /**
     * Tien prijzen die verdubbelen (samen één bulkprijsincident), één regel waarvan enkel de
     * omschrijving wijzigt en één regel met een gewijzigde EAN (vastgehouden identiteit).
     */
    private static String bulkPriceDelivery() {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            csv.append(row("R" + i, "3,00", "Boormachine", "E-" + i));
        }
        csv.append(row("R11", "1,50", "Slijpschijf", "E-11"));
        csv.append(row("R12", "1,50", "Boormachine", "E-912"));
        return csv.toString();
    }

    private static String row(String reference, String price, String description, String ean) {
        return "ACME;G1;" + reference + ';' + price + ';' + description + ';' + ean + '\n';
    }

    private static String rows(int count) {
        StringBuilder csv = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            csv.append(row("R" + i, "1,50", "Boormachine", "E-" + i));
        }
        return csv.toString();
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

    private List<String> mutationStatuses(long batchId) {
        return jdbc.queryForList("select status from import_mutation where batch_id = ? "
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

    private long awaitingApprovalCount(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE') and status = 'AWAITING_APPROVAL'",
                Long.class, batchId);
        return count == null ? 0L : count;
    }

    private long contentMutationCount(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and action_type in ('CREATE', 'UPDATE')", Long.class, batchId);
        return count == null ? 0L : count;
    }

    private List<String> markerSummaries(long batchId) {
        return jdbc.queryForList("select result_summary from import_mutation where batch_id = ? "
                + "and action_type = 'IMPORT_MARKER'", String.class, batchId);
    }

    private ScreeningOutcome screenSecond(Fixture fixture, String body) {
        return screening.screen(deliver(fixture, "REF-NEXT", HEADER + body).batchId());
    }

    private Fixture acceptedBaseline(String prefix) {
        return acceptedBaseline(prefix, 12, revision -> {
        });
    }

    private Fixture acceptedBaseline(String prefix, int rows,
                                     Consumer<ImportDefinitionRevision> tweak) {
        Fixture fixture = fixture(prefix, tweak);
        long batchId = deliver(fixture, "REF-BASE", HEADER + rows(rows)).batchId();
        assertThat(screening.screen(batchId).status()).isEqualTo(ImportBatchStatus.SCREENED);
        baseline.acceptBaseline(batchId, USER, "nulmeting");
        return fixture;
    }

    private Delivered deliver(Fixture fixture, String reference, String csv) {
        DeliveryView view = intake.intake(fixture.taskId(), reference, USER, null, null, "levering.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).delivery();
        return new Delivered(deliveries.findById(view.deliveryId()).orElseThrow().getId(),
                view.batch().batchId());
    }

    /**
     * Eén eigen keten met een gemapte kritieke koppelreferentie (EAN).
     * <p>
     * {@code max_critical_share_percent} staat op 100: deze test gaat over het oordeel en de
     * mutatiestatussen, niet over de drempel die een levering stopt
     * ({@code ThresholdBlockingTest} bewijst die).
     */
    private Fixture fixture(String prefix, Consumer<ImportDefinitionRevision> tweak) {
        String unique = "BI" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        revision.setRecordCanonicalisationVersion(2);
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        tweak.accept(revision);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);

        ImportFieldCatalogEntry ean = fieldCatalog.findById("EAN").orElseThrow();
        ImportFieldMapping mapping = new ImportFieldMapping(stored, 1, ean, FieldValueKind.SOURCE_FIELD,
                ean.getDataType(), ean.getDefaultOwner(), ean.getIdentityClass());
        mapping.setSourceReference("EAN");
        mapping.setReferenceType(ean.getReferenceType());
        fieldMappings.saveAndFlush(mapping);

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
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
