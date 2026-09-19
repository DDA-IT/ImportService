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
 * <p>
 * Sinds fase 3 (ontwerp par. 3.3) schrijft elke blokkade ook één issuerij. {@link #getFieldName()},
 * {@link #getSourceValue()} en {@link #getExpectedValue()} dragen daarvoor de losse gegevens die
 * anders alleen in de vrije tekst zouden zitten: wat er gevonden werd naast wat er verwacht werd.
 * Ze zijn optioneel ({@code null}) — de melding blijft leidend en wordt nooit stil aangepast.
 */
public class ScreeningBlockedException extends RuntimeException {

    private final String code;
    private final String fieldName;
    private final String sourceValue;
    private final String expectedValue;

    public ScreeningBlockedException(String code, String reason) {
        this(code, null, null, null, reason);
    }

    public ScreeningBlockedException(String code, String fieldName, String sourceValue,
                                     String expectedValue, String reason) {
        super(reason);
        this.code = code;
        this.fieldName = fieldName;
        this.sourceValue = sourceValue;
        this.expectedValue = expectedValue;
    }

    public String getCode() {
        return code;
    }

    /** Het logische bronveld waar de blokkade op slaat, of {@code null}. */
    public String getFieldName() {
        return fieldName;
    }

    /** De aangetroffen waarde, of {@code null} wanneer die niet één waarde is. */
    public String getSourceValue() {
        return sourceValue;
    }

    /** De verwachte waarde, of {@code null}. */
    public String getExpectedValue() {
        return expectedValue;
    }
}
