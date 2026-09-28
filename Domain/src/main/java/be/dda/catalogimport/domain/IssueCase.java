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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Eén behandelgeval: de observatie-eenheid van een terugkerend probleem binnen één koppeling
 * (docs/design/issue-case-design.md par. 1, spoor S2-0 uit docs/decisions.md 2026-09-27
 * "Heropening D14-vervolg"). De identiteit is {@code (importLink, issueCode, signature)} (D1) -
 * herhaling van hetzelfde probleem verhoogt de tellers van het bestaande geval, er ontstaat nooit
 * een tweede geval voor dezelfde identiteit (R-CASE-01).
 * <p>
 * {@code severity}/{@code issueDomain}/{@code controlLevel}/{@code impactScope}/{@code incidentKind}
 * zijn gedenormaliseerd bij aanmaak uit de eerste gekoppelde {@code import_issue_group}-rij en
 * worden daarna nooit bijgewerkt: de classificatie mag niet van de wijzigbare foutcodecatalogus
 * afhangen (zelfde motivatie als {@code import_issue_group} zelf, R-ISS-02).
 * <p>
 * De samengestelde overgangsmethode {@link #recordHumanDecision} (S2-B2, design par. 4) is de enige
 * weg waarlangs een mens de status wijzigt: status, reden, actor en {@code decisionRevision} worden
 * altijd samen gezet, nooit via losse setters — patroon {@code PublicationRun.markPreparing/
 * recordSimulated/recordFailed}. De systeemheropening ({@code reopenBySystem}, S2-B1b) schrijft
 * rechtstreeks via {@link be.dda.catalogimport.dao.IssueCaseDao} (set-based) en heeft daarom geen
 * eigen entiteitsmethode nodig.
 * <p>
 * <b>Belangrijk:</b> deze klasse kent {@code service.ActorIdentity} niet — Domain heeft geen
 * afhankelijkheid op Service. {@link #recordHumanDecision} neemt daarom de ontvlochten
 * {@code changedBy}/{@code changedBySubject} aan, net als de bestaande {@code PublicationRun}-
 * constructor met {@code requestedBy}/{@code requestedBySubject}; de Web/Service-laag ontvlecht de
 * {@code ActorIdentity} vóór de aanroep.
 */
@Entity
@Table(name = "issue_case",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_issue_case_identity",
                        columnNames = {"import_link_id", "issue_code", "signature"})
        })
