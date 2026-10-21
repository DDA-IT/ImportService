package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.DiscountCodeState;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.service.support.CandidateNormaliser.NormalisedCandidate;
import be.dda.catalogimport.service.support.CandidateNormaliser.Result;
import be.dda.catalogimport.service.support.CandidateNormaliser.RowIssue;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Legacy-config S6 ({@code docs/design/legacy-config-bundle-design.md} par. 4, 5 en 8; beslissingslog 2026-10-20 M3 en
 * "datasync-aanpak ... optie iii"): vaste leverancier/groep en hoofdletters in de bronconfiguratie en de normalisatie.
 * Unittest zonder Spring, via de echte fabriek ({@link SourceStructureConfigFactory}) zoals de screening.
 *
 * <h2>Business rule → wat hier bewezen wordt</h2>
 * <ul>
 *   <li>Een vaste leverancier/groep levert die waarde voor élke regel; de kolom wordt niet gelezen en hoeft niet in het
 *       bestand te staan.</li>
 *   <li>Precies één van kolom of vaste waarde: beide → {@code CONFIG_IDENTITY_SOURCE_CONFLICT}, geen →
 *       {@code CONFIG_IDENTITY_FIELD_MISSING} (ook in de stand "alle bevindingen").</li>
 *   <li>{@code identity_uppercase}: {@code abc} en {@code ABC} zijn één identiteit (legacy {@code Upper()}), ook voor een
 *       vaste waarde, maar de kortingscode blijft ongewijzigd (zoals legacy); zonder de schakelaar blijft alles
 *       hoofdlettergevoelig en byte-identiek aan vóór S6.</li>
 * </ul>
 */
class IdentityFixedValueTest {

    private static final SourceStructureConfigFactory FACTORY = new SourceStructureConfigFactory();
    private final CandidateNormaliser normaliser = new CandidateNormaliser();

    // --- Bronconfiguratie ------------------------------------------------------------------------------------------

    @Test
    void aFixedSupplierAndGroupDeclareNoColumn() {
        ImportDefinitionRevision revision = revision();
        revision.setIdentitySupplierField(null);
        revision.setIdentitySupplierFixedValue(" ACME ");
        revision.setIdentitySupplierGroupField(null);
        revision.setIdentitySupplierGroupFixedValue("GRP-A");

        SourceStructureConfig config = FACTORY.from(revision);

        assertThat(config.supplierField()).isNull();
        assertThat(config.supplierFixedValue()).isEqualTo("ACME");
        assertThat(config.supplierGroupField()).isNull();
        assertThat(config.supplierGroupFixedValue()).isEqualTo("GRP-A");
        assertThat(config.declaredFields()).containsExactly("REFERENTIE", "PRIJS");
        assertThat(FACTORY.collect(revision, null).findings()).isEmpty();
    }

    @Test
    void bothOrNeitherSourceIsAConfigurationErrorPerPart() {
        ImportDefinitionRevision both = revision();
        both.setIdentitySupplierGroupFixedValue("GRP-A");
        assertThatThrownBy(() -> FACTORY.from(both)).isInstanceOf(ScreeningBlockedException.class)
                .extracting("code").isEqualTo(SourceStructureConfigFactory.CODE_IDENTITY_SOURCE_CONFLICT);

        ImportDefinitionRevision neither = revision();
        neither.setIdentitySupplierField(null);
        assertThatThrownBy(() -> FACTORY.from(neither)).isInstanceOf(ScreeningBlockedException.class)
                .extracting("code").isEqualTo(SourceStructureConfigFactory.CODE_IDENTITY_FIELD_MISSING);

        // De stand "alle bevindingen" meldt beide delen apart, met hun revisiekolom.
        ImportDefinitionRevision twice = revision();
        twice.setIdentitySupplierFixedValue("ACME");
        twice.setIdentitySupplierGroupField(null);
        List<ConfigFinding> findings = FACTORY.collect(twice, null).findings();
        assertThat(findings).extracting(ConfigFinding::code).containsExactly(
                SourceStructureConfigFactory.CODE_IDENTITY_SOURCE_CONFLICT,
                SourceStructureConfigFactory.CODE_IDENTITY_FIELD_MISSING);
        assertThat(findings).extracting(ConfigFinding::revisionField)
                .containsExactly("supplierField", "supplierGroupField");
    }

    @Test
    void aFixedValueNeedsNoColumnIndexCheck() {
        ImportDefinitionRevision revision = revision();
        revision.setStructureFieldReferenceKind("COLUMN_INDEX");
        revision.setStructureHasHeader(false);
        revision.setIdentitySupplierField(null);
        revision.setIdentitySupplierFixedValue("ACME");
        revision.setIdentitySupplierGroupField("2");
        revision.setIdentitySupplierReferenceField("1");
        revision.setRecordBasePriceField("3");
        revision.setStructureExpectedColumnCount(3);

        assertThat(FACTORY.collect(revision, null).findings()).isEmpty();
        assertThat(FACTORY.from(revision).declaredFields()).containsExactly("2", "1", "3");
    }

