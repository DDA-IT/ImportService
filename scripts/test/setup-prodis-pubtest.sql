-- Lokale testopzet voor de publicatie naar Prodis (slice P5a; docs/design/publicatie-prodis-db-direct-design.md par. 6
-- "Veilige testopzet"; beslissingslog 2026-10-22 "Lokale testdatabase voor de publicatie").
--
-- ENKEL LOKAAL, in de Docker-container prodis-postgresql-1. Maakt een APARTE database prodis_pubtest (eigenaar
-- catalog_import) en de testrol catalogimport_pub_test. Raakt de echte database Prodis NIET aan.
-- Idempotent: mag opnieuw uitgevoerd worden. Uitvoeren als superuser:
--
--   docker exec -i prodis-postgresql-1 psql -U Prodis -d postgres < scripts/test/setup-prodis-pubtest.sql
--
-- De tabellen zelf maakt de testcode aan (Web/src/test/resources/prodis-publication-test-schema.sql, als eigenaar
-- catalog_import); de rechten en de rolinstellingen (lock_timeout, statement_timeout, ...) past de testcode toe uit
-- hetzelfde rolscript als productie (docs/ops/prodis-catalogimport-pub-role.sql). Enkel CREATE ROLE gebeurt hier, omdat
-- daar superuser- of CREATEROLE-rechten voor nodig zijn; de test vergelijkt de rolattributen met dat rolscript.
\set ON_ERROR_STOP on

SELECT 'CREATE DATABASE prodis_pubtest OWNER catalog_import'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'prodis_pubtest')\gexec
ALTER DATABASE prodis_pubtest OWNER TO catalog_import;

-- Zelfde attributen als CREATE ROLE in docs/ops/prodis-catalogimport-pub-role.sql, plus een testwachtwoord (geen echt
-- secret: de rol bestaat enkel lokaal en heeft enkel rechten in prodis_pubtest).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'catalogimport_pub_test') THEN
        CREATE ROLE catalogimport_pub_test LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 4;
    END IF;
END
$$;
ALTER ROLE catalogimport_pub_test LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 4
    PASSWORD 'catalogimport_pub_test';

\connect prodis_pubtest
-- De trigram-indexen van de Prodis-tabellen (exacte kopie) vragen pg_trgm, zoals in de echte database.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
SELECT current_database() AS database, pg_get_userbyid(datdba) AS owner
FROM pg_database WHERE datname = current_database();
