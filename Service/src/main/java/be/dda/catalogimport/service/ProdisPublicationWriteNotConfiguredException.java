package be.dda.catalogimport.service;

/**
 * 409 {@link #CODE}: er is geen schrijfverbinding naar de Prodis-database geconfigureerd
 * ({@code catalogimport.prodis-db-write.*}; {@code docs/design/publicatie-prodis-db-direct-design.md} par. 2.1). De
 * applicatie start wel (functie uit); enkel het echt publiceren naar Prodis weigert, expliciet en nooit stil.
 */
public class ProdisPublicationWriteNotConfiguredException extends ConflictException {

    public static final String CODE = "PRODIS_WRITE_NOT_CONFIGURED";

    public ProdisPublicationWriteNotConfiguredException() {
        super(CODE, "The Prodis publication (write) database is not configured on this environment "
                + "(catalogimport.prodis-db-write.* is not set)");
    }
}
