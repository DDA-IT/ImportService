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
import java.time.Instant;

/**
 * Eén vastgesteld probleem binnen een {@link ImportBatch}. Bulk-schrijven gebeurt via JdbcTemplate;
 * deze entiteit is bedoeld om te lezen (paginering) en voor tests. {@code sourceValue} is afgekapt;
 * de brontekst zelf staat enkel in het gearchiveerde bestand.
 * <p>
 * <b>De tabelnaam is historisch en dekt sinds fase 3 de lading niet meer volledig.</b> Ondanks
 * "row" draagt deze tabel <b>alle drie</b> de controleniveaus uit {@link ControlLevel}: een
 * leverings- of structuurprobleem (leeg bestand, ontbrekende headerkolom, configuratiefout) staat
 * hier net zo goed in als een regelprobleem. Daarom zijn {@code rowNumber} en {@code deliveryFile}
 * sinds changeset 004-12 <b>nullable</b>: een probleem dat de hele levering raakt hoort bij geen
 * enkele regel, en een verzonnen regelnummer 0 zou dat verbergen. De tabel is bewust uitgebreid en
 * niet vervangen (ontwerp fase 3, 004-12 en aanname A14): dat houdt de fase 2-contracten heel en
 * vraagt geen datamigratie.
 * <p>
 * {@code severity}, {@code issueDomain}, {@code controlLevel} en {@code impactScope} staan
 * gedenormaliseerd op elke rij (R-ISS-02). Ze komen uit de foutcodecatalogus op het moment van
 * vaststellen; een latere wijziging van die catalogus mag het oordeel over een reeds verwerkte
 * levering nooit met terugwerkende kracht veranderen.
 */
@Entity
@Table(name = "import_row_issue")
public class ImportRowIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_row_issue_batch"))
    private ImportBatch batch;

    /** {@code null} bij een probleem dat niet aan één bronbestand toe te wijzen is. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_file_id",
            foreignKey = @ForeignKey(name = "fk_import_row_issue_file"))
    private DeliveryFile deliveryFile;

    /**
     * Fysiek regelnummer (1-gebaseerd, inclusief header/prefix), of {@code null} bij een probleem op
     * leverings- of structuurniveau dat bij geen enkele regel hoort.
     */
    @Column(name = "row_number")
    private Long rowNumber;

    @Column(name = "issue_code", nullable = false, length = 60)
    private String issueCode;

    @Column(name = "field_name", length = 200)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private RowIssueSeverity severity = RowIssueSeverity.ERROR;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_domain", nullable = false, length = 40)
    private IssueDomain issueDomain = IssueDomain.MAPPING_VALIDATION;

    @Enumerated(EnumType.STRING)
    @Column(name = "control_level", nullable = false, length = 20)
    private ControlLevel controlLevel = ControlLevel.RECORD;

    @Enumerated(EnumType.STRING)
    @Column(name = "impact_scope", nullable = false, length = 20)
    private ImpactScope impactScope = ImpactScope.RECORD;

    /**
     * De groep waarin dit probleem samengevat is, of {@code null} wanneer het los staat: minder dan
     * tien gelijksoortige vaststellingen, of een probleem dat per definitie hoogstens één keer
     * voorkomt. Bewust een losse sleutel en geen {@code @ManyToOne}: {@code import_issue_group}
     * wordt set-based geschreven en gelezen.
     * <p>
     * <b>Het aantal van de groep is niet het aantal van deze rijen.</b> Per foutcode worden er
     * hoogstens {@code max-sample-rows-per-code} voorbeeldrijen bewaard (R-ISS-03); het werkelijke
     * aantal staat in {@code import_issue_group.occurrence_count}.
     */
    @Column(name = "issue_group_id")
    private Long issueGroupId;

    /**
     * De foutsignatuur waarop pass E4 groepeert: wat deze vaststelling gelijksoortig maakt aan een
     * andere (foutcode + veld, of prijscomponent + richting, of referentietype + soort incident).
     * {@code null} voor een probleem dat bij geen enkele groep hoort.
     * <p>
     * Ze wordt vastgelegd op het moment van vaststellen en niet later herleid: een deel ervan
     * (richting van een prijsafwijking, soort referentie-incident) staat nergens anders als kolom,
     * en het in SQL herberekenen zou de prijs- en identiteitsregel een tweede keer implementeren.
     */
    @Column(name = "signature", length = 300)
    private String signature;

    @Enumerated(EnumType.STRING)
    @Column(name = "handling_status", nullable = false, length = 30)
    private IssueHandlingStatus handlingStatus = IssueHandlingStatus.DETECTED;

    /** Versie van de regelconfiguratie waaronder dit vastgesteld is; nog niet gevuld in fase 3a. */
    @Column(name = "rule_config_version")
    private Integer ruleConfigVersion;

    /** Wat er verwacht werd, náást de bronwaarde — nooit stilzwijgend toegepast, enkel getoond. */
    @Column(name = "expected_value", length = 200)
    private String expectedValue;

    /** Volgnummer binnen de groep; gevuld vanaf bouwstap 3g. */
    @Column(name = "occurrence_seq")
    private Integer occurrenceSeq;

    @Column(name = "source_value", length = 200)
    private String sourceValue;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImportRowIssue() {
        // JPA
    }

    public ImportRowIssue(ImportBatch batch, DeliveryFile deliveryFile, Long rowNumber,
                          String issueCode, String message) {
        this.batch = batch;
        this.deliveryFile = deliveryFile;
        this.rowNumber = rowNumber;
        this.issueCode = issueCode;
        this.message = message;
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

    public DeliveryFile getDeliveryFile() {
        return deliveryFile;
    }

    /** {@code null} bij een probleem op leverings- of structuurniveau. */
    public Long getRowNumber() {
        return rowNumber;
    }

    public String getIssueCode() {
        return issueCode;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public RowIssueSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(RowIssueSeverity severity) {
        this.severity = severity;
    }

    public IssueDomain getIssueDomain() {
        return issueDomain;
    }

    public void setIssueDomain(IssueDomain issueDomain) {
        this.issueDomain = issueDomain;
    }

    public ControlLevel getControlLevel() {
        return controlLevel;
    }

    public void setControlLevel(ControlLevel controlLevel) {
        this.controlLevel = controlLevel;
    }

    public ImpactScope getImpactScope() {
        return impactScope;
    }

    public void setImpactScope(ImpactScope impactScope) {
        this.impactScope = impactScope;
    }

    public Long getIssueGroupId() {
        return issueGroupId;
    }

    /** @return de foutsignatuur, of {@code null} wanneer dit probleem bij geen groep hoort */
    public String getSignature() {
        return signature;
    }

    public IssueHandlingStatus getHandlingStatus() {
        return handlingStatus;
    }

    public Integer getRuleConfigVersion() {
        return ruleConfigVersion;
    }

    public String getExpectedValue() {
        return expectedValue;
    }

    public void setExpectedValue(String expectedValue) {
        this.expectedValue = expectedValue;
    }

    public Integer getOccurrenceSeq() {
        return occurrenceSeq;
    }

    public String getSourceValue() {
        return sourceValue;
    }

    public void setSourceValue(String sourceValue) {
        this.sourceValue = sourceValue;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
