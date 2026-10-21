package be.dda.catalogimport.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Een H2-database in het geheugen die de drie Prodis-tabellen nabootst ({@code prodis-reference-test-schema.sql}; legacy-config
 * S5). De applicatie leest ze alleen-lezen via {@code ProdisReferenceJdbc}; de test vult ze via een eigen verbinding met
 * dezelfde URL en gebruiker. Leeft zolang de JVM ({@code DB_CLOSE_DELAY=-1}); elke test gebruikt eigen leveranciersnummers.
 */
public final class ProdisReferenceTestDatabase {

    public static final String USERNAME = "sa";
    /** Enkel voor de H2-testbron; geen echt secret. */
    public static final String PASSWORD = "prodis-reference-test-only";

    private final String url;

    private ProdisReferenceTestDatabase(String url) {
        this.url = url;
    }

    /**
     * {@code name} = de naam van de H2-database in het geheugen (één per Spring-context of testklasse). Maakt het schema
     * aan (idempotent). Bewust niet via {@code INIT=} in de URL: dan zou elke nieuwe verbinding een weggehaalde tabel
     * opnieuw aanmaken en kon {@link #breakSource()} niets simuleren.
     */
    public static ProdisReferenceTestDatabase named(String name) {
        return at(url(name));
    }

    /** Zoals {@link #named}, met een volledige URL (bv. een constante in een testannotatie). */
    public static ProdisReferenceTestDatabase at(String url) {
        ProdisReferenceTestDatabase database = new ProdisReferenceTestDatabase(url);
        database.execute("runscript from 'classpath:prodis-reference-test-schema.sql'");
        return database;
    }

    public static String url(String name) {
        return "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    public String url() {
        return url;
    }

    public long insertSupplier(String number, String name) {
        return insert("insert into supplier (number, name) values (?, ?)", number, name);
    }

    public long insertGroup(long supplierId, String legacyCode, String groupName, String discountCode) {
        return insert("insert into supplier_group (supplier_id, legacy_code, group_name, discount_code) "
                + "values (?, ?, ?, ?)", supplierId, legacyCode, groupName, discountCode);
    }

    public long insertCatalog(long supplierId, String scopeKey, String legacyNumber, String catalogName,
                              String status) {
        return insert("insert into supplier_catalog (supplier_id, scope_key, legacy_number, catalog_name, status) "
                + "values (?, ?, ?, ?, ?)", supplierId, scopeKey, legacyNumber, catalogName, status);
    }

    public void execute(String sql, Object... args) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Maakt de bron tijdelijk onbruikbaar (alsof Prodis weg is of de rechten ontbreken): {@code supplier} wordt hernoemd.
     * Altijd herstellen met {@link #restore()} (in een {@code finally}).
     */
    public void breakSource() {
        execute("alter table supplier rename to supplier_unavailable");
    }

    public void restore() {
        execute("alter table supplier_unavailable rename to supplier");
    }

    private long insert(String sql, Object... args) {
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, USERNAME, PASSWORD);
    }
}
