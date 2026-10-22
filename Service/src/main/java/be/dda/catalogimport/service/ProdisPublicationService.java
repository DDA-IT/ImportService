package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.CommitOutcomeUnknownException;
import be.dda.catalogimport.dao.ImportMutationRepository;
import be.dda.catalogimport.dao.ProdisPublicationWriter;
import be.dda.catalogimport.dao.ProdisPublicationWriter.Decision;
import be.dda.catalogimport.dao.ProdisPublicationWriter.DetailColumn;
import be.dda.catalogimport.dao.ProdisPublicationWriter.Outcome;
import be.dda.catalogimport.dao.ProdisPublicationWriter.WriteSet;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleLookup;
import be.dda.catalogimport.dao.ProdisTransactionNotAppliedException;
import be.dda.catalogimport.dao.ProdisUpdateCountException;
import be.dda.catalogimport.dao.PublicationRunItemRepository;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationRunItem;
import be.dda.catalogimport.domain.PublicationRunItemStatus;
import be.dda.catalogimport.service.ProdisPublicationPlanner.ArticleState;
import be.dda.catalogimport.service.ProdisPublicationPlanner.Change;
import be.dda.catalogimport.service.ProdisPublicationPlanner.Plan;
import be.dda.catalogimport.service.ProdisPublicationPlanner.PlanOutcome;
import be.dda.catalogimport.service.ProdisPublicationProjection.Item;
import be.dda.catalogimport.service.ProdisPublicationProjection.Notice;
import be.dda.catalogimport.service.ProdisPublicationProjection.Severity;
import be.dda.catalogimport.service.ProdisReferenceService.LegacyMasterMode;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * De levenscyclus van één publicatie-item naar de Prodis-database ({@code docs/design/publicatie-prodis-db-direct-design.md}
 * par. 2.4-2.6 en 4, slice P5b; beslissingen 2026-10-22 P-V1..P-V5, Q1 = b, Q2, P2b, P5a). Geen 2PC: drie losse
 * transacties, herstartbaar. De orchestratie (items aanmaken, chunks, lease, run afronden) is
 * {@link ProdisPublicationRunService} (P5c).
 *
 * <h2>Volgorde ({@link #processItem})</h2>
 * <ol>
 *   <li><b>CI-transactie 1</b> (slot op het item): een al beantwoord of {@code NOT_SENT} item wordt ongewijzigd
 *       teruggegeven (idempotent, Prodis wordt niet gecontacteerd). Anders: {@code request_hash} = SHA-256 van
 *       {@code request_json} en de canonieke hash van het verzoek kloppen ({@value #CODE_ITEM_REQUEST_INVALID});
 *       de mutatie is {@code READY_FOR_PUBLICATION} of {@code IN_PROGRESS} ({@value #CODE_MUTATION_NOT_PUBLISHABLE}).
 *       Wat zonder Prodis vaststaat ({@link ProdisPublicationPlanner#planWithoutProdis}) wordt meteen beslist: CREATE →
 *       {@code NOT_SENT}, een door de projectie geweigerd item of een te lange omschrijving → {@code REJECTED}. Anders
 *       {@code PENDING | SENT | RESULT_UNKNOWN → SENT} ({@code send_count + 1}, {@code sent_at}, {@code sent_by(_subject)},
 *       {@code legacy_master}) en de mutatie {@code READY_FOR_PUBLICATION → IN_PROGRESS}; commit.</li>
 *   <li><b>Prodis-transactie</b> ({@link ProdisPublicationWriter}, buiten elke CI-transactie): identiteit, sloten, de planner
 *       opnieuw op de vergrendelde prior (de lees-lookup van de parallelrun is niet bindend), en enkel bij
 *       {@code WOULD_UPDATE} de UPDATE's.</li>
 *   <li><b>CI-transactie 2</b>: het antwoord {@code APPLIED | UNCHANGED | REJECTED | CONFLICT} met {@code prior_json},
 *       {@code result_json}, {@code warnings_json}; de mutatie {@code IN_PROGRESS → PUBLISHED} (APPLIED/UNCHANGED) of
 *       terug naar {@code READY_FOR_PUBLICATION} (REJECTED/CONFLICT). Bij APPLIED/UNCHANGED legt
 *       {@link PublishedSourceStateRecorder} in dezelfde transactie de bronwaarden vast als bronstaat
 *       ({@code state_origin = PUBLISHED}, beslissing Q3, P5c); kan de bronstaat niet veilig bijgewerkt worden (bv. ze
 *       wijzigde intussen), dan komt er een melding {@code SOURCE_STATE_NOT_UPDATED} en blijft het Prodis-antwoord wat
 *       het is.</li>
 * </ol>
 *
 * <h2>Foutklassen (design par. 2.4, beslissing P5a punt 1)</h2>
 * <ul>
 *   <li>{@link ProdisTransactionNotAppliedException} (fout vóór de commit: 55P03, 40P01, 40001, 57014, 08 vóór de commit,
 *       42501, ...; of een commit die de server uitdrukkelijk terugdraaide, klasse 23/40) → item terug {@code PENDING}
 *       met {@code last_error_code} = {@value #ERROR_NOT_APPLIED_PREFIX}{@code <SQLState>}. Mutatie blijft
 *       {@code IN_PROGRESS}.</li>
 *   <li>{@link CommitOutcomeUnknownException} → {@code RESULT_UNKNOWN} ({@value #ERROR_COMMIT_UNKNOWN_PREFIX}...).</li>
 *   <li>CI-transactie 2 mislukt na een Prodis-commit (bv. CI-database weg) → best effort {@code RESULT_UNKNOWN}
 *       ({@value #ERROR_ANSWER_NOT_RECORDED}); lukt ook dat niet, dan blijft het item {@code SENT}. Beide worden bij een
 *       volgende poging met dezelfde sleutel en dezelfde bytes verstuurd: reconcile-by-state vindt de target en schrijft
 *       niets dubbel ({@code UNCHANGED}).</li>
 *   <li>Een onverwachte fout (programmeerfout, {@link ProdisUpdateCountException}) → zeker niets toegepast (de writer
 *       draaide terug): {@code PENDING} met een code, en de fout gaat naar boven.</li>
 * </ul>
 *
 * <h2>Gelijktijdigheid</h2>
 * {@code send_count} is de fencing-token: CI-transactie 2 schrijft enkel als het item nog {@code SENT} is met de
 * {@code send_count} van deze poging; anders nam een latere poging het over en wordt niets overschreven
 * ({@link ItemResult#superseded()}). Twee gelijktijdige pogingen schrijven in Prodis hoogstens één keer (rijsloten +
 * reconcile-by-state). Het voorkomen van gelijktijdige pogingen zelf (lease) is P5c.
 *
 * <h2>Financieel</h2>
 * Er wordt exact geschreven wat de planner berekent (de target-{@code double}s en de {@link java.math.BigDecimal}
 * basisprijs), enkel voor de kolommen die echt wijzigen; afwijkingen blijven meldingen in {@code warnings_json}. Niets
 * wordt stil gecorrigeerd of afgekapt.
 */
@Service
public class ProdisPublicationService {

    /** 404: het item bestaat niet. */
    public static final String CODE_ITEM_NOT_FOUND = "PUBLICATION_RUN_ITEM_NOT_FOUND";
    /** 409: {@code request_json} is onleesbaar of past niet bij {@code request_hash}/zijn canonieke hash. */
    public static final String CODE_ITEM_REQUEST_INVALID = "PUBLICATION_ITEM_REQUEST_INVALID";
    /** 409: de mutatie is niet (meer) {@code READY_FOR_PUBLICATION} of {@code IN_PROGRESS}. */
    public static final String CODE_MUTATION_NOT_PUBLISHABLE = "MUTATION_NOT_PUBLISHABLE";
    /** 409: het item staat in een toestand waarin deze stap niet kan (bv. een al verstuurd CREATE-item). */
    public static final String CODE_ITEM_STATE_INVALID = "PUBLICATION_ITEM_STATE_INVALID";

    /** {@code last_error_code} na een zeker niet-toegepaste Prodis-transactie, gevolgd door de SQLState. */
    public static final String ERROR_NOT_APPLIED_PREFIX = "PRODIS_NOT_APPLIED:";
    /** {@code last_error_code} na een mislukte commit met onbekende uitkomst, gevolgd door de SQLState. */
    public static final String ERROR_COMMIT_UNKNOWN_PREFIX = "PRODIS_COMMIT_UNKNOWN:";
    /** Prodis-transactie klaar, maar het antwoord kon niet in CatalogImport vastgelegd worden. */
    public static final String ERROR_ANSWER_NOT_RECORDED = "CI_ANSWER_NOT_RECORDED";
    /** Een onverwachte fout tijdens de Prodis-transactie (teruggedraaid). */
    public static final String ERROR_UNEXPECTED = "PRODIS_WRITE_UNEXPECTED_ERROR";

    /** {@code publication_run_item.sent_by}: varchar(100). */
    static final int MAX_SENT_BY_LENGTH = 100;
    /** {@code publication_run_item.reason_detail}: varchar(1000). */
    static final int MAX_REASON_DETAIL_LENGTH = 1000;

    private static final Logger LOG = LoggerFactory.getLogger(ProdisPublicationService.class);
    private static final JsonFactory JSON = new JsonFactory();

    /**
     * Wat één verwerking opleverde.
     *
     * @param status         de itemstatus na deze aanroep
     * @param prodisWritten  {@code true} als deze aanroep in Prodis schreef en vastlegde
     * @param prodisContacted {@code true} als deze aanroep een Prodis-transactie opende
     * @param superseded     {@code true} als een latere poging het item intussen overnam (niets overschreven)
     * @param mutationStatus de status van de mutatie na deze aanroep ({@code null} als niet gelezen)
     */
    public record ItemResult(long itemId, PublicationRunItemStatus status, String reasonCode, String lastErrorCode,
                             int sendCount, boolean prodisContacted, boolean prodisWritten, boolean superseded,
                             MutationStatus mutationStatus) {
    }

    private record Prepared(long itemId, long mutationId, Item request, int sendCount,
                            PublicationRunItemStatus previousStatus, LegacyMasterMode legacyMaster, String sentBy,
                            ItemResult finished) {
    }

    private final PublicationRunItemRepository items;
    private final ImportMutationRepository mutations;
    private final ProdisPublicationWriter writer;
    private final ProdisReferenceService reference;
    private final PublishedSourceStateRecorder sourceState;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ProdisPublicationService(PublicationRunItemRepository items, ImportMutationRepository mutations,
                                    ProdisPublicationWriter writer, ProdisReferenceService reference,
                                    PublishedSourceStateRecorder sourceState,
                                    PlatformTransactionManager transactionManager, Clock clock) {
        this.items = items;
        this.mutations = mutations;
        this.writer = writer;
        this.reference = reference;
        this.sourceState = sourceState;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Verwerkt één item met de modus van Prodis zoals de leesbron ze nu leest (onleesbaar of niet geconfigureerd →
     * {@code UNKNOWN}, nooit een weigering; design par. 5). P5c geeft per chunk de modus mee via
     * {@link #processItem(long, ActorIdentity, LegacyMasterMode)}.
     */
    public ItemResult processItem(long itemId, ActorIdentity actor) {
        LegacyMasterMode mode = reference.isConfigured() ? reference.legacyMaster().mode() : LegacyMasterMode.UNKNOWN;
        return processItem(itemId, actor, mode);
    }

    /**
     * Verwerkt één item (zie de klassebeschrijving).
     *
     * @param actor        de verzender: {@code sent_by} = gebruikersnaam (verplicht, nooit {@code system}),
     *                     {@code sent_by_subject} = subject; in Prodis {@code last_modified_by} = subject, zonder subject de
     *                     gebruikersnaam
     * @param legacyMaster de modus van Prodis ({@code null} = UNKNOWN); er wordt niets op geweigerd (P-V1)
     * @throws ProdisPublicationWriteNotConfiguredException 409, er wordt niets gewijzigd
     * @throws NotFoundException                            {@value #CODE_ITEM_NOT_FOUND}
     * @throws ConflictException                            {@value #CODE_ITEM_REQUEST_INVALID},
     *                                                      {@value #CODE_MUTATION_NOT_PUBLISHABLE},
     *                                                      {@value #CODE_ITEM_STATE_INVALID}
     */
    public ItemResult processItem(long itemId, ActorIdentity actor, LegacyMasterMode legacyMaster) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing sentBy");
        }
        String sentBy = ActorNames.requireActorName(actor.username(), "sentBy", MAX_SENT_BY_LENGTH);
        String sentBySubject = actor.subject();
        String modifiedBy = sentBySubject != null ? sentBySubject : sentBy;
        LegacyMasterMode mode = legacyMaster == null ? LegacyMasterMode.UNKNOWN : legacyMaster;
        if (!writer.isConfigured()) {
            throw new ProdisPublicationWriteNotConfiguredException();
        }

        // 1. CI-transactie 1
        Prepared prepared = transaction.execute(status -> prepare(itemId, sentBy, sentBySubject, mode));
        if (prepared.finished() != null) {
            return prepared.finished();
        }

        // 2. Prodis-transactie
        Outcome<Plan> outcome;
        try {
            outcome = writer.write(prepared.request().identity().toLookupIdentity(), modifiedBy,
                    locked -> decide(prepared.request(), locked, mode));
        } catch (CommitOutcomeUnknownException unknown) {
            LOG.warn("Publication item {}: Prodis commit outcome unknown (SQLState {}); RESULT_UNKNOWN", itemId,
                    unknown.getSqlState());
            return finishWithoutAnswer(prepared, ERROR_COMMIT_UNKNOWN_PREFIX + state(unknown.getSqlState()), true);
        } catch (ProdisTransactionNotAppliedException notApplied) {
            LOG.info("Publication item {}: Prodis transaction not applied (SQLState {}); back to PENDING", itemId,
                    notApplied.getSqlState());
            return finishWithoutAnswer(prepared, ERROR_NOT_APPLIED_PREFIX + state(notApplied.getSqlState()), false);
        } catch (RuntimeException unexpected) {
            // De writer draaide terug: zeker niets toegepast. Zichtbaar maken, niet inslikken.
            String code = unexpected instanceof ProdisUpdateCountException ? ProdisUpdateCountException.CODE
                    : ERROR_UNEXPECTED;
            LOG.error("Publication item {}: unexpected failure in the Prodis transaction (rolled back)", itemId,
                    unexpected);
            try {
                finishWithoutAnswer(prepared, code, false);
            } catch (RuntimeException recordFailure) {
                unexpected.addSuppressed(recordFailure);
            }
            throw unexpected;
        }

        // 3. CI-transactie 2
        try {
            return transaction.execute(status -> recordAnswer(prepared, outcome));
        } catch (RuntimeException failure) {
            LOG.error("Publication item {}: the Prodis transaction finished (written: {}) but the answer could not be "
                    + "recorded; marking it RESULT_UNKNOWN", itemId, outcome.written(), failure);
            try {
                finishWithoutAnswer(prepared, ERROR_ANSWER_NOT_RECORDED, true);
            } catch (RuntimeException markFailure) {
                // Blijft SENT: een volgende poging verstuurt dezelfde bytes (reconcile-by-state).
                failure.addSuppressed(markFailure);
            }
            throw failure;
        }
    }

    // --- CI-transactie 1 -----------------------------------------------------------------------------------------

    private Prepared prepare(long itemId, String sentBy, String sentBySubject, LegacyMasterMode mode) {
        PublicationRunItem item = items.findByIdForUpdate(itemId).orElseThrow(
                () -> new NotFoundException(CODE_ITEM_NOT_FOUND, "Publication run item " + itemId + " not found"));
        long mutationId = item.getMutation().getId();
        PublicationRunItemStatus previous = item.getStatus();
        if (previous.isAnswered() || previous == PublicationRunItemStatus.NOT_SENT) {
            // Idempotent: een beantwoord item wordt nooit opnieuw verstuurd (een nieuwe poging is een nieuw item, P5c).
            return finished(item, mutationId, false, false, false);
        }
        Item request = request(item, mutationId);
        MutationStatus mutationStatus = mutations.findStatusById(mutationId).orElseThrow();
        if (mutationStatus != MutationStatus.READY_FOR_PUBLICATION && mutationStatus != MutationStatus.IN_PROGRESS) {
            throw new ConflictException(CODE_MUTATION_NOT_PUBLISHABLE, "Mutation " + mutationId + " is " + mutationStatus
                    + "; only READY_FOR_PUBLICATION or IN_PROGRESS can be published");
        }

        Plan pure = ProdisPublicationPlanner.planWithoutProdis(request, mode);
        if (pure != null && pure.outcome() == PlanOutcome.NOT_SENT) {
            if (previous != PublicationRunItemStatus.PENDING || item.getSendCount() != 0) {
                throw new ConflictException(CODE_ITEM_STATE_INVALID, "Item " + itemId + " (" + previous + ", sent "
                        + item.getSendCount() + " times) cannot become NOT_SENT");
            }
            item.markNotSent(pure.reasonCode(), reasonDetail(pure), warningsJson(pure.notices()), mode.name());
            items.saveAndFlush(item);
            return finished(item, mutationId, false, false, false);
        }
        if (pure != null) {
            // REJECTED zonder Prodis (projectie, te lange omschrijving): in één CI-transactie verstuurd en beantwoord.
            item.markSent(clock.instant(), sentBy, sentBySubject, mode.name());
            item.recordAnswer(PublicationRunItemStatus.REJECTED, clock.instant(), pure.reasonCode(), reasonDetail(pure),
                    null, resultJson(pure, previous, item.getSendCount(), false, null, null), warningsJson(pure.notices()));
            items.saveAndFlush(item);
            mutations.transitionStatus(mutationId, MutationStatus.IN_PROGRESS, MutationStatus.READY_FOR_PUBLICATION);
            LOG.info("Publication item {} (mutation {}): REJECTED {} without contacting Prodis", itemId, mutationId,
                    pure.reasonCode());
            return finished(item, mutationId, false, false, false);
        }

        if (mutationStatus == MutationStatus.READY_FOR_PUBLICATION
                && mutations.transitionStatus(mutationId, MutationStatus.READY_FOR_PUBLICATION,
                MutationStatus.IN_PROGRESS) != 1) {
            throw new ConflictException(CODE_MUTATION_NOT_PUBLISHABLE, "Mutation " + mutationId
                    + " changed status while it was being sent; nothing was sent");
        }
        item.markSent(clock.instant(), sentBy, sentBySubject, mode.name());
        items.saveAndFlush(item);
        return new Prepared(itemId, mutationId, request, item.getSendCount(), previous, mode, sentBy, null);
    }

    private Item request(PublicationRunItem item, long mutationId) {
        String json = item.getRequestJson();
        if (!ProdisPublicationRequestJson.sha256Hex(json).equals(item.getRequestHash())) {
            throw new ConflictException(CODE_ITEM_REQUEST_INVALID, "Item " + item.getId()
                    + ": request_json does not match request_hash; nothing was sent");
        }
        Item request;
        try {
            request = ProdisPublicationRequestJson.read(json);
        } catch (ProdisPublicationRequestJson.InvalidRequestException invalid) {
            throw new ConflictException(CODE_ITEM_REQUEST_INVALID, "Item " + item.getId() + ": " + invalid.getMessage()
                    + "; nothing was sent");
        }
        if (request.mutationId() != mutationId) {
            throw new ConflictException(CODE_ITEM_REQUEST_INVALID, "Item " + item.getId() + ": the request belongs to "
                    + "mutation " + request.mutationId() + ", not " + mutationId + "; nothing was sent");
        }
        return request;
    }

    // --- Prodis-transactie (binnen de writer) -------------------------------------------------------------------

    /** De planner op de vergrendelde toestand; enkel {@code WOULD_UPDATE} schrijft, exact de target-waarden. */
    static Decision<Plan> decide(Item request, CatalogArticleLookup locked, LegacyMasterMode mode) {
        Plan plan = ProdisPublicationPlanner.plan(request, locked, mode);
        if (plan.outcome() != PlanOutcome.WOULD_UPDATE) {
            return new Decision<>(plan, null);
        }
        return new Decision<>(plan, writeSet(plan));
    }

    static WriteSet writeSet(Plan plan) {
        ArticleState target = plan.target();
        WriteSet write = new WriteSet();
        for (Change change : plan.changes()) {
            switch (change.column()) {
                case BASE_PRICE -> write.basePrice(target.basePrice());
                case BRUT_PRICE -> write.brutPrice(target.brutPrice());
                case BRUT_PRICE_PERCENTAGE -> write.brutPricePercentage(target.brutPricePercentage());
                case DETAIL_PERCENTAGE -> write.detail(Integer.toString(change.detailCode()), DetailColumn.PERCENTAGE,
                        target.percentages().get(change.detailCode()));
                case DETAIL_PRICE -> write.detail(Integer.toString(change.detailCode()), DetailColumn.PRICE,
                        target.prices().get(change.detailCode()));
                case DESCRIPTION -> write.description(target.description());
                default -> throw new IllegalStateException("Unknown column " + change.column());
            }
        }
        if (plan.salesPriceLastUpdated()) {
            write.touchSalesPriceLastUpdated();
        }
        return write;
    }

    // --- CI-transactie 2 -----------------------------------------------------------------------------------------

    private ItemResult recordAnswer(Prepared prepared, Outcome<Plan> outcome) {
        Plan plan = outcome.plan();
        PublicationRunItemStatus answer = switch (plan.outcome()) {
            case WOULD_UPDATE -> {
                if (!outcome.written()) {
                    throw new IllegalStateException("WOULD_UPDATE without a write for item " + prepared.itemId());
                }
                yield PublicationRunItemStatus.APPLIED;
            }
            case UNCHANGED -> PublicationRunItemStatus.UNCHANGED;
            case REJECTED -> PublicationRunItemStatus.REJECTED;
            case CONFLICT -> PublicationRunItemStatus.CONFLICT;
            case NOT_SENT -> throw new IllegalStateException("NOT_SENT after contacting Prodis for item "
                    + prepared.itemId());
        };
        PublicationRunItem item = items.findByIdForUpdate(prepared.itemId()).orElseThrow();
        if (superseded(item, prepared)) {
            LOG.warn("Publication item {}: answer {} of send {} not recorded, a later attempt took over (now {}, send {})",
                    prepared.itemId(), answer, prepared.sendCount(), item.getStatus(), item.getSendCount());
            return result(item, prepared.mutationId(), true, outcome.written(), true);
        }
        Instant answeredAt = clock.instant();
        // Q3 (beslissing 2026-10-22): bij APPLIED/UNCHANGED de bronwaarden als bronstaat vastleggen, in DEZE transactie.
        PublishedSourceStateRecorder.Result source = null;
        List<Notice> notices = plan.notices();
        if (answer == PublicationRunItemStatus.APPLIED || answer == PublicationRunItemStatus.UNCHANGED) {
            source = sourceState.recordPublished(prepared.mutationId(), prepared.sentBy(), answeredAt);
            if (!source.outcome().current()) {
                notices = new ArrayList<>(notices);
                notices.add(new Notice(PublishedSourceStateRecorder.NOTICE_SOURCE_STATE_NOT_UPDATED, Severity.WARNING,
                        null, source.outcome().name() + ": " + source.message()));
            }
        }
        item.recordAnswer(answer, answeredAt, plan.reasonCode(), reasonDetail(plan), priorJson(plan.prior()),
                resultJson(plan, prepared.previousStatus(), prepared.sendCount(), outcome.written(), outcome.writtenAt(),
                        source),
                warningsJson(notices));
        items.saveAndFlush(item);
        MutationStatus next = answer == PublicationRunItemStatus.APPLIED || answer == PublicationRunItemStatus.UNCHANGED
                ? MutationStatus.PUBLISHED : MutationStatus.READY_FOR_PUBLICATION;
        if (mutations.transitionStatus(prepared.mutationId(), MutationStatus.IN_PROGRESS, next) != 1) {
            // Nooit de Prodis-uitkomst verbergen: het item draagt het antwoord, de afwijkende mutatiestatus wordt gemeld.
            LOG.warn("Publication item {}: mutation {} was not IN_PROGRESS; its status was left unchanged (answer {})",
                    prepared.itemId(), prepared.mutationId(), answer);
        }
        LOG.info("Publication item {} (mutation {}): {}{} (send {}, written {})", prepared.itemId(),
                prepared.mutationId(), answer, plan.reasonCode() == null ? "" : " " + plan.reasonCode(),
                prepared.sendCount(), outcome.written());
        return result(item, prepared.mutationId(), true, outcome.written(), false);
    }

    /** PENDING (zeker niet toegepast) of RESULT_UNKNOWN, in een eigen CI-transactie. */
    private ItemResult finishWithoutAnswer(Prepared prepared, String errorCode, boolean unknown) {
        String code = errorCode.length() > 60 ? errorCode.substring(0, 60) : errorCode;
        return transaction.execute(status -> {
            PublicationRunItem item = items.findByIdForUpdate(prepared.itemId()).orElseThrow();
            if (superseded(item, prepared)) {
                return result(item, prepared.mutationId(), true, false, true);
            }
            if (unknown) {
                item.recordResultUnknown(code);
            } else {
                item.recordNotApplied(code);
            }
            items.saveAndFlush(item);
            return result(item, prepared.mutationId(), true, false, false);
        });
    }

    /** De SQLState voor {@code last_error_code}; {@code NONE} als de driver er geen gaf (nooit de tekst "null"). */
    private static String state(String sqlState) {
        return sqlState == null ? "NONE" : sqlState;
    }

    private static boolean superseded(PublicationRunItem item, Prepared prepared) {
        return item.getStatus() != PublicationRunItemStatus.SENT || item.getSendCount() != prepared.sendCount();
    }

    private Prepared finished(PublicationRunItem item, long mutationId, boolean contacted, boolean written,
                              boolean superseded) {
        return new Prepared(item.getId(), mutationId, null, item.getSendCount(), item.getStatus(), null, null,
                result(item, mutationId, contacted, written, superseded));
    }

    private ItemResult result(PublicationRunItem item, long mutationId, boolean contacted, boolean written,
                              boolean superseded) {
        return new ItemResult(item.getId(), item.getStatus(), item.getReasonCode(), item.getLastErrorCode(),
                item.getSendCount(), contacted, written, superseded, mutations.findStatusById(mutationId).orElse(null));
    }

    // --- JSON ----------------------------------------------------------------------------------------------------

    /** De meldingtekst van de reden; langer dan {@value #MAX_REASON_DETAIL_LENGTH} → zichtbaar ingekort, volledig in warnings_json. */
    static String reasonDetail(Plan plan) {
        if (plan.reasonCode() == null) {
            return null;
        }
        String message = null;
        for (Notice notice : plan.notices()) {
            if (notice.blocking() && plan.reasonCode().equals(notice.code())) {
                message = notice.message();
            }
        }
        if (message == null || message.length() <= MAX_REASON_DETAIL_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_REASON_DETAIL_LENGTH - 3) + "...";
    }

    static String priorJson(ArticleState prior) {
        if (prior == null) {
            return null;
        }
        return json(json -> {
            json.writeStartObject();
            json.writeBooleanField("readUnderLock", true);
            PublicationParallelRunReport.writeState(json, "prior", prior);
            json.writeEndObject();
        });
    }

    /**
     * @param sourceState de bronstaat na een APPLIED/UNCHANGED (Q3), anders {@code null}: dan staat {@code sourceState} er
     *                    als {@code null} (niet van toepassing)
     */
    static String resultJson(Plan plan, PublicationRunItemStatus previousStatus, int sendCount, boolean written,
                             LocalDateTime writtenAt, PublishedSourceStateRecorder.Result sourceState) {
        return json(json -> {
            json.writeStartObject();
            json.writeStringField("planOutcome", plan.outcome().name());
            json.writeStringField("reasonCode", plan.reasonCode());
            json.writeStringField("legacyMaster", plan.legacyMaster().name());
            json.writeBooleanField("written", written);
            json.writeStringField("writtenAtUtc", writtenAt == null ? null : writtenAt.toString());
            json.writeBooleanField("salesPriceLastUpdated", written && plan.salesPriceLastUpdated());
            json.writeNumberField("sendCount", sendCount);
            json.writeStringField("sentFromStatus", previousStatus == null ? null : previousStatus.name());
            json.writeStringField("requestHash", plan.requestHash());
            PublicationParallelRunReport.writeChanges(json, "changes", written ? plan.changes() : List.of());
            PublicationParallelRunReport.writeChanges(json, "derivedDrift", plan.derivedDrift());
            PublicationParallelRunReport.writeState(json, "target", plan.target());
            json.writeStringField("sourceState", sourceState == null ? null : sourceState.outcome().name());
            json.writeEndObject();
        });
    }

    static String warningsJson(List<Notice> notices) {
        return json(json -> PublicationParallelRunReport.writeNotices(json, notices));
    }

    @FunctionalInterface
    private interface JsonWork {
        void write(JsonGenerator json) throws IOException;
    }

    private static String json(JsonWork work) {
        StringWriter out = new StringWriter(512);
        try (JsonGenerator json = JSON.createGenerator(out)) {
            work.write(json);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return Objects.requireNonNull(out.toString());
    }
}
