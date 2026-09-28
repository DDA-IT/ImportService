package be.dda.catalogimport.domain;

/**
 * De statusmachine van een {@link IssueCase} (docs/design/issue-case-design.md par. 4, R-CASE-01
 * t/m R-CASE-04). Vier waarden, geen enkele terminaal.
 * <p>
 * Bewust geen {@code ACCEPTED_*}-achtige waarde (D3): een behandelgeval laat per definitie niets
 * door. Zie ook de waarschuwing op {@link IssueHandlingStatus}.
 */
public enum IssueCaseStatus {

    /** Initieel bij aanmaak; wacht op een menselijke beoordeling of een systeemvaststelling. */
    AWAITING_REVIEW,

    /** Door een mens gecorrigeerd. */
    CORRECTED,

    /** Door een mens afgewezen; een identieke herlevering blijft onderdrukt (R-CASE-02). */
    REJECTED,

    /**
     * Gedeclareerd, in deze ronde door geen enkel codepad gezet (design par. 6, aanname A1):
     * "zonder tussenkomst opgelost" vereist een vaststelling die vandaag niet bestaat.
     */
    AUTO_RESOLVED
}
