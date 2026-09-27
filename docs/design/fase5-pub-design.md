# Ontwerp Fase 5-PUB (deel a) — bundelsnapshot bij bevriezen en publicatierun in SIMULATION

Bindend na `docs/decisions.md` 2026-09-26 ("Vier keuzes uit het beslisdossier" en "5-PUB: ontwerp bindend"). Bouwstappen 5P-1 … 5P-8, strikt sequentieel.
Er wordt NIETS naar ProDisWebbase, PSIMPORT of Pervasive geschreven. 5-PUB-b (TRIAL_LIBRARY) en 5-PUB-c (PRODUCTION) blijven dicht tot het verwerkingscontract
(252 IMPORT/1179, ARIMP_DELETE-codes, OUT02) bewezen is.

## 1. Bundelsnapshot (changeset `008-publication-snapshot.sql`, additief; 001-007 ongewijzigd)

- **008-1 `publication_bundle_snapshot`** (één rij per te publiceren mutatie): `id`, `bundle_id` fk, `mutation_id` fk, `batch_id`, `import_link_id`, `source_row_number`,
  `description varchar(1000)`, `description_state varchar(20) not null` (`VALUE|EMPTY|NOT_MAPPED`, zelfde patroon als `identity_discount_state`), `created_at`.
  `unique (mutation_id)` en `unique (bundle_id, mutation_id)`.
- **008-2 `publication_bundle_snapshot_price`**: PK `(snapshot_id, component_code)`, `source_amount numeric(24,6)`, `percentage numeric(24,12)`, `currency varchar(3)`, `status varchar(30) not null`;
  exact dezelfde types als `import_candidate_price`, kopie zonder herberekening of afronding.
- **008-3 `publication_bundle`** krijgt nullable `snapshot_hash` (hash-type van de bestaande `content_hash`) en `snapshot_spec_version varchar(10)`.
- **Bewezen behoefte:** `DESCRIPTION` en `VKP1_PCT…VKP5_PCT` (`PsimportPreviewMapper` zet ze nu hard op `NOT_AVAILABLE_IN_MUTATION`; h.26 vraagt ze). Meegenomen omdat ze in dezelfde tabel staan:
  `AKP` en `VKP_GROSS`. **Niet gekopieerd:** basisprijs, valuta, identiteit en referenties (staan al op `import_mutation`, dat nooit wordt opgeruimd; dupliceren zou een tweede bron van waarheid voor een bedrag zijn).
  Geen `*_by_subject`: de ondertekenaar staat al op `publication_bundle.frozen_by(_subject)` en op de FREEZE-beslissingsregel.
- **Vullen** in `BundleFreezeService.freeze`, binnen dezelfde transactie, ná `approveRemainingPlanned` en vóór `computeContentHash`/`recordFreeze`: set-based `insert … select` uit `import_candidate_stage`/`import_candidate_price` op
  `(m.batch_id, m.source_row_number) = (s.batch_id, s.row_number)`, met dezelfde scope als `PsimportPreviewDao.FROM_WHERE`. **Staging weg = blokkeren:** tel mutaties in scope en geschreven snapshotrijen; bij verschil 409
  `SNAPSHOT_SOURCE_MISSING` en de hele bevriezing rolt terug. Nooit stil leeg of 0.
- **Hash (keuze mens):** `computeContentHash` blijft **byte-identiek**; de snapshot krijgt een aparte `snapshot_hash` (zelfde serialisatiestijl: U+001F/U+001E, `stripTrailingZeros().toPlainString()`), over snapshot en prijscomponenten.
- **Reeds bevroren bundels (keuze mens):** geen automatische backfill; ze houden `snapshot_hash = null` (afgeleide toestand `NO_SNAPSHOT`); de preview toont `NOT_SNAPSHOTTED`. Een SIMULATION-run mag er wel op draaien, met
  `incompleteRowCount > 0` (niets wordt stil ingevuld).
- **Retentie kandidaatstaging:** staging van batch B mag pas weg als B terminaal is, B geen actief lidmaatschap heeft in een niet-geannuleerde bundel, én elke bundel waarin B zat een `snapshot_hash` draagt. Nu alleen de regel en een
  read-only `StagingRetentionDao.findPurgeableBatches` met test; de `delete` zelf en een scheduler komen later. Tot 5P-2 gebouwd is, is elke opruiming van `import_candidate_stage` verboden.

