package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRowIssueRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ControlLevel;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImpactScope;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRowIssue;
import be.dda.catalogimport.domain.IssueDomain;
import be.dda.catalogimport.domain.IssueHandlingStatus;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.CandidateNormaliser;
import be.dda.catalogimport.service.support.CsvRecordStreamer;
import be.dda.catalogimport.service.support.ImportValueRules;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * Fase 3a (ontwerp fase 3, par. 3.3 en R-STR-01): de controlehiërarchie is hard.
 * <ul>
 *   <li>Een fout op leverings- of structuurniveau laat de recordfase niet starten: een ontbrekende
 *       headerkolom levert <b>precies één</b> structuurprobleem op en nooit duizenden identieke
 *       recordproblemen, hoe groot het bestand ook is.</li>
 *   <li>Elke blokkade laat sinds deze bouwstap ook een issuerij achter, zodat de zwaarste
 *       vaststelling over een levering niet alleen in {@code blocked_code} staat.</li>
 *   <li>Veel identieke regelfouten blokkeren juist <b>niet</b> meer (afwijking C): er worden enkel
 *       minder voorbeeldrijen bewaard en de volledige aantallen blijven behouden.</li>
 * </ul>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest
@ActiveProfiles("local")
class ControlHierarchyTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String VALID_ROW = "ACME;G1;R1;1,50;Boormachine\n";

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
    private ImportRowIssueRepository rowIssues;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Niveau 2: het structuurcontract ---------------------------------------------------------

    /**
     * Het bestand bevat 400 datalijnen, maar de header mist een gedeclareerd veld. Zonder hiërarchie
     * zou elke regel opnieuw over datzelfde ontbrekende veld klagen; dat is exact het "10.000 losse
     * fouten in plaats van één bulkincident" dat de businessanalyse verbiedt.
     */
    @Test
    void aMissingHeaderFieldBlocksWithExactlyOneStructureIssueAndNoRecordIssues() {
        StringBuilder csv = new StringBuilder("LEVERANCIER;GROEP;REFERENTIE;BEDRAG;OMSCHRIJVING\n");
        for (int i = 1; i <= 400; i++) {
            csv.append("ACME;G1;R").append(i).append(";1,50;Boormachine\n");
        }
        Uploaded uploaded = upload("HIERHDR", csv.toString());

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo("HEADER_FIELD_MISSING:PRIJS");
        assertThat(issues(uploaded)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode()).isEqualTo("HEADER_FIELD_MISSING:PRIJS");
            assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.BLOCKING);
            assertThat(issue.getIssueDomain()).isEqualTo(IssueDomain.STRUCTURE_DATASET);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.STRUCTURE);
            assertThat(issue.getImpactScope()).isEqualTo(ImpactScope.DELIVERY);
            assertThat(issue.getHandlingStatus()).isEqualTo(IssueHandlingStatus.DETECTED);
            assertThat(issue.getRowNumber()).isNull();
            assertThat(issue.getFieldName()).isEqualTo("PRIJS");
        });
        assertThat(countAtLevel(uploaded, ControlLevel.RECORD)).isZero();
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.BLOCKING);
    }

    @Test
    void aConfigurationErrorBlocksAtStructureLevelBeforeASingleByteIsParsed() {
        Uploaded uploaded = upload("HIERCFG", HEADER + VALID_ROW, null, null,
                revision -> revision.setStructureCharset("NO-SUCH-CHARSET"));

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(issues(uploaded)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode()).isEqualTo("CONFIG_CHARSET_UNKNOWN");
            assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.BLOCKING);
            assertThat(issue.getIssueDomain()).isEqualTo(IssueDomain.AUTHORISATION_CONFIG);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.STRUCTURE);
            assertThat(issue.getRowNumber()).isNull();
        });
    }

    // --- Niveau 1: de levering ---------------------------------------------------------------------

    @Test
    void anEmptyFileProducesOneDeliveryIssue() {
        Uploaded uploaded = upload("HIEREMPTY", "");

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(CsvRecordStreamer.CODE_SOURCE_FILE_EMPTY);
        assertThat(issues(uploaded)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode()).isEqualTo(CsvRecordStreamer.CODE_SOURCE_FILE_EMPTY);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.DELIVERY);
            assertThat(issue.getImpactScope()).isEqualTo(ImpactScope.DELIVERY);
            assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.BLOCKING);
            assertThat(issue.getRowNumber()).isNull();
        });
    }

    @Test
    void aHeaderWithoutDataRecordsProducesOneDeliveryIssue() {
        Uploaded uploaded = upload("HIERHDRONLY", HEADER);

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(issues(uploaded)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode()).isEqualTo(DeliveryScreeningService.CODE_SOURCE_NO_DATA_RECORDS);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.DELIVERY);
        });
    }

    /** Het byte-aantal wordt vóór het parsen gecontroleerd: geen enkele regel wordt gelezen. */
    @Test
    void aByteSizeMismatchProducesOneDeliveryIssueShowingFoundAndExpected() {
        String csv = HEADER + VALID_ROW;
        long actual = csv.getBytes(StandardCharsets.UTF_8).length;
        Uploaded uploaded = upload("HIERBYTES", csv, null, actual + 5);

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(issues(uploaded)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode()).isEqualTo(DeliveryScreeningService.CODE_BYTE_SIZE_MISMATCH);
            assertThat(issue.getIssueDomain()).isEqualTo(IssueDomain.DELIVERY_SOURCE);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.DELIVERY);
            // Gevonden én verwacht staan er allebei: er wordt nooit stil één van de twee gekozen.
            assertThat(issue.getSourceValue()).isEqualTo(String.valueOf(actual));
            assertThat(issue.getExpectedValue()).isEqualTo(String.valueOf(actual + 5));
        });
    }

    @Test
    void aRecordCountMismatchProducesOneDeliveryIssueAndKeepsTheStagingAsEvidence() {
        Uploaded uploaded = upload("HIERCOUNT", HEADER + VALID_ROW, 99L, null);

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(issues(uploaded)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode()).isEqualTo(DeliveryScreeningService.CODE_RECORD_COUNT_MISMATCH);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.DELIVERY);
            assertThat(issue.getSourceValue()).isEqualTo("1");
            assertThat(issue.getExpectedValue()).isEqualTo("99");
        });
        assertThat(staged(uploaded)).isEqualTo(1L);
    }

    /**
     * Een dubbele identiteit meldde in fase 2 al elke betrokken regel met dezelfde foutcode. Die
     * rijen zijn de issuerijen van de blokkade: er komt géén extra samenvattende rij bij, anders zou
     * dezelfde vaststelling dubbel geteld worden.
     */
    @Test
    void aDuplicateIdentityKeepsItsPerRowIssuesWithoutAddingASecondBlockageRow() {
        Uploaded uploaded = upload("HIERDUP", HEADER + VALID_ROW + "ACME;G1;R1;9,99;Boormachine XL\n");

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode())
                .isEqualTo(DeliveryScreeningService.CODE_DUPLICATE_IDENTITY_IN_DELIVERY);
        assertThat(issues(uploaded)).hasSize(2).allSatisfy(issue -> {
            assertThat(issue.getIssueCode())
                    .isEqualTo(DeliveryScreeningService.CODE_DUPLICATE_IDENTITY_IN_DELIVERY);
            // De code is vanaf fase 3 BLOCKING en staat op leveringsniveau: de hele levering deugt niet.
            assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.BLOCKING);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.DELIVERY);
            assertThat(issue.getRowNumber()).isNotNull();
        });
    }

    // --- Niveau 3: regelfouten blokkeren de levering niet ------------------------------------------

    /**
     * Afwijking C: 500 identieke regelfouten waren in fase 2 een blokkade
     * ({@code TOO_MANY_ROW_ISSUES}). Nu wordt het bestand volledig gelezen, blijven er hoogstens
     * {@code max-sample-rows-per-code} (standaard 200) voorbeeldrijen over en wordt het volledige
     * aantal in één informatieve melding bewaard.
     */
    @Test
    void manyIdenticalRowErrorsCapTheExamplesInsteadOfBlockingTheDelivery() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 1; i <= 500; i++) {
            csv.append("ACME;G1;R").append(i).append(";onleesbaar;Boormachine\n");
        }
        Uploaded uploaded = upload("HIERCAP", csv.toString());

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.blockedCode()).isNull();
        assertThat(outcome.rawRecordCount()).isEqualTo(500L);
        // Elke verworpen regel telt mee, ook de regels waarvan geen voorbeeld bewaard is.
        assertThat(outcome.rejectedRecordCount()).isEqualTo(500L);
        assertThat(outcome.validRecordCount()).isZero();

        List<ImportRowIssue> samples = issues(uploaded).stream()
                .filter(issue -> issue.getIssueCode().equals(ImportValueRules.CODE_PRICE_UNREADABLE))
                .toList();
        assertThat(samples).hasSize(DeliveryScreeningService.DEFAULT_MAX_SAMPLE_ROWS_PER_CODE);
        // Deterministisch de laagste regelnummers: het bestand wordt in leesvolgorde verwerkt.
        assertThat(samples.get(0).getRowNumber()).isEqualTo(2L);
        assertThat(samples.get(samples.size() - 1).getRowNumber())
                .isEqualTo(1L + DeliveryScreeningService.DEFAULT_MAX_SAMPLE_ROWS_PER_CODE);

        assertThat(issues(uploaded)).filteredOn(issue -> issue.getIssueCode()
                        .equals(DeliveryScreeningService.CODE_ROW_ISSUE_RECORDING_CAPPED))
                .singleElement()
                .satisfies(capped -> {
                    assertThat(capped.getSeverity()).isEqualTo(RowIssueSeverity.INFO);
                    assertThat(capped.getControlLevel()).isEqualTo(ControlLevel.DELIVERY);
                    assertThat(capped.getImpactScope()).isEqualTo(ImpactScope.DELIVERY);
                    assertThat(capped.getRowNumber()).isNull();
                    assertThat(capped.getMessage())
                            .contains(ImportValueRules.CODE_PRICE_UNREADABLE + "=500");
                });
        assertThat(issues(uploaded))
                .hasSize(DeliveryScreeningService.DEFAULT_MAX_SAMPLE_ROWS_PER_CODE + 1);
    }

    @Test
    void anInvalidRowRejectsOnlyThatRowAndStaysAtRecordLevel() {
        Uploaded uploaded = upload("HIERROW", HEADER + VALID_ROW + "ACME;;R2;2,25;Schroevendraaier\n");

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(1L);
        assertThat(issues(uploaded)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode()).isEqualTo(CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY);
            assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.ERROR);
            assertThat(issue.getIssueDomain()).isEqualTo(IssueDomain.IDENTITY_REFERENCE);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.RECORD);
            assertThat(issue.getImpactScope()).isEqualTo(ImpactScope.RECORD);
            assertThat(issue.getRowNumber()).isEqualTo(3L);
        });
        assertThat(countAtLevel(uploaded, ControlLevel.DELIVERY)).isZero();
    }

    /**
     * De regressiecontrole op de hele afwijking: elke geblokkeerde levering laat een issuerij achter
     * met haar eigen blokkeercode, en {@code blocked_code}/{@code blocked_reason} blijven bestaan.
     */
    @Test
    void everyBlockedDeliveryLeavesAnIssueRowForItsBlockedCode() {
        record Case(String prefix, String content, Long expectedRecordCount) {
        }
        List<Case> cases = List.of(
                new Case("BLKEMPTY", "", null),
                new Case("BLKHDR", HEADER, null),
                new Case("BLKFIELD", "LEVERANCIER;GROEP;REFERENTIE;BEDRAG;OMSCHRIJVING\n" + VALID_ROW, null),
                new Case("BLKCOUNT", HEADER + VALID_ROW, 42L),
                new Case("BLKDUP", HEADER + VALID_ROW + "ACME;G1;R1;2,50;Boormachine\n", null));

        for (Case scenario : cases) {
            Uploaded uploaded = upload(scenario.prefix(), scenario.content(),
                    scenario.expectedRecordCount(), null);

            ScreeningOutcome outcome = screening.screen(uploaded.batchId());

            assertThat(outcome.status()).as(scenario.prefix()).isEqualTo(ImportBatchStatus.BLOCKED);
            assertThat(outcome.blockedCode()).as(scenario.prefix()).isNotNull();
            assertThat(outcome.blockedReason()).as(scenario.prefix()).isNotNull();
            assertThat(outcome.validationResult()).as(scenario.prefix())
                    .isEqualTo(ValidationResult.BLOCKING);
            assertThat(issues(uploaded)).as(scenario.prefix())
                    .isNotEmpty()
                    .anySatisfy(issue -> assertThat(issue.getIssueCode()).isEqualTo(outcome.blockedCode()))
                    .allSatisfy(issue -> assertThat(issue.getControlLevel())
                            .isIn(ControlLevel.STRUCTURE, ControlLevel.DELIVERY));
        }
    }

    /**
     * Een blokkade schrijft haar issuerij precies één keer. Een tweede screening van dezelfde
     * levering onder dezelfde revisie wordt geweigerd en laat de probleemlijst onaangeroerd: zo kan
     * herverwerking nooit dezelfde vaststelling verdubbelen.
     */
    @Test
    void screeningABlockedDeliveryAgainNeverDuplicatesItsBlockageIssue() {
        Uploaded uploaded = upload("HIERTWICE", "");
        ScreeningOutcome first = screening.screen(uploaded.batchId());
        List<Long> issueIdsAfterFirst = issues(uploaded).stream().map(ImportRowIssue::getId).toList();

        Throwable second = catchThrowable(() -> screening.screen(uploaded.batchId()));

        assertThat(first.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(second).isInstanceOf(ConflictException.class);
        assertThat(issues(uploaded)).extracting(ImportRowIssue::getId)
                .containsExactlyElementsOf(issueIdsAfterFirst);
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private List<ImportRowIssue> issues(Uploaded uploaded) {
        return rowIssues.findByBatchId(uploaded.batchId(), PageRequest.of(0, 1000)).getContent();
    }

    private long countAtLevel(Uploaded uploaded, ControlLevel level) {
        Long count = jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and control_level = ?", Long.class, uploaded.batchId(), level.name());
        return count == null ? 0L : count;
    }

    private long staged(Uploaded uploaded) {
        Long count = jdbc.queryForObject("select count(*) from import_candidate_stage where batch_id = ?",
                Long.class, uploaded.batchId());
        return count == null ? 0L : count;
    }

    private Uploaded upload(String prefix, String content) {
        return upload(prefix, content, null, null, null);
    }

    private Uploaded upload(String prefix, String content, Long expectedRecordCount, Long expectedByteSize) {
        return upload(prefix, content, expectedRecordCount, expectedByteSize, null);
    }

    private Uploaded upload(String prefix, String content, Long expectedRecordCount, Long expectedByteSize,
                            Consumer<ImportDefinitionRevision> revisionCustomiser) {
        String unique = "CH" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        revision.setStatus(RevisionStatus.ACTIVE);
        if (revisionCustomiser != null) {
            revisionCustomiser.accept(revision);
        }
        revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));

        DeliveryView view = intake.intake(task.getId(), unique, "tester@example.test", expectedRecordCount,
                expectedByteSize, "levering.csv",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))).delivery();
        return new Uploaded(view.deliveryId(), view.batch().batchId());
    }

    private record Uploaded(long deliveryId, long batchId) {
    }
}
