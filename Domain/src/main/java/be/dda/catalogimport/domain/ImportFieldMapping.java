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
 * Eén doelveld van één bevroren {@link ImportDefinitionRevision}, en waar de waarde vandaan komt
 * (ontwerp fase 3, par. 2 004-2, R-STR-04).
 * <p>
 * <b>Naast, niet in plaats van, de bestaande revisiekolommen.</b> Leverancier, leveranciersgroep,
 * leveranciersreferentie, kortingscode, basisprijs en omschrijving blijven autoritair op de revisie
 * staan (aanname A19). Een mapping die zo'n veld nóg eens bepaalt wordt geblokkeerd met
 * {@code CONFIG_FIELD_MAPPING_DUPLICATES_REVISION} (R-STR-06): twee bronnen voor dezelfde waarde is
 * altijd een fout, ook als ze toevallig hetzelfde zeggen.
 * <p>
 * <b>{@code expectedPosition} is een controle, geen mapping.</b> De kolom wordt opgezocht op naam (of
 * op kolomindex bij een bron zonder header); de verwachte positie dient enkel om een verschoven of
 * hernoemde kolom te herkennen (R-STR-02/R-STR-03). Er wordt nooit stil op positie gemapt wanneer de
 * naam niet klopt.
 * <p>
 * <b>Sjabloon-/bookmarkklaar.</b> {@link FieldValueKind#BOOKMARK} en {@link #getBookmarkName()}
 * bestaan al, zodat een sjabloon-afgeleide definitie later zonder schemamigratie kan aansluiten
 * (beslissingslog 18/09); tot dan wordt een bookmark-mapping expliciet geweigerd.
 */
@Entity
@Table(name = "import_field_mapping",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_import_field_mapping_target",
                        columnNames = {"definition_revision_id", "target_field_code"}),
                @UniqueConstraint(name = "uk_import_field_mapping_sequence",
                        columnNames = {"definition_revision_id", "sequence_number"})
        })
