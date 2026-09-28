package be.dda.catalogimport.web;

import be.dda.catalogimport.domain.IssueCaseStatus;
import be.dda.catalogimport.service.ActorIdentity;
import be.dda.catalogimport.service.BadRequestException;
import be.dda.catalogimport.service.IssueCaseService;
import be.dda.catalogimport.service.IssueCaseService.IssueCaseDecision;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * De menselijke statuswijziging op een behandelgeval (docs/design/issue-case-design.md par. 4,
 * bouwstap S2-B2). Lezen (S2-B3) en de systeemheropening (S2-B1b, tijdens de screening) horen hier
 * niet: dit endpoint is uitsluitend de menselijke beslissing.
 * <p>
 * <b>Statuscodes.</b> 404 {@code ISSUE_CASE_NOT_FOUND}; 400 {@code ISSUE_CASE_STATUS_REQUIRED},
 * {@code ISSUE_CASE_STATUS_UNKNOWN}, {@code ISSUE_CASE_REASON_REQUIRED}; 409
 * {@code ISSUE_CASE_TRANSITION_NOT_ALLOWED}, {@code ISSUE_CASE_STATUS_CHANGED}.
 */
@RestController
@RequestMapping("/api/catalog-import/issue-cases")
public class CatalogImportIssueCaseController {

    private final IssueCaseService issueCases;
    private final CurrentActor currentActor;

    public CatalogImportIssueCaseController(IssueCaseService issueCases, CurrentActor currentActor) {
        this.issueCases = issueCases;
        this.currentActor = currentActor;
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
