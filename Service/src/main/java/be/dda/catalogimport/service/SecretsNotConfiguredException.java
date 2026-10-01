package be.dda.catalogimport.service;

/**
 * 409 {@link #CODE}: er is geen sleutelring geconfigureerd ({@code catalogimport.secrets.*}), dus kan er niets
 * versleuteld of ontsleuteld worden. De applicatie start wel (beslissingslog 2026-09-29, V7); enkel het gebruik
 * van de functie weigert, expliciet en nooit stil.
 */
public class SecretsNotConfiguredException extends ConflictException {

    public static final String CODE = "SECRETS_NOT_CONFIGURED";

    public SecretsNotConfiguredException() {
        super(CODE, "Encrypted credential storage is not configured on this environment "
                + "(catalogimport.secrets.* is not set)");
    }
}
