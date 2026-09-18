package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Eén uitvoering van een {@link CatalogImportTask}.
 * <p>
 * De concurrency-bescherming is bewust een <b>databaseconstraint</b> en niet enkel een
 * applicatiecheck (AGENT.md §2 principe 6): {@code concurrencyToken} draagt de id van de taak
 * zolang de run niet in een eindtoestand is, en is {@code null} zodra de run beëindigd is.
 * Samen met {@code uk_task_run_concurrency} betekent dat: hoogstens één lopende run per taak,
 * ook onder gelijktijdige verwerking door twee applicatie-instanties. Wanneer een taak
 * {@code preventConcurrentRuns = false} heeft, blijft de token {@code null} en is gelijktijdigheid
 * toegestaan; de configuratie blijft dus betekenisvol.
 */
@Entity
@Table(name = "task_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_task_run_concurrency",
                columnNames = {"task_id", "concurrency_token"}))
public class TaskRun {

    /** Eindtoestanden laten de concurrency-token los. */
    private static boolean isTerminal(TaskRunStatus status) {
        return status == TaskRunStatus.COMPLETED
                || status == TaskRunStatus.FAILED
                || status == TaskRunStatus.CANCELLED;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_task_run_task"))
    private CatalogImportTask task;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private TaskRunStatus status = TaskRunStatus.PENDING;

    /** Zie klassedocumentatie: {@code task.id} zolang de run loopt, anders {@code null}. */
    @Column(name = "concurrency_token")
    private Long concurrencyToken;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Wie/welke instantie de run vasthoudt; nodig om een verweesde lock te herkennen. */
    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    /** Manueel gestart door deze gebruiker, of {@code null} bij een geplande run. */
    @Column(name = "triggered_by", length = 100)
    private String triggeredBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TaskRun() {
        // JPA
    }

    public TaskRun(CatalogImportTask task, Instant startedAt, String triggeredBy) {
        this.task = task;
        this.startedAt = startedAt;
        this.triggeredBy = triggeredBy;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (startedAt == null) {
            startedAt = now;
        }
        syncConcurrencyToken();
    }

    @PreUpdate
    void onUpdate() {
        syncConcurrencyToken();
    }

    private void syncConcurrencyToken() {
        boolean guarded = task != null && task.isPreventConcurrentRuns() && !isTerminal(status);
        concurrencyToken = guarded ? task.getId() : null;
    }

    public Long getId() {
        return id;
    }

    public CatalogImportTask getTask() {
        return task;
    }

    public TaskRunStatus getStatus() {
        return status;
    }

    public void setStatus(TaskRunStatus status) {
        this.status = status;
        syncConcurrencyToken();
    }

    public Long getConcurrencyToken() {
        return concurrencyToken;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
