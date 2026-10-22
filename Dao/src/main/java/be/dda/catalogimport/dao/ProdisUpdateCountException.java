package be.dda.catalogimport.dao;

/**
 * Een {@code UPDATE ... WHERE id = ?} van {@link ProdisPublicationWriter} raakte niet precies één rij (of er was geen
 * eenduidig vergrendelde rij om bij te werken). De hele Prodis-transactie wordt teruggedraaid: er is <b>zeker niets</b>
 * toegepast ({@code docs/design/publicatie-prodis-db-direct-design.md} par. 2.4: "update-count = 1"). De boodschap noemt
 * enkel tabel en aantallen, nooit id's of waarden.
 */
public class ProdisUpdateCountException extends RuntimeException {

    /** Stabiele code voor {@code publication_run_item.last_error_code}. */
    public static final String CODE = "PRODIS_UPDATE_COUNT_MISMATCH";

    public ProdisUpdateCountException(String message) {
        super(message);
    }
}
