package be.dda.catalogimport.dao;

/**
 * De {@code commit()} van een Prodis-schrijftransactie ({@link ProdisPublicationJdbc#inTransaction}) is mislukt op een manier
 * waarbij niet vaststaat of de server de transactie heeft vastgelegd (verbinding verbroken, SQLState-klasse {@code 08},
 * sessie door de server beëindigd, ...). {@code docs/design/publicatie-prodis-db-direct-design.md} par. 2.2/2.4: het item
 * wordt {@code RESULT_UNKNOWN}; een volgende poging stelt via reconcile-by-state vast wat er in Prodis staat en schrijft
 * nooit dubbel. Nooit stil als "niet toegepast" of "toegepast" behandelen.
 * <p>
 * {@link #getSqlState()} is de SQLState van de oorzaak, of {@code null} als die er niet is. De boodschap noemt nooit URL,
 * gebruiker of parameters.
 */
public class CommitOutcomeUnknownException extends RuntimeException {

    private final String sqlState;

    public CommitOutcomeUnknownException(String sqlState, Throwable cause) {
        super("Prodis commit outcome unknown (SQLState " + sqlState + ")", cause);
        this.sqlState = sqlState;
    }

    /** De SQLState van de oorzaak; {@code null} als die er niet is (bv. een onverwachte driverfout). */
    public String getSqlState() {
        return sqlState;
    }
}
