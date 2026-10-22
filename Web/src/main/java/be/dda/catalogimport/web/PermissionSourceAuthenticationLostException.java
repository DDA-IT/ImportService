package be.dda.catalogimport.web;

import org.springframework.security.core.AuthenticationException;

/**
 * De rechtenbron kon de gebruiker niet bevragen omdat de login van deze sessie geen bruikbaar token meer heeft
 * (5B-7, beslissingslog 2026-10-22 V4 = B): de refresh werd geweigerd of er is geen geautoriseerde client in de sessie.
 * <p>
 * Antwoord: <b>401 {@code AUTHENTICATION_REQUIRED}</b>, zodat de SPA "sessie verlopen" toont en opnieuw laat inloggen.
 * Bewust een {@link AuthenticationException}: zo loopt ze langs de bestaande 401-route (de
 * {@code ExceptionTranslationFilter} en het JSON-entrypoint van {@code SecurityConfiguration}), net als de
 * {@code AuthenticationCredentialsNotFoundException} van {@link CurrentActor} — geen tweede 401-vorm in
 * {@link ApiExceptionHandler}.
 * <p>
 * Blijft fail-closed: nooit een lege set en nooit een gecachet antwoord. De boodschap is vast en noemt token, body of
 * gebruiker nooit; de veilige reden staat in de WARN-regel van {@link ProdisPermissionSource}.
 */
public class PermissionSourceAuthenticationLostException extends AuthenticationException {

    private static final long serialVersionUID = 1L;

    public PermissionSourceAuthenticationLostException() {
        super("Authentication required: the session can no longer be used to determine permissions");
    }
}