## 2. PSIMPORT-preview slice 2

`PsimportPreviewDao` krijgt een `left join` op de snapshottabellen; `PsimportPreviewMapper`: `DESCRIPTION` en `VKPn_PCT` worden `VALUE|EMPTY|NOT_MAPPED`, nieuwe state `NOT_SNAPSHOTTED` voor oude bundels; percentages als string
(`toPlainString()`), nooit een stille 0. `previewSpecVersion` 1 → 2; `contractStatus` blijft `UNVERIFIED_FIELD_INVENTORY`. `PROCESS/DELETE/RECORD/NUMBER` blijven `NOT_CONTRACTED`.

## 3. Publicatierun (changeset `009-publication-run.sql`)

`publication_run`: `id`, `bundle_id` fk not null, `target_mode varchar(30) not null` (check op de drie waarden, geen default), `attempt int not null default 1`, `status varchar(30) not null`, `requested_by varchar(100) not null`,
`requested_by_subject varchar(255)`, `requested_at/started_at/finished_at`, `bundle_content_hash` (kopie op runmoment), `snapshot_hash`, `payload_hash`, `artifact_reference varchar(500)`, `artifact_sha256`, `artifact_byte_size`,
`row_count`, `incomplete_row_count`, `failure_code varchar(60)`, `failure_message varchar(1000)`, `idempotency_key varchar(200) not null unique` (`run:<bundleId>:<mode>:<attempt>`), `active_marker boolean`.
**Concurrency:** `unique (bundle_id, active_marker)` (NULL-markertruc zoals `uk_import_batch_open`): hoogstens één niet-terminale run per bundel, door de database afgedwongen.

**Statusmachine.** Gebruikt: `REQUESTED → PREPARING → SIMULATED` (terminaal, geslaagd) of `→ FAILED` (terminaal). Gedeclareerd maar nooit gezet (5-PUB-b/c): `WAITING_FOR_TARGET_CONTRACT`, `READY_FOR_DELIVERY`,
`WRITTEN_TO_PSIMPORT`, `RESULT_UNKNOWN`, `APPLIED`, `REJECTED_BY_PRODIS`, `RECOVERY_REQUIRED`. `SIMULATED` = artefact geschreven, **geen enkel operationeel effect**; nooit te verwarren met `APPLIED`.
`publication_bundle.status` blijft `FROZEN`; mutatiestatussen en `catalog_source_state` worden niet aangeraakt.

**Artefact (keuze mens):** het PSIMPORT-CSV-artefact (banner en vaste header van `PsimportPreviewCsvSerializer`) wordt op het bestandssysteem bewaard via een nieuwe `PublicationArtifactStore` (patroon `DeliveryArchiveStore`: root-property,
`<yyyy>/<MM>/<dd>/<runId>/…`), SHA-256 en bytes in de database, de bytes zelf nooit. Geen `publication_run_item`-tabel nu. De bestaande `toCsv(...)` bouwt de hele CSV in het geheugen; 5P-7 schrijft paginegewijs naar de stroom
(of voegt een streaming-overload toe) en laat de bestaande methode ongewijzigd.

**Annuleren (keuze mens):** een geslaagde SIMULATION-run blokkeert het annuleren van de bundel NIET; de runs blijven als auditspoor staan.

## 4. Endpoints, rechten, fouten

| Endpoint | Recht |
|---|---|
| `POST /bundles/{id}/publication-runs` (alleen SIMULATION) | APPROVE (A2 van 2026-09-25: "publiceren, alle modi") |
| `GET /bundles/{id}/publication-runs`, `GET /publication-runs/{runId}`, `GET /publication-runs/{runId}/artifact` | READ |

Foutvolgorde zoals `fase5-perm-design.md` §3; servicevolgorde: 400 `PUBLICATION_MODE_REQUIRED`/`_UNKNOWN` → 409 `PUBLICATION_MODE_NOT_ENABLED` (TRIAL_LIBRARY/PRODUCTION altijd) → 404 `BUNDLE_NOT_FOUND` →
409 `BUNDLE_NOT_FROZEN` → 409 `PUBLICATION_RUN_IN_PROGRESS` → 409 `BUNDLE_CONTENT_CHANGED_SINCE_FREEZE` (herberekende hash ≠ opgeslagen). Elk antwoord en de CSV-banner dragen `simulationOnly: true`, `writesToProdis: false`,
`contractStatus: "UNVERIFIED_FIELD_INVENTORY"`, `previewSpecVersion` en `snapshotSpecVersion`. Nieuwe endpoints komen in `fase5-perm-design.md` §1 en in `PermissionCoverageTest`.

