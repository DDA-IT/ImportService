package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Eén ontvangen legacy-config-bundel (changeset 020-1; {@code docs/design/legacy-config-bundle-design.md} par. 2 en
 * 4; contract {@code docs/design/legacy-config-bundle-v1.md}).
 *
 * <h2>Identiteit</h2>
 * Herkomst = ({@code originEnvironment}, {@code originDossier}, {@code interfaceCode}). Een push is een no-op (200
 * {@code UNCHANGED}) als de <b>laatste</b> ontvangst van de herkomst (hoogste id) dezelfde {@code contentHash} heeft,
 * ongeacht haar status; een terugkerende inhoud (A → B → A) wordt een nieuwe ontvangst, dus {@code contentHash} is per
 * herkomst niet uniek (beslissingslog 2026-10-20 "Legacy-config S1: idempotentie tegenover de laatste ontvangst"). Per
 * herkomst is hoogstens een ontvangst open ({@code RECEIVED}, {@code open_marker = TRUE},
 * {@code uk_legacy_config_receipt_open}); de laatste ontvangst is nooit {@code SUPERSEDED}.
 *
 * <h2>Wat deze klasse nooit bevat</h2>
 * Het legacy-wachtwoord. {@link #getBundleJson()} is de bundel opnieuw geserialiseerd met {@code "secret": null};
 * {@link #isSecretReceived()} zegt of er een secret meekwam. {@link #toString()} toont de bundel niet.
 *
 * <h2>Statusovergangen</h2>
 * Enkel via samengestelde methoden, zodat status, {@code open_marker} en de bijbehorende velden nooit los van elkaar
 * gezet worden (patroon {@code PublicationRun}). S1 kent enkel aanmaken ({@code RECEIVED}) en vervangen
 * ({@link #markSuperseded()} gevolgd door {@link #linkSuccessor(Long)}).
 */
@Entity
@Table(name = "legacy_config_receipt",
        uniqueConstraints = @UniqueConstraint(name = "uk_legacy_config_receipt_open",
                columnNames = {"origin_environment", "origin_dossier", "interface_code", "open_marker"}))
public class LegacyConfigReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "origin_environment", nullable = false, updatable = false, length = 40)
    private String originEnvironment;

    @Column(name = "origin_dossier", nullable = false, updatable = false, length = 40)
    private String originDossier;

    @Column(name = "interface_code", nullable = false, updatable = false, length = 20)
    private String interfaceCode;

    @Column(name = "bundle_version", nullable = false, updatable = false)
    private int bundleVersion;

    /** SHA-256 (hex, kleine letters) over de canonieke inhoud (contract par. 6.3), door CatalogImport herberekend. */
    @Column(name = "content_hash", nullable = false, updatable = false, length = 64)
    private String contentHash;

    /** Contract par. 6.4; {@code null} tenzij {@code definitionStatus = PRESENT}. */
    @Column(name = "definition_content_hash", updatable = false, length = 64)
    private String definitionContentHash;

    /** {@code definition.code} bij {@code definitionStatus = PRESENT}, anders {@code null}. */
    @Column(name = "legacy_definition_code", updatable = false, length = 10)
    private String legacyDefinitionCode;

    /** De bundel zonder secret ({@code "secret": null}); nooit de ruwe body. */
    @Column(name = "bundle_json", nullable = false, updatable = false)
    private String bundleJson;

    @Column(name = "secret_received", nullable = false, updatable = false)
    private boolean secretReceived;

    /** {@code null} = geen secret ontvangen (S1: altijd). */
    @Enumerated(EnumType.STRING)
    @Column(name = "secret_outcome", length = 40)
    private LegacyConfigSecretOutcome secretOutcome;

    /** Verwijzing naar {@code external_credential.id} (vanaf S4); nooit een waarde. */
    @Column(name = "credential_id")
    private Long credentialId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LegacyConfigReceiptStatus status;

    @Column(name = "open_marker")
    private Boolean openMarker;

    @Column(name = "exported_at", nullable = false, updatable = false)
    private Instant exportedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    /** {@code svc:<azp>} van de technische client (M2). */
    @Column(name = "received_by", nullable = false, updatable = false, length = 100)
    private String receivedBy;

    @Column(name = "received_by_subject", updatable = false, length = 255)
    private String receivedBySubject;

    @Column(name = "superseded_by_receipt_id")
    private Long supersededByReceiptId;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "applied_by", length = 100)
    private String appliedBy;

    @Column(name = "applied_by_subject", length = 255)
    private String appliedBySubject;

    @Column(name = "apply_report_json")
    private String applyReportJson;

    @Column(name = "discarded_at")
    private Instant discardedAt;

    @Column(name = "discarded_by", length = 100)
    private String discardedBy;

    @Column(name = "discarded_by_subject", length = 255)
    private String discardedBySubject;

    @Column(name = "discarded_reason", length = 500)
    private String discardedReason;

    protected LegacyConfigReceipt() {
        // JPA
    }

    /**
     * Een nieuwe, open ontvangst ({@link LegacyConfigReceiptStatus#RECEIVED}) zonder secret. De aanroeper heeft de
     * bundel al gevalideerd en beide hashes herberekend.
     *
     * @throws IllegalArgumentException een verplicht gegeven ontbreekt (de melding noemt enkel de naam ervan)
     */
    public LegacyConfigReceipt(String originEnvironment, String originDossier, String interfaceCode, int bundleVersion,
                               String contentHash, String definitionContentHash, String legacyDefinitionCode,
                               String bundleJson, Instant exportedAt, Instant receivedAt, String receivedBy,
                               String receivedBySubject) {
        this.originEnvironment = requiredText(originEnvironment, "originEnvironment");
        this.originDossier = requiredText(originDossier, "originDossier");
        this.interfaceCode = requiredText(interfaceCode, "interfaceCode");
        this.bundleVersion = bundleVersion;
        this.contentHash = requiredText(contentHash, "contentHash");
        this.definitionContentHash = definitionContentHash;
        this.legacyDefinitionCode = legacyDefinitionCode;
        this.bundleJson = requiredText(bundleJson, "bundleJson");
        this.exportedAt = required(exportedAt, "exportedAt");
        this.receivedAt = required(receivedAt, "receivedAt");
        this.receivedBy = requiredText(receivedBy, "receivedBy");
        this.receivedBySubject = receivedBySubject;
        this.secretReceived = false;
        this.secretOutcome = null;
        this.status = LegacyConfigReceiptStatus.RECEIVED;
        this.openMarker = Boolean.TRUE;
    }

    /**
     * {@code RECEIVED → SUPERSEDED}: een nieuwere ontvangst van dezelfde herkomst vervangt deze. Sluit de open marker,
     * zodat de opvolger open kan worden. {@link #linkSuccessor(Long)} volgt in dezelfde transactie zodra de opvolger
     * een id heeft.
     *
     * @throws IllegalStateException de ontvangst is niet {@code RECEIVED}
     */
    public void markSuperseded() {
        if (status != LegacyConfigReceiptStatus.RECEIVED) {
            throw new IllegalStateException("Only a RECEIVED receipt can be superseded; receipt " + id + " is "
                    + status);
        }
        this.status = LegacyConfigReceiptStatus.SUPERSEDED;
        this.openMarker = null;
    }

    /**
     * Legt de opvolger vast van een vervangen ontvangst.
     *
     * @throws IllegalStateException de ontvangst is niet {@code SUPERSEDED} of heeft al een opvolger
     */
    public void linkSuccessor(Long successorId) {
        if (status != LegacyConfigReceiptStatus.SUPERSEDED || supersededByReceiptId != null) {
            throw new IllegalStateException("Receipt " + id + " cannot get a successor in status " + status);
        }
        this.supersededByReceiptId = required(successorId, "successorId");
    }

    public Long getId() {
        return id;
    }

    public String getOriginEnvironment() {
        return originEnvironment;
    }

    public String getOriginDossier() {
        return originDossier;
    }

    public String getInterfaceCode() {
        return interfaceCode;
    }

    public int getBundleVersion() {
        return bundleVersion;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getDefinitionContentHash() {
        return definitionContentHash;
    }

    public String getLegacyDefinitionCode() {
        return legacyDefinitionCode;
    }

    public String getBundleJson() {
        return bundleJson;
    }

    public boolean isSecretReceived() {
        return secretReceived;
    }

    public LegacyConfigSecretOutcome getSecretOutcome() {
        return secretOutcome;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public LegacyConfigReceiptStatus getStatus() {
        return status;
    }

    public Boolean getOpenMarker() {
        return openMarker;
    }

    public Instant getExportedAt() {
        return exportedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getReceivedBy() {
        return receivedBy;
    }

    public String getReceivedBySubject() {
        return receivedBySubject;
    }

    public Long getSupersededByReceiptId() {
        return supersededByReceiptId;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public String getAppliedBy() {
        return appliedBy;
    }

    public String getAppliedBySubject() {
        return appliedBySubject;
    }

    public String getApplyReportJson() {
        return applyReportJson;
    }

    public Instant getDiscardedAt() {
        return discardedAt;
    }

    public String getDiscardedBy() {
        return discardedBy;
    }

    public String getDiscardedBySubject() {
        return discardedBySubject;
    }

    public String getDiscardedReason() {
        return discardedReason;
    }

    /** Zonder bundel: enkel identiteit, hash en status. */
    @Override
    public String toString() {
        return "LegacyConfigReceipt[id=" + id + ", origin=" + originEnvironment + "/" + originDossier + "/"
                + interfaceCode + ", contentHash=" + contentHash + ", status=" + status + "]";
    }

    private static <T> T required(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
