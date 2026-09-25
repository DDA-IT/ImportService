package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.MutationDao;
import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleDao.MutationStatusCount;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationDecisionRepository;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleBatch;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationDecision;
import be.dda.catalogimport.service.BatchQueryService.MutationRow;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leesmodel van de Publicatiebundel (ontwerp fase 4 par. 3 en 6, bouwstappen 4b en 4c): records, geen
 * JPA-entiteiten, zelfde stijl als {@code BatchQueryService}.
 * <p>
 * Paginering: {@code page} 0-gebaseerd, {@code size} standaard {@value #DEFAULT_PAGE_SIZE} en begrensd
 * tot {@value #MAX_PAGE_SIZE}.
 */
@Service
@Transactional(readOnly = true)
public class BundleQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * Overzicht van één bundel voor de lijstweergave: de opgeslagen velden zoals ze op de rij staan,
     * zonder live herberekening. Voor de actuele tellers van een {@code ASSEMBLING}-bundel: zie
     * {@link BundleDetail}.
     */
    public record BundleSummary(long id, String bundleReference, String description, String status,
                                String targetMode, Instant targetMoment, String publicationPolicy,
                                String createdBy, Instant createdAt, String frozenBy, Instant frozenAt,
                                String frozenReason, String cancelledBy, Instant cancelledAt,
                                String cancelledReason, Long batchCount, Long contentMutationCount,
                                Long readyCount, Long rejectedCount, Long blockedCount, Long expiredCount,
                                Long identityIncidentCount, Long bulkIncidentCount, Long criticalIssueCount,
                                Long warningCount) {

        private static BundleSummary of(PublicationBundle bundle) {
            return new BundleSummary(bundle.getId(), bundle.getBundleReference(), bundle.getDescription(),
                    bundle.getStatus().name(), bundle.getTargetMode().name(), bundle.getTargetMoment(),
                    bundle.getPublicationPolicy(), bundle.getCreatedBy(), bundle.getCreatedAt(),
                    bundle.getFrozenBy(), bundle.getFrozenAt(), bundle.getFrozenReason(), bundle.getCancelledBy(),
                    bundle.getCancelledAt(), bundle.getCancelledReason(), bundle.getBatchCount(),
                    bundle.getContentMutationCount(), bundle.getReadyCount(), bundle.getRejectedCount(),
                    bundle.getBlockedCount(), bundle.getExpiredCount(), bundle.getIdentityIncidentCount(),
                    bundle.getBulkIncidentCount(), bundle.getCriticalIssueCount(), bundle.getWarningCount());
        }
    }

    /**
     * Volledige stand van één bundel. Zolang de bundel {@code ASSEMBLING} is, zijn
     * {@code contentMutationCount}/{@code readyCount}/{@code rejectedCount}/{@code blockedCount}/
     * {@code identityIncidentCount} <b>live</b> berekend over haar actieve batches
     * ({@code PublicationBundleDao.countByStatus}) in plaats van de (nog niet vastgestelde) waarden op
     * de rij zelf. Zodra de bundel bevroren is (4e), komen alle tien de tellers van de rij: dat zijn de
     * getallen die op het moment van bevriezen zijn vastgesteld en waarvoor getekend is — ze mogen
     * daarna nooit meer meebewegen.
     * <p>
     * {@code staleMutationCount} is de informatieve, niet-blokkerende baselinecontrole (R-BND-06);
     * {@code null} zodra de bundel niet meer {@code ASSEMBLING} is — bij het bevriezen is die controle
     * blokkerend uitgevoerd (R-FRZ) en is ze daarna niet meer van toepassing.
     * <p>
     * {@code contentHash} is de volledige bundelhash als hexadecimale tekst, enkel gevuld bij een
     * bevroren (of daarna geannuleerde) bundel; {@code null} zolang ze {@code ASSEMBLING} is.
     * <p>
     * {@code plannedCount} (het aantal dat het bevriezen in bulk goedkeurt, {@code countPlanned}) en
     * {@code awaitingApprovalCount} (de blokkadevoorwaarde van het bevriezen, {@code countUndecided}) zijn
     * enkel live gevuld bij {@code ASSEMBLING}; {@code null} bij FROZEN/CANCELLED (geen levend getal meer).
     * <p>
     * {@code expirableCount} (stap C7) is het aantal mutaties dat bij annuleren {@code EXPIRED} wordt
     * ({@code countExpirableMutations}, dezelfde selectie als het annuleren zelf): live gevuld bij
     * {@code ASSEMBLING} én {@code FROZEN} (beide zijn annuleerbaar); {@code null} bij {@code CANCELLED}.
     */
    public record BundleDetail(long id, String bundleReference, String description, String status,
                               String targetMode, Instant targetMoment, String publicationPolicy,
                               String createdBy, Instant createdAt, String frozenBy, Instant frozenAt,
                               String frozenReason, String cancelledBy, Instant cancelledAt,
                               String cancelledReason, Long batchCount, Long contentMutationCount,
                               Long readyCount, Long rejectedCount, Long blockedCount, Long expiredCount,
                               Long identityIncidentCount, Long bulkIncidentCount, Long criticalIssueCount,
                               Long warningCount, Long staleMutationCount, String contentHash,
                               Long plannedCount, Long awaitingApprovalCount,
                               Long expirableCount) {

        private static BundleDetail frozen(PublicationBundle bundle, Long expirableCount) {
            return new BundleDetail(bundle.getId(), bundle.getBundleReference(), bundle.getDescription(),
                    bundle.getStatus().name(), bundle.getTargetMode().name(), bundle.getTargetMoment(),
                    bundle.getPublicationPolicy(), bundle.getCreatedBy(), bundle.getCreatedAt(),
                    bundle.getFrozenBy(), bundle.getFrozenAt(), bundle.getFrozenReason(), bundle.getCancelledBy(),
                    bundle.getCancelledAt(), bundle.getCancelledReason(), bundle.getBatchCount(),
                    bundle.getContentMutationCount(), bundle.getReadyCount(), bundle.getRejectedCount(),
                    bundle.getBlockedCount(), bundle.getExpiredCount(), bundle.getIdentityIncidentCount(),
                    bundle.getBulkIncidentCount(), bundle.getCriticalIssueCount(), bundle.getWarningCount(), null,
                    hex(bundle.getContentHash()), null, null, expirableCount);
        }

        private static BundleDetail live(PublicationBundle bundle, List<MutationStatusCount> counts,
                                         long activeBatchCount, long staleCount, long plannedCount,
                                         long awaitingApprovalCount, long expirableCount) {
            BundleMutationTotals totals = BundleMutationTotals.of(counts);
            return new BundleDetail(bundle.getId(), bundle.getBundleReference(), bundle.getDescription(),
                    bundle.getStatus().name(), bundle.getTargetMode().name(), bundle.getTargetMoment(),
                    bundle.getPublicationPolicy(), bundle.getCreatedBy(), bundle.getCreatedAt(),
                    bundle.getFrozenBy(), bundle.getFrozenAt(), bundle.getFrozenReason(), bundle.getCancelledBy(),
                    bundle.getCancelledAt(), bundle.getCancelledReason(), activeBatchCount,
                    totals.contentMutationCount(), totals.readyCount(), totals.rejectedCount(),
                    // expiredCount blijft bewust de opgeslagen waarde (en dus null zolang de bundel
                    // ASSEMBLING is): EXPIRED ontstaat pas bij het annuleren (4f), en dan is de bundel
                    // CANCELLED en leest deze weergave de vastgestelde rij. Gedrag van 4b, ongewijzigd.
                    totals.blockedCount(), bundle.getExpiredCount(), totals.identityIncidentCount(),
                    bundle.getBulkIncidentCount(), bundle.getCriticalIssueCount(), bundle.getWarningCount(),
                    staleCount, hex(bundle.getContentHash()), plannedCount, awaitingApprovalCount,
                    expirableCount);
        }

        /** De bundelhash als hexadecimale tekst; binaire bytes horen niet in een JSON-antwoord. */
        private static String hex(byte[] contentHash) {
            return contentHash == null ? null : HexFormat.of().formatHex(contentHash);
        }
    }

    /** Eén batchlidmaatschap met de status en de inhoudelijke tellers van de betrokken batch. */
    public record BundleBatchRow(long id, long bundleId, long batchId, long importLinkId, String addedBy,
                                 Instant addedAt, String removedBy, Instant removedAt, String removedReason,
                                 boolean active, String batchStatus, Long batchContentMutationCount) {

        private static BundleBatchRow of(PublicationBundleBatch membership) {
            ImportBatch batch = membership.getBatch();
            return new BundleBatchRow(membership.getId(), membership.getBundle().getId(), batch.getId(),
                    membership.getImportLink().getId(), membership.getAddedBy(), membership.getAddedAt(),
                    membership.getRemovedBy(), membership.getRemovedAt(), membership.getRemovedReason(),
                    membership.getActiveMarker() != null, batch.getStatus().name(), batch.getContentMutationCount());
        }
    }

    /**
     * Eén regel uit het append-only beslissingsregister van een bundel (bouwstap 4c, ontwerp par. 2
     * 005-3). Een herziening voegt een <b>nieuwe</b> regel toe; de oude blijft hier staan, met haar
     * eigen {@code previousStatus}/{@code newStatus}. {@code mutationId} is enkel gevuld bij
     * {@code decisionScope = MUTATION}; {@code selectionFilter} enkel bij een groepsactie (4d).
     */
    public record DecisionRow(long id, long bundleId, Long mutationId, String decisionKind, String decisionScope,
                              String selectionFilter, String previousStatus, String newStatus,
                              long affectedCount, String decidedBy, Instant decidedAt, String reason) {

        static DecisionRow of(PublicationDecision decision) {
            return new DecisionRow(decision.getId(), decision.getBundle().getId(),
                    decision.getMutation() == null ? null : decision.getMutation().getId(),
                    decision.getDecisionKind().name(), decision.getDecisionScope().name(),
                    decision.getSelectionFilter(), decision.getPreviousStatus(), decision.getNewStatus(),
                    decision.getAffectedCount(), decision.getDecidedBy(), decision.getDecidedAt(),
                    decision.getReason());
        }
    }

    private final PublicationBundleRepository bundles;
    private final PublicationBundleBatchRepository bundleBatches;
    private final ImportMutationRepository mutations;
    private final PublicationDecisionRepository decisions;
    private final PublicationBundleDao dao;
    /** Enkel voor de niet-gemapte {@code identity_hash} van een opgehaalde pagina (bouwstap C4). */
    private final MutationDao mutationHashes;

    public BundleQueryService(PublicationBundleRepository bundles, PublicationBundleBatchRepository bundleBatches,
                              ImportMutationRepository mutations, PublicationDecisionRepository decisions,
                              PublicationBundleDao dao, MutationDao mutationHashes) {
        this.bundles = bundles;
        this.bundleBatches = bundleBatches;
        this.mutations = mutations;
        this.decisions = decisions;
        this.dao = dao;
        this.mutationHashes = mutationHashes;
    }

    /**
     * Volledige stand van één bundel, met live tellers zolang ze {@code ASSEMBLING} is.
     *
     * @throws NotFoundException {@link PublicationBundleService#CODE_BUNDLE_NOT_FOUND}
     */
    public BundleDetail getBundle(long bundleId) {
        PublicationBundle bundle = requireBundle(bundleId);
        if (bundle.getStatus() == PublicationBundleStatus.ASSEMBLING) {
            List<MutationStatusCount> counts = dao.countByStatus(bundleId);
            long activeBatchCount = bundleBatches.countByBundleIdAndActiveMarkerIsNotNull(bundleId);
            long stale = dao.countStaleMutations(bundleId);
            return BundleDetail.live(bundle, counts, activeBatchCount, stale, dao.countPlanned(bundleId),
                    dao.countUndecided(bundleId), dao.countExpirableMutations(bundleId));
        }
        Long expirable = bundle.getStatus() == PublicationBundleStatus.FROZEN
                ? dao.countExpirableMutations(bundleId) : null;
        return BundleDetail.frozen(bundle, expirable);
    }

    /**
     * De bundels, aflopend op id (nieuwste eerst; beslissingslog 23/09 "Frontend-slice 1", vraag Q4),
     * optioneel beperkt tot één status. Geen live tellers (te duur per pagina); zie {@link #getBundle}
     * voor de actuele stand van één bundel.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<BundleSummary> listBundles(PublicationBundleStatus status, Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<PublicationBundle> result = status == null ? bundles.findAll(pageRequest)
                : bundles.findByStatus(status, pageRequest);
        return PageResult.of(result, BundleSummary::of);
    }

    /**
     * De lidmaatschappen van een bundel (actief en verwijderd), oplopend op id (beslissingslog 23/09
     * "Frontend-slice 1", vraag Q4).
     *
     * @throws NotFoundException        {@link PublicationBundleService#CODE_BUNDLE_NOT_FOUND}
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<BundleBatchRow> getBundleBatches(long bundleId, Integer page, Integer size) {
        requireBundle(bundleId);
        PageRequest pageRequest = pageRequest(page, size, Sort.by(Sort.Direction.ASC, "id"));
        Page<PublicationBundleBatch> result = bundleBatches.findByBundleId(bundleId, pageRequest);
        return PageResult.of(result, BundleBatchRow::of);
    }

    /**
     * De mutatielijst van een bundel: alle mutaties van haar <b>actieve</b> batchlidmaatschappen,
     * oplopend op id, optioneel gefilterd (bouwstap 4c). Elke regel toont ook haar beslissing
     * ({@code decidedBy}/{@code decidedAt}/{@code decidedFromStatus}/{@code decisionId}).
     * <p>
     * De bundel van een mutatie loopt bewust via haar batch (ontwerp par. 2): er is geen
     * {@code publication_bundle_id} op {@code import_mutation}. Een batch waarvan het lidmaatschap
     * verwijderd is, valt hier dus meteen weg.
     *
     * @param statusReason exacte (hoofdlettergevoelige) statusreden; {@code null} of blanco = geen
     *                     filter, een onbekende reden geeft een lege pagina
     * @param batchId enkel de mutaties van deze batch; een batch zonder actief lidmaatschap in deze
     *                bundel levert een lege pagina op (en geen fout: het lidmaatschap kan net
     *                verwijderd zijn)
     * @param identityHash enkel de mutaties met deze identiteitshash — de wijzigingsgroep van één
     *                     aanbieding (bouwstap C4). Hexadecimaal, hoofdletterongevoelig; {@code null}
     *                     of blanco = geen filter. Een onbekende of ongeldige waarde levert een lege
     *                     pagina op en geen fout. Het filter werkt aan de serverkant en dus over
     *                     paginagrenzen heen — dat is precies waarom het geen UI-groepering geworden is
     *                     (ontwerp scherm 3 par. 11.5)
     * @throws NotFoundException        {@link PublicationBundleService#CODE_BUNDLE_NOT_FOUND}
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<MutationRow> getBundleMutations(long bundleId, MutationStatus status, Long batchId,
                                                      MutationActionType actionType, String statusReason,
                                                      String identityHash, Integer page, Integer size) {
        requireBundle(bundleId);
        boolean byIdentityHash = BatchQueryService.isIdentityHashFilter(identityHash);
        // Ongesorteerde Pageable voor de native variant: die draagt haar eigen "order by m.id".
        PageRequest pageRequest = byIdentityHash ? pageRequest(page, size, Sort.unsorted())
                : pageRequest(page, size, Sort.by("id"));
        List<Long> batchIds = bundleBatches.findByBundleIdAndActiveMarkerIsNotNull(bundleId).stream()
                .map(membership -> membership.getBatch().getId())
                .filter(id -> batchId == null || id.equals(batchId))
                .toList();
        if (batchIds.isEmpty()) {
            return BatchQueryService.emptyMutationPage(pageRequest);
        }
        String reason = statusReason == null || statusReason.isBlank() ? null : statusReason;
        if (byIdentityHash) {
            byte[] hash = BatchQueryService.parseIdentityHash(identityHash);
            if (hash == null) {
                return BatchQueryService.emptyMutationPage(pageRequest);
            }
            return BatchQueryService.mutationRows(mutations.findBundleMutationsByIdentityHash(batchIds,
                    BatchQueryService.name(status), BatchQueryService.name(actionType), reason, hash,
                    pageRequest), mutationHashes);
        }
        Page<ImportMutation> result = mutations.findBundleMutations(batchIds, status, actionType, reason,
                pageRequest);
        return BatchQueryService.mutationRows(result, mutationHashes);
    }

    /**
     * Het beslissingsregister van een bundel, chronologisch (bouwstap 4c). Append-only: een herziening
     * staat hier als extra regel naast de beslissing die ze herziet, die nooit gewijzigd of verwijderd
     * wordt.
     *
     * @throws NotFoundException        {@link PublicationBundleService#CODE_BUNDLE_NOT_FOUND}
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<DecisionRow> getBundleDecisions(long bundleId, Integer page, Integer size) {
        requireBundle(bundleId);
        PageRequest pageRequest = pageRequest(page, size, Sort.by("decidedAt", "id"));
        return PageResult.of(decisions.findByBundleId(bundleId, pageRequest), DecisionRow::of);
    }

    private PublicationBundle requireBundle(long bundleId) {
        return bundles.findById(bundleId).orElseThrow(() -> new NotFoundException(
                PublicationBundleService.CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
    }

    private static PageRequest pageRequest(Integer page, Integer size, Sort sort) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), sort);
    }
}
