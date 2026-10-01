package be.dda.catalogimport.domain;

/**
 * Soort secret van een {@link ExternalCredential} ({@code ck_external_credential_secret_kind}, changeset 013-1).
 * De naam maakt deel uit van de associated data van de versleuteling
 * ({@code catalogimport|external_credential|<credential_ref>|<secret_kind>|v1}): hernoemen maakt elke bestaande
 * ciphertext onleesbaar.
 */
public enum ExternalCredentialSecretKind {
    /** SFTP-wachtwoord; in v1 de enige gebruikte soort (A5). */
    SFTP_PASSWORD,
    /** Gereserveerd voor sleutelauthenticatie; nog niet in gebruik (A5). */
    SSH_PRIVATE_KEY
}
