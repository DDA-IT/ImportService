--liquibase formatted sql

-- Valuta-standaard, bouwstap V-1 (docs/design/valuta-standaard-design.md par. 2, 3 en 7; docs/decisions.md
-- 2026-09-26 "Ontbrekende valuta = euro" en "Valuta-standaard: ontwerp bindend"). Volledig ADDITIEF naast
-- 001-009 en ENKEL schema: de normaliser, de doorschrijving en de setup-API volgen in V-2 t/m V-4.
--
-- Geen defaults en geen backfill: bestaande rijen behouden NULL (= herkomst onbekend, par. 4b). Een
-- check op een nullable kolom slaagt bij NULL, precies gewenst voor die oude rijen. De herkomst staat
-- niet op de prijscomponenttabellen: die erven de munt van de basisprijs (R-PRI-06).

--changeset catalogimport:010-1-import-candidate-stage-currency-origin
--comment Herkomst van de effectieve basismunt in de staging: SOURCE, LINK_DEFAULT of SYSTEM_DEFAULT (NULL = onbekend).

alter table import_candidate_stage add column base_price_currency_origin varchar(20);
alter table import_candidate_stage add constraint ck_import_candidate_stage_currency_origin
    check (base_price_currency_origin in ('SOURCE', 'LINK_DEFAULT', 'SYSTEM_DEFAULT'));

--rollback alter table import_candidate_stage drop constraint ck_import_candidate_stage_currency_origin;
--rollback alter table import_candidate_stage drop column base_price_currency_origin;

--changeset catalogimport:010-2-catalog-source-state-currency-origin
--comment Herkomst van de basismunt in de aanvaarde bronstaat, zodat accept-baseline ze niet verliest (NULL = onbekend).

alter table catalog_source_state add column base_price_currency_origin varchar(20);
alter table catalog_source_state add constraint ck_catalog_source_state_currency_origin
    check (base_price_currency_origin in ('SOURCE', 'LINK_DEFAULT', 'SYSTEM_DEFAULT'));

--rollback alter table catalog_source_state drop constraint ck_catalog_source_state_currency_origin;
--rollback alter table catalog_source_state drop column base_price_currency_origin;

--changeset catalogimport:010-3-import-mutation-currency-origin
--comment Herkomst van de basismunt op een mutatie (NULL = onbekend, ook voor markers en oude rijen).

-- ck_import_mutation_marker (004-13) beperkt enkel actietype, domein, status en identiteitskolommen;
-- een extra nullable kolom raakt die check niet.
alter table import_mutation add column base_price_currency_origin varchar(20);
alter table import_mutation add constraint ck_import_mutation_currency_origin
    check (base_price_currency_origin in ('SOURCE', 'LINK_DEFAULT', 'SYSTEM_DEFAULT'));

--rollback alter table import_mutation drop constraint ck_import_mutation_currency_origin;
--rollback alter table import_mutation drop column base_price_currency_origin;

--changeset catalogimport:010-4-import-link-default-currency
--comment Optionele vaste valuta van een koppeling (ISO-4217-vorm, drie hoofdletters); NULL = geen vaste valuta.

alter table import_link add column default_currency varchar(3);

--rollback alter table import_link drop column default_currency;

-- De vormcheck staat in twee dialectvarianten, om dezelfde reden als 006-1b/006-1c: H2 kent de
-- REGEXP-operator, PostgreSQL de ~-operator. Beide leggen dezelfde constraintnaam en regel op.

--changeset catalogimport:010-4b-import-link-default-currency-postgresql dbms:postgresql
--comment Vormregel van default_currency, PostgreSQL-variant van dezelfde regel als 010-4c.

alter table import_link add constraint ck_import_link_default_currency
    check (default_currency is null or default_currency ~ '^[A-Z]{3}$');

--rollback alter table import_link drop constraint ck_import_link_default_currency;

--changeset catalogimport:010-4c-import-link-default-currency-h2 dbms:h2
--comment Vormregel van default_currency, H2-variant van dezelfde regel als 010-4b.

alter table import_link add constraint ck_import_link_default_currency
    check (default_currency is null or default_currency regexp '^[A-Z]{3}$');

--rollback alter table import_link drop constraint ck_import_link_default_currency;
