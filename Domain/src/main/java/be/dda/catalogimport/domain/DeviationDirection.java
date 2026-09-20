package be.dda.catalogimport.domain;

/**
 * De richting van een prijsafwijking ten opzichte van haar referentie
 * ({@code import_issue_group.deviation_direction}, R-PRI-14).
 * <p>
 * De richting hoort bij de <b>signatuur</b> van een prijsincident: honderd stijgingen en honderd
 * dalingen zijn twee verschillende verschijnselen (een prijsverhoging versus een verkeerd geplaatste
 * decimaal) en mogen nooit in één bulkincident samenvallen. Daarom is dit geen afgeleid detail maar
 * een sleutelonderdeel.
 */
public enum DeviationDirection {

    /** Het geleverde bedrag ligt <b>boven</b> de referentie. */
    UP,

    /** Het geleverde bedrag ligt <b>onder</b> de referentie. */
    DOWN
}
