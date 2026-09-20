package be.dda.catalogimport.domain;

/**
 * Het oordeel van het <b>creatiebeleid</b> over één {@link ImportBatch} (ontwerp fase 3 par. 15.2,
 * beslissingslog 20/09 "drempels zijn altijd een percentage").
 * <p>
 * Het beleid beantwoordt één vraag: mogen de nieuwe aanbiedingen van deze levering zonder menselijke
 * tussenkomst aangemaakt worden? Het oordeel wordt <b>vóór</b> de mutatiegeneratie vastgelegd op
 * {@code import_batch.creation_outcome} en daarna nooit herberekend: een hervatte mutatiegeneratie
 * moet exact dezelfde mutatiestatussen opleveren als een verwerking die in één keer doorliep, ook
 * wanneer de bronstaat van de koppeling intussen door een andere aanvaarding gewijzigd is.
 * <p>
 * {@code null} op de batch betekent "nog niet beoordeeld" en wordt nooit stil {@link #AUTOMATIC}.
 */
public enum CreationOutcome {

    /**
     * Binnen de drempel, of er is geen enkele creatie. De {@code CREATE}-mutaties blijven
     * {@link MutationStatus#PLANNED}; het gedrag is exact dat van vóór bouwstap 3h.
     */
    AUTOMATIC,

    /**
     * De koppeling had nog geen enkele actieve aanbieding: deze levering bouwt de bronstaat op. Elke
     * creatie wacht op goedkeuring ({@link MutationStatus#AWAITING_APPROVAL}, reden
     * {@code INITIAL_LOAD_REQUIRES_APPROVAL}). Initialisatie wint van de creatiedrempel: er wordt nooit
     * ook een bulkcreatie gemeld, want zonder bestaande omvang bestaat er geen percentage.
     */
    INITIAL_LOAD,

    /**
     * Het aantal creaties ligt boven {@code creation_threshold_share_percent} van de bestaande omvang
     * van de koppeling. Elke creatie wacht op goedkeuring (reden {@code BULK_CREATION_INCIDENT});
     * wijzigingen van bestaande aanbiedingen gaan gewoon door.
     */
    THRESHOLD_EXCEEDED;

    /** Vraagt dit oordeel een menselijke goedkeuring vóór er aanbiedingen aangemaakt worden? */
    public boolean requiresApproval() {
        return this != AUTOMATIC;
    }
}
