package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Bouwstap 5a (sjabloon-materialisatie-design.md §2, §3): {@link RevisionConfigHashes} is verhuisd uit
 * de private {@code hash(...)} van {@code SetupService}, byte-identiek. Deze test bewijst dat door de
 * oude berekening hier onafhankelijk te reconstrueren (niet door {@link RevisionConfigHashes} aan te
 * roepen) en tegen de verhuisde implementatie te vergelijken, voor dezelfde configuratie-onderdelen als
 * {@code SetupService.createRevision} gebruikt.
 */
class RevisionConfigHashesTest {

    @Test
    void hashIsByteIdenticalToTheOldSetupServiceImplementation() {
        assertHashMatchesOldImplementation("access", "THREE_PART", "FULL_ARCHIVE");
        assertHashMatchesOldImplementation("structure", "CSV", "UTF-8", ";", ",", "true", "1",
                "HEADER_NAME", "5");
        assertHashMatchesOldImplementation("record", "THREE_PART", "SUPPLIER_FIELD", "GROUP_FIELD",
                "REFERENCE_FIELD", "null", "PRICE_FIELD", "null", "null", "1");
        assertHashMatchesOldImplementation("composite", "abc123", "def456", "ghi789");
    }

    @Test
    void differentPartsProduceDifferentHashes() {
        String base = RevisionConfigHashes.hash("record", "A", "B");
        String changed = RevisionConfigHashes.hash("record", "A", "C");
        assertThat(base).isNotEqualTo(changed);
    }

    @Test
    void isDeterministicForTheSameInput() {
        String first = RevisionConfigHashes.hash("structure", "CSV", "UTF-8");
        String second = RevisionConfigHashes.hash("structure", "CSV", "UTF-8");
        assertThat(first).isEqualTo(second);
    }

    private static void assertHashMatchesOldImplementation(String layer, String... parts) {
        assertThat(RevisionConfigHashes.hash(layer, parts)).isEqualTo(oldSetupServiceHash(layer, parts));
    }

    /**
     * Onafhankelijke reconstructie van de OUDE, nu verwijderde private {@code SetupService.hash(...)}:
     * laagnaam gevolgd door elk onderdeel, telkens voorafgegaan door {@code U+001F} (unit separator),
     * SHA-256 over UTF-8-bytes, hexadecimaal. Bewust hier gekopieerd (niet gedeeld) zodat deze test
     * onafhankelijk blijft van de verhuisde implementatie die ze net controleert.
     */
    private static String oldSetupServiceHash(String layer, String... parts) {
        StringBuilder canonical = new StringBuilder(layer);
        for (String part : parts) {
            canonical.append('').append(part);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required but not available", impossible);
        }
    }
}