    // --- Normalisatie ----------------------------------------------------------------------------------------------

    @Test
    void theFixedValuesApplyToEveryRowAndTheColumnsAreNotRead() {
        ImportDefinitionRevision revision = revision();
        revision.setIdentitySupplierField(null);
        revision.setIdentitySupplierFixedValue("ACME");
        revision.setIdentitySupplierGroupField(null);
        revision.setIdentitySupplierGroupFixedValue("GRP-A");
        SourceStructureConfig config = FACTORY.from(revision);

        // Een bestand zonder leveranciers- en groepskolom.
        NormalisedCandidate first = candidate(row(Map.of("REFERENTIE", "R-1", "PRIJS", "1,50")), config);
        NormalisedCandidate second = candidate(row(Map.of("REFERENTIE", "R-2", "PRIJS", "2,50")), config);

        assertThat(first.supplier()).isEqualTo("ACME");
        assertThat(first.supplierGroup()).isEqualTo("GRP-A");
        assertThat(second.supplier()).isEqualTo("ACME");
        assertThat(second.supplierGroup()).isEqualTo("GRP-A");
        // Dezelfde canonieke tekst als een bronkolom met die waarden: de vaste waarde is geen eigen identiteitsvorm.
        assertThat(first.identityHash()).isEqualTo(ImportValueRules.sha256Utf8(
                ImportValueRules.canonical(2, "ACME", "GRP-A", "R-1")));
        NormalisedCandidate fromColumns = candidate(row(Map.of("LEVERANCIER", "ACME", "GROEP", "GRP-A",
                "REFERENTIE", "R-1", "PRIJS", "1,50")), FACTORY.from(revision()));
        assertThat(first.identityHash()).isEqualTo(fromColumns.identityHash());
    }

    @Test
    void uppercaseMakesLowerAndUpperCaseOneIdentityLikeLegacy() {
        ImportDefinitionRevision revision = revision();
        revision.setIdentityUppercase(true);
        SourceStructureConfig config = FACTORY.from(revision);

        NormalisedCandidate lower = candidate(row(Map.of("LEVERANCIER", "acme", "GROEP", "boor",
                "REFERENTIE", "a-1", "PRIJS", "1,50")), config);
        NormalisedCandidate upper = candidate(row(Map.of("LEVERANCIER", "ACME", "GROEP", "BOOR",
                "REFERENTIE", "A-1", "PRIJS", "1,50")), config);

        assertThat(lower.identityHash()).isEqualTo(upper.identityHash());
        assertThat(lower.supplier()).isEqualTo("ACME");
        assertThat(lower.supplierGroup()).isEqualTo("BOOR");
        assertThat(lower.supplierReference()).isEqualTo("A-1");
        // Zelfde canonicalisatieversie: de hoofdletterversie heeft exact de hash van een bron die al in hoofdletters
        // stond, zonder de schakelaar.
        NormalisedCandidate withoutSwitch = candidate(row(Map.of("LEVERANCIER", "ACME", "GROEP", "BOOR",
                "REFERENTIE", "A-1", "PRIJS", "1,50")), FACTORY.from(revision()));
        assertThat(upper.identityHash()).isEqualTo(withoutSwitch.identityHash());
        assertThat(upper.combinedFingerprint()).isEqualTo(withoutSwitch.combinedFingerprint());
    }

    @Test
    void withoutUppercaseTheIdentityStaysCaseSensitiveAsBeforeS6() {
        SourceStructureConfig config = FACTORY.from(revision());

        NormalisedCandidate lower = candidate(row(Map.of("LEVERANCIER", "acme", "GROEP", "boor",
                "REFERENTIE", "a-1", "PRIJS", "1,50")), config);
        NormalisedCandidate upper = candidate(row(Map.of("LEVERANCIER", "ACME", "GROEP", "BOOR",
                "REFERENTIE", "A-1", "PRIJS", "1,50")), config);

        assertThat(lower.identityHash()).isNotEqualTo(upper.identityHash());
        assertThat(lower.supplier()).isEqualTo("acme");
    }