public class IssueCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_link_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_issue_case_import_link"))
    private ImportLink importLink;

    @Column(name = "issue_code", nullable = false, length = 60)
    private String issueCode;

    @Column(name = "signature", nullable = false, length = 300)
    private String signature;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private RowIssueSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_domain", nullable = false, length = 40)
    private IssueDomain issueDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_level", nullable = false, length = 20)
    private ControlLevel controlLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "impact_scope", nullable = false, length = 20)
    private ImpactScope impactScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "incident_kind", nullable = false, length = 30)
    private IssueIncidentKind incidentKind;

    /** Enkel gevuld bij {@link IssueIncidentKind#PRICE}. */
    @Column(name = "price_component_code", length = 20)
    private String priceComponentCode;

    /** Enkel gevuld bij {@link IssueIncidentKind#IDENTITY}. */
    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private IssueCaseStatus status = IssueCaseStatus.AWAITING_REVIEW;

    /** Aantal gekoppelde {@code import_issue_group}-rijen; altijd herberekend, nooit {@code +1}. */
    @Column(name = "observation_count", nullable = false)
    private long observationCount;

    /** {@code sum(occurrence_count)} over de gekoppelde groepen; altijd herberekend. */
    @Column(name = "total_occurrence_count", nullable = false)
    private long totalOccurrenceCount;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "first_seen_batch_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_issue_case_first_batch"))
    private ImportBatch firstSeenBatch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_seen_batch_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_issue_case_last_batch"))
    private ImportBatch lastSeenBatch;

    /** De revisie waaronder de laatste waarneming gescreend is. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "last_seen_revision_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_issue_case_last_revision"))
    private ImportDefinitionRevision lastSeenRevision;

    /** Hoe vaak dit geval heropend is (handmatig of door het systeem). */
    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    /** {@code null} = nog nooit gewijzigd. */
    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    /** {@code null} bij een systeemheropening. */
    @Column(name = "status_changed_by", length = 100)
    private String statusChangedBy;

    /** OIDC-{@code sub} van de laatste menselijke beslisser (patroon changeset 007). */
    @Column(name = "status_changed_by_subject", length = 255)
    private String statusChangedBySubject;

    /**
     * De revisie waaronder de laatste menselijke beslissing genomen is; input van de
     * heropeningsregel voor {@link IssueCaseStatus#REJECTED} (R-CASE-02/R-CASE-03).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decision_revision_id",
            foreignKey = @ForeignKey(name = "fk_issue_case_decision_revision"))
    private ImportDefinitionRevision decisionRevision;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IssueCase() {
        // JPA
    }

    /**
     * Nieuw behandelgeval, altijd in status {@link IssueCaseStatus#AWAITING_REVIEW} (design par. 1/4).
     * De classificatievelden worden hier gedenormaliseerd uit de eerste gekoppelde groep en nooit meer
     * bijgewerkt.
     */
    public IssueCase(ImportLink importLink, String issueCode, String signature, RowIssueSeverity severity,
                     IssueDomain issueDomain, ControlLevel controlLevel, ImpactScope impactScope,
                     IssueIncidentKind incidentKind, String priceComponentCode, String referenceType,
                     long observationCount, long totalOccurrenceCount, Instant firstSeenAt, Instant lastSeenAt,
                     ImportBatch firstSeenBatch, ImportBatch lastSeenBatch, ImportDefinitionRevision lastSeenRevision,
                     Instant createdAt) {
        this.importLink = importLink;
        this.issueCode = issueCode;
        this.signature = signature;
        this.severity = severity;
        this.issueDomain = issueDomain;
        this.controlLevel = controlLevel;
        this.impactScope = impactScope;
        this.incidentKind = incidentKind;
        this.priceComponentCode = priceComponentCode;
        this.referenceType = referenceType;
        this.status = IssueCaseStatus.AWAITING_REVIEW;
        this.observationCount = observationCount;
        this.totalOccurrenceCount = totalOccurrenceCount;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.firstSeenBatch = firstSeenBatch;
        this.lastSeenBatch = lastSeenBatch;
        this.lastSeenRevision = lastSeenRevision;
        this.reopenCount = 0;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * De toegestane overgangen door een mens (design par. 4): initiële beoordeling vanuit
     * {@code AWAITING_REVIEW}, en handmatig heropenen vanuit elk van de drie andere statussen. Een
     * herziening tussen {@code CORRECTED} en {@code REJECTED} loopt hier bewust niet rechtstreeks
     * langs — dat moet altijd eerst expliciet heropend worden, zodat de heropeningsreden apart
     * geregistreerd wordt. Dezelfde status naar dezelfde status en elke overgang naar
     * {@code AUTO_RESOLVED} zijn hier evenmin toegestaan (A1: die waarde wordt door geen enkel
     * codepad gezet).
     */
    private static final Map<IssueCaseStatus, Set<IssueCaseStatus>> ALLOWED_HUMAN_TRANSITIONS = allowedHumanTransitions();

    private static Map<IssueCaseStatus, Set<IssueCaseStatus>> allowedHumanTransitions() {
        Map<IssueCaseStatus, Set<IssueCaseStatus>> transitions = new EnumMap<>(IssueCaseStatus.class);
        transitions.put(IssueCaseStatus.AWAITING_REVIEW,
                EnumSet.of(IssueCaseStatus.CORRECTED, IssueCaseStatus.REJECTED));
        transitions.put(IssueCaseStatus.CORRECTED, EnumSet.of(IssueCaseStatus.AWAITING_REVIEW));
        transitions.put(IssueCaseStatus.REJECTED, EnumSet.of(IssueCaseStatus.AWAITING_REVIEW));
        transitions.put(IssueCaseStatus.AUTO_RESOLVED, EnumSet.of(IssueCaseStatus.AWAITING_REVIEW));
        return Map.copyOf(transitions);
    }

    /**
     * De samengestelde overgangsmethode voor een menselijke beslissing (S2-B2, design par. 4): status,
     * reden, actor en {@code decisionRevision} worden altijd samen gezet. Geen losse setters — patroon
     * {@code PublicationRun.markPreparing/recordSimulated/recordFailed}.
     * <p>
     * {@code decisionRevision} wordt bij <b>elke</b> menselijke beslissing bijgewerkt naar de actieve
     * revisie van de betrokken koppeling (design par. 1): dat is de input van de heropeningsregel voor
     * een latere {@code REJECTED}-waarneming (R-CASE-02/R-CASE-03).
     * <p>
     * {@code reopenCount} telt op wanneer deze beslissing zelf een heropening is (elke overgang terug
     * naar {@code AWAITING_REVIEW} vanuit een van de drie andere statussen) — dezelfde generieke
     * betekenis ("hoe vaak heropend", design par. 1) als bij een systeemheropening. De eerste
     * beoordeling ({@code AWAITING_REVIEW → CORRECTED}/{@code REJECTED}) is geen heropening en telt
     * niet mee.
     *
     * @throws IllegalStateException {@code newStatus} is vanuit de huidige status niet toegestaan voor
     *                                een mens (zie {@link #ALLOWED_HUMAN_TRANSITIONS}); de Service-laag
     *                                vertaalt dit naar 409 {@code ISSUE_CASE_TRANSITION_NOT_ALLOWED}
     */
    public void recordHumanDecision(IssueCaseStatus newStatus, String reason, String changedBy,
                                    String changedBySubject, ImportDefinitionRevision decisionRevision,
                                    Instant at) {
        Set<IssueCaseStatus> allowed = ALLOWED_HUMAN_TRANSITIONS.get(this.status);
        if (allowed == null || !allowed.contains(newStatus)) {
            throw new IllegalStateException("Issue case " + this.id + " cannot move from " + this.status
                    + " to " + newStatus + " by a human decision");
        }
        boolean reopening = this.status != IssueCaseStatus.AWAITING_REVIEW
                && newStatus == IssueCaseStatus.AWAITING_REVIEW;
        this.status = newStatus;
        this.statusReason = reason;
        this.statusChangedAt = at;
        this.statusChangedBy = changedBy;
        this.statusChangedBySubject = changedBySubject;
        this.decisionRevision = decisionRevision;
        this.updatedAt = at;
        if (reopening) {
            this.reopenCount = this.reopenCount + 1;
        }
    }

    public Long getId() {
        return id;
    }

    public ImportLink getImportLink() {
        return importLink;
    }

    public String getIssueCode() {
        return issueCode;
    }

    public String getSignature() {
        return signature;
    }

    public RowIssueSeverity getSeverity() {
        return severity;
    }

    public IssueDomain getIssueDomain() {
        return issueDomain;
    }

    public ControlLevel getControlLevel() {
        return controlLevel;
    }

    public ImpactScope getImpactScope() {
        return impactScope;
    }

    public IssueIncidentKind getIncidentKind() {
        return incidentKind;
    }

    public String getPriceComponentCode() {
        return priceComponentCode;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public IssueCaseStatus getStatus() {
        return status;
    }

    public long getObservationCount() {
        return observationCount;
    }

    public long getTotalOccurrenceCount() {
        return totalOccurrenceCount;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public ImportBatch getFirstSeenBatch() {
        return firstSeenBatch;
    }

    public ImportBatch getLastSeenBatch() {
        return lastSeenBatch;
    }

    public ImportDefinitionRevision getLastSeenRevision() {
        return lastSeenRevision;
    }

    public int getReopenCount() {
        return reopenCount;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public String getStatusChangedBy() {
        return statusChangedBy;
    }

    public String getStatusChangedBySubject() {
        return statusChangedBySubject;
    }

    public ImportDefinitionRevision getDecisionRevision() {
        return decisionRevision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
