package be.dda.catalogimport.domain;

/**
 * Wie de huidige aanmaakstandaard (btw-code, eenheid) van een koppeling zette ({@code import_link.creation_defaults_origin},
 * changeset 030; beslissingslog 2026-10-23 "Aanmaakstandaard per koppeling: uitwerking (CD)"). {@code null} in de
 * database betekent "nooit gezet" en is bewust geen enumwaarde.
 */
public enum CreationDefaultsOrigin {

    /** Overgenomen uit de vaste legacy-waarden (veld 51 btw, veld 45 eenheid) bij de legacy-config-overname. */
    LEGACY,

    /** Door een gebruiker gezet of gewist via de setup-API. */
    MANUAL
}
