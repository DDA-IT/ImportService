package be.dda.catalogimport.domain;

/**
 * Het controleniveau waarop een {@link ImportRowIssue} is vastgesteld (ontwerp fase 3, par. 3.3).
 * <p>
 * <b>De hiërarchie is hard.</b> Een fout op niveau {@link #DELIVERY} of {@link #STRUCTURE} betekent
 * dat de recordfase niet start of stopt: een ontbrekende headerkolom levert één structuurprobleem op
 * en nooit duizenden identieke recordproblemen. Omgekeerd verwerpt een {@link #RECORD}-probleem enkel
 * die ene regel en blokkeert het de levering niet.
 */
public enum ControlLevel {

    /** De levering als geheel: leeg bestand, aantallen, duplicaten, technische onderbreking. */
    DELIVERY,

    /** Het bestands-/datasetcontract: header, kolomaantal, en de configuratie die dat beschrijft. */
    STRUCTURE,

    /** Eén bronregel: mapping, waarde, identiteitscomponent, prijs. */
    RECORD
}
