package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.IssueCaseDao;
import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.service.ScreeningRecoveryService.RecoveryReport;
import be.dda.catalogimport.service.testsupport.NoOpTransactionManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Claim-bewust opstartherstel (stap S4-c, beslissingslog 2026-10-01): {@code SCREENING} wordt enkel {@code FAILED}
 * met een dode claim (compare-and-set op de waargenomen token, vrijgeven in dezelfde wijziging), {@code MUTATING} met
 * een dode claim krijgt de token gewist, en een levende claim van een andere instantie blijft ongemoeid.
 * <p>
 * Zonder Spring-context of database; deze instantie is {@code host-1} met boot {@code boot-new}. De bestaande
 * {@code ScreeningRecoveryServiceTest} (Web) bewijst het herstel tegen de echte database.
 */
class ScreeningRecoveryServiceClaimTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private record Snapshot(long batchId, ImportBatchStatus status, UUID token) {
    }

    private ImportBatchRepository batches;
    private CandidateStageDao stage;
    private RowIssueDao rowIssues;
    private ScreeningRecoveryService recovery;
    private final List<Snapshot> saved = new ArrayList<>();
    private final List<ImportBatch> screening = new ArrayList<>();
    private final List<ImportBatch> mutating = new ArrayList<>();

    @BeforeEach
    void setUp() {
        batches = mock(ImportBatchRepository.class);
        stage = mock(CandidateStageDao.class);
        rowIssues = mock(RowIssueDao.class);
        IssueCaseDao issueCases = mock(IssueCaseDao.class);
        when(batches.findByStatus(ImportBatchStatus.SCREENING)).thenReturn(screening);
        when(batches.findByStatus(ImportBatchStatus.MUTATING)).thenReturn(mutating);
        when(batches.saveAndFlush(any(ImportBatch.class))).thenAnswer(call -> {
            ImportBatch b = call.getArgument(0);
            saved.add(new Snapshot(b.getId(), b.getStatus(), b.getProcessingClaimToken()));
            return b;
        });
        BatchProcessingClaims claims = new BatchProcessingClaims(batches, Clock.fixed(NOW, ZoneOffset.UTC), "PT60M",
                "host-1", "boot-new");
        recovery = new ScreeningRecoveryService(batches, mock(TaskRunRepository.class), stage, rowIssues,
                mock(IssueGroupDao.class), issueCases, claims, new NoOpTransactionManager(), false);
    }

    /** Een batch die zowel in de lijst als onder het slot dezelfde is. */
    private ImportBatch batch(long id, ImportBatchStatus status, String claimedBy, Instant heartbeat) {
        ImportBatch batch = new ImportBatch(null, null, null, 1, "tester");
        ReflectionTestUtils.setField(batch, "id", id);
        batch.setStatus(status);
        if (claimedBy != null) {
            batch.claimProcessing(UUID.randomUUID(), claimedBy, heartbeat);
        }
        when(batches.findByIdForUpdate(id)).thenReturn(Optional.of(batch));
        (status == ImportBatchStatus.SCREENING ? screening : mutating).add(batch);
        return batch;
    }

    private Snapshot lastSaved(long id) {
        return saved.stream().filter(s -> s.batchId() == id).reduce((a, b) -> b).orElse(null);
    }

    // --- SCREENING -----------------------------------------------------------------------------------------------

    @Test
    void screeningWithoutClaimBecomesFailedAsBefore() {
        batch(1, ImportBatchStatus.SCREENING, null, null);

        RecoveryReport report = recovery.recover();

        assertThat(report.failedBatchIds()).containsExactly(1L);
        assertThat(lastSaved(1)).isEqualTo(new Snapshot(1, ImportBatchStatus.FAILED, null));
        verify(stage).deleteByBatchId(1L);
    }

    @Test
    void screeningWithAnExpiredLeaseBecomesFailedAndReleasesTheClaimInTheSameChange() {
        ImportBatch batch = batch(2, ImportBatchStatus.SCREENING, "host-2/boot-x", NOW.minus(Duration.ofMinutes(61)));

        RecoveryReport report = recovery.recover();

        assertThat(report.failedBatchIds()).containsExactly(2L);
        assertThat(lastSaved(2)).isEqualTo(new Snapshot(2, ImportBatchStatus.FAILED, null));
        assertThat(batch.getProcessingClaimedBy()).isNull();
        assertThat(batch.getBlockedCode()).isEqualTo(ScreeningRecoveryService.CODE_SCREENING_INTERRUPTED);
    }

    @Test
    void screeningClaimedByAPreviousBootOfThisInstanceBecomesFailedAtOnce() {
        batch(3, ImportBatchStatus.SCREENING, "host-1/boot-old", NOW.minusSeconds(10));

        assertThat(recovery.recover().failedBatchIds()).containsExactly(3L);
        assertThat(lastSaved(3).token()).isNull();
    }

    @Test
    void screeningWithALiveClaimOfAnotherInstanceIsLeftUntouched() {
        ImportBatch batch = batch(4, ImportBatchStatus.SCREENING, "host-2/boot-x", NOW.minus(Duration.ofMinutes(5)));
        UUID token = batch.getProcessingClaimToken();

        RecoveryReport report = recovery.recover();

        assertThat(report.failedBatchIds()).isEmpty();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.SCREENING);
        assertThat(batch.getProcessingClaimToken()).isEqualTo(token);
        assertThat(saved).isEmpty();
        verify(stage, never()).deleteByBatchId(anyLong());
        verify(rowIssues, never()).deleteByBatchId(anyLong());
    }

    @Test
    void screeningWhoseTokenChangedSinceTheObservationIsLeftUntouched() {
        // Waargenomen in de lijst met een dode claim ...
        ImportBatch observed = new ImportBatch(null, null, null, 1, "tester");
        ReflectionTestUtils.setField(observed, "id", 5L);
        observed.setStatus(ImportBatchStatus.SCREENING);
        observed.claimProcessing(UUID.randomUUID(), "host-2/boot-x", NOW.minus(Duration.ofHours(2)));
        screening.add(observed);
        // ... maar onder het slot draagt de batch een andere token (een andere worker nam ze intussen over). Ook al
        // lijkt die claim op zich dood, de compare-and-set faalt en er gebeurt niets.
        ImportBatch locked = new ImportBatch(null, null, null, 1, "tester");
        ReflectionTestUtils.setField(locked, "id", 5L);
        locked.setStatus(ImportBatchStatus.SCREENING);
        UUID newer = UUID.randomUUID();
        locked.claimProcessing(newer, "host-3/boot-y", NOW.minus(Duration.ofHours(2)));
        when(batches.findByIdForUpdate(5L)).thenReturn(Optional.of(locked));

        RecoveryReport report = recovery.recover();

        assertThat(report.failedBatchIds()).isEmpty();
        assertThat(locked.getStatus()).isEqualTo(ImportBatchStatus.SCREENING);
        assertThat(locked.getProcessingClaimToken()).isEqualTo(newer);
        verify(stage, never()).deleteByBatchId(anyLong());
    }

    @Test
    void screeningThatMovedOnUnderTheLockIsNotFailed() {
        ImportBatch observed = batch(6, ImportBatchStatus.SCREENING, null, null);
        ImportBatch locked = new ImportBatch(null, null, null, 1, "tester");
        ReflectionTestUtils.setField(locked, "id", 6L);
        locked.setStatus(ImportBatchStatus.MUTATING);
        when(batches.findByIdForUpdate(6L)).thenReturn(Optional.of(locked));

        assertThat(recovery.recover().failedBatchIds()).isEmpty();
        assertThat(observed.getStatus()).isEqualTo(ImportBatchStatus.SCREENING);
        assertThat(locked.getStatus()).isEqualTo(ImportBatchStatus.MUTATING);
        verify(stage, never()).deleteByBatchId(anyLong());
    }

    // --- MUTATING ------------------------------------------------------------------------------------------------

    @Test
    void mutatingWithADeadClaimGetsItsTokenClearedAndStaysResumable() {
        ImportBatch batch = batch(7, ImportBatchStatus.MUTATING, "host-1/boot-old", NOW.minusSeconds(10));

        RecoveryReport report = recovery.recover();

        assertThat(report.resumableBatchIds()).containsExactly(7L);
        assertThat(lastSaved(7)).isEqualTo(new Snapshot(7, ImportBatchStatus.MUTATING, null));
        assertThat(batch.getProcessingHeartbeatAt()).isNull();
        verify(stage, never()).deleteByBatchId(anyLong());
    }

    @Test
    void mutatingWithoutClaimIsResumableAndNotWritten() {
        batch(8, ImportBatchStatus.MUTATING, null, null);

        assertThat(recovery.recover().resumableBatchIds()).containsExactly(8L);
        assertThat(saved).isEmpty();
    }

    @Test
    void mutatingWithALiveClaimOfAnotherInstanceIsLeftUntouchedAndNotReportedResumable() {
        ImportBatch batch = batch(9, ImportBatchStatus.MUTATING, "host-2/boot-x", NOW.minus(Duration.ofMinutes(59)));
        UUID token = batch.getProcessingClaimToken();

        RecoveryReport report = recovery.recover();

        assertThat(report.resumableBatchIds()).isEmpty();
        assertThat(batch.getProcessingClaimToken()).isEqualTo(token);
        assertThat(saved).isEmpty();
    }

    @Test
    void oneFailingBatchDoesNotStopTheOthers() {
        batch(10, ImportBatchStatus.SCREENING, null, null);
        batch(11, ImportBatchStatus.SCREENING, null, null);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(stage).deleteByBatchId(10L);

        assertThat(recovery.recover().failedBatchIds()).containsExactly(11L);
    }
}
