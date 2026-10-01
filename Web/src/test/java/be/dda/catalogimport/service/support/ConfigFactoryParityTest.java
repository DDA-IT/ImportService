package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Golden table — mag tijdens NT-14-1 niet gewijzigd worden.
 * <p>
 * Pariteitsbewijs voor NT-14 (docs/design/configfouten-alle-tegelijk-design.md par. 5.1): voor elke
 * werpplaats van {@link SourceStructureConfigFactory} en {@link ImportMappingConfigFactory} (inclusief
 * de werpplaatsen die via {@link MappingSettings} en {@link FieldTransform#of} bereikt worden) staat er
 * één rij met een basisrevisie, <b>één</b> mutator en de <b>eerste worp</b> letterlijk uitgeschreven:
 * code, fieldName, sourceValue, expectedValue en de volledige boodschap. Geschreven tegen de huidige
 * code (vóór de collector-refactor); na NT-14-1 moet dezelfde tabel ongewijzigd groen blijven.
 * <p>
 * Eerste worp = de volgorde waarin een aanroeper de fabrieken gebruikt: eerst de bronstructuur
 * ({@code SourceStructureConfigFactory.from(revision, linkCurrency)}), daarna de mapping
 * ({@code ImportMappingConfigFactory.from(revision, structure, mappings, filters, criticalities)}).
 * Rijen zonder verwachte code zijn "geen worp"-rijen (grensgevallen die expliciet slagen).
 * <p>
 * Niet in de tabel (bewust): de rij-niveau {@code ImportValueException}-codes van {@link FieldTransform}
 * ({@code TRANSFORM_FAILED}, {@code TRANSFORM_DIVIDE_BY_ZERO}, {@code MAPPING_VALUE_UNKNOWN}); dat zijn
 * geen configuratiefouten maar bronregelfouten tijdens de verwerking.
 * <p>
 * Onbereikbaar via de fabrieken (dus niet in de tabel): de {@code IllegalArgumentException} in de
 * recordconstructor van {@link SourceStructureConfig} (de fabriek blokkeert eerder), die van
 * {@code ImportValueRules.DecimalFormat} (schaal 0..12 en verschillende scheidingstekens worden eerder
 * geweigerd) en {@code MappingSettings.character(...)} (wordt door geen enkele transformatie gebruikt).
 */
class ConfigFactoryParityTest {

    private static final SourceStructureConfigFactory STRUCTURE = new SourceStructureConfigFactory();
    private static final ImportMappingConfigFactory MAPPING = new ImportMappingConfigFactory(null, null);

    private static final List<Row> TABLE = buildTable();

    // --- Tests ----------------------------------------------------------------------------------

    @TestFactory
    Stream<DynamicTest> everyRowThrowsExactlyItsGoldenFirstThrow() {
        return TABLE.stream().map(row -> DynamicTest.dynamicTest(row.id(), () -> verify(row)));
    }

    @Test
    void baseRevisionProducesNoThrow() {
        Fixture fixture = new Fixture();
        SourceStructureConfig structure = STRUCTURE.from(fixture.revision, fixture.linkCurrency);
        ImportMappingConfig config = MAPPING.from(fixture.revision, structure, fixture.mappings,
                fixture.filters, fixture.criticalities);

        assertThat(config.fields()).hasSize(1);
        assertThat(config.filters()).hasSize(1);
        assertThat(TABLE).anyMatch(row -> row.id().equals("OK-base") && row.code() == null);
    }

    @Test
    void rowIdsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Row row : TABLE) {
            assertThat(seen.add(row.id())).as("duplicate row id %s", row.id()).isTrue();
        }
    }

    /**
     * Elke {@code CODE_*}-constante van beide fabrieken en elke andere {@code CONFIG_*}-tekstconstante
     * (ook in {@link MappingSettings} en {@link FieldTransform}) komt in minstens één rij voor, en elke
     * code in de tabel is een bestaande constante.
     */
    @Test
    void everyConfigCodeConstantAppearsInAtLeastOneRow() throws IllegalAccessException {
        Set<String> declared = new TreeSet<>();
        declared.addAll(stringConstants(SourceStructureConfigFactory.class, true));
        declared.addAll(stringConstants(ImportMappingConfigFactory.class, true));
        declared.addAll(stringConstants(MappingSettings.class, false));
        declared.addAll(stringConstants(FieldTransform.class, false));

        Set<String> covered = new TreeSet<>();
        for (Row row : TABLE) {
            if (row.code() != null) {
                covered.add(row.code());
            }
        }

        assertThat(declared).isNotEmpty();
        assertThat(covered).as("codes in the table that are no declared constant")
                .isSubsetOf(declared);
        assertThat(declared).as("declared codes without any row").isSubsetOf(covered);
    }

    private static Set<String> stringConstants(Class<?> type, boolean everyCodePrefixed)
            throws IllegalAccessException {
        Set<String> values = new TreeSet<>();
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            field.setAccessible(true);
            String value = (String) field.get(null);
            boolean byName = everyCodePrefixed && field.getName().startsWith("CODE_");
            boolean byValue = value != null && value.startsWith("CONFIG_");
            if (byName || byValue) {
                values.add(value);
            }
        }
        return values;
    }

    private static void verify(Row row) {
        Fixture fixture = new Fixture();
        row.mutator().accept(fixture);

        ScreeningBlockedException thrown = null;
        try {
            SourceStructureConfig structure = fixture.structure != null ? fixture.structure
                    : STRUCTURE.from(fixture.revision, fixture.linkCurrency);
            MAPPING.from(fixture.revision, structure, fixture.mappings, fixture.filters,
                    fixture.criticalities);
        } catch (ScreeningBlockedException blocked) {
            thrown = blocked;
        }

        if (row.code() == null) {
            assertThat(thrown).as("row %s must not throw", row.id()).isNull();
            return;
        }
        assertThat(thrown).as("row %s must throw", row.id()).isNotNull();
        assertThat(thrown.getCode()).as("%s code", row.id()).isEqualTo(row.code());
        assertThat(thrown.getFieldName()).as("%s fieldName", row.id()).isEqualTo(row.fieldName());
        assertThat(thrown.getSourceValue()).as("%s sourceValue", row.id()).isEqualTo(row.sourceValue());
        assertThat(thrown.getExpectedValue()).as("%s expectedValue", row.id()).isNull();
        assertThat(thrown.getMessage()).as("%s message", row.id()).isEqualTo(row.message());
    }

    // --- Tabel ----------------------------------------------------------------------------------

    private static final String FORMAT = "CONFIG_FORMAT_UNSUPPORTED";
    private static final String CHARSET = "CONFIG_CHARSET_UNKNOWN";
    private static final String DELIM_MISSING = "CONFIG_DELIMITER_MISSING";
    private static final String DELIM_INVALID = "CONFIG_DELIMITER_INVALID";
    private static final String QUOTE = "CONFIG_QUOTE_INVALID";
    private static final String HEADER_LINE = "CONFIG_HEADER_LINE_INVALID";
    private static final String REF_KIND = "CONFIG_FIELD_REFERENCE_KIND_INVALID";
    private static final String HEADER_REF = "CONFIG_HEADER_REFERENCE_INCONSISTENT";
    private static final String IDENTITY_MISSING = "CONFIG_IDENTITY_FIELD_MISSING";
    private static final String DISCOUNT_MISSING = "CONFIG_DISCOUNT_FIELD_MISSING";
    private static final String PRICE_MISSING = "CONFIG_PRICE_FIELD_MISSING";
    private static final String COLUMN_COUNT = "CONFIG_COLUMN_COUNT_INVALID";
    private static final String FIELD_REF = "CONFIG_FIELD_REFERENCE_INVALID";
    private static final String CANON_UNSUPPORTED = "CONFIG_CANONICALISATION_VERSION_UNSUPPORTED";
    private static final String LINK_CURRENCY = "CONFIG_LINK_CURRENCY_INVALID";
    private static final String TARGET_UNKNOWN = "CONFIG_MAPPING_TARGET_UNKNOWN";
    private static final String SOURCE_UNRESOLVED = "CONFIG_MAPPING_SOURCE_UNRESOLVED";
    private static final String DUPLICATE_TARGET = "CONFIG_MAPPING_DUPLICATE_TARGET";
    private static final String TYPE_INCOMPATIBLE = "CONFIG_MAPPING_TYPE_INCOMPATIBLE";
    private static final String DUPLICATES_REVISION = "CONFIG_FIELD_MAPPING_DUPLICATES_REVISION";
    private static final String IDENTITY_CLASS = "CONFIG_IDENTITY_CLASS_CONFLICT";
    private static final String OWNER = "CONFIG_OWNER_NOT_CHANGEABLE";
    private static final String PRICE_COMPONENT = "CONFIG_PRICE_COMPONENT_DUPLICATE";
    private static final String TRANSFORM = "CONFIG_TRANSFORM_INVALID";
    private static final String FILTER = "CONFIG_FILTER_INVALID";
    private static final String CANON_REQUIRED = "CONFIG_CANONICALISATION_VERSION_REQUIRED";
    private static final String CRITICALITY = "CONFIG_FIELD_CRITICALITY_INVALID";
    private static final String PRICE_MODEL = "CONFIG_PRICE_CONTROL_MODEL_UNSUPPORTED";

    private static List<Row> buildTable() {
        List<Row> t = new ArrayList<>();
        successRows(t);
        structureRows(t);
        priceModelAndCriticalityRows(t);
        mappingTargetRows(t);
        mappingTypeOwnerIdentityRows(t);
        transformSettingsRows(t);
        valueFormatRows(t);
        transformKindRows(t);
        maxPercentageRows(t);
        sourceRows(t);
        priceComponentAndFilterRows(t);
        identityAndCanonicalisationRows(t);
        return List.copyOf(t);
    }

    // Rijen zonder worp: de basisrevisie en grensgevallen die uitdrukkelijk slagen.
    private static void successRows(List<Row> t) {
        ok(t, "OK-base", f -> { });
        ok(t, "OK-quote-null", f -> f.revision.setStructureQuoteChar(null));
        ok(t, "OK-tolerance-null-falls-back-to-default", f -> f.revision.setPriceDerivationTolerance(null));
        ok(t, "OK-link-currency-padded", f -> f.linkCurrency = " EUR ");
        ok(t, "OK-four-part-with-discount", f -> {
            f.revision.setIdentityProfileKind(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE);
            f.revision.setIdentityDiscountCodeField("KORTING");
        });
        ok(t, "OK-no-header-column-index-ignores-header-line", f -> {
            f.columnIndex();
            f.revision.setStructureHasHeader(false);
            f.revision.setStructureHeaderLineNumber(0);
        });
        ok(t, "OK-inactive-mapping-is-skipped", f -> {
            f.first().setActive(false);
            f.first().setDataType(FieldDataType.INTEGER);
        });
        ok(t, "OK-identity-overrule-restates-default", f -> f.criticalities.add(
                new ImportRevisionFieldCriticality(null, "SUPPLIER_GROUP", Criticality.CRITICAL)));
        ok(t, "OK-expected-position", f -> f.first().setExpectedPosition(6));
        ok(t, "OK-fixed-value-mapping", f -> {
            f.first().setValueKind(FieldValueKind.FIXED_VALUE);
            f.first().setFixedValue("X");
        });
        ok(t, "OK-derived-concat", f -> {
            f.first().setValueKind(FieldValueKind.DERIVED);
            f.first().setSourceReference(null);
            f.first().setTransformKind(FieldTransformKind.CONCAT);
            f.first().setTransformConfig("sources=LEVERANCIER|REFERENTIE;separator=-");
        });
        ok(t, "OK-translate-transform", f -> {
            f.first().setTransformKind(FieldTransformKind.MAP);
            f.first().setTransformConfig("values=A>1|B>2;caseSensitive=true");
        });
        ok(t, "OK-date-format-and-zone", f -> {
            f.replace(mapping(f.revision, 1, dateCatalog(), "VANAF"));
            f.first().setTransformConfig("dateFormat=dd/MM/yyyy;zone=Europe/Brussels");
        });
        ok(t, "OK-price-component-with-max-percentage", f -> {
            f.replace(priceMapping(f.revision, 1, priceCatalog("AKP_PCT", "AKP"), "AKP"));
            f.first().setTransformConfig("maxPercentage=250");
        });
        ok(t, "OK-critical-reference-mapping-version-2", f ->
                f.replace(referenceMapping(f.revision, "EAN13")));
        ok(t, "OK-filter-not-equals-blank-compare-value", f -> {
            f.firstFilter().setOperator(FilterOperator.NOT_EQUALS);
            f.firstFilter().setCompareValue("");
        });
        ok(t, "OK-strong-mapping-rescues-missing-revision-identity", f -> {
            f.structure = f.deriveStructure();
            f.revision.setIdentitySupplierReferenceField(null);
            f.mappings.add(mapping(f.revision, 2, catalog("E_STRONG", "Sterk", FieldDataType.TEXT,
                    FieldOwner.CATALOG_SOURCE, IdentityClass.STRONG), "E_STRONG_COL"));
        });
        ok(t, "OK-version-1-without-mapping-or-currency", f -> {
            f.revision.setRecordCanonicalisationVersion(1);
            f.mappings.clear();
        });
        ok(t, "OK-version-1-blank-currency-field", f -> {
            f.revision.setRecordCanonicalisationVersion(1);
            f.revision.setRecordCurrencyField("  ");
            f.mappings.clear();
        });
    }

    // SourceStructureConfigFactory S1-S15 (volgorde van de code).
    private static void structureRows(List<Row> t) {
        // S1: SourceStructureConfigFactory:93-94
        blocked(t, "S1-format-xml", f -> f.revision.setStructureFormat("XML"), FORMAT, null, null,
                "Source format XML is not supported");
        blocked(t, "S1-format-null", f -> f.revision.setStructureFormat(null), FORMAT, null, null,
                "Source format null is not supported");
        // S2: :204-205 en :209-210
        blocked(t, "S2-charset-null", f -> f.revision.setStructureCharset(null), CHARSET, null, null,
                "No charset configured; the source encoding is never guessed");
        blocked(t, "S2-charset-blank", f -> f.revision.setStructureCharset("  "), CHARSET, null, null,
                "No charset configured; the source encoding is never guessed");
        blocked(t, "S2-charset-unsupported-name", f -> f.revision.setStructureCharset("NOPE-9"), CHARSET,
                null, null, "Unsupported charset NOPE-9");
        blocked(t, "S2-charset-illegal-name", f -> f.revision.setStructureCharset("bad name!"), CHARSET,
                null, null, "Unsupported charset bad name!");
        // S3: :215-219
        blocked(t, "S3-delimiter-null", f -> f.revision.setStructureDelimiter(null), DELIM_MISSING, null,
                null, "No column delimiter configured on the revision");
        blocked(t, "S3-delimiter-empty", f -> f.revision.setStructureDelimiter(""), DELIM_MISSING, null,
                null, "No column delimiter configured on the revision");
        blocked(t, "S3-delimiter-two-chars", f -> f.revision.setStructureDelimiter("ab"), DELIM_INVALID,
                null, null, "Column delimiter must be exactly one character");
        // S4: :228-233
        blocked(t, "S4-quote-two-chars", f -> f.revision.setStructureQuoteChar("ab"), QUOTE, null, null,
                "Quote character must be exactly one character");
        blocked(t, "S4-quote-equals-delimiter", f -> f.revision.setStructureQuoteChar(";"), QUOTE, null,
                null, "Quote character must differ from the column delimiter");
        // S5: :103-106
        blocked(t, "S5-header-line-zero", f -> f.revision.setStructureHeaderLineNumber(0), HEADER_LINE,
                null, null, "Header line number must be 1 or higher but is 0");
        // S6: :238-241
        blocked(t, "S6-reference-kind-unknown", f -> f.revision.setStructureFieldReferenceKind("FOO"),
                REF_KIND, null, null, "Unknown field reference kind FOO");
        blocked(t, "S6-reference-kind-null", f -> f.revision.setStructureFieldReferenceKind(null), REF_KIND,
                null, null, "Unknown field reference kind null");
        // S7: :109-111
        blocked(t, "S7-no-header-with-header-name", f -> f.revision.setStructureHasHeader(false),
                HEADER_REF, null, null, "A source without a header cannot reference fields by header name");
        // S8: :115-118
        blocked(t, "S8-column-count-zero", f -> f.revision.setStructureExpectedColumnCount(0), COLUMN_COUNT,
                null, null, "Expected column count must be 1 or higher but is 0");
        blocked(t, "S8-column-count-negative", f -> f.revision.setStructureExpectedColumnCount(-3),
                COLUMN_COUNT, null, null, "Expected column count must be 1 or higher but is -3");
        // S9: :246-249
        blocked(t, "S9-supplier-null", f -> f.revision.setIdentitySupplierField(null), IDENTITY_MISSING,
                null, null, "No source field configured for identity_supplier_field");
        blocked(t, "S9-supplier-blank", f -> f.revision.setIdentitySupplierField("   "), IDENTITY_MISSING,
                null, null, "No source field configured for identity_supplier_field");
        blocked(t, "S9-supplier-group-null", f -> f.revision.setIdentitySupplierGroupField(null),
                IDENTITY_MISSING, null, null,
                "No source field configured for identity_supplier_group_field");
        blocked(t, "S9-supplier-reference-null", f -> f.revision.setIdentitySupplierReferenceField(null),
                IDENTITY_MISSING, null, null,
                "No source field configured for identity_supplier_reference_field");
        // S10: :128-134
        blocked(t, "S10-discount-field-null", f -> f.revision.setIdentityProfileKind(
                IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE), DISCOUNT_MISSING, null, null,
                "Identity profile FOUR_PART_WITH_DISCOUNT_CODE requires identity_discount_code_field");
        blocked(t, "S10-discount-field-blank", f -> {
            f.revision.setIdentityProfileKind(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE);
            f.revision.setIdentityDiscountCodeField("  ");
        }, DISCOUNT_MISSING, null, null,
                "Identity profile FOUR_PART_WITH_DISCOUNT_CODE requires identity_discount_code_field");
        // S11: :136-139
        blocked(t, "S11-base-price-field-null", f -> f.revision.setRecordBasePriceField(null), PRICE_MISSING,
                null, null, "No base price field configured on the revision");
        blocked(t, "S11-base-price-field-blank", f -> f.revision.setRecordBasePriceField(" "), PRICE_MISSING,
                null, null, "No base price field configured on the revision");
        // S12: :174-177 (zelfde code als S11, andere betekenis)
        blocked(t, "S12-negative-tolerance",
                f -> f.revision.setPriceDerivationTolerance(new BigDecimal("-0.5")), PRICE_MISSING, null,
                null, "price_derivation_tolerance is negative (-0.5); a tolerance is a distance and is "
                        + "never negative");
        // S13: :195-199
        blocked(t, "S13-link-currency-lowercase", f -> f.linkCurrency = "eur", LINK_CURRENCY, null, null,
                "import_link.default_currency 'eur' is not an ISO 4217 currency code (exactly three "
                        + "capital letters); a fixed currency is never guessed, corrected or replaced by "
                        + "the system default");
        blocked(t, "S13-link-currency-two-letters", f -> f.linkCurrency = "EU", LINK_CURRENCY, null, null,
                "import_link.default_currency 'EU' is not an ISO 4217 currency code (exactly three "
                        + "capital letters); a fixed currency is never guessed, corrected or replaced by "
                        + "the system default");
        // S14: :144-148 (de verzameling komt uit de constante: Set.of kent geen vaste volgorde)
        blocked(t, "S14-canonicalisation-version-3",
                f -> f.revision.setRecordCanonicalisationVersion(3), CANON_UNSUPPORTED, null, null,
                "Canonicalisation version 3 is not supported by this build; supported versions are "
                        + SourceStructureConfigFactory.SUPPORTED_CANONICALISATION_VERSIONS);
        blocked(t, "S14-canonicalisation-version-0",
                f -> f.revision.setRecordCanonicalisationVersion(0), CANON_UNSUPPORTED, null, null,
                "Canonicalisation version 0 is not supported by this build; supported versions are "
                        + SourceStructureConfigFactory.SUPPORTED_CANONICALISATION_VERSIONS);
        // S15: :253-269 over declaredFields(): leverancier, groep, referentie, korting, prijs,
        // omschrijving, munt.
        blocked(t, "S15-supplier-not-a-number", f -> {
            f.columnIndex();
            f.revision.setIdentitySupplierField("abc");
        }, FIELD_REF, null, null, "Field reference abc must be a 1-based column index");
        blocked(t, "S15-group-zero", f -> {
            f.columnIndex();
            f.revision.setIdentitySupplierGroupField("0");
        }, FIELD_REF, null, null, "Field reference 0 must be a 1-based column index");
        blocked(t, "S15-reference-negative", f -> {
            f.columnIndex();
            f.revision.setIdentitySupplierReferenceField("-1");
        }, FIELD_REF, null, null, "Field reference -1 must be a 1-based column index");
        blocked(t, "S15-discount-not-a-number", f -> {
            f.columnIndex();
            f.revision.setIdentityProfileKind(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE);
            f.revision.setIdentityDiscountCodeField("x");
        }, FIELD_REF, null, null, "Field reference x must be a 1-based column index");
        blocked(t, "S15-price-beyond-column-count", f -> {
            f.columnIndex();
            f.revision.setStructureExpectedColumnCount(3);
        }, FIELD_REF, null, null, "Field reference 4 is beyond the expected column count 3");
        blocked(t, "S15-description-beyond-column-count", f -> {
            f.columnIndex();
            f.revision.setStructureExpectedColumnCount(4);
        }, FIELD_REF, null, null, "Field reference 5 is beyond the expected column count 4");
        blocked(t, "S15-currency-beyond-column-count", f -> {
            f.columnIndex();
            f.revision.setStructureExpectedColumnCount(5);
            f.revision.setRecordCurrencyField("7");
        }, FIELD_REF, null, null, "Field reference 7 is beyond the expected column count 5");
    }

    // M1 (ImportMappingConfigFactory:519-528) en M2 (:237-267).
    private static void priceModelAndCriticalityRows(List<Row> t) {
        String boxplot = "This revision declares price control model BOXPLOT, which this build does not "
                + "perform; only DEVIATION (the deviation check against the previous value and the two "
                + "moving averages) is implemented";
        blocked(t, "M1-price-model-boxplot",
                f -> f.revision.setPriceControlModel(PriceControlModel.BOXPLOT), PRICE_MODEL, null,
                "BOXPLOT", boxplot);
        blocked(t, "M1-price-model-null", f -> f.revision.setPriceControlModel(null), PRICE_MODEL, null,
                "null", "This revision declares price control model null, which this build does not "
                        + "perform; only DEVIATION (the deviation check against the previous value and "
                        + "the two moving averages) is implemented");

        String known = "[SUPPLIER, SUPPLIER_GROUP, SUPPLIER_REFERENCE, DISCOUNT_CODE, BASE_PRICE, "
                + "CURRENCY, DESCRIPTION]";
        blocked(t, "M2-overrule-unknown-key", f -> f.criticalities.add(
                new ImportRevisionFieldCriticality(null, "BRAND", Criticality.NON_CRITICAL)),
                CRITICALITY, "BRAND", "NON_CRITICAL", "Criticality is configured for 'BRAND', which is not "
                        + "a field of the revision itself; the known fields are " + known);
        blocked(t, "M2-overrule-null-key", f -> f.criticalities.add(
                new ImportRevisionFieldCriticality(null, null, Criticality.CRITICAL)),
                CRITICALITY, null, "CRITICAL", "Criticality is configured for 'null', which is not a field "
                        + "of the revision itself; the known fields are " + known);
        blocked(t, "M2-overrule-without-value", f -> f.criticalities.add(
                new ImportRevisionFieldCriticality(null, "BASE_PRICE", null)),
                CRITICALITY, "BASE_PRICE", null, "Criticality of 'BASE_PRICE' has no value; use CRITICAL "
                        + "or NON_CRITICAL");
        blocked(t, "M2-overrule-identity-non-critical", f -> f.criticalities.add(
                new ImportRevisionFieldCriticality(null, "SUPPLIER", Criticality.NON_CRITICAL)),
                CRITICALITY, "SUPPLIER", "NON_CRITICAL", "Field 'SUPPLIER' is part of the offer identity "
                        + "and can never be NON_CRITICAL; without an identity there is no offer to review");
        blocked(t, "M2-overrule-twice", f -> {
            f.criticalities.add(
                    new ImportRevisionFieldCriticality(null, "BASE_PRICE", Criticality.NON_CRITICAL));
            f.criticalities.add(
                    new ImportRevisionFieldCriticality(null, "BASE_PRICE", Criticality.CRITICAL));
        }, CRITICALITY, "BASE_PRICE", "CRITICAL", "Criticality of 'BASE_PRICE' is configured more than "
                + "once for this revision; a field has exactly one criticality");
    }

    // M3a-M3d: readFields, eerste controles per mappingrij.
    private static void mappingTargetRows(List<Row> t) {
        // M3a: verifyDoesNotDuplicateRevision :531-540 (alle zes takken van isDeterminedByRevision)
        duplicatesRevision(t, "SUPPLIER", "identity_supplier_field", f -> { });
        duplicatesRevision(t, "SUPPLIER_GROUP", "identity_supplier_group_field", f -> { });
        duplicatesRevision(t, "SUPPLIER_REFERENCE", "identity_supplier_reference_field", f -> { });
        duplicatesRevision(t, "DISCOUNT_CODE", "identity_discount_code_field",
                f -> f.revision.setIdentityDiscountCodeField("KORTING"));
        duplicatesRevision(t, "BASE_PRICE", "record_base_price_field", f -> { });
        duplicatesRevision(t, "DESCRIPTION", "record_description_field", f -> { });

        // M3b: :330-334
        blocked(t, "M3b-target-null", f -> f.first().setTargetField(null), TARGET_UNKNOWN, null, null,
                "Mapping 1 targets field 'null', which does not exist or is no longer active in the "
                        + "field catalogue");
        blocked(t, "M3b-target-inactive", f -> f.first().getTargetField().setActive(false), TARGET_UNKNOWN,
                "E_SUPPLIER", null, "Mapping 1 targets field 'E_SUPPLIER', which does not exist or is no "
                        + "longer active in the field catalogue");
        // M3c: :335-339
        blocked(t, "M3c-duplicate-target", f -> f.mappings.add(
                mapping(f.revision, 2, supporting("E_SUPPLIER"), "E_LEV_2")), DUPLICATE_TARGET,
                "E_SUPPLIER", null, "Target field 'E_SUPPLIER' is mapped more than once in this revision; "
                        + "a target field has exactly one source");
        // M3d: :340-345
        blocked(t, "M3d-duplicate-sequence", f -> f.mappings.add(
                mapping(f.revision, 1, supporting("E_BRAND"), "BRAND")), DUPLICATE_TARGET, "E_BRAND", "1",
                "Sequence number 1 occurs more than once in the mapping of this revision");
    }

    private static void duplicatesRevision(List<Row> t, String code, String column, Consumer<Fixture> prep) {
        blocked(t, "M3a-duplicates-revision-" + code, f -> {
            prep.accept(f);
            f.replace(mapping(f.revision, 1, supporting(code), "X_COL"));
        }, DUPLICATES_REVISION, code, column, "Target field '" + code + "' is already determined by "
                + "revision column '" + column + "'; remove either the mapping or the revision column "
                + "instead of keeping two sources for the same value");
    }

    // M3e-M3h: verifyTypes :600-644, verifyOwner :647-658, verifyIdentityClass :680-692,
    // verifyMappingCriticality :308-315.
    private static void mappingTypeOwnerIdentityRows(List<Row> t) {
        blocked(t, "M3e-data-type-differs-from-catalogue", f -> f.first().setDataType(FieldDataType.INTEGER),
                TYPE_INCOMPATIBLE, "E_SUPPLIER", "INTEGER", "Mapping for 'E_SUPPLIER' declares type "
                        + "INTEGER but the field catalogue declares TEXT; a leading zero or a decimal "
                        + "separator would silently change meaning");
        blocked(t, "M3e-max-length-zero", f -> f.first().setMaxLength(0), TYPE_INCOMPATIBLE, "E_SUPPLIER",
                "0", "Maximum length of 'E_SUPPLIER' must be 1 or higher");
        blocked(t, "M3e-decimal-scale-negative", f -> f.first().setDecimalScale(-1), TYPE_INCOMPATIBLE,
                "E_SUPPLIER", "-1", "Decimal scale of 'E_SUPPLIER' must be between 0 and 12");
        blocked(t, "M3e-decimal-scale-13", f -> f.first().setDecimalScale(13), TYPE_INCOMPATIBLE,
                "E_SUPPLIER", "13", "Decimal scale of 'E_SUPPLIER' must be between 0 and 12");
        blocked(t, "M3e-price-component-differs-from-catalogue", f -> f.first().setPriceComponentCode("AKP"),
                TYPE_INCOMPATIBLE, "E_SUPPLIER", "AKP", "Mapping for 'E_SUPPLIER' declares price component "
                        + "'AKP' but the field catalogue declares 'null'");
        blocked(t, "M3e-price-component-not-decimal", f -> {
            ImportFieldCatalogEntry textual = catalog("AKP_PCT", "AKP_PCT", FieldDataType.TEXT,
                    FieldOwner.CATALOG_SOURCE, IdentityClass.NONE);
            textual.setPriceComponentCode("AKP");
            ImportFieldMapping row = mapping(f.revision, 1, textual, "AKP");
            row.setPriceComponentCode("AKP");
            f.replace(row);
        }, TYPE_INCOMPATIBLE, "AKP_PCT", "TEXT", "Mapping for 'AKP_PCT' carries price component 'AKP' but "
                + "is declared as TEXT; an amount is always DECIMAL");
        blocked(t, "M3e-price-component-scale-beyond-amount", f -> {
            f.replace(priceMapping(f.revision, 1, priceCatalog("AKP_PCT", "AKP"), "AKP"));
            f.first().setDecimalScale(8);
        }, TYPE_INCOMPATIBLE, "AKP_PCT", "8", "Mapping for 'AKP_PCT' declares 8 decimals for price "
                + "component 'AKP', but an amount is stored with at most 6; the remaining decimals would "
                + "be rounded away silently");
        blocked(t, "M3e-reference-type-differs-from-catalogue", f -> f.first().setReferenceType("EAN"),
                TYPE_INCOMPATIBLE, "E_SUPPLIER", "EAN", "Mapping for 'E_SUPPLIER' declares reference type "
                        + "'EAN' but the field catalogue declares 'null'");

        blocked(t, "M3f-owner-not-changeable", f -> {
            ImportFieldMapping stolen = referenceMapping(f.revision, "EAN13");
            stolen.setFieldOwner(FieldOwner.CATALOG_SOURCE);
            stolen.setReferenceType(null);
            f.replace(stolen);
        }, OWNER, "EAN", "CATALOG_SOURCE", "Target field 'EAN' is owned by CRITICAL_REFERENCE and that "
                + "ownership cannot be changed by an import definition");
        blocked(t, "M3f-critical-reference-without-reference-type", f -> {
            ImportFieldCatalogEntry untyped = catalog("E_REF", "Referentie", FieldDataType.TEXT,
                    FieldOwner.CRITICAL_REFERENCE, IdentityClass.ARTICLE_REFERENCE);
            f.replace(mapping(f.revision, 1, untyped, "REF"));
        }, TYPE_INCOMPATIBLE, "E_REF", null, "Target field 'E_REF' is owned by the reference control but "
                + "carries no reference type");

        blocked(t, "M3g-identity-class-contradicts-catalogue",
                f -> f.first().setIdentityClass(IdentityClass.STRONG), IDENTITY_CLASS, "E_SUPPLIER",
                "STRONG", "Target field 'E_SUPPLIER' is classified as SUPPORTING in the field catalogue "
                        + "but this revision declares STRONG; a field is never strongly identifying and "
                        + "supporting or weak at the same time. Only NONE (not used for identity) is "
                        + "allowed as an alternative.");

        blocked(t, "M3h-reference-mapping-not-critical", f -> {
            ImportFieldMapping ean = referenceMapping(f.revision, "EAN13");
            ean.setCriticality(Criticality.NON_CRITICAL);
            f.replace(ean);
        }, CRITICALITY, "EAN", "NON_CRITICAL", "Mapping for 'EAN' carries reference type 'EAN' and can "
                + "never be NON_CRITICAL; an unreliable reference is exactly what the review must catch");
    }

    // M3i: MappingSettings.parse :61-82 (transformKind NONE).
    private static void transformSettingsRows(List<Row> t) {
        settings(t, "M3i-pair-without-key-separator", "foo", "foo",
                "setting 'foo' is not of the form key=value");
        settings(t, "M3i-pair-with-empty-key", "=x", "=x", "setting '=x' is not of the form key=value");
        settings(t, "M3i-duplicate-key", "prefix=A;prefix=B", "prefix=B",
                "setting 'prefix' occurs more than once");
        // M3m: MappingSettings.verifyFullyUsed :197-204
        settings(t, "M3m-unused-setting", "onbekend=x", "onbekend",
                "setting 'onbekend' is not used by this transformation or data type; it would silently "
                        + "have no effect");
        // optionalCharacter :139-147 en flag :106-118 via readValueFormat/MAP
        settings(t, "M3j-decimal-separator-two-chars", "decimalSeparator=ab", "ab",
                "setting 'decimalSeparator' must be exactly one character");
        settings(t, "M3j-grouping-separator-two-chars", "groupingSeparator=xy", "xy",
                "setting 'groupingSeparator' must be exactly one character");
    }

    private static void settings(List<Row> t, String id, String config, String source, String reason) {
        blocked(t, id, f -> f.first().setTransformConfig(config), TRANSFORM, "E_SUPPLIER", source,
                tc("E_SUPPLIER") + reason);
    }

    // M3j: readValueFormat :415-446, zone :485-495, dateFormatter :460-469.
    private static void valueFormatRows(List<Row> t) {
        blocked(t, "M3j-same-decimal-and-grouping-separator",
                f -> f.first().setTransformConfig("decimalSeparator=,;groupingSeparator=,"), TRANSFORM,
                "E_SUPPLIER", ",", "Mapping for 'E_SUPPLIER' declares the same decimal and grouping "
                        + "separator; the notation of a number would then be undefined");
        blocked(t, "M3j-unknown-zone", f -> f.first().setTransformConfig("zone=Mars/Base"), TRANSFORM,
                "E_SUPPLIER", "Mars/Base",
                "Mapping for 'E_SUPPLIER' declares the unknown time zone 'Mars/Base'");
        blocked(t, "M3j-zone-on-non-date", f -> f.first().setTransformConfig("zone=Europe/Brussels"),
                TRANSFORM, "E_SUPPLIER", null,
                "Mapping for 'E_SUPPLIER' declares a time zone but is not a date or timestamp");
        blocked(t, "M3j-date-format-on-non-date", f -> f.first().setTransformConfig("dateFormat=dd/MM/yyyy"),
                TRANSFORM, "E_SUPPLIER", "dd/MM/yyyy",
                "Mapping for 'E_SUPPLIER' declares a date format but its type is TEXT");
        blocked(t, "M3j-invalid-date-pattern", f -> {
            f.replace(mapping(f.revision, 1, dateCatalog(), "VANAF"));
            f.first().setTransformConfig("dateFormat={");
        }, TRANSFORM, "E_DATE", "{", "Mapping for 'E_DATE' declares the date format '{', which is not a "
                + "valid pattern: Pattern includes reserved character: '{'");
    }

    // M3k: readTransform :501-509 en FieldTransform.of :238-305 (via MappingSettings en invalid()).
    private static void transformKindRows(List<Row> t) {
        blocked(t, "M3k-transform-kind-null", f -> f.first().setTransformKind(null), TRANSFORM, "E_SUPPLIER",
                null, "Mapping for 'E_SUPPLIER' has no transform kind; use NONE to state that there is no "
                        + "transformation");

        // MappingSettings.require :98-104
        transform(t, "FT-fixed-value-missing", FieldTransformKind.FIXED_VALUE, null, null,
                "setting 'value' is required: a fixed value transformation needs the value it produces");
        transform(t, "FT-prefix-missing", FieldTransformKind.PREFIX, null, null,
                "setting 'prefix' is required: a prefix transformation needs its text");
        transform(t, "FT-suffix-missing", FieldTransformKind.SUFFIX, null, null,
                "setting 'suffix' is required: a suffix transformation needs its text");
        transform(t, "FT-concat-sources-missing", FieldTransformKind.CONCAT, null, null,
                "setting 'sources' is required: a concatenation needs the columns it joins");
        transform(t, "FT-split-separator-missing", FieldTransformKind.SPLIT, null, null,
                "setting 'separator' is required: a split needs the character it splits on");
        transform(t, "FT-split-index-missing", FieldTransformKind.SPLIT, "separator=-", null,
                "setting 'index' is required: a split needs the 1-based part it keeps");
        transform(t, "FT-map-values-missing", FieldTransformKind.MAP, null, null,
                "setting 'values' is required: a translation needs its table of source and target values");
        transform(t, "FT-empty-value-counts-as-missing", FieldTransformKind.PREFIX, "prefix=", null,
                "setting 'prefix' is required: a prefix transformation needs its text");
        // MappingSettings.list :150-162 (alle onderdelen leeg)
        transform(t, "FT-concat-sources-only-separators", FieldTransformKind.CONCAT, "sources=|", null,
                "setting 'sources' is required: a concatenation needs the columns it joins");
        // MappingSettings.integer :120-127
        transform(t, "FT-split-index-not-a-number", FieldTransformKind.SPLIT, "separator=-;index=x", "x",
                "setting 'index' must be a whole number");
        // MappingSettings.table :165-189
        transform(t, "FT-map-entry-without-arrow", FieldTransformKind.MAP, "values=A", "A",
                "translation 'A' is not of the form source>target");
        transform(t, "FT-map-entry-without-source", FieldTransformKind.MAP, "values=>1", ">1",
                "translation '>1' has no source value");
        transform(t, "FT-map-source-twice", FieldTransformKind.MAP, "values=A>1|A>2", "A>2",
                "source value 'A' is translated more than once");
        transform(t, "FT-map-table-empty", FieldTransformKind.MAP, "values=|", null,
                "setting 'values' is required: a translation needs its table of source and target values");
        // MappingSettings.flag :106-118
        transform(t, "FT-map-case-sensitive-not-boolean", FieldTransformKind.MAP,
                "values=A>1;caseSensitive=maybe", "maybe", "setting 'caseSensitive' must be true or false");

        // FieldTransform.arithmetic :260-277, percentage :279-293, operand :295-305 (invalid(): source null)
        transform(t, "FT-add-operand-missing", FieldTransformKind.ADD, null, null,
                "an arithmetic transformation needs either operand=<value> or operandField=<column>");
        transform(t, "FT-add-operand-not-decimal", FieldTransformKind.ADD, "operand=abc", null,
                "setting 'operand' must be a decimal number but is 'abc'");
        transform(t, "FT-add-operand-and-operand-field", FieldTransformKind.ADD,
                "operand=2;operandField=KOL", null, "an arithmetic transformation has both operand and "
                        + "operandField; only one source of the second value is allowed");
        transform(t, "FT-divide-by-fixed-zero", FieldTransformKind.DIVIDE, "operand=0", null,
                "dividing by the fixed value 0 can never produce a result");
        transform(t, "FT-percentage-base-missing", FieldTransformKind.PERCENTAGE, null, null,
                "a percentage needs either base=<value> or baseField=<column>");
        transform(t, "FT-percentage-base-not-decimal", FieldTransformKind.PERCENTAGE, "base=abc", null,
                "setting 'base' must be a decimal number but is 'abc'");
        transform(t, "FT-percentage-base-and-base-field", FieldTransformKind.PERCENTAGE,
                "base=5;baseField=KOL", null,
                "a percentage has both base and baseField; only one base is allowed");
        transform(t, "FT-percentage-base-zero", FieldTransformKind.PERCENTAGE, "base=0", null,
                "a percentage of the fixed base 0 can never be computed");
    }

    private static void transform(List<Row> t, String id, FieldTransformKind kind, String config,
                                  String source, String reason) {
        blocked(t, id, f -> {
            f.first().setTransformKind(kind);
            f.first().setTransformConfig(config);
        }, TRANSFORM, "E_SUPPLIER", source, tc("E_SUPPLIER") + reason);
    }

    // M3l: readMaxPercentage :380-404.
    private static void maxPercentageRows(List<Row> t) {
        blocked(t, "M3l-max-percentage-without-price-component",
                f -> f.first().setTransformConfig("maxPercentage=250"), TRANSFORM, "E_SUPPLIER", "250",
                "Mapping for 'E_SUPPLIER' declares maxPercentage but carries no price component; the "
                        + "limit would never be checked");
        blocked(t, "M3l-max-percentage-not-a-number", f -> {
            f.replace(priceMapping(f.revision, 1, priceCatalog("AKP_PCT", "AKP"), "AKP"));
            f.first().setTransformConfig("maxPercentage=abc");
        }, TRANSFORM, "AKP_PCT", "abc", "Setting maxPercentage of 'AKP_PCT' must be a decimal percentage "
                + "but is 'abc'");
        blocked(t, "M3l-max-percentage-zero", f -> {
            f.replace(priceMapping(f.revision, 1, priceCatalog("AKP_PCT", "AKP"), "AKP"));
            f.first().setTransformConfig("maxPercentage=0");
        }, TRANSFORM, "AKP_PCT", "0", "Setting maxPercentage of 'AKP_PCT' must be higher than 0; a maximum "
                + "of 0 would reject every positive price");
    }

    // M3n: verifySource :554-598.
    private static void sourceRows(List<Row> t) {
        blocked(t, "M3n-fixed-value-without-value", f -> f.first().setValueKind(FieldValueKind.FIXED_VALUE),
                SOURCE_UNRESOLVED, "E_SUPPLIER", null,
                "Mapping for 'E_SUPPLIER' declares a fixed value but does not carry one");
        blocked(t, "M3n-bookmark", f -> {
            f.first().setValueKind(FieldValueKind.BOOKMARK);
            f.first().setBookmarkName("BESTANDS_PREFIX");
        }, SOURCE_UNRESOLVED, "E_SUPPLIER", "BESTANDS_PREFIX", "Mapping for 'E_SUPPLIER' reads the template "
                + "value 'BESTANDS_PREFIX', which this build cannot fill in yet");
        blocked(t, "M3n-bookmark-without-name", f -> f.first().setValueKind(FieldValueKind.BOOKMARK),
                SOURCE_UNRESOLVED, "E_SUPPLIER", null, "Mapping for 'E_SUPPLIER' reads the template value "
                        + "'null', which this build cannot fill in yet");
        blocked(t, "M3n-derived-with-prefix", f -> {
            f.first().setValueKind(FieldValueKind.DERIVED);
            f.first().setTransformKind(FieldTransformKind.PREFIX);
            f.first().setTransformConfig("prefix=ART-");
        }, TRANSFORM, "E_SUPPLIER", "PREFIX", derivedMessage("PREFIX"));
        blocked(t, "M3n-derived-without-transformation", f -> f.first().setValueKind(FieldValueKind.DERIVED),
                TRANSFORM, "E_SUPPLIER", "NONE", derivedMessage("NONE"));
        blocked(t, "M3n-source-reference-null", f -> f.first().setSourceReference(null), SOURCE_UNRESOLVED,
                "E_SUPPLIER", null, "Mapping for 'E_SUPPLIER' has no source column");
        blocked(t, "M3n-source-reference-blank", f -> f.first().setSourceReference("   "), SOURCE_UNRESOLVED,
                "E_SUPPLIER", "   ", "Mapping for 'E_SUPPLIER' has no source column");
        // requireColumnIndex :816-831 (code SOURCE_UNRESOLVED, fieldName = doelveldcode)
        blocked(t, "M3n-column-index-not-a-number", f -> {
            f.columnIndex();
            f.first().setSourceReference("abc");
        }, SOURCE_UNRESOLVED, "E_SUPPLIER", "abc", "Source reference 'abc' must be a 1-based column index "
                + "because this revision references fields by column index");
        blocked(t, "M3n-column-index-zero", f -> {
            f.columnIndex();
            f.first().setSourceReference("0");
        }, SOURCE_UNRESOLVED, "E_SUPPLIER", "0", "Source reference '0' is not a column of this source "
                + "(expected column count null)");
        blocked(t, "M3n-column-index-beyond-column-count", f -> {
            f.columnIndex();
            f.revision.setStructureExpectedColumnCount(8);
            f.first().setSourceReference("9");
        }, SOURCE_UNRESOLVED, "E_SUPPLIER", "9", "Source reference '9' is not a column of this source "
                + "(expected column count 8)");
        blocked(t, "M3n-expected-position-zero", f -> f.first().setExpectedPosition(0), SOURCE_UNRESOLVED,
                "E_SUPPLIER", "0", "Expected position of 'E_SUPPLIER' must be a 1-based column position");
    }

    private static String derivedMessage(String kind) {
        return "Mapping for 'E_SUPPLIER' is derived but its transformation " + kind + " needs a source "
                + "value; only a fixed value or a concatenation can build a derived field on its own";
    }

    // M3o: :359-364; M4: readFilters :761-806.
    private static void priceComponentAndFilterRows(List<Row> t) {
        blocked(t, "M3o-price-component-twice", f -> {
            f.replace(priceMapping(f.revision, 1, priceCatalog("AKP_PCT", "AKP"), "AKP"));
            f.mappings.add(priceMapping(f.revision, 2, priceCatalog("AKP_PCT_ALT", "AKP"), "AKP_ALT"));
        }, PRICE_COMPONENT, "AKP_PCT_ALT", "AKP", "Price component 'AKP' is mapped more than once in this "
                + "revision; two sources for one price component are never reconciled silently");

        blocked(t, "M4a-filter-sequence-twice", f -> f.filters.add(
                filter(f.revision, 1, "STATUS", FilterOperator.EQUALS, "EOL", FilterOutcome.EXCLUDE)),
                FILTER, null, "1", "Record filter sequence number 1 occurs more than once in this "
                        + "revision; the evaluation order would be undefined");
        blocked(t, "M4b-filter-stage-target-field",
                f -> f.firstFilter().setFilterStage(FilterStage.TARGET_FIELD), FILTER, "CULTURE",
                "TARGET_FIELD", "Record filter 1 filters on stage TARGET_FIELD, which this build does not "
                        + "support yet; ignoring it would import records that are deliberately out of scope");
        blocked(t, "M4b-filter-stage-null", f -> f.firstFilter().setFilterStage(null), FILTER, "CULTURE",
                "null", "Record filter 1 filters on stage null, which this build does not support yet; "
                        + "ignoring it would import records that are deliberately out of scope");
        blocked(t, "M4c-filter-source-reference-blank", f -> f.firstFilter().setSourceReference("  "),
                FILTER, null, null, "Record filter 1 has no source column");
        blocked(t, "M4c-filter-source-reference-null", f -> f.firstFilter().setSourceReference(null),
                FILTER, null, null, "Record filter 1 has no source column");
        // requireColumnIndex met code FILTER (fieldName = verwijzing)
        blocked(t, "M4d-filter-column-index-not-a-number", f -> {
            f.columnIndex();
            f.firstFilter().setSourceReference("abc");
        }, FILTER, "abc", "abc", "Source reference 'abc' must be a 1-based column index because this "
                + "revision references fields by column index");
        blocked(t, "M4d-filter-column-index-beyond-column-count", f -> {
            f.columnIndex();
            f.revision.setStructureExpectedColumnCount(8);
            f.firstFilter().setSourceReference("9");
        }, FILTER, "9", "9", "Source reference '9' is not a column of this source (expected column count 8)");
        String incomplete = "Record filter 1 is incomplete; operator, outcome, null behaviour and missing "
                + "column behaviour are all required";
        blocked(t, "M4e-filter-operator-null", f -> f.firstFilter().setOperator(null), FILTER, "CULTURE",
                null, incomplete);
        blocked(t, "M4e-filter-outcome-null", f -> f.firstFilter().setOutcome(null), FILTER, "CULTURE",
                null, incomplete);
        blocked(t, "M4e-filter-null-behaviour-null", f -> f.firstFilter().setNullBehaviour(null), FILTER,
                "CULTURE", null, incomplete);
        blocked(t, "M4e-filter-missing-column-behaviour-null",
                f -> f.firstFilter().setMissingColumnBehaviour(null), FILTER, "CULTURE", null, incomplete);
        blocked(t, "M4f-filter-compare-value-null", f -> f.firstFilter().setCompareValue(null), FILTER,
                "CULTURE", null, "Record filter 1 compares with EQUALS but has no value to compare with; "
                        + "that rule would match every record");
        blocked(t, "M4f-filter-contains-blank-compare-value", f -> {
            f.firstFilter().setOperator(FilterOperator.CONTAINS);
            f.firstFilter().setCompareValue("  ");
        }, FILTER, "CULTURE", "  ", "Record filter 1 compares with CONTAINS but has no value to compare "
                + "with; that rule would match every record");
    }

    // M5: verifyIdentityClasses :667-678 (enkel bereikbaar met een vooraf opgebouwde structuur);
    // M6: verifyCanonicalisationVersion :716-745.
    private static void identityAndCanonicalisationRows(List<Row> t) {
        String noStrong = "This revision declares no strong identity rule; an offer identity can never be "
                + "derived from supporting or weak fields alone";
        blocked(t, "M5-no-strong-identity-supplier-missing", f -> {
            f.structure = f.deriveStructure();
            f.revision.setIdentitySupplierField(null);
        }, IDENTITY_CLASS, null, null, noStrong);
        blocked(t, "M5-no-strong-identity-group-missing", f -> {
            f.structure = f.deriveStructure();
            f.revision.setIdentitySupplierGroupField(null);
        }, IDENTITY_CLASS, null, null, noStrong);
        blocked(t, "M5-no-strong-identity-reference-missing", f -> {
            f.structure = f.deriveStructure();
            f.revision.setIdentitySupplierReferenceField(null);
        }, IDENTITY_CLASS, null, null, noStrong);

        blocked(t, "M6a-references-under-version-1", f -> {
            f.revision.setRecordCanonicalisationVersion(1);
            f.replace(referenceMapping(f.revision, "EAN13"));
        }, CANON_REQUIRED, null, "1", referencesMessage(1));
        blocked(t, "M6a-references-under-version-3", f -> {
            f.structure = f.deriveStructure();
            f.revision.setRecordCanonicalisationVersion(3);
            f.replace(referenceMapping(f.revision, "EAN13"));
        }, CANON_REQUIRED, null, "3", referencesMessage(3));
        blocked(t, "M6b-currency-under-version-1", f -> {
            f.revision.setRecordCanonicalisationVersion(1);
            f.revision.setRecordCurrencyField("MUNT");
        }, CANON_REQUIRED, null, "1", "This revision reads a currency from the source, which is part of "
                + "the price fingerprint of canonicalisation version 2; under version 1 the currency "
                + "would change the fingerprint of every existing source state");
        blocked(t, "M6c-mapped-fields-under-version-1", f -> f.revision.setRecordCanonicalisationVersion(1),
                CANON_REQUIRED, null, "1", "This revision maps 1 target field(s), which must be covered by "
                        + "canonicalisation version 2; version 1 would leave changes to those fields out "
                        + "of the fingerprint");
    }

    private static String referencesMessage(int version) {
        return "This revision maps critical references. Those belong to canonicalisation version 2; under "
                + "version " + version + " a changed EAN, PIM or CAB would stay out of the fingerprint and "
                + "would silently pass as an ordinary update";
    }

    // --- Rijen en fixture -------------------------------------------------------------------------

    private static String tc(String code) {
        return "Mapping for '" + code + "' has an invalid transform configuration: ";
    }

    private static void ok(List<Row> table, String id, Consumer<Fixture> mutator) {
        table.add(new Row(id, mutator, null, null, null, null));
    }

    private static void blocked(List<Row> table, String id, Consumer<Fixture> mutator, String code,
                                String fieldName, String sourceValue, String message) {
        table.add(new Row(id, mutator, code, fieldName, sourceValue, message));
    }

    /** Eén rij: één mutator op de basisrevisie en de letterlijk verwachte eerste worp ({@code code == null}: geen worp). */
    private record Row(String id, Consumer<Fixture> mutator, String code, String fieldName,
                       String sourceValue, String message) {

        @Override
        public String toString() {
            return id;
        }
    }

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

    private static ImportFieldCatalogEntry dateCatalog() {
        return catalog("E_DATE", "Datum", FieldDataType.DATE, FieldOwner.CATALOG_SOURCE, IdentityClass.NONE);
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
