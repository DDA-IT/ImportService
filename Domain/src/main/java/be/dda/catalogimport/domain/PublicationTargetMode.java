package be.dda.catalogimport.domain;

/**
 * Het doel waarnaar een {@link PublicationBundle} publiceert (ontwerp fase 4 par. 2). Geen default op
 * databaseniveau: een bundel kiest dit altijd expliciet.
 */
public enum PublicationTargetMode {

    /** Proefpublicatie zonder effect op een echte bibliotheek. */
    SIMULATION,

    /** Publicatie naar een controlebibliotheek. */
    TRIAL_LIBRARY,

    /** Echte publicatie naar ProDisWebbase/Pervasive. */
    PRODUCTION
}
