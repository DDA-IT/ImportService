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
    PERCENTAGE,

    /** Hoofdletters ({@code Locale.ROOT}); geen instellingen (legacy CALC 15, S8b). */
    UPPER,

    /** Deeltekst vanaf een 1-gebaseerde positie, met optionele lengte (legacy Positie/Lengte, S8b). */
    SUBSTRING,

    /**
     * Prefix en/of suffix met de legacy-semantiek (S8c, {@code Prg_1170} r.730): enkel als de getrimde waarde niet leeg
     * is {@code Trim(prefix) & waarde & Trim(suffix)}, anders {@code ""}. {@link #PREFIX} en {@link #SUFFIX} blijven
     * ongewijzigd (die zetten hun tekst ook rond een lege waarde).
     */
    AFFIX,

    /**
     * Een vaste keten van 1-3 stappen uit {@code import_field_transform_step} (S8c, changeset 024-1), in de gesloten
     * volgorde {@code [SUBSTRING] → [AFFIX|PREFIX|SUFFIX] → [één waardebewerking]}. De mapping zelf draagt dan enkel nog
     * de notatie-instellingen in {@code transform_config}. Nooit zelf een stap (databasecheck 024-1).
     */
    CHAIN,

    /**
     * Vertaling op "bevat" via de maptabel {@code import_field_value_map} (S8d, legacy CALC 14): geordend op
     * {@code sequence_number}, hoofdletterongevoelig, eerste treffer wint; een miss verwerpt de regel
     * ({@code MAPPING_VALUE_UNKNOWN}, nooit {@code ''}).
     */
    MAP_CONTAINS
}
