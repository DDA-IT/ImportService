package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CandidateStageDao;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.IssueCaseDao;
import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.RowIssueDao;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.service.support.ImportIssueCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Herstel bij het opstarten van de applicatie (design par. 9): een screening die halverwege wegviel,
 * mag niet eeuwig open blijven staan en de {@code TaskRun}-concurrency-token vasthouden.
 * <ul>
 *   <li>{@code SCREENING} ⇒ {@code FAILED} met {@code blocked_code=SCREENING_INTERRUPTED}. Staging en
 *       regelproblemen van die poging worden verwijderd, de {@code TaskRun} gaat naar {@code FAILED}
 *       (token vrij). Er wordt <b>nooit</b> een marker of mutatie geschreven: de stagingfase was niet
 *       af, dus er valt niets over de levering vast te stellen.</li>
 *   <li>{@code MUTATING} ⇒ ongewijzigd. De staging is volledig en de reeds geschreven mutaties zijn
 *       gecommit; die batch blijft hervatbaar via {@code POST /batches/{id}/continue}. Ze wordt enkel
 *       gelogd, zodat een operator ziet welke batches wachten.</li>
 * </ul>
 * Sinds bouwstap S2-B1b (docs/design/issue-case-design.md par. 3) verdwijnen met de issuegroepen ook
 * de waarnemingen van hun <b>behandelgeval</b>. De tellers van de betrokken gevallen worden daarom
 * herteld, en een geval dat nooit een geldige waarneming gehad heeft én waaraan nog nooit een mens
 * geraakt heeft, wordt verwijderd — <b>in deze volgorde: eerst verwijderen, dan hertellen</b>, omdat
 * CHECK-constraints in PostgreSQL nooit deferrable zijn. Een geval met een menselijke beslissing
 * blijft bestaan met {@code observation_count = 0}: die beslissing mag nooit door een technische
 * onderbreking verdwijnen.
 * Uitschakelbaar met {@code catalogimport.screening.recovery-on-startup=false} (standaard aan); tests
 * gebruiken dat zodat gedeelde testdata elkaar niet beïnvloeden.
 * <p>
 * <b>Claim-bewust (stap 4, S4-c, beslissingslog 2026-10-01).</b> Sinds de verwerkingsclaim
 * ({@link BatchProcessingClaims}) is een batch die een andere instantie nog verwerkt wél te onderscheiden van een
 * verweesde:
 * <ul>
 *   <li>{@code SCREENING} gaat enkel naar {@code FAILED} als de claim <b>dood</b> is (geen token, verlopen lease,
 *       of een vorige boot van deze instantie). Compare-and-set: onder schrijfslot moet de token nog exact de
 *       waargenomen token zijn; de claim gaat vrij in dezelfde wijziging als de overgang naar {@code FAILED}.</li>
 *   <li>{@code MUTATING} met een dode claim: de token wordt gewist (zelfde compare-and-set) en gelogd; de batch
 *       blijft hervatbaar.</li>
 *   <li>Een levende claim van een andere instantie blijft ongemoeid: die batch wordt niet gefaald, niet
 *       vrijgegeven en niet als hervatbaar gemeld (enkel gelogd).</li>
 * </ul>
 */
@Service
public class ScreeningRecoveryService {

    /** Blokkeercode van een screening die door een herstart onderbroken werd. */
    public static final String CODE_SCREENING_INTERRUPTED = ImportIssueCatalog.SCREENING_INTERRUPTED;

    private static final int MAX_BLOCKED_REASON_LENGTH = 500;
    private static final Logger LOG = LoggerFactory.getLogger(ScreeningRecoveryService.class);

    /**
     * Wat het herstel gedaan heeft: {@code failedBatchIds} zijn de onderbroken screenings die op
     * {@code FAILED} gezet zijn, {@code resumableBatchIds} de {@code MUTATING}-batches die hervatbaar
     * bleven. Een batch met een levende claim van een andere worker staat in geen van beide.
     */
    public record RecoveryReport(List<Long> failedBatchIds, List<Long> resumableBatchIds) {
    }

    /** Wat er met één open batch gebeurde. */
    private enum Recovered {
        /** Gefaald ({@code SCREENING}) of hervatbaar gelaten ({@code MUTATING}). */
        HANDLED,
        /** Een levende (of intussen vernieuwde) claim van een andere worker: ongemoeid gelaten. */
        ACTIVE,
        /** Intussen niet meer in de verwachte status (of verdwenen): niets gedaan. */
        GONE
    }

