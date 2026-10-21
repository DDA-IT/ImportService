--liquibase formatted sql

-- Legacy-config S8f: EN-groepen van recordfilters voor PSIMPEXCL (docs/design/legacy-config-s8-design.md par. 2f en 3;
-- docs/design/legacy-config-bundle-design.md par. 4 "025-record-filter-match-group"; beslissingslog 2026-10-21
-- "Legacy-config S8", V2 = A). Volledig ADDITIEF: geen bestaande kolom of constraint wijzigt; er wordt niets gebackfilld.
--
-- Waarom: een legacy-uitsluiting (PSIMPEXCL) treft een record pas als meerdere identiteitsdelen tegelijk kloppen
-- (bv. groep EN kortingscode EN referentie, Prg_1170 r.2640-2686). Een filterrij vergelijkt een kolom; filterrijen met
-- dezelfde match_group vormen samen een EN-regel: het record valt buiten de scope als ALLE rijen van de groep matchen.
-- De groep werkt op de bronkolommen, direct na het parsen en voor de identiteit (R-FLT-02), en wordt geevalueerd op de
-- plaats van haar laagste sequence_number.
--
-- Regels:
--   * match_group is null = de rij gedraagt zich exact zoals voor S8f (elke bestaande rij).
--   * match_group > 0.
--   * Een rij in een groep is altijd EXCLUDE met null_behaviour COMPARE_AS_EMPTY: een INCLUDE/REJECT-groep of een lege
--     waarde die op zich al een eindbeslissing geeft, zou de EN-betekenis van de groep breken. Een lege waarde wordt dus
--     gewoon als '' vergeleken (legacy: '' is letterlijk leeg).
--   * Een groep met een ongeldige samenstelling die de database niet ziet (bv. een rij met een fout) bewaakt de
--     configuratievalidatie (CONFIG_FILTER_INVALID), zoals voor elke filterrij (M4).
--
-- Rollback: de kolom en haar checks verdwijnen; een gegroepeerde rij wordt dan een losse EXCLUDE-rij en sluit meer uit
-- dan bedoeld. Enkel terugdraaien zolang geen enkele rij een match_group heeft.

--changeset catalogimport:025-1-import-record-filter-match-group
--comment S8f: EN-groep van recordfilters (PSIMPEXCL); additief, null = ongewijzigd gedrag.
alter table import_record_filter add column match_group integer;

alter table import_record_filter add constraint ck_import_record_filter_match_group_positive
    check (match_group is null or match_group > 0);
alter table import_record_filter add constraint ck_import_record_filter_match_group_exclude
    check (match_group is null or (outcome = 'EXCLUDE' and null_behaviour = 'COMPARE_AS_EMPTY'));

--rollback alter table import_record_filter drop constraint ck_import_record_filter_match_group_exclude;
--rollback alter table import_record_filter drop constraint ck_import_record_filter_match_group_positive;
--rollback alter table import_record_filter drop column match_group;
