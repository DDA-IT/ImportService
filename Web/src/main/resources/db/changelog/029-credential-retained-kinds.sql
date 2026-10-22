--liquibase formatted sql

-- LF (docs/decisions.md 2026-10-22 "Legacy-config: ook FTP- en API-wachtwoorden bewaren" en "LF: uitwerking FTP-/API-
-- wachtwoorden bewaren (ontwerp)"; docs/design/legacy-config-bundle-design.md par. 2 "Credential bij ontvangst").
-- ADDITIEF naast 001-028: enkel de check op secret_kind verruimd. Geen kolom hernoemd, geen data gewijzigd.

--changeset catalogimport:029-1-credential-retained-kinds
--comment Twee nieuwe soorten FTP_PASSWORD en API_PASSWORD: enkel bewaring bij de legacy-config-overname, niet bruikbaar in een verbindingsprofiel.

-- Waarom enkel de check: secret_kind is varchar(40) en de soort zit al in de associated data van de versleuteling
-- (013). Een verbindingsprofielversie kan een nieuwe soort niet gebruiken: connection_profile_version eist
-- auth_method PASSWORD <=> credential_secret_kind SFTP_PASSWORD (014), en de service weigert vooraf met
-- CONNECTION_PROFILE_CREDENTIAL_KIND_MISMATCH. Bestaande rijen (SFTP_PASSWORD/SSH_PRIVATE_KEY) blijven geldig.
alter table external_credential drop constraint ck_external_credential_secret_kind;
alter table external_credential add constraint ck_external_credential_secret_kind
    check (secret_kind in ('SFTP_PASSWORD', 'SSH_PRIVATE_KEY', 'FTP_PASSWORD', 'API_PASSWORD'));

-- De rollback faalt (bewust) zolang er een FTP_PASSWORD- of API_PASSWORD-credential bestaat: die eerst intrekken en
-- verwijderen is een menselijke beslissing, nooit een stille datawijziging.
--rollback alter table external_credential drop constraint ck_external_credential_secret_kind;
--rollback alter table external_credential add constraint ck_external_credential_secret_kind check (secret_kind in ('SFTP_PASSWORD', 'SSH_PRIVATE_KEY'));
