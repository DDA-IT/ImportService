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
 * Een probleem bij één fysieke bronregel van een {@link ImportBatch}. Bulk-schrijven gebeurt via
 * JdbcTemplate; deze entiteit is bedoeld om te lezen (paginering) en voor tests.
 * {@code sourceValue} is afgekapt; de brontekst zelf staat enkel in het gearchiveerde bestand.
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_file_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_row_issue_file"))
    private DeliveryFile deliveryFile;

    /** Fysiek regelnummer (1-gebaseerd, inclusief header/prefix). */
    @Column(name = "row_number", nullable = false)
    private long rowNumber;

    @Column(name = "issue_code", nullable = false, length = 60)
    private String issueCode;

    @Column(name = "field_name", length = 200)
    private String fieldName;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 20)
    private RowIssueSeverity severity = RowIssueSeverity.ERROR;

    @Column(name = "source_value", length = 200)
    private String sourceValue;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImportRowIssue() {
        // JPA
    }

    public ImportRowIssue(ImportBatch batch, DeliveryFile deliveryFile, long rowNumber,
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

    public long getRowNumber() {
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
