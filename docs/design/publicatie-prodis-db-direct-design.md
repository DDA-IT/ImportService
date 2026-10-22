# Publicatie rechtstreeks in de Prodis-PostgreSQL — uitvoerbaar ontwerp

> **Status: VOORSTEL (2026-10-22), denker-zwaar.** Herwerkt `publicatie-prodis-db-design.md` naar de beslissingen van 2026-10-22
> (P-V1..P-V5: rechtstreeks schrijven, ook in modus PRODIS, eerst UPDATE-only). Bij tegenspraak geldt dit document.
> **Open bij de mens: Q1–Q3 (§7).** Kern: v1 schrijft enkel met `UPDATE` — geen `INSERT`, geen sequence, geen Prodis-schemawijziging;
> idempotentie in onze DB (`publication_run_item`) + "reconcile-by-state" binnen een Prodis-transactie met rijsloten.

## 1. Wat wijzigt t.o.v. het vorige ontwerp

| Vorig | Nieuw |
|---|---|
| Prodis-endpoint, stateless chain, token relay (P3) | vervalt (P-V2); ook D1 (CSRF). Het Prodis-voorstel `prodis/docs/internal/architecture/catalog-import-publication-api-design.md` is achterhaald — de mens beslist wat ermee gebeurt |
| Idempotentietabel in Prodis | vervalt; sleutel enkel in `publication_run_item`, veiligheid via reconcile-by-state (§2.4) |
| VKP berekend in Prodis | berekend in CatalogImport (port van `LegacyPriceCalculator`, §3) |
| Dry-run in Prodis | pure planner in CatalogImport via de leesbron (parallelrun = dry-run) |
| Guard `LEGACY_MASTER_NOT_NONE` | vervalt (P-V1): modus getoond, niets geweigerd (§5) |
| Behouden | `publication_run_item`, run-/itemstatussen, chunks + `continue` zonder scheduler, startcontroles, herstel, referentiedata, mapping, parallelrun, CREATE later |

## 2. Schrijfverbinding

**2.1 Config** (patroon S5): `catalogimport.prodis-db-write.url/username/password` (env `CATALOG_PRODIS_DB_WRITE_*`, geen default),
`connect-timeout` PT5S, `query-timeout` PT30S, `maximum-pool-size` 2; Hikari `readOnly=false`, `autoCommit=false`, `minimumIdle=0`,
`initializationFailTimeout=-1`, `ApplicationName=catalogimport-publish`, `logServerErrorDetail=false`. Niet geconfigureerd → 409
`PRODIS_WRITE_NOT_CONFIGURED`. Vlag `catalogimport.publication.prodis.enabled` (default false) blijft als noodrem.

**2.2 Lagen:** Web `config/ProdisPublicationDatabaseConfiguration` levert één eigen bean-type `ProdisPublicationJdbc` (Dao) — geen bean
van type `DataSource`/`JdbcTemplate` (constraint `ProdisReferenceJdbc.java` r.16-21). `inTransaction(work)`: één verbinding, expliciete
commit/rollback; fout tijdens `commit()` (SQLState-klasse 08) → `CommitOutcomeUnknownException`, andere fouten = zeker niet toegepast.
Dao `ProdisPublicationWriter` (alle SQL); Service `ProdisPublicationService` (itemlevenscyclus); `ProdisPublicationPlanner` en
`LegacyCatalogPriceFormula` puur (Service).

