package be.dda.catalogimport.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Chunked delete of the candidate staging of <b>one</b> batch (stap 7, S7-P2; docs/decisions.md 2026-10-02 "Stap 7
 * uitgewerkt"): {@code import_candidate_price} and {@code import_candidate_reference} (children) and
 * {@code import_candidate_stage} (parent). Nothing else: no row issues, mutations, source state, price observations,
 * snapshots, issue groups, batch or delivery rows, and no counters ({@code staged_row_count} stays).
 * <p>
 * <b>No eligibility check here.</b> This DAO deletes what it is told to. The only permitted caller is
 * {@code StagingPurgeService}, which selects candidates via {@link StagingRetentionDao} and rechecks them under the
 * batch row lock in the <b>same</b> transaction as the delete.
 * <p>
 * <b>Chunk shape (PostgreSQL-safe, index-backed).</b> The staging rows of a batch are addressed by the primary key
 * {@code (batch_id, row_number)}; the children carry the same leading key columns in their primary keys. A chunk
 * first determines the upper {@code row_number} of the lowest {@code chunkSize} remaining stage rows (an index range
 * scan on {@code pk_import_candidate_stage}), then deletes children and stage rows with
 * {@code batch_id = ? and row_number <= upper}. Because chunks run from low to high, everything below the bound is
 * already gone, so the range never reaches back into deleted data. No {@code ctid}, no {@code delete ... limit}
 * (not PostgreSQL).
 * <p>
 * <b>Children explicitly first.</b> Both child tables have {@code on delete cascade} to the stage rows (changeset
 * 004); they are deleted explicitly anyway, as in {@code DeliveryScreeningService.fail()}, so the intent is visible
 * and the counts per table can be reported. The three statements must run in one transaction: this DAO refuses to
 * run without one.
 */
@Repository
public class StagingPurgeDao {

    /** Row counts per staging table (a census, or what one chunk deleted). */
    public record StagingCounts(long stageRows, long priceRows, long referenceRows) {

        public static final StagingCounts NONE = new StagingCounts(0, 0, 0);

        public StagingCounts plus(StagingCounts other) {
            return new StagingCounts(stageRows + other.stageRows, priceRows + other.priceRows,
                    referenceRows + other.referenceRows);
        }

        public long total() {
            return stageRows + priceRows + referenceRows;
        }

        public boolean isEmpty() {
            return total() == 0;
        }
    }

    private final JdbcTemplate jdbc;

    public StagingPurgeDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Counts the staging rows of one batch per table (read-only; used by the dry run and the final check). */
    public StagingCounts count(long batchId) {
        return new StagingCounts(
                countRows("import_candidate_stage", batchId),
                countRows("import_candidate_price", batchId),
                countRows("import_candidate_reference", batchId));
    }

    /**
     * Deletes the lowest {@code chunkSize} stage rows of one batch together with their prices and references.
     *
     * @return what was deleted; {@link StagingCounts#NONE} when the batch has no stage rows left
     * @throws IllegalArgumentException when {@code chunkSize} is not positive
     * @throws IllegalStateException    when called outside a transaction
     */
    public StagingCounts deleteChunk(long batchId, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive (was " + chunkSize + ")");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A staging chunk must be deleted inside a transaction");
        }
        Long upper = jdbc.queryForObject(
                "select max(row_number) from (select row_number from import_candidate_stage "
                        + "where batch_id = ? order by row_number limit ?) chunk",
                Long.class, batchId, chunkSize);
        if (upper == null) {
            return StagingCounts.NONE;
        }
        int prices = jdbc.update(
                "delete from import_candidate_price where batch_id = ? and row_number <= ?", batchId, upper);
        int references = jdbc.update(
                "delete from import_candidate_reference where batch_id = ? and row_number <= ?", batchId, upper);
        int stage = jdbc.update(
                "delete from import_candidate_stage where batch_id = ? and row_number <= ?", batchId, upper);
        return new StagingCounts(stage, prices, references);
    }

    private long countRows(String table, long batchId) {
        // table is one of three constants above, never input.
        Long count = jdbc.queryForObject("select count(*) from " + table + " where batch_id = ?", Long.class,
                batchId);
        return count == null ? 0L : count;
    }
}