    @Test
    void uppercaseAppliesToAFixedValueButNotToTheDiscountCodeLikeLegacy() {
        ImportDefinitionRevision revision = revision();
        revision.setIdentityProfileKind(IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE);
        revision.setIdentityDiscountCodeField("KORTING");
        revision.setIdentitySupplierField(null);
        revision.setIdentitySupplierFixedValue("acme");
        revision.setIdentityUppercase(true);
        SourceStructureConfig config = FACTORY.from(revision);

        NormalisedCandidate candidate = candidate(row(Map.of("GROEP", "boor", "REFERENTIE", "a-1",
                "KORTING", "ab", "PRIJS", "1,50")), config);
        NormalisedCandidate emptyDiscount = candidate(row(Map.of("GROEP", "boor", "REFERENTIE", "a-1",
                "KORTING", "", "PRIJS", "1,50")), config);

        assertThat(candidate.supplier()).isEqualTo("ACME");
        // Legacy zet veld 23 niet in hoofdletters (Prg_1170 r.768/863; beslissingslog 2026-10-21): "ab" blijft "ab".
        assertThat(candidate.discountCode()).isEqualTo("ab");
        NormalisedCandidate upperDiscount = candidate(row(Map.of("GROEP", "boor", "REFERENTIE", "a-1",
                "KORTING", "AB", "PRIJS", "1,50")), config);
        assertThat(upperDiscount.identityHash()).isNotEqualTo(candidate.identityHash());
        assertThat(candidate.discountState()).isEqualTo(DiscountCodeState.VALUE);
        // Hoofdletters veranderen niets aan "gemapt maar leeg".
        assertThat(emptyDiscount.discountCode()).isEmpty();
        assertThat(emptyDiscount.discountState()).isEqualTo(DiscountCodeState.EMPTY);
    }

    @Test
    void anEmptyColumnStillRejectsTheRowWithUppercase() {
        ImportDefinitionRevision revision = revision();
        revision.setIdentityUppercase(true);

        Result result = normaliser.normalise(row(Map.of("LEVERANCIER", "  ", "GROEP", "boor",
                "REFERENTIE", "a-1", "PRIJS", "1,50")), FACTORY.from(revision));

        assertThat(result).isInstanceOf(RowIssue.class);
        assertThat(((RowIssue) result).code()).isEqualTo(CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY);
    }

    @Test
    void uppercaseIsLocaleIndependentAndTheLengthIsCheckedAfterwards() {
        ImportDefinitionRevision revision = revision();
        revision.setIdentityUppercase(true);
        SourceStructureConfig config = FACTORY.from(revision);
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            NormalisedCandidate candidate = candidate(row(Map.of("LEVERANCIER", "dixi", "GROEP", "boor",
                    "REFERENTIE", "a-1", "PRIJS", "1,50")), config);
            // Locale.ROOT: "i" wordt "I", nooit de Turkse "İ".
            assertThat(candidate.supplier()).isEqualTo("DIXI");
        } finally {
            java.util.Locale.setDefault(previous);
        }
        // 199 tekens + "ß" = 200 tekens, in hoofdletters 201 ("SS"): nooit stil afkappen.
        Result tooLong = normaliser.normalise(row(Map.of("LEVERANCIER", "a".repeat(199) + "ß", "GROEP", "boor",
                "REFERENTIE", "a-1", "PRIJS", "1,50")), config);
        assertThat(tooLong).isInstanceOf(RowIssue.class);
        assertThat(((RowIssue) tooLong).code()).isEqualTo(CandidateNormaliser.CODE_VALUE_TOO_LONG);
    }

    // --- Hulp ------------------------------------------------------------------------------------------------------

    /** Een geldige revisie van vóór S6: drie kolommen, header, canonicalisatieversie 2. */
    private static ImportDefinitionRevision revision() {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1, IdentityProfileKind.THREE_PART,
                "tester");
        revision.setIdentitySupplierField("LEVERANCIER");
        revision.setIdentitySupplierGroupField("GROEP");
        revision.setIdentitySupplierReferenceField("REFERENTIE");
        revision.setRecordBasePriceField("PRIJS");
        revision.setRecordCanonicalisationVersion(2);
        revision.setStructureDelimiter(";");
        return revision;
    }

    /** Een regel met precies deze kolommen (in een vaste volgorde). */
    private static ParsedRow row(Map<String, String> columns) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        String[] values = new String[columns.size()];
        int index = 0;
        for (String name : List.of("LEVERANCIER", "GROEP", "REFERENTIE", "KORTING", "PRIJS")) {
            if (columns.containsKey(name)) {
                positions.put(name, index);
                values[index++] = columns.get(name);
            }
        }
        return new ParsedRow(12, Arrays.asList(values), new SourceFieldPositions(positions));
    }

    private NormalisedCandidate candidate(ParsedRow row, SourceStructureConfig config) {
        Result result = normaliser.normalise(row, config);
        assertThat(result).isInstanceOf(NormalisedCandidate.class);
        return (NormalisedCandidate) result;
    }
}
