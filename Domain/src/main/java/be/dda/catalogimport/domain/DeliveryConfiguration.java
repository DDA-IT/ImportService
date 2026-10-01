package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * De kop van een Leveringsconfiguratie (changeset 014-3, {@code docs/design/leveringsconfiguratie-design.md}
 * par. 3.2): stabiele {@code code} en naam. De ophaalinstellingen staan in de onveranderlijke
 * {@link DeliveryConfigurationVersion}-rijen (L3). Enkel naam en {@code active} zijn wijzigbaar.
 */
@Entity
@Table(name = "delivery_configuration",
        uniqueConstraints = @UniqueConstraint(name = "uk_delivery_configuration_code", columnNames = {"code"}))
public class DeliveryConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, updatable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition_kind", nullable = false, updatable = false, length = 20)
    private AcquisitionKind acquisitionKind;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    /** OIDC-{@code sub}; {@code null} = geen geverifieerde identiteit (changeset 007-conventie). */
    @Column(name = "created_by_subject", updatable = false, length = 255)
    private String createdBySubject;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DeliveryConfiguration() {
        // JPA
    }

    /** Een nieuwe, actieve configuratie. Meldingen bij ongeldige invoer noemen enkel de naam van het gegeven. */
    public DeliveryConfiguration(String code, String name, AcquisitionKind acquisitionKind, String createdBy,
                                 String createdBySubject, Instant createdAt) {
        this.code = ConfigurationGuard.requiredText(code, "code", 50);
        this.name = ConfigurationGuard.requiredText(name, "name", 200);
        this.acquisitionKind = ConfigurationGuard.required(acquisitionKind, "acquisitionKind");
        this.createdBy = ConfigurationGuard.requiredText(createdBy, "createdBy", 100);
        ConfigurationGuard.optionalText(createdBySubject, "createdBySubject", 255);
        this.createdBySubject = createdBySubject;
        this.createdAt = ConfigurationGuard.required(createdAt, "createdAt");
        this.updatedAt = createdAt;
        this.active = true;
    }

    public void rename(String newName, Instant at) {
        this.name = ConfigurationGuard.requiredText(newName, "name", 200);
        this.updatedAt = ConfigurationGuard.required(at, "updatedAt");
    }

    public void setActive(boolean active, Instant at) {
        this.active = active;
        this.updatedAt = ConfigurationGuard.required(at, "updatedAt");
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AcquisitionKind getAcquisitionKind() {
        return acquisitionKind;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getCreatedBySubject() {
        return createdBySubject;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "DeliveryConfiguration[id=" + id + ", code=" + code + ", acquisitionKind=" + acquisitionKind
                + ", active=" + active + "]";
    }
}
