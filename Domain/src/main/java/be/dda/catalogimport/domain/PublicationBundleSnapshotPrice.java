package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Eén prijscomponent van een {@link PublicationBundleSnapshot} (ontwerp fase 5-PUB par. 1, changeset
 * 008-2): een exacte kopie van {@code import_candidate_price}, zonder herberekening of afronding.
 * Sleutel {@code (snapshotId, componentCode)}, volgens het {@code @IdClass}-patroon van
 * {@link ImportRevisionFieldCriticality}.
 */
@Entity
@Table(name = "publication_bundle_snapshot_price")
@IdClass(PublicationBundleSnapshotPrice.Key.class)
public class PublicationBundleSnapshotPrice {

    /** Samengestelde sleutel: snapshot + componentcode. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long snapshotId;
        private String componentCode;

        public Key() {
            // JPA
        }

        public Key(Long snapshotId, String componentCode) {
            this.snapshotId = snapshotId;
            this.componentCode = componentCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(snapshotId, key.snapshotId) && Objects.equals(componentCode, key.componentCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshotId, componentCode);
        }
    }

    @Id
    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Id
    @Column(name = "component_code", nullable = false, length = 20)
    private String componentCode;

    @Column(name = "source_amount", precision = 24, scale = 6)
    private BigDecimal sourceAmount;

    @Column(name = "percentage", precision = 24, scale = 12)
    private BigDecimal percentage;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    protected PublicationBundleSnapshotPrice() {
        // JPA
    }

    public PublicationBundleSnapshotPrice(Long snapshotId, String componentCode, BigDecimal sourceAmount,
                                          BigDecimal percentage, String currency, String status) {
        this.snapshotId = snapshotId;
        this.componentCode = componentCode;
        this.sourceAmount = sourceAmount;
        this.percentage = percentage;
        this.currency = currency;
        this.status = status;
    }

    public Long getSnapshotId() {
        return snapshotId;
    }

    public String getComponentCode() {
        return componentCode;
    }

    public BigDecimal getSourceAmount() {
        return sourceAmount;
    }

    public BigDecimal getPercentage() {
        return percentage;
    }

    public String getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }
}
