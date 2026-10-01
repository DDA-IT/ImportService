package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.dao.SecretKeyCheckDao;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Opstartcontrole {@code secret_key_check}: hoort elke geconfigureerde sleutel bij <b>deze</b> database? Bouwstap
 * K-2a ({@code docs/design/credentials-sleutelbeheer-design.md} par. 5.3; beslissingslog 2026-09-29, V7 en A7).
 * <p>
 * <b>Gedrag.</b>
 * <ul>
 *   <li><b>Geen sleutelring</b> ({@code catalogimport.secrets.*} niet gezet): niets — de functie staat uit (V7).</li>
 *   <li>Voor <b>elke</b> sleutel in de ring (niet enkel de actieve):
 *     <ul>
 *       <li>er is een rij: die moet met deze sleutel ontsleutelen tot de bekende controletekst, anders start de
 *           applicatie <b>niet</b> ({@link IllegalStateException}). Oorzaak is dan: ander sleutelmateriaal onder
 *           dezelfde ID (een sleutel van een andere omgeving of database, of een tikfout), of een gewijzigde rij.
 *           Doorstarten zou nieuwe credentials versleutelen met een sleutel die hier niet thuishoort;</li>
 *       <li>er is geen rij: aanmaken. Een bestaande rij wordt nooit overschreven.</li>
 *     </ul></li>
 *   <li>Een sleutel-ID in {@code external_credential} die <b>niet</b> in de ring staat: enkel een waarschuwing
 *       in de log. Die credentials zijn dan (afgeleid) {@code UNDECRYPTABLE}; de applicatie start wel (V5, A6).</li>
 * </ul>
 * <b>Wanneer.</b> Als {@link SmartInitializingSingleton}: nadat alle singletons — ook Liquibase, dus het schema
 * staat er — aangemaakt zijn, en vóór de webserver verzoeken aanneemt. Een fout breekt het opstarten van de
 * context af. Er is bewust geen transactie: elke insert is op zich atomair, en een {@code DuplicateKeyException}
 * binnen een PostgreSQL-transactie zou die transactie onbruikbaar maken.
 * <p>
 * <b>Lekpreventie.</b> Logs en meldingen noemen enkel sleutel-ID's, nooit sleutelmateriaal of een controlewaarde.
 */
@Component
public class SecretKeyCheckVerifier implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(SecretKeyCheckVerifier.class);

    private final SecretsService secrets;
    private final SecretKeyCheckDao checks;
    private final ExternalCredentialRepository credentials;

    public SecretKeyCheckVerifier(SecretsService secrets, SecretKeyCheckDao checks,
                                  ExternalCredentialRepository credentials) {
        this.secrets = secrets;
        this.checks = checks;
        this.credentials = credentials;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verify();
    }

    /**
     * Voert de controle uit (zie de klassebeschrijving). Idempotent: een tweede aanroep met dezelfde sleutelring
     * maakt niets bij en wijzigt niets.
     *
     * @throws IllegalStateException een geconfigureerde sleutel hoort niet bij deze database
     */
    public void verify() {
        if (!secrets.configured()) {
            return;
        }
        List<String> ring = secrets.keyIds();
        for (String keyId : ring) {
            verifyKey(keyId);
        }
        for (String usedKeyId : credentials.findDistinctEncryptionKeyIds()) {
            if (!ring.contains(usedKeyId)) {
                LOG.warn("Stored credentials are encrypted with key id '{}', which is not in "
                        + "catalogimport.secrets.keys: they cannot be decrypted (CREDENTIAL_UNDECRYPTABLE) until "
                        + "that key is restored or the values are entered again", usedKeyId);
            }
        }
    }

    private void verifyKey(String keyId) {
        Optional<String> stored = checks.findCheckValue(keyId);
        if (stored.isEmpty()) {
            if (checks.insertIfAbsent(keyId, secrets.keyCheckValue(keyId), Instant.now())) {
                LOG.info("Secrets key check: control value recorded for key id '{}'", keyId);
                return;
            }
            // Een andere instantie was ons net voor: haar rij is de maatstaf, dus die controleren.
            stored = checks.findCheckValue(keyId);
        }
        if (stored.isEmpty() || !secrets.matchesKeyCheckValue(keyId, stored.get())) {
            throw new IllegalStateException("catalogimport.secrets.keys: key id '" + keyId + "' does not belong "
                    + "to this database (its secret_key_check control value does not decrypt with the configured "
                    + "key material). Configure the original key for this id, or use a new key id with an "
                    + "environment prefix; the application does not start with a key that is not its own");
        }
        LOG.info("Secrets key check: key id '{}' verified against this database", keyId);
    }

    @Override
    public String toString() {
        return "SecretKeyCheckVerifier[configured=" + secrets.configured() + "]";
    }
}
