package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
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
 * Import Definitie Hoofd: de <b>stabiele</b> zakelijke identiteit van een importdefinitie.
 * <p>
 * Businessanalyse §14.20 houdt dit hoofd bewust klein: code, omschrijving en een optionele
 * opvolger. Alle inhoudelijke configuratie (toegang, structuur, recordregels, identiteitsprofiel)
 * hangt onder een {@link ImportDefinitionRevision} — zie §14.14. De code blijft stabiel over
 * revisies heen.
 * <p>
 * Natuurlijke sleutel: bronorganisatie + code. Eén bronorganisatie (bv. de aankoopvereniging
 * VROOAM) kan meerdere definities hebben; §14.15 vereist per leverancier een eigen definitie.
 */
@Entity
@Table(name = "import_definition",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_import_definition_code",
                columnNames = {"source_organisation_id", "code"}))
public class ImportDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** De bronorganisatie die de bestanden voor deze definitie aanlevert. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_organisation_id", nullable = false,
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_import_definition_source_organisation"))
    private SourceOrganisation sourceOrganisation;

    /** Zakelijke herkenningscode, bv. {@code VROOAM2}. Legacy "Code"; stabiel over revisies. */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    /** Legacy "Omschrijving", bv. {@code VROOAM catalogus CSV}. */
    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "usage_type", nullable = false, length = 40)
    private DefinitionUsageType usageType = DefinitionUsageType.OWN_DEFINITION;

    /**
     * Legacy "Code vervanging / opvolger": de definitie die deze definitie <i>als geheel</i>
     * uitfaseert. Dit is nadrukkelijk iets anders dan een nieuwe revisie (§14.20).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "superseded_by_definition_id",
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_import_definition_superseded_by"))
    private ImportDefinition supersededByDefinition;

    /** "Gebaseerd op": gekopieerde definitie of sjabloon waaruit deze definitie ontstond (§14.16). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "based_on_definition_id",
            foreignKey = @jakarta.persistence.ForeignKey(name = "fk_import_definition_based_on"))
    private ImportDefinition basedOnDefinition;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImportDefinition() {
        // JPA
    }

    public ImportDefinition(SourceOrganisation sourceOrganisation, String code, String description, String createdBy) {
        this.sourceOrganisation = sourceOrganisation;
        this.code = code;
        this.description = description;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public SourceOrganisation getSourceOrganisation() {
        return sourceOrganisation;
    }

    public void setSourceOrganisation(SourceOrganisation sourceOrganisation) {
        this.sourceOrganisation = sourceOrganisation;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public DefinitionUsageType getUsageType() {
        return usageType;
    }

    public void setUsageType(DefinitionUsageType usageType) {
        this.usageType = usageType;
    }

    public ImportDefinition getSupersededByDefinition() {
        return supersededByDefinition;
    }

    public void setSupersededByDefinition(ImportDefinition supersededByDefinition) {
        this.supersededByDefinition = supersededByDefinition;
    }

    public ImportDefinition getBasedOnDefinition() {
        return basedOnDefinition;
    }

    public void setBasedOnDefinition(ImportDefinition basedOnDefinition) {
        this.basedOnDefinition = basedOnDefinition;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
