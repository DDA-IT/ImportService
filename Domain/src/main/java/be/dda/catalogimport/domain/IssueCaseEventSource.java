package be.dda.catalogimport.domain;

/**
 * Wie een {@link IssueCaseEvent} veroorzaakte (design par. 1/§2, R-CASE-04).
 */
public enum IssueCaseEventSource {

    /** Een menselijke beslissing; draagt altijd {@code changedBy}. */
    HUMAN,

    /** Een automatische heropening (R-CASE-03); draagt nooit {@code changedBy}/{@code changedBySubject}. */
    SYSTEM
}
