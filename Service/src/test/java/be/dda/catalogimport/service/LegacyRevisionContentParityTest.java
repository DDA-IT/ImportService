package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.domain.FieldOwner;
import be.dda.catalogimport.domain.FieldTransformKind;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityClass;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.domain.ImportFieldTransformStep;
import be.dda.catalogimport.domain.ImportFieldValueMap;
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
