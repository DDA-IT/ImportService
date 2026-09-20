package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * De kritiek-vlag van één revisie-eigen veld (ontwerp fase 3, par. 15.1, changeset 004-15).
 * <p>
 * Leverancier, groep, referentie, kortingscode, basisprijs, munt en omschrijving staan op de
 * {@link ImportDefinitionRevision} zelf en hebben geen {@link ImportFieldMapping}-rij. Een rij hier is
 * een <b>overrule van de standaard van de soort</b> ({@link RevisionCriticalityField}); zonder rij
 * geldt die standaard. Identiteitsvelden kunnen nooit {@link Criticality#NON_CRITICAL} zijn: dat
 * bewaakt de databasecheck {@code ck_import_revision_field_criticality_identity}, en de
 * configuratievalidatie doet het nog eens over.
 * <p>
 * De sleutel is {@code (definitionRevisionId, fieldKey)}. De sleutel is bewust een gewone
 * {@code String} en geen enum: een onbekende sleutel moet als waarde bestaan kunnen om door de check
 * en door de validatie geweigerd te worden, in plaats van al bij het lezen van de rij te crashen.
 */
@Entity
@Table(name = "import_revision_field_criticality")
@IdClass(ImportRevisionFieldCriticality.Key.class)
public class ImportRevisionFieldCriticality {

    /** Samengestelde sleutel: revisie + veldsleutel. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long definitionRevisionId;
        private String fieldKey;

        public Key() {
            // JPA
        }

        public Key(Long definitionRevisionId, String fieldKey) {
            this.definitionRevisionId = definitionRevisionId;
            this.fieldKey = fieldKey;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(definitionRevisionId, key.definitionRevisionId)
                    && Objects.equals(fieldKey, key.fieldKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(definitionRevisionId, fieldKey);
        }
    }

    @Id
    @Column(name = "definition_revision_id", nullable = false)
    private Long definitionRevisionId;

    @Id
    @Column(name = "field_key", nullable = false, length = 60)
    private String fieldKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "criticality", nullable = false, length = 20)
    private Criticality criticality;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    protected ImportRevisionFieldCriticality() {
        // JPA
    }

    public ImportRevisionFieldCriticality(Long definitionRevisionId, String fieldKey, Criticality criticality) {
        this.definitionRevisionId = definitionRevisionId;
        this.fieldKey = fieldKey;
        this.criticality = criticality;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getDefinitionRevisionId() {
        return definitionRevisionId;
    }

    public String getFieldKey() {
        return fieldKey;
    }

    public Criticality getCriticality() {
        return criticality;
    }

    public void setCriticality(Criticality criticality) {
        this.criticality = criticality;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
