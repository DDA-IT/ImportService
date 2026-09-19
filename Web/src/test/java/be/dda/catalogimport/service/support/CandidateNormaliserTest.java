package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.DiscountCodeState;
import be.dda.catalogimport.domain.FieldDataType;
import be.dda.catalogimport.domain.FieldOwner;
import be.dda.catalogimport.domain.FieldValueKind;
import be.dda.catalogimport.domain.IdentityClass;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.domain.ImportDefinitionRevision;
import be.dda.catalogimport.domain.ImportFieldCatalogEntry;
import be.dda.catalogimport.domain.ImportFieldMapping;
import be.dda.catalogimport.service.support.CandidateNormaliser.NormalisedCandidate;
import be.dda.catalogimport.service.support.CandidateNormaliser.Result;
import be.dda.catalogimport.service.support.CandidateNormaliser.RowIssue;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Fase 2c: identiteit, prijs en vingerafdrukken van één bronregel (design par. 8, beslissingslog
 * 18/09 over de aanbiedingsidentiteit). Unittest zonder Spring: deze regels mogen nooit van een
 * database of omgeving afhangen.
 */
class CandidateNormaliserTest {

    private static final String SUPPLIER = "LEVERANCIER";
    private static final String GROUP = "GROEP";
    private static final String REFERENCE = "REFERENTIE";
    private static final String DISCOUNT = "KORTING";
    private static final String PRICE = "PRIJS";
    private static final String DESCRIPTION = "OMSCHRIJVING";
    private static final String EXTRA_ONE = "E_LEV";
    private static final String EXTRA_TWO = "BARCODE";
    private static final String PRICE_COLUMN = "AKP";
    private static final String CURRENCY_COLUMN = "MUNT";

    private final CandidateNormaliser normaliser = new CandidateNormaliser();

    // --- Canonicalisatieversie 1 ligt vast -------------------------------------------------------

    /**
     * De hashes van versie 1 zijn <b>vastgepind</b> op hun hexwaarde, onafhankelijk berekend uit de
     * gedocumenteerde canonieke vorm (versienummer, onderdelen gescheiden door {@code U+001F}, een
     * niet-gemapt onderdeel als {@code U+0000}).
     * <p>
     * Zonder deze pin zou een wijziging aan de canonicalisatie — een extra onderdeel, een andere
     * volgorde, een andere scheiding — zichzelf bewijzen: de test zou dan gewoon de nieuwe hash
     * herberekenen. Het gevolg in productie is groot: elke bestaande bronstaat zou als CHANGED uit de
     * delta komen en een volledige catalogus zou onterecht als gewijzigd gepubliceerd worden.
     */
    @Test
    void keepsTheVersionOneFingerprintsByteIdenticalToTheOnesAlreadyInTheSourceState() {
        NormalisedCandidate candidate = candidate(row("ACME", "G1", "R-1", null, "1,50", "Boormachine"),
                threePart(DESCRIPTION));

        assertThat(hex(candidate.identityHash()))
                .isEqualTo("dff236898555b53c1cc5d938b32aea5a7eb40352eef59d7e03e3422db13ae3ba");
        assertThat(hex(candidate.articleFingerprint()))
                .isEqualTo("93596a1d3671d8d2d34fe5333bf7f33327f9fad2ae7e5a9d3b119b91462c76c3");
        assertThat(hex(candidate.priceFingerprint()))
                .isEqualTo("a69bed13cadfe6a3868f53f3fb3f90f8eea9c1bcd2240b77fb45c010de227f39");
        assertThat(hex(candidate.combinedFingerprint()))
                .isEqualTo("8d41d04385229e381addd8e615f5989cc7882fde949ba508be54a20bb6216875");
        // Versie 1 kent geen referentiedeel; null is hier "bestaat niet", niet "leeg".
        assertThat(candidate.referenceFingerprint()).isNull();
        assertThat(candidate.notices()).isEmpty();
    }

    // --- Normaal scenario ----------------------------------------------------------------------

