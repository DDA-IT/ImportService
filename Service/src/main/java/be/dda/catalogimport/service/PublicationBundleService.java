package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleBatch;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.domain.ValidationResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * De Publicatiebundel: aanmaken, batches toevoegen/verwijderen, kandidaten opzoeken (ontwerp fase 4
 * par. 2-3, R-BND). Bouwstap 4b bevat bewust nog geen beslissingen, bevriezing of annulering (4c-4f).
 * <p>
 * Zelfde patroon als {@code SourceStateBaselineService}: niet {@code @Transactional}, één
 * {@link TransactionTemplate} per aanroep, {@link PublicationBundleRepository#findByIdForUpdate} als
 * serialisatiepunt tegen gelijktijdige wijzigingen aan dezelfde bundel.
 */
@Service
public class PublicationBundleService {

    /** Een tweede aanmaak met dezelfde {@code bundleReference} maar een andere scope. */
    public static final String CODE_REFERENCE_REUSED = "BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE";
    /** Onbekende bundel. */
    public static final String CODE_BUNDLE_NOT_FOUND = "BUNDLE_NOT_FOUND";
    /** Onbekende batch. */
    public static final String CODE_BATCH_NOT_FOUND = "BATCH_NOT_FOUND";
    /** De bundel accepteert geen wijzigingen meer (niet {@code ASSEMBLING}). */
    public static final String CODE_BUNDLE_NOT_ASSEMBLING = "BUNDLE_NOT_ASSEMBLING";
    /** De batch heeft elders al een actief lidmaatschap. */
    public static final String CODE_BATCH_ALREADY_IN_BUNDLE = "BATCH_ALREADY_IN_BUNDLE";
    /** De batch is niet {@code SCREENED} (of al {@code BASELINE_ACCEPTED}) en dus niet bundelbaar. */
    public static final String CODE_BATCH_NOT_BUNDLEABLE = "BATCH_NOT_BUNDLEABLE";
    /** De batch is {@code SCREENED} maar heeft nog geen vastgesteld {@code validationResult}. */
    public static final String CODE_BATCH_VALIDATION_NOT_ESTABLISHED = "BATCH_VALIDATION_NOT_ESTABLISHED";
    /** De batch heeft {@code validationResult = BLOCKING}. */
    public static final String CODE_BATCH_VALIDATION_BLOCKING = "BATCH_VALIDATION_BLOCKING";
    /** De batch heeft in deze bundel geen actief lidmaatschap (meer). */
    public static final String CODE_BATCH_NOT_IN_BUNDLE = "BATCH_NOT_IN_BUNDLE";
    /** De batch heeft al besliste mutaties en kan daarom niet meer uit de bundel verwijderd worden. */
    public static final String CODE_BATCH_HAS_DECIDED_MUTATIONS = "BATCH_HAS_DECIDED_MUTATIONS";

    /** {@code publication_bundle.bundle_reference}: varchar(100). */
    static final int MAX_REFERENCE_LENGTH = 100;
    /** {@code publication_bundle.description}: varchar(500). */
    static final int MAX_DESCRIPTION_LENGTH = 500;
    /** {@code publication_bundle.publication_policy}: varchar(1000). */
    static final int MAX_POLICY_LENGTH = 1000;
    /** {@code publication_bundle.created_by} / {@code publication_bundle_batch.added_by/removed_by}: varchar(100). */
    static final int MAX_ACTOR_LENGTH = 100;
    /** {@code publication_bundle_batch.removed_reason}: varchar(500). */
    static final int MAX_REASON_LENGTH = 500;

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAX_PAGE_SIZE = 200;

    /**
     * De onveranderlijke identiteitsgegevens van een aangemaakte (of via idempotentie teruggevonden)
     * bundel; de tellers en de bevroren/geannuleerde audit staan in {@code BundleQueryService.getBundle}.
     */
    public record BundleReference(long id, String bundleReference, String description, String status,
                                  String targetMode, Instant targetMoment, String publicationPolicy,
                                  String createdBy, Instant createdAt, String idempotencyKey) {

        private static BundleReference of(PublicationBundle bundle) {
            return new BundleReference(bundle.getId(), bundle.getBundleReference(), bundle.getDescription(),
                    bundle.getStatus().name(), bundle.getTargetMode().name(), bundle.getTargetMoment(),
                    bundle.getPublicationPolicy(), bundle.getCreatedBy(), bundle.getCreatedAt(),
                    bundle.getIdempotencyKey());
        }
    }

    /** Eén batchlidmaatschap, actief of verwijderd. */
    public record Membership(long id, long bundleId, long batchId, long importLinkId, String addedBy,
                             Instant addedAt, String removedBy, Instant removedAt, String removedReason,
                             boolean active) {

        private static Membership of(PublicationBundleBatch membership) {
            return new Membership(membership.getId(), membership.getBundle().getId(), membership.getBatch().getId(),
                    membership.getImportLink().getId(), membership.getAddedBy(), membership.getAddedAt(),
                    membership.getRemovedBy(), membership.getRemovedAt(), membership.getRemovedReason(),
                    membership.getActiveMarker() != null);
        }
    }

    /** Eén batch die in aanmerking komt om aan een bundel toegevoegd te worden. */
    public record BundleCandidate(long batchId, long importLinkId, String status, String validationResult,
                                  Long contentMutationCount, Instant finishedAt) {

        private static BundleCandidate of(ImportBatch batch) {
            return new BundleCandidate(batch.getId(), batch.getImportLink().getId(), batch.getStatus().name(),
                    batch.getValidationResult() == null ? null : batch.getValidationResult().name(),
                    batch.getContentMutationCount(), batch.getFinishedAt());
        }
    }

    private final PublicationBundleRepository bundles;
    private final PublicationBundleBatchRepository bundleBatches;
    private final ImportBatchRepository batches;
    private final ImportMutationRepository mutations;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PublicationBundleService(PublicationBundleRepository bundles,
                                    PublicationBundleBatchRepository bundleBatches,
                                    ImportBatchRepository batches, ImportMutationRepository mutations,
                                    PlatformTransactionManager transactionManager, Clock clock) {
        this.bundles = bundles;
        this.bundleBatches = bundleBatches;
        this.batches = batches;
        this.mutations = mutations;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Maakt een bundel aan, of geeft de bestaande bundel terug als {@code bundleReference} al bestaat
     * met dezelfde scope ({@code targetMode} + {@code description}) — idempotent op
     * {@code idempotency_key = 'bundle:' || bundleReference}, zelfde patroon als een delivery-retry.
     * Een andere scope bij dezelfde referentie is een conflict: de referentie mag niet stilzwijgend
     * een andere bundel gaan betekenen.
     *
     * @throws IllegalArgumentException ontbrekende/ongeldige velden, ontbrekende {@code targetMode}
     * @throws ConflictException        {@link #CODE_REFERENCE_REUSED}
     */
    public BundleReference createBundle(String bundleReference, String description, PublicationTargetMode targetMode,
                                        Instant targetMoment, String publicationPolicy, String createdBy) {
        String reference = ActorNames.requireText(bundleReference, "bundleReference", MAX_REFERENCE_LENGTH);
        String creator = ActorNames.requireActorName(createdBy, "createdBy", MAX_ACTOR_LENGTH);
        if (targetMode == null) {
            throw new IllegalArgumentException("Missing targetMode");
        }
        String trimmedDescription = trimToNull(description, "description", MAX_DESCRIPTION_LENGTH);
        String trimmedPolicy = trimToNull(publicationPolicy, "publicationPolicy", MAX_POLICY_LENGTH);

        return transaction.execute(status -> {
            Optional<PublicationBundle> existing = bundles.findByBundleReference(reference);
            if (existing.isPresent()) {
                return BundleReference.of(requireSameScope(existing.get(), targetMode, trimmedDescription));
            }
            PublicationBundle bundle = new PublicationBundle(reference, targetMode, creator);
            bundle.setDescription(trimmedDescription);
            bundle.setTargetMoment(targetMoment);
            bundle.setPublicationPolicy(trimmedPolicy);
            try {
                return BundleReference.of(bundles.saveAndFlush(bundle));
            } catch (DataIntegrityViolationException race) {
                // Twee gelijktijdige aanmaken met dezelfde referentie: de unieke constraint besliste,
                // niet deze aanroep. Behandel het exact zoals een normale idempotente hervinding.
                PublicationBundle found = bundles.findByBundleReference(reference)
                        .orElseThrow(() -> race);
                return BundleReference.of(requireSameScope(found, targetMode, trimmedDescription));
            }
        });
    }

    private static PublicationBundle requireSameScope(PublicationBundle existing, PublicationTargetMode targetMode,
                                                       String description) {
        boolean sameScope = existing.getTargetMode() == targetMode
                && Objects.equals(existing.getDescription(), description);
        if (!sameScope) {
            throw new ConflictException(CODE_REFERENCE_REUSED, "Bundle reference '" + existing.getBundleReference()
                    + "' already exists with a different scope (targetMode=" + existing.getTargetMode()
                    + ", description=" + existing.getDescription() + ")");
        }
        return existing;
    }

    /**
     * Voegt batches in één transactie toe aan een bundel, alles-of-niets: faalt één batch, dan wordt
     * er niets toegevoegd. Volgorde van de controles per batch: bestaat de batch, actief lidmaatschap
     * elders, {@code SCREENED}, vastgesteld {@code validationResult} dat geen {@code BLOCKING} is.
     *
     * @throws NotFoundException        {@link #CODE_BUNDLE_NOT_FOUND}, {@link #CODE_BATCH_NOT_FOUND}
     * @throws ConflictException        {@link #CODE_BUNDLE_NOT_ASSEMBLING}, {@link #CODE_BATCH_ALREADY_IN_BUNDLE},
     *                                  {@link #CODE_BATCH_NOT_BUNDLEABLE}, {@link #CODE_BATCH_VALIDATION_NOT_ESTABLISHED},
     *                                  {@link #CODE_BATCH_VALIDATION_BLOCKING}
     * @throws IllegalArgumentException lege batchlijst, ongeldige {@code addedBy}
     */
    public List<Membership> addBatches(long bundleId, List<Long> batchIds, String addedBy) {
        String adder = ActorNames.requireActorName(addedBy, "addedBy", MAX_ACTOR_LENGTH);
        if (batchIds == null || batchIds.isEmpty()) {
            throw new IllegalArgumentException("batchIds must not be empty");
        }
        return transaction.execute(status -> {
            PublicationBundle bundle = requireBundleForUpdate(bundleId);
            requireAssembling(bundle);
            List<Membership> memberships = new ArrayList<>(batchIds.size());
            for (Long batchId : batchIds) {
                memberships.add(Membership.of(addOneBatch(bundle, batchId, adder)));
            }
            return memberships;
        });
    }

    private PublicationBundleBatch addOneBatch(PublicationBundle bundle, long batchId, String addedBy) {
        ImportBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new NotFoundException(CODE_BATCH_NOT_FOUND, "Batch " + batchId + " not found"));
        if (bundleBatches.findByBatchIdAndActiveMarkerIsNotNull(batchId).isPresent()) {
            throw new ConflictException(CODE_BATCH_ALREADY_IN_BUNDLE,
                    "Batch " + batchId + " already has an active publication bundle membership");
        }
        if (batch.getStatus() != ImportBatchStatus.SCREENED) {
            throw new ConflictException(CODE_BATCH_NOT_BUNDLEABLE, "Batch " + batchId + " is in status "
                    + batch.getStatus() + "; only a SCREENED batch (not yet baseline-accepted) can join a bundle");
        }
        ValidationResult result = batch.getValidationResult();
        if (result == null) {
            throw new ConflictException(CODE_BATCH_VALIDATION_NOT_ESTABLISHED,
                    "Batch " + batchId + " has no established validation result yet");
        }
        if (result == ValidationResult.BLOCKING) {
            throw new ConflictException(CODE_BATCH_VALIDATION_BLOCKING,
                    "Batch " + batchId + " has a blocking validation result");
        }
        PublicationBundleBatch membership = new PublicationBundleBatch(bundle, batch, batch.getImportLink(), addedBy);
        return bundleBatches.saveAndFlush(membership);
    }

    /**
     * Verwijdert een batch uit een bundel: enkel toegestaan zolang de bundel {@code ASSEMBLING} is en
     * geen enkele mutatie van deze batch al een beslissing draagt (die zou een append-only
     * beslissingsregel wees maken zonder geldig lidmaatschap).
     *
     * @throws NotFoundException {@link #CODE_BUNDLE_NOT_FOUND}, {@link #CODE_BATCH_NOT_IN_BUNDLE}
     * @throws ConflictException {@link #CODE_BUNDLE_NOT_ASSEMBLING}, {@link #CODE_BATCH_HAS_DECIDED_MUTATIONS}
     */
    public Membership removeBatch(long bundleId, long batchId, String removedBy, String reason) {
        String remover = ActorNames.requireActorName(removedBy, "removedBy", MAX_ACTOR_LENGTH);
        String removalReason = ActorNames.requireText(reason, "reason", MAX_REASON_LENGTH);
        return transaction.execute(status -> {
            PublicationBundle bundle = requireBundleForUpdate(bundleId);
            requireAssembling(bundle);
            PublicationBundleBatch membership = bundleBatches.findByBundleIdAndBatchId(bundleId, batchId)
                    .filter(m -> m.getActiveMarker() != null)
                    .orElseThrow(() -> new NotFoundException(CODE_BATCH_NOT_IN_BUNDLE,
                            "Batch " + batchId + " has no active membership in bundle " + bundleId));
            if (mutations.existsByBatchIdAndDecisionIdIsNotNull(batchId)) {
                throw new ConflictException(CODE_BATCH_HAS_DECIDED_MUTATIONS, "Batch " + batchId
                        + " has decided mutations and can no longer be removed from bundle " + bundleId);
            }
            membership.recordRemoval(remover, clock.instant(), removalReason);
            return Membership.of(bundleBatches.saveAndFlush(membership));
        });
    }

    /**
     * Batches die in aanmerking komen om aan een bundel toegevoegd te worden, gepagineerd en optioneel
     * beperkt tot één koppeling ({@link ImportBatchRepository#findBundleCandidates}).
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<BundleCandidate> candidates(Long importLinkId, Integer page, Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<ImportBatch> result = batches.findBundleCandidates(importLinkId, pageRequest);
        return PageResult.of(result, BundleCandidate::of);
    }

    private PublicationBundle requireBundleForUpdate(long bundleId) {
        return bundles.findByIdForUpdate(bundleId)
                .orElseThrow(() -> new NotFoundException(CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
    }

    private static void requireAssembling(PublicationBundle bundle) {
        if (!bundle.getStatus().acceptsChanges()) {
            throw new ConflictException(CODE_BUNDLE_NOT_ASSEMBLING, "Bundle " + bundle.getId() + " is "
                    + bundle.getStatus() + "; batches can only be added to or removed from an ASSEMBLING bundle");
        }
    }

    private static String trimToNull(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            return null;
        }
        return ActorNames.requireText(value, field, maxLength);
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
