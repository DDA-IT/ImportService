package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Het lidmaatschap van één {@link ImportBatch} in één {@link PublicationBundle} (ontwerp fase 4 par. 2,
 * R-BND). Koppeling op BATCHNIVEAU, niet op mutatieniveau.
 * <p>
 * <b>Hoogstens één actief lidmaatschap per batch, over alle bundels heen.</b> {@code activeMarker} is
 * {@code TRUE} zolang het lidmaatschap niet verwijderd is en {@code null} daarna. Samen met
 * {@code uk_publication_bundle_batch_active} dwingt dat op databaseniveau af dat een batch nooit in
 * twee bundels tegelijk actief lid is (zelfde patroon als {@link ImportBatch#getOpenMarker()} en
 * {@link ImportDefinitionRevision#getActiveMarker()}).
 */
@Entity
@Table(name = "publication_bundle_batch",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_publication_bundle_batch_bundle", columnNames = {"bundle_id", "batch_id"}),
                @UniqueConstraint(name = "uk_publication_bundle_batch_active", columnNames = {"batch_id", "active_marker"})
        })
public class PublicationBundleBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bundle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_publication_bundle_batch_bundle"))
    private PublicationBundle bundle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_publication_bundle_batch_batch"))
    private ImportBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_link_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_publication_bundle_batch_link"))
    private ImportLink importLink;

    @Column(name = "added_by", nullable = false, length = 100)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    @Column(name = "removed_by", length = 100)
    private String removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "removed_reason", length = 500)
    private String removedReason;

    /**
     * Technische marker die "actief lidmaatschap" op databaseniveau uniek houdt per batch. {@code TRUE}
     * zolang niet verwijderd, anders {@code null}. Wordt automatisch afgeleid uit de removed-velden;
     * niet los te zetten.
     */
    @Column(name = "active_marker")
    private Boolean activeMarker;

    protected PublicationBundleBatch() {
        // JPA
    }

    public PublicationBundleBatch(PublicationBundle bundle, ImportBatch batch, ImportLink importLink,
                                  String addedBy) {
        this.bundle = bundle;
        this.batch = batch;
        this.importLink = importLink;
        this.addedBy = addedBy;
    }

    @PrePersist
    void onPersist() {
        if (addedAt == null) {
            addedAt = Instant.now();
        }
        syncActiveMarker();
    }

    @PreUpdate
    void onUpdate() {
        syncActiveMarker();
    }

    private void syncActiveMarker() {
        activeMarker = removedAt == null ? Boolean.TRUE : null;
    }

    public Long getId() {
        return id;
    }

    public PublicationBundle getBundle() {
        return bundle;
    }

    public ImportBatch getBatch() {
        return batch;
    }

    public ImportLink getImportLink() {
        return importLink;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public Instant getAddedAt() {
        return addedAt;
    }

    public String getRemovedBy() {
        return removedBy;
    }

    public Instant getRemovedAt() {
        return removedAt;
    }

    public String getRemovedReason() {
        return removedReason;
    }

    public Boolean getActiveMarker() {
        return activeMarker;
    }

    /** Legt de drie auditgegevens van een verwijdering samen vast; nooit los te zetten. */
    public void recordRemoval(String removedBy, Instant removedAt, String removedReason) {
        this.removedBy = removedBy;
        this.removedAt = removedAt;
        this.removedReason = removedReason;
        syncActiveMarker();
    }
}
