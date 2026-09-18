--liquibase formatted sql

-- Fase 2e (docs/decisions.md, "Fase 2: baseline-acceptatie"): additief naast 001 en 002, die ongewijzigd blijven.

--changeset catalogimport:003-1-import-batch-baseline-audit
--comment Persistente audit van accept-baseline op de batch: wie, wanneer en waarom. accepted_by/accepted_at staan daarnaast op elke geschreven bronstaatrij; de reden had nergens anders een plaats.

-- Bewust geen check "status = BASELINE_ACCEPTED => audit gevuld": 001/002-tests en latere fasen mogen de status
-- zonder deze kolommen zetten; de service schrijft ze altijd samen met de statusovergang, in één transactie.
alter table import_batch add column baseline_accepted_by varchar(100);
alter table import_batch add column baseline_accepted_at timestamp with time zone;
alter table import_batch add column baseline_accept_reason varchar(500);

--rollback alter table import_batch drop column baseline_accept_reason;
--rollback alter table import_batch drop column baseline_accepted_at;
--rollback alter table import_batch drop column baseline_accepted_by;