    @Test
    void normalisesAValidRowWithTrimmedValuesAndASixDigitScale() {
        Result result = normaliser.normalise(row(" ACME ", "G1", " R-1 ", "K1", " 12,5 ", " Boormachine "),
                threePart(DESCRIPTION));

        assertThat(result).isInstanceOf(NormalisedCandidate.class);
        NormalisedCandidate candidate = (NormalisedCandidate) result;
        assertThat(candidate.supplier()).isEqualTo("ACME");
        assertThat(candidate.supplierGroup()).isEqualTo("G1");
        assertThat(candidate.supplierReference()).isEqualTo("R-1");
        assertThat(candidate.description()).isEqualTo("Boormachine");
        assertThat(candidate.basePrice().toPlainString()).isEqualTo("12.500000");
        assertThat(candidate.basePriceCurrency()).isNull(); // nooit stil EUR veronderstellen
        assertThat(candidate.identityHash()).hasSize(32);
        assertThat(candidate.articleFingerprint()).hasSize(32);
        assertThat(candidate.priceFingerprint()).hasSize(32);
        assertThat(candidate.combinedFingerprint()).hasSize(32);
        assertThat(candidate.mutationKeyPrefix(7L, 3L)).startsWith("7:3:").hasSize(4 + 64);
    }

    @Test
    void producesByteIdenticalFingerprintsForIdenticalInput() {
        NormalisedCandidate first = candidate(row("ACME", "G1", "R-1", null, "12,50", "Boor"),
                threePart(DESCRIPTION));
        NormalisedCandidate second = candidate(row("ACME", "G1", "R-1", null, "12,50", "Boor"),
                threePart(DESCRIPTION));

        assertThat(second.identityHash()).isEqualTo(first.identityHash());
        assertThat(second.articleFingerprint()).isEqualTo(first.articleFingerprint());
        assertThat(second.priceFingerprint()).isEqualTo(first.priceFingerprint());
        assertThat(second.combinedFingerprint()).isEqualTo(first.combinedFingerprint());
    }

    @Test
    void readsTheSamePriceFromACommaAndAPointNotation() {
        NormalisedCandidate comma = candidate(row("ACME", "G1", "R-1", null, "12,5", null), threePart(null));
        NormalisedCandidate point = candidate(row("ACME", "G1", "R-1", null, "12.5", null), threePart(null));

        assertThat(point.basePrice()).isEqualByComparingTo(comma.basePrice());
        assertThat(point.priceFingerprint()).isEqualTo(comma.priceFingerprint());
        assertThat(point.combinedFingerprint()).isEqualTo(comma.combinedFingerprint());
    }

    // --- Identiteit ----------------------------------------------------------------------------

    @Test
    void rejectsAnEmptySupplierGroupOrReferenceWithoutInventingAPlaceholder() {
        Map<String, ParsedRow> rows = new LinkedHashMap<>();
        rows.put(SUPPLIER, row("  ", "G1", "R-1", null, "1,00", null));
        rows.put(GROUP, row("ACME", "", "R-1", null, "1,00", null));
        rows.put(REFERENCE, row("ACME", "G1", " ", null, "1,00", null));

        rows.forEach((field, row) -> {
            Result result = normaliser.normalise(row, threePart(null));
            assertThat(result).as(field).isInstanceOf(RowIssue.class);
            RowIssue issue = (RowIssue) result;
            assertThat(issue.code()).isEqualTo(CandidateNormaliser.CODE_IDENTITY_COMPONENT_EMPTY);
            assertThat(issue.fieldName()).isEqualTo(field);
            assertThat(issue.rowNumber()).isEqualTo(12);
        });
    }

    @Test
    void ignoresTheDiscountColumnForAThreePartIdentityEvenWhenItIsFilled() {
        NormalisedCandidate filled = candidate(row("ACME", "G1", "R-1", "K1", "1,00", null), threePart(null));
        NormalisedCandidate empty = candidate(row("ACME", "G1", "R-1", "", "1,00", null), threePart(null));

        assertThat(filled.discountCode()).isNull();
        assertThat(filled.discountState()).isEqualTo(DiscountCodeState.NOT_USED);
        assertThat(filled.identityHash()).isEqualTo(empty.identityHash());
    }

    @Test
    void keepsAMappedButEmptyDiscountCodeAsAMeaningfulValue() {
        NormalisedCandidate candidate = candidate(row("ACME", "G1", "R-1", "  ", "1,00", null), fourPart());

        assertThat(candidate.discountCode()).isEmpty();
        assertThat(candidate.discountState()).isEqualTo(DiscountCodeState.EMPTY);
    }

