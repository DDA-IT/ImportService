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
import java.time.Instant;

/**
 * Eén screening van één {@link Delivery} onder één bevroren {@link ImportDefinitionRevision}.
 * <p>
 * <b>Hoogstens één open batch per levering.</b> {@code openMarker} is {@code TRUE} zolang de batch
 * niet in een terminale status staat ({@link ImportBatchStatus#isTerminal()}) en {@code null}
 * daarna. Samen met {@code uk_import_batch_open} dwingt dat op databaseniveau af dat een levering
 * niet gelijktijdig door twee screenings verwerkt wordt (zelfde patroon als
 * {@link TaskRun#getConcurrencyToken()} en {@link ImportDefinitionRevision#getActiveMarker()}).
 * <p>
 * <b>Poging.</b> {@code uk_import_batch_attempt} maakt {@code (delivery, revisie, attemptNo)}
 * uniek; een herstart na een mislukte poging krijgt {@code attemptNo + 1}.
 * <p>
 * Tellers zijn nullable: {@code null} betekent onbekend en wordt nooit stil {@code 0}.
 */
@Entity
@Table(name = "import_batch",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_import_batch_attempt",
                        columnNames = {"delivery_id", "definition_revision_id", "attempt_no"}),
                @UniqueConstraint(name = "uk_import_batch_open",
                        columnNames = {"delivery_id", "open_marker"})
        })
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_batch_delivery"))
    private Delivery delivery;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_link_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_batch_import_link"))
    private ImportLink importLink;

    /** De bevroren revisie waaronder gescreend wordt. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "definition_revision_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_batch_definition_revision"))
    private ImportDefinitionRevision definitionRevision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_run_id",
            foreignKey = @ForeignKey(name = "fk_import_batch_task_run"))
    private TaskRun taskRun;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ImportBatchStatus status = ImportBatchStatus.RECEIVED;

    /**
     * Technische marker die "open batch" op databaseniveau uniek houdt per levering. {@code TRUE}
     * zolang de status niet-terminaal is, anders {@code null}. Wordt automatisch afgeleid uit
     * {@link #status}; niet los te zetten.
     */
    @Column(name = "open_marker")
    private Boolean openMarker;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "staged_row_count", nullable = false)
    private long stagedRowCount;

    /** Hervatpunt van de mutatiegeneratie (laatst verwerkte fysieke regelnummer). */
    @Column(name = "mutation_progress_row_number", nullable = false)
    private long mutationProgressRowNumber;

    /**
     * Hervatpunt van de prijscontrolepass (ontwerp fase 3, par. 3.1 stap E3). Een eigen kolom naast
     * {@link #mutationProgressRowNumber}: de prijsafwijkingscontrole is een afzonderlijk hervatbare
     * pass, zodat een onderbroken verwerking geen tweede reeks prijsissues oplevert.
     */
    @Column(name = "price_progress_row_number", nullable = false)
    private long priceProgressRowNumber;

    /**
     * Hervatpunt van de classificatiepass (ontwerp fase 3, par. 3.1 stap E1). In fase 2 zat de
     * classificatie in dezelfde chunktransactie als de mutatie-insert; vanaf fase 3 zijn dat aparte
     * passes, omdat de referentiecontrole (E2) en de drempels (3h) de mutatiestatus bepalen en dus
     * volledig berekend moeten zijn vóór er één mutatie geschreven wordt.
     */
    @Column(name = "classify_progress_row_number", nullable = false)
    private long classifyProgressRowNumber;

    /** Hervatpunt van de referentiecontrolepass (ontwerp fase 3, par. 3.1 stap E2). */
    @Column(name = "reference_progress_row_number", nullable = false)
    private long referenceProgressRowNumber;

    @Column(name = "raw_record_count")
    private Long rawRecordCount;

    @Column(name = "valid_record_count")
    private Long validRecordCount;

    @Column(name = "rejected_record_count")
    private Long rejectedRecordCount;

    /**
     * Aantal gelezen records dat door een recordfilter buiten de importscope viel (R-FLT-04). Dat is
     * geen fout: zonder geconfigureerde filters staat deze teller op 0, met filters is hij het bewijs
     * dat het bestand volledig gelezen is en welk deel bewust niet meetelt.
     */
    @Column(name = "filtered_out_count")
    private Long filteredOutCount;

    /**
     * Aantal gelezen records dat al vóór het filter onleesbaar was (kolomaantal, niet-gesloten quote,
     * te lange regel) en dus niet meer aan de importscope toegewezen kon worden (R-FLT-04). Zulke
     * records tellen bewust <b>niet</b> in {@link #rejectedRecordCount}: dat is het aantal verworpen
     * records <b>binnen</b> de scope, waarop de drempels rekenen.
     */
    @Column(name = "error_before_filter_count")
    private Long errorBeforeFilterCount;

    @Column(name = "duplicate_identity_count")
    private Long duplicateIdentityCount;

    @Column(name = "new_count")
    private Long newCount;

    @Column(name = "changed_count")
    private Long changedCount;

    @Column(name = "unchanged_count")
    private Long unchangedCount;

    /**
     * Aantal gestagede regels dat wegens een kritiek referentie-incident is vastgehouden
     * ({@link CandidateClassification#IDENTITY_INCIDENT}, R-REF-09). Zo'n regel is geldig gelezen —
     * ze telt dus mee in {@link #validRecordCount} — maar wordt niet doorgelaten. Vanaf fase 3f geldt
     * {@code valid = new + changed + unchanged + duplicateIdentity + identityIncident}.
     */
    @Column(name = "identity_incident_count")
    private Long identityIncidentCount;

    @Column(name = "content_mutation_count")
    private Long contentMutationCount;

    /**
     * Het inhoudelijke eindoordeel, als aparte statusas naast {@link #status} (ontwerp fase 3,
     * afwijking D en R-THR-06). {@code null} zolang de screening niet afgerond is én bij een
     * technische fout ({@code FAILED}): er is dan niets vastgesteld, en dat wordt nooit stil
     * {@link ValidationResult#VALID}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "validation_result", length = 30)
    private ValidationResult validationResult;

    @Column(name = "blocked_code", length = 60)
    private String blockedCode;

    @Column(name = "blocked_reason", length = 500)
    private String blockedReason;

    /**
     * Audit van {@code accept-baseline} (changeset 003): wie, wanneer en met welke reden deze batch als
     * nulmeting van de bronstaat is aanvaard. Alleen gevuld bij {@link ImportBatchStatus#BASELINE_ACCEPTED}.
     */
    @Column(name = "baseline_accepted_by", length = 100)
    private String baselineAcceptedBy;

    @Column(name = "baseline_accepted_at")
    private Instant baselineAcceptedAt;

    @Column(name = "baseline_accept_reason", length = 500)
    private String baselineAcceptReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    protected ImportBatch() {
        // JPA
    }

    public ImportBatch(Delivery delivery, ImportLink importLink, ImportDefinitionRevision definitionRevision,
                       int attemptNo, String createdBy) {
        this.delivery = delivery;
        this.importLink = importLink;
        this.definitionRevision = definitionRevision;
        this.attemptNo = attemptNo;
        this.createdBy = createdBy;
    }

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        syncOpenMarker();
    }

    @PreUpdate
    void onUpdate() {
        syncOpenMarker();
    }

    private void syncOpenMarker() {
        openMarker = status.isTerminal() ? null : Boolean.TRUE;
    }

    public Long getId() {
        return id;
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

    public void setTaskRun(TaskRun taskRun) {
        this.taskRun = taskRun;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public ImportBatchStatus getStatus() {
        return status;
    }

    public void setStatus(ImportBatchStatus status) {
        this.status = status;
        syncOpenMarker();
    }

    public Boolean getOpenMarker() {
        return openMarker;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public long getStagedRowCount() {
        return stagedRowCount;
    }

    public void setStagedRowCount(long stagedRowCount) {
        this.stagedRowCount = stagedRowCount;
    }

    public long getMutationProgressRowNumber() {
        return mutationProgressRowNumber;
    }

    public void setMutationProgressRowNumber(long mutationProgressRowNumber) {
        this.mutationProgressRowNumber = mutationProgressRowNumber;
    }

    public long getPriceProgressRowNumber() {
        return priceProgressRowNumber;
    }

    public void setPriceProgressRowNumber(long priceProgressRowNumber) {
        this.priceProgressRowNumber = priceProgressRowNumber;
    }

    public long getClassifyProgressRowNumber() {
        return classifyProgressRowNumber;
    }

    public void setClassifyProgressRowNumber(long classifyProgressRowNumber) {
        this.classifyProgressRowNumber = classifyProgressRowNumber;
    }

    public long getReferenceProgressRowNumber() {
        return referenceProgressRowNumber;
    }

    public void setReferenceProgressRowNumber(long referenceProgressRowNumber) {
        this.referenceProgressRowNumber = referenceProgressRowNumber;
    }

    public Long getRawRecordCount() {
        return rawRecordCount;
    }

    public void setRawRecordCount(Long rawRecordCount) {
        this.rawRecordCount = rawRecordCount;
    }

    public Long getValidRecordCount() {
        return validRecordCount;
    }

    public void setValidRecordCount(Long validRecordCount) {
        this.validRecordCount = validRecordCount;
    }

    public Long getRejectedRecordCount() {
        return rejectedRecordCount;
    }

    public void setRejectedRecordCount(Long rejectedRecordCount) {
        this.rejectedRecordCount = rejectedRecordCount;
    }

    public Long getFilteredOutCount() {
        return filteredOutCount;
    }

    public void setFilteredOutCount(Long filteredOutCount) {
        this.filteredOutCount = filteredOutCount;
    }

    public Long getErrorBeforeFilterCount() {
        return errorBeforeFilterCount;
    }

    public void setErrorBeforeFilterCount(Long errorBeforeFilterCount) {
        this.errorBeforeFilterCount = errorBeforeFilterCount;
    }

    public Long getDuplicateIdentityCount() {
        return duplicateIdentityCount;
    }

    public void setDuplicateIdentityCount(Long duplicateIdentityCount) {
        this.duplicateIdentityCount = duplicateIdentityCount;
    }

    public Long getNewCount() {
        return newCount;
    }

    public void setNewCount(Long newCount) {
        this.newCount = newCount;
    }

    public Long getChangedCount() {
        return changedCount;
    }

    public void setChangedCount(Long changedCount) {
        this.changedCount = changedCount;
    }

    public Long getUnchangedCount() {
        return unchangedCount;
    }

    public void setUnchangedCount(Long unchangedCount) {
        this.unchangedCount = unchangedCount;
    }

    public Long getIdentityIncidentCount() {
        return identityIncidentCount;
    }

    public void setIdentityIncidentCount(Long identityIncidentCount) {
        this.identityIncidentCount = identityIncidentCount;
    }

    public Long getContentMutationCount() {
        return contentMutationCount;
    }

    public void setContentMutationCount(Long contentMutationCount) {
        this.contentMutationCount = contentMutationCount;
    }

    public ValidationResult getValidationResult() {
        return validationResult;
    }

    public void setValidationResult(ValidationResult validationResult) {
        this.validationResult = validationResult;
    }

    public String getBlockedCode() {
        return blockedCode;
    }

    public void setBlockedCode(String blockedCode) {
        this.blockedCode = blockedCode;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }

    public String getBaselineAcceptedBy() {
        return baselineAcceptedBy;
    }

    public Instant getBaselineAcceptedAt() {
        return baselineAcceptedAt;
    }

    public String getBaselineAcceptReason() {
        return baselineAcceptReason;
    }

    /** Legt de drie auditgegevens van een baseline-acceptatie samen vast; nooit los te zetten. */
    public void recordBaselineAcceptance(String acceptedBy, Instant acceptedAt, String reason) {
        this.baselineAcceptedBy = acceptedBy;
        this.baselineAcceptedAt = acceptedAt;
        this.baselineAcceptReason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
