package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.IssueCaseEventRepository;
import be.dda.catalogimport.dao.IssueCaseRepository;
import be.dda.catalogimport.dao.IssueGroupDao;
import be.dda.catalogimport.dao.IssueGroupDao.CaseObservationRow;
import be.dda.catalogimport.domain.IssueCase;
import be.dda.catalogimport.domain.IssueCaseEvent;
import be.dda.catalogimport.domain.IssueCaseStatus;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RowIssueSeverity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alleen-lezen inzage van het behandelgeval (S2-B3, docs/design/issue-case-design.md §6): de lijst,
 * het detail, de gekoppelde waarnemingen, de audit-geschiedenis en de statussamenvatting. Patroon
 * {@link BatchQueryService}/{@link SetupQueryService}: geen JPA-entiteiten naar de Web-laag, wel
 * records; paginering {@code page} 0-gebaseerd, {@code size} standaard {@value #DEFAULT_PAGE_SIZE} en
 * begrensd tot {@value #MAX_PAGE_SIZE}. Schrijft niets.
 */
@Service
@Transactional(readOnly = true)
public class IssueCaseQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;

    /** Onbekend behandelgeval; dezelfde code als {@code IssueCaseService.CODE_CASE_NOT_FOUND}. */
    public static final String CODE_CASE_NOT_FOUND = IssueCaseService.CODE_CASE_NOT_FOUND;

    /**
     * Eén behandelgeval, zoals het in de lijst en op het detailscherm getoond wordt.
     * <p>
     * {@code hasUnreviewedRecurrence} is het kenmerk dat ontwerp §2 verplicht maakt ("Zichtbaarheid
     * van de onderdrukking"): zijn er waarnemingen ná de laatste menselijke beslissing?
     * <b>Ontwerpkeuze (niet letterlijk uit het ontwerp af te leiden voor een geval zonder
     * beslissing):</b> zolang er nog nooit een beslissing genomen is ({@code statusChangedAt == null},
     * dus ook elk vers geval in {@code AWAITING_REVIEW}), staat dit kenmerk op {@code true} — het
     * geval is per definitie nog niet beoordeeld, dus elke waarneming erin is "ongeziene herhaling"
     * in de brede zin. Zodra er wél een beslissing is, is de letterlijke ontwerpformule van
     * toepassing: {@code lastSeenAt > statusChangedAt}.
     */
    public record IssueCaseRow(long id, long importLinkId, String issueCode, String signature,
                               String severity, String issueDomain, String controlLevel,
                               String impactScope, String incidentKind, String priceComponentCode,
                               String referenceType, String status, long observationCount,
                               long totalOccurrenceCount, Instant firstSeenAt, Instant lastSeenAt,
                               long firstSeenBatchId, long lastSeenBatchId, long lastSeenRevisionId,
                               int reopenCount, String statusReason, Instant statusChangedAt,
                               String statusChangedBy, String statusChangedBySubject,
                               Long decisionRevisionId, Instant createdAt, Instant updatedAt,
                               boolean hasUnreviewedRecurrence) {

        private static IssueCaseRow of(IssueCase issueCase) {
            ImportDefinitionRevision decisionRevision = issueCase.getDecisionRevision();
            Instant statusChangedAt = issueCase.getStatusChangedAt();
            boolean hasUnreviewedRecurrence = statusChangedAt == null
                    || issueCase.getLastSeenAt().isAfter(statusChangedAt);
            return new IssueCaseRow(issueCase.getId(), issueCase.getImportLink().getId(),
                    issueCase.getIssueCode(), issueCase.getSignature(), issueCase.getSeverity().name(),
                    issueCase.getIssueDomain().name(), issueCase.getControlLevel().name(),
                    issueCase.getImpactScope().name(), issueCase.getIncidentKind().name(),
                    issueCase.getPriceComponentCode(), issueCase.getReferenceType(),
                    issueCase.getStatus().name(), issueCase.getObservationCount(),
                    issueCase.getTotalOccurrenceCount(), issueCase.getFirstSeenAt(),
                    issueCase.getLastSeenAt(), issueCase.getFirstSeenBatch().getId(),
                    issueCase.getLastSeenBatch().getId(), issueCase.getLastSeenRevision().getId(),
                    issueCase.getReopenCount(), issueCase.getStatusReason(), statusChangedAt,
                    issueCase.getStatusChangedBy(), issueCase.getStatusChangedBySubject(),
                    decisionRevision == null ? null : decisionRevision.getId(), issueCase.getCreatedAt(),
                    issueCase.getUpdatedAt(), hasUnreviewedRecurrence);
        }
    }

    /**
     * Eén waarneming van een behandelgeval: de gekoppelde groep, met de batch-, leverings- en
     * revisiereferentie ("voor welke leveringen dit probleem vastgesteld is").
     */
    public record ObservationRow(long issueGroupId, long batchId, long deliveryId, int attemptNo,
                                 long definitionRevisionId, long occurrenceCount, Instant firstDetectedAt,
                                 Instant lastDetectedAt) {

        private static ObservationRow of(CaseObservationRow row) {
            return new ObservationRow(row.issueGroupId(), row.batchId(), row.deliveryId(), row.attemptNo(),
                    row.definitionRevisionId(), row.occurrenceCount(), row.firstDetectedAt(),
                    row.lastDetectedAt());
        }
    }

    /** Eén regel van de append-only geschiedenis van een behandelgeval, oplopend op {@code id}. */
    public record EventRow(long id, String eventKind, String previousStatus, String newStatus, String reason,
                           String source, String changedBy, String changedBySubject, Instant changedAt,
                           Long observationBatchId) {

        private static EventRow of(IssueCaseEvent event) {
            return new EventRow(event.getId(), event.getEventKind().name(),
                    event.getPreviousStatus() == null ? null : event.getPreviousStatus().name(),
                    event.getNewStatus().name(), event.getReason(), event.getSource().name(),
                    event.getChangedBy(), event.getChangedBySubject(), event.getChangedAt(),
                    event.getObservationBatch() == null ? null : event.getObservationBatch().getId());
        }
    }

    /** Aantal behandelgevallen met een bepaalde {@link IssueCaseStatus}. */
    public record StatusCount(IssueCaseStatus status, long count) {
    }

    /**
     * Samenvatting: totaal en de verdeling over status, optioneel beperkt tot één koppeling. Patroon
     * {@code BatchQueryService.BatchSummary}: {@code total} is de som van {@code byStatus} (elk geval
     * heeft altijd een status).
     */
    public record IssueCaseSummary(long total, List<StatusCount> byStatus) {
    }

    private final IssueCaseRepository cases;
    private final IssueCaseEventRepository events;
    private final IssueGroupDao issueGroups;

    public IssueCaseQueryService(IssueCaseRepository cases, IssueCaseEventRepository events,
                                 IssueGroupDao issueGroups) {
        this.cases = cases;
        this.events = events;
        this.issueGroups = issueGroups;
    }

    /**
     * De behandelgevallenlijst, optioneel gefilterd op {@code importLinkId}, {@code status},
     * {@code severity}, {@code issueCode} en het halfopen {@code [lastSeenFrom, lastSeenTo)}-interval
     * op {@code lastSeenAt}. Vaste sortering {@code last_seen_at desc, id desc}.
     *
     * @throws IllegalArgumentException ongeldige paginering
     */
    public PageResult<IssueCaseRow> listCases(Long importLinkId, IssueCaseStatus status,
                                              RowIssueSeverity severity, String issueCode,
                                              Instant lastSeenFrom, Instant lastSeenTo, Integer page,
                                              Integer size) {
        PageRequest pageRequest = pageRequest(page, size);
        Page<IssueCase> result = cases.findCaseRows(importLinkId, status, severity, issueCode, lastSeenFrom,
                lastSeenTo, pageRequest);
        return PageResult.of(result, IssueCaseRow::of);
    }

    /** @throws NotFoundException onbekend geval ({@link #CODE_CASE_NOT_FOUND}) */
    public IssueCaseRow getCase(long caseId) {
        return IssueCaseRow.of(requireCase(caseId));
    }

    /**
     * De gekoppelde {@code import_issue_group}-waarnemingen van één geval, oplopend op groep-id.
     *
     * @throws NotFoundException onbekend geval ({@link #CODE_CASE_NOT_FOUND})
     */
    public List<ObservationRow> getObservations(long caseId) {
        requireCase(caseId);
        return issueGroups.findByIssueCaseId(caseId).stream().map(ObservationRow::of).toList();
    }

    /**
     * De volledige, append-only geschiedenis van één geval, oplopend op {@code id} ({@code CREATED}
     * eerst, dan elke {@code STATUS_CHANGE} in chronologische volgorde). Geen paginering: patroon
     * {@code publication_decision}.
     *
     * @throws NotFoundException onbekend geval ({@link #CODE_CASE_NOT_FOUND})
     */
    public List<EventRow> getEvents(long caseId) {
        requireCase(caseId);
        return events.findByIssueCaseIdOrderByIdAsc(caseId).stream().map(EventRow::of).toList();
    }

    /**
     * Tellers per status, optioneel beperkt tot één koppeling. Alle vier statuswaarden zijn hier altijd
     * berekenbaar; een status zonder gevallen krijgt eenvoudigweg geen regel (geen "niet
     * vastgesteld"-groep nodig, in tegenstelling tot {@code BatchQueryService.BatchSummary}).
     */
    public IssueCaseSummary getSummary(Long importLinkId) {
        List<StatusCount> byStatus = cases.countByStatusGrouped(importLinkId).stream()
                .map(row -> new StatusCount((IssueCaseStatus) row[0], (Long) row[1]))
                .toList();
        long total = byStatus.stream().mapToLong(StatusCount::count).sum();
        return new IssueCaseSummary(total, byStatus);
    }

    private IssueCase requireCase(long caseId) {
        return cases.findById(caseId)
                .orElseThrow(() -> new NotFoundException(CODE_CASE_NOT_FOUND, "Issue case " + caseId + " not found"));
    }

    private static PageRequest pageRequest(Integer page, Integer size) {
        int number = page == null ? 0 : page;
        int requested = size == null ? DEFAULT_PAGE_SIZE : size;
        if (number < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        return PageRequest.of(number, Math.min(requested, MAX_PAGE_SIZE), Sort.unsorted());
    }
}