    @Test
    void neverGivesAThreePartIdentityTheSameHashAsAFourPartIdentityWithAnEmptyDiscountCode() {
        NormalisedCandidate three = candidate(row("ACME", "G1", "R-1", "", "1,00", null), threePart(null));
        NormalisedCandidate four = candidate(row("ACME", "G1", "R-1", "", "1,00", null), fourPart());
        NormalisedCandidate fourWithValue = candidate(row("ACME", "G1", "R-1", "K1", "1,00", null), fourPart());

        assertThat(four.identityHash()).isNotEqualTo(three.identityHash());
        assertThat(fourWithValue.identityHash()).isNotEqualTo(four.identityHash());
        assertThat(four.combinedFingerprint()).isNotEqualTo(three.combinedFingerprint());
    }

    @Test
    void blocksTheDeliveryWhenAFourPartIdentityHasNoDiscountColumnInTheSource() {
        SourceStructureConfig config = fourPart();
        ParsedRow withoutDiscountColumn = new ParsedRow(12,
                List.of("ACME", "G1", "R-1", "1,00"),
                new SourceFieldPositions(Map.of(SUPPLIER, 0, GROUP, 1, REFERENCE, 2, PRICE, 3)));

        assertThatThrownBy(() -> normaliser.normalise(withoutDiscountColumn, config))
                .isInstanceOf(ScreeningBlockedException.class)
                .extracting(failure -> ((ScreeningBlockedException) failure).getCode())
                .isEqualTo(CandidateNormaliser.CODE_CONFIG_DISCOUNT_FIELD_MISSING);
    }

    @Test
    void rejectsASourceValueThatContainsTheCanonicalSeparator() {
        Result result = normaliser.normalise(row("ACMEX", "G1", "R-1", null, "1,00", null),
                threePart(null));

        assertThat(result).isInstanceOf(RowIssue.class);
        assertThat(((RowIssue) result).code())
                .isEqualTo(ImportValueRules.CODE_CANONICAL_CONTROL_CHARACTER);
    }

    // --- Prijs: nooit stil 0 of null -----------------------------------------------------------

    @Test
    void rejectsAnUnreadablePriceWithoutStoringAnyPrice() {
        Result result = normaliser.normalise(row("ACME", "G1", "R-1", null, "12,3x", null), threePart(null));

        assertThat(result).isInstanceOf(RowIssue.class);
        RowIssue issue = (RowIssue) result;
        assertThat(issue.code()).isEqualTo(ImportValueRules.CODE_PRICE_UNREADABLE);
        assertThat(issue.fieldName()).isEqualTo(PRICE);
        assertThat(issue.sourceValue()).isEqualTo("12,3x");
    }

    @Test
    void rejectsAnEmptyPriceInsteadOfDefaultingToZero() {
        Result blank = normaliser.normalise(row("ACME", "G1", "R-1", null, "   ", null), threePart(null));
        Result empty = normaliser.normalise(row("ACME", "G1", "R-1", null, "", null), threePart(null));

        assertThat(blank).isInstanceOf(RowIssue.class);
        assertThat(empty).isInstanceOf(RowIssue.class);
        assertThat(((RowIssue) blank).code()).isEqualTo(ImportValueRules.CODE_PRICE_MISSING);
        assertThat(((RowIssue) empty).code()).isEqualTo(ImportValueRules.CODE_PRICE_MISSING);
    }

    @Test
    void rejectsAPriceWithMoreThanSixDecimalsInsteadOfRoundingItSilently() {
        Result result = normaliser.normalise(row("ACME", "G1", "R-1", null, "1,2345678", null),
                threePart(null));

        assertThat(result).isInstanceOf(RowIssue.class);
        assertThat(((RowIssue) result).code()).isEqualTo(ImportValueRules.CODE_PRICE_SCALE_EXCEEDED);
    }

    @Test
    void acceptsExactlySixDecimalsAsTheBoundaryCase() {
        NormalisedCandidate candidate = candidate(row("ACME", "G1", "R-1", null, "1,234567", null),
                threePart(null));

        assertThat(candidate.basePrice().toPlainString()).isEqualTo("1.234567");
    }

