package be.dda.catalogimport.service;

/**
 * De aanvraag botst met de huidige toestand van het systeem (configuratie, status, bestaande
 * levering). De Web-laag vertaalt dit naar HTTP 409; {@link #getCode()} is een stabiele,
 * machineleesbare code (bv. {@code TASK_RUN_IN_PROGRESS}) die in het antwoord terugkomt.
 */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