## 5. Expliciet NIET in scope

Geen schrijfactie naar ProDisWebbase/PSIMPORT/PSARFxxx, geen Pervasive-driver, geen `ARIMP_Verwerken`/`ARIMP_DELETE`/`ARIMP_Record`, geen OUT02-resultaat, geen scheduler of `@Scheduled`, geen retry-lus, geen notificaties,
geen `state_origin = PUBLISHED`, geen mutatiestatuswijziging.

## 6. Bouwstappen

| # | Doel | Changeset | Trap | Gerichte testklassen |
|---|---|---|---|---|
| 5P-1 | Snapshotschema + entiteiten + repo's | 008-1/2/3 | gemiddeld | `PublicationSnapshotSchemaTest` (nieuw) |
| 5P-2 | Snapshot vullen bij bevriezen + `SNAPSHOT_SOURCE_MISSING`; `content_hash` byte-identiek (regressietest) | — | zwaar | `BundleFreezeTest`, `BundleConflictTest` |
| 5P-3 | `snapshot_hash`/`snapshot_spec_version` berekenen en bewaren | (008-3) | gemiddeld | `BundleFreezeTest` |
| 5P-4 | Preview slice 2 | — | gemiddeld | `PsimportPreviewMapperTest`, `PsimportPreviewCsvSerializerTest`, `PsimportPreviewHttpTest` |
| 5P-5 | Retentieregel + read-only guard | — | licht | `StagingRetentionGuardTest` (nieuw) |
| 5P-6 | `publication_run` schema + enum + repo | 009-1 | gemiddeld | `PublicationRunSchemaTest` (nieuw); `ConfigurationActorSubjectSchemaTest` 18/12 → 19/13 en `fase5-auth-design.md` §1.2/§4 bijwerken |
| 5P-7 | `PublicationRunService` (SIMULATION) + `PublicationArtifactStore` | — | zwaar | `PublicationRunSimulationTest` (nieuw) |
| 5P-8 | Endpoints + rechten + docs | — | gemiddeld | `PublicationRunHttpTest` (nieuw), `PermissionCoverageTest`, `PermissionHttpTest` |

Volledige ronde via `scripts/test/run-full-tests.ps1` na 5P-4 en na 5P-8.

> **Important technical constraint discovered**
>
> `ConfigurationActorSubjectSchemaTest` telt `%_by_subject` over het volledige schema en eist exact 18 kolommen op 12 tabellen. Elke nieuwe subjectkolom (hier `publication_run.requested_by_subject`) laat die test falen: een bewuste poort. De test en
> `fase5-auth-design.md` §1.2/§4 moeten in dezelfde stap (5P-6) naar 19/13.

> **Important business rule discovered**
>
> Een batch in een bevroren bundel mag nooit zijn staging verliezen vóórdat de snapshot bestaat. Zolang 5P-2 niet gebouwd is, is elke opruiming van `import_candidate_stage` verboden, ook handmatig.

## 7. Aanvullingen na implementatie (5P-1 t/m 5P-8, geïmplementeerd en getest)

- **Retentieguard (5P-5):** opruimbaar zijn uitsluitend batches met status `FAILED` of `BASELINE_ACCEPTED`. `SCREENED` (nog een bundelkandidaat) en `BLOCKED` (onopgelost) zijn bewust NIET opruimbaar, strenger dan
  `ImportBatchStatus.isTerminal()`. Voorwaarde (b) sluit ook bevroren bundels met een snapshot uit zolang het lidmaatschap actief is; voorwaarde (c) houdt de staging vast als een bundel waarin de batch ooit zat geen `snapshot_hash`
  draagt (ook een geannuleerde of een waaruit de batch verwijderd is, zolang die nooit bevroren werd). Er bestaat nog steeds geen delete of scheduler.
