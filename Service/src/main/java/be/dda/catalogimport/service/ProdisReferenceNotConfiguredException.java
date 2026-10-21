package be.dda.catalogimport.service;

/**
 * 409 {@link #CODE}: er is geen alleen-lezen Prodis-database geconfigureerd ({@code catalogimport.prodis-db.*}). De
 * applicatie start wel (functie uit, {@code docs/design/legacy-config-bundle-design.md} par. 6); enkel de keuzelijsten
 * weigeren, expliciet en nooit stil.
 */
public class ProdisReferenceNotConfiguredException extends ConflictException {

    public static final String CODE = "PRODIS_DB_NOT_CONFIGURED";

    public ProdisReferenceNotConfiguredException() {
        super(CODE, "The Prodis reference database is not configured on this environment "
                + "(catalogimport.prodis-db.* is not set)");
    }
}