**2.3 DB-rol `catalogimport_pub`** (infra; script `docs/ops/prodis-catalogimport-pub-role.sql`, uit te voeren door mens/DBA):
```
CREATE ROLE catalogimport_pub LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 4;
ALTER ROLE catalogimport_pub SET lock_timeout='5s';
ALTER ROLE catalogimport_pub SET statement_timeout='30s';
ALTER ROLE catalogimport_pub SET idle_in_transaction_session_timeout='60s';
GRANT CONNECT ON DATABASE "Prodis" TO catalogimport_pub;  GRANT USAGE ON SCHEMA public TO catalogimport_pub;
GRANT SELECT (id, number) ON supplier TO catalogimport_pub;
GRANT SELECT (id, supplier_id, legacy_code) ON supplier_group TO catalogimport_pub;
GRANT SELECT (id, supplier_id, scope_key, status) ON supplier_catalog TO catalogimport_pub;
GRANT SELECT (id, code) ON language TO catalogimport_pub;
GRANT SELECT (id, legacy_master) ON system_settings TO catalogimport_pub;
GRANT SELECT (id, supplier_id, supplier_group_id, supplier_catalog_id, supplier_reference_stripped, record_active, deleted_at) ON catalog_article TO catalogimport_pub;
GRANT SELECT (id, catalog_article_id, price_code, brut_price_politics, base_price, brut_price, brut_price_percentage, sales_price_last_updated, record_active, deleted_at),
      UPDATE (base_price, brut_price, brut_price_percentage, sales_price_last_updated, last_modified_by, last_modified_date) ON catalog_article_price TO catalogimport_pub;
GRANT SELECT (id, catalog_article_price_id, code, percentage, price, record_active, deleted_at),
      UPDATE (percentage, price, last_modified_by, last_modified_date) ON catalog_article_price_detail TO catalogimport_pub;
GRANT SELECT (id, catalog_article_id, language_id, description, record_active, deleted_at),
      UPDATE (description, last_modified_by, last_modified_date) ON catalog_article_description TO catalogimport_pub;
```
Geen INSERT, DELETE, TRUNCATE of sequence-USAGE. Bij Q1 = (c) komt `UPDATE (purchase_price)` erbij; CREATE (P8) krijgt later INSERT + sequence.

**2.4 Volgorde per item (geen 2PC, herstartbaar):**
1. CI-tx: item `PENDING → SENT`, `send_count+1`, `sent_at`, `sent_by(_subject)`; commit.
2. Prodis-tx (READ COMMITTED, buiten elke CI-transactie): identiteit oplossen zonder lock (`supplier.number` + `supplier_group.legacy_code` +
   `supplier_catalog.scope_key` ACTIVE + `supplier_reference_stripped`) → precies één `catalog_article`, anders REJECTED
   `ARTICLE_NOT_FOUND`/`ARTICLE_AMBIGUOUS`/`SUPPLIER_AMBIGUOUS`; locks in vaste volgorde (`catalog_article_price` FOR UPDATE, details
   `ORDER BY code` FOR UPDATE, NL-omschrijving FOR UPDATE); vormcontrole (één prijsrij, details "1".."5" elk één keer, één NL-rij, alles actief)
   anders REJECTED `PRODIS_ROW_SHAPE_UNSUPPORTED`; planner berekent prior → target; REJECTED/CONFLICT → rollback; anders `UPDATE … WHERE id=?`
   enkel op echt wijzigende rijen met update-count = 1; commit.
3. CI-tx: outcome `APPLIED|UNCHANGED|REJECTED|CONFLICT` met `prior_json`, `result_json`, `warnings_json`, `legacy_master`; mutatiestatus.

Foutklassen: fout vóór `commit()` (40P01, 40001, 55P03, 57014, 08) → zeker niet toegepast → `PENDING` met `last_error_code`; fout tijdens
`commit()` of crash tussen 2 en 3 → `RESULT_UNKNOWN`.

**Reconcile-by-state:** elk geschreven veld is een absolute waarde; per bewaakt veld is de Prodis-waarde toegestaan als ze ∈ {expected,
target}, anders CONFLICT; daarna het volledige target zetten; niets te wijzigen → `UNCHANGED`. Een retry van `RESULT_UNKNOWN` vindt dus
target en schrijft niets dubbel; `sales_price_last_updated` en audit schuiven enkel bij echte wijziging. Geen Prodis-Liquibase.

