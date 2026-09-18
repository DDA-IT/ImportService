package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Eén concrete levering onder een {@link CatalogImportTask}: de bestandset die op één moment
 * ontvangen werd, met haar volledigheidsbewijs.
 * <p>
 * <b>Idempotentie.</b> {@code idempotencyKey} is uniek per taak en beschermt uitsluitend tegen een
 * <i>technische retry van dezelfde leveringsregistratie</i> (bv. dezelfde SFTP-bestandset opnieuw
 * ophalen na een timeout, of dezelfde API-pagina twee keer ontvangen — §16.6). De sleutel wordt
 * daarom afgeleid uit de transport-/registratie-identiteit (connector, remote object-id,
 * remote tijdstempel, run), <b>nooit alleen uit de inhoudshash van het bestand</b>: een
 * leverancier die morgen een inhoudelijk identiek bestand opnieuw levert, moet een nieuwe
 * {@code Delivery} krijgen die gewoon volledig gescreend wordt. Ongewijzigde records leveren dan
 * geen bibliotheekwrite op (§15.2 punt 10), maar de levering zelf wordt nooit stil overgeslagen.
 * <p>
 * Het bronbestand zelf staat <b>niet</b> in de database maar in objectopslag; zie
 * {@link DeliveryFile#getArchiveReference()}. Een ontvangen levering is immutable: een correctie
 * is een nieuwe levering die via {@link #getSupersedesDelivery()} naar de vorige verwijst (§16.6).
 */
@Entity
@Table(name = "delivery",
        uniqueConstraints = @UniqueConstraint(name = "uk_delivery_idempotency",
                columnNames = {"task_id", "idempotency_key"}))
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_delivery_task"))
    private CatalogImportTask task;

    /**
     * De run die deze levering ontving. {@code null} wanneer de levering manueel geregistreerd
     * werd vóór er een run over liep.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_run_id",
            foreignKey = @ForeignKey(name = "fk_delivery_task_run"))
    private TaskRun taskRun;

    /** Zie klassedocumentatie: transport-/registratie-identiteit, geen inhoudshash. */
    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** Verwijzing naar het manifest/de bestandslijst in het bronarchief; geen inhoud. */
    @Column(name = "manifest_reference", length = 500)
    private String manifestReference;

    // --- Volledigheidsbewijs (§15.2 punt 11, §16.6) -------------------------------------------

    /** Verwacht aantal bestanden volgens manifest/leveringsconfiguratie; {@code null} = onbekend. */
    @Column(name = "expected_file_count")
    private Integer expectedFileCount;

    @Column(name = "actual_file_count", nullable = false)
    private int actualFileCount;

    /** Verwacht aantal records volgens manifest; {@code null} = onbekend, nooit stil 0. */
    @Column(name = "expected_record_count")
    private Long expectedRecordCount;

    /** Werkelijk gelezen aantal records; {@code null} zolang niet geteld, nooit stil 0. */
    @Column(name = "actual_record_count")
    private Long actualRecordCount;

    @Column(name = "expected_byte_size")
    private Long expectedByteSize;

    @Column(name = "actual_byte_size", nullable = false)
    private long actualByteSize;

    /**
     * Formeel Volledigheidsbewijs. Zolang dit {@code false} is, mag afwezigheid van een record
     * nooit als verwijdering behandeld worden (§15.2 punt 11).
     */
    @Column(name = "completeness_proven", nullable = false)
    private boolean completenessProven;

    /** De levering die deze levering vervangt/corrigeert (§16.6); bronbestanden blijven immutable. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_delivery_id",
            foreignKey = @ForeignKey(name = "fk_delivery_supersedes"))
    private Delivery supersedesDelivery;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Delivery() {
        // JPA
    }

    public Delivery(CatalogImportTask task, String idempotencyKey, Instant receivedAt) {
        this.task = task;
        this.idempotencyKey = idempotencyKey;
        this.receivedAt = receivedAt;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (receivedAt == null) {
            receivedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public CatalogImportTask getTask() {
        return task;
    }

    public TaskRun getTaskRun() {
        return taskRun;
    }

    public void setTaskRun(TaskRun taskRun) {
        this.taskRun = taskRun;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getManifestReference() {
        return manifestReference;
    }

    public void setManifestReference(String manifestReference) {
        this.manifestReference = manifestReference;
    }

    public Integer getExpectedFileCount() {
        return expectedFileCount;
    }

    public void setExpectedFileCount(Integer expectedFileCount) {
        this.expectedFileCount = expectedFileCount;
    }

    public int getActualFileCount() {
        return actualFileCount;
    }

    public void setActualFileCount(int actualFileCount) {
        this.actualFileCount = actualFileCount;
    }

    public Long getExpectedRecordCount() {
        return expectedRecordCount;
    }

    public void setExpectedRecordCount(Long expectedRecordCount) {
        this.expectedRecordCount = expectedRecordCount;
    }

    public Long getActualRecordCount() {
        return actualRecordCount;
    }

    public void setActualRecordCount(Long actualRecordCount) {
        this.actualRecordCount = actualRecordCount;
    }

    public Long getExpectedByteSize() {
        return expectedByteSize;
    }

    public void setExpectedByteSize(Long expectedByteSize) {
        this.expectedByteSize = expectedByteSize;
    }

    public long getActualByteSize() {
        return actualByteSize;
    }

    public void setActualByteSize(long actualByteSize) {
        this.actualByteSize = actualByteSize;
    }

    public boolean isCompletenessProven() {
        return completenessProven;
    }

    public void setCompletenessProven(boolean completenessProven) {
        this.completenessProven = completenessProven;
    }

    public Delivery getSupersedesDelivery() {
        return supersedesDelivery;
    }

    public void setSupersedesDelivery(Delivery supersedesDelivery) {
        this.supersedesDelivery = supersedesDelivery;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
