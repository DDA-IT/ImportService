package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import be.dda.catalogimport.dao.PsimportPreviewDao;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationRunRepository;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationRun;
import be.dda.catalogimport.domain.PublicationRunStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.service.PublicationArtifactStore.StoredArtifact;
import be.dda.catalogimport.service.PublicationRunService.PublicationRunView;
import be.dda.catalogimport.service.testsupport.NoOpTransactionManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Statusguard van de publicatierun (docs/decisions.md 2026-10-01, analyse-opvolging stap 3b): afronden
 * ({@code completeRun}/{@code failRun}, via {@link PublicationRunService#requestRun}) en afbreken
 * ({@link PublicationRunService#abortRun}) lezen de run vers <b>na</b> het schrijfslot op de bundel en leggen
 * enkel iets vast als de run dan nog {@code PREPARING} is. Precedent: {@code FetchRunService.close} (K-4c).
 * <p>
 * Zonder Spring-context of database: repositories en DAO's zijn Mockito-mocks, de transactiemanager doet niets.
 * Een gelijktijdige afbreking wordt nagebootst doordat {@code runs.findById} in de afrondtransactie een andere,
 * intussen afgebroken versie van dezelfde run teruggeeft — precies wat een verse lezing na het slot zou zien.
 */
class PublicationRunServiceGuardTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:15:30Z");
    private static final long BUNDLE_ID = 70L;
    private static final long RUN_ID = 501L;
    private static final byte[] CONTENT_HASH = {1, 2, 3};
    private static final String REFERENCE = "publication-artifacts/2026/10/01/501/psimport.csv";
    private static final String SHA = "ab".repeat(32);
    private static final ActorIdentity ACTOR = new ActorIdentity("jan", "sub-123");
    private static final String ABORT_MESSAGE = "Manually aborted by piet";

    private PublicationRunRepository runs;
    private PublicationBundleRepository bundles;
    private PublicationArtifactStore artifacts;
    private PublicationBundle bundle;
    private PublicationRunService service;
    /** De run zoals {@code createRun} ze aanmaakte (status PREPARING na stap 1). */
    private final AtomicReference<PublicationRun> created = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        runs = mock(PublicationRunRepository.class);
        bundles = mock(PublicationBundleRepository.class);
        PublicationBundleDao bundleDao = mock(PublicationBundleDao.class);
        PsimportPreviewDao previewDao = mock(PsimportPreviewDao.class);
        artifacts = mock(PublicationArtifactStore.class);
        bundle = mock(PublicationBundle.class);
        when(bundle.getId()).thenReturn(BUNDLE_ID);
        when(bundle.getStatus()).thenReturn(PublicationBundleStatus.FROZEN);
        when(bundle.getContentHash()).thenReturn(CONTENT_HASH);
        when(bundle.getSnapshotSpecVersion()).thenReturn("snapshot-v1");
        when(bundles.findByIdForUpdate(BUNDLE_ID)).thenReturn(Optional.of(bundle));
        when(bundleDao.computeContentHash(BUNDLE_ID)).thenReturn(CONTENT_HASH);
        when(runs.findByBundleIdAndActiveMarkerIsNotNull(BUNDLE_ID)).thenReturn(Optional.empty());
        when(runs.findMaxAttempt(eq(BUNDLE_ID), any())).thenReturn(0);
        when(runs.findBundleIdByRunId(RUN_ID)).thenReturn(Optional.of(BUNDLE_ID));
        when(runs.saveAndFlush(any(PublicationRun.class))).thenAnswer(call -> {
            PublicationRun run = call.getArgument(0);
            if (run.getId() == null) {
                ReflectionTestUtils.setField(run, "id", RUN_ID);
                created.set(run);
            }
            return run;
        });
        when(previewDao.count(anyLong())).thenReturn(0L);
        when(artifacts.write(eq(RUN_ID), any())).thenReturn(new StoredArtifact(REFERENCE, SHA, 1234L));
        service = new PublicationRunService(runs, bundles, bundleDao, previewDao, artifacts,
                new NoOpTransactionManager(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(60));
    }

    // --- Hulp --------------------------------------------------------------------------------------------------

    private PublicationRun preparingRun() {
        PublicationRun run = new PublicationRun(bundle, PublicationTargetMode.SIMULATION, 1, "jan", "sub-123",
                NOW.minusSeconds(60), CONTENT_HASH, null, "run:70:SIMULATION:1");
        ReflectionTestUtils.setField(run, "id", RUN_ID);
        run.markPreparing(NOW.minusSeconds(50));
        return run;
    }

    /** Zoals {@code abortRun} een run achterlaat: FAILED met FAILURE_MANUALLY_ABORTED (er is geen status ABORTED). */
    private PublicationRun abortedRun() {
        PublicationRun run = preparingRun();
        run.recordFailed(NOW.minusSeconds(10), PublicationRunService.FAILURE_MANUALLY_ABORTED, ABORT_MESSAGE);
        return run;
    }

    private PublicationRun simulatedRun() {
        PublicationRun run = preparingRun();
        run.recordSimulated(NOW.minusSeconds(10), REFERENCE, SHA, 1234L, new byte[] {9}, 4L, 0L);
        return run;
    }

    private static void assertStillAborted(PublicationRun run) {
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo(PublicationRunService.FAILURE_MANUALLY_ABORTED);
        assertThat(run.getFailureMessage()).isEqualTo(ABORT_MESSAGE);
        assertThat(run.getFinishedAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(run.getArtifactReference()).isNull();
        assertThat(run.getRowCount()).isNull();
        assertThat(run.getActiveMarker()).isNull();
    }

    // --- completeRun ------------------------------------------------------------------------------------------

    @Test
    void anAbortedRunIsNotSimulatedAfterwardsAndItsArtifactIsCleanedUp() {
        PublicationRun meanwhileAborted = abortedRun();
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(meanwhileAborted));

        PublicationRunView view = service.requestRun(BUNDLE_ID, PublicationTargetMode.SIMULATION, ACTOR);

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.failureCode()).isEqualTo(PublicationRunService.FAILURE_MANUALLY_ABORTED);
        assertThat(view.failureMessage()).isEqualTo(ABORT_MESSAGE);
        assertThat(view.artifactSha256()).isNull();
        assertStillAborted(meanwhileAborted);
        verify(runs, never()).saveAndFlush(meanwhileAborted);
        verify(artifacts).deleteQuietly(REFERENCE);
        // Slot op de bundel na het schrijven van het artefact en vóór de verse lezing van de run.
        InOrder order = inOrder(artifacts, bundles, runs);
        order.verify(artifacts).write(eq(RUN_ID), any());
        order.verify(bundles).findByIdForUpdate(BUNDLE_ID);
        order.verify(runs).findById(RUN_ID);
    }

    @Test
    void aRunThatTimedOutMeanwhileIsNotSimulatedEither() {
        PublicationRun timedOut = preparingRun();
        timedOut.recordFailed(NOW.minusSeconds(10), PublicationRunService.FAILURE_TIMED_OUT, "timed out");
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(timedOut));

        PublicationRunView view = service.requestRun(BUNDLE_ID, PublicationTargetMode.SIMULATION, ACTOR);

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.failureCode()).isEqualTo(PublicationRunService.FAILURE_TIMED_OUT);
        assertThat(timedOut.getArtifactReference()).isNull();
        verify(runs, never()).saveAndFlush(timedOut);
        verify(artifacts).deleteQuietly(REFERENCE);
    }

    @Test
    void aStillPreparingRunIsSimulatedAndSavedUnderTheLock() {
        when(runs.findById(RUN_ID)).thenAnswer(call -> Optional.of(created.get()));

        PublicationRunView view = service.requestRun(BUNDLE_ID, PublicationTargetMode.SIMULATION, ACTOR);

        PublicationRun run = created.get();
        assertThat(view.status()).isEqualTo("SIMULATED");
        assertThat(view.artifactSha256()).isEqualTo(SHA);
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.SIMULATED);
        assertThat(run.getArtifactReference()).isEqualTo(REFERENCE);
        assertThat(run.getFailureCode()).isNull();
        // Twee keer in stap 1 (REQUESTED, PREPARING) en één keer bij het afronden.
        verify(runs, times(3)).saveAndFlush(run);
        verify(artifacts, never()).deleteQuietly(anyString());
        InOrder order = inOrder(artifacts, bundles, runs);
        order.verify(artifacts).write(eq(RUN_ID), any());
        order.verify(bundles).findByIdForUpdate(BUNDLE_ID);
        order.verify(runs).findById(RUN_ID);
        order.verify(runs).saveAndFlush(run);
    }

    // --- failRun ----------------------------------------------------------------------------------------------

    @Test
    void anAbortedRunKeepsItsFailureCodeWhenTheArtifactFailsAfterwards() {
        when(artifacts.write(eq(RUN_ID), any()))
                .thenThrow(new UncheckedIOException("Cannot write publication artifact for run 501",
                        new IOException("disk full")));
        PublicationRun meanwhileAborted = abortedRun();
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(meanwhileAborted));

        PublicationRunView view = service.requestRun(BUNDLE_ID, PublicationTargetMode.SIMULATION, ACTOR);

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.failureCode()).isEqualTo(PublicationRunService.FAILURE_MANUALLY_ABORTED);
        assertThat(view.failureMessage()).isEqualTo(ABORT_MESSAGE);
        assertStillAborted(meanwhileAborted);
        verify(runs, never()).saveAndFlush(meanwhileAborted);
        InOrder order = inOrder(bundles, runs);
        order.verify(bundles, times(2)).findByIdForUpdate(BUNDLE_ID);
        order.verify(runs).findById(RUN_ID);
    }

    @Test
    void aStillPreparingRunIsMarkedFailedWithTheArtifactWriteCode() {
        when(artifacts.write(eq(RUN_ID), any()))
                .thenThrow(new UncheckedIOException("Cannot write publication artifact for run 501",
                        new IOException("disk full")));
        when(runs.findById(RUN_ID)).thenAnswer(call -> Optional.of(created.get()));

        PublicationRunView view = service.requestRun(BUNDLE_ID, PublicationTargetMode.SIMULATION, ACTOR);

        PublicationRun run = created.get();
        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.failureCode()).isEqualTo(PublicationRunService.FAILURE_ARTIFACT_WRITE_FAILED);
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.FAILED);
        assertThat(run.getFailureMessage()).startsWith("UncheckedIOException: ").doesNotContain("disk full");
        verify(runs, times(3)).saveAndFlush(run);
    }

    // --- abortRun ---------------------------------------------------------------------------------------------

    @Test
    void abortLocksTheBundleBeforeReadingTheRunAndAbortsAPreparingRun() {
        PublicationRun run = preparingRun();
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run));

        PublicationRunView view = service.abortRun(RUN_ID, ACTOR);

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.failureCode()).isEqualTo(PublicationRunService.FAILURE_MANUALLY_ABORTED);
        assertThat(run.getFailureMessage()).isEqualTo("Manually aborted by jan");
        assertThat(run.getFinishedAt()).isEqualTo(NOW);
        assertThat(run.getActiveMarker()).isNull();
        InOrder order = inOrder(runs, bundles);
        order.verify(runs).findBundleIdByRunId(RUN_ID);
        order.verify(bundles).findByIdForUpdate(BUNDLE_ID);
        order.verify(runs).findById(RUN_ID);
        order.verify(runs).saveAndFlush(run);
    }

    @Test
    void abortingARunThatWasSimulatedMeanwhileIsAConflictAndChangesNothing() {
        PublicationRun run = simulatedRun();
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.abortRun(RUN_ID, ACTOR))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo(PublicationRunService.CODE_RUN_NOT_STUCK);

        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.SIMULATED);
        assertThat(run.getArtifactReference()).isEqualTo(REFERENCE);
        assertThat(run.getFailureCode()).isNull();
        verify(bundles).findByIdForUpdate(BUNDLE_ID);
        verify(runs, never()).saveAndFlush(any(PublicationRun.class));
    }

    @Test
    void abortingTwiceIsAConflictTheSecondTimeAndKeepsTheFirstAbort() {
        PublicationRun run = abortedRun();
        when(runs.findById(RUN_ID)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.abortRun(RUN_ID, ACTOR))
                .isInstanceOf(ConflictException.class)
                .extracting(error -> ((ConflictException) error).getCode())
                .isEqualTo(PublicationRunService.CODE_RUN_NOT_STUCK);

        assertStillAborted(run);
        verify(runs, never()).saveAndFlush(any(PublicationRun.class));
    }

    @Test
    void abortingAnUnknownRunIsNotFoundWithoutTakingALock() {
        when(runs.findBundleIdByRunId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.abortRun(999L, ACTOR))
                .isInstanceOf(NotFoundException.class)
                .extracting(error -> ((NotFoundException) error).getCode())
                .isEqualTo(PublicationRunService.CODE_RUN_NOT_FOUND);

        verify(bundles, never()).findByIdForUpdate(anyLong());
        verify(runs, never()).findById(anyLong());
        verify(runs, never()).saveAndFlush(any(PublicationRun.class));
    }

    @Test
    void abortWithoutActorIsRejectedBeforeAnyRead() {
        assertThatThrownBy(() -> service.abortRun(RUN_ID, null)).isInstanceOf(IllegalArgumentException.class);

        verify(runs, never()).findBundleIdByRunId(anyLong());
        verify(bundles, never()).findByIdForUpdate(anyLong());
    }
}
