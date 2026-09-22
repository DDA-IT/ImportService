package be.dda.catalogimport.domain;

/**
 * De reikwijdte van een {@code publication_decision}-rij (ontwerp fase 4 par. 2). {@link #MUTATION} is
 * de enige scope die een {@code mutation_id} draagt; {@link #GROUP} en {@link #BUNDLE} beslissen over
 * meerdere mutaties tegelijk en tellen het werkelijke aantal in {@code affected_count}.
 */
public enum BundleDecisionScope {

    /** Precies één mutatie; {@code affected_count} is altijd 1. */
    MUTATION,

    /** Een groepsactie via een expliciete filter (bv. alle wachtende mutaties van een batch). */
    GROUP,

    /** Een actie op de hele bundel (bv. {@link BundleDecisionKind#FREEZE}, {@link BundleDecisionKind#CANCEL}). */
    BUNDLE
}
