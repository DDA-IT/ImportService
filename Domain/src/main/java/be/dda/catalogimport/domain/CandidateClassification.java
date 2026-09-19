package be.dda.catalogimport.domain;

/** Classificatie van een gestagede kandidaat ten opzichte van de bronstaat. */
public enum CandidateClassification {

    /** Identiteit onbekend in de bronstaat. */
    NEW,

    /** Identiteit bekend, gecombineerde fingerprint verschilt. */
    CHANGED,

    /** Identiteit bekend, fingerprint identiek: raakt de bronstaat nooit aan. */
    UNCHANGED,

    /** Identiteit komt meermaals voor in dezelfde levering. */
    DUPLICATE_IN_DELIVERY,

    /**
     * De regel draagt een kritiek referentie-incident en wordt <b>vastgehouden</b> (R-REF-09): ze
     * vervangt {@link #NEW}, {@link #CHANGED} of {@link #UNCHANGED} zodra de screening vaststelt dat
     * een EAN, PIM-ID, CAB-ID of {@code E_MARK+ARTICLE_REFERENCE} gewijzigd, verwijderd, hergebruikt
     * of dubbelzinnig is, of dat dezelfde referentiewaarde in deze levering bij twee verschillende
     * aanbiedingen staat.
     * <p>
     * Zo'n regel krijgt nog steeds haar inhoudelijke mutatie — maar met status
     * {@link MutationStatus#BLOCKED} — en wordt door {@code accept-baseline} <b>nooit</b> in de
     * bronstaat of in {@code catalog_reference_state} aanvaard. Een referentiewijziging is nooit een
     * gewone update (businessanalyse par. 14.23.3).
     */
    IDENTITY_INCIDENT
}
