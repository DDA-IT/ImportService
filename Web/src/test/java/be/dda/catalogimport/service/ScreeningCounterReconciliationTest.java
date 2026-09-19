package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.ImportRecordFilterRepository;
import be.dda.catalogimport.dao.ImportRowIssueRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.ControlLevel;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.ImportRowIssue;
import be.dda.catalogimport.domain.MissingColumnBehaviour;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.support.CsvRecordStreamer;
import be.dda.catalogimport.service.support.RecordFilterEvaluator;
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
 * Fase 3b (ontwerp fase 3, R-FLT-02/R-FLT-04): de recordfilterstap in de staging en de vijf tellers
 * die er samen een sluitende telling van maken.
 * <p>
 * <b>De regel die hier bewezen wordt.</b> Het bronbestand wordt altijd volledig gelezen — header,
 * structuur, parsefouten en het ruwe recordaantal gelden over 100% van de levering — en elk gelezen
 * record komt in exact één teller terecht:
 * {@code raw = filtered_out + error_before_filter + rejected + valid}. Zonder die sluitende telling
 * kan niemand achteraf aantonen wat er met een record gebeurd is, en dat is precies wat een
 * catalogusimport moet kunnen verantwoorden.
 * <p>
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest
@ActiveProfiles("local")
class ScreeningCounterReconciliationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING;CULTURE\n";

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
    private ImportRecordFilterRepository recordFilters;
    @Autowired
    private ImportLinkRepository links;
    @Autowired
    private CatalogImportTaskRepository tasks;
    @Autowired
    private DeliveryRepository deliveries;
    @Autowired
    private ImportRowIssueRepository rowIssues;
    @Autowired
    private JdbcTemplate jdbc;

    // --- Met filter ------------------------------------------------------------------------------

    /**
     * Acht gelezen records: drie buiten de scope (BEFR), één onleesbaar vóór het filter (te weinig
     * kolommen), één verworpen binnen de scope (onleesbare prijs) en drie geldige.
     */
    @Test
    void countsEveryReadRecordInExactlyOneBucketWhenAFilterDefinesTheImportScope() {
        String csv = HEADER
                + "ACME;G1;R1;1,50;Boormachine;BENL\n"
                + "ACME;G1;R2;2,25;Schroevendraaier;BEFR\n"
                + "ACME;G1;R3;3,00;Hamer;BENL\n"
                + "ACME;G1;R4;onleesbaar;Zaag;BENL\n"
                + "ACME;G1;R5;5,00;Beitel;BEFR\n"
                + "ACME;G1;R6\n"
                + "ACME;G1;R7;7,00;Vijl;benl\n"
                + "ACME;G1;R8;8,00;Tang;BEFR\n";
        Uploaded uploaded = upload("FLTMIX", csv, this::includeBenl);

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rawRecordCount()).isEqualTo(8L);
        assertThat(outcome.filteredOutCount()).isEqualTo(3L);
        assertThat(outcome.errorBeforeFilterCount()).isEqualTo(1L);
        assertThat(outcome.rejectedRecordCount()).isEqualTo(1L);
        assertThat(outcome.validRecordCount()).isEqualTo(3L);
        assertThat(outcome.filteredOutCount() + outcome.errorBeforeFilterCount()
                + outcome.rejectedRecordCount() + outcome.validRecordCount())
                .isEqualTo(outcome.rawRecordCount());
        // En de tweede reconciliatie uit fase 2 blijft gelden.
        assertThat(outcome.newCount() + outcome.changedCount() + outcome.unchangedCount()
                + outcome.duplicateIdentityCount()).isEqualTo(outcome.validRecordCount());

        // Alleen records binnen de scope zijn gestaged; een BEFR-regel is nooit verder onderzocht.
        assertThat(stagedReferences(uploaded)).containsExactly("R1", "R3", "R7");
        // Een uitgesloten record levert géén probleemrij op: buiten de scope vallen is geen fout.
        assertThat(issues(uploaded)).extracting(ImportRowIssue::getIssueCode)
                .containsExactlyInAnyOrder(CsvRecordStreamer.CODE_ROW_COLUMN_COUNT_MISMATCH,
                        "PRICE_UNREADABLE");
    }

    /** Een REJECT-filterrij verwerpt binnen de scope: zichtbaar in de problemen én in de teller. */
    @Test
    void countsARecordRejectedByAFilterAsRejectedAndNotAsFilteredOut() {
        String csv = HEADER
                + "ACME;G1;R1;1,50;Boormachine;BENL\n"
                + "ACME;G1;R2;2,25;Schroevendraaier;ONBEKEND\n";
        Uploaded uploaded = upload("FLTREJ", csv, revision -> {
            ImportRecordFilter reject = new ImportRecordFilter(revision, 1, "CULTURE",
                    FilterOperator.EQUALS, "ONBEKEND", FilterOutcome.REJECT);
            reject.setNullBehaviour(FilterNullBehaviour.COMPARE_AS_EMPTY);
            recordFilters.saveAndFlush(reject);
        });

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.rawRecordCount()).isEqualTo(2L);
        assertThat(outcome.filteredOutCount()).isZero();
        assertThat(outcome.rejectedRecordCount()).isEqualTo(1L);
        assertThat(outcome.validRecordCount()).isEqualTo(1L);
        assertThat(issues(uploaded)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode())
                    .isEqualTo(RecordFilterEvaluator.CODE_FILTER_RECORD_REJECTED);
            assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.ERROR);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.RECORD);
            assertThat(issue.getRowNumber()).isEqualTo(3L);
        });
    }

    // --- Zonder filter: exact het fase 2-gedrag ---------------------------------------------------

    @Test
    void keepsThePhaseTwoCountersExactlyWhenNoFilterIsConfigured() {
        String csv = HEADER
                + "ACME;G1;R1;1,50;Boormachine;BENL\n"
                + "ACME;G1;R2;onleesbaar;Schroevendraaier;BEFR\n"
                + "ACME;G1;R3\n";
        Uploaded uploaded = upload("FLTNONE", csv, revision -> {
        });

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.rawRecordCount()).isEqualTo(3L);
        // Zonder importscope is er niets om buiten te vallen: beide nieuwe tellers zijn 0, niet null.
        assertThat(outcome.filteredOutCount()).isZero();
        assertThat(outcome.errorBeforeFilterCount()).isZero();
        // Elke regelfout telt als verworpen, exact zoals in fase 2 - ook de onleesbare regel.
        assertThat(outcome.rejectedRecordCount()).isEqualTo(2L);
        assertThat(outcome.validRecordCount()).isEqualTo(1L);
        assertThat(stagedReferences(uploaded)).containsExactly("R1");
    }

    /**
     * Een levering die vóór het parsen blokkeert, heeft géén tellers: die blijven {@code null}
     * ("onbekend") en worden nooit stil 0. Een 0 zou beweren dat er niets uitgefilterd is, terwijl er
     * niets gelezen is.
     */
    @Test
    void leavesTheNewCountersUnknownWhenTheFileWasNeverRead() {
        String csv = HEADER + "ACME;G1;R1;1,50;Boormachine;BENL\n";
        long actualBytes = csv.getBytes(StandardCharsets.UTF_8).length;
        Uploaded uploaded = upload("FLTNULL", csv, this::includeBenl, null, actualBytes + 5);

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(DeliveryScreeningService.CODE_BYTE_SIZE_MISMATCH);
        assertThat(outcome.rawRecordCount()).isNull();
        assertThat(outcome.filteredOutCount()).isNull();
        assertThat(outcome.errorBeforeFilterCount()).isNull();
    }

    /** Een blokkade ná een volledige lezing houdt de tellers wél: het bewijsmateriaal blijft staan. */
    @Test
    void keepsTheCountersWhenTheDeliveryIsBlockedAfterAFullRead() {
        String csv = HEADER
                + "ACME;G1;R1;1,50;Boormachine;BENL\n"
                + "ACME;G1;R2;2,25;Schroevendraaier;BEFR\n";
        Uploaded uploaded = upload("FLTBLK", csv, this::includeBenl, 99L);

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(DeliveryScreeningService.CODE_RECORD_COUNT_MISMATCH);
        assertThat(outcome.rawRecordCount()).isEqualTo(2L);
        assertThat(outcome.filteredOutCount()).isEqualTo(1L);
        assertThat(outcome.errorBeforeFilterCount()).isZero();
        assertThat(outcome.validRecordCount()).isEqualTo(1L);
    }

    // --- Ontbrekende filterkolom -------------------------------------------------------------------

    @Test
    void blocksTheWholeDeliveryWithOneStructureIssueWhenTheFilterColumnIsMissing() {
        String csv = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n"
                + "ACME;G1;R1;1,50;Boormachine\n"
                + "ACME;G1;R2;2,25;Schroevendraaier\n";
        Uploaded uploaded = upload("FLTMISS", csv, this::includeBenl);

        ScreeningOutcome outcome = screening.screen(uploaded.batchId());

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.blockedCode()).isEqualTo(RecordFilterEvaluator.CODE_FILTER_COLUMN_MISSING);
        assertThat(outcome.blockedReason()).contains("CULTURE");
        // Eén structuurprobleem voor de hele levering, geen probleem per regel.
        assertThat(issues(uploaded)).singleElement().satisfies(issue -> {
            assertThat(issue.getIssueCode()).isEqualTo(RecordFilterEvaluator.CODE_FILTER_COLUMN_MISSING);
            assertThat(issue.getSeverity()).isEqualTo(RowIssueSeverity.BLOCKING);
            assertThat(issue.getControlLevel()).isEqualTo(ControlLevel.STRUCTURE);
            assertThat(issue.getRowNumber()).isNull();
            assertThat(issue.getFieldName()).isEqualTo("CULTURE");
        });
    }

    // --- Helpers -------------------------------------------------------------------------------------

    private void includeBenl(ImportDefinitionRevision revision) {
        ImportRecordFilter filter = new ImportRecordFilter(revision, 1, "CULTURE", FilterOperator.EQUALS,
                "BENL", FilterOutcome.INCLUDE);
        filter.setNullBehaviour(FilterNullBehaviour.EXCLUDE);
        filter.setMissingColumnBehaviour(MissingColumnBehaviour.BLOCK);
        recordFilters.saveAndFlush(filter);
    }

    private List<ImportRowIssue> issues(Uploaded uploaded) {
        return rowIssues.findByBatchId(uploaded.batchId(), PageRequest.of(0, 100)).getContent();
    }

    private List<String> stagedReferences(Uploaded uploaded) {
        return jdbc.queryForList("select identity_supplier_reference from import_candidate_stage "
                + "where batch_id = ? order by row_number", String.class, uploaded.batchId());
    }

    private Uploaded upload(String prefix, String content, Consumer<ImportDefinitionRevision> filters) {
        return upload(prefix, content, filters, null, null);
    }

    private Uploaded upload(String prefix, String content, Consumer<ImportDefinitionRevision> filters,
                            Long expectedRecordCount) {
        return upload(prefix, content, filters, expectedRecordCount, null);
    }

    private Uploaded upload(String prefix, String content, Consumer<ImportDefinitionRevision> filters,
                            Long expectedRecordCount, Long expectedByteSize) {
        String unique = "RC" + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        filters.accept(stored);

        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));

        DeliveryView view = intake.intake(task.getId(), unique, "tester@example.test", expectedRecordCount,
                expectedByteSize, "levering.csv",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))).delivery();
        return new Uploaded(deliveries.findById(view.deliveryId()).orElseThrow().getId(),
                view.batch().batchId());
    }

    private record Uploaded(long deliveryId, long batchId) {
    }
}