**Gelijktijdigheid:** chunk-lease op `publication_run` (`lease_owner`, `lease_until`), tweede `continue` → 409 `PUBLICATION_RUN_BUSY`;
`SENT` onder een verlopen lease → `RESULT_UNKNOWN`, eerst opnieuw; partiële unieke index: hoogstens één actieve PRODUCTION-run.

**2.5 P-V4 `expected`:** basisprijs = `import_mutation.before_base_price` (exact met `numeric(21,6)` als BigDecimal). Percentages: nieuwe
additieve kolom `publication_bundle_snapshot_price.before_percentage`, gevuld bij het bevriezen uit `catalog_source_state_price`, met
`snapshot_spec_version` 2 (in de hash); spec-v1-bundels schrijven geen percentages (KEEP, WARNING `EXPECTED_NOT_AVAILABLE`); vergelijking
`BigDecimal.valueOf(prodisDouble).compareTo(before) == 0`. Omschrijving: nooit CONFLICT, melding `DESCRIPTION_OVERWRITTEN` als de overschreven
waarde ≠ de nieuwe (aanname). Leeg (P-V5): KEEP + `EMPTY_KEEPS_EXISTING`, KEEP-velden niet bewaakt.

**2.6 Precisie** (REJECTED `PRECISION_LOSS` met veld): basisprijs ≤ 6 decimalen en ≤ 15 cijfers voor de komma (`numeric(21,6)`, entiteit
`Double`, `CatalogArticlePrice.java` r.34-47); percentages ≤ 15 significante cijfers. Enkel melding `EXCEEDS_LEGACY_PRECISION` bij > 3 decimalen
voor bedragen of > 2 voor percentages (legacy `#6.#3`, `N###.##`).

## 3. Prijsberekening (`LegacyCatalogPriceFormula`, puur)
- Letterlijke port van `LegacyPriceCalculator.calculate` (r.31-73): `bverk * pct / 100d * coef`, `BigDecimal.valueOf(d).setScale(dec, HALF_UP).doubleValue()`
  — bit-identiek aan Prodis.
- Catalogusparameters zoals Prodis' Prg_261-port (`SupplierGroupPricePropagationService` r.267-282): `coefficient=1`, `pricePolicy=""`,
  `decimals=2` (`ARBIB_Aant_dec_prijs` niet gesynchroniseerd), salesBase = basisprijs.

| `price_code` | Basisprijs | Onze VKP% | Afgeleid |
|---|---|---|---|
| `0` | schrijven | schrijven (KEEP → Prodis-waarde) | VKP1..5 `price` herberekend; brut volgens `brut_price_politics` |
| `2` | schrijven | niet (legacy neemt geen import-% over), melding `PERCENTAGES_NOT_APPLICABLE` | VKP herberekend met bestaande % |
| `1` | schrijven | niet (legacy zet ze op 0), melding | VKP ongemoeid; brut enkel bij `0` |
| anders | — | — | REJECTED `PRICE_CODE_UNSUPPORTED` |

- `sales_price_last_updated` = `now() at time zone 'UTC'`, enkel als een VKP1..5-prijs echt wijzigt (Prg_1179 r.510).
- `PRICE_ROUNDING_DIFFERENCE`: snapshot levert een `source_amount` dat afwijkt van de berekende prijs → WARNING; altijd de berekende waarde
  schrijven, nooit stil corrigeren.
- Golden tests: de 9 gevallen uit `LegacyPriceCalculatorTest` r.16-130, het catalogusgeval uit `SupplierGroupPricePropagationServiceTest`
  r.161-177, ≥ 12 grensgevallen; verwachte waarden gegenereerd met de Prodis-klasse zelf → `legacy-price-golden.csv` met Prodis-commithash.

## 4. Audit, tenant, caches

