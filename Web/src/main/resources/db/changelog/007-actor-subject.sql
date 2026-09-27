--liquibase formatted sql

-- Fase 5-AUTH, geverifieerde identiteit (docs/design/fase5-auth-design.md par. 4; docs/decisions.md
-- 2026-09-25 "Fase 5: opknipping ..." Q1 en "Fase 5-AUTH: ontwerp bindend" G1). Volledig ADDITIEF naast
-- 001-006, die ongewijzigd blijven: geen enkele bestaande kolom of constraint wordt hernoemd, verwijderd
-- of van type gewijzigd, en er wordt niets gebackfilld.
--
-- Wat een *_by_subject betekent: het OIDC-subject (claim `sub`) van de gebruiker die de bijbehorende
-- *_by-naam ondertekende. NULL betekent overal exact hetzelfde: "GEEN GEVERIFIEERDE IDENTITEIT" - een rij
-- van voor Fase 5, de DemoDataSeeder, of een rechtstreekse Service-aanroep in een test. Nooit stil
-- ingevuld, nooit afgeleid uit de naam: een naam is navertelbaar, een subject niet.
--
-- Vaste regels voor elke kolom in deze changelog:
--   * varchar(255) - bovengrens van `sub` (OIDC Core par. 2); nooit afkappen, langere waarden worden in
--     de Web-laag geweigerd (403 ACTOR_IDENTITY_INVALID);
--   * nullable, geen default, geen backfill, geen index (audit-only, nooit een zoeksleutel);
--   * bij een NULLABLE *_by-kolom een koppelcheck "(x_by_subject is null or x_by is not null)": een
--     subject zonder naam zou een handtekening zijn zonder ondertekenaar.
-- Een changeset per bouwstap (patroon van 004), elk met --rollback. Met 007-4 (5A-6) is de changelog
-- volledig: 18 kolommen op 12 tabellen (ontwerp par. 1.2 en par. 4).

--changeset catalogimport:007-1-bundle-freeze-subject
--comment Bouwstap 5A-2: geverifieerd subject bij het bevriezen van een bundel en bij elke beslissingsregel.

-- publication_bundle.frozen_by is NULLABLE (een ASSEMBLING-bundel is nog niet bevroren), vandaar de
-- koppelcheck. Bewust NIET aan ck_publication_bundle_frozen (005:63) geraakt: die bestaande constraint
-- blijft letterlijk zoals ze was, zodat een frozen_by_subject nooit een voorwaarde wordt om te kunnen
-- bevriezen. Een bevriezing zonder login (DemoDataSeeder, Service-test) blijft dus geldig.
alter table publication_bundle add column frozen_by_subject varchar(255);

alter table publication_bundle add constraint ck_publication_bundle_frozen_subject
    check (frozen_by_subject is null or frozen_by is not null);

-- publication_decision.decided_by is NOT NULL (005:140): elke regel heeft per definitie een
-- ondertekenaar, dus hier is geen koppelcheck mogelijk of nodig.
--
-- Deze ene kolom dekt elke beslissingssoort: APPROVE, REJECT, AUTO_APPROVE_PLANNED, FREEZE en CANCEL.
-- import_mutation.decided_by (005:176) krijgt bewust GEEN eigen subjectkolom: die rijen zijn een
-- JDBC-kopie van de beslissing en verwijzen via decision_id naar deze tabel, die het subject al draagt.
-- Een tweede kolom zou twee waarheden kunnen krijgen (ontwerp par. 1.2).
alter table publication_decision add column decided_by_subject varchar(255);

--rollback alter table publication_decision drop column decided_by_subject;
--rollback alter table publication_bundle drop constraint ck_publication_bundle_frozen_subject;
--rollback alter table publication_bundle drop column frozen_by_subject;

--changeset catalogimport:007-2-bundle-subject
--comment Bouwstap 5A-4: geverifieerd subject bij aanmaken/annuleren van een bundel en bij toevoegen/verwijderen van een batch.

-- created_by en added_by zijn NOT NULL (005): geen koppelcheck nodig. cancelled_by en removed_by zijn
-- NULLABLE (nog niet geannuleerd/verwijderd), vandaar de koppelchecks. Bestaande constraints blijven
-- ongewijzigd; een subject wordt nooit een voorwaarde om te kunnen annuleren of verwijderen.
alter table publication_bundle add column created_by_subject varchar(255);
alter table publication_bundle add column cancelled_by_subject varchar(255);
alter table publication_bundle add constraint ck_publication_bundle_cancelled_subject
    check (cancelled_by_subject is null or cancelled_by is not null);

alter table publication_bundle_batch add column added_by_subject varchar(255);
alter table publication_bundle_batch add column removed_by_subject varchar(255);
alter table publication_bundle_batch add constraint ck_publication_bundle_batch_removed_subject
    check (removed_by_subject is null or removed_by is not null);

--rollback alter table publication_bundle_batch drop constraint ck_publication_bundle_batch_removed_subject;
--rollback alter table publication_bundle_batch drop column removed_by_subject;
--rollback alter table publication_bundle_batch drop column added_by_subject;
--rollback alter table publication_bundle drop constraint ck_publication_bundle_cancelled_subject;
--rollback alter table publication_bundle drop column cancelled_by_subject;
--rollback alter table publication_bundle drop column created_by_subject;

--changeset catalogimport:007-3-import-batch-subject
--comment Bouwstap 5A-5: geverifieerd subject bij de upload (import_batch.created_by) en bij accept-baseline.

