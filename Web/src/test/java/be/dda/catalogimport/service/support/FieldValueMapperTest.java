package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.domain.FieldOwner;
import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityClass;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.FieldValueMapper.MappedRecord;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Fase 3c (ontwerp fase 3, R-REC-01..R-REC-08): de recordvalidatie van de gemapte doelvelden.
 * <p>
 * Unittest zonder Spring en zonder database. De mapping wordt wél door de echte
 * {@link ImportMappingConfigFactory} gebouwd: de transformatie- en notatieconfiguratie wordt in
 * productie exact één keer per batch geparsed, en een test die dat overslaat zou een configuratie
 * kunnen bewijzen die een beheerder nooit ingevoerd krijgt.
 * <p>
 * <b>Wat hier telkens bewezen wordt.</b> Een onbruikbare waarde verwerpt de <b>regel</b> met een
 * expliciete foutcode; ze wordt nooit stil 0, leeg, afgekapt of geraden. Een fout in de definitie zelf
 * blokkeert de <b>levering</b>.
 */
class FieldValueMapperTest {

    private static final String ARTICLE_COLUMN = "ARTIKEL";
    private static final String AMOUNT_COLUMN = "BEDRAG";
    private static final String DATE_COLUMN = "VANAF";
    private static final String FLAG_COLUMN = "ACTIEF";

    private final FieldValueMapper mapper = new FieldValueMapper();
    private final ImportMappingConfigFactory factory = new ImportMappingConfigFactory(null, null);

    // --- R-REC-01: identificerende tekst blijft tekst ---------------------------------------------

    @Test
    void keepsLeadingZeroesLengthAndCaseOfAnIdentifyingTextField() {
        ImportMappingConfig config = config(text("E_SUPPLIER", ARTICLE_COLUMN, IdentityClass.SUPPORTING,
                mapping -> {
                }));

        MappedRecord mapped = mapper.map(row("  007-aB  ", "", "", ""), config);

        // Enkel de buitenste spaties verdwijnen (R-REC-09): geen case-folding, geen nullen weg.
        assertThat(mapped.value("E_SUPPLIER")).isEqualTo("007-aB");
    }

    @Test
    void rejectsARowWhoseIdentifyingFieldIsDeclaredAsANumber() {
        ImportFieldCatalogEntry target = new ImportFieldCatalogEntry("E_SUPPLIER", "Leveranciersnummer",
                FieldDataType.INTEGER, FieldOwner.CATALOG_SOURCE, IdentityClass.SUPPORTING, 130);
        ImportMappingConfig config = config(mapping(target, ARTICLE_COLUMN, mapping -> {
        }));

        assertThatThrownBy(() -> mapper.map(row("007", "", "", ""), config))
                .isInstanceOf(ImportValueException.class)
                .extracting(failure -> ((ImportValueException) failure).getCode())
                .isEqualTo(FieldValueMapper.CODE_VALUE_TYPE_MISMATCH);
    }

    // --- R-REC-02 en R-REC-03: verplicht, leeg en standaardwaarde ---------------------------------

    @Test
    void rejectsARowWhoseRequiredFieldIsEmptyInsteadOfApplyingTheDefault() {
        ImportMappingConfig config = config(text("E_SUPPLIER", ARTICLE_COLUMN, IdentityClass.NONE, mapping -> {
            mapping.setRequired(true);
            mapping.setDefaultValue("ONBEKEND");
        }));

        assertThatThrownBy(() -> mapper.map(row("   ", "", "", ""), config))
                .isInstanceOf(ImportValueException.class)
                .extracting(failure -> ((ImportValueException) failure).getCode())
                .isEqualTo(FieldValueMapper.CODE_VALUE_MISSING);
    }

    /**
     * Een expliciet lege bronwaarde is een uitspraak van de leverancier ("dit veld is leeg") en krijgt
     * nooit de standaardwaarde; enkel een werkelijk ontbrekende waarde krijgt die wél, en dan met een
     * informatieve melding.
     */
    @Test
    void appliesTheDefaultOnlyToATrulyMissingValueAndNeverToAnEmptyOne() {
        ImportMappingConfig config = config(text("E_SUPPLIER", ARTICLE_COLUMN, IdentityClass.NONE,
                mapping -> mapping.setDefaultValue("ONBEKEND")));

        MappedRecord empty = mapper.map(row("", "", "", ""), config);
        MappedRecord absent = mapper.map(rowWithoutValues(), config);

        assertThat(empty.value("E_SUPPLIER")).isEmpty();
        assertThat(empty.notices()).isEmpty();
        assertThat(absent.value("E_SUPPLIER")).isEqualTo("ONBEKEND");
        assertThat(absent.notices()).singleElement().satisfies(notice -> {
            assertThat(notice.code()).isEqualTo(FieldValueMapper.CODE_VALUE_DEFAULT_APPLIED);
            assertThat(notice.fieldName()).isEqualTo("Externe leveranciersidentiteit");
            assertThat(notice.message()).contains("ONBEKEND");
        });
    }

