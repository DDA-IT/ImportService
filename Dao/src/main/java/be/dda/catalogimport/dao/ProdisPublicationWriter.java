package be.dda.catalogimport.dao;

import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleDescriptionRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleIdentity;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleLookup;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceDetailRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticlePriceRow;
import be.dda.catalogimport.dao.ProdisReferenceDao.CatalogArticleRow;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;

/**
 * Schrijft één publicatie-item in de Prodis-PostgreSQL, binnen één eigen Prodis-transactie ({@code docs/design/
 * publicatie-prodis-db-direct-design.md} par. 2.4 stap 2, par. 3 en 4, slice P5b; beslissingen 2026-10-22 P-V2, Q1 = b,
 * P5a). <b>Alle schrijf-SQL naar Prodis staat hier</b>; de beslissing wat er geschreven wordt neemt de aanroeper (de
 * pure planner in de Service-laag) via {@link LockedPlanner}, op de <b>vergrendelde</b> rijen.
 *
 * <h2>Volgorde binnen {@link ProdisPublicationJdbc#inTransaction} (READ COMMITTED)</h2>
 * <ol>
 *   <li>Identiteit opnieuw oplossen <b>zonder slot</b>, met dezelfde regels als de leesbron (P1,
 *       {@link ProdisReferenceDao#findCatalogArticles}): het aantal {@code supplier}-rijen met dit nummer, en alle
 *       {@code catalog_article}-rijen met leverancier + {@code supplier_group.legacy_code} + {@code supplier_catalog.scope_key}
 *       (status ACTIVE) + {@code supplier_reference_stripped}, ook inactieve/verwijderde. Nooit een slot op
 *       {@code catalog_article} (de rol mag dat ook niet: {@code FOR UPDATE} vraagt UPDATE-recht).</li>
 *   <li>Enkel bij precies één leverancier en precies één artikel: sloten in vaste volgorde -
 *       {@code catalog_article_price} {@code FOR UPDATE} op {@code catalog_article_id}, daarna de details
 *       {@code ORDER BY code} {@code FOR UPDATE}, daarna de NL-omschrijving {@code FOR UPDATE OF} de omschrijving (nooit
 *       {@code language}). Wat onder het slot gelezen wordt is de prior; een gelijktijdige wijziging die eerst commit, is
 *       dus zichtbaar (READ COMMITTED leest na het wachten de laatste versie).</li>
 *   <li>{@link LockedPlanner#decide} op die toestand. {@link Decision#write()} {@code null} of leeg (REJECTED, CONFLICT,
 *       UNCHANGED) → {@link ProdisPublicationJdbc.Transaction#setRollbackOnly()}: er wordt niets geschreven en er is ook
 *       geen commit die kan mislukken.</li>
 *   <li>Anders {@code UPDATE ... WHERE id = ?} per rij, enkel met de opgegeven kolommen en exact de opgegeven waarden
 *       ({@link BigDecimal} voor {@code base_price}, {@code double} voor de rest); update-count moet 1 zijn
 *       ({@link ProdisUpdateCountException}, alles teruggedraaid).</li>
 * </ol>
 *
 * <h2>Audit (design par. 4)</h2>
 * Op elke bijgewerkte rij: {@code last_modified_by} = de opgegeven actor (de aanroeper geeft het Keycloak-subject, of
 * {@code preferred_username} zonder subject), {@code last_modified_date = now() at time zone 'UTC'}.
 * {@code sales_price_last_updated = now() at time zone 'UTC'} enkel met {@link WriteSet#touchSalesPriceLastUpdated()}.
 * {@code created_*}, {@code tenant_id}, {@code record_active} en {@code deleted_*} worden nooit aangeraakt. {@code now()} is
 * binnen één transactie constant: elke rij krijgt hetzelfde tijdstip ({@link Outcome#writtenAt()}).
 *
 * <h2>Fouten</h2>
 * Zoals {@link ProdisPublicationJdbc#inTransaction}: {@link ProdisTransactionNotAppliedException} (zeker niets toegepast,
 * met SQLState: lock-time-out 55P03, deadlock 40P01, ...), {@link CommitOutcomeUnknownException} (uitkomst onbekend), en
 * een {@link RuntimeException} uit de planner of {@link ProdisUpdateCountException} na een rollback (niets toegepast).
 * Prodis-id's verlaten deze klasse nooit.
 */
