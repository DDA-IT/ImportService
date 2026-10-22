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
 * Welke importdefinitie een legacy-definitie vertegenwoordigt (changeset 020-3 in
 * {@code 021-legacy-config-definition-origin.sql}; {@code docs/design/legacy-config-bundle-design.md} par. 4 en 5;
 * beslissingslog 2026-10-20 M1 en "Legacy-config S2").
 * <p>
 * Groepssleutel ({@code originEnvironment}, {@code originDossier}, {@code supplierCode}, {@code definitionContentHash}),
 * uniek. Een definitie met zo'n rij is <b>gemigreerd</b>; een definitie zonder is handmatig en nooit een migratiedoel.
 * Sinds S7 (beslissingslog 2026-10-21 "Legacy-config S7: gedeelde definities", A2) is {@code importDefinitionId} de
 * <b>gedeelde</b> {@code OWN_DEFINITION} van de groep en {@code templateDefinitionId} het {@code REUSABLE_TEMPLATE} waarop
 * ze gebaseerd is; een rij uit S2 (vóór S7) heeft geen sjabloon. Bij een gewijzigde legacy-definitie wordt de hash ter
 * plaatse bijgewerkt ({@link #moveTo(String, String, Instant)}); de historiek per revisie staat in
 * {@link LegacyConfigRevisionOrigin}.
 * <p>
 * <b>Twee soorten rijen (changeset 026-1, G2a; beslissingslog 2026-10-22 "G2a: loskoppelen van een gedeelde
 * definitie"):</b>
 * <ul>
 *   <li><b>groep</b> ({@link #isGroup()}): {@code group_marker = TRUE}, geen {@code detached_interface_code}. De
 *       groepssleutel hierboven is uniek onder groepen ({@code uk_legacy_config_definition_origin} draagt de marker);
 *       enkel een groep wordt door een nieuwe interface herkend en trekt leden aan;</li>
 *   <li><b>losgekoppelde kopie</b> ({@link #isDetached()}): de eigen {@code OWN_DEFINITION} van één interface die van
 *       haar groep loskoppelde; {@code group_marker = NULL}, {@code detached_interface_code} = die interface (uniek per
 *       omgeving/dossier), nooit een sjabloon. Ze mag dezelfde inhoudshash hebben als een groep.</li>
 * </ul>
 * De soort ligt vast bij het aanmaken ({@code updatable = false}).
 */
@Entity
@Table(name = "legacy_config_definition_origin",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_legacy_config_definition_origin",
                        columnNames = {"origin_environment", "origin_dossier", "supplier_code",
                                "definition_content_hash", "group_marker"}),
                @UniqueConstraint(name = "uk_legacy_config_definition_origin_detached",
                        columnNames = {"origin_environment", "origin_dossier", "detached_interface_code"})
        })
public class LegacyConfigDefinitionOrigin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "origin_environment", nullable = false, updatable = false, length = 40)
    private String originEnvironment;

    @Column(name = "origin_dossier", nullable = false, updatable = false, length = 40)
    private String originDossier;

    @Column(name = "supplier_code", nullable = false, updatable = false, length = 50)
    private String supplierCode;

    @Column(name = "definition_content_hash", nullable = false, length = 64)
    private String definitionContentHash;

    @Column(name = "legacy_definition_codes", nullable = false)
    private String legacyDefinitionCodes;

    @Column(name = "template_definition_id")
    private Long templateDefinitionId;

    @Column(name = "import_definition_id", nullable = false, updatable = false)
    private Long importDefinitionId;

    /** 026-1: {@code TRUE} = groep, {@code NULL} = losgekoppelde kopie (NULL-markertruc, nooit {@code FALSE}). */
    @Column(name = "group_marker", updatable = false)
    private Boolean groupMarker;

    /** 026-1: de interface die loskoppelde; enkel bij een losgekoppelde kopie. */
    @Column(name = "detached_interface_code", updatable = false, length = 20)
    private String detachedInterfaceCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected LegacyConfigDefinitionOrigin() {
        // JPA
    }

    public LegacyConfigDefinitionOrigin(String originEnvironment, String originDossier, String supplierCode,
                                        String definitionContentHash, String legacyDefinitionCodes,
                                        Long importDefinitionId, Instant createdAt) {
        this.originEnvironment = requiredText(originEnvironment, "originEnvironment");
        this.originDossier = requiredText(originDossier, "originDossier");
        this.supplierCode = requiredText(supplierCode, "supplierCode");
        this.definitionContentHash = requiredText(definitionContentHash, "definitionContentHash");
        this.legacyDefinitionCodes = requiredText(legacyDefinitionCodes, "legacyDefinitionCodes");
        this.importDefinitionId = required(importDefinitionId, "importDefinitionId");
        this.createdAt = required(createdAt, "createdAt");
        this.groupMarker = Boolean.TRUE;
    }

    /**
     * S7: een groep met haar sjabloon ({@code REUSABLE_TEMPLATE}) en haar gedeelde definitie ({@code OWN_DEFINITION}
     * {@code based_on} dat sjabloon).
     */
    public LegacyConfigDefinitionOrigin(String originEnvironment, String originDossier, String supplierCode,
                                        String definitionContentHash, String legacyDefinitionCodes,
                                        Long templateDefinitionId, Long importDefinitionId, Instant createdAt) {
        this(originEnvironment, originDossier, supplierCode, definitionContentHash, legacyDefinitionCodes,
                importDefinitionId, createdAt);
        this.templateDefinitionId = required(templateDefinitionId, "templateDefinitionId");
    }

    /**
     * G2a: de eigen kopie van één interface die van haar groep loskoppelde ({@code OWN_DEFINITION} {@code based_on} de
     * gedeelde definitie, zonder sjabloon). {@code definitionContentHash} is de legacy-inhoud die de kopie nu
     * vertegenwoordigt, {@code legacyDefinitionCodes} de legacy-code van die interface.
     */
    public static LegacyConfigDefinitionOrigin detached(String originEnvironment, String originDossier,
                                                        String supplierCode, String definitionContentHash,
                                                        String legacyDefinitionCodes, Long importDefinitionId,
                                                        String detachedInterfaceCode, Instant createdAt) {
        LegacyConfigDefinitionOrigin row = new LegacyConfigDefinitionOrigin(originEnvironment, originDossier,
                supplierCode, definitionContentHash, legacyDefinitionCodes, importDefinitionId, createdAt);
        row.groupMarker = null;
        row.detachedInterfaceCode = requiredText(detachedInterfaceCode, "detachedInterfaceCode");
        return row;
    }

    /**
     * De legacy-definitie van deze definitie wijzigde: de rij vertegenwoordigt voortaan de nieuwe inhoud (beslissing
     * S2, "020-3-hash ter plaatse bijwerken").
     */
    public void moveTo(String newDefinitionContentHash, String newLegacyDefinitionCodes, Instant at) {
        this.definitionContentHash = requiredText(newDefinitionContentHash, "definitionContentHash");
        this.legacyDefinitionCodes = requiredText(newLegacyDefinitionCodes, "legacyDefinitionCodes");
        this.updatedAt = required(at, "updatedAt");
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

    public String getSupplierCode() {
        return supplierCode;
    }

    public String getDefinitionContentHash() {
        return definitionContentHash;
    }

    public String getLegacyDefinitionCodes() {
        return legacyDefinitionCodes;
    }

    public Long getTemplateDefinitionId() {
        return templateDefinitionId;
    }

    public Long getImportDefinitionId() {
        return importDefinitionId;
    }

    /** Een groep (S7): herkend door nieuwe interfaces, met leden. */
    public boolean isGroup() {
        return Boolean.TRUE.equals(groupMarker);
    }

    /** De eigen kopie van een losgekoppelde interface (G2a); nooit een groep. */
    public boolean isDetached() {
        return detachedInterfaceCode != null;
    }

    /** De interface die loskoppelde, of {@code null} voor een groep. */
    public String getDetachedInterfaceCode() {
        return detachedInterfaceCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "LegacyConfigDefinitionOrigin[id=" + id + ", origin=" + originEnvironment + "/" + originDossier
                + ", supplier=" + supplierCode + ", definition=" + importDefinitionId
                + (detachedInterfaceCode == null ? "" : ", detachedFor=" + detachedInterfaceCode) + "]";
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
