package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Stap 7, S7-P1: {@link ImportBatch#markStagingPurged} enkel op BASELINE_ACCEPTED en hoogstens één keer. */
class ImportBatchStagingPurgeTest {

    private static final Instant NOW = Instant.parse("2026-10-02T03:30:00Z");

    private static ImportBatch batch(ImportBatchStatus status) {
        ImportBatch batch = new ImportBatch(null, null, null, 1, "tester");
        batch.setStatus(status);
        return batch;
    }

    @Test
    void aNewBatchHasNoPurgeTimestamp() {
        assertThat(new ImportBatch(null, null, null, 1, "tester").getStagingPurgedAt()).isNull();
    }

    @Test
    void aBaselineAcceptedBatchCanBeMarkedOnce() {
        ImportBatch batch = batch(ImportBatchStatus.BASELINE_ACCEPTED);
        batch.setStagedRowCount(1234);

        batch.markStagingPurged(NOW);

        assertThat(batch.getStagingPurgedAt()).isEqualTo(NOW);
        assertThat(batch.getStagedRowCount()).as("the historic counter is not touched").isEqualTo(1234);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.BASELINE_ACCEPTED);
    }

    @Test
    void everyOtherStatusIsRefused() {
        for (ImportBatchStatus status : ImportBatchStatus.values()) {
            if (status == ImportBatchStatus.BASELINE_ACCEPTED) {
                continue;
            }
            ImportBatch batch = batch(status);
            assertThatThrownBy(() -> batch.markStagingPurged(NOW)).as("status %s", status)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(status.name());
            assertThat(batch.getStagingPurgedAt()).isNull();
        }
    }

    @Test
    void aSecondMarkNeverOverwritesTheFirstTimestamp() {
        ImportBatch batch = batch(ImportBatchStatus.BASELINE_ACCEPTED);
        batch.markStagingPurged(NOW);

        assertThatThrownBy(() -> batch.markStagingPurged(NOW.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(batch.getStagingPurgedAt()).isEqualTo(NOW);
    }

    @Test
    void aMissingTimestampIsRefused() {
        ImportBatch batch = batch(ImportBatchStatus.BASELINE_ACCEPTED);

        assertThatThrownBy(() -> batch.markStagingPurged(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(batch.getStagingPurgedAt()).isNull();
    }
}
