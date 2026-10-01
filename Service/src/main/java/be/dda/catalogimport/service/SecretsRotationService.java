package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ExternalCredentialEventRepository;
import be.dda.catalogimport.dao.ExternalCredentialRepository;
import be.dda.catalogimport.domain.ExternalCredential;
import be.dda.catalogimport.domain.ExternalCredentialEvent;
import be.dda.catalogimport.domain.ExternalCredentialEventKind;
import be.dda.catalogimport.domain.ExternalCredentialEventSource;
import be.dda.catalogimport.domain.ExternalCredentialStatus;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Herversleutelen naar de actieve sleutel bij opstart (R2a): bouwstap K-2b van
 * {@code docs/design/credentials-sleutelbeheer-design.md} (par. 5.2, 6), beslissingslog 2026-09-29 (V4, V5, A6, A7).
 * <p>
 * <b>Regel.</b> Na een sleutelrotatie (nieuwe sleutel in {@code CATALOG_SECRETS_KEYS}, {@code CATALOG_SECRETS_ACTIVE_KEY_ID}
 * omgezet) staan bestaande credentials nog onder de oude sleutel. Deze batch versleutelt elke {@code ACTIVE}-rij onder
 * een andere sleutel dan de actieve opnieuw met de actieve sleutel, zodat de oude sleutel na de back-upretentie weg kan
 * (pas bij 0 rijen eronder, zie {@link ExternalCredentialRepository#countRowsPerEncryptionKeyId()}).
 * <p>
 * <b>Ordening t.o.v. {@link SecretKeyCheckVerifier}.</b> {@code SmartInitializingSingleton}-callbacks lopen in
 * bean-registratievolgorde, niet in afhankelijkheidsvolgorde; enkel een constructorafhankelijkheid garandeert dus
 * niets. Daarom roept {@link #afterSingletonsInstantiated()} eerst expliciet {@link SecretKeyCheckVerifier#verify()}
 * aan (idempotent: hoort een sleutel niet bij deze database, dan start de applicatie niet en is er nooit iets
 * herversleuteld) en pas daarna {@link #rotate()}. Geen wijziging aan de verifier zelf.
 * <p>
 * <b>Per rij, eigen transactie</b> ({@link TransactionTemplate}, conventie van de andere services): ontsleutelen met
 * dezelfde associated data (credential_ref + secret_kind), versleutelen met de actieve sleutel, ciphertext en
 * {@code encryption_key_id} bijwerken en één {@code REENCRYPTED}-event (source {@code SYSTEM}, {@code changed_by}
 * null, {@code previous_key_id}, {@code new_key_id}) schrijven. {@code secret_updated_*} blijft ongemoeid: de waarde zelf
 * is niet veranderd, enkel haar omhulling.
 * <p>
 * <b>Gelijktijdigheid.</b> De entiteit heeft geen {@code @Version}; de update is een optimistische guard
 * ({@link ExternalCredentialRepository#replaceCiphertextIfUnchanged}: {@code where id, ciphertext, encryption_key_id,
 * status} nog zoals gelezen). Enkel bij 1 geraakte rij wordt het event geschreven, in dezelfde transactie; bij 0 rijen
 * (een andere instantie was eerst, of de rij is intussen vervangen/ingetrokken) gebeurt er niets. Zo geen dubbel event
 * en geen verloren update.
 * <p>
 * <b>Uitzonderingen.</b> Geen config: niets. Een rij onder een sleutel-ID buiten de ring wordt niet geselecteerd
 * (enkel de bestaande WARN van de verifier). Een rij die niet ontsleutelt (corrupt, verkeerde context): WARN met
 * {@code CREDENTIAL_UNDECRYPTABLE}, credential-id en sleutel-ID (nooit een waarde of ciphertext), volgende rij
 * gewoon verwerkt, de applicatie start. {@code REVOKED}-rijen worden nooit aangeraakt. INFO-log: enkel aantallen
 * per oude sleutel-ID.
 */
@Component
public class SecretsRotationService implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsRotationService.class);

    static final String REASON = "Automatisch herversleuteld naar de actieve sleutel bij opstart";

    private final SecretsService secrets;
    private final SecretKeyCheckVerifier verifier;
    private final ExternalCredentialRepository credentials;
    private final ExternalCredentialEventRepository events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public SecretsRotationService(SecretsService secrets, SecretKeyCheckVerifier verifier,
                                  ExternalCredentialRepository credentials,
                                  ExternalCredentialEventRepository events,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.secrets = secrets;
        this.verifier = verifier;
        this.credentials = credentials;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!secrets.configured()) {
            return;
        }
        verifier.verify();
        rotate();
    }

    /**
     * Herversleutelt alle kandidaten. Idempotent: een tweede aanroep vindt geen kandidaten meer.
     *
     * @return per oude sleutel-ID het aantal herversleutelde rijen (leeg als er niets te doen was of geen config)
     */
    public Map<String, Integer> rotate() {
        Map<String, Integer> rotated = new LinkedHashMap<>();
        if (!secrets.configured()) {
            return rotated;
        }
        String active = secrets.activeKeyId();
        List<ExternalCredential> candidates = credentials.findRotationCandidates(
                ExternalCredentialStatus.ACTIVE, active, secrets.keyIds());
        for (ExternalCredential candidate : candidates) {
            String previous = candidate.getEncryptionKeyId();
            try {
                if (rotateRow(candidate)) {
                    rotated.merge(previous, 1, Integer::sum);
                }
            } catch (CredentialUndecryptableException undecryptable) {
                LOG.warn("Credential {} (key id '{}') was not re-encrypted: CREDENTIAL_UNDECRYPTABLE (the stored "
                        + "value does not decrypt; enter it again)", candidate.getId(), previous);
            } catch (RuntimeException failure) {
                // Herversleutelen mag het opstarten nooit breken; de rij blijft onder de oude sleutel en komt
                // bij de volgende start opnieuw aan bod. Enkel het type, nooit de melding (kan data bevatten).
                LOG.warn("Credential {} (key id '{}') was not re-encrypted: {}", candidate.getId(), previous,
                        failure.getClass().getSimpleName());
            }
        }
        rotated.forEach((keyId, count) -> LOG.info(
                "Secrets rotation: {} credential(s) re-encrypted from key id '{}' to active key id '{}'",
                count, keyId, active));
        return rotated;
    }

    /**
     * Herversleutelt één rij in een eigen transactie op basis van de gelezen momentopname.
     *
     * @return {@code true} als deze aanroep de rij herversleuteld heeft, {@code false} als de guard 0 rijen raakte
     * @throws CredentialUndecryptableException de waarde ontsleutelt niet
     */
    boolean rotateRow(ExternalCredential candidate) {
        String activeKeyId = secrets.activeKeyId();
        String previousKeyId = candidate.getEncryptionKeyId();
        String plaintext = secrets.decrypt(candidate.getCiphertext(), candidate.getCredentialRef(),
                candidate.getSecretKind().name());
        String reEncrypted = secrets.encrypt(plaintext, candidate.getCredentialRef(),
                candidate.getSecretKind().name());
        Boolean done = transaction.execute(status -> {
            int touched = credentials.replaceCiphertextIfUnchanged(candidate.getId(), candidate.getCiphertext(),
                    previousKeyId, reEncrypted, activeKeyId, ExternalCredentialStatus.ACTIVE);
            if (touched == 0) {
                return false;
            }
            events.save(new ExternalCredentialEvent(credentials.getReferenceById(candidate.getId()),
                    ExternalCredentialEventKind.REENCRYPTED, REASON, ExternalCredentialEventSource.SYSTEM, null,
                    null, clock.instant(), previousKeyId, activeKeyId));
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    @Override
    public String toString() {
        return "SecretsRotationService[configured=" + secrets.configured() + "]";
    }
}
