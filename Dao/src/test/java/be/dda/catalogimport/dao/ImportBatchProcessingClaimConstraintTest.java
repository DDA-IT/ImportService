package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verwerkingsclaim op {@code import_batch} (changeset 017): mapping en de twee CHECK-constraints. */
@DaoTest
class ImportBatchProcessingClaimConstraintTest {

    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private DaoFixtures fixtures;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aClaimOnAnOpenBatchIsStoredAndReadBackWithAllFields() {
        ImportBatch batch = batches.saveAndFlush(fixtures.chain().newBatch(1));
        UUID token = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        batch.claimProcessing(token, "host-1/boot-a", now);
        batches.saveAndFlush(batch);
        em.clear();

        ImportBatch reread = batches.findById(batch.getId()).orElseThrow();
        assertThat(reread.getProcessingClaimToken()).isEqualTo(token);
        assertThat(reread.getProcessingClaimedBy()).isEqualTo("host-1/boot-a");
        assertThat(reread.getProcessingClaimedAt()).isEqualTo(now);
        assertThat(reread.getProcessingHeartbeatAt()).isEqualTo(now);
    }

    @Test
    void aReleasedClaimIsStoredAsFourNulls() {
        ImportBatch batch = batches.saveAndFlush(fixtures.chain().newBatch(1));
        batch.claimProcessing(UUID.randomUUID(), "h/b", Instant.now());
        batches.saveAndFlush(batch);
        batch.releaseProcessing();
        batches.saveAndFlush(batch);
        em.clear();

        ImportBatch reread = batches.findById(batch.getId()).orElseThrow();
        assertThat(reread.getProcessingClaimToken()).isNull();
        assertThat(reread.getProcessingClaimedAt()).isNull();
        assertThat(reread.getProcessingHeartbeatAt()).isNull();
        assertThat(reread.getProcessingClaimedBy()).isNull();
    }

    @Test
    void aTerminalTransitionWhileTheClaimIsStillSetIsRefused() {
        ImportBatch batch = batches.saveAndFlush(fixtures.chain().newBatch(1));
        batch.claimProcessing(UUID.randomUUID(), "h/b", Instant.now());
        batches.saveAndFlush(batch);

        batch.setStatus(ImportBatchStatus.FAILED);
        assertThatThrownBy(() -> batches.saveAndFlush(batch))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_batch_claim_open");
    }

    @Test
    void aTerminalTransitionWithReleaseInTheSameFlushIsAccepted() {
        ImportBatch batch = batches.saveAndFlush(fixtures.chain().newBatch(1));
        batch.claimProcessing(UUID.randomUUID(), "h/b", Instant.now());
        batches.saveAndFlush(batch);

        batch.releaseProcessing();
        batch.setStatus(ImportBatchStatus.FAILED);
        batches.saveAndFlush(batch);
        assertThat(batch.getOpenMarker()).isNull();
    }

    @Test
    void aTerminalBatchWithATokenViaNativeUpdateIsRefused() {
        ImportBatch batch = batches.saveAndFlush(fixtures.chain().newBatch(1));
        batch.setStatus(ImportBatchStatus.FAILED);
        batches.saveAndFlush(batch);

        assertThatThrownBy(() -> jdbc.update(
                "update import_batch set processing_claim_token = ?, processing_claimed_at = now(),"
                        + " processing_heartbeat_at = now(), processing_claimed_by = 'h/b' where id = ?",
                UUID.randomUUID(), batch.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_batch_claim_open");
    }

    @Test
    void aHalfSetClaimIsRefused() {
        ImportBatch batch = batches.saveAndFlush(fixtures.chain().newBatch(1));

        // Token zonder de andere drie velden.
        assertThatThrownBy(() -> jdbc.update(
                "update import_batch set processing_claim_token = ? where id = ?",
                UUID.randomUUID(), batch.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_batch_claim_complete");
    }

    @Test
    void aClaimedByWithoutTokenIsRefused() {
        ImportBatch batch = batches.saveAndFlush(fixtures.chain().newBatch(1));

        assertThatThrownBy(() -> jdbc.update(
                "update import_batch set processing_claimed_by = 'h/b' where id = ?", batch.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_batch_claim_complete");
    }
}