    @Test
    void rejectsAPriceThatDoesNotFitInTheTargetColumn() {
        Result result = normaliser.normalise(row("ACME", "G1", "R-1", null, "1234567890123456789", null),
                threePart(null));

        assertThat(result).isInstanceOf(RowIssue.class);
        assertThat(((RowIssue) result).code()).isEqualTo(CandidateNormaliser.CODE_PRICE_OUT_OF_RANGE);
    }

    // --- Omschrijving --------------------------------------------------------------------------

    @Test
    void separatesAnUnmappedDescriptionFromAMappedButEmptyOne() {
        NormalisedCandidate unmapped = candidate(row("ACME", "G1", "R-1", null, "1,00", "Boor"),
                threePart(null));
        NormalisedCandidate mappedEmpty = candidate(row("ACME", "G1", "R-1", null, "1,00", ""),
                threePart(DESCRIPTION));

        assertThat(unmapped.description()).isNull();
        assertThat(mappedEmpty.description()).isEmpty();
        assertThat(mappedEmpty.articleFingerprint()).isNotEqualTo(unmapped.articleFingerprint());
        assertThat(mappedEmpty.identityHash()).isEqualTo(unmapped.identityHash());
    }

    @Test
    void rejectsADescriptionThatWouldNotFitInTheTargetColumn() {
        Result result = normaliser.normalise(
                row("ACME", "G1", "R-1", null, "1,00", "x".repeat(1001)), threePart(DESCRIPTION));

        assertThat(result).isInstanceOf(RowIssue.class);
        assertThat(((RowIssue) result).code()).isEqualTo(CandidateNormaliser.CODE_VALUE_TOO_LONG);
    }

    // --- Canonicalisatieversie 2: de gemapte velden zitten in de artikelvingerafdruk --------------

    @Test
    void coversEveryMappedCatalogueFieldInTheVersionTwoArticleFingerprint() {
        ImportMappingConfig config = mappingConfig(field("E_SUPPLIER", 1, EXTRA_ONE),
                field("SUPPLIER_BARCODE", 2, EXTRA_TWO));

        NormalisedCandidate first = candidate(mappedRow("A1", "B1"), versionTwo(), config);
        NormalisedCandidate second = candidate(mappedRow("A1", "B2"), versionTwo(), config);

        assertThat(second.articleFingerprint()).isNotEqualTo(first.articleFingerprint());
        assertThat(second.combinedFingerprint()).isNotEqualTo(first.combinedFingerprint());
        // De identiteit en de prijs zijn niet gewijzigd: enkel het artikeldeel verschilt.
        assertThat(second.identityHash()).isEqualTo(first.identityHash());
        assertThat(second.priceFingerprint()).isEqualTo(first.priceFingerprint());
    }

    /**
     * De volgorde in de vingerafdruk is die van de doelveldcode, niet die van het volgnummer van de
     * mapping. Een beheerder die zijn mappings hernummert, mag nooit een volledige catalogus als
     * gewijzigd laten uitkomen.
     */
    @Test
    void sortsTheArticleFingerprintOnTargetFieldCodeAndNotOnMappingOrder() {
        ImportMappingConfig ascending = mappingConfig(field("E_SUPPLIER", 1, EXTRA_ONE),
                field("SUPPLIER_BARCODE", 2, EXTRA_TWO));
        ImportMappingConfig renumbered = mappingConfig(field("SUPPLIER_BARCODE", 1, EXTRA_TWO),
                field("E_SUPPLIER", 2, EXTRA_ONE));

        assertThat(candidate(mappedRow("A1", "B1"), versionTwo(), renumbered).articleFingerprint())
                .isEqualTo(candidate(mappedRow("A1", "B1"), versionTwo(), ascending).articleFingerprint());
    }

    /** Niet gemapt, gemapt maar ontbrekend en gemapt maar leeg zijn drie verschillende toestanden. */
    @Test
    void distinguishesAnUnmappedFieldFromAnAbsentValueAndFromAnEmptyValue() {
        ImportMappingConfig withField = mappingConfig(field("E_SUPPLIER", 1, EXTRA_ONE));
        ImportMappingConfig withoutField = mappingConfig();

        byte[] unmapped = candidate(mappedRow("A1", "B1"), versionTwo(), withoutField).articleFingerprint();
        byte[] empty = candidate(mappedRow("", "B1"), versionTwo(), withField).articleFingerprint();
        byte[] absent = candidate(rowWithoutMappedColumns(), versionTwo(), withField).articleFingerprint();

        assertThat(empty).isNotEqualTo(unmapped);
        assertThat(absent).isNotEqualTo(empty);
        assertThat(absent).isNotEqualTo(unmapped);
    }

