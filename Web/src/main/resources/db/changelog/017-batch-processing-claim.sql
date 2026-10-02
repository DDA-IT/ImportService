--liquibase formatted sql

-- Stap 4 (beslissingslog 2026-10-01, "Stap 4 en 5 uitgewerkt"): verwerkingsclaim per batch met lease en fencing.
-- Een screening/hervatting neemt een claim op de batch (token + tijdstippen + eigenaar); schrijvende transacties na de
-- start worden op die token gefenced. Zo kunnen twee instanties of een herstart nooit tegelijk dezelfde batch verwerken.
--
-- processing_claimed_by heeft het formaat <instanceId>/<bootId> (gesplitst op de laatste '/').
--
-- ADDITIEF naast 001-016: vier nieuwe nullable kolommen en twee checks; niets hernoemd, niets verwijderd. Bestaande
-- rijen hebben nergens een claim (alle vier NULL) en voldoen dus aan beide checks.

--changeset catalogimport:017-1-batch-processing-claim
--comment processing_claim_* op import_batch + checks: claim enkel op een open batch, en de vier velden samen gezet of samen leeg.

alter table import_batch add column processing_claim_token uuid;
alter table import_batch add column processing_claimed_at timestamp with time zone;
alter table import_batch add column processing_heartbeat_at timestamp with time zone;
alter table import_batch add column processing_claimed_by varchar(100);

-- Een claim bestaat alleen op een open batch (open_marker TRUE). Een terminale overgang moet de claim dus in dezelfde
-- transactie vrijgeven; anders weigert de database de overgang. "is true" en niet "= true": een CHECK slaagt bij NULL,
-- dus "open_marker = true" liet een geclaimde batch met open_marker NULL (terminaal) door (zie 009, ck_publication_run_marker_status).
alter table import_batch add constraint ck_import_batch_claim_open
    check (processing_claim_token is null or open_marker is true);

-- Alles of niets: nooit een half gezette claim (bv. token zonder heartbeat, waardoor de lease niet te beoordelen is).
alter table import_batch add constraint ck_import_batch_claim_complete
    check ((processing_claim_token is null and processing_claimed_at is null
            and processing_heartbeat_at is null and processing_claimed_by is null)
        or (processing_claim_token is not null and processing_claimed_at is not null
            and processing_heartbeat_at is not null and processing_claimed_by is not null));

--rollback alter table import_batch drop constraint ck_import_batch_claim_complete;
--rollback alter table import_batch drop constraint ck_import_batch_claim_open;
--rollback alter table import_batch drop column processing_claimed_by;
--rollback alter table import_batch drop column processing_heartbeat_at;
--rollback alter table import_batch drop column processing_claimed_at;
--rollback alter table import_batch drop column processing_claim_token;
