package be.dda.catalogimport.dao;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only JDBC DAO for identifying batches whose staging ({@code import_candidate_stage} and
 * {@code import_candidate_price}) can be safely purged (ontwerp fase 5-PUB §1, bouwstap 5P-5;
 * docs/decisions.md 2026-09-26 "5-PUB (deel a): ontwerp bindend").
 *
 * <h2>The three conditions for purgeable staging</h2>
 * The staging of batch B may be purged only if <b>all three</b> conditions are met:
 * <ol>
 *   <li><b>B is terminal (for cleanup):</b> status in ({@code FAILED}, {@code BASELINE_ACCEPTED}).
 *       <br/>
 *       <b>Why not SCREENED or BLOCKED?</b>
 *       <ul>
 *         <li><b>SCREENED:</b> A batch in SCREENED status is still a bundle candidate — it can still be added to
 *           a bundle and have a snapshot taken of it later. If its staging is cleaned up before snapshotting,
 *           the snapshot operation will fail with {@code SNAPSHOT_SOURCE_MISSING}. Therefore, SCREENED batches
 *           must retain their staging until they reach BASELINE_ACCEPTED or FAILED.</li>
 *         <li><b>BLOCKED:</b> A batch in BLOCKED status is an unresolved diagnostic or error state, awaiting
 *           investigation or manual remediation. Cleaning up its staging at this point would prevent future
 *           troubleshooting and recovery. BLOCKED batches must be manually resolved or explicitly moved to a
 *           terminal state before cleanup is permitted.</li>
 *       </ul>
 *       Note: this is stricter than {@link ImportBatchStatus#isTerminal()}, which includes SCREENED and BLOCKED.
 *   </li>
 *   <li><b>B has no active membership in a non-cancelled bundle:</b> there is no row in
 *       {@code publication_bundle_batch} where {@code batch_id = B.id}, {@code active_marker is not
 *       null} (i.e., {@code true}), and the associated bundle status is not {@code CANCELLED}.
 *       <br/>
 *       A batch that was never added to any bundle automatically satisfies this condition
 *       (no membership rows at all).</li>
 *   <li><b>Every bundle B was ever in carries a snapshot_hash:</b> for all bundles that ever contained
 *       B (including removed memberships), {@code publication_bundle.snapshot_hash is not null}.
 *       <br/>
 *       A batch that was never in any bundle automatically satisfies this condition
 *       (no bundles to snapshot).</li>
 * </ol>
 *
 * <h2>Why the snapshot_hash requirement</h2>
 * A batch in a bundle must not lose its staging before the bundle has taken a snapshot of it.
 * Once the snapshot exists ({@code snapshot_hash is not null}), the staging is no longer the source
 * of truth for that bundle — the snapshot is. A batch whose staging is cleaned up while the snapshot
 * is still being computed or verified would cause the snapshot to fail with {@code SNAPSHOT_SOURCE_MISSING}
 * (bouwstap 5P-2). The snapshot_hash therefore acts as a lock: no staging cleanup until snapshots
 * across all past and present bundle memberships are complete and recorded.
 *
 * <h2>Read-only, no service coupling</h2>
 * This DAO is a query-only guard for staging cleanup. There is <b>no delete</b> here. It is the <b>only
 * permitted</b> check to determine eligibility for a delete operation. Since stap 7 (docs/decisions.md 2026-10-02
 * "Stap 7 uitgewerkt", S7-P2) its only deleting consumer is {@code StagingPurgeService}, which selects candidates
 * with {@link #findStagingPurgeCandidates(Instant)} (the guard narrowed to the purge policy) and rechecks
 * {@link #isPurgeable(long)} under the batch row lock; the delete itself lives in {@link StagingPurgeDao}.
 *
 * <h2>Scope and performance</h2>
 * Results are sorted by batch ID (ascending). No pagination is provided: the result set should be
 * small under normal retention policies (typically days to weeks; batches with active Frozen bundles
 * are blocked regardless of age). Each query is set-based and runs in a single round-trip.
 */
@Repository
public class StagingRetentionDao {

    /**
     * Purgeable statuses for cleanup: FAILED and BASELINE_ACCEPTED only.
     * <p>
     * These are the statuses that indicate a batch has reached a truly terminal, stable state where
     * no future bundle membership or snapshotting is possible. Notably, SCREENED and BLOCKED are excluded:
     * <ul>
     *   <li>SCREENED = still a bundle candidate, requires staging for future snapshots</li>
     *   <li>BLOCKED = unresolved diagnostic state, requires staging for investigation and recovery</li>
     * </ul>
     * Codified here (not fetched from the enum at runtime) to match the established DAO pattern:
     * constants that bridge the database schema and the application domain, without creating a
     * coupling from the Dao layer to the service/domain layer.
     */
    private static final String TERMINAL_STATUSES = "'FAILED', 'BASELINE_ACCEPTED'";

    /**
     * Conditions 1 to 3 on alias {@code b} (import_batch). Shared by every query of this guard, so the purge policy
     * can only narrow it, never widen it.
     */
    private static final String GUARD_CONDITIONS = "b.status in (" + TERMINAL_STATUSES + ") "
            + "  and not exists ("
            + "    select 1 from publication_bundle_batch pbb "
            + "    join publication_bundle pb on pb.id = pbb.bundle_id "
            + "    where pbb.batch_id = b.id "
            + "      and pbb.active_marker is not null "
            + "      and pb.status <> 'CANCELLED') "
            + "  and not exists ("
            + "    select 1 from publication_bundle_batch pbb2 "
            + "    join publication_bundle pb2 on pb2.id = pbb2.bundle_id "
            + "    where pbb2.batch_id = b.id "
            + "      and pb2.snapshot_hash is null) ";

    private final JdbcTemplate jdbc;

    public StagingRetentionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Find all batch IDs whose staging can be safely purged, sorted by batch ID.
     *
     * <h2>The query logic</h2>
     * <ol>
     *   <li><b>Start with all purgeable batches</b> ({@code b.status in (FAILED, BASELINE_ACCEPTED)}).</li>
     *   <li><b>Exclude batches with active non-cancelled bundle membership:</b> filter out batches that have
     *       an active membership (active_marker is not null) in a bundle whose status is not 'CANCELLED'.</li>
     *   <li><b>Exclude batches with any un-snapshotted past or present bundle:</b> filter out batches that
     *       were ever in a bundle (including removed memberships) that lacks a {@code snapshot_hash}
     *       (is {@code null}).</li>
     * </ol>
     * A batch that was never in any bundle passes conditions 2 and 3 automatically (no membership rows exist,
     * so no exclusions apply).
     *
     * @return list of batch IDs that satisfy all three conditions, sorted ascending, or an empty list
     *         if none exist
     */
    public List<Long> findPurgeableBatches() {
        return jdbc.queryForList(
                "select distinct b.id from import_batch b "
                        + "where " + GUARD_CONDITIONS
                        + "order by b.id asc",
                Long.class);
    }

    /**
     * Check whether a single batch ID is purgeable (satisfies all three conditions).
     * Convenience method for targeted checks; equivalent to checking membership in
     * {@link #findPurgeableBatches()}.
     *
     * @param batchId the batch ID to check
     * @return {@code true} if the batch satisfies all conditions; {@code false} otherwise
     */
    public boolean isPurgeable(long batchId) {
        Boolean exists = jdbc.queryForObject(
                "select count(*) > 0 from ("
                        + "select 1 from import_batch b "
                        + "where b.id = ? "
                        + "  and " + GUARD_CONDITIONS
                        + ") t",
                Boolean.class,
                batchId);
        return exists != null && exists;
    }

    /**
     * Candidates of the staging purge (stap 7, S7-P2): the guard above, narrowed to the purge policy decided by the
     * mens (docs/decisions.md 2026-10-02 "Stap 7 uitgewerkt"):
     * <ul>
     *   <li>status {@code BASELINE_ACCEPTED} only ({@code FAILED} passes the guard but is not purged in stap 7);</li>
     *   <li>{@code baseline_accepted_at} strictly before {@code acceptedBefore} (now minus the retention period); a
     *       batch without an acceptance timestamp is never a candidate;</li>
     *   <li>{@code staging_purged_at is null} (changeset 019): an already purged batch is never selected again.</li>
     * </ul>
     * Read-only and without locks: the caller must lock the batch row and recheck before deleting anything.
     *
     * @return candidate batch IDs, ascending (the lock order for batches)
     */
    public List<Long> findStagingPurgeCandidates(Instant acceptedBefore) {
        if (acceptedBefore == null) {
            throw new IllegalArgumentException("acceptedBefore is required");
        }
        return jdbc.queryForList(
                "select b.id from import_batch b "
                        + "where b.status = 'BASELINE_ACCEPTED' "
                        + "  and b.staging_purged_at is null "
                        + "  and b.baseline_accepted_at is not null "
                        + "  and b.baseline_accepted_at < ? "
                        + "  and " + GUARD_CONDITIONS
                        + "order by b.id asc",
                Long.class,
                OffsetDateTime.ofInstant(acceptedBefore, ZoneOffset.UTC));
    }
}
