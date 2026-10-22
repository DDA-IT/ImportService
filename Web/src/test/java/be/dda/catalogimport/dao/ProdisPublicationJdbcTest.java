package be.dda.catalogimport.dao;

import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.DETAIL_ID_VKP1;
import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.PRICE_ID;
import static be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase.ownerValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.config.ProdisPublicationDatabaseConfiguration;
import be.dda.catalogimport.config.ProdisPublicationDatabaseProperties;
import be.dda.catalogimport.testsupport.ProdisPublicationTestDatabase;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;

/**
 * Het transactiecontract van {@link ProdisPublicationJdbc#inTransaction} tegen PostgreSQL (publicatie P5a;
 * {@code docs/design/publicatie-prodis-db-direct-design.md} par. 2.2 en 2.4), als de testrol met de rechten uit het
 * productierolscript, enkel in {@code prodis_pubtest} (guard in {@link ProdisPublicationTestDatabase}).
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Eén verbinding, expliciete commit: het werk is na afloop zichtbaar, READ COMMITTED, ApplicationName gezet.</li>
 *   <li>Fout vóór de commit (SQL-fout, ook een door het werk opgevangen fout, verbindingsfout, lock-time-out) = zeker niet
 *       toegepast: {@link ProdisTransactionNotAppliedException} met SQLState, niets gewijzigd.</li>
 *   <li>{@code setRollbackOnly} (REJECTED/CONFLICT) = niets gewijzigd, resultaat terug.</li>
 *   <li>Verbinding weg na het werk maar vóór de commit = zeker niet toegepast (08003 / 57P01).</li>
 *   <li>Fout tijdens {@code commit()} = {@link CommitOutcomeUnknownException}: gesimuleerd met een test-hook rond de pool
 *       die in {@code commit()} vóór de echte driver-commit (1) de fysieke verbinding sluit (SQLState 08003,
 *       deterministisch) of (2) de backend laat beëindigen met {@code pg_terminate_backend} vanuit een tweede sessie van
 *       dezelfde rol (realistisch; PostgreSQL meldt 57P01).</li>
 *   <li>Het werk kan de transactie niet zelf beheren (commit/rollback/close geweigerd) en de verbinding niet na afloop
 *       gebruiken.</li>
 * </ul>
 */
