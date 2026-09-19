package be.dda.catalogimport.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Eén logisch doelveld dat een importdefinitie mag vullen (ontwerp fase 3, par. 2 004-1).
 * <p>
 * <b>Referentiedata, geen configuratie per revisie.</b> De catalogus zegt wélke doelvelden er bestaan,
 * van welk type ze zijn, wie ze bezit en welk gewicht ze in de identiteit hebben. Een
 * {@link ImportFieldMapping} verwijst hiernaar en mag daar enkel binnen de toegelaten ruimte van
 * afwijken:
 * <ul>
 *   <li>staat {@link #isOwnerChangeable()} op {@code false}, dan is de eigenaar hard — een revisie die
 *       een andere eigenaar declareert wordt geblokkeerd met {@code CONFIG_OWNER_NOT_CHANGEABLE}
 *       (R-REF-08). Dat geldt voor elke kritieke referentie en voor de velden die de Prodis-gebruiker
 *       bezit ({@code BRAND}, {@code UNIT});</li>
 *   <li>de identiteitsklasse van de mapping moet die van de catalogus zijn, of expliciet
 *       {@link IdentityClass#NONE}; elke andere afwijking is {@code CONFIG_IDENTITY_CLASS_CONFLICT}
 *       (R-STR-05).</li>
 * </ul>
 * De <b>logische naam</b> uit dit veld is ook de veldnaam die in meldingen getoond wordt
 * (meldingsstijl par. 15.12), zodat een gebruiker geen bronkolomnummers hoeft te interpreteren.
 * <p>
 * De code is de natuurlijke sleutel en wordt nooit hergebruikt voor een ander veld: {@code active}
 * zet een veld buiten gebruik zonder de historiek van bestaande mappings te breken.
 */
@Entity
@Table(name = "import_field_catalog")
public class ImportFieldCatalogEntry {

    @Id
    @Column(name = "code", nullable = false, length = 60)
    private String code;

    /** Logische veldnaam zoals ze in meldingen en schermen getoond wordt. */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    private FieldDataType dataType;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_owner", nullable = false, length = 30)
    private FieldOwner defaultOwner;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_class", nullable = false, length = 30)
    private IdentityClass identityClass;

    /** Prijscomponent ({@code BASE_PRICE}, {@code AKP}, {@code VKP1}, ...), of {@code null}. */
    @Column(name = "price_component_code", length = 20)
    private String priceComponentCode;

    /** Soort kritieke referentie ({@code EAN}, {@code PIM_ID}, ...), of {@code null}. */
    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "owner_changeable", nullable = false)
    private boolean ownerChangeable = true;

    /** Waar de waarde in het doelsysteem landt; documentatie, geen uitvoerbaar pad. */
    @Column(name = "target_route", length = 200)
    private String targetRoute;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ImportFieldCatalogEntry() {
        // JPA
    }

    public ImportFieldCatalogEntry(String code, String name, FieldDataType dataType, FieldOwner defaultOwner,
                                   IdentityClass identityClass, int sortOrder) {
        this.code = code;
        this.name = name;
        this.dataType = dataType;
        this.defaultOwner = defaultOwner;
        this.identityClass = identityClass;
        this.sortOrder = sortOrder;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public FieldDataType getDataType() {
        return dataType;
    }

    public void setDataType(FieldDataType dataType) {
        this.dataType = dataType;
    }

    public FieldOwner getDefaultOwner() {
        return defaultOwner;
    }

    public void setDefaultOwner(FieldOwner defaultOwner) {
        this.defaultOwner = defaultOwner;
    }

    public IdentityClass getIdentityClass() {
        return identityClass;
    }

    public void setIdentityClass(IdentityClass identityClass) {
        this.identityClass = identityClass;
    }

    public String getPriceComponentCode() {
        return priceComponentCode;
    }

    public void setPriceComponentCode(String priceComponentCode) {
        this.priceComponentCode = priceComponentCode;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(String referenceType) {
        this.referenceType = referenceType;
    }

    public boolean isOwnerChangeable() {
        return ownerChangeable;
    }

    public void setOwnerChangeable(boolean ownerChangeable) {
        this.ownerChangeable = ownerChangeable;
    }

    public String getTargetRoute() {
        return targetRoute;
    }

    public void setTargetRoute(String targetRoute) {
        this.targetRoute = targetRoute;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
