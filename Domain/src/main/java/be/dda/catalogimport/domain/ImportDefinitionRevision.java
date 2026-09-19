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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Eén onveranderlijke revisie van een {@link ImportDefinition}.
 * <p>
 * Businessanalyse §14.14: de gebruiker wijzigt nooit de actieve revisie in plaats, maar maakt een
 * opvolger. Een revisie bestaat uit drie <b>afzonderlijk versieerbare</b> lagen:
 * <ol>
 *   <li><b>toegang/levering</b> — waar en hoe een levering gevonden wordt;</li>
 *   <li><b>structuur/dataset</b> — formaat, encoding, werkblad, header/datazone, recordnode;</li>
 *   <li><b>recordregels</b> — filter, identiteit, mapping, vertaling, validatie, prijs-/supplementbeleid.</li>
 * </ol>
 * Fase 1 legt per laag een eigen versienummer en configuratiehash vast, plus de samengestelde
 * hash over de drie lagen. De inhoudelijke kolommen/tabellen per laag komen in fase 2/3 en kunnen
 * zonder migratiebreuk aan deze revisie gekoppeld worden.
 * <p>
 * Het <b>identiteitsprofiel</b> hoort volgens §14.20 bij de revisie en geldt voor de volledige
 * importfile, nooit per record (§14.23.3, beslissingslog 18/09).
 */
@Entity
@Table(name = "import_definition_revision",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_import_definition_revision_number",
                        columnNames = {"import_definition_id", "revision_number"}),
                // Hoogstens één ACTIVE revisie per definitie (§14.14). active_marker is TRUE
                // wanneer de revisie actief is en NULL in alle andere toestanden; NULL-waarden
                // botsen niet in een UNIQUE constraint, dus dit dwingt de regel op databaseniveau af.
                @UniqueConstraint(name = "uk_import_definition_revision_active",
                        columnNames = {"import_definition_id", "active_marker"})
        })
