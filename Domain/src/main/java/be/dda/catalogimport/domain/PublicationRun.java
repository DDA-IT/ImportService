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
 * bijbehorende velden nooit los van elkaar gezet kunnen worden. {@link #recordSimulated} en
 * {@link #recordFailed} werken enkel vanuit {@code PREPARING}: een afgebroken run blijft afgebroken.
 * De PRODUCTION-run (P5c) voegt {@link #markPublishing} ({@code PREPARING → PUBLISHING}) en {@link #recordApplied}
 * ({@code PUBLISHING → APPLIED}) toe; de chunk-lease ({@code lease_owner}/{@code lease_until}) wordt met
 * voorwaardelijke updates in de repository genomen en vrijgegeven, nooit via een setter.
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

    /**
     * De {@code legacy_master}-modus bij de start van een PRODUCTION-run (changeset 027-2); {@code null} =
     * niet vastgesteld (elke run die geen PRODUCTION-run is). Gevuld door {@link #markPublishing} (P5c).
     */
    @Column(name = "legacy_master_at_start", length = 30)
    private String legacyMasterAtStart;

    /** Houder van de chunk-lease (changeset 027-2); samen met {@code leaseUntil} gevuld of leeg. */
    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

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
     * @throws IllegalStateException de run staat niet (meer) op {@code PREPARING}, bv. omdat ze intussen
     *                               afgebroken is; de run blijft dan ongewijzigd (docs/decisions.md 2026-10-01,
     *                               analyse-opvolging stap 3b)
     */
    public void recordSimulated(Instant finishedAt, String artifactReference, String artifactSha256,
                                long artifactByteSize, byte[] payloadHash, long rowCount,
                                long incompleteRowCount) {
        requirePreparing("SIMULATED");
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
     * @throws IllegalStateException de run staat niet (meer) op {@code PREPARING}; een afgebroken of al
     *                               afgeronde run blijft ongewijzigd, haar {@code failure_code} wordt nooit
     *                               overschreven (docs/decisions.md 2026-10-01, analyse-opvolging stap 3b)
     */
    public void recordFailed(Instant finishedAt, String failureCode, String failureMessage) {
        requirePreparing("FAILED");
        this.status = PublicationRunStatus.FAILED;
        this.finishedAt = finishedAt;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.activeMarker = null;
    }

    /**
     * {@code PREPARING → PUBLISHING} voor een PRODUCTION-run (P5c, docs/design/publicatie-prodis-db-direct-design.md par.
     * 2.4): de items zijn gematerialiseerd, de run begint te versturen. Niet-terminaal: de marker blijft {@code TRUE}.
     *
     * @param legacyMasterAtStart de modus van Prodis bij de start ({@code NONE|PRODIS|PROFAB|UNKNOWN}); nooit stil een
     *                            default, design par. 5
     * @param itemCount           het aantal gematerialiseerde items ({@code row_count})
     * @throws IllegalStateException geen PRODUCTION-run of niet (meer) {@code PREPARING}; de run blijft ongewijzigd
     */
    public void markPublishing(String legacyMasterAtStart, long itemCount) {
        if (this.targetMode != PublicationTargetMode.PRODUCTION) {
            throw new IllegalStateException("Publication run " + this.id + " is a " + this.targetMode
                    + " run; only a PRODUCTION run publishes");
        }
        requirePreparing("PUBLISHING");
        if (legacyMasterAtStart == null || legacyMasterAtStart.isBlank()) {
            throw new IllegalArgumentException("legacyMasterAtStart is required (UNKNOWN when it could not be read)");
        }
        this.status = PublicationRunStatus.PUBLISHING;
        this.legacyMasterAtStart = legacyMasterAtStart;
        this.rowCount = itemCount;
        // De startcontrole weigert een bundel met onvolledige rijen (BUNDLE_HAS_INCOMPLETE_ROWS): vastgesteld 0.
        this.incompleteRowCount = 0L;
    }

    /**
     * {@code PUBLISHING → APPLIED} (P5c): elk item van deze PRODUCTION-run heeft een definitief antwoord of werd niet
     * verstuurd. Terminaal: {@code finished_at} gezet, marker {@code null} (het slot op bundel en installatie komt vrij,
     * changeset 028-1) en de lease vervalt.
     *
     * @throws IllegalStateException de run staat niet op {@code PUBLISHING}; de run blijft ongewijzigd
     */
    public void recordApplied(Instant finishedAt) {
        if (this.status != PublicationRunStatus.PUBLISHING) {
            throw new IllegalStateException("Publication run " + this.id + " cannot move from " + this.status
                    + " to APPLIED; only a PUBLISHING run can be finished");
        }
        if (finishedAt == null) {
            throw new IllegalArgumentException("finishedAt is required");
        }
        this.status = PublicationRunStatus.APPLIED;
        this.finishedAt = finishedAt;
        this.activeMarker = null;
        this.leaseOwner = null;
        this.leaseUntil = null;
    }

    /** Domeinregel: een run wordt enkel vanuit {@code PREPARING} afgerond. Controleert vóór elke wijziging. */
    private void requirePreparing(String target) {
        if (this.status != PublicationRunStatus.PREPARING) {
            throw new IllegalStateException("Publication run " + this.id + " cannot move from " + this.status
                    + " to " + target + "; only a PREPARING run can be finished");
        }
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

    public String getLegacyMasterAtStart() {
        return legacyMasterAtStart;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }
}
