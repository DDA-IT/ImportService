package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * De eenheid van goedkeuring, bevriezing en publicatie over meerdere {@link ImportBatch}'es heen
 * (ontwerp fase 4, docs/design/fase4-publication-bundle-design.md par. 2, R-BND). Een afzonderlijke
 * importkoppeling publiceert nooit zelfstandig.
 * <p>
 * <b>Lidmaatschap.</b> Op BATCHNIVEAU via {@link PublicationBundleBatch}, bewust géén
 * {@code publication_bundle_id} op {@link ImportMutation} — zie het ontwerp par. 2 voor de motivering.
 * <p>
 * <b>Tellers</b> zijn nullable: {@code null} betekent "niet vastgesteld" (bundel nog niet bevroren),
 * nooit stil 0. Ze worden pas bij bevriezen (bouwstap 4e) berekend en bewaard.
 * <p>
 * <b>Idempotentie.</b> {@code uk_publication_bundle_idempotency} maakt {@code idempotencyKey} uniek:
 * {@code 'bundle:' || bundleReference}.
 */
@Entity
@Table(name = "publication_bundle",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_publication_bundle_reference", columnNames = "bundle_reference"),
                @UniqueConstraint(name = "uk_publication_bundle_idempotency", columnNames = "idempotency_key")
        })
public class PublicationBundle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "bundle_reference", nullable = false, length = 100)
    private String bundleReference;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PublicationBundleStatus status = PublicationBundleStatus.ASSEMBLING;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_mode", nullable = false, length = 30)
    private PublicationTargetMode targetMode;

    @Column(name = "target_moment")
    private Instant targetMoment;

    @Column(name = "publication_policy", length = 1000)
    private String publicationPolicy;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "frozen_by", length = 100)
    private String frozenBy;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "frozen_reason", length = 500)
    private String frozenReason;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_reason", length = 500)
    private String cancelledReason;

    /** Volledige bundelhash, enkel gevuld zodra {@link #status} {@code FROZEN} is (ck_publication_bundle_frozen). */
    @Column(name = "content_hash")
    private byte[] contentHash;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "batch_count")
    private Long batchCount;

    @Column(name = "content_mutation_count")
    private Long contentMutationCount;

    @Column(name = "ready_count")
    private Long readyCount;

    @Column(name = "rejected_count")
    private Long rejectedCount;

    @Column(name = "blocked_count")
    private Long blockedCount;

    @Column(name = "expired_count")
    private Long expiredCount;

    @Column(name = "identity_incident_count")
    private Long identityIncidentCount;

    @Column(name = "bulk_incident_count")
    private Long bulkIncidentCount;

    @Column(name = "critical_issue_count")
    private Long criticalIssueCount;

    @Column(name = "warning_count")
    private Long warningCount;

    protected PublicationBundle() {
        // JPA
    }

    public PublicationBundle(String bundleReference, PublicationTargetMode targetMode, String createdBy) {
        this.bundleReference = bundleReference;
        this.targetMode = targetMode;
        this.createdBy = createdBy;
        this.idempotencyKey = "bundle:" + bundleReference;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getBundleReference() {
        return bundleReference;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PublicationBundleStatus getStatus() {
        return status;
    }

    public void setStatus(PublicationBundleStatus status) {
        this.status = status;
    }

    public PublicationTargetMode getTargetMode() {
        return targetMode;
    }

    public Instant getTargetMoment() {
        return targetMoment;
    }

    public void setTargetMoment(Instant targetMoment) {
        this.targetMoment = targetMoment;
    }

    public String getPublicationPolicy() {
        return publicationPolicy;
    }

    public void setPublicationPolicy(String publicationPolicy) {
        this.publicationPolicy = publicationPolicy;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getFrozenBy() {
        return frozenBy;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public String getFrozenReason() {
        return frozenReason;
    }

    /** Legt de drie auditgegevens van een bevriezing samen vast; nooit los te zetten. */
    public void recordFreeze(String frozenBy, Instant frozenAt, String frozenReason, byte[] contentHash) {
        this.frozenBy = frozenBy;
        this.frozenAt = frozenAt;
        this.frozenReason = frozenReason;
        this.contentHash = contentHash;
        this.status = PublicationBundleStatus.FROZEN;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelledReason() {
        return cancelledReason;
    }

    /** Legt de drie auditgegevens van een annulering samen vast; nooit los te zetten. */
    public void recordCancellation(String cancelledBy, Instant cancelledAt, String cancelledReason) {
        this.cancelledBy = cancelledBy;
        this.cancelledAt = cancelledAt;
        this.cancelledReason = cancelledReason;
        this.status = PublicationBundleStatus.CANCELLED;
    }

    public byte[] getContentHash() {
        return contentHash;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getBatchCount() {
        return batchCount;
    }

    public void setBatchCount(Long batchCount) {
        this.batchCount = batchCount;
    }

    public Long getContentMutationCount() {
        return contentMutationCount;
    }

    public void setContentMutationCount(Long contentMutationCount) {
        this.contentMutationCount = contentMutationCount;
    }

    public Long getReadyCount() {
        return readyCount;
    }

    public void setReadyCount(Long readyCount) {
        this.readyCount = readyCount;
    }

    public Long getRejectedCount() {
        return rejectedCount;
    }

    public void setRejectedCount(Long rejectedCount) {
        this.rejectedCount = rejectedCount;
    }

    public Long getBlockedCount() {
        return blockedCount;
    }

    public void setBlockedCount(Long blockedCount) {
        this.blockedCount = blockedCount;
    }

    public Long getExpiredCount() {
        return expiredCount;
    }

    public void setExpiredCount(Long expiredCount) {
        this.expiredCount = expiredCount;
    }

    public Long getIdentityIncidentCount() {
        return identityIncidentCount;
    }

    public void setIdentityIncidentCount(Long identityIncidentCount) {
        this.identityIncidentCount = identityIncidentCount;
    }

    public Long getBulkIncidentCount() {
        return bulkIncidentCount;
    }

    public void setBulkIncidentCount(Long bulkIncidentCount) {
        this.bulkIncidentCount = bulkIncidentCount;
    }

    public Long getCriticalIssueCount() {
        return criticalIssueCount;
    }

    public void setCriticalIssueCount(Long criticalIssueCount) {
        this.criticalIssueCount = criticalIssueCount;
    }

    public Long getWarningCount() {
        return warningCount;
    }

    public void setWarningCount(Long warningCount) {
        this.warningCount = warningCount;
    }
}
