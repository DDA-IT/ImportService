package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.ImportBatchStatus;
import be.dda.catalogimport.domain.MutationActionType;
import be.dda.catalogimport.domain.MutationStatus;
import be.dda.catalogimport.domain.ValidationResult;
import be.dda.catalogimport.service.BatchQueryService;
import be.dda.catalogimport.service.BatchQueryService.BatchDetail;
import be.dda.catalogimport.service.BatchQueryService.BatchRow;
import be.dda.catalogimport.service.BatchQueryService.BatchSummary;
import be.dda.catalogimport.service.BatchQueryService.IssueGroupRow;
import be.dda.catalogimport.service.BatchQueryService.IssueRow;
import be.dda.catalogimport.service.BatchQueryService.MutationRow;
import be.dda.catalogimport.service.DeliveryScreeningService;
import be.dda.catalogimport.service.DeliveryScreeningService.ScreeningOutcome;
import be.dda.catalogimport.service.PageResult;
import be.dda.catalogimport.service.SourceStateBaselineService;
import be.dda.catalogimport.service.SourceStateBaselineService.BaselineAcceptance;
import java.time.Instant;
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
     * De werkvoorraadlijst (Scherm 0, D14, bouwstap S0-B1): alle batches, nieuwste eerst
     * ({@code id desc}, vast — nooit onbepaald), optioneel gefilterd op {@code status},
     * {@code validationResult}, {@code importLinkId} en het halfopen {@code [createdFrom, createdTo)}
     * op {@code createdAt}. Let op de padvolgorde met {@link #summary}: {@code /batches/summary} is een
     * letterlijk pad en wint van dit endpoint niet — Spring matcht {@code GET /batches} hier alleen
     * zonder verder pad.
     */
    @GetMapping
    PageResult<BatchRow> batches(@RequestParam(value = "status", required = false) ImportBatchStatus status,
                                 @RequestParam(value = "validationResult", required = false)
                                 ValidationResult validationResult,
                                 @RequestParam(value = "importLinkId", required = false) Long importLinkId,
                                 @RequestParam(value = "createdFrom", required = false) Instant createdFrom,
                                 @RequestParam(value = "createdTo", required = false) Instant createdTo,
                                 @RequestParam(value = "page", required = false) Integer page,
                                 @RequestParam(value = "size", required = false) Integer size) {
        return queries.listBatches(status, validationResult, importLinkId, createdFrom, createdTo, page, size);
    }

    /**
     * De werkvoorraadsamenvatting (Scherm 0, D14, bouwstap S0-B2): totaal en de verdeling over status
     * en eindoordeel, optioneel beperkt tot één koppeling. {@code validationResult == null} ("niet
     * vastgesteld") is een eigen zichtbare regel in {@code byValidationResult}, nooit samengevoegd met
     * {@code VALID} en nooit weggelaten wanneer er werkelijk zulke batches bestaan.
     * <p>
     * Dit letterlijke pad wordt vóór {@code GET /batches/{batchId}} gematcht: {@code "summary"} is geen
     * geldige {@code batchId} maar Spring's {@code PathPattern}-matching geeft toch voorrang aan het
     * letterlijke segment.
     */
    @GetMapping("/summary")
    BatchSummary summary(@RequestParam(value = "importLinkId", required = false) Long importLinkId) {
        return queries.getSummary(importLinkId);
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
     * <p>
     * Additief sinds bouwstap 3g: {@code bulkIncidentCount}, het aantal foutgroepen dat als
     * bulkincident aangemerkt is. De groepen zelf staan in {@code GET /batches/{id}/issue-groups}.
     * <p>
     * Additief sinds bouwstap 3h-2: {@code criticalLineCount}, het aantal verworpen bronregels met een
     * fout op een kritieke kolom; {@code null} is "niet vastgesteld", nooit stil 0.
     * <p>
     * Additief sinds bouwstap 3h-3: {@code creationOutcome}
     * ({@code AUTOMATIC|INITIAL_LOAD|THRESHOLD_EXCEEDED}) en {@code creationScopeCount}, het aantal
     * actieve aanbiedingen van de koppeling waartegen het creatiebeleid geoordeeld heeft. Beide zijn
     * {@code null} zolang dat oordeel er niet is.
     */
    @GetMapping("/{batchId}")
    BatchDetail batch(@PathVariable("batchId") long batchId) {
        return queries.getBatch(batchId);
    }

    /**
     * De mutatielijst, gepagineerd en optioneel gefilterd op {@code status}, {@code statusReason}
     * (exact, hoofdlettergevoelig; afwezig of blanco = geen filter), {@code actionType} en
     * {@code identityHash}.
     * <p>
     * Additief sinds bouwstap C4: elke regel toont haar {@code identityHash} (hexadecimaal, kleine
     * letters; {@code null} bij de {@code IMPORT_MARKER}) en de lijst kan met {@code identityHash} tot
     * één wijzigingsgroep beperkt worden. Dat filter werkt aan de serverkant en dus over paginagrenzen
     * heen. Een onbekende of ongeldige hexwaarde geeft een lege pagina en geen fout; blanco of afwezig
     * is geen filter.
     */
    @GetMapping("/{batchId}/mutations")
    PageResult<MutationRow> mutations(@PathVariable("batchId") long batchId,
                                      @RequestParam(value = "status", required = false) MutationStatus status,
                                      @RequestParam(value = "statusReason", required = false) String statusReason,
                                      @RequestParam(value = "actionType", required = false)
                                      MutationActionType actionType,
                                      @RequestParam(value = "identityHash", required = false) String identityHash,
                                      @RequestParam(value = "page", required = false) Integer page,
                                      @RequestParam(value = "size", required = false) Integer size) {
        return queries.getMutations(batchId, status, statusReason, actionType, identityHash, page, size);
    }

    /**
     * De vastgestelde problemen (rijnummer, code, veld, bronwaarde), gepagineerd.
     * <p>
     * Sinds bouwstap 3a draagt deze lijst alle drie de controleniveaus: {@code rowNumber} kan
     * {@code null} zijn (een leverings- of structuurprobleem hoort bij geen enkele regel) en
     * {@code severity} kan naast {@code ERROR}/{@code WARNING} ook {@code CRITICAL},
     * {@code BLOCKING} of {@code INFO} zijn. {@code issueDomain}, {@code controlLevel},
     * {@code impactScope}, {@code handlingStatus} en {@code expectedValue} zijn additief.
     * <p>
     * Additief sinds bouwstap 3g: elke rij toont haar {@code issueGroupId} en de lijst kan met
     * {@code issueGroupId} tot één foutgroep beperkt worden. Let op: de getoonde rijen zijn
     * <b>voorbeelden</b> — per foutcode worden er hoogstens
     * {@code catalogimport.screening.max-sample-rows-per-code} bewaard. Het werkelijke aantal staat
     * uitsluitend in {@code GET /batches/{id}/issue-groups}; een telling over deze lijst is dus
     * systematisch te laag.
     */
    @GetMapping("/{batchId}/issues")
    PageResult<IssueRow> issues(@PathVariable("batchId") long batchId,
                                @RequestParam(value = "issueGroupId", required = false) Long issueGroupId,
                                @RequestParam(value = "page", required = false) Integer page,
                                @RequestParam(value = "size", required = false) Integer size) {
        return queries.getIssues(batchId, issueGroupId, page, size);
    }

    /**
     * De foutgroepen van een batch: gelijksoortige vaststellingen samengevat, met het
     * <b>werkelijke</b> aantal ({@code occurrenceCount}), het aantal bewaarde voorbeeldrijen
     * ({@code recordedSampleCount}), de gecontroleerde scope, het aandeel daarin en of de groep een
     * bulkincident is (bouwstap 3g, R-THR-04).
     * <p>
     * Gepagineerd zoals de andere lijsten: {@code page} 0-gebaseerd, {@code size} standaard 50 en
     * begrensd tot 200.
     */
    @GetMapping("/{batchId}/issue-groups")
    PageResult<IssueGroupRow> issueGroups(@PathVariable("batchId") long batchId,
                                          @RequestParam(value = "page", required = false) Integer page,
                                          @RequestParam(value = "size", required = false) Integer size) {
        return queries.getIssueGroups(batchId, page, size);
    }

    /**
     * Aanvaardt een gescreende batch als nulmeting van de bronstaat; enkel vanuit {@code SCREENED}.
     * <p>
     * <b>Eén bevoegde persoon volstaat</b> (bewuste beslissing 20/09/2026): {@code acceptedBy} en
     * {@code reason} zijn de enige velden, ook voor een batch met wachtende creaties, een
     * bulkincident of {@code REVIEW_REQUIRED}. Er is geen {@code approvedBy} en geen 409
     * {@code FOUR_EYES_APPROVAL_REQUIRED}. Een onbekend extra veld in de body (bv. {@code approvedBy})
     * wordt door de standaard Jackson-configuratie genegeerd en nergens bewaard. Vier-ogen wordt pas
     * met authenticatie (Fase 5) opnieuw beoordeeld.
     */
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
