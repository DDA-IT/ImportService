package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleDao.MutationStatusCount;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleBatch;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Leesmodel van de Publicatiebundel (ontwerp fase 4 par. 3 en 6, bouwstap 4b): records, geen
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
     * de rij zelf. Na bevriezen (4e) komen ze van de bevroren rij. {@code staleMutationCount} is de
     * informatieve, niet-blokkerende baselinecontrole (R-BND-06); {@code null} zodra de bundel niet meer
     * {@code ASSEMBLING} is — die controle is dan al gebeurd of niet meer relevant.
     */
    public record BundleDetail(long id, String bundleReference, String description, String status,
                               String targetMode, Instant targetMoment, String publicationPolicy,
                               String createdBy, Instant createdAt, String frozenBy, Instant frozenAt,
                               String frozenReason, String cancelledBy, Instant cancelledAt,
                               String cancelledReason, Long batchCount, Long contentMutationCount,
                               Long readyCount, Long rejectedCount, Long blockedCount, Long expiredCount,
                               Long identityIncidentCount, Long bulkIncidentCount, Long criticalIssueCount,
                               Long warningCount, Long staleMutationCount) {

        private static BundleDetail frozen(PublicationBundle bundle) {
            return new BundleDetail(bundle.getId(), bundle.getBundleReference(), bundle.getDescription(),
                    bundle.getStatus().name(), bundle.getTargetMode().name(), bundle.getTargetMoment(),
                    bundle.getPublicationPolicy(), bundle.getCreatedBy(), bundle.getCreatedAt(),
                    bundle.getFrozenBy(), bundle.getFrozenAt(), bundle.getFrozenReason(), bundle.getCancelledBy(),
                    bundle.getCancelledAt(), bundle.getCancelledReason(), bundle.getBatchCount(),
                    bundle.getContentMutationCount(), bundle.getReadyCount(), bundle.getRejectedCount(),
                    bundle.getBlockedCount(), bundle.getExpiredCount(), bundle.getIdentityIncidentCount(),
                    bundle.getBulkIncidentCount(), bundle.getCriticalIssueCount(), bundle.getWarningCount(), null);
        }

        private static BundleDetail live(PublicationBundle bundle, List<MutationStatusCount> counts,
                                         long activeBatchCount, long staleCount) {
            long contentMutations = 0;
            long ready = 0;
            long rejected = 0;
            long blocked = 0;
            long identityIncidents = 0;
            for (MutationStatusCount count : counts) {
                boolean isContentMutation = "CREATE".equals(count.actionType()) || "UPDATE".equals(count.actionType());
                if (isContentMutation) {
                    contentMutations += count.count();
                    if ("READY_FOR_PUBLICATION".equals(count.status())) {
                        ready += count.count();
                    } else if ("REJECTED".equals(count.status())) {
                        rejected += count.count();
                    } else if ("BLOCKED".equals(count.status())) {
                        blocked += count.count();
                    }
                } else if ("IDENTITY_REFERENCE_INCIDENT".equals(count.actionType())) {
                    identityIncidents += count.count();
                }
            }
            return new BundleDetail(bundle.getId(), bundle.getBundleReference(), bundle.getDescription(),
                    bundle.getStatus().name(), bundle.getTargetMode().name(), bundle.getTargetMoment(),
                    bundle.getPublicationPolicy(), bundle.getCreatedBy(), bundle.getCreatedAt(),
                    bundle.getFrozenBy(), bundle.getFrozenAt(), bundle.getFrozenReason(), bundle.getCancelledBy(),
                    bundle.getCancelledAt(), bundle.getCancelledReason(), activeBatchCount, contentMutations,
                    ready, rejected, blocked, bundle.getExpiredCount(), identityIncidents,
                    bundle.getBulkIncidentCount(), bundle.getCriticalIssueCount(), bundle.getWarningCount(),
                    staleCount);
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

    private final PublicationBundleRepository bundles;
    private final PublicationBundleBatchRepository bundleBatches;
    private final PublicationBundleDao dao;

    public BundleQueryService(PublicationBundleRepository bundles, PublicationBundleBatchRepository bundleBatches,
                              PublicationBundleDao dao) {
        this.bundles = bundles;
        this.bundleBatches = bundleBatches;
        this.dao = dao;
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
            return BundleDetail.live(bundle, counts, activeBatchCount, stale);
        }
        return BundleDetail.frozen(bundle);
    }

    /**
     * De bundels, oplopend op id, optioneel beperkt tot één status. Geen live tellers (te duur per
     * pagina); zie {@link #getBundle} voor de actuele stand van één bundel.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<BundleSummary> listBundles(PublicationBundleStatus status, Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<PublicationBundle> result = status == null ? bundles.findAll(pageRequest)
                : bundles.findByStatus(status, pageRequest);
        return PageResult.of(result, BundleSummary::of);
    }

    /**
     * De lidmaatschappen van een bundel (actief en verwijderd), oplopend op id.
     *
     * @throws NotFoundException        {@link PublicationBundleService#CODE_BUNDLE_NOT_FOUND}
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<BundleBatchRow> getBundleBatches(long bundleId, Integer page, Integer size) {
        requireBundle(bundleId);
        PageRequest pageRequest = pageRequest(page, size);
        Page<PublicationBundleBatch> result = bundleBatches.findByBundleId(bundleId, pageRequest);
        return PageResult.of(result, BundleBatchRow::of);
    }

    private PublicationBundle requireBundle(long bundleId) {
        return bundles.findById(bundleId).orElseThrow(() -> new NotFoundException(
                PublicationBundleService.CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
    }

    private static PageRequest pageRequest(Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE));
    }
}
