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
 * Importkoppeling: bindt één {@link ImportDefinition} aan een concrete leverancierscontext en aan
 * de scope waarbinnen die geldt (doelbibliotheek).
 * <p>
 * Businessanalyse §14.17: de Importkoppeling is de zelfstandige import die leveringen ontvangt en
 * publiceert; zij draagt de doelbibliotheek, de leverancierscontext en de ingevulde bookmarks.
 * §14.15 houdt de <b>bibliotheekzoekleverancier</b> ({@code PSBIB.Leveranciernr}) bewust apart van
 * de detailleverancier; §15.2 punt 3 stelt dat die zoekleverancier een filter is en géén identiteit.
 * <p>
 * Bibliotheek en bronorganisatie zijn hier uitsluitend <b>scope</b>: zij maken nooit deel uit van
 * de aanbiedingsidentiteit (beslissingslog 18/09 "Fase 0: aanbiedingsidentiteit").
 */
@Entity
@Table(name = "import_link",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_import_link_code", columnNames = "code"),
                @UniqueConstraint(name = "uk_import_link_scope",
                        columnNames = {"import_definition_id", "supplier_organisation_id", "library_code"})
        })
public class ImportLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** Zakelijke referentie van de koppeling, bv. {@code VROOAM-ABP4-PSARF012}. */
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_definition_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_link_definition"))
    private ImportDefinition importDefinition;

    /** De concrete leverancier/aankoopgroepsrelatie waarvoor deze koppeling importeert (§14.15). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_organisation_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_import_link_supplier_organisation"))
    private SourceOrganisation supplierOrganisation;

    /** Doelbibliotheek, bv. {@code PSARF012}. Scope, geen sleutelonderdeel. */
    @Column(name = "library_code", nullable = false, length = 20)
    private String libraryCode;

    /**
     * Optionele bibliotheekzoekleverancier ({@code PSBIB.Leveranciernr}). Uitsluitend een
     * zoekfilter op bibliotheekniveau; nooit automatisch de leverancier van een detailregel
     * (§15.2 punt 3, §5.7.2).
     */
    @Column(name = "library_search_supplier_code", length = 50)
    private String librarySearchSupplierCode;

    /**
     * Optionele vaste valuta van de koppeling (ISO-4217-vorm, drie hoofdletters), gebruikt als er geen
     * muntveld gemapt is; {@code null} = geen vaste valuta (valuta-standaard-design par. 2).
     */
    @Column(name = "default_currency", length = 3)
    private String defaultCurrency;

    /**
     * Aanmaakstandaard (changeset 030; beslissingslog 2026-10-23 "Aanmaakstandaard per koppeling: uitwerking (CD)"):
     * de Prodis-btw-code ({@code vat_code.code}) die CatalogImport gebruikt wanneer het later een artikel aanmaakt (P8);
     * nooit om een bestaand artikel te overschrijven. {@code null} = geen standaard.
     */
    @Column(name = "default_vat_code", length = 50)
    private String defaultVatCode;

    /** Aanmaakstandaard: de Prodis-eenheid ({@code unit_of_measure.code}); zie {@link #defaultVatCode}. */
    @Column(name = "default_unit_code", length = 50)
    private String defaultUnitCode;

    /** Wie de huidige aanmaakstandaard zette; {@code null} = nooit gezet. */
    @Enumerated(EnumType.STRING)
    @Column(name = "creation_defaults_origin", length = 20)
    private CreationDefaultsOrigin creationDefaultsOrigin;

    /** Audit van de laatste wijziging van de aanmaakstandaard (ook een wissing). */
    @Column(name = "creation_defaults_updated_at")
    private Instant creationDefaultsUpdatedAt;

    @Column(name = "creation_defaults_updated_by", length = 100)
    private String creationDefaultsUpdatedBy;

    /** OIDC-subject bij {@link #creationDefaultsUpdatedBy}; {@code null} = geen geverifieerde identiteit (zoals 007). */
    @Column(name = "creation_defaults_updated_by_subject", length = 255)
    private String creationDefaultsUpdatedBySubject;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImportLink() {
        // JPA
    }

    public ImportLink(String code, String name, ImportDefinition importDefinition,
                      SourceOrganisation supplierOrganisation, String libraryCode) {
        this.code = code;
        this.name = name;
        this.importDefinition = importDefinition;
        this.supplierOrganisation = supplierOrganisation;
        this.libraryCode = libraryCode;
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ImportDefinition getImportDefinition() {
        return importDefinition;
    }

    public void setImportDefinition(ImportDefinition importDefinition) {
        this.importDefinition = importDefinition;
    }

    public SourceOrganisation getSupplierOrganisation() {
        return supplierOrganisation;
    }

    public void setSupplierOrganisation(SourceOrganisation supplierOrganisation) {
        this.supplierOrganisation = supplierOrganisation;
    }

    public String getLibraryCode() {
        return libraryCode;
    }

    public void setLibraryCode(String libraryCode) {
        this.libraryCode = libraryCode;
    }

    public String getLibrarySearchSupplierCode() {
        return librarySearchSupplierCode;
    }

    public void setLibrarySearchSupplierCode(String librarySearchSupplierCode) {
        this.librarySearchSupplierCode = librarySearchSupplierCode;
    }

    public String getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public String getDefaultVatCode() {
        return defaultVatCode;
    }

    public String getDefaultUnitCode() {
        return defaultUnitCode;
    }

    public CreationDefaultsOrigin getCreationDefaultsOrigin() {
        return creationDefaultsOrigin;
    }

    public Instant getCreationDefaultsUpdatedAt() {
        return creationDefaultsUpdatedAt;
    }

    public String getCreationDefaultsUpdatedBy() {
        return creationDefaultsUpdatedBy;
    }

    public String getCreationDefaultsUpdatedBySubject() {
        return creationDefaultsUpdatedBySubject;
    }

    /**
     * Vervangt de volledige aanmaakstandaard (btw-code én eenheid) en legt vast wie dat deed en wanneer. {@code null}
     * wist een code. Er is geen losse setter per veld: herkomst en audit horen altijd bij de waarden
     * ({@code ck_import_link_creation_defaults_audit}).
     *
     * @param vatCode       de btw-code (getrimd, niet blanco) of {@code null}
     * @param unitCode      de eenheid (getrimd, niet blanco) of {@code null}
     * @param origin        wie de waarden zette; verplicht
     * @param updatedBy     de naam van wie de wijziging deed; verplicht
     * @param updatedBySubject het OIDC-subject, of {@code null} (geen geverifieerde identiteit)
     * @param now           het tijdstip van de wijziging; verplicht
     */
    public void setCreationDefaults(String vatCode, String unitCode, CreationDefaultsOrigin origin, String updatedBy,
                                    String updatedBySubject, Instant now) {
        if (origin == null || updatedBy == null || updatedBy.isBlank() || now == null) {
            throw new IllegalArgumentException("Creation defaults need an origin, a signer and a timestamp");
        }
        this.defaultVatCode = vatCode;
        this.defaultUnitCode = unitCode;
        this.creationDefaultsOrigin = origin;
        this.creationDefaultsUpdatedBy = updatedBy;
        this.creationDefaultsUpdatedBySubject = updatedBySubject;
        this.creationDefaultsUpdatedAt = now;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
