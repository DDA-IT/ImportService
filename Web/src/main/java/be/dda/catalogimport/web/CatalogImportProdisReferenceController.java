package be.dda.catalogimport.web;

import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierCatalogRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierGroupRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierRow;
import be.dda.catalogimport.service.ProdisReferenceService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Keuzelijsten uit de alleen-lezen Prodis-database ({@code docs/design/legacy-config-bundle-design.md} par. 6, slice S5):
 * leveranciers, hun groepen en hun bibliotheken, als businesscodes (nooit Prodis-id's).
 *
 * <ul>
 *   <li>{@code GET /suppliers?q=}: nummer begint met {@code q} of naam bevat {@code q}; hoogstens 50.</li>
 *   <li>{@code GET /suppliers/{nr}/groups}: {@code legacyCode} ({@code lev|grp|korting}), {@code groupName},
 *       {@code discountCode}.</li>
 *   <li>{@code GET /suppliers/{nr}/catalogs}: één rij per {@code scopeKey} (= mogelijke {@code libraryCode}).</li>
 * </ul>
 * Alle drie {@code MANAGE} (zoals de legacy-config-endpoints; wie migreert, kiest). 403 vóór alles; 409
 * {@code PRODIS_DB_NOT_CONFIGURED} zonder configuratie; 503 {@code PRODIS_DB_UNAVAILABLE} bij een onbereikbare database;
 * 404 {@code PRODIS_SUPPLIER_NOT_FOUND} voor een onbekende leverancier. Niet achter {@code catalogimport.setup-api.enabled}.
 */
@RestController
@RequestMapping("/api/catalog-import/prodis-reference")
public class CatalogImportProdisReferenceController {

    private final ProdisReferenceService prodis;

    public CatalogImportProdisReferenceController(ProdisReferenceService prodis) {
        this.prodis = prodis;
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/suppliers")
    List<SupplierRow> suppliers(@RequestParam(value = "q", required = false) String q) {
        return prodis.searchSuppliers(q);
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/suppliers/{supplierNumber}/groups")
    List<SupplierGroupRow> groups(@PathVariable("supplierNumber") String supplierNumber) {
        return prodis.supplierGroups(supplierNumber);
    }

    @RequiresPermission(Permission.MANAGE)
    @GetMapping("/suppliers/{supplierNumber}/catalogs")
    List<SupplierCatalogRow> catalogs(@PathVariable("supplierNumber") String supplierNumber) {
        return prodis.supplierCatalogs(supplierNumber);
    }
}
