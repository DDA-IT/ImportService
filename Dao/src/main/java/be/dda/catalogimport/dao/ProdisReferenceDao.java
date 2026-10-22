package be.dda.catalogimport.dao;

import java.sql.ResultSet;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 *   <li>Publicatie-parallelrun (P1, {@code docs/design/publicatie-prodis-db-direct-design.md} par. 2.3/5):
 *       {@code system_settings(id, legacy_master)}, {@code catalog_article(id, supplier_id, supplier_group_id,
 *       supplier_catalog_id, supplier_reference_stripped, record_active, deleted_at)},
 *       {@code catalog_article_price(id, catalog_article_id, price_code, brut_price_politics, base_price, brut_price,
 *       brut_price_percentage, sales_price_last_updated, record_active, deleted_at)},
 *       {@code catalog_article_price_detail(id, catalog_article_price_id, code, percentage, price, record_active,
 *       deleted_at)}, {@code catalog_article_description(id, catalog_article_id, language_id, description,
 *       record_active, deleted_at)} en {@code language(id, code)}.</li>
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

    private static final String LEGACY_MASTER = "select m.legacy_master from system_settings m order by m.id limit 1";

    /** Taal van de omschrijving die gepubliceerd wordt ({@code language.code}). */
    static final String DESCRIPTION_LANGUAGE = "NL";

    /** Identiteiten per query (4 parameters per identiteit: ruim onder de driverlimiet). */
    public static final int CATALOG_BATCH_SIZE = 200;

    /** {@code supplier.number}, {@code supplier.name}. */
    public record SupplierRow(String number, String name) {
    }

    /** {@code supplier_group.legacy_code} ({@code lev|grp|korting}), {@code group_name}, {@code discount_code}. */
    public record SupplierGroupRow(String legacyCode, String groupName, String discountCode) {
    }

    /** {@code supplier_catalog.scope_key}, {@code legacy_number}, {@code catalog_name}, {@code status}. */
    public record SupplierCatalogRow(String scopeKey, String legacyNumber, String catalogName, String status) {
    }

    /**
     * De identiteit van een catalogusartikel: leverancier ({@code supplier.number}), groep
     * ({@code supplier_group.legacy_code}, {@code lev|grp|korting}), bibliotheek ({@code supplier_catalog.scope_key}, enkel
     * status ACTIVE) en {@code supplier_reference_stripped}. Letterlijke vergelijking, geen normalisatie.
     */
    public record CatalogArticleIdentity(String supplierNumber, String groupLegacyCode, String scopeKey,
                                         String supplierReferenceStripped) {
        public CatalogArticleIdentity {
            Objects.requireNonNull(supplierNumber, "supplierNumber");
            Objects.requireNonNull(groupLegacyCode, "groupLegacyCode");
            Objects.requireNonNull(scopeKey, "scopeKey");
            Objects.requireNonNull(supplierReferenceStripped, "supplierReferenceStripped");
        }
    }

    /** {@code catalog_article_price_detail}: {@code code} ("1".."5"), {@code percentage}, {@code price} (nullable). */
    public record CatalogArticlePriceDetailRow(String code, Double percentage, Double price, boolean active,
                                               boolean deleted) {
    }

    /** {@code catalog_article_price}; {@code details} = alle detailrijen (de planner controleert de vorm). */
    public record CatalogArticlePriceRow(String priceCode, String brutPricePolitics, BigDecimal basePrice,
                                         Double brutPrice, Double brutPricePercentage,
                                         LocalDateTime salesPriceLastUpdated, boolean active, boolean deleted,
                                         List<CatalogArticlePriceDetailRow> details) {
        public CatalogArticlePriceRow {
            details = List.copyOf(details);
        }
    }

    /** {@code catalog_article_description} in de taal {@value #DESCRIPTION_LANGUAGE}. */
    public record CatalogArticleDescriptionRow(String description, boolean active, boolean deleted) {
    }

    /** Een {@code catalog_article}-rij met haar prijsrijen en NL-omschrijvingen (zonder Prodis-id's). */
    public record CatalogArticleRow(boolean active, boolean deleted, List<CatalogArticlePriceRow> prices,
                                    List<CatalogArticleDescriptionRow> descriptions) {
        public CatalogArticleRow {
            prices = List.copyOf(prices);
            descriptions = List.copyOf(descriptions);
        }
    }

    /**
     * Alles wat de planner over een identiteit moet weten.
     *
     * @param supplierCount het aantal {@code supplier}-rijen met dit nummer (geen unieke constraint: &gt; 1 =
     *                      niet eenduidig)
     * @param articles      alle artikelrijen met deze identiteit, ook inactieve/verwijderde (0 = niet gevonden, &gt; 1 =
     *                      niet eenduidig)
     */
    public record CatalogArticleLookup(CatalogArticleIdentity identity, int supplierCount,
                                       List<CatalogArticleRow> articles) {
        public CatalogArticleLookup {
            articles = List.copyOf(articles);
        }
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

    /**
     * De ruwe waarde van {@code system_settings.legacy_master} van de eerste rij op id; de lijst is leeg als er geen rij
     * is (de service vertaalt dat naar PRODIS, zoals Prodis' {@code LegacyMasterResolver}). Een ontbrekende tabel of
     * kolom is een fout ({@code DataAccessException}), nooit een stille default.
     */
    public List<String> readLegacyMaster() {
        return jdbc().query(LEGACY_MASTER, row -> row.getString(1));
    }

    /**
     * Leest per identiteit de catalogusrij(en) voor de publicatie-parallelrun, in batches van
     * {@value #CATALOG_BATCH_SIZE} identiteiten (vier queries per batch: artikelen, details, prijzen, omschrijvingen).
     * Het resultaat heeft precies één element per verschillende identiteit, in volgorde van de invoer; een niet
     * gevonden identiteit heeft geen artikelen. Prodis-id's worden enkel binnen deze methode gebruikt om te groeperen.
     */
    public List<CatalogArticleLookup> findCatalogArticles(List<CatalogArticleIdentity> identities) {
        List<CatalogArticleIdentity> distinct = new ArrayList<>(new LinkedHashSet<>(identities));
        List<CatalogArticleLookup> result = new ArrayList<>(distinct.size());
        for (int from = 0; from < distinct.size(); from += CATALOG_BATCH_SIZE) {
            result.addAll(readBatch(distinct.subList(from, Math.min(distinct.size(), from + CATALOG_BATCH_SIZE))));
        }
        return result;
    }

    private List<CatalogArticleLookup> readBatch(List<CatalogArticleIdentity> batch) {
        ProdisReferenceJdbc jdbc = jdbc();

        Set<String> numbers = new LinkedHashSet<>();
        batch.forEach(identity -> numbers.add(identity.supplierNumber()));
        Map<String, Integer> supplierCounts = new HashMap<>();
        jdbc.query("select s.number, count(*) from supplier s where s.number in (" + placeholders(numbers.size())
                + ") group by s.number", row -> supplierCounts.put(row.getString(1), row.getInt(2)), numbers.toArray());

        // Eerst de (selectieve) referenties voor de index, daarna per identiteit de exacte combinatie. Geen
        // rij-waarde-IN: dat is niet overal even robuust met parameters (H2) en OR van gelijkheden werkt overal.
        Set<String> references = new LinkedHashSet<>();
        batch.forEach(identity -> references.add(identity.supplierReferenceStripped()));
        Object[] identityArgs = new Object[references.size() + batch.size() * 4];
        int next = 0;
        for (String reference : references) {
            identityArgs[next++] = reference;
        }
        for (CatalogArticleIdentity identity : batch) {
            identityArgs[next++] = identity.supplierNumber();
            identityArgs[next++] = identity.groupLegacyCode();
            identityArgs[next++] = identity.scopeKey();
            identityArgs[next++] = identity.supplierReferenceStripped();
        }
        // Prodis-id's: enkel hier, enkel als sleutel om de kindrijen aan hun artikel te hangen.
        Map<Long, CatalogArticleIdentity> identityByArticle = new LinkedHashMap<>();
        Map<Long, boolean[]> flagsByArticle = new HashMap<>();
        jdbc.query("select a.id, s.number, g.legacy_code, c.scope_key, a.supplier_reference_stripped, a.record_active, "
                + "a.deleted_at from catalog_article a "
                + "join supplier s on s.id = a.supplier_id "
                + "join supplier_group g on g.id = a.supplier_group_id "
                + "join supplier_catalog c on c.id = a.supplier_catalog_id and c.status = 'ACTIVE' "
                + "where a.supplier_reference_stripped in (" + placeholders(references.size()) + ") and ("
                + identityConditions(batch.size()) + ") order by a.id", row -> {
                    long id = row.getLong(1);
                    identityByArticle.put(id, new CatalogArticleIdentity(row.getString(2), row.getString(3),
                            row.getString(4), row.getString(5)));
                    flagsByArticle.put(id, new boolean[] {row.getBoolean(6), row.getTimestamp(7) != null});
                    return null;
                }, identityArgs);

        Map<Long, List<CatalogArticlePriceRow>> pricesByArticle = new HashMap<>();
        Map<Long, List<CatalogArticleDescriptionRow>> descriptionsByArticle = new HashMap<>();
        if (!identityByArticle.isEmpty()) {
            Object[] articleIds = identityByArticle.keySet().toArray();
            String in = placeholders(articleIds.length);

            Map<Long, List<CatalogArticlePriceDetailRow>> details = new HashMap<>();
            jdbc.query("select d.catalog_article_price_id, d.code, d.percentage, d.price, d.record_active, d.deleted_at "
                    + "from catalog_article_price_detail d where d.catalog_article_price_id in (select p.id from "
                    + "catalog_article_price p where p.catalog_article_id in (" + in + ")) order by d.code, d.id",
                    row -> {
                        details.computeIfAbsent(row.getLong(1), key -> new ArrayList<>())
                                .add(new CatalogArticlePriceDetailRow(row.getString(2), nullableDouble(row, 3),
                                        nullableDouble(row, 4), row.getBoolean(5), row.getTimestamp(6) != null));
                        return null;
                    }, articleIds);

            jdbc.query("select p.catalog_article_id, p.id, p.price_code, p.brut_price_politics, p.base_price, "
                    + "p.brut_price, p.brut_price_percentage, p.sales_price_last_updated, p.record_active, p.deleted_at "
                    + "from catalog_article_price p where p.catalog_article_id in (" + in + ") order by p.id", row -> {
                        Timestamp updated = row.getTimestamp(8);
                        pricesByArticle.computeIfAbsent(row.getLong(1), key -> new ArrayList<>())
                                .add(new CatalogArticlePriceRow(row.getString(3), row.getString(4),
                                        row.getBigDecimal(5), nullableDouble(row, 6), nullableDouble(row, 7),
                                        updated == null ? null : updated.toLocalDateTime(), row.getBoolean(9),
                                        row.getTimestamp(10) != null, details.getOrDefault(row.getLong(2), List.of())));
                        return null;
                    }, articleIds);

            Object[] descriptionArgs = new Object[articleIds.length + 1];
            descriptionArgs[0] = DESCRIPTION_LANGUAGE;
            System.arraycopy(articleIds, 0, descriptionArgs, 1, articleIds.length);
            jdbc.query("select x.catalog_article_id, x.description, x.record_active, x.deleted_at "
                    + "from catalog_article_description x join language l on l.id = x.language_id and l.code = ? "
                    + "where x.catalog_article_id in (" + in + ") order by x.id", row -> {
                        descriptionsByArticle.computeIfAbsent(row.getLong(1), key -> new ArrayList<>())
                                .add(new CatalogArticleDescriptionRow(row.getString(2), row.getBoolean(3),
                                        row.getTimestamp(4) != null));
                        return null;
                    }, descriptionArgs);
        }

        Map<CatalogArticleIdentity, List<CatalogArticleRow>> articles = new HashMap<>();
        identityByArticle.forEach((id, identity) -> articles.computeIfAbsent(identity, key -> new ArrayList<>())
                .add(new CatalogArticleRow(flagsByArticle.get(id)[0], flagsByArticle.get(id)[1],
                        pricesByArticle.getOrDefault(id, List.of()), descriptionsByArticle.getOrDefault(id, List.of()))));

        List<CatalogArticleLookup> lookups = new ArrayList<>(batch.size());
        for (CatalogArticleIdentity identity : batch) {
            lookups.add(new CatalogArticleLookup(identity, supplierCounts.getOrDefault(identity.supplierNumber(), 0),
                    articles.getOrDefault(identity, List.of())));
        }
        return lookups;
    }

    private static Double nullableDouble(ResultSet row, int column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static String identityConditions(int count) {
        return String.join(" or ", Collections.nCopies(count, "(s.number = ? and g.legacy_code = ? "
                + "and c.scope_key = ? and a.supplier_reference_stripped = ?)"));
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
