package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.BundleDecisionKind;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.service.BatchQueryService.MutationRow;
import be.dda.catalogimport.service.BundleCancellationService;
import be.dda.catalogimport.service.BundleDecisionService;
import be.dda.catalogimport.service.BundleDecisionService.DecisionFilter;
import be.dda.catalogimport.service.BundleDecisionService.GroupDecisionView;
import be.dda.catalogimport.service.BundleDecisionService.MutationDecisionView;
import be.dda.catalogimport.service.BundleFreezeService;
import be.dda.catalogimport.service.BundleQueryService;
import be.dda.catalogimport.service.BundleQueryService.BundleBatchRow;
import be.dda.catalogimport.service.BundleQueryService.BundleDetail;
import be.dda.catalogimport.service.BundleQueryService.BundleSummary;
import be.dda.catalogimport.service.BundleQueryService.DecisionRow;
import be.dda.catalogimport.service.PageResult;
import be.dda.catalogimport.service.PublicationBundleService;
import be.dda.catalogimport.service.PublicationBundleService.BundleCandidate;
import be.dda.catalogimport.service.PublicationBundleService.BundleReference;
import be.dda.catalogimport.service.PublicationBundleService.Membership;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bediening en inzage van de Publicatiebundel (Fase 4, volledig afgerond met bouwstap 4f): kandidaten
 * opzoeken, een bundel aanmaken (idempotent op {@code bundleReference}), batches toevoegen/verwijderen,
 * het leesmodel, het individueel goedkeuren/afkeuren van één mutatie met haar beslissingsregister, de
 * groepsactie over een gefilterde selectie, het bevriezen en het annuleren. Publiceren zelf (het
 * daadwerkelijk wegschrijven naar ProDisWebbase/Pervasive) is Fase 5 en bestaat hier niet.
 * <p>
 * <b>Statuscodes.</b> 404 met een stabiele {@code code}: {@code BUNDLE_NOT_FOUND},
 * {@code BATCH_NOT_FOUND}, {@code BATCH_NOT_IN_BUNDLE}, {@code MUTATION_NOT_IN_BUNDLE}. 409 met een
 * stabiele {@code code}: {@code BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE},
 * {@code BUNDLE_NOT_ASSEMBLING} (batches toevoegen/verwijderen, elke individuele of groepsbeslissing, of
 * een tweede bevriezing op een niet-{@code ASSEMBLING} bundel), {@code BATCH_ALREADY_IN_BUNDLE},
 * {@code BATCH_NOT_BUNDLEABLE}, {@code BATCH_VALIDATION_NOT_ESTABLISHED},
 * {@code BATCH_VALIDATION_BLOCKING}, {@code BATCH_HAS_DECIDED_MUTATIONS}, {@code MUTATION_NOT_DECIDABLE},
 * {@code MUTATION_BLOCKED_BY_IDENTITY_INCIDENT}, {@code IDENTITY_DECISION_NOT_IN_SCOPE},
 * {@code BUNDLE_EMPTY}, {@code BUNDLE_HAS_UNDECIDED_MUTATIONS},
 * {@code SOURCE_STATE_CHANGED_SINCE_SCREENING}, {@code BUNDLE_OFFER_CONFLICT},
 * {@code OFFER_ALREADY_IN_ANOTHER_BUNDLE}, {@code BUNDLE_CONTENT_CHANGED_DURING_FREEZE} (freeze, in de
 * praktijk onbereikbaar), {@code BUNDLE_NOT_CANCELLABLE} (cancel op elke status buiten
 * {@code ASSEMBLING}/{@code FROZEN}, dus ook een tweede annulering), en
 * {@code BUNDLE_CONTENT_CHANGED_DURING_CANCEL} (cancel, in de praktijk onbereikbaar). 400 met code
 * {@code DECISION_FILTER_REQUIRED} bij een lege groepsfilter; 400 zonder code bij een andere ongeldige
 * aanvraag (lege naam, lege of {@code system} als actor, ontbrekende {@code targetMode}, lege
 * batchlijst, ontbrekende reden bij een afkeuring, een herziening, een bevriezing of een annulering,
 * ongeldige paginering).
 * <p>
 * Autorisatie volgt in Fase 5: {@code createdBy}/{@code addedBy}/{@code removedBy}/{@code decidedBy}/
 * {@code frozenBy}/{@code cancelledBy} zijn voorlopig requestvelden.
 */
