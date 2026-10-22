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

/**
 * Eén te publiceren mutatie binnen een PRODUCTION-{@link PublicationRun} (changeset 027-1;
 * docs/design/publicatie-prodis-db-direct-design.md par. 2.4). Bewaart de idempotentiesleutel, het exacte verzoek
 * ({@code requestJson} + {@code requestHash}) en het resultaat; de Prodis-id's staan er bewust niet in.
 * <p>
 * De overgangen (P5b) zijn samengestelde methoden, zodat status, tijdstippen en tellers nooit los van elkaar gezet
 * worden; elke methode weigert een overgang die de levenscyclus (design par. 2.4) niet kent met
 * {@link IllegalStateException}:
 * <ul>
 *   <li>{@link #markSent}: {@code PENDING | SENT | RESULT_UNKNOWN → SENT} ({@code send_count + 1}); opnieuw versturen van
 *       {@code SENT}/{@code RESULT_UNKNOWN} gebeurt met dezelfde sleutel en dezelfde bytes (reconcile-by-state).</li>
 *   <li>{@link #recordAnswer}: {@code SENT → APPLIED | UNCHANGED | REJECTED | CONFLICT}.</li>
 *   <li>{@link #recordNotApplied}: {@code SENT → PENDING} met {@code last_error_code} (zeker niets toegepast).</li>
 *   <li>{@link #recordResultUnknown}: {@code SENT → RESULT_UNKNOWN} met {@code last_error_code}.</li>
 *   <li>{@link #markNotSent}: een nooit verstuurd {@code PENDING}-item → {@code NOT_SENT} (bv. CREATE in v1).</li>
 * </ul>
 */
@Entity
@Table(name = "publication_run_item",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_publication_run_item_idempotency", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uk_publication_run_item_run_mutation",
                        columnNames = {"run_id", "mutation_id"}),
                @UniqueConstraint(name = "uk_publication_run_item_mutation_attempt",
                        columnNames = {"mutation_id", "publication_attempt"})
        })