-- created_by (002:81) en baseline_accepted_by (003:10) zijn beide NULLABLE, vandaar de koppelchecks.
-- Bestaande constraints blijven ongewijzigd. Bulkkopieen (catalog_source_state, catalog_price_observation,
-- catalog_reference_state) en task_run.triggered_by krijgen bewust geen kolom (ontwerp par. 1.2): ze
-- verwijzen naar deze batchrij, die het subject draagt.
alter table import_batch add column created_by_subject varchar(255);
alter table import_batch add constraint ck_import_batch_created_subject
    check (created_by_subject is null or created_by is not null);

alter table import_batch add column baseline_accepted_by_subject varchar(255);
alter table import_batch add constraint ck_import_batch_baseline_accepted_subject
    check (baseline_accepted_by_subject is null or baseline_accepted_by is not null);

--rollback alter table import_batch drop constraint ck_import_batch_baseline_accepted_subject;
--rollback alter table import_batch drop column baseline_accepted_by_subject;
--rollback alter table import_batch drop constraint ck_import_batch_created_subject;
--rollback alter table import_batch drop column created_by_subject;

--changeset catalogimport:007-4-configuration-subject
--comment Bouwstap 5A-6 (G1): geverifieerd subject op de configuratietabellen - definitie, revisie, mapping, filter, kritiekheid en bookmarks.

-- G1 (mens, 2026-09-25): ook de configuratietabellen krijgen een subject. Zonder deze tien kolommen zou
-- NULL twee dingen betekenen - "vóór Fase 5" op de bundel-/batchtabellen en "deze tabel kent geen
-- subject" hier - terwijl de setup-/materialisatie-API na 5A-1 ook een login vereist. NULL betekent nu
-- overal exact hetzelfde: geen geverifieerde identiteit (rij van vóór Fase 5, DemoDataSeeder, of een
-- rechtstreekse Service-aanroep met de oude String-overload, bv. de default "setup-api").
--
-- import_definition.created_by, import_definition_revision.created_by,
-- import_definition_bookmark_value.filled_by en import_link_bookmark_value.filled_by zijn NOT NULL
-- (001:27, 001:60, 006:164, 006:204): daar is een koppelcheck onmogelijk en overbodig. De zes
-- nullable *_by-kolommen (approved_by, de vier created_by's en updated_by) krijgen er wel een.

alter table import_definition add column created_by_subject varchar(255);

alter table import_definition_revision add column created_by_subject varchar(255);
alter table import_definition_revision add column approved_by_subject varchar(255);
alter table import_definition_revision add constraint ck_import_definition_revision_approved_subject
    check (approved_by_subject is null or approved_by is not null);

alter table import_field_mapping add column created_by_subject varchar(255);
alter table import_field_mapping add constraint ck_import_field_mapping_created_subject
    check (created_by_subject is null or created_by is not null);

alter table import_record_filter add column created_by_subject varchar(255);
alter table import_record_filter add constraint ck_import_record_filter_created_subject
    check (created_by_subject is null or created_by is not null);

alter table import_revision_field_criticality add column created_by_subject varchar(255);
alter table import_revision_field_criticality add constraint ck_import_revision_field_criticality_subject
    check (created_by_subject is null or created_by is not null);

alter table import_definition_bookmark add column created_by_subject varchar(255);
alter table import_definition_bookmark add constraint ck_import_definition_bookmark_created_subject
    check (created_by_subject is null or created_by is not null);

alter table import_definition_bookmark_value add column filled_by_subject varchar(255);

-- updated_by is nullable en hangt al samen met updated_at (ck_import_link_bookmark_value_update,
-- 006:215). Die bestaande constraint blijft letterlijk zoals ze was; de nieuwe check koppelt enkel het
-- subject aan de naam, zodat een wijziging zonder login geldig blijft.
alter table import_link_bookmark_value add column filled_by_subject varchar(255);
alter table import_link_bookmark_value add column updated_by_subject varchar(255);
alter table import_link_bookmark_value add constraint ck_import_link_bookmark_value_updated_subject
    check (updated_by_subject is null or updated_by is not null);

--rollback alter table import_link_bookmark_value drop constraint ck_import_link_bookmark_value_updated_subject;
--rollback alter table import_link_bookmark_value drop column updated_by_subject;
--rollback alter table import_link_bookmark_value drop column filled_by_subject;
--rollback alter table import_definition_bookmark_value drop column filled_by_subject;
--rollback alter table import_definition_bookmark drop constraint ck_import_definition_bookmark_created_subject;
--rollback alter table import_definition_bookmark drop column created_by_subject;
--rollback alter table import_revision_field_criticality drop constraint ck_import_revision_field_criticality_subject;
--rollback alter table import_revision_field_criticality drop column created_by_subject;
--rollback alter table import_record_filter drop constraint ck_import_record_filter_created_subject;
--rollback alter table import_record_filter drop column created_by_subject;
--rollback alter table import_field_mapping drop constraint ck_import_field_mapping_created_subject;
--rollback alter table import_field_mapping drop column created_by_subject;
--rollback alter table import_definition_revision drop constraint ck_import_definition_revision_approved_subject;
--rollback alter table import_definition_revision drop column approved_by_subject;
--rollback alter table import_definition_revision drop column created_by_subject;
--rollback alter table import_definition drop column created_by_subject;
