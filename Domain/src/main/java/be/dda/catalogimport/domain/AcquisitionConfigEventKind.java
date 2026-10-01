package be.dda.catalogimport.domain;

/** Soort gebeurtenis in het {@link AcquisitionConfigEvent}-register (changeset 014-6). */
public enum AcquisitionConfigEventKind {
    TASK_BOUND,
    TASK_UNBOUND,
    CONNECTION_TESTED,
    HOST_KEY_SCANNED,
    RETIRED
}
