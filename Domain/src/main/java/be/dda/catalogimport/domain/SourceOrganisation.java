package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Bronorganisatie: een leverancier of aankoopvereniging die bronbestanden aanlevert.
 * <p>
 * Businessanalyse §15.2 punt 2 en §16.1: de bronorganisatie is een zakelijk gegeven, los van
 * de technische levering (folder, SFTP, API, document). Eén bronorganisatie kan meerdere
 * leveranciers leveren en door meerdere importdefinities hergebruikt worden.
 * <p>
 * De bronorganisatie is <b>scope</b>, nooit een onderdeel van de aanbiedingsidentiteit
 * (beslissingslog 18/09 "Fase 0: aanbiedingsidentiteit").
 */
@Entity
@Table(name = "source_organisation",
        uniqueConstraints = @UniqueConstraint(name = "uk_source_organisation_code", columnNames = "code"))
public class SourceOrganisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Stabiele zakelijke referentie, bv. {@code VROOAM} of {@code 02006}. */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "organisation_type", nullable = false, length = 40)
    private SourceOrganisationType organisationType;

    /** Inactieve bronorganisaties blijven bestaan voor historiek en traceerbaarheid. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SourceOrganisation() {
        // JPA
    }

    public SourceOrganisation(String code, String name, SourceOrganisationType organisationType) {
        this.code = code;
        this.name = name;
        this.organisationType = organisationType;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SourceOrganisationType getOrganisationType() {
        return organisationType;
    }

    public void setOrganisationType(SourceOrganisationType organisationType) {
        this.organisationType = organisationType;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
