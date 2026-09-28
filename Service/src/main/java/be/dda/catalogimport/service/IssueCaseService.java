package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.IssueCaseEventRepository;
import be.dda.catalogimport.dao.IssueCaseRepository;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportLink;
import be.dda.catalogimport.domain.IssueCase;
import be.dda.catalogimport.domain.IssueCaseEvent;
import be.dda.catalogimport.domain.IssueCaseEventKind;
import be.dda.catalogimport.domain.IssueCaseEventSource;
import be.dda.catalogimport.domain.IssueCaseStatus;
import be.dda.catalogimport.domain.RevisionStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * De menselijke statuswijziging op een {@link IssueCase} (docs/design/issue-case-design.md par. 4,
 * bouwstap S2-B2): de vijf toegestane overgangen uit {@link IssueCase#recordHumanDecision} plus het
 * bijhorende append-only {@code issue_case_event} (patroon {@link BundleDecisionService}/
 * {@code PublicationBundleRepository#findByIdForUpdate}: één {@link TransactionTemplate}-aanroep,
 * niet {@code @Transactional}, en {@link IssueCaseRepository#findByIdForUpdate} als serialisatiepunt).
 *
 * <h2>Businessgedrag (design par. 4)</h2>
 * <ul>
 *   <li>Enkel {@code MANAGE} (design A5: een behandelgeval laat per D3 niets door, dus geen
 *       {@code APPROVE}-niveau nodig) — afgedwongen door de Web-laag ({@code @RequiresPermission}).</li>
 *   <li>{@code reason} is altijd verplicht, ook bij een heropening.</li>
 *   <li>{@code expectedStatus} moet de huidige status zijn (optimistic concurrency op de status zelf,
 *       geen apart versieveld); een afwijking is 409 {@link #CODE_STATUS_CHANGED}.</li>
 *   <li>Een niet-toegestane overgang (o.a. {@code CORRECTED} ↔ {@code REJECTED} rechtstreeks, dezelfde
 *       status naar dezelfde status, of elke overgang naar {@code AUTO_RESOLVED}) is 409
 *       {@link #CODE_TRANSITION_NOT_ALLOWED} — bewaakt door {@link IssueCase#recordHumanDecision}
 *       zelf, hier enkel vertaald naar de HTTP-foutcode.</li>
 *   <li>{@code decision_revision_id} wordt bij <b>elke</b> menselijke beslissing bijgewerkt naar de
 *       actieve revisie van de betrokken koppeling (design par. 1) — dezelfde opzoeking als
 *       {@code LinkBookmarkValueService#activeRevision}. Bestaat er geen actieve revisie (zeldzaam:
 *       een koppeling zonder actieve revisie), dan blijft {@code decisionRevision} {@code null} in
 *       plaats van de aanvraag te weigeren; de heropeningsregel behandelt dat net als "regels
 *       gewijzigd" bij de volgende waarneming (R-CASE-03, "of {@code decision_revision_id is null}").</li>
 *   <li>Schrijft één {@code issue_case_event} ({@code STATUS_CHANGE}, {@code source = HUMAN},
 *       {@code observation_batch_id = null} — dit is geen systeemwaarneming).</li>
 * </ul>
 * <h2>D3</h2>
 * Deze service schrijft uitsluitend in {@code issue_case} en {@code issue_case_event}: geen enkel
 * effect op {@code import_batch}, {@code validation_result} of {@code import_mutation.status}.
 */
@Service
public class IssueCaseService {

    /** Het geval bestaat niet. */
    public static final String CODE_CASE_NOT_FOUND = "ISSUE_CASE_NOT_FOUND";
    /** {@code newStatus} ontbreekt in de aanvraag. */
    public static final String CODE_STATUS_REQUIRED = "ISSUE_CASE_STATUS_REQUIRED";
    /** {@code newStatus} is geen bestaande {@link IssueCaseStatus}-waarde. */
    public static final String CODE_STATUS_UNKNOWN = "ISSUE_CASE_STATUS_UNKNOWN";
    /** {@code reason} ontbreekt of is blanco. */
    public static final String CODE_REASON_REQUIRED = "ISSUE_CASE_REASON_REQUIRED";
    /** De gevraagde overgang staat niet in {@link IssueCase#recordHumanDecision}. */
    public static final String CODE_TRANSITION_NOT_ALLOWED = "ISSUE_CASE_TRANSITION_NOT_ALLOWED";
    /** {@code expectedStatus} komt niet overeen met de huidige status van het geval. */
    public static final String CODE_STATUS_CHANGED = "ISSUE_CASE_STATUS_CHANGED";

    /** {@code issue_case.status_reason}/{@code issue_case_event.reason}: varchar(500). */
    static final int MAX_REASON_LENGTH = 500;
    /** {@code issue_case.status_changed_by}/{@code issue_case_event.changed_by}: varchar(100). */
    static final int MAX_CHANGED_BY_LENGTH = 100;

    private static final Logger LOG = LoggerFactory.getLogger(IssueCaseService.class);

    /** Het geval zoals het ná de beslissing bij staat; bewust een projectie, geen gedetachte entiteit. */
    public record IssueCaseDecision(long id, IssueCaseStatus status, String statusReason,
                                    Instant statusChangedAt, String statusChangedBy,
                                    String statusChangedBySubject, Long decisionRevisionId, int reopenCount) {

        static IssueCaseDecision of(IssueCase issueCase) {
            ImportDefinitionRevision decisionRevision = issueCase.getDecisionRevision();
            return new IssueCaseDecision(issueCase.getId(), issueCase.getStatus(), issueCase.getStatusReason(),
                    issueCase.getStatusChangedAt(), issueCase.getStatusChangedBy(),
                    issueCase.getStatusChangedBySubject(),
                    decisionRevision == null ? null : decisionRevision.getId(), issueCase.getReopenCount());
        }
    }

    private final IssueCaseRepository cases;
    private final IssueCaseEventRepository events;
    private final ImportDefinitionRevisionRepository revisions;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public IssueCaseService(IssueCaseRepository cases, IssueCaseEventRepository events,
                            ImportDefinitionRevisionRepository revisions,
                            PlatformTransactionManager transactionManager, Clock clock) {
        this.cases = cases;
        this.events = events;
        this.revisions = revisions;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Wijzigt de status van één behandelgeval, door een mens (design par. 4).
     *
     * @param expectedStatus de status waarvan de aanvrager uitgaat; wijkt de werkelijke status af, dan
     *                       409 {@link #CODE_STATUS_CHANGED} zonder iets te wijzigen
     * @throws IllegalArgumentException ontbrekende/ongeldige {@code changedBy}, ontbrekende
     *                                  {@code expectedStatus}
     * @throws BadRequestException      {@link #CODE_STATUS_REQUIRED}, {@link #CODE_REASON_REQUIRED}
     * @throws NotFoundException        {@link #CODE_CASE_NOT_FOUND}
     * @throws ConflictException        {@link #CODE_STATUS_CHANGED}, {@link #CODE_TRANSITION_NOT_ALLOWED}
     */
    public IssueCaseDecision changeStatus(long caseId, IssueCaseStatus newStatus, IssueCaseStatus expectedStatus,
                                          String reason, ActorIdentity actor) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing changedBy");
        }
        if (newStatus == null) {
            throw new BadRequestException(CODE_STATUS_REQUIRED, "Missing newStatus");
        }
        if (expectedStatus == null) {
            throw new IllegalArgumentException("Missing expectedStatus");
        }
        String changedBy = ActorNames.requireActorName(actor.username(), "changedBy", MAX_CHANGED_BY_LENGTH);
        String changedBySubject = actor.subject();
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(CODE_REASON_REQUIRED, "Missing reason");
        }
        String motivation = ActorNames.requireText(reason, "reason", MAX_REASON_LENGTH);

        return transaction.execute(status -> {
            IssueCase issueCase = cases.findByIdForUpdate(caseId)
                    .orElseThrow(() -> new NotFoundException(CODE_CASE_NOT_FOUND,
                            "Issue case " + caseId + " not found"));
            if (issueCase.getStatus() != expectedStatus) {
                throw new ConflictException(CODE_STATUS_CHANGED, "Issue case " + caseId + " is in status "
                        + issueCase.getStatus() + ", not the expected " + expectedStatus
                        + "; reload the case and try again");
            }
            IssueCaseStatus previous = issueCase.getStatus();
            Instant changedAt = clock.instant();
            ImportDefinitionRevision decisionRevision = activeRevision(issueCase.getImportLink()).orElse(null);
            try {
                issueCase.recordHumanDecision(newStatus, motivation, changedBy, changedBySubject,
                        decisionRevision, changedAt);
            } catch (IllegalStateException notAllowed) {
                throw new ConflictException(CODE_TRANSITION_NOT_ALLOWED, notAllowed.getMessage());
            }
            IssueCase saved = cases.saveAndFlush(issueCase);
            IssueCaseEvent event = new IssueCaseEvent(saved, IssueCaseEventKind.STATUS_CHANGE, previous,
                    newStatus, motivation, IssueCaseEventSource.HUMAN, changedBy, changedBySubject, changedAt,
                    null);
            events.saveAndFlush(event);
            LOG.info("Issue case {} changed {} -> {} by {} ({})", caseId, previous, newStatus, changedBy,
                    motivation);
            return IssueCaseDecision.of(saved);
        });
    }

    /** Zelfde opzoeking als {@code LinkBookmarkValueService#activeRevision}. */
    private Optional<ImportDefinitionRevision> activeRevision(ImportLink link) {
        return revisions.findByImportDefinitionIdAndStatus(link.getImportDefinition().getId(), RevisionStatus.ACTIVE);
    }
}