    // --- R-REC-06: te lang wordt verworpen, nooit afgekapt ----------------------------------------

    @Test
    void rejectsAValueThatIsLongerThanTheDeclaredMaximumInsteadOfTruncatingIt() {
        ImportMappingConfig config = config(text("E_SUPPLIER", ARTICLE_COLUMN, IdentityClass.NONE,
                mapping -> mapping.setMaxLength(5)));

        assertThatThrownBy(() -> mapper.map(row("123456", "", "", ""), config))
                .isInstanceOf(ImportValueException.class)
                .extracting(failure -> ((ImportValueException) failure).getCode())
                .isEqualTo(FieldValueMapper.CODE_VALUE_TOO_LONG);
        // Grensgeval: exact de maximale lengte is toegelaten.
        assertThat(mapper.map(row("12345", "", "", ""), config).value("E_SUPPLIER")).isEqualTo("12345");
    }

    // --- R-REC-04: decimalen ----------------------------------------------------------------------

    @Test
    void readsADecimalWithTheDeclaredSeparatorAndScale() {
        ImportMappingConfig config = config(decimal(2, "decimalSeparator=,;groupingSeparator=."));

        assertThat(mapper.map(row("", "1.234,50", "", ""), config).value("E_AMOUNT")).isEqualTo("1234.50");
    }

    @Test
    void rejectsADecimalThatUsesAnotherSeparatorThanTheDeclaredOne() {
        ImportMappingConfig config = config(decimal(2, "decimalSeparator=,"));

        // 1.234 mag nooit stil 1234 of 1,234 worden: zonder verklaarde duizendtalscheiding is dit
        // onleesbaar, geen interpretatiekwestie.
        assertThatThrownBy(() -> mapper.map(row("", "1.234", "", ""), config))
                .isInstanceOf(ImportValueException.class)
                .extracting(failure -> ((ImportValueException) failure).getCode())
                .isEqualTo(FieldValueMapper.CODE_VALUE_TYPE_MISMATCH);
    }

    @Test
    void rejectsAnUnreadableOrTooPreciseDecimalWithoutEverStoringZero() {
        ImportMappingConfig config = config(decimal(2, null));

        assertThat(codeOf(config, row("", "12,3x", "", ""))).isEqualTo(FieldValueMapper.CODE_VALUE_TYPE_MISMATCH);
        assertThat(codeOf(config, row("", "12,345", "", ""))).isEqualTo(FieldValueMapper.CODE_VALUE_TYPE_MISMATCH);
        // Grensgeval: exact de verklaarde schaal blijft geldig.
        assertThat(mapper.map(row("", "12,34", "", ""), config).value("E_AMOUNT")).isEqualTo("12.34");
    }

    /** Een leeg decimaal veld blijft leeg: nul, ontbrekend en leeg zijn drie verschillende toestanden. */
    @Test
    void leavesAnEmptyDecimalEmptyInsteadOfTurningItIntoZero() {
        ImportMappingConfig config = config(decimal(2, null));

        assertThat(mapper.map(row("", "", "", ""), config).value("E_AMOUNT")).isEmpty();
        assertThat(mapper.map(rowWithoutValues(), config).value("E_AMOUNT")).isNull();
    }

    // --- R-REC-05: datums -------------------------------------------------------------------------

    @Test
    void readsADateInTheDeclaredSourceFormat() {
        ImportMappingConfig config = config(date("dateFormat=dd/MM/yyyy"));

        assertThat(mapper.map(row("", "", "01/02/2026", ""), config).value("E_DATE")).isEqualTo("2026-02-01");
    }

    @Test
    void rejectsADateThatDoesNotExistInsteadOfShiftingItToTheEndOfTheMonth() {
        ImportMappingConfig config = config(date("dateFormat=dd/MM/yyyy"));

        assertThat(codeOf(config, row("", "", "31/02/2026", "")))
                .isEqualTo(FieldValueMapper.CODE_DATE_UNREADABLE);
    }

    @Test
    void refusesToGuessTheDayAndMonthOrderOfADateWithoutADeclaredFormat() {
        ImportMappingConfig config = config(date(null));

        assertThat(codeOf(config, row("", "", "01/02/2026", "")))
                .isEqualTo(FieldValueMapper.CODE_DATE_AMBIGUOUS);
        // Een onmiskenbare ISO-datum is wél eenduidig en wordt aanvaard.
        assertThat(mapper.map(row("", "", "2026-02-01", ""), config).value("E_DATE")).isEqualTo("2026-02-01");
    }