public class PublicationRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, foreignKey = @ForeignKey(name = "fk_publication_run_item_run"))
    private PublicationRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mutation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_publication_run_item_mutation"))
    private ImportMutation mutation;

    @Column(name = "publication_attempt", nullable = false)
    private int publicationAttempt;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    /** Hex-SHA-256 van {@code requestJson}. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "request_json", nullable = false, columnDefinition = "text")
    private String requestJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PublicationRunItemStatus status = PublicationRunItemStatus.PENDING;

    @Column(name = "outcome", length = 30)
    private String outcome;

    @Column(name = "reason_code", length = 60)
    private String reasonCode;

    @Column(name = "reason_detail", length = 1000)
    private String reasonDetail;

    @Column(name = "prior_json", columnDefinition = "text")
    private String priorJson;

    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    @Column(name = "warnings_json", columnDefinition = "text")
    private String warningsJson;

    /** De {@code legacy_master}-modus waaronder dit item verstuurd werd (per chunk); {@code null} = niet vastgesteld. */
    @Column(name = "legacy_master", length = 30)
    private String legacyMaster;

    @Column(name = "last_error_code", length = 60)
    private String lastErrorCode;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "sent_by", length = 100)
    private String sentBy;

    /** OIDC-subject van de verzender (changeset 027-1); {@code null} = geen geverifieerde identiteit. */
    @Column(name = "sent_by_subject", length = 255)
    private String sentBySubject;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "send_count", nullable = false)
    private int sendCount;

    protected PublicationRunItem() {
        // JPA
    }

    /** Nieuw item: status {@code PENDING}, nog nooit verstuurd ({@code sendCount} 0). */
    public PublicationRunItem(PublicationRun run, ImportMutation mutation, int publicationAttempt,
                              String idempotencyKey, String requestHash, String requestJson) {
        this.run = run;
        this.mutation = mutation;
        this.publicationAttempt = publicationAttempt;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.requestJson = requestJson;
        this.status = PublicationRunItemStatus.PENDING;
        this.sendCount = 0;
    }

    // --- overgangen (P5b) ----------------------------------------------------------------------------------------

    /**
     * Het item gaat (opnieuw) naar Prodis: {@code PENDING}, {@code SENT} (herstart na een crash) of {@code RESULT_UNKNOWN}
     * → {@code SENT}, {@code send_count + 1}.
     *
     * @param legacyMaster de modus van Prodis waaronder verstuurd wordt; {@code null} = niet vastgesteld
     */
    public void markSent(Instant at, String by, String bySubject, String legacyMaster) {
        if (status != PublicationRunItemStatus.PENDING && status != PublicationRunItemStatus.SENT
                && status != PublicationRunItemStatus.RESULT_UNKNOWN) {
            throw new IllegalStateException("Item " + id + " cannot be sent from status " + status);
        }
        if (at == null || by == null) {
            throw new IllegalArgumentException("sentAt and sentBy are required");
        }
        this.status = PublicationRunItemStatus.SENT;
        this.sendCount = sendCount + 1;
        this.sentAt = at;
        this.sentBy = by;
        this.sentBySubject = bySubject;
        this.legacyMaster = legacyMaster;
    }

    /**
     * Het definitieve antwoord: {@code SENT → APPLIED | UNCHANGED | REJECTED | CONFLICT} (status = uitkomst).
     *
     * @param reasonCode verplicht bij REJECTED/CONFLICT
     */
    public void recordAnswer(PublicationRunItemStatus answer, Instant answeredAt, String reasonCode, String reasonDetail,
                             String priorJson, String resultJson, String warningsJson) {
        if (status != PublicationRunItemStatus.SENT) {
            throw new IllegalStateException("Item " + id + " cannot be answered from status " + status);
        }
        if (answer == null || !answer.isAnswered() || answeredAt == null) {
            throw new IllegalArgumentException("A definitive answer and its time are required, not " + answer);
        }
        if ((answer == PublicationRunItemStatus.REJECTED || answer == PublicationRunItemStatus.CONFLICT)
                && reasonCode == null) {
            throw new IllegalArgumentException(answer + " requires a reason code");
        }
        this.status = answer;
        this.outcome = answer.name();
        this.answeredAt = answeredAt;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
        this.priorJson = priorJson;
        this.resultJson = resultJson;
        this.warningsJson = warningsJson;
    }

    /** Zeker niets toegepast (fout vóór de Prodis-commit): {@code SENT → PENDING}, tijdstip en teller blijven. */
    public void recordNotApplied(String errorCode) {
        requireSent("fall back to PENDING");
        this.status = PublicationRunItemStatus.PENDING;
        this.lastErrorCode = requireErrorCode(errorCode);
    }

    /** Uitkomst onbekend (fout tijdens de Prodis-commit of daarna): {@code SENT → RESULT_UNKNOWN}. */
    public void recordResultUnknown(String errorCode) {
        requireSent("become RESULT_UNKNOWN");
        this.status = PublicationRunItemStatus.RESULT_UNKNOWN;
        this.lastErrorCode = requireErrorCode(errorCode);
    }

    /** Een nooit verstuurd {@code PENDING}-item wordt in deze run niet verstuurd (bv. CREATE in v1). */
    public void markNotSent(String reasonCode, String reasonDetail, String warningsJson, String legacyMaster) {
        if (status != PublicationRunItemStatus.PENDING || sendCount != 0 || sentAt != null) {
            throw new IllegalStateException("Item " + id + " cannot become NOT_SENT from status " + status
                    + " (send count " + sendCount + ")");
        }
        this.status = PublicationRunItemStatus.NOT_SENT;
        this.reasonCode = reasonCode;
        this.reasonDetail = reasonDetail;
        this.warningsJson = warningsJson;
        this.legacyMaster = legacyMaster;
    }

    private void requireSent(String action) {
        if (status != PublicationRunItemStatus.SENT) {
            throw new IllegalStateException("Item " + id + " cannot " + action + " from status " + status);
        }
    }

    private static String requireErrorCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank() || errorCode.length() > 60) {
            throw new IllegalArgumentException("An error code of 1..60 characters is required");
        }
        return errorCode;
    }

    public Long getId() {
        return id;
    }

    public PublicationRun getRun() {
        return run;
    }

    public ImportMutation getMutation() {
        return mutation;
    }

    public int getPublicationAttempt() {
        return publicationAttempt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public String getRequestJson() {
        return requestJson;
    }

    public PublicationRunItemStatus getStatus() {
        return status;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getReasonDetail() {
        return reasonDetail;
    }

    public String getPriorJson() {
        return priorJson;
    }

    public String getResultJson() {
        return resultJson;
    }

    public String getWarningsJson() {
        return warningsJson;
    }

    public String getLegacyMaster() {
        return legacyMaster;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getSentBy() {
        return sentBy;
    }

    public String getSentBySubject() {
        return sentBySubject;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public int getSendCount() {
        return sendCount;
    }
}
