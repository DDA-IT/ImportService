package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.PublicationBundleStatus;
import be.dda.catalogimport.domain.PublicationTargetMode;
import be.dda.catalogimport.service.BatchQueryService.MutationRow;
import be.dda.catalogimport.service.BundleDecisionService;
import be.dda.catalogimport.service.BundleDecisionService.MutationDecisionView;
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
 * Bediening en inzage van de Publicatiebundel (Fase 4, bouwstappen 4b en 4c): kandidaten opzoeken, een
 * bundel aanmaken (idempotent op {@code bundleReference}), batches toevoegen/verwijderen, het
 * leesmodel, en het individueel goedkeuren/afkeuren van één mutatie met haar beslissingsregister. De
 * groepsactie, het bevriezen en het annuleren volgen in 4d-4f.
 * <p>
 * <b>Statuscodes.</b> 404 {@code BUNDLE_NOT_FOUND}, {@code BATCH_NOT_FOUND},
 * {@code BATCH_NOT_IN_BUNDLE}, {@code MUTATION_NOT_IN_BUNDLE}; 409 met een stabiele {@code code}:
 * {@code BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE}, {@code BUNDLE_NOT_ASSEMBLING},
 * {@code BATCH_ALREADY_IN_BUNDLE}, {@code BATCH_NOT_BUNDLEABLE},
 * {@code BATCH_VALIDATION_NOT_ESTABLISHED}, {@code BATCH_VALIDATION_BLOCKING},
 * {@code BATCH_HAS_DECIDED_MUTATIONS}, {@code MUTATION_NOT_DECIDABLE},
 * {@code MUTATION_BLOCKED_BY_IDENTITY_INCIDENT}, {@code IDENTITY_DECISION_NOT_IN_SCOPE}; 400 bij een
 * ongeldige aanvraag (lege naam, lege of {@code system} als actor, ontbrekende {@code targetMode},
 * lege batchlijst, ontbrekende reden bij een afkeuring of een herziening, ongeldige paginering).
 * <p>
 * Autorisatie volgt in Fase 5: {@code createdBy}/{@code addedBy}/{@code removedBy}/{@code decidedBy}
 * zijn voorlopig requestvelden.
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

    private final PublicationBundleService bundleService;
    private final BundleDecisionService decisionService;
    private final BundleQueryService queries;

    public CatalogImportBundleController(PublicationBundleService bundleService,
                                         BundleDecisionService decisionService, BundleQueryService queries) {
        this.bundleService = bundleService;
        this.decisionService = decisionService;
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
     * {@code status}, {@code batchId} en {@code actionType} (bouwstap 4c).
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
                                      @RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "size", required = false) Integer size) {
        return queries.getBundleMutations(bundleId, status, batchId, actionType, page, size);
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
}
