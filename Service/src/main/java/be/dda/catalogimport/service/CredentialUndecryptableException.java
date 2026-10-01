package be.dda.catalogimport.service;

/**
 * {@link #CODE}: een versleutelde waarde kan niet ontsleuteld worden (onbekende sleutel-ID, verkeerd formaat,
 * gewijzigde ciphertext of tag, of afwijkende associated data). De boodschap bevat nooit de waarde, de
 * ciphertext of sleutelmateriaal. Een aanroeper mag dit nooit stil overslaan (beslissingslog 2026-09-29, V5).
 */
public class CredentialUndecryptableException extends ConflictException {

    public static final String CODE = "CREDENTIAL_UNDECRYPTABLE";

    public CredentialUndecryptableException(String reason) {
        super(CODE, "The stored credential cannot be decrypted: " + reason);
    }
}
