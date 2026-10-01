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
import java.util.UUID;

/**
 * Een versleuteld opgeslagen credential van een externe bron (SFTP-wachtwoord), changeset 013-1
 * ({@code docs/design/credentials-sleutelbeheer-design.md} par. 5.1, {@code leveringsconfiguratie-design.md}
 * par. 3.1; beslissingslog 2026-09-29 V1-V8, L4a, A6, A19).
 * <p>
 * <b>Wat deze klasse nooit bevat.</b> Een secret in zuivere tekst. {@link #getCiphertext()} geeft de
 * <i>versleutelde</i> kolomwaarde ({@code v1:<keyId>:<base64>}) terug, zodat een server-side component met de
 * sleutel ze kan ontsleutelen; ontsleutelen gebeurt uitsluitend in {@code SecretsService}, nooit hier. Er bestaat
 * geen getter of repositorymethode die een ontsleutelde waarde teruggeeft, en {@link #toString()} toont de
 * ciphertext nooit (enkel of er een waarde is).
 * <p>
 * <b>Identiteit.</b> {@code credentialRef} is een vooraf in Java gegenereerde UUID (A19): de associated data van
 * de versleuteling bevat hem, en een identity-{@code id} bestaat pas na de insert (par. 3). {@code credentialRef}
 * en {@code secretKind} zijn daarom niet bijwerkbaar: een andere waarde zou de ciphertext onleesbaar maken.
 * <p>
 * <b>Status.</b> Enkel {@link ExternalCredentialStatus#ACTIVE}/{@link ExternalCredentialStatus#REVOKED}; of een
 * waarde ontsleutelbaar is ({@code UNDECRYPTABLE}) wordt afgeleid, nooit opgeslagen (A6). De databasechecks
 * houden {@code status}, {@code ciphertext} en {@code encryptionKeyId} onderling consistent.
 * <p>
 * Bouwstap K-2a kent enkel het aanmaken. K-3 voegt de menselijke overgangen toe, aangeroepen door
 * {@code CredentialService}: vervangen ({@link #recordReplacement}), intrekken met crypto-shred
 * ({@link #recordRevocation}, par. 5.4) en heractiveren van een ingetrokken credential ({@link #recordReactivation},
 * beslissingslog 2026-09-29 "K-3: een ingetrokken credential mag heractiveerd worden"). Het herversleutelen (K-2b)
 * loopt via een bulk-update in de repository. De methoden hier zijn bewakers: de service controleert de status
 * eerst en vertaalt een verkeerde status naar een HTTP-foutcode.
 */
@Entity
@Table(name = "external_credential",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_external_credential_ref", columnNames = {"credential_ref"}),
                @UniqueConstraint(name = "uk_external_credential_host_binding",
                        columnNames = {"id", "secret_kind", "bound_host"})
        })
