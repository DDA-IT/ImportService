package be.dda.catalogimport.dao;

import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.ARTICLE_ID;
import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.DESCRIPTION_ID;
import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.DETAIL_ID_VKP1;
import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.PRICE_ID;
import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.ownerValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * De rechten van de schrijfrol zoals het productierolscript ze toekent ({@code docs/ops/prodis-catalogimport-pub-role.sql};
 * {@code docs/design/publicatie-prodis-db-direct-design.md} par. 2.3, acceptatie P5a "niet-gegrante actie → 42501"),
 * toegepast op de testrol {@code catalogimport_pub_test} in {@code prodis_pubtest} (nooit in {@code Prodis}).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>v1 schrijft enkel met UPDATE op de opgesomde kolommen: elke INSERT, DELETE, TRUNCATE, sequence-gebruik, UPDATE op
 *       een andere kolom of tabel en elke SELECT op een niet-gegrante kolom geeft 42501 - en verandert niets.</li>
 *   <li>De gegrante SELECT's en UPDATE's werken, ook via de applicatiepool ({@link ProdisPublicationJdbc}).</li>
 *   <li>{@code FOR UPDATE} werkt op prijs, VKP-details en omschrijving (de sloten uit design par. 2.4), niet op
 *       {@code catalog_article}: de identiteit wordt zonder slot opgelost.</li>
 *   <li>{@code lock_timeout}, {@code statement_timeout} en {@code idle_in_transaction_session_timeout} staan op de rol en
 *       zijn actief in elke sessie (een echte lockwachttijd stopt na 5 s met 55P03).</li>
 *   <li>De rolattributen van de testrol zijn die van {@code CREATE ROLE} in het rolscript (geen drift met de setup).</li>
 * </ul>
 */
class ProdisPublicationGrantTest {

    private static final String DENIED = "42501";

    private static ProdisPublicationTestDatabase database;
    private static ProdisPublicationJdbc jdbc;

    @BeforeAll
    static void prepare() {
        database = ProdisPublicationTestDatabase.prepare();
        jdbc = ProdisPublicationTestDatabase.publicationJdbc(Duration.ofSeconds(30), 2);
    }

    @AfterAll
    static void close() throws Exception {
        if (jdbc != null) {
            jdbc.close();
        }
    }

    @BeforeEach
    void seed() {
        database.seedArticle();
    }

    @Test
    void theRoleAttributesAreThoseOfTheRoleScript() throws SQLException {
        assertThat(database.roleScript().createRole())
                .isEqualTo("CREATE ROLE \"catalogimport_pub_test\" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE "
                        + "CONNECTION LIMIT 4");
        assertThat(database.verifyRoleAttributes()).isEmpty();
        assertThat(database.roleScript().roleSettings()).hasSize(3);
        assertThat(database.roleScript().grants()).hasSize(11);
    }

    /**
     * De guard: elke andere database wordt geweigerd - op de URL vóór het verbinden (de echte {@code Prodis} wordt dus
     * niet eens benaderd) en op {@code current_database()} na het verbinden (hier bewezen met de eigen lokale
     * {@code catalog_import}-database, enkel een SELECT).
     */
    @Test
    void theGuardRefusesEveryOtherDatabase() throws SQLException {
        for (String url : List.of("jdbc:postgresql://localhost:5432/Prodis", "jdbc:postgresql://localhost:5432/prodis",
                "jdbc:postgresql://localhost:5432/prodis_pubtest_other", "jdbc:postgresql://localhost:5432/catalog_import",
                "jdbc:postgresql://localhost:5432/Prodis?currentSchema=prodis_pubtest")) {
            assertThatThrownBy(() -> ProdisPublicationTestDatabase.requireTestDatabaseUrl(url)).as(url)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Refusing to run");
        }
        ProdisPublicationTestDatabase.requireTestDatabaseUrl("jdbc:postgresql://localhost:5432/prodis_pubtest?ssl=false");
        try (Connection other = java.sql.DriverManager.getConnection("jdbc:postgresql://localhost:5432/catalog_import",
                ProdisPublicationTestDatabase.OWNER, ProdisPublicationTestDatabase.OWNER_PASSWORD)) {
            assertThatThrownBy(() -> ProdisPublicationTestDatabase.requireTestDatabase(other))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("connected to database 'catalog_import'");
        }
        try (Connection role = ProdisPublicationTestDatabase.role()) {
            ProdisPublicationTestDatabase.requireTestDatabase(role);
        }
    }

