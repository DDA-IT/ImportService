package be.dda.catalogimport.domain;

/**
 * Wat er met een bronregel gebeurt wanneer een recordfilter matcht (ontwerp fase 3, R-FLT-01/R-FLT-02).
 */
public enum FilterOutcome {

    /**
     * De regel hoort tot de importscope. Zijn er één of meer {@code INCLUDE}-filters, dan moet er
     * minstens één matchen voordat een regel in scope is.
     */
    INCLUDE,

    /**
     * De regel valt buiten de importscope: ze krijgt geen enkele verdere controle en telt in
     * {@code filtered_out_count}. Dit is geen fout.
     */
    EXCLUDE,

    /**
     * De regel hoort niet te bestaan: ze wordt verworpen mét een probleem, telt in
     * {@code rejected_record_count} en is dus zichtbaar. Bewust iets anders dan {@link #EXCLUDE}.
     */
    REJECT
}
