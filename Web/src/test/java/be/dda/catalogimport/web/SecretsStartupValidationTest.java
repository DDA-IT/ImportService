package be.dda.catalogimport.web;

import static be.dda.catalogimport.web.SecretCipherTest.KEY_A;
import static be.dda.catalogimport.web.SecretCipherTest.KEY_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.service.SecretsService;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** K-1 (V7): config aanwezig maar ongeldig = fail-fast bij constructie, dus bij opstart. */
class SecretsStartupValidationTest {

    private static void assertRejected(String keys, String active) {
        assertThatThrownBy(() -> new SecretsService(keys, active))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY_A).doesNotContain(KEY_B));
    }

    @Test
    void validConfigurationStarts() {
        assertThatCode(() -> new SecretsService("a:" + KEY_A + " , b-2:" + KEY_B, " b-2 ")).doesNotThrowAnyException();
        assertThat(new SecretsService("a:" + KEY_A, "a").configured()).isTrue();
    }

    @Test
    void notBase64IsRejected() {
        assertRejected("a:not*base64!", "a");
    }

    @Test
    void wrongLengthIsRejected() {
        assertRejected("a:" + Base64.getEncoder().encodeToString(new byte[16]), "a");
        assertRejected("a:" + Base64.getEncoder().encodeToString(new byte[33]), "a");
        assertRejected("a:", "a");
    }

    @Test
    void duplicateKeyIdIsRejected() {
        assertRejected("a:" + KEY_A + ",a:" + KEY_B, "a");
    }

    @Test
    void invalidKeyIdIsRejected() {
        assertRejected(":" + KEY_A, "x");
        assertRejected("a_b:" + KEY_A, "a_b");
        assertRejected("a b:" + KEY_A, "a b");
        assertRejected("x".repeat(33) + ":" + KEY_A, "x".repeat(33));
        assertRejected("noColonHere", "noColonHere");
        assertRejected("a:" + KEY_A + ",", "a");
    }

    @Test
    void activeKeyIdMissingFromTheRingIsRejected() {
        assertRejected("a:" + KEY_A, "b");
    }

    @Test
    void sameMaterialUnderTwoIdsIsRejected() {
        assertRejected("a:" + KEY_A + ",b:" + KEY_A, "a");
    }

    @Test
    void keysWithoutActiveIdOrTheOtherWayAroundIsRejected() {
        assertRejected("a:" + KEY_A, "");
        assertRejected("a:" + KEY_A, null);
        assertRejected("", "a");
        assertRejected(null, "a");
    }
}
