package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.service.BatchQueryService;
import be.dda.catalogimport.service.BatchQueryService.BatchDetail;
import be.dda.catalogimport.service.BatchQueryService.IssueRow;
import be.dda.catalogimport.service.BatchQueryService.MutationRow;
import be.dda.catalogimport.service.DeliveryScreeningService;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.PageResult;
import be.dda.catalogimport.service.SourceStateBaselineService;
import be.dda.catalogimport.service.SourceStateBaselineService.BaselineAcceptance;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bediening en inzage van een screeningbatch (Fase 2, bouwstap 2e): status en tellers, de centrale
 * mutatielijst, de regelproblemen, het hervatten van een onderbroken mutatiegeneratie en het
 * aanvaarden van een gescreende levering als nulmeting van de bronstaat.
 * <p>
 * <b>Statuscodes.</b> 404 {@code BATCH_NOT_FOUND}; 409 met een stabiele {@code code}:
 * {@code BATCH_NOT_ACCEPTABLE} (accept-baseline buiten {@code SCREENED}),
 * {@code SOURCE_STATE_CHANGED_SINCE_SCREENING}, {@code BATCH_NOT_RESUMABLE} (continue buiten
 * {@code MUTATING}); 400 bij een ongeldige aanvraag (o.a. lege {@code reason}, lege of {@code system}
 * als {@code acceptedBy}, ongeldige paginering).
 * <p>
 * Autorisatie volgt in Fase 5: {@code acceptedBy} is voorlopig een requestveld.
 */
@RestController
@RequestMapping("/api/catalog-import/batches")
public class CatalogImportBatchController {

    /** Body van {@code accept-baseline}; beide velden zijn verplicht. */
    public record AcceptBaselineRequest(String acceptedBy, String reason) {
    }

    private final BatchQueryService queries;
    private final SourceStateBaselineService baseline;
    private final DeliveryScreeningService screening;

    public CatalogImportBatchController(BatchQueryService queries, SourceStateBaselineService baseline,
                                        DeliveryScreeningService screening) {
        this.queries = queries;
        this.baseline = baseline;
        this.screening = screening;
    }

    /**
     * Status, alle tellers en eventuele blokkeerreden van één batch.
     * <p>
     * Additief sinds bouwstap 3a: {@code validationResult} is het inhoudelijke eindoordeel naast
     * {@code status} en is {@code null} zolang er niets vastgesteld is.
     * <p>
     * Additief sinds bouwstap 3b: {@code filteredOutCount} (records die een recordfilter buiten de
     * importscope zette) en {@code errorBeforeFilterCount} (records die al vóór dat filter onleesbaar
     * waren). Samen met de bestaande tellers geldt
     * {@code raw = filteredOut + errorBeforeFilter + rejected + valid}; zonder geconfigureerde
     * recordfilters staan beide op 0 en blijven de fase 2-waarden ongewijzigd.
     */
    @GetMapping("/{batchId}")
    BatchDetail batch(@PathVariable("batchId") long batchId) {
        return queries.getBatch(batchId);
    }

    /** De mutatielijst, gepagineerd en optioneel gefilterd op {@code actionType}; zonder hashkolommen. */
    @GetMapping("/{batchId}/mutations")
    PageResult<MutationRow> mutations(@PathVariable("batchId") long batchId,
                                      @RequestParam(value = "actionType", required = false)
                                      MutationActionType actionType,
                                      @RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "size", required = false) Integer size) {
        return queries.getMutations(batchId, actionType, page, size);
    }

    /**
     * De vastgestelde problemen (rijnummer, code, veld, bronwaarde), gepagineerd.
     * <p>
     * Sinds bouwstap 3a draagt deze lijst alle drie de controleniveaus: {@code rowNumber} kan
     * {@code null} zijn (een leverings- of structuurprobleem hoort bij geen enkele regel) en
     * {@code severity} kan naast {@code ERROR}/{@code WARNING} ook {@code CRITICAL},
     * {@code BLOCKING} of {@code INFO} zijn. {@code issueDomain}, {@code controlLevel},
     * {@code impactScope}, {@code handlingStatus} en {@code expectedValue} zijn additief.
     */
    @GetMapping("/{batchId}/issues")
    PageResult<IssueRow> issues(@PathVariable("batchId") long batchId,
                                @RequestParam(value = "page", required = false) Integer page,
                                @RequestParam(value = "size", required = false) Integer size) {
        return queries.getIssues(batchId, page, size);
    }

    /** Aanvaardt een gescreende batch als nulmeting van de bronstaat; enkel vanuit {@code SCREENED}. */
    @PostMapping("/{batchId}/accept-baseline")
    BaselineAcceptance acceptBaseline(@PathVariable("batchId") long batchId,
                                      @RequestBody AcceptBaselineRequest request) {
        return baseline.acceptBaseline(batchId, request.acceptedBy(), request.reason());
    }

    /**
     * Hervat de mutatiegeneratie van een batch die op {@code MUTATING} bleef staan en antwoordt met de
     * eindstatus en de tellers. Een technische fout blijft een fout (500): de batch blijft dan
     * hervatbaar en dezelfde aanroep mag herhaald worden.
     */
    @PostMapping("/{batchId}/continue")
    ScreeningOutcome resume(@PathVariable("batchId") long batchId) {
        return screening.continueMutating(batchId);
    }
}
