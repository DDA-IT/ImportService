--liquibase formatted sql

-- Stap 6 (beslissingslog 2026-10-02, "Stap 6, 8 en 9 gestart"): de status van de vier kernentiteiten werd tot nu toe enkel
-- door de Java-enum bewaakt (kolom varchar(40) zonder CHECK). Een rechtstreekse SQL-update of een toekomstige enumwaarde
-- zonder migratie kon een onbekende status wegschrijven. Elke tabel krijgt nu een CHECK met EXACT de waarden van de enum
-- (JPA slaat ze op als EnumType.STRING). De kolommen zijn NOT NULL, dus NULL
-- is al uitgesloten en de IN-check (die bij NULL zou slagen) laat geen NULL-achterdeur open.
-- Een nieuwe enumwaarde vraagt voortaan een migratie die de check uitbreidt (bewust).
-- Daarnaast wordt ck_publication_bundle_batch_removed NULL-veilig gemaakt (zie 018-5).
--
-- ADDITIEF naast 001-017: nieuwe CHECK-constraints; niets hernoemd, geen data gewijzigd. Elke precondition stopt de
-- migratie (HALT) bij bestaande rijen buiten de set: die data wordt NOOIT stil aangepast of verwijderd.

--changeset catalogimport:018-1-import-batch-status-check
--comment CHECK ck_import_batch_status: status uitsluitend de waarden van ImportBatchStatus.
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 select count(*) from import_batch where status not in ('RECEIVED', 'SCREENING', 'MUTATING', 'SCREENED', 'BLOCKED', 'FAILED', 'BASELINE_ACCEPTED')

-- De precondition hierboven stopt de migratie wanneer import_batch een status buiten ImportBatchStatus bevat. Controle:
--   select status, count(*) from import_batch where status not in ('RECEIVED', 'SCREENING', 'MUTATING', 'SCREENED', 'BLOCKED', 'FAILED', 'BASELINE_ACCEPTED') group by status;
alter table import_batch add constraint ck_import_batch_status check (status in
    ('RECEIVED', 'SCREENING', 'MUTATING', 'SCREENED', 'BLOCKED', 'FAILED', 'BASELINE_ACCEPTED'));

--rollback alter table import_batch drop constraint ck_import_batch_status;

--changeset catalogimport:018-2-import-mutation-status-check
--comment CHECK ck_import_mutation_status: status uitsluitend de waarden van MutationStatus.
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 select count(*) from import_mutation where status not in ('PLANNED', 'BLOCKED', 'AWAITING_APPROVAL', 'READY_FOR_PUBLICATION', 'IN_PROGRESS', 'PUBLISHED', 'TECHNICALLY_FAILED', 'REJECTED', 'EXPIRED', 'SKIPPED', 'RECORDED')

-- De precondition hierboven stopt de migratie wanneer import_mutation een status buiten MutationStatus bevat. Controle:
--   select status, count(*) from import_mutation where status not in ('PLANNED', 'BLOCKED', 'AWAITING_APPROVAL', 'READY_FOR_PUBLICATION', 'IN_PROGRESS', 'PUBLISHED', 'TECHNICALLY_FAILED', 'REJECTED', 'EXPIRED', 'SKIPPED', 'RECORDED') group by status;
alter table import_mutation add constraint ck_import_mutation_status check (status in
    ('PLANNED', 'BLOCKED', 'AWAITING_APPROVAL', 'READY_FOR_PUBLICATION', 'IN_PROGRESS', 'PUBLISHED', 'TECHNICALLY_FAILED', 'REJECTED', 'EXPIRED', 'SKIPPED', 'RECORDED'));

--rollback alter table import_mutation drop constraint ck_import_mutation_status;

--changeset catalogimport:018-3-task-run-status-check
--comment CHECK ck_task_run_status: status uitsluitend de waarden van TaskRunStatus.
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 select count(*) from task_run where status not in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')

-- De precondition hierboven stopt de migratie wanneer task_run een status buiten TaskRunStatus bevat. Controle:
--   select status, count(*) from task_run where status not in ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED') group by status;
alter table task_run add constraint ck_task_run_status check (status in
    ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'));

--rollback alter table task_run drop constraint ck_task_run_status;

--changeset catalogimport:018-4-import-definition-revision-status-check
--comment CHECK ck_import_definition_revision_status: status uitsluitend de waarden van RevisionStatus.
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 select count(*) from import_definition_revision where status not in ('DRAFT', 'SCREENING', 'REVIEW_REQUIRED', 'PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'WITHDRAWN')

-- De precondition hierboven stopt de migratie wanneer import_definition_revision een status buiten RevisionStatus bevat. Controle:
--   select status, count(*) from import_definition_revision where status not in ('DRAFT', 'SCREENING', 'REVIEW_REQUIRED', 'PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'WITHDRAWN') group by status;
alter table import_definition_revision add constraint ck_import_definition_revision_status check (status in
    ('DRAFT', 'SCREENING', 'REVIEW_REQUIRED', 'PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'WITHDRAWN'));

--rollback alter table import_definition_revision drop constraint ck_import_definition_revision_status;

--changeset catalogimport:018-5-publication-bundle-batch-removed-null-safe
--comment ck_publication_bundle_batch_removed NULL-veilig: active_marker is true i.p.v. = true (zelfde les als 009, ck_publication_run_marker_status).
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 select count(*) from publication_bundle_batch where not ((removed_by is null and removed_at is null and removed_reason is null and active_marker is true) or (removed_by is not null and removed_at is not null and removed_reason is not null and active_marker is null))

-- De oorspronkelijke check (005-2) gebruikte "active_marker = true": bij active_marker NULL en alle removed-velden leeg
-- evalueert dat tot NULL en een CHECK slaagt bij NULL, dus een actief lidmaatschap zonder marker glipte erdoor en
-- schakelde uk_publication_bundle_batch_active (hoogstens een actief lidmaatschap per batch) uit. De precondition stopt
-- de migratie wanneer bestaande data de nieuwe check niet haalt (nooit stil herstellen). Controle: de select hierboven
-- als "select id, ..." zonder count.
alter table publication_bundle_batch drop constraint ck_publication_bundle_batch_removed;
alter table publication_bundle_batch add constraint ck_publication_bundle_batch_removed check (
    (removed_by is null and removed_at is null and removed_reason is null and active_marker is true)
    or (removed_by is not null and removed_at is not null and removed_reason is not null
        and active_marker is null)
);

--rollback alter table publication_bundle_batch drop constraint ck_publication_bundle_batch_removed;
--rollback alter table publication_bundle_batch add constraint ck_publication_bundle_batch_removed check ((removed_by is null and removed_at is null and removed_reason is null and active_marker = true) or (removed_by is not null and removed_at is not null and removed_reason is not null and active_marker is null));