public class ExternalCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "credential_ref", nullable = false, updatable = false)
    private UUID credentialRef;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "secret_kind", nullable = false, updatable = false, length = 40)
    private ExternalCredentialSecretKind secretKind;

    /** Genormaliseerd (kleine letters, geen punt achteraan) door de aanroeper; de credential werkt enkel voor deze host. */
    @Column(name = "bound_host", nullable = false, length = 255)
    private String boundHost;

    /** Versleutelde waarde {@code v1:<keyId>:<base64>}; {@code null} na intrekken. Nooit zuivere tekst. */
    @Column(name = "ciphertext")
    private String ciphertext;

    /** Kopie van het keyId in {@link #ciphertext}; {@code null} exact wanneer die {@code null} is. */
    @Column(name = "encryption_key_id", length = 32)
    private String encryptionKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExternalCredentialStatus status;

    @Column(name = "secret_updated_at")
    private Instant secretUpdatedAt;

    @Column(name = "secret_updated_by", length = 100)
    private String secretUpdatedBy;

    @Column(name = "secret_updated_by_subject", length = 255)
    private String secretUpdatedBySubject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    /** OIDC-{@code sub}; {@code null} = geen geverifieerde identiteit (changeset 007-conventie). */
    @Column(name = "created_by_subject", updatable = false, length = 255)
    private String createdBySubject;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 100)
    private String revokedBy;

    @Column(name = "revoked_by_subject", length = 255)
    private String revokedBySubject;

    @Column(name = "revoked_reason", length = 500)
    private String revokedReason;

    protected ExternalCredential() {
        // JPA
    }

    /**
     * Een nieuwe, {@link ExternalCredentialStatus#ACTIVE} credential. De aanroeper heeft de waarde al versleuteld
     * met {@code SecretsService.encrypt(plaintext, credentialRef, secretKind.name())}; deze klasse krijgt de
     * zuivere tekst nooit te zien. Het eerste instellen telt ook als "laatst ingesteld" ({@code secret_updated_*}).
     *
     * @throws IllegalArgumentException een verplicht gegeven ontbreekt (de melding noemt enkel de naam van het
     *                                  gegeven, nooit een waarde)
     */
    public ExternalCredential(UUID credentialRef, String label, ExternalCredentialSecretKind secretKind,
                              String boundHost, String ciphertext, String encryptionKeyId, String createdBy,
                              String createdBySubject, Instant createdAt) {
        this.credentialRef = required(credentialRef, "credentialRef");
        this.label = requiredText(label, "label");
        this.secretKind = required(secretKind, "secretKind");
        this.boundHost = normalizedHost(boundHost);
        this.ciphertext = requiredText(ciphertext, "ciphertext");
        this.encryptionKeyId = requiredText(encryptionKeyId, "encryptionKeyId");
        this.createdBy = requiredText(createdBy, "createdBy");
        this.createdBySubject = createdBySubject;
        this.createdAt = required(createdAt, "createdAt");
        this.status = ExternalCredentialStatus.ACTIVE;
        this.secretUpdatedAt = createdAt;
        this.secretUpdatedBy = createdBy;
        this.secretUpdatedBySubject = createdBySubject;
    }

    /**
     * Vervangt de waarde van een {@link ExternalCredentialStatus#ACTIVE} credential (K-3, event {@code REPLACED}).
     * De aanroeper heeft de nieuwe waarde al versleuteld met deze {@code credentialRef}/{@code secretKind}.
     *
     * @throws IllegalStateException de credential is niet {@code ACTIVE} (gebruik {@link #recordReactivation})
     */
    public void recordReplacement(String newCiphertext, String newEncryptionKeyId, String updatedBy,
                                  String updatedBySubject, Instant updatedAt) {
        if (status != ExternalCredentialStatus.ACTIVE) {
            throw new IllegalStateException("Only an ACTIVE credential can be replaced");
        }
        storeNewValue(newCiphertext, newEncryptionKeyId, updatedBy, updatedBySubject, updatedAt);
    }

    /**
     * Geeft een {@link ExternalCredentialStatus#REVOKED} credential een nieuwe waarde en zet haar terug op
     * {@code ACTIVE} (beslissingslog 2026-09-29, K-3 heractiveren). De {@code revoked_*}-velden worden leeggemaakt:
     * de rij toont de huidige toestand, de historiek van de intrekking blijft in de events. {@code credentialRef},
     * {@code secretKind} en {@code boundHost} veranderen niet (associated data en host-binding blijven).
     *
     * @throws IllegalStateException de credential is niet {@code REVOKED}
     */
    public void recordReactivation(String newCiphertext, String newEncryptionKeyId, String updatedBy,
                                   String updatedBySubject, Instant updatedAt) {
        if (status != ExternalCredentialStatus.REVOKED) {
            throw new IllegalStateException("Only a REVOKED credential can be reactivated");
        }
        storeNewValue(newCiphertext, newEncryptionKeyId, updatedBy, updatedBySubject, updatedAt);
        this.status = ExternalCredentialStatus.ACTIVE;
        this.revokedAt = null;
        this.revokedBy = null;
        this.revokedBySubject = null;
        this.revokedReason = null;
    }

    /**
     * Intrekken = crypto-shred (par. 5.4): ciphertext en sleutel-ID worden {@code null}, status {@code REVOKED},
     * met wie/wanneer/waarom. {@code secret_updated_*} blijft staan (wanneer de laatste waarde ingesteld werd).
     *
     * @throws IllegalStateException de credential is al {@code REVOKED}, of een verplicht gegeven ontbreekt
     */
    public void recordRevocation(String revokedBy, String revokedBySubject, Instant revokedAt, String reason) {
        if (status != ExternalCredentialStatus.ACTIVE) {
            throw new IllegalStateException("Only an ACTIVE credential can be revoked");
        }
        String by = requiredText(revokedBy, "revokedBy");
        Instant at = required(revokedAt, "revokedAt");
        String why = requiredText(reason, "revokedReason");
        this.ciphertext = null;
        this.encryptionKeyId = null;
        this.status = ExternalCredentialStatus.REVOKED;
        this.revokedBy = by;
        this.revokedBySubject = revokedBySubject;
        this.revokedAt = at;
        this.revokedReason = why;
    }

    private void storeNewValue(String newCiphertext, String newEncryptionKeyId, String updatedBy,
                               String updatedBySubject, Instant updatedAt) {
        String value = requiredText(newCiphertext, "ciphertext");
        String keyId = requiredText(newEncryptionKeyId, "encryptionKeyId");
        String by = requiredText(updatedBy, "secretUpdatedBy");
        Instant at = required(updatedAt, "secretUpdatedAt");
        this.ciphertext = value;
        this.encryptionKeyId = keyId;
        this.secretUpdatedAt = at;
        this.secretUpdatedBy = by;
        this.secretUpdatedBySubject = updatedBySubject;
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /**
     * Weigert (normaliseert niet) een host die de databasecheck {@code ck_external_credential_bound_host_normalized}
     * zou weigeren; normaliseren hoort in de CredentialService. De melding noemt de waarde niet.
     */
    private static String normalizedHost(String host) {
        requiredText(host, "boundHost");
        if (!host.equals(host.toLowerCase(java.util.Locale.ROOT)) || host.endsWith(".")
                || host.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("boundHost must be normalized (lower case, no trailing dot, no whitespace)");
        }
        return host;
    }

    public Long getId() {
        return id;
    }

    public UUID getCredentialRef() {
        return credentialRef;
    }

    public String getLabel() {
        return label;
    }

    public ExternalCredentialSecretKind getSecretKind() {
        return secretKind;
    }

    public String getBoundHost() {
        return boundHost;
    }

    /**
     * De <b>versleutelde</b> kolomwaarde, voor ontsleuteling door {@code SecretsService} op de server. Nooit in
     * een API-antwoord, log of exceptie opnemen (V1, par. 2.5); een API toont hoogstens {@link #isSecretSet()}.
     */
    public String getCiphertext() {
        return ciphertext;
    }

    public String getEncryptionKeyId() {
        return encryptionKeyId;
    }

    /** {@code true} wanneer er een versleutelde waarde opgeslagen is (het {@code secretSet} van V1). */
    public boolean isSecretSet() {
        return ciphertext != null;
    }

    public ExternalCredentialStatus getStatus() {
        return status;
    }

    public Instant getSecretUpdatedAt() {
        return secretUpdatedAt;
    }

    public String getSecretUpdatedBy() {
        return secretUpdatedBy;
    }

    public String getSecretUpdatedBySubject() {
        return secretUpdatedBySubject;
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

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public String getRevokedBySubject() {
        return revokedBySubject;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    /** Bewust zonder {@link #ciphertext}: enkel of er een waarde is (par. 2.5, lekpreventie). */
    @Override
    public String toString() {
        return "ExternalCredential[id=" + id + ", credentialRef=" + credentialRef + ", secretKind=" + secretKind
                + ", boundHost=" + boundHost + ", status=" + status + ", secretSet=" + isSecretSet()
                + ", encryptionKeyId=" + encryptionKeyId + "]";
    }
}
