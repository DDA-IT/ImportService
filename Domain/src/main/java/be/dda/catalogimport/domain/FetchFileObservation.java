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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Eén matchend bestand dat een ophaalrun bekeek, met haar beslissing (changeset 015-2; beslissingslog 2026-09-29 L6:
 * "overgeslagen bestanden blijven altijd zichtbaar"; {@code docs/design/leveringsconfiguratie-design.md} par. 3.3).
 * <p>
 * Append-only: nooit bijgewerkt of verwijderd, dus geen setters en elke kolom {@code updatable = false}.
 * {@link #getDelivery()} is enkel gevuld bij {@link FetchFileDecision#SELECTED} en enkel als de levering echt
 * geregistreerd werd (de database dwingt dat af met {@code ck_fetch_file_observation_delivery}). De waarneming met een
 * levering is ook de <b>watermark</b> van de taak: het laatst opgehaalde bestand (wijzigingstijd + naam).
 */
@Entity
@Table(name = "fetch_file_observation",
        uniqueConstraints = @UniqueConstraint(name = "uk_fetch_file_observation_run_file",
                columnNames = {"task_run_id", "remote_file_name"}))
public class FetchFileObservation {

    public static final int MAX_FILE_NAME_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_run_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_fetch_file_observation_task_run"))
    private TaskRun taskRun;

    @Column(name = "remote_file_name", nullable = false, updatable = false, length = MAX_FILE_NAME_LENGTH)
    private String remoteFileName;

    @Column(name = "byte_size", nullable = false, updatable = false)
    private long byteSize;

    /** {@code null} als de server geen wijzigingstijd gaf; zo'n bestand wordt nooit gekozen. */
    @Column(name = "remote_modified_at", updatable = false)
    private Instant remoteModifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, updatable = false, length = 30)
    private FetchFileDecision decision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_id", updatable = false,
            foreignKey = @ForeignKey(name = "fk_fetch_file_observation_delivery"))
    private Delivery delivery;

    protected FetchFileObservation() {
        // JPA
    }

    /**
     * @throws IllegalArgumentException een verplicht gegeven ontbreekt, de naam is te lang, de grootte negatief of een
     *                                  levering hangt aan een andere beslissing dan {@code SELECTED}
     */
    public FetchFileObservation(TaskRun taskRun, String remoteFileName, long byteSize, Instant remoteModifiedAt,
                                FetchFileDecision decision, Delivery delivery) {
        if (taskRun == null) {
            throw new IllegalArgumentException("taskRun is required");
        }
        if (remoteFileName == null || remoteFileName.isEmpty() || remoteFileName.length() > MAX_FILE_NAME_LENGTH) {
            throw new IllegalArgumentException("remoteFileName must be 1 to " + MAX_FILE_NAME_LENGTH + " characters");
        }
        if (byteSize < 0) {
            throw new IllegalArgumentException("byteSize must not be negative");
        }
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        if (delivery != null && decision != FetchFileDecision.SELECTED) {
            throw new IllegalArgumentException("only a SELECTED observation carries a delivery");
        }
        this.taskRun = taskRun;
        this.remoteFileName = remoteFileName;
        this.byteSize = byteSize;
        this.remoteModifiedAt = remoteModifiedAt;
        this.decision = decision;
        this.delivery = delivery;
    }

    public Long getId() {
        return id;
    }

    public TaskRun getTaskRun() {
        return taskRun;
    }

    public String getRemoteFileName() {
        return remoteFileName;
    }

    public long getByteSize() {
        return byteSize;
    }

    public Instant getRemoteModifiedAt() {
        return remoteModifiedAt;
    }

    public FetchFileDecision getDecision() {
        return decision;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    @Override
    public String toString() {
        return "FetchFileObservation[id=" + id + ", decision=" + decision + ", byteSize=" + byteSize + "]";
    }
}
