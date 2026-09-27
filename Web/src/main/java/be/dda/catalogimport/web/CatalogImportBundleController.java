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
import be.dda.catalogimport.service.BadRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
 * praktijk onbereikbaar), {@code SNAPSHOT_SOURCE_MISSING} (freeze: de kandidaatstaging van minstens één
 * te publiceren mutatie bestaat niet meer, dus de bundelsnapshot zou onvolledig zijn — bouwstap 5P-2),
 * {@code BUNDLE_NOT_CANCELLABLE} (cancel op elke status buiten
 * {@code ASSEMBLING}/{@code FROZEN}, dus ook een tweede annulering), en
 * {@code BUNDLE_CONTENT_CHANGED_DURING_CANCEL} (cancel, in de praktijk onbereikbaar). 400 met code
 * {@code DECISION_FILTER_REQUIRED} bij een lege groepsfilter; 400 zonder code bij een andere ongeldige
 * aanvraag (lege naam, lege of {@code system} als actor, ontbrekende {@code targetMode}, lege
 * batchlijst, ontbrekende reden bij een afkeuring, een herziening, een bevriezing of een annulering,
 * ongeldige paginering).
 * <p>
 * <b>Geverifieerde identiteit (Fase 5-AUTH).</b> Bouwstap 5A-2 (freeze) en 5A-4 (alle overige
 * bundelschrijfendpoints) sluiten aan op de aangemelde gebruiker: {@code frozenBy}, {@code createdBy},
 * {@code addedBy}, {@code removedBy}, {@code decidedBy} en {@code cancelledBy} zijn <b>optionele
 * controlevelden</b> in plaats van de bron van de naam (400 {@code ACTOR_FIELD_MISMATCH} bij een andere
 * naam, 403 {@code SYSTEM_ACTOR_FORBIDDEN} voor {@code system}). Die controle gebeurt telkens vóór de
 * service, dus ook vóór een 404/409 (bv. 400 vóór 404 bij een onbekende bundel). Bewaard wordt de
 * token-username met het OIDC-subject.
 * <p>
 * <b>Rechten per actie (Fase 5-PERM, bouwstap 5B-1).</b> De vijf goedkeurende endpoints —
 * {@code approve}, {@code reject}, de groepsactie {@code decisions}, {@code freeze} en {@code cancel} —
 * dragen {@code @RequiresPermission(APPROVE)} (ontwerp par. 1; beslissingslog 2026-09-25 A2). Die check
 * gebeurt in een interceptor en dus <b>vóór</b> de handler. De volgorde is daardoor: 403
 * {@code SYSTEM_ACTOR_FORBIDDEN} (enkel bij MANAGE/APPROVE), 503
 * {@code PERMISSION_SOURCE_UNAVAILABLE}, 403 {@code PERMISSION_DENIED}, en pas daarna 400
 * {@code ACTOR_FIELD_MISMATCH} / {@code DECISION_FILTER_UNKNOWN_FIELD} en 404/409 (keuze mens
 * 2026-09-26, V1 "recht eerst"). Dat wijzigt bewust de volgorde uit {@code fase5-auth-design.md}
 * par. 13.1. De overige endpoints van deze controller zijn nog niet geannoteerd en worden dus nog niet
 * op rechten gecontroleerd (5B-2/5B-3).
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
     * <p>
     * {@code filter} draagt sinds bouwstap C5 dezelfde vijf velden als de queryparameters van
     * {@code GET /bundles/{id}/mutations}: {@code batchId}, {@code status}, {@code statusReason},
     * {@code actionType} en {@code identityHash}. Additief: een body zonder {@code identityHash} gedraagt
     * zich exact zoals voorheen.
     */
    public record DecideGroupRequest(BundleDecisionKind decisionKind, String decidedBy, String reason,
                                     DecisionFilter filter) {
    }

    /**
     * Body van {@code POST /bundles/{id}/freeze} (bouwstap 4e). {@code reason} is verplicht (R-FRZ).
     * <p>
     * Sinds 5A-2 is {@code frozenBy} <b>optioneel</b> en enkel nog een controle: de naam komt uit de
     * aangemelde gebruiker (die nooit {@code system} mag zijn). Blanco of afwezig = geen controle;
     * een andere naam is 400 {@code ACTOR_FIELD_MISMATCH}. Het record zelf is niet gewijzigd.
     */
    public record FreezeBundleRequest(String frozenBy, String reason) {
    }

    /**
     * Body van {@code POST /bundles/{id}/cancel} (bouwstap 4f). {@code reason} is verplicht: een
     * annulering draagt altijd een reden. Sinds 5A-4 is {@code cancelledBy} optioneel en enkel een
     * controle op de aangemelde gebruiker (die nooit {@code system} mag zijn).
     */
    public record CancelBundleRequest(String cancelledBy, String reason) {
    }

    /** Stap C6: foutcode voor een onbekend veld in de groepsactie (topniveau of filter). */
    public static final String CODE_DECISION_FILTER_UNKNOWN_FIELD = "DECISION_FILTER_UNKNOWN_FIELD";

    private static final Set<String> GROUP_TOP_LEVEL_FIELDS = Set.of("decisionKind", "decidedBy", "reason", "filter");
    private static final Set<String> GROUP_FILTER_FIELDS =
            Set.of("batchId", "status", "statusReason", "actionType", "identityHash");
    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper();

    /** Whitelist op de ruwe JSON (beslissing C6, optie A): enkel dit endpoint weigert onbekende velden. */
    private static void rejectUnknownFields(JsonNode body) {
        if (body == null || !body.isObject()) {
            return;
        }
        checkFields(body, GROUP_TOP_LEVEL_FIELDS, "");
        JsonNode filter = body.get("filter");
        if (filter != null && filter.isObject()) {
            checkFields(filter, GROUP_FILTER_FIELDS, "filter.");
        }
    }

    private static void checkFields(JsonNode node, Set<String> allowed, String prefix) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new BadRequestException(CODE_DECISION_FILTER_UNKNOWN_FIELD,
                        "Unknown field in group decision request: " + prefix + name);
            }
        }
    }

    private final PublicationBundleService bundleService;
    private final BundleDecisionService decisionService;
    private final BundleFreezeService freezeService;
    private final BundleCancellationService cancellationService;
    private final BundleQueryService queries;
    private final CurrentActor currentActor;

    public CatalogImportBundleController(PublicationBundleService bundleService,
                                         BundleDecisionService decisionService, BundleFreezeService freezeService,
                                         BundleCancellationService cancellationService, BundleQueryService queries,
                                         CurrentActor currentActor) {
        this.bundleService = bundleService;
        this.decisionService = decisionService;
        this.freezeService = freezeService;
        this.cancellationService = cancellationService;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /** Batches die in aanmerking komen om aan een bundel toegevoegd te worden, gepagineerd. */
    @RequiresPermission(Permission.READ)
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
    @RequiresPermission(Permission.MANAGE)
    @PostMapping
    BundleReference create(@RequestBody CreateBundleRequest request) {
        return bundleService.createBundle(request.bundleReference(), request.description(), request.targetMode(),
                request.targetMoment(), request.publicationPolicy(),
                currentActor.signer(request.createdBy(), "createdBy"));
    }

    /** De bundels, gepagineerd en optioneel beperkt tot één status. */
    @RequiresPermission(Permission.READ)
    @GetMapping
    PageResult<BundleSummary> list(@RequestParam(value = "status", required = false) PublicationBundleStatus status,
                                   @RequestParam(value = "page", required = false) Integer page,
                                   @RequestParam(value = "size", required = false) Integer size) {
        return queries.listBundles(status, page, size);
    }

    /** Volledige stand van één bundel, met live tellers zolang ze {@code ASSEMBLING} is. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/{bundleId}")
    BundleDetail get(@PathVariable("bundleId") long bundleId) {
        return queries.getBundle(bundleId);
    }

    /** De lidmaatschappen (actief en verwijderd) van een bundel, gepagineerd. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/{bundleId}/batches")
    PageResult<BundleBatchRow> batches(@PathVariable("bundleId") long bundleId,
                                       @RequestParam(value = "page", required = false) Integer page,
                                       @RequestParam(value = "size", required = false) Integer size) {
        return queries.getBundleBatches(bundleId, page, size);
    }

    /** Voegt batches alles-of-niets toe aan een {@code ASSEMBLING}-bundel. */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/{bundleId}/batches")
    List<Membership> addBatches(@PathVariable("bundleId") long bundleId, @RequestBody AddBatchesRequest request) {
        return bundleService.addBatches(bundleId, request.batchIds(),
                currentActor.signer(request.addedBy(), "addedBy"));
    }

    /** Verwijdert een batch uit een {@code ASSEMBLING}-bundel; weigert zodra ze besliste mutaties draagt. */
    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/{bundleId}/batches/{batchId}/remove")
    Membership removeBatch(@PathVariable("bundleId") long bundleId, @PathVariable("batchId") long batchId,
                           @RequestBody RemoveBatchRequest request) {
        return bundleService.removeBatch(bundleId, batchId, currentActor.signer(request.removedBy(), "removedBy"),
                request.reason());
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
     * <p>
     * Additief sinds bouwstap C4: elke regel toont haar {@code identityHash} (hexadecimaal, kleine
     * letters; {@code null} bij de {@code IMPORT_MARKER}) — de sleutel van de wijzigingsgroep — en de
     * lijst kan met {@code identityHash} tot die ene wijzigingsgroep beperkt worden. Dat filter werkt
     * aan de serverkant en dus over paginagrenzen heen, wat een groepering in de UI juist niet zou
     * kunnen (ontwerp scherm 3 par. 11.5). Een onbekende of ongeldige hexwaarde geeft een lege pagina
     * en geen fout; blanco of afwezig is geen filter.
     */
    @RequiresPermission(Permission.READ)
    @GetMapping("/{bundleId}/mutations")
    PageResult<MutationRow> mutations(@PathVariable("bundleId") long bundleId,
                                      @RequestParam(value = "status", required = false) MutationStatus status,
                                      @RequestParam(value = "batchId", required = false) Long batchId,
                                      @RequestParam(value = "actionType", required = false)
                                      MutationActionType actionType,
                                      @RequestParam(value = "statusReason", required = false) String statusReason,
                                      @RequestParam(value = "identityHash", required = false) String identityHash,
                                      @RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "size", required = false) Integer size) {
        return queries.getBundleMutations(bundleId, status, batchId, actionType, statusReason, identityHash,
                page, size);
    }

    /**
     * Het beslissingsregister van deze bundel, chronologisch en gepagineerd (bouwstap 4c).
     * Append-only: een herziening staat hier als extra regel náást de beslissing die ze herziet, die
     * nooit gewijzigd of verwijderd wordt.
     */
    @RequiresPermission(Permission.READ)
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
    @RequiresPermission(Permission.APPROVE)
    @PostMapping("/{bundleId}/mutations/{mutationId}/approve")
    MutationDecisionView approve(@PathVariable("bundleId") long bundleId,
                                 @PathVariable("mutationId") long mutationId,
                                 @RequestBody DecideMutationRequest request) {
        return decisionService.approve(bundleId, mutationId, currentActor.signer(request.decidedBy(), "decidedBy"),
                request.reason());
    }

    /** Keurt één mutatie af ({@code REJECTED}); {@code reason} is hier altijd verplicht (R-DEC). */
    @RequiresPermission(Permission.APPROVE)
    @PostMapping("/{bundleId}/mutations/{mutationId}/reject")
    MutationDecisionView reject(@PathVariable("bundleId") long bundleId,
                                @PathVariable("mutationId") long mutationId,
                                @RequestBody DecideMutationRequest request) {
        return decisionService.reject(bundleId, mutationId, currentActor.signer(request.decidedBy(), "decidedBy"),
                request.reason());
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
     * <p>
     * De filter is dezelfde als die van {@code GET /bundles/{id}/mutations}, inclusief
     * {@code identityHash} (bouwstap C5): {@code affectedCount} is dus nooit groter dan het
     * {@code totalElements} van die lijst met dezelfde filter. Een ongeldige of onbekende
     * {@code identityHash} levert {@code affectedCount = 0} op en geen fout — net zoals die lijst dan
     * leeg is.
     */
    @RequiresPermission(Permission.APPROVE)
    @PostMapping("/{bundleId}/decisions")
    GroupDecisionView decideGroup(@PathVariable("bundleId") long bundleId,
                                  @RequestBody JsonNode body) {
        rejectUnknownFields(body);
        DecideGroupRequest request;
        try {
            request = STRICT_MAPPER.treeToValue(body, DecideGroupRequest.class);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed group decision request", e);
        }
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing request body");
        }
        return decisionService.decideGroup(bundleId, request.decisionKind(),
                currentActor.signer(request.decidedBy(), "decidedBy"), request.reason(), request.filter());
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
     * {@code OFFER_ALREADY_IN_ANOTHER_BUNDLE}; 400 bij een ontbrekende {@code reason}.
     * <p>
     * <b>Fase 5-AUTH, bouwstap 5A-2.</b> De ondertekenaar komt uit de aangemelde gebruiker, niet uit het
     * request: {@code frozenBy} is optioneel geworden (was het al als nullable recordveld) en dient nog
     * enkel als controle. Staat er een andere naam in, dan is dat 400 {@code ACTOR_FIELD_MISMATCH} en
     * gebeurt er niets; een aangemelde {@code system} krijgt 403 {@code SYSTEM_ACTOR_FORBIDDEN}. Beide
     * controles gebeuren <b>vóór</b> de service, dus ook vóór de 404 op een onbekende bundel. Bewaard
     * wordt altijd de naam uit het token, samen met het OIDC-subject.
     */
    @RequiresPermission(Permission.APPROVE)
    @PostMapping("/{bundleId}/freeze")
    BundleDetail freeze(@PathVariable("bundleId") long bundleId, @RequestBody FreezeBundleRequest request) {
        freezeService.freeze(bundleId, currentActor.signer(request.frozenBy(), "frozenBy"), request.reason());
        return queries.getBundle(bundleId);
    }

    /**
     * Read-only droogloop van {@code freeze}: MOMENTOPNAME ZONDER SLOT, {@code freezable = true} is nooit
     * een garantie. Een niet-{@code ASSEMBLING} bundel is geen fout maar {@code freezable = false}.
     * 404 {@code BUNDLE_NOT_FOUND} bij een onbekende bundel.
     */
    @RequiresPermission(Permission.READ)
    @GetMapping("/{bundleId}/freeze-check")
    BundleFreezeService.FreezePreflight freezeCheck(@PathVariable("bundleId") long bundleId) {
        return freezeService.checkFreeze(bundleId);
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
     * een ontbrekende {@code reason}. Sinds 5A-4 is {@code cancelledBy} optioneel en enkel een controle
     * (400 {@code ACTOR_FIELD_MISMATCH}, 403 {@code SYSTEM_ACTOR_FORBIDDEN}); de naam komt uit de login.
     */
    @RequiresPermission(Permission.APPROVE)
    @PostMapping("/{bundleId}/cancel")
    BundleDetail cancel(@PathVariable("bundleId") long bundleId, @RequestBody CancelBundleRequest request) {
        cancellationService.cancel(bundleId, currentActor.signer(request.cancelledBy(), "cancelledBy"),
                request.reason());
        return queries.getBundle(bundleId);
    }
}
