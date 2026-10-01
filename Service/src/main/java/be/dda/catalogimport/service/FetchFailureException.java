package be.dda.catalogimport.service;

/**
 * Een mislukte verbinding, hostsleutelcontrole, aanmelding of maplijst bij een externe bron (bouwstap K-4a,
 * {@code docs/design/leveringsconfiguratie-design.md} par. 4.4 en 5). Geen HTTP-fout: de aanroeper (verbindingstest,
 * later de ophaalrun) maakt er een uitkomst met {@link #getCode()} van.
 * <p>
 * <b>Lekpreventie.</b> De boodschap is altijd een vaste tekst van deze applicatie: nooit een secret, nooit de
 * serverbanner en nooit de boodschap van een onderliggende library-exceptie. Daarom is er bewust <b>geen</b>
 * oorzaak-keten ({@code cause}); de aanroeper logt hoogstens het type van de oorzaak.
 */
public class FetchFailureException extends RuntimeException {

    private final String code;
    /** De SHA-256-vingerafdruk die de server toonde, als die al bekend was; anders {@code null}. */
    private final String presentedHostKeyFingerprint;

    public FetchFailureException(String code, String message) {
        this(code, message, null);
    }

    public FetchFailureException(String code, String message, String presentedHostKeyFingerprint) {
        super(message, null, false, false);
        this.code = code;
        this.presentedHostKeyFingerprint = presentedHostKeyFingerprint;
    }

    public String getCode() {
        return code;
    }

    public String getPresentedHostKeyFingerprint() {
        return presentedHostKeyFingerprint;
    }

    /** Dezelfde fout, aangevuld met de getoonde vingerafdruk (als die er nog niet in zat). */
    FetchFailureException withPresentedHostKeyFingerprint(String fingerprint) {
        if (presentedHostKeyFingerprint != null || fingerprint == null) {
            return this;
        }
        return new FetchFailureException(code, getMessage(), fingerprint);
    }
}
