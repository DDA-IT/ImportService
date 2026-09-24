package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleDao.BundleBatchTotals;
import be.dda.catalogimport.dao.PublicationBundleDao.CrossBundleOfferConflict;
import be.dda.catalogimport.dao.PublicationBundleDao.InBundleOfferConflict;
import be.dda.catalogimport.dao.PublicationBundleDao.OfferIdentity;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationDecisionRepository;
import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.BundleDecisionScope;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationDecision;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Het bevriezen van een Publicatiebundel (ontwerp fase 4 par. 1 R-FRZ, par. 3, par. 4 statusdiagram,
 * par. 6 stap 4e): de overgang van "nog samen te stellen" naar "vastgelegd en klaar voor Fase 5".
 * Daarna aanvaardt de bundel geen nieuwe leden en geen beslissingen meer; enkel annuleren blijft
 * mogelijk (4f, beslissingslog 22/09 keuze 4).
 * <p>
 * Zelfde patroon als {@link PublicationBundleService} en {@link BundleDecisionService}: niet
 * {@code @Transactional}, één {@link TransactionTemplate}, en
 * {@link PublicationBundleRepository#findByIdForUpdate} als serialisatiepunt.
 *
 * <h2>Voorwaarden, in deze volgorde (R-FRZ)</h2>
 * <ol>
 *   <li>De bundel is {@code ASSEMBLING} (anders 409 {@link #CODE_BUNDLE_NOT_ASSEMBLING}) en heeft
 *       minstens één <b>actief</b> batchlidmaatschap (anders 409 {@link #CODE_BUNDLE_EMPTY}). Een lege
 *       bundel bevriezen zou een goedkeuringsdossier opleveren dat over niets gaat.</li>
 *   <li>Geen enkele inhoudelijke mutatie staat nog op {@code AWAITING_APPROVAL} (409
 *       {@link #CODE_BUNDLE_HAS_UNDECIDED_MUTATIONS}, met het aantal): een regel, drempel of incident
 *       heeft daar om een mens gevraagd, en bevriezen mag die vraag nooit stilzwijgend
 *       beantwoorden.</li>
 *   <li>De bronstaat is niet verschoven sinds de screening (409
 *       {@link #CODE_SOURCE_STATE_CHANGED}, R-BND-06, met het aantal). Wél verschoven betekent dat de
 *       "voor"-waarde waarop deze mutaties gebaseerd zijn niet meer klopt; publiceren zou dan een
 *       wijziging toepassen op een toestand die niet meer bestaat.</li>
 *   <li>Geen twee publiceerbare mutaties op dezelfde {@code (importkoppeling, aanbieding)} uit
 *       verschillende batches binnen deze bundel (409 {@link #CODE_BUNDLE_OFFER_CONFLICT}, R-FRZ-03) en
 *       niet in een andere, niet-geannuleerde bundel (409
 *       {@link #CODE_OFFER_ALREADY_IN_ANOTHER_BUNDLE}, R-FRZ-04). Beide zijn dezelfde regel: "laatste
 *       import wint" is verboden (businessanalyse r.1412). Het conflict verdwijnt zodra één van beide
 *       kanten afgekeurd wordt.</li>
 * </ol>
 *
 * <h2>Wat bevriezen zelf doet</h2>
 * <ol>
 *   <li>Alle resterende {@code PLANNED}-mutaties worden in bulk goedgekeurd
 *       ({@code READY_FOR_PUBLICATION}) op naam van de bevriezer — beslissingslog 22/09, keuze 1 — met
 *       één {@code AUTO_APPROVE_PLANNED}-beslissingsregel (scope {@code BUNDLE}) waarnaar elke geraakte
 *       mutatie wijst, en met per mutatie haar eigen {@code decided_by}/{@code decided_at}/
 *       {@code decided_from_status}. Raakt die bulkgoedkeuring 0 rijen, dan wordt er géén regel
 *       geschreven (zelfde regel als de groepsactie in 4d: een beslissing die niets raakte, is geen
 *       beslissing).</li>
 *   <li>De <b>handeling bevriezen zelf</b> krijgt een tweede, aparte {@code FREEZE}-regel met dezelfde
 *       reden. Bewust los van de bulkgoedkeuring: wie later het register leest, moet kunnen zien dat
 *       iemand de bundel gesloten heeft, ook wanneer er niets automatisch goed te keuren viel.</li>
 *   <li>De tien tellers uit ontwerp par. 2 worden vastgesteld en op de rij bewaard. Vanaf dan toont
 *       {@code GET /bundles/{id}} die bevroren getallen in plaats van live te tellen: dat zijn de
 *       getallen waarvoor getekend is.</li>
 *   <li>De volledige bundelhash wordt berekend en in {@code content_hash} bewaard
 *       ({@link PublicationBundleDao#computeContentHash}).</li>
 *   <li>De bundel gaat naar {@code FROZEN} met {@code frozen_by}/{@code frozen_at}/
 *       {@code frozen_reason} ({@link PublicationBundle#recordFreeze}).</li>
 * </ol>
 *
 * <h2>Alles of niets (R-FRZ-09)</h2>
 * De hele bevriezing zit in <b>één</b> transactie. Loopt er iets mis — een conflict dat pas bij de
 * bulkgoedkeuring blijkt, een databasefout halverwege — dan wordt alles teruggedraaid: de bundel blijft
 * {@code ASSEMBLING}, er staat geen halve beslissingsregel in het register en geen enkele mutatie is
 * half goedgekeurd. "Hervatbaar" betekent hier dus: de aanroep mag onverkort herhaald worden, niet dat
 * er gedeeltelijke voortgang bewaard blijft (ontwerp par. 9, A27). Een tweede geslaagde poging is geen
 * herhaling maar een conflict: de bundel is dan niet meer {@code ASSEMBLING}.
 *
 * <h2>Financiële onveranderlijkheid</h2>
 * Bevriezen raakt geen enkel prijsveld. De bulkgoedkeuring loopt via
 * {@link PublicationBundleDao#approvePlanned}, dat letterlijk hetzelfde codepad is als de groepsactie
 * uit 4d en dus dezelfde {@code set}-lijst van vijf kolommen heeft. De bundelhash <b>leest</b> de
 * prijzen alleen. De bundelrij zelf wordt via JPA geschreven en draagt geen financiële velden.
 */
@Service
public class BundleFreezeService {

    /** De bundel bestaat niet. */
    public static final String CODE_BUNDLE_NOT_FOUND = PublicationBundleService.CODE_BUNDLE_NOT_FOUND;
    /** De bundel is niet (meer) {@code ASSEMBLING} en kan dus niet (nogmaals) bevroren worden. */
    public static final String CODE_BUNDLE_NOT_ASSEMBLING = PublicationBundleService.CODE_BUNDLE_NOT_ASSEMBLING;
    /** De bundel heeft geen enkel actief batchlidmaatschap. */
    public static final String CODE_BUNDLE_EMPTY = "BUNDLE_EMPTY";
    /** Er staan nog {@code AWAITING_APPROVAL}-mutaties open (R-FRZ-02). */
    public static final String CODE_BUNDLE_HAS_UNDECIDED_MUTATIONS = "BUNDLE_HAS_UNDECIDED_MUTATIONS";
    /**
     * De bronstaat van een betrokken koppeling is verschoven sinds de screening (R-BND-06). Bewust
     * dezelfde code als bij {@code accept-baseline}
     * ({@link SourceStateBaselineService#CODE_SOURCE_STATE_CHANGED}): het is dezelfde vaststelling met
     * dezelfde oplossing — opnieuw screenen.
     */
    public static final String CODE_SOURCE_STATE_CHANGED = SourceStateBaselineService.CODE_SOURCE_STATE_CHANGED;
    /** Twee publiceerbare mutaties op dezelfde aanbieding binnen deze bundel (R-FRZ-03). */
    public static final String CODE_BUNDLE_OFFER_CONFLICT = "BUNDLE_OFFER_CONFLICT";
    /** Dezelfde aanbieding staat publiceerbaar in een andere, niet-geannuleerde bundel (R-FRZ-04). */
    public static final String CODE_OFFER_ALREADY_IN_ANOTHER_BUNDLE = "OFFER_ALREADY_IN_ANOTHER_BUNDLE";
    /**
     * De inhoud van de bundel verschoof tussen de telling en de bulkgoedkeuring, ondanks het slot.
     * Alles is teruggedraaid; de aanroeper leest opnieuw en bevriest opnieuw.
     */
    public static final String CODE_FREEZE_CONTENT_CHANGED = "BUNDLE_CONTENT_CHANGED_DURING_FREEZE";

    /** Hoeveel voorbeelden van een conflict in de foutmelding komen; nooit een volledige dump. */
    static final int CONFLICT_SAMPLE_LIMIT = 10;

    /** {@code publication_bundle.frozen_by}: varchar(100). */
    static final int MAX_ACTOR_LENGTH = 100;
    /** {@code publication_bundle.frozen_reason} en {@code publication_decision.reason}: varchar(500). */
    static final int MAX_REASON_LENGTH = 500;

    private static final Logger LOG = LoggerFactory.getLogger(BundleFreezeService.class);

    /**
     * Wat één bevriezing heeft vastgesteld. Het volledige leesmodel van de bevroren bundel (met de tien
     * tellers en de hash) komt uit {@link BundleQueryService#getBundle}.
     *
     * @param autoApproveDecisionId {@code null} wanneer er geen enkele {@code PLANNED}-mutatie meer
     *                              openstond; er is dan bewust geen {@code AUTO_APPROVE_PLANNED}-regel
     *                              geschreven
     */
    public record BundleFreezeView(long bundleId, String status, String frozenBy, Instant frozenAt,
                                   String frozenReason, String contentHash, long autoApprovedCount,
                                   Long autoApproveDecisionId, long freezeDecisionId) {
    }

    /**
     * Uitkomst van {@link #checkFreeze}: een MOMENTOPNAME ZONDER SLOT. {@code freezable = true} is nooit
     * een garantie; {@code freeze} controleert alles opnieuw in zijn eigen transactie.
     *
     * @param blockerCodes        de stabiele foutcodes die {@code freeze} zou geven, in de volgorde van
     *                            R-FRZ; leeg als er niets blokkeert
     * @param inBundleConflicts   leesbare voorbeelden (hoogstens {@value #CONFLICT_SAMPLE_LIMIT})
     * @param crossBundleConflicts leesbare voorbeelden (hoogstens {@value #CONFLICT_SAMPLE_LIMIT})
     */
    public record FreezePreflight(boolean freezable, List<String> blockerCodes, long batchCount, long plannedCount,
                                  long awaitingApprovalCount, long staleMutationCount,
                                  List<String> inBundleConflicts, List<String> crossBundleConflicts) {
    }

    private final PublicationBundleRepository bundles;
    private final PublicationBundleBatchRepository bundleBatches;
    private final PublicationDecisionRepository decisions;
    private final PublicationBundleDao dao;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BundleFreezeService(PublicationBundleRepository bundles, PublicationBundleBatchRepository bundleBatches,
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
     * Bevriest de bundel: controleert alle voorwaarden, keurt de resterende {@code PLANNED}-mutaties in
     * bulk goed, stelt de tellers en de bundelhash vast en sluit de bundel af. Zie de klassedocumentatie
     * voor de volledige volgorde en voor wat er bij een fout halverwege gebeurt.
     *
     * @param frozenBy verplicht, niet leeg, hoogstens {@value #MAX_ACTOR_LENGTH} tekens, nooit
     *                 {@code system}: een bevriezing is altijd van een mens
     * @param reason   <b>verplicht</b> (R-FRZ), hoogstens {@value #MAX_REASON_LENGTH} tekens
     * @throws IllegalArgumentException ontbrekende/ongeldige {@code frozenBy} of {@code reason}
     * @throws NotFoundException        {@link #CODE_BUNDLE_NOT_FOUND}
     * @throws ConflictException        {@link #CODE_BUNDLE_NOT_ASSEMBLING}, {@link #CODE_BUNDLE_EMPTY},
     *                                  {@link #CODE_BUNDLE_HAS_UNDECIDED_MUTATIONS},
     *                                  {@link #CODE_SOURCE_STATE_CHANGED},
     *                                  {@link #CODE_BUNDLE_OFFER_CONFLICT},
     *                                  {@link #CODE_OFFER_ALREADY_IN_ANOTHER_BUNDLE},
     *                                  {@link #CODE_FREEZE_CONTENT_CHANGED}
     */
    public BundleFreezeView freeze(long bundleId, String frozenBy, String reason) {
        String freezer = ActorNames.requireActorName(frozenBy, "frozenBy", MAX_ACTOR_LENGTH);
        String motivation = ActorNames.requireText(reason, "reason", MAX_REASON_LENGTH);

        return transaction.execute(status -> {
            PublicationBundle bundle = bundles.findByIdForUpdate(bundleId).orElseThrow(
                    () -> new NotFoundException(CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
            requireAssembling(bundle);
            long activeBatches = bundleBatches.countByBundleIdAndActiveMarkerIsNotNull(bundleId);
            if (activeBatches == 0) {
                throw new ConflictException(CODE_BUNDLE_EMPTY, "Bundle " + bundleId + " has no active batch "
                        + "membership; an empty bundle cannot be frozen");
            }
            requireEveryMutationDecided(bundleId);
            requireUnchangedSourceState(bundleId);
            requireNoOfferConflicts(bundleId);

            Instant frozenAt = clock.instant();
            AutoApproval autoApproval = approveRemainingPlanned(bundle, freezer, motivation, frozenAt);
            long freezeDecisionId = recordFreezeDecision(bundle, freezer, motivation, frozenAt);

            applyCounts(bundle, activeBatches);
            byte[] contentHash = dao.computeContentHash(bundleId);
            bundle.recordFreeze(freezer, frozenAt, motivation, contentHash);
            bundles.saveAndFlush(bundle);

            LOG.info("Bundle {} frozen by {} ({} batches, {} planned mutations auto-approved, decision {}/{}): {}",
                    bundleId, freezer, activeBatches, autoApproval.affectedCount(), autoApproval.decisionId(),
                    freezeDecisionId, motivation);
            return new BundleFreezeView(bundleId, bundle.getStatus().name(), freezer, frozenAt, motivation,
                    HexFormat.of().formatHex(contentHash), autoApproval.affectedCount(), autoApproval.decisionId(),
                    freezeDecisionId);
        });
    }

    /**
     * Read-only droogloop van {@link #freeze}: leest dezelfde controles (dezelfde queries, dezelfde
     * conflictlimiet) maar werpt niets en schrijft niets, ook geen slot.
     * <p>
     * <b>Dit is een MOMENTOPNAME ZONDER SLOT.</b> {@code freeze} controleert alles opnieuw binnen zijn
     * eigen transactie; {@code freezable = true} is dus nooit een garantie dat een bevriezing daarna
     * slaagt. Een niet-{@code ASSEMBLING} bundel of een lege bundel geeft enkel die ene blokkade (freeze
     * stopt daar ook); daarna worden alle overige blokkades samen gemeld, terwijl {@code freeze} enkel de
     * eerste werpt.
     *
     * @throws NotFoundException {@link #CODE_BUNDLE_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public FreezePreflight checkFreeze(long bundleId) {
        PublicationBundle bundle = bundles.findById(bundleId).orElseThrow(
                () -> new NotFoundException(CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
        long activeBatches = bundleBatches.countByBundleIdAndActiveMarkerIsNotNull(bundleId);
        long planned = dao.countPlanned(bundleId);
        long undecided = dao.countUndecided(bundleId);
        long stale = dao.countStaleMutations(bundleId);
        List<String> blockers = new ArrayList<>();
        List<String> inBundleExamples = List.of();
        List<String> crossBundleExamples = List.of();
        if (bundle.getStatus() != PublicationBundleStatus.ASSEMBLING) {
            blockers.add(CODE_BUNDLE_NOT_ASSEMBLING);
        } else if (activeBatches == 0) {
            blockers.add(CODE_BUNDLE_EMPTY);
        } else {
            if (undecided > 0) {
                blockers.add(CODE_BUNDLE_HAS_UNDECIDED_MUTATIONS);
            }
            if (stale > 0) {
                blockers.add(CODE_SOURCE_STATE_CHANGED);
            }
            inBundleExamples = dao.findInBundleOfferConflicts(bundleId, CONFLICT_SAMPLE_LIMIT).stream()
                    .map(BundleFreezeService::describeInBundle).toList();
            crossBundleExamples = dao.findCrossBundleOfferConflicts(bundleId, CONFLICT_SAMPLE_LIMIT).stream()
                    .map(BundleFreezeService::describeCrossBundle).toList();
            if (!inBundleExamples.isEmpty()) {
                blockers.add(CODE_BUNDLE_OFFER_CONFLICT);
            }
            if (!crossBundleExamples.isEmpty()) {
                blockers.add(CODE_OFFER_ALREADY_IN_ANOTHER_BUNDLE);
            }
        }
        return new FreezePreflight(blockers.isEmpty(), List.copyOf(blockers), activeBatches, planned, undecided,
                stale, inBundleExamples, crossBundleExamples);
    }

    private static String describeInBundle(InBundleOfferConflict conflict) {
        return describe(conflict.offer()) + " in " + conflict.batchCount() + " batches (e.g. "
                + conflict.firstBatchId() + " and " + conflict.lastBatchId() + ")";
    }

    private static String describeCrossBundle(CrossBundleOfferConflict conflict) {
        return describe(conflict.offer()) + " (batch " + conflict.batchId() + " here, batch "
                + conflict.otherBatchId() + " in bundle " + conflict.otherBundleId() + ")";
    }

    /** Het resultaat van de bulkgoedkeuring; {@code decisionId} is null zodra er niets te doen viel. */
    private record AutoApproval(Long decisionId, long affectedCount) {
    }

    /**
     * Bewust een expliciete vergelijking met {@code ASSEMBLING} en niet
     * {@code PublicationBundleStatus.acceptsChanges()}: dat laatste beantwoordt de vraag "mag hier nog
     * iets bijkomen of af", terwijl dit een statusovergang is die precies vanuit één status vertrekt.
     * Vandaag vallen beide samen; ze mogen niet stilzwijgend aan elkaar vastzitten.
     */
    private static void requireAssembling(PublicationBundle bundle) {
        if (bundle.getStatus() != PublicationBundleStatus.ASSEMBLING) {
            throw new ConflictException(CODE_BUNDLE_NOT_ASSEMBLING, "Bundle " + bundle.getId() + " is "
                    + bundle.getStatus() + "; only an ASSEMBLING bundle can be frozen");
        }
    }

    /**
     * R-FRZ-02. {@code PLANNED} telt hier bewust niet mee: die wordt hieronder in bulk goedgekeurd.
     * {@code BLOCKED}-mutaties en identiteitsincidenten evenmin — die krijgen in Fase 4 geen beslispad,
     * blijven zichtbaar staan en beletten het bevriezen niet (ontwerp par. 3.6, beslissingslog 22/09
     * keuze 3). Ze worden door Fase 5 niet gepubliceerd.
     */
    private void requireEveryMutationDecided(long bundleId) {
        long undecided = dao.countUndecided(bundleId);
        if (undecided > 0) {
            throw new ConflictException(CODE_BUNDLE_HAS_UNDECIDED_MUTATIONS, "Bundle " + bundleId + " still has "
                    + undecided + " mutation(s) awaiting approval; every AWAITING_APPROVAL mutation must be "
                    + "explicitly approved or rejected before the bundle can be frozen");
        }
    }

    /**
     * R-BND-06: de baselinecontrole die bij het toevoegen enkel informatief was, is hier blokkerend.
     * Een verschoven bronstaat betekent dat een andere batch van dezelfde koppeling intussen als
     * nulmeting aanvaard is (of dat de aanbieding er al staat terwijl deze mutatie ze wil aanmaken).
     */
    private void requireUnchangedSourceState(long bundleId) {
        long stale = dao.countStaleMutations(bundleId);
        if (stale > 0) {
            throw new ConflictException(CODE_SOURCE_STATE_CHANGED, "The source state changed after the batches of "
                    + "bundle " + bundleId + " were screened (" + stale + " mutation(s) affected); screen the "
                    + "deliveries again before freezing this bundle");
        }
    }

    /** R-FRZ-03 en R-FRZ-04; eerst binnen de bundel, dan erbuiten. */
    private void requireNoOfferConflicts(long bundleId) {
        List<InBundleOfferConflict> inBundle = dao.findInBundleOfferConflicts(bundleId, CONFLICT_SAMPLE_LIMIT);
        if (!inBundle.isEmpty()) {
            StringJoiner examples = new StringJoiner(", ");
            for (InBundleOfferConflict conflict : inBundle) {
                examples.add(describeInBundle(conflict));
            }
            throw new ConflictException(CODE_BUNDLE_OFFER_CONFLICT, "Bundle " + bundleId + " contains the same "
                    + "offer in more than one batch, which would make the last import silently win: " + examples
                    + ". Reject one side before freezing");
        }
        List<CrossBundleOfferConflict> crossBundle =
                dao.findCrossBundleOfferConflicts(bundleId, CONFLICT_SAMPLE_LIMIT);
        if (!crossBundle.isEmpty()) {
            StringJoiner examples = new StringJoiner(", ");
            for (CrossBundleOfferConflict conflict : crossBundle) {
                examples.add(describeCrossBundle(conflict));
            }
            throw new ConflictException(CODE_OFFER_ALREADY_IN_ANOTHER_BUNDLE, "Bundle " + bundleId + " contains "
                    + "offers that are also publishable in another open or frozen bundle: " + examples
                    + ". Reject one side, or publish or cancel the other bundle first");
        }
    }

    /** Leesbare aanduiding van één aanbieding voor een foutmelding; de identiteitshash zelf zegt niets. */
    private static String describe(OfferIdentity offer) {
        return "link " + offer.importLinkId() + " offer " + offer.supplier() + "/" + offer.supplierGroup() + "/"
                + offer.supplierReference();
    }

    /**
     * De bulkgoedkeuring van de resterende {@code PLANNED}-mutaties (beslissingslog 22/09, keuze 1).
     * Zelfde robuuste volgorde als de groepsactie in 4d: eerst tellen met exact dezelfde
     * {@code where}-clausule, bij 0 helemaal niets schrijven, anders de append-only regel met haar
     * definitieve {@code affected_count} invoegen en pas dan de {@code update} uitvoeren. Klopt het
     * aantal achteraf niet, dan rolt de <b>hele</b> bevriezing terug: een beslissingsregel die een ander
     * aantal claimt dan ze raakte, is geen audit meer.
     */
    private AutoApproval approveRemainingPlanned(PublicationBundle bundle, String freezer, String reason,
                                                 Instant frozenAt) {
        long bundleId = bundle.getId();
        long planned = dao.countPlanned(bundleId);
        if (planned == 0) {
            return new AutoApproval(null, 0L);
        }
        PublicationDecision decision = new PublicationDecision(bundle, null,
                BundleDecisionKind.AUTO_APPROVE_PLANNED, BundleDecisionScope.BUNDLE, planned, freezer, frozenAt,
                reason);
        decision.setPreviousStatus(MutationStatus.PLANNED.name());
        decision.setNewStatus(MutationStatus.READY_FOR_PUBLICATION.name());
        decision.setSelectionFilter("status=" + MutationStatus.PLANNED.name());
        PublicationDecision saved = decisions.saveAndFlush(decision);

        int affected = dao.approvePlanned(bundleId, saved.getId(), freezer, frozenAt);
        if (affected != planned) {
            throw new ConflictException(CODE_FREEZE_CONTENT_CHANGED, "The content of bundle " + bundleId
                    + " changed while it was being frozen (" + planned + " planned mutations counted, " + affected
                    + " updated); nothing was changed. Read the bundle again and freeze once more");
        }
        return new AutoApproval(saved.getId(), affected);
    }

    /**
     * De handeling bevriezen zelf als aparte auditregel ({@code decision_kind = FREEZE}, scope
     * {@code BUNDLE}, {@code affected_count = 1}: ze gaat over deze ene bundel, niet over N mutaties —
     * die staan al op de {@code AUTO_APPROVE_PLANNED}-regel en zouden hier dubbel geteld worden).
     */
    private long recordFreezeDecision(PublicationBundle bundle, String freezer, String reason, Instant frozenAt) {
        PublicationDecision decision = new PublicationDecision(bundle, null, BundleDecisionKind.FREEZE,
                BundleDecisionScope.BUNDLE, 1, freezer, frozenAt, reason);
        decision.setPreviousStatus(PublicationBundleStatus.ASSEMBLING.name());
        decision.setNewStatus(PublicationBundleStatus.FROZEN.name());
        return decisions.saveAndFlush(decision).getId();
    }

    /**
     * Stelt de tien tellers van ontwerp par. 2 vast en zet ze op de entiteit. Vanaf het bevriezen
     * leest {@code GET /bundles/{id}} deze waarden in plaats van live te tellen.
     * <p>
     * Zes ervan komen uit de mutaties van de actieve leden ({@link BundleMutationTotals}, exact dezelfde
     * optelling als de live weergave — anders zou het bevroren getal iets anders kunnen betekenen dan
     * wat de gebruiker vlak vóór het bevriezen zag). {@code batchCount} is het aantal actieve
     * lidmaatschappen. De drie laatste komen uit de screeningtellers van de leden-batches en zijn
     * {@code null} zodra ook maar één batch er geen waarde voor draagt — nooit stil 0.
     * <p>
     * Bewust via JPA-setters en één {@code save} van de bundelrij, niet via een JDBC-{@code update}: de
     * bundel is in deze transactie een beheerde entiteit, en een JDBC-schrijfactie zou even later door
     * de flush van {@link PublicationBundle#recordFreeze} overschreven worden met de (nog lege)
     * waarden uit het geheugen.
     */
    private void applyCounts(PublicationBundle bundle, long activeBatches) {
        BundleMutationTotals totals = BundleMutationTotals.of(dao.countByStatus(bundle.getId()));
        BundleBatchTotals batchTotals = dao.computeBatchTotals(bundle.getId());
        bundle.setBatchCount(activeBatches);
        bundle.setContentMutationCount(totals.contentMutationCount());
        bundle.setReadyCount(totals.readyCount());
        bundle.setRejectedCount(totals.rejectedCount());
        bundle.setBlockedCount(totals.blockedCount());
        bundle.setExpiredCount(totals.expiredCount());
        bundle.setIdentityIncidentCount(totals.identityIncidentCount());
        bundle.setBulkIncidentCount(batchTotals.bulkIncidentCount());
        bundle.setCriticalIssueCount(batchTotals.criticalIssueCount());
        bundle.setWarningCount(batchTotals.warningCount());
    }
}
