package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Eén append-only auditregel van een {@link IssueCase} (docs/design/issue-case-design.md par. 1,
 * changeset 012-2). Nooit bijgewerkt of verwijderd: een heropening schrijft een <b>nieuwe</b> rij,
 * de vorige beslissing blijft ongewijzigd staan (R-CASE-04). Patroon {@link PublicationDecision}
 * (005-3): sortering op {@code id}, geen apart {@code sequence_no}.
 * <p>
 * {@code previousStatus} is {@code null} enkel bij {@link IssueCaseEventKind#CREATED} (er is nog
 * geen vorige status). {@code source} bepaalt of {@code changedBy}/{@code changedBySubject} gevuld
 * mogen zijn: {@link IssueCaseEventSource#HUMAN} draagt altijd een naam, {@link IssueCaseEventSource#SYSTEM}
 * nooit.
 */
@Entity
@Table(name = "issue_case_event")
public class IssueCaseEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "issue_case_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_issue_case_event_case"))
    private IssueCase issueCase;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", nullable = false, length = 30)
    private IssueCaseEventKind eventKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30)
    private IssueCaseStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 30)
    private IssueCaseStatus newStatus;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private IssueCaseEventSource source;

    /** {@code null} bij {@link IssueCaseEventSource#SYSTEM}. */
    @Column(name = "changed_by", length = 100)
    private String changedBy;

    /** OIDC-{@code sub}; {@code null} bij {@link IssueCaseEventSource#SYSTEM} of geen geverifieerde identiteit. */
    @Column(name = "changed_by_subject", length = 255)
    private String changedBySubject;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    /** Gevuld bij een systeemheropening of bij {@link IssueCaseEventKind#CREATED}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "observation_batch_id",
            foreignKey = @ForeignKey(name = "fk_issue_case_event_batch"))
    private ImportBatch observationBatch;

    protected IssueCaseEvent() {
        // JPA
    }

    public IssueCaseEvent(IssueCase issueCase, IssueCaseEventKind eventKind, IssueCaseStatus previousStatus,
                          IssueCaseStatus newStatus, String reason, IssueCaseEventSource source,
                          String changedBy, String changedBySubject, Instant changedAt,
                          ImportBatch observationBatch) {
        this.issueCase = issueCase;
        this.eventKind = eventKind;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reason = reason;
        this.source = source;
        this.changedBy = changedBy;
        this.changedBySubject = changedBySubject;
        this.changedAt = changedAt;
        this.observationBatch = observationBatch;
    }

    public Long getId() {
        return id;
    }

    public IssueCase getIssueCase() {
        return issueCase;
    }

    public IssueCaseEventKind getEventKind() {
        return eventKind;
    }

    public IssueCaseStatus getPreviousStatus() {
        return previousStatus;
    }

    public IssueCaseStatus getNewStatus() {
        return newStatus;
    }

    public String getReason() {
        return reason;
    }

    public IssueCaseEventSource getSource() {
        return source;
    }

    public String getChangedBy() {
        return changedBy;
    }

    public String getChangedBySubject() {
        return changedBySubject;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public ImportBatch getObservationBatch() {
        return observationBatch;
    }
}
