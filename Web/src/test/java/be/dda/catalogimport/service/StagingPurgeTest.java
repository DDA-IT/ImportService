package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import be.dda.catalogimport.config.StagingPurgeSchedule;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.ImportDefinitionRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.ImportFieldCatalogRepository;
import be.dda.catalogimport.dao.ImportFieldMappingRepository;
import be.dda.catalogimport.dao.ImportLinkRepository;
import be.dda.catalogimport.dao.SourceOrganisationRepository;
import be.dda.catalogimport.dao.StagingPurgeDao;
import be.dda.catalogimport.dao.StagingPurgeDao.StagingCounts;
import be.dda.catalogimport.dao.StagingRetentionDao;
import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinition;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.SourceOrganisation;
import be.dda.catalogimport.domain.SourceOrganisationType;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import be.dda.catalogimport.service.StagingPurgeService.PurgeReport;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stap 7, S7-P2/S7-P3 (docs/decisions.md 2026-10-02 "Stap 7 uitgewerkt"): {@link StagingPurgeService} tegen de echte
 * PostgreSQL, op echte gescreende en aanvaarde leveringen.
 * <ul>
 *   <li>Een BASELINE_ACCEPTED-batch die langer dan de retentie geleden aanvaard is (het aanvaardingstijdstip wordt
 *       native acht dagen teruggezet) verliest haar staging, in meerdere chunks, en krijgt {@code staging_purged_at}.
 *       Row-issues, issuegroepen, mutaties, bronstaat (+prijzen, referenties), prijsobservaties, snapshots, bundels,
 *       de batch- en leveringsrij en {@code staged_row_count} blijven bit-voor-bit gelijk.</li>
 *   <li>Een tweede run verandert niets.</li>
 *   <li>SCREENED, BLOCKED, FAILED, een recent aanvaarde batch en een batch in een bevroren bundel blijven onaangeroerd.</li>
 *   <li>Een batch onder een rijslot wordt overgeslagen zonder te wachten en de volgende run ruimt ze op.</li>
 *   <li>De proefrun telt zonder iets te wijzigen.</li>
 *   <li>De geplande taak bestaat niet zolang {@code catalogimport.staging-retention.enabled} niet {@code true} is.</li>
 * </ul>
 * <b>Veiligheid van de test zelf.</b> De database kan andere (ook echte, lokale) batches bevatten. De kandidaatquery
 * wordt daarom echt uitgevoerd, maar haar uitkomst wordt beperkt tot de batches die deze test zelf aanmaakte: deze
 * test verwijdert nooit staging van andere batches.
 */
@SpringBootTest(properties = {
        "catalogimport.screening.stage-batch-size=50",
        "catalogimport.screening.mutation-chunk-size=50",
        "catalogimport.screening.recovery-on-startup=false",
        "catalogimport.staging-retention.chunk-size=2"})
