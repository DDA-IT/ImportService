package be.dda.catalogimport.domain;

/** Doeldomein van een {@link ImportMutation}. */
public enum MutationTargetDomain {

    /** De aanbieding (prijs/artikel) zelf. */
    OFFER,

    /** De import als proces; enkel gebruikt door {@link MutationActionType#IMPORT_MARKER}. */
    IMPORT
}
