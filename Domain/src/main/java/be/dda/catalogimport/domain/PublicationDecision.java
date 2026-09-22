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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Eén append-only beslissingsregel binnen een {@link PublicationBundle} (ontwerp fase 4 par. 2, R-DEC;
 * retentie 7 jaar). Wordt nooit bijgewerkt of verwijderd: een herziening schrijft een nieuwe rij, de
 * oude blijft staan.
 * <p>
 * {@code mutation_id} is enkel gevuld bij {@link BundleDecisionScope#MUTATION} (dan is
 * {@code affected_count} altijd 1); bij {@link BundleDecisionScope#GROUP} of {@link BundleDecisionScope#BUNDLE}
 * blijft ze leeg en telt {@code affected_count} het werkelijke aantal geraakte mutaties.
 */
@Entity
@Table(name = "publication_decision")
public class PublicationDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bundle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_publication_decision_bundle"))
    private PublicationBundle bundle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mutation_id",
            foreignKey = @ForeignKey(name = "fk_publication_decision_mutation"))
    private ImportMutation mutation;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_kind", nullable = false, length = 30)
    private BundleDecisionKind decisionKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_scope", nullable = false, length = 20)
    private BundleDecisionScope decisionScope;

    @Column(name = "selection_filter", length = 500)
    private String selectionFilter;

    @Column(name = "previous_status", length = 40)
    private String previousStatus;

    @Column(name = "new_status", length = 40)
    private String newStatus;

    @Column(name = "affected_count", nullable = false)
    private long affectedCount = 1;

    @Column(name = "decided_by", nullable = false, length = 100)
    private String decidedBy;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    protected PublicationDecision() {
        // JPA
    }

    public PublicationDecision(PublicationBundle bundle, ImportMutation mutation, BundleDecisionKind decisionKind,
                               BundleDecisionScope decisionScope, long affectedCount, String decidedBy,
                               String reason) {
        this.bundle = bundle;
        this.mutation = mutation;
        this.decisionKind = decisionKind;
        this.decisionScope = decisionScope;
        this.affectedCount = affectedCount;
        this.decidedBy = decidedBy;
        this.reason = reason;
    }

    @PrePersist
    void onPersist() {
        if (decidedAt == null) {
            decidedAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public PublicationBundle getBundle() {
        return bundle;
    }

    public ImportMutation getMutation() {
        return mutation;
    }

    public BundleDecisionKind getDecisionKind() {
        return decisionKind;
    }

    public BundleDecisionScope getDecisionScope() {
        return decisionScope;
    }

    public String getSelectionFilter() {
        return selectionFilter;
    }

    public void setSelectionFilter(String selectionFilter) {
        this.selectionFilter = selectionFilter;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(String previousStatus) {
        this.previousStatus = previousStatus;
    }

    public String getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(String newStatus) {
        this.newStatus = newStatus;
    }

    public long getAffectedCount() {
        return affectedCount;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getReason() {
        return reason;
    }
}
