--liquibase formatted sql

-- Aanmaakstandaard per koppeling, slice CD-B2 (docs/decisions.md 2026-10-23 "Btw-code en eenheid: aanmaakstandaard per
-- koppeling", "Aanmaakstandaard per koppeling: uitwerking (CD)" en "Vlag 'standaarden overschrijven' op de
-- importdefinitie"). Volledig ADDITIEF naast 001-029: enkel nieuwe, nullable kolommen (of een boolean met default
-- false) en nieuwe checks. Geen kolom hernoemd, geen bestaande constraint gewijzigd, geen backfill.
--
-- Conventies zoals 006 (bookmarkwaarde) en 007 (subject): timestamp with time zone; *_by varchar(100); *_by_subject
-- varchar(255) (bovengrens OIDC `sub`); "geen halve audit" (tijdstip en naam samen of geen van beide) en "geen subject
-- zonder naam".

--changeset catalogimport:030-1-import-link-creation-defaults
--comment Aanmaakstandaard van een koppeling (btw-code, eenheid) met herkomst LEGACY/MANUAL en audit van de laatste wijziging.

-- default_vat_code / default_unit_code: businesscodes uit Prodis (vat_code.code, unit_of_measure.code), nooit een
-- Prodis-id. Enkel gebruikt wanneer CatalogImport later een artikel AANMAAKT (P8), nooit om een bestaand artikel te
-- overschrijven. Eén eenheid: bij aanmaak naar factuur-, verkoop- en aankoopeenheid zoals legacy. NULL = geen
-- standaard. Lengte 50: de setup-API weigert langer met 400 VAT_CODE_TOO_LONG / UNIT_CODE_TOO_LONG.
--
-- creation_defaults_origin: wie de huidige waarden zette - LEGACY (de legacy-config-overname, CD-B3) of MANUAL (de
-- setup-API). De audit beschrijft de LAATSTE wijziging (ook een wissing: dan staan beide codes op NULL en blijven
-- herkomst en audit staan als verklaring). Historiek van vroegere waarden is bewust niet voorzien (beslissing CD:
-- "audit (laatste wijziging)").
alter table import_link add column default_vat_code varchar(50);
alter table import_link add column default_unit_code varchar(50);
alter table import_link add column creation_defaults_origin varchar(20);
alter table import_link add column creation_defaults_updated_at timestamp with time zone;
alter table import_link add column creation_defaults_updated_by varchar(100);
alter table import_link add column creation_defaults_updated_by_subject varchar(255);

alter table import_link add constraint ck_import_link_creation_defaults_origin
    check (creation_defaults_origin in ('LEGACY', 'MANUAL'));

-- Een code is nooit leeg of enkel spaties: de service zet blanco om naar NULL ("wissen").
alter table import_link add constraint ck_import_link_creation_defaults_not_blank
    check ((default_vat_code is null or trim(default_vat_code) <> '')
        and (default_unit_code is null or trim(default_unit_code) <> ''));

-- Geen halve audit:
--   * een gezette code heeft altijd een herkomst, een tijdstip en een naam (een aanmaakstandaard bepaalt mee wat er
--     in Prodis aangemaakt wordt; wie ze zette mag nooit enkel in een logregel bestaan);
--   * tijdstip en naam staan samen of geen van beide;
--   * een herkomst zonder tijdstip zou een wijziging zonder verklaring zijn;
--   * een subject zonder naam zou een handtekening zonder ondertekenaar zijn (007).
-- Bestaande rijen (alles NULL) voldoen.
alter table import_link add constraint ck_import_link_creation_defaults_audit
    check (((default_vat_code is null and default_unit_code is null)
            or (creation_defaults_origin is not null and creation_defaults_updated_at is not null
                and creation_defaults_updated_by is not null))
        and ((creation_defaults_updated_at is null and creation_defaults_updated_by is null)
            or (creation_defaults_updated_at is not null and creation_defaults_updated_by is not null))
        and (creation_defaults_origin is null or creation_defaults_updated_at is not null)
        and (creation_defaults_updated_by_subject is null or creation_defaults_updated_by is not null));

--rollback alter table import_link drop constraint ck_import_link_creation_defaults_audit;
--rollback alter table import_link drop constraint ck_import_link_creation_defaults_not_blank;
--rollback alter table import_link drop constraint ck_import_link_creation_defaults_origin;
--rollback alter table import_link drop column creation_defaults_updated_by_subject;
--rollback alter table import_link drop column creation_defaults_updated_by;
--rollback alter table import_link drop column creation_defaults_updated_at;
--rollback alter table import_link drop column creation_defaults_origin;
--rollback alter table import_link drop column default_unit_code;
--rollback alter table import_link drop column default_vat_code;

--changeset catalogimport:030-2-import-definition-overwrite-creation-defaults
--comment Vlag "standaarden overschrijven" op de importdefinitie (standaard uit) met audit van de laatste wijziging.

-- Op de definitie, niet op de revisie: de vlag hoort niet bij de inhoudshash of de identiteit. Bij een gedeelde
-- definitie geldt ze voor alle koppelingen eronder. De legacy-config-overname zet de vlag nooit (PSIMDHFD "Update
-- bestand" wordt sinds 2015 niet meer gelezen; updateFile blijft NOT_MIGRATED). Bestaande rijen krijgen false
-- (= het gedrag van vóór deze changeset: een bestaande aanmaakstandaard wordt bij de overname nooit overschreven).
--
-- De audit is nullable en NIET verplicht bij true: een losgekoppelde kopie (G2a) erft de vlag van de gedeelde
-- definitie zonder eigen wijziging, dus zonder audit. Enkel een halve audit en een subject zonder naam zijn verboden.
alter table import_definition add column overwrite_creation_defaults boolean default false not null;
alter table import_definition add column overwrite_creation_defaults_changed_at timestamp with time zone;
alter table import_definition add column overwrite_creation_defaults_changed_by varchar(100);
alter table import_definition add column overwrite_creation_defaults_changed_by_subject varchar(255);

alter table import_definition add constraint ck_import_definition_overwrite_cd_audit
    check (((overwrite_creation_defaults_changed_at is null and overwrite_creation_defaults_changed_by is null)
            or (overwrite_creation_defaults_changed_at is not null
                and overwrite_creation_defaults_changed_by is not null))
        and (overwrite_creation_defaults_changed_by_subject is null
            or overwrite_creation_defaults_changed_by is not null));

--rollback alter table import_definition drop constraint ck_import_definition_overwrite_cd_audit;
--rollback alter table import_definition drop column overwrite_creation_defaults_changed_by_subject;
--rollback alter table import_definition drop column overwrite_creation_defaults_changed_by;
--rollback alter table import_definition drop column overwrite_creation_defaults_changed_at;
--rollback alter table import_definition drop column overwrite_creation_defaults;
