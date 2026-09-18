package be.dda.catalogimport.domain;

/**
 * Vorm van de bibliotheekonafhankelijke aanbiedingsidentiteit die één importdefinitierevisie
 * kiest voor de <b>volledige</b> importfile — nooit per record.
 * <p>
 * Beslissingslog 18/09 "Fase 0: aanbiedingsidentiteit" en businessanalyse §14.23.3 / §15.2 punt 5:
 * <ul>
 *   <li>{@link #THREE_PART}: kortingscode is <i>niet gemapt</i> ({@code null}) en maakt dus geen
 *       deel uit van de sleutel;</li>
 *   <li>{@link #FOUR_PART_WITH_DISCOUNT_CODE}: kortingscode is <i>wel gemapt</i> en maakt deel uit
 *       van de sleutel, ook wanneer de bronwaarde expliciet leeg ({@code ""}) is.</li>
 * </ul>
 * {@code null} (niet gemapt) en {@code ""} (expliciet leeg) zijn dus verschillende zakelijke
 * toestanden; de import mag de ene nooit stil in de andere omzetten.
 * Bibliotheek en bronorganisatie zijn scope en nooit sleutelonderdeel.
 */
public enum IdentityProfileKind {

    /** leverancier + leveranciersgroep + leveranciersreferentie. */
    THREE_PART,

    /** leverancier + leveranciersgroep + kortingscode + leveranciersreferentie. */
    FOUR_PART_WITH_DISCOUNT_CODE
}
