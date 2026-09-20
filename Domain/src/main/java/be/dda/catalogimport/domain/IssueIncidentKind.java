package be.dda.catalogimport.domain;

/**
 * Het soort samenvatting in {@code import_issue_group.incident_kind} (ontwerp fase 3, changeset
 * 004-4, R-THR-04). Het soort bepaalt <b>waaruit de signatuur bestaat</b> en <b>waartegen het
 * volume gemeten wordt</b> — niet hoe zwaar het probleem is; dat blijft de ernst van de foutcode.
 */
public enum IssueIncidentKind {

    /**
     * Een gewone foutgroep: dezelfde foutcode op hetzelfde logische veld. Boven de bulkgrens krijgt
     * de groep {@code is_bulk_incident = true}, maar er komt géén extra foutcode bij: het blijven
     * dezelfde regelfouten, nu samengevat.
     */
    GENERIC,

    /**
     * Gelijksoortige prijsafwijkingen (R-PRI-14): zelfde prijscomponent én zelfde richting binnen
     * dezelfde levering. Boven de bulkgrens levert dit één {@code BULK_PRICE_INCIDENT}.
     */
    PRICE,

    /**
     * Gelijksoortige incidenten op kritieke koppelreferenties (R-REF-07): zelfde referentietype én
     * zelfde soort incident. Boven de bulkgrens levert dit één {@code BULK_IDENTITY_INCIDENT}; de
     * individuele incidenten blijven onverkort bestaan, want een kritieke referentie blokkeert
     * ongeacht volume.
     */
    IDENTITY,

    /**
     * De creatiedrempel (R-THR-01, {@code BULK_CREATION_INCIDENT}). Gedeclareerd maar nog niet
     * gebruikt: de drempels horen bij bouwstap 3h.
     */
    CREATION
}
