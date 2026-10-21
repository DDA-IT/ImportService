package be.dda.catalogimport.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Leest leveranciers, leveranciersgroepen en bibliotheken uit de Prodis-database (alleen lezen;
 * {@code docs/design/legacy-config-bundle-design.md} par. 6, slice S5). <b>Alle SQL naar Prodis staat hier.</b>
 *
 * <h2>Tabellen en kolommen (Prodis-Liquibase, bevestigd 2026-10-20)</h2>
 * <ul>
 *   <li>{@code supplier(id, number, name)} — {@code number} heeft in Prodis <b>geen</b> unieke constraint (enkel
 *       {@code idx_supplier__number_coalesced}); daarom geeft {@link #findSupplier} een lijst (hoogstens 2 rijen).</li>
 *   <li>{@code supplier_group(id, supplier_id, group_name, discount_code, legacy_code)} — {@code legacy_code} =
 *       {@code lev|grp|korting} (changelog 20260519120000).</li>
 *   <li>{@code supplier_catalog(id, supplier_id, scope_key, legacy_number, catalog_name, status)} — meerdere rijen per
 *       {@code scope_key} mogelijk (historiek per status; uniek enkel {@code (supplier_id, catalog_name, scope_key)}
 *       waar {@code status = 'ACTIVE'}).</li>
 * </ul>
 * Precies deze kolommen zijn de GRANT van de rol {@code catalogimport_ro} (design par. 6). Prodis-id's worden enkel in
 * joins gebruikt, nooit teruggegeven of bewaard (geen cross-DB-FK).
 * <p>
 * Zonder configuratie bestaat er geen {@link ProdisReferenceJdbc}-bean (bv. in de Dao-tests) of is ze uitgeschakeld:
 * {@link #isConfigured()} is dan {@code false}. Fouten komen als {@code DataAccessException} naar boven; de service
 * vertaalt ze.
 */
@Repository
public class ProdisReferenceDao {

    /** Hoogstens zoveel leveranciers per zoekopdracht. */
    public static final int SEARCH_LIMIT = 50;

    private static final String FIND_SUPPLIER =
            "select s.number, s.name from supplier s where s.number = ? order by s.id limit 2";

    private static final String SEARCH_SUPPLIERS =
            "select s.number, s.name from supplier s "
                    + "where s.number like ? escape '\\' or lower(s.name) like ? escape '\\' "
                    + "order by s.number, s.id limit ?";

    private static final String FIND_GROUPS =
            "select g.legacy_code, g.group_name, g.discount_code from supplier_group g "
                    + "join supplier s on s.id = g.supplier_id where s.number = ? "
                    + "order by g.legacy_code, g.group_name, g.discount_code, g.id";

    private static final String FIND_CATALOGS =
            "select c.scope_key, c.legacy_number, c.catalog_name, c.status from supplier_catalog c "
                    + "join supplier s on s.id = c.supplier_id where s.number = ? "
                    + "order by c.scope_key, c.id desc";

    private static final String FIND_CATALOGS_BY_LEGACY_NUMBER =
            "select c.scope_key, c.legacy_number, c.catalog_name, c.status from supplier_catalog c "
                    + "join supplier s on s.id = c.supplier_id where s.number = ? and c.legacy_number = ? "
                    + "order by c.scope_key, c.id desc";

    /** {@code supplier.number}, {@code supplier.name}. */
    public record SupplierRow(String number, String name) {
    }

    /** {@code supplier_group.legacy_code} ({@code lev|grp|korting}), {@code group_name}, {@code discount_code}. */
    public record SupplierGroupRow(String legacyCode, String groupName, String discountCode) {
    }

    /** {@code supplier_catalog.scope_key}, {@code legacy_number}, {@code catalog_name}, {@code status}. */
    public record SupplierCatalogRow(String scopeKey, String legacyNumber, String catalogName, String status) {
    }

    private final ObjectProvider<ProdisReferenceJdbc> jdbcProvider;

    public ProdisReferenceDao(ObjectProvider<ProdisReferenceJdbc> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    /** {@code true} als er een geconfigureerde Prodis-leesbron is. */
    public boolean isConfigured() {
        ProdisReferenceJdbc jdbc = jdbcProvider.getIfAvailable();
        return jdbc != null && jdbc.isConfigured();
    }

    /**
     * De leverancier(s) met exact dit nummer: normaal 0 of 1; 2 betekent "niet eenduidig" (geen unieke constraint in
     * Prodis), meer wordt niet gelezen.
     */
    public List<SupplierRow> findSupplier(String number) {
        return jdbc().query(FIND_SUPPLIER, row -> new SupplierRow(row.getString(1), row.getString(2)), number);
    }

    /**
     * Leveranciers waarvan het nummer met {@code text} begint of de naam {@code text} bevat (hoofdletterongevoelig),
     * gesorteerd op nummer, hoogstens {@value #SEARCH_LIMIT}. Leeg = de eerste {@value #SEARCH_LIMIT}.
     */
    public List<SupplierRow> searchSuppliers(String text) {
        String escaped = escapeLike(text == null ? "" : text.strip());
        return jdbc().query(SEARCH_SUPPLIERS, row -> new SupplierRow(row.getString(1), row.getString(2)),
                escaped + "%", "%" + escaped.toLowerCase(Locale.ROOT) + "%", SEARCH_LIMIT);
    }

    /** De groepen van de leverancier met dit nummer, gesorteerd op {@code legacy_code}. */
    public List<SupplierGroupRow> findSupplierGroups(String supplierNumber) {
        return jdbc().query(FIND_GROUPS, row -> new SupplierGroupRow(row.getString(1), row.getString(2),
                row.getString(3)), supplierNumber);
    }

    /**
     * De bibliotheken van de leverancier met dit nummer, gesorteerd op {@code scope_key} en daarbinnen de nieuwste rij
     * eerst.
     *
     * @param legacyNumber {@code null} = alle bibliotheken van de leverancier; anders enkel die met exact dit
     *                     {@code legacy_number}
     */
    public List<SupplierCatalogRow> findSupplierCatalogs(String supplierNumber, String legacyNumber) {
        if (legacyNumber == null) {
            return jdbc().query(FIND_CATALOGS, ProdisReferenceDao::catalog, supplierNumber);
        }
        return jdbc().query(FIND_CATALOGS_BY_LEGACY_NUMBER, ProdisReferenceDao::catalog, supplierNumber,
                legacyNumber);
    }

    private static SupplierCatalogRow catalog(ResultSet row) throws SQLException {
        return new SupplierCatalogRow(row.getString(1), row.getString(2), row.getString(3), row.getString(4));
    }

    private ProdisReferenceJdbc jdbc() {
        ProdisReferenceJdbc jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null || !jdbc.isConfigured()) {
            throw new IllegalStateException("The Prodis reference database is not configured");
        }
        return jdbc;
    }

    /** {@code \}, {@code %} en {@code _} letterlijk (escape {@code \}). */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
