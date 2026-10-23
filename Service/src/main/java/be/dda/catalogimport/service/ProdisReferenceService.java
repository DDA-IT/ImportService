package be.dda.catalogimport.service;

import be.dda.catalogimport.dao.ProdisReferenceDao;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleIdentity;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleLookup;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierCatalogRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierGroupRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.SupplierRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.UnitOfMeasureRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.VatCodeRow;
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

    /**
     * Wat de Prodis-database zegt over een vaste leverancier en/of groep van een revisie (legacy-config S6,
     * {@code docs/design/legacy-config-bundle-design.md} par. 5 rijen 9/10).
     *
     * @param suppliers de leveranciers met exact het gevraagde nummer (0, 1 of 2 = niet eenduidig); leeg als er geen
     *                  leverancier gevraagd werd of tenzij {@code VERIFIED}
     * @param groups    alle groepen van de gevraagde groepsleverancier; leeg als er geen groep gevraagd werd of tenzij
     *                  {@code VERIFIED}
     */
    public record FixedIdentityLookup(Availability availability, List<SupplierRow> suppliers,
                                      List<SupplierGroupRow> groups) {

        public FixedIdentityLookup {
            suppliers = List.copyOf(suppliers);
            groups = List.copyOf(groups);
        }

        static FixedIdentityLookup of(Availability availability) {
            return new FixedIdentityLookup(availability, List.of(), List.of());
        }

        /** Of er een groep bestaat waarvan de code ({@code group_name}, getrimd) exact {@code groupCode} is. */
        public boolean hasGroup(String groupCode) {
            return groups.stream().anyMatch(group -> group.groupName() != null
                    && group.groupName().trim().equals(groupCode));
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

    /** De actieve btw-codes, één per code, op code (CD-B1); 409 zonder configuratie, 503 onbereikbaar. */
    public List<VatCodeRow> vatCodes() {
        requireConfigured();
        try {
            return dao.findVatCodes();
        } catch (DataAccessException failure) {
            throw unavailable(failure);
        }
    }

    /** De actieve eenheden, één per code, op code (CD-B1); 409 zonder configuratie, 503 onbereikbaar. */
    public List<UnitOfMeasureRow> unitsOfMeasure() {
        requireConfigured();
        try {
            return dao.findUnitsOfMeasure();
        } catch (DataAccessException failure) {
            throw unavailable(failure);
        }
    }

    // --- Aanmaakstandaard (CD-B1; beslissingslog 2026-10-23) ------------------------------------------------------

    /** Uitkomst van de controle van één code. {@code NOT_REQUESTED} = er werd geen code gevraagd (null). */
    public enum CodeMatch {
        FOUND, NOT_FOUND, AMBIGUOUS, NOT_CONFIGURED, NOT_REQUESTED
    }

    /**
     * @param vat  uitkomst voor de btw-code ({@code AMBIGUOUS} = meer dan één actieve rij met die code)
     * @param unit uitkomst voor de eenheid
     */
    public record CreationDefaultsCheck(Availability availability, CodeMatch vat, CodeMatch unit) {
    }

    /**
     * Voor de setup-API: controleert een btw-code en/of eenheid (getrimd, exact) tegen de actieve Prodis-rijen. Niet
     * geconfigureerd = {@code NOT_CONFIGURED} (de aanroeper laat de waarde toe met een waarschuwing); onbereikbaar = 503
     * zoals {@link #verifyFixedIdentity}.
     *
     * @throws ProdisReferenceUnavailableException 503 {@value ProdisReferenceUnavailableException#CODE}
     */
    public CreationDefaultsCheck verifyCreationDefaults(String vatCode, String unitCode) {
        if (!dao.isConfigured()) {
            return new CreationDefaultsCheck(Availability.NOT_CONFIGURED,
                    vatCode == null ? CodeMatch.NOT_REQUESTED : CodeMatch.NOT_CONFIGURED,
                    unitCode == null ? CodeMatch.NOT_REQUESTED : CodeMatch.NOT_CONFIGURED);
        }
        try {
            CodeMatch vat = vatCode == null ? CodeMatch.NOT_REQUESTED : match(dao.findVatCodes().stream()
                    .filter(row -> row.code().equals(vatCode.trim())).mapToInt(VatCodeRow::occurrences).sum());
            CodeMatch unit = unitCode == null ? CodeMatch.NOT_REQUESTED : match(dao.findUnitsOfMeasure().stream()
                    .filter(row -> row.code().equals(unitCode.trim())).mapToInt(UnitOfMeasureRow::occurrences).sum());
            return new CreationDefaultsCheck(Availability.VERIFIED, vat, unit);
        } catch (DataAccessException failure) {
            throw unavailable(failure);
        }
    }

    private static CodeMatch match(int occurrences) {
        return occurrences == 0 ? CodeMatch.NOT_FOUND : occurrences == 1 ? CodeMatch.FOUND : CodeMatch.AMBIGUOUS;
    }

    /**
     * Wat PREVIEW/APPLY van de legacy-config-overname (CD-B3) over de geplande aanmaakstandaard weet.
     *
     * @param vat      uitkomst voor de btw-code, zoals {@link CreationDefaultsCheck}; {@code NOT_CONFIGURED} ook bij
     *                 {@code UNAVAILABLE} (niets geverifieerd)
     * @param vatRows  de gelezen actieve Prodis-rij(en) met exact die code (0 of 1 rij, met {@code occurrences}); leeg
     *                 tenzij {@code VERIFIED}. Voor de {@code previewFingerprint}.
     * @param unitRows idem voor de eenheid
     */
    public record CreationDefaultsLookup(Availability availability, CodeMatch vat, CodeMatch unit,
                                         List<VatCodeRow> vatRows, List<UnitOfMeasureRow> unitRows) {

        public CreationDefaultsLookup {
            vatRows = List.copyOf(vatRows);
            unitRows = List.copyOf(unitRows);
        }
    }

    /**
     * Voor PREVIEW/APPLY van de migratie (CD-B3): dezelfde controle als {@link #verifyCreationDefaults}, maar nooit een
     * exceptie voor "uit" of "onbereikbaar" (zelfde regels als {@link #lookup} en {@link #lookupFixedIdentity}): de
     * aanroeper zet de waarde dan toch, met {@code PRODIS_REFERENCE_NOT_VERIFIED} en (onbereikbaar) een "unverified"
     * fingerprint.
     */
    public CreationDefaultsLookup lookupCreationDefaults(String vatCode, String unitCode) {
        CodeMatch vatRequested = vatCode == null ? CodeMatch.NOT_REQUESTED : CodeMatch.NOT_CONFIGURED;
        CodeMatch unitRequested = unitCode == null ? CodeMatch.NOT_REQUESTED : CodeMatch.NOT_CONFIGURED;
        if (!dao.isConfigured()) {
            return new CreationDefaultsLookup(Availability.NOT_CONFIGURED, vatRequested, unitRequested, List.of(),
                    List.of());
        }
        try {
            List<VatCodeRow> vatRows = vatCode == null ? List.of() : dao.findVatCodes().stream()
                    .filter(row -> row.code().equals(vatCode.trim())).toList();
            List<UnitOfMeasureRow> unitRows = unitCode == null ? List.of() : dao.findUnitsOfMeasure().stream()
                    .filter(row -> row.code().equals(unitCode.trim())).toList();
            return new CreationDefaultsLookup(Availability.VERIFIED,
                    vatCode == null ? CodeMatch.NOT_REQUESTED
                            : match(vatRows.stream().mapToInt(VatCodeRow::occurrences).sum()),
                    unitCode == null ? CodeMatch.NOT_REQUESTED
                            : match(unitRows.stream().mapToInt(UnitOfMeasureRow::occurrences).sum()),
                    vatRows, unitRows);
        } catch (DataAccessException failure) {
            logUnavailable(failure);
            return new CreationDefaultsLookup(Availability.UNAVAILABLE, vatRequested, unitRequested, List.of(),
                    List.of());
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

    // --- Vaste leverancier/groep (legacy-config S6) --------------------------------------------------------------

    /**
     * Voor PREVIEW/APPLY van de migratie: nooit een exceptie voor "uit" of "onbereikbaar" (zelfde regels als
     * {@link #lookup}).
     *
     * @param supplierNumber      de vaste leverancier om te controleren, of {@code null}
     * @param groupSupplierNumber de leverancier waarvan de groepen gelezen worden (voor een vaste groep), of {@code null}
     */
    public FixedIdentityLookup lookupFixedIdentity(String supplierNumber, String groupSupplierNumber) {
        if (!dao.isConfigured()) {
            return FixedIdentityLookup.of(Availability.NOT_CONFIGURED);
        }
        try {
            return readFixedIdentity(supplierNumber, groupSupplierNumber);
        } catch (DataAccessException failure) {
            logUnavailable(failure);
            return FixedIdentityLookup.of(Availability.UNAVAILABLE);
        }
    }

    /**
     * Voor de setup-API: niet geconfigureerd = {@link Availability#NOT_CONFIGURED} (de aanroeper laat de waarde toe met
     * een waarschuwing); onbereikbaar = 503 zoals de keuzelijsten (S5).
     *
     * @throws ProdisReferenceUnavailableException 503 {@value ProdisReferenceUnavailableException#CODE}
     */
    public FixedIdentityLookup verifyFixedIdentity(String supplierNumber, String groupSupplierNumber) {
        if (!dao.isConfigured()) {
            return FixedIdentityLookup.of(Availability.NOT_CONFIGURED);
        }
        try {
            return readFixedIdentity(supplierNumber, groupSupplierNumber);
        } catch (DataAccessException failure) {
            throw unavailable(failure);
        }
    }

    // --- Publicatie-parallelrun (P1; docs/design/publicatie-prodis-db-direct-design.md par. 5) --------------------

    /** De modus van Prodis ({@code system_settings.legacy_master}); {@code UNKNOWN} = niet gelezen of onleesbaar. */
    public enum LegacyMasterMode {
        NONE, PRODIS, PROFAB, UNKNOWN
    }

    /**
     * @param availability {@code NOT_CONFIGURED}, {@code UNAVAILABLE} (onbereikbaar, of tabel/kolom/rechten ontbreken) of
     *                     {@code VERIFIED}
     * @param mode         {@code UNKNOWN} tenzij {@code VERIFIED}; ook {@code UNKNOWN} bij een onbekende waarde in Prodis
     */
    public record LegacyMasterReading(Availability availability, LegacyMasterMode mode) {
    }

    /**
     * Leest de modus van Prodis: de eerste rij op id; <b>geen rij = PRODIS</b> (zoals Prodis' {@code LegacyMasterResolver}).
     * Een fout of een onbekende waarde wordt nooit stil een default: {@code UNAVAILABLE} resp. {@code UNKNOWN}. Gooit
     * nooit; de aanroeper toont "onbekend" en weigert niets (design par. 5).
     */
    public LegacyMasterReading legacyMaster() {
        if (!dao.isConfigured()) {
            return new LegacyMasterReading(Availability.NOT_CONFIGURED, LegacyMasterMode.UNKNOWN);
        }
        try {
            List<String> rows = dao.readLegacyMaster();
            if (rows.isEmpty()) {
                return new LegacyMasterReading(Availability.VERIFIED, LegacyMasterMode.PRODIS);
            }
            String raw = rows.get(0);
            LegacyMasterMode mode = LegacyMasterMode.UNKNOWN;
            for (LegacyMasterMode candidate : LegacyMasterMode.values()) {
                if (candidate != LegacyMasterMode.UNKNOWN && candidate.name().equals(raw)) {
                    mode = candidate;
                }
            }
            if (mode == LegacyMasterMode.UNKNOWN) {
                LOG.warn("Unreadable system_settings.legacy_master value in Prodis; treated as unknown");
            }
            return new LegacyMasterReading(Availability.VERIFIED, mode);
        } catch (DataAccessException failure) {
            logUnavailable(failure);
            return new LegacyMasterReading(Availability.UNAVAILABLE, LegacyMasterMode.UNKNOWN);
        }
    }

    /**
     * @param availability {@code NOT_CONFIGURED} of {@code UNAVAILABLE}: {@code lookups} is dan leeg (nooit "niet
     *                     gevonden" als antwoord op een fout)
     * @param lookups      één element per verschillende identiteit, in volgorde van de invoer; zonder Prodis-id's
     */
    public record CatalogArticleLookups(Availability availability, List<CatalogArticleLookup> lookups) {

        public CatalogArticleLookups {
            lookups = List.copyOf(lookups);
        }
    }

    /**
     * Leest de catalogusrijen voor de publicatie-parallelrun, in batches (geen N+1). Gooit nooit voor "uit" of
     * "onbereikbaar"; de planner bepaalt uit de vorm {@code ARTICLE_NOT_FOUND}, {@code ARTICLE_AMBIGUOUS},
     * {@code SUPPLIER_AMBIGUOUS} en {@code PRODIS_ROW_SHAPE_UNSUPPORTED}.
     */
    public CatalogArticleLookups lookupCatalogArticles(List<CatalogArticleIdentity> identities) {
        if (!dao.isConfigured()) {
            return new CatalogArticleLookups(Availability.NOT_CONFIGURED, List.of());
        }
        try {
            return new CatalogArticleLookups(Availability.VERIFIED, dao.findCatalogArticles(identities));
        } catch (DataAccessException failure) {
            logUnavailable(failure);
            return new CatalogArticleLookups(Availability.UNAVAILABLE, List.of());
        }
    }

    private FixedIdentityLookup readFixedIdentity(String supplierNumber, String groupSupplierNumber) {
        List<SupplierRow> suppliers = supplierNumber == null ? List.of() : dao.findSupplier(supplierNumber);
        List<SupplierGroupRow> groups = groupSupplierNumber == null ? List.of()
                : dao.findSupplierGroups(groupSupplierNumber);
        return new FixedIdentityLookup(Availability.VERIFIED, suppliers, groups);
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
