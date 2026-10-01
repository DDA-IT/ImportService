package be.dda.catalogimport.domain;

/** Soort auditregel van een {@link ExternalCredential} ({@code ck_external_credential_event_kind}, changeset 013-2). */
public enum ExternalCredentialEventKind {
    /** Eerste keer aangemaakt; er is nog geen vorige sleutel. */
    CREATED,
    /** Een mens heeft een nieuwe waarde ingevoerd. */
    REPLACED,
    /** Ingetrokken (crypto-shred); er is daarna geen sleutel meer. */
    REVOKED,
    /** Herversleuteld met de actieve sleutel (K-2b, R2a); vorige en nieuwe sleutel-ID altijd gevuld. */
    REENCRYPTED
}