public class ImportFieldMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_revision_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_field_mapping_revision"))
    private ImportDefinitionRevision definitionRevision;

    /** Volgorde van verwerking binnen de revisie; uniek per revisie. */
    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_field_code", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_field_mapping_target"))
    private ImportFieldCatalogEntry targetField;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_kind", nullable = false, length = 20)
    private FieldValueKind valueKind = FieldValueKind.SOURCE_FIELD;

    /** Headernaam of 1-gebaseerde kolomindex, net als {@code identity_*_field} op de revisie. */
    @Column(name = "source_reference", length = 200)
    private String sourceReference;

    /** Kolompositie (1-gebaseerd) waarop dit veld bij het vastleggen stond, of {@code null}. */
    @Column(name = "expected_position")
    private Integer expectedPosition;

    @Column(name = "fixed_value", length = 500)
    private String fixedValue;

    @Column(name = "bookmark_name", length = 60)
    private String bookmarkName;

    /** Alleen toe te passen bij een werkelijk ontbrekende waarde, nooit bij een lege (R-REC-03). */
    @Column(name = "default_value", length = 500)
    private String defaultValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    private FieldDataType dataType = FieldDataType.TEXT;

    @Column(name = "required", nullable = false)
    private boolean required;

    @Column(name = "max_length")
    private Integer maxLength;

    @Column(name = "decimal_scale")
    private Integer decimalScale;

    /** Nul is voor een bedrag een betekenisvolle, verdachte waarde: enkel toegelaten als dit aan staat. */
    @Column(name = "zero_allowed", nullable = false)
    private boolean zeroAllowed;

    @Column(name = "negative_allowed", nullable = false)
    private boolean negativeAllowed;

    @Enumerated(EnumType.STRING)
    @Column(name = "transform_kind", nullable = false, length = 30)
    private FieldTransformKind transformKind = FieldTransformKind.NONE;

    @Column(name = "transform_config", length = 1000)
    private String transformConfig;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_owner", nullable = false, length = 30)
    private FieldOwner fieldOwner = FieldOwner.CATALOG_SOURCE;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_class", nullable = false, length = 30)
    private IdentityClass identityClass = IdentityClass.NONE;

    @Column(name = "price_component_code", length = 20)
    private String priceComponentCode;

    @Column(name = "reference_type", length = 30)
    private String referenceType;

    /**
     * Is een fout op deze kolom kritiek (ontwerp fase 3, par. 15.1)? {@code null} betekent "niet
     * uitdrukkelijk gezet": {@link #getCriticality()} leidt dan de standaard af (referentie- en
     * prijscomponentmappings {@code CRITICAL}, al het andere {@code NON_CRITICAL}) en {@link #onPersist()}
     * bewaart die. Dat is dezelfde regel als de backfill van changeset 004-2b, zodat een nieuwe
     * referentiemapping de databasecheck {@code ck_import_field_mapping_reference_critical} nooit schendt
     * zonder dat iemand ze uitdrukkelijk op {@code NON_CRITICAL} zette.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "criticality", nullable = false, length = 20)
    private Criticality criticality;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    protected ImportFieldMapping() {
        // JPA
    }

    public ImportFieldMapping(ImportDefinitionRevision definitionRevision, int sequenceNumber,
                              ImportFieldCatalogEntry targetField, FieldValueKind valueKind,
                              FieldDataType dataType, FieldOwner fieldOwner, IdentityClass identityClass) {
        this.definitionRevision = definitionRevision;
        this.sequenceNumber = sequenceNumber;
        this.targetField = targetField;
        this.valueKind = valueKind;
        this.dataType = dataType;
        this.fieldOwner = fieldOwner;
        this.identityClass = identityClass;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        criticality = getCriticality();
    }

    public Long getId() {
        return id;
    }

    public ImportDefinitionRevision getDefinitionRevision() {
        return definitionRevision;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(int sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public ImportFieldCatalogEntry getTargetField() {
        return targetField;
    }

    public void setTargetField(ImportFieldCatalogEntry targetField) {
        this.targetField = targetField;
    }

    public FieldValueKind getValueKind() {
        return valueKind;
    }

    public void setValueKind(FieldValueKind valueKind) {
        this.valueKind = valueKind;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public Integer getExpectedPosition() {
        return expectedPosition;
    }

    public void setExpectedPosition(Integer expectedPosition) {
        this.expectedPosition = expectedPosition;
    }

    public String getFixedValue() {
        return fixedValue;
    }

    public void setFixedValue(String fixedValue) {
        this.fixedValue = fixedValue;
    }

    public String getBookmarkName() {
        return bookmarkName;
    }

    public void setBookmarkName(String bookmarkName) {
        this.bookmarkName = bookmarkName;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public FieldDataType getDataType() {
        return dataType;
    }

    public void setDataType(FieldDataType dataType) {
        this.dataType = dataType;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
    }

    public Integer getDecimalScale() {
        return decimalScale;
    }

    public void setDecimalScale(Integer decimalScale) {
        this.decimalScale = decimalScale;
    }

    public boolean isZeroAllowed() {
        return zeroAllowed;
    }

    public void setZeroAllowed(boolean zeroAllowed) {
        this.zeroAllowed = zeroAllowed;
    }

    public boolean isNegativeAllowed() {
        return negativeAllowed;
    }

    public void setNegativeAllowed(boolean negativeAllowed) {
        this.negativeAllowed = negativeAllowed;
    }

    public FieldTransformKind getTransformKind() {
        return transformKind;
    }

    public void setTransformKind(FieldTransformKind transformKind) {
        this.transformKind = transformKind;
    }

    public String getTransformConfig() {
        return transformConfig;
    }

    public void setTransformConfig(String transformConfig) {
        this.transformConfig = transformConfig;
    }

    public FieldOwner getFieldOwner() {
        return fieldOwner;
    }

    public void setFieldOwner(FieldOwner fieldOwner) {
        this.fieldOwner = fieldOwner;
    }

    public IdentityClass getIdentityClass() {
        return identityClass;
    }

    public void setIdentityClass(IdentityClass identityClass) {
        this.identityClass = identityClass;
    }

    public String getPriceComponentCode() {
        return priceComponentCode;
    }

    public void setPriceComponentCode(String priceComponentCode) {
        this.priceComponentCode = priceComponentCode;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    /**
     * De kritiek-vlag van deze kolom: de uitdrukkelijk gezette waarde, of anders de standaard
     * ({@code CRITICAL} voor een referentie- of prijscomponentmapping, anders {@code NON_CRITICAL}).
     */
    public Criticality getCriticality() {
        if (criticality != null) {
            return criticality;
        }
        return referenceType != null || priceComponentCode != null
                ? Criticality.CRITICAL : Criticality.NON_CRITICAL;
    }

    public void setCriticality(Criticality criticality) {
        this.criticality = criticality;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
