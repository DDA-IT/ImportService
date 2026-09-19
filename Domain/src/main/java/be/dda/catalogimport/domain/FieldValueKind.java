package be.dda.catalogimport.domain;

/**
 * Waar de waarde van een gemapt doelveld vandaan komt (ontwerp fase 3, par. 2 004-2).
 * <p>
 * {@link #BOOKMARK} bestaat al vanaf deze bouwstap, ook al zijn sjablonen en bookmarks nog niet
 * gebouwd: het beslissingslog van 18/09 eist dat een sjabloon-afgeleide definitie later zonder
 * schemamigratie kan aansluiten. Tot dan wordt een bookmark-mapping expliciet geweigerd in plaats van
 * stil genegeerd.
 */
public enum FieldValueKind {

    /** Een kolom uit het bronbestand (headernaam of 1-gebaseerde kolomindex). */
    SOURCE_FIELD,

    /** Een vaste waarde uit de definitie zelf. */
    FIXED_VALUE,

    /** Een per koppeling ingevulde sjabloonwaarde; nog niet ondersteund. */
    BOOKMARK,

    /** Afgeleid uit andere doelvelden; nog niet ondersteund. */
    DERIVED
}
