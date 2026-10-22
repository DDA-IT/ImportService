package be.dda.catalogimport.dao;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in ({@code -Dprodis.schemaParity=true}): vergelijkt het testschema in {@code prodis_pubtest}
 * ({@code prodis-publication-test-schema.sql}) met de echte lokale {@code Prodis}-database (publicatie P5a;
 * {@code docs/design/publicatie-prodis-db-direct-design.md} par. 6 "Veilige testopzet"). Zo valt een Prodis-Liquibase-
 * wijziging aan de negen tabellen op vóór de publicatie ertegen schrijft.
 *
 * <h2>Strikt alleen-lezen tegenover Prodis</h2>
 * <ul>
 *   <li>Verbonden als {@code catalog_import} (default; overschrijfbaar met {@code -Dprodis.schemaParity.url/.username/
 *       .password}): die rol heeft geen enkel recht op de Prodis-tabellen, enkel het standaard-CONNECT van PUBLIC.</li>
 *   <li>Sessie {@code default_transaction_read_only=on} en {@code Connection.setReadOnly(true)}; de test controleert
 *       {@code transaction_read_only = on} vóór de eerste query. Enkel SELECT's op {@code pg_catalog}.</li>
 *   <li>Daarom {@code pg_catalog} en niet {@code information_schema.columns}: die toont enkel kolommen waarop de gebruiker
 *       rechten heeft (voor {@code catalog_import} dus niets). Dezelfde query aan beide kanten.</li>
 * </ul>
 * Vergeleken: kolommen (volgorde, type met lengte/precisie, NOT NULL, default), primaire/unieke sleutels, foreign keys
 * binnen de negen tabellen (die naar andere tabellen zijn in de kopie bewust weggelaten), indexen, triggers (geen) en de
 * twee sequences.
 */
@EnabledIfSystemProperty(named = "prodis.schemaParity", matches = "true")
class ProdisSchemaParityTest {

    private static final String TABLES = "'supplier', 'supplier_group', 'supplier_catalog', 'language', 'system_settings', "
            + "'catalog_article', 'catalog_article_price', 'catalog_article_price_detail', 'catalog_article_description'";

    private static final String COLUMNS = "select c.relname || '.' || a.attname || ' #'"
            + " || row_number() over (partition by c.relname order by a.attnum) || ' ' || format_type(a.atttypid, a.atttypmod)"
            + " || case when a.attnotnull then ' not null' else '' end"
            + " || coalesce(' default ' || pg_get_expr(d.adbin, d.adrelid), '')"
            + " from pg_attribute a join pg_class c on c.oid = a.attrelid"
            + " join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'"
            + " left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum"
            + " where c.relkind = 'r' and c.relname in (" + TABLES + ") and a.attnum > 0 and not a.attisdropped";

    private static final String CONSTRAINTS = "select c.relname || ' ' || k.conname || ' ' || pg_get_constraintdef(k.oid)"
            + " from pg_constraint k join pg_class c on c.oid = k.conrelid"
            + " join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'"
            + " left join pg_class f on f.oid = k.confrelid"
            + " where c.relname in (" + TABLES + ") and (k.contype in ('p', 'u', 'c', 'x')"
            + " or (k.contype = 'f' and f.relname in (" + TABLES + ")))";

    private static final String INDEXES = "select tablename || ' ' || indexdef from pg_indexes"
            + " where schemaname = 'public' and tablename in (" + TABLES + ")";

    private static final String TRIGGERS = "select c.relname || ' ' || t.tgname from pg_trigger t"
            + " join pg_class c on c.oid = t.tgrelid where not t.tgisinternal and c.relname in (" + TABLES + ")";

    private static final String SEQUENCES = "select c.relname || ' start ' || s.seqstart || ' increment ' || s.seqincrement"
            + " || ' cache ' || s.seqcache || ' cycle ' || s.seqcycle from pg_sequence s join pg_class c on c.oid = s.seqrelid"
            + " where c.relname in ('sequence_generator', 'sequencegenerator')";

    @Test
    void theTestSchemaIsAnExactCopyOfTheProdisTables() throws SQLException {
        ProdisPublicationTestDatabase.prepare();
        try (Connection prodis = readOnly(System.getProperty("prodis.schemaParity.url",
                "jdbc:postgresql://localhost:5432/Prodis"),
                System.getProperty("prodis.schemaParity.username", "catalog_import"),
                System.getProperty("prodis.schemaParity.password", "catalog_import"));
             Connection copy = readOnly(ProdisPublicationTestDatabase.URL, ProdisPublicationTestDatabase.OWNER,
                     ProdisPublicationTestDatabase.OWNER_PASSWORD)) {
            ProdisPublicationTestDatabase.requireTestDatabase(copy);
            assertThat(lines(prodis, "select current_database()")).as("parity source").containsExactly("Prodis");

            List<String> prodisColumns = lines(prodis, COLUMNS);
            assertThat(prodisColumns).as("Prodis columns (is the parity user able to read pg_catalog?)").hasSizeGreaterThan(100);
            assertThat(lines(copy, COLUMNS)).as("columns").containsExactlyInAnyOrderElementsOf(prodisColumns);
            assertThat(lines(copy, CONSTRAINTS)).as("constraints").containsExactlyInAnyOrderElementsOf(
                    lines(prodis, CONSTRAINTS));
            assertThat(lines(copy, INDEXES)).as("indexes").containsExactlyInAnyOrderElementsOf(lines(prodis, INDEXES));
            assertThat(lines(prodis, TRIGGERS)).as("Prodis triggers").isEmpty();
            assertThat(lines(copy, TRIGGERS)).as("copy triggers").isEmpty();
            assertThat(lines(copy, SEQUENCES)).as("sequences").containsExactlyInAnyOrderElementsOf(lines(prodis, SEQUENCES));
            System.out.println("P5a schema parity: " + prodisColumns.size() + " columns, "
                    + lines(prodis, CONSTRAINTS).size() + " constraints, " + lines(prodis, INDEXES).size()
                    + " indexes identical");
        }
    }

    /** Een alleen-lezen sessie: {@code default_transaction_read_only=on} vanaf het verbinden, gecontroleerd. */
    private static Connection readOnly(String url, String user, String password) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("options", "-c default_transaction_read_only=on");
        properties.setProperty("readOnly", "true");
        properties.setProperty("readOnlyMode", "always");
        properties.setProperty("ApplicationName", "catalogimport-schema-parity (read-only)");
        Connection connection = DriverManager.getConnection(url, properties);
        try {
            connection.setReadOnly(true);
            if (!lines(connection, "show transaction_read_only").equals(List.of("on"))) {
                throw new IllegalStateException("Refusing to run: the parity session is not read-only");
            }
            return connection;
        } catch (SQLException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    private static List<String> lines(Connection connection, String sql) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                lines.add(rows.getString(1));
            }
        }
        return lines;
    }
}
