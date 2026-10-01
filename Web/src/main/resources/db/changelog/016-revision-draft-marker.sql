--liquibase formatted sql

-- NT-13 (beslissingslog 2026-10-01, "Woordkeuzes en openstaande punten NT-spoor"): hoogstens een concept (DRAFT) per
-- importdefinitie, door de database afgedwongen. Tot nu toe bewaakte enkel het opvolgerpad (RevisionSuccessorService)
-- dit met een leescontrole; SetupService.createRevision niet, en twee gelijktijdige verzoeken konden beide slagen.
--
-- Zelfde NULL-markertruc als active_marker (001): draft_marker is TRUE zolang de revisie DRAFT is en NULL in elke andere
-- toestand; NULL-waarden botsen niet in een UNIQUE constraint. Bewust geen PostgreSQL-only partieel unique index:
-- het bestaande patroon (uk_import_definition_revision_active) werkt op alle dialecten en wordt door de entiteit
-- zelf bijgehouden (ImportDefinitionRevision.syncActiveMarker).
--
-- ADDITIEF naast 001-015: een nieuwe nullable kolom + een unieke sleutel; niets hernoemd, niets verwijderd.

--changeset catalogimport:016-1-revision-draft-marker
--comment draft_marker op import_definition_revision + unieke sleutel (import_definition_id, draft_marker): hoogstens een DRAFT per definitie.
--preconditions onFail:HALT onError:HALT
--precondition-sql-check expectedResult:0 select count(*) from (select import_definition_id from import_definition_revision where status = 'DRAFT' group by import_definition_id having count(*) > 1) duplicate_drafts

-- De precondition hierboven stopt de migratie wanneer er al een definitie met twee of meer DRAFT-revisies bestaat: die
-- data wordt NOOIT stil samengevoegd of verwijderd. Los het eerst handmatig op (een van de concepten activeren of
-- door een beheerder laten verwijderen) en start dan opnieuw. Controle:
--   select import_definition_id, count(*) from import_definition_revision where status = 'DRAFT'
--   group by import_definition_id having count(*) > 1;
alter table import_definition_revision add column draft_marker boolean;

-- Backfill: elke bestaande DRAFT krijgt de marker (de precondition garandeert dat dit nooit botst).
update import_definition_revision set draft_marker = true where status = 'DRAFT';

-- Bewust geen check die marker en status koppelt: ook active_marker heeft die op de revisietabel niet, en rechtstreekse
-- SQL-inserts (testdata) zouden er onnodig op breken. De entiteit houdt de marker consistent.
alter table import_definition_revision add constraint uk_import_definition_revision_draft
    unique (import_definition_id, draft_marker);

--rollback alter table import_definition_revision drop constraint uk_import_definition_revision_draft;
--rollback alter table import_definition_revision drop column draft_marker;