@Repository
public class ProdisPublicationWriter {

    /** {@code last_modified_by} is {@code varchar(255)} in Prodis. */
    public static final int MAX_MODIFIED_BY_LENGTH = 255;

    static final String DESCRIPTION_LANGUAGE = ProdisReferenceDao.DESCRIPTION_LANGUAGE;

    private static final String SUPPLIER_COUNT = "select count(*) from supplier s where s.number = ?";

    private static final String ARTICLES = "select a.id, a.record_active, a.deleted_at from catalog_article a "
            + "join supplier s on s.id = a.supplier_id "
            + "join supplier_group g on g.id = a.supplier_group_id "
            + "join supplier_catalog c on c.id = a.supplier_catalog_id and c.status = 'ACTIVE' "
            + "where s.number = ? and g.legacy_code = ? and c.scope_key = ? and a.supplier_reference_stripped = ? "
            + "order by a.id";

    private static final String LOCK_PRICES = "select p.id, p.price_code, p.brut_price_politics, p.base_price, "
            + "p.brut_price, p.brut_price_percentage, p.sales_price_last_updated, p.record_active, p.deleted_at "
            + "from catalog_article_price p where p.catalog_article_id = ? order by p.id for update";

    private static final String LOCK_DETAILS = "select d.id, d.catalog_article_price_id, d.code, d.percentage, d.price, "
            + "d.record_active, d.deleted_at from catalog_article_price_detail d "
            + "where d.catalog_article_price_id = any (?) order by d.code, d.id for update";

    private static final String LOCK_DESCRIPTIONS = "select x.id, x.description, x.record_active, x.deleted_at "
            + "from catalog_article_description x join language l on l.id = x.language_id and l.code = ? "
            + "where x.catalog_article_id = ? order by x.id for update of x";

    private static final String NOW_UTC = "now() at time zone 'UTC'";

    /** Schrijfbare kolommen van {@code catalog_article_price} (GRANT in {@code prodis-catalogimport-pub-role.sql}). */
    public enum PriceColumn {
        BASE_PRICE("base_price"),
        BRUT_PRICE("brut_price"),
        BRUT_PRICE_PERCENTAGE("brut_price_percentage");

        private final String column;

        PriceColumn(String column) {
            this.column = column;
        }

        public String column() {
            return column;
        }
    }

    /** Schrijfbare kolommen van {@code catalog_article_price_detail}. */
    public enum DetailColumn {
        PERCENTAGE("percentage"),
        PRICE("price");

        private final String column;

        DetailColumn(String column) {
            this.column = column;
        }

        public String column() {
            return column;
        }
    }

    /**
     * Wat er geschreven moet worden: exact deze kolommen met exact deze waarden ({@code null} = de kolom op NULL zetten,
     * enkel als de planner dat zo beslist). Niet opgegeven kolommen blijven ongemoeid.
     */
    public static final class WriteSet {

        private final Map<PriceColumn, Object> priceRow = new EnumMap<>(PriceColumn.class);
        private final Map<String, Map<DetailColumn, Double>> details = new TreeMap<>();
        private boolean touchSalesPriceLastUpdated;
        private boolean writeDescription;
        private String description;

        public WriteSet basePrice(BigDecimal value) {
            priceRow.put(PriceColumn.BASE_PRICE, value);
            return this;
        }

        public WriteSet brutPrice(Double value) {
            priceRow.put(PriceColumn.BRUT_PRICE, value);
            return this;
        }

        public WriteSet brutPricePercentage(Double value) {
            priceRow.put(PriceColumn.BRUT_PRICE_PERCENTAGE, value);
            return this;
        }

        /** @param code de detailcode ("1".."5") */
        public WriteSet detail(String code, DetailColumn column, Double value) {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(column, "column");
            details.computeIfAbsent(code, key -> new EnumMap<>(DetailColumn.class)).put(column, value);
            return this;
        }

        public WriteSet description(String value) {
            Objects.requireNonNull(value, "description");
            writeDescription = true;
            description = value;
            return this;
        }

        /** {@code sales_price_last_updated} schuift mee (enkel bij een echte wijziging van een VKP1..5-prijs). */
        public WriteSet touchSalesPriceLastUpdated() {
            touchSalesPriceLastUpdated = true;
            return this;
        }

        public Map<PriceColumn, Object> priceRow() {
            return Collections.unmodifiableMap(priceRow);
        }

