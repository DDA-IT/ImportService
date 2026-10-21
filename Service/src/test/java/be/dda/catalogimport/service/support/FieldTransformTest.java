package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    // --- Legacy-config S8a: vertaaltabel uit import_field_value_map --------------------------------

    /** Dezelfde vertaling als {@code values=}, maar uit de rijen; ook tekens die {@code values=} niet kan dragen. */
    @Test
    void translatesThroughTheValueMapRowsIncludingCharactersValuesCannotExpress() {
        List<FieldTransform.MapEntry> rows = List.of(entry(1, "A;1", "ALFA|1"), entry(2, "B>2", "BRAVO;2"),
                entry(3, "LEEG", ""));

        assertThat(applyTable("caseSensitive=true", rows, "A;1")).isEqualTo("ALFA|1");
        assertThat(applyTable("caseSensitive=true", rows, "B>2")).isEqualTo("BRAVO;2");
        // Een lege doelwaarde is een vertaling naar leeg, geen ontbrekende vertaling.
        assertThat(applyTable("caseSensitive=true", rows, "LEEG")).isEmpty();
        // Ontbrekend blijft ontbrekend; de standaardwaarde (R-REC-03) kan nog gelden.
        assertThat(applyTable("caseSensitive=true", rows, null)).isNull();
    }

    /** R-REC-08 geldt ook voor de tabel: een miss verwerpt de regel en valt nooit terug op de bronwaarde. */
    @Test
    void aValueOutsideTheValueMapRejectsTheRowAndIsNeverPassedThrough() {
        List<FieldTransform.MapEntry> rows = List.of(entry(1, "a", "ALFA"));

        assertThat(applyTable(null, rows, "A")).isEqualTo("ALFA");
        assertThatThrownBy(() -> applyTable("caseSensitive=true", rows, "A"))
                .isInstanceOf(ImportValueException.class)
                .extracting(failure -> ((ImportValueException) failure).getCode())
                .isEqualTo(FieldTransform.CODE_MAPPING_VALUE_UNKNOWN);
    }

    /** Legacy CALC 7 met een grote PSIMPDEFMAP: 2000 sleutels, elke sleutel vertaalt, een onbekende niet. */
    @Test
    void translatesTwoThousandKeys() {
        List<FieldTransform.MapEntry> rows = new ArrayList<>();
        for (int i = 1; i <= 2000; i++) {
            rows.add(entry(i, "K" + i, "V" + i));
        }

        for (int i = 1; i <= 2000; i++) {
            assertThat(applyTable("caseSensitive=true", rows, "K" + i)).isEqualTo("V" + i);
        }
        assertThat(codeOfTable("caseSensitive=true", rows, "K2001"))
                .isEqualTo(FieldTransform.CODE_MAPPING_VALUE_UNKNOWN);
    }

    @Test
    void refusesAValueMapThatDoesNotFitTheTransformation() {
        List<FieldTransform.MapEntry> rows = List.of(entry(1, "A", "1"));

        // Beide tabellen, of de rijen bij een andere soort: nooit stil een van beide negeren.
        assertThat(tableConfigFailure(FieldTransformKind.MAP, "values=A>1", rows).getMessage())
                .endsWith("it has both values= and 1 value map row(s); a translation has exactly one table");
        assertThat(tableConfigFailure(FieldTransformKind.PREFIX, "prefix=X", rows).getMessage())
                .endsWith("it has 1 value map row(s) but transformation PREFIX does not use a translation table; "
                        + "they would silently have no effect");
        // Geen van beide: dezelfde melding als vóór S8a.
        assertThat(tableConfigFailure(FieldTransformKind.MAP, null, List.of()).getMessage())
                .endsWith("setting 'values' is required: a translation needs its table of source and target values");
    }

    @Test
    void refusesValueMapRowsThatWouldMakeTheTranslationAmbiguous() {
        ScreeningBlockedException caseConflict = tableConfigFailure(FieldTransformKind.MAP, null,
                List.of(entry(1, "ab", "1"), entry(2, "AB", "2")));
        assertThat(caseConflict.getCode()).isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(caseConflict.getSourceValue()).isEqualTo("AB");
        assertThat(caseConflict.getMessage()).endsWith("source values 'ab' and 'AB' differ only in case while "
                + "caseSensitive=false; which one translates would be undefined");
        // Hoofdlettergevoelig zijn het twee verschillende sleutels.
        assertThat(applyTable("caseSensitive=true", List.of(entry(1, "ab", "1"), entry(2, "AB", "2")), "AB"))
                .isEqualTo("2");
        // Teken per teken zoals equalsIgnoreCase: 'ß' en "ss" zijn geen hoofdlettervariant van elkaar.
        assertThat(applyTable(null, List.of(entry(1, "straße", "1"), entry(2, "STRASSE", "2")), "strasse"))
                .isEqualTo("2");

        assertThat(tableConfigFailure(FieldTransformKind.MAP, "caseSensitive=true",
                List.of(entry(1, "A", "1"), entry(2, "A", "2"))).getMessage())
                .endsWith("source value 'A' is translated more than once");
        assertThat(tableConfigFailure(FieldTransformKind.MAP, null, List.of(entry(7, " ", "1"))).getMessage())
                .endsWith("value map row 7 has no source value");
    }

    // --- Legacy-config S8d: MAP_CONTAINS (CALC 14) -----------------------------------------------

    /** Legacy {@code MapInstr}: de eerste rij (KEY-volgorde) waarvan de sleutel in de waarde voorkomt, wint. */
    @Test
    void mapContainsTakesTheFirstRowInSequenceWhoseKeyIsContainedCaseInsensitively() {
        List<FieldTransform.MapEntry> rows = List.of(entry(1, "ROOD", "R"), entry(2, "  groen ", "G"),
                entry(3, "RO", "X"));

        assertThat(applyContains(rows, "Donkerrood")).isEqualTo("R");
        assertThat(applyContains(rows, "licht GROEN metallic")).isEqualTo("G");
        // Beide 'ROOD' (volgorde 1) en 'RO' (volgorde 3) komen voor: de eerste in volgorde wint.
        assertThat(applyContains(rows, "rood")).isEqualTo("R");
        // Enkel 'RO' komt voor.
        assertThat(applyContains(rows, "Roze")).isEqualTo("X");
        assertThat(applyContains(rows, null)).isNull();
        // De volgorde is die van de rijen, niet de lengte of de alfabetische volgorde.
        assertThat(applyContains(List.of(entry(1, "RO", "X"), entry(2, "ROOD", "R")), "rood")).isEqualTo("X");
    }

    @Test
    void mapContainsRejectsAMissInsteadOfGivingAnEmptyValue() {
        List<FieldTransform.MapEntry> rows = List.of(entry(1, "ROOD", "R"));

        assertThatThrownBy(() -> applyContains(rows, "blauw"))
                .isInstanceOf(ImportValueException.class)
                .satisfies(failure -> {
                    assertThat(((ImportValueException) failure).getCode())
                            .isEqualTo(FieldTransform.CODE_MAPPING_VALUE_UNKNOWN);
                    assertThat(((ImportValueException) failure).getRawValue()).isEqualTo("blauw");
                });
        assertThatThrownBy(() -> applyContains(rows, "")).isInstanceOf(ImportValueException.class);
    }

    /** {@code Locale.ROOT}: onder een Turkse standaardtaal wordt "i" niet "İ" en blijft "ID" in "title" gevonden. */
    @Test
    void mapContainsIsIndependentOfTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(applyContains(List.of(entry(1, "TITLE", "T"), entry(2, "ILE", "I")), "title")).isEqualTo("T");
            assertThat(applyContains(List.of(entry(1, "iLE", "I")), "FILE")).isEqualTo("I");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void mapContainsRefusesRowsThatCanNeverBeTheResult() {
        // Geen rijen, lege doelwaarde, blanco sleutel, dubbele sleutel, een instelling die niet bestaat.
        assertThat(tableConfigFailure(FieldTransformKind.MAP_CONTAINS, null, List.of()).getMessage())
                .endsWith("a contains-translation needs its value map rows (import_field_value_map)");
        assertThat(tableConfigFailure(FieldTransformKind.MAP_CONTAINS, null, List.of(entry(4, "A", ""))).getMessage())
                .endsWith("value map row 4 of a contains-translation has no target value; a row with an empty target "
                        + "can never be the result");
        assertThat(tableConfigFailure(FieldTransformKind.MAP_CONTAINS, null, List.of(entry(2, " ", "1"))).getMessage())
                .endsWith("value map row 2 has no source value");
        assertThat(tableConfigFailure(FieldTransformKind.MAP_CONTAINS, null,
                List.of(entry(1, "A", "1"), entry(2, "A", "2"))).getMessage())
                .endsWith("source value 'A' is translated more than once");
        assertThat(tableConfigFailure(FieldTransformKind.MAP_CONTAINS, "values=A>1", List.of(entry(1, "A", "1")))
                .getMessage()).contains("values");
    }

    @Test
    void mapContainsIsAValueOperationOfAChainAndExcludesASecondTableStep() {
        List<FieldTransform.MapEntry> rows = List.of(entry(1, "ROOD", "R"), entry(2, "GROEN", "G"));
        List<FieldTransform.Step> steps = List.of(step(1, FieldTransformKind.SUBSTRING, "start=2;length=8"),
                step(2, FieldTransformKind.AFFIX, "prefix=d;suffix=!"), step(3, FieldTransformKind.MAP_CONTAINS, null));

        // "xrood zzzzz" -> MID(2,8) = "rood zzz" -> "drood zzz!" -> bevat "ROOD" -> "R"
        assertThat(applyChain(steps, rows, "xrood zzzzz")).isEqualTo("R");
        assertThat(applyChain(steps, rows, "   ")).isEmpty();
        assertThatThrownBy(() -> applyChain(steps, rows, "xblauw")).isInstanceOf(ImportValueException.class);

        // Hoogstens één tabelstap, en de tabelstap is altijd de laatste (plaats 3).
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.MAP, "caseSensitive=true"),
                step(2, FieldTransformKind.MAP_CONTAINS, null)), rows))
                .contains("step 2 (MAP_CONTAINS) cannot follow step 1 (MAP)");
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.MAP_CONTAINS, null),
                step(2, FieldTransformKind.UPPER, null)), rows))
                .contains("step 2 (UPPER) cannot follow step 1 (MAP_CONTAINS)");
        // Rijen zonder tabelstap blijven een fout; een instelling op de stap ook.
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.UPPER, null)), rows))
                .endsWith("it has 2 value map row(s) but no step of its chain uses a translation table; they would "
                        + "silently have no effect");
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.MAP_CONTAINS, "caseSensitive=true")), rows))
                .contains("step 1 (MAP_CONTAINS)");
    }

    @Test
    void mapContainsRowsAtAnyOtherKindAreAConfigurationError() {
        assertThat(tableConfigFailure(FieldTransformKind.UPPER, null, List.of(entry(1, "A", "1"))).getMessage())
                .endsWith("it has 1 value map row(s) but transformation UPPER does not use a translation table; "
                        + "they would silently have no effect");
    }

    private String applyContains(List<FieldTransform.MapEntry> rows, String value) {
        MappingSettings settings = MappingSettings.parse(FIELD, null);
        FieldTransform transform = FieldTransform.of(FieldTransformKind.MAP_CONTAINS, settings, rows, FIELD,
                SCALE_TWO);
        settings.verifyFullyUsed();
        return transform.apply(value, FIELD, columns);
    }

    // --- Legacy-config S8b: UPPER en SUBSTRING -----------------------------------------------------

    @Test
    void upperCasesLanguageIndependentlyAndLeavesAMissingValueMissing() {
        assertThat(apply(FieldTransformKind.UPPER, null, "abc-1")).isEqualTo("ABC-1");
        assertThat(apply(FieldTransformKind.UPPER, null, "")).isEmpty();
        assertThat(apply(FieldTransformKind.UPPER, null, null)).isNull();
        // Locale.ROOT: een Turkse i wordt nooit een i met punt, ongeacht de standaardtaal van de JVM.
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(apply(FieldTransformKind.UPPER, null, "title")).isEqualTo("TITLE");
        } finally {
            java.util.Locale.setDefault(previous);
        }
        assertThat(configCodeOf(FieldTransformKind.UPPER, "prefix=X"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
    }

    @Test
    void takesASubstringFromAOneBasedStartWithAnOptionalLength() {
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=2;length=3", "ABCDEF")).isEqualTo("BCD");
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=3", "ABCDEF")).isEqualTo("CDEF");
        assertThat(apply(FieldTransformKind.SUBSTRING, "length=2", "ABCDEF")).isEqualTo("AB");
        assertThat(apply(FieldTransformKind.SUBSTRING, null, "ABCDEF")).isEqualTo("ABCDEF");
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=1;length=500", "ABCDEF")).isEqualTo("ABCDEF");
        // Grenzen: laatste teken, lengte voorbij het einde, start precies op/voorbij het einde.
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=6;length=1", "ABCDEF")).isEqualTo("F");
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=5;length=99", "ABCDEF")).isEqualTo("EF");
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=7", "ABCDEF")).isEmpty();
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=50;length=2", "ABCDEF")).isEmpty();
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=2", "")).isEmpty();
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=2;length=2147483647", "ABCDEF")).isEqualTo("BCDEF");
        // Ontbrekend blijft ontbrekend.
        assertThat(apply(FieldTransformKind.SUBSTRING, "start=2", null)).isNull();
    }

    @Test
    void refusesAnInvalidSubstringConfiguration() {
        for (String config : List.of("start=0", "start=-1", "length=0", "length=-3", "start=x", "length=1,5",
                "start=2;stop=3")) {
            assertThat(configCodeOf(FieldTransformKind.SUBSTRING, config)).as(config)
                    .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        }
    }

    // --- Legacy-config S8c: AFFIX en de keten ------------------------------------------------------

    /** AFFIX volgt legacy: {@code Trim(prefix) & Trim(v) & Trim(suffix)} enkel als de getrimde waarde niet leeg is. */
    @Test
    void anAffixFollowsLegacyAndNeverBuildsAValueAroundAnEmptyCell() {
        assertThat(apply(FieldTransformKind.AFFIX, "prefix=ART-;suffix=-NL", "007")).isEqualTo("ART-007-NL");
        assertThat(apply(FieldTransformKind.AFFIX, "prefix=ART-;suffix=-NL", "  007  ")).isEqualTo("ART-007-NL");
        assertThat(apply(FieldTransformKind.AFFIX, "prefix= ART- ", "007")).isEqualTo("ART-007");
        assertThat(apply(FieldTransformKind.AFFIX, "suffix=-NL", "007")).isEqualTo("007-NL");
        assertThat(apply(FieldTransformKind.AFFIX, "prefix=ART-;suffix=-NL", "")).isEmpty();
        assertThat(apply(FieldTransformKind.AFFIX, "prefix=ART-;suffix=-NL", "    ")).isEmpty();
        assertThat(apply(FieldTransformKind.AFFIX, "prefix=ART-", null)).isNull();
        // PREFIX/SUFFIX blijven ongewijzigd: die zetten hun tekst ook rond een lege waarde.
        assertThat(apply(FieldTransformKind.PREFIX, "prefix=ART-", "")).isEqualTo("ART-");
        assertThat(configCodeOf(FieldTransformKind.AFFIX, null)).isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.AFFIX, "prefix=;suffix="))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.AFFIX, "prefix=X;index=2"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
    }

    /**
     * Legacy CALC 7 op een regel met prefix en suffix ({@code Prg_1170} r.730, r.1053): de opzoeking gebeurt op
     * {@code Trim(prefix) & Trim(v) & Trim(suffix)}. Een lege cel blijft leeg (legacy: geen sleutel {@code ''}, dus
     * {@code ''}); een onbekende sleutel verwerpt de regel (R-REC-08, tot V1/S8g).
     */
    @Test
    void aChainOfPrefixSuffixAndMapTranslatesLikeLegacy() {
        List<FieldTransform.Step> steps = List.of(step(1, FieldTransformKind.AFFIX, "prefix=A-;suffix=-Z"),
                step(2, FieldTransformKind.MAP, "caseSensitive=true"));
        List<FieldTransform.MapEntry> rows = List.of(entry(1, "A-1-Z", "EEN"), entry(2, "A-2-Z", "TWEE"));

        assertThat(applyChain(steps, rows, "1")).isEqualTo("EEN");
        assertThat(applyChain(steps, rows, "  2   ")).isEqualTo("TWEE");
        assertThat(applyChain(steps, rows, "")).isEmpty();
        assertThat(applyChain(steps, rows, "   ")).isEmpty();
        assertThat(applyChain(steps, rows, null)).isNull();
        assertThatThrownBy(() -> applyChain(steps, rows, "3"))
                .isInstanceOf(ImportValueException.class)
                .satisfies(failure -> {
                    assertThat(((ImportValueException) failure).getCode())
                            .isEqualTo(FieldTransform.CODE_MAPPING_VALUE_UNKNOWN);
                    assertThat(((ImportValueException) failure).getRawValue()).isEqualTo("A-3-Z");
                });
        // values= in de MAP-stap werkt ook (zonder rijen).
        assertThat(applyChain(List.of(step(1, FieldTransformKind.AFFIX, "suffix=X"),
                step(2, FieldTransformKind.MAP, "values=7X>ZEVEN")), List.of(), "7")).isEqualTo("ZEVEN");
    }

    /**
     * Substring op de <b>ruwe</b> waarde (legacy {@code MID} op het record), dan trimmen, dan prefix/suffix, dan de
     * waardebewerking. Opvulspaties tellen dus mee voor de startpositie, en vallen daarna weg.
     */
    @Test
    void aChainOfSubstringAffixAndUpperFollowsTheLegacyOrder() {
        List<FieldTransform.Step> steps = List.of(step(1, FieldTransformKind.SUBSTRING, "start=3;length=4"),
                step(2, FieldTransformKind.AFFIX, "prefix=x-"), step(3, FieldTransformKind.UPPER, null));

        assertThat(applyChain(steps, List.of(), "abcdefgh")).isEqualTo("X-CDEF");
        // Twee voorloopspaties: MID(v, 3, 4) neemt "abcd" (zoals legacy op het ruwe record).
        assertThat(applyChain(steps, List.of(), "  abcdefgh")).isEqualTo("X-ABCD");
        // Achterloopspaties binnen het deel vallen weg na de substring.
        assertThat(applyChain(steps, List.of(), "abcd    ")).isEqualTo("X-CD");
        // Een deel dat enkel uit spaties bestaat is leeg: prefix en hoofdletters worden overgeslagen.
        assertThat(applyChain(steps, List.of(), "ab    gh")).isEmpty();
        // Start voorbij het einde: "" (S8b), ook in de keten.
        assertThat(applyChain(steps, List.of(), "ab")).isEmpty();
        assertThat(applyChain(steps, List.of(), null)).isNull();
    }

    /**
     * Een lege cel blijft leeg, ook voor een rekenkundige stap: legacy rekent {@code Val('')=0 ± x}, hier geen verzonnen
     * getal (verklaard verschil). Een niet-lege waarde rekent zoals de losse soort.
     */
    @Test
    void anEmptyInputStaysEmptyAndSkipsEveryStep() {
        List<FieldTransform.Step> add = List.of(step(1, FieldTransformKind.ADD, "operand=1,50"));
        assertThat(applyChain(add, List.of(), "")).isEmpty();
        assertThat(applyChain(add, List.of(), "   ")).isEmpty();
        assertThat(applyChain(add, List.of(), " 10,00 ")).isEqualTo("11.50");
        assertThat(applyChain(List.of(step(1, FieldTransformKind.DIVIDE, "operandField=NUL")), List.of(), " "))
                .isEmpty();
        assertThat(applyChain(List.of(step(1, FieldTransformKind.PREFIX, "prefix=ART-")), List.of(), "  "))
                .isEmpty();
        assertThat(applyChain(List.of(step(1, FieldTransformKind.SPLIT, "separator=-;index=2")), List.of(),
                " ART-007 ")).isEqualTo("007");
        // Een mislukte stap blijft een mislukte regel, nooit een lege waarde.
        assertThat(codeOfChain(List.of(step(1, FieldTransformKind.AFFIX, "suffix=%"),
                step(2, FieldTransformKind.MULTIPLY, "operand=2")), "8")).isEqualTo(FieldTransform.CODE_TRANSFORM_FAILED);
    }

    /** De gesloten grammatica: elke overtreding is {@code CONFIG_TRANSFORM_INVALID}, met een herkenbare melding. */
    @Test
    void refusesAChainOutsideTheClosedGrammar() {
        assertThat(chainFailure(List.of(), List.of())).endsWith("a chain needs 1 to 3 steps but has 0");
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.SUBSTRING, null),
                step(2, FieldTransformKind.AFFIX, "prefix=X"), step(3, FieldTransformKind.UPPER, null),
                step(4, FieldTransformKind.MAP, "values=A>B")), List.of())).endsWith("a chain needs 1 to 3 steps but has 4");
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.AFFIX, "prefix=X"),
                step(3, FieldTransformKind.UPPER, null)), List.of()))
                .endsWith("the steps of a chain are numbered 1, 2, 3 without gaps, but step 2 has number 3");
        for (FieldTransformKind never : List.of(FieldTransformKind.NONE, FieldTransformKind.FIXED_VALUE,
                FieldTransformKind.CONCAT, FieldTransformKind.CHAIN)) {
            assertThat(chainFailure(List.of(step(1, never, null)), List.of())).as(never.name())
                    .contains("step 1 (" + never + ") can never be a step of a chain; a chain holds at most SUBSTRING");
        }
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.UPPER, null),
                step(2, FieldTransformKind.SUBSTRING, null)), List.of()))
                .contains("step 2 (SUBSTRING) cannot follow step 1 (UPPER)");
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.AFFIX, "prefix=X"),
                step(2, FieldTransformKind.SUFFIX, "suffix=Y")), List.of()))
                .contains("step 2 (SUFFIX) cannot follow step 1 (AFFIX)");
        // Hoogstens één waardebewerking, dus ook hoogstens één tabelstap.
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.MAP, "caseSensitive=true"),
                step(2, FieldTransformKind.UPPER, null)), List.of(entry(1, "A", "1"))))
                .contains("step 2 (UPPER) cannot follow step 1 (MAP)");
        assertThat(chainFailure(List.of(step(1, null, null)), List.of()))
                .endsWith("step 1 of the chain has no transform kind");
    }

    /** De configuratie van elke stap volgt de regels van haar soort; de melding noemt de stap. */
    @Test
    void refusesAStepWhoseOwnConfigurationIsInvalid() {
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.AFFIX, null)), List.of()))
                .endsWith("has an invalid transform configuration: step 1 (AFFIX): an affix transformation needs "
                        + "prefix=<text>, suffix=<text> or both");
        // De notatie hoort bij de mapping, nooit bij een stap.
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.ADD, "operand=1;decimalSeparator=,")), List.of()))
                .endsWith("step 1 (ADD): setting 'decimalSeparator' is not used by this transformation or data type; "
                        + "it would silently have no effect");
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.SUBSTRING, "start=0")), List.of()))
                .endsWith("step 1 (SUBSTRING): setting 'start' must be 1 or higher");
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.MAP, "values=A>1")), List.of(entry(1, "B", "2"))))
                .endsWith("step 1 (MAP): it has both values= and 1 value map row(s); a translation has exactly one table");
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.MAP, null)), List.of()))
                .endsWith("step 1 (MAP): setting 'values' is required: a translation needs its table of source and "
                        + "target values");
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.AFFIX, "prefix")), List.of()))
                .endsWith("step 1 (AFFIX): setting 'prefix' is not of the form key=value");
        // Vertaalrijen zonder MAP-stap zouden stil zonder effect blijven.
        assertThat(chainFailure(List.of(step(1, FieldTransformKind.UPPER, null)), List.of(entry(1, "A", "1"))))
                .endsWith("it has 1 value map row(s) but no step of its chain uses a translation table; they would "
                        + "silently have no effect");
    }

    /** Stappen horen enkel bij een CHAIN; een CHAIN zonder de stappen-overload heeft er geen. */
    @Test
    void stepsOnlyBelongToAChain() {
        ScreeningBlockedException onPrefix = assertBlocked(() -> FieldTransform.of(FieldTransformKind.PREFIX,
                MappingSettings.parse(FIELD, "prefix=X"), List.of(), List.of(step(1, FieldTransformKind.UPPER, null)),
                FIELD, SCALE_TWO));
        assertThat(onPrefix.getMessage()).endsWith("it has 1 transformation step(s) but transformation PREFIX is not "
                + "a CHAIN; they would silently have no effect");
        assertThat(configCodeOf(FieldTransformKind.CHAIN, null)).isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        // De mapping zelf draagt bij een CHAIN geen transformatie-instelling: die blijft ongelezen en is dus een fout.
        assertBlocked(() -> {
            MappingSettings settings = MappingSettings.parse(FIELD, "prefix=X");
            FieldTransform.of(FieldTransformKind.CHAIN, settings, List.of(),
                    List.of(step(1, FieldTransformKind.UPPER, null)), FIELD, SCALE_TWO);
            settings.verifyFullyUsed();
        });
    }

    // --- Legacy-config S8g: onMiss=KEEP_SOURCE (beslissing V1 = B) ----------------------------------

    /** Opt-in: de bronwaarde gaat door en wordt per regel gemeld; een treffer en REJECT blijven ongewijzigd. */
    @Test
    void keepSourcePassesAMissThroughAndReportsItWhileRejectStillRejects() {
        List<FieldTransform.MapEntry> rows = List.of(entry(1, "A", "ALFA"));

        assertThat(applyTable("caseSensitive=true;onMiss=KEEP_SOURCE", rows, "A")).isEqualTo("ALFA");
        assertThat(columns.passed).isEmpty();
        assertThat(applyTable("caseSensitive=true;onMiss=KEEP_SOURCE", rows, "Z9")).isEqualTo("Z9");
        assertThat(columns.passed).containsExactly(FIELD + ":Z9");
        // Ontbrekend blijft ontbrekend, zonder melding.
        assertThat(applyTable("onMiss=KEEP_SOURCE", rows, null)).isNull();
        assertThat(columns.passed).hasSize(1);

        assertThat(codeOfTable("caseSensitive=true;onMiss=REJECT", rows, "Z9"))
                .isEqualTo(FieldTransform.CODE_MAPPING_VALUE_UNKNOWN);
        assertThat(codeOfTable("caseSensitive=true", rows, "Z9")).isEqualTo(FieldTransform.CODE_MAPPING_VALUE_UNKNOWN);
        assertThat(columns.passed).hasSize(1);
    }

    @Test
    void keepSourceWorksWithTheInlineValuesToo() {
        assertThat(apply(FieldTransformKind.MAP, "values=A>ALFA;onMiss=KEEP_SOURCE", "Q")).isEqualTo("Q");
        assertThat(columns.passed).containsExactly(FIELD + ":Q");
        assertThat(codeOf(FieldTransformKind.MAP, "values=A>ALFA;onMiss=REJECT", "Q"))
                .isEqualTo(FieldTransform.CODE_MAPPING_VALUE_UNKNOWN);
    }

    /** Alleen MAP kent onMiss; elke andere soort (ook MAP_CONTAINS) en een onbekende waarde is een configuratiefout. */
    @Test
    void onMissIsOnlyValidOnMapAndOnlyWithAKnownValue() {
        assertThat(configCodeOf(FieldTransformKind.MAP, "values=A>1;onMiss=SKIP"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.MAP, "values=A>1;onMiss=keep_source"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.PREFIX, "prefix=X;onMiss=KEEP_SOURCE"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(configCodeOf(FieldTransformKind.UPPER, "onMiss=KEEP_SOURCE"))
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
        assertThat(tableConfigFailure(FieldTransformKind.MAP_CONTAINS, "onMiss=KEEP_SOURCE",
                List.of(entry(1, "ROOD", "R"))).getMessage()).contains("setting 'onMiss' is not used");
    }

    /** In een keten krijgt de MAP-stap de instelling; de invoer van die stap gaat door, hier na substring en affix. */
    @Test
    void keepSourceInAChainStepPassesTheInputOfThatStepThrough() {
        List<FieldTransform.MapEntry> rows = List.of(entry(1, "P-A", "ALFA"));
        List<FieldTransform.Step> steps = List.of(step(1, FieldTransformKind.SUBSTRING, "start=2"),
                step(2, FieldTransformKind.AFFIX, "prefix=P-"), step(3, FieldTransformKind.MAP, "onMiss=KEEP_SOURCE"));

        assertThat(applyChain(steps, rows, "XA")).isEqualTo("ALFA");
        assertThat(columns.passed).isEmpty();
        assertThat(applyChain(steps, rows, "XB")).isEqualTo("P-B");
        assertThat(columns.passed).containsExactly(FIELD + ":P-B");
        // Zonder opt-in verwerpt de MAP-stap.
        List<FieldTransform.Step> rejecting = List.of(step(1, FieldTransformKind.MAP, null));
        assertThatThrownBy(() -> applyChain(rejecting, rows, "XB")).isInstanceOf(ImportValueException.class);
        // De keten zelf draagt de instelling niet.
        assertBlocked(() -> {
            MappingSettings settings = MappingSettings.parse(FIELD, "onMiss=KEEP_SOURCE");
            FieldTransform.of(FieldTransformKind.CHAIN, settings, rows, rejecting, FIELD, SCALE_TWO);
            settings.verifyFullyUsed();
        });
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private static FieldTransform.Step step(int number, FieldTransformKind kind, String config) {
        return new FieldTransform.Step(number, kind, config);
    }

    private String applyChain(List<FieldTransform.Step> steps, List<FieldTransform.MapEntry> rows, String value) {
        MappingSettings settings = MappingSettings.parse(FIELD, null);
        FieldTransform transform = FieldTransform.of(FieldTransformKind.CHAIN, settings, rows, steps, FIELD, SCALE_TWO);
        settings.verifyFullyUsed();
        return transform.apply(value, FIELD, columns);
    }

    private String codeOfChain(List<FieldTransform.Step> steps, String value) {
        try {
            applyChain(steps, List.of(), value);
            throw new AssertionError("expected the row to be rejected");
        } catch (ImportValueException rejected) {
            return rejected.getCode();
        }
    }

    private static String chainFailure(List<FieldTransform.Step> steps, List<FieldTransform.MapEntry> rows) {
        return assertBlocked(() -> FieldTransform.of(FieldTransformKind.CHAIN, MappingSettings.parse(FIELD, null),
                rows, steps, FIELD, SCALE_TWO)).getMessage();
    }

    private static ScreeningBlockedException assertBlocked(Runnable configuration) {
        try {
            configuration.run();
            throw new AssertionError("expected the configuration to block the delivery");
        } catch (ScreeningBlockedException blocked) {
            assertThat(blocked.getCode()).isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
            assertThat(blocked.getFieldName()).isEqualTo(FIELD);
            return blocked;
        }
    }

    private static FieldTransform.MapEntry entry(int sequence, String source, String target) {
        return new FieldTransform.MapEntry(sequence, source, target);
    }

    private String applyTable(String config, List<FieldTransform.MapEntry> rows, String value) {
        MappingSettings settings = MappingSettings.parse(FIELD, config);
        FieldTransform transform = FieldTransform.of(FieldTransformKind.MAP, settings, rows, FIELD, SCALE_TWO);
        settings.verifyFullyUsed();
        return transform.apply(value, FIELD, columns);
    }

    private String codeOfTable(String config, List<FieldTransform.MapEntry> rows, String value) {
        try {
            applyTable(config, rows, value);
            throw new AssertionError("expected the row to be rejected");
        } catch (ImportValueException rejected) {
            return rejected.getCode();
        }
    }

    private static ScreeningBlockedException tableConfigFailure(FieldTransformKind kind, String config,
                                                                List<FieldTransform.MapEntry> rows) {
        try {
            MappingSettings settings = MappingSettings.parse(FIELD, config);
            FieldTransform.of(kind, settings, rows, FIELD, SCALE_TWO);
            settings.verifyFullyUsed();
            throw new AssertionError("expected the configuration to block the delivery");
        } catch (ScreeningBlockedException blocked) {
            assertThat(blocked.getCode()).isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
            return blocked;
        }
    }

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
        /** Elke doorgelaten tabelmiss als {@code veld:waarde} (S8g). */
        private final List<String> passed = new ArrayList<>();

        @Override
        public void passedThrough(String fieldName, String value, String message) {
            assertThat(message).contains("onMiss=KEEP_SOURCE");
            passed.add(fieldName + ":" + value);
        }

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
