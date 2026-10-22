-- Schrijfrol van CatalogImport op de Prodis-PostgreSQL (publicatie, slice P5a).
-- Bron: docs/design/publicatie-prodis-db-direct-design.md par. 2.3; beslissingslog 2026-10-22 "Publicatiefase: P-V1..P-V5"
-- (P-V2: rechtstreeks schrijven, eigen DB-rol) en "Publicatie rechtstreeks in de Prodis-DB: Q1-Q3" (Q1 = b: geen
-- purchase_price).
--
-- Uit te voeren door de mens/DBA (infra), met psql, verbonden met de Prodis-database als superuser (of een rol met
-- CREATEROLE die ook eigenaar is van de tabellen):
--
--   psql -d Prodis -U <superuser> -f docs/ops/prodis-catalogimport-pub-role.sql
--   psql -d Prodis -U <superuser> -c '\password catalogimport_pub'      -- wachtwoord apart, nooit in de repo
--
-- Parameters (psql-variabelen, met deze defaults):
--   pub_role   naam van de rol             (default catalogimport_pub)
--   prodis_db  naam van de Prodis-database (default Prodis)
-- De lokale tests passen exact deze GRANT- en ALTER ROLE-opdrachten toe op de testrol catalogimport_pub_test in de
-- testdatabase prodis_pubtest (Web/src/test/java/.../testsupport/ProdisPublicationTestDatabase.java); CREATE ROLE doet daar
-- scripts/test/setup-prodis-pubtest.sql, de test vergelijkt de rolattributen met de regel hieronder.
--
-- v1 schrijft enkel met UPDATE: GEEN INSERT, DELETE, TRUNCATE of sequence-USAGE. Enkel de opgesomde kolommen.
-- CREATE (P8) krijgt later INSERT + sequence; dat is een aparte, bewuste uitbreiding van dit script.
-- Let op: de rolinstellingen (lock_timeout, ...) zijn sessiedefaults; PostgreSQL laat elke rol haar eigen defaults
-- wijzigen. De applicatie zet ze niet terug; de pool zet daarnaast zelf een query-time-out per opdracht.

\if :{?pub_role}
\else
\set pub_role catalogimport_pub
\endif
\if :{?prodis_db}
\else
\set prodis_db Prodis
\endif
\set ON_ERROR_STOP on

-- 1. De rol (superuser of CREATEROLE).
CREATE ROLE :"pub_role" LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 4;
ALTER ROLE :"pub_role" SET lock_timeout = '5s';
ALTER ROLE :"pub_role" SET statement_timeout = '30s';
ALTER ROLE :"pub_role" SET idle_in_transaction_session_timeout = '60s';

-- 2. De rechten (eigenaar van de tabellen of superuser), enkel deze kolommen.
GRANT CONNECT ON DATABASE :"prodis_db" TO :"pub_role";
GRANT USAGE ON SCHEMA public TO :"pub_role";
GRANT SELECT (id, number) ON supplier TO :"pub_role";
GRANT SELECT (id, supplier_id, legacy_code) ON supplier_group TO :"pub_role";
GRANT SELECT (id, supplier_id, scope_key, status) ON supplier_catalog TO :"pub_role";
GRANT SELECT (id, code) ON language TO :"pub_role";
GRANT SELECT (id, legacy_master) ON system_settings TO :"pub_role";
GRANT SELECT (id, supplier_id, supplier_group_id, supplier_catalog_id, supplier_reference_stripped, record_active,
              deleted_at) ON catalog_article TO :"pub_role";
GRANT SELECT (id, catalog_article_id, price_code, brut_price_politics, base_price, brut_price, brut_price_percentage,
              sales_price_last_updated, record_active, deleted_at),
      UPDATE (base_price, brut_price, brut_price_percentage, sales_price_last_updated, last_modified_by,
              last_modified_date) ON catalog_article_price TO :"pub_role";
GRANT SELECT (id, catalog_article_price_id, code, percentage, price, record_active, deleted_at),
      UPDATE (percentage, price, last_modified_by, last_modified_date) ON catalog_article_price_detail TO :"pub_role";
GRANT SELECT (id, catalog_article_id, language_id, description, record_active, deleted_at),
      UPDATE (description, last_modified_by, last_modified_date) ON catalog_article_description TO :"pub_role";
