package be.dda.catalogimport.domain;

/**
 * Hoe een {@link TaskRun} gestart werd (changeset 015-1, {@code task_run.trigger_source}; bouwstap K-4b van
 * {@code docs/design/leveringsconfiguratie-design.md} par. 3.3). Herkomst, geen status: één keer gezet bij het aanmaken
 * van de run. Runs van vóór 015 hebben {@code null} (onbekend; bewust geen backfill).
 */
public enum TaskRunTriggerSource {

    /** Browser-upload ({@code POST /tasks/{id}/deliveries}). */
    UPLOAD,

    /** Ingelezen uit de beheerde servermap ({@code POST /tasks/{id}/deliveries/local-source}). */
    LOCAL_DIRECTORY,

    /** Handmatig "Nu ophalen" via de Leveringsconfiguratie van de taak ({@code POST /tasks/{id}/fetch-runs}). */
    MANUAL_FETCH,

    /** Geplande ophaling; gereserveerd voor de scheduler-stap (S-0/S-1), nog nooit gezet. */
    SCHEDULED_FETCH;

    /** De herkomst van een run die door een intake langs deze ontvangstweg gestart wordt; {@code null} = upload. */
    public static TaskRunTriggerSource forIntake(DeliverySourceKind sourceKind) {
        if (sourceKind == null) {
            return UPLOAD;
        }
        return switch (sourceKind) {
            case UPLOAD -> UPLOAD;
            case LOCAL_DIRECTORY -> LOCAL_DIRECTORY;
            case SFTP -> MANUAL_FETCH;
        };
    }
}
