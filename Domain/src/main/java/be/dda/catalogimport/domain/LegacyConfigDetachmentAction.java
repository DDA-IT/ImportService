package be.dda.catalogimport.domain;

/**
 * Wat een rij in {@link LegacyConfigDetachment} vastlegt (changeset 026-2, check
 * {@code ck_legacy_config_detachment_action}; {@code docs/design/g2-design.md} par. G2a).
 */
public enum LegacyConfigDetachmentAction {

    /** De interface kreeg een eigen kopie van de gedeelde definitie; haar koppeling staat voortaan op die kopie. */
    DETACH,

    /**
     * Opnieuw aansluiten bij de groep. In het model voorzien (design G2a punt 9), maar in G2 nooit gezet: er bestaat nog
     * geen handeling die het doet.
     */
    REATTACH
}
