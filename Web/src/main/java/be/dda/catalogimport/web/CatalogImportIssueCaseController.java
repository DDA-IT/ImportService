package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.IssueCaseStatus;
import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.IssueCaseQueryService;
import be.dda.catalogimport.service.IssueCaseQueryService.EventRow;
import be.dda.catalogimport.service.IssueCaseQueryService.IssueCaseRow;
import be.dda.catalogimport.service.IssueCaseQueryService.IssueCaseSummary;
import be.dda.catalogimport.service.IssueCaseQueryService.ObservationRow;
import be.dda.catalogimport.service.IssueCaseService;
import be.dda.catalogimport.service.IssueCaseService.IssueCaseDecision;
import be.dda.catalogimport.service.PageResult;
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
 * Het behandelgeval (docs/design/issue-case-design.md): de menselijke statuswijziging (par. 4,
 * bouwstap S2-B2) en de leesendpoints (§6, bouwstap S2-B3). De systeemheropening (S2-B1b, tijdens de
 * screening) hoort hier niet.
 * <p>
 * <b>Statuscodes.</b> 404 {@code ISSUE_CASE_NOT_FOUND}; 400 {@code ISSUE_CASE_STATUS_REQUIRED},
 * {@code ISSUE_CASE_STATUS_UNKNOWN}, {@code ISSUE_CASE_REASON_REQUIRED}; 409
 * {@code ISSUE_CASE_TRANSITION_NOT_ALLOWED}, {@code ISSUE_CASE_STATUS_CHANGED}.
 */
@RestController
@RequestMapping("/api/catalog-import/issue-cases")
public class CatalogImportIssueCaseController {

    private final IssueCaseService issueCases;
    private final IssueCaseQueryService queries;
    private final CurrentActor currentActor;

    public CatalogImportIssueCaseController(IssueCaseService issueCases, IssueCaseQueryService queries,
                                            CurrentActor currentActor) {
        this.issueCases = issueCases;
        this.queries = queries;
        this.currentActor = currentActor;
    }

    /**
     * De behandelgevallenlijst (S2-B3), optioneel gefilterd op {@code importLinkId}, {@code status},
     * {@code severity}, {@code issueCode} en het halfopen {@code [lastSeenFrom, lastSeenTo)}-interval
     * op {@code lastSeenAt}. Vaste sortering {@code last_seen_at desc, id desc}. Let op de padvolgorde
     * met {@link #summary}: {@code /issue-cases/summary} is een letterlijk pad en wint van dit endpoint
     * niet.
     */
    @RequiresPermission(Permission.READ)
    @GetMapping
    PageResult<IssueCaseRow> cases(@RequestParam(value = "importLinkId", required = false) Long importLinkId,
                                   @RequestParam(value = "status", required = false) IssueCaseStatus status,
                                   @RequestParam(value = "severity", required = false) RowIssueSeverity severity,
                                   @RequestParam(value = "issueCode", required = false) String issueCode,
                                   @RequestParam(value = "lastSeenFrom", required = false) Instant lastSeenFrom,
                                   @RequestParam(value = "lastSeenTo", required = false) Instant lastSeenTo,
                                   @RequestParam(value = "page", required = false) Integer page,
                                   @RequestParam(value = "size", required = false) Integer size) {
        return queries.listCases(importLinkId, status, severity, issueCode, lastSeenFrom, lastSeenTo, page, size);
    }

    /**
     * Tellers per status, optioneel beperkt tot één koppeling. Dit letterlijke pad wordt vóór
     * {@code GET /issue-cases/{caseId}} gematcht (patroon {@code CatalogImportBatchController#summary}).
     */
    @RequiresPermission(Permission.READ)
    @GetMapping("/summary")
    IssueCaseSummary summary(@RequestParam(value = "importLinkId", required = false) Long importLinkId) {
        return queries.getSummary(importLinkId);
    }

    /** Detail van één behandelgeval. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/{caseId}")
    IssueCaseRow getCase(@PathVariable("caseId") long caseId) {
        return queries.getCase(caseId);
    }

    /**
     * De gekoppelde waarnemingen ({@code import_issue_group}-rijen) van één geval: voor welke
     * leveringen dit probleem vastgesteld is.
     */
    @RequiresPermission(Permission.READ)
    @GetMapping("/{caseId}/observations")
    List<ObservationRow> observations(@PathVariable("caseId") long caseId) {
        return queries.getObservations(caseId);
    }

    /** De volledige, append-only geschiedenis van één geval, oplopend op {@code id}. */
    @RequiresPermission(Permission.READ)
    @GetMapping("/{caseId}/events")
    List<EventRow> events(@PathVariable("caseId") long caseId) {
        return queries.getEvents(caseId);
    }

    /**
     * Body van de statuswijziging. {@code newStatus} en {@code expectedStatus} zijn tekst (niet
     * rechtstreeks het enum-type): een onbekende waarde moet 400 {@code ISSUE_CASE_STATUS_UNKNOWN}
     * opleveren in plaats van de generieke 400 die Spring geeft bij een mislukte enum-deserialisatie.
     */
    public record IssueCaseStatusChangeRequest(String newStatus, String expectedStatus, String reason,
                                                String changedBy) {
    }

    @RequiresPermission(Permission.MANAGE)
    @PostMapping("/{caseId}/status")
    IssueCaseDecision changeStatus(@PathVariable("caseId") long caseId,
                                   @RequestBody IssueCaseStatusChangeRequest request) {
        // Vóór de service: 400 ACTOR_FIELD_MISMATCH / 403 SYSTEM_ACTOR_FORBIDDEN gaan vóór 404/409,
        // exact zoals CatalogImportBatchController#acceptBaseline.
        ActorIdentity actor = currentActor.signer(request.changedBy(), "changedBy");
        IssueCaseStatus newStatus = parseStatus(request.newStatus(), IssueCaseService.CODE_STATUS_REQUIRED,
                IssueCaseService.CODE_STATUS_UNKNOWN, "newStatus");
        IssueCaseStatus expectedStatus = parseStatus(request.expectedStatus(), null, null, "expectedStatus");
        return issueCases.changeStatus(caseId, newStatus, expectedStatus, request.reason(), actor);
    }

    /**
     * @param missingCode  code voor een lege/ontbrekende waarde, of {@code null} voor een generieke
     *                     {@link IllegalArgumentException} (geen benoemde code in het ontwerp voor
     *                     {@code expectedStatus})
     * @param unknownCode  code voor een onbekende waarde, of {@code null} voor hetzelfde generieke gedrag
     */
    private static IssueCaseStatus parseStatus(String raw, String missingCode, String unknownCode,
                                                String fieldName) {
        if (raw == null || raw.isBlank()) {
            if (missingCode != null) {
                throw new BadRequestException(missingCode, "Missing " + fieldName);
            }
            throw new IllegalArgumentException("Missing " + fieldName);
        }
        try {
            return IssueCaseStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException notAnEnumValue) {
            if (unknownCode != null) {
                throw new BadRequestException(unknownCode, "Unknown " + fieldName + " '" + raw + "'");
            }
            throw new IllegalArgumentException("Unknown " + fieldName + " '" + raw + "'");
        }
    }
}
