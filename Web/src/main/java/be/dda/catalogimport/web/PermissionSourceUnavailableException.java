package be.dda.catalogimport.web;

/**
 * De rechtenbron kon niet bevraagd worden (Fase 5-PERM, ontwerp par. 2 en 3): HTTP 503 met code
 * {@value #CODE}.
 * <p>
 * <b>Nooit verwarren met "geen rechten".</b> Een bron die antwoordt dat iemand niets heeft, geeft een
 * lege set en dat is 403 {@code PERMISSION_DENIED}. Een bron die niet antwoordt, gooit dit — anders zou
 * een storing er als een intrekking uitzien, of erger: als een stille toekenning.
 * <p>
 * Bewust geen subklasse van {@link IllegalArgumentException} of {@link IllegalStateException}: die
 * worden door {@link ApiExceptionHandler} als 400 afgehandeld.
 */
public class PermissionSourceUnavailableException extends RuntimeException {

    /** 503: de rechtenbron is (tijdelijk) onbereikbaar. */
    public static final String CODE = "PERMISSION_SOURCE_UNAVAILABLE";

    public PermissionSourceUnavailableException(String message) {
        super(message);
    }

    public PermissionSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
