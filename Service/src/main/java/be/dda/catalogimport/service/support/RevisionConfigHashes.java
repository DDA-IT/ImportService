package be.dda.catalogimport.service.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * De configuratiehash van één laag van een {@code ImportDefinitionRevision} (businessanalyse §14.14).
 * <p>
 * <b>Verhuisd uit {@code SetupService}</b> (sjabloon-materialisatie-design.md §2, §3; bouwstap 5a), met
 * <b>exact hetzelfde gedrag</b>: dezelfde canonieke opbouw (laagnaam, gevolgd door elk onderdeel,
 * telkens voorafgegaan door {@code U+001F} — unit separator) en dezelfde SHA-256/hex-omzetting. De
 * verhuizing bestaat omdat de materialisatiewizard een DEFINITION-scope bookmarkwaarde in een
 * revisieveld kan schrijven (bv. {@code REVISION_IDENTITY_FIELD}), waarna de vier hashes van de
 * afgeleide revisie herberekend moeten worden — dezelfde berekening die {@code SetupService} al bij het
 * aanmaken van een revisie gebruikt, nu op één plek gedeeld in plaats van gedupliceerd.
 * <p>
 * Geen enkele bestaande publieke methode of hashwaarde verandert door deze verhuizing: de tests in
 * {@code RevisionConfigHashesTest} bewijzen dit met een byte-vergelijking tegen de oude, nu verwijderde
 * private implementatie in {@code SetupService}.
 */
public final class RevisionConfigHashes {

    private RevisionConfigHashes() {
        // Enkel statische helpers.
    }

    /**
     * De configuratiehash van één laag. De revisie draagt drie laaghashes plus een samengestelde hash
     * (§14.14): ze maken zichtbaar dát een configuratie verschilt. Een verzonnen constante zou dat
     * onderscheid stilzwijgend wegnemen.
     */
    public static String hash(String layer, String... parts) {
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
