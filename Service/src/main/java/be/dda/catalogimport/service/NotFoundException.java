package be.dda.catalogimport.service;

/**
 * Een gevraagde resource bestaat niet. De Web-laag vertaalt dit naar HTTP 404; {@link #getCode()}
 * is een stabiele, machineleesbare code (bv. {@code TASK_NOT_FOUND}) die in het antwoord terugkomt.
 */
public class NotFoundException extends RuntimeException {

    private final String code;

    public NotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
