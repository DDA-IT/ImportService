package be.dda.catalogimport.service;

/**
 * De stabiele uitkomstcodes van verbinden, hostsleutel, aanmelden en map lijsten bij een externe bron
 * ({@code docs/design/leveringsconfiguratie-design.md} par. 4.4 en 5; bouwstap K-4a). Dezelfde codes zijn sinds K-4b
 * ook {@code task_run.outcome_code} van een ophaalrun, aangevuld met de uitkomsten en download-codes van die run.
 */
public final class FetchOutcomeCodes {

    /** Geslaagd (verbindingstest en hostsleutelscan). */
    public static final String OK = "OK";

    // --- Ophaalrun (K-4b, design par. 4.4) --------------------------------------------------------------------

    /** Ophaalrun: één bestand opgehaald en als levering geregistreerd; de screening sluit de run af. */
    public static final String FETCHED = "FETCHED";
    /** Ophaalrun: geen match, of niets nieuws/ophaalbaars; de run is {@code COMPLETED} zonder levering. */
    public static final String NO_NEW_FILE = "NO_NEW_FILE";
    /** Ophaalrun: tijdens het streamen groter dan de maximale grootte (byte-guard); niets geregistreerd. */
    public static final String FETCH_FILE_TOO_LARGE = "FETCH_FILE_TOO_LARGE";
    /** Ophaalrun: grootte of wijzigingstijd na de download anders dan in de listing (of het bestand verdween). */
    public static final String FETCH_FILE_CHANGED_DURING_TRANSFER = "FETCH_FILE_CHANGED_DURING_TRANSFER";
    /** Ophaalrun: de overdracht brak af of leverde een ander aantal bytes dan de (ongewijzigde) grootte. */
    public static final String FETCH_TRANSFER_INCOMPLETE = "FETCH_TRANSFER_INCOMPLETE";
    /**
     * Ophaalrun: een onverwachte interne fout (bv. het archief kon niet geschreven worden). Geen remote fout: het
     * antwoord is dan een 500, maar de run staat wel op {@code FAILED} en blokkeert de taak niet.
     */
    public static final String FETCH_INTERNAL_ERROR = "FETCH_INTERNAL_ERROR";
    /**
     * Ophaalrun (K-4c): bleef {@code RUNNING} zonder levering langer dan {@code catalogimport.fetch.stuck-after} en werd
     * bij het opstarten automatisch afgesloten ({@code FAILED}).
     */
    public static final String FETCH_TIMED_OUT = "FETCH_TIMED_OUT";
    /** Ophaalrun (K-4c): expliciet afgebroken via {@code POST /task-runs/{id}/abort} ({@code FAILED}). */
    public static final String FETCH_MANUALLY_ABORTED = "FETCH_MANUALLY_ABORTED";

    /** Naam niet te resolven, verbinding geweigerd/onbereikbaar, time-out, geen gemeenschappelijk algoritme, protocolfout. */
    public static final String CONNECTION_FAILED = "CONNECTION_FAILED";
    /** De server toonde een andere hostsleutel dan de vastgepinde (L5); er is dan niet aangemeld. */
    public static final String SFTP_HOST_KEY_MISMATCH = "SFTP_HOST_KEY_MISMATCH";
    /** De server weigerde login en wachtwoord. */
    public static final String SFTP_AUTHENTICATION_FAILED = "SFTP_AUTHENTICATION_FAILED";
    /** De credential van de profielversie is ingetrokken; er is niet verbonden. */
    public static final String CREDENTIAL_REVOKED = "CREDENTIAL_REVOKED";
    /** De opgeslagen waarde kan niet ontsleuteld worden (V5); nooit stil overgeslagen. */
    public static final String CREDENTIAL_UNDECRYPTABLE = CredentialUndecryptableException.CODE;
    /** De externe map bestaat niet of is geen map. */
    public static final String REMOTE_DIRECTORY_NOT_FOUND = "REMOTE_DIRECTORY_NOT_FOUND";
    /** De server weigert de map te lezen. */
    public static final String REMOTE_PERMISSION_DENIED = "REMOTE_PERMISSION_DENIED";
    /** De map bevat meer ingangen dan de listing-cap (A12, A18): nooit stil afgekapt. */
    public static final String FETCH_LISTING_TOO_LARGE = "FETCH_LISTING_TOO_LARGE";
    /** De host staat niet in de allowlist of resolvet naar een verboden adres (L4b). */
    public static final String FETCH_HOST_NOT_ALLOWED = "FETCH_HOST_NOT_ALLOWED";
    /** 404: {@code catalogimport.fetch.allowed-hosts} is niet gezet; ophalen, scan en test bestaan dan niet (L4b). */
    public static final String FETCH_NOT_CONFIGURED = "FETCH_NOT_CONFIGURED";

    private FetchOutcomeCodes() {
    }
}
