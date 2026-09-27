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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Snapshot van één te publiceren mutatie, bewaard bij het bevriezen van een {@link PublicationBundle}
 * (ontwerp fase 5-PUB par. 1, changeset 008-1). Enkel de velden die niet op {@link ImportMutation}
 * staan; basisprijs, valuta, identiteit en referenties worden bewust niet gedupliceerd.
 */
@Entity
@Table(name = "publication_bundle_snapshot",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_publication_bundle_snapshot_mutation", columnNames = "mutation_id"),
                @UniqueConstraint(name = "uk_publication_bundle_snapshot_bundle_mutation",
                        columnNames = {"bundle_id", "mutation_id"})
        })
public class PublicationBundleSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bundle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_publication_bundle_snapshot_bundle"))
    private PublicationBundle bundle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mutation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_publication_bundle_snapshot_mutation"))
    private ImportMutation mutation;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "import_link_id", nullable = false)
    private Long importLinkId;

    @Column(name = "source_row_number", nullable = false)
    private Long sourceRowNumber;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "description_state", nullable = false, length = 20)
    private SnapshotDescriptionState descriptionState;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PublicationBundleSnapshot() {
        // JPA
    }

    public PublicationBundleSnapshot(PublicationBundle bundle, ImportMutation mutation, Long batchId,
                                     Long importLinkId, Long sourceRowNumber, String description,
                                     SnapshotDescriptionState descriptionState) {
        this.bundle = bundle;
        this.mutation = mutation;
        this.batchId = batchId;
        this.importLinkId = importLinkId;
        this.sourceRowNumber = sourceRowNumber;
        this.description = description;
        this.descriptionState = descriptionState;
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

    public PublicationBundle getBundle() {
        return bundle;
    }

    public ImportMutation getMutation() {
        return mutation;
    }

    public Long getBatchId() {
        return batchId;
    }

    public Long getImportLinkId() {
        return importLinkId;
    }

    public Long getSourceRowNumber() {
        return sourceRowNumber;
    }

    public String getDescription() {
        return description;
    }

    public SnapshotDescriptionState getDescriptionState() {
        return descriptionState;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
