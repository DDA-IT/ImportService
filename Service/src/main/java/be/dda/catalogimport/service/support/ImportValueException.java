package be.dda.catalogimport.service.support;

/**
 * Eén bronwaarde is onbruikbaar. De {@link #getCode() code} is exact de {@code issue_code} die in
 * {@code import_row_issue} terechtkomt (design par. 5 en par. 8), zodat een regelfout nooit als
 * anonieme {@code IllegalArgumentException} eindigt en nooit stil een nul of lege waarde oplevert.
 * <p>
 * <b>Businessgedrag.</b> Deze fout verwerpt uitsluitend de betrokken bronregel; de batch blijft
 * doorlopen (design par. 9, aanname A4). Een fout die de volledige levering onbruikbaar maakt is
 * een {@link ScreeningBlockedException}, geen {@code ImportValueException}.
 * <p>
 * {@link #getRawValue()} is de ongewijzigde bronwaarde; de aanroeper kapt ze af voor
 * {@code import_row_issue.source_value} (varchar(200)).
 */
public class ImportValueException extends RuntimeException {

    private final String code;
    private final String field;
    private final String rawValue;

    public ImportValueException(String code, String field, String rawValue, String message) {
        super(message);
        this.code = code;
        this.field = field;
        this.rawValue = rawValue;
    }

    /** Stabiele, machineleesbare code; wordt {@code import_row_issue.issue_code}. */
    public String getCode() {
        return code;
    }

    /** Het betrokken bronveld (headernaam of kolomindex), of {@code null} als dat niet bekend is. */
    public String getField() {
        return field;
    }

    /** De ongewijzigde bronwaarde, of {@code null}. */
    public String getRawValue() {
        return rawValue;
    }
}
