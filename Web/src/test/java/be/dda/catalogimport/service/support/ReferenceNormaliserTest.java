package be.dda.catalogimport.service.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Fase 3f (businessanalyse par. 14.23.7, "Normatieve normalisatie van CAB-/PIM-ID"; R-REF-01):
 * {@link ReferenceNormaliser} bepaalt de vergelijkingswaarde van een kritieke koppelreferentie.
 * <p>
 * <b>Wat hier bewezen wordt is vooral wat er níét gebeurt.</b> Een normalisatie die te veel doet,
 * voegt twee verschillende artikelen samen; die fout is achteraf niet meer te herstellen, want de
 * koppeling naar het interne artikel is dan al gelegd. Daarom: geen hoofdletterconversie, geen
 * leading-zeroverwijdering, geen tekenvervanging — {@code 000123}, {@code 123} en {@code AB-123}
 * blijven drie verschillende referenties.
 * <p>
 * Pure unittest: geen Spring, geen database.
 */
class ReferenceNormaliserTest {

    /** De kern van par. 14.23.7: deze drie zijn nooit dezelfde kritieke referentie. */
    @Test
    void keepsLeadingZeroesCaseAndSeparatorsSoThreeSimilarValuesStayThreeReferences() {
        assertThat(ReferenceNormaliser.normalise("000123")).isEqualTo("000123");
        assertThat(ReferenceNormaliser.normalise("123")).isEqualTo("123");
        assertThat(ReferenceNormaliser.normalise("AB-123")).isEqualTo("AB-123");

        assertThat(ReferenceNormaliser.normalise("000123"))
                .isNotEqualTo(ReferenceNormaliser.normalise("123"));
        assertThat(ReferenceNormaliser.normalise("AB-123"))
                .isNotEqualTo(ReferenceNormaliser.normalise("ab-123"));
        assertThat(ReferenceNormaliser.normalise("AB-123"))
                .isNotEqualTo(ReferenceNormaliser.normalise("AB123"));
        // Een numerieke interpretatie zou 000123 en 123 gelijkstellen; dat gebeurt nergens.
        assertThat(ReferenceNormaliser.normalise("0000000005449000000996"))
                .isEqualTo("0000000005449000000996");
    }

    /** Trim aan de buitenkant is wél toegelaten; spaties binnenin dragen betekenis en blijven. */
    @Test
    void trimsOnlyTheOutsideAndKeepsTheRawValueUntouched() {
        String raw = "  5449000000996  ";

        assertThat(ReferenceNormaliser.normalise(raw)).isEqualTo("5449000000996");
        // De ruwe waarde wordt door deze klasse nooit gewijzigd: ze levert enkel een nieuwe tekst op.
        assertThat(raw).isEqualTo("  5449000000996  ");
        assertThat(ReferenceNormaliser.normalise("AB 123")).isEqualTo("AB 123");
    }

    /** Onzichtbare tekens dragen in een EAN/PIM/CAB nooit betekenis en verdwijnen uit de vergelijking. */
    @Test
    void removesInvisibleControlAndFormatCharacters() {
        // Zero-width space, soft hyphen, BOM en een C0-stuurteken.
        assertThat(ReferenceNormaliser.normalise("544​9000­000996"))
                .isEqualTo("5449000000996");
        assertThat(ReferenceNormaliser.normalise("﻿5449000000996")).isEqualTo("5449000000996");
        assertThat(ReferenceNormaliser.normalise("5449000000996")).isEqualTo("5449000000996");
        // Maar een gewoon leesbaar teken blijft staan: dat is een betekenisvol verschil.
        assertThat(ReferenceNormaliser.normalise("5449.000000996")).isEqualTo("5449.000000996");
    }

    /**
     * Leeg is geen waarde maar een uitspraak (R-REF-03): het veld is gemapt en de leverancier levert
     * niets. Dat wordt {@code null} en nooit de lege tekst — een lege vergelijkingswaarde zou in
     * {@code catalog_reference_state} als echte referentie kunnen belanden.
     */
    @Test
    void treatsAnEmptyOrWhitespaceOnlyValueAsNoValueAtAll() {
        assertThat(ReferenceNormaliser.normalise(null)).isNull();
        assertThat(ReferenceNormaliser.normalise("")).isNull();
        assertThat(ReferenceNormaliser.normalise("   ")).isNull();
        assertThat(ReferenceNormaliser.normalise("​­")).isNull();

        assertThat(ReferenceNormaliser.isEmptyValue(null)).isTrue();
        assertThat(ReferenceNormaliser.isEmptyValue("  ")).isTrue();
        assertThat(ReferenceNormaliser.isEmptyValue("0")).isFalse();
    }

    /** Dezelfde invoer levert altijd dezelfde uitvoer: de waarde belandt in een vingerafdruk. */
    @Test
    void isDeterministic() {
        assertThat(ReferenceNormaliser.normalise(" AB-000123 "))
                .isEqualTo(ReferenceNormaliser.normalise(" AB-000123 "))
                .isEqualTo("AB-000123");
    }
}
