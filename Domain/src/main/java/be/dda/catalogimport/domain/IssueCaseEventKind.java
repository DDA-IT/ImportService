package be.dda.catalogimport.domain;

/**
 * Het soort gebeurtenis in het append-only auditregister {@link IssueCaseEvent} (design par. 1,
 * changeset 012-2).
 */
public enum IssueCaseEventKind {

    /** Het behandelgeval is voor het eerst aangemaakt; draagt geen {@code previousStatus}. */
    CREATED,

    /** De status van het behandelgeval is gewijzigd, handmatig of door het systeem. */
    STATUS_CHANGE
}
