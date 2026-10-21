package be.dda.catalogimport.service;

/**
 * 503 {@link #CODE}: de alleen-lezen Prodis-database is geconfigureerd maar nu niet bruikbaar (onbereikbaar, time-out,
 * ontbrekende rechten of tabel; {@code docs/design/legacy-config-bundle-design.md} par. 6, slice S5). De boodschap noemt
 * nooit URL, gebruiker of wachtwoord.
 */
public class ProdisReferenceUnavailableException extends RuntimeException {

    public static final String CODE = "PRODIS_DB_UNAVAILABLE";

    public ProdisReferenceUnavailableException(Throwable cause) {
        super("The Prodis reference database is not available right now; try again later", cause);
    }

    public String getCode() {
        return CODE;
    }
}