| Kolom | Wat wij invullen | Waarom |
|---|---|---|
| `last_modified_by` | Keycloak-subject van de actor (fallback `preferred_username`) | `SpringSecurityAuditorAware.java` r.17-19 |
| `last_modified_date` | `now() at time zone 'UTC'` | Prodis `hibernate.jdbc.time_zone: UTC` |
| `created_*`, `tenant_id`, `record_active`, `deleted_*` | ongemoeid (UPDATE) | bij CREATE (P8) te beslissen |

Geen L2-cache op `CatalogArticle*` (Prodis `AGENTS.md` r.134), geen Elasticsearch/events/materialized views, triggers enkel op `article*`.
**Restrisico:** Prodis-entiteiten hebben geen `@Version`; een gelijktijdige `SupplierGroupPricePropagationService` kan na ons de oude
basisprijs terugschrijven (lost update) — operationeel niet tegelijk draaien; de volgende parallelrun toont het.

## 5. Modus PRODIS
Leesbron leest `system_settings.legacy_master` (eerste rij op id; ontbreekt → PRODIS, zoals `LegacyMasterResolver` r.42). Bewaard als
`publication_run.legacy_master_at_start` en `publication_run_item.legacy_master` (per chunk). Preview, parallelrun, run-view en bevestiging tonen
de modus; bij PRODIS vaste waarschuwing "De volgende datasync zet deze wijzigingen terug naar de legacy-waarden (bewust aanvaard, beslissing
2026-10-22)". Onleesbaar → "onbekend" + waarschuwing, geen weigering; PROFAB idem (aanname).

## 6. Bouwvolgorde (strikt sequentieel)

| # | Inhoud | Trap | Acceptatie |
|---|---|---|---|
| P1 | Leesbron: SELECT-GRANTs voor `catalogimport_ro` op de catalogustabellen + `system_settings` + `language`; `legacy_master` lezen | gemiddeld | onbereikbaar → 503/UNKNOWN; geen Prodis-id's bewaard |
| P2a | `LegacyCatalogPriceFormula` + golden CSV | zwaar | golden bit-identiek |
| P2b | `ProdisPublicationProjection` + `ProdisPublicationPlanner` | zwaar | deterministisch; elke regel een test |
| P2c | Parallelrun op de SIMULATION-run (ro-rol) | zwaar | niets geschreven; 2× identiek |
| P4a | Liquibase 027: `publication_run_item`, `PUBLISHING`, lease-/moduskolommen, partiële index, `before_percentage` | gemiddeld | constraints onder gelijktijdigheid |
| P4b | Bevriezen vult `before_percentage`, snapshot spec v2 | zwaar | `content_hash` byte-identiek voor v1 |
| P5a | Schrijfverbinding + config + rolscript + testopzet | zwaar | fail-fast; niet-gegrante actie → 42501 |
| P5b | Writer + `ProdisPublicationService` | zwaar | 2× item → één wijziging; commit-fout → RESULT_UNKNOWN → retry UNCHANGED; CONFLICT schrijft niets |
| P5c | Orchestratie start/continue/lease/afronden; bronstaat (na Q3) | zwaar | REJECTED → PARTIALLY_PUBLISHED; tweede continue → 409 |
| P6 | Endpoints continue, itemlijst, foutcodes, rechten | gemiddeld | coverage groen |
| P7 | Frontend: bevestiging met modus + waarschuwing, continue-lus, itemresultaten, parallelrapport | gemiddeld | afwijking en afronding zichtbaar |
| P8 | CREATE met creatiestandaarden; INSERT-GRANTs; sequencekeuze | zwaar | later |

Generiek: `mvn -pl Web -am test "-Dtest=<Klassen>" -Dsurefire.failIfNoSpecifiedTests=false`. Volledige ronde na P5c door de mens.
**Operationele poort:** echt schrijven pas na beoordeling van het P2c-rapport.

