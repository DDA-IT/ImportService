package be.dda.catalogimport.domain;

/**
 * De behandelstatus van een {@link ImportRowIssue} (ontwerp fase 3, R-ISS-04).
 * <p>
 * De volledige levenscyclus is hier bewust gedeclareerd, maar <b>fase 3 zet uitsluitend
 * {@link #DETECTED}</b>. De overige waarden horen bij het goedkeurings- en uitzonderingsbeheer van een
 * latere fase; ze nu al vastleggen voorkomt een migratie van historische issuerijen zodra dat beheer er
 * komt. Een waarde die nog niet gezet wordt, wordt ook niet stilzwijgend gezet.
 * <p>
 * <b>Niet hergebruiken voor het behandelgeval ({@code issue_case}).</b> Deze enum bevat
 * {@link #ACCEPTED_FOR_BATCH} en {@link #ACCEPTED_BY_RULE} - precies het soort "geaccepteerd"-achtige
 * waarde dat D3 (docs/decisions.md) voor het behandelgeval verbiedt: een {@code issue_case} mag nooit
 * tegengehouden data laten doorgaan. {@code issue_case.status} gebruikt daarom een eigen enum,
 * {@link IssueCaseStatus} (docs/design/issue-case-design.md par. 0/1), met uitsluitend
 * {@code AWAITING_REVIEW}/{@code CORRECTED}/{@code REJECTED}/{@code AUTO_RESOLVED}. Wie deze twee
 * enums gelijktrekt, bouwt een tweede weg om tegengehouden data door te laten die D3 net uitsluit.
 */
public enum IssueHandlingStatus {

    /** Vastgesteld door de screening; nog niet behandeld. De enige waarde die fase 3 schrijft. */
    DETECTED,

    /** Door een volgende verwerking vanzelf opgelost (bv. de bron leverde corrigerende data). */
    AUTO_RESOLVED,

    /** Wacht op een menselijke beoordeling. */
    AWAITING_REVIEW,

    /** Door een beheerder gecorrigeerd. */
    CORRECTED,

    /** Eenmalig aanvaard voor deze batch. */
    ACCEPTED_FOR_BATCH,

    /** Aanvaard op grond van een vastgelegde uitzonderingsregel. */
    ACCEPTED_BY_RULE,

    /** Afgewezen; de betrokken data wordt niet doorgelaten. */
    REJECTED,

    /** De uitzondering die dit issue onderdrukte is verlopen. */
    EXPIRED_EXCEPTION,

    /** Opnieuw geopend na een eerdere afhandeling. */
    REOPENED
}
