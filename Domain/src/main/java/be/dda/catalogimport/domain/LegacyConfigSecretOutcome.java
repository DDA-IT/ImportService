package be.dda.catalogimport.domain;

/**
 * Wat er met een meegestuurd legacy-wachtwoord gebeurde (changeset 020-1, {@code secret_outcome};
 * {@code docs/design/legacy-config-bundle-design.md} par. 2 "Credential bij ontvangst"). Gezet vanaf slice S4, enkel
 * bij een nieuwe ontvangst ({@code RECEIVED}); {@code null} = er kwam geen secret mee. Een push die {@code UNCHANGED}
 * is, schrijft niets en gooit het secret weg (aanname A1).
 */
public enum LegacyConfigSecretOutcome {
    /** Nieuw credential versleuteld aangemaakt. */
    STORED,
    /** Er bestond al een credential voor deze herkomst; niets vergeleken of vervangen (A1). */
    DISCARDED_ALREADY_PRESENT,
    /** FTP of onbekend protocol (schema van {@code FTP}+{@code FTP2}, A7): geen SFTP-credential mogelijk. */
    DISCARDED_PROTOCOL_UNSUPPORTED,
    /** Geen bestemming, of geen geldige host om het credential aan te binden. */
    DISCARDED_NO_HOST
}
