package be.dda.catalogimport.domain;

/**
 * Wat er met een meegestuurd legacy-wachtwoord gebeurde (changeset 020-1, {@code secret_outcome};
 * {@code docs/design/legacy-config-bundle-design.md} par. 2 "Credential bij ontvangst"). Gezet vanaf slice S4 bij een
 * nieuwe ontvangst ({@code RECEIVED}); {@code null} = er kwam geen secret mee. Een push die {@code UNCHANGED} is, zet
 * enkel {@code STORED} (de credential ontstaat dan alsnog) en schrijft bij elke andere uitkomst niets (S4-vervolg,
 * beslissingslog 2026-10-21).
 */
public enum LegacyConfigSecretOutcome {
    /**
     * Nieuw credential versleuteld aangemaakt. De soort staat op de credential ({@code SFTP_PASSWORD}, of sinds
     * 2026-10-22 ook {@code FTP_PASSWORD}/{@code API_PASSWORD}: enkel bewaring, nog geen connector; beslissingslog
     * 2026-10-22 "LF").
     */
    STORED,
    /** Er bestond al een credential voor deze herkomst (welke status of soort ook); niets vergeleken of vervangen (A1). */
    DISCARDED_ALREADY_PRESENT,
    /**
     * Geen protocol waarvoor een wachtwoord bewaard wordt: een lokaal bestand ({@code FTP/LOCAL/API} = {@code L}) of
     * een onduidelijk of tegenstrijdig protocol (A7, verfijnd 2026-10-22). Vóór 2026-10-22 kregen ook FTP- en
     * API-bestemmingen deze uitkomst; een herhaalde push van dezelfde inhoud maakt die alsnog {@code STORED}.
     */
    DISCARDED_PROTOCOL_UNSUPPORTED,
    /** Geen bestemming, of geen geldige host om het credential aan te binden. */
    DISCARDED_NO_HOST
}
