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
 * Een publicatierun: een poging om een bevroren {@link PublicationBundle} te publiceren
 * (docs/design/fase5-pub-design.md par. 3, changeset 009-1). De statusovergangen die 5-PUB-a gebruikt
 * staan hieronder als drie samengestelde overgangen ({@link #markPreparing}, {@link #recordSimulated},
 * {@link #recordFailed}, bouwstap 5P-7); er zijn bewust geen losse setters, zodat status, marker en de
 * bijbehorende velden nooit los van elkaar gezet kunnen worden.
 * <p>
 * {@code activeMarker} is {@code TRUE} zolang de run niet-terminaal is en {@code null} daarna; samen met
 * {@code uk_publication_run_active} maximaal één niet-terminale run per bundel.
 */
@Entity
@Table(name = "publication_run",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_publication_run_idempotency", columnNames = "idempotency_key"),
                @UniqueConstraint(name = "uk_publication_run_active", columnNames = {"bundle_id", "active_marker"})
        })
public class PublicationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bundle_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_publication_run_bundle"))
    private PublicationBundle bundle;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_mode", nullable = false, length = 30)
    private PublicationTargetMode targetMode;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PublicationRunStatus status = PublicationRunStatus.REQUESTED;

    @Column(name = "requested_by", nullable = false, length = 100)
    private String requestedBy;

    /** OIDC-subject van de aanvrager (changeset 009-1); {@code null} = geen geverifieerde identiteit. */
    @Column(name = "requested_by_subject", length = 255)
    private String requestedBySubject;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Kopie van de bundelhash op het moment van de run. */
    @Column(name = "bundle_content_hash", nullable = false)
    private byte[] bundleContentHash;

    @Column(name = "snapshot_hash")
    private byte[] snapshotHash;

    @Column(name = "payload_hash")
    private byte[] payloadHash;

    @Column(name = "artifact_reference", length = 500)
    private String artifactReference;

    @Column(name = "artifact_sha256", length = 64)
    private String artifactSha256;

    @Column(name = "artifact_byte_size")
    private Long artifactByteSize;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "incomplete_row_count")
    private Long incompleteRowCount;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_message", length = 1000)
    private String failureMessage;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Column(name = "active_marker")
    private Boolean activeMarker;

    protected PublicationRun() {
        // JPA
    }

    /** Nieuwe run: status {@code REQUESTED}, actieve marker {@code TRUE}. */
    public PublicationRun(PublicationBundle bundle, PublicationTargetMode targetMode, int attempt,
                          String requestedBy, String requestedBySubject, Instant requestedAt,
                          byte[] bundleContentHash, byte[] snapshotHash, String idempotencyKey) {
        this.bundle = bundle;
        this.targetMode = targetMode;
        this.attempt = attempt;
        this.requestedBy = requestedBy;
        this.requestedBySubject = requestedBySubject;
        this.requestedAt = requestedAt;
        this.bundleContentHash = bundleContentHash;
        this.snapshotHash = snapshotHash;
        this.idempotencyKey = idempotencyKey;
        this.status = PublicationRunStatus.REQUESTED;
        this.activeMarker = Boolean.TRUE;
    }

    /**
     * {@code REQUESTED → PREPARING} met het starttijdstip (bouwstap 5P-7). De run blijft niet-terminaal,
     * dus {@code active_marker} blijft {@code TRUE}: hij houdt het slot op de bundel vast zolang het
     * artefact gebouwd wordt.
     */
    public void markPreparing(Instant startedAt) {
        this.status = PublicationRunStatus.PREPARING;
        this.startedAt = startedAt;
    }

    /**
     * {@code PREPARING → SIMULATED} (bouwstap 5P-7): het artefact is geschreven en er is <b>geen enkel
     * operationeel effect</b> — nooit te verwarren met {@code APPLIED}. De artefactvelden, de tellers en
     * het eindtijdstip worden samen met de status gezet, en {@code active_marker} wordt {@code null}
     * zodat een volgende run op deze bundel mogelijk wordt ({@code ck_publication_run_marker_status}).
     *
     * @param payloadHash de SHA-256 van exact de geschreven artefactbytes (dezelfde waarde als
     *                    {@code artifactSha256}, binair in plaats van hex)
     * @param incompleteRowCount het aantal rijen met minstens één onbekend of niet-gesnapshot veld; nooit
     *                           stil op 0 gezet
     */
    public void recordSimulated(Instant finishedAt, String artifactReference, String artifactSha256,
                                long artifactByteSize, byte[] payloadHash, long rowCount,
                                long incompleteRowCount) {
        this.status = PublicationRunStatus.SIMULATED;
        this.finishedAt = finishedAt;
        this.artifactReference = artifactReference;
        this.artifactSha256 = artifactSha256;
        this.artifactByteSize = artifactByteSize;
        this.payloadHash = payloadHash;
        this.rowCount = rowCount;
        this.incompleteRowCount = incompleteRowCount;
        this.activeMarker = null;
    }

    /**
     * {@code → FAILED} (bouwstap 5P-7): de run is technisch mislukt. Terminaal, dus {@code finished_at}
     * is verplicht ({@code ck_publication_run_finished}) en {@code failure_code} ook
     * ({@code ck_publication_run_failed}); {@code active_marker} wordt {@code null} zodat er nooit een run
     * in een actieve toestand blijft hangen na een uitzondering.
     *
     * @param failureMessage korte, door de aanroeper al ingekorte uitleg zonder stacktrace of bestandspad
     */
    public void recordFailed(Instant finishedAt, String failureCode, String failureMessage) {
        this.status = PublicationRunStatus.FAILED;
        this.finishedAt = finishedAt;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.activeMarker = null;
    }

    public Long getId() {
        return id;
    }

    public PublicationBundle getBundle() {
        return bundle;
    }

    public PublicationTargetMode getTargetMode() {
        return targetMode;
    }

    public int getAttempt() {
        return attempt;
    }

    public PublicationRunStatus getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getRequestedBySubject() {
        return requestedBySubject;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public byte[] getBundleContentHash() {
        return bundleContentHash;
    }

    public byte[] getSnapshotHash() {
        return snapshotHash;
    }

    public byte[] getPayloadHash() {
        return payloadHash;
    }

    public String getArtifactReference() {
        return artifactReference;
    }

    public String getArtifactSha256() {
        return artifactSha256;
    }

    public Long getArtifactByteSize() {
        return artifactByteSize;
    }

    public Long getRowCount() {
        return rowCount;
    }

    public Long getIncompleteRowCount() {
        return incompleteRowCount;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Boolean getActiveMarker() {
        return activeMarker;
    }
}
