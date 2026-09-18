package be.dda.catalogimport.service.support;

/**
 * De levering als geheel is onbruikbaar: een contract-, structuur- of configuratiefout (design
 * par. 9). De batch gaat naar {@code BLOCKED} met deze {@link #getCode() code} in
 * {@code import_batch.blocked_code} en de boodschap in {@code blocked_reason}; er worden geen
 * inhoudelijke mutaties geschreven.
 * <p>
 * Dit is uitdrukkelijk <b>geen technische fout</b>: een I/O-, encoding- of databasefout levert
 * {@code FAILED} op (geen marker, staging opgeruimd), terwijl een geblokkeerde batch een geldig,
 * verklaarbaar eindresultaat is dat de gebruiker moet zien.
 * <p>
 * De code mag langer zijn dan {@code blocked_code} (varchar(60)) — bijvoorbeeld
 * {@code HEADER_FIELD_MISSING:<veld>} uit design par. 8; de schrijvende laag kapt af en bewaart de
 * volledige tekst in {@code blocked_reason}.
 */
public class ScreeningBlockedException extends RuntimeException {

    private final String code;

    public ScreeningBlockedException(String code, String reason) {
        super(reason);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
