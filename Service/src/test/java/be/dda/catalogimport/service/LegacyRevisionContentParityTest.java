package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.domain.FieldOwner;
import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.FilterNullBehaviour;
import be.dda.catalogimport.domain.FilterOperator;
import be.dda.catalogimport.domain.FilterOutcome;
import be.dda.catalogimport.domain.IdentityClass;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportFieldTransformStep;
import be.dda.catalogimport.domain.ImportFieldValueMap;
import be.dda.catalogimport.domain.ImportRecordFilter;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedFilter;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedMapping;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedRevision;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedStep;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedValueMapEntry;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Legacy-config S6: {@link LegacyRevisionContent} neemt de vaste leverancier/groep en de hoofdletters mee, maar enkel als
 * ze niet de default hebben. De {@code migrated_content_hash} van een revisie die vóór S6 gemigreerd werd (020-4) blijft
 * daardoor geldig: de letterlijke waarde hieronder is berekend met de implementatie van vóór S6.
 */
class LegacyRevisionContentParityTest {

    /** {@code ofRevision} van {@link #preS6Revision()} zonder mappings en filters, vóór S6. */
    private static final String PRE_S6 = "7cf361c6a323921998ad7cae9e59a4f75bee752749dd55f46e794754aaa102e1";

    /**
     * S8a: {@code ofRevision} van {@link #s2MigratedRevision()} met de S2-gemigreerde MAP-mapping
     * ({@code values=DOOS>BOX|STK>PCE;...}), berekend met de implementatie van vóór S8a (commit 3637455).
     */
    private static final String PRE_S8A_WITH_VALUES_MAP =
            "480fe0a19fbe1a081aec7f089cf157655ba8f407ee6980304cc601983dcf2b18";

    /**
     * S8c: {@code ofRevision} van {@link #s2MigratedRevision()} met de S8a-gemigreerde MAP-mapping en haar twee
     * vertaalrijen (zoals in {@link #theValueMapRowsCountInThePlannedAndTheWrittenContent()}), berekend met de
     * implementatie van vóór S8c (commit 0a6c10e).
     */
    private static final String PRE_S8C_WITH_VALUE_MAP_ROWS =
            "2cc2e14dd0e8fab18542dfcfd88c51116733264e01dcdb13410f44803351982f";

    /**
     * S8f: {@code ofRevision} van {@link #s2MigratedRevision()} met twee losse filters (een S8e-uitsluiting en een
     * S2-include, {@link #s8eFilters}), berekend met de implementatie van voor S8f (commit 8833248).
     */
    private static final String PRE_S8F_WITH_FILTERS =
            "8497b98a44f31966a30301e6d97ee5a91d82dce87ffbf69991ea5e474805f429";

    @Test
    void aRevisionMigratedBeforeS6KeepsItsContentHash() {
        assertThat(LegacyRevisionContent.ofRevision(preS6Revision(), List.of(), List.of(), List.of())).isEqualTo(PRE_S6);
    }

    @Test
    void thePlannedContentEqualsTheWrittenRevisionIncludingTheS6Fields() {
        PlannedRevision planned = new PlannedRevision("UTF-8", ";", true, 1,
                IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, null, "4", "1", "5", "7", "2", "ACME", null, true,
                List.of(), List.of());
        ImportDefinitionRevision written = preS6Revision();
        written.setIdentitySupplierField(null);
        written.setIdentitySupplierFixedValue("ACME");
        written.setIdentityUppercase(true);

        String plannedHash = LegacyRevisionContent.ofPlanned(planned, "CSV", code -> Criticality.NON_CRITICAL);

        assertThat(plannedHash).isEqualTo(LegacyRevisionContent.ofRevision(written, List.of(), List.of(), List.of()))
                .isNotEqualTo(PRE_S6);
        // Enkel hoofdletters (de gewone situatie na S6 voor een ongewijzigde legacy-definitie): een andere inhoud, dus
        // een opvolgende DRAFT met IDENTITY_CHANGE, en nooit "ongewijzigd".
        ImportDefinitionRevision uppercaseOnly = preS6Revision();
        uppercaseOnly.setIdentityUppercase(true);
        assertThat(LegacyRevisionContent.ofRevision(uppercaseOnly, List.of(), List.of(), List.of())).isNotEqualTo(PRE_S6);
    }

