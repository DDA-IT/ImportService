package be.dda.catalogimport.domain;

/**
 * Toestand van de kortingscode in een aanbiedingsidentiteit. Houdt "niet gemapt" en "gemapt maar
 * leeg" bewust uit elkaar (beslissingslog 18/09, aanbiedingsidentiteit).
 */
public enum DiscountCodeState {

    /** Kortingscode niet gemapt (driedelige identiteit): waarde {@code null}. */
    NOT_USED,

    /** Kortingscode gemapt, bronwaarde expliciet leeg: waarde {@code ""}. */
    EMPTY,

    /** Kortingscode gemapt met een niet-lege waarde. */
    VALUE
}
