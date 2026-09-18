package be.dda.catalogimport.domain;

/**
 * Status van een {@link ImportMutation}. Volledig gedeclareerd voor latere fasen; Fase 2 zet enkel
 * {@link #PLANNED}, {@link #RECORDED} en {@link #SKIPPED}.
 */
public enum MutationStatus {
    PLANNED,
    BLOCKED,
    AWAITING_APPROVAL,
    READY_FOR_PUBLICATION,
    IN_PROGRESS,
    PUBLISHED,
    TECHNICALLY_FAILED,
    REJECTED,
    EXPIRED,
    SKIPPED,
    RECORDED
}
