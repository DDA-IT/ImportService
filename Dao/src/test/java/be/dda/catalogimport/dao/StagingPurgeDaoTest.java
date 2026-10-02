package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.dao.CandidatePriceDao.PriceRow;
import be.dda.catalogimport.dao.CandidateReferenceDao.ReferenceRow;
import be.dda.catalogimport.dao.CandidateStageDao.StageRow;
import be.dda.catalogimport.dao.StagingPurgeDao.StagingCounts;
import be.dda.catalogimport.domain.CurrencyOrigin;
import be.dda.catalogimport.domain.DeliveryFile;
import be.dda.catalogimport.domain.DiscountCodeState;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stap 7, S7-P2 (Dao): {@link StagingPurgeDao#deleteChunk} verwijdert enkel de staging van de doelbatch, in chunks
 * van laag naar hoog regelnummer, met prijzen en referenties; en {@link StagingRetentionDao#findStagingPurgeCandidates}
 * selecteert enkel BASELINE_ACCEPTED-batches die vóór de grens aanvaard zijn en nog niet opgeruimd werden.
 * Tegen de echte PostgreSQL, transactioneel terugrollend.
 */
@DaoTest
class StagingPurgeDaoTest {

    @Autowired
    private StagingPurgeDao purge;
    @Autowired
    private StagingRetentionDao guard;
    @Autowired
    private CandidateStageDao stage;
    @Autowired
    private CandidatePriceDao prices;
    @Autowired
    private CandidateReferenceDao references;
    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private DeliveryFileRepository deliveryFiles;
    @Autowired
    private DaoFixtures fixtures;
    @Autowired
    private JdbcTemplate jdbc;

    // --- chunked delete ------------------------------------------------------------------------------

    @Test
    void chunksDeleteOnlyTheTargetBatchFromLowToHighUntilNothingRemains() {
        long target = stagedBatch(7);
        long other = stagedBatch(5);
        StagingCounts otherBefore = purge.count(other);
        assertThat(purge.count(target)).isEqualTo(new StagingCounts(7, 14, 7));

        StagingCounts first = purge.deleteChunk(target, 3);
        assertThat(first).isEqualTo(new StagingCounts(3, 6, 3));
        assertThat(remainingRowNumbers(target)).containsExactly(4L, 5L, 6L, 7L);

        StagingCounts second = purge.deleteChunk(target, 3);
        assertThat(second).isEqualTo(new StagingCounts(3, 6, 3));
        assertThat(remainingRowNumbers(target)).containsExactly(7L);

        StagingCounts third = purge.deleteChunk(target, 3);
        assertThat(third).isEqualTo(new StagingCounts(1, 2, 1));

        assertThat(purge.deleteChunk(target, 3)).isEqualTo(StagingCounts.NONE);
        assertThat(purge.count(target)).isEqualTo(StagingCounts.NONE);
        assertThat(purge.count(other)).as("the other batch is untouched").isEqualTo(otherBefore);
        assertThat(batches.findById(target).orElseThrow().getStagedRowCount())
                .as("staged_row_count is a historic counter").isEqualTo(7);
    }

    @Test
    void aBatchWithoutStagingDeletesNothing() {
        long batchId = baselineAcceptedBatch().getId();

        assertThat(purge.deleteChunk(batchId, 10)).isEqualTo(StagingCounts.NONE);
        assertThat(purge.count(batchId)).isEqualTo(StagingCounts.NONE);
    }

    @Test
    void aChunkLargerThanTheStagingDeletesEverythingAtOnce() {
        long batchId = stagedBatch(4);

        assertThat(purge.deleteChunk(batchId, 10_000)).isEqualTo(new StagingCounts(4, 8, 4));
        assertThat(purge.count(batchId)).isEqualTo(StagingCounts.NONE);
    }

    @Test
    void aNonPositiveChunkSizeIsRefused() {
        long batchId = stagedBatch(1);

        assertThatThrownBy(() -> purge.deleteChunk(batchId, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purge.deleteChunk(batchId, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(purge.count(batchId).stageRows()).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aChunkOutsideATransactionIsRefusedBeforeTouchingTheDatabase() {
        assertThatThrownBy(() -> purge.deleteChunk(Long.MAX_VALUE, 10)).isInstanceOf(IllegalStateException.class);
    }

    // --- candidates ----------------------------------------------------------------------------------

    @Test
    void candidatesAreBaselineAcceptedBeforeTheCutoffAndNotYetPurged() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        long old = acceptedAt(cutoff.minusSeconds(1));
        long exactlyAtCutoff = acceptedAt(cutoff);
        long recent = acceptedAt(cutoff.plusSeconds(3600));
        long alreadyPurged = acceptedAt(cutoff.minusSeconds(60));
        jdbc.update("update import_batch set staging_purged_at = now() where id = ?", alreadyPurged);
        long withoutTimestamp = baselineAcceptedBatch().getId();
        long failedOld = failedAt(cutoff.minusSeconds(60));

        List<Long> candidates = guard.findStagingPurgeCandidates(cutoff);

        assertThat(candidates).contains(old)
                .doesNotContain(exactlyAtCutoff, recent, alreadyPurged, withoutTimestamp, failedOld)
                .isSorted();
        assertThat(guard.isPurgeable(failedOld)).as("the guard itself still allows FAILED").isTrue();
    }

    @Test
    void aMissingCutoffIsRefused() {
        assertThatThrownBy(() -> guard.findStagingPurgeCandidates(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- hulpmethodes --------------------------------------------------------------------------------

    private ImportBatch baselineAcceptedBatch() {
        ImportBatch batch = fixtures.chain().newBatch(1);
        batch.setStatus(ImportBatchStatus.BASELINE_ACCEPTED);
        return batches.saveAndFlush(batch);
    }

    private long acceptedAt(Instant acceptedAt) {
        ImportBatch batch = fixtures.chain().newBatch(1);
        batch.setStatus(ImportBatchStatus.BASELINE_ACCEPTED);
        batch.recordBaselineAcceptance("tester", acceptedAt, "proef");
        return batches.saveAndFlush(batch).getId();
    }

    private long failedAt(Instant acceptedAt) {
        ImportBatch batch = fixtures.chain().newBatch(1);
        batch.setStatus(ImportBatchStatus.FAILED);
        batch.recordBaselineAcceptance("tester", acceptedAt, "synthetisch");
        return batches.saveAndFlush(batch).getId();
    }

    /** Een BASELINE_ACCEPTED-batch met {@code rows} stagerijen, elk met twee prijscomponenten en een referentie. */
    private long stagedBatch(int rows) {
        DaoFixtures.Chain chain = fixtures.chain();
        ImportBatch batch = chain.newBatch(1);
        batch.setStatus(ImportBatchStatus.BASELINE_ACCEPTED);
        batch.setStagedRowCount(rows);
        batch = batches.saveAndFlush(batch);
        DeliveryFile file = deliveryFiles.saveAndFlush(new DeliveryFile(chain.delivery(), 1, "levering.csv",
                "2026/10/02/" + chain.delivery().getId() + "/levering.csv", "a".repeat(64), 100L));
        long batchId = batch.getId();
        List<StageRow> stageRows = new ArrayList<>();
        List<PriceRow> priceRows = new ArrayList<>();
        List<ReferenceRow> referenceRows = new ArrayList<>();
        for (long row = 1; row <= rows; row++) {
            byte[] hash = new byte[32];
            hash[0] = (byte) row;
            stageRows.add(new StageRow(batchId, row, file.getId(), "ACME", "G1", "R" + row, null,
                    DiscountCodeState.NOT_USED, hash, new BigDecimal("1.000000"), "EUR", CurrencyOrigin.SOURCE,
                    "omschrijving " + row, hash, hash, null, hash, "P" + row, Instant.now()));
            priceRows.add(new PriceRow(batchId, row, "BASE_PRICE", new BigDecimal("1.000000"), null, "EUR", "OK"));
            priceRows.add(new PriceRow(batchId, row, "NET_PRICE", new BigDecimal("0.900000"),
                    new BigDecimal("0.900000000000"), "EUR", "OK"));
            referenceRows.add(new ReferenceRow(batchId, row, "EAN", "540000" + row, "540000" + row));
        }
        stage.insertBatch(stageRows);
        prices.insertBatch(priceRows);
        references.insertBatch(referenceRows);
        return batchId;
    }

    private List<Long> remainingRowNumbers(long batchId) {
        return jdbc.queryForList("select row_number from import_candidate_stage where batch_id = ? order by 1",
                Long.class, batchId);
    }
}
