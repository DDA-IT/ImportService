package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class ImportBatchOpenMarkerTest {

    private static ImportBatch newBatch() {
        return new ImportBatch(null, null, null, 1, "tester");
    }

    @Test
    void openMarkerIsNullBeforeAnySync() {
        assertThat(newBatch().getOpenMarker()).isNull();
        assertThat(newBatch().getStatus()).isEqualTo(ImportBatchStatus.RECEIVED);
    }

    @Test
    void setStatusSetsMarkerTrueForOpenAndNullForTerminalStatuses() {
        for (ImportBatchStatus status : ImportBatchStatus.values()) {
            ImportBatch batch = newBatch();
            batch.setStatus(status);
            if (status.isTerminal()) {
                assertThat(batch.getOpenMarker()).as("terminal %s", status).isNull();
            } else {
                assertThat(batch.getOpenMarker()).as("open %s", status).isEqualTo(Boolean.TRUE);
            }
        }
    }

    @Test
    void openStatusesAreExactlyReceivedScreeningMutating() {
        EnumSet<ImportBatchStatus> open = EnumSet.noneOf(ImportBatchStatus.class);
        for (ImportBatchStatus status : ImportBatchStatus.values()) {
            if (!status.isTerminal()) {
                open.add(status);
            }
        }
        assertThat(open).containsExactlyInAnyOrder(ImportBatchStatus.RECEIVED, ImportBatchStatus.SCREENING,
                ImportBatchStatus.MUTATING);
    }

    @Test
    void markerFollowsStatusAcrossTransitions() {
        ImportBatch batch = newBatch();
        batch.setStatus(ImportBatchStatus.SCREENING);
        assertThat(batch.getOpenMarker()).isTrue();
        batch.setStatus(ImportBatchStatus.SCREENED);
        assertThat(batch.getOpenMarker()).isNull();
    }

    @Test
    void onPersistSyncsMarkerForEveryStatus() {
        for (ImportBatchStatus status : ImportBatchStatus.values()) {
            ImportBatch batch = newBatch();
            batch.setStatus(status);
            batch.onPersist();
            assertThat(batch.getOpenMarker()).as("%s", status).isEqualTo(status.isTerminal() ? null : Boolean.TRUE);
        }
    }

    @Test
    void onUpdateSyncsMarker() {
        ImportBatch batch = newBatch();
        batch.onPersist();
        assertThat(batch.getOpenMarker()).isTrue();
        batch.setStatus(ImportBatchStatus.FAILED);
        batch.onUpdate();
        assertThat(batch.getOpenMarker()).isNull();
    }

    @Test
    void onPersistSetsCreatedAtOnlyWhenAbsent() {
        ImportBatch batch = newBatch();
        assertThat(batch.getCreatedAt()).isNull();
        Instant before = Instant.now();
        batch.onPersist();
        Instant created = batch.getCreatedAt();
        assertThat(created).isNotNull().isAfterOrEqualTo(before);
        batch.onPersist();
        assertThat(batch.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void recordBaselineAcceptanceSetsAllAuditFieldsTogether() {
        ImportBatch batch = newBatch();
        Instant at = Instant.parse("2026-01-02T03:04:05Z");
        batch.recordBaselineAcceptance("alice", "sub-1", at, "nulmeting akkoord");
        assertThat(batch.getBaselineAcceptedBy()).isEqualTo("alice");
        assertThat(batch.getBaselineAcceptedBySubject()).isEqualTo("sub-1");
        assertThat(batch.getBaselineAcceptedAt()).isEqualTo(at);
        assertThat(batch.getBaselineAcceptReason()).isEqualTo("nulmeting akkoord");
    }

    @Test
    void recordBaselineAcceptanceWithoutSubjectLeavesSubjectNull() {
        ImportBatch batch = newBatch();
        Instant at = Instant.parse("2026-01-02T03:04:05Z");
        batch.recordBaselineAcceptance("alice", at, "reden");
        assertThat(batch.getBaselineAcceptedBy()).isEqualTo("alice");
        assertThat(batch.getBaselineAcceptedAt()).isEqualTo(at);
        assertThat(batch.getBaselineAcceptReason()).isEqualTo("reden");
        assertThat(batch.getBaselineAcceptedBySubject()).isNull();
    }
}
