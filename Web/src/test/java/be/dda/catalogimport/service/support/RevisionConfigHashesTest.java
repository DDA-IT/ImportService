package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Bouwstap 5a (sjabloon-materialisatie-design.md §2, §3): {@link RevisionConfigHashes} is verhuisd uit
 * de private {@code hash(...)} van {@code SetupService}, byte-identiek. Deze test bewijst dat door de
 * oude berekening hier onafhankelijk te reconstrueren (niet door {@link RevisionConfigHashes} aan te
 * roepen) en tegen de verhuisde implementatie te vergelijken, voor dezelfde configuratie-onderdelen als
 * {@code SetupService.createRevision} gebruikt.
 */
class RevisionConfigHashesTest {

    @Test
    void hashIsByteIdenticalToTheOldSetupServiceImplementation() {
        assertHashMatchesOldImplementation("access", "THREE_PART", "FULL_ARCHIVE");
        assertHashMatchesOldImplementation("structure", "CSV", "UTF-8", ";", ",", "true", "1",
                "HEADER_NAME", "5");
        assertHashMatchesOldImplementation("record", "THREE_PART", "SUPPLIER_FIELD", "GROUP_FIELD",
                "REFERENCE_FIELD", "null", "PRICE_FIELD", "null", "null", "1");
        assertHashMatchesOldImplementation("composite", "abc123", "def456", "ghi789");
    }

    @Test
    void differentPartsProduceDifferentHashes() {
        String base = RevisionConfigHashes.hash("record", "A", "B");
        String changed = RevisionConfigHashes.hash("record", "A", "C");
        assertThat(base).isNotEqualTo(changed);
    }

    @Test
    void isDeterministicForTheSameInput() {
        String first = RevisionConfigHashes.hash("structure", "CSV", "UTF-8");
        String second = RevisionConfigHashes.hash("structure", "CSV", "UTF-8");
        assertThat(first).isEqualTo(second);
    }

    /**
     * Bouwstap 5c: {@link RevisionConfigHashes#applyAll} zet de vier hashes die
     * {@code SetupService.createRevision} vóór deze bouwstap zelf, regel voor regel, samenstelde. Die
     * vier regels zijn verplaatst zodat de materialisatiewizard exact dezelfde berekening op een
     * afgeleide revisie kan doen. Deze test reconstrueert de <b>oude</b> samenstelling hier
     * onafhankelijk — inclusief de volgorde van de onderdelen per laag — en bewijst zo dat geen enkele
     * bestaande hash van waarde verandert.
     */
    @Test
    void applyAllIsByteIdenticalToTheOldSetupServiceComposition() {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1,
                IdentityProfileKind.THREE_PART, "tester");
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setIdentityDiscountCodeField(null);
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordDescriptionField("OMSCHRIJVING");
        revision.setRecordCurrencyField(null);
        revision.setRecordCanonicalisationVersion(2);
        revision.setStructureDelimiter(";");
        revision.setStructureExpectedColumnCount(7);

        String expectedAccess = oldSetupServiceHash("access", "THREE_PART",
                revision.getAccessDeliverySetKind());
        String expectedStructure = oldSetupServiceHash("structure", revision.getStructureFormat(),
                revision.getStructureCharset(), revision.getStructureDelimiter(),
                String.valueOf(revision.getStructureQuoteChar()),
                String.valueOf(revision.isStructureHasHeader()),
                String.valueOf(revision.getStructureHeaderLineNumber()),
                revision.getStructureFieldReferenceKind(),
                String.valueOf(revision.getStructureExpectedColumnCount()));
        String expectedRecord = oldSetupServiceHash("record", "THREE_PART", "LEVERANCIER", "GROEP",
                "REFERENTIE", "null", "PRIJS", "OMSCHRIJVING", "null", "2");
        String expectedComposite = oldSetupServiceHash("composite", expectedAccess, expectedStructure,
                expectedRecord);

        RevisionConfigHashes.applyAll(revision);

        assertThat(revision.getAccessConfigHash()).isEqualTo(expectedAccess);
        assertThat(revision.getStructureConfigHash()).isEqualTo(expectedStructure);
        assertThat(revision.getRecordRulesConfigHash()).isEqualTo(expectedRecord);
        assertThat(revision.getCompositeConfigHash()).isEqualTo(expectedComposite);
    }

    /**
     * De kern van §2: twee inhoudelijk verschillende revisies dragen nooit dezelfde configuratiehash.
     * Precies dit maakt het herberekenen bij materialisatie noodzakelijk zodra een bookmarkwaarde een
     * revisieveld invult.
     */
    @Test
    void applyAllChangesTheRecordAndCompositeHashWhenAnIdentityFieldChanges() {
        ImportDefinitionRevision first = minimalRevision();
        RevisionConfigHashes.applyAll(first);
        ImportDefinitionRevision second = minimalRevision();
        second.setIdentitySupplierField("EEN_ANDERE_KOLOM");
        RevisionConfigHashes.applyAll(second);

        assertThat(second.getRecordRulesConfigHash()).isNotEqualTo(first.getRecordRulesConfigHash());
        assertThat(second.getCompositeConfigHash()).isNotEqualTo(first.getCompositeConfigHash());
        // De structuurlaag is niet geraakt en blijft dus wél gelijk: de drie lagen zijn apart versieerbaar.
        assertThat(second.getStructureConfigHash()).isEqualTo(first.getStructureConfigHash());
    }

    private static ImportDefinitionRevision minimalRevision() {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1,
                IdentityProfileKind.THREE_PART, "tester");
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setStructureDelimiter(";");
        return revision;
    }

    private static void assertHashMatchesOldImplementation(String layer, String... parts) {
        assertThat(RevisionConfigHashes.hash(layer, parts)).isEqualTo(oldSetupServiceHash(layer, parts));
    }

    /**
     * Onafhankelijke reconstructie van de OUDE, nu verwijderde private {@code SetupService.hash(...)}:
     * laagnaam gevolgd door elk onderdeel, telkens voorafgegaan door {@code U+001F} (unit separator),
     * SHA-256 over UTF-8-bytes, hexadecimaal. Bewust hier gekopieerd (niet gedeeld) zodat deze test
     * onafhankelijk blijft van de verhuisde implementatie die ze net controleert.
     */
    private static String oldSetupServiceHash(String layer, String... parts) {
        StringBuilder canonical = new StringBuilder(layer);
        for (String part : parts) {
            canonical.append('').append(part);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required but not available", impossible);
        }
    }
}
