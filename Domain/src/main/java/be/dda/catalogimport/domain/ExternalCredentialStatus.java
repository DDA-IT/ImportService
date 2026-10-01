package be.dda.catalogimport.domain;

/**
 * Opgeslagen status van een {@link ExternalCredential} ({@code ck_external_credential_status}, changeset 013-1).
 * {@code UNDECRYPTABLE} bestaat bewust niet als waarde: dat is een afgeleide toestand (de ciphertext ontsleutelt
 * niet met de geconfigureerde sleutelring), nooit opgeslagen (A6).
 */
public enum ExternalCredentialStatus {
    /** Er is een versleutelde waarde ({@code ciphertext} en {@code encryption_key_id} gevuld). */
    ACTIVE,
    /** Ingetrokken: {@code ciphertext} en {@code encryption_key_id} zijn {@code null} (crypto-shred), reden verplicht. */
    REVOKED
}