    // --- R-REC-07 en R-REC-08: transformaties -----------------------------------------------------

    @Test
    void appliesAPrefixTransformationToTheSourceValue() {
        ImportMappingConfig config = config(text("E_SUPPLIER", ARTICLE_COLUMN, IdentityClass.NONE, mapping -> {
            mapping.setTransformKind(FieldTransformKind.PREFIX);
            mapping.setTransformConfig("prefix=ART-");
        }));

        assertThat(mapper.map(row("007", "", "", ""), config).value("E_SUPPLIER")).isEqualTo("ART-007");
    }

    @Test
    void rejectsARowWhoseSourceValueIsNotInTheTranslationTable() {
        ImportMappingConfig config = config(text("E_SUPPLIER", ARTICLE_COLUMN, IdentityClass.NONE, mapping -> {
            mapping.setTransformKind(FieldTransformKind.MAP);
            mapping.setTransformConfig("values=A>ALFA|B>BRAVO");
        }));

        assertThat(mapper.map(row("A", "", "", ""), config).value("E_SUPPLIER")).isEqualTo("ALFA");
        // Legacygedrag (de bronwaarde doorlaten) is uitdrukkelijk verboden.
        assertThat(codeOf(config, row("Z", "", "", "")))
                .isEqualTo(FieldTransform.CODE_MAPPING_VALUE_UNKNOWN);
    }

    @Test
    void rejectsARowWhoseTransformationDividesByZero() {
        ImportMappingConfig config = config(decimal(2, "operandField=" + ARTICLE_COLUMN, mapping ->
                mapping.setTransformKind(FieldTransformKind.DIVIDE)));

        assertThat(codeOf(config, row("0", "10,00", "", "")))
                .isEqualTo(FieldTransform.CODE_TRANSFORM_DIVIDE_BY_ZERO);
        assertThat(mapper.map(row("4", "10,00", "", ""), config).value("E_AMOUNT")).isEqualTo("2.50");
    }

    // --- Andere soorten bronwaarde -----------------------------------------------------------------

    @Test
    void readsAFixedValueWithoutLookingAtTheSource() {
        ImportMappingConfig config = config(text("E_SUPPLIER", null, IdentityClass.NONE, mapping -> {
            mapping.setValueKind(FieldValueKind.FIXED_VALUE);
            mapping.setFixedValue("VROOAM");
        }));

        assertThat(mapper.map(row("ANDERS", "", "", ""), config).value("E_SUPPLIER")).isEqualTo("VROOAM");
    }

