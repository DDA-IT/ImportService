package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.PublicationBundleBatchRepository;
import be.dda.catalogimport.dao.PublicationBundleDao;
import be.dda.catalogimport.dao.PublicationBundleRepository;
import be.dda.catalogimport.dao.PublicationDecisionRepository;
import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.BundleDecisionScope;
import be.dda.catalogimport.domain.ImportMutation;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundle;
import be.dda.catalogimport.domain.PublicationDecision;
import be.dda.catalogimport.service.BatchQueryService.MutationRow;
import be.dda.catalogimport.service.BundleQueryService.DecisionRow;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Goedkeuren en afkeuren van <b>één</b> mutatie binnen een Publicatiebundel (ontwerp fase 4 par. 1
 * R-DEC, par. 3 "Goedkeuringsalgoritme (individueel)", par. 4 statusdiagram; bouwstap 4c). De
 * groepsactie (4d), het bevriezen (4e) en het annuleren (4f) volgen apart.
 * <p>
 * Zelfde patroon als {@link PublicationBundleService} en {@code SourceStateBaselineService}: niet
 * {@code @Transactional}, één {@link TransactionTemplate} per aanroep, en
 * {@link PublicationBundleRepository#findByIdForUpdate} als serialisatiepunt — twee beslissingen over
 * dezelfde bundel kunnen elkaar zo nooit kruisen.
 *
 * <h2>Businessgedrag (R-DEC)</h2>
 * <ul>
 *   <li>Enkel in een {@code ASSEMBLING}-bundel (anders 409 {@link #CODE_BUNDLE_NOT_ASSEMBLING}); de
 *       mutatie moet tot een batch met een <b>actief</b> lidmaatschap van déze bundel behoren (anders
 *       404 {@link #CODE_MUTATION_NOT_IN_BUNDLE}).</li>
 *   <li>Goedgekeurd ⇒ {@code READY_FOR_PUBLICATION}, afgekeurd ⇒ {@code REJECTED}; beslisbaar vanuit
 *       {@code PLANNED} en {@code AWAITING_APPROVAL}.</li>
 *   <li>Eén bevoegde persoon volstaat (beslissingslog 20/09/2026: vier-ogen wordt nergens
 *       afgedwongen). {@code decidedBy} is verplicht, niet leeg, hoogstens
 *       {@value #MAX_ACTOR_LENGTH} tekens en nooit {@code system}.</li>
 *   <li><b>Afkeuren vereist altijd een reden</b> (verplicht, hoogstens {@value #MAX_REASON_LENGTH}
 *       tekens). Bij goedkeuren is een reden optioneel; ontbreekt ze, dan draagt de beslissingsregel
 *       {@value #DEFAULT_APPROVAL_REASON} — {@code publication_decision.reason} is
 *       {@code not null}, en een lege tekst zou "geen reden" en "reden vergeten" ononderscheidbaar
 *       maken.</li>
 *   <li><b>Een herziening vereist wél altijd een reden</b>, ook bij goedkeuren: wie een eerder
 *       ondertekende beslissing omkeert, moet verantwoorden waarom (zie
 *       {@link #decide} en het rapport bij bouwstap 4c).</li>
 *   <li>Een herhaling met dezelfde beslisser en dezelfde doelstatus is <b>idempotent</b>: status 200,
 *       geen tweede beslissingsregel. Dezelfde doelstatus door een <b>andere</b> beslisser levert wél
 *       een nieuwe regel op — twee mensen die tekenen is twee vaststellingen, geen ruis.</li>
 *   <li>Een <b>herziening</b> ({@code READY_FOR_PUBLICATION} ↔ {@code REJECTED}) mag enkel individueel
 *       en enkel zolang de bundel {@code ASSEMBLING} is. Ze schrijft een nieuwe, append-only regel met
 *       {@code previous_status} = de oude eindstatus; de vorige regel blijft ongewijzigd staan.</li>
 *   <li>{@code BLOCKED} (vastgehouden identiteitsincident) ⇒ 409
 *       {@link #CODE_MUTATION_BLOCKED_BY_IDENTITY_INCIDENT}; een
 *       {@code IDENTITY_REFERENCE_INCIDENT}-mutatie ⇒ 409
 *       {@link #CODE_IDENTITY_DECISION_NOT_IN_SCOPE} (ontwerp par. 3.6: zo'n beslissing schrijft in
 *       {@code catalog_reference_state}, wat Fase 4 niet mag); de {@code IMPORT_MARKER} en elke
 *       terminale of lopende status ⇒ 409 {@link #CODE_MUTATION_NOT_DECIDABLE}.</li>
 * </ul>
 *
 * <h2>Financiële onveranderlijkheid</h2>
 * Een beslissing raakt <b>uitsluitend</b> {@code status}, {@code decided_by}, {@code decided_at},
 * {@code decided_from_status} en {@code decision_id}. {@code before_base_price},
 * {@code after_base_price}, {@code domain_mask}, {@code status_reason}, {@code identity_hash}, de
 * vingerafdrukken, {@code source_row_number} en {@code delivery_file_id} blijven vóór en na exact
 * gelijk. Dat is geen afspraak maar een eigenschap van de schrijfweg: het gebeurt met
 * {@link PublicationBundleDao#decideMutation}, een {@code update} met precies die vijf kolommen in
 * haar {@code set}-lijst. De JPA-entiteit wordt gelezen voor de controles en daarna <b>niet</b>
 * gewijzigd of bewaard — anders zou Hibernate elke gemapte kolom terugschrijven vanuit een
 * in-memory representatie.
 * <p>
 * {@code status_reason} blijft om dezelfde reden staan: die draagt waaróm de mutatie wachtte
 * ({@code BULK_PRICE_INCIDENT}, {@code INITIAL_LOAD_REQUIRES_APPROVAL}, ...) en dus waarvoor iemand
 * tekende. De reden van de beslissing zelf staat op de beslissingsregel.
 */
@Service
public class BundleDecisionService {

    /** De bundel bestaat niet. */
    public static final String CODE_BUNDLE_NOT_FOUND = PublicationBundleService.CODE_BUNDLE_NOT_FOUND;
    /** De bundel is niet (meer) {@code ASSEMBLING} en aanvaardt dus geen beslissingen. */
    public static final String CODE_BUNDLE_NOT_ASSEMBLING = PublicationBundleService.CODE_BUNDLE_NOT_ASSEMBLING;
    /**
     * De mutatie bestaat niet, of haar batch heeft geen actief lidmaatschap in déze bundel. Bewust
     * dezelfde code voor beide: van buiten de bundel is er geen verschil tussen "bestaat niet" en
     * "hoort hier niet", en een aparte code zou het bestaan van een vreemde mutatie prijsgeven.
     */
    public static final String CODE_MUTATION_NOT_IN_BUNDLE = "MUTATION_NOT_IN_BUNDLE";
    /** Over deze mutatie valt niet te beslissen: een marker, of een terminale/lopende status. */
    public static final String CODE_MUTATION_NOT_DECIDABLE = "MUTATION_NOT_DECIDABLE";
    /** De mutatie is {@code BLOCKED} door een kritiek identiteitsincident (R-REF-09). */
    public static final String CODE_MUTATION_BLOCKED_BY_IDENTITY_INCIDENT =
            "MUTATION_BLOCKED_BY_IDENTITY_INCIDENT";
    /** Een {@code IDENTITY_REFERENCE_INCIDENT} krijgt in Fase 4 geen beslispad (ontwerp par. 3.6). */
    public static final String CODE_IDENTITY_DECISION_NOT_IN_SCOPE = "IDENTITY_DECISION_NOT_IN_SCOPE";

    /** {@code publication_decision.reason} van een goedkeuring waarbij geen reden opgegeven is. */
    public static final String DEFAULT_APPROVAL_REASON = "APPROVED_WITHOUT_REASON";

    /** {@code import_mutation.decided_by} en {@code publication_decision.decided_by}: varchar(100). */
    static final int MAX_ACTOR_LENGTH = 100;
    /** {@code publication_decision.reason}: varchar(500). */
    static final int MAX_REASON_LENGTH = 500;

    private static final Logger LOG = LoggerFactory.getLogger(BundleDecisionService.class);

    /**
     * Het resultaat van een beslissing: de mutatie zoals ze er ná de beslissing bij staat, en de
     * beslissingsregel die haar draagt.
     *
     * @param idempotent {@code true} wanneer deze aanroep een herhaling was van een identieke, al
     *                   genomen beslissing; er is dan géén nieuwe beslissingsregel geschreven en
     *                   {@code decision} is de bestaande regel
     */
    public record MutationDecisionView(MutationRow mutation, DecisionRow decision, boolean idempotent) {
    }

    private final PublicationBundleRepository bundles;
    private final PublicationBundleBatchRepository bundleBatches;
    private final ImportMutationRepository mutations;
    private final PublicationDecisionRepository decisions;
    private final PublicationBundleDao dao;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BundleDecisionService(PublicationBundleRepository bundles,
                                 PublicationBundleBatchRepository bundleBatches,
                                 ImportMutationRepository mutations, PublicationDecisionRepository decisions,
                                 PublicationBundleDao dao, PlatformTransactionManager transactionManager,
                                 Clock clock) {
        this.bundles = bundles;
        this.bundleBatches = bundleBatches;
        this.mutations = mutations;
        this.decisions = decisions;
        this.dao = dao;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Keurt één mutatie goed: {@code PLANNED} of {@code AWAITING_APPROVAL} ⇒
     * {@code READY_FOR_PUBLICATION}.
     *
     * @param reason optioneel; verplicht zodra dit een herziening van een eerdere afkeuring is
     * @throws IllegalArgumentException ongeldige {@code decidedBy} of {@code reason}
     * @throws NotFoundException        {@link #CODE_BUNDLE_NOT_FOUND}, {@link #CODE_MUTATION_NOT_IN_BUNDLE}
     * @throws ConflictException        {@link #CODE_BUNDLE_NOT_ASSEMBLING}, {@link #CODE_MUTATION_NOT_DECIDABLE},
     *                                  {@link #CODE_MUTATION_BLOCKED_BY_IDENTITY_INCIDENT},
     *                                  {@link #CODE_IDENTITY_DECISION_NOT_IN_SCOPE}
     */
    public MutationDecisionView approve(long bundleId, long mutationId, String decidedBy, String reason) {
        return decide(bundleId, mutationId, decidedBy, reason, MutationStatus.READY_FOR_PUBLICATION);
    }

    /**
     * Keurt één mutatie af: {@code PLANNED} of {@code AWAITING_APPROVAL} ⇒ {@code REJECTED}.
     *
     * @param reason <b>verplicht</b> (R-DEC): een afkeuring zonder reden is niet te verantwoorden
     * @throws IllegalArgumentException ontbrekende/ongeldige {@code decidedBy} of {@code reason}
     * @throws NotFoundException        {@link #CODE_BUNDLE_NOT_FOUND}, {@link #CODE_MUTATION_NOT_IN_BUNDLE}
     * @throws ConflictException        zie {@link #approve}
     */
    public MutationDecisionView reject(long bundleId, long mutationId, String decidedBy, String reason) {
        return decide(bundleId, mutationId, decidedBy, reason, MutationStatus.REJECTED);
    }

    /**
     * Het goedkeuringsalgoritme uit ontwerp par. 3, in exact die volgorde: bundel vergrendelen en
     * {@code ASSEMBLING} eisen → mutatie + actief lidmaatschap → marker/identiteitsincident → blokkade
     * → idempotentie → herziening → beslissingsregel invoegen → mutatie bijwerken.
     */
    private MutationDecisionView decide(long bundleId, long mutationId, String decidedBy, String reason,
                                        MutationStatus target) {
        String decider = ActorNames.requireActorName(decidedBy, "decidedBy", MAX_ACTOR_LENGTH);
        String motivation = optionalText(reason);
        if (target == MutationStatus.REJECTED && motivation == null) {
            throw new IllegalArgumentException("Missing reason: rejecting a mutation always requires one");
        }
        return transaction.execute(status -> {
            PublicationBundle bundle = bundles.findByIdForUpdate(bundleId).orElseThrow(
                    () -> new NotFoundException(CODE_BUNDLE_NOT_FOUND, "Bundle " + bundleId + " not found"));
            if (!bundle.getStatus().acceptsChanges()) {
                throw new ConflictException(CODE_BUNDLE_NOT_ASSEMBLING, "Bundle " + bundleId + " is "
                        + bundle.getStatus() + "; mutations can only be decided while the bundle is ASSEMBLING");
            }
            ImportMutation mutation = requireMutationOfBundle(bundleId, mutationId);
            requireDecidableKind(mutation);
            MutationStatus current = mutation.getStatus();
            requireDecidableStatus(mutation, current, target);

            if (current == target && decider.equalsIgnoreCase(mutation.getDecidedBy())) {
                // Exact dezelfde beslissing van dezelfde persoon: niets te doen, en vooral geen tweede
                // regel in het register - dat zou een herhaalde aanroep (retry, dubbelklik) als een
                // tweede vaststelling laten lijken.
                PublicationDecision existing = decisions.findById(mutation.getDecisionId()).orElseThrow(
                        () -> new IllegalStateException("Mutation " + mutationId + " points to decision "
                                + mutation.getDecisionId() + ", which does not exist"));
                return new MutationDecisionView(MutationRow.of(mutation), DecisionRow.of(existing), true);
            }
            boolean revision = current == MutationStatus.READY_FOR_PUBLICATION
                    || current == MutationStatus.REJECTED;
            if (revision && current != target && motivation == null) {
                throw new IllegalArgumentException("Missing reason: revising an earlier decision on mutation "
                        + mutationId + " (" + current + " -> " + target + ") always requires one");
            }

            Instant decidedAt = clock.instant();
            BundleDecisionKind kind = target == MutationStatus.READY_FOR_PUBLICATION
                    ? BundleDecisionKind.APPROVE : BundleDecisionKind.REJECT;
            PublicationDecision decision = new PublicationDecision(bundle, mutation, kind,
                    BundleDecisionScope.MUTATION, 1, decider, decidedAt,
                    motivation == null ? DEFAULT_APPROVAL_REASON : motivation);
            decision.setPreviousStatus(current.name());
            decision.setNewStatus(target.name());
            // Eerst de append-only regel (flush, want de update hieronder verwijst naar haar id), dan
            // pas de mutatie: een mutatie wijst nooit naar een beslissing die nog niet bestaat.
            PublicationDecision saved = decisions.saveAndFlush(decision);

            int updated = dao.decideMutation(mutationId, current.name(), target.name(), decider, decidedAt,
                    saved.getId());
            if (updated != 1) {
                // Kan enkel wanneer de mutatie buiten het bundelslot om verschoof. Liever een conflict
                // dan een beslissing die op een andere status van toepassing blijkt te zijn.
                throw new ConflictException(CODE_MUTATION_NOT_DECIDABLE, "Mutation " + mutationId
                        + " was no longer in status " + current + " when the decision was written; nothing was "
                        + "changed. Read the mutation again and decide once more");
            }
            LOG.info("Mutation {} of bundle {} decided {} by {} ({} -> {}), decision {}", mutationId, bundleId,
                    kind, decider, current, target, saved.getId());
            MutationRow decided = MutationRow.of(mutation)
                    .withDecision(target.name(), decider, decidedAt, current.name(), saved.getId());
            return new MutationDecisionView(decided, DecisionRow.of(saved), false);
        });
    }

    /**
     * De mutatie én het bewijs dat ze bij deze bundel hoort. Dat laatste loopt over
     * {@code publication_bundle_batch} (ontwerp par. 2: lidmaatschap op batchniveau, er is geen
     * {@code publication_bundle_id} op de mutatie) en enkel over een <b>actief</b> lidmaatschap: een
     * verwijderde batch hoort niet meer bij de bundel en haar mutaties dus ook niet.
     */
    private ImportMutation requireMutationOfBundle(long bundleId, long mutationId) {
        ImportMutation mutation = mutations.findById(mutationId).orElseThrow(() -> notInBundle(bundleId, mutationId));
        long batchId = mutation.getBatch().getId();
        bundleBatches.findByBundleIdAndBatchId(bundleId, batchId)
                .filter(membership -> membership.getActiveMarker() != null)
                .orElseThrow(() -> notInBundle(bundleId, mutationId));
        return mutation;
    }

    private static NotFoundException notInBundle(long bundleId, long mutationId) {
        return new NotFoundException(CODE_MUTATION_NOT_IN_BUNDLE, "Mutation " + mutationId
                + " is not part of bundle " + bundleId);
    }

    /**
     * Enkel een inhoudelijke mutatie ({@code CREATE}/{@code UPDATE}) is beslisbaar. De
     * {@code IMPORT_MARKER} is een vaststelling dat er gescreend is, geen voorstel; een
     * {@code IDENTITY_REFERENCE_INCIDENT} vraagt een identiteitsbeslissing die in
     * {@code catalog_reference_state} zou moeten schrijven — precies wat Fase 4 niet mag (ontwerp
     * par. 3.6, R-BND-08).
     */
    private static void requireDecidableKind(ImportMutation mutation) {
        MutationActionType actionType = mutation.getActionType();
        if (actionType == MutationActionType.IDENTITY_REFERENCE_INCIDENT) {
            throw new ConflictException(CODE_IDENTITY_DECISION_NOT_IN_SCOPE, "Mutation " + mutation.getId()
                    + " is an identity reference incident; deciding on a critical reference is out of scope "
                    + "for phase 4 and needs its own migration audit");
        }
        if (actionType != MutationActionType.CREATE && actionType != MutationActionType.UPDATE) {
            throw new ConflictException(CODE_MUTATION_NOT_DECIDABLE, "Mutation " + mutation.getId() + " is a "
                    + actionType + " and carries no proposed change to decide on");
        }
    }

    /**
     * Beslisbaar zijn: {@code PLANNED}, {@code AWAITING_APPROVAL} (eerste beslissing) en de twee
     * eindstatussen {@code READY_FOR_PUBLICATION}/{@code REJECTED} (herhaling of herziening).
     * {@code BLOCKED} krijgt een eigen code, want dat is geen gewone weigering maar een vastgehouden
     * identiteitsincident dat eerst opgelost moet worden.
     */
    private static void requireDecidableStatus(ImportMutation mutation, MutationStatus current,
                                               MutationStatus target) {
        if (current == MutationStatus.BLOCKED) {
            throw new ConflictException(CODE_MUTATION_BLOCKED_BY_IDENTITY_INCIDENT, "Mutation "
                    + mutation.getId() + " is BLOCKED by a critical identity reference incident ("
                    + mutation.getStatusReason() + ") and cannot be approved or rejected in phase 4");
        }
        boolean decidable = current == MutationStatus.PLANNED
                || current == MutationStatus.AWAITING_APPROVAL
                || current == MutationStatus.READY_FOR_PUBLICATION
                || current == MutationStatus.REJECTED;
        if (!decidable) {
            throw new ConflictException(CODE_MUTATION_NOT_DECIDABLE, "Mutation " + mutation.getId()
                    + " is in status " + current + "; only PLANNED, AWAITING_APPROVAL or a revision of an "
                    + "earlier decision can become " + target);
        }
    }

    /** Een lege of ontbrekende reden is "geen reden"; een te lange wordt geweigerd, nooit afgekapt. */
    private static String optionalText(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return ActorNames.requireText(reason, "reason", MAX_REASON_LENGTH);
    }
}
