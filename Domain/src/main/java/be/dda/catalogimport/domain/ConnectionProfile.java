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
 * De kop van een verbindingsprofiel (changeset 014-1, {@code docs/design/leveringsconfiguratie-design.md} par. 3.2):
 * stabiele {@code code} en naam. De verbindingsgegevens (host, poort, login, credential, hostsleutel) staan in de
 * onveranderlijke {@link ConnectionProfileVersion}-rijen (L3). Enkel naam en {@code active} zijn wijzigbaar.
 */
@Entity
@Table(name = "connection_profile",
        uniqueConstraints = @UniqueConstraint(name = "uk_connection_profile_code", columnNames = {"code"}))
public class ConnectionProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, updatable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol", nullable = false, updatable = false, length = 20)
    private ConnectionProtocol protocol;

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

    protected ConnectionProfile() {
        // JPA
    }

    /** Een nieuw, actief profiel. Meldingen bij ongeldige invoer noemen enkel de naam van het gegeven. */
    public ConnectionProfile(String code, String name, ConnectionProtocol protocol, String createdBy,
                             String createdBySubject, Instant createdAt) {
        this.code = ConfigurationGuard.requiredText(code, "code", 50);
        this.name = ConfigurationGuard.requiredText(name, "name", 200);
        this.protocol = ConfigurationGuard.required(protocol, "protocol");
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

    public ConnectionProtocol getProtocol() {
        return protocol;
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
        return "ConnectionProfile[id=" + id + ", code=" + code + ", protocol=" + protocol + ", active=" + active + "]";
    }
}
