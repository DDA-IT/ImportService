package be.dda.catalogimport.domain;

/**
 * De toegelaten transformaties op een gemapte bronwaarde (ontwerp fase 3, R-REC-07).
 * <p>
 * Bewust een <b>gesloten opsomming</b>: er is geen vrije expressietaal. Een onbekende
 * {@code transform_kind} is een configuratiefout ({@code CONFIG_TRANSFORM_INVALID}) en wordt nooit
 * als "geen transformatie" behandeld.
 * <p>
 * Bouwstap 3b valideert enkel dat de soort bestaat; het uitvoeren van de transformaties (inclusief
 * {@code TRANSFORM_FAILED} en {@code TRANSFORM_DIVIDE_BY_ZERO}) is bouwstap 3c.
 */
public enum FieldTransformKind {

    /** Geen transformatie; de bronwaarde wordt enkel gecanonicaliseerd. */
    NONE,

    /** Vaste waarde in plaats van de bronwaarde. */
    FIXED_VALUE,

    /** Vaste tekst vóór de bronwaarde. */
    PREFIX,

    /** Vaste tekst ná de bronwaarde. */
    SUFFIX,

    /** Meerdere bronkolommen in vaste volgorde samenvoegen. */
    CONCAT,

    /** Splitsen op een scheidingsteken en één onderdeel nemen. */
    SPLIT,

    /** Een bronwaarde via een vertaaltabel omzetten; een miss valt nooit terug op de bronwaarde. */
    MAP,

    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,

    /** De waarde delen door een andere kolom en met 100 vermenigvuldigen. */
    PERCENTAGE
}
