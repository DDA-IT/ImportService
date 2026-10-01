package be.dda.catalogimport.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalCredentialLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-03-01T00:00:00Z");

    private static ExternalCredential newCredential(String host) {
        return new ExternalCredential(UUID.randomUUID(), "label", ExternalCredentialSecretKind.SFTP_PASSWORD, host,
                "cipher-0", "key-0", "creator", "creator-sub", T0);
    }

    private static ExternalCredential revoked() {
        ExternalCredential credential = newCredential("sftp.example.com");
        credential.recordRevocation("admin", "admin-sub", T1, "gelekt");
        return credential;
    }

    private static ExternalCredential with(UUID ref, String label, ExternalCredentialSecretKind kind, String cipher,
                                           String keyId, String createdBy, Instant createdAt) {
        return new ExternalCredential(ref, label, kind, "h", cipher, keyId, createdBy, null, createdAt);
    }

    @Test
    void newCredentialIsActiveWithSecretSet() {
        ExternalCredential credential = newCredential("sftp.example.com");
        assertThat(credential.getStatus()).isEqualTo(ExternalCredentialStatus.ACTIVE);
        assertThat(credential.isSecretSet()).isTrue();
        assertThat(credential.getSecretUpdatedAt()).isEqualTo(T0);
        assertThat(credential.getSecretUpdatedBy()).isEqualTo("creator");
        assertThat(credential.getBoundHost()).isEqualTo("sftp.example.com");
    }

    @Test
    void replacementFromActiveStoresNewValueAndAudit() {
        ExternalCredential credential = newCredential("sftp.example.com");
        credential.recordReplacement("cipher-1", "key-1", "bob", "bob-sub", T1);
        assertThat(credential.getStatus()).isEqualTo(ExternalCredentialStatus.ACTIVE);
        assertThat(credential.getCiphertext()).isEqualTo("cipher-1");
        assertThat(credential.getEncryptionKeyId()).isEqualTo("key-1");
        assertThat(credential.getSecretUpdatedBy()).isEqualTo("bob");
        assertThat(credential.getSecretUpdatedBySubject()).isEqualTo("bob-sub");
        assertThat(credential.getSecretUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void replacementFromRevokedIsRejected() {
        ExternalCredential credential = revoked();
        assertThatThrownBy(() -> credential.recordReplacement("c", "k", "bob", null, T2))
                .isInstanceOf(IllegalStateException.class);
        assertThat(credential.isSecretSet()).isFalse();
        assertThat(credential.getStatus()).isEqualTo(ExternalCredentialStatus.REVOKED);
    }

    @Test
    void revocationFromActiveClearsSecretAndRecordsAudit() {
        ExternalCredential credential = revoked();
        assertThat(credential.getStatus()).isEqualTo(ExternalCredentialStatus.REVOKED);
        assertThat(credential.getCiphertext()).isNull();
        assertThat(credential.getEncryptionKeyId()).isNull();
        assertThat(credential.isSecretSet()).isFalse();
        assertThat(credential.getRevokedBy()).isEqualTo("admin");
        assertThat(credential.getRevokedBySubject()).isEqualTo("admin-sub");
        assertThat(credential.getRevokedAt()).isEqualTo(T1);
        assertThat(credential.getRevokedReason()).isEqualTo("gelekt");
    }

    @Test
    void revocationFromRevokedIsRejected() {
        ExternalCredential credential = revoked();
        assertThatThrownBy(() -> credential.recordRevocation("admin", null, T2, "nogmaals"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(credential.getRevokedReason()).isEqualTo("gelekt");
        assertThat(credential.getRevokedAt()).isEqualTo(T1);
    }

    @Test
    void revocationRequiresByAtAndReasonAndLeavesStateUntouchedOnFailure() {
        ExternalCredential credential = newCredential("sftp.example.com");
        assertThatThrownBy(() -> credential.recordRevocation(" ", null, T1, "r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credential.recordRevocation("a", null, null, "r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credential.recordRevocation("a", null, T1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(credential.getStatus()).isEqualTo(ExternalCredentialStatus.ACTIVE);
        assertThat(credential.isSecretSet()).isTrue();
    }

    @Test
    void reactivationFromRevokedRestoresActiveAndClearsRevocationFields() {
        ExternalCredential credential = revoked();
        credential.recordReactivation("cipher-2", "key-2", "carol", "carol-sub", T2);
        assertThat(credential.getStatus()).isEqualTo(ExternalCredentialStatus.ACTIVE);
        assertThat(credential.getCiphertext()).isEqualTo("cipher-2");
        assertThat(credential.getEncryptionKeyId()).isEqualTo("key-2");
        assertThat(credential.getSecretUpdatedBy()).isEqualTo("carol");
        assertThat(credential.getSecretUpdatedAt()).isEqualTo(T2);
        assertThat(credential.getRevokedAt()).isNull();
        assertThat(credential.getRevokedBy()).isNull();
        assertThat(credential.getRevokedBySubject()).isNull();
        assertThat(credential.getRevokedReason()).isNull();
    }

    @Test
    void reactivationFromActiveIsRejected() {
        ExternalCredential credential = newCredential("sftp.example.com");
        assertThatThrownBy(() -> credential.recordReactivation("c", "k", "carol", null, T2))
                .isInstanceOf(IllegalStateException.class);
        assertThat(credential.getCiphertext()).isEqualTo("cipher-0");
    }

    /** De code normaliseert niet stil: een niet-genormaliseerde host wordt geweigerd. */
    @Test
    void boundHostMustBeNormalizedAndIsNotNormalizedSilently() {
        assertThat(newCredential("a.b-c.example").getBoundHost()).isEqualTo("a.b-c.example");
        for (String bad : new String[] {"SFTP.Example.com", "sftp.example.com.", "sftp example.com",
                " sftp.example.com", "", " ", null}) {
            assertThatThrownBy(() -> newCredential(bad)).as("host [%s]", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void constructorRequiresMandatoryValues() {
        UUID ref = UUID.randomUUID();
        ExternalCredentialSecretKind kind = ExternalCredentialSecretKind.SSH_PRIVATE_KEY;
        assertThatThrownBy(() -> with(null, "l", kind, "c", "k", "u", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(ref, "", kind, "c", "k", "u", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(ref, "l", null, "c", "k", "u", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(ref, "l", kind, " ", "k", "u", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(ref, "l", kind, "c", null, "u", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(ref, "l", kind, "c", "k", " ", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(ref, "l", kind, "c", "k", "u", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNeverContainsTheCiphertext() {
        assertThat(newCredential("sftp.example.com").toString()).doesNotContain("cipher-0");
    }
}
