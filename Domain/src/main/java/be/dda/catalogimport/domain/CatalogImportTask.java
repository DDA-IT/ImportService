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
 * Een geconfigureerde, herhaalbare importtaak: koppelt een {@link ImportLink} aan een trigger of
 * planning. De eigenlijke scheduler-/uitvoeringslogica is fase 5; fase 1 legt enkel de
 * configuratie en het laatste-runtijdstip vast.
 */
@Entity
@Table(name = "catalog_import_task",
        uniqueConstraints = @UniqueConstraint(name = "uk_catalog_import_task_name",
                columnNames = {"import_link_id", "name"}))
public class CatalogImportTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_link_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_catalog_import_task_import_link"))
    private ImportLink importLink;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 40)
    private TaskTriggerType triggerType = TaskTriggerType.MANUAL;

    /**
     * Planningsexpressie (cron) wanneer {@link #triggerType} {@code SCHEDULED} is; anders
     * {@code null}. De databasecheck {@code ck_catalog_import_task_trigger} houdt beide consistent.
     */
    @Column(name = "trigger_expression", length = 200)
    private String triggerExpression;

    /**
     * Wanneer {@code true} mag er hoogstens één niet-afgeronde {@link TaskRun} per taak bestaan.
     * Dit wordt op databaseniveau afgedwongen via {@code TaskRun.concurrencyToken} en de
     * constraint {@code uk_task_run_concurrency} — niet enkel door een applicatiecheck.
     */
    @Column(name = "prevent_concurrent_runs", nullable = false)
    private boolean preventConcurrentRuns = true;

    @Column(name = "last_run_started_at")
    private Instant lastRunStartedAt;

    @Column(name = "last_run_finished_at")
    private Instant lastRunFinishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CatalogImportTask() {
        // JPA
    }

    public CatalogImportTask(ImportLink importLink, String name, TaskTriggerType triggerType) {
        this.importLink = importLink;
        this.name = name;
        this.triggerType = triggerType;
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public ImportLink getImportLink() {
        return importLink;
    }

    public void setImportLink(ImportLink importLink) {
        this.importLink = importLink;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public TaskTriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(TaskTriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public String getTriggerExpression() {
        return triggerExpression;
    }

    public void setTriggerExpression(String triggerExpression) {
        this.triggerExpression = triggerExpression;
    }

    public boolean isPreventConcurrentRuns() {
        return preventConcurrentRuns;
    }

    public void setPreventConcurrentRuns(boolean preventConcurrentRuns) {
        this.preventConcurrentRuns = preventConcurrentRuns;
    }

    public Instant getLastRunStartedAt() {
        return lastRunStartedAt;
    }

    public void setLastRunStartedAt(Instant lastRunStartedAt) {
        this.lastRunStartedAt = lastRunStartedAt;
    }

    public Instant getLastRunFinishedAt() {
        return lastRunFinishedAt;
    }

    public void setLastRunFinishedAt(Instant lastRunFinishedAt) {
        this.lastRunFinishedAt = lastRunFinishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
