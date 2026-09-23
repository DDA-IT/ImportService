package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import be.dda.catalogimport.service.PublicationBundleService.Membership;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
 * Bouwstap 4b: R-BAS-02, de accept-baseline-wacht (docs/design/fase4-publication-bundle-design.md
 * "Important technical constraint discovered" in par. 9). {@code accept-baseline} en een actief
 * bundellidmaatschap sluiten elkaar per batch uit; de weigering gebeurt vóór er iets geschreven wordt.
 * Elke test bouwt een eigen keten met unieke codes: de H2-database is gedeeld.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class BundleBaselineInteractionTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;OMSCHRIJVING\n";
    private static final String ACCEPTED_BY = "jan.peeters@example.test";
    private static final String REASON = "Eerste nulmeting van de leverancierscatalogus";

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
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void acceptBaselineOnABatchWithoutABundleWorksUnchanged() {
        Fixture f = fixture("NOBUNDLE");
        long batchId = screenedBatch(f, "REF-1");

        SourceStateBaselineService.BaselineAcceptance result = baseline.acceptBaseline(batchId, ACCEPTED_BY, REASON);

        assertThat(result.status()).isEqualTo("BASELINE_ACCEPTED");
        assertThat(stateRowCount(f)).isEqualTo(5L);
    }

    @Test
    void acceptBaselineOnABatchWithAnActiveBundleMembershipIsRefusedAndWritesNothing() {
        Fixture f = fixture("INBUNDLE");
        long batchId = screenedBatch(f, "REF-1");
        BundleReference bundle = bundleService.createBundle("BND-BASELINE", null,
                be.dda.catalogimport.domain.PublicationTargetMode.SIMULATION, null, null, ACCEPTED_BY);
        bundleService.addBatches(bundle.id(), List.of(batchId), ACCEPTED_BY);

        assertThatThrownBy(() -> baseline.acceptBaseline(batchId, ACCEPTED_BY, REASON))
                .isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", SourceStateBaselineService.CODE_BATCH_IN_PUBLICATION_BUNDLE);

        // Niets geschreven: geen bronstaatrijen, geen SKIPPED-mutaties, batch blijft SCREENED.
        assertThat(stateRowCount(f)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from import_mutation where batch_id = ? "
                + "and status = 'SKIPPED'", Long.class, batchId)).isZero();
        assertThat(batches.findById(batchId).orElseThrow().getStatus()).isEqualTo(ImportBatchStatus.SCREENED);
        assertThat(batches.findById(batchId).orElseThrow().getBaselineAcceptedBy()).isNull();
    }

    @Test
    void acceptBaselineWorksAgainAfterTheMembershipIsRemoved() {
        Fixture f = fixture("REACCEPT");
        long batchId = screenedBatch(f, "REF-1");
        BundleReference bundle = bundleService.createBundle("BND-REACCEPT", null,
                be.dda.catalogimport.domain.PublicationTargetMode.SIMULATION, null, null, ACCEPTED_BY);
        bundleService.addBatches(bundle.id(), List.of(batchId), ACCEPTED_BY);

        assertThatThrownBy(() -> baseline.acceptBaseline(batchId, ACCEPTED_BY, REASON))
                .isInstanceOf(ConflictException.class);

        Membership removed = bundleService.removeBatch(bundle.id(), batchId, "remover@example.test",
                "Batch uit bundel gehaald voor herbeoordeling");
        assertThat(removed.active()).isFalse();

        SourceStateBaselineService.BaselineAcceptance result = baseline.acceptBaseline(batchId, ACCEPTED_BY, REASON);
        assertThat(result.status()).isEqualTo("BASELINE_ACCEPTED");
        assertThat(stateRowCount(f)).isEqualTo(5L);
    }

    // --- Helpers ------------------------------------------------------------------------------------

    private long stateRowCount(Fixture f) {
        Long count = jdbc.queryForObject("select count(*) from catalog_source_state where import_link_id = ?",
                Long.class, f.linkId());
        return count == null ? 0L : count;
    }

    private long screenedBatch(Fixture f, String reference) {
        StringBuilder csv = new StringBuilder(HEADER);
        for (String row : new String[] {
                "ACME;G1;R1;1,50;Boormachine",
                "ACME;G1;R2;2,25;Schroevendraaier",
                "ACME;G1;R3;3,00;Hamer",
                "ACME;G1;R4;4,00;Zaag",
                "ACME;G1;R5;5,00;Beitel"}) {
            csv.append(row).append('\n');
        }
        var delivery = intake.intake(f.taskId(), reference, "tester@example.test", null, null, "levering.csv",
                new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8))).delivery();
        long batchId = delivery.batch().batchId();
        ScreeningOutcome outcome = screening.screen(batchId);
        assertThat(outcome.status()).isEqualTo(ImportBatchStatus.SCREENED);
        return batchId;
    }

    private Fixture fixture(String prefix) {
        String unique = "BBI" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet() + "-" + prefix;
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
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak", TaskTriggerType.MANUAL));
        return new Fixture(task.getId(), link.getId());
    }

    private record Fixture(long taskId, long linkId) {
    }
}
