package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fase 3b (ontwerp fase 3, par. 3.1 stap B', R-STR-04..R-STR-06, R-REF-08, R-FLT-01): de
 * definitievalidatie die draait vóór er één byte van het bronbestand gelezen is.
 * <p>
 * Unittest zonder Spring en zonder database: de configuratieregels moeten ook los van een levering
 * hard zijn, en de databaseconstraints op dezelfde regels worden apart bewezen in
 * {@code ScreeningSchemaTest}. Enkele van deze regels zijn bewust dubbel bewaakt — één keer in de
 * database, één keer hier — omdat een configuratiefout die pas halverwege een miljoenenimport opvalt
 * een halve catalogus onbruikbaar maakt.
 */
class MappingConfigValidationTest {

    private final ImportMappingConfigFactory factory = new ImportMappingConfigFactory(null, null);

    // --- Geldige configuratie ---------------------------------------------------------------------

    @Test
    void acceptsAMappingAndAFilterThatSatisfyEveryRule() {
        // Een revisie mét veldmappings moet canonicalisatieversie 2 declareren (bouwstap 3c): enkel die
        // versie neemt de gemapte velden in de artikelvingerafdruk op.
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldMapping mapping = mapping(revision, 1, supportingField(), "E_LEV");
        mapping.setExpectedPosition(6);

        ImportMappingConfig config = factory.from(revision, structure(2), List.of(mapping),
                List.of(filter(revision, 1, "CULTURE", FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE)));

        assertThat(config.canonicalisationVersion()).isEqualTo(2);
        assertThat(config.hasFields()).isTrue();
        assertThat(config.hasFilters()).isTrue();
        assertThat(config.fields()).singleElement().satisfies(field -> {
            assertThat(field.targetFieldCode()).isEqualTo("E_SUPPLIER");
            // De logische naam uit de catalogus is wat de gebruiker in een melding te zien krijgt.
            assertThat(field.targetFieldName()).isEqualTo("Externe leveranciersidentiteit");
            assertThat(field.expectedPosition()).isEqualTo(6);
        });
        assertThat(config.headerExpectations().references()).containsExactly("E_LEV", "CULTURE");
    }

    @Test
    void acceptsARevisionWithoutAnyMappingOrFilterSoPhaseTwoBehaviourIsUnchanged() {
        ImportMappingConfig config = factory.from(revision(), structure(), List.of(), List.of());

        assertThat(config.hasFields()).isFalse();
        assertThat(config.hasFilters()).isFalse();
        assertThat(config.headerExpectations().isEmpty()).isTrue();
    }

    /** Een inactieve mapping wordt overgeslagen, niet gevalideerd: ze doet niet mee aan deze revisie. */
    @Test
    void ignoresAnInactiveMapping() {
        ImportDefinitionRevision revision = revision();
        ImportFieldMapping inactive = mapping(revision, 1, supportingField(), "E_LEV");
        inactive.setActive(false);

        assertThat(factory.from(revision, structure(), List.of(inactive), List.of()).hasFields()).isFalse();
    }

    // --- R-STR-04: mapping verwijst naar bestaand doelveld en bestaande bron ----------------------

    @Test
    void refusesAMappingToAnUnknownOrInactiveTargetField() {
        ImportDefinitionRevision revision = revision();
        ImportFieldCatalogEntry retired = supportingField();
        retired.setActive(false);

        assertBlocks(ImportMappingConfigFactory.CODE_MAPPING_TARGET_UNKNOWN, revision,
                List.of(mapping(revision, 1, retired, "E_LEV")));
    }

    @Test
    void refusesTwoMappingsForTheSameTargetField() {
        ImportDefinitionRevision revision = revision();

        assertBlocks(ImportMappingConfigFactory.CODE_MAPPING_DUPLICATE_TARGET, revision,
                List.of(mapping(revision, 1, supportingField(), "E_LEV"),
                        mapping(revision, 2, supportingField(), "E_LEV_2")));
    }

    @Test
    void refusesAMappingWhoseSourceCannotBeResolved() {
        ImportDefinitionRevision revision = revision();
        ImportFieldMapping blank = mapping(revision, 1, supportingField(), "   ");
        ImportFieldMapping bookmark = mapping(revision, 1, supportingField(), null);
        bookmark.setValueKind(FieldValueKind.BOOKMARK);
        bookmark.setBookmarkName("BESTANDS_PREFIX");

        assertBlocks(ImportMappingConfigFactory.CODE_MAPPING_SOURCE_UNRESOLVED, revision, List.of(blank));
        // Sjablonen bestaan al in het schema maar nog niet in de verwerking: stil negeren zou een leeg
        // doelveld opleveren (beslissingslog 18/09).
        assertBlocks(ImportMappingConfigFactory.CODE_MAPPING_SOURCE_UNRESOLVED, revision, List.of(bookmark));
    }

    @Test
    void refusesANonNumericSourceReferenceWhenTheRevisionReferencesFieldsByColumnIndex() {
        ImportDefinitionRevision revision = revision();
        SourceStructureConfig byIndex = new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"',
                false, 1, FieldReferenceKind.COLUMN_INDEX, 8, IdentityProfileKind.THREE_PART, "1", "2", "3",
                null, "4", "5", 1);

        assertThatThrownBy(() -> factory.from(revision, byIndex,
                List.of(mapping(revision, 1, supportingField(), "E_LEV")), List.of()))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_MAPPING_SOURCE_UNRESOLVED);
    }

    @Test
    void refusesAMappingWhoseTypeContradictsTheFieldCatalogue() {
        ImportDefinitionRevision revision = revision();
        // Een leveranciersidentiteit is tekst: als getal zouden voorloopnullen verdwijnen (R-REC-01).
        ImportFieldMapping numeric = mapping(revision, 1, supportingField(), "E_LEV");
        numeric.setDataType(FieldDataType.INTEGER);

        assertBlocks(ImportMappingConfigFactory.CODE_MAPPING_TYPE_INCOMPATIBLE, revision, List.of(numeric));
    }

    @Test
    void refusesTwoMappingsThatFillTheSamePriceComponent() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldCatalogEntry akp = priceField("AKP_PCT", "AKP", 20);
        // Een tweede catalogusveld dat dezelfde prijscomponent voedt: twee bronnen voor één bedrag.
        ImportFieldCatalogEntry akpAlternative = priceField("AKP_PCT_ALT", "AKP", 21);

        ImportFieldMapping first = priceMapping(revision, 1, akp, "AKP");
        ImportFieldMapping second = priceMapping(revision, 2, akpAlternative, "AKP_ALT");

        assertBlocks(ImportMappingConfigFactory.CODE_PRICE_COMPONENT_DUPLICATE, revision,
                List.of(first, second));
    }

    // --- R-STR-05 en R-REF-08: identiteitsklasse en eigenaarschap ---------------------------------

    @Test
    void refusesAnIdentityClassThatContradictsTheFieldCatalogue() {
        ImportDefinitionRevision revision = revision();
        ImportFieldMapping promoted = mapping(revision, 1, supportingField(), "E_LEV");
        promoted.setIdentityClass(IdentityClass.STRONG);

        assertBlocks(ImportMappingConfigFactory.CODE_IDENTITY_CLASS_CONFLICT, revision, List.of(promoted));
    }

    /** NONE is de enige toegelaten afwijking: "dit veld doet in deze bron niet mee aan de identiteit". */
    @Test
    void acceptsAMappingThatExplicitlyOptsOutOfTheIdentity() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldMapping neutral = mapping(revision, 1, supportingField(), "E_LEV");
        neutral.setIdentityClass(IdentityClass.NONE);

        assertThatCode(() -> factory.from(revision, structure(2), List.of(neutral), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesARevisionWithoutAnyStrongIdentityRule() {
        ImportDefinitionRevision revision = revision();
        revision.setIdentitySupplierReferenceField(null);

        assertBlocks(ImportMappingConfigFactory.CODE_IDENTITY_CLASS_CONFLICT, revision, List.of());
    }

    @Test
    void refusesToChangeTheOwnerOfACriticalReference() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldMapping stolen = new ImportFieldMapping(revision, 1, referenceField(),
                FieldValueKind.SOURCE_FIELD, FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE,
                IdentityClass.ARTICLE_REFERENCE);
        stolen.setSourceReference("EAN13");

        assertBlocks(ImportMappingConfigFactory.CODE_OWNER_NOT_CHANGEABLE, revision, List.of(stolen));
    }

    // --- R-STR-06: geen tweede bron voor wat de revisie al bepaalt ---------------------------------

    @Test
    void refusesAMappingForAFieldThatARevisionColumnAlreadyDetermines() {
        ImportDefinitionRevision revision = revision();
        ImportFieldCatalogEntry description = new ImportFieldCatalogEntry("DESCRIPTION", "Omschrijving",
                FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE, IdentityClass.NONE, 150);

        assertBlocks(ImportMappingConfigFactory.CODE_FIELD_MAPPING_DUPLICATES_REVISION, revision,
                List.of(mapping(revision, 1, description, "OMSCHRIJVING_2")));
    }

    // --- Canonicalisatieversie --------------------------------------------------------------------

    @Test
    void refusesAVersionOneRevisionThatMapsAPriceComponent() {
        ImportDefinitionRevision revision = revision();
        ImportFieldMapping akp = priceMapping(revision, 1, priceField("AKP_PCT", "AKP", 20), "AKP");

        assertBlocks(ImportMappingConfigFactory.CODE_CANONICALISATION_VERSION_REQUIRED, revision,
                List.of(akp));
    }

    /**
     * Bouwstap 3d: onder versie 2 is een prijscomponent wél volledig verwerkbaar — haar verhouding zit
     * in de prijsvingerafdruk. Tot 3c werd zo'n revisie ook mét versie 2 geblokkeerd.
     */
    @Test
    void acceptsAVersionTwoRevisionThatMapsPriceComponents() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldMapping vkp = priceMapping(revision, 1, priceField("VKP1_PCT", "VKP1", 30), "VKP1");
        ImportFieldMapping akp = priceMapping(revision, 2, priceField("AKP_PCT", "AKP", 20), "AKP");
        akp.setTransformConfig("maxPercentage=250");

        ImportMappingConfig config = factory.from(revision, structure(2), List.of(vkp, akp), List.of());

        assertThat(config.hasPriceComponents()).isTrue();
        // Gesorteerd op componentcode en niet op volgnummer: hernummeren mag de prijsvingerafdruk
        // nooit verschuiven.
        assertThat(config.priceComponentFields())
                .extracting(ImportMappingConfig.FieldMapping::priceComponentCode)
                .containsExactly("AKP", "VKP1");
        assertThat(config.priceComponentFields().get(0).maxPercentage()).isEqualByComparingTo("250");
        assertThat(config.priceComponentFields().get(1).maxPercentage()).isNull();
    }

    /**
     * R-PRI-08: een bovengrens hoort bij een prijscomponent. Op een gewoon veld zou ze nooit gebruikt
     * worden terwijl de beheerder denkt dat er gecontroleerd wordt.
     */
    @Test
    void refusesAMaximumPercentageThatIsNotOnAPriceComponent() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldMapping supporting = mapping(revision, 1, supportingField(), "E_LEV");
        supporting.setTransformConfig("maxPercentage=250");

        assertThatThrownBy(() -> factory.from(revision, structure(2), List.of(supporting), List.of()))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);

        ImportFieldMapping akp = priceMapping(revision, 1, priceField("AKP_PCT", "AKP", 20), "AKP");
        akp.setTransformConfig("maxPercentage=0");
        assertThatThrownBy(() -> factory.from(revision, structure(2), List.of(akp), List.of()))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
    }

    /** Een bedrag wordt bewaard met zes decimalen; meer declareren zou stil afgerond worden. */
    @Test
    void refusesAPriceComponentWithMoreDecimalsThanTheAmountColumnOrWithTheWrongType() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldMapping tooPrecise = priceMapping(revision, 1, priceField("AKP_PCT", "AKP", 20), "AKP");
        tooPrecise.setDecimalScale(8);

        assertThatThrownBy(() -> factory.from(revision, structure(2), List.of(tooPrecise), List.of()))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_MAPPING_TYPE_INCOMPATIBLE);
    }

    /**
     * De munt zit in de prijsvingerafdruk van versie 2. Een versie 1-revisie die ze toch leest, zou de
     * hash van elke bestaande bronstaat verschuiven.
     */
    @Test
    void refusesAVersionOneRevisionThatReadsACurrencyFromTheSource() {
        ImportDefinitionRevision revision = revision();
        revision.setRecordCurrencyField("MUNT");

        assertThatThrownBy(() -> factory.from(revision, structure(), List.of(), List.of()))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_CANONICALISATION_VERSION_REQUIRED);

        ImportDefinitionRevision versionTwo = revisionWithCanonicalisationVersion(2);
        versionTwo.setRecordCurrencyField("MUNT");
        assertThatCode(() -> factory.from(versionTwo, structure(2), List.of(), List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesAVersionOneRevisionThatMapsACriticalReference() {
        ImportDefinitionRevision revision = revision();
        ImportFieldMapping ean = new ImportFieldMapping(revision, 1, referenceField(),
                FieldValueKind.SOURCE_FIELD, FieldDataType.TEXT, FieldOwner.CRITICAL_REFERENCE,
                IdentityClass.ARTICLE_REFERENCE);
        ean.setSourceReference("EAN13");
        ean.setReferenceType("EAN");

        assertBlocks(ImportMappingConfigFactory.CODE_CANONICALISATION_VERSION_REQUIRED, revision,
                List.of(ean));
    }

    @Test
    void refusesAVersionOneRevisionThatMapsAnyTargetFieldAtAll() {
        ImportDefinitionRevision revision = revision();

        // Versie 1 hasht enkel de omschrijving: een wijziging in een gemapt veld zou onzichtbaar
        // blijven in de delta. Dat is geen detail maar het verschil tussen "gewijzigd" en "ongewijzigd".
        assertBlocks(ImportMappingConfigFactory.CODE_CANONICALISATION_VERSION_REQUIRED, revision,
                List.of(mapping(revision, 1, supportingField(), "E_LEV")));
    }

    // --- R-REC-07: de transformatieconfiguratie wordt vóór het lezen geparsed -----------------------

    @Test
    void parsesEveryTransformationOnceBeforeASingleByteIsRead() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldMapping translated = mapping(revision, 1, supportingField(), "E_LEV");
        translated.setTransformKind(FieldTransformKind.MAP);
        translated.setTransformConfig("values=A>1|B>2;caseSensitive=true");

        ImportMappingConfig config = factory.from(revision, structure(2), List.of(translated), List.of());

        assertThat(config.fields()).singleElement().satisfies(field -> {
            assertThat(field.transform()).isInstanceOf(FieldTransform.Translate.class);
            assertThat(((FieldTransform.Translate) field.transform()).values())
                    .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("A", "1", "B", "2"));
            assertThat(field.valueFormat()).isNotNull();
        });
    }

    @Test
    void refusesATransformationWhoseConfigurationIsUnusable() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);

        // Een vertaling zonder tabel, een splitsing zonder positie, een onbekende instelling en een
        // deling door een vaste nul: stuk voor stuk configuratie die pas bij regel 1 zou opvallen.
        assertBlocksTransform(revision, FieldTransformKind.MAP, null);
        assertBlocksTransform(revision, FieldTransformKind.MAP, "values=A|B");
        assertBlocksTransform(revision, FieldTransformKind.SPLIT, "separator=-");
        assertBlocksTransform(revision, FieldTransformKind.PREFIX, "prefix=ART-;onbekend=x");
        assertBlocksTransform(revision, FieldTransformKind.DIVIDE, "operand=0");
        assertBlocksTransform(revision, FieldTransformKind.DIVIDE, "operand=2;operandField=KOL");
        assertBlocksTransform(revision, FieldTransformKind.CONCAT, "sources=");
        assertBlocksTransform(revision, FieldTransformKind.NONE, "dateFormat=dd/MM/yyyy");
    }

    @Test
    void refusesADerivedFieldWhoseTransformationCannotBuildAValue() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldMapping derived = mapping(revision, 1, supportingField(), null);
        derived.setValueKind(FieldValueKind.DERIVED);
        derived.setTransformKind(FieldTransformKind.PREFIX);
        derived.setTransformConfig("prefix=ART-");

        assertBlocks(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID, revision, List.of(derived));
    }

    @Test
    void acceptsADerivedFieldThatConcatenatesTwoSourceColumns() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldMapping derived = mapping(revision, 1, supportingField(), null);
        derived.setValueKind(FieldValueKind.DERIVED);
        derived.setTransformKind(FieldTransformKind.CONCAT);
        derived.setTransformConfig("sources=LEVERANCIER|REFERENTIE;separator=-");

        ImportMappingConfig config = factory.from(revision, structure(2), List.of(derived), List.of());

        assertThat(config.fields()).singleElement().satisfies(field ->
                assertThat(field.transform()).isInstanceOf(FieldTransform.Concat.class));
    }

    /** Een datumveld mag zijn bronformaat en tijdzone in de bestaande {@code transform_config} zetten. */
    @Test
    void readsTheDeclaredDateFormatAndZoneOfADateField() {
        ImportDefinitionRevision revision = revisionWithCanonicalisationVersion(2);
        ImportFieldCatalogEntry target = new ImportFieldCatalogEntry("E_SUPPLIER", "Geldig vanaf",
                FieldDataType.DATE, FieldOwner.CATALOG_SOURCE, IdentityClass.NONE, 130);
        ImportFieldMapping date = mapping(revision, 1, target, "VANAF");
        date.setIdentityClass(IdentityClass.NONE);
        date.setTransformConfig("dateFormat=dd/MM/yyyy;zone=Europe/Brussels");

        ImportMappingConfig config = factory.from(revision, structure(2), List.of(date), List.of());

        assertThat(config.fields()).singleElement().satisfies(field -> {
            assertThat(field.valueFormat().datePattern()).isEqualTo("dd/MM/yyyy");
            assertThat(field.valueFormat().dateFormatter()).isNotNull();
            assertThat(field.valueFormat().zone()).isEqualTo(java.time.ZoneId.of("Europe/Brussels"));
        });
    }

    // --- Recordfilters ----------------------------------------------------------------------------

    @Test
    void refusesAFilterWithoutAUsableColumnOperatorOrCompareValue() {
        ImportDefinitionRevision revision = revision();
        ImportRecordFilter blankColumn = filter(revision, 1, "  ", FilterOperator.EQUALS, "BENL",
                FilterOutcome.INCLUDE);
        ImportRecordFilter emptyContains = filter(revision, 1, "CULTURE", FilterOperator.CONTAINS, "  ",
                FilterOutcome.INCLUDE);
        ImportRecordFilter targetStage = filter(revision, 1, "LEVGROEP", FilterOperator.EQUALS, "35",
                FilterOutcome.INCLUDE);
        targetStage.setFilterStage(FilterStage.TARGET_FIELD);

        assertBlocksFilter(revision, blankColumn);
        // 'bevat een lege waarde' zou elke regel matchen: dat is nooit wat de beheerder bedoelde.
        assertBlocksFilter(revision, emptyContains);
        // Een doelveldfilter wordt geweigerd in plaats van genegeerd: negeren zou records importeren
        // die bewust buiten de scope gezet zijn.
        assertBlocksFilter(revision, targetStage);
    }

    @Test
    void refusesTwoFiltersWithTheSameSequenceNumber() {
        ImportDefinitionRevision revision = revision();

        assertThatThrownBy(() -> factory.from(revision, structure(), List.of(),
                List.of(filter(revision, 1, "CULTURE", FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE),
                        filter(revision, 1, "STATUS", FilterOperator.EQUALS, "EOL", FilterOutcome.EXCLUDE))))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_FILTER_INVALID);
    }

    /** Een lege vergelijkingswaarde is bij EQUALS wél betekenisvol: "de waarde moet leeg zijn". */
    @Test
    void acceptsAnEmptyCompareValueForAnEqualityFilter() {
        ImportDefinitionRevision revision = revision();

        assertThatCode(() -> factory.from(revision, structure(), List.of(),
                List.of(filter(revision, 1, "STATUS", FilterOperator.NOT_EQUALS, "", FilterOutcome.INCLUDE))))
                .doesNotThrowAnyException();
    }

    // --- Helpers -----------------------------------------------------------------------------------

    private void assertBlocks(String expectedCode, ImportDefinitionRevision revision,
                              List<ImportFieldMapping> mappings) {
        assertThatThrownBy(() -> factory.from(revision, structure(), mappings, List.of()))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(expectedCode);
    }

    private void assertBlocksTransform(ImportDefinitionRevision revision, FieldTransformKind kind,
                                       String transformConfig) {
        ImportFieldMapping mapping = mapping(revision, 1, supportingField(), "E_LEV");
        mapping.setTransformKind(kind);
        mapping.setTransformConfig(transformConfig);

        assertThatThrownBy(() -> factory.from(revision, structure(2), List.of(mapping), List.of()))
                .as("%s with '%s'", kind, transformConfig)
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_TRANSFORM_INVALID);
    }

    private void assertBlocksFilter(ImportDefinitionRevision revision, ImportRecordFilter filter) {
        assertThatThrownBy(() -> factory.from(revision, structure(), List.of(), List.of(filter)))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(ImportMappingConfigFactory.CODE_FILTER_INVALID);
    }

    private static SourceStructureConfig structure() {
        return structure(1);
    }

    private static SourceStructureConfig structure(int canonicalisationVersion) {
        return new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"', true, 1,
                FieldReferenceKind.HEADER_NAME, null, IdentityProfileKind.THREE_PART, "LEVERANCIER",
                "GROEP", "REFERENTIE", null, "PRIJS", "OMSCHRIJVING", canonicalisationVersion);
    }

    private static ImportDefinitionRevision revision() {
        return revisionWithCanonicalisationVersion(1);
    }

    private static ImportDefinitionRevision revisionWithCanonicalisationVersion(int version) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setRecordCanonicalisationVersion(version);
        return revision;
    }

    private static ImportFieldCatalogEntry supportingField() {
        return new ImportFieldCatalogEntry("E_SUPPLIER", "Externe leveranciersidentiteit",
                FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE, IdentityClass.SUPPORTING, 130);
    }

    private static ImportFieldCatalogEntry referenceField() {
        ImportFieldCatalogEntry ean = new ImportFieldCatalogEntry("EAN", "EAN-barcode", FieldDataType.TEXT,
                FieldOwner.CRITICAL_REFERENCE, IdentityClass.ARTICLE_REFERENCE, 90);
        ean.setReferenceType("EAN");
        ean.setOwnerChangeable(false);
        return ean;
    }

    private static ImportFieldCatalogEntry priceField(String code, String componentCode, int sortOrder) {
        ImportFieldCatalogEntry field = new ImportFieldCatalogEntry(code, code, FieldDataType.DECIMAL,
                FieldOwner.PRICE_CONTROL, IdentityClass.NONE, sortOrder);
        field.setPriceComponentCode(componentCode);
        return field;
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

    private static ImportRecordFilter filter(ImportDefinitionRevision revision, int sequenceNumber,
                                             String sourceReference, FilterOperator operator,
                                             String compareValue, FilterOutcome outcome) {
        ImportRecordFilter filter = new ImportRecordFilter(revision, sequenceNumber, sourceReference,
                operator, compareValue, outcome);
        filter.setNullBehaviour(FilterNullBehaviour.EXCLUDE);
        return filter;
    }
}
