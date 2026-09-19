package be.dda.catalogimport.service.support;

import java.math.BigDecimal;

/**
 * Het prijsbeleid van één bevroren {@code ImportDefinitionRevision} voor de <b>basisprijs</b>
 * (ontwerp fase 3, R-PRI-02, R-PRI-03, R-PRI-06, R-PRI-07). Momentopname van de revisiekolommen,
 * exact één keer per batch gelezen (stap B'); geen JPA-entiteit en geen query per bronregel.
 * <p>
 * <b>Waarom de basisprijs hier staat en niet op een mapping.</b> De basisprijs wordt door
 * {@code record_base_price_field} bepaald en mag volgens R-STR-06 geen tweede bron krijgen; ze heeft
 * dus geen {@code import_field_mapping}-rij waarop {@code zero_allowed} en {@code negative_allowed}
 * zouden kunnen staan. Afgeleide componenten dragen die twee schakelaars wél op hun eigen mapping.
 *
 * @param currencyField        bronveld (headernaam of kolomindex) van de munt, of {@code null}.
 *                             {@code null} betekent <b>onbekend</b>: er wordt nooit stilzwijgend EUR
 *                             verondersteld (aanname A22), en dan heeft ook geen enkele component een
 *                             munt
 * @param zeroAllowed          mag de basisprijs 0 zijn? (R-PRI-02)
 * @param negativeAllowed      mag de basisprijs negatief zijn? (R-PRI-03)
 * @param derivationTolerance  toegelaten afwijking op de prijsreconstructie, in de eenheid van het
 *                             bedrag zelf en op de doelschaal van 2 decimalen (R-PRI-07); nooit
 *                             negatief
 */
public record PricePolicy(String currencyField, boolean zeroAllowed, boolean negativeAllowed,
                          BigDecimal derivationTolerance) {

    /**
     * Het strengste beleid: geen munt, geen nul, geen negatieve basisprijs, tolerantie 0,01 — exact de
     * normatieve defaults van changeset 004-10/004-10b. Dit is wat geldt wanneer er geen revisie in
     * het spel is (unittests van de losse regels).
     */
    public static final PricePolicy DEFAULT =
            new PricePolicy(null, false, false, PriceRules.DEFAULT_DERIVATION_TOLERANCE);

    public PricePolicy {
        if (derivationTolerance == null) {
            throw new IllegalArgumentException("A price derivation tolerance is required; "
                    + "'no tolerance' would silently accept any reconstruction difference");
        }
        if (derivationTolerance.signum() < 0) {
            throw new IllegalArgumentException("The price derivation tolerance cannot be negative");
        }
    }

    /** Leest deze revisie een munt uit de bron? */
    public boolean hasCurrencyField() {
        return currencyField != null;
    }
}
