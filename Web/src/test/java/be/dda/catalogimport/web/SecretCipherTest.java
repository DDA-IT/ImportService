package be.dda.catalogimport.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.service.CredentialUndecryptableException;
import be.dda.catalogimport.service.SecretsNotConfiguredException;
import be.dda.catalogimport.service.SecretsService;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** K-1: de versleutelcomponent zelf, zonder Spring-context. Testsleutels staan enkel hier. */
class SecretCipherTest {

    static final String KEY_A = b64(1);
    static final String KEY_B = b64(2);
    private static final String PLAIN = "S3cr3t-Wachtwoord!";
    private static final String KIND = "SFTP_PASSWORD";
    private static final UUID REF = UUID.fromString("11111111-2222-3333-4444-555555555555");

    static String b64(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static SecretsService service() {
        return new SecretsService("a:" + KEY_A, "a");
    }

    @Test
    void roundTrip() {
        SecretsService s = service();
        String enc = s.encrypt(PLAIN, REF, KIND);
        assertThat(enc).startsWith("v1:a:").doesNotContain(PLAIN);
        assertThat(s.decrypt(enc, REF, KIND)).isEqualTo(PLAIN);
    }

    @Test
    void emptyAndUnicodeValuesRoundTrip() {
        SecretsService s = service();
        assertThat(s.decrypt(s.encrypt("", REF, KIND), REF, KIND)).isEmpty();
        String unicode = "wächtwoord-€-日本";
        assertThat(s.decrypt(s.encrypt(unicode, REF, KIND), REF, KIND)).isEqualTo(unicode);
    }

    @Test
    void sameValueTwiceGivesDifferentOutput() {
        SecretsService s = service();
        assertThat(s.encrypt(PLAIN, REF, KIND)).isNotEqualTo(s.encrypt(PLAIN, REF, KIND));
    }

    @Test
    void alteredCiphertextOrTagFails() {
        SecretsService s = service();
        String[] parts = s.encrypt(PLAIN, REF, KIND).split(":");
        byte[] payload = Base64.getDecoder().decode(parts[2]);
        for (int index : new int[] {0, 12, payload.length - 1}) { // nonce, ciphertext, tag
            byte[] copy = payload.clone();
            copy[index] ^= 0x01;
            String tampered = parts[0] + ":" + parts[1] + ":" + Base64.getEncoder().encodeToString(copy);
            assertThatThrownBy(() -> s.decrypt(tampered, REF, KIND))
                    .isInstanceOf(CredentialUndecryptableException.class);
        }
        String truncated = parts[0] + ":" + parts[1] + ":"
                + Base64.getEncoder().encodeToString(Arrays.copyOf(payload, payload.length - 1));
        assertThatThrownBy(() -> s.decrypt(truncated, REF, KIND)).isInstanceOf(CredentialUndecryptableException.class);
    }

    @Test
    void differentAssociatedDataFails() {
        SecretsService s = service();
        String enc = s.encrypt(PLAIN, REF, KIND);
        assertThatThrownBy(() -> s.decrypt(enc, UUID.randomUUID(), KIND))
                .isInstanceOf(CredentialUndecryptableException.class);
        assertThatThrownBy(() -> s.decrypt(enc, REF, "API_KEY"))
                .isInstanceOf(CredentialUndecryptableException.class);
    }

    @Test
    void unknownKeyIdAndBadFormatFail() {
        SecretsService s = service();
        String enc = s.encrypt(PLAIN, REF, KIND);
        assertThatThrownBy(() -> s.decrypt(enc.replaceFirst("^v1:a:", "v1:zzz:"), REF, KIND))
                .isInstanceOf(CredentialUndecryptableException.class);
        for (String bad : new String[] {"", "garbage", "v2:a:AAAA", "v1:a", "v1:a:!!!not-base64", "v1:a:AAAA", null}) {
            assertThatThrownBy(() -> s.decrypt(bad, REF, KIND)).isInstanceOf(CredentialUndecryptableException.class);
        }
    }

    @Test
    void oldKeyStillDecryptsWhileTheActiveKeyEncrypts() {
        SecretsService old = new SecretsService("a:" + KEY_A, "a");
        String encWithOld = old.encrypt(PLAIN, REF, KIND);
        SecretsService rotated = new SecretsService("a:" + KEY_A + ",b:" + KEY_B, "b");
        assertThat(rotated.activeKeyId()).isEqualTo("b");
        assertThat(rotated.decrypt(encWithOld, REF, KIND)).isEqualTo(PLAIN);
        String encWithNew = rotated.encrypt(PLAIN, REF, KIND);
        assertThat(encWithNew).startsWith("v1:b:");
        // Zonder de nieuwe sleutel in de ring is de nieuwe waarde niet leesbaar.
        assertThatThrownBy(() -> old.decrypt(encWithNew, REF, KIND))
                .isInstanceOf(CredentialUndecryptableException.class);
    }

    @Test
    void wrongKeyMaterialUnderTheSameIdFails() {
        String enc = service().encrypt(PLAIN, REF, KIND);
        SecretsService other = new SecretsService("a:" + KEY_B, "a");
        assertThatThrownBy(() -> other.decrypt(enc, REF, KIND)).isInstanceOf(CredentialUndecryptableException.class);
    }

    @Test
    void notConfiguredMeansFunctionOff() {
        SecretsService off = new SecretsService("", "");
        assertThat(off.configured()).isFalse();
        assertThatThrownBy(() -> off.encrypt(PLAIN, REF, KIND)).isInstanceOf(SecretsNotConfiguredException.class)
                .hasMessageNotContaining(PLAIN);
        assertThatThrownBy(() -> off.decrypt("v1:a:AAAA", REF, KIND))
                .isInstanceOf(SecretsNotConfiguredException.class);
        assertThatThrownBy(off::activeKeyId).isInstanceOf(SecretsNotConfiguredException.class);
        assertThat(new SecretsService(null, null).configured()).isFalse();
        assertThat(new SecretsService("  ", "  ").configured()).isFalse();
        assertThat(new SecretsNotConfiguredException().getCode()).isEqualTo("SECRETS_NOT_CONFIGURED");
    }

    @Test
    void invalidArgumentsAreRejected() {
        SecretsService s = service();
        assertThatThrownBy(() -> s.encrypt(null, REF, KIND)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.encrypt(PLAIN, null, KIND)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.encrypt(PLAIN, REF, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.encrypt(PLAIN, REF, "A|B")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exceptionMessagesAndToStringNeverLeakPlaintextOrKeys() {
        SecretsService s = service();
        String enc = s.encrypt(PLAIN, REF, KIND);
        String payload = enc.split(":")[2];
        assertThat(s.toString()).doesNotContain(KEY_A).doesNotContain(PLAIN);
        Throwable[] failures = {
                catchDecrypt(s, enc.replaceFirst("^v1:a:", "v1:zzz:"), REF, KIND),
                catchDecrypt(s, enc, UUID.randomUUID(), KIND),
                catchDecrypt(s, "v1:a:!!!" + PLAIN, REF, KIND),
        };
        for (Throwable failure : failures) {
            assertThat(failure).isInstanceOf(CredentialUndecryptableException.class);
            assertThat(failure.getMessage()).doesNotContain(PLAIN).doesNotContain(KEY_A).doesNotContain(payload);
            assertThat(((CredentialUndecryptableException) failure).getCode()).isEqualTo("CREDENTIAL_UNDECRYPTABLE");
        }
    }

    private static Throwable catchDecrypt(SecretsService s, String value, UUID ref, String kind) {
        try {
            s.decrypt(value, ref, kind);
            return null;
        } catch (RuntimeException expected) {
            return expected;
        }
    }
}
