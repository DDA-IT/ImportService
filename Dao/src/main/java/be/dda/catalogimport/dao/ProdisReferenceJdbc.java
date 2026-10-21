package be.dda.catalogimport.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * De alleen-lezen verbinding naar de Prodis-PostgreSQL ({@code docs/design/legacy-config-bundle-design.md} par. 6,
 * slice S5; beslissingslog 2026-10-20 "datasync-aanpak, Prodis-DB lezen, sjablonen").
 *
 * <h2>Waarom een eigen type en geen {@code DataSource}/{@code JdbcTemplate}-bean</h2>
 * Een tweede bean van type {@code DataSource}, {@code JdbcTemplate} of {@code NamedParameterJdbcTemplate} laat de
 * Spring Boot-autoconfiguratie van de hoofddatabase terugtreden ({@code @ConditionalOnMissingBean}; design par. 10).
 * Deze klasse houdt de pool daarom privé en is zelf het enige bean-type van de Prodis-bron. Ze gebruikt ook bewust
 * geen {@code DataSourceUtils}: een verbinding wordt per query geopend en meteen teruggegeven, en hangt dus nooit aan
 * een transactie (of transactiesynchronisatie) van de hoofddatabase.
 * <p>
 * Uitgeschakeld ({@link #disabled()}) als {@code catalogimport.prodis-db.*} niet gezet is: dan bestaat er geen pool en
 * weigert {@link #query} met een {@link IllegalStateException} (de service vraagt eerst {@link #isConfigured()}).
 * <p>
 * Alleen lezen wordt afgedwongen door de pool ({@code readOnly}, voor PostgreSQL ook {@code readOnlyMode=always}) en door
 * de DB-rol ({@code catalogimport_ro}, {@code default_transaction_read_only=on}); deze klasse biedt enkel lezen aan.
 * Een fout wordt een {@link DataAccessResourceFailureException} met enkel de SQLState in de boodschap (nooit URL,
 * gebruiker of parameters).
 */
public final class ProdisReferenceJdbc implements AutoCloseable {

    /** Eén rij omzetten; mag {@link SQLException} gooien. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }

    private final DataSource dataSource;
    private final AutoCloseable pool;
    private final int queryTimeoutSeconds;

    private ProdisReferenceJdbc(DataSource dataSource, AutoCloseable pool, int queryTimeoutSeconds) {
        this.dataSource = dataSource;
        this.pool = pool;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** De functie staat uit: geen configuratie, geen pool. */
    public static ProdisReferenceJdbc disabled() {
        return new ProdisReferenceJdbc(null, null, 0);
    }

    /**
     * @param dataSource          de (alleen-lezen) pool; wordt bij {@link #close()} gesloten als ze {@link AutoCloseable} is
     * @param queryTimeoutSeconds {@code Statement.setQueryTimeout}, minstens 1
     */
    public static ProdisReferenceJdbc of(DataSource dataSource, int queryTimeoutSeconds) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource is required");
        }
        if (queryTimeoutSeconds < 1) {
            throw new IllegalArgumentException("queryTimeoutSeconds must be at least 1");
        }
        return new ProdisReferenceJdbc(dataSource, dataSource instanceof AutoCloseable closeable ? closeable : null,
                queryTimeoutSeconds);
    }

    public boolean isConfigured() {
        return dataSource != null;
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    /**
     * Voert één SELECT uit op een eigen, kortlevende verbinding.
     *
     * @throws IllegalStateException               de functie staat uit
     * @throws DataAccessResourceFailureException de Prodis-database is onbereikbaar of de query faalt
     */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        if (dataSource == null) {
            throw new IllegalStateException("The Prodis reference database is not configured");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(queryTimeoutSeconds);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            List<T> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(mapper.map(resultSet));
                }
            }
            return rows;
        } catch (SQLException failure) {
            throw new DataAccessResourceFailureException("Prodis reference query failed (SQLState "
                    + failure.getSQLState() + ")", failure);
        }
    }

    /** Sluit de pool (Spring roept dit op bij het afsluiten van de context). */
    @Override
    public void close() throws Exception {
        if (pool != null) {
            pool.close();
        }
    }
}
