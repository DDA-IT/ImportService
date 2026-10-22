package be.dda.catalogimport.testsupport;

import be.dda.catalogimport.config.ProdisPublicationDatabaseConfiguration;
import be.dda.catalogimport.config.ProdisPublicationDatabaseProperties;
import be.dda.catalogimport.dao.ProdisPublicationJdbc;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * De lokale PostgreSQL-testdatabase {@value #DATABASE} voor de schrijfverbinding naar Prodis (slice P5a;
 * {@code docs/design/publicatie-prodis-db-direct-design.md} par. 6 "Veilige testopzet"; beslissingslog 2026-10-22 "Lokale
 * testdatabase voor de publicatie"). H2 volstaat hier niet: rechten (42501), rijsloten, rolinstellingen en
 * commit-fouten zijn PostgreSQL-gedrag.
 *
 * <h2>Opbouw</h2>
 * <ol>
 *   <li>Eenmalig, als superuser: {@code scripts/test/setup-prodis-pubtest.sql} (database, eigenaar {@value #OWNER}, rol
 *       {@value #ROLE}, pg_trgm).</li>
 *   <li>{@link #prepare()}: als eigenaar het schema {@code prodis-publication-test-schema.sql} opnieuw opbouwen; daarna
 *       <b>hetzelfde rolscript als productie</b> ({@code docs/ops/prodis-catalogimport-pub-role.sql}) toepassen op de
 *       testrol: de GRANT-opdrachten als eigenaar, de {@code ALTER ROLE ... SET}-opdrachten als de rol zelf (PostgreSQL
 *       laat een rol haar eigen sessiedefaults zetten). {@code CREATE ROLE} deed de setup; {@link #verifyRoleAttributes()}
 *       vergelijkt de attributen.</li>
 *   <li>De applicatie verbindt als {@value #ROLE} via de echte configuratie ({@link #publicationJdbc}).</li>
 * </ol>
 *
 * <h2>Guard</h2>
 * Elke verbinding (eigenaar, rol, applicatiepool) controleert {@code current_database() = 'prodis_pubtest'}; anders faalt
 * de test meteen, vóór er iets uitgevoerd wordt. De URL moet al vóór het verbinden op {@code /prodis_pubtest} eindigen, en
 * het schemascript weigert zelf in elke andere database. De echte database {@code Prodis} wordt nooit beschreven.
 */
public final class ProdisPublicationTestDatabase {

    public static final String DATABASE = "prodis_pubtest";
    public static final String URL = System.getProperty("prodis.pubtest.url",
            "jdbc:postgresql://localhost:5432/" + DATABASE);
    public static final String OWNER = "catalog_import";
    /** Het lokale wachtwoord van de bestaande ontwikkelrol (zoals de andere PostgreSQL-tests). */
    public static final String OWNER_PASSWORD = "catalog_import";
    public static final String ROLE = "catalogimport_pub_test";
    /** Enkel lokaal (setup-prodis-pubtest.sql); geen echt secret. */
    public static final String ROLE_PASSWORD = "catalogimport_pub_test";

    public static final String ROLE_SCRIPT = "docs/ops/prodis-catalogimport-pub-role.sql";
    private static final String SCHEMA_RESOURCE = "/prodis-publication-test-schema.sql";
    private static final List<String> TABLES = List.of("catalog_article_description", "catalog_article_price_detail",
            "catalog_article_price", "catalog_article", "system_settings", "language", "supplier_catalog",
            "supplier_group", "supplier");

    /** Vaste id's van {@link #seedArticle()}. */
    public static final long SUPPLIER_ID = 1;
    public static final long GROUP_ID = 2;
    public static final long CATALOG_ID = 3;
    public static final long LANGUAGE_NL_ID = 4;
    public static final long ARTICLE_ID = 10;
    public static final long PRICE_ID = 20;
    public static final long DETAIL_ID_VKP1 = 31;
    public static final long DESCRIPTION_ID = 40;

    private final RoleScript roleScript;

    private ProdisPublicationTestDatabase(RoleScript roleScript) {
        this.roleScript = roleScript;
    }

    /**
     * Bouwt het schema opnieuw op en past het rolscript toe op de testrol. Faalt meteen met een duidelijke boodschap als
     * de setup ontbreekt of de verbinding naar een andere database wijst.
     */
    public static ProdisPublicationTestDatabase prepare() {
        requireTestDatabaseUrl(URL);
        RoleScript script = RoleScript.parse(read(locateRoleScript()));
        try (Connection owner = owner()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute(readSchema());
                for (String grant : script.grants()) {
                    statement.execute(grant);
                }
            }
            owner.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not build " + DATABASE + " (run scripts/test/setup-prodis-pubtest.sql "
                    + "once as superuser in prodis-postgresql-1)", failure);
        }
        try (Connection role = role(); Statement statement = role.createStatement()) {
            statement.execute("alter role " + ROLE + " reset all");
            for (String setting : script.roleSettings()) {
                statement.execute(setting);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not apply the role settings to " + ROLE, failure);
        }
        return new ProdisPublicationTestDatabase(script);
    }

    public RoleScript roleScript() {
        return roleScript;
    }

    /** Een verbinding als eigenaar ({@value #OWNER}, autocommit), na de guard. */
    public static Connection owner() throws SQLException {
        return connect(OWNER, OWNER_PASSWORD);
    }

    /** Een verbinding als de testrol ({@value #ROLE}, autocommit), na de guard. */
    public static Connection role() throws SQLException {
        return connect(ROLE, ROLE_PASSWORD);
    }

    /**
     * De echte applicatieconfiguratie ({@link ProdisPublicationDatabaseConfiguration}) als {@value #ROLE}; de guard
     * draait meteen als eerste transactie op de pool.
     */
    public static ProdisPublicationJdbc publicationJdbc(Duration queryTimeout, int maximumPoolSize) {
        requireTestDatabaseUrl(URL);
        ProdisPublicationDatabaseProperties properties = new ProdisPublicationDatabaseProperties();
        properties.setUrl(URL);
        properties.setUsername(ROLE);
        properties.setPassword(ROLE_PASSWORD);
        properties.setQueryTimeout(queryTimeout);
        properties.setMaximumPoolSize(maximumPoolSize);
        ProdisPublicationJdbc jdbc = new ProdisPublicationDatabaseConfiguration().prodisPublicationJdbc(properties);
        try {
            jdbc.inTransaction(transaction -> {
                requireTestDatabase(transaction.connection());
                return null;
            });
        } catch (RuntimeException failure) {
            closeQuietly(jdbc);
            throw failure;
        }
        return jdbc;
    }

    /** Leegt de negen tabellen en zet één volledig artikel klaar (prijsrij, VKP1..5, NL-omschrijving). */
    public void seedArticle() {
        try (Connection owner = owner(); Statement statement = owner.createStatement()) {
            statement.execute("truncate " + String.join(", ", TABLES) + " cascade");
            statement.execute("insert into supplier (id, number, name_alfa, name) values (" + SUPPLIER_ID
                    + ", '50001', 'PUBTEST', 'Pubtest supplier')");
            statement.execute("insert into supplier_group (id, supplier_id, group_name, legacy_code) values (" + GROUP_ID
                    + ", " + SUPPLIER_ID + ", 'G', '50001|G|')");
            statement.execute("insert into supplier_catalog (id, catalog_name, scope_key, imported_at, status, supplier_id) "
                    + "values (" + CATALOG_ID + ", 'Catalog', 'SCOPE', timestamp '2026-10-01 00:00:00', 'ACTIVE', "
                    + SUPPLIER_ID + ")");
            statement.execute("insert into language (id, code, txt) values (" + LANGUAGE_NL_ID + ", 'NL', 'Nederlands')");
            statement.execute("insert into system_settings (id, legacy_master) values (1, 'NONE')");
            statement.execute("insert into catalog_article (id, supplier_id, supplier_reference, "
                    + "supplier_reference_stripped, supplier_catalog_id, supplier_group_id) values (" + ARTICLE_ID + ", "
                    + SUPPLIER_ID + ", 'AB-1', 'AB1', " + CATALOG_ID + ", " + GROUP_ID + ")");
            statement.execute("insert into catalog_article_price (id, base_price, catalog_article_id, price_code, "
                    + "brut_price_politics, purchase_price, brut_price, brut_price_percentage, sales_price_last_updated) "
                    + "values (" + PRICE_ID + ", 100.000000, " + ARTICLE_ID + ", '0', '1', 55.5, 120.0, 0, "
                    + "timestamp '2026-02-03 04:05:06')");
            for (int code = 1; code <= 5; code++) {
                statement.execute("insert into catalog_article_price_detail (id, code, percentage, price, "
                        + "catalog_article_price_id) values (" + (DETAIL_ID_VKP1 + code - 1) + ", '" + code + "', "
                        + (100 + code * 10) + ", " + (100 + code * 10) + ", " + PRICE_ID + ")");
            }
            statement.execute("insert into catalog_article_description (id, language_id, description, default_language, "
                    + "catalog_article_id) values (" + DESCRIPTION_ID + ", " + LANGUAGE_NL_ID + ", 'Omschrijving', true, "
                    + ARTICLE_ID + ")");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Leest één waarde als eigenaar (bv. om na een rollback te bewijzen dat er niets veranderde). */
    public static Object ownerValue(String sql) {
        try (Connection owner = owner(); Statement statement = owner.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            if (!row.next()) {
                throw new IllegalStateException("No row for " + sql);
            }
            return row.getObject(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Vergelijkt de attributen van {@value #ROLE} met {@code CREATE ROLE} uit het rolscript (de setup maakt de rol aan; dit
     * vangt drift tussen beide). Geeft de verschillen terug; leeg = gelijk.
     */
    public List<String> verifyRoleAttributes() throws SQLException {
        Map<String, Object> expected = roleScript.expectedRoleAttributes();
        List<String> differences = new ArrayList<>();
        try (Connection owner = owner(); Statement statement = owner.createStatement();
             ResultSet row = statement.executeQuery("select rolcanlogin, rolinherit, rolsuper, rolcreatedb, "
                     + "rolcreaterole, rolreplication, rolbypassrls, rolconnlimit from pg_roles where rolname = '"
                     + ROLE + "'")) {
            if (!row.next()) {
                return List.of("role " + ROLE + " does not exist (run scripts/test/setup-prodis-pubtest.sql)");
            }
            for (Map.Entry<String, Object> attribute : expected.entrySet()) {
                Object actual = row.getObject(attribute.getKey());
                if (!Objects.equals(attribute.getValue(), actual)) {
                    differences.add(attribute.getKey() + ": expected " + attribute.getValue() + " but was " + actual);
                }
            }
        }
        return differences;
    }

    /** Faalt meteen als de verbinding niet in {@value #DATABASE} zit. */
    public static void requireTestDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("select current_database()")) {
            row.next();
            String database = row.getString(1);
            if (!DATABASE.equals(database)) {
                throw new IllegalStateException("Refusing to run: connected to database '" + database + "', only '"
                        + DATABASE + "' is allowed for the Prodis publication tests");
            }
        }
    }

    /** Faalt meteen (vóór elke verbinding) als de URL niet naar {@value #DATABASE} wijst. */
    public static void requireTestDatabaseUrl(String url) {
        String withoutParameters = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        if (!withoutParameters.endsWith("/" + DATABASE)) {
            throw new IllegalStateException("Refusing to run: the Prodis publication tests only connect to '" + DATABASE
                    + "'");
        }
    }

    private static Connection connect(String user, String password) throws SQLException {
        requireTestDatabaseUrl(URL);
        Connection connection = DriverManager.getConnection(URL, user, password);
        try {
            requireTestDatabase(connection);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    private static String readSchema() {
        try (var in = ProdisPublicationTestDatabase.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            return new String(Objects.requireNonNull(in, SCHEMA_RESOURCE).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Zoekt het rolscript vanaf de werkmap (Web/ bij Maven, de projectroot in de IDE) naar boven. */
    public static Path locateRoleScript() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(ROLE_SCRIPT);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(ROLE_SCRIPT + " not found above " + Path.of("").toAbsolutePath());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // enkel opruimen
        }
    }

    /**
     * Het productierolscript, met de psql-variabelen ingevuld voor de testrol en de testdatabase. Elke opdracht is
     * {@code CREATE ROLE}, {@code ALTER ROLE ... SET} of {@code GRANT}; iets anders laat de test falen (een wijziging van
     * het script valt zo op).
     */
    public record RoleScript(String createRole, List<String> roleSettings, List<String> grants) {

        static RoleScript parse(String script) {
            StringBuilder sql = new StringBuilder();
            for (String line : script.split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("\\")) {
                    continue;
                }
                sql.append(trimmed).append('\n');
            }
            String createRole = null;
            List<String> settings = new ArrayList<>();
            List<String> grants = new ArrayList<>();
            for (String raw : sql.toString().split(";")) {
                String statement = raw.strip().replace(":\"pub_role\"", "\"" + ROLE + "\"")
                        .replace(":\"prodis_db\"", "\"" + DATABASE + "\"");
                if (statement.isEmpty()) {
                    continue;
                }
                if (statement.contains(":\"") || statement.contains(":'")) {
                    throw new IllegalStateException("Unknown psql variable in " + ROLE_SCRIPT + ": " + statement);
                }
                String upper = statement.toUpperCase(Locale.ROOT);
                if (upper.startsWith("CREATE ROLE ")) {
                    if (createRole != null) {
                        throw new IllegalStateException("More than one CREATE ROLE in " + ROLE_SCRIPT);
                    }
                    createRole = statement;
                } else if (upper.startsWith("ALTER ROLE ") && upper.contains(" SET ")) {
                    settings.add(statement);
                } else if (upper.startsWith("GRANT ")) {
                    grants.add(statement);
                } else {
                    throw new IllegalStateException("Unexpected statement in " + ROLE_SCRIPT + ": " + statement);
                }
            }
            if (createRole == null || grants.isEmpty()) {
                throw new IllegalStateException(ROLE_SCRIPT + " has no CREATE ROLE or no GRANT");
            }
            return new RoleScript(createRole, List.copyOf(settings), List.copyOf(grants));
        }

        /** {@code pg_roles}-kolom → verwachte waarde, afgeleid uit {@code CREATE ROLE} (met de PostgreSQL-defaults). */
        public Map<String, Object> expectedRoleAttributes() {
            Map<String, Object> expected = new LinkedHashMap<>();
            expected.put("rolcanlogin", false);
            expected.put("rolinherit", true);
            expected.put("rolsuper", false);
            expected.put("rolcreatedb", false);
            expected.put("rolcreaterole", false);
            expected.put("rolreplication", false);
            expected.put("rolbypassrls", false);
            expected.put("rolconnlimit", -1);
            String[] tokens = createRole.substring("CREATE ROLE ".length()).strip().split("\\s+");
            for (int i = 1; i < tokens.length; i++) {
                switch (tokens[i].toUpperCase(Locale.ROOT)) {
                    case "LOGIN" -> expected.put("rolcanlogin", true);
                    case "NOLOGIN" -> expected.put("rolcanlogin", false);
                    case "INHERIT" -> expected.put("rolinherit", true);
                    case "NOINHERIT" -> expected.put("rolinherit", false);
                    case "NOSUPERUSER" -> expected.put("rolsuper", false);
                    case "NOCREATEDB" -> expected.put("rolcreatedb", false);
                    case "NOCREATEROLE" -> expected.put("rolcreaterole", false);
                    case "NOREPLICATION" -> expected.put("rolreplication", false);
                    case "NOBYPASSRLS" -> expected.put("rolbypassrls", false);
                    case "CONNECTION" -> {
                        if (i + 2 >= tokens.length || !tokens[i + 1].equalsIgnoreCase("LIMIT")) {
                            throw new IllegalStateException("CONNECTION without LIMIT n in " + createRole);
                        }
                        expected.put("rolconnlimit", Integer.parseInt(tokens[i + 2]));
                        i += 2;
                    }
                    default -> throw new IllegalStateException("Unsupported CREATE ROLE option '" + tokens[i]
                            + "' (extend ProdisPublicationTestDatabase.RoleScript)");
                }
            }
            return expected;
        }
    }
}
