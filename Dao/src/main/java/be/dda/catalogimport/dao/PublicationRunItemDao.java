package be.dda.catalogimport.dao;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Het materialiseren van de items van een PRODUCTION-run (P5c, {@code docs/design/publicatie-prodis-db-direct-design.md}
 * par. 2.4; {@code publicatie-prodis-db-design.md} par. 2 "Transacties (1)"): één JDBC-batch per pagina snapshotrijen, zodat
 * een grote bundel niet als duizenden entiteiten in de persistentiecontext belandt. De overgangen daarna gaan via de
 * entiteit ({@link be.dda.catalogimport.domain.PublicationRunItem}). Opent zelf geen transactie.
 */
@Repository
public class PublicationRunItemDao {

    /** Een nieuw item: status {@code PENDING}, nooit verstuurd (de kolomdefaults van 027-1/028-2). */
    public record NewItem(long mutationId, int publicationAttempt, String idempotencyKey, String requestHash,
                          String requestJson) {
    }

    /** Een eerder item van dezelfde mutatie (in om het even welke run). */
    public record PriorItem(long mutationId, int publicationAttempt, String status) {
    }

    private final JdbcTemplate jdbc;

    public PublicationRunItemDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Alle eerdere items van deze mutaties, voor het attempt-nummer en de controle dat niets dubbel loopt. */
    public List<PriorItem> findPriorItems(List<Long> mutationIds) {
        if (mutationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select mutation_id, publication_attempt, status from publication_run_item where mutation_id in ("
                        + String.join(",", Collections.nCopies(mutationIds.size(), "?")) + ") order by mutation_id, "
                        + "publication_attempt",
                (rs, row) -> new PriorItem(rs.getLong(1), rs.getInt(2), rs.getString(3)), mutationIds.toArray());
    }

    /**
     * Voegt de items in als {@code PENDING}. Een botsing op {@code uk_publication_run_item_*} gaat als
     * {@link org.springframework.dao.DataIntegrityViolationException} naar boven: de aanroeper draait de hele start terug.
     *
     * @return het aantal ingevoegde rijen
     */
    public int insertPending(long runId, List<NewItem> items) {
        if (items.isEmpty()) {
            return 0;
        }
        int[] counts = jdbc.batchUpdate("insert into publication_run_item (run_id, mutation_id, publication_attempt, "
                + "idempotency_key, request_hash, request_json) values (?, ?, ?, ?, ?, ?)", new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        NewItem item = items.get(index);
                        statement.setLong(1, runId);
                        statement.setLong(2, item.mutationId());
                        statement.setInt(3, item.publicationAttempt());
                        statement.setString(4, item.idempotencyKey());
                        statement.setString(5, item.requestHash());
                        statement.setString(6, item.requestJson());
                    }

                    @Override
                    public int getBatchSize() {
                        return items.size();
                    }
                });
        int inserted = 0;
        for (int count : counts) {
            // SUCCESS_NO_INFO (-2) telt als één rij: een insert ... values voegt er precies één toe of faalt.
            inserted += count == java.sql.Statement.SUCCESS_NO_INFO ? 1 : count;
        }
        return inserted;
    }
}
