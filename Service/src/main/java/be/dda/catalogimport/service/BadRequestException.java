package be.dda.catalogimport.service;

/**
 * De aanvraag zelf deugt niet, los van de toestand van het systeem. De Web-laag vertaalt dit naar
 * HTTP 400; {@link #getCode()} is een stabiele, machineleesbare code (bv.
 * {@code DECISION_FILTER_REQUIRED}) die in het antwoord terugkomt.
 * <p>
 * Bouwstap 4d, additief naast {@link ConflictException} (409) en {@link NotFoundException} (404). Een
 * gewone {@link IllegalArgumentException} blijft bestaan en blijft 400 zonder code opleveren: dit type
 * is bedoeld voor de enkele 400 waar het ontwerp een <b>benoemde</b> code voorschrijft, zodat een
 * aanroeper "je filter is leeg" kan onderscheiden van elke andere ongeldige invoer zonder op de
 * foutboodschap te moeten parsen.
 */
public class BadRequestException extends IllegalArgumentException {

    private final String code;

    public BadRequestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
