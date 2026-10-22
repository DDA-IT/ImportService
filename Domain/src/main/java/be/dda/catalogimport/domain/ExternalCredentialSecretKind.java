package be.dda.catalogimport.domain;

/**
 * Soort secret van een {@link ExternalCredential} ({@code ck_external_credential_secret_kind}, changesets 013-1 en
 * 029-1). De naam maakt deel uit van de associated data van de versleuteling
 * ({@code catalogimport|external_credential|<credential_ref>|<secret_kind>|v1}): hernoemen maakt elke bestaande
 * ciphertext onleesbaar.
 */
public enum ExternalCredentialSecretKind {
    /** SFTP-wachtwoord; de enige soort die een mens aanmaakt en die in een verbindingsprofiel past (A5). */
    SFTP_PASSWORD,
    /** Gereserveerd voor sleutelauthenticatie; nog niet in gebruik (A5). */
    SSH_PRIVATE_KEY,
    /**
     * FTP-wachtwoord, enkel bewaard bij de legacy-config-overname (beslissingslog 2026-10-22 "Legacy-config: ook FTP-
     * en API-wachtwoorden bewaren"). Enkel bewaring: geen FTP-connector, niet toegelaten in een verbindingsprofiel.
     */
    FTP_PASSWORD,
    /**
     * Wachtwoord van een legacy-API-bestemming ({@code FTP/LOCAL/API} = {@code A}), enkel bewaard bij de
     * legacy-config-overname (beslissingslog 2026-10-22). Enkel bewaring: geen API-connector, niet toegelaten in een
     * verbindingsprofiel.
     */
    API_PASSWORD
}