class ProdisPublicationJdbcTest {

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
    void theWorkIsCommittedOnOneReadCommittedConnection() {
        String[] session = jdbc.inTransaction(transaction -> {
            Connection connection = transaction.connection();
            assertThat(connection.getAutoCommit()).isFalse();
            assertThat(update(transaction, "update catalog_article_price set base_price = ?, last_modified_by = 'p5a' "
                    + "where id = ?", new BigDecimal("123.456789"), PRICE_ID)).isEqualTo(1);
            return new String[] {single(connection, "show transaction_isolation"),
                    single(connection, "show application_name"), single(connection, "show transaction_read_only")};
        });
        assertThat(session).containsExactly("read committed", "catalogimport-publish", "off");
        assertThat(ownerValue("select base_price from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo(new BigDecimal("123.456789"));
    }

    @Test
    void aSqlErrorInTheWorkRollsBackEverythingAndCarriesTheSqlState() {
        assertThatThrownBy(() -> jdbc.inTransaction(transaction -> {
            update(transaction, "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID);
            return update(transaction, "update catalog_article_price set purchase_price = 1 where id = ?", PRICE_ID);
        })).isInstanceOfSatisfying(ProdisTransactionNotAppliedException.class,
                failure -> assertThat(failure.getSqlState()).isEqualTo("42501"))
                .hasMessageContaining("SQLState 42501").hasMessageNotContaining("catalogimport_pub_test");
        assertUnchanged();
    }

    @Test
    void anErrorTheWorkSwallowedNeverLooksLikeACommit() {
        // PostgreSQL beantwoordt COMMIT van een afgebroken transactie stil met ROLLBACK; de controle vóór de commit vangt dat.
        assertThatThrownBy(() -> jdbc.inTransaction(transaction -> {
            update(transaction, "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID);
            try {
                update(transaction, "insert into catalog_article_price (id) values (?)", 999L);
            } catch (SQLException swallowed) {
                // bewust opgevangen: de transactie is nu afgebroken
            }
            return "looks fine";
        })).isInstanceOfSatisfying(ProdisTransactionNotAppliedException.class,
                failure -> assertThat(failure.getSqlState()).isEqualTo("25P02"));
        assertUnchanged();
    }

    @Test
    void aRuntimeExceptionFromTheWorkIsRethrownAfterTheRollback() {
        IllegalArgumentException planner = new IllegalArgumentException("planner says no");
        assertThatThrownBy(() -> jdbc.inTransaction(transaction -> {
            update(transaction, "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID);
            throw planner;
        })).isSameAs(planner);
        assertUnchanged();
    }

    @Test
    void rollbackOnlyWritesNothingAndReturnsTheResult() {
        String result = jdbc.inTransaction(transaction -> {
            update(transaction, "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID);
            update(transaction, "update catalog_article_price_detail set price = 1 where id = ?", DETAIL_ID_VKP1);
            transaction.setRollbackOnly();
            return "CONFLICT";
        });
        assertThat(result).isEqualTo("CONFLICT");
        assertUnchanged();
    }

    @Test
    void theWorkCannotManageTheTransactionNorUseTheConnectionAfterwards() {
        Connection[] leaked = new Connection[1];
        assertThatThrownBy(() -> jdbc.inTransaction(transaction -> {
            update(transaction, "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID);
            transaction.connection().commit();
            return null;
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Connection.commit is managed");
        assertUnchanged();
        for (String forbidden : new String[] {"rollback", "close", "setAutoCommit"}) {
            assertThatThrownBy(() -> jdbc.inTransaction(transaction -> {
                Connection connection = transaction.connection();
                switch (forbidden) {
                    case "rollback" -> connection.rollback();
                    case "close" -> connection.close();
                    default -> connection.setAutoCommit(true);
                }
                return null;
            })).as(forbidden).isInstanceOf(IllegalStateException.class).hasMessageContaining(forbidden);
        }
        jdbc.inTransaction(transaction -> {
            leaked[0] = transaction.connection();
            return null;
        });
        assertThatThrownBy(() -> leaked[0].createStatement()).isInstanceOf(SQLException.class)
                .hasMessageContaining("already finished");
    }

    @Test
    void aConnectionLostBeforeTheCommitIsNotApplied() {
        // Verbinding gesloten of sessie door de server beëindigd NA het werk maar VÓÓR de commit: de controle vóór de
        // commit ziet het, er is geen COMMIT verstuurd → zeker niet toegepast (design par. 2.4: klasse 08 vóór commit).
        assertThatThrownBy(() -> jdbc.inTransaction(transaction -> {
            update(transaction, "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID);
            ((Connection) transaction.connection().unwrap(PGConnection.class)).close();
            return null;
        })).isInstanceOfSatisfying(ProdisTransactionNotAppliedException.class,
                failure -> assertThat(failure.getSqlState()).isEqualTo("08003"));
        assertUnchanged();
        assertThatThrownBy(() -> jdbc.inTransaction(transaction -> {
            update(transaction, "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID);
            terminateAndWait(transaction.connection().unwrap(PGConnection.class).getBackendPID());
            return null;
        })).isInstanceOfSatisfying(ProdisTransactionNotAppliedException.class,
                failure -> assertThat(failure.getSqlState()).isEqualTo("57P01"));
        assertUnchanged();
        // De pool ruimt de kapotte verbindingen op: de volgende transactie werkt gewoon.
        String next = jdbc.inTransaction(transaction -> single(transaction.connection(), "select 1"));
        assertThat(next).isEqualTo("1");
    }

    /**
     * Test-hook rond de pool: {@code commit()} sluit eerst de fysieke verbinding en roept dan de echte commit van de driver
     * op - een echte driverfout tijdens de commit (SQLState 08003), deterministisch.
     */
    @Test
    void aClosedConnectionDuringCommitGivesAnUnknownOutcome() throws Exception {
        try (ProdisPublicationJdbc hooked = hookedJdbc(physical -> ((Connection) physical.unwrap(PGConnection.class))
                .close())) {
            assertThatThrownBy(() -> hooked.inTransaction(transaction -> update(transaction,
                    "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID)))
                    .isInstanceOfSatisfying(CommitOutcomeUnknownException.class,
                            failure -> assertThat(failure.getSqlState()).isEqualTo("08003"))
                    .hasMessageContaining("commit outcome unknown");
        }
        // Hier weten we toevallig dat de server niets vastlegde (de COMMIT ging nooit weg); de uitkomst heet "onbekend"
        // omdat de klasse dat in het algemeen niet kan weten - de retry (P5b) beslist via reconcile-by-state.
        assertUnchanged();
    }

    /**
     * Test-hook rond de pool: {@code commit()} laat eerst een tweede sessie van dezelfde rol de backend beëindigen
     * ({@code pg_terminate_backend}, wachten tot hij weg is) en roept dan de echte commit op - zoals een serverstop of
     * beheerder midden in de publicatie. PostgreSQL meldt dat met 57P01 (geen klasse 08): ook dat is "onbekend".
     */
    @Test
    void aSessionTerminatedByTheServerDuringCommitGivesAnUnknownOutcome() throws Exception {
        try (ProdisPublicationJdbc hooked = hookedJdbc(physical -> terminateAndWait(
                physical.unwrap(PGConnection.class).getBackendPID()))) {
            assertThatThrownBy(() -> hooked.inTransaction(transaction -> update(transaction,
                    "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID)))
                    .isInstanceOfSatisfying(CommitOutcomeUnknownException.class,
                            failure -> assertThat(failure.getSqlState()).isIn("57P01", "08006"));
        }
        assertUnchanged();
    }

    /** Een succesvolle commit via dezelfde hook-opbouw (controle dat de hook zelf niets anders verandert). */
    @Test
    void theHookedPoolCommitsNormallyWithoutAFailure() throws Exception {
        try (ProdisPublicationJdbc hooked = hookedJdbc(physical -> { })) {
            hooked.inTransaction(transaction -> update(transaction,
                    "update catalog_article_price set base_price = 7 where id = ?", PRICE_ID));
        }
        assertThat(ownerValue("select base_price from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo(new BigDecimal("7.000000"));
    }

    @Test
    void aLockTimeoutIsNotAppliedWithItsSqlState() throws Exception {
        try (Connection owner = ProdisPublicationTestDatabase.owner()) {
            owner.setAutoCommit(false);
            try (Statement lock = owner.createStatement()) {
                lock.execute("select id from catalog_article_price where id = " + PRICE_ID + " for update");
            }
            long start = System.nanoTime();
            assertThatThrownBy(() -> jdbc.inTransaction(transaction -> update(transaction,
                    "update catalog_article_price set base_price = 1 where id = ?", PRICE_ID)))
                    .isInstanceOfSatisfying(ProdisTransactionNotAppliedException.class,
                            failure -> assertThat(failure.getSqlState()).isEqualTo("55P03"));
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(4_500),
                    Duration.ofSeconds(15));
            owner.rollback();
        }
        assertUnchanged();
    }

    @Test
    void aConnectionFailureIsNotApplied() throws Exception {
        ProdisPublicationDatabaseProperties properties = new ProdisPublicationDatabaseProperties();
        properties.setUrl(ProdisPublicationTestDatabase.URL);
        properties.setUsername(ProdisPublicationTestDatabase.ROLE);
        properties.setPassword("wrong-password");
        properties.setConnectTimeout(Duration.ofSeconds(2));
        try (ProdisPublicationJdbc wrong = new ProdisPublicationDatabaseConfiguration()
                .prodisPublicationJdbc(properties)) {
            assertThatThrownBy(() -> wrong.inTransaction(transaction -> 1))
                    .isInstanceOf(ProdisTransactionNotAppliedException.class)
                    .hasMessageNotContaining("wrong-password");
        }
    }

    @FunctionalInterface
    private interface BeforeCommit {
        void run(Connection physical) throws SQLException;
    }

    /**
     * Een pool met dezelfde transactie-instellingen als productie (autoCommit=false, READ COMMITTED) waarvan elke
     * verbinding vóór de echte {@code commit()} eerst de hook uitvoert. Enkel testcode: {@link ProdisPublicationJdbc} zelf
     * kent geen hook.
     */
    private static ProdisPublicationJdbc hookedJdbc(BeforeCommit hook) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("p5a-commit-hook");
        config.setJdbcUrl(ProdisPublicationTestDatabase.URL);
        config.setUsername(ProdisPublicationTestDatabase.ROLE);
        config.setPassword(ProdisPublicationTestDatabase.ROLE_PASSWORD);
        config.setAutoCommit(false);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        HikariDataSource pool = new HikariDataSource(config);
        DataSource hooked = (DataSource) Proxy.newProxyInstance(ProdisPublicationJdbcTest.class.getClassLoader(),
                new Class<?>[] {DataSource.class, AutoCloseable.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        pool.close();
                        return null;
                    }
                    Object result = invoke(method, pool, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    Connection connection = (Connection) result;
                    ProdisPublicationTestDatabase.requireTestDatabase(connection);
                    return Proxy.newProxyInstance(ProdisPublicationJdbcTest.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (connectionProxy, connectionMethod, connectionArgs) -> {
                                if (connectionMethod.getName().equals("commit")) {
                                    hook.run(connection);
                                }
                                return invoke(connectionMethod, connection, connectionArgs);
                            });
                });
        return ProdisPublicationJdbc.of(hooked, 30);
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException invocation) {
            throw invocation.getCause();
        }
    }

    private static void terminateAndWait(int pid) throws SQLException {
        try (Connection other = ProdisPublicationTestDatabase.role(); Statement statement = other.createStatement()) {
            try (ResultSet terminated = statement.executeQuery("select pg_terminate_backend(" + pid + ")")) {
                terminated.next();
                assertThat(terminated.getBoolean(1)).isTrue();
            }
            for (int attempt = 0; attempt < 100; attempt++) {
                try (ResultSet alive = statement.executeQuery("select count(*) from pg_stat_activity where pid = "
                        + pid)) {
                    alive.next();
                    if (alive.getInt(1) == 0) {
                        return;
                    }
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            throw new IllegalStateException("backend " + pid + " still alive");
        }
    }

    private static void assertUnchanged() {
        assertThat(ownerValue("select base_price from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo(new BigDecimal("100.000000"));
        assertThat(ownerValue("select price from catalog_article_price_detail where id = " + DETAIL_ID_VKP1))
                .isEqualTo(110.0);
        assertThat(ownerValue("select last_modified_by is null from catalog_article_price where id = " + PRICE_ID))
                .isEqualTo(true);
    }

    private static int update(ProdisPublicationJdbc.Transaction transaction, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = transaction.connection().prepareStatement(sql)) {
            statement.setQueryTimeout(transaction.queryTimeoutSeconds());
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static String single(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }
}
