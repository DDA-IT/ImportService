package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRowIssueRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ControlLevel;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRowIssue;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.CsvRecordStreamer;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import be.dda.catalogimport.service.support.ImportValueRules;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Fase 2c: de stagingfase van de screening (design par. 8 en par. 9 stap C) tegen de echte
 * service, DAO's, het bestandsarchief en H2. De microbatchgrootte staat op 2 en de voorbeeldcap per
 * foutcode op 5, zodat meerdere microbatch-commits en de cap met kleine bestanden aantoonbaar zijn.
 * <p>
 * De cap is sinds bouwstap 3a géén blokkeerreden meer (ontwerp fase 3, afwijking C): boven de cap
 * worden er enkel minder voorbeeldrijen bewaard.
 * <p>
 * Sinds bouwstap 2d loopt {@code screen} door tot de eindstatus; deze tests kijken naar het
 * stagingdeel daarvan. De delta, de mutatielijst en de marker worden in
 * {@code DeliveryScreeningFlowTest} gecontroleerd.
 * <p>
 * Elke test bouwt een eigen taak met unieke codes: de H2-database is gedeeld en een open
 * {@code TaskRun} blokkeert een tweede upload op dezelfde taak.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=2",
        "catalogimport.screening.max-sample-rows-per-code=5"})
