package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.StagingPurgeDao;
import be.dda.catalogimport.dao.StagingPurgeDao.StagingCounts;
import be.dda.catalogimport.dao.StagingRetentionDao;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.service.StagingPurgeService.PurgeReport;
import be.dda.catalogimport.service.testsupport.NoOpTransactionManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Stap 7, S7-P2: {@link StagingPurgeService} zonder Spring of database. Selectie en retentiegrens, het batchslot
 * NOWAIT (bezet = overslaan, ook tussen twee chunks), de herscheck onder het slot, de proefrun en de fail-fast
 * configuratie. De echte delete en sloten staan in {@code StagingPurgeDaoTest} (Dao) en {@code StagingPurgeTest} (Web).
 */
class StagingPurgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:30:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(7));
    private static final StagingCounts CHUNK = new StagingCounts(3, 6, 3);

    private ImportBatchRepository batches;
    private StagingRetentionDao guard;
    private StagingPurgeDao purgeDao;
    private StagingPurgeService service;

    @BeforeEach
    void setUp() {
        batches = mock(ImportBatchRepository.class);
        guard = mock(StagingRetentionDao.class);
        purgeDao = mock(StagingPurgeDao.class);
        service = service("P7D", "3");
        when(guard.isPurgeable(anyLong())).thenReturn(true);
        when(purgeDao.count(anyLong())).thenReturn(StagingCounts.NONE);
    }

    private StagingPurgeService service(String retention, String chunkSize) {
        return new StagingPurgeService(batches, guard, purgeDao, new NoOpTransactionManager(),
                Clock.fixed(NOW, ZoneOffset.UTC), retention, chunkSize);
    }

    private ImportBatch acceptedBatch(long id, Instant acceptedAt) {
        ImportBatch batch = new ImportBatch(null, null, null, 1, "tester");
        ReflectionTestUtils.setField(batch, "id", id);
        batch.setStatus(ImportBatchStatus.BASELINE_ACCEPTED);
        batch.recordBaselineAcceptance("tester", acceptedAt, "nulmeting");
        when(batches.findByIdForUpdateNowait(id)).thenReturn(Optional.of(batch));
        return batch;
    }

    // --- selectie en happy path ----------------------------------------------------------------------

    @Test
    void candidatesAreSelectedWithNowMinusTheRetention() {
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of());

        PurgeReport report = service.purge(false);

        verify(guard).findStagingPurgeCandidates(CUTOFF);
        assertThat(report.cutoff()).isEqualTo(CUTOFF);
        assertThat(report.candidateCount()).isZero();
        assertThat(report.rows()).isEqualTo(StagingCounts.NONE);
    }

    @Test
    void aCandidateIsPurgedInChunksAndMarkedInTheLastTransaction() {
        ImportBatch batch = acceptedBatch(10L, CUTOFF.minusSeconds(1));
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of(10L));
        when(purgeDao.deleteChunk(10L, 3)).thenReturn(CHUNK, new StagingCounts(1, 2, 1), StagingCounts.NONE);

        PurgeReport report = service.purge(false);

        assertThat(report.purgedBatchIds()).containsExactly(10L);
        assertThat(report.busyBatchIds()).isEmpty();
        assertThat(report.rows()).isEqualTo(new StagingCounts(4, 8, 4));
        assertThat(batch.getStagingPurgedAt()).isEqualTo(NOW);
        verify(purgeDao, times(3)).deleteChunk(10L, 3);
        // transactie 1 + drie chunks: elk neemt opnieuw het slot.
        verify(batches, times(4)).findByIdForUpdateNowait(10L);
        verify(guard, times(4)).isPurgeable(10L);
    }

    @Test
    void aCandidateWithoutStagingIsOnlyMarked() {
        ImportBatch batch = acceptedBatch(10L, CUTOFF.minusSeconds(1));
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of(10L));
        when(purgeDao.deleteChunk(10L, 3)).thenReturn(StagingCounts.NONE);

        PurgeReport report = service.purge(false);

        assertThat(report.purgedBatchIds()).containsExactly(10L);
        assertThat(report.rows()).isEqualTo(StagingCounts.NONE);
        assertThat(batch.getStagingPurgedAt()).isEqualTo(NOW);
    }

    // --- bezet slot ----------------------------------------------------------------------------------

    @Test
    void aLockedBatchIsSkippedWithoutDeletingAndTheNextBatchStillRuns() {
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of(10L, 20L));
        when(batches.findByIdForUpdateNowait(10L)).thenThrow(new CannotAcquireLockException("55P03"));
        ImportBatch second = acceptedBatch(20L, CUTOFF.minusSeconds(1));
        when(purgeDao.deleteChunk(20L, 3)).thenReturn(StagingCounts.NONE);

        PurgeReport report = service.purge(false);

        assertThat(report.busyBatchIds()).containsExactly(10L);
        assertThat(report.purgedBatchIds()).containsExactly(20L);
        assertThat(report.failedBatchIds()).isEmpty();
        verify(purgeDao, never()).deleteChunk(eq(10L), anyInt());
        assertThat(second.getStagingPurgedAt()).isEqualTo(NOW);
    }

    @Test
    void aLockTakenBetweenTwoChunksStopsThisBatchWithoutMarkingIt() {
        ImportBatch batch = new ImportBatch(null, null, null, 1, "tester");
        ReflectionTestUtils.setField(batch, "id", 10L);
        batch.setStatus(ImportBatchStatus.BASELINE_ACCEPTED);
        batch.recordBaselineAcceptance("tester", CUTOFF.minusSeconds(1), "nulmeting");
        when(batches.findByIdForUpdateNowait(10L))
                .thenReturn(Optional.of(batch))                  // transactie 1
                .thenReturn(Optional.of(batch))                  // chunk 1
                .thenThrow(new CannotAcquireLockException("55P03")); // chunk 2: bezet
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of(10L));
        when(purgeDao.deleteChunk(10L, 3)).thenReturn(CHUNK);

        PurgeReport report = service.purge(false);

        assertThat(report.busyBatchIds()).containsExactly(10L);
        assertThat(report.purgedBatchIds()).isEmpty();
        assertThat(report.rows()).as("the committed first chunk is reported").isEqualTo(CHUNK);
        assertThat(batch.getStagingPurgedAt()).as("the next run continues").isNull();
        verify(purgeDao, times(1)).deleteChunk(10L, 3);
    }

    // --- herscheck onder het slot --------------------------------------------------------------------

    @Test
    void theGuardIsRecheckedUnderTheLock() {
        ImportBatch batch = acceptedBatch(10L, CUTOFF.minusSeconds(1));
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of(10L));
        when(guard.isPurgeable(10L)).thenReturn(false);

        PurgeReport report = service.purge(false);

        assertThat(report.ineligibleBatchIds()).containsExactly(10L);
        verify(batches).findByIdForUpdateNowait(10L);
        verify(purgeDao, never()).deleteChunk(anyLong(), anyInt());
        assertThat(batch.getStagingPurgedAt()).isNull();
    }

    @Test
    void theStatusTimestampAndPurgeMarkerAreRecheckedOnTheLockedRow() {
        ImportBatch screened = new ImportBatch(null, null, null, 1, "tester");
        screened.setStatus(ImportBatchStatus.SCREENED);
        when(batches.findByIdForUpdateNowait(1L)).thenReturn(Optional.of(screened));
        acceptedBatch(2L, CUTOFF); // precies op de grens: nog binnen de retentie
        acceptedBatch(3L, CUTOFF.plusSeconds(60));
        ImportBatch purged = acceptedBatch(4L, CUTOFF.minusSeconds(60));
        purged.markStagingPurged(NOW.minusSeconds(3600));
        when(batches.findByIdForUpdateNowait(5L)).thenReturn(Optional.empty());
        acceptedBatch(6L, null);
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of(1L, 2L, 3L, 4L, 5L, 6L));

        PurgeReport report = service.purge(false);

        assertThat(report.ineligibleBatchIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(report.purgedBatchIds()).isEmpty();
        verify(purgeDao, never()).deleteChunk(anyLong(), anyInt());
        assertThat(purged.getStagingPurgedAt()).as("never overwritten").isEqualTo(NOW.minusSeconds(3600));
    }

    // --- fouten --------------------------------------------------------------------------------------

    @Test
    void anUnexpectedFailureIsReportedAndTheNextBatchStillRuns() {
        ImportBatch first = acceptedBatch(10L, CUTOFF.minusSeconds(1));
        ImportBatch second = acceptedBatch(20L, CUTOFF.minusSeconds(1));
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of(10L, 20L));
        when(purgeDao.deleteChunk(10L, 3)).thenThrow(new DataAccessResourceFailureException("connection reset"));
        when(purgeDao.deleteChunk(20L, 3)).thenReturn(StagingCounts.NONE);

        PurgeReport report = service.purge(false);

        assertThat(report.failedBatchIds()).containsExactly(10L);
        assertThat(report.purgedBatchIds()).containsExactly(20L);
        assertThat(first.getStagingPurgedAt()).isNull();
        assertThat(second.getStagingPurgedAt()).isEqualTo(NOW);
    }

    @Test
    void aBatchIsNeverMarkedWhileStagingRemains() {
        ImportBatch batch = acceptedBatch(10L, CUTOFF.minusSeconds(1));
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of(10L));
        when(purgeDao.deleteChunk(10L, 3)).thenReturn(StagingCounts.NONE);
        when(purgeDao.count(10L)).thenReturn(new StagingCounts(0, 1, 0));

        PurgeReport report = service.purge(false);

        assertThat(report.failedBatchIds()).containsExactly(10L);
        assertThat(batch.getStagingPurgedAt()).isNull();
    }

    // --- proefrun ------------------------------------------------------------------------------------

    @Test
    void aDryRunCountsWithoutLockingDeletingOrMarking() {
        when(guard.findStagingPurgeCandidates(CUTOFF)).thenReturn(List.of(10L, 20L));
        when(purgeDao.count(10L)).thenReturn(new StagingCounts(5, 10, 5));
        when(purgeDao.count(20L)).thenReturn(new StagingCounts(1, 1, 0));

        PurgeReport report = service.purge(true);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.purgedBatchIds()).containsExactly(10L, 20L);
        assertThat(report.rows()).isEqualTo(new StagingCounts(6, 11, 5));
        verify(batches, never()).findByIdForUpdateNowait(anyLong());
        verify(purgeDao, never()).deleteChunk(anyLong(), anyInt());
    }

    // --- configuratie --------------------------------------------------------------------------------

    @Test
    void theDefaultsAreSevenDaysAndTenThousandRows() {
        StagingPurgeService defaults = service(StagingPurgeService.DEFAULT_RETENTION,
                String.valueOf(StagingPurgeService.DEFAULT_CHUNK_SIZE));

        assertThat(defaults.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(defaults.chunkSize()).isEqualTo(10_000);
    }

    @Test
    void anInvalidOrNonPositiveRetentionStopsTheStartup() {
        for (String invalid : new String[] {"7 dagen", "", "PT0S", "-P1D", null}) {
            assertThatThrownBy(() -> service(invalid, "3")).as("retention '%s'", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalogimport.staging-retention.after");
        }
    }

    @Test
    void anInvalidOrNonPositiveChunkSizeStopsTheStartup() {
        for (String invalid : new String[] {"tienduizend", "", "0", "-5", "1.5", null}) {
            assertThatThrownBy(() -> service("P7D", invalid)).as("chunk size '%s'", invalid)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalogimport.staging-retention.chunk-size");
        }
    }
}