    @Test
    void producesByteIdenticalVersionTwoFingerprintsForIdenticalInput() {
        ImportMappingConfig config = mappingConfig(field("E_SUPPLIER", 1, EXTRA_ONE));

        NormalisedCandidate first = candidate(mappedRow("A1", "B1"), versionTwo(), config);
        NormalisedCandidate second = candidate(mappedRow("A1", "B1"), versionTwo(), config);

        assertThat(second.identityHash()).isEqualTo(first.identityHash());
        assertThat(second.articleFingerprint()).isEqualTo(first.articleFingerprint());
        assertThat(second.priceFingerprint()).isEqualTo(first.priceFingerprint());
        assertThat(second.referenceFingerprint()).isEqualTo(first.referenceFingerprint());
        assertThat(second.combinedFingerprint()).isEqualTo(first.combinedFingerprint());
    }

    /**
     * Versie 2 levert met zekerheid andere hashes op dan versie 1 — het versienummer staat vooraan in
     * elke canonieke tekst. Een revisie die van versie wisselt, vraagt dus een bewuste herbaselining
     * en kan nooit stilzwijgend "ongewijzigd" opleveren.
     */
    @Test
    void neverProducesTheSameFingerprintsUnderVersionTwoAsUnderVersionOne() {
        NormalisedCandidate one = candidate(mappedRow("A1", "B1"), threePart(DESCRIPTION));
        NormalisedCandidate two = candidate(mappedRow("A1", "B1"), versionTwo(), mappingConfig());

        assertThat(two.identityHash()).isNotEqualTo(one.identityHash());
        assertThat(two.articleFingerprint()).isNotEqualTo(one.articleFingerprint());
        assertThat(two.priceFingerprint()).isNotEqualTo(one.priceFingerprint());
        assertThat(two.combinedFingerprint()).isNotEqualTo(one.combinedFingerprint());
        // Enkel versie 2 heeft een referentiedeel; onder versie 2 bestaat het ook zonder referenties.
        assertThat(one.referenceFingerprint()).isNull();
        assertThat(two.referenceFingerprint()).isNotNull().hasSize(32);
    }

    // --- Canonicalisatieversie 2: de prijsvingerafdruk (fase 3d, ontwerp par. 3.5) ----------------

    /**
     * De prijsvingerafdruk is <b>vastgepind</b> op zijn hexwaarde, onafhankelijk berekend uit de
     * gedocumenteerde canonieke vorm. Drie dingen worden hier tegelijk bewezen:
     * <ol>
     *   <li>versie 1 blijft byte-identiek aan wat er al in bestaande bronstaten staat;</li>
     *   <li>versie 2 <b>zonder</b> prijscomponenten levert exact dezelfde tekst op als bouwstap 3c
     *       (basisprijs en munt, lege componentenlijst) — bouwstap 3d mag de hash van zo'n revisie niet
     *       verschuiven, anders zou elke aanbieding onterecht als CHANGED uit de delta komen;</li>
     *   <li>een component erbij wijzigt de hash aantoonbaar.</li>
     * </ol>
     */
    @Test
    void keepsThePriceFingerprintOfARevisionWithoutPriceComponentsUnchanged() {
        NormalisedCandidate versionOne = candidate(row("ACME", "G1", "R-1", null, "1,50", null),
                threePart(null));
        NormalisedCandidate versionTwo = candidate(mappedRow("A1", "B1"), versionTwo(), mappingConfig());

        assertThat(hex(versionOne.priceFingerprint()))
                .isEqualTo("a69bed13cadfe6a3868f53f3fb3f90f8eea9c1bcd2240b77fb45c010de227f39");
        assertThat(hex(versionTwo.priceFingerprint()))
                .isEqualTo("12f8cd1cae0b1aa9b0a6f4a29835e1f6e0a55f845823d42777f486e2b192e2c8");
        assertThat(versionOne.priceComponents()).isEmpty();
        assertThat(versionTwo.priceComponents()).isEmpty();
    }

