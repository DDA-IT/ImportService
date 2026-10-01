package be.dda.catalogimport.domain;

/**
 * Wie een {@link ExternalCredentialEvent} veroorzaakte ({@code ck_external_credential_event_source}, changeset
 * 013-2): {@link #HUMAN} draagt altijd een naam, {@link #SYSTEM} nooit (patroon {@link IssueCaseEventSource}).
 */
public enum ExternalCredentialEventSource {
    HUMAN,
    SYSTEM
}
