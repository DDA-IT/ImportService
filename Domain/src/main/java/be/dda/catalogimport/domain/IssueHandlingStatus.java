package be.dda.catalogimport.domain;

/**
 * De behandelstatus van een {@link ImportRowIssue} (ontwerp fase 3, R-ISS-04).
 * <p>
 * De volledige levenscyclus is hier bewust gedeclareerd, maar <b>fase 3 zet uitsluitend
 * {@link #DETECTED}</b>. De overige waarden horen bij het goedkeurings- en uitzonderingsbeheer van een
 * latere fase; ze nu al vastleggen voorkomt een migratie van historische issuerijen zodra dat beheer er
 * komt. Een waarde die nog niet gezet wordt, wordt ook niet stilzwijgend gezet.
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