    @Test
    void coversEveryPriceComponentWithItsPercentageInTheVersionTwoPriceFingerprint() {
        ImportMappingConfig config = mappingConfig(priceField("AKP_PCT", "AKP", 1, PRICE_COLUMN));

        // 0,75 van een basisprijs van 1,50 is exact 50%.
        NormalisedCandidate candidate = candidate(priceRow("0,75"), versionTwo(), config);

        assertThat(hex(candidate.priceFingerprint()))
                .isEqualTo("29ce940c6aea72cac8678ac808b93fc0d605544ac85a98ec4d6b84edc94b56de");
        assertThat(candidate.priceComponents()).hasSize(2);
        assertThat(candidate.priceComponents().get(0).componentCode())
                .isEqualTo(PriceRules.BASE_COMPONENT_CODE);
        assertThat(candidate.priceComponents().get(0).sourceAmount().toPlainString())
                .isEqualTo("1.500000");
        assertThat(candidate.priceComponents().get(0).percentage()).isNull();
        assertThat(candidate.priceComponents().get(1).percentage().toPlainString())
                .isEqualTo("50.000000000000");

        // Een andere verhouding bij dezelfde basisprijs wijzigt de prijsvingerafdruk (R-PRI-09).
        NormalisedCandidate other = candidate(priceRow("0,90"), versionTwo(), config);
        assertThat(other.priceFingerprint()).isNotEqualTo(candidate.priceFingerprint());
        assertThat(other.articleFingerprint()).isEqualTo(candidate.articleFingerprint());
        assertThat(other.identityHash()).isEqualTo(candidate.identityHash());
    }

    @Test
    void readsTheCurrencyFromTheDeclaredSourceFieldAndNeverAssumesEuro() {
        NormalisedCandidate withCurrency = candidate(currencyRow("EUR"), withCurrencyField());

        assertThat(withCurrency.basePriceCurrency()).isEqualTo("EUR");
        assertThat(hex(withCurrency.priceFingerprint()))
                .isEqualTo("1e342ba81ce51a7af83c011621435e710941a589a08115b8d5e2387f3665b8ae");
        // Zonder muntveld blijft de munt onbekend; dat is iets anders dan EUR.
        assertThat(candidate(mappedRow("A1", "B1"), versionTwo(), mappingConfig()).basePriceCurrency())
                .isNull();

        Result unusable = normaliser.normalise(currencyRow("eur"), withCurrencyField());
        assertThat(unusable).isInstanceOf(RowIssue.class);
        assertThat(((RowIssue) unusable).code()).isEqualTo(PriceRules.CODE_PRICE_CURRENCY_MISMATCH);
    }

    /** R-PRI-02: een basisprijs 0 verwerpt de regel, tenzij de revisie ze uitdrukkelijk toelaat. */
    @Test
    void rejectsAZeroBasePriceUnlessTheRevisionAllowsIt() {
        Result rejected = normaliser.normalise(row("ACME", "G1", "R-1", null, "0,00", null),
                threePart(null));

        assertThat(rejected).isInstanceOf(RowIssue.class);
        assertThat(((RowIssue) rejected).code()).isEqualTo(PriceRules.CODE_PRICE_ZERO_NOT_ALLOWED);
        assertThat(((RowIssue) rejected).fieldName()).isEqualTo(PRICE);
        assertThat(((RowIssue) rejected).sourceValue()).isEqualTo("0,00");

        NormalisedCandidate allowed = candidate(row("ACME", "G1", "R-1", null, "0,00", null),
                threePartAllowingZero());
        assertThat(allowed.basePrice().toPlainString()).isEqualTo("0.000000");
    }

    @Test
    void rejectsOnlyTheRowWhoseMappedFieldIsUnusable() {
        ImportMappingConfig config = mappingConfig(field("E_SUPPLIER", 1, EXTRA_ONE, 3));

        Result result = normaliser.normalise(mappedRow("TE-LANG", "B1"), versionTwo(), config);

        assertThat(result).isInstanceOf(RowIssue.class);
        RowIssue issue = (RowIssue) result;
        assertThat(issue.code()).isEqualTo(FieldValueMapper.CODE_VALUE_TOO_LONG);
        assertThat(issue.fieldName()).isEqualTo("Externe leveranciersidentiteit");
        assertThat(issue.rowNumber()).isEqualTo(12);
    }

    // --- Helpers -------------------------------------------------------------------------------

