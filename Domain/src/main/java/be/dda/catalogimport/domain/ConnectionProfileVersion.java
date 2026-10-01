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
 * Een onveranderlijke versie van een {@link ConnectionProfile} (changeset 014-2,
 * {@code docs/design/leveringsconfiguratie-design.md} par. 3.2, L3-L5). Een versie in gebruik wijzigt nooit: alle
 * kolommen zijn {@code updatable = false} en er zijn geen setters; een wijziging is een nieuwe versie.
 * <p>
 * <b>Credential en host.</b> De credential is in de database via een samengestelde FK
 * {@code (credential_id, credential_secret_kind, credential_host)} aan {@code external_credential (id, secret_kind,
 * bound_host)} gekoppeld, met de checks {@code credential_host = host} en authenticatiemethode = soort credential
 * (L4a). Die drie kolommen worden hier als gewone kolommen bewaard en in de constructor uit de meegegeven
 * {@link ExternalCredential} afgeleid, zodat ze niet uit elkaar kunnen lopen; de constructor weigert dezelfde
 * fouten die de database zou weigeren. De klasse bevat nooit een secret: enkel een verwijzing naar de credential.
 * <p>
 * <b>Logging.</b> {@link #toString()} toont geen host, login of vingerafdruk (L7b).
 */
@Entity
@Table(name = "connection_profile_version",
        uniqueConstraints = @UniqueConstraint(name = "uk_connection_profile_version_number",
                columnNames = {"connection_profile_id", "version_number"}))
public class ConnectionProfileVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_profile_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_connection_profile_version_profile"))
    private ConnectionProfile connectionProfile;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    /** Genormaliseerd (kleine letters, geen punt achteraan) door de aanroeper. */
    @Column(name = "host", nullable = false, updatable = false, length = 255)
    private String host;

    @Column(name = "port", nullable = false, updatable = false)
    private int port;

    @Column(name = "username", nullable = false, updatable = false, length = 200)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_method", nullable = false, updatable = false, length = 20)
    private ConnectionAuthMethod authMethod;

    @Column(name = "credential_id", nullable = false, updatable = false)
    private Long credentialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_secret_kind", nullable = false, updatable = false, length = 40)
    private ExternalCredentialSecretKind credentialSecretKind;

    @Column(name = "credential_host", nullable = false, updatable = false, length = 255)
    private String credentialHost;

    @Column(name = "host_key_algorithm", nullable = false, updatable = false, length = 40)
    private String hostKeyAlgorithm;

    @Column(name = "host_key_fingerprint_sha256", nullable = false, updatable = false, length = 100)
    private String hostKeyFingerprintSha256;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "based_on_version_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_connection_profile_version_based_on"))
    private ConnectionProfileVersion basedOnVersion;

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

    protected ConnectionProfileVersion() {
        // JPA
    }

    /**
     * @param credential een reeds opgeslagen credential (id bekend) waarvan {@code boundHost} gelijk is aan
     *                   {@code host} en waarvan de soort bij {@code authMethod} past
     * @throws IllegalArgumentException een gegeven ontbreekt of is ongeldig (de melding noemt enkel de naam van
     *                                  het gegeven, nooit een waarde)
     */
    public ConnectionProfileVersion(ConnectionProfile connectionProfile, int versionNumber, String host, int port,
                                    String username, ConnectionAuthMethod authMethod, ExternalCredential credential,
                                    String hostKeyAlgorithm, String hostKeyFingerprintSha256,
                                    ConnectionProfileVersion basedOnVersion, String changeReason, String configHash,
                                    String createdBy, String createdBySubject, Instant createdAt) {
        this.connectionProfile = ConfigurationGuard.required(connectionProfile, "connectionProfile");
        this.versionNumber = ConfigurationGuard.atLeast(versionNumber, 1, "versionNumber");
        this.host = ConfigurationGuard.normalizedHost(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port is out of range");
        }
        this.port = port;
        this.username = ConfigurationGuard.requiredText(username, "username", 200);
        this.authMethod = ConfigurationGuard.required(authMethod, "authMethod");
        ConfigurationGuard.required(credential, "credential");
        this.credentialId = ConfigurationGuard.required(credential.getId(), "credential id");
        this.credentialSecretKind = ConfigurationGuard.required(credential.getSecretKind(), "credential secretKind");
        this.credentialHost = credential.getBoundHost();
        if (!this.credentialHost.equals(this.host)) {
            throw new IllegalArgumentException("credential is bound to another host");
        }
        boolean passwordKind = credentialSecretKind == ExternalCredentialSecretKind.SFTP_PASSWORD;
        boolean keyKind = credentialSecretKind == ExternalCredentialSecretKind.SSH_PRIVATE_KEY;
        if ((authMethod == ConnectionAuthMethod.PASSWORD) != passwordKind
                || (authMethod == ConnectionAuthMethod.PRIVATE_KEY) != keyKind) {
            throw new IllegalArgumentException("authMethod does not match the credential secretKind");
        }
        this.hostKeyAlgorithm = ConfigurationGuard.requiredText(hostKeyAlgorithm, "hostKeyAlgorithm", 40);
        this.hostKeyFingerprintSha256 = ConfigurationGuard.requiredText(hostKeyFingerprintSha256,
                "hostKeyFingerprintSha256", 100);
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

    public ConnectionProfile getConnectionProfile() {
        return connectionProfile;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUsername() {
        return username;
    }

    public ConnectionAuthMethod getAuthMethod() {
        return authMethod;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public ExternalCredentialSecretKind getCredentialSecretKind() {
        return credentialSecretKind;
    }

    public String getCredentialHost() {
        return credentialHost;
    }

    public String getHostKeyAlgorithm() {
        return hostKeyAlgorithm;
    }

    public String getHostKeyFingerprintSha256() {
        return hostKeyFingerprintSha256;
    }

    public ConnectionProfileVersion getBasedOnVersion() {
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

    /** Bewust zonder host, login en vingerafdruk (L7b): een log mag die niet verspreiden. */
    @Override
    public String toString() {
        return "ConnectionProfileVersion[id=" + id + ", versionNumber=" + versionNumber + ", authMethod="
                + authMethod + ", credentialId=" + credentialId + "]";
    }
}