    /** Een open batch zoals waargenomen in de lijst, vóór het slot: id en claim-token. */
    private record Observed(long batchId, UUID claimToken) {
    }

    private final ImportBatchRepository batches;
    private final TaskRunRepository runs;
    private final CandidateStageDao stage;
    private final RowIssueDao rowIssues;
    private final IssueGroupDao issueGroups;
    /**
     * Bouwstap S2-B1b: de behandelgevallen waarvan de waarnemingen mee verdwijnen
     * (docs/design/issue-case-design.md par. 3, laatste alinea).
     */
    private final IssueCaseDao issueCases;
    /** Stap 4 (S4-c): beoordeelt of de claim op een open batch nog levend is. */
    private final BatchProcessingClaims claims;
    private final TransactionTemplate transaction;
    private final boolean enabled;

    public ScreeningRecoveryService(ImportBatchRepository batches, TaskRunRepository runs,
                                    CandidateStageDao stage, RowIssueDao rowIssues,
                                    IssueGroupDao issueGroups, IssueCaseDao issueCases,
                                    BatchProcessingClaims claims,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${catalogimport.screening.recovery-on-startup:true}")
                                    boolean enabled) {
        this.batches = batches;
        this.runs = runs;
        this.stage = stage;
        this.rowIssues = rowIssues;
        this.issueGroups = issueGroups;
        this.issueCases = issueCases;
        this.claims = claims;
        this.transaction = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
    }

    /** Herstel bij het opstarten; een mislukt herstel mag het opstarten nooit tegenhouden. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            LOG.info("Screening recovery on startup is disabled");
            return;
        }
        try {
            recover();
        } catch (RuntimeException failure) {
            LOG.error("Screening recovery on startup failed", failure);
        }
    }

    /**
     * Voert het herstel uit, ook wanneer het opstartherstel uitgeschakeld is. Elke batch wordt in haar
     * eigen transactie afgehandeld: een fout bij één batch laat de andere niet liggen.
     */
    public RecoveryReport recover() {
        List<Long> failed = new ArrayList<>();
        for (Observed observed : observe(ImportBatchStatus.SCREENING)) {
            try {
                Recovered result = transaction.execute(status -> failInterrupted(observed));
                if (result == Recovered.HANDLED) {
                    failed.add(observed.batchId());
                }
            } catch (RuntimeException failure) {
                LOG.error("Cannot recover interrupted screening of batch {}", observed.batchId(), failure);
            }
        }
        List<Long> resumable = new ArrayList<>();
        for (Observed observed : observe(ImportBatchStatus.MUTATING)) {
            try {
                Recovered result = transaction.execute(status -> releaseDeadClaim(observed));
                if (result == Recovered.HANDLED) {
                    resumable.add(observed.batchId());
                    LOG.warn("Batch {} is in MUTATING and can be resumed with "
                            + "POST /api/catalog-import/batches/{}/continue", observed.batchId(), observed.batchId());
                }
            } catch (RuntimeException failure) {
                LOG.error("Cannot recover the processing claim of batch {}", observed.batchId(), failure);
            }
        }
        return new RecoveryReport(List.copyOf(failed), List.copyOf(resumable));
    }

    private List<Observed> observe(ImportBatchStatus status) {
        return batches.findByStatus(status).stream()
                .map(batch -> new Observed(batch.getId(), batch.getProcessingClaimToken()))
                .toList();
    }

    /**
     * Compare-and-set onder schrijfslot: de batch moet nog in {@code expected} staan, de token moet nog exact de
     * waargenomen token zijn, en de claim moet dood zijn. Anders blijft de batch ongemoeid en staat de reden in
     * {@code outcome[0]}.
     *
     * @return de vergrendelde, verweesde batch, of {@code null}
     */
    private ImportBatch lockIfOrphaned(Observed observed, ImportBatchStatus expected, Recovered[] outcome) {
        ImportBatch batch = batches.findByIdForUpdate(observed.batchId()).orElse(null);
        if (batch == null || batch.getStatus() != expected) {
            outcome[0] = Recovered.GONE;
            return null;
        }
        if (!Objects.equals(batch.getProcessingClaimToken(), observed.claimToken())) {
            // Een andere worker nam intussen (opnieuw) een claim: die is per definitie vers.
            LOG.info("Batch {} ({}) got a new processing claim from {} while recovering; left untouched",
                    observed.batchId(), expected, batch.getProcessingClaimedBy());
            outcome[0] = Recovered.ACTIVE;
            return null;
        }
        if (claims.isAlive(batch)) {
            LOG.info("Batch {} ({}) is being processed by {} (last heartbeat {}); left untouched",
                    observed.batchId(), expected, batch.getProcessingClaimedBy(), batch.getProcessingHeartbeatAt());
            outcome[0] = Recovered.ACTIVE;
            return null;
        }
        return batch;
    }

