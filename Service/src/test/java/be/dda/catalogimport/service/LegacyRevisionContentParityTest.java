package be.dda.catalogimport.service;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.Criticality;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.service.LegacyConfigTranslator.PlannedRevision;
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

    @Test
    void aRevisionMigratedBeforeS6KeepsItsContentHash() {
        assertThat(LegacyRevisionContent.ofRevision(preS6Revision(), List.of(), List.of())).isEqualTo(PRE_S6);
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

        assertThat(plannedHash).isEqualTo(LegacyRevisionContent.ofRevision(written, List.of(), List.of()))
                .isNotEqualTo(PRE_S6);
        // Enkel hoofdletters (de gewone situatie na S6 voor een ongewijzigde legacy-definitie): een andere inhoud, dus
        // een opvolgende DRAFT met IDENTITY_CHANGE, en nooit "ongewijzigd".
        ImportDefinitionRevision uppercaseOnly = preS6Revision();
        uppercaseOnly.setIdentityUppercase(true);
        assertThat(LegacyRevisionContent.ofRevision(uppercaseOnly, List.of(), List.of())).isNotEqualTo(PRE_S6);
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