@ActiveProfiles("local")
class StagingPurgeTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HEADER = "LEVERANCIER;GROEP;REFERENTIE;PRIJS;AKP;EAN\n";
    private static final String CREATOR = "jan.peeters@example.test";
    private static final String DECIDER = "piet.willems@example.test";
    private static final String FREEZER = "an.janssens@example.test";
    private static final int VALID_ROWS = 5;
    private static final long MUST_ANSWER_WITHIN_MILLIS = 5_000;

    @TempDir
    static Path archiveRoot;

    @DynamicPropertySource
    static void archiveProperties(DynamicPropertyRegistry registry) {
        registry.add("catalogimport.archive.root", () -> archiveRoot.toString());
    }

    @MockitoSpyBean
    private StagingRetentionDao guard;
    @MockitoSpyBean
    private StagingPurgeDao purgeDao;
    @Autowired
    private StagingPurgeService purge;
    @Autowired
    private DeliveryIntakeService intake;
    @Autowired
    private DeliveryScreeningService screening;
    @Autowired
    private SourceStateBaselineService baseline;
    @Autowired
    private PublicationBundleService bundleService;
    @Autowired
    private BundleDecisionService decisions;
    @Autowired
    private BundleFreezeService freezeService;
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
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbc;

    /** Batches die deze test aanmaakte; enkel die mogen opgeruimd worden. */
    private final Set<Long> own = new HashSet<>();
    /** De echte uitkomst van de laatste kandidaatquery, vóór de beperking tot {@link #own}. */
    private List<Long> lastRealCandidates = List.of();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void limitThePurgeToOwnBatches() {
        doAnswer(call -> {
            lastRealCandidates = (List<Long>) call.callRealMethod();
            return lastRealCandidates.stream().filter(own::contains).toList();
        }).when(guard).findStagingPurgeCandidates(any());
    }

    // --- opruimen ------------------------------------------------------------------------------------

    @Test
    void anOldBaselineAcceptedBatchLosesOnlyItsStagingInSeveralChunks() {
        long batchId = acceptedBatch();
        backdateAcceptance(batchId, 8);
        StagingCounts before = staging(batchId);
        assertThat(before.stageRows()).isEqualTo(VALID_ROWS);
        assertThat(before.priceRows()).isPositive();
        assertThat(before.referenceRows()).isEqualTo(VALID_ROWS);
        long stagedRowCount = stagedRowCount(batchId);
        assertThat(count("import_row_issue", batchId)).as("the fixture has a row issue").isPositive();
        assertThat(count("import_mutation", batchId)).isPositive();
        Map<String, String> retained = retained(batchId);

        PurgeReport report = purge.purge(false);

        assertThat(lastRealCandidates).contains(batchId);
        assertThat(report.purgedBatchIds()).containsExactly(batchId);
        assertThat(report.rows()).isEqualTo(before);
        assertThat(staging(batchId)).isEqualTo(StagingCounts.NONE);
        assertThat(stagingPurgedAt(batchId)).isNotNull();
        assertThat(stagedRowCount(batchId)).isEqualTo(stagedRowCount);
        assertThat(retained(batchId)).isEqualTo(retained);
        // 5 rijen in chunks van 2: drie chunks met rijen en een laatste lege die markeert.
        verify(purgeDao, atLeast(4)).deleteChunk(eq(batchId), eq(2));

        Instant purgedAt = stagingPurgedAt(batchId);
        PurgeReport second = purge.purge(false);

        assertThat(lastRealCandidates).as("a purged batch is never selected again").doesNotContain(batchId);
        assertThat(second.purgedBatchIds()).isEmpty();
        assertThat(second.rows()).isEqualTo(StagingCounts.NONE);
        assertThat(stagingPurgedAt(batchId)).isEqualTo(purgedAt);
        assertThat(retained(batchId)).isEqualTo(retained);
    }

    @Test
    void screenedBlockedFailedRecentAndFrozenBundleBatchesAreLeftAlone() {
        long screened = screenedBatch();
        backdateAcceptance(screened, 30);
        long blocked = screenedBatch();
        jdbc.update("update import_batch set status = 'BLOCKED' where id = ?", blocked);
        backdateAcceptance(blocked, 30);
        long failed = screenedBatch();
        jdbc.update("update import_batch set status = 'FAILED' where id = ?", failed);
        backdateAcceptance(failed, 30);
        long recent = acceptedBatch();
        long frozen = screenedBatch();
        long bundleId = createBundleWith(frozen);
        approveAndFreeze(bundleId);
        assertThat(snapshotHash(bundleId)).isNotNull();
        // Synthetisch, zoals in StagingRetentionGuardTest: in de echte flow sluit een bundel accept-baseline uit.
        jdbc.update("update import_batch set status = 'BASELINE_ACCEPTED' where id = ?", frozen);
        backdateAcceptance(frozen, 30);
        List<Long> untouched = List.of(screened, blocked, failed, recent, frozen);
        Map<Long, StagingCounts> stagingBefore = new LinkedHashMap<>();
        Map<Long, Map<String, String>> retainedBefore = new LinkedHashMap<>();
        for (long batchId : untouched) {
            stagingBefore.put(batchId, staging(batchId));
            retainedBefore.put(batchId, retained(batchId));
            assertThat(stagingBefore.get(batchId).stageRows()).as("batch %s has staging", batchId).isPositive();
        }
        String hashBefore = snapshotHash(bundleId);

        PurgeReport report = purge.purge(false);

        assertThat(lastRealCandidates).doesNotContainAnyElementsOf(untouched);
        assertThat(report.purgedBatchIds()).doesNotContainAnyElementsOf(untouched);
        for (long batchId : untouched) {
            assertThat(staging(batchId)).as("staging of batch %s", batchId).isEqualTo(stagingBefore.get(batchId));
            assertThat(retained(batchId)).as("retained data of batch %s", batchId)
                    .isEqualTo(retainedBefore.get(batchId));
            assertThat(stagingPurgedAt(batchId)).isNull();
        }
        assertThat(snapshotHash(bundleId)).isEqualTo(hashBefore);
    }

    @Test
    void aBatchUnderARowLockIsSkippedWithoutWaitingAndPurgedOnTheNextRun() throws Exception {
        long batchId = acceptedBatch();
        backdateAcceptance(batchId, 8);
        StagingCounts before = staging(batchId);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> a = holder.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                jdbc.queryForList("select id from import_batch where id = ? for update", batchId);
                locked.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).as("thread A holds the batch row lock").isTrue();

            long start = System.nanoTime();
            PurgeReport report = purge.purge(false);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                    .as("skipped without waiting for the lock").isLessThan(MUST_ANSWER_WITHIN_MILLIS);

            assertThat(report.busyBatchIds()).containsExactly(batchId);
            assertThat(report.purgedBatchIds()).isEmpty();
            assertThat(report.failedBatchIds()).isEmpty();
            release.countDown();
            a.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }
        assertThat(staging(batchId)).isEqualTo(before);
        assertThat(stagingPurgedAt(batchId)).isNull();
        verify(purgeDao, never()).deleteChunk(eq(batchId), anyInt());

        PurgeReport next = purge.purge(false);

        assertThat(next.purgedBatchIds()).containsExactly(batchId);
        assertThat(staging(batchId)).isEqualTo(StagingCounts.NONE);
        assertThat(stagingPurgedAt(batchId)).isNotNull();
    }

    @Test
    void aDryRunCountsWithoutChangingAnything() {
        long batchId = acceptedBatch();
        backdateAcceptance(batchId, 8);
        StagingCounts before = staging(batchId);
        Map<String, String> retained = retained(batchId);

        PurgeReport report = purge.purge(true);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.purgedBatchIds()).containsExactly(batchId);
        assertThat(report.rows()).isEqualTo(before);
        assertThat(staging(batchId)).isEqualTo(before);
        assertThat(stagingPurgedAt(batchId)).isNull();
        assertThat(retained(batchId)).isEqualTo(retained);
        verify(purgeDao, never()).deleteChunk(eq(batchId), anyInt());
    }

    // --- geplande taak -------------------------------------------------------------------------------

    @Test
    void theScheduledTaskDoesNotExistByDefault() {
        assertThat(context.getBeanNamesForType(StagingPurgeSchedule.class)).isEmpty();
        assertThat(context.containsBean("org.springframework.context.annotation.internalScheduledAnnotationProcessor"))
                .as("no scheduling infrastructure at all").isFalse();
    }

    // --- hulpmethodes --------------------------------------------------------------------------------

    private long screenedBatch() {
        long taskId = fixture();
        var delivery = intake.intake(taskId, "REF-" + SEQUENCE.incrementAndGet(), "tester@example.test", null, null,
                "levering.csv", new ByteArrayInputStream(csv().getBytes(StandardCharsets.UTF_8))).delivery();
        long batchId = delivery.batch().batchId();
        own.add(batchId);
        screening.screen(batchId);
        return batchId;
    }

    private long acceptedBatch() {
        long batchId = screenedBatch();
        baseline.acceptBaseline(batchId, CREATOR, "Nulmeting van de proef");
        return batchId;
    }

    private void backdateAcceptance(long batchId, int days) {
        jdbc.update("update import_batch set baseline_accepted_at = now() - make_interval(days => ?) where id = ?",
                days, batchId);
    }

    private long createBundleWith(long batchId) {
        BundleReference bundle = bundleService.createBundle("BND-SP-" + SEQUENCE.incrementAndGet() + "-" + batchId,
                null, PublicationTargetMode.SIMULATION, null, null, CREATOR);
        bundleService.addBatches(bundle.id(), List.of(batchId), CREATOR);
        return bundle.id();
    }

    private void approveAndFreeze(long bundleId) {
        decisions.decideGroup(bundleId, BundleDecisionKind.APPROVE, DECIDER, "Nagekeken",
                new DecisionFilter(null, MutationStatus.AWAITING_APPROVAL, null, null, null));
        freezeService.freeze(bundleId, FREEZER, "Prijsronde goedgekeurd");
    }

    private String snapshotHash(long bundleId) {
        return jdbc.queryForObject("select encode(snapshot_hash, 'hex') from publication_bundle where id = ?",
                String.class, bundleId);
    }

    private StagingCounts staging(long batchId) {
        return new StagingCounts(count("import_candidate_stage", batchId), count("import_candidate_price", batchId),
                count("import_candidate_reference", batchId));
    }

    private long count(String table, long batchId) {
        Long count = jdbc.queryForObject("select count(*) from " + table + " where batch_id = ?", Long.class, batchId);
        return count == null ? 0 : count;
    }

    private long stagedRowCount(long batchId) {
        return jdbc.queryForObject("select staged_row_count from import_batch where id = ?", Long.class, batchId);
    }

    private Instant stagingPurgedAt(long batchId) {
        java.sql.Timestamp value = jdbc.queryForObject("select staging_purged_at from import_batch where id = ?",
                java.sql.Timestamp.class, batchId);
        return value == null ? null : value.toInstant();
    }

    /**
     * Een vingerafdruk (aantal + md5 over de volledige rijen) van alles wat de opruiming NOOIT mag raken, voor deze
     * batch, haar levering, haar koppeling en haar bundels. De batchrij zelf zonder {@code staging_purged_at}.
     */
    private Map<String, String> retained(long batchId) {
        Map<String, String> result = new LinkedHashMap<>();
        String link = "(select import_link_id from import_batch where id = ?)";
        String delivery = "(select delivery_id from import_batch where id = ?)";
        result.put("import_batch", jdbc.queryForObject(
                "select (to_jsonb(b) - 'staging_purged_at')::text from import_batch b where id = ?", String.class,
                batchId));
        result.put("delivery", fingerprint("delivery", "id = " + delivery, batchId));
        result.put("delivery_file", fingerprint("delivery_file", "delivery_id = " + delivery, batchId));
        result.put("import_row_issue", fingerprint("import_row_issue", "batch_id = ?", batchId));
        result.put("import_issue_group", fingerprint("import_issue_group", "batch_id = ?", batchId));
        result.put("import_mutation", fingerprint("import_mutation", "batch_id = ?", batchId));
        result.put("catalog_source_state", fingerprint("catalog_source_state", "import_link_id = " + link, batchId));
        result.put("catalog_source_state_price", fingerprint("catalog_source_state_price",
                "source_state_id in (select id from catalog_source_state where import_link_id = " + link + ")",
                batchId));
        result.put("catalog_reference_state", fingerprint("catalog_reference_state", "import_link_id = " + link,
                batchId));
        result.put("catalog_price_observation", fingerprint("catalog_price_observation", "import_link_id = " + link,
                batchId));
        result.put("publication_bundle_batch", fingerprint("publication_bundle_batch", "batch_id = ?", batchId));
        result.put("publication_bundle", fingerprint("publication_bundle",
                "id in (select bundle_id from publication_bundle_batch where batch_id = ?)", batchId));
        result.put("publication_bundle_snapshot", fingerprint("publication_bundle_snapshot", "batch_id = ?",
                batchId));
        result.put("publication_bundle_snapshot_price", fingerprint("publication_bundle_snapshot_price",
                "snapshot_id in (select id from publication_bundle_snapshot where batch_id = ?)", batchId));
        return result;
    }

    private String fingerprint(String table, String where, Object... args) {
        return jdbc.queryForObject("select count(*) || ':' || coalesce(md5(string_agg(t::text, '|' order by "
                + "t::text)), '') from " + table + " t where " + where, String.class, args);
    }

    /**
     * Vijf geldige regels (elk met basisprijs, AKP en EAN) en een verworpen regel (ongeldige prijs): staging met
     * prijzen en referenties, plus een row-issue.
     */
    private String csv() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int row = 1; row <= VALID_ROWS; row++) {
            csv.append("ACME;G1;R").append(row).append(';').append(row).append("0,00;").append(row).append("8,00;E-")
                    .append(row).append('\n');
        }
        return csv.append("ACME;G1;RX;geen-prijs;1,00;E-X\n").toString();
    }

    /** Een eigen keten (bron, definitie, actieve revisie, koppeling, taak) met unieke codes; geeft het taak-id. */
    private long fixture() {
        String unique = "SPT" + Long.toString(System.nanoTime(), 36) + SEQUENCE.incrementAndGet();
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
        revision.setRecordCanonicalisationVersion(2);
        revision.setMaxCriticalSharePercent(new BigDecimal("100"));
        revision.setStatus(RevisionStatus.ACTIVE);
        ImportDefinitionRevision stored = revisions.saveAndFlush(revision);
        // Een prijscomponent (AKP) en een kritieke referentie (EAN): zo heeft de staging ook kindrijen.
        ImportFieldCatalogEntry akp = fieldCatalog.findById("AKP_PCT").orElseThrow();
        ImportFieldMapping price = new ImportFieldMapping(stored, 1, akp, FieldValueKind.SOURCE_FIELD,
                akp.getDataType(), akp.getDefaultOwner(), akp.getIdentityClass());
        price.setSourceReference("AKP");
        price.setPriceComponentCode(akp.getPriceComponentCode());
        fieldMappings.saveAndFlush(price);
        ImportFieldCatalogEntry ean = fieldCatalog.findById("EAN").orElseThrow();
        ImportFieldMapping reference = new ImportFieldMapping(stored, 2, ean, FieldValueKind.SOURCE_FIELD,
                ean.getDataType(), ean.getDefaultOwner(), ean.getIdentityClass());
        reference.setSourceReference("EAN");
        reference.setReferenceType(ean.getReferenceType());
        fieldMappings.saveAndFlush(reference);
        SourceOrganisation supplier = sourceOrganisations.saveAndFlush(
                new SourceOrganisation(unique + "-SUP", unique + "-SUP BV", SourceOrganisationType.SUPPLIER));
        ImportLink link = links.saveAndFlush(
                new ImportLink(unique + "-LINK", unique + " koppeling", definition, supplier, "PSARF050"));
        CatalogImportTask task = tasks.saveAndFlush(new CatalogImportTask(link, unique + "-taak",
                TaskTriggerType.MANUAL));
        return task.getId();
    }
}