        public Map<String, Map<DetailColumn, Double>> details() {
            return Collections.unmodifiableMap(details);
        }

        public boolean isTouchSalesPriceLastUpdated() {
            return touchSalesPriceLastUpdated;
        }

        public boolean writesDescription() {
            return writeDescription;
        }

        public String getDescription() {
            return description;
        }

        /** {@code true} als er niets te schrijven is. */
        public boolean isEmpty() {
            return priceRow.isEmpty() && details.isEmpty() && !writeDescription && !touchSalesPriceLastUpdated;
        }

        boolean writesPriceRow() {
            return !priceRow.isEmpty() || touchSalesPriceLastUpdated;
        }
    }

    /**
     * Wat de planner op de vergrendelde toestand beslist.
     *
     * @param plan  het plan van de aanroeper, ongewijzigd teruggegeven
     * @param write {@code null} of leeg = niets schrijven (de transactie wordt teruggedraaid)
     */
    public record Decision<P>(P plan, WriteSet write) {
    }

    /** De planner van de aanroeper; draait binnen de Prodis-transactie, na de sloten. */
    @FunctionalInterface
    public interface LockedPlanner<P> {
        Decision<P> decide(CatalogArticleLookup lockedState);
    }

    /**
     * Het resultaat van één schrijfpoging.
     *
     * @param written   {@code true} als er rijen bijgewerkt en vastgelegd zijn
     * @param writtenAt het tijdstip ({@code now() at time zone 'UTC'}) in de audit- en datumkolommen; {@code null} als er
     *                  niets geschreven werd
     */
    public record Outcome<P>(P plan, boolean written, LocalDateTime writtenAt) {
    }

    private final ObjectProvider<ProdisPublicationJdbc> jdbcProvider;

    public ProdisPublicationWriter(ObjectProvider<ProdisPublicationJdbc> jdbcProvider) {
        this.jdbcProvider = jdbcProvider;
    }

    /** {@code true} als er een geconfigureerde schrijfverbinding is. */
    public boolean isConfigured() {
        ProdisPublicationJdbc jdbc = jdbcProvider.getIfAvailable();
        return jdbc != null && jdbc.isConfigured();
    }

