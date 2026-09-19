package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fase 3c (ontwerp fase 3, R-REC-07/R-REC-08): elke toegelaten bewerking op een gemapte bronwaarde,
 * en wat er gebeurt als ze niet kan slagen.
 * <p>
 * Unittest zonder Spring: een transformatie mag nooit van een database, een tijdstip of een omgeving
 * afhangen — haar resultaat komt in een vingerafdruk terecht en moet dus altijd identiek zijn.
 */
class FieldTransformTest {

    private static final String FIELD = "Leveranciersnummer";
    private static final DecimalFormat SCALE_TWO = new DecimalFormat(2, null, null);

    private final Columns columns = new Columns()
            .with("LEV", "ACME")
            .with("REF", "R-1")
            .with("BASIS", "200,00")
            .with("NUL", "0")
            .with("LEEG", null);

    // --- Normaal scenario per soort ---------------------------------------------------------------

    @Test
    void appliesEveryKindOfTransformationToItsSourceValue() {
        assertThat(apply(FieldTransformKind.NONE, null, "007")).isEqualTo("007");
        assertThat(apply(FieldTransformKind.FIXED_VALUE, "value=VROOAM", "007")).isEqualTo("VROOAM");
        assertThat(apply(FieldTransformKind.PREFIX, "prefix=ART-", "007")).isEqualTo("ART-007");
        assertThat(apply(FieldTransformKind.SUFFIX, "suffix=-NL", "007")).isEqualTo("007-NL");
        assertThat(apply(FieldTransformKind.CONCAT, "sources=LEV|REF;separator=-", null)).isEqualTo("ACME-R-1");
        assertThat(apply(FieldTransformKind.SPLIT, "separator=-;index=2", "ART-007")).isEqualTo("007");
        assertThat(apply(FieldTransformKind.MAP, "values=A>ALFA|B>BRAVO", "B")).isEqualTo("BRAVO");
        assertThat(apply(FieldTransformKind.ADD, "operand=1,50", "10,00")).isEqualTo("11.50");
        assertThat(apply(FieldTransformKind.SUBTRACT, "operand=1,50", "10,00")).isEqualTo("8.50");
        assertThat(apply(FieldTransformKind.MULTIPLY, "operand=1,21", "10,00")).isEqualTo("12.10");
        assertThat(apply(FieldTransformKind.DIVIDE, "operand=4", "10,00")).isEqualTo("2.50");
        // 50,00 van een basis van 200,00 is 25%.
        assertThat(apply(FieldTransformKind.PERCENTAGE, "baseField=BASIS", "50,00")).isEqualTo("25.00");
    }

    @Test
    void readsTheSecondOperandFromAnotherColumnWhenConfiguredThatWay() {
        assertThat(apply(FieldTransformKind.MULTIPLY, "operandField=BASIS", "0,50")).isEqualTo("100.00");
    }

    /** Hoofdletterongevoelig is de standaard; hoofdlettergevoelig is een bewuste instelling. */
    @Test
    void comparesTheTranslationTableCaseInsensitivelyUnlessConfiguredOtherwise() {
        assertThat(apply(FieldTransformKind.MAP, "values=a>ALFA", "A")).isEqualTo("ALFA");

        assertThatThrownBy(() -> apply(FieldTransformKind.MAP, "values=a>ALFA;caseSensitive=true", "A"))
                .isInstanceOf(ImportValueException.class)
                .extracting(failure -> ((ImportValueException) failure).getCode())
                .isEqualTo(FieldTransform.CODE_MAPPING_VALUE_UNKNOWN);
    }

    // --- Ontbrekende bronwaarde --------------------------------------------------------------------

