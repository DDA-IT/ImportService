package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.AcquisitionConfigEventRepository;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryConfigurationVersionRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.AcquisitionConfigEvent;
import be.dda.catalogimport.domain.AcquisitionConfigEventKind;
import be.dda.catalogimport.domain.AcquisitionConfigEventSource;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.DeliveryConfigurationVersion;
import be.dda.catalogimport.domain.TaskRunStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Koppelt een taak aan een Leveringsconfiguratie-versie of ontkoppelt ze: bouwstap LC-2 van
 * {@code docs/design/leveringsconfiguratie-design.md} par. 3.2, 6 en 10; beslissingslog 2026-09-29 (L2, L3, A10, A11).
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Koppelen</b> zet {@code catalog_import_task.delivery_configuration_version_id} en schrijft een
 *       {@code TASK_BOUND}-event (HUMAN, actor uit het token, verplichte reden, met de DC-versie en haar
 *       profielversie). <b>Herkoppelen</b> naar een andere versie (L3: een taak neemt een nieuwe versie expliciet
 *       over) schrijft eerst {@code TASK_UNBOUND} voor de oude en dan {@code TASK_BOUND} voor de nieuwe versie,
 *       zodat elke versie in het register met een echte verwijzing voorkomt. Dezelfde versie opnieuw koppelen is
 *       idempotent: 200, geen wijziging en geen event.</li>
 *   <li><b>Ontkoppelen</b> zet de kolom op {@code null} en schrijft {@code TASK_UNBOUND}. Een taak zonder koppeling
 *       ontkoppelen is 409 {@link #CODE_NOT_BOUND} zonder event (conventie {@code CREDENTIAL_ALREADY_REVOKED}).</li>
 *   <li><b>A11:</b> hoogstens één taak met een Leveringsconfiguratie per koppeling ({@code import_link}); een tweede
 *       is 409 {@link #CODE_LINK_ALREADY_HAS_DC_TASK}. Servicecontrole zonder databaseconstraint (design par. 3.2),
 *       geserialiseerd door alle taken van de koppeling te vergrendelen.</li>
 *   <li><b>Lopende run:</b> een taak met een run in {@code PENDING}/{@code RUNNING} wordt niet (ont)koppeld: 409
 *       {@code TASK_RUN_IN_PROGRESS} (bestaande code van de intake). Getoetst op status, niet enkel op de
 *       concurrency-token, zodat ook een taak met {@code preventConcurrentRuns = false} gedekt is.</li>
 * </ul>
 * Foutvolgorde binnen de service: 400 (invoer) → 404 (taak, dan versie) → 409. De 403 komt er in de Web-laag vóór.
 */
@Service
public class TaskDeliveryConfigurationService {

    public static final String CODE_REASON_REQUIRED = "TASK_BINDING_REASON_REQUIRED";
    public static final String CODE_VERSION_REQUIRED = "TASK_BINDING_VERSION_REQUIRED";
    public static final String CODE_TASK_NOT_FOUND = "TASK_NOT_FOUND";
    public static final String CODE_VERSION_NOT_FOUND = "DELIVERY_CONFIGURATION_VERSION_NOT_FOUND";
    public static final String CODE_RUN_IN_PROGRESS = "TASK_RUN_IN_PROGRESS";
    /** A11: op deze koppeling heeft al een andere taak een Leveringsconfiguratie. */
    public static final String CODE_LINK_ALREADY_HAS_DC_TASK = "IMPORT_LINK_HAS_DELIVERY_CONFIGURATION_TASK";
    public static final String CODE_NOT_BOUND = "TASK_HAS_NO_DELIVERY_CONFIGURATION";

    static final int MAX_REASON_LENGTH = 500;
    static final int MAX_ACTOR_LENGTH = 100;

    private static final Logger LOG = LoggerFactory.getLogger(TaskDeliveryConfigurationService.class);

    /** De koppeling van een taak na de actie; de DC-velden zijn {@code null} zonder koppeling. */
    public record TaskBindingView(long taskId, String taskName, long importLinkId, String importLinkCode,
                                  Long deliveryConfigurationVersionId, Long deliveryConfigurationId,
                                  String deliveryConfigurationCode, Integer deliveryConfigurationVersionNumber) {
    }

    private final CatalogImportTaskRepository tasks;
    private final DeliveryConfigurationVersionRepository versions;
    private final TaskRunRepository runs;
    private final AcquisitionConfigEventRepository events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public TaskDeliveryConfigurationService(CatalogImportTaskRepository tasks,
                                            DeliveryConfigurationVersionRepository versions, TaskRunRepository runs,
                                            AcquisitionConfigEventRepository events,
                                            PlatformTransactionManager transactionManager, Clock clock) {
        this.tasks = tasks;
        this.versions = versions;
        this.runs = runs;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Koppelt (of herkoppelt) {@code taskId} aan DC-versie {@code versionId}.
     *
     * @throws BadRequestException 400 {@link #CODE_VERSION_REQUIRED}, {@link #CODE_REASON_REQUIRED}
     * @throws NotFoundException   404 {@link #CODE_TASK_NOT_FOUND}, {@link #CODE_VERSION_NOT_FOUND}
     * @throws ConflictException   409 {@link #CODE_RUN_IN_PROGRESS}, {@link #CODE_LINK_ALREADY_HAS_DC_TASK}
     */
    public TaskBindingView bind(long taskId, Long versionId, String reason, ActorIdentity actor) {
        String by = requireActor(actor);
        if (versionId == null) {
            throw new BadRequestException(CODE_VERSION_REQUIRED, "Missing versionId");
        }
        String motivation = requireReason(reason);

        return transaction.execute(status -> {
            List<CatalogImportTask> linkTasks = lockTasksOfLinkOf(taskId);
            CatalogImportTask task = find(linkTasks, taskId);
            DeliveryConfigurationVersion version = versions.findById(versionId)
                    .orElseThrow(() -> new NotFoundException(CODE_VERSION_NOT_FOUND,
                            "Delivery configuration version " + versionId + " not found"));
            DeliveryConfigurationVersion previous = task.getDeliveryConfigurationVersion();
            if (previous != null && previous.getId().equals(version.getId())) {
                return view(task);
            }
            requireNoRunInProgress(task);
            boolean otherTaskBound = linkTasks.stream()
                    .anyMatch(t -> !t.getId().equals(task.getId()) && t.getDeliveryConfigurationVersion() != null);
            if (otherTaskBound) {
                throw new ConflictException(CODE_LINK_ALREADY_HAS_DC_TASK, "Another task of import link "
                        + task.getImportLink().getCode() + " already has a delivery configuration (at most one per link)");
            }
            Instant now = now();
            if (previous != null) {
                events.saveAndFlush(event(AcquisitionConfigEventKind.TASK_UNBOUND, task, previous, motivation, by,
                        actor, now));
            }
            task.setDeliveryConfigurationVersion(version);
            tasks.saveAndFlush(task);
            events.saveAndFlush(event(AcquisitionConfigEventKind.TASK_BOUND, task, version, motivation, by, actor,
                    now));
            LOG.info("Task {} bound to delivery configuration version {} by {}{}", task.getId(), version.getId(), by,
                    previous == null ? "" : " (replaces version " + previous.getId() + ")");
            return view(task);
        });
    }

    /**
     * Ontkoppelt {@code taskId}.
     *
     * @throws BadRequestException 400 {@link #CODE_REASON_REQUIRED}
     * @throws NotFoundException   404 {@link #CODE_TASK_NOT_FOUND}
     * @throws ConflictException   409 {@link #CODE_NOT_BOUND}, {@link #CODE_RUN_IN_PROGRESS}
     */
    public TaskBindingView unbind(long taskId, String reason, ActorIdentity actor) {
        String by = requireActor(actor);
        String motivation = requireReason(reason);

        return transaction.execute(status -> {
            CatalogImportTask task = find(lockTasksOfLinkOf(taskId), taskId);
            DeliveryConfigurationVersion previous = task.getDeliveryConfigurationVersion();
            if (previous == null) {
                throw new ConflictException(CODE_NOT_BOUND, "Task " + taskId + " has no delivery configuration");
            }
            requireNoRunInProgress(task);
            Instant now = now();
            task.setDeliveryConfigurationVersion(null);
            tasks.saveAndFlush(task);
            events.saveAndFlush(event(AcquisitionConfigEventKind.TASK_UNBOUND, task, previous, motivation, by, actor,
                    now));
            LOG.info("Task {} unbound from delivery configuration version {} by {}", task.getId(), previous.getId(),
                    by);
            return view(task);
        });
    }

    // --- Hulp ------------------------------------------------------------------------------------------------

    /**
     * Vergrendelt alle taken van de koppeling van {@code taskId} (vaste volgorde op id). De taak wordt bewust niet
     * vooraf als entiteit geladen: zo komt haar toestand vers van na het slot.
     */
    private List<CatalogImportTask> lockTasksOfLinkOf(long taskId) {
        Long linkId = tasks.findImportLinkIdByTaskId(taskId).orElseThrow(() -> taskNotFound(taskId));
        return tasks.findByImportLinkIdForUpdate(linkId);
    }

    /** Ook een 404 als de taak tussen beide queries van koppeling veranderde of verdween (uiterst zeldzaam). */
    private static CatalogImportTask find(List<CatalogImportTask> linkTasks, long taskId) {
        return linkTasks.stream().filter(t -> t.getId() == taskId).findFirst()
                .orElseThrow(() -> taskNotFound(taskId));
    }

    private void requireNoRunInProgress(CatalogImportTask task) {
        boolean running = !runs.findByTaskIdAndStatus(task.getId(), TaskRunStatus.RUNNING).isEmpty()
                || !runs.findByTaskIdAndStatus(task.getId(), TaskRunStatus.PENDING).isEmpty();
        if (running) {
            throw new ConflictException(CODE_RUN_IN_PROGRESS,
                    "Task " + task.getId() + " already has a run in progress");
        }
    }

    private static AcquisitionConfigEvent event(AcquisitionConfigEventKind kind, CatalogImportTask task,
                                                DeliveryConfigurationVersion version, String reason, String by,
                                                ActorIdentity actor, Instant at) {
        return new AcquisitionConfigEvent(kind, task, version, version.getConnectionProfileVersion(), null, null,
                reason, AcquisitionConfigEventSource.HUMAN, by, actor.subject(), at);
    }

    private static TaskBindingView view(CatalogImportTask task) {
        DeliveryConfigurationVersion version = task.getDeliveryConfigurationVersion();
        return new TaskBindingView(task.getId(), task.getName(), task.getImportLink().getId(),
                task.getImportLink().getCode(), version == null ? null : version.getId(),
                version == null ? null : version.getDeliveryConfiguration().getId(),
                version == null ? null : version.getDeliveryConfiguration().getCode(),
                version == null ? null : version.getVersionNumber());
    }

    private static NotFoundException taskNotFound(long taskId) {
        return new NotFoundException(CODE_TASK_NOT_FOUND, "Task " + taskId + " not found");
    }

    private static String requireReason(String reason) {
        return AcquisitionConfigInput.requireText(reason, "reason", MAX_REASON_LENGTH, CODE_REASON_REQUIRED);
    }

    private static String requireActor(ActorIdentity actor) {
        if (actor == null) {
            throw new IllegalArgumentException("Missing actor");
        }
        return ActorNames.requireActorName(actor.username(), "actor", MAX_ACTOR_LENGTH);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
