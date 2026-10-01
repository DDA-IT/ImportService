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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Eén bestandsvoorwaarde van een {@link DeliveryConfigurationVersion} (changeset 014-5). Semantiek: voorwaarden
 * binnen een groep ({@code groupNumber}) zijn EN, groepen zijn OF. Onveranderlijk zoals haar versie: geen setters,
 * alle kolommen {@code updatable = false}. {@code bookmarkName} is nullable en ongebruikt (T13).
 */
@Entity
@Table(name = "delivery_configuration_file_condition",
        uniqueConstraints = @UniqueConstraint(name = "uk_delivery_configuration_file_condition_order",
                columnNames = {"dc_version_id", "group_number", "sequence_number"}))
public class DeliveryConfigurationFileCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dc_version_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_delivery_configuration_file_condition_version"))
    private DeliveryConfigurationVersion deliveryConfigurationVersion;

    @Column(name = "group_number", nullable = false, updatable = false)
    private int groupNumber;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_kind", nullable = false, updatable = false, length = 30)
    private DeliveryFileConditionKind conditionKind;

    @Column(name = "compare_value", nullable = false, updatable = false, length = 200)
    private String compareValue;

    @Column(name = "case_sensitive", nullable = false, updatable = false)
    private boolean caseSensitive;

    @Column(name = "bookmark_name", updatable = false, length = 100)
    private String bookmarkName;

    protected DeliveryConfigurationFileCondition() {
        // JPA
    }

    /** @throws IllegalArgumentException een gegeven ontbreekt of is ongeldig (de melding noemt geen waarde) */
    public DeliveryConfigurationFileCondition(DeliveryConfigurationVersion deliveryConfigurationVersion,
                                              int groupNumber, int sequenceNumber,
                                              DeliveryFileConditionKind conditionKind, String compareValue,
                                              boolean caseSensitive, String bookmarkName) {
        this.deliveryConfigurationVersion = ConfigurationGuard.required(deliveryConfigurationVersion,
                "deliveryConfigurationVersion");
        this.groupNumber = ConfigurationGuard.atLeast(groupNumber, 0, "groupNumber");
        this.sequenceNumber = ConfigurationGuard.atLeast(sequenceNumber, 0, "sequenceNumber");
        this.conditionKind = ConfigurationGuard.required(conditionKind, "conditionKind");
        this.compareValue = ConfigurationGuard.requiredText(compareValue, "compareValue", 200);
        this.caseSensitive = caseSensitive;
        this.bookmarkName = ConfigurationGuard.optionalText(bookmarkName, "bookmarkName", 100);
    }

    public Long getId() {
        return id;
    }

    public DeliveryConfigurationVersion getDeliveryConfigurationVersion() {
        return deliveryConfigurationVersion;
    }

    public int getGroupNumber() {
        return groupNumber;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public DeliveryFileConditionKind getConditionKind() {
        return conditionKind;
    }

    public String getCompareValue() {
        return compareValue;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public String getBookmarkName() {
        return bookmarkName;
    }

    @Override
    public String toString() {
        return "DeliveryConfigurationFileCondition[id=" + id + ", groupNumber=" + groupNumber + ", sequenceNumber="
                + sequenceNumber + ", conditionKind=" + conditionKind + "]";
    }
}
