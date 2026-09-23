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
 * De <b>declaratie</b> van één invulveld (bookmark) van een importsjabloon, op revisieniveau
 * (businessanalyse §14.16, beslissingslog 23/09, changeset 006-1).
 * <p>
 * Een sjabloon is geen aparte entiteit: het is een gewone {@link ImportDefinition} met
 * {@link DefinitionUsageType#REUSABLE_TEMPLATE}, met hergebruik van {@link ImportDefinitionRevision}
 * als versiebegrip. Een bookmark hoort daarom bij een <b>revisie</b> en niet bij de definitie: een
 * sjabloon dat een invulveld toevoegt of laat vallen is een nieuwe sjabloonversie, net als elke andere
 * structuurwijziging (§14.14).
 * <p>
 * <b>Gevolg dat elders terugkomt:</b> deze declaratierijen verdwijnen bij elke opvolgrevisie, terwijl
 * de ingevulde waarden per koppeling blijven leven. Daarom koppelt {@link ImportLinkBookmarkValue} op
 * {@link #getName()} en niet met een verwijzing naar deze rij.
 * <p>
 * <b>Scope is een businesskeuze, geen technisch detail.</b> {@link BookmarkValueScope#DEFINITION}
 * wordt bij materialisatie in de afgeleide revisie vastgezet en geldt voor élke koppeling die die
 * definitie deelt; een waarde die per leverancier zou moeten verschillen hoort dus
 * {@link BookmarkValueScope#LINK} te zijn. Die regel moet bij het declareren afgedwongen worden — niet
 * pas bij materialisatie ontdekt (beslissingslog 23/09 keuze 2). Dat is servicelaagwerk: de database
 * kan de scope niet tegen de toegelaten {@link BookmarkUsagePlace}-rijen afwegen binnen één check.
 */
@Entity
@Table(name = "import_definition_bookmark",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_import_definition_bookmark_name",
                        columnNames = {"definition_revision_id", "name"}),
                @UniqueConstraint(name = "uk_import_definition_bookmark_order",
                        columnNames = {"definition_revision_id", "sort_order"})
        })
public class ImportDefinitionBookmark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_revision_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_definition_bookmark_revision"))
    private ImportDefinitionRevision definitionRevision;

    /**
     * Technische naam, bv. {@code BESTANDS_PREFIX} of {@code CULTUUR}. Stabiel over sjabloonversies
     * heen: dit is de sleutel waarmee een ingevulde waarde teruggevonden wordt.
     */
    @Column(name = "name", nullable = false, length = 60)
    private String name;

    /** Leesbaar label voor de invuller. */
    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 40)
    private BookmarkDataType dataType = BookmarkDataType.TEXT;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_scope", nullable = false, length = 20)
    private BookmarkValueScope valueScope;

    /**
     * Welke rol dit veld hoort in te vullen. Bewust een vrije code en geen enum: CatalogImport heeft
     * geen eigen rollentabel, de rechten komen uit Prodis (beslissingslog 18/09).
     */
    @Column(name = "owner_role", nullable = false, length = 40)
    private String ownerRole;

    /**
     * Standaard {@code true}: een bookmark bestaat om ingevuld te worden, en een niet ingevuld
     * verplicht veld moet blokkeren (beslissingslog 23/09 keuze 6) in plaats van stil leeg te blijven.
     */
    @Column(name = "required", nullable = false)
    private boolean required = true;

    @Column(name = "default_value", length = 500)
    private String defaultValue;

    /** Toegelaten waarden bij {@link BookmarkDataType#ENUM}; dan verplicht (databasecheck). */
    @Column(name = "allowed_values", length = 2000)
    private String allowedValues;

    /** Optioneel patroon voor de invoervalidatie in de servicelaag; niet door de database toegepast. */
    @Column(name = "validation_pattern", length = 200)
    private String validationPattern;

    /** Volgorde in het invulscherm; uniek per revisie, net als {@code sequence_number} elders. */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    protected ImportDefinitionBookmark() {
        // JPA
    }

    public ImportDefinitionBookmark(ImportDefinitionRevision definitionRevision, String name, String label,
                                    BookmarkDataType dataType, BookmarkValueScope valueScope,
                                    String ownerRole, int sortOrder) {
        this.definitionRevision = definitionRevision;
        this.name = name;
        this.label = label;
        this.dataType = dataType;
        this.valueScope = valueScope;
        this.ownerRole = ownerRole;
        this.sortOrder = sortOrder;
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

    public ImportDefinitionRevision getDefinitionRevision() {
        return definitionRevision;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BookmarkDataType getDataType() {
        return dataType;
    }

    public void setDataType(BookmarkDataType dataType) {
        this.dataType = dataType;
    }

    public BookmarkValueScope getValueScope() {
        return valueScope;
    }

    public void setValueScope(BookmarkValueScope valueScope) {
        this.valueScope = valueScope;
    }

    public String getOwnerRole() {
        return ownerRole;
    }

    public void setOwnerRole(String ownerRole) {
        this.ownerRole = ownerRole;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getAllowedValues() {
        return allowedValues;
    }

    public void setAllowedValues(String allowedValues) {
        this.allowedValues = allowedValues;
    }

    public String getValidationPattern() {
        return validationPattern;
    }

    public void setValidationPattern(String validationPattern) {
        this.validationPattern = validationPattern;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
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
