package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
        failed.recordFailed(FINISHED_AT, "X", "y");
        assertThat(failed.getStatus().isTerminal()).isTrue();
        assertThat(failed.getActiveMarker()).isNull();
    }

    @Test
    void isUsedInSimulationCoversExactlyTheFourStatusesThatAreSet() {
        assertThat(PublicationRunStatus.values())
                .filteredOn(PublicationRunStatus::isUsedInSimulation)
                .containsExactlyInAnyOrder(PublicationRunStatus.REQUESTED, PublicationRunStatus.PREPARING,
                        PublicationRunStatus.SIMULATED, PublicationRunStatus.FAILED);
    }
}
