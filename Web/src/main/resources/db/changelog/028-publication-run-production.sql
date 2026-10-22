--liquibase formatted sql

-- P5c (docs/design/publicatie-prodis-db-direct-design.md par. 2.4, 6 rij P5c; publicatie-prodis-db-design.md par. 2;
-- docs/decisions.md 2026-10-22 "P4a: schema publicatie-items" open punt en "P5b: schrijven per item" open punten). ADDITIEF naast
-- 001-027: twee checks herzien (verruimd voor een afgeronde PRODUCTION-run) en een teller erbij. Geen kolom hernoemd, geen data gewijzigd.

--changeset catalogimport:028-1-publication-run-applied-terminal
--comment Een afgeronde PRODUCTION-run (APPLIED) is terminaal: marker NULL en finished_at verplicht, zodat ze het slot op bundel en installatie vrijgeeft (P5c).
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 select count(*) from publication_run where status = 'APPLIED' and target_mode = 'PRODUCTION'

-- Waarom enkel APPLIED + PRODUCTION: APPLIED is "echt toegepast in Prodis" en enkel een PRODUCTION-run kan dat bereiken. De service zette
-- APPLIED tot nu nooit; enkel schematests schreven het (als SIMULATION-run, marker TRUE, zonder finished_at). Die rijen blijven geldig onder de
-- tweede tak (niet-terminaal => marker TRUE), er wordt dus geen bestaande rij ongeldig en niets gemigreerd. De precondition stopt als er toch al
-- een APPLIED PRODUCTION-run zou bestaan (die zou dan een onbekende herkomst hebben).
-- REJECTED_BY_PRODIS, RECOVERY_REQUIRED en de andere nooit gezette statussen blijven niet-terminaal (ongewijzigd).
alter table publication_run drop constraint ck_publication_run_marker_status;
alter table publication_run add constraint ck_publication_run_marker_status check (
    ((status in ('SIMULATED', 'FAILED') or (status = 'APPLIED' and target_mode = 'PRODUCTION')) and active_marker is null)
    -- "is true" en niet "= true" (zie 009): een CHECK slaagt bij NULL.
    or (not (status in ('SIMULATED', 'FAILED') or (status = 'APPLIED' and target_mode = 'PRODUCTION'))
        and active_marker is true));

alter table publication_run drop constraint ck_publication_run_finished;
alter table publication_run add constraint ck_publication_run_finished check (
    not (status in ('SIMULATED', 'FAILED') or (status = 'APPLIED' and target_mode = 'PRODUCTION'))
    or finished_at is not null);

--rollback alter table publication_run drop constraint ck_publication_run_finished;
--rollback alter table publication_run add constraint ck_publication_run_finished check (status not in ('SIMULATED', 'FAILED') or finished_at is not null);
--rollback alter table publication_run drop constraint ck_publication_run_marker_status;
--rollback alter table publication_run add constraint ck_publication_run_marker_status check ((status in ('SIMULATED', 'FAILED') and active_marker is null) or (status not in ('SIMULATED', 'FAILED') and active_marker is true));

--changeset catalogimport:028-2-publication-run-item-not-applied-count
--comment Aantal Prodis-pogingen van een item die zeker niets toepasten (PRODIS_NOT_APPLIED of een teruggedraaide onverwachte fout), voor de begrenzing van herhaalde pogingen (P5c).

-- send_count telt elke verzending (ook die met onbekende uitkomst); not_applied_count enkel de zeker niet-toegepaste. Het verschil toont of een
-- eerdere poging misschien toch in Prodis schreef (RESULT_UNKNOWN), wat de begrenzing expliciet meldt. Default 0 voor bestaande rijen: die
-- telling bestond niet en P5b zette geen enkele poging dubbel (nooit stil een andere waarde).
alter table publication_run_item add column not_applied_count integer not null default 0;
alter table publication_run_item add constraint ck_publication_run_item_not_applied_count
    check (not_applied_count >= 0 and not_applied_count <= send_count);

--rollback alter table publication_run_item drop constraint ck_publication_run_item_not_applied_count;
--rollback alter table publication_run_item drop column not_applied_count;
