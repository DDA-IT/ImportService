package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.BundleCancellationService;
import be.dda.catalogimport.service.BundleDecisionService;
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.BundleFreezeService;
import be.dda.catalogimport.service.DeliveryIntakeService;
import be.dda.catalogimport.service.DeliveryScreeningService;
import be.dda.catalogimport.service.PublicationBundleService;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import be.dda.catalogimport.service.SourceStateBaselineService;
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
 * Bouwstap 5P-5 (docs/design/fase5-pub-design.md par. 1 en 6; docs/decisions.md 2026-09-26 "5-PUB (deel a):
 * ontwerp bindend"): bewijst dat {@link StagingRetentionDao} de drie voorwaarden voor opruimbare kandidaatstaging
 * afleidt. De guard is read-only; de enige delete is de geplande opruiming van stap 7 ({@code StagingPurgeService},
 * bewezen in {@code StagingPurgeTest}), die deze guard versmalt en onder het batchslot herhaalt.
 *
 * <h2>Wat hier bewezen wordt</h2>
 * <ul>
 *   <li><b>(a) Status:</b> alleen {@code FAILED} en {@code BASELINE_ACCEPTED} zijn opruimbaar. {@code SCREENED}
 *       (nog een bundelkandidaat), {@code BLOCKED} (onopgelost) en {@code RECEIVED} nooit.</li>
 *   <li><b>(b) Actief lidmaatschap:</b> een batch die actief lid is van een niet-geannuleerde bundel is nooit
 *       opruimbaar, ook niet als die bundel bevroren is en een snapshot draagt.</li>
 *   <li><b>(c) Snapshot:</b> elke bundel waarin de batch ooit zat moet een {@code snapshot_hash} dragen.
 *       Bundels die nooit bevroren zijn (dus zonder hash), ook geannuleerde of waaruit de batch is verwijderd,
 *       houden de staging vast (bewust conservatief).</li>
 *   <li>Een batch die nooit in een bundel zat, voldoet aan (b) en (c) automatisch.</li>
 *   <li>De uitkomst is oplopend gesorteerd op batch-id.</li>
 * </ul>
 * De batchstatus {@code BASELINE_ACCEPTED} wordt in de bundelscenario's synthetisch via JDBC gezet: in de echte
 * flow sluit een bundellidmaatschap accept-baseline uit. Dat is bewust: hier wordt enkel de guardlogica bewezen.
 * Elke test bouwt eigen ketens met unieke codes.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false"})
@ActiveProfiles("local")
class StagingRetentionGuardTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String FREEZER = "an.janssens@example.test";

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @Autowired
    private StagingRetentionDao guard;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private PublicationBundleService bundleService;
    @Autowired
    private BundleDecisionService decisions;
    @Autowired
    private BundleFreezeService freezeService;
    @Autowired
    private BundleCancellationService cancellation;
    @Autowired
    private SourceStateBaselineService baseline;
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
    private JdbcTemplate jdbc;

    // --- (a) Status ----------------------------------------------------------------------------------

    @Test
    void aScreenedBatchWithoutABundleIsNeverPurgeable() {
        long batchId = screenedBatch();

        assertThat(guard.findPurgeableBatches()).doesNotContain(batchId);
        assertThat(guard.isPurgeable(batchId)).isFalse();
    }

    @Test
    void aReceivedBatchIsNeverPurgeable() {
        long batchId = receivedBatch();

        assertThat(guard.findPurgeableBatches()).doesNotContain(batchId);
        assertThat(guard.isPurgeable(batchId)).isFalse();
    }

    @Test
    void aBlockedBatchWithoutABundleIsNeverPurgeable() {
        long batchId = screenedBatch();
        setStatus(batchId, "BLOCKED");

        assertThat(guard.findPurgeableBatches()).doesNotContain(batchId);
        assertThat(guard.isPurgeable(batchId)).isFalse();
    }

    @Test
    void aBaselineAcceptedBatchWithoutABundleIsPurgeable() {
        long batchId = screenedBatch();
        baseline.acceptBaseline(batchId, CREATOR, "Nulmeting van de proef");

        assertThat(guard.findPurgeableBatches()).contains(batchId);
        assertThat(guard.isPurgeable(batchId)).isTrue();
    }

    @Test
    void aFailedBatchWithoutABundleIsPurgeable() {
        long batchId = screenedBatch();
        setStatus(batchId, "FAILED");

        assertThat(guard.findPurgeableBatches()).contains(batchId);
        assertThat(guard.isPurgeable(batchId)).isTrue();
    }

    // --- (b) Actief lidmaatschap ---------------------------------------------------------------------

    @Test
    void aBatchActiveInAnAssemblingBundleIsNotPurgeableEvenWhenItsStatusIsPurgeable() {
        long batchId = screenedBatch();
        createBundleWith(batchId);
        setStatus(batchId, "BASELINE_ACCEPTED");

        assertThat(guard.isPurgeable(batchId)).isFalse();
        assertThat(guard.findPurgeableBatches()).doesNotContain(batchId);
    }

    @Test
    void aBatchInAFrozenBundleWithASnapshotIsStillNotPurgeableWhileItsMembershipIsActive() {
        long batchId = screenedBatch();
        long bundleId = createBundleWith(batchId);
        approveAndFreeze(bundleId);
        assertThat(snapshotHashPresent(bundleId)).isTrue();
        setStatus(batchId, "BASELINE_ACCEPTED");

        // Voorwaarde (b): het lidmaatschap van een bevroren bundel is nog actief.
        assertThat(guard.isPurgeable(batchId)).isFalse();
        assertThat(guard.findPurgeableBatches()).doesNotContain(batchId);
    }

    // --- (c) Snapshot --------------------------------------------------------------------------------

    @Test
    void aBatchInACancelledBundleThatWasFrozenWithASnapshotIsPurgeable() {
        long batchId = screenedBatch();
        long bundleId = createBundleWith(batchId);
        approveAndFreeze(bundleId);
        cancellation.cancel(bundleId, FREEZER, "Proef geannuleerd");
        assertThat(snapshotHashPresent(bundleId)).isTrue();
        setStatus(batchId, "BASELINE_ACCEPTED");

        assertThat(guard.isPurgeable(batchId)).isTrue();
        assertThat(guard.findPurgeableBatches()).contains(batchId);
    }

    @Test
    void aBatchInABundleThatWasCancelledBeforeAnySnapshotIsNotPurgeable() {
        long batchId = screenedBatch();
        long bundleId = createBundleWith(batchId);
        cancellation.cancel(bundleId, FREEZER, "Proef geannuleerd");
        assertThat(snapshotHashPresent(bundleId)).isFalse();
        setStatus(batchId, "BASELINE_ACCEPTED");

        // Voorwaarde (c): deze bundel draagt geen snapshot_hash; bewust conservatief.
        assertThat(guard.isPurgeable(batchId)).isFalse();
        assertThat(guard.findPurgeableBatches()).doesNotContain(batchId);
    }

    @Test
    void aBatchRemovedFromAnAssemblingBundleIsNotPurgeableBecauseThatBundleHasNoSnapshot() {
        long batchId = screenedBatch();
        long bundleId = createBundleWith(batchId);
        bundleService.removeBatch(bundleId, batchId, CREATOR, "Toch niet in deze bundel");
        setStatus(batchId, "BASELINE_ACCEPTED");

        assertThat(guard.isPurgeable(batchId)).isFalse();
        assertThat(guard.findPurgeableBatches()).doesNotContain(batchId);
    }

    // --- Uitkomst ------------------------------------------------------------------------------------

    @Test
    void theResultIsSortedAscendingAndAgreesWithIsPurgeable() {
        long first = screenedBatch();
        long second = screenedBatch();
        long third = screenedBatch();
        long notPurgeable = screenedBatch();
        setStatus(third, "FAILED");
        setStatus(first, "FAILED");
        setStatus(second, "FAILED");

        List<Long> purgeable = guard.findPurgeableBatches();

        assertThat(purgeable).contains(first, second, third).doesNotContain(notPurgeable);
        assertThat(purgeable).isSorted();
        assertThat(purgeable).allMatch(guard::isPurgeable);
    }

    // --- Hulpmethodes --------------------------------------------------------------------------------

    /** Een ontvangen maar niet gescreende batch (status RECEIVED). */
    private long receivedBatch() {
        long taskId = fixture();
        var delivery = intake.intake(taskId, "REF-" + SEQUENCE.incrementAndGet(), "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv().getBytes(StandardCharsets.UTF_8))).delivery();
        return delivery.batch().batchId();
    }

    /** Een echte levering, gescreend: status SCREENED, met een mutatie die op goedkeuring wacht. */
    private long screenedBatch() {
        long batchId = receivedBatch();
        screening.screen(batchId);
        return batchId;
    }

    private long createBundleWith(long batchId) {
        BundleReference bundle = bundleService.createBundle("BND-" + SEQUENCE.incrementAndGet() + "-" + batchId, null,
                PublicationTargetMode.SIMULATION, null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);
        return bundle.id();
    }

    private void approveAndFreeze(long bundleId) {
        decisions.decideGroup(bundleId, BundleDecisionKind.APPROVE, DECIDER, "Nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));
        freezeService.freeze(bundleId, FREEZER, "Prijsronde goedgekeurd");
    }

    private boolean snapshotHashPresent(long bundleId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from publication_bundle where id = ? and snapshot_hash is not null", Integer.class,
                bundleId);
        return count != null && count == 1;
    }

    /** Synthetische statuswissel: enkel de guardlogica wordt bewezen, niet de statusflow. */
    private void setStatus(long batchId, String status) {
        jdbc.update("update import_batch set status = ? where id = ?", status, batchId);
    }

    private String csv() {
        return HEADER + "ACME;G1;R1;1,00\n";
    }

    /** Een eigen keten (bron, definitie, actieve revisie, koppeling, taak) met unieke codes; geeft het taak-id. */
    private long fixture() {
        String unique = "SRG" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
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
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        revisions.saveAndFlush(revision);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return task.getId();
    }
}
