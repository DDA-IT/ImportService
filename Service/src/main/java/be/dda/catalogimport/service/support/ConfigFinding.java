package be.dda.catalogimport.service.support;

/**
 * Eén configuratiefout uit een volledige controle ({@link ConfigProblemCollector.Mode#ALL}), NT-14a
 * par. 1 en 3.
 * <p>
 * De eerste vijf velden zijn letterlijk die van de {@link ScreeningBlockedException} die de fabriek
 * gooit — dezelfde code, hetzelfde veld, dezelfde waarden en dezelfde tekst; er wordt niets
 * herschreven. {@link #revisionField()} is nieuw en bestaat enkel in dit resultaat: de sleutel van de
 * revisiekolom (woordenlijstdomein {@code revisionField}, bv. {@code delimiter},
 * {@code priceDerivationTolerance}) waar een fout in de bronstructuur op slaat. Zo zijn bv. de twee
 * betekenissen van {@code CONFIG_PRICE_FIELD_MISSING} en de drie identiteitskolommen van elkaar te
 * onderscheiden zonder de bestaande {@code fieldName} (en dus de opgeslagen issuerij van de
 * screening) te wijzigen.
 *
 * @param revisionField de revisiekolom, of {@code null} bij een fout op een mapping-, filter- of
 *                      kritiekrij of op een gegeven buiten de revisie
 */
public record ConfigFinding(String code, String fieldName, String sourceValue, String expectedValue,
                            String message, String revisionField) {

    /** Neemt de gegevens van de worp letterlijk over. */
    public static ConfigFinding of(ScreeningBlockedException blocked, String revisionField) {
        return new ConfigFinding(blocked.getCode(), blocked.getFieldName(), blocked.getSourceValue(),
                blocked.getExpectedValue(), blocked.getMessage(), revisionField);
    }
}
