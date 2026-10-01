package be.dda.catalogimport.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Versleutelt en ontsleutelt de secrets van externe bronnen (SFTP/API-wachtwoorden): stap K-1 van
 * {@code docs/design/credentials-sleutelbeheer-design.md}, beslissingslog 2026-09-29 (V1-V8).
 * <p>
 * <b>Codering.</b> JDK {@code javax.crypto}, {@code AES/GCM/NoPadding}, AES-256, 96-bit nonce uit één
 * {@link SecureRandom} (per versleuteling vers), 128-bit tag. Uitvoer: {@code v1:<keyId>:<base64(nonce‖ciphertext+tag)>}
 * met de <b>actieve</b> sleutel. Ontsleutelen kiest de sleutel op het keyId in de waarde. Associated data
 * (gebouwd in deze klasse) is {@code catalogimport|external_credential|<credential_ref>|<secretKind>|v1}: een
 * ciphertext kan zo niet naar een andere credential of ander doel verplaatst worden.
 * <p>
 * <b>Controlewaarde (K-2a).</b> Een tweede, aparte associated-data-vorm
 * {@code catalogimport|secret_key_check|<keyId>|v1} dient enkel voor {@code secret_key_check}
 * ({@link #keyCheckValue}, {@link #matchesKeyCheckValue}): per sleutel een bekende tekst, waarmee
 * {@link SecretKeyCheckVerifier} bij het opstarten vaststelt of de sleutel bij deze database hoort. Formaat en
 * associated data van credentials zijn daardoor niet gewijzigd.
 * <p>
 * <b>Configuratie</b> (geen default, nooit een sleutel in {@code application*.yml}):
 * <ul>
 *   <li>{@code catalogimport.secrets.keys} = {@code id1:base64,id2:base64} (env-var {@code CATALOG_SECRETS_KEYS});
 *       elke sleutel is base64 van exact 32 bytes ({@code openssl rand -base64 32});</li>
 *   <li>{@code catalogimport.secrets.active-key-id} (env-var {@code CATALOG_SECRETS_ACTIVE_KEY_ID}): de sleutel die
 *       versleutelt; de andere sleutels ontsleutelen enkel (rotatie).</li>
 * </ul>
 * <b>Uit of aan (V7).</b> Beide leeg = functie uit: de app start, {@link #configured()} is {@code false} en
 * gebruik geeft {@link SecretsNotConfiguredException}. Iets gezet maar ongeldig = {@link IllegalStateException}
 * bij opstart. Meldingen bevatten nooit sleutelmateriaal.
 * <p>
 * <b>Lekpreventie.</b> Plaintext, ciphertext en sleutels komen nooit in logs, exceptiemeldingen of
 * {@link #toString()}.
 */
@Component
public class SecretsService {

    private static final Logger LOG = LoggerFactory.getLogger(SecretsService.class);

    static final String VERSION = "v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9-]{1,32}");

    /**
     * De bekende tekst van {@code secret_key_check} (K-2a). Geen geheim: de controle bewijst enkel dat een sleutel
     * met deze ID dezelfde is als die waarmee de controlewaarde in deze database ooit gemaakt werd. Nooit wijzigen:
     * elke bestaande controlewaarde zou dan afwijken en de applicatie zou niet meer starten.
     */
    static final String KEY_CHECK_TEXT = "CatalogImport secret key check v1";

    private final SecureRandom random = new SecureRandom();
    /** Leeg wanneer niet geconfigureerd. */
    private final Map<String, SecretKeySpec> keyRing;
    private final String activeKeyId;

    public SecretsService(@Value("${catalogimport.secrets.keys:}") String keys,
                          @Value("${catalogimport.secrets.active-key-id:}") String activeKeyId) {
        boolean noKeys = keys == null || keys.isBlank();
        boolean noActive = activeKeyId == null || activeKeyId.isBlank();
        if (noKeys && noActive) {
            this.keyRing = Map.of();
            this.activeKeyId = null;
            LOG.info("catalogimport.secrets.* is not set; encrypted credential storage is disabled");
            return;
        }
        if (noKeys) {
            throw invalid("catalogimport.secrets.active-key-id is set but catalogimport.secrets.keys is empty");
        }
        if (noActive) {
            throw invalid("catalogimport.secrets.keys is set but catalogimport.secrets.active-key-id is empty");
        }
        Map<String, SecretKeySpec> ring = new LinkedHashMap<>();
        Map<String, byte[]> material = new LinkedHashMap<>();
        for (String entry : keys.split(",", -1)) {
            String trimmed = entry.strip();
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                throw invalid("catalogimport.secrets.keys: an entry is not of the form <keyId>:<base64>");
            }
            String id = trimmed.substring(0, colon).strip();
            String encoded = trimmed.substring(colon + 1).strip();
            if (!KEY_ID.matcher(id).matches()) {
                throw invalid("catalogimport.secrets.keys: a key id must match [A-Za-z0-9-]{1,32}");
            }
            if (ring.containsKey(id)) {
                throw invalid("catalogimport.secrets.keys: duplicate key id '" + id + "'");
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException notBase64) {
                throw invalid("catalogimport.secrets.keys: key '" + id + "' is not valid base64");
            }
            if (bytes.length != KEY_BYTES) {
                throw invalid("catalogimport.secrets.keys: key '" + id + "' must be exactly " + KEY_BYTES
                        + " bytes (base64 of 32 bytes)");
            }
            for (Map.Entry<String, byte[]> other : material.entrySet()) {
                if (MessageDigest.isEqual(other.getValue(), bytes)) {
                    throw invalid("catalogimport.secrets.keys: keys '" + other.getKey() + "' and '" + id
                            + "' have identical key material");
                }
            }
            material.put(id, bytes);
            ring.put(id, new SecretKeySpec(bytes, "AES"));
        }
        String active = activeKeyId.strip();
        if (!ring.containsKey(active)) {
            throw invalid("catalogimport.secrets.active-key-id does not refer to a key in catalogimport.secrets.keys");
        }
        this.keyRing = Map.copyOf(ring);
        this.activeKeyId = active;
        LOG.info("Secrets key ring loaded: {} key(s), active key id '{}'", ring.size(), active);
    }

    /** {@code false} wanneer geen sleutelring geconfigureerd is: versleutelen en ontsleutelen weigeren dan. */
    public boolean configured() {
        return activeKeyId != null;
    }

    /** Het sleutel-ID waarmee nu versleuteld wordt (voor de kolom {@code encryption_key_id}). */
    public String activeKeyId() {
        requireConfigured();
        return activeKeyId;
    }

    /**
     * Versleutelt met de actieve sleutel.
     *
     * @return {@code v1:<keyId>:<base64(nonce‖ciphertext+tag)>}
     * @throws SecretsNotConfiguredException geen sleutelring
     */
    public String encrypt(String plaintext, UUID credentialRef, String secretKind) {
        requireConfigured();
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext is required");
        }
        byte[] aad = associatedData(credentialRef, secretKind);
        return seal(activeKeyId, plaintext, aad);
    }

    /**
     * Ontsleutelt met de sleutel die het keyId in de waarde aanwijst.
     *
     * @throws SecretsNotConfiguredException  geen sleutelring
     * @throws CredentialUndecryptableException onbekend keyId, verkeerd formaat, tag- of AD-mismatch
     */
    public String decrypt(String encrypted, UUID credentialRef, String secretKind) {
        requireConfigured();
        byte[] aad = associatedData(credentialRef, secretKind);
        if (encrypted == null) {
            throw new CredentialUndecryptableException("no stored value");
        }
        return open(encrypted, aad, null);
    }

    /**
     * De sleutel-ID's in de ring, oplopend gesorteerd — nooit sleutelmateriaal. Leeg wanneer niet geconfigureerd.
     * Voor de opstartcontrole {@code secret_key_check} (K-2a, A7): elke sleutel wordt tegen deze database
     * gecontroleerd.
     */
    public List<String> keyIds() {
        return keyRing.keySet().stream().sorted().toList();
    }

    /**
     * De controlewaarde voor {@code secret_key_check} (K-2a, credentials-design par. 5.3): de vaste tekst
     * {@link #KEY_CHECK_TEXT}, versleuteld met <b>deze</b> sleutel (niet noodzakelijk de actieve) onder de eigen
     * associated data {@code catalogimport|secret_key_check|<keyId>|v1}. Zelfde formaat als een credential
     * ({@code v1:<keyId>:<base64>}); de associated data van credentials blijft ongewijzigd.
     *
     * @throws SecretsNotConfiguredException geen sleutelring
     * @throws IllegalArgumentException      het sleutel-ID staat niet in de ring
     */
    public String keyCheckValue(String keyId) {
        requireConfigured();
        requireKeyInRing(keyId);
        return seal(keyId, KEY_CHECK_TEXT, keyCheckAssociatedData(keyId));
    }

    /**
     * Ontsleutelt een opgeslagen controlewaarde met de sleutel {@code keyId} en vergelijkt ze met
     * {@link #KEY_CHECK_TEXT}. {@code false} bij elke afwijking — ander sleutelmateriaal onder dezelfde ID,
     * een gewijzigde of onleesbare waarde, of een waarde die onder een ander sleutel-ID versleuteld is. De
     * aanroeper beslist wat dat betekent (bij opstart: niet starten).
     *
     * @throws SecretsNotConfiguredException geen sleutelring
     * @throws IllegalArgumentException      het sleutel-ID staat niet in de ring
     */
    public boolean matchesKeyCheckValue(String keyId, String storedValue) {
        requireConfigured();
        requireKeyInRing(keyId);
        if (storedValue == null) {
            return false;
        }
        try {
            return KEY_CHECK_TEXT.equals(open(storedValue, keyCheckAssociatedData(keyId), keyId));
        } catch (CredentialUndecryptableException mismatch) {
            return false;
        }
    }

    /** Versleutelt met de sleutel {@code keyId}; het formaat is {@code v1:<keyId>:<base64(nonce‖ciphertext+tag)>}. */
    private String seal(String keyId, String plaintext, byte[] aad) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, keyRing.get(keyId), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, payload, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, payload, NONCE_BYTES, sealed.length);
            return VERSION + ":" + keyId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException failure) {
            // Bewust zonder oorzaak-keten met data: de JCE-meldingen bevatten geen plaintext, maar we houden het schoon.
            throw new IllegalStateException("Encryption failed (" + failure.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Ontsleutelt met de sleutel die het keyId in de waarde aanwijst. {@code requiredKeyId} (optioneel) eist dat
     * de waarde onder precies die sleutel-ID versleuteld is.
     */
    private String open(String encrypted, byte[] aad, String requiredKeyId) {
        String[] parts = encrypted.split(":", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new CredentialUndecryptableException("unsupported format");
        }
        if (requiredKeyId != null && !requiredKeyId.equals(parts[1])) {
            throw new CredentialUndecryptableException("the value was encrypted under another key id");
        }
        SecretKeySpec key = keyRing.get(parts[1]);
        if (key == null) {
            throw new CredentialUndecryptableException("the encryption key is not in the key ring");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(parts[2]);
        } catch (IllegalArgumentException notBase64) {
            throw new CredentialUndecryptableException("unsupported format");
        }
        if (payload.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new CredentialUndecryptableException("unsupported format");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, 0, NONCE_BYTES));
            cipher.updateAAD(aad);
            byte[] plain = cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException tagOrContextMismatch) {
            throw new CredentialUndecryptableException("integrity check failed (wrong key, altered value or "
                    + "different credential context)");
        }
    }

    private void requireKeyInRing(String keyId) {
        if (keyId == null || !keyRing.containsKey(keyId)) {
            throw new IllegalArgumentException("the key id is not in the key ring");
        }
    }

    /** Tweede, aparte associated-data-vorm (K-2a): een controlewaarde kan nooit als credential gelezen worden. */
    private static byte[] keyCheckAssociatedData(String keyId) {
        return ("catalogimport|secret_key_check|" + keyId + "|" + VERSION).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] associatedData(UUID credentialRef, String secretKind) {
        if (credentialRef == null) {
            throw new IllegalArgumentException("credentialRef is required");
        }
        if (secretKind == null || secretKind.isBlank() || secretKind.indexOf('|') >= 0) {
            throw new IllegalArgumentException("secretKind is required and must not contain '|'");
        }
        return ("catalogimport|external_credential|" + credentialRef + "|" + secretKind + "|" + VERSION)
                .getBytes(StandardCharsets.UTF_8);
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new SecretsNotConfiguredException();
        }
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }

    @Override
    public String toString() {
        return "SecretsService[configured=" + configured() + "]";
    }
}
