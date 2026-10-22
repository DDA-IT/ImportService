package be.dda.catalogimport.dao;

import java.sql.SQLException;

/**
 * Een Prodis-schrijftransactie ({@link ProdisPublicationJdbc#inTransaction}) is mislukt <b>vóór</b> of met een expliciete
 * rollback van de server: er is <b>zeker niets</b> toegepast ({@code docs/design/publicatie-prodis-db-direct-design.md} par.
 * 2.2 en 2.4: fout vóór {@code commit()} → item terug naar {@code PENDING} met {@code last_error_code}).
 * <p>
 * {@link #getSqlState()} is de SQLState van de oorzaak (bv. {@code 42501} geen recht, {@code 55P03} lock-time-out,
 * {@code 40P01} deadlock, {@code 57014} statement-time-out, {@code 08006} verbinding weg vóór de commit), of {@code null}
 * als de driver er geen gaf. De boodschap noemt nooit URL, gebruiker of parameters.
 */
public class ProdisTransactionNotAppliedException extends RuntimeException {

    private final String sqlState;

    public ProdisTransactionNotAppliedException(SQLException cause) {
        super("Prodis transaction not applied (rolled back, SQLState " + cause.getSQLState() + ")", cause);
        this.sqlState = cause.getSQLState();
    }

    /** De SQLState van de oorzaak; {@code null} als de driver er geen gaf. */
    public String getSqlState() {
        return sqlState;
    }
}
