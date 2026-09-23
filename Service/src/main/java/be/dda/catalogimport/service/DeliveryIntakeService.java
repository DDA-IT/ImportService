package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.BatchBookmarkHashDao;
import be.dda.catalogimport.dao.CatalogImportTaskRepository;
import be.dda.catalogimport.dao.DeliveryFileRepository;
import be.dda.catalogimport.dao.DeliveryRepository;
import be.dda.catalogimport.dao.ImportBatchRepository;
import be.dda.catalogimport.dao.ImportDefinitionRevisionRepository;
import be.dda.catalogimport.dao.TaskRunRepository;
import be.dda.catalogimport.domain.CatalogImportTask;
import be.dda.catalogimport.domain.Delivery;
import be.dda.catalogimport.domain.DeliveryFile;
import be.dda.catalogimport.domain.ImportBatch;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.RevisionStatus;
import be.dda.catalogimport.domain.TaskRun;
import be.dda.catalogimport.domain.TaskRunStatus;
import be.dda.catalogimport.domain.TaskTriggerType;
import be.dda.catalogimport.service.DeliveryArchiveStore.ArchivedObject;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ontvangt een manueel geüploade CSV-levering (design par. 6, par. 9 stap A en B, par. 10).
 * <p>
 * <b>Businessgedrag.</b> Een upload is alleen toegelaten op een taak met {@link TaskTriggerType#MANUAL}
 * die een actieve {@link ImportDefinitionRevision} en een basisprijsveld heeft. De levering krijgt
 * {@code idempotencyKey = "manual:" + deliveryReference}: dezelfde referentie met identieke
 * bestandshash is een retry (bestaande levering terug, geen tweede run of batch); dezelfde referentie
 * met andere inhoud is een conflict. Een tweede upload op een taak met een niet-afgeronde
 * {@link TaskRun} botst op {@code uk_task_run_concurrency}.
 * <p>
 * <b>Technische volgorde.</b> (1) Read-only voorcontrole zodat een afgekeurde aanvraag niets
 * archiveert, (2) archiveren buiten elke transactie, (3) één korte transactie die
 * {@code TaskRun}, {@code Delivery}, {@code DeliveryFile} en {@code ImportBatch} registreert. Faalt
 * (3) of blijkt de upload een retry, dan wordt het zojuist geschreven archiefobject opgeruimd. Het
 * orkestreren gebeurt met {@link TransactionTemplate}; deze klasse is zelf niet {@code @Transactional}.
 * <p>
 * <b>Grens van deze service.</b> De intake registreert en archiveert alleen; de batch komt op
 * {@code RECEIVED} te staan en de {@code TaskRun} op {@code RUNNING}. De aanroeper (de upload-POST)
 * start daarna de screening, die de batch naar haar eindstatus brengt en de {@code TaskRun} afsluit.
 * Zolang die run open is, blokkeert de concurrency-constraint een volgende upload op dezelfde taak;
 * dat is het beoogde gedrag. {@code actual_record_count} blijft {@code null} tot de screening geteld
 * heeft.
 */
@Service
public class DeliveryIntakeService {

    static final String KEY_PREFIX = "manual:";
    /** {@code delivery.idempotency_key} is varchar(200). */
    static final int MAX_DELIVERY_REFERENCE_LENGTH = 200 - KEY_PREFIX.length();
    /** {@code task_run.triggered_by} en {@code import_batch.created_by} zijn varchar(100). */
    static final int MAX_UPLOADED_BY_LENGTH = 100;
    /** {@code delivery_file.file_name} is varchar(500). */
    static final int MAX_FILE_NAME_LENGTH = 500;

    /** Resultaat van een upload: {@code created} is {@code false} bij een idempotente retry. */
    public record IntakeResult(boolean created, DeliveryView delivery) {
    }

    private record Resolved(CatalogImportTask task, ImportDefinitionRevision revision, Delivery existing) {
    }

    private final DeliveryArchiveStore archive;
    private final DeliveryQueryService queries;
    private final CatalogImportTaskRepository tasks;
    private final ImportDefinitionRevisionRepository revisions;
    private final TaskRunRepository runs;
    private final DeliveryRepository deliveries;
    private final DeliveryFileRepository deliveryFiles;
    private final ImportBatchRepository batches;
    private final LinkBookmarkValueService linkBookmarkValues;
    private final BatchBookmarkHashDao bookmarkHashes;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnlyTransaction;

    public DeliveryIntakeService(DeliveryArchiveStore archive, DeliveryQueryService queries,
                                 CatalogImportTaskRepository tasks, ImportDefinitionRevisionRepository revisions,
                                 TaskRunRepository runs, DeliveryRepository deliveries,
                                 DeliveryFileRepository deliveryFiles, ImportBatchRepository batches,
                                 LinkBookmarkValueService linkBookmarkValues,
                                 BatchBookmarkHashDao bookmarkHashes,
                                 PlatformTransactionManager transactionManager) {
        this.archive = archive;
        this.queries = queries;
        this.tasks = tasks;
        this.revisions = revisions;
        this.runs = runs;
        this.deliveries = deliveries;
        this.deliveryFiles = deliveryFiles;
        this.batches = batches;
        this.linkBookmarkValues = linkBookmarkValues;
        this.bookmarkHashes = bookmarkHashes;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * Neemt een levering aan. De stream wordt niet gesloten; dat blijft de verantwoordelijkheid van
     * de aanroeper.
     *
     * @throws NotFoundException  onbekende taak ({@code TASK_NOT_FOUND})
     * @throws ConflictException  {@code TASK_NOT_MANUAL}, {@code NO_ACTIVE_REVISION},
     *                            {@code CONFIG_PRICE_FIELD_MISSING},
     *                            {@code CONFIG_REQUIRED_BOOKMARK_MISSING}, {@code TASK_RUN_IN_PROGRESS},
     *                            {@code DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT}
     * @throws IllegalArgumentException ongeldige aanvraagvelden
     */
    public IntakeResult intake(long taskId, String deliveryReference, String uploadedBy,
                               Long expectedRecordCount, Long expectedByteSize,
                               String originalFileName, InputStream content) {
        String reference = requireText(deliveryReference, "deliveryReference", MAX_DELIVERY_REFERENCE_LENGTH);
        String uploader = requireText(uploadedBy, "uploadedBy", MAX_UPLOADED_BY_LENGTH);
        String fileName = requireText(originalFileName, "file name", MAX_FILE_NAME_LENGTH);
        if (expectedRecordCount != null && expectedRecordCount < 0) {
            throw new IllegalArgumentException("expectedRecordCount must not be negative");
        }
        if (expectedByteSize != null && expectedByteSize < 0) {
            throw new IllegalArgumentException("expectedByteSize must not be negative");
        }
        String idempotencyKey = KEY_PREFIX + reference;

        // Stap 0: een afgekeurde aanvraag mag niets archiveren.
        readOnlyTransaction.executeWithoutResult(status -> resolve(taskId, idempotencyKey));

        // Stap A: archiveren, buiten elke transactie.
        ArchivedObject archived = archive.store(content, fileName);
        boolean keepArchive = false;
        try {
            // Stap B: registratie in één korte transactie.
            IntakeResult result = transaction.execute(status ->
                    register(taskId, idempotencyKey, uploader, expectedRecordCount, expectedByteSize,
                            fileName, archived));
            keepArchive = result != null && result.created();
            return result;
        } finally {
            if (!keepArchive) {
                archive.deleteQuietly(archived.archiveReference());
            }
        }
    }

    private IntakeResult register(long taskId, String idempotencyKey, String uploader,
                                  Long expectedRecordCount, Long expectedByteSize,
                                  String fileName, ArchivedObject archived) {
        Resolved resolved = resolve(taskId, idempotencyKey);

        if (resolved.existing() != null) {
            List<DeliveryFile> files = deliveryFiles
                    .findByDeliveryIdOrderBySequenceNumberAsc(resolved.existing().getId());
            boolean identical = files.size() == 1
                    && files.get(0).getContentHash().equals(archived.sha256Hex());
            if (!identical) {
                throw new ConflictException("DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT",
                        "Delivery reference " + idempotencyKey.substring(KEY_PREFIX.length())
                                + " was already used with different file content");
            }
            return new IntakeResult(false, queries.getDelivery(resolved.existing().getId()));
        }

        CatalogImportTask task = resolved.task();
        Instant now = Instant.now();

        TaskRun run = new TaskRun(task, now, uploader);
        run.setStatus(TaskRunStatus.RUNNING);
        try {
            runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException concurrentRun) {
            // uk_task_run_concurrency: een gelijktijdige upload was sneller dan de voorcontrole.
            throw inProgress(task);
        }

        Delivery delivery = new Delivery(task, idempotencyKey, now);
        delivery.setTaskRun(run);
        delivery.setExpectedFileCount(1);
        delivery.setActualFileCount(1);
        delivery.setExpectedRecordCount(expectedRecordCount);
        delivery.setExpectedByteSize(expectedByteSize);
        delivery.setActualByteSize(archived.byteSize());
        delivery.setCompletenessProven(false);
        delivery.setManifestReference(null);
        deliveries.saveAndFlush(delivery);

        deliveryFiles.saveAndFlush(new DeliveryFile(delivery, 1, fileName, archived.archiveReference(),
                archived.sha256Hex(), archived.byteSize()));

        ImportBatch batch = new ImportBatch(delivery, task.getImportLink(), resolved.revision(), 1, uploader);
        batch.setTaskRun(run);
        batches.saveAndFlush(batch);
        recordBookmarkValuesHash(batch);

        return new IntakeResult(true, queries.getDelivery(delivery.getId()));
    }

    /**
     * Legt vast met welke LINK-bookmarkwaarden deze batch gedraaid heeft
     * ({@code import_batch.bookmark_values_hash}, ontwerp §7, beslissingslog 23/09 keuze 5).
     * <p>
     * De kolom is bewust niet op {@link ImportBatch} gemapt (changeset 006-6) en wordt daarom met één
     * gerichte JDBC-update gezet, in dezelfde transactie als de batch zelf: een volledige
     * {@code save()} zou andere kolommen meeschrijven. Heeft de koppeling geen enkele bookmarkwaarde,
     * dan blijft de kolom {@code null} — "geen bookmarkwaarden van toepassing", niet de hash van een
     * lege reeks. Vanaf dit punt is het slot op die waarden actief: de batch is open, dus
     * {@code LinkBookmarkValueService} weigert elke wijziging tot ze terminaal is.
     */
    private void recordBookmarkValuesHash(ImportBatch batch) {
        byte[] hash = bookmarkHashes.computeLinkBookmarkValuesHash(batch.getImportLink().getId());
        if (hash != null) {
            bookmarkHashes.setBookmarkValuesHash(batch.getId(), hash);
        }
    }

    /**
     * Gedeelde controle voor de voorcontrole en de registratie. Een bestaande levering met dezelfde
     * referentie wordt teruggegeven zonder revisiecontroles: een retry moet ook slagen wanneer de
     * revisie sindsdien vervangen werd.
     */
    private Resolved resolve(long taskId, String idempotencyKey) {
        CatalogImportTask task = tasks.findById(taskId)
                .orElseThrow(() -> new NotFoundException("TASK_NOT_FOUND", "Task " + taskId + " not found"));
        if (task.getTriggerType() != TaskTriggerType.MANUAL) {
            throw new ConflictException("TASK_NOT_MANUAL",
                    "Task " + taskId + " does not accept manual uploads (trigger type "
                            + task.getTriggerType() + ")");
        }
        Delivery existing = deliveries.findByTaskIdAndIdempotencyKey(taskId, idempotencyKey).orElse(null);
        if (existing != null) {
            return new Resolved(task, null, existing);
        }
        ImportDefinitionRevision revision = revisions
                .findByImportDefinitionIdAndStatus(task.getImportLink().getImportDefinition().getId(),
                        RevisionStatus.ACTIVE)
                .orElseThrow(() -> new ConflictException("NO_ACTIVE_REVISION",
                        "Task " + taskId + " has no active import definition revision"));
        String priceField = revision.getRecordBasePriceField();
        if (priceField == null || priceField.isBlank()) {
            throw new ConflictException("CONFIG_PRICE_FIELD_MISSING",
                    "Active revision " + revision.getRevisionNumber() + " has no base price field configured");
        }
        // Blokkeerpunt verplichte LINK-bookmarks (beslissingslog 23/09 keuze 6, vraag Q4, ontwerp §7):
        // dezelfde vorm, plaats en foutfamilie als de prijsveldcontrole hierboven. Bewust hier en niet
        // later: de upload wordt geweigerd vóór er iets gearchiveerd of geregistreerd is, en de batch
        // wordt niet op BLOCKED gezet - de serverstand is onvolledig, niet de levering.
        List<String> missingBookmarks = linkBookmarkValues
                .missingRequiredValues(task.getImportLink().getId(), revision.getId());
        if (!missingBookmarks.isEmpty()) {
            throw new ConflictException("CONFIG_REQUIRED_BOOKMARK_MISSING",
                    "Import link " + task.getImportLink().getId() + " has no value for required LINK bookmark(s) "
                            + missingBookmarks + " declared in active revision "
                            + revision.getRevisionNumber());
        }
        if (runs.findByTaskIdAndConcurrencyTokenIsNotNull(taskId).isPresent()) {
            throw inProgress(task);
        }
        return new Resolved(task, revision, null);
    }

    private static ConflictException inProgress(CatalogImportTask task) {
        return new ConflictException("TASK_RUN_IN_PROGRESS",
                "Task " + task.getId() + " already has a run in progress");
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
        }
        return value;
    }
}
