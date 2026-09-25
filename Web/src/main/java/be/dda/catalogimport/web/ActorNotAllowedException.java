package be.dda.catalogimport.web;

/**
 * De aangemelde gebruiker mag deze actie niet uitvoeren; de Web-laag antwoordt HTTP 403 met een stabiele
 * {@link #getCode()} (Fase 5-AUTH, ontwerp par. 3), in dezelfde vorm {@code {error, code}} als de andere
 * foutantwoorden van {@link ApiExceptionHandler}.
 * <p>
 * Codes in 5-AUTH: {@code ACTOR_IDENTITY_INVALID} (de login levert geen bruikbare identiteit op) en, vanaf
 * 5A-2, {@code SYSTEM_ACTOR_FORBIDDEN}. 5-PERM hergebruikt deze vorm voor ontbrekende rechten.
 * <p>
 * Bewust geen subklasse van {@link IllegalArgumentException}: een 403 mag nooit door de bredere
 * 400-handler opgevangen worden.
 */
public class ActorNotAllowedException extends RuntimeException {

    private final String code;

    public ActorNotAllowedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
