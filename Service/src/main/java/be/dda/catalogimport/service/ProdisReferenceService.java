package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ProdisReferenceDao;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierCatalogRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierGroupRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Leveranciers, groepen en bibliotheken uit de alleen-lezen Prodis-database ({@code docs/design/legacy-config-bundle-design.md}
 * par. 6, slice S5; beslissingslog 2026-10-20 "datasync-aanpak, Prodis-DB lezen, sjablonen").
 *
 * <h2>Businessgedrag</h2>
 * <ul>
 *   <li><b>Niet geconfigureerd</b> = functie uit: de keuzelijsten geven 409 {@value ProdisReferenceNotConfiguredException#CODE};
 *       PREVIEW/APPLY gedragen zich zoals in S2 ({@link Availability#NOT_CONFIGURED}).</li>
 *   <li><b>Onbereikbaar</b> (verbinding, time-out, ontbrekende rechten of tabel): keuzelijsten 503
 *       {@value ProdisReferenceUnavailableException#CODE}; PREVIEW krijgt {@link Availability#UNAVAILABLE} en toont
 *       {@code PRODIS_REFERENCE_NOT_VERIFIED}. Nooit "leeg" als antwoord op een fout (fail-closed).</li>
 *   <li>Enkel businesscodes ({@code supplier.number}, {@code scope_key}, {@code legacy_code}) verlaten deze service;
 *       nooit een Prodis-id.</li>
 * </ul>
 * Bewust <b>niet</b> {@code @Transactional}: deze bron hoort niet bij de transactiemanager van de hoofddatabase; elke
 * query gebruikt een eigen kortlevende verbinding ({@code ProdisReferenceJdbc}).
 */
@Service
public class ProdisReferenceService {

    public static final String CODE_SUPPLIER_NOT_FOUND = "PRODIS_SUPPLIER_NOT_FOUND";
    public static final String CODE_QUERY_INVALID = "PRODIS_REFERENCE_QUERY_INVALID";

    /** Langste zoektekst voor {@link #searchSuppliers}. */
    static final int MAX_QUERY_LENGTH = 100;

    private static final String ACTIVE = "ACTIVE";
    private static final Logger LOG = LoggerFactory.getLogger(ProdisReferenceService.class);

    /** Of de Prodis-gegevens gebruikt konden worden. */
    public enum Availability {
        /** {@code catalogimport.prodis-db.*} niet gezet: functie uit. */
        NOT_CONFIGURED,
        /** Geconfigureerd, maar de query faalde: niets geverifieerd. */
        UNAVAILABLE,
        /** Gelezen; {@link LegacyLookup#suppliers()} en {@link LegacyLookup#catalogs()} zijn de echte Prodis-gegevens. */
        VERIFIED
    }

    /**
     * Wat PREVIEW/APPLY over één leverancier weet.
     *
     * @param suppliers de leveranciers met exact dat nummer (0, 1 of 2 = niet eenduidig); leeg tenzij {@code VERIFIED}
     * @param catalogs  alle bibliotheekrijen van die leverancier (ook historiek), gesorteerd op {@code scope_key}, nieuwste
     *                  eerst; leeg tenzij {@code VERIFIED} met precies één leverancier
     */
    public record LegacyLookup(Availability availability, List<SupplierRow> suppliers,
                               List<SupplierCatalogRow> catalogs) {

        public LegacyLookup {
            suppliers = List.copyOf(suppliers);
            catalogs = List.copyOf(catalogs);
        }

        static LegacyLookup of(Availability availability) {
            return new LegacyLookup(availability, List.of(), List.of());
        }
    }

    private final ProdisReferenceDao dao;

    public ProdisReferenceService(ProdisReferenceDao dao) {
        this.dao = dao;
    }

    public boolean isConfigured() {
        return dao.isConfigured();
    }

    // --- Keuzelijsten (409 zonder configuratie, 503 onbereikbaar) ------------------------------------------------

    /**
     * Leveranciers waarvan het nummer met {@code q} begint of de naam {@code q} bevat; hoogstens
     * {@value ProdisReferenceDao#SEARCH_LIMIT}.
     *
     * @throws BadRequestException 400 {@value #CODE_QUERY_INVALID} bij meer dan {@value #MAX_QUERY_LENGTH} tekens
     */
    public List<SupplierRow> searchSuppliers(String q) {
        requireConfigured();
        if (q != null && q.strip().length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException(CODE_QUERY_INVALID, "q must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        try {
            return dao.searchSuppliers(q);
        } catch (DataAccessException failure) {
            throw unavailable(failure);
        }
    }

    /**
     * De groepen van een leverancier.
     *
     * @throws NotFoundException 404 {@value #CODE_SUPPLIER_NOT_FOUND}
     */
    public List<SupplierGroupRow> supplierGroups(String supplierNumber) {
        requireConfigured();
        try {
            requireSupplier(supplierNumber);
            return dao.findSupplierGroups(supplierNumber);
        } catch (DataAccessException failure) {
            throw unavailable(failure);
        }
    }

    /**
     * De bibliotheken van een leverancier: één rij per {@code scope_key} ({@link #libraries}).
     *
     * @throws NotFoundException 404 {@value #CODE_SUPPLIER_NOT_FOUND}
     */
    public List<SupplierCatalogRow> supplierCatalogs(String supplierNumber) {
        requireConfigured();
        try {
            requireSupplier(supplierNumber);
            return libraries(dao.findSupplierCatalogs(supplierNumber, null));
        } catch (DataAccessException failure) {
            throw unavailable(failure);
        }
    }

    // --- PREVIEW/APPLY (nooit een exceptie voor "uit" of "onbereikbaar") ------------------------------------------

    /** De leverancier en al haar bibliotheekrijen; {@code supplierNumber == null} = niets op te zoeken. */
    public LegacyLookup lookup(String supplierNumber) {
        if (!dao.isConfigured()) {
            return LegacyLookup.of(Availability.NOT_CONFIGURED);
        }
        if (supplierNumber == null) {
            return LegacyLookup.of(Availability.VERIFIED);
        }
        try {
            List<SupplierRow> suppliers = dao.findSupplier(supplierNumber);
            List<SupplierCatalogRow> catalogs = suppliers.size() == 1
                    ? dao.findSupplierCatalogs(supplierNumber, null) : List.of();
            return new LegacyLookup(Availability.VERIFIED, suppliers, catalogs);
        } catch (DataAccessException failure) {
            logUnavailable(failure);
            return LegacyLookup.of(Availability.UNAVAILABLE);
        }
    }

    /**
     * Eén rij per {@code scope_key}, in de volgorde van de invoer: de {@code ACTIVE}-rij als die er is, anders de eerste
     * (de nieuwste, zie {@link ProdisReferenceDao#findSupplierCatalogs}). Een bibliotheek is een {@code scope_key}; de
     * andere rijen zijn haar historiek.
     */
    public static List<SupplierCatalogRow> libraries(List<SupplierCatalogRow> rows) {
        Map<String, SupplierCatalogRow> byScope = new LinkedHashMap<>();
        for (SupplierCatalogRow row : rows) {
            SupplierCatalogRow known = byScope.get(row.scopeKey());
            if (known == null || (!ACTIVE.equals(known.status()) && ACTIVE.equals(row.status()))) {
                byScope.put(row.scopeKey(), row);
            }
        }
        return new ArrayList<>(byScope.values());
    }

    private void requireConfigured() {
        if (!dao.isConfigured()) {
            throw new ProdisReferenceNotConfiguredException();
        }
    }

    private void requireSupplier(String supplierNumber) {
        if (supplierNumber == null || supplierNumber.isBlank() || dao.findSupplier(supplierNumber).isEmpty()) {
            throw new NotFoundException(CODE_SUPPLIER_NOT_FOUND, "Supplier " + supplierNumber
                    + " does not exist in Prodis");
        }
    }

    private static ProdisReferenceUnavailableException unavailable(DataAccessException failure) {
        logUnavailable(failure);
        return new ProdisReferenceUnavailableException(failure);
    }

    /** Enkel het type en de SQLState-boodschap van de Dao; nooit URL, gebruiker of parameters. */
    private static void logUnavailable(DataAccessException failure) {
        LOG.warn("{}: {} ({})", ProdisReferenceUnavailableException.CODE, failure.getMessage(),
                failure.getMostSpecificCause().getClass().getSimpleName());
    }
}
