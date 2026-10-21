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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Eén regel van de vertaaltabel van een {@link ImportFieldMapping} met {@link FieldTransformKind#MAP}
 * (legacy-config S8a, changeset 023-1; {@code docs/design/legacy-config-s8-design.md} par. 2a): bronwaarde →
 * doelwaarde, zoals legacy PSIMPDEFMAP KEY → VALUE (DS_0254).
 * <p>
 * <b>Naast {@code values=}, niet in plaats ervan.</b> Een MAP-mapping draagt haar tabel óf in
 * {@code transform_config} ({@code values=bron>doel|...}, ongewijzigd ondersteund) óf in deze rijen; beide, of geen van
 * beide, is een configuratiefout ({@code CONFIG_TRANSFORM_INVALID}). De hoofdlettergevoeligheid blijft de instelling
 * {@code caseSensitive} van de mapping.
 * <p>
 * <b>Hard in de database:</b> bronwaarde niet blanco, hoogstens 50 tekens (doelwaarde idem, {@code ''} toegelaten);
 * uniek per (mapping, bronwaarde) en per (mapping, volgnummer); een subject nooit zonder naam
 * ({@code ck_import_field_value_map_created_subject}, zoals 007-4).
 */
@Entity
@Table(name = "import_field_value_map",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_import_field_value_map_source",
                        columnNames = {"mapping_id", "source_value"}),
                @UniqueConstraint(name = "uk_import_field_value_map_sequence",
                        columnNames = {"mapping_id", "sequence_number"})
        })
public class ImportFieldValueMap {

    /** Legacy PSIMPDEFMAP KEY/VALUE zijn 50 tekens (DS_0254); dezelfde grens als de kolommen. */
    public static final int MAX_VALUE_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_field_value_map_mapping"))
    private ImportFieldMapping mapping;

    /** Vaste volgorde binnen de mapping (1-gebaseerd); bij een migratie de legacy-volgorde. */
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "source_value", nullable = false, length = MAX_VALUE_LENGTH)
    private String sourceValue;

    /** De doelwaarde; {@code ""} betekent "vertaalt naar leeg" en is iets anders dan een ontbrekende rij. */
    @Column(name = "target_value", nullable = false, length = MAX_VALUE_LENGTH)
    private String targetValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    /** OIDC-subject van wie de rij vastlegde; {@code null} = geen geverifieerde identiteit (zoals 007-4). */
    @Column(name = "created_by_subject", length = 255)
    private String createdBySubject;

    protected ImportFieldValueMap() {
        // JPA
    }

    public ImportFieldValueMap(ImportFieldMapping mapping, int sequenceNumber, String sourceValue,
                               String targetValue) {
        this.mapping = mapping;
        this.sequenceNumber = sequenceNumber;
        this.sourceValue = sourceValue;
        this.targetValue = targetValue;
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

    public ImportFieldMapping getMapping() {
        return mapping;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public String getSourceValue() {
        return sourceValue;
    }

    public String getTargetValue() {
        return targetValue;
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

    public String getCreatedBySubject() {
        return createdBySubject;
    }

    /** Zet altijd samen met {@link #setCreatedBy(String)}; {@code null} = niet geverifieerd. */
    public void setCreatedBySubject(String createdBySubject) {
        this.createdBySubject = createdBySubject;
    }
}
