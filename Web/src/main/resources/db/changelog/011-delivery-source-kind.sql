--liquibase formatted sql

-- Tweede ontvangstweg: herkomst van een levering (docs/decisions.md 2026-09-27 "Ontwerp bindend: tweede
-- ontvangstweg - levering inlezen uit een beheerde servermap", Q2 = optie 1). Volledig ADDITIEF naast
-- 001-010: geen bestaande kolom of constraint wordt hernoemd, verwijderd of van type gewijzigd.
--
-- Waarom een kolom en geen afleiding uit de idempotency_key: de herkomst van een levering moet permanent
-- auditeerbaar zijn, ook nadat de sleutelvorm ooit verandert. UPLOAD = via de browser-upload,
-- LOCAL_DIRECTORY = ingelezen uit de beheerde servermap. Bestaande rijen krijgen terecht UPLOAD: dat is
-- exact hoe ze ontvangen zijn, geen gemakkelijkheidswaarde.
--
-- De default staat bewust OOK in de database (niet enkel op de entiteit): een rechtstreekse insert
-- (DemoDataSeeder, scripts, herstelwerk) mag deze kolom nooit leeg kunnen laten.

--changeset catalogimport:011-1-delivery-source-kind
--comment Herkomst van de levering: UPLOAD (browser) of LOCAL_DIRECTORY (beheerde servermap); bestaande rijen worden UPLOAD.

alter table delivery add column source_kind varchar(20) default 'UPLOAD' not null;
alter table delivery add constraint ck_delivery_source_kind
    check (source_kind in ('UPLOAD', 'LOCAL_DIRECTORY'));

--rollback alter table delivery drop constraint ck_delivery_source_kind;
--rollback alter table delivery drop column source_kind;