    /** S8a: zonder vertaalrijen byte-identiek, ook met een {@code values=}-mapping (zoals vóór S8a gemigreerd). */
    @Test
    void aRevisionWithAValuesMapMigratedBeforeS8aKeepsItsContentHash() {
        ImportDefinitionRevision revision = s2MigratedRevision();

        assertThat(LegacyRevisionContent.ofRevision(revision, List.of(s2MapMapping(revision)), List.of(), List.of()))
                .isEqualTo(PRE_S8A_WITH_VALUES_MAP);
    }

    /**
     * S8a: de vertaalrijen tellen mee ({@code V, volgnummer, bron, doel} achter hun mapping): plan en geschreven revisie
     * zijn gelijk, een andere doelwaarde of volgorde geeft een andere inhoud, en dezelfde legacy-tabel als rijen in
     * plaats van {@code values=} is een andere inhoud dan vóór S8a (dus een opvolgende DRAFT, nooit "ongewijzigd").
     */
    @Test
    void theValueMapRowsCountInThePlannedAndTheWrittenContent() throws Exception {
        ImportDefinitionRevision revision = s2MigratedRevision();
        ImportFieldMapping mapping = s2MapMapping(revision);
        mapping.setTransformConfig("caseSensitive=true;decimalSeparator=,");
        setId(mapping, 41L);
        List<ImportFieldValueMap> rows = List.of(new ImportFieldValueMap(mapping, 2, "STK", "PCE"),
                new ImportFieldValueMap(mapping, 1, "DOOS", "BOX"));
        PlannedRevision planned = new PlannedRevision("UTF-8", ";", true, 1,
                IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, "3", "4", "1", "5", "7", "2", null, null, true,
                List.of(new PlannedMapping(1, "VKP1_PCT", FieldValueKind.SOURCE_FIELD, "8", null,
                        FieldTransformKind.MAP, "caseSensitive=true;decimalSeparator=,",
                        List.of(new PlannedValueMapEntry(1, "DOOS", "BOX"), new PlannedValueMapEntry(2, "STK", "PCE")))),
                List.of());

        String written = LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), rows);

        assertThat(LegacyRevisionContent.ofPlanned(planned, revision.getStructureFormat(),
                code -> Criticality.NON_CRITICAL)).isEqualTo(written);
        assertThat(written).isNotEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(),
                List.of()));
        assertThat(written).isNotEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(),
                List.of(new ImportFieldValueMap(mapping, 1, "DOOS", "BOX"),
                        new ImportFieldValueMap(mapping, 2, "STK", "PCS"))));
        assertThat(written).isNotEqualTo(PRE_S8A_WITH_VALUES_MAP);
        // Rijen van een andere mapping tellen niet mee voor deze mapping.
        ImportFieldMapping other = s2MapMapping(revision);
        setId(other, 42L);
        assertThat(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(),
                List.of(new ImportFieldValueMap(other, 1, "X", "Y")))).isEqualTo(
                LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), List.of()));
    }

    /** S8c: zonder ketenstappen byte-identiek aan vóór S8c, ook met vertaalrijen (S8a) en via de nieuwe overload. */
    @Test
    void aRevisionWithoutStepsKeepsItsContentHash() throws Exception {
        ImportDefinitionRevision revision = s2MigratedRevision();
        ImportFieldMapping mapping = s2MapMapping(revision);
        mapping.setTransformConfig("caseSensitive=true;decimalSeparator=,");
        setId(mapping, 41L);
        List<ImportFieldValueMap> rows = List.of(new ImportFieldValueMap(mapping, 1, "DOOS", "BOX"),
                new ImportFieldValueMap(mapping, 2, "STK", "PCE"));

        assertThat(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), rows))
                .isEqualTo(PRE_S8C_WITH_VALUE_MAP_ROWS);
        assertThat(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), rows, List.of()))
                .isEqualTo(PRE_S8C_WITH_VALUE_MAP_ROWS);
        assertThat(LegacyRevisionContent.ofRevision(preS6Revision(), List.of(), List.of(), List.of(), List.of()))
                .isEqualTo(PRE_S6);
        ImportDefinitionRevision s2 = s2MigratedRevision();
        assertThat(LegacyRevisionContent.ofRevision(s2, List.of(s2MapMapping(s2)), List.of(), List.of(), List.of()))
                .isEqualTo(PRE_S8A_WITH_VALUES_MAP);
    }

    /**
     * S8c: de ketenstappen tellen mee ({@code T, stapnummer, soort, config} achter hun mapping, vóór de vertaalrijen):
     * plan en geschreven revisie zijn gelijk; een andere soort, config of volgorde geeft een andere inhoud; dezelfde
     * legacy-regel als CHAIN is een andere inhoud dan de S8a-vorm (dus een opvolgende DRAFT, nooit "ongewijzigd").
     */
    @Test
    void theStepsCountInThePlannedAndTheWrittenContent() throws Exception {
        ImportDefinitionRevision revision = s2MigratedRevision();
        ImportFieldMapping mapping = s2MapMapping(revision);
        mapping.setTransformKind(FieldTransformKind.CHAIN);
        mapping.setTransformConfig("decimalSeparator=,");
        setId(mapping, 41L);
        List<ImportFieldValueMap> rows = List.of(new ImportFieldValueMap(mapping, 1, "P-DOOS", "BOX"));
        List<ImportFieldTransformStep> steps = List.of(
                new ImportFieldTransformStep(mapping, 2, FieldTransformKind.MAP, "caseSensitive=true"),
                new ImportFieldTransformStep(mapping, 1, FieldTransformKind.AFFIX, "prefix=P-"));
        PlannedRevision planned = new PlannedRevision("UTF-8", ";", true, 1,
                IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, "3", "4", "1", "5", "7", "2", null, null, true,
                List.of(new PlannedMapping(1, "VKP1_PCT", FieldValueKind.SOURCE_FIELD, "8", null,
                        FieldTransformKind.CHAIN, "decimalSeparator=,",
                        List.of(new PlannedValueMapEntry(1, "P-DOOS", "BOX")),
                        List.of(new PlannedStep(1, FieldTransformKind.AFFIX, "prefix=P-"),
                                new PlannedStep(2, FieldTransformKind.MAP, "caseSensitive=true")))),
                List.of());

        String written = LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), rows, steps);

        assertThat(LegacyRevisionContent.ofPlanned(planned, revision.getStructureFormat(),
                code -> Criticality.NON_CRITICAL)).isEqualTo(written);
        assertThat(written).isNotEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), rows))
                .isNotEqualTo(PRE_S8C_WITH_VALUE_MAP_ROWS);
        assertThat(written).isNotEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), rows,
                List.of(new ImportFieldTransformStep(mapping, 1, FieldTransformKind.AFFIX, "prefix=Q-"),
                        new ImportFieldTransformStep(mapping, 2, FieldTransformKind.MAP, "caseSensitive=true"))));
        assertThat(written).isNotEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), rows,
                List.of(new ImportFieldTransformStep(mapping, 1, FieldTransformKind.PREFIX, "prefix=P-"),
                        new ImportFieldTransformStep(mapping, 2, FieldTransformKind.MAP, "caseSensitive=true"))));
        // Stappen van een andere mapping tellen niet mee voor deze mapping.
        ImportFieldMapping other = s2MapMapping(revision);
        setId(other, 42L);
        assertThat(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), rows,
                List.of(new ImportFieldTransformStep(other, 1, FieldTransformKind.UPPER, null))))
                .isEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(mapping), List.of(), rows));
    }

    /**
     * S8e: een filter draagt zijn null-gedrag mee in de inhoud. Een S2-include ({@code INCLUDE EQUALS}, null
     * {@code EXCLUDE}) hasht zoals de geschreven rij (dus byte-identiek aan een vóór S8e gemigreerde revisie: de hash van
     * {@code ofRevision} is niet gewijzigd); een uitsluiting met {@code COMPARE_AS_EMPTY} is een andere inhoud.
     */
    @Test
    void theFilterNullBehaviourCountsInThePlannedAndTheWrittenContent() {
        ImportDefinitionRevision revision = s2MigratedRevision();
        PlannedRevision include = planned(new PlannedFilter(1, "6", FilterOperator.EQUALS, "BENL", FilterOutcome.INCLUDE,
                true, FilterNullBehaviour.EXCLUDE));
        ImportRecordFilter written = new ImportRecordFilter(revision, 1, "6", FilterOperator.EQUALS, "BENL",
                FilterOutcome.INCLUDE);
        written.setCaseSensitive(true);
        written.setNullBehaviour(FilterNullBehaviour.EXCLUDE);

        assertThat(LegacyRevisionContent.ofPlanned(include, revision.getStructureFormat(),
                code -> Criticality.NON_CRITICAL)).isEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(),
                List.of(written), List.of()));

        PlannedRevision exclude = planned(new PlannedFilter(1, "6", FilterOperator.EQUALS, "BENL", FilterOutcome.EXCLUDE,
                true, FilterNullBehaviour.COMPARE_AS_EMPTY));
        ImportRecordFilter writtenExclude = new ImportRecordFilter(revision, 1, "6", FilterOperator.EQUALS, "BENL",
                FilterOutcome.EXCLUDE);
        writtenExclude.setCaseSensitive(true);
        writtenExclude.setNullBehaviour(FilterNullBehaviour.COMPARE_AS_EMPTY);
        String plannedExclude = LegacyRevisionContent.ofPlanned(exclude, revision.getStructureFormat(),
                code -> Criticality.NON_CRITICAL);
        assertThat(plannedExclude).isEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(),
                List.of(writtenExclude), List.of()));
        assertThat(plannedExclude).isNotEqualTo(LegacyRevisionContent.ofPlanned(planned(new PlannedFilter(1, "6",
                FilterOperator.EQUALS, "BENL", FilterOutcome.EXCLUDE, true, FilterNullBehaviour.EXCLUDE)),
                revision.getStructureFormat(), code -> Criticality.NON_CRITICAL));
    }

    /** S8f: een revisie met losse filters (zonder groep) houdt byte voor byte haar inhoudshash. */
    @Test
    void aRevisionWithLooseFiltersKeepsItsContentHash() {
        ImportDefinitionRevision revision = s2MigratedRevision();

        assertThat(LegacyRevisionContent.ofRevision(revision, List.of(), s8eFilters(revision), List.of()))
                .isEqualTo(PRE_S8F_WITH_FILTERS);
    }

    /**
     * S8f: de EN-groep telt mee als {@code group=n}, gelijk in plan en geschreven rij; een andere groep of geen groep is
     * een andere inhoud.
     */
    @Test
    void theMatchGroupCountsInThePlannedAndTheWrittenContent() {
        ImportDefinitionRevision revision = s2MigratedRevision();
        PlannedRevision grouped = new PlannedRevision("UTF-8", ";", true, 1, IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE,
                "3", "4", "1", "5", "7", "2", null, null, true, List.of(), List.of(
                        new PlannedFilter(1, "3", FilterOperator.EQUALS, "ACME", FilterOutcome.EXCLUDE, false,
                                FilterNullBehaviour.COMPARE_AS_EMPTY, 1),
                        new PlannedFilter(2, "4", FilterOperator.EQUALS, "", FilterOutcome.EXCLUDE, false,
                                FilterNullBehaviour.COMPARE_AS_EMPTY, 1)));
        ImportRecordFilter first = groupedRow(revision, 1, "3", "ACME", 1);
        ImportRecordFilter second = groupedRow(revision, 2, "4", "", 1);

        String plannedHash = LegacyRevisionContent.ofPlanned(grouped, revision.getStructureFormat(),
                code -> Criticality.NON_CRITICAL);
        assertThat(plannedHash).isEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(), List.of(first, second),
                List.of()));

        second.setMatchGroup(2);
        assertThat(LegacyRevisionContent.ofRevision(revision, List.of(), List.of(first, second), List.of()))
                .isNotEqualTo(plannedHash);
        first.setMatchGroup(null);
        second.setMatchGroup(null);
        assertThat(LegacyRevisionContent.ofRevision(revision, List.of(), List.of(first, second), List.of()))
                .isNotEqualTo(plannedHash);
        // Zonder groep is het plan gelijk aan de losse rijen (de vorm van voor S8f).
        PlannedRevision loose = new PlannedRevision("UTF-8", ";", true, 1, IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE,
                "3", "4", "1", "5", "7", "2", null, null, true, List.of(), List.of(
                        new PlannedFilter(1, "3", FilterOperator.EQUALS, "ACME", FilterOutcome.EXCLUDE, false,
                                FilterNullBehaviour.COMPARE_AS_EMPTY),
                        new PlannedFilter(2, "4", FilterOperator.EQUALS, "", FilterOutcome.EXCLUDE, false,
                                FilterNullBehaviour.COMPARE_AS_EMPTY)));
        assertThat(LegacyRevisionContent.ofPlanned(loose, revision.getStructureFormat(), code -> Criticality.NON_CRITICAL))
                .isEqualTo(LegacyRevisionContent.ofRevision(revision, List.of(), List.of(first, second), List.of()));
    }

    private static ImportRecordFilter groupedRow(ImportDefinitionRevision revision, int sequence, String column,
                                                 String value, Integer group) {
        ImportRecordFilter row = new ImportRecordFilter(revision, sequence, column, FilterOperator.EQUALS, value,
                FilterOutcome.EXCLUDE);
        row.setNullBehaviour(FilterNullBehaviour.COMPARE_AS_EMPTY);
        row.setMatchGroup(group);
        return row;
    }

    private static List<ImportRecordFilter> s8eFilters(ImportDefinitionRevision revision) {
        ImportRecordFilter exclude = new ImportRecordFilter(revision, 1, "6", FilterOperator.EQUALS, "BENL",
                FilterOutcome.EXCLUDE);
        exclude.setCaseSensitive(true);
        exclude.setNullBehaviour(FilterNullBehaviour.COMPARE_AS_EMPTY);
        ImportRecordFilter include = new ImportRecordFilter(revision, 2, "7", FilterOperator.CONTAINS, "EOL",
                FilterOutcome.INCLUDE);
        include.setCaseSensitive(true);
        include.setNullBehaviour(FilterNullBehaviour.EXCLUDE);
        return List.of(exclude, include);
    }

    private static PlannedRevision planned(PlannedFilter filter) {
        return new PlannedRevision("UTF-8", ";", true, 1, IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, "3", "4", "1",
                "5", "7", "2", null, null, true, List.of(), List.of(filter));
    }

    private static ImportDefinitionRevision s2MigratedRevision() {
        ImportDefinitionRevision revision = preS6Revision();
        revision.setIdentityUppercase(true);
        return revision;
    }

    private static ImportFieldMapping s2MapMapping(ImportDefinitionRevision revision) {
        ImportFieldCatalogEntry target = new ImportFieldCatalogEntry("VKP1_PCT", "VKP1_PCT", FieldDataType.DECIMAL,
                FieldOwner.CATALOG_SOURCE, IdentityClass.NONE, 10);
        ImportFieldMapping mapping = new ImportFieldMapping(revision, 1, target, FieldValueKind.SOURCE_FIELD,
                FieldDataType.DECIMAL, FieldOwner.CATALOG_SOURCE, IdentityClass.NONE);
        mapping.setSourceReference("8");
        mapping.setTransformKind(FieldTransformKind.MAP);
        mapping.setTransformConfig("values=DOOS>BOX|STK>PCE;caseSensitive=true;decimalSeparator=,");
        return mapping;
    }

    /** De rijen worden op {@code mapping.id} gekoppeld; zonder database zetten we het id hier zelf. */
    private static void setId(ImportFieldMapping mapping, long id) throws ReflectiveOperationException {
        Field field = ImportFieldMapping.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(mapping, id);
    }

    private static ImportDefinitionRevision preS6Revision() {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1,
                IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE, "tester");
        revision.setIdentitySupplierField("3");
        revision.setIdentitySupplierGroupField("4");
        revision.setIdentitySupplierReferenceField("1");
        revision.setIdentityDiscountCodeField("5");
        revision.setRecordBasePriceField("7");
        revision.setRecordDescriptionField("2");
        revision.setStructureDelimiter(";");
        revision.setStructureFieldReferenceKind("COLUMN_INDEX");
        return revision;
    }
}
