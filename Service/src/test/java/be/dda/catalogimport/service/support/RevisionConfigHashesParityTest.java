package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import org.junit.jupiter.api.Test;

/**
 * Slice S6 van de legacy-config-migratie ({@code docs/design/legacy-config-bundle-design.md} par. 5 "Bouw van de
 * configuratiehash", par. 10): de nieuwe revisiekolommen ({@code identity_supplier_fixed_value},
 * {@code identity_supplier_group_fixed_value}, {@code identity_uppercase}) tellen in {@link RevisionConfigHashes} enkel
 * mee als ze <b>niet</b> de default hebben. Een bestaande revisie (geen vaste waarde, geen hoofdletters) houdt dus byte
 * voor byte haar vier configuratiehashes; anders zou §14.14 zijn traceerbaarheid verliezen.
 * <p>
 * De verwachte waarden hieronder zijn letterlijke SHA-256-hexwaarden, berekend vóór S6 (met {@code sha256sum} over de
 * canonieke tekst en bevestigd tegen de implementatie van vóór deze slice). Ze worden bewust niet opnieuw afgeleid uit
 * {@link RevisionConfigHashes}: een wijziging aan de canonieke opbouw moet deze test doen falen.
 */
class RevisionConfigHashesParityTest {

    private static final String ACCESS = "e9e1644c20d9c7152b73e30d96ae1c54351cb4f368a4ce7565ec4a454eb1069c";
    private static final String STRUCTURE = "f50791a404a335207875fac6f4dda5308786a86211deb0261b270b8e159776a2";
    private static final String RECORD = "744f3ee9a39e435f1529f9bc43d6c27dbd51ab303ffc2a99299bc9ef485fb01e";
    private static final String COMPOSITE = "4db93bc6b07104ace76c4e74e6c481b5d4c6828234ee1df1406aa5ebb39c7959";

    @Test
    void aRevisionWithoutFixedValuesOrUppercaseKeepsItsPreS6HashesByteForByte() {
        ImportDefinitionRevision revision = existingRevision();

        RevisionConfigHashes.applyAll(revision);

        assertThat(revision.getAccessConfigHash()).isEqualTo(ACCESS);
        assertThat(revision.getStructureConfigHash()).isEqualTo(STRUCTURE);
        assertThat(revision.getRecordRulesConfigHash()).isEqualTo(RECORD);
        assertThat(revision.getCompositeConfigHash()).isEqualTo(COMPOSITE);
    }

    @Test
    void explicitlySettingTheDefaultsKeepsTheSameHashes() {
        ImportDefinitionRevision revision = existingRevision();
        revision.setIdentitySupplierFixedValue(null);
        revision.setIdentitySupplierGroupFixedValue(null);
        revision.setIdentityUppercase(false);

        RevisionConfigHashes.applyAll(revision);

        assertThat(revision.getRecordRulesConfigHash()).isEqualTo(RECORD);
        assertThat(revision.getCompositeConfigHash()).isEqualTo(COMPOSITE);
    }

    @Test
    void uppercaseChangesOnlyTheRecordAndCompositeHash() {
        ImportDefinitionRevision revision = existingRevision();
        revision.setIdentityUppercase(true);

        RevisionConfigHashes.applyAll(revision);

        assertThat(revision.getAccessConfigHash()).isEqualTo(ACCESS);
        assertThat(revision.getStructureConfigHash()).isEqualTo(STRUCTURE);
        assertThat(revision.getRecordRulesConfigHash()).isNotEqualTo(RECORD);
        assertThat(revision.getCompositeConfigHash()).isNotEqualTo(COMPOSITE);
    }

    @Test
    void aFixedValueReplacingTheColumnGivesItsOwnHashPerPart() {
        ImportDefinitionRevision supplierFixed = existingRevision();
        supplierFixed.setIdentitySupplierField(null);
        supplierFixed.setIdentitySupplierFixedValue("LEVERANCIER");
        RevisionConfigHashes.applyAll(supplierFixed);

        ImportDefinitionRevision groupFixed = existingRevision();
        groupFixed.setIdentitySupplierGroupField(null);
        groupFixed.setIdentitySupplierGroupFixedValue("LEVERANCIER");
        RevisionConfigHashes.applyAll(groupFixed);

        // Een kolom met de naam "LEVERANCIER" en een vaste waarde "LEVERANCIER" zijn verschillende configuraties.
        assertThat(supplierFixed.getRecordRulesConfigHash()).isNotEqualTo(RECORD)
                .isNotEqualTo(groupFixed.getRecordRulesConfigHash());
        // Andere vaste waarde, andere hash.
        ImportDefinitionRevision otherValue = existingRevision();
        otherValue.setIdentitySupplierField(null);
        otherValue.setIdentitySupplierFixedValue("ANDERE");
        RevisionConfigHashes.applyAll(otherValue);
        assertThat(otherValue.getRecordRulesConfigHash()).isNotEqualTo(supplierFixed.getRecordRulesConfigHash());
    }

    /** Zoals een revisie van vóór S6: drie kolommen, omschrijving, geen munt, canonicalisatieversie 2. */
    private static ImportDefinitionRevision existingRevision() {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1,
                IdentityProfileKind.THREE_PART, "tester");
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setRecordCanonicalisationVersion(2);
        revision.setStructureDelimiter(";");
        revision.setStructureExpectedColumnCount(7);
        return revision;
    }
}
