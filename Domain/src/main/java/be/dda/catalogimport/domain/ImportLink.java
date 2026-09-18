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
