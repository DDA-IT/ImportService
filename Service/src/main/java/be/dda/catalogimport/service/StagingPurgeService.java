package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.StagingPurgeDao;
import be.dda.catalogimport.dao.StagingPurgeDao.StagingCounts;
import be.dda.catalogimport.dao.StagingRetentionDao;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opruiming van de kandidaatstaging (stap 7, S7-P2; docs/decisions.md 2026-10-02 "Stap 7 uitgewerkt").
 * <p>
 * <b>Businessregel (mens).</b> De kandidaatstaging ({@code import_candidate_stage} met haar prijzen en referenties)
 * van een batch die als nulmeting aanvaard is ({@code BASELINE_ACCEPTED}) wordt na de retentietermijn
 * ({@code catalogimport.staging-retention.after}, default {@value #DEFAULT_RETENTION}) verwijderd. Al het andere blijft:
 * row-issues (BA: 7 jaar), mutaties, bronstaat, prijsobservaties, snapshots/bundels/runs, issuegroepen en
 * behandelgevallen, de batch- en leveringsrij en hun tellers ({@code staged_row_count} blijft het aantal ooit gestagede
 * regels). SCREENED, BLOCKED en FAILED worden niet opgeruimd.
 * <p>
 * <b>Technische invulling.</b>
 * <ol>
 *   <li>Kandidaten: {@link StagingRetentionDao#findStagingPurgeCandidates} (de opruimguard, versmald tot
 *       BASELINE_ACCEPTED, aanvaard vóór {@code nu - retentie}, {@code staging_purged_at is null}), oplopend id.</li>
 *   <li>Per batch, transactie 1: het batchslot met {@code NOWAIT}; bezet = overslaan (de volgende run probeert
 *       opnieuw). Onder het slot alles opnieuw controleren, ook de guard ({@link StagingRetentionDao#isPurgeable}).</li>
 *   <li>Daarna elke chunk ({@code catalogimport.staging-retention.chunk-size}, default {@value #DEFAULT_CHUNK_SIZE}
 *       stagerijen) in een eigen transactie die eerst opnieuw het batchslot NOWAIT neemt en dezelfde controle doet;
 *       bezet of niet meer in aanmerking = stoppen voor deze batch. Wat al weg is, blijft weg; de volgende run gaat
 *       verder met wat overblijft (idempotent: een chunk op een lege staging verwijdert niets).</li>
 *   <li>De laatste transactie (een chunk die niets meer vindt) controleert dat er niets overblijft en zet
 *       {@code staging_purged_at}. Daarna wordt de batch nooit meer geselecteerd.</li>
 * </ol>
 * Enkel het batchslot wordt genomen, en nooit een ander slot terwijl het vastgehouden wordt: dat past onderaan in de
 * globale slotvolgorde (bundel, run, koppeling, batch). Lock-fouten worden <b>buiten</b> de transactie vertaald
 * ({@link LockFailures}). Een onverwachte fout bij één batch rolt enkel die transactie terug en wordt gemeld; de run
 * gaat verder met de volgende batch.
 * <p>
 * <b>Proefrun.</b> {@code purge(true)} telt per kandidaat wat verwijderd zou worden, zonder slot en zonder iets te
 * wijzigen.
 * <p>
 * <b>Configuratie (fail-fast).</b> Een ongeldige of niet-positieve retentie of chunkgrootte laat de applicatie niet
 * opstarten (zelfde patroon als {@code catalogimport.screening.claim-lease}). Tijd komt uit de gedeelde
 * {@link Clock}-bean.
 */
@Service
public class StagingPurgeService {

    static final String DEFAULT_RETENTION = "P7D";
    static final int DEFAULT_CHUNK_SIZE = 10_000;
    static final String RETENTION_PROPERTY = "catalogimport.staging-retention.after";
    static final String CHUNK_SIZE_PROPERTY = "catalogimport.staging-retention.chunk-size";

    /** Interne conflictcode voor een bezet batchslot; verlaat deze klasse nooit. */
    private static final String CODE_BATCH_LOCKED = "STAGING_PURGE_BATCH_LOCKED";

    private static final Logger LOG = LoggerFactory.getLogger(StagingPurgeService.class);

    /**
     * Uitkomst van één run.
     *
     * @param dryRun             {@code true}: er is niets verwijderd; {@code purgedBatchIds} en {@code rows} zijn wat
     *                           verwijderd <i>zou</i> worden
     * @param cutoff             batches aanvaard vóór dit tijdstip kwamen in aanmerking
     * @param candidateCount     aantal geselecteerde kandidaten
     * @param purgedBatchIds     batches waarvan de staging volledig weg is en {@code staging_purged_at} gezet werd
     * @param busyBatchIds       batches waarvan het slot bezet was (bij de start of tussen twee chunks); de volgende
     *                           run gaat verder
     * @param ineligibleBatchIds batches die onder het slot niet meer in aanmerking kwamen
     * @param failedBatchIds     batches met een onverwachte fout (gelogd); de lopende chunk is teruggerold
     * @param rows               verwijderde (of bij een proefrun: te verwijderen) rijen per tabel
     */
    public record PurgeReport(boolean dryRun, Instant cutoff, int candidateCount, List<Long> purgedBatchIds,
                              List<Long> busyBatchIds, List<Long> ineligibleBatchIds, List<Long> failedBatchIds,
                              StagingCounts rows) {

        public PurgeReport {
            purgedBatchIds = List.copyOf(purgedBatchIds);
            busyBatchIds = List.copyOf(busyBatchIds);
            ineligibleBatchIds = List.copyOf(ineligibleBatchIds);
            failedBatchIds = List.copyOf(failedBatchIds);
        }
    }

    private enum Outcome { PURGED, BUSY, INELIGIBLE }

    /**
     * Resultaat van één transactie onder het batchslot: {@code stop == null} = de controle slaagde en {@code value} is
     * het resultaat van het werk; anders {@link Outcome#BUSY} of {@link Outcome#INELIGIBLE} met de reden.
     */
    private record Locked<T>(Outcome stop, String reason, T value) {
    }

    private final ImportBatchRepository batches;
    private final StagingRetentionDao guard;
    private final StagingPurgeDao purgeDao;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration retention;
    private final int chunkSize;

    @Autowired
    public StagingPurgeService(ImportBatchRepository batches, StagingRetentionDao guard, StagingPurgeDao purgeDao,
                               PlatformTransactionManager transactionManager, Clock clock,
                               @Value("${" + RETENTION_PROPERTY + ":" + DEFAULT_RETENTION + "}") String retention,
                               @Value("${" + CHUNK_SIZE_PROPERTY + ":" + DEFAULT_CHUNK_SIZE + "}") String chunkSize) {
        this.batches = batches;
        this.guard = guard;
        this.purgeDao = purgeDao;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.retention = parseRetention(retention);
        this.chunkSize = parseChunkSize(chunkSize);
    }

    /** Fail-fast: geen stille terugval op de default bij een fout in de configuratie. */
    static Duration parseRetention(String value) {
        Duration parsed;
        try {
            parsed = Duration.parse(value == null ? "" : value.trim());
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException(RETENTION_PROPERTY + " must be an ISO-8601 duration such as "
                    + DEFAULT_RETENTION + " (was '" + value + "')", invalid);
        }
        if (parsed.isZero() || parsed.isNegative()) {
            throw new IllegalStateException(RETENTION_PROPERTY + " must be positive (was '" + value + "')");
        }
        return parsed;
    }

    /** Fail-fast, zoals {@link #parseRetention}. */
    static int parseChunkSize(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException(CHUNK_SIZE_PROPERTY + " must be a whole number such as "
                    + DEFAULT_CHUNK_SIZE + " (was '" + value + "')", invalid);
        }
        if (parsed <= 0) {
            throw new IllegalStateException(CHUNK_SIZE_PROPERTY + " must be positive (was '" + value + "')");
        }
        return parsed;
    }

    public Duration retention() {
        return retention;
    }

    public int chunkSize() {
        return chunkSize;
    }

    /**
     * Eén opruimrun over alle kandidaten.
     *
     * @param dryRun {@code true} = enkel tellen, niets vergrendelen of wijzigen
     */
    public PurgeReport purge(boolean dryRun) {
        Instant cutoff = clock.instant().minus(retention);
        List<Long> candidates = guard.findStagingPurgeCandidates(cutoff);
        List<Long> purged = new ArrayList<>();
        List<Long> busy = new ArrayList<>();
        List<Long> ineligible = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        StagingCounts[] rows = {StagingCounts.NONE};
        for (long batchId : candidates) {
            if (dryRun) {
                rows[0] = rows[0].plus(purgeDao.count(batchId));
                purged.add(batchId);
                continue;
            }
            try {
                Outcome outcome = purgeBatch(batchId, cutoff, deleted -> rows[0] = rows[0].plus(deleted));
                switch (outcome) {
                    case PURGED -> purged.add(batchId);
                    case BUSY -> busy.add(batchId);
                    case INELIGIBLE -> ineligible.add(batchId);
                }
            } catch (RuntimeException failure) {
                LOG.error("Staging purge of batch {} failed; the current chunk was rolled back", batchId, failure);
                failed.add(batchId);
            }
        }
        PurgeReport report = new PurgeReport(dryRun, cutoff, candidates.size(), purged, busy, ineligible, failed,
                rows[0]);
        LOG.info("Staging purge{}: cutoff {}, {} candidate(s), {} {}, {} busy, {} no longer eligible, {} failed; "
                        + "rows stage={} price={} reference={}",
                dryRun ? " (dry run)" : "", cutoff, report.candidateCount(), purged.size(),
                dryRun ? "would be purged" : "purged", busy.size(), ineligible.size(), failed.size(),
                report.rows().stageRows(), report.rows().priceRows(), report.rows().referenceRows());
        return report;
    }

    /**
     * Ruimt de staging van één batch op: transactie 1 (slot + controle), dan chunks, dan de markering.
     *
     * @param onDeleted krijgt na elke gecommitte chunk wat verwijderd werd
     */
    private Outcome purgeBatch(long batchId, Instant cutoff, Consumer<StagingCounts> onDeleted) {
        Locked<Void> start = underLock(batchId, cutoff, batch -> null);
        if (start.stop() != null) {
            logStop(batchId, start, false);
            return start.stop();
        }
        while (true) {
            Locked<StagingCounts> chunk = underLock(batchId, cutoff, batch -> {
                StagingCounts deleted = purgeDao.deleteChunk(batchId, chunkSize);
                if (deleted.isEmpty()) {
                    StagingCounts remaining = purgeDao.count(batchId);
                    if (!remaining.isEmpty()) {
                        // Kan niet: een kind zonder stagerij verbiedt de foreign key. Nooit markeren als er iets blijft.
                        throw new IllegalStateException("Batch " + batchId + " still has staging rows " + remaining
                                + " after its last chunk");
                    }
                    batch.markStagingPurged(clock.instant());
                }
                return deleted;
            });
            if (chunk.stop() != null) {
                logStop(batchId, chunk, true);
                return chunk.stop();
            }
            if (chunk.value().isEmpty()) {
                LOG.info("Staging of batch {} purged", batchId);
                return Outcome.PURGED;
            }
            onDeleted.accept(chunk.value());
        }
    }

    /**
     * Eén transactie onder het batchslot ({@code NOWAIT}): eerst de volledige controle, dan {@code work}. Een bezet
     * slot wordt buiten de transactie vertaald (PostgreSQL breekt de transactie af bij een lock-fout).
     */
    private <T> Locked<T> underLock(long batchId, Instant cutoff, Function<ImportBatch, T> work) {
        try {
            return LockFailures.translate(() -> transaction.execute(status -> {
                ImportBatch batch = batches.findByIdForUpdateNowait(batchId).orElse(null);
                String reason = ineligibility(batchId, batch, cutoff);
                if (reason != null) {
                    return new Locked<T>(Outcome.INELIGIBLE, reason, null);
                }
                return new Locked<>(null, null, work.apply(batch));
            }), CODE_BATCH_LOCKED, "Batch " + batchId + " is locked");
        } catch (ConflictException conflict) {
            if (!CODE_BATCH_LOCKED.equals(conflict.getCode())) {
                throw conflict;
            }
            return new Locked<>(Outcome.BUSY, "batch row is locked", null);
        }
    }

    /** {@code null} = de batch komt (nog) in aanmerking; anders de reden waarom niet. Onder het batchslot. */
    private String ineligibility(long batchId, ImportBatch batch, Instant cutoff) {
        if (batch == null) {
            return "batch no longer exists";
        }
        if (batch.getStatus() != ImportBatchStatus.BASELINE_ACCEPTED) {
            return "status is " + batch.getStatus();
        }
        if (batch.getStagingPurgedAt() != null) {
            return "staging already purged at " + batch.getStagingPurgedAt();
        }
        if (batch.getBaselineAcceptedAt() == null || !batch.getBaselineAcceptedAt().isBefore(cutoff)) {
            return "accepted at " + batch.getBaselineAcceptedAt() + ", not before " + cutoff;
        }
        if (!guard.isPurgeable(batchId)) {
            return "the staging retention guard refuses it (bundle membership or missing snapshot)";
        }
        return null;
    }

    private static void logStop(long batchId, Locked<?> locked, boolean midway) {
        String when = midway ? " between chunks; the next run continues" : "";
        if (locked.stop() == Outcome.BUSY) {
            LOG.info("Staging purge skipped batch {}: {}{}", batchId, locked.reason(), when);
        } else {
            LOG.warn("Staging purge skipped batch {}: {}{}", batchId, locked.reason(), when);
        }
    }
}
