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
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
 * Fase 3a (ontwerp fase 3, afwijking D en R-THR-06): {@code import_batch.validation_result} is een
 * aparte statusas naast {@code status}. Deze test dekt de drie uitkomsten die in deze bouwstap al
 * berekenbaar zijn.
 * <p>
 * {@code REVIEW_REQUIRED} hoort bij bulkincidenten en wachtende creaties en komt pas met de drempels
 * in bouwstap 3h; die waarde wordt hier dus nooit gezet in plaats van geraden. Bij een technische
 * fout blijft het oordeel {@code null}: er is dan niets vastgesteld.
 * <p>
 * De uitkomst reist ook mee in de {@code result_summary} van de {@code IMPORT_MARKER}, zodat een
 * latere reconciliatie het oordeel niet uit de (wijzigbare) batchrij hoeft te halen.
 */
@SpringBootTest
@ActiveProfiles("local")
class ValidationResultTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String VALID_ROWS = "ACME;G1;R1;1,50;Boormachine\nACME;G1;R2;2,25;Schroevendraaier\n";

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
    private SourceStateBaselineService baseline;
    @Autowired
    private BatchQueryService queries;
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

    @Test
    void aCleanDeliveryIsValid() {
        // Een koppeling met een aanvaarde nulmeting. Sinds bouwstap 3h-3 is de állereerste levering
        // van een koppeling een initialisatie: haar creaties wachten op goedkeuring en dat is (tot de
        // herziening van 3h-5) een blokkerende vaststelling. Het eindoordeel van een gewone, schone
        // levering bestaat dus pas vanaf de tweede levering.
        long batchId = uploadAfterBaseline("VRVALID", HEADER + VALID_ROWS, HEADER + VALID_ROWS);

        ScreeningOutcome outcome = screening.screen(batchId);

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID);
        assertThat(batch(batchId).getValidationResult()).isEqualTo(ValidationResult.VALID);
        assertThat(queries.getBatch(batchId).validationResult()).isEqualTo("VALID");
        assertThat(markerSummary(batchId))
                .contains("outcome=SCREENED")
                // De bestaande sleutels blijven onaangeroerd; validationResult komt erbij.
                .contains("completenessProven=false")
                .contains("completenessReason=")
                .contains("fileSha256=")
                .contains("validationResult=VALID");
    }

    /** Een verwijderde BOM is een waarschuwing: de levering blijft bruikbaar, maar niet onopgemerkt. */
    @Test
    void aWarningMakesTheDeliveryValidWithWarnings() {
        // Zelfde reden als hierboven: pas na de aanvaarde nulmeting is een levering "enkel een
        // waarschuwing" en niet ook een initialisatie.
        long batchId = uploadAfterBaseline("VRWARN", HEADER + VALID_ROWS,
                "﻿" + HEADER + VALID_ROWS);

        ScreeningOutcome outcome = screening.screen(batchId);

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.VALID_WITH_WARNINGS);
        assertThat(markerSummary(batchId)).contains("validationResult=VALID_WITH_WARNINGS");
        assertThat(jdbc.queryForObject("select count(*) from import_row_issue where batch_id = ? "
                + "and severity = 'WARNING'", Long.class, batchId)).isEqualTo(1L);
    }

    @Test
    void aBlockedDeliveryIsBlocking() {
        long batchId = upload("VRBLOCK", HEADER);

        ScreeningOutcome outcome = screening.screen(batchId);

        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.BLOCKED);
        assertThat(outcome.validationResult()).isEqualTo(ValidationResult.BLOCKING);
        assertThat(batch(batchId).getValidationResult()).isEqualTo(ValidationResult.BLOCKING);
        assertThat(markerSummary(batchId))
                .contains("outcome=BLOCKED")
                .contains("validationResult=BLOCKING");
    }

    /** Zolang er niets vastgesteld is, blijft het oordeel leeg — nooit stilzwijgend VALID. */
    @Test
    void aBatchThatHasNotFinishedHasNoValidationResultYet() {
        long batchId = upload("VROPEN", HEADER + VALID_ROWS);

        assertThat(batch(batchId).getStatus()).isEqualTo(ImportBatchStatus.RECEIVED);
        assertThat(batch(batchId).getValidationResult()).isNull();
        assertThat(queries.getBatch(batchId).validationResult()).isNull();
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private ImportBatch batch(long batchId) {
        return batches.findById(batchId).orElseThrow();
    }

    private String markerSummary(long batchId) {
        return jdbc.queryForObject("select result_summary from import_mutation where batch_id = ? "
                + "and action_type = 'IMPORT_MARKER'", String.class, batchId);
    }

    /**
     * Levert eerst een nulmeting aan, aanvaardt die, en levert dan de te beoordelen inhoud op
     * dezelfde koppeling. Zo heeft de koppeling een bronstaat en is de tweede levering geen
     * initialisatie meer (bouwstap 3h-3, ontwerp par. 15.2).
     *
     * @return de batch van de tweede levering, nog niet gescreend
     */
    private long uploadAfterBaseline(String prefix, String baselineContent, String content) {
        long taskId = chain(prefix);
        long baselineBatch = deliver(taskId, prefix + "-BASE", baselineContent);
        screening.screen(baselineBatch);
        baseline.acceptBaseline(baselineBatch, "tester@example.test", "nulmeting");
        return deliver(taskId, prefix + "-NEXT", content);
    }

    private long upload(String prefix, String content) {
        return deliver(chain(prefix), prefix + "-1", content);
    }

    /** Eén eigen keten (bron, definitie, revisie, koppeling, taak) met unieke codes. */
    private long chain(String prefix) {
        String unique = "VR" + SEQUENCE.incrementAndGet() + "-" + prefix;
        SourceOrganisation organisation = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-ORG", unique + "-ORG BV", SourceOrganisationType.SUPPLIER));
        ImportDefinition definition = definitions.saveAndFlush(new ImportDefinition(
                organisation, unique + "-DEF", unique + " catalogus", "beheerder@example.test"));
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
        revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(
                new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return task.getId();
    }

    private long deliver(long taskId, String reference, String content) {
        return intake.intake(taskId, reference, "tester@example.test", null, null, "levering.csv",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)))
                .delivery().batch().batchId();
    }
}