    /**
     * Lost de identiteit op, vergrendelt de rijen, laat de planner beslissen en schrijft (zie de klassebeschrijving).
     *
     * @param modifiedBy {@code last_modified_by}: niet leeg, hoogstens {@value #MAX_MODIFIED_BY_LENGTH} tekens
     */
    public <P> Outcome<P> write(CatalogArticleIdentity identity, String modifiedBy, LockedPlanner<P> planner) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(planner, "planner");
        if (modifiedBy == null || modifiedBy.isBlank() || modifiedBy.length() > MAX_MODIFIED_BY_LENGTH) {
            throw new IllegalArgumentException("modifiedBy must be 1.." + MAX_MODIFIED_BY_LENGTH + " characters");
        }
        ProdisPublicationJdbc jdbc = jdbcProvider.getIfAvailable();
        if (jdbc == null || !jdbc.isConfigured()) {
            throw new IllegalStateException("The Prodis publication database is not configured");
        }
        return jdbc.inTransaction(transaction -> {
            Locked locked = readAndLock(transaction, identity);
            Decision<P> decision = planner.decide(locked.lookup());
            if (decision == null) {
                throw new IllegalStateException("The planner returned no decision");
            }
            WriteSet write = decision.write();
            if (write == null || write.isEmpty()) {
                transaction.setRollbackOnly();
                return new Outcome<>(decision.plan(), false, null);
            }
            LocalDateTime writtenAt = apply(transaction, locked, write, modifiedBy);
            return new Outcome<>(decision.plan(), true, writtenAt);
        });
    }

    // --- lezen en vergrendelen ---------------------------------------------------------------------------------

    /** De vergrendelde toestand en, intern, de Prodis-id's om te kunnen schrijven. */
    private record Locked(CatalogArticleLookup lookup, Long priceId, Map<String, Long> detailIds,
                          Long descriptionId) {
    }

    private static Locked readAndLock(ProdisPublicationJdbc.Transaction transaction, CatalogArticleIdentity identity)
            throws SQLException {
        Connection connection = transaction.connection();
        int supplierCount;
        try (PreparedStatement statement = prepare(transaction, SUPPLIER_COUNT)) {
            statement.setString(1, identity.supplierNumber());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                supplierCount = row.getInt(1);
            }
        }
        List<long[]> articleIds = new ArrayList<>();
        List<boolean[]> articleFlags = new ArrayList<>();
        try (PreparedStatement statement = prepare(transaction, ARTICLES)) {
            statement.setString(1, identity.supplierNumber());
            statement.setString(2, identity.groupLegacyCode());
            statement.setString(3, identity.scopeKey());
            statement.setString(4, identity.supplierReferenceStripped());
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    articleIds.add(new long[] {row.getLong(1)});
                    articleFlags.add(new boolean[] {row.getBoolean(2), row.getTimestamp(3) != null});
                }
            }
        }
        if (supplierCount != 1 || articleIds.size() != 1) {
            // Niet eenduidig of niet gevonden: de planner weigert; geen sloten nodig (niets wordt geschreven).
            List<CatalogArticleRow> articles = new ArrayList<>();
            for (boolean[] flags : articleFlags) {
                articles.add(new CatalogArticleRow(flags[0], flags[1], List.of(), List.of()));
            }
            return new Locked(new CatalogArticleLookup(identity, supplierCount, articles), null, Map.of(), null);
        }
        long articleId = articleIds.get(0)[0];

        // 1. Prijsrij(en)
        List<Long> priceIds = new ArrayList<>();
        List<Object[]> priceValues = new ArrayList<>();
        try (PreparedStatement statement = prepare(transaction, LOCK_PRICES)) {
            statement.setLong(1, articleId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    priceIds.add(row.getLong(1));
                    Timestamp updated = row.getTimestamp(7);
                    priceValues.add(new Object[] {row.getString(2), row.getString(3), row.getBigDecimal(4),
                            nullableDouble(row, 5), nullableDouble(row, 6),
                            updated == null ? null : updated.toLocalDateTime(), row.getBoolean(8),
                            row.getTimestamp(9) != null});
                }
            }
        }
        // 2. Details, op code
        Map<Long, List<CatalogArticlePriceDetailRow>> detailsByPrice = new HashMap<>();
        Map<String, Long> detailIds = new HashMap<>();
        if (!priceIds.isEmpty()) {
            try (PreparedStatement statement = prepare(transaction, LOCK_DETAILS)) {
                statement.setArray(1, connection.createArrayOf("bigint", priceIds.toArray()));
                try (ResultSet row = statement.executeQuery()) {
                    while (row.next()) {
                        String code = row.getString(3);
                        detailsByPrice.computeIfAbsent(row.getLong(2), key -> new ArrayList<>())
                                .add(new CatalogArticlePriceDetailRow(code, nullableDouble(row, 4),
                                        nullableDouble(row, 5), row.getBoolean(6), row.getTimestamp(7) != null));
                        // Bij een dubbele code weigert de planner (vorm); de id is dan nooit nodig.
                        detailIds.putIfAbsent(code, row.getLong(1));
                    }
                }
            }
        }
        // 3. NL-omschrijving
        List<CatalogArticleDescriptionRow> descriptions = new ArrayList<>();
        Long descriptionId = null;
        try (PreparedStatement statement = prepare(transaction, LOCK_DESCRIPTIONS)) {
            statement.setString(1, DESCRIPTION_LANGUAGE);
            statement.setLong(2, articleId);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    if (descriptionId == null) {
                        descriptionId = row.getLong(1);
                    }
                    descriptions.add(new CatalogArticleDescriptionRow(row.getString(2), row.getBoolean(3),
                            row.getTimestamp(4) != null));
                }
            }
        }

        List<CatalogArticlePriceRow> prices = new ArrayList<>();
        for (int i = 0; i < priceIds.size(); i++) {
            Object[] value = priceValues.get(i);
            prices.add(new CatalogArticlePriceRow((String) value[0], (String) value[1], (BigDecimal) value[2],
                    (Double) value[3], (Double) value[4], (LocalDateTime) value[5], (Boolean) value[6],
                    (Boolean) value[7], detailsByPrice.getOrDefault(priceIds.get(i), List.of())));
        }
        boolean[] flags = articleFlags.get(0);
        CatalogArticleLookup lookup = new CatalogArticleLookup(identity, supplierCount,
                List.of(new CatalogArticleRow(flags[0], flags[1], prices, descriptions)));
        return new Locked(lookup, priceIds.size() == 1 ? priceIds.get(0) : null, detailIds,
                descriptions.size() == 1 ? descriptionId : null);
    }

    // --- schrijven ---------------------------------------------------------------------------------------------

    private static LocalDateTime apply(ProdisPublicationJdbc.Transaction transaction, Locked locked, WriteSet write,
                                       String modifiedBy) throws SQLException {
        LocalDateTime writtenAt;
        try (PreparedStatement statement = prepare(transaction, "select " + NOW_UTC)) {
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                writtenAt = row.getTimestamp(1).toLocalDateTime();
            }
        }
        if (write.writesPriceRow()) {
            Long priceId = requireId(locked.priceId(), "catalog_article_price");
            StringBuilder sql = new StringBuilder("update catalog_article_price set ");
            List<PriceColumn> columns = new ArrayList<>(write.priceRow().keySet());
            for (PriceColumn column : columns) {
                sql.append(column.column()).append(" = ?, ");
            }
            if (write.isTouchSalesPriceLastUpdated()) {
                sql.append("sales_price_last_updated = ").append(NOW_UTC).append(", ");
            }
            sql.append("last_modified_by = ?, last_modified_date = ").append(NOW_UTC).append(" where id = ?");
            try (PreparedStatement statement = prepare(transaction, sql.toString())) {
                int index = 1;
                for (PriceColumn column : columns) {
                    Object value = write.priceRow().get(column);
                    if (column == PriceColumn.BASE_PRICE) {
                        if (value == null) {
                            statement.setNull(index++, Types.NUMERIC);
                        } else {
                            statement.setBigDecimal(index++, (BigDecimal) value);
                        }
                    } else {
                        setDouble(statement, index++, (Double) value);
                    }
                }
                statement.setString(index++, modifiedBy);
                statement.setLong(index, priceId);
                requireOneRow(statement.executeUpdate(), "catalog_article_price");
            }
        }
        for (Map.Entry<String, Map<DetailColumn, Double>> detail : write.details().entrySet()) {
            Long detailId = requireId(locked.detailIds().get(detail.getKey()),
                    "catalog_article_price_detail code " + detail.getKey());
            StringBuilder sql = new StringBuilder("update catalog_article_price_detail set ");
            List<DetailColumn> columns = new ArrayList<>(detail.getValue().keySet());
            for (DetailColumn column : columns) {
                sql.append(column.column()).append(" = ?, ");
            }
            sql.append("last_modified_by = ?, last_modified_date = ").append(NOW_UTC).append(" where id = ?");
            try (PreparedStatement statement = prepare(transaction, sql.toString())) {
                int index = 1;
                for (DetailColumn column : columns) {
                    setDouble(statement, index++, detail.getValue().get(column));
                }
                statement.setString(index++, modifiedBy);
                statement.setLong(index, detailId);
                requireOneRow(statement.executeUpdate(), "catalog_article_price_detail code " + detail.getKey());
            }
        }
        if (write.writesDescription()) {
            Long descriptionId = requireId(locked.descriptionId(), "catalog_article_description");
            try (PreparedStatement statement = prepare(transaction, "update catalog_article_description set "
                    + "description = ?, last_modified_by = ?, last_modified_date = " + NOW_UTC + " where id = ?")) {
                statement.setString(1, write.getDescription());
                statement.setString(2, modifiedBy);
                statement.setLong(3, descriptionId);
                requireOneRow(statement.executeUpdate(), "catalog_article_description");
            }
        }
        return writtenAt;
    }

    private static PreparedStatement prepare(ProdisPublicationJdbc.Transaction transaction, String sql)
            throws SQLException {
        PreparedStatement statement = transaction.connection().prepareStatement(sql);
        statement.setQueryTimeout(transaction.queryTimeoutSeconds());
        return statement;
    }

    private static void setDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.DOUBLE);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static Long requireId(Long id, String what) {
        if (id == null) {
            // De planner mag enkel schrijven op een ondersteunde vorm (precies één rij); anders is dit een programmeerfout.
            throw new ProdisUpdateCountException("no single locked row for " + what + " to update");
        }
        return id;
    }

    private static void requireOneRow(int count, String what) {
        if (count != 1) {
            throw new ProdisUpdateCountException("updating " + what + " affected " + count + " rows instead of 1");
        }
    }

    private static Double nullableDouble(ResultSet row, int column) throws SQLException {
        double value = row.getDouble(column);
        return row.wasNull() ? null : value;
    }
}
