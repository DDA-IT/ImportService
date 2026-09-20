package be.dda.catalogimport.service.support;

import be.dda.catalogimport.domain.RowIssueSeverity;
import be.dda.catalogimport.domain.ValidationResult;

/**
 * Wat één foutcode betekent voor de <b>levering als geheel</b> (bouwstap 3h-5, ontwerp fase 3
 * par. 15.3). Samen met de tellers bepaalt dit {@code import_batch.validation_result}; zie
 * {@link ValidationResultEvaluator}.
 *
 * <h2>Waarom dit naast de ernst bestaat en die niet vervangt</h2>
 * {@link RowIssueSeverity} zegt hoe zwaar één vaststelling is voor het <b>record</b> waarop ze slaat.
 * Dat is iets anders dan wat ze met de levering doet, en die twee liepen vóór deze bouwstap door
 * elkaar: {@code BULK_PRICE_INCIDENT} is een zware vaststelling (ernst {@code BLOCKING}) maar de
 * levering moet erop <b>beoordeeld</b> worden, niet stoppen — de mutaties bestaan gewoon en wachten
 * op goedkeuring. Omgekeerd is {@code CRITICAL_RECORD_THRESHOLD_EXCEEDED} wél een reden om te
 * stoppen. Eén as voor twee vragen dwong tot de keuze tussen "te streng" (alles blokkeert) en "te
 * los" (een bulkincident verdwijnt in een waarschuwing).
 *
 * <h2>Expliciet per code, nooit een standaard per ernst</h2>
 * Elke code in {@link ImportIssueCatalog} krijgt haar effect expliciet toegewezen; er bestaat geen
 * impliciete afleiding uit de ernst. Een nieuwe code moet dus een bewuste keuze maken in plaats van
 * er stilzwijgend een te erven — precies de fout die deze bouwstap rechtzet.
 */
public enum DeliveryEffect {

    /**
     * Deze vaststelling zegt niets over de levering als geheel. Ze blijft zichtbaar in de
     * probleemlijst en telt in haar tellers; het eindoordeel wordt er hoogstens
     * {@link ValidationResult#VALID_WITH_WARNINGS} door (bij ernst {@code ERROR} of
     * {@code WARNING}).
     */
    NONE,

    /**
     * Deze vaststelling vraagt een menselijke beoordeling van de levering
     * ({@link ValidationResult#REVIEW_REQUIRED}), maar houdt de verwerking niet tegen: de batch
     * eindigt gewoon op {@code SCREENED} met haar mutatielijst, waarvan de betrokken mutaties op
     * goedkeuring wachten.
     */
    REVIEW,

    /**
     * Deze vaststelling maakt de levering als geheel onbruikbaar:
     * {@link ValidationResult#BLOCKING}. Dat is een uitspraak over het <b>oordeel</b>; of de batch
     * ook op {@code BLOCKED} eindigt, bepaalt het blokkeerpad van de screening zelf.
     */
    BLOCK
}
