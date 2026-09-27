package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * De afleidingsregel van {@code deliveryReference} aan de <b>serverkant</b> (beslissingslog 2026-09-27, Q1
 * = optie A). Deze test is met opzet de spiegel van {@code Frontend/src/test/UploadPage.test.tsx}
 * ("deriveDeliveryReference"): dezelfde drie eigenschappen, plus één vaste vector.
 *
 * <ul>
 *   <li><b>Regel:</b> de referentie is afgeleid van naam én inhoud, nooit van een tijdstempel.
 *       <b>Implementatie:</b> {@code <naam>#<eerste 12 hex van de SHA-256>}; <b>data:</b> ze wordt bewaard
 *       als {@code delivery.idempotency_key} met het voorvoegsel {@code manual:}.</li>
 *   <li><b>Uitzondering:</b> een lege naam of een onbruikbare hash levert nooit stil een referentie op.</li>
 * </ul>
 * Waarom dit ertoe doet: loopt deze regel uit elkaar met de browserregel, dan wordt hetzelfde bestand via
 * de servermap een <i>tweede</i> levering in plaats van een idempotente retry. Databasevrij en zonder
 * Spring-context.
 */
class DeliveryReferencesTest {

    private static final String CSV = "a;b\n1;2\n";

    @Test
    void isDeterministicForTheSameNameAndContent() {
        assertThat(DeliveryReferences.derive("levering.csv", sha256Hex(CSV)))
                .isEqualTo(DeliveryReferences.derive("levering.csv", sha256Hex(CSV)));
    }

    @Test
    void anotherContentOrNameGivesAnotherReference() {
        String base = DeliveryReferences.derive("levering.csv", sha256Hex(CSV));

        assertThat(DeliveryReferences.derive("levering.csv", sha256Hex("a;b\n1;3\n"))).isNotEqualTo(base);
        assertThat(DeliveryReferences.derive("andere.csv", sha256Hex(CSV))).isNotEqualTo(base);
    }

    /** Vaste vector: SHA-256("abc") begint met {@code ba7816bf8f01}; zo staat de vorm ook zonder hasher vast. */
    @Test
    void usesTheFirstTwelveHexCharactersOfTheContentHash() {
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

        assertThat(DeliveryReferences.derive("levering.csv", hash)).isEqualTo("levering.csv#ba7816bf8f01");
        // Hoofdletters in de hash geven dezelfde referentie als de browser, die kleine letters produceert.
        assertThat(DeliveryReferences.derive("levering.csv", hash.toUpperCase(java.util.Locale.ROOT)))
                .isEqualTo("levering.csv#ba7816bf8f01");
    }

    @Test
    void staysWithinTheServerLimitOfOneHundredAndNinetyCharacters() {
        String longName = "n".repeat(300) + ".csv";

        String reference = DeliveryReferences.derive(longName, sha256Hex("x"));

        assertThat(reference).hasSize(DeliveryReferences.MAX_LENGTH);
        assertThat(reference).matches(".*#[0-9a-f]{12}$");
        // Exact dezelfde afkapping als `file.name.slice(0, 190 - 13)` in TypeScript.
        assertThat(reference).startsWith(longName.substring(0, 177));
    }

    @Test
    void refusesAMissingNameOrAnUnusableHash() {
        assertThatThrownBy(() -> DeliveryReferences.derive("  ", sha256Hex(CSV)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliveryReferences.derive("levering.csv", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DeliveryReferences.derive("levering.csv", "abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String sha256Hex(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
