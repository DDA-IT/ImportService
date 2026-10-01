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
import java.time.Instant;

/**
 * Een onveranderlijke versie van een {@link DeliveryConfiguration} (changeset 014-4,
 * {@code docs/design/leveringsconfiguratie-design.md} par. 3.2, L3). Een versie in gebruik wijzigt nooit: alle
 * kolommen zijn {@code updatable = false} en er zijn geen setters; een wijziging is een nieuwe versie die een taak
 * expliciet overneemt. De bestandsvoorwaarden zitten in {@link DeliveryConfigurationFileCondition} (opgevraagd via
 * de repository, niet als collectie op deze entiteit).
 * <p>
 * De constructor weigert dezelfde fouten als de databasechecks (post-fetch-actie is enkel {@code LEAVE}, geen
 * negatieve ouderdom, positieve maximale grootte). De servicecontrole "{@code ALL_FILES} = geen voorwaarden,
 * {@code CONDITIONS} = minstens één" en de regels voor {@code remoteDirectory} (A17) horen bij LC-2.
 */
@Entity
@Table(name = "delivery_configuration_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_delivery_configuration_version_number",
                columnNames = {"delivery_configuration_id", "version_number"}))
public class DeliveryConfigurationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_configuration_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_delivery_configuration_version_configuration"))
    private DeliveryConfiguration deliveryConfiguration;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_profile_version_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_delivery_configuration_version_profile_version"))
    private ConnectionProfileVersion connectionProfileVersion;

    @Column(name = "remote_directory", nullable = false, updatable = false, length = 500)
    private String remoteDirectory;

    @Enumerated(EnumType.STRING)
    @Column(name = "selection_mode", nullable = false, updatable = false, length = 20)
    private DeliverySelectionMode selectionMode;

    @Column(name = "min_file_age_seconds", nullable = false, updatable = false)
    private int minFileAgeSeconds;

    @Column(name = "max_file_bytes", nullable = false, updatable = false)
    private long maxFileBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_fetch_action", nullable = false, updatable = false, length = 20)
    private DeliveryPostFetchAction postFetchAction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "based_on_version_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_delivery_configuration_version_based_on"))
    private DeliveryConfigurationVersion basedOnVersion;

    @Column(name = "change_reason", updatable = false, length = 500)
    private String changeReason;

    @Column(name = "config_hash", nullable = false, updatable = false, length = 64)
    private String configHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    /** OIDC-{@code sub}; {@code null} = geen geverifieerde identiteit (changeset 007-conventie). */
    @Column(name = "created_by_subject", updatable = false, length = 255)
    private String createdBySubject;

    protected DeliveryConfigurationVersion() {
        // JPA
    }

    /**
     * @throws IllegalArgumentException een gegeven ontbreekt of is ongeldig (de melding noemt enkel de naam van
     *                                  het gegeven, nooit een waarde)
     */
    public DeliveryConfigurationVersion(DeliveryConfiguration deliveryConfiguration, int versionNumber,
                                        ConnectionProfileVersion connectionProfileVersion, String remoteDirectory,
                                        DeliverySelectionMode selectionMode, int minFileAgeSeconds,
                                        long maxFileBytes, DeliveryPostFetchAction postFetchAction,
                                        DeliveryConfigurationVersion basedOnVersion, String changeReason,
                                        String configHash, String createdBy, String createdBySubject,
                                        Instant createdAt) {
        this.deliveryConfiguration = ConfigurationGuard.required(deliveryConfiguration, "deliveryConfiguration");
        this.versionNumber = ConfigurationGuard.atLeast(versionNumber, 1, "versionNumber");
        this.connectionProfileVersion = ConfigurationGuard.required(connectionProfileVersion,
                "connectionProfileVersion");
        this.remoteDirectory = ConfigurationGuard.requiredText(remoteDirectory, "remoteDirectory", 500);
        this.selectionMode = ConfigurationGuard.required(selectionMode, "selectionMode");
        this.minFileAgeSeconds = ConfigurationGuard.atLeast(minFileAgeSeconds, 0, "minFileAgeSeconds");
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes is out of range");
        }
        this.maxFileBytes = maxFileBytes;
        this.postFetchAction = ConfigurationGuard.required(postFetchAction, "postFetchAction");
        this.basedOnVersion = basedOnVersion;
        this.changeReason = ConfigurationGuard.optionalText(changeReason, "changeReason", 500);
        this.configHash = ConfigurationGuard.requiredText(configHash, "configHash", 64);
        this.createdBy = ConfigurationGuard.requiredText(createdBy, "createdBy", 100);
        this.createdBySubject = ConfigurationGuard.optionalText(createdBySubject, "createdBySubject", 255);
        this.createdAt = ConfigurationGuard.required(createdAt, "createdAt");
    }

    public Long getId() {
        return id;
    }

    public DeliveryConfiguration getDeliveryConfiguration() {
        return deliveryConfiguration;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public ConnectionProfileVersion getConnectionProfileVersion() {
        return connectionProfileVersion;
    }

    public String getRemoteDirectory() {
        return remoteDirectory;
    }

    public DeliverySelectionMode getSelectionMode() {
        return selectionMode;
    }

    public int getMinFileAgeSeconds() {
        return minFileAgeSeconds;
    }

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public DeliveryPostFetchAction getPostFetchAction() {
        return postFetchAction;
    }

    public DeliveryConfigurationVersion getBasedOnVersion() {
        return basedOnVersion;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public String getConfigHash() {
        return configHash;
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

    /** Bewust zonder de externe map (L7b): die hoort enkel in MANAGE-antwoorden. */
    @Override
    public String toString() {
        return "DeliveryConfigurationVersion[id=" + id + ", versionNumber=" + versionNumber + ", selectionMode="
                + selectionMode + ", postFetchAction=" + postFetchAction + "]";
    }
}
