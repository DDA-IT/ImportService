package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import be.dda.catalogimport.domain.DiscountCodeState;
import be.dda.catalogimport.domain.IdentityProfileKind;
import be.dda.catalogimport.service.support.CandidateNormaliser.NormalisedCandidate;
import be.dda.catalogimport.service.support.CandidateNormaliser.Result;
import be.dda.catalogimport.service.support.CandidateNormaliser.RowIssue;
import be.dda.catalogimport.service.support.CsvRecordStreamer.ParsedRow;
import be.dda.catalogimport.service.support.SourceStructureConfig.FieldReferenceKind;
import java.nio.charset.StandardCharsets;
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

    private final CandidateNormaliser normaliser = new CandidateNormaliser();

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

    // --- Helpers -------------------------------------------------------------------------------

    private NormalisedCandidate candidate(ParsedRow row, SourceStructureConfig config) {
        Result result = normaliser.normalise(row, config);
        assertThat(result).isInstanceOf(NormalisedCandidate.class);
        return (NormalisedCandidate) result;
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
