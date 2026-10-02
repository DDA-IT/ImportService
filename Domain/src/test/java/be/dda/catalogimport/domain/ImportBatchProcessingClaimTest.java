package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportBatchProcessingClaimTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(60);

    private static ImportBatch newBatch() {
        ImportBatch batch = new ImportBatch(null, null, null, 1, "tester");
        batch.setStatus(ImportBatchStatus.SCREENING);
        return batch;
    }

    private static ImportBatch claimed(String claimedBy, Instant heartbeat) {
        ImportBatch batch = newBatch();
        batch.claimProcessing(UUID.randomUUID(), claimedBy, heartbeat);
        return batch;
    }

    @Test
    void claimOnOpenBatchSetsAllFieldsAndHeartbeatEqualsClaimedAt() {
        ImportBatch batch = newBatch();
        UUID token = UUID.randomUUID();
        batch.claimProcessing(token, "host-1/boot-a", NOW);

        assertThat(batch.getProcessingClaimToken()).isEqualTo(token);
        assertThat(batch.getProcessingClaimedBy()).isEqualTo("host-1/boot-a");
        assertThat(batch.getProcessingClaimedAt()).isEqualTo(NOW);
        assertThat(batch.getProcessingHeartbeatAt()).isEqualTo(NOW);
    }

    @Test
    void claimOnTerminalBatchIsRefused() {
        for (ImportBatchStatus status : ImportBatchStatus.values()) {
            if (!status.isTerminal()) {
                continue;
            }
            ImportBatch batch = newBatch();
            batch.setStatus(status);
            assertThatThrownBy(() -> batch.claimProcessing(UUID.randomUUID(), "h/b", NOW))
                    .as("terminal %s", status)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(batch.getProcessingClaimToken()).isNull();
        }
    }

    @Test
    void touchMovesOnlyTheHeartbeat() {
        ImportBatch batch = claimed("h/b", NOW);
        batch.touchProcessing(NOW.plusSeconds(30));

        assertThat(batch.getProcessingHeartbeatAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(batch.getProcessingClaimedAt()).isEqualTo(NOW);
    }

    @Test
    void touchWithoutClaimIsRefused() {
        assertThatThrownBy(() -> newBatch().touchProcessing(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releaseClearsAllFourFieldsAndIsIdempotent() {
        ImportBatch batch = claimed("h/b", NOW);
        batch.releaseProcessing();
        batch.releaseProcessing();

        assertThat(batch.getProcessingClaimToken()).isNull();
        assertThat(batch.getProcessingClaimedAt()).isNull();
        assertThat(batch.getProcessingHeartbeatAt()).isNull();
        assertThat(batch.getProcessingClaimedBy()).isNull();
    }

    @Test
    void setStatusDoesNotReleaseTheClaim() {
        ImportBatch batch = claimed("h/b", NOW);
        batch.setStatus(ImportBatchStatus.SCREENED);
        assertThat(batch.getProcessingClaimToken()).isNotNull();
    }

    @Test
    void noTokenIsNotAlive() {
        assertThat(newBatch().isProcessingClaimAlive(NOW, LEASE, "h", "b")).isFalse();
    }

    @Test
    void freshHeartbeatIsAlive() {
        assertThat(claimed("other/boot", NOW).isProcessingClaimAlive(NOW.plusSeconds(1), LEASE, "h", "b")).isTrue();
    }

    @Test
    void expiredLeaseIsDead() {
        ImportBatch batch = claimed("other/boot", NOW);
        assertThat(batch.isProcessingClaimAlive(NOW.plus(LEASE).plusMillis(1), LEASE, "h", "b")).isFalse();
    }

    @Test
    void heartbeatExactlyOnTheLeaseBoundaryIsStillAlive() {
        ImportBatch batch = claimed("other/boot", NOW);
        assertThat(batch.isProcessingClaimAlive(NOW.plus(LEASE), LEASE, "h", "b")).isTrue();
    }

    @Test
    void sameInstanceOtherBootIsDeadEvenWithFreshHeartbeat() {
        assertThat(claimed("host-1/boot-old", NOW).isProcessingClaimAlive(NOW, LEASE, "host-1", "boot-new"))
                .isFalse();
    }

    @Test
    void sameInstanceSameBootIsAlive() {
        assertThat(claimed("host-1/boot-a", NOW).isProcessingClaimAlive(NOW, LEASE, "host-1", "boot-a")).isTrue();
    }

    @Test
    void otherInstanceWithinLeaseIsAliveRegardlessOfBoot() {
        assertThat(claimed("host-2/boot-x", NOW).isProcessingClaimAlive(NOW, LEASE, "host-1", "boot-a")).isTrue();
    }

    @Test
    void hostnameLikeInstanceIdsAreMatchedExactly() {
        ImportBatch batch = claimed("app-01.dda.local/boot-old", NOW);
        assertThat(batch.isProcessingClaimAlive(NOW, LEASE, "app-01.dda.local", "boot-new")).isFalse();
        // prefix van de instantie is niet dezelfde instantie
        assertThat(batch.isProcessingClaimAlive(NOW, LEASE, "app-01", "boot-new")).isTrue();
    }

    @Test
    void splitsOnTheLastSlashSoInstanceIdMayContainSlash() {
        ImportBatch batch = claimed("region/host-1/boot-old", NOW);
        assertThat(batch.isProcessingClaimAlive(NOW, LEASE, "region/host-1", "boot-new")).isFalse();
        assertThat(batch.isProcessingClaimAlive(NOW, LEASE, "region/host-1", "boot-old")).isTrue();
        assertThat(batch.isProcessingClaimAlive(NOW, LEASE, "region", "host-1")).isTrue();
    }

    @Test
    void claimedByWithoutSlashFallsBackToLeaseOnly() {
        ImportBatch batch = claimed("legacy-owner", NOW);
        assertThat(batch.isProcessingClaimAlive(NOW, LEASE, "legacy-owner", "b")).isTrue();
    }
}