@RestController
@RequestMapping("/api/catalog-import/bundles")
public class CatalogImportBundleController {

    /** Body van {@code POST /bundles}; {@code targetMode} is verplicht, geen default (fase 4 par. 2). */
    public record CreateBundleRequest(String bundleReference, String description, PublicationTargetMode targetMode,
                                      Instant targetMoment, String publicationPolicy, String createdBy) {
    }

    /** Body van {@code POST /bundles/{id}/batches}. */
    public record AddBatchesRequest(List<Long> batchIds, String addedBy) {
    }

    /** Body van {@code POST /bundles/{id}/batches/{batchId}/remove}; {@code reason} is verplicht. */
    public record RemoveBatchRequest(String removedBy, String reason) {
    }

    /**
     * Body van {@code approve} en {@code reject}. {@code reason} is verplicht bij een afkeuring en bij
     * een herziening van een eerdere beslissing, en optioneel bij een gewone goedkeuring.
     */
    public record DecideMutationRequest(String decidedBy, String reason) {
    }

    /**
     * Body van de groepsactie {@code POST /bundles/{id}/decisions} (bouwstap 4d). {@code decisionKind}
     * is {@code APPROVE} of {@code REJECT}; {@code reason} is verplicht bij een afkeuring.
     * {@code filter} moet minstens één veld dragen — een lege filter is 400
     * {@code DECISION_FILTER_REQUIRED}, nooit "dan maar de hele bundel".
     */
    public record DecideGroupRequest(BundleDecisionKind decisionKind, String decidedBy, String reason,
                                     DecisionFilter filter) {
    }

    /**
     * Body van {@code POST /bundles/{id}/freeze} (bouwstap 4e). Beide velden zijn verplicht: een
     * bevriezing is altijd van een mens ({@code frozenBy}, nooit {@code system}) en draagt altijd een
     * reden (R-FRZ).
     */
    public record FreezeBundleRequest(String frozenBy, String reason) {
    }

    /**
     * Body van {@code POST /bundles/{id}/cancel} (bouwstap 4f). Beide velden zijn verplicht: een
     * annulering is altijd van een mens ({@code cancelledBy}, nooit {@code system}) en draagt altijd
     * een reden.
     */
    public record CancelBundleRequest(String cancelledBy, String reason) {
    }

    private final PublicationBundleService bundleService;
    private final BundleDecisionService decisionService;
    private final BundleFreezeService freezeService;
    private final BundleCancellationService cancellationService;
    private final BundleQueryService queries;

    public CatalogImportBundleController(PublicationBundleService bundleService,
                                         BundleDecisionService decisionService, BundleFreezeService freezeService,
                                         BundleCancellationService cancellationService, BundleQueryService queries) {
        this.bundleService = bundleService;
        this.decisionService = decisionService;
        this.freezeService = freezeService;
        this.cancellationService = cancellationService;
        this.queries = queries;
    }

    /** Batches die in aanmerking komen om aan een bundel toegevoegd te worden, gepagineerd. */
    @GetMapping("/candidates")
    PageResult<BundleCandidate> candidates(@RequestParam(value = "importLinkId", required = false) Long importLinkId,
                                           @RequestParam(value = "page", required = false) Integer page,
                                           @RequestParam(value = "size", required = false) Integer size) {
        return bundleService.candidates(importLinkId, page, size);
    }

    /**
     * Maakt een bundel aan, of geeft — idempotent — de bestaande bundel terug bij een herhaalde aanroep
     * met dezelfde {@code bundleReference} en scope. Een andere scope bij dezelfde referentie is 409
     * {@code BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE}.
     */
    @PostMapping
    BundleReference create(@RequestBody CreateBundleRequest request) {
        return bundleService.createBundle(request.bundleReference(), request.description(), request.targetMode(),
                request.targetMoment(), request.publicationPolicy(), request.createdBy());
    }

    /** De bundels, gepagineerd en optioneel beperkt tot één status. */
    @GetMapping
    PageResult<BundleSummary> list(@RequestParam(value = "status", required = false) PublicationBundleStatus status,
                                   @RequestParam(value = "page", required = false) Integer page,
                                   @RequestParam(value = "size", required = false) Integer size) {
        return queries.listBundles(status, page, size);
    }

