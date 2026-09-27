package be.dda.catalogimport.domain;

/**
 * Herkomst van de effectieve basismunt van een aanbieding (docs/design/valuta-standaard-design.md
 * par. 1 en 3). {@code null} in de database betekent "herkomst onbekend" (rijen van vóór de
 * valuta-standaard) en is bewust geen enumwaarde.
 */
public enum CurrencyOrigin {

    /** De munt komt uit een gemapt bronveld van de levering. */
    SOURCE,

    /** Geen bronveld gemapt; de vaste valuta van de koppeling ({@code import_link.default_currency}) geldt. */
    LINK_DEFAULT,

    /** Geen bronveld en geen vaste valuta van de koppeling; de systeemstandaard (EUR) geldt. */
    SYSTEM_DEFAULT
}
