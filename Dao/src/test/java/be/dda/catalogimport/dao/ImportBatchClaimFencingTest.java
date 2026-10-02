package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.ImportBatch;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Fencing-update van de verwerkingsclaim (stap S4-b, {@link ImportBatchRepository#touchProcessingClaim}): enkel de
 * houder van de huidige token ververst het levensteken; een verkeerde of gewiste token raakt niets.
 */
@DaoTest
class ImportBatchClaimFencingTest {

    private static final Instant CLAIMED_AT = Instant.parse("2026-10-02T08:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-02T08:05:00Z");

    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private DaoFixtures fixtures;
    @Autowired
    private EntityManager em;

    private ImportBatch claimedBatch(UUID token) {
        ImportBatch batch = batches.saveAndFlush(fixtures.chain().newBatch(1));
        batch.claimProcessing(token, "host-1/boot-a", CLAIMED_AT);
        batches.saveAndFlush(batch);
        em.clear();
        return batch;
    }

    @Test
    void theCurrentTokenTouchesExactlyOneRowAndMovesOnlyTheHeartbeat() {
        UUID token = UUID.randomUUID();
        ImportBatch batch = claimedBatch(token);

        assertThat(batches.touchProcessingClaim(batch.getId(), token, LATER)).isEqualTo(1);

        em.clear();
        ImportBatch reread = batches.findById(batch.getId()).orElseThrow();
        assertThat(reread.getProcessingHeartbeatAt()).isEqualTo(LATER);
        assertThat(reread.getProcessingClaimedAt()).isEqualTo(CLAIMED_AT);
        assertThat(reread.getProcessingClaimToken()).isEqualTo(token);
        assertThat(reread.getProcessingClaimedBy()).isEqualTo("host-1/boot-a");
    }

    @Test
    void aWrongTokenTouchesNothing() {
        UUID token = UUID.randomUUID();
        ImportBatch batch = claimedBatch(token);

        assertThat(batches.touchProcessingClaim(batch.getId(), UUID.randomUUID(), LATER)).isZero();

        em.clear();
        assertThat(batches.findById(batch.getId()).orElseThrow().getProcessingHeartbeatAt())
                .isEqualTo(CLAIMED_AT);
    }

    @Test
    void aReleasedClaimTouchesNothing() {
        UUID token = UUID.randomUUID();
        ImportBatch batch = claimedBatch(token);
        ImportBatch loaded = batches.findById(batch.getId()).orElseThrow();
        loaded.releaseProcessing();
        batches.saveAndFlush(loaded);
        em.clear();

        assertThat(batches.touchProcessingClaim(batch.getId(), token, LATER)).isZero();

        em.clear();
        ImportBatch reread = batches.findById(batch.getId()).orElseThrow();
        assertThat(reread.getProcessingClaimToken()).isNull();
        assertThat(reread.getProcessingHeartbeatAt()).isNull();
    }

    @Test
    void aBatchThatNeverHadAClaimTouchesNothing() {
        ImportBatch batch = batches.saveAndFlush(fixtures.chain().newBatch(1));

        assertThat(batches.touchProcessingClaim(batch.getId(), UUID.randomUUID(), LATER)).isZero();
    }

    @Test
    void aReplacedClaimFencesOutThePreviousHolder() {
        UUID first = UUID.randomUUID();
        ImportBatch batch = claimedBatch(first);
        ImportBatch loaded = batches.findById(batch.getId()).orElseThrow();
        UUID second = UUID.randomUUID();
        loaded.claimProcessing(second, "host-2/boot-b", LATER.truncatedTo(ChronoUnit.MICROS));
        batches.saveAndFlush(loaded);
        em.clear();

        assertThat(batches.touchProcessingClaim(batch.getId(), first, LATER.plusSeconds(1))).isZero();
        assertThat(batches.touchProcessingClaim(batch.getId(), second, LATER.plusSeconds(1))).isEqualTo(1);
    }
}