    private NormalisedCandidate candidate(ParsedRow row, SourceStructureConfig config) {
        Result result = normaliser.normalise(row, config);
        assertThat(result).isInstanceOf(NormalisedCandidate.class);
        return (NormalisedCandidate) result;
    }

    private NormalisedCandidate candidate(ParsedRow row, SourceStructureConfig config,
                                          ImportMappingConfig mappingConfig) {
        Result result = normaliser.normalise(row, config, mappingConfig);
        assertThat(result).isInstanceOf(NormalisedCandidate.class);
        return (NormalisedCandidate) result;
    }

    private static String hex(byte[] hash) {
        return HexFormat.of().formatHex(hash);
    }

    private ImportMappingConfig mappingConfig(ImportFieldMapping... mappings) {
        ImportDefinitionRevision revision = new ImportDefinitionRevision(null, 1,
                IdentityProfileKind.THREE_PART, "beheerder@example.test");
        revision.setIdentitySupplierField(SUPPLIER);
        revision.setIdentitySupplierGroupField(GROUP);
        revision.setIdentitySupplierReferenceField(REFERENCE);
        revision.setRecordBasePriceField(PRICE);
        revision.setRecordDescriptionField(DESCRIPTION);
        revision.setRecordCanonicalisationVersion(2);
        return new ImportMappingConfigFactory(null, null)
                .from(revision, versionTwo(), List.of(mappings), List.of());
    }

    private static ImportFieldMapping field(String code, int sequenceNumber, String sourceReference) {
        return field(code, sequenceNumber, sourceReference, null);
    }

    /** Een gemapte prijscomponent: eigenaar PRICE_CONTROL, DECIMAL, nooit identiteitsbeslissend. */
    private static ImportFieldMapping priceField(String code, String componentCode, int sequenceNumber,
                                                 String sourceReference) {
        ImportFieldCatalogEntry target = new ImportFieldCatalogEntry(code,
                "Aankoopprijs in procent van de basisprijs", FieldDataType.DECIMAL,
                FieldOwner.PRICE_CONTROL, IdentityClass.NONE, 20);
        target.setPriceComponentCode(componentCode);
        ImportFieldMapping mapping = new ImportFieldMapping(null, sequenceNumber, target,
                FieldValueKind.SOURCE_FIELD, FieldDataType.DECIMAL, FieldOwner.PRICE_CONTROL,
                IdentityClass.NONE);
        mapping.setSourceReference(sourceReference);
        mapping.setPriceComponentCode(componentCode);
        return mapping;
    }

    private static ImportFieldMapping field(String code, int sequenceNumber, String sourceReference,
                                            Integer maxLength) {
        ImportFieldCatalogEntry target = new ImportFieldCatalogEntry(code,
                "Externe leveranciersidentiteit", FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE,
                IdentityClass.SUPPORTING, 130);
        ImportFieldMapping mapping = new ImportFieldMapping(null, sequenceNumber, target,
                FieldValueKind.SOURCE_FIELD, FieldDataType.TEXT, FieldOwner.CATALOG_SOURCE,
                IdentityClass.SUPPORTING);
        mapping.setSourceReference(sourceReference);
        mapping.setMaxLength(maxLength);
        return mapping;
    }

    /** Dezelfde zes kolommen als {@link #row}, met de aankoopprijskolom erachter. */
    private static ParsedRow priceRow(String purchasePrice) {
        Map<String, Integer> positions = defaultPositions();
        positions.put(PRICE_COLUMN, 6);
        List<String> values = Arrays.asList("ACME", "G1", "R-1", "", "1,50", "Boormachine",
                nullToEmpty(purchasePrice));
        return new ParsedRow(12, values, new SourceFieldPositions(positions));
    }

    /** Dezelfde zes kolommen als {@link #row}, met de muntkolom erachter. */
    private static ParsedRow currencyRow(String currency) {
        Map<String, Integer> positions = defaultPositions();
        positions.put(CURRENCY_COLUMN, 6);
        List<String> values = Arrays.asList("ACME", "G1", "R-1", "", "1,50", "Boormachine",
                nullToEmpty(currency));
        return new ParsedRow(12, values, new SourceFieldPositions(positions));
    }

