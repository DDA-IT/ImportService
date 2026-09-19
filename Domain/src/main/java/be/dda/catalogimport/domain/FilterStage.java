package be.dda.catalogimport.domain;

/**
 * Waarop een recordfilter vergelijkt (ontwerp fase 3, par. 2 004-3; businessanalyse par. 14.4).
 * <p>
 * {@link #TARGET_FIELD} is gedeclareerd maar in fase 3 nog niet ondersteund: een doelveldfilter moet
 * eerst de afhankelijke mapping en transformatie berekenen. Een revisie die er één declareert wordt
 * geblokkeerd ({@code CONFIG_FILTER_INVALID}); stil negeren zou records importeren die de beheerder
 * bewust buiten de scope gezet heeft.
 */
public enum FilterStage {

    /** Op de ongewijzigde bronkolom, onmiddellijk na het parsen (R-FLT-02). */
    SOURCE_FIELD,

    /** Op een afgeleid doelveld; gedeclareerd, nog niet ondersteund. */
    TARGET_FIELD
}
