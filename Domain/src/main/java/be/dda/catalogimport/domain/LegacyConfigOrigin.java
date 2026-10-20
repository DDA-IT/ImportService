package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Herkomst van een legacy-interface en wat CatalogImport ervoor aanmaakte (changeset 020-2;
 * {@code docs/design/legacy-config-bundle-design.md} par. 4). Uniek per ({@code originEnvironment},
 * {@code originDossier}, {@code interfaceCode}).
 * <p>
 * Alle verwijzingen zijn nullable en worden stap voor stap aangevuld: de koppeling, de taak en de laatst toegepaste
 * ontvangst door APPLY (S2), het credential door een overgenomen secret (S4). <b>S1 maakt enkel het schema en deze
 * entiteit; er wordt nog geen rij geschreven.</b> {@code credentialId} is enkel een verwijzing naar de versleutelde
 * opslag, nooit een waarde.
 */
@Entity
@Table(name = "legacy_config_origin",
        uniqueConstraints = @UniqueConstraint(name = "uk_legacy_config_origin",
                columnNames = {"origin_environment", "origin_dossier", "interface_code"}))
public class LegacyConfigOrigin {

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

    @Column(name = "import_link_id")
    private Long importLinkId;

    @Column(name = "catalog_import_task_id")
    private Long catalogImportTaskId;

    @Column(name = "credential_id")
    private Long credentialId;

    @Column(name = "last_applied_receipt_id")
    private Long lastAppliedReceiptId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected LegacyConfigOrigin() {
        // JPA
    }

    /** Een nieuwe herkomst zonder verwijzingen. */
    public LegacyConfigOrigin(String originEnvironment, String originDossier, String interfaceCode,
                              Instant createdAt) {
        this.originEnvironment = requiredText(originEnvironment, "originEnvironment");
        this.originDossier = requiredText(originDossier, "originDossier");
        this.interfaceCode = requiredText(interfaceCode, "interfaceCode");
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }
        this.createdAt = createdAt;
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

    public Long getImportLinkId() {
        return importLinkId;
    }

    public Long getCatalogImportTaskId() {
        return catalogImportTaskId;
    }

    public Long getCredentialId() {
        return credentialId;
    }

    public Long getLastAppliedReceiptId() {
        return lastAppliedReceiptId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "LegacyConfigOrigin[id=" + id + ", origin=" + originEnvironment + "/" + originDossier + "/"
                + interfaceCode + "]";
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