    /** Versie 2 met een verklaard muntveld; de munt hoort dan in de prijsvingerafdruk. */
    private static SourceStructureConfig withCurrencyField() {
        return new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"', true, 1,
                FieldReferenceKind.HEADER_NAME, null, IdentityProfileKind.THREE_PART,
                SUPPLIER, GROUP, REFERENCE, null, PRICE, null, 2,
                new PricePolicy(CURRENCY_COLUMN, false, false, PriceRules.DEFAULT_DERIVATION_TOLERANCE));
    }

    /** Fase 2-configuratie waarin de beheerder een basisprijs 0 uitdrukkelijk toelaat (R-PRI-02). */
    private static SourceStructureConfig threePartAllowingZero() {
        return new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"', true, 1,
                FieldReferenceKind.HEADER_NAME, null, IdentityProfileKind.THREE_PART,
                SUPPLIER, GROUP, REFERENCE, null, PRICE, null, 1,
                new PricePolicy(null, true, false, PriceRules.DEFAULT_DERIVATION_TOLERANCE));
    }

    /** Dezelfde zes kolommen als {@link #row}, met twee extra gemapte bronkolommen erachter. */
    private static ParsedRow mappedRow(String extraOne, String extraTwo) {
        Map<String, Integer> positions = defaultPositions();
        positions.put(EXTRA_ONE, 6);
        positions.put(EXTRA_TWO, 7);
        List<String> values = Arrays.asList("ACME", "G1", "R-1", "", "1,50", "Boormachine",
                nullToEmpty(extraOne), nullToEmpty(extraTwo));
        return new ParsedRow(12, values, new SourceFieldPositions(positions));
    }

    /** Dezelfde kolommen gedeclareerd, maar deze regel draagt er geen waarde voor: werkelijk ontbrekend. */
    private static ParsedRow rowWithoutMappedColumns() {
        Map<String, Integer> positions = defaultPositions();
        positions.put(EXTRA_ONE, 6);
        positions.put(EXTRA_TWO, 7);
        List<String> values = Arrays.asList("ACME", "G1", "R-1", "", "1,50", "Boormachine");
        return new ParsedRow(12, values, new SourceFieldPositions(positions));
    }

    private static Map<String, Integer> defaultPositions() {
        Map<String, Integer> positions = new LinkedHashMap<>();
        positions.put(SUPPLIER, 0);
        positions.put(GROUP, 1);
        positions.put(REFERENCE, 2);
        positions.put(DISCOUNT, 3);
        positions.put(PRICE, 4);
        positions.put(DESCRIPTION, 5);
        return positions;
    }

    private static SourceStructureConfig versionTwo() {
        return new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"', true, 1,
                FieldReferenceKind.HEADER_NAME, null, IdentityProfileKind.THREE_PART,
                SUPPLIER, GROUP, REFERENCE, null, PRICE, DESCRIPTION, 2);
    }

    /** Vaste kolomindeling: leverancier, groep, referentie, kortingscode, prijs, omschrijving. */
    private static ParsedRow row(String supplier, String group, String reference, String discount,
                                 String price, String description) {
        Map<String, Integer> positions = new LinkedHashMap<>();
        positions.put(SUPPLIER, 0);
        positions.put(GROUP, 1);
        positions.put(REFERENCE, 2);
        positions.put(DISCOUNT, 3);
        positions.put(PRICE, 4);
        positions.put(DESCRIPTION, 5);
        List<String> values = java.util.Arrays.asList(
                nullToEmpty(supplier), nullToEmpty(group), nullToEmpty(reference),
                nullToEmpty(discount), nullToEmpty(price), nullToEmpty(description));
        return new ParsedRow(12, values, new SourceFieldPositions(positions));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static SourceStructureConfig threePart(String descriptionField) {
        return new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"', true, 1,
                FieldReferenceKind.HEADER_NAME, null, IdentityProfileKind.THREE_PART,
                SUPPLIER, GROUP, REFERENCE, null, PRICE, descriptionField, 1);
    }

    private static SourceStructureConfig fourPart() {
        return new SourceStructureConfig("CSV", StandardCharsets.UTF_8, ';', '"', true, 1,
                FieldReferenceKind.HEADER_NAME, null, IdentityProfileKind.FOUR_PART_WITH_DISCOUNT_CODE,
                SUPPLIER, GROUP, REFERENCE, DISCOUNT, PRICE, null, 1);
    }
}
