package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Welke revisie uit welke legacy-ontvangst ontstond (changeset 020-4 in {@code 021-legacy-config-definition-origin.sql};
 * {@code docs/design/legacy-config-bundle-design.md} par. 4 en 5; beslissingslog 2026-10-20 "Legacy-config S2").
 * <p>
 * Eén rij per revisie die APPLY aanmaakte, nooit bijgewerkt. {@code migratedContentHash} is de inhoud die de migratie
 * schreef (door legacy bepaalde revisievelden, alle mappings, alle filters); daarmee beslist APPLY of er niets
 * veranderde ({@code DEFINITION_UNCHANGED}) of dat er sindsdien handmatig gewijzigd werd
 * ({@code MANUAL_CHANGES_SINCE_MIGRATION}).
 */
@Entity
@Table(name = "legacy_config_revision_origin",
        uniqueConstraints = @UniqueConstraint(name = "uk_legacy_config_revision_origin_revision",
                columnNames = "revision_id"))
public class LegacyConfigRevisionOrigin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private Long revisionId;

    @Column(name = "receipt_id", nullable = false, updatable = false)
    private Long receiptId;

    @Column(name = "definition_content_hash", nullable = false, updatable = false, length = 64)
    private String definitionContentHash;

    @Column(name = "migrated_content_hash", nullable = false, updatable = false, length = 64)
    private String migratedContentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LegacyConfigRevisionOrigin() {
        // JPA
    }

    public LegacyConfigRevisionOrigin(Long revisionId, Long receiptId, String definitionContentHash,
                                      String migratedContentHash, Instant createdAt) {
        this.revisionId = required(revisionId, "revisionId");
        this.receiptId = required(receiptId, "receiptId");
        this.definitionContentHash = required(definitionContentHash, "definitionContentHash");
        this.migratedContentHash = required(migratedContentHash, "migratedContentHash");
        this.createdAt = required(createdAt, "createdAt");
    }

    public Long getId() {
        return id;
    }

    public Long getRevisionId() {
        return revisionId;
    }

    public Long getReceiptId() {
        return receiptId;
    }

    public String getDefinitionContentHash() {
        return definitionContentHash;
    }

    public String getMigratedContentHash() {
        return migratedContentHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "LegacyConfigRevisionOrigin[id=" + id + ", revision=" + revisionId + ", receipt=" + receiptId + "]";
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