    /**
     * Een werkelijk ontbrekende bronwaarde blijft ontbrekend: de bewerking wordt overgeslagen, zodat
     * de standaardwaarde van R-REC-03 nog kan gelden. Anders zou een prefix van een lege waarde een
     * bestaande doelwaarde ("ART-") suggereren die de leverancier nooit gestuurd heeft.
     */
    @Test
    void leavesAMissingSourceValueMissingInsteadOfBuildingAValueAroundIt() {
        assertThat(apply(FieldTransformKind.PREFIX, "prefix=ART-", null)).isNull();
        assertThat(apply(FieldTransformKind.SPLIT, "separator=-;index=1", null)).isNull();
        assertThat(apply(FieldTransformKind.MAP, "values=A>ALFA", null)).isNull();
        assertThat(apply(FieldTransformKind.ADD, "operand=1", null)).isNull();
        // Een vaste waarde en een samenvoeging bouwen hun waarde zélf op en hebben geen bron nodig.
        assertThat(apply(FieldTransformKind.FIXED_VALUE, "value=VROOAM", null)).isEqualTo("VROOAM");
        assertThat(apply(FieldTransformKind.CONCAT, "sources=LEV|LEEG;separator=-", null)).isEqualTo("ACME-");
    }

    // --- Mislukte bewerkingen ----------------------------------------------------------------------

    @Test
    void rejectsTheRowWhenADivisionByZeroWouldHappen() {
        assertThat(codeOf(FieldTransformKind.DIVIDE, "operandField=NUL", "10,00"))
                .isEqualTo(FieldTransform.CODE_TRANSFORM_DIVIDE_BY_ZERO);
        assertThat(codeOf(FieldTransformKind.PERCENTAGE, "baseField=NUL", "10,00"))
                .isEqualTo(FieldTransform.CODE_TRANSFORM_DIVIDE_BY_ZERO);
    }

    @Test
    void rejectsTheRowWhenTheTransformationCannotBeCarriedOut() {
        // Onderdeel 3 van een waarde met twee onderdelen bestaat niet.
        assertThat(codeOf(FieldTransformKind.SPLIT, "separator=-;index=3", "ART-007"))
                .isEqualTo(FieldTransform.CODE_TRANSFORM_FAILED);
        // Rekenen met een onleesbaar getal is een mislukte bewerking, geen stille 0.
        assertThat(codeOf(FieldTransformKind.ADD, "operand=1", "tien"))
                .isEqualTo(FieldTransform.CODE_TRANSFORM_FAILED);
        // Een kolom die de definitie belooft maar die dit bestand niet heeft.
        assertThat(codeOf(FieldTransformKind.MULTIPLY, "operandField=ONBEKEND", "10,00"))
                .isEqualTo(FieldTransform.CODE_TRANSFORM_FAILED);
        assertThat(codeOf(FieldTransformKind.CONCAT, "sources=LEV|ONBEKEND", null))
                .isEqualTo(FieldTransform.CODE_TRANSFORM_FAILED);
    }

    // --- Configuratie ------------------------------------------------------------------------------

    @Test
    void refusesAConfigurationThatCannotProduceAResult() {
        assertThat(configCodeOf(FieldTransformKind.PREFIX, null))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.SPLIT, "separator=-;index=x"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.MAP, "values=A>1|A>2"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.ADD, "operand=tien"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.PERCENTAGE, "base=0"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.NONE, "prefix"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private String apply(FieldTransformKind kind, String config, String value) {
        MappingSettings settings = MappingSettings.parse(FIELD, config);
        FieldTransform transform = FieldTransform.of(kind, settings, FIELD, SCALE_TWO);
        settings.verifyFullyUsed();
        return transform.apply(value, FIELD, columns);
    }

    private String codeOf(FieldTransformKind kind, String config, String value) {
        try {
            apply(kind, config, value);
            throw new AssertionError("expected the row to be rejected");
        } catch (ImportValueException rejected) {
            return rejected.getCode();
        }
    }

    private String configCodeOf(FieldTransformKind kind, String config) {
        try {
            MappingSettings settings = MappingSettings.parse(FIELD, config);
            FieldTransform.of(kind, settings, FIELD, SCALE_TWO);
            settings.verifyFullyUsed();
            throw new AssertionError("expected the configuration to block the delivery");
        } catch (ScreeningBlockedException blocked) {
            return blocked.getCode();
        }
    }

    /** De overige kolommen van dezelfde bronregel. */
    private static final class Columns implements FieldTransform.SourceValues {

        private final Map<String, String> values = new LinkedHashMap<>();

        private Columns with(String reference, String value) {
            values.put(reference, value);
            return this;
        }

        @Override
        public boolean hasColumn(String reference) {
            return values.containsKey(reference);
        }

        @Override
        public String value(String reference) {
            return values.get(reference);
        }
    }
}
