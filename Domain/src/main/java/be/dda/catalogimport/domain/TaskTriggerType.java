package be.dda.catalogimport.domain;

/**
 * Hoe een {@link CatalogImportTask} gestart wordt. De eigenlijke planner/scheduler is fase 5;
 * fase 1 legt enkel de configuratie vast.
 */
public enum TaskTriggerType {

    /** Alleen manueel gestart (bv. manuele upload of manuele herverwerking). */
    MANUAL,

    /** Gepland volgens {@code triggerExpression} (cron). */
    SCHEDULED
}
