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
 * Eén stap van de transformatieketen van een {@link ImportFieldMapping} met {@link FieldTransformKind#CHAIN}
 * (legacy-config S8c, changeset 024-1; {@code docs/design/legacy-config-s8-design.md} par. 2c): legacy past per regel
 * een vaste pijplijn toe (MID → Trim → prefix/suffix → hoogstens één CALC), deze rijen leggen die pijplijn vast.
 * <p>
 * <b>Gesloten grammatica, geen expressietaal (Q9).</b> De volgorde is altijd {@code [SUBSTRING] → [AFFIX|PREFIX|SUFFIX]
 * → [UPPER|MAP|ADD|SUBTRACT|MULTIPLY|DIVIDE|PERCENTAGE|SPLIT]}, 1-3 stappen; de configuratievalidatie
 * ({@code CONFIG_TRANSFORM_INVALID}) bewaakt dat. De database bewaakt wat altijd waar moet zijn: hoogstens drie stappen
 * met een uniek nummer per mapping, en nooit een keten als stap.
 * <p>
 * De vertaalrijen van een MAP-stap blijven aan de mapping hangen ({@link ImportFieldValueMap}); de notatie
 * ({@code decimalSeparator}, ...) blijft in {@code transform_config} van de mapping.
 */
@Entity
@Table(name = "import_field_transform_step",
        uniqueConstraints = @UniqueConstraint(name = "uk_import_field_transform_step_number",
                columnNames = {"mapping_id", "step_number"}))
public class ImportFieldTransformStep {

    /** Hoogstens drie stappen: substring, prefix/suffix, één waardebewerking ({@code ck_..._number}). */
    public static final int MAX_STEPS = 3;
    /** Zoals {@code import_field_mapping.transform_config}. */
    public static final int MAX_CONFIG_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mapping_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_field_transform_step_mapping"))
    private ImportFieldMapping mapping;

    /** De plaats in de keten, 1-gebaseerd (1..3). */
    @Column(name = "step_number", nullable = false)
    private int stepNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "transform_kind", nullable = false, length = 30)
    private FieldTransformKind transformKind;

    /** De instellingen van deze stap ({@code sleutel=waarde;...}), zoals {@code transform_config} van een mapping. */
    @Column(name = "transform_config", length = MAX_CONFIG_LENGTH)
    private String transformConfig;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    /** OIDC-subject van wie de stap vastlegde; {@code null} = geen geverifieerde identiteit (zoals 007-4). */
    @Column(name = "created_by_subject", length = 255)
    private String createdBySubject;

    protected ImportFieldTransformStep() {
        // JPA
    }

    public ImportFieldTransformStep(ImportFieldMapping mapping, int stepNumber, FieldTransformKind transformKind,
                                    String transformConfig) {
        this.mapping = mapping;
        this.stepNumber = stepNumber;
        this.transformKind = transformKind;
        this.transformConfig = transformConfig;
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

    public int getStepNumber() {
        return stepNumber;
    }

    public FieldTransformKind getTransformKind() {
        return transformKind;
    }

    public String getTransformConfig() {
        return transformConfig;
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
