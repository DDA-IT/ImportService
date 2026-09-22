package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationDecisionRepository;
import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.BundleDecisionScope;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleBatch;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationDecision;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Het annuleren van een Publicatiebundel (ontwerp fase 4 par. 1 R-FRZ-10, par. 3, par. 4 statusdiagram,
 * par. 6 stap 4f; beslissingslog 22/09, keuze 4). Een aparte klasse naast {@link BundleFreezeService} en
 * {@link BundleDecisionService}: annuleren mag — in tegenstelling tot elke andere bundelactie — vanuit
 * <b>twee</b> statussen ({@code ASSEMBLING} én {@code FROZEN}) en doet iets wat geen van beide andere
 * services doet: batches vrijgeven. Samenvoegen met {@link BundleFreezeService} zou die twee heel
 * verschillende voorwaardenpatronen in één klasse vermengen.
 * <p>
 * Zelfde patroon als de andere bundelservices: niet {@code @Transactional}, één
 * {@link TransactionTemplate}, en {@link PublicationBundleRepository#findByIdForUpdate} als
 * serialisatiepunt.
 *
 * <h2>Wanneer</h2>
 * Vanuit {@code ASSEMBLING} of {@code FROZEN} (elke andere status — {@code CANCELLED},
 * {@code PUBLISHING}, {@code PARTIALLY_PUBLISHED}, {@code PUBLISHED}, {@code PUBLICATION_FAILED} — geeft
 * 409 {@link #CODE_BUNDLE_NOT_CANCELLABLE}). Dat een bevroren bundel nog geannuleerd kan worden zolang
 * Fase 5 niet begonnen is met publiceren, is een bewuste, door de mens bevestigde keuze (beslissingslog
 * 22/09): een vergeten bevroren bundel zou anders voorgoed een aanbieding blokkeren voor elke andere
 * bundel (het cross-bundelconflict R-FRZ-04).
 *
 * <h2>Wat annuleren doet, in één transactie</h2>
 * <ol>
 *   <li>Eén {@code CANCEL}-beslissingsregel (scope {@code BUNDLE}) met {@code affected_count} = het
 *       vooraf getelde aantal mutaties dat {@link PublicationBundleDao#expireOpenMutations} gaat raken —
 *       ook wanneer dat aantal 0 is: in tegenstelling tot {@code AUTO_APPROVE_PLANNED} is dit de
 *       <b>enige</b> auditregel van de annulering, en die mag nooit ontbreken.</li>
 *   <li>Elke nog niet-terminale inhoudelijke mutatie van de actieve leden ({@code PLANNED},
 *       {@code AWAITING_APPROVAL}, {@code READY_FOR_PUBLICATION}) wordt {@code EXPIRED}, met een
 *       verwijzing naar die ene regel. {@code REJECTED}, {@code SKIPPED}, {@code RECORDED},
 *       {@code BLOCKED} en elke identiteitsincidentmutatie blijven exact zoals ze stonden (R-FRZ-10:
 *       "REJECTED blijft REJECTED, BLOCKED blijft BLOCKED, marker blijft RECORDED").</li>
 *   <li>Elk <b>actief</b> batchlidmaatschap wordt vrijgegeven ({@link PublicationBundleBatch#recordRemoval}):
 *       {@code active_marker} wordt {@code null}, zodat de batch weer normaal bruikbaar is — opnieuw aan
 *       een andere bundel toe te voegen (R-BND-03/{@code uk_publication_bundle_batch_active}) én weer in
 *       aanmerking voor {@code accept-baseline} (R-BAS-02, {@code BATCH_IN_PUBLICATION_BUNDLE} verdwijnt).</li>
 *   <li>De bundel zelf gaat naar {@code CANCELLED} met {@code cancelled_by}/{@code cancelled_at}/
 *       {@code cancelled_reason} ({@link PublicationBundle#recordCancellation}).</li>
 * </ol>
 * Alles of niets, net als bevriezen: loopt er iets mis, dan blijft de bundel in haar oorspronkelijke
 * status, staat er geen halve beslissingsregel en is geen enkel lidmaatschap half vrijgegeven.
 *
 * <h2>Financiële onveranderlijkheid</h2>
 * {@code expireOpenMutations} raakt exact dezelfde vijf kolommen als elke andere beslissing
 * ({@code status}, {@code decided_by}, {@code decided_at}, {@code decided_from_status},
 * {@code decision_id}); geen enkel prijsveld of {@code status_reason} wordt aangeraakt.
 */
@Service
public class BundleCancellationService {

    /** De bundel bestaat niet. */
    public static final String CODE_BUNDLE_NOT_FOUND = PublicationBundleService.CODE_BUNDLE_NOT_FOUND;
    /**
     * De bundel is noch {@code ASSEMBLING}, noch {@code FROZEN}, en kan dus niet (meer) geannuleerd
     * worden — ook een tweede annulering van een al {@code CANCELLED} bundel geeft deze code.
     */
    public static final String CODE_BUNDLE_NOT_CANCELLABLE = "BUNDLE_NOT_CANCELLABLE";
    /**
     * De selectie van {@code expireOpenMutations} verschoof tussen de telling en de update, ondanks het
     * slot op de bundel. Alles is teruggedraaid; de aanroeper leest opnieuw en annuleert opnieuw.
     */
    public static final String CODE_CANCELLATION_CONTENT_CHANGED = "BUNDLE_CONTENT_CHANGED_DURING_CANCEL";

    /** {@code publication_bundle.cancelled_by}: varchar(100). */
    static final int MAX_ACTOR_LENGTH = 100;
    /** {@code publication_bundle.cancelled_reason} en {@code publication_decision.reason}: varchar(500). */
    static final int MAX_REASON_LENGTH = 500;
    /** {@code publication_bundle_batch.removed_reason}: varchar(500). */
    static final int MAX_REMOVED_REASON_LENGTH = 500;

    private static final Logger LOG = LoggerFactory.getLogger(BundleCancellationService.class);

    /**
     * Wat één annulering heeft vastgesteld. Het volledige leesmodel van de geannuleerde bundel komt uit
     * {@link BundleQueryService#getBundle}.
     */
    public record BundleCancelView(long bundleId, String status, String cancelledBy, Instant cancelledAt,
                                   String cancelledReason, long expiredMutationCount, int releasedBatchCount,
                                   long cancelDecisionId) {
    }

    private final PublicationBundleRepository bundles;
    private final PublicationBundleBatchRepository bundleBatches;
    private final PublicationDecisionRepository decisions;
    private final PublicationBundleDao dao;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BundleCancellationService(PublicationBundleRepository bundles,
                                     PublicationBundleBatchRepository bundleBatches,
                                     PublicationDecisionRepository decisions, PublicationBundleDao dao,
                                     PlatformTransactionManager transactionManager, Clock clock) {
        this.bundles = bundles;
        this.bundleBatches = bundleBatches;
        this.decisions = decisions;
        this.dao = dao;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Annuleert de bundel: schrijft de {@code CANCEL}-beslissingsregel, laat alle nog niet-terminale
     * mutaties van haar actieve leden vervallen, geeft die leden vrij en sluit de bundel af. Zie de
     * klassedocumentatie voor de volledige volgorde.
     *
     * @param cancelledBy verplicht, niet leeg, hoogstens {@value #MAX_ACTOR_LENGTH} tekens, nooit
     *                    {@code system}: een annulering is altijd van een mens
     * @param reason      <b>verplicht</b>, hoogstens {@value #MAX_REASON_LENGTH} tekens
     * @throws IllegalArgumentException ontbrekende/ongeldige {@code cancelledBy} of {@code reason}
     * @throws NotFoundException        {@link #CODE_BUNDLE_NOT_FOUND}
     * @throws ConflictException        {@link #CODE_BUNDLE_NOT_CANCELLABLE},
     *                                  {@link #CODE_CANCELLATION_CONTENT_CHANGED}
     */
    public BundleCancelView cancel(long bundleId, String cancelledBy, String reason) {
        String canceller = ActorNames.requireActorName(cancelledBy, "cancelledBy", MAX_ACTOR_LENGTH);
        String motivation = ActorNames.requireText(reason, "reason", MAX_REASON_LENGTH);

        return transaction.execute(status -> {
            PublicationBundle bundle = bundles.findByIdForUpdate(bundleId).orElseThrow(
                    () -> new NotFoundException(CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
            PublicationBundleStatus previousStatus = requireCancellable(bundle);
            Instant cancelledAt = clock.instant();

            long expirable = dao.countExpirableMutations(bundleId);
            PublicationDecision decision = new PublicationDecision(bundle, null, BundleDecisionKind.CANCEL,
                    BundleDecisionScope.BUNDLE, expirable, canceller, cancelledAt, motivation);
            decision.setPreviousStatus(previousStatus.name());
            decision.setNewStatus(PublicationBundleStatus.CANCELLED.name());
            PublicationDecision saved = decisions.saveAndFlush(decision);

            int expired = expirable == 0 ? 0
                    : dao.expireOpenMutations(bundleId, saved.getId(), canceller, cancelledAt);
            if (expired != expirable) {
                // Kan enkel wanneer de inhoud tussen de telling en de update verschoof, ondanks het slot
                // op de bundel. Alles terugdraaien is dan het enige eerlijke antwoord: een
                // beslissingsregel die een ander aantal claimt dan ze raakte, is geen audit meer.
                throw new ConflictException(CODE_CANCELLATION_CONTENT_CHANGED, "The content of bundle "
                        + bundleId + " changed while it was being cancelled (" + expirable + " mutations "
                        + "counted, " + expired + " updated); nothing was changed. Read the bundle again and "
                        + "cancel once more");
            }

            List<PublicationBundleBatch> memberships = bundleBatches.findByBundleIdAndActiveMarkerIsNotNull(bundleId);
            String removalReason = removalReasonFor(motivation);
            for (PublicationBundleBatch membership : memberships) {
                membership.recordRemoval(canceller, cancelledAt, removalReason);
            }
            bundleBatches.saveAll(memberships);

            bundle.recordCancellation(canceller, cancelledAt, motivation);
            bundles.saveAndFlush(bundle);

            LOG.info("Bundle {} cancelled by {} (was {}, {} mutations expired, {} batches released), decision {}: {}",
                    bundleId, canceller, previousStatus, expired, memberships.size(), saved.getId(), motivation);
            return new BundleCancelView(bundleId, bundle.getStatus().name(), canceller, cancelledAt, motivation,
                    expired, memberships.size(), saved.getId());
        });
    }

    /**
     * {@code "Bundle cancelled: " + reason} zodra dat binnen de {@value #MAX_REMOVED_REASON_LENGTH}
     * tekens van {@code removed_reason} past; anders de reden zelf, zonder voorvoegsel. Nooit stil
     * afgekapt: {@code reason} zelf is al gevalideerd op hoogstens {@value #MAX_REASON_LENGTH} tekens,
     * dus enkel het voorvoegsel zou de combinatie soms net over de grens duwen.
     */
    private static String removalReasonFor(String reason) {
        String withPrefix = "Bundle cancelled: " + reason;
        return withPrefix.length() <= MAX_REMOVED_REASON_LENGTH ? withPrefix : reason;
    }

    /**
     * Annuleren mag vanuit {@code ASSEMBLING} én {@code FROZEN} — expliciet anders dan elke andere
     * bundelactie, die enkel {@code ASSEMBLING} toestaat (beslissingslog 22/09, keuze 4).
     *
     * @return de status van de bundel vóór deze annulering, voor {@code previous_status} op de
     *         beslissingsregel
     */
    private static PublicationBundleStatus requireCancellable(PublicationBundle bundle) {
        PublicationBundleStatus current = bundle.getStatus();
        if (current != PublicationBundleStatus.ASSEMBLING && current != PublicationBundleStatus.FROZEN) {
            throw new ConflictException(CODE_BUNDLE_NOT_CANCELLABLE, "Bundle " + bundle.getId() + " is "
                    + current + "; only an ASSEMBLING or FROZEN bundle can be cancelled");
        }
        return current;
    }
}
