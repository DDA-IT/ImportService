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
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Eén regel van de centrale mutatielijst (de PSIMPORT001-lijst): wat een screening voorstelt te
 * doen met één aanbieding, of de {@link MutationActionType#IMPORT_MARKER} die een afgeronde
 * screening vastlegt.
 * <p>
 * <b>Mapping.</b> Bulk-schrijven gebeurt via JdbcTemplate; deze entiteit is het leesmodel. De
 * binaire kolommen {@code identity_hash}, {@code before_combined_fingerprint} en
 * {@code after_combined_fingerprint} worden bewust <b>niet</b> gemapt (databasespecifiek type,
 * enkel voor set-based SQL). Lees nooit via JPA wat dezelfde transactie via JdbcTemplate schreef.
 * <p>
 * <b>Idempotentie.</b> {@code uk_import_mutation_idempotency} maakt {@code idempotencyKey} uniek:
 * inhoudelijk {@code <delivery>:<revisie>:<identiteitshash-hex>:OFFER}, marker
 * {@code <delivery>:<revisie>:MARKER}. {@code ck_import_mutation_marker} bewaakt dat een marker
 * geen identiteit draagt en een CREATE/UPDATE altijd een volledige identiteit.
 */
@Entity
@Table(name = "import_mutation",
        uniqueConstraints = @UniqueConstraint(name = "uk_import_mutation_idempotency",
                columnNames = "idempotency_key"))
public class ImportMutation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_mutation_batch"))
    private ImportBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_mutation_delivery"))
    private Delivery delivery;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_link_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_mutation_import_link"))
    private ImportLink importLink;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_revision_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_mutation_definition_revision"))
    private ImportDefinitionRevision definitionRevision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_run_id",
            foreignKey = @ForeignKey(name = "fk_import_mutation_task_run"))
    private TaskRun taskRun;

    /** Sinds changeset 004-13 varchar(40): {@code IDENTITY_REFERENCE_INCIDENT} telt 27 tekens. */
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 40)
    private MutationActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_domain", nullable = false, length = 20)
    private MutationTargetDomain targetDomain;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private MutationStatus status;

    @Column(name = "status_reason", length = 200)
    private String statusReason;

    // --- Aanbiedingsidentiteit (null voor een IMPORT_MARKER) ----------------------------------

    @Column(name = "identity_supplier", length = 200)
    private String identitySupplier;

    @Column(name = "identity_supplier_group", length = 200)
    private String identitySupplierGroup;

    @Column(name = "identity_supplier_reference", length = 200)
    private String identitySupplierReference;

    /** {@code null} = niet gemapt (zie {@link DiscountCodeState#NOT_USED}); {@code ""} = gemapt maar leeg. */
    @Column(name = "identity_discount_code", length = 200)
    private String identityDiscountCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_discount_state", length = 20)
    private DiscountCodeState identityDiscountState;

    // --- Inhoud van de mutatie ----------------------------------------------------------------

    /** Betrokken domeinen van een UPDATE, bv. {@code PRICE}. Sinds changeset 004-13b varchar(200). */
    @Column(name = "domain_mask", length = 200)
    private String domainMask;

    /**
     * Het soort kritieke koppelreferentie waarover dit incident gaat ({@code EAN}, {@code PIM_ID},
     * {@code CAB_ID}, {@code E_MARK_ARTICLE_REFERENCE}); enkel gevuld op een
     * {@link MutationActionType#IDENTITY_REFERENCE_INCIDENT} (changeset 004-13, R-REF-02..R-REF-05).
     */
    @Column(name = "reference_type", length = 30)
    private String referenceType;

    /**
     * De <b>genormaliseerde</b> waarde die vóór dit incident actief was, of {@code null} wanneer de
     * aanbieding er nog geen had. Bewust leesbare tekst naast de hash: een beslisser moet oude en
     * nieuwe waarde kunnen zien zonder de staging te raadplegen, en die wordt opgeruimd.
     */
    @Column(name = "before_reference_value", length = 200)
    private String beforeReferenceValue;

    /** De genormaliseerde waarde uit de levering; {@code null} betekent "gemapt maar leeg" (R-REF-03). */
    @Column(name = "after_reference_value", length = 200)
    private String afterReferenceValue;

    /**
     * De issuegroep waarin dit incident later als bulkincident samengevat wordt (bouwstap 3g). In
     * bouwstap 3f blijft deze verwijzing leeg; de kolom bestaat al zodat de groepering additief kan
     * aansluiten.
     */
    @Column(name = "issue_group_id")
    private Long issueGroupId;

    @Column(name = "before_base_price", precision = 24, scale = 6)
    private BigDecimal beforeBasePrice;

    @Column(name = "after_base_price", precision = 24, scale = 6)
    private BigDecimal afterBasePrice;

    /** Nooit EUR veronderstellen: {@code null} als de bron geen munt levert. */
    @Column(name = "base_price_currency", length = 3)
    private String basePriceCurrency;

    /** Verwijzing naar {@code catalog_source_state} (JDBC-only tabel, geen entiteit). */
    @Column(name = "source_state_id")
    private Long sourceStateId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_file_id",
            foreignKey = @ForeignKey(name = "fk_import_mutation_file"))
    private DeliveryFile deliveryFile;

    @Column(name = "source_row_number")
    private Long sourceRowNumber;

    /** Enkel voor de marker, bv. {@code outcome=SCREENED;completenessProven=false;...}. */
    @Column(name = "result_summary", length = 1000)
    private String resultSummary;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImportMutation() {
        // JPA
    }

    public ImportMutation(ImportBatch batch, MutationActionType actionType, MutationTargetDomain targetDomain,
                          MutationStatus status, String idempotencyKey) {
        this.batch = batch;
        this.delivery = batch.getDelivery();
        this.importLink = batch.getImportLink();
        this.definitionRevision = batch.getDefinitionRevision();
        this.taskRun = batch.getTaskRun();
        this.actionType = actionType;
        this.targetDomain = targetDomain;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
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

    public ImportBatch getBatch() {
        return batch;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public ImportLink getImportLink() {
        return importLink;
    }

    public ImportDefinitionRevision getDefinitionRevision() {
        return definitionRevision;
    }

    public TaskRun getTaskRun() {
        return taskRun;
    }

    public MutationActionType getActionType() {
        return actionType;
    }

    public MutationTargetDomain getTargetDomain() {
        return targetDomain;
    }

    public MutationStatus getStatus() {
        return status;
    }

    public void setStatus(MutationStatus status) {
        this.status = status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public String getIdentitySupplier() {
        return identitySupplier;
    }

    public void setIdentitySupplier(String identitySupplier) {
        this.identitySupplier = identitySupplier;
    }

    public String getIdentitySupplierGroup() {
        return identitySupplierGroup;
    }

    public void setIdentitySupplierGroup(String identitySupplierGroup) {
        this.identitySupplierGroup = identitySupplierGroup;
    }

    public String getIdentitySupplierReference() {
        return identitySupplierReference;
    }

    public void setIdentitySupplierReference(String identitySupplierReference) {
        this.identitySupplierReference = identitySupplierReference;
    }

    public String getIdentityDiscountCode() {
        return identityDiscountCode;
    }

    public void setIdentityDiscountCode(String identityDiscountCode) {
        this.identityDiscountCode = identityDiscountCode;
    }

    public DiscountCodeState getIdentityDiscountState() {
        return identityDiscountState;
    }

    public void setIdentityDiscountState(DiscountCodeState identityDiscountState) {
        this.identityDiscountState = identityDiscountState;
    }

    public String getDomainMask() {
        return domainMask;
    }

    public void setDomainMask(String domainMask) {
        this.domainMask = domainMask;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public String getBeforeReferenceValue() {
        return beforeReferenceValue;
    }

    public void setBeforeReferenceValue(String beforeReferenceValue) {
        this.beforeReferenceValue = beforeReferenceValue;
    }

    public String getAfterReferenceValue() {
        return afterReferenceValue;
    }

    public void setAfterReferenceValue(String afterReferenceValue) {
        this.afterReferenceValue = afterReferenceValue;
    }

    public Long getIssueGroupId() {
        return issueGroupId;
    }

    public void setIssueGroupId(Long issueGroupId) {
        this.issueGroupId = issueGroupId;
    }

    public BigDecimal getBeforeBasePrice() {
        return beforeBasePrice;
    }

    public void setBeforeBasePrice(BigDecimal beforeBasePrice) {
        this.beforeBasePrice = beforeBasePrice;
    }

    public BigDecimal getAfterBasePrice() {
        return afterBasePrice;
    }

    public void setAfterBasePrice(BigDecimal afterBasePrice) {
        this.afterBasePrice = afterBasePrice;
    }

    public String getBasePriceCurrency() {
        return basePriceCurrency;
    }

    public void setBasePriceCurrency(String basePriceCurrency) {
        this.basePriceCurrency = basePriceCurrency;
    }

    public Long getSourceStateId() {
        return sourceStateId;
    }

    public void setSourceStateId(Long sourceStateId) {
        this.sourceStateId = sourceStateId;
    }

    public DeliveryFile getDeliveryFile() {
        return deliveryFile;
    }

    public void setDeliveryFile(DeliveryFile deliveryFile) {
        this.deliveryFile = deliveryFile;
    }

    public Long getSourceRowNumber() {
        return sourceRowNumber;
    }

    public void setSourceRowNumber(Long sourceRowNumber) {
        this.sourceRowNumber = sourceRowNumber;
    }

    public String getResultSummary() {
        return resultSummary;
    }

    public void setResultSummary(String resultSummary) {
        this.resultSummary = resultSummary;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
