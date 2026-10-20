package be.dda.catalogimport.domain;

/**
 * Wat er met een meegestuurd legacy-wachtwoord gebeurde (changeset 020-1, {@code secret_outcome};
 * {@code docs/design/legacy-config-bundle-design.md} par. 2 "Credential bij ontvangst"). Pas vanaf slice S4 gezet: in
 * S1 wordt een bundel met secret geweigerd en blijft {@code secret_outcome} {@code null} (= geen secret ontvangen).
 */
public enum LegacyConfigSecretOutcome {
    /** Nieuw credential versleuteld aangemaakt. */
    STORED,
    /** Er bestond al een credential voor deze herkomst; niets vergeleken of vervangen (A1). */
    DISCARDED_ALREADY_PRESENT,
    /** FTP of onbekend protocol: geen SFTP-credential mogelijk. */
    DISCARDED_PROTOCOL_UNSUPPORTED,
    /** Geen of ongeldige host om het credential aan te binden. */
    DISCARDED_NO_HOST
}
