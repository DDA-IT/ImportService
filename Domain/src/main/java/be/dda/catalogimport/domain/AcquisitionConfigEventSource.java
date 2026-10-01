package be.dda.catalogimport.domain;

/**
 * Wie een {@link AcquisitionConfigEvent} veroorzaakte ({@code ck_acquisition_config_event_source}, changeset 014-6):
 * {@link #HUMAN} draagt altijd een naam, {@link #SYSTEM} nooit (patroon {@link ExternalCredentialEventSource}).
 */
public enum AcquisitionConfigEventSource {
    HUMAN,
    SYSTEM
}