    @Test
    void theRoleTimeoutsAreActiveInEverySession() throws SQLException {
        Map<String, String> expected = Map.of("lock_timeout", "5000", "statement_timeout", "30000",
                "idle_in_transaction_session_timeout", "60000");
        try (Connection role = ProdisPublicationTestDatabase.role()) {
            assertThat(settings(role)).isEqualTo(expected);
        }
        // Ook in de applicatiepool.
        Map<String, String> viaPool = jdbc.inTransaction(transaction -> settings(transaction.connection()));
        assertThat(viaPool).isEqualTo(expected);
    }

    @Test
    void aRealLockWaitStopsAfterTheRoleLockTimeout() throws Exception {
        try (Connection owner = ProdisPublicationTestDatabase.owner(); Connection role = ProdisPublicationTestDatabase.role()) {
            owner.setAutoCommit(false);
            execute(owner, "select id from catalog_article_price where id = " + PRICE_ID + " for update");
            long start = System.nanoTime();
            assertSqlState(role, "select id from catalog_article_price where id = " + PRICE_ID + " for update", "55P03");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(4_500),
                    Duration.ofSeconds(15));
            owner.rollback();
        }
    }

    @Test
    void theGrantedColumnsCanBeRead() throws SQLException {
        try (Connection role = ProdisPublicationTestDatabase.role()) {
            assertThat(rows(role, "select id, number from supplier")).isEqualTo(1);
            assertThat(rows(role, "select id, supplier_id, legacy_code from supplier_group")).isEqualTo(1);
            assertThat(rows(role, "select id, supplier_id, scope_key, status from supplier_catalog")).isEqualTo(1);
            assertThat(rows(role, "select id, code from language")).isEqualTo(1);
            assertThat(rows(role, "select id, legacy_master from system_settings")).isEqualTo(1);
            assertThat(rows(role, "select id, supplier_id, supplier_group_id, supplier_catalog_id, "
                    + "supplier_reference_stripped, record_active, deleted_at from catalog_article")).isEqualTo(1);
            assertThat(rows(role, "select id, catalog_article_id, price_code, brut_price_politics, base_price, brut_price, "
                    + "brut_price_percentage, sales_price_last_updated, record_active, deleted_at "
                    + "from catalog_article_price")).isEqualTo(1);
            assertThat(rows(role, "select id, catalog_article_price_id, code, percentage, price, record_active, deleted_at "
                    + "from catalog_article_price_detail")).isEqualTo(5);
            assertThat(rows(role, "select id, catalog_article_id, language_id, description, record_active, deleted_at "
                    + "from catalog_article_description")).isEqualTo(1);
        }
    }

    @Test
    void theNonGrantedColumnsCannotBeRead() throws SQLException {
        try (Connection role = ProdisPublicationTestDatabase.role()) {
            for (String sql : List.of("select * from supplier", "select name from supplier",
                    "select group_name from supplier_group", "select catalog_name from supplier_catalog",
                    "select txt from language", "select tenant_id from system_settings",
                    "select * from catalog_article", "select supplier_reference from catalog_article",
                    "select vat_id from catalog_article", "select purchase_price from catalog_article_price",
                    "select tenant_id from catalog_article_price", "select created_by from catalog_article_price_detail",
                    "select default_language from catalog_article_description")) {
                assertSqlState(role, sql, DENIED);
            }
        }
    }

    @Test
    void theGrantedUpdatesWork() {
        Timestamp now = Timestamp.valueOf("2026-10-22 12:00:00");
        int[] counts = jdbc.inTransaction(transaction -> new int[] {
            update(transaction.connection(), "update catalog_article_price set base_price = ?, brut_price = ?, "
                    + "brut_price_percentage = ?, sales_price_last_updated = ?, last_modified_by = ?, "
                    + "last_modified_date = ? where id = ?", new BigDecimal("101.500000"), 130.0, 0.0, now, "subject-1",
                    now, PRICE_ID),
            update(transaction.connection(), "update catalog_article_price_detail set percentage = ?, price = ?, "
                    + "last_modified_by = ?, last_modified_date = ? where id = ?", 111.0, 112.67, "subject-1", now,
                    DETAIL_ID_VKP1),
            update(transaction.connection(), "update catalog_article_description set description = ?, "
                    + "last_modified_by = ?, last_modified_date = ? where id = ?", "Nieuwe omschrijving", "subject-1", now,
                    DESCRIPTION_ID)});
        assertThat(counts).containsExactly(1, 1, 1);
        assertThat(ownerValue("select base_price from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo(new BigDecimal("101.500000"));
        assertThat(ownerValue("select price from catalog_article_price_detail where id = " + DETAIL_ID_VKP1))
                .isEqualTo(112.67);
        assertThat(ownerValue("select description from catalog_article_description where id = " + DESCRIPTION_ID))
                .isEqualTo("Nieuwe omschrijving");
        assertThat(ownerValue("select last_modified_by from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo("subject-1");
    }

    @Test
    void anUpdateOfANonGrantedColumnOrTableIsDenied() throws SQLException {
        try (Connection role = ProdisPublicationTestDatabase.role()) {
            Map<String, String> denied = new LinkedHashMap<>();
            // Wel UPDATE op de tabel, maar niet op deze kolommen ("= default": geen leesrecht nodig, de rechtencontrole
            // gebeurt vóór elke rij of constraint).
            for (String column : List.of("purchase_price", "purchase_percentage", "price_code", "brut_price_politics",
                    "catalog_article_id", "record_active", "deleted_at", "deleted_by", "tenant_id", "created_by",
                    "created_date")) {
                denied.put("catalog_article_price." + column, "update catalog_article_price set " + column
                        + " = default where id = " + PRICE_ID);
            }
            for (String column : List.of("code", "catalog_article_price_id", "record_active", "tenant_id")) {
                denied.put("catalog_article_price_detail." + column, "update catalog_article_price_detail set " + column
                        + " = default where id = " + DETAIL_ID_VKP1);
            }
            for (String column : List.of("language_id", "default_language", "catalog_article_id", "record_active")) {
                denied.put("catalog_article_description." + column, "update catalog_article_description set " + column
                        + " = default where id = " + DESCRIPTION_ID);
            }
            // Geen UPDATE op de tabel.
            denied.put("catalog_article", "update catalog_article set supplier_reference_stripped = 'X' where id = "
                    + ARTICLE_ID);
            denied.put("supplier", "update supplier set number = 'X' where id = 1");
            denied.put("supplier_group", "update supplier_group set legacy_code = 'X' where id = 2");
            denied.put("supplier_catalog", "update supplier_catalog set status = 'X' where id = 3");
            denied.put("language", "update language set code = 'X' where id = 4");
            denied.put("system_settings", "update system_settings set legacy_master = 'PRODIS' where id = 1");
            assertAllDenied(role, denied);
        }
        assertSeedUnchanged();
    }

    @Test
    void insertDeleteAndTruncateAreDeniedOnEveryTable() throws SQLException {
        Map<String, String> denied = new LinkedHashMap<>();
        denied.put("insert price", "insert into catalog_article_price (id, catalog_article_id) values (900, " + ARTICLE_ID
                + ")");
        denied.put("insert detail", "insert into catalog_article_price_detail (id, code, catalog_article_price_id) "
                + "values (901, '6', " + PRICE_ID + ")");
        denied.put("insert description", "insert into catalog_article_description (id, language_id, description, "
                + "catalog_article_id) values (902, 4, 'x', " + ARTICLE_ID + ")");
        denied.put("insert article", "insert into catalog_article (id, supplier_id, supplier_reference, "
                + "supplier_reference_stripped, supplier_catalog_id, supplier_group_id) values (903, 1, 'N', 'N', 3, 2)");
        denied.put("insert supplier", "insert into supplier (id, name_alfa, name) values (904, 'X', 'X')");
        for (String table : List.of("catalog_article_description", "catalog_article_price_detail", "catalog_article_price",
                "catalog_article", "system_settings", "language", "supplier_catalog", "supplier_group", "supplier")) {
            denied.put("delete " + table, "delete from " + table + " where id = id");
            denied.put("truncate " + table, "truncate " + table + " cascade");
        }
        try (Connection role = ProdisPublicationTestDatabase.role()) {
            assertAllDenied(role, denied);
        }
        assertSeedUnchanged();
    }

    @Test
    void sequencesAndSchemaChangesAreDenied() throws SQLException {
        try (Connection role = ProdisPublicationTestDatabase.role()) {
            assertAllDenied(role, Map.of(
                    "nextval sequence_generator", "select nextval('sequence_generator')",
                    "nextval sequencegenerator", "select nextval('sequencegenerator')",
                    "setval sequence_generator", "select setval('sequence_generator', 5000)",
                    "create table", "create table p5a_should_not_exist (id bigint)",
                    "alter table", "alter table catalog_article_price add column p5a bigint"));
        }
        assertThat(ownerValue("select last_value from sequence_generator")).isEqualTo(1050L);
        assertThat(ownerValue("select count(*) from information_schema.tables where table_name = 'p5a_should_not_exist'"))
                .isEqualTo(0L);
    }

    @Test
    void rowLocksWorkOnPriceDetailsAndDescriptionButNotOnTheArticle() throws SQLException {
        // De slotvolgorde van design par. 2.4: prijsrij, details ORDER BY code, NL-omschrijving.
        int locked = jdbc.inTransaction(transaction -> {
            Connection connection = transaction.connection();
            int rows = rows(connection, "select id from catalog_article_price where catalog_article_id = " + ARTICLE_ID
                    + " for update");
            rows += rows(connection, "select id, code from catalog_article_price_detail where catalog_article_price_id = "
                    + PRICE_ID + " order by code for update");
            rows += rows(connection, "select id from catalog_article_description where catalog_article_id = "
                    + ARTICLE_ID + " and language_id = 4 for update");
            transaction.setRollbackOnly();
            return rows;
        });
        assertThat(locked).isEqualTo(7);
        try (Connection role = ProdisPublicationTestDatabase.role()) {
            assertAllDenied(role, Map.of(
                    "lock catalog_article", "select id from catalog_article where id = " + ARTICLE_ID + " for update",
                    "lock supplier", "select id from supplier for update",
                    "share-lock catalog_article", "select id from catalog_article for share"));
        }
    }

    @Test
    void aDeniedActionThroughTheApplicationPoolIsNotAppliedWith42501() {
        assertThatThrownBy(() -> jdbc.inTransaction(transaction -> {
            update(transaction.connection(), "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID);
            return update(transaction.connection(), "insert into catalog_article_price (id, catalog_article_id) "
                    + "values (?, ?)", 905L, ARTICLE_ID);
        })).isInstanceOfSatisfying(ProdisTransactionNotAppliedException.class,
                failure -> assertThat(failure.getSqlState()).isEqualTo(DENIED));
        assertSeedUnchanged();
    }

    private static void assertAllDenied(Connection role, Map<String, String> statements) {
        List<String> notDenied = new ArrayList<>();
        statements.forEach((label, sql) -> {
            String state = sqlState(role, sql);
            if (!DENIED.equals(state)) {
                notDenied.add(label + " -> " + state);
            }
        });
        assertThat(notDenied).as("statements that were not denied with 42501").isEmpty();
    }

    private static void assertSqlState(Connection role, String sql, String expected) {
        assertThat(sqlState(role, sql)).as(sql).isEqualTo(expected);
    }

    /** Voert de opdracht uit als de rol (autocommit) en geeft de SQLState terug; {@code "OK"} als ze slaagde. */
    private static String sqlState(Connection role, String sql) {
        try (Statement statement = role.createStatement()) {
            statement.execute(sql);
            return "OK";
        } catch (SQLException failure) {
            return failure.getSQLState();
        }
    }

    private static void assertSeedUnchanged() {
        assertThat(ownerValue("select count(*) from catalog_article_price")).isEqualTo(1L);
        assertThat(ownerValue("select count(*) from catalog_article_price_detail")).isEqualTo(5L);
        assertThat(ownerValue("select count(*) from catalog_article_description")).isEqualTo(1L);
        assertThat(ownerValue("select count(*) from catalog_article")).isEqualTo(1L);
        assertThat(ownerValue("select base_price from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo(new BigDecimal("100.000000"));
        assertThat(ownerValue("select purchase_price from catalog_article_price where id = " + PRICE_ID)).isEqualTo(55.5);
        assertThat(ownerValue("select legacy_master from system_settings")).isEqualTo("NONE");
    }

    private static Map<String, String> settings(Connection connection) throws SQLException {
        Map<String, String> settings = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select name, setting from pg_settings where name in "
                     + "('lock_timeout', 'statement_timeout', 'idle_in_transaction_session_timeout')")) {
            while (rows.next()) {
                settings.put(rows.getString(1), rows.getString(2));
            }
        }
        return settings;
    }

    private static int rows(Connection connection, String sql) throws SQLException {
        int count = 0;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                count++;
            }
        }
        return count;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int update(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement.executeUpdate();
        }
    }
}
