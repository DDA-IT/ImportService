package be.dda.catalogimport.service;

/**
 * De aanvraagbody is groter dan toegelaten. De Web-laag vertaalt dit naar HTTP 413; {@link #getCode()} is een
 * stabiele, machineleesbare code (bv. {@code LEGACY_BUNDLE_TOO_LARGE}). Additief naast {@link BadRequestException}
 * (400), {@link NotFoundException} (404) en {@link ConflictException} (409); de bestaande multipartlimiet blijft via
 * {@code UPLOAD_TOO_LARGE} lopen.
 */
public class PayloadTooLargeException extends RuntimeException {

    private final String code;

    public PayloadTooLargeException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
