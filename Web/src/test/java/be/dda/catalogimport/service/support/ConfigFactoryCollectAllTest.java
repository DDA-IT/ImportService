package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.domain.FieldOwner;
import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.FilterStage;
import be.dda.catalogimport.domain.IdentityClass;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.domain.ImportRevisionFieldCriticality;
import be.dda.catalogimport.domain.MissingColumnBehaviour;
import be.dda.catalogimport.domain.PriceControlModel;
import be.dda.catalogimport.domain.RevisionCriticalityField;
import be.dda.catalogimport.service.support.ConfigProblemCollector.Outcome;
import be.dda.catalogimport.service.support.ImportMappingConfig.FieldMapping;
import be.dda.catalogimport.service.support.ImportMappingConfig.RecordFilter;
import be.dda.catalogimport.service.support.ImportMappingConfig.ValueFormat;
import be.dda.catalogimport.service.support.ImportValueRules.DecimalFormat;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * NT-14-1: de volledige controle ({@link ConfigProblemCollector.Mode#ALL}) van
 * {@link SourceStructureConfigFactory} en {@link ImportMappingConfigFactory}
 * (docs/design/configfouten-alle-tegelijk-design.md par. 2 en 5).
 * <p>
 * Deel 1 is het invariantbewijs: voor een eigen corpus van mutatoren (enkelvoudig en gecombineerd)
 * is de eerste bevinding exact de worp van de gewone stand (code, fieldName, sourceValue,
 * expectedValue en boodschap), en is er zonder worp geen enkele bevinding. De golden table
 * ({@code ConfigFactoryParityTest}) blijft ongewijzigd en bewijst de gewone stand zelf.
 * <p>
 * Deel 2 legt het gedrag vast dat enkel in de volledige controle bestaat: meerdere bevindingen,
 * afhankelijke controles die als overgeslagen gemeld worden, en de revisiekolom per bevinding.
 */
class ConfigFactoryCollectAllTest {

    private static final SourceStructureConfigFactory STRUCTURE = new SourceStructureConfigFactory();
    private static final ImportMappingConfigFactory MAPPING = new ImportMappingConfigFactory(null, null);

    private static final String FORMAT = "CONFIG_FORMAT_UNSUPPORTED";
    private static final String DELIM_INVALID = "CONFIG_DELIMITER_INVALID";
    private static final String QUOTE = "CONFIG_QUOTE_INVALID";
    private static final String REF_KIND = "CONFIG_FIELD_REFERENCE_KIND_INVALID";
    private static final String IDENTITY_MISSING = "CONFIG_IDENTITY_FIELD_MISSING";
    private static final String PRICE_MISSING = "CONFIG_PRICE_FIELD_MISSING";
    private static final String COLUMN_COUNT = "CONFIG_COLUMN_COUNT_INVALID";
    private static final String FIELD_REF = "CONFIG_FIELD_REFERENCE_INVALID";
    private static final String CANON_UNSUPPORTED = "CONFIG_CANONICALISATION_VERSION_UNSUPPORTED";
    private static final String SOURCE_UNRESOLVED = "CONFIG_MAPPING_SOURCE_UNRESOLVED";
    private static final String TYPE_INCOMPATIBLE = "CONFIG_MAPPING_TYPE_INCOMPATIBLE";
    private static final String IDENTITY_CLASS = "CONFIG_IDENTITY_CLASS_CONFLICT";
    private static final String TRANSFORM = "CONFIG_TRANSFORM_INVALID";
    private static final String FILTER = "CONFIG_FILTER_INVALID";
    private static final String CANON_REQUIRED = "CONFIG_CANONICALISATION_VERSION_REQUIRED";
    private static final String CRITICALITY = "CONFIG_FIELD_CRITICALITY_INVALID";
    private static final String PRICE_MODEL = "CONFIG_PRICE_CONTROL_MODEL_UNSUPPORTED";

    // --- Deel 1: invariant ----------------------------------------------------------------------

    private static final Map<String, Consumer<Fixture>> CORPUS = corpus();

    @TestFactory
    Stream<DynamicTest> firstFindingIsExactlyTheFirstThrow() {
        return CORPUS.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(),
                () -> assertInvariant(entry.getKey(), entry.getValue())));
    }

    @Test
    void corpusIsLargeEnoughAndContainsCombinations() {
        assertThat(CORPUS).hasSizeGreaterThanOrEqualTo(30);
        assertThat(CORPUS.keySet().stream().filter(id -> id.startsWith("COMBI-"))).hasSizeGreaterThanOrEqualTo(10);
        assertThat(CORPUS.keySet().stream().filter(id -> id.startsWith("OK-"))).isNotEmpty();
    }

    private static void assertInvariant(String id, Consumer<Fixture> mutator) {
        Fixture forFirst = new Fixture();
        mutator.accept(forFirst);
        ScreeningBlockedException thrown = firstThrow(forFirst);

        Fixture forAll = new Fixture();
        mutator.accept(forAll);
        ConfigCheckReport report = collectAll(forAll);

        if (thrown == null) {
            assertThat(report.findings()).as("%s: no throw means no finding", id).isEmpty();
            return;
        }
        assertThat(report.findings()).as("%s: a throw means at least one finding", id).isNotEmpty();
        ConfigFinding first = report.findings().get(0);
        assertThat(first.code()).as("%s code", id).isEqualTo(thrown.getCode());
        assertThat(first.fieldName()).as("%s fieldName", id).isEqualTo(thrown.getFieldName());
        assertThat(first.sourceValue()).as("%s sourceValue", id).isEqualTo(thrown.getSourceValue());
        assertThat(first.expectedValue()).as("%s expectedValue", id).isEqualTo(thrown.getExpectedValue());
        assertThat(first.message()).as("%s message", id).isEqualTo(thrown.getMessage());
    }

    /** De gewone stand, zoals een aanroeper de fabrieken gebruikt: eerst de structuur, dan de mapping. */
    private static ScreeningBlockedException firstThrow(Fixture f) {
        try {
            SourceStructureConfig structure = f.structure != null ? f.structure
                    : STRUCTURE.from(f.revision, f.linkCurrency);
            MAPPING.from(f.revision, structure, f.mappings, f.filters, f.criticalities);
            return null;
        } catch (ScreeningBlockedException blocked) {
            return blocked;
        }
    }

    /** De volledige controle: structuur en mapping in één gedeelde collector. */
    private static ConfigCheckReport collectAll(Fixture f) {
        if (f.structure != null) {
            return MAPPING.collect(f.revision, StructureFacts.of(f.structure), f.mappings, f.filters,
                    f.criticalities);
        }
        ConfigProblemCollector problems = ConfigProblemCollector.all();
        StructureFacts facts = STRUCTURE.collectInto(f.revision, f.linkCurrency, problems);
        MAPPING.collectInto(f.revision, facts, f.mappings, f.filters, f.criticalities, problems);
        return problems.toReport();
    }

    private static Map<String, Consumer<Fixture>> corpus() {
        Map<String, Consumer<Fixture>> c = new LinkedHashMap<>();
        // Geen worp
        c.put("OK-base", f -> { });
        c.put("OK-four-part-with-discount", f -> {
            f.revision.setIdentityProfileKind(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE);
            f.revision.setIdentityDiscountCodeField("KORTING");
        });
        c.put("OK-column-index", Fixture::columnIndex);
        c.put("OK-reference-mapping", f -> f.replace(referenceMapping(f.revision, "EAN13")));

        // Eén fout
        c.put("S1-format", f -> f.revision.setStructureFormat("XML"));
        c.put("S2-charset", f -> f.revision.setStructureCharset(null));
        c.put("S3-delimiter", f -> f.revision.setStructureDelimiter("ab"));
        c.put("S4-quote-equals-delimiter", f -> f.revision.setStructureQuoteChar(";"));
        c.put("S5-header-line", f -> f.revision.setStructureHeaderLineNumber(0));
        c.put("S6-reference-kind", f -> f.revision.setStructureFieldReferenceKind("FOO"));
        c.put("S7-no-header", f -> f.revision.setStructureHasHeader(false));
        c.put("S8-column-count", f -> f.revision.setStructureExpectedColumnCount(0));
        c.put("S9-group", f -> f.revision.setIdentitySupplierGroupField(null));
        c.put("S10-discount", f -> f.revision.setIdentityProfileKind(
                IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE));
        c.put("S11-base-price", f -> f.revision.setRecordBasePriceField(" "));
        c.put("S12-tolerance", f -> f.revision.setPriceDerivationTolerance(new BigDecimal("-0.5")));
        c.put("S13-link-currency", f -> f.linkCurrency = "eur");
        c.put("S14-version", f -> f.revision.setRecordCanonicalisationVersion(3));
        c.put("S15-supplier-index", f -> {
            f.columnIndex();
            f.revision.setIdentitySupplierField("abc");
        });
        c.put("M1-price-model", f -> f.revision.setPriceControlModel(PriceControlModel.BOXPLOT));
        c.put("M2-overrule-unknown", f -> f.criticalities.add(
                new ImportRevisionFieldCriticality(null, "BRAND", Criticality.NON_CRITICAL)));
        c.put("M3a-duplicates-revision", f -> f.replace(mapping(f.revision, 1, supporting("SUPPLIER"), "X")));
        c.put("M3b-target-null", f -> f.first().setTargetField(null));
        c.put("M3c-duplicate-target", f -> f.mappings.add(mapping(f.revision, 2, supporting("E_SUPPLIER"), "X")));
        c.put("M3e-type", f -> f.first().setDataType(FieldDataType.INTEGER));
        c.put("M3i-settings", f -> f.first().setTransformConfig("foo"));
        c.put("M3j-zone", f -> f.first().setTransformConfig("zone=Mars/Base"));
        c.put("M3k-transform-kind", f -> f.first().setTransformKind(null));
        c.put("M3l-max-percentage", f -> f.first().setTransformConfig("maxPercentage=250"));
        c.put("M3m-unused", f -> f.first().setTransformConfig("onbekend=x"));
        c.put("M3n-source-null", f -> f.first().setSourceReference(null));
        c.put("M3o-price-component-twice", f -> {
            f.replace(priceMapping(f.revision, 1, priceCatalog("AKP_PCT", "AKP"), "AKP"));
            f.mappings.add(priceMapping(f.revision, 2, priceCatalog("AKP_PCT_ALT", "AKP"), "AKP_ALT"));
        });
        c.put("M4b-filter-stage", f -> f.firstFilter().setFilterStage(FilterStage.TARGET_FIELD));
        c.put("M4e-filter-operator", f -> f.firstFilter().setOperator(null));
        c.put("M5-prebuilt-structure", f -> {
            f.structure = f.deriveStructure();
            f.revision.setIdentitySupplierField(null);
        });
        c.put("M6c-version-1", f -> f.revision.setRecordCanonicalisationVersion(1));

        // Combinaties: de eerste fout in de volgorde van de code moet vooraan staan.
        c.put("COMBI-S3-S4-S8", f -> {
            f.revision.setStructureDelimiter(null);
            f.revision.setStructureQuoteChar("ab");
            f.revision.setStructureExpectedColumnCount(0);
        });
        c.put("COMBI-S2-S9-S14-M1", f -> {
            f.revision.setStructureCharset("NOPE-9");
            f.revision.setIdentitySupplierGroupField(null);
            f.revision.setRecordCanonicalisationVersion(3);
            f.revision.setPriceControlModel(PriceControlModel.BOXPLOT);
        });
        c.put("COMBI-S6-with-bad-indexes", f -> {
            f.columnIndex();
            f.revision.setStructureFieldReferenceKind("FOO");
            f.first().setSourceReference("abc");
            f.firstFilter().setSourceReference("abc");
        });
        c.put("COMBI-S12-S13", f -> {
            f.revision.setPriceDerivationTolerance(new BigDecimal("-1"));
            f.linkCurrency = "EU";
        });
        c.put("COMBI-M3e-scale-with-separator", f -> {
            f.first().setDecimalScale(13);
            f.first().setTransformConfig("decimalSeparator=,");
        });
        c.put("COMBI-M3i-M3n", f -> {
            f.first().setTransformConfig("=x");
            f.first().setSourceReference(null);
        });
        c.put("COMBI-M2-M3d-M4a", f -> {
            f.criticalities.add(new ImportRevisionFieldCriticality(null, "BASE_PRICE", Criticality.CRITICAL));
            f.criticalities.add(new ImportRevisionFieldCriticality(null, "BASE_PRICE", Criticality.CRITICAL));
            f.mappings.add(mapping(f.revision, 1, supporting("E_BRAND"), "BRAND"));
            f.filters.add(filter(f.revision, 1, "STATUS", FilterOperator.EQUALS, "EOL", FilterOutcome.EXCLUDE));
        });
        c.put("COMBI-S9-all-three", f -> {
            f.revision.setIdentitySupplierField(null);
            f.revision.setIdentitySupplierGroupField(null);
            f.revision.setIdentitySupplierReferenceField(null);
        });
        c.put("COMBI-M3b-then-M3e", f -> {
            f.first().setTargetField(null);
            ImportFieldMapping second = mapping(f.revision, 2, supporting("E_BRAND"), "BRAND");
            second.setDataType(FieldDataType.INTEGER);
            f.mappings.add(second);
        });
        c.put("COMBI-column-index-everything-beyond", f -> {
            f.columnIndex();
            f.revision.setStructureExpectedColumnCount(3);
            f.first().setSourceReference("9");
            f.firstFilter().setSourceReference("abc");
        });
        c.put("COMBI-M6a-references-and-currency", f -> {
            f.revision.setRecordCanonicalisationVersion(1);
            f.revision.setRecordCurrencyField("MUNT");
            f.replace(referenceMapping(f.revision, "EAN13"));
        });
        c.put("COMBI-M3j-M3k", f -> {
            f.first().setTransformKind(FieldTransformKind.ADD);
            f.first().setTransformConfig("decimalSeparator=ab");
        });
        c.put("COMBI-M4c-column-index", f -> {
            f.columnIndex();
            f.firstFilter().setSourceReference(null);
            f.firstFilter().setCompareValue(null);
        });
        c.put("COMBI-S15-and-M3n-index", f -> {
            f.columnIndex();
            f.revision.setIdentitySupplierReferenceField("x");
            f.revision.setRecordBasePriceField("0");
            f.first().setSourceReference("abc");
        });
        c.put("COMBI-S8-with-column-index", f -> {
            f.columnIndex();
            f.revision.setStructureExpectedColumnCount(-1);
            f.first().setSourceReference("0");
        });
        c.put("COMBI-everything-broken", f -> {
            f.revision.setStructureFormat("XML");
            f.revision.setStructureCharset(null);
            f.revision.setStructureDelimiter("");
            f.revision.setStructureQuoteChar("\"\"");
            f.revision.setStructureHeaderLineNumber(0);
            f.revision.setStructureFieldReferenceKind(null);
            f.revision.setIdentitySupplierField(null);
            f.revision.setRecordBasePriceField(null);
            f.revision.setPriceDerivationTolerance(new BigDecimal("-2"));
            f.revision.setRecordCanonicalisationVersion(9);
            f.revision.setPriceControlModel(null);
            f.linkCurrency = "x";
            f.first().setTransformKind(null);
            f.firstFilter().setOperator(null);
        });
        return c;
    }

    // --- Deel 2: gedrag van de volledige controle -------------------------------------------------

    @Test
    void columnIndexWithThreeHeaderNameMappingsGivesThreeUnresolvedSources() {
        Fixture f = new Fixture();
        f.columnIndex();
        f.replace(mapping(f.revision, 1, supporting("E_A"), "NAAM_A"));
        f.mappings.add(mapping(f.revision, 2, supporting("E_B"), "NAAM_B"));
        f.mappings.add(mapping(f.revision, 3, supporting("E_C"), "NAAM_C"));

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code)
                .containsExactly(SOURCE_UNRESOLVED, SOURCE_UNRESOLVED, SOURCE_UNRESOLVED);
        assertThat(report.findings()).extracting(ConfigFinding::fieldName).containsExactly("E_A", "E_B", "E_C");
        assertThat(report.findings()).extracting(ConfigFinding::sourceValue)
                .containsExactly("NAAM_A", "NAAM_B", "NAAM_C");
        assertThat(report.findings().get(1).message()).isEqualTo("Source reference 'NAAM_B' must be a 1-based "
                + "column index because this revision references fields by column index");
        assertThat(report.findings()).extracting(ConfigFinding::revisionField).containsOnlyNulls();
        assertThat(report.skippedBecause()).isEmpty();
    }

    @Test
    void everyMissingIdentityColumnIsItsOwnFindingAndSuppressesTheStrongIdentityCheck() {
        Fixture f = new Fixture();
        f.revision.setIdentitySupplierField(null);
        f.revision.setIdentitySupplierGroupField("  ");
        f.revision.setIdentitySupplierReferenceField(null);

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code)
                .containsExactly(IDENTITY_MISSING, IDENTITY_MISSING, IDENTITY_MISSING);
        assertThat(report.findings()).extracting(ConfigFinding::revisionField)
                .containsExactly("supplierField", "supplierGroupField", "supplierReferenceField");
        assertThat(report.findings()).extracting(ConfigFinding::message).containsExactly(
                "No source field configured for identity_supplier_field",
                "No source field configured for identity_supplier_group_field",
                "No source field configured for identity_supplier_reference_field");
        assertThat(report.findings()).extracting(ConfigFinding::fieldName).containsOnlyNulls();
        // M5 steunt op S9: geen valse "geen sterke identiteit", wel gemeld als overgeslagen.
        assertThat(report.findings()).extracting(ConfigFinding::code).doesNotContain(IDENTITY_CLASS);
        assertThat(report.skippedBecause()).containsExactly(IDENTITY_MISSING);
    }

    @Test
    void strongIdentityCheckStillRunsWhenTheStructureItselfIsValid() {
        Fixture f = new Fixture();
        f.structure = f.deriveStructure();
        f.revision.setIdentitySupplierField(null);

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code).containsExactly(IDENTITY_CLASS);
        assertThat(report.skippedBecause()).isEmpty();
    }

    @Test
    void structureAndMappingFindingsComeTogetherInCodeOrder() {
        Fixture f = new Fixture();
        f.revision.setStructureFormat("XML");
        f.revision.setPriceControlModel(PriceControlModel.BOXPLOT);
        f.first().setDataType(FieldDataType.INTEGER);
        f.firstFilter().setOperator(null);

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code)
                .containsExactly(FORMAT, PRICE_MODEL, TYPE_INCOMPATIBLE, FILTER);
        assertThat(report.findings()).extracting(ConfigFinding::revisionField)
                .containsExactly("structureFormat", "priceControlModel", null, null);
        assertThat(report.findings().get(1).sourceValue()).isEqualTo("BOXPLOT");
        assertThat(report.findings().get(2).fieldName()).isEqualTo("E_SUPPLIER");
    }

    @Test
    void invalidReferenceKindSkipsEveryColumnIndexCheck() {
        Fixture f = new Fixture();
        f.columnIndex();
        f.revision.setStructureFieldReferenceKind("FOO");
        f.revision.setIdentitySupplierField("abc");
        f.revision.setStructureHasHeader(false);
        f.first().setSourceReference("abc");
        f.firstFilter().setSourceReference("abc");

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code).containsExactly(REF_KIND);
        assertThat(report.findings().get(0).revisionField()).isEqualTo("fieldReferenceKind");
        assertThat(report.skippedBecause()).containsExactly(REF_KIND);
    }

    @Test
    void invalidColumnCountStillChecksTheLowerBoundButNotTheUpperBound() {
        Fixture f = new Fixture();
        f.columnIndex();
        f.revision.setStructureExpectedColumnCount(0);
        f.first().setSourceReference("0");

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code).containsExactly(COLUMN_COUNT, SOURCE_UNRESOLVED);
        assertThat(report.findings().get(0).revisionField()).isEqualTo("expectedColumnCount");
        assertThat(report.findings().get(1).message())
                .isEqualTo("Source reference '0' is not a column of this source (expected column count null)");
        assertThat(report.skippedBecause()).containsExactly(COLUMN_COUNT);
    }

    @Test
    void everyDeclaredColumnIndexIsItsOwnFinding() {
        Fixture f = new Fixture();
        f.columnIndex();
        f.revision.setIdentitySupplierField("abc");
        f.revision.setIdentitySupplierReferenceField("0");
        f.revision.setRecordDescriptionField("x");

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code).containsExactly(FIELD_REF, FIELD_REF, FIELD_REF);
        assertThat(report.findings()).extracting(ConfigFinding::revisionField)
                .containsExactly("supplierField", "supplierReferenceField", "descriptionField");
    }

    @Test
    void unreadableTransformConfigGivesNoFalseUnusedSetting() {
        Fixture f = new Fixture();
        f.first().setTransformConfig("foo;onbekend=x");

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).hasSize(1);
        assertThat(report.findings().get(0).code()).isEqualTo(TRANSFORM);
        assertThat(report.findings().get(0).message()).isEqualTo("Mapping for 'E_SUPPLIER' has an invalid "
                + "transform configuration: setting 'foo' is not of the form key=value");
        assertThat(report.findings()).extracting(ConfigFinding::message)
                .noneMatch(message -> message.contains("is not used"));
        assertThat(report.skippedBecause()).containsExactly(TRANSFORM);
    }

    @Test
    void transformIsJudgedIndependentlyOfAnInvalidNotation() {
        Fixture f = new Fixture();
        f.first().setTransformKind(FieldTransformKind.PREFIX);
        f.first().setTransformConfig("zone=Mars/Base");

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::message).containsExactly(
                "Mapping for 'E_SUPPLIER' declares the unknown time zone 'Mars/Base'",
                "Mapping for 'E_SUPPLIER' has an invalid transform configuration: setting 'prefix' is required: "
                        + "a prefix transformation needs its text");
        // De controle op ongebruikte instellingen vraagt een geldige notatie.
        assertThat(report.skippedBecause()).containsExactly(TRANSFORM);
    }

    @Test
    void invalidDecimalScaleSkipsTheNotationInsteadOfFailingTechnically() {
        Fixture f = new Fixture();
        f.first().setDecimalScale(13);
        f.first().setTransformConfig("decimalSeparator=,");

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::message)
                .containsExactly("Decimal scale of 'E_SUPPLIER' must be between 0 and 12");
        assertThat(report.skippedBecause()).containsExactly(TYPE_INCOMPATIBLE);
    }

    @Test
    void derivedFieldIsNotJudgedWithoutAUsableTransformation() {
        Fixture f = new Fixture();
        f.first().setValueKind(FieldValueKind.DERIVED);
        f.first().setTransformKind(null);

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::message).containsExactly(
                "Mapping for 'E_SUPPLIER' has no transform kind; use NONE to state that there is no "
                        + "transformation");
        assertThat(report.skippedBecause()).containsExactly(TRANSFORM);
    }

    @Test
    void filterWithoutSourceColumnIsNotIndexCheckedButTheRestOfTheRowIs() {
        Fixture f = new Fixture();
        f.columnIndex();
        f.firstFilter().setSourceReference(null);
        f.firstFilter().setCompareValue(null);

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::message).containsExactly(
                "Record filter 1 has no source column",
                "Record filter 1 compares with EQUALS but has no value to compare with; that rule would match "
                        + "every record");
        assertThat(report.skippedBecause()).containsExactly(FILTER);
    }

    @Test
    void unsupportedVersionSuppressesTheRequiredVersionCheck() {
        Fixture f = new Fixture();
        f.revision.setRecordCanonicalisationVersion(3);
        f.replace(referenceMapping(f.revision, "EAN13"));

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code).containsExactly(CANON_UNSUPPORTED);
        assertThat(report.findings().get(0).revisionField()).isEqualTo("canonicalisationVersion");
        assertThat(report.skippedBecause()).containsExactly(CANON_UNSUPPORTED);
    }

    @Test
    void requiredVersionGivesAtMostOneFinding() {
        Fixture f = new Fixture();
        f.revision.setRecordCanonicalisationVersion(1);
        f.revision.setRecordCurrencyField("MUNT");
        f.replace(referenceMapping(f.revision, "EAN13"));

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code).containsExactly(CANON_REQUIRED);
        assertThat(report.findings().get(0).revisionField()).isEqualTo("canonicalisationVersion");
    }

    @Test
    void bothMeaningsOfPriceFieldMissingAreDistinguishedByRevisionField() {
        Fixture f = new Fixture();
        f.revision.setRecordBasePriceField(null);
        f.revision.setPriceDerivationTolerance(new BigDecimal("-0.5"));
        f.linkCurrency = "eur";

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code)
                .containsExactly(PRICE_MISSING, PRICE_MISSING, "CONFIG_LINK_CURRENCY_INVALID");
        assertThat(report.findings()).extracting(ConfigFinding::revisionField)
                .containsExactly("basePriceField", "priceDerivationTolerance", null);
        assertThat(report.findings()).extracting(ConfigFinding::fieldName).containsOnlyNulls();
    }

    @Test
    void quoteEqualityIsNotJudgedAgainstAnInvalidDelimiterButItsLengthIs() {
        Fixture equality = new Fixture();
        equality.revision.setStructureDelimiter("ab");
        equality.revision.setStructureQuoteChar("a");
        ConfigCheckReport skipped = collectAll(equality);
        assertThat(skipped.findings()).extracting(ConfigFinding::code).containsExactly(DELIM_INVALID);
        assertThat(skipped.findings()).extracting(ConfigFinding::revisionField).containsExactly("delimiter");
        assertThat(skipped.skippedBecause()).containsExactly(DELIM_INVALID);

        Fixture length = new Fixture();
        length.revision.setStructureDelimiter("ab");
        length.revision.setStructureQuoteChar("xy");
        ConfigCheckReport both = collectAll(length);
        assertThat(both.findings()).extracting(ConfigFinding::code).containsExactly(DELIM_INVALID, QUOTE);
        assertThat(both.findings()).extracting(ConfigFinding::revisionField).containsExactly("delimiter", "quoteChar");
    }

    @Test
    void everyBrokenOverruleRowIsItsOwnFinding() {
        Fixture f = new Fixture();
        f.criticalities.add(new ImportRevisionFieldCriticality(null, "BRAND", Criticality.NON_CRITICAL));
        f.criticalities.add(new ImportRevisionFieldCriticality(null, "SUPPLIER", Criticality.NON_CRITICAL));

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code).containsExactly(CRITICALITY, CRITICALITY);
        assertThat(report.findings()).extracting(ConfigFinding::fieldName).containsExactly("BRAND", "SUPPLIER");
        assertThat(report.findings()).extracting(ConfigFinding::revisionField).containsOnlyNulls();
    }

    @Test
    void unknownTargetStopsOnlyItsOwnRow() {
        Fixture f = new Fixture();
        f.first().setTargetField(null);
        f.first().setDataType(FieldDataType.INTEGER);
        ImportFieldMapping second = mapping(f.revision, 2, supporting("E_BRAND"), "BRAND");
        second.setMaxLength(0);
        f.mappings.add(second);

        ConfigCheckReport report = collectAll(f);

        assertThat(report.findings()).extracting(ConfigFinding::code)
                .containsExactly("CONFIG_MAPPING_TARGET_UNKNOWN", TYPE_INCOMPATIBLE);
        assertThat(report.findings().get(1).fieldName()).isEqualTo("E_BRAND");
    }

    @Test
    void collectNeverThrowsAndAValidRevisionHasNoFindings() {
        Fixture broken = new Fixture();
        CORPUS.get("COMBI-everything-broken").accept(broken);
        assertThatCode(() -> collectAll(broken)).doesNotThrowAnyException();
        assertThat(collectAll(broken).findings()).hasSizeGreaterThan(8);

        Fixture valid = new Fixture();
        ConfigCheckReport report = STRUCTURE.collect(valid.revision, null);
        assertThat(report.hasFindings()).isFalse();
        assertThat(report.skippedBecause()).isEmpty();
    }

    // --- Deel 3: de geslaagde stand bouwt exact dezelfde configuratie ----------------------------------
    //
    // De golden table vergelijkt enkel worpen. Deze tests pinnen het opgebouwde resultaat van from(...)
    // vast met een uitdrukkelijk opgebouwd verwacht record (de vorm van vóór NT-14-1): elke component,
    // de volgorde van de gedeclareerde velden, de mappings, de filters en de kritiek-map.

    @Test
    void baseStructureIsBuiltExactlyAsBefore() {
        ImportDefinitionRevision r = new Fixture().revision;

        SourceStructureConfig expected = new SourceStructureConfig("CSV",
                Charset.forName(r.getStructureCharset().trim()), ';', quoteOf(r), true,
                r.getStructureHeaderLineNumber(), FieldReferenceKind.HEADER_NAME, null,
                IdentityProfileKind.THREE_PART, "LEVERANCIER", "GROEP", "REFERENTIE", null, "PRIJS",
                "OMSCHRIJVING", 2, new PricePolicy(null, r.isBasePriceZeroAllowed(), r.isBasePriceNegativeAllowed(),
                toleranceOf(r), null));

        SourceStructureConfig actual = STRUCTURE.from(r, null);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.declaredFields()).containsExactly("LEVERANCIER", "GROEP", "REFERENTIE", "PRIJS",
                "OMSCHRIJVING");
        assertThat(STRUCTURE.collect(r, null).findings()).isEmpty();
    }

    @Test
    void fourPartStructureWithCurrencyAndLinkCurrencyIsBuiltExactlyAsBefore() {
        ImportDefinitionRevision r = new Fixture().revision;
        r.setIdentityProfileKind(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE);
        r.setIdentityDiscountCodeField(" KORTING ");
        r.setIdentitySupplierField(" LEVERANCIER ");
        r.setRecordDescriptionField("  ");
        r.setRecordCurrencyField(" MUNT ");
        r.setPriceDerivationTolerance(null);
        r.setStructureQuoteChar(null);

        SourceStructureConfig expected = new SourceStructureConfig("CSV",
                Charset.forName(r.getStructureCharset().trim()), ';', null, true,
                r.getStructureHeaderLineNumber(), FieldReferenceKind.HEADER_NAME, null,
                IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, "LEVERANCIER", "GROEP", "REFERENTIE", "KORTING",
                "PRIJS", null, 2, new PricePolicy("MUNT", r.isBasePriceZeroAllowed(),
                r.isBasePriceNegativeAllowed(), PriceRules.DEFAULT_DERIVATION_TOLERANCE, "EUR"));

        SourceStructureConfig actual = STRUCTURE.from(r, " EUR ");

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.declaredFields()).containsExactly("LEVERANCIER", "GROEP", "REFERENTIE", "KORTING",
                "PRIJS", "MUNT");
    }

    @Test
    void columnIndexStructureWithoutHeaderIsBuiltExactlyAsBefore() {
        Fixture f = new Fixture();
        f.columnIndex();
        ImportDefinitionRevision r = f.revision;
        r.setStructureHasHeader(false);
        r.setStructureHeaderLineNumber(0);
        r.setStructureExpectedColumnCount(7);
        r.setRecordCurrencyField("6");

        SourceStructureConfig expected = new SourceStructureConfig("CSV",
                Charset.forName(r.getStructureCharset().trim()), ';', quoteOf(r), false, 0,
                FieldReferenceKind.COLUMN_INDEX, 7, IdentityProfileKind.THREE_PART, "1", "2", "3", null, "4", "5",
                2, new PricePolicy("6", r.isBasePriceZeroAllowed(), r.isBasePriceNegativeAllowed(), toleranceOf(r),
                null));

        SourceStructureConfig actual = STRUCTURE.from(r, null);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.declaredFields()).containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    void baseMappingIsBuiltExactlyAsBefore() {
        Fixture f = new Fixture();
        SourceStructureConfig structure = STRUCTURE.from(f.revision, null);

        ImportMappingConfig actual = MAPPING.from(f.revision, structure, f.mappings, f.filters, f.criticalities);

        List<FieldMapping> fields = List.of(
                expectedField(f.first(), new FieldTransform.Unchanged(), ValueFormat.DEFAULT, null));
        Map<String, Criticality> own = new LinkedHashMap<>();
        own.put("LEVERANCIER", RevisionCriticalityField.SUPPLIER.defaultCriticality());
        own.put("GROEP", RevisionCriticalityField.SUPPLIER_GROUP.defaultCriticality());
        own.put("REFERENTIE", RevisionCriticalityField.SUPPLIER_REFERENCE.defaultCriticality());
        own.put("PRIJS", RevisionCriticalityField.BASE_PRICE.defaultCriticality());
        own.put("OMSCHRIJVING", RevisionCriticalityField.DESCRIPTION.defaultCriticality());
        ImportMappingConfig expected = new ImportMappingConfig(2, fields, List.of(expectedFilter(f.firstFilter())),
                ImportMappingConfig.criticalityMap(own, fields));

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void severalMappingsFiltersAndOverrulesKeepTheirOrderAndValues() {
        Fixture f = new Fixture();
        ImportFieldMapping translated = mapping(f.revision, 3, supporting("E_BRAND"), "MERK");
        translated.setTransformKind(FieldTransformKind.MAP);
        translated.setTransformConfig("values=A>1|B>2;caseSensitive=true");
        ImportFieldMapping price = priceMapping(f.revision, 1, priceCatalog("AKP_PCT", "AKP"), "AKP");
        price.setTransformConfig("maxPercentage=250;decimalSeparator=,");
        f.replace(translated);
        f.mappings.add(price);
        ImportRecordFilter second = filter(f.revision, 2, " STATUS ", FilterOperator.NOT_EQUALS, "EOL",
                FilterOutcome.EXCLUDE);
        f.filters.add(second);
        f.criticalities.add(new ImportRevisionFieldCriticality(null, "BASE_PRICE", Criticality.NON_CRITICAL));
        SourceStructureConfig structure = STRUCTURE.from(f.revision, null);

        ImportMappingConfig actual = MAPPING.from(f.revision, structure, f.mappings, f.filters, f.criticalities);

        List<FieldMapping> fields = List.of(
                expectedField(translated, new FieldTransform.Translate(Map.of("A", "1", "B", "2"), true),
                        ValueFormat.DEFAULT, null),
                expectedField(price, new FieldTransform.Unchanged(),
                        new ValueFormat(new DecimalFormat(ImportValueRules.MAX_DECIMAL_SCALE, ',', null), null, null,
                                null),
                        new BigDecimal("250")));
        Map<String, Criticality> own = new LinkedHashMap<>();
        own.put("LEVERANCIER", RevisionCriticalityField.SUPPLIER.defaultCriticality());
        own.put("GROEP", RevisionCriticalityField.SUPPLIER_GROUP.defaultCriticality());
        own.put("REFERENTIE", RevisionCriticalityField.SUPPLIER_REFERENCE.defaultCriticality());
        own.put("PRIJS", Criticality.NON_CRITICAL);
        own.put("OMSCHRIJVING", RevisionCriticalityField.DESCRIPTION.defaultCriticality());
        ImportMappingConfig expected = new ImportMappingConfig(2, fields,
                List.of(expectedFilter(f.firstFilter()), expectedFilter(second)),
                ImportMappingConfig.criticalityMap(own, fields));

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.fields()).extracting(FieldMapping::sequenceNumber).containsExactly(3, 1);
        assertThat(actual.filters()).extracting(RecordFilter::sourceReference).containsExactly("CULTURE", "STATUS");
    }

    @Test
    void columnIndexArithmeticMappingIsBuiltExactlyAsBefore() {
        Fixture f = new Fixture();
        f.columnIndex();
        f.revision.setStructureExpectedColumnCount(8);
        ImportFieldMapping number = mapping(f.revision, 1, catalog("E_NUM", "Getal", FieldDataType.DECIMAL,
                FieldOwner.CATALOG_SOURCE, IdentityClass.NONE), " 6 ");
        number.setDecimalScale(2);
        number.setTransformKind(FieldTransformKind.ADD);
        number.setTransformConfig("operand=2");
        f.replace(number);
        SourceStructureConfig structure = STRUCTURE.from(f.revision, null);

        ImportMappingConfig actual = MAPPING.from(f.revision, structure, f.mappings, f.filters, f.criticalities);

        DecimalFormat scaleTwo = new DecimalFormat(2, null, null);
        List<FieldMapping> fields = List.of(expectedField(number,
                new FieldTransform.Arithmetic(FieldTransformKind.ADD, new BigDecimal("2"), null, scaleTwo),
                new ValueFormat(scaleTwo, null, null, null), null));
        Map<String, Criticality> own = new LinkedHashMap<>();
        own.put("1", RevisionCriticalityField.SUPPLIER.defaultCriticality());
        own.put("2", RevisionCriticalityField.SUPPLIER_GROUP.defaultCriticality());
        own.put("3", RevisionCriticalityField.SUPPLIER_REFERENCE.defaultCriticality());
        own.put("4", RevisionCriticalityField.BASE_PRICE.defaultCriticality());
        own.put("5", RevisionCriticalityField.DESCRIPTION.defaultCriticality());
        ImportMappingConfig expected = new ImportMappingConfig(2, fields, List.of(expectedFilter(f.firstFilter())),
                ImportMappingConfig.criticalityMap(own, fields));

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.fields().get(0).sourceReference()).isEqualTo("6");
    }

    private static Character quoteOf(ImportDefinitionRevision revision) {
        String quote = revision.getStructureQuoteChar();
        return quote == null || quote.isEmpty() ? null : quote.charAt(0);
    }

    private static BigDecimal toleranceOf(ImportDefinitionRevision revision) {
        return revision.getPriceDerivationTolerance() == null ? PriceRules.DEFAULT_DERIVATION_TOLERANCE
                : revision.getPriceDerivationTolerance();
    }

    /** Dezelfde vorm als de (ongewijzigde) toFieldMapping van de fabriek. */
    private static FieldMapping expectedField(ImportFieldMapping row, FieldTransform transform,
                                              ValueFormat valueFormat, BigDecimal maxPercentage) {
        ImportFieldCatalogEntry target = row.getTargetField();
        String source = row.getSourceReference() == null || row.getSourceReference().isBlank() ? null
                : row.getSourceReference().trim();
        return new FieldMapping(row.getSequenceNumber(), target.getCode(), target.getName(), row.getValueKind(),
                source, row.getExpectedPosition(), row.getFixedValue(), row.getBookmarkName(),
                row.getDefaultValue(), row.getDataType(), row.isRequired(), row.getMaxLength(),
                row.getDecimalScale(), row.isZeroAllowed(), row.isNegativeAllowed(), row.getTransformKind(),
                row.getTransformConfig(), transform, valueFormat, row.getFieldOwner(), row.getIdentityClass(),
                row.getPriceComponentCode(), row.getReferenceType(), maxPercentage, row.getCriticality());
    }

    private static RecordFilter expectedFilter(ImportRecordFilter row) {
        return new RecordFilter(row.getSequenceNumber(), row.getSourceReference().trim(), row.getOperator(),
                row.getCompareValue(), row.getOutcome(), row.isCaseSensitive(), row.isTrimBeforeCompare(),
                row.getNullBehaviour(), row.getMissingColumnBehaviour());
    }

    // --- Collector ---------------------------------------------------------------------------------

    @Test
    void firstModeRethrowsTheSameObject() {
        ScreeningBlockedException blocked = new ScreeningBlockedException("CONFIG_X", "f", "s", "e", "boom");
        ConfigProblemCollector first = ConfigProblemCollector.first();

        assertThatThrownBy(() -> first.check("delimiter", () -> {
            throw blocked;
        })).isSameAs(blocked);
        assertThatThrownBy(() -> first.report(blocked, null)).isSameAs(blocked);
        assertThat(first.findingCount()).isZero();
    }

    @Test
    void allModeRecordsTheFindingLiterallyAndContinues() {
        ScreeningBlockedException blocked = new ScreeningBlockedException("CONFIG_X", "f", "s", "e", "boom");
        ConfigProblemCollector all = ConfigProblemCollector.all();

        Outcome<String> failed = all.branch("delimiter", () -> {
            throw blocked;
        });
        Outcome<String> passed = all.branch(null, () -> "ok");

        assertThat(failed.failed()).isTrue();
        assertThat(failed.failedCode()).isEqualTo("CONFIG_X");
        assertThat(failed.value()).isNull();
        assertThat(passed.failed()).isFalse();
        assertThat(passed.value()).isEqualTo("ok");
        assertThat(all.toReport().findings())
                .containsExactly(new ConfigFinding("CONFIG_X", "f", "s", "e", "boom", "delimiter"));
    }

    @Test
    void otherExceptionsAreNeverCollected() {
        ConfigProblemCollector all = ConfigProblemCollector.all();
        IllegalStateException technical = new IllegalStateException("technical");

        assertThatThrownBy(() -> all.check(null, () -> {
            throw technical;
        })).isSameAs(technical);
        assertThat(all.findingCount()).isZero();
    }

    @Test
    void skippedRootCausesAreDistinctAndOrdered() {
        ConfigProblemCollector all = ConfigProblemCollector.all();
        all.skip("B");
        all.skip("A");
        all.skip("B");

        ConfigCheckReport report = all.toReport();

        assertThat(report.skippedBecause()).containsExactly("B", "A");
        assertThatThrownBy(() -> report.skippedBecause().add("C")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.findings().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }

    // --- Fixture (zelfde basis als ConfigFactoryParityTest) ---------------------------------------

    /** De basisrevisie plus één mapping en één filter; geen enkele regel wordt geschonden. */
    private static final class Fixture {

        final ImportDefinitionRevision revision = baseRevision();
        String linkCurrency;
        /** {@code null}: afleiden uit de revisie via de bronstructuurfabriek. */
        SourceStructureConfig structure;
        final List<ImportFieldMapping> mappings = new ArrayList<>();
        final List<ImportRecordFilter> filters = new ArrayList<>();
        final List<ImportRevisionFieldCriticality> criticalities = new ArrayList<>();

        Fixture() {
            mappings.add(mapping(revision, 1, supporting("E_SUPPLIER"), "E_LEV"));
            filters.add(filter(revision, 1, "CULTURE", FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE));
        }

        ImportFieldMapping first() {
            return mappings.get(0);
        }

        ImportRecordFilter firstFilter() {
            return filters.get(0);
        }

        void replace(ImportFieldMapping only) {
            mappings.clear();
            mappings.add(only);
        }

        SourceStructureConfig deriveStructure() {
            return STRUCTURE.from(revision, linkCurrency);
        }

        /** Verwijst naar kolommen per index; basismapping en -filter krijgen een geldige index. */
        void columnIndex() {
            revision.setStructureFieldReferenceKind("COLUMN_INDEX");
            revision.setIdentitySupplierField("1");
            revision.setIdentitySupplierGroupField("2");
            revision.setIdentitySupplierReferenceField("3");
            revision.setRecordBasePriceField("4");
            revision.setRecordDescriptionField("5");
            first().setSourceReference("6");
            firstFilter().setSourceReference("7");
        }
    }

    private static ImportDefinitionRevision baseRevision() {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setStructureDelimiter(";");
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setRecordCanonicalisationVersion(2);
        return revision;
    }

    private static ImportFieldCatalogEntry catalog(String code, String name, FieldDataType type,
                                                   FieldOwner owner, IdentityClass identityClass) {
        return new ImportFieldCatalogEntry(code, name, type, owner, identityClass, 10);
    }

    private static ImportFieldCatalogEntry supporting(String code) {
        return catalog(code, code, FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE, IdentityClass.SUPPORTING);
    }

    private static ImportFieldCatalogEntry priceCatalog(String code, String componentCode) {
        ImportFieldCatalogEntry field = catalog(code, code, FieldDataType.DECIMAL, FieldOwner.PRICE_CONTROL,
                IdentityClass.NONE);
        field.setPriceComponentCode(componentCode);
        return field;
    }

    private static ImportFieldCatalogEntry referenceCatalog() {
        ImportFieldCatalogEntry ean = catalog("EAN", "EAN-barcode", FieldDataType.TEXT,
                FieldOwner.CRITICAL_REFERENCE, IdentityClass.ARTICLE_REFERENCE);
        ean.setReferenceType("EAN");
        ean.setOwnerChangeable(false);
        return ean;
    }

    private static ImportFieldMapping mapping(ImportDefinitionRevision revision, int sequenceNumber,
                                              ImportFieldCatalogEntry target, String sourceReference) {
        ImportFieldMapping mapping = new ImportFieldMapping(revision, sequenceNumber, target,
                FieldValueKind.SOURCE_FIELD, target.getDataType(), target.getDefaultOwner(),
                target.getIdentityClass());
        mapping.setSourceReference(sourceReference);
        return mapping;
    }

    private static ImportFieldMapping priceMapping(ImportDefinitionRevision revision, int sequenceNumber,
                                                   ImportFieldCatalogEntry target, String sourceReference) {
        ImportFieldMapping mapping = mapping(revision, sequenceNumber, target, sourceReference);
        mapping.setPriceComponentCode(target.getPriceComponentCode());
        return mapping;
    }

    private static ImportFieldMapping referenceMapping(ImportDefinitionRevision revision,
                                                       String sourceReference) {
        ImportFieldMapping ean = mapping(revision, 1, referenceCatalog(), sourceReference);
        ean.setReferenceType("EAN");
        return ean;
    }

    private static ImportRecordFilter filter(ImportDefinitionRevision revision, int sequenceNumber,
                                             String sourceReference, FilterOperator operator,
                                             String compareValue, FilterOutcome outcome) {
        ImportRecordFilter filter = new ImportRecordFilter(revision, sequenceNumber, sourceReference,
                operator, compareValue, outcome);
        filter.setNullBehaviour(FilterNullBehaviour.EXCLUDE);
        filter.setMissingColumnBehaviour(MissingColumnBehaviour.BLOCK);
        return filter;
    }
}
