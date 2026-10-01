package be.dda.catalogimport.domain;

/**
 * Wat een ophaalrun met een matchend bestand deed (changeset 015-2, {@code fetch_file_observation.decision}; beslissingslog
 * 2026-09-29 L6; {@code docs/design/leveringsconfiguratie-design.md} par. 3.3 en 4.1).
 */
public enum FetchFileDecision {

    /** Gekozen om op te halen (hoogstens één per run). De waarneming draagt de levering als ze geregistreerd werd. */
    SELECTED,

    /** Dit remote object (zelfde pad, wijzigingstijd en grootte) werd al eerder als levering van deze taak opgehaald. */
    ALREADY_FETCHED,

    /** Jonger dan de minimale ouderdom van de Leveringsconfiguratie (of zonder wijzigingstijd van de server). */
    TOO_YOUNG,

    /** Groter dan de maximale grootte van de Leveringsconfiguratie (of de globale bovengrens van 1 GB). */
    TOO_LARGE,

    /** Nieuw en ophaalbaar, maar er wordt één bestand per run opgehaald: komt in een volgende run aan de beurt. */
    DEFERRED,

    /** Ouder dan het laatst opgehaalde bestand (watermark), of bij de eerste run niet het recentste: nooit opgehaald. */
    OLDER_THAN_WATERMARK
}
