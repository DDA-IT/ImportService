package be.dda.catalogimport.domain;

/**
 * Het gekozen model voor de prijsafwijkingscontrole per revisie (ontwerp fase 3, par. 2 004-10 en
 * R-PRI-10).
 * <p>
 * {@link #BOXPLOT} is bewust <b>gedeclareerd maar niet ondersteund</b> (ontwerp fase 3, par. 0): zo
 * hoeft het schema later niet te wijzigen. Een revisie die het kiest moet expliciet geweigerd worden;
 * stil terugvallen op {@link #DEVIATION} zou een ander financieel oordeel opleveren dan de beheerder
 * ingesteld heeft.
 */
public enum PriceControlModel {

    /** Afwijking ten opzichte van vorige waarde en gemiddelden over twee vensters. */
    DEVIATION,

    /** Boxplot-gebaseerde uitschieterdetectie; gedeclareerd, nog niet ondersteund. */
    BOXPLOT
}