public class ImportDefinitionRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_definition_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_definition_revision_definition"))
    private ImportDefinition importDefinition;

    /** Oplopend revisienummer binnen de definitie: 1, 2, 3, ... */
    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private RevisionStatus status = RevisionStatus.DRAFT;

    /**
     * Technische marker die {@link RevisionStatus#ACTIVE} op databaseniveau uniek houdt per
     * definitie. Altijd {@code TRUE} bij ACTIVE, altijd {@code null} in elke andere toestand.
     * Wordt automatisch afgeleid uit {@link #status}; niet los te zetten.
     */
    @Column(name = "active_marker")
    private Boolean activeMarker;

    // --- Laag 1: toegang/levering -------------------------------------------------------------

    @Column(name = "access_version", nullable = false)
    private int accessVersion = 1;

    @Column(name = "access_config_hash", nullable = false, length = 64)
    private String accessConfigHash;

    // --- Laag 2: structuur/dataset ------------------------------------------------------------

    @Column(name = "structure_version", nullable = false)
    private int structureVersion = 1;

    @Column(name = "structure_config_hash", nullable = false, length = 64)
    private String structureConfigHash;

    // --- Laag 3: recordregels -----------------------------------------------------------------

    @Column(name = "record_rules_version", nullable = false)
    private int recordRulesVersion = 1;

    @Column(name = "record_rules_config_hash", nullable = false, length = 64)
    private String recordRulesConfigHash;

    /** Samengestelde configuratiehash over de drie lagen (§14.14). */
    @Column(name = "composite_config_hash", nullable = false, length = 64)
    private String compositeConfigHash;

    // --- Identiteitsprofiel (§14.23.3) --------------------------------------------------------

    /**
     * Gekozen vorm van de aanbiedingsidentiteit voor de volledige importfile.
     * Consistent met {@link #identityDiscountCodeField} via de databasecheck
     * {@code ck_import_definition_revision_identity}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "identity_profile_kind", nullable = false, length = 40)
    private IdentityProfileKind identityProfileKind;

    /** Bronveld/-pad dat de leverancier levert. Altijd verplicht gemapt. */
    @Column(name = "identity_supplier_field", nullable = false, length = 200)
    private String identitySupplierField;

    /** Bronveld/-pad dat de leveranciersgroep levert. Altijd verplicht gemapt. */
    @Column(name = "identity_supplier_group_field", nullable = false, length = 200)
    private String identitySupplierGroupField;

    /** Bronveld/-pad dat de leveranciersreferentie levert. Altijd verplicht gemapt. */
    @Column(name = "identity_supplier_reference_field", nullable = false, length = 200)
    private String identitySupplierReferenceField;

    /**
     * Bronveld/-pad dat de kortingscode levert, of {@code null} wanneer kortingscode voor deze
     * importfile <b>niet gemapt</b> is. Dit is de expliciete scheiding tussen "niet gemapt"
     * ({@code null} → driedelige sleutel) en "gemapt maar mogelijk leeg" ({@code ""} → vierdelige
     * sleutel waarin de expliciet lege kortingscode meetelt). De import mag {@code null} nooit
     * stil in {@code ""} omzetten of omgekeerd (§14.23.3).
     */
    @Column(name = "identity_discount_code_field", length = 200)
    private String identityDiscountCodeField;

    // --- Bronconfiguratie (Fase 2, design par. 7) ----------------------------------------------
    // De bestaande identity_*_field-kolommen dragen de headernaam of, bij structure_field_reference_kind
    // COLUMN_INDEX, de 1-gebaseerde kolomindex. Een aparte import_field_mapping-tabel volgt in Fase 3.

    /** Bestandsformaat; voorlopig enkel {@code CSV} (databasecheck). */
    @Column(name = "structure_format", nullable = false, length = 20)
    private String structureFormat = "CSV";

    @Column(name = "structure_charset", nullable = false, length = 40)
    private String structureCharset = "UTF-8";

    /** Verplicht en zonder databasedefault: de applicatie zet het scheidingsteken expliciet. */
    @Column(name = "structure_delimiter", nullable = false, length = 1)
    private String structureDelimiter;

    /** Quote-teken, of {@code null} wanneer het bestand geen quoting kent. */
    @Column(name = "structure_quote_char", length = 1)
    private String structureQuoteChar = "\"";

    @Column(name = "structure_has_header", nullable = false)
    private boolean structureHasHeader = true;

    /** Fysiek (1-gebaseerd) regelnummer van de header; regels ervoor zijn prefix. */
    @Column(name = "structure_header_line_number", nullable = false)
    private int structureHeaderLineNumber = 1;

    /** {@code HEADER_NAME} of {@code COLUMN_INDEX}; zonder header verplicht {@code COLUMN_INDEX}. */
    @Column(name = "structure_field_reference_kind", nullable = false, length = 20)
    private String structureFieldReferenceKind = "HEADER_NAME";

    /** Verwacht aantal kolommen, of {@code null} wanneer niet gedeclareerd. */
    @Column(name = "structure_expected_column_count")
    private Integer structureExpectedColumnCount;

    /** {@code FULL_SNAPSHOT}, {@code DELTA} of {@code UNDECLARED}. */
    @Column(name = "access_delivery_set_kind", nullable = false, length = 30)
    private String accessDeliverySetKind = "UNDECLARED";

    /** Bronveld (of kolomindex) van de basisprijs. */
    @Column(name = "record_base_price_field", length = 200)
    private String recordBasePriceField;

    /** Bronveld (of kolomindex) van de omschrijving; optioneel. */
    @Column(name = "record_description_field", length = 200)
    private String recordDescriptionField;

    @Column(name = "record_canonicalisation_version", nullable = false)
    private int recordCanonicalisationVersion = 1;

    /** Bronveld (of kolomindex) van de munt; {@code null} betekent onbekend, nooit stil EUR (A22). */
    @Column(name = "record_currency_field", length = 200)
    private String recordCurrencyField;

    // --- Prijsbeleid (Fase 3, ontwerp par. 2 004-10) ------------------------------------------
    // De defaults zijn de normatieve waarden uit het ontwerp en gelden ook voor bestaande revisies.
    // Ze worden in bouwstap 3d-3e toegepast; hier worden ze enkel bevroren bij de revisie bewaard.

    /** Toegelaten prijsafwijking in procent t.o.v. de referenties; default 15 (R-PRI-10). */
    @Column(name = "price_deviation_percent", nullable = false, precision = 24, scale = 12)
    private BigDecimal priceDeviationPercent = new BigDecimal("15");

    /** Ernst van een overschrijding: standaard {@code WARNING}, per revisie te verzwaren naar {@code ERROR}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_deviation_severity", nullable = false, length = 20)
    private RowIssueSeverity priceDeviationSeverity = RowIssueSeverity.WARNING;

    /** Tolerantie op de prijsreconstructie {@code basis × pct / 100}; default 0,01 (R-PRI-07). */
    @Column(name = "price_derivation_tolerance", nullable = false, precision = 24, scale = 6)
    private BigDecimal priceDerivationTolerance = new BigDecimal("0.01");

    /** Venster (in goedgekeurde dagwaarden) van het korte gemiddelde; default 50 (R-PRI-10). */
    @Column(name = "price_avg_short_window", nullable = false)
    private int priceAvgShortWindow = 50;

    /** Venster (in goedgekeurde dagwaarden) van het lange gemiddelde; default 200 (R-PRI-10). */
    @Column(name = "price_avg_long_window", nullable = false)
    private int priceAvgLongWindow = 200;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_control_model", nullable = false, length = 20)
    private PriceControlModel priceControlModel = PriceControlModel.DEVIATION;

    // --- Drempels (Fase 3, ontwerp par. 2 004-10, R-THR-01/R-THR-05) ---------------------------

    /** Automatisch aanmaken tot dit aantal nieuwe aanbiedingen; default 100. */
    @Column(name = "creation_threshold_absolute", nullable = false)
    private int creationThresholdAbsolute = 100;

    /**
     * Én tot dit aandeel van de importscope; default 1 procent. Bewust <b>niet</b> 100: het oude
     * requirementsdocument is op dit punt aantoonbaar fout (ontwerp fase 3, par. 8).
     */
    @Column(name = "creation_threshold_share_percent", nullable = false, precision = 24, scale = 12)
    private BigDecimal creationThresholdSharePercent = BigDecimal.ONE;

    /** Aantal toegelaten kritieke records; default 0 ⇒ één kritiek record blokkeert de levering. */
    @Column(name = "max_critical_records", nullable = false)
    private int maxCriticalRecords;

    /** {@code null} betekent "niet geconfigureerd", nooit 0 (aanname A18). */
    @Column(name = "max_rejected_records")
    private Integer maxRejectedRecords;

    /** {@code null} betekent "niet geconfigureerd", nooit 0 (aanname A18). */
    @Column(name = "max_rejected_share_percent", precision = 24, scale = 12)
    private BigDecimal maxRejectedSharePercent;

    // --- Herkomst en audit --------------------------------------------------------------------

    /** Revisie waaruit deze revisie gekopieerd is (§14.14 "Gebaseerd op"). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "based_on_revision_id",
            foreignKey = @ForeignKey(name = "fk_import_definition_revision_based_on"))
    private ImportDefinitionRevision basedOnRevision;

    /** Verplicht bij een opvolgrevisie; ondersteunt tracing en vergelijking (§14.20). */
    @Column(name = "change_reason", length = 500)
    private String changeReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    protected ImportDefinitionRevision() {
        // JPA
    }

    public ImportDefinitionRevision(ImportDefinition importDefinition,
                                    int revisionNumber,
                                    IdentityProfileKind identityProfileKind,
                                    String createdBy) {
        this.importDefinition = importDefinition;
        this.revisionNumber = revisionNumber;
        this.identityProfileKind = identityProfileKind;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        syncActiveMarker();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        syncActiveMarker();
    }

    private void syncActiveMarker() {
        activeMarker = status == RevisionStatus.ACTIVE ? Boolean.TRUE : null;
    }

    public Long getId() {
        return id;
    }

    public ImportDefinition getImportDefinition() {
        return importDefinition;
    }

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public void setRevisionNumber(int revisionNumber) {
        this.revisionNumber = revisionNumber;
    }

    public RevisionStatus getStatus() {
        return status;
    }

    public void setStatus(RevisionStatus status) {
        this.status = status;
        syncActiveMarker();
    }

    public Boolean getActiveMarker() {
        return activeMarker;
    }

    public int getAccessVersion() {
        return accessVersion;
    }

    public void setAccessVersion(int accessVersion) {
        this.accessVersion = accessVersion;
    }

    public String getAccessConfigHash() {
        return accessConfigHash;
    }

    public void setAccessConfigHash(String accessConfigHash) {
        this.accessConfigHash = accessConfigHash;
    }

    public int getStructureVersion() {
        return structureVersion;
    }

    public void setStructureVersion(int structureVersion) {
        this.structureVersion = structureVersion;
    }

    public String getStructureConfigHash() {
        return structureConfigHash;
    }

    public void setStructureConfigHash(String structureConfigHash) {
        this.structureConfigHash = structureConfigHash;
    }

    public int getRecordRulesVersion() {
        return recordRulesVersion;
    }

    public void setRecordRulesVersion(int recordRulesVersion) {
        this.recordRulesVersion = recordRulesVersion;
    }

    public String getRecordRulesConfigHash() {
        return recordRulesConfigHash;
    }

    public void setRecordRulesConfigHash(String recordRulesConfigHash) {
        this.recordRulesConfigHash = recordRulesConfigHash;
    }

    public String getCompositeConfigHash() {
        return compositeConfigHash;
    }

    public void setCompositeConfigHash(String compositeConfigHash) {
        this.compositeConfigHash = compositeConfigHash;
    }

    public IdentityProfileKind getIdentityProfileKind() {
        return identityProfileKind;
    }

    public void setIdentityProfileKind(IdentityProfileKind identityProfileKind) {
        this.identityProfileKind = identityProfileKind;
    }

    public String getIdentitySupplierField() {
        return identitySupplierField;
    }

    public void setIdentitySupplierField(String identitySupplierField) {
        this.identitySupplierField = identitySupplierField;
    }

    public String getIdentitySupplierGroupField() {
        return identitySupplierGroupField;
    }

    public void setIdentitySupplierGroupField(String identitySupplierGroupField) {
        this.identitySupplierGroupField = identitySupplierGroupField;
    }

    public String getIdentitySupplierReferenceField() {
        return identitySupplierReferenceField;
    }

    public void setIdentitySupplierReferenceField(String identitySupplierReferenceField) {
        this.identitySupplierReferenceField = identitySupplierReferenceField;
    }

    public String getIdentityDiscountCodeField() {
        return identityDiscountCodeField;
    }

    public void setIdentityDiscountCodeField(String identityDiscountCodeField) {
        this.identityDiscountCodeField = identityDiscountCodeField;
    }

    public String getStructureFormat() {
        return structureFormat;
    }

    public void setStructureFormat(String structureFormat) {
        this.structureFormat = structureFormat;
    }

    public String getStructureCharset() {
        return structureCharset;
    }

    public void setStructureCharset(String structureCharset) {
        this.structureCharset = structureCharset;
    }

    public String getStructureDelimiter() {
        return structureDelimiter;
    }

    public void setStructureDelimiter(String structureDelimiter) {
        this.structureDelimiter = structureDelimiter;
    }

    public String getStructureQuoteChar() {
        return structureQuoteChar;
    }

    public void setStructureQuoteChar(String structureQuoteChar) {
        this.structureQuoteChar = structureQuoteChar;
    }

    public boolean isStructureHasHeader() {
        return structureHasHeader;
    }

    public void setStructureHasHeader(boolean structureHasHeader) {
        this.structureHasHeader = structureHasHeader;
    }

    public int getStructureHeaderLineNumber() {
        return structureHeaderLineNumber;
    }

    public void setStructureHeaderLineNumber(int structureHeaderLineNumber) {
        this.structureHeaderLineNumber = structureHeaderLineNumber;
    }

    public String getStructureFieldReferenceKind() {
        return structureFieldReferenceKind;
    }

    public void setStructureFieldReferenceKind(String structureFieldReferenceKind) {
        this.structureFieldReferenceKind = structureFieldReferenceKind;
    }

    public Integer getStructureExpectedColumnCount() {
        return structureExpectedColumnCount;
    }

    public void setStructureExpectedColumnCount(Integer structureExpectedColumnCount) {
        this.structureExpectedColumnCount = structureExpectedColumnCount;
    }

    public String getAccessDeliverySetKind() {
        return accessDeliverySetKind;
    }

    public void setAccessDeliverySetKind(String accessDeliverySetKind) {
        this.accessDeliverySetKind = accessDeliverySetKind;
    }

    public String getRecordBasePriceField() {
        return recordBasePriceField;
    }

    public void setRecordBasePriceField(String recordBasePriceField) {
        this.recordBasePriceField = recordBasePriceField;
    }

    public String getRecordDescriptionField() {
        return recordDescriptionField;
    }

    public void setRecordDescriptionField(String recordDescriptionField) {
        this.recordDescriptionField = recordDescriptionField;
    }

    public int getRecordCanonicalisationVersion() {
        return recordCanonicalisationVersion;
    }

    public void setRecordCanonicalisationVersion(int recordCanonicalisationVersion) {
        this.recordCanonicalisationVersion = recordCanonicalisationVersion;
    }

    public String getRecordCurrencyField() {
        return recordCurrencyField;
    }

    public void setRecordCurrencyField(String recordCurrencyField) {
        this.recordCurrencyField = recordCurrencyField;
    }

    public BigDecimal getPriceDeviationPercent() {
        return priceDeviationPercent;
    }

    public void setPriceDeviationPercent(BigDecimal priceDeviationPercent) {
        this.priceDeviationPercent = priceDeviationPercent;
    }

    public RowIssueSeverity getPriceDeviationSeverity() {
        return priceDeviationSeverity;
    }

    public void setPriceDeviationSeverity(RowIssueSeverity priceDeviationSeverity) {
        this.priceDeviationSeverity = priceDeviationSeverity;
    }

    public BigDecimal getPriceDerivationTolerance() {
        return priceDerivationTolerance;
    }

    public void setPriceDerivationTolerance(BigDecimal priceDerivationTolerance) {
        this.priceDerivationTolerance = priceDerivationTolerance;
    }

    public int getPriceAvgShortWindow() {
        return priceAvgShortWindow;
    }

    public void setPriceAvgShortWindow(int priceAvgShortWindow) {
        this.priceAvgShortWindow = priceAvgShortWindow;
    }

    public int getPriceAvgLongWindow() {
        return priceAvgLongWindow;
    }

    public void setPriceAvgLongWindow(int priceAvgLongWindow) {
        this.priceAvgLongWindow = priceAvgLongWindow;
    }

    public PriceControlModel getPriceControlModel() {
        return priceControlModel;
    }

    public void setPriceControlModel(PriceControlModel priceControlModel) {
        this.priceControlModel = priceControlModel;
    }

    public int getCreationThresholdAbsolute() {
        return creationThresholdAbsolute;
    }

    public void setCreationThresholdAbsolute(int creationThresholdAbsolute) {
        this.creationThresholdAbsolute = creationThresholdAbsolute;
    }

    public BigDecimal getCreationThresholdSharePercent() {
        return creationThresholdSharePercent;
    }

    public void setCreationThresholdSharePercent(BigDecimal creationThresholdSharePercent) {
        this.creationThresholdSharePercent = creationThresholdSharePercent;
    }

    public int getMaxCriticalRecords() {
        return maxCriticalRecords;
    }

    public void setMaxCriticalRecords(int maxCriticalRecords) {
        this.maxCriticalRecords = maxCriticalRecords;
    }

    public Integer getMaxRejectedRecords() {
        return maxRejectedRecords;
    }

    public void setMaxRejectedRecords(Integer maxRejectedRecords) {
        this.maxRejectedRecords = maxRejectedRecords;
    }

    public BigDecimal getMaxRejectedSharePercent() {
        return maxRejectedSharePercent;
    }

    public void setMaxRejectedSharePercent(BigDecimal maxRejectedSharePercent) {
        this.maxRejectedSharePercent = maxRejectedSharePercent;
    }

    public ImportDefinitionRevision getBasedOnRevision() {
        return basedOnRevision;
    }

    public void setBasedOnRevision(ImportDefinitionRevision basedOnRevision) {
        this.basedOnRevision = basedOnRevision;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
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

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }
}