@ActiveProfiles("local")
class DeliveryStagingTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final byte[] THREE_VALID_ROWS = (HEADER
            + "ACME;G1;R1;1,50;Boormachine\n"
            + "ACME;G1;R2;2,25;Schroevendraaier\n"
            + "ACME;G1;R3;3,00;Hamer\n").getBytes(StandardCharsets.UTF_8);

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @MockitoSpyBean
    private DeliveryArchiveStore archive;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
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
    private TaskRunRepository runs;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private DeliveryFileRepository deliveryFiles;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private ImportRowIssueRepository rowIssues;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Normaal scenario ----------------------------------------------------------------------

    @Test
    void stagesEveryValidRowWithItsPhysicalLineNumberIdentityHashAndPrice() {
        Screened screened = upload("OK", THREE_VALID_ROWS);

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rawRecordCount()).isEqualTo(3L);
        assertThat(outcome.validRecordCount()).isEqualTo(3L);
        assertThat(outcome.rejectedRecordCount()).isZero();

        List<StagedRow> staged = stagedRows(screened.batchId());
        assertThat(staged).hasSize(3);
        // Fysieke, 1-gebaseerde regelnummers: de header staat op regel 1.
        assertThat(staged).extracting(StagedRow::rowNumber).containsExactly(2L, 3L, 4L);
        assertThat(staged).extracting(StagedRow::supplierReference).containsExactly("R1", "R2", "R3");
        assertThat(staged).extracting(StagedRow::description)
                .containsExactly("Boormachine", "Schroevendraaier", "Hamer");
        assertThat(staged).extracting(row -> row.basePrice().toPlainString())
                .containsExactly("1.500000", "2.250000", "3.000000");
        assertThat(staged).allSatisfy(row -> {
            assertThat(row.supplier()).isEqualTo("ACME");
            assertThat(row.supplierGroup()).isEqualTo("G1");
            assertThat(row.discountCode()).isNull();
            assertThat(row.discountState()).isEqualTo("NOT_USED");
            assertThat(row.basePriceCurrency()).isNull();
            // Lege bronstaat: de delta uit bouwstap 2d ziet elke regel als nieuw.
            assertThat(row.classification()).isEqualTo("NEW");
        });

        byte[] expectedIdentityHash = ImportValueRules.sha256Utf8(
                ImportValueRules.canonical(1, "ACME", "G1", "R1"));
        assertThat(staged.get(0).identityHash()).isEqualTo(expectedIdentityHash);
        assertThat(staged.get(0).mutationKeyPrefix()).isEqualTo(screened.deliveryId() + ":"
                + screened.revisionId() + ":" + HexFormat.of().formatHex(expectedIdentityHash));

        ImportBatch batch = batches.findById(screened.batchId()).orElseThrow();
        assertThat(batch.getStagedRowCount()).isEqualTo(3);
        assertThat(batch.getStartedAt()).isNotNull();
        assertThat(batch.getFinishedAt()).isNotNull();
        assertThat(batch.getBlockedCode()).isNull();
        assertThat(deliveries.findById(screened.deliveryId()).orElseThrow().getActualRecordCount())
                .isEqualTo(3L);
        // Geen enkel regelprobleem. Sinds bouwstap 3h-3 laat elke eerste levering van een koppeling
        // wél één melding op leveringsniveau achter: de initialisatie waardoor de creaties op
        // goedkeuring wachten (ontwerp fase 3 par. 15.2).
        assertThat(rowIssues.findByBatchId(screened.batchId(), PageRequest.of(0, 10)).getContent())
                .extracting(ImportRowIssue::getIssueCode)
                .containsExactly(ImportIssueCatalog.INITIAL_LOAD_REQUIRES_APPROVAL);
        assertThat(runs.findById(screened.taskRunId()).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.COMPLETED);
    }

    @Test
    void readsExactlyTheArchivedBytes() throws Exception {
        Screened screened = upload("BYTES", THREE_VALID_ROWS);
        Path archived = archiveRoot.resolve(
                deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(screened.deliveryId()).get(0)
                        .getArchiveReference());
        assertThat(archived).hasBinaryContent(THREE_VALID_ROWS);

        screening.screen(screened.batchId());

        assertThat(stagedRows(screened.batchId())).hasSize(3);
    }

    @Test
    void refusesToStageAnArchivedObjectThatNoLongerMatchesTheRegisteredByteSize() throws Exception {
        Screened screened = upload("TRUNC", THREE_VALID_ROWS);
        Path archived = archiveRoot.resolve(
                deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(screened.deliveryId()).get(0)
                        .getArchiveReference());
        archived.toFile().setWritable(true);
        Files.write(archived, (HEADER + "ACME;G1;R1;1,50;Boormachine\n").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> screening.screen(screened.batchId()))
                .isInstanceOf(IllegalStateException.class);

        ImportBatch batch = batches.findById(screened.batchId()).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(stagedRows(screened.batchId())).isEmpty();
    }

    @Test
    void screensADeliveryOnlyOnceSoTheStagingCanNeverBeDuplicated() {
        Screened screened = upload("TWICE", THREE_VALID_ROWS);
        screening.screen(screened.batchId());

        // De marker van de eerste screening is het bewijs dat deze levering onder deze revisie klaar is.
        assertThatThrownBy(() -> screening.screen(screened.batchId()))
                .isInstanceOf(ConflictException.class)
                .extracting(failure -> ((ConflictException) failure).getCode())
                .isEqualTo(DeliveryScreeningService.CODE_ALREADY_SCREENED);

        assertThat(stagedRows(screened.batchId())).hasSize(3);
    }

    @Test
    void reportsAnUnknownBatch() {
        assertThatThrownBy(() -> screening.screen(999_999_999L))
                .isInstanceOf(NotFoundException.class)
                .extracting(failure -> ((NotFoundException) failure).getCode())
                .isEqualTo("BATCH_NOT_FOUND");
    }

    // --- Ongeldige regels: enkel die regel wordt verworpen --------------------------------------

    @Test
    void rejectsOnlyTheRowsWithAnUnreadableOrMissingPriceAndNeverStoresAZeroPrice() {
        byte[] csv = (HEADER
                + "ACME;G1;R1;1,50;Boormachine\n"
                + "ACME;G1;R2;12,3x;Schroevendraaier\n"
                + "ACME;G1;R3;;Hamer\n"
                + "ACME;G1;R4;4,00;Zaag\n").getBytes(StandardCharsets.UTF_8);
        Screened screened = upload("REJECT", csv);

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rawRecordCount()).isEqualTo(4L);
        assertThat(outcome.validRecordCount()).isEqualTo(2L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(stagedRows(screened.batchId()))
                .extracting(StagedRow::supplierReference).containsExactly("R1", "R4");
        // Nergens een prijs 0 of null als gevolg van een parsefout.
        assertThat(jdbc.queryForObject("select count(*) from import_candidate_stage where batch_id = ? "
                + "and base_price = 0", Long.class, screened.batchId())).isZero();

        // Enkel de regelproblemen; de melding over de initialisatie (bouwstap 3h-3) hoort bij de
        // levering als geheel en draagt dus geen regelnummer.
        List<ImportRowIssue> issues = rowIssues.findByBatchId(screened.batchId(), PageRequest.of(0, 10))
                .getContent().stream().filter(issue -> issue.getRowNumber() != null).toList();
        assertThat(issues).hasSize(2);
        assertThat(issues).extracting(ImportRowIssue::getIssueCode)
                .containsExactlyInAnyOrder(ImportValueRules.CODE_PRICE_UNREADABLE,
                        ImportValueRules.CODE_PRICE_MISSING);
        assertThat(issues).extracting(ImportRowIssue::getRowNumber).containsExactlyInAnyOrder(3L, 4L);
        assertThat(issues).allSatisfy(issue -> {
            assertThat(issue.getFieldName()).isEqualTo("PRIJS");
            assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.ERROR);
        });
        assertThat(jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                        + "and delivery_file_id = ? and row_number is not null", Long.class,
                screened.batchId(), screened.deliveryFileId())).isEqualTo(2L);
        assertThat(issues).filteredOn(issue -> issue.getRowNumber() == 3L).singleElement()
                .satisfies(issue -> assertThat(issue.getSourceValue()).isEqualTo("12,3x"));
    }

    @Test
    void recordsAnEmptyIdentityComponentAsARowIssue() {
        byte[] csv = (HEADER
                + "ACME;;R1;1,50;Boormachine\n"
                + "ACME;G1;R2;2,25;Schroevendraaier\n").getBytes(StandardCharsets.UTF_8);
        Screened screened = upload("IDEMPTY", csv);

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.validRecordCount()).isEqualTo(1L);
        // Enkel de regelproblemen; de initialisatiemelding van bouwstap 3h-3 hoort bij de levering.
        assertThat(rowIssues.findByBatchId(screened.batchId(), PageRequest.of(0, 10)).getContent())
                .filteredOn(issue -> issue.getRowNumber() != null)
                .singleElement().satisfies(issue -> {
                    assertThat(issue.getIssueCode())
                            .isEqualTo(CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY);
                    assertThat(issue.getFieldName()).isEqualTo("GROEP");
                    assertThat(issue.getRowNumber()).isEqualTo(2L);
                });
    }

    /**
     * Aangepast in bouwstap 3a (ontwerp fase 3, afwijking C): veel identieke regelfouten waren in
     * fase 2 een blokkeerreden ({@code TOO_MANY_ROW_ISSUES}), maar dat was een technische
     * logginggrens die zich als businessoordeel voordeed. Nu worden er enkel minder voorbeeldrijen
     * bewaard; het bestand wordt volledig gelezen en de volledige aantallen per code blijven bewaard.
     */
    @Test
    void keepsOnlyTheConfiguredNumberOfExampleRowsPerCodeWithoutBlockingTheDelivery() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 8; i++) {
            csv.append("ACME;G1;R").append(i).append(";fout;Boormachine\n");
        }
        Screened screened = upload("CAP", csv.toString().getBytes(StandardCharsets.UTF_8));

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.blockedCode()).isNull();
        // Het bestand is volledig gelezen: alle acht de regels zijn geteld en verworpen.
        assertThat(outcome.rawRecordCount()).isEqualTo(8L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(8L);
        assertThat(outcome.validRecordCount()).isZero();

        // Vijf voorbeelden (de laagste regelnummers) plus één melding met de volledige aantallen.
        List<ImportRowIssue> issues = rowIssues.findByBatchId(screened.batchId(), PageRequest.of(0, 20))
                .getContent();
        assertThat(issues).filteredOn(issue ->
                        issue.getIssueCode().equals(ImportValueRules.CODE_PRICE_UNREADABLE))
                .hasSize(5)
                .extracting(ImportRowIssue::getRowNumber).containsExactly(2L, 3L, 4L, 5L, 6L);
        assertThat(issues).filteredOn(issue -> issue.getIssueCode()
                        .equals(DeliveryScreeningService.CODE_ROW_ISSUE_RECORDING_CAPPED))
                .singleElement()
                .satisfies(capped -> {
                    assertThat(capped.getSeverity()).isEqualTo(RowIssueSeverity.INFO);
                    assertThat(capped.getControlLevel()).isEqualTo(ControlLevel.DELIVERY);
                    assertThat(capped.getRowNumber()).isNull();
                    // Het volledige aantal gaat nooit verloren, ook al zijn er maar vijf voorbeelden.
                    assertThat(capped.getMessage()).contains(ImportValueRules.CODE_PRICE_UNREADABLE + "=8");
                });
        assertThat(rowIssues.countByBatchId(screened.batchId())).isEqualTo(6);
        assertThat(runs.findById(screened.taskRunId()).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.COMPLETED);
    }

    /** Grensgeval: precies de cap halen is geen overschrijding, dus geen melding erbij. */
    @Test
    void doesNotReportACapWhenTheNumberOfErrorsIsExactlyTheConfiguredMaximum() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 5; i++) {
            csv.append("ACME;G1;R").append(i).append(";fout;Boormachine\n");
        }
        Screened screened = upload("CAPEXACT", csv.toString().getBytes(StandardCharsets.UTF_8));

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(5L);
        assertThat(rowIssues.countByBatchId(screened.batchId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                        + "and issue_code = ?", Long.class, screened.batchId(),
                DeliveryScreeningService.CODE_ROW_ISSUE_RECORDING_CAPPED)).isZero();
    }

    /** De voorbeeldcap geldt ook voor duplicaten; het volledige aantal blijft in de tellers staan. */
    @Test
    void recordsAtMostTheSampleCapForDuplicateIdentitiesButStillCountsThemAll() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 8; i++) {
            csv.append("ACME;G1;R1;1,5").append(i).append(";Boormachine\n");
        }
        Screened screened = upload("DUPCAP", csv.toString().getBytes(StandardCharsets.UTF_8));

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(DeliveryScreeningService.CODE_DUPLICATE_IDENTITY_IN_DELIVERY);
        // Alle betrokken regels worden geteld, ook al worden er maar vijf problemen bewaard.
        assertThat(outcome.duplicateIdentityCount()).isEqualTo(8L);
        assertThat(outcome.blockedReason()).contains("8 lines");
        // Aangepast in bouwstap 3g (ontwerp par. 9 kondigde dit aan: "uniforme telling komt in 3g"):
        // vijf voorbeeldrijen plus één cap-melding met het werkelijke aantal. Die melding hing er
        // eerder niet aan, waardoor het totaal enkel in duplicate_identity_count stond en niet in de
        // probleemlijst die de gebruiker leest.
        assertThat(rowIssues.countByBatchId(screened.batchId())).isEqualTo(6);
        assertThat(jdbc.queryForObject("select message from import_row_issue where batch_id = ? "
                        + "and issue_code = ?", String.class, screened.batchId(),
                DeliveryScreeningService.CODE_ROW_ISSUE_RECORDING_CAPPED))
                .contains(DeliveryScreeningService.CODE_DUPLICATE_IDENTITY_IN_DELIVERY + "=8");
        assertThat(outcome.contentMutationCount()).isZero();
    }

    // --- Contract- en structuurfouten: blokkeren de levering ------------------------------------

    @Test
    void blocksAnEmptyFile() {
        Screened screened = upload("EMPTY", new byte[0]);

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(CsvRecordStreamer.CODE_SOURCE_FILE_EMPTY);
        assertThat(stagedRows(screened.batchId())).isEmpty();
        ImportBatch batch = batches.findById(screened.batchId()).orElseThrow();
        assertThat(batch.getFinishedAt()).isNotNull();
        assertThat(batch.getOpenMarker()).isNull();
        assertThat(runs.findById(screened.taskRunId()).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.COMPLETED);
    }

    @Test
    void blocksAFileWithOnlyAHeader() {
        Screened screened = upload("HDRONLY", HEADER.getBytes(StandardCharsets.UTF_8));

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(DeliveryScreeningService.CODE_SOURCE_NO_DATA_RECORDS);
        assertThat(outcome.rawRecordCount()).isZero();
        assertThat(stagedRows(screened.batchId())).isEmpty();
    }

    @Test
    void blocksAFileWhoseHeaderMissesADeclaredField() {
        byte[] csv = ("LEVERANCIER;GROEP;REFERENTIE;BEDRAG;OMSCHRIJVING\n"
                + "ACME;G1;R1;1,50;Boormachine\n").getBytes(StandardCharsets.UTF_8);
        Screened screened = upload("NOFIELD", csv);

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo("HEADER_FIELD_MISSING:PRIJS");
        assertThat(outcome.blockedReason()).contains("PRIJS");
        assertThat(stagedRows(screened.batchId())).isEmpty();
    }

    @Test
    void blocksWhenTheManifestRecordCountDoesNotMatchButKeepsTheStagingAsEvidence() {
        Screened screened = upload("RECNT", THREE_VALID_ROWS, 99L, null);

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(DeliveryScreeningService.CODE_RECORD_COUNT_MISMATCH);
        assertThat(outcome.blockedReason()).contains("99").contains("3");
        assertThat(stagedRows(screened.batchId())).hasSize(3);
        assertThat(deliveries.findById(screened.deliveryId()).orElseThrow().getActualRecordCount())
                .isEqualTo(3L);
    }

    @Test
    void blocksBeforeParsingWhenTheManifestByteSizeDoesNotMatch() {
        Screened screened = upload("BYTECNT", THREE_VALID_ROWS, null, (long) THREE_VALID_ROWS.length + 5);

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(DeliveryScreeningService.CODE_BYTE_SIZE_MISMATCH);
        assertThat(stagedRows(screened.batchId())).isEmpty();
        assertThat(outcome.rawRecordCount()).isNull();
    }

    @Test
    void blocksADeliveryWhoseRevisionHasNoUsableSourceConfiguration() {
        Screened screened = upload("CFG", THREE_VALID_ROWS, null, null,
                revision -> revision.setStructureCharset("NO-SUCH-CHARSET"));

        ScreeningOutcome outcome = screening.screen(screened.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo("CONFIG_CHARSET_UNKNOWN");
        assertThat(stagedRows(screened.batchId())).isEmpty();
    }

    // --- Technische fout halverwege --------------------------------------------------------------

    @Test
    void marksTheBatchFailedAndRemovesTheStagingWhenTheSourceBreaksHalfway() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 8; i++) {
            csv.append("ACME;G1;R").append(i).append(";1,50;Boor\n");
        }
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        Screened screened = upload("CRASH", content);
        AtomicLong stagedAtCrash = new AtomicLong(-1);
        int failAfter = HEADER.length() + 4 * "ACME;G1;R1;1,50;Boor\n".length();
        doAnswer(invocation -> failingStream(content, failAfter, () -> stagedAtCrash.set(
                countStaged(screened.batchId()))))
                .when(archive).open(anyString());

        assertThatThrownBy(() -> screening.screen(screened.batchId()))
                .isInstanceOf(UncheckedIOException.class);

        // Er was wel degelijk al een microbatch vastgelegd vóór de crash ...
        assertThat(stagedAtCrash).hasValueGreaterThan(0);
        // ... en die is samen met de problemen van deze poging opgeruimd.
        ImportBatch batch = batches.findById(screened.batchId()).orElseThrow();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.FAILED);
        assertThat(batch.getStagedRowCount()).isZero();
        assertThat(batch.getRawRecordCount()).isNull();
        assertThat(batch.getBlockedCode()).isEqualTo(DeliveryScreeningService.CODE_SCREENING_FAILED);
        assertThat(stagedRows(screened.batchId())).isEmpty();
        assertThat(rowIssues.countByBatchId(screened.batchId())).isZero();
        assertThat(runs.findById(screened.taskRunId()).orElseThrow().getStatus())
                .isEqualTo(TaskRunStatus.FAILED);
    }

    // --- Helpers -------------------------------------------------------------------------------

    private long countStaged(long batchId) {
        Long count = jdbc.queryForObject("select count(*) from import_candidate_stage where batch_id = ?",
                Long.class, batchId);
        return count == null ? 0L : count;
    }

    /** Leest de staging via kolomposities: H2 en PostgreSQL geven kolomnamen in een andere schrijfwijze terug. */
    private List<StagedRow> stagedRows(long batchId) {
        return jdbc.query("select row_number, identity_supplier, identity_supplier_group, "
                        + "identity_supplier_reference, identity_discount_code, identity_discount_state, "
                        + "identity_hash, base_price, base_price_currency, description, mutation_key_prefix, "
                        + "classification from import_candidate_stage where batch_id = ? order by row_number",
                (resultSet, rowNumber) -> new StagedRow(
                        resultSet.getLong(1), resultSet.getString(2), resultSet.getString(3),
                        resultSet.getString(4), resultSet.getString(5), resultSet.getString(6),
                        resultSet.getBytes(7), resultSet.getBigDecimal(8), resultSet.getString(9),
                        resultSet.getString(10), resultSet.getString(11), resultSet.getString(12)),
                batchId);
    }

    private record StagedRow(long rowNumber, String supplier, String supplierGroup, String supplierReference,
                             String discountCode, String discountState, byte[] identityHash, BigDecimal basePrice,
                             String basePriceCurrency, String description, String mutationKeyPrefix,
                             String classification) {
    }

    /**
     * Levert de bytes tot {@code failAfter} en breekt daarna met een I/O-fout, zoals een defecte
     * schijf. {@code beforeFailing} loopt bij elke poging; de laatste meting is die van de fout die
     * werkelijk doorbreekt ({@code InputStream.read(byte[], int, int)} slikt de eerste fout op en
     * geeft dan het aantal reeds gelezen bytes terug).
     */
    private static InputStream failingStream(byte[] content, int failAfter, Runnable beforeFailing) {
        return new InputStream() {

            private final ByteArrayInputStream delegate = new ByteArrayInputStream(content);
            private int delivered;

            @Override
            public int read() throws IOException {
                if (delivered >= failAfter) {
                    beforeFailing.run();
                    throw new IOException("simulated source failure");
                }
                delivered++;
                return delegate.read();
            }
        };
    }

    private Screened upload(String prefix, byte[] content) {
        return upload(prefix, content, null, null, null);
    }

    private Screened upload(String prefix, byte[] content, Long expectedRecordCount, Long expectedByteSize) {
        return upload(prefix, content, expectedRecordCount, expectedByteSize, null);
    }

    private Screened upload(String prefix, byte[] content, Long expectedRecordCount, Long expectedByteSize,
                            Consumer<ImportDefinitionRevision> revisionCustomiser) {
        String unique = "ST" + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(
                new ImportDefinition(organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
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
        revision.setStatus(RevisionStatus.ACTIVE);
        if (revisionCustomiser != null) {
            revisionCustomiser.accept(revision);
        }
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));

        DeliveryView view = intake.intake(task.getId(), unique, "tester@example.test", expectedRecordCount,
                expectedByteSize, "levering.csv", new ByteArrayInputStream(content)).delivery();
        Delivery delivery = deliveries.findById(view.deliveryId()).orElseThrow();
        TaskRun run = runs.findByTaskIdAndConcurrencyTokenIsNotNull(task.getId()).orElseThrow();
        return new Screened(view.batch().batchId(), delivery.getId(),
                deliveryFiles.findByDeliveryIdOrderBySequenceNumberAsc(delivery.getId()).get(0).getId(),
                stored.getId(), run.getId());
    }

    private record Screened(long batchId, long deliveryId, long deliveryFileId, long revisionId, long taskRunId) {
    }
}
