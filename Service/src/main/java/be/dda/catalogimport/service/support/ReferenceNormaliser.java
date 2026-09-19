package be.dda.catalogimport.service.support;

/**
 * Normaliseert de waarde van één kritieke koppelreferentie (EAN, PIM-ID, CAB-ID,
 * {@code E_MARK+ARTICLE_REFERENCE}) tot haar vergelijkingswaarde (R-REF-01). Pure klasse: geen
 * Spring, geen database, geen tijd- of omgevingsafhankelijkheid — dezelfde bronwaarde levert altijd
 * dezelfde vergelijkingswaarde op, wat nodig is omdat die waarde in {@code catalog_reference_state}
 * en in de referentievingerafdruk terechtkomt.
 *
 * <h2>Wat hier wél gebeurt (businessanalyse par. 14.23.7, "Normatieve normalisatie van CAB-/PIM-ID")</h2>
 * <ul>
 *   <li>de <b>ruwe</b> waarde blijft bewaard naast de genormaliseerde; deze klasse levert enkel de
 *       vergelijkingswaarde en wijzigt nooit wat er in {@code value_raw} terechtkomt;</li>
 *   <li>trim aan de <b>buitenkant</b>;</li>
 *   <li>verwijdering van <b>onzichtbare</b> tekens: stuurtekens (Unicode-categorie {@code Cc}) en
 *       formattekens ({@code Cf}: zero-width space, zachte koppelteken, BOM, ...). Die dragen in een
 *       EAN, PIM-ID of CAB-ID nooit betekenis en zouden anders per bronbestand een ander
 *       vergelijkingsresultaat geven.</li>
 * </ul>
 *
 * <h2>Wat hier nooit gebeurt</h2>
 * Hoofdletterconversie, prefix- of suffixverwijdering, <b>leading-zeroverwijdering</b>,
 * tekenvervanging en numerieke interpretatie zijn volgens par. 14.23.7 uitdrukkelijk <b>geen</b>
 * algemene regels. {@code 000123}, {@code 123} en {@code AB-123} blijven daarom drie verschillende
 * kritieke referenties. Zulke regels mogen alleen per referentietype in een expliciete, versieerbare
 * normalisatieregel gezet worden; zo'n regel bestaat in dit schema nog niet, en zonder die regel is
 * een verschil volgens par. 14.23.7 een kritiek identiteitsincident — nooit stilzwijgend "hetzelfde".
 *
 * <h2>Leeg is geen waarde</h2>
 * Een bronwaarde die na normalisatie leeg is, levert {@code null} op: dat is "gemapt maar leeg"
 * (R-REF-03) en wordt bewust <b>niet</b> als lege tekst vergeleken. Een niet-gemapt veld komt hier
 * helemaal niet langs — daarover wordt geen uitspraak gedaan.
 */
public final class ReferenceNormaliser {

    private ReferenceNormaliser() {
    }

    /**
     * @param rawValue de waarde zoals ze na mapping en transformatie uit de bron komt; mag
     *                 {@code null} zijn
     * @return de vergelijkingswaarde, of {@code null} wanneer de leverancier geen waarde meegaf
     */
    public static String normalise(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        StringBuilder visible = new StringBuilder(rawValue.length());
        for (int index = 0; index < rawValue.length(); ) {
            int codePoint = rawValue.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isInvisible(codePoint)) {
                continue;
            }
            visible.appendCodePoint(codePoint);
        }
        // Trim pas ná het weglaten van de onzichtbare tekens: een waarde die enkel uit spaties en een
        // zero-width space bestaat, is leeg en niet "een waarde met een spatie".
        String normalised = visible.toString().trim();
        return normalised.isEmpty() ? null : normalised;
    }

    /** Is deze waarde gemapt maar leeg (R-REF-03)? */
    public static boolean isEmptyValue(String rawValue) {
        return normalise(rawValue) == null;
    }

    /**
     * Onzichtbare tekens: de C0/C1-stuurtekens en de Unicode-formattekens. Betekenisvolle tekens —
     * letters, cijfers, streepjes, punten, spaties binnenin — blijven staan (par. 14.23.3: de
     * normalisatie mag geen betekenisvolle tekens stil verwijderen of vervangen).
     */
    private static boolean isInvisible(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT;
    }
}