**Veilige testopzet:** eenmalig `scripts/test/setup-prodis-pubtest.sql` (mens, als superuser in `prodis-postgresql-1`): database
`prodis_pubtest` (eigenaar `catalog_import`) + rol `catalogimport_pub_test`; tests bouwen het schema uit `prodis-publication-test-schema.sql`
(exacte kopie van de acht tabellen uit de Prodis-Liquibase), passen hetzelfde rolscript toe op de testrol en verbinden als die rol. Guard:
`current_database() ≠ 'prodis_pubtest'` → test stopt; nooit naar `Prodis` schrijven. Opt-in `ProdisSchemaParityTest`
(`-Dprodis.schemaParity=true`) vergelijkt `information_schema.columns` van de echte `Prodis` alleen-lezend. H2 volstaat niet.

## 7. Vragen voor de mens
- **Q1 (financieel, blokkeert P2a/P2b): welke afgeleide velden schrijven we?** (a) enkel VKP1..5-prijs; (b) (a) + `brut_price` (AVP1) + reset
  `brut_price_percentage := 0` volgens Prg_1185; (c) (b) + `purchase_price`. **Aanbeveling (b):** bij (c) zou `purchase_price` de AKP2-mirror
  zijn terwijl 1185 AKP1 berekent (D6).
- **Q2 (blokkeert P2b): expliciete 0 voor basisprijs of VKP%?** Legacy 1179 behoudt dan de bestaande waarde (r.463, r.474-478).
  **Aanbeveling:** zoals legacy: KEEP + WARNING `ZERO_KEEPS_EXISTING`.
- **Q3 (blokkeert P5c): bronstaat na publicatie.** A7 ("`catalog_source_state` ongewijzigd") spreekt `SourceStateBaselineService.java`
  r.28-32 tegen; zonder bijwerken levert elke volgende levering dezelfde mutaties opnieuw. **Aanbeveling:** bij APPLIED/UNCHANGED in dezelfde
  CI-transactie de bronwaarden naar `catalog_source_state`, `_price` en `catalog_price_observation` schrijven (`state_origin=PUBLISHED`);
  bij REJECTED/CONFLICT niets.

**Aannames:** audit = subject van de actor; PROFAB = PRODIS; melding omschrijving zonder voor-waarde; `before_percentage` + snapshot v2;
chunk 200, lock_timeout 5s; geen stop-actie voor een PUBLISHING-run; REJECTED/CONFLICT → mutatie terug naar `READY_FOR_PUBLICATION`; niet-EUR →
REJECTED `CURRENCY_NOT_SUPPORTED_BY_PRODIS`.

## 8. Ontdekkingen
- *Technisch:* `CatalogArticleJob` haalt id's uit sequence `sequencegenerator` (`SequenceIdHelper.java` r.9, r.31), Prodis-JPA uit
  `sequence_generator` (pooled +50); twee sequences op dezelfde tabellen = bestaand risico op id-botsingen; elke CREATE moet expliciet kiezen.
- *Technisch:* de datasync schrijft per artikel altijd een prijsrij, vijf detailrijen en een NL-omschrijving (`CatalogArticleMapper.java`
  r.60, r.135-139) → UPDATE kan zonder INSERT; geen L2-cache/trigger/zoekindex op `catalog_article*`; geen `@Version`.
- *Business:* Prg_1179: 0 of leeg = behouden voor basisprijs en VKP%; prijscode `1` zet % op 0 en neemt VKP-bedragen uit de bron; `2` neemt
  geen import-% over; Prg_1185 rekent met coëfficiënt, prijsbeleid en decimalen van de catalogus, die niet naar Prodis gesynchroniseerd zijn →
  voor catalogi met coef ≠ 1 of ≠ 2 decimalen wijkt de berekening af van legacy (parallelrun maakt het zichtbaar).
- *Technisch:* Prodis schrijft UTC met het Keycloak-subject; de datasync Europe/Brussels met `SYNC` (en `deleted_by='SYNC'` ook bij niet-verwijderde
  rijen). Wij volgen Prodis.
- *Business:* A7 spreekt "bronstaat enkel na publicatie bijgewerkt" (`SourceStateBaselineService.java` r.28-32) tegen — zie Q3.
