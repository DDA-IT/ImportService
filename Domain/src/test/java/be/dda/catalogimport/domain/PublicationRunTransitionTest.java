package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PublicationRunTransitionTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-01-01T10:01:00Z");
    private static final Instant FINISHED_AT = Instant.parse("2026-01-01T10:02:00Z");

    private static PublicationRun newRun() {
        return new PublicationRun(null, null, 1, "tester", "sub", REQUESTED_AT,
                new byte[] {1}, new byte[] {2}, "key-1");
    }

    @Test
    void newRunIsRequestedWithActiveMarker() {
        PublicationRun run = newRun();
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.REQUESTED);
        assertThat(run.getActiveMarker()).isTrue();
        assertThat(run.getStartedAt()).isNull();
        assertThat(run.getFinishedAt()).isNull();
    }

    @Test
    void markPreparingSetsStatusAndStartedAtAndKeepsMarker() {
        PublicationRun run = newRun();
        run.markPreparing(STARTED_AT);
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.PREPARING);
        assertThat(run.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(run.getActiveMarker()).isTrue();
        assertThat(run.getFinishedAt()).isNull();
    }

    @Test
    void recordSimulatedFromPreparingSetsAllFieldsAndClearsMarker() {
        PublicationRun run = newRun();
        run.markPreparing(STARTED_AT);
        byte[] payloadHash = {9, 9};
        run.recordSimulated(FINISHED_AT, "artifacts/run-1.csv", "ab".repeat(32), 1234L, payloadHash, 10L, 2L);
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.SIMULATED);
        assertThat(run.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(run.getArtifactReference()).isEqualTo("artifacts/run-1.csv");
        assertThat(run.getArtifactSha256()).isEqualTo("ab".repeat(32));
        assertThat(run.getArtifactByteSize()).isEqualTo(1234L);
        assertThat(run.getPayloadHash()).isEqualTo(payloadHash);
        assertThat(run.getRowCount()).isEqualTo(10L);
        assertThat(run.getIncompleteRowCount()).isEqualTo(2L);
        assertThat(run.getActiveMarker()).isNull();
        assertThat(run.getFailureCode()).isNull();
        assertThat(run.getFailureMessage()).isNull();
    }

    @Test
    void recordSimulatedKeepsZeroCountsAsZero() {
        PublicationRun run = newRun();
        run.markPreparing(STARTED_AT);
        run.recordSimulated(FINISHED_AT, "ref", "00", 0L, new byte[0], 0L, 0L);
        assertThat(run.getRowCount()).isZero();
        assertThat(run.getIncompleteRowCount()).isZero();
    }

    @Test
    void recordFailedFromPreparingSetsAllFieldsAndClearsMarker() {
        PublicationRun run = newRun();
        run.markPreparing(STARTED_AT);
        run.recordFailed(FINISHED_AT, "ARTIFACT_WRITE_FAILED", "schijf vol");
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.FAILED);
        assertThat(run.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(run.getFailureCode()).isEqualTo("ARTIFACT_WRITE_FAILED");
        assertThat(run.getFailureMessage()).isEqualTo("schijf vol");
        assertThat(run.getActiveMarker()).isNull();
        assertThat(run.getArtifactReference()).isNull();
        assertThat(run.getRowCount()).isNull();
    }

    @Test
    void enumHelpersAreConsistentWithMarker() {
        for (PublicationRunStatus status : PublicationRunStatus.values()) {
            assertThat(status.isTerminal()).as("%s", status)
                    .isEqualTo(status == PublicationRunStatus.SIMULATED || status == PublicationRunStatus.FAILED);
        }
        PublicationRun run = newRun();
        assertThat(run.getStatus().isTerminal()).isFalse();
        assertThat(run.getActiveMarker()).isTrue();
        run.markPreparing(STARTED_AT);
        assertThat(run.getStatus().isTerminal()).isFalse();
        assertThat(run.getActiveMarker()).isTrue();
        run.recordSimulated(FINISHED_AT, "r", "s", 1L, new byte[] {1}, 1L, 0L);
        assertThat(run.getStatus().isTerminal()).isTrue();
        assertThat(run.getActiveMarker()).isNull();

        PublicationRun failed = newRun();
        failed.markPreparing(STARTED_AT);
        failed.recordFailed(FINISHED_AT, "X", "y");
        assertThat(failed.getStatus().isTerminal()).isTrue();
        assertThat(failed.getActiveMarker()).isNull();
    }

    // --- Stap 3b: enkel vanuit PREPARING afronden; de run blijft anders ongewijzigd -------------------------

    /** Een afgebroken run is FAILED met FAILURE_MANUALLY_ABORTED (er bestaat geen aparte status ABORTED). */
    private static PublicationRun abortedRun() {
        PublicationRun run = newRun();
        run.markPreparing(STARTED_AT);
        run.recordFailed(FINISHED_AT, "FAILURE_MANUALLY_ABORTED", "Manually aborted by jan");
        return run;
    }

    private static PublicationRun simulatedRun() {
        PublicationRun run = newRun();
        run.markPreparing(STARTED_AT);
        run.recordSimulated(FINISHED_AT, "artifacts/run-1.csv", "ab".repeat(32), 1234L, new byte[] {9}, 10L, 2L);
        return run;
    }

    @Test
    void recordSimulatedFromRequestedIsRejectedAndChangesNothing() {
        PublicationRun run = newRun();
        assertThatThrownBy(() -> run.recordSimulated(FINISHED_AT, "ref", "00", 1L, new byte[] {1}, 1L, 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REQUESTED");
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.REQUESTED);
        assertThat(run.getActiveMarker()).isTrue();
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getArtifactReference()).isNull();
        assertThat(run.getRowCount()).isNull();
    }

    @Test
    void recordFailedFromRequestedIsRejectedAndChangesNothing() {
        PublicationRun run = newRun();
        assertThatThrownBy(() -> run.recordFailed(FINISHED_AT, "X", "y"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.REQUESTED);
        assertThat(run.getActiveMarker()).isTrue();
        assertThat(run.getFinishedAt()).isNull();
        assertThat(run.getFailureCode()).isNull();
        assertThat(run.getFailureMessage()).isNull();
    }

    @Test
    void anAbortedRunCannotBeSimulatedAfterwardsAndKeepsItsAbortFields() {
        PublicationRun run = abortedRun();
        assertThatThrownBy(() -> run.recordSimulated(Instant.parse("2026-01-01T11:00:00Z"), "ref", "00", 1L,
                new byte[] {1}, 1L, 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAILED");
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("FAILURE_MANUALLY_ABORTED");
        assertThat(run.getFailureMessage()).isEqualTo("Manually aborted by jan");
        assertThat(run.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(run.getActiveMarker()).isNull();
        assertThat(run.getArtifactReference()).isNull();
        assertThat(run.getPayloadHash()).isNull();
        assertThat(run.getRowCount()).isNull();
    }

    @Test
    void anAbortedRunCannotFailAgainSoItsFailureCodeIsNeverOverwritten() {
        PublicationRun run = abortedRun();
        assertThatThrownBy(() -> run.recordFailed(Instant.parse("2026-01-01T11:00:00Z"), "ARTIFACT_WRITE_FAILED",
                "schijf vol"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("FAILURE_MANUALLY_ABORTED");
        assertThat(run.getFailureMessage()).isEqualTo("Manually aborted by jan");
        assertThat(run.getFinishedAt()).isEqualTo(FINISHED_AT);
    }

    @Test
    void aSimulatedRunCannotBeFinishedAgainEitherWay() {
        PublicationRun run = simulatedRun();
        assertThatThrownBy(() -> run.recordFailed(Instant.parse("2026-01-01T11:00:00Z"), "X", "y"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SIMULATED");
        assertThatThrownBy(() -> run.recordSimulated(Instant.parse("2026-01-01T11:00:00Z"), "other", "11", 2L,
                new byte[] {2}, 3L, 1L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.SIMULATED);
        assertThat(run.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThat(run.getArtifactReference()).isEqualTo("artifacts/run-1.csv");
        assertThat(run.getRowCount()).isEqualTo(10L);
        assertThat(run.getFailureCode()).isNull();
        assertThat(run.getActiveMarker()).isNull();
    }

    @Test
    void anOrdinaryFailedRunCannotBeSimulatedAfterwards() {
        PublicationRun run = newRun();
        run.markPreparing(STARTED_AT);
        run.recordFailed(FINISHED_AT, "PROJECTION_FAILED", "boom");
        assertThatThrownBy(() -> run.recordSimulated(FINISHED_AT, "ref", "00", 1L, new byte[] {1}, 1L, 0L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(run.getStatus()).isEqualTo(PublicationRunStatus.FAILED);
        assertThat(run.getFailureCode()).isEqualTo("PROJECTION_FAILED");
        assertThat(run.getArtifactReference()).isNull();
    }

    @Test
    void isUsedInSimulationCoversExactlyTheFourStatusesThatAreSet() {
        assertThat(PublicationRunStatus.values())
                .filteredOn(PublicationRunStatus::isUsedInSimulation)
                .containsExactlyInAnyOrder(PublicationRunStatus.REQUESTED, PublicationRunStatus.PREPARING,
                        PublicationRunStatus.SIMULATED, PublicationRunStatus.FAILED);
    }
}
