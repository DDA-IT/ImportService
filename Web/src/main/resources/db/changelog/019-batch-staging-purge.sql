--liquibase formatted sql

-- Stap 7, opruiming (beslissingslog 2026-10-02, "Stap 7 uitgewerkt"): de kandidaatstaging (import_candidate_stage met
-- import_candidate_price en import_candidate_reference) van een BASELINE_ACCEPTED-batch mag na de retentietermijn
-- verwijderd worden door de geplande opruimtaak (StagingPurgeService). Deze kolom legt vast WANNEER dat gebeurde: zo is
-- een lege staging achteraf altijd te onderscheiden van "nooit gestaged" of "per ongeluk verdwenen", en is de opruiming
-- idempotent (een batch met een tijdstip wordt nooit opnieuw behandeld).
--
-- Wat NIET verandert: staged_row_count blijft het aantal regels dat ooit gestaged werd (historische teller, geen
-- live telling van de staging). Row-issues, mutaties, bronstaat, prijsobservaties, snapshots, issuegroepen en de
-- batch-/leveringsrij zelf worden nooit door de opruiming geraakt.
--
-- ADDITIEF naast 001-018: een nieuwe nullable kolom en een CHECK; niets hernoemd, geen data gewijzigd. Bestaande rijen
-- krijgen NULL en voldoen dus aan de check (geen precondition nodig).

--changeset catalogimport:019-1-batch-staging-purged-at
--comment import_batch.staging_purged_at + check: enkel gezet op een BASELINE_ACCEPTED-batch.

alter table import_batch add column staging_purged_at timestamp with time zone;

-- Enkel een BASELINE_ACCEPTED-batch mag als opgeruimd gemarkeerd zijn (beslissing stap 7: SCREENED, BLOCKED en FAILED
-- vallen buiten de opruiming). BASELINE_ACCEPTED is een eindstatus zonder uitgaande overgang, dus deze check kan geen
-- bestaande flow blokkeren. De opruimguard (StagingRetentionDao) kent ook FAILED als opruimbaar; wordt de opruiming
-- ooit naar FAILED uitgebreid, dan vraagt dat bewust een migratie die deze check verruimt. status is NOT NULL, dus de
-- vergelijking evalueert nooit tot NULL.
alter table import_batch add constraint ck_import_batch_staging_purged
    check (staging_purged_at is null or status = 'BASELINE_ACCEPTED');

--rollback alter table import_batch drop constraint ck_import_batch_staging_purged;
--rollback alter table import_batch drop column staging_purged_at;
