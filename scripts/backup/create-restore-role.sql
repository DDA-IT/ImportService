-- Eenmalig aan te maken door een databasebeheerder (superuser of rol met CREATEROLE).
-- Maakt een aparte rol voor de hersteltest (restore-and-verify.sh/.ps1), zodat de
-- applicatierol `catalog_import` geen CREATEDB-recht hoeft te krijgen.
--
-- Het wachtwoord staat NIET in dit bestand; geef het mee als psql-variabele:
--   psql -d postgres -v restore_password='<geheim>' -f create-restore-role.sql
--
-- Gebruik daarna in de omgeving van de hersteltest: PGUSER=catalog_import_restore en
-- PGPASSWORD=<geheim> (of .pgpass). Zie docs/design/backup-herstel-design.md §5.

\if :{?restore_password}
\else
  \echo 'FOUT: geef het wachtwoord mee met -v restore_password=...'
  \quit
\endif

CREATE ROLE catalog_import_restore LOGIN CREATEDB PASSWORD :'restore_password';
