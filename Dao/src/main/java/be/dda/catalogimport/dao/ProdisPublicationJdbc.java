package be.dda.catalogimport.dao;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * De schrijfverbinding naar de Prodis-PostgreSQL voor de publicatie ({@code docs/design/publicatie-prodis-db-direct-design.md}
 * par. 2.1-2.4, slice P5a; beslissingslog 2026-10-22 "Publicatiefase: P-V1..P-V5", P-V2: rechtstreeks schrijven met een
 * eigen DB-rol {@code catalogimport_pub}).
 *
 * <h2>Waarom een eigen type en geen {@code DataSource}/{@code JdbcTemplate}-bean</h2>
 * Zoals {@link ProdisReferenceJdbc}: een tweede bean van type {@code DataSource}, {@code JdbcTemplate} of
 * {@code NamedParameterJdbcTemplate} laat de autoconfiguratie van de hoofddatabase (DataSource, JPA, Liquibase,
 * transactiemanager) terugtreden. De pool blijft daarom privé; deze klasse is het enige bean-type van de schrijfbron en
 * hangt nooit aan een transactie (of transactiesynchronisatie) van de hoofddatabase: geen 2PC (design par. 2.4).
 *
 * <h2>Transactiecontract ({@link #inTransaction})</h2>
 * <ul>
 *   <li>Eén verbinding, {@code autoCommit=false}, READ COMMITTED (de pool), expliciete commit of rollback hier - nooit door
 *       het werk zelf: de {@link Transaction#connection()} weigert {@code commit}, {@code rollback()},
 *       {@code setAutoCommit}, {@code close}, ... ({@link IllegalStateException}) en is na afloop onbruikbaar.</li>
 *   <li>Fout in het werk, bij het openen van de verbinding, of een transactie die de server al afbrak (een fout die het werk
 *       zelf opving): rollback → {@link ProdisTransactionNotAppliedException} met de SQLState - <b>zeker niets
 *       toegepast</b>. Een {@link RuntimeException} uit het werk wordt na de rollback ongewijzigd doorgegeven.</li>
 *   <li>{@link Transaction#setRollbackOnly()} (REJECTED/CONFLICT): rollback, het resultaat van het werk komt terug.</li>
 *   <li>Fout <b>tijdens</b> {@code commit()}: enkel als de server de transactie uitdrukkelijk terugdraaide (SQLState-klasse
 *       {@code 23} integriteit, {@code 40} transactie teruggedraaid) is het "niet toegepast"; elke andere fout - klasse
 *       {@code 08} (verbinding), {@code 57P0x} (sessie beëindigd), geen SQLState, een onverwachte driverfout - is
 *       {@link CommitOutcomeUnknownException}: de commit kan wel of niet doorgegaan zijn (design par. 2.4
 *       {@code RESULT_UNKNOWN}).</li>
 * </ul>
 * Vóór de commit controleert een lichte {@code select 1} dat de transactie niet door de server afgebroken is: PostgreSQL
 * beantwoordt een {@code COMMIT} van een afgebroken transactie stil met {@code ROLLBACK} en de driver meldt dat niet. Zo
 * wordt een opgevangen fout nooit een schijnbaar geslaagde commit.
 * <p>
 * Uitgeschakeld ({@link #disabled()}) als {@code catalogimport.prodis-db-write.*} niet gezet is: geen pool,
 * {@link #inTransaction} weigert met {@link IllegalStateException}; de service vraagt eerst {@link #isConfigured()} en geeft
 * dan 409 {@code PRODIS_WRITE_NOT_CONFIGURED}. Boodschappen noemen nooit URL, gebruiker of parameters.
 */
public final class ProdisPublicationJdbc implements AutoCloseable {

    /** Het werk binnen één Prodis-transactie; mag {@link SQLException} gooien (→ rollback, niet toegepast). */
    @FunctionalInterface
    public interface TransactionWork<T> {
        T run(Transaction transaction) throws SQLException;
    }

    /** De lopende transactie zoals het werk ze ziet. */
    public static final class Transaction {

        private final int queryTimeoutSeconds;
        private Connection connection;
        private boolean rollbackOnly;
        private boolean finished;

        private Transaction(int queryTimeoutSeconds) {
            this.queryTimeoutSeconds = queryTimeoutSeconds;
        }

        /**
         * De verbinding voor SELECT/UPDATE. Commit, rollback, autocommit, isolatie, alleen-lezen en sluiten beheert
         * {@link ProdisPublicationJdbc#inTransaction}; na afloop van het werk is ze onbruikbaar.
         */
        public Connection connection() {
            return connection;
        }

        /** Voor {@code Statement.setQueryTimeout} op elke opdracht van het werk. */
        public int queryTimeoutSeconds() {
            return queryTimeoutSeconds;
        }

        /** De transactie wordt teruggedraaid in plaats van vastgelegd (bv. REJECTED/CONFLICT); geen uitzondering. */
        public void setRollbackOnly() {
            rollbackOnly = true;
        }

        public boolean isRollbackOnly() {
            return rollbackOnly;
        }
    }

    /** Methoden die enkel {@link #inTransaction} zelf mag aanroepen. */
    private static final Set<String> MANAGED = Set.of("commit", "setAutoCommit", "close", "abort",
            "setTransactionIsolation", "setReadOnly", "setCatalog", "setSchema");
    /** SQLState-klassen waarbij de server een mislukte commit uitdrukkelijk terugdraaide. */
    private static final Set<String> ROLLED_BACK_ON_COMMIT = Set.of("23", "40");

    private final DataSource dataSource;
    private final AutoCloseable pool;
    private final int queryTimeoutSeconds;

    private ProdisPublicationJdbc(DataSource dataSource, AutoCloseable pool, int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.pool = pool;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** De functie staat uit: geen configuratie, geen pool. */
    public static ProdisPublicationJdbc disabled() {
        return new ProdisPublicationJdbc(null, null, 0);
    }

    /**
     * @param dataSource          de schrijfpool ({@code autoCommit=false}); wordt bij {@link #close()} gesloten als ze
     *                            {@link AutoCloseable} is
     * @param queryTimeoutSeconds {@code Statement.setQueryTimeout}, minstens 1
     */
    public static ProdisPublicationJdbc of(DataSource dataSource, int queryTimeoutSeconds) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource is required");
        }
        if (queryTimeoutSeconds < 1) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be at least 1");
        }
        return new ProdisPublicationJdbc(dataSource, dataSource instanceof AutoCloseable closeable ? closeable : null,
                queryTimeoutSeconds);
    }

    public boolean isConfigured() {
        return dataSource != null;
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    /**
     * Voert het werk uit in één eigen Prodis-transactie en legt die vast (of draait ze terug), zie de klassebeschrijving.
     *
     * @return het resultaat van het werk (ook bij {@link Transaction#setRollbackOnly()})
     * @throws IllegalStateException                de functie staat uit
     * @throws ProdisTransactionNotAppliedException zeker niets toegepast (met SQLState)
     * @throws CommitOutcomeUnknownException        de commit mislukte; uitkomst onbekend
     */
    public <T> T inTransaction(TransactionWork<T> work) {
        Objects.requireNonNull(work, "work");
        if (dataSource == null) {
            throw new IllegalStateException("The Prodis publication database is not configured");
        }
        Connection physical;
        try {
            physical = dataSource.getConnection();
        } catch (SQLException failure) {
            throw new ProdisTransactionNotAppliedException(failure);
        }
        Transaction transaction = new Transaction(queryTimeoutSeconds);
        try {
            if (physical.getAutoCommit()) {
                physical.setAutoCommit(false);
            }
            transaction.connection = guard(physical, transaction);
            T result;
            try {
                result = work.run(transaction);
            } catch (SQLException failure) {
                rollbackQuietly(physical, failure);
                throw new ProdisTransactionNotAppliedException(failure);
            } catch (RuntimeException | Error failure) {
                rollbackQuietly(physical, failure);
                throw failure;
            } finally {
                transaction.finished = true;
            }
            if (transaction.rollbackOnly) {
                rollbackQuietly(physical, null);
                return result;
            }
            requireNotAborted(physical);
            commit(physical);
            return result;
        } catch (SQLException beforeWork) {
            // setAutoCommit/getAutoCommit: nog niets uitgevoerd.
            rollbackQuietly(physical, beforeWork);
            throw new ProdisTransactionNotAppliedException(beforeWork);
        } finally {
            transaction.finished = true;
            closeQuietly(physical);
        }
    }

    /** Een door de server afgebroken transactie (een fout die het werk opving) mag nooit "gecommit" lijken. */
    private void requireNotAborted(Connection physical) {
        try (Statement probe = physical.createStatement()) {
            probe.setQueryTimeout(queryTimeoutSeconds);
            probe.execute("select 1");
        } catch (SQLException aborted) {
            // 25P02 (afgebroken transactie) of de verbinding is weg: de COMMIT is nog niet verstuurd.
            rollbackQuietly(physical, aborted);
            throw new ProdisTransactionNotAppliedException(aborted);
        }
    }

    private static void commit(Connection physical) {
        try {
            physical.commit();
        } catch (SQLException failure) {
            String state = failure.getSQLState();
            if (state != null && state.length() >= 2 && ROLLED_BACK_ON_COMMIT.contains(state.substring(0, 2))) {
                rollbackQuietly(physical, failure);
                throw new ProdisTransactionNotAppliedException(failure);
            }
            throw new CommitOutcomeUnknownException(state, failure);
        } catch (RuntimeException unexpected) {
            throw new CommitOutcomeUnknownException(null, unexpected);
        }
    }

    private static void rollbackQuietly(Connection physical, Throwable primary) {
        try {
            physical.rollback();
        } catch (SQLException | RuntimeException rollbackFailure) {
            // Zonder COMMIT legt PostgreSQL niets vast (ook niet als de verbinding weg is); de oorspronkelijke fout telt.
            if (primary != null) {
                primary.addSuppressed(rollbackFailure);
            }
        }
    }

    private static void closeQuietly(Connection physical) {
        try {
            physical.close();
        } catch (SQLException | RuntimeException ignored) {
            // De pool ruimt een kapotte verbinding zelf op.
        }
    }

    /** De verbinding voor het werk: transactiebeheer is voorbehouden, en na afloop is ze onbruikbaar. */
    private static Connection guard(Connection physical, Transaction transaction) {
        return (Connection) Proxy.newProxyInstance(ProdisPublicationJdbc.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (name) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> "ProdisPublicationJdbc.Transaction connection";
                        };
                    }
                    if (transaction.finished) {
                        throw new SQLException("The Prodis transaction has already finished", "08003");
                    }
                    boolean wholeRollback = name.equals("rollback") && (args == null || args.length == 0);
                    if (MANAGED.contains(name) || wholeRollback) {
                        throw new IllegalStateException("Connection." + name + " is managed by "
                                + "ProdisPublicationJdbc.inTransaction (use Transaction.setRollbackOnly to roll back)");
                    }
                    try {
                        return method.invoke(physical, args);
                    } catch (InvocationTargetException invocation) {
                        throw invocation.getCause();
                    }
                });
    }

    /** Sluit de pool (Spring roept dit op bij het afsluiten van de context). */
    @Override
    public void close() throws Exception {
        if (pool != null) {
            pool.close();
        }
    }
}
