--liquibase formatted sql

-- Legacy-config-migratie, slice S6 (docs/design/legacy-config-bundle-design.md par. 4, 5 en 8; beslissingslog 2026-10-20
-- "M1-M3 en bouwopdracht S0-S9" (M3) en "datasync-aanpak, Prodis-DB lezen, sjablonen" (optie iii)).
--
-- Vaste leverancier/groep en hoofdletters op de aanbiedingsidentiteit van een revisie:
--   * identity_supplier_fixed_value / identity_supplier_group_fixed_value: een businesscode (supplier.number /
--     supplier_group.group_name uit de Prodis-database), nooit een Prodis-id, geen cross-DB-FK. Geldt voor ELKE regel van
--     de levering in plaats van een bronkolom (legacy PSIMPDEF 9/10 met een vaste "Waarde", Prg_1170).
--   * identity_uppercase: zet de identiteitsdelen in hoofdletters vóór de canonicalisatie (legacy Upper(), Prg_1170
--     r.733-815). Default false: elke bestaande revisie houdt exact haar gedrag en haar identity_hash.
--
-- GEMELDE WIJZIGING VAN EEN BESTAANDE CONSTRAINT (M3, AGENT.md par. 6): identity_supplier_field en
-- identity_supplier_group_field verliezen hun "not null"; in de plaats komt per deel de check "precies een van kolom of
-- vaste waarde". Elke bestaande rij voldoet (kolom gevuld, vaste waarde null). Geen sentinelwaarde.
--
-- Rollback: enkel zolang geen enkele rij een vaste waarde heeft. Het terugzetten van "not null" faalt anders bewust
-- (een rij met een vaste waarde heeft geen kolom); dat is de bewaking, niet een fout in de rollback.

--changeset catalogimport:022-1-identity-fixed-value-and-uppercase-columns
--comment Vaste leverancier/groep (businesscode) en hoofdletterschakelaar op de revisie; additief, default zonder gedragswijziging.
alter table import_definition_revision add column identity_supplier_fixed_value varchar(50);
alter table import_definition_revision add column identity_supplier_group_fixed_value varchar(50);
alter table import_definition_revision add column identity_uppercase boolean not null default false;

-- Een vaste waarde is nooit blanco: dan zou elke regel IDENTITY_COMPONENT_EMPTY krijgen.
alter table import_definition_revision add constraint ck_import_definition_revision_supplier_fixed_not_blank
    check (identity_supplier_fixed_value is null or length(btrim(identity_supplier_fixed_value)) > 0);
alter table import_definition_revision add constraint ck_import_definition_revision_group_fixed_not_blank
    check (identity_supplier_group_fixed_value is null or length(btrim(identity_supplier_group_fixed_value)) > 0);

--rollback alter table import_definition_revision drop constraint ck_import_definition_revision_group_fixed_not_blank;
--rollback alter table import_definition_revision drop constraint ck_import_definition_revision_supplier_fixed_not_blank;
--rollback alter table import_definition_revision drop column identity_uppercase;
--rollback alter table import_definition_revision drop column identity_supplier_group_fixed_value;
--rollback alter table import_definition_revision drop column identity_supplier_fixed_value;

--changeset catalogimport:022-2-identity-supplier-source-exactly-one
--comment M3: leverancier en groep komen uit precies een van kolom of vaste waarde; identity_supplier_field/_group_field worden nullable.
alter table import_definition_revision alter column identity_supplier_field drop not null;
alter table import_definition_revision alter column identity_supplier_group_field drop not null;

alter table import_definition_revision add constraint ck_import_definition_revision_supplier_source
    check ((identity_supplier_field is not null and identity_supplier_fixed_value is null)
        or (identity_supplier_field is null and identity_supplier_fixed_value is not null));
alter table import_definition_revision add constraint ck_import_definition_revision_group_source
    check ((identity_supplier_group_field is not null and identity_supplier_group_fixed_value is null)
        or (identity_supplier_group_field is null and identity_supplier_group_fixed_value is not null));

--rollback alter table import_definition_revision drop constraint ck_import_definition_revision_group_source;
--rollback alter table import_definition_revision drop constraint ck_import_definition_revision_supplier_source;
--rollback alter table import_definition_revision alter column identity_supplier_group_field set not null;
--rollback alter table import_definition_revision alter column identity_supplier_field set not null;