    /**
     * Een sjabloonwaarde (bookmark) bestaat al in het schema maar kan nog niet ingevuld worden. Dat
     * blokkeert de levering; een leeg doelveld zou op een bewuste blanco lijken.
     */
    @Test
    void blocksTheDeliveryWhenABookmarkHasNoFilledInValue() {
        ImportFieldCatalogEntry target = catalogEntry("E_SUPPLIER", FieldDataType.TEXT, IdentityClass.NONE);
        ImportFieldMapping bookmark = new ImportFieldMapping(null, 1, target, FieldValueKind.BOOKMARK,
                FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE, IdentityClass.NONE);
        bookmark.setBookmarkName("BESTANDS_PREFIX");
        // De factory weigert deze mapping al; de mapper mag er evenmin een lege waarde van maken.
        ImportMappingConfig config = new ImportMappingConfig(2,
                List.of(new ImportMappingConfig.FieldMapping(1, "E_SUPPLIER", "Externe leveranciersidentiteit",
                        FieldValueKind.BOOKMARK, null, null, null, "BESTANDS_PREFIX", null,
                        FieldDataType.TEXT, false, null, null, false, false, FieldTransformKind.NONE, null,
                        new FieldTransform.Unchanged(), ImportMappingConfig.ValueFormat.DEFAULT,
                        FieldOwner.CATALOG_SOURCE, IdentityClass.NONE, null, null)),
                List.of());

        assertThatThrownBy(() -> mapper.map(row("x", "", "", ""), config))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_MAPPING_SOURCE_UNRESOLVED);
    }

    @Test
    void readsABooleanOnlyInItsAcceptedNotations() {
        ImportMappingConfig config = config(mapping(
                catalogEntry("E_FLAG", FieldDataType.BOOLEAN, IdentityClass.NONE), FLAG_COLUMN, mapping -> {
                }));

        assertThat(mapper.map(row("", "", "", "1"), config).value("E_FLAG")).isEqualTo("true");
        assertThat(mapper.map(row("", "", "", "false"), config).value("E_FLAG")).isEqualTo("false");
        assertThat(codeOf(config, row("", "", "", "misschien")))
                .isEqualTo(FieldValueMapper.CODE_VALUE_TYPE_MISMATCH);
    }

    @Test
    void mapsNothingForARevisionWithoutFieldMappings() {
        MappedRecord mapped = mapper.map(row("x", "", "", ""), new ImportMappingConfig(1, List.of(), List.of()));

        assertThat(mapped.values()).isEmpty();
        assertThat(mapped.notices()).isEmpty();
        assertThat(mapper.map(row("x", "", "", ""), null).values()).isEmpty();
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private String codeOf(ImportMappingConfig config, ParsedRow row) {
        try {
            mapper.map(row, config);
            throw new AssertionError("expected the row to be rejected");
        } catch (ImportValueException rejected) {
            return rejected.getCode();
        }
    }

    private ImportMappingConfig config(ImportFieldMapping... mappings) {
        ImportDefinitionRevision revision = revision();
        List<ImportFieldMapping> rows = new ArrayList<>();
        for (ImportFieldMapping mapping : mappings) {
            mapping.setSequenceNumber(rows.size() + 1);
            rows.add(mapping);
        }
        return factory.from(revision, structure(), rows, List.of());
    }

    private static ImportFieldMapping text(String code, String sourceReference, IdentityClass identityClass,
                                           Consumer<ImportFieldMapping> customiser) {
        return mapping(catalogEntry(code, FieldDataType.TEXT, identityClass), sourceReference, customiser);
    }

    private static ImportFieldMapping decimal(int scale, String transformConfig) {
        return decimal(scale, transformConfig, mapping -> {
        });
    }

    private static ImportFieldMapping decimal(int scale, String transformConfig,
                                              Consumer<ImportFieldMapping> customiser) {
        return mapping(catalogEntry("E_AMOUNT", FieldDataType.DECIMAL, IdentityClass.NONE), AMOUNT_COLUMN,
                mapping -> {
                    mapping.setDecimalScale(scale);
                    mapping.setTransformConfig(transformConfig);
                    customiser.accept(mapping);
                });
    }

    private static ImportFieldMapping date(String transformConfig) {
        return mapping(catalogEntry("E_DATE", FieldDataType.DATE, IdentityClass.NONE), DATE_COLUMN,
                mapping -> mapping.setTransformConfig(transformConfig));
    }

    private static ImportFieldMapping mapping(ImportFieldCatalogEntry target, String sourceReference,
                                              Consumer<ImportFieldMapping> customiser) {
        ImportFieldMapping mapping = new ImportFieldMapping(null, 1, target, FieldValueKind.SOURCE_FIELD,
                target.getDataType(), target.getDefaultOwner(), target.getIdentityClass());
        mapping.setSourceReference(sourceReference);
        customiser.accept(mapping);
        return mapping;
    }

    private static ImportFieldCatalogEntry catalogEntry(String code, FieldDataType dataType,
                                                        IdentityClass identityClass) {
        return new ImportFieldCatalogEntry(code, "Externe leveranciersidentiteit", dataType,
                FieldOwner.CATALOG_SOURCE, identityClass, 130);
    }

    /** Vier kolommen: artikel, bedrag, datum, vlag. */
    private static ParsedRow row(String article, String amount, String date, String flag) {
        return new ParsedRow(12, Arrays.asList(article, amount, date, flag), positions());
    }

    /**
     * Dezelfde kolommen, maar zonder waarden in deze regel: zo ziet een <b>werkelijk ontbrekende</b>
     * bronwaarde eruit. In een CSV met een vast kolomaantal komt dat zelden voor (zo'n regel wordt al
     * bij het parsen verworpen); bij een bron met optionele elementen is het de normale toestand.
     */
    private static ParsedRow rowWithoutValues() {
        return new ParsedRow(12, List.of(), positions());
    }

    private static SourceFieldPositions positions() {
        Map<String, Integer> positions = new LinkedHashMap<>();
        positions.put(ARTICLE_COLUMN, 0);
        positions.put(AMOUNT_COLUMN, 1);
        positions.put(DATE_COLUMN, 2);
        positions.put(FLAG_COLUMN, 3);
        return new SourceFieldPositions(positions);
    }

    private static SourceStructureConfig structure() {
        return new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"', true, 1,
                FieldReferenceKind.HEADER_NAME, null, IdentityProfileKind.THREE_PART, "LEVERANCIER",
                "GROEP", "REFERENTIE", null, "PRIJS", "OMSCHRIJVING", 2);
    }

    private static ImportDefinitionRevision revision() {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setRecordCanonicalisationVersion(2);
        return revision;
    }
}
