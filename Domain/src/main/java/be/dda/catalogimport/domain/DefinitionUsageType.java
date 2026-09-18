package be.dda.catalogimport.domain;

/**
 * Gebruikstype van een importdefinitie. Businessanalyse §14.20: bepaalt vooral
 * zoekbaarheid en beheer, niet de inhoudelijke verwerking.
 */
public enum DefinitionUsageType {

    /** Eigen definitie van één bron/leverancier. */
    OWN_DEFINITION,

    /** Herbruikbaar sjabloon; nooit zelf een live productieconfiguratie (§14.16). */
    REUSABLE_TEMPLATE
}