    /** Volledige stand van één bundel, met live tellers zolang ze {@code ASSEMBLING} is. */
    @GetMapping("/{bundleId}")
    BundleDetail get(@PathVariable("bundleId") long bundleId) {
        return queries.getBundle(bundleId);
    }

    /** De lidmaatschappen (actief en verwijderd) van een bundel, gepagineerd. */
    @GetMapping("/{bundleId}/batches")
    PageResult<BundleBatchRow> batches(@PathVariable("bundleId") long bundleId,
                                       @RequestParam(value = "page", required = false) Integer page,
                                       @RequestParam(value = "size", required = false) Integer size) {
        return queries.getBundleBatches(bundleId, page, size);
    }

    /** Voegt batches alles-of-niets toe aan een {@code ASSEMBLING}-bundel. */
    @PostMapping("/{bundleId}/batches")
    List<Membership> addBatches(@PathVariable("bundleId") long bundleId, @RequestBody AddBatchesRequest request) {
        return bundleService.addBatches(bundleId, request.batchIds(), request.addedBy());
    }

    /** Verwijdert een batch uit een {@code ASSEMBLING}-bundel; weigert zodra ze besliste mutaties draagt. */
    @PostMapping("/{bundleId}/batches/{batchId}/remove")
    Membership removeBatch(@PathVariable("bundleId") long bundleId, @PathVariable("batchId") long batchId,
                           @RequestBody RemoveBatchRequest request) {
        return bundleService.removeBatch(bundleId, batchId, request.removedBy(), request.reason());
    }