    /** {@code MUTATING} met een dode claim: token wissen en loggen; zonder claim verandert er niets. */
    private Recovered releaseDeadClaim(Observed observed) {
        Recovered[] outcome = new Recovered[1];
        ImportBatch batch = lockIfOrphaned(observed, ImportBatchStatus.MUTATING, outcome);
        if (batch == null) {
            return outcome[0];
        }
        if (batch.getProcessingClaimToken() != null) {
            LOG.warn("Batch {} (MUTATING): releasing the dead processing claim of {} (last heartbeat {})",
                    observed.batchId(), batch.getProcessingClaimedBy(), batch.getProcessingHeartbeatAt());
            batch.releaseProcessing();
            batches.saveAndFlush(batch);
        }
        return Recovered.HANDLED;
    }

    /**
     * {@code SCREENING} met een dode claim (of zonder claim) wordt {@code FAILED}; de claim gaat vrij in dezelfde
     * wijziging.
     */
    private Recovered failInterrupted(Observed observed) {
        Recovered[] outcome = new Recovered[1];
        ImportBatch batch = lockIfOrphaned(observed, ImportBatchStatus.SCREENING, outcome);
        if (batch == null) {
            return outcome[0];
        }
        long batchId = observed.batchId();
        if (batch.getProcessingClaimToken() != null) {
            LOG.warn("Batch {} (SCREENING): the processing claim of {} is dead (last heartbeat {})", batchId,
                    batch.getProcessingClaimedBy(), batch.getProcessingHeartbeatAt());
        }
        stage.deleteByBatchId(batchId);
        rowIssues.deleteByBatchId(batchId);
        // Vóór het verwijderen van de groepen vastleggen welke behandelgevallen hierdoor een
        // waarneming verliezen (S2-B1b, ontwerp par. 3): daarna is de verwijzing weg.
        List<Long> observedCases = issueCases.findCaseIdsByBatchId(batchId);
        // Ná de issuerijen (foreign key): een samenvatting van verdwenen problemen zou een verzonnen
        // aantal zijn.
        issueGroups.deleteByBatchId(batchId);
        // En dan de behandelgevallen bijwerken: EERST opruimen (verwijderen) voordat we hertellen.
        // CHECK-constraints zijn in PostgreSQL nooit deferrable; een tussentoestand die de constraint
        // ck_issue_case_observations schendt mag dus nooit als apart statement geschreven worden.
        // Een geval opruimen betekent: nooit een geldige waarneming gehad én waaraan nog nooit een mens
        // geraakt heeft. Een geval waar al een mens aan raakte blijft staan met observation_count = 0
        // - exact wat ck_issue_case_observations toelaat, en zijn beslissing mag niet verdwijnen.
        int discarded = issueCases.deleteWithoutObservationsOrHumanAction(observedCases);
        issueCases.recount(observedCases, Instant.now());
        if (!observedCases.isEmpty()) {
            LOG.warn("Batch {} lost its observations for {} issue case(s); {} untouched case(s) removed",
                    batchId, observedCases.size(), discarded);
        }
        batch.setStagedRowCount(0);
        batch.setBlockedCode(CODE_SCREENING_INTERRUPTED);
        batch.setBlockedReason(truncate(CODE_SCREENING_INTERRUPTED
                + ": the application stopped while this batch was being screened; staging and row issues "
                + "were removed, upload the delivery again"));
        // In dezelfde wijziging als de terminale overgang (ck_import_batch_claim_open).
        batch.releaseProcessing();
        batch.setStatus(ImportBatchStatus.FAILED);
        batch.setFinishedAt(Instant.now());
        batches.saveAndFlush(batch);
        TaskRun run = batch.getTaskRun() == null ? null : runs.findById(batch.getTaskRun().getId()).orElse(null);
        if (run != null && run.getStatus() != TaskRunStatus.FAILED) {
            run.setStatus(TaskRunStatus.FAILED);
            run.setFinishedAt(Instant.now());
            runs.saveAndFlush(run);
        }
        LOG.warn("Batch {} was interrupted while screening: marked FAILED ({})", batchId,
                CODE_SCREENING_INTERRUPTED);
        return Recovered.HANDLED;
    }

    private static String truncate(String value) {
        return value.length() <= MAX_BLOCKED_REASON_LENGTH ? value : value.substring(0, MAX_BLOCKED_REASON_LENGTH);
    }
}
