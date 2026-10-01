package be.dda.catalogimport.domain;

/**
 * Bestandskeuze van een {@link DeliveryConfigurationVersion} (changeset 014-4). {@link #ALL_FILES} heeft geen
 * {@link DeliveryConfigurationFileCondition}-rijen, {@link #CONDITIONS} minstens één (servicecontrole, LC-2).
 */
public enum DeliverySelectionMode {
    CONDITIONS,
    ALL_FILES
}
