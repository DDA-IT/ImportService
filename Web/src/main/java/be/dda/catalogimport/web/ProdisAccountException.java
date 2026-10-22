package be.dda.catalogimport.web;

/**
 * Een mislukte call naar Prodis' {@code /api/account} met een veilige, korte reden (nooit token, body of
 * gebruikersgegevens). {@link Kind} maakt het onderscheid dat de rechtenadapter (5B-7b, V4) nodig heeft.
 */
final class ProdisAccountException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    enum Kind {
        /** Token verloren: geweigerde refresh of geen geautoriseerde client in de sessie. */
        TOKEN_LOST,
        /** Al het overige: timeout, verbinding, 3xx/4xx/5xx, te groot, geen (geldige) JSON. */
        OTHER
    }

    private final Kind kind;

    private ProdisAccountException(Kind kind, String reason) {
        super(reason, null, false, false);
        this.kind = kind;
    }

    static ProdisAccountException tokenLost(String reason) {
        return new ProdisAccountException(Kind.TOKEN_LOST, reason);
    }

    static ProdisAccountException other(String reason) {
        return new ProdisAccountException(Kind.OTHER, reason);
    }

    Kind kind() {
        return kind;
    }
}
