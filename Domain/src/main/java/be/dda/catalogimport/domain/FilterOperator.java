package be.dda.catalogimport.domain;

/**
 * De zes vergelijkingen die een recordfilter op een bronwaarde mag doen (ontwerp fase 3, R-FLT-01;
 * businessanalyse par. 5.5.1).
 * <p>
 * Gesloten opsomming: geen reguliere expressies, geen vrije formules. Hoofdlettergevoeligheid en
 * trimmen staan per filterrij apart geconfigureerd en zitten dus <b>niet</b> in de operator.
 */
public enum FilterOperator {

    EQUALS,
    NOT_EQUALS,
    BEGINS_WITH,
    ENDS_WITH,
    CONTAINS,
    NOT_CONTAINS
}