- **Constraint `ck_publication_run_marker_status` (009-1):** de marker-check gebruikt `active_marker is true` en niet `= true`, omdat een CHECK bij `NULL` slaagt en `= true` een niet-terminale run zonder marker doorliet
  (waardoor de garantie "hoogstens één actieve run per bundel" wegviel).
- **`BASE_PRICE` niet in de snapshot (5P-2):** de basisprijs staat als gewone componentrij in staging maar ook op `import_mutation`; ze wordt bewust niet gekopieerd (geen tweede bron voor een bedrag). De `snapshot_hash` (5P-3, spec versie 1)
  is SHA-256 over per snapshotrij (oplopend op `mutation_id`): `mutation_id, description_state, description` en per prijscomponent (oplopend op `component_code`, Java `String.compareTo`): `component_code, source_amount, percentage, currency, status`;
  velden gescheiden door U+001F, rijen afgesloten met U+001E, `null` als tekst `null`, bedragen met `stripTrailingZeros().toPlainString()`. Een omschrijving die zelf U+001F/U+001E bevat kan met de scheiding botsen (geen escaping, zoals bij `content_hash`).
- **Preview slice 2 (5P-4):** een prijscomponent zonder percentage geeft `UNKNOWN` (rij onvolledig); een rij zonder snapshotrij geeft `NOT_SNAPSHOTTED` op `DESCRIPTION` en `VKPn_PCT` en telt als onvolledig. De CSV-banner van de preview-endpoint blijft
  ongewijzigd (draagt `previewSpecVersion=2`); het artefact van een run heeft een uitgebreide banner (`simulationOnly=true writesToProdis=false snapshotSpecVersion snapshotHash`).
- **Run (5P-7):** aanvraag in een eigen transactie (slot op de bundel, controles, `REQUESTED` → `PREPARING`), artefact paginagewijs gestreamd buiten een transactie, afronding in een tweede transactie (`SIMULATED`, marker NULL). Een uitzondering
  tijdens het schrijven geeft `FAILED` (`ARTIFACT_WRITE_FAILED` of `PROJECTION_FAILED`, melding zonder pad), waarna de aanroep de FAILED-view teruggeeft. `payload_hash` = `artifact_sha256`. `attempt` telt per (bundel, modus).
  Artefact onder `<catalogimport.archive.root>/publication-runs/<yyyy>/<MM>/<dd>/<runId>/psimport-preview.csv`, atomair geschreven; geen nieuwe property.
- **Extra foutcodes (niet in de eerste versie van dit ontwerp):** `PUBLICATION_RUN_NOT_FOUND` (404) en `PUBLICATION_RUN_ARTIFACT_NOT_AVAILABLE` (409). `listRuns` op een onbekende bundel geeft 404 `BUNDLE_NOT_FOUND`. De modus wordt strikt geparsed (exacte enumnaam na `trim`).
- **Endpoints (5P-8):** `POST /bundles/{id}/publication-runs` (APPROVE, status 200, body `{"targetMode":"SIMULATION"}`, geen actorveld), `GET /bundles/{id}/publication-runs`, `GET /publication-runs/{runId}`, `GET /publication-runs/{runId}/artifact`
  (alle READ; het artefact als `text/csv` met bestandsnaam `psimport-simulation-run-<runId>.csv`). De view bevat nooit het subject of het artefactpad.
- **Voorcontrole (`freeze-check`) kent `SNAPSHOT_SOURCE_MISSING` nog niet:** `freezable = true` kan gemeld worden terwijl `freeze` 409 geeft. Bekende, ongewijzigde beperking van de preflight (momentopname zonder garantie).

> **Important technical constraint discovered**
>
> Een run die tussen het aanmaken (stap 1) en het afronden (stap 3) door een proces- of serverfout blijft staan, behoudt status `PREPARING` met `active_marker = true` en blokkeert daarmee elke volgende run voor die bundel. Er is bewust geen recovery
> of scheduler gebouwd (§5). Voor 5-PUB-b/c is een aparte beslissing nodig over herstel van vastgelopen runs (bv. een expliciete "run afbreken"-actie of een time-out-regel).

> **Important technical constraint discovered**
>
> De testfixtures leveren mutaties zonder valuta (`base_price_currency = null`); zulke rijen zijn in de preview en in een run terecht onvolledig (`UNKNOWN`, nooit EUR aannemen). Tests die volledige rijen willen, zetten de valuta expliciet via JDBC vóór het bevriezen.