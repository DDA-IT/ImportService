package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Changeset 019 (stap 7, S7-P1): {@code import_batch.staging_purged_at} en {@code ck_import_batch_staging_purged}
 * (enkel gezet op een BASELINE_ACCEPTED-batch). Een constraintfout breekt de transactie: na de verwachte exception
 * volgt geen databasetoegang meer.
 */
@DaoTest
class ImportBatchStagingPurgeConstraintTest {

    @Autowired
    private ImportBatchRepository batches;
    @Autowired
    private DaoFixtures fixtures;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private ImportBatch batchWithStatus(ImportBatchStatus status) {
        ImportBatch batch = fixtures.chain().newBatch(1);
        batch.setStatus(status);
        return batches.saveAndFlush(batch);
    }

    @Test
    void aNewBatchHasNoPurgeTimestampInTheDatabase() {
        ImportBatch batch = fixtures.batch();

        assertThat(jdbc.queryForObject("select staging_purged_at is null from import_batch where id = ?",
                Boolean.class, batch.getId())).isTrue();
    }

    @Test
    void thePurgeTimestampOfABaselineAcceptedBatchIsStoredAndReadBack() {
        ImportBatch batch = batchWithStatus(ImportBatchStatus.BASELINE_ACCEPTED);
        Instant purgedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        batch.markStagingPurged(purgedAt);
        batches.saveAndFlush(batch);
        em.clear();

        assertThat(batches.findById(batch.getId()).orElseThrow().getStagingPurgedAt()).isEqualTo(purgedAt);
    }

    @Test
    void aPurgeTimestampOnAnyOtherStatusIsRefusedByTheDatabase() {
        for (ImportBatchStatus status : new ImportBatchStatus[] {ImportBatchStatus.SCREENED,
                ImportBatchStatus.BLOCKED, ImportBatchStatus.FAILED, ImportBatchStatus.RECEIVED}) {
            assertThat(violatesPurgeCheck(batchWithStatus(status).getId())).as("status %s", status).isTrue();
        }
    }

    @Test
    void aPurgedBatchCannotLeaveBaselineAccepted() {
        ImportBatch batch = batchWithStatus(ImportBatchStatus.BASELINE_ACCEPTED);
        batch.markStagingPurged(Instant.now());
        batches.saveAndFlush(batch);

        assertThatThrownBy(() -> jdbc.update("update import_batch set status = 'SCREENED' where id = ?",
                batch.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_batch_staging_purged");
    }

    /**
     * Native update per status in een savepoint, zodat meerdere statussen in één test kunnen (een geweigerde update
     * zonder savepoint breekt de transactie).
     */
    private boolean violatesPurgeCheck(long batchId) {
        jdbc.execute("savepoint purge_check");
        try {
            jdbc.update("update import_batch set staging_purged_at = now() where id = ?", batchId);
            jdbc.execute("release savepoint purge_check");
            return false;
        } catch (DataIntegrityViolationException refused) {
            jdbc.execute("rollback to savepoint purge_check");
            assertThat(refused).hasMessageContaining("ck_import_batch_staging_purged");
            return true;
        }
    }
}