    /**
     * De mutaties van alle actieve batches van deze bundel, gepagineerd en optioneel gefilterd op
     * {@code status}, {@code batchId}, {@code actionType} en {@code statusReason} (exacte,
     * hoofdlettergevoelige gelijkheid; afwezig of blanco = geen filter) (bouwstap 4c).
     * <p>
     * Elke regel toont naast de screeninggegevens ook haar beslissing: {@code decidedBy},
     * {@code decidedAt}, {@code decidedFromStatus} en {@code decisionId} — dezelfde vier velden die
     * sinds 4c ook in {@code GET /batches/{id}/mutations} staan, zodat beide lijsten exact dezelfde
     * vorm hebben. {@code decisionId} is de <b>laatste</b> beslissing; het volledige verloop staat in
     * {@code GET /bundles/{id}/decisions}.
     */
    @GetMapping("/{bundleId}/mutations")
    PageResult<MutationRow> mutations(@PathVariable("bundleId") long bundleId,
                                      @RequestParam(value = "status", required = false) MutationStatus status,
                                      @RequestParam(value = "batchId", required = false) Long batchId,
                                      @RequestParam(value = "actionType", required = false)
                                      MutationActionType actionType,
                                      @RequestParam(value = "statusReason", required = false) String statusReason,
                                      @RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "size", required = false) Integer size) {
        return queries.getBundleMutations(bundleId, status, batchId, actionType, statusReason, page, size);
    }

    /**
     * Het beslissingsregister van deze bundel, chronologisch en gepagineerd (bouwstap 4c).
     * Append-only: een herziening staat hier als extra regel náást de beslissing die ze herziet, die
     * nooit gewijzigd of verwijderd wordt.
     */
    @GetMapping("/{bundleId}/decisions")
    PageResult<DecisionRow> decisions(@PathVariable("bundleId") long bundleId,
                                      @RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "size", required = false) Integer size) {
        return queries.getBundleDecisions(bundleId, page, size);
    }

    /**
     * Keurt één mutatie goed ({@code READY_FOR_PUBLICATION}). {@code reason} is optioneel, behalve bij
     * een herziening van een eerdere afkeuring. Een herhaling door dezelfde beslisser is idempotent:
     * 200 en {@code idempotent = true}, zonder tweede beslissingsregel.
     */
    @PostMapping("/{bundleId}/mutations/{mutationId}/approve")
    MutationDecisionView approve(@PathVariable("bundleId") long bundleId,
                                 @PathVariable("mutationId") long mutationId,
                                 @RequestBody DecideMutationRequest request) {
        return decisionService.approve(bundleId, mutationId, request.decidedBy(), request.reason());
    }

    /** Keurt één mutatie af ({@code REJECTED}); {@code reason} is hier altijd verplicht (R-DEC). */
    @PostMapping("/{bundleId}/mutations/{mutationId}/reject")
    MutationDecisionView reject(@PathVariable("bundleId") long bundleId,
                                @PathVariable("mutationId") long mutationId,
                                @RequestBody DecideMutationRequest request) {
        return decisionService.reject(bundleId, mutationId, request.decidedBy(), request.reason());
    }

    /**
     * Keurt in één handeling alle mutaties van deze bundel goed of af die aan de filter voldoen
     * (bouwstap 4d). Het antwoord draagt de ene beslissingsregel en het werkelijke aantal geraakte
     * mutaties; raakte de actie niets, dan is {@code decisionId} {@code null} en {@code affectedCount}
     * 0 — er is dan bewust géén regel geschreven.
     * <p>
     * De actie raakt nooit een {@code BLOCKED} mutatie, een identiteitsincident, de
     * {@code IMPORT_MARKER} of een mutatie die al een beslissing draagt. Een herziening blijft daarom
     * exclusief het individuele pad hierboven. Een herhaalde, identieke aanroep is veilig: ze vindt
     * niets meer.
     */
    @PostMapping("/{bundleId}/decisions")
    GroupDecisionView decideGroup(@PathVariable("bundleId") long bundleId,
                                  @RequestBody DecideGroupRequest request) {
        return decisionService.decideGroup(bundleId, request.decisionKind(), request.decidedBy(),
                request.reason(), request.filter());
    }

    /**
     * Bevriest de bundel (bouwstap 4e): controleert alle voorwaarden, keurt de resterende
     * {@code PLANNED}-mutaties in bulk goed op naam van de bevriezer, stelt de tien tellers en de
     * bundelhash vast en sluit de bundel af ({@code FROZEN}). Alles in één transactie: bij een fout
     * halverwege blijft de bundel onveranderd {@code ASSEMBLING} en mag de aanroep herhaald worden.
     * <p>
     * Het antwoord is de volledige {@code BundleDetail} van de bevroren bundel: de tellers komen dan
     * van de rij zelf (niet meer live berekend) en {@code contentHash} draagt de bundelhash als
     * hexadecimale tekst.
     * <p>
     * 409 met een stabiele {@code code}: {@code BUNDLE_NOT_ASSEMBLING} (ook bij een tweede poging),
     * {@code BUNDLE_EMPTY}, {@code BUNDLE_HAS_UNDECIDED_MUTATIONS},
     * {@code SOURCE_STATE_CHANGED_SINCE_SCREENING}, {@code BUNDLE_OFFER_CONFLICT},
     * {@code OFFER_ALREADY_IN_ANOTHER_BUNDLE}; 400 bij een ontbrekende {@code frozenBy} of
     * {@code reason}.
     */
    @PostMapping("/{bundleId}/freeze")
    BundleDetail freeze(@PathVariable("bundleId") long bundleId, @RequestBody FreezeBundleRequest request) {
        freezeService.freeze(bundleId, request.frozenBy(), request.reason());
        return queries.getBundle(bundleId);
    }

    /**
     * Annuleert de bundel (bouwstap 4f): vanuit {@code ASSEMBLING} <b>of</b> {@code FROZEN} (beslissingslog
     * 22/09, keuze 4) — dit is de enige bundelactie die vanuit twee statussen mag. Laat elke nog
     * niet-terminale mutatie van de actieve leden vervallen ({@code EXPIRED}), geeft die leden vrij (weer
     * bruikbaar voor {@code accept-baseline} of een andere bundel) en sluit de bundel af
     * ({@code CANCELLED}). Alles in één transactie, net als bevriezen.
     * <p>
     * Het antwoord is de volledige {@code BundleDetail} van de geannuleerde bundel.
     * <p>
     * 409 {@code BUNDLE_NOT_CANCELLABLE} vanuit elke andere status (ook een tweede annulering); 400 bij
     * een ontbrekende {@code cancelledBy} of {@code reason}.
     */
    @PostMapping("/{bundleId}/cancel")
    BundleDetail cancel(@PathVariable("bundleId") long bundleId, @RequestBody CancelBundleRequest request) {
        cancellationService.cancel(bundleId, request.cancelledBy(), request.reason());
        return queries.getBundle(bundleId);
    }
}
