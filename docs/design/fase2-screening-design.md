# Ontwerp Fase 2 — Kleinste verticale slice (screening van een manuele CSV-levering)

Bron: denker-zwaar-ontwerp van 2026-09-18, geaccepteerd door de hoofdsessie. Bindend naast
`docs/decisions.md`. Vervangt waar van toepassing de proefversie-beschrijvingen in
`docs/design/catalog-import-design.md`.

## 0. Overzicht

Vijf nieuwe tabellen (`import_batch`, `import_candidate_stage`, `import_row_issue`,
`catalog_source_state`, `import_mutation`) plus additieve kolommen op
`import_definition_revision`, in één nieuwe changeset `002-import-screening-core.sql`
(001 blijft ongewijzigd). `ImportLink` is de fysieke invulling van `source_scope_id`
(bibliotheek + bronorganisatie + leverancier); geen aparte scope-tabel.
`import_candidate_stage` en `catalog_source_state` zijn JDBC-only (geen `@Entity`);
`import_mutation` en `import_row_issue` worden via JdbcTemplate bulk geschreven en via een
JPA-entiteit gelezen die de binaire hashkolommen niet mapt. Nooit in één transactie JPA
lezen wat diezelfde transactie via JdbcTemplate schreef.

Hashkolommen: Liquibase-property `hash.type` = `bytea` (postgresql) / `varbinary(32)` (h2).
Loopt dit vast: escaleren, niet zelf kiezen.

## 1. `import_batch` (één screening van één levering onder één bevroren revisie)

Kolommen: `id` bigint identity; `delivery_id` fk not null; `import_link_id` fk not null;
`definition_revision_id` fk not null; `task_run_id` fk null; `attempt_no` int not null
default 1; `status` varchar(40); (stap 4: vier `processing_*`-claimkolommen, changeset 017, zie §9); `open_marker` boolean null (TRUE zolang niet-terminaal,
anders NULL); `started_at`/`finished_at` timestamptz; `staged_row_count` bigint default 0;
`mutation_progress_row_number` bigint default 0; nullable tellers (`null` = onbekend, nooit
stil 0): `raw_record_count`, `valid_record_count`, `rejected_record_count`,
`duplicate_identity_count`, `new_count`, `changed_count`, `unchanged_count`,
`content_mutation_count`; `blocked_code` varchar(60); `blocked_reason` varchar(500);
`created_at`, `created_by`.
Constraints: `uk_import_batch_attempt unique (delivery_id, definition_revision_id, attempt_no)`;
`uk_import_batch_open unique (delivery_id, open_marker)`; index `(import_link_id, created_at)`.
`ImportBatchStatus`: RECEIVED → SCREENING → MUTATING → SCREENED | BLOCKED | FAILED; daarna
optioneel BASELINE_ACCEPTED. Terminaal (open_marker NULL): SCREENED, BLOCKED, FAILED,
BASELINE_ACCEPTED.

## 2. `import_candidate_stage` (JDBC-only, `JdbcTemplate.batchUpdate`, microbatch default 2000)

`pk (batch_id, row_number)`; `row_number` = fysiek regelnummer (1-gebaseerd, incl. header/prefix);
`delivery_file_id` fk; `identity_supplier`/`identity_supplier_group`/`identity_supplier_reference`
varchar(200) not null; `identity_discount_code` varchar(200) NULL (null ⇔ THREE_PART,
"" ⇔ gemapt maar leeg); `identity_discount_state` varchar(20) (`NOT_USED`/`EMPTY`/`VALUE`);
`identity_hash` `${hash.type}`; `base_price` numeric(24,6) not null (nooit 0 bij parsefout:
rij wordt dan niet gestaged); `base_price_currency` varchar(3) null. In Fase 2 niet gebruikt; in Fase 3 gevuld volgens de valuta-standaard (`docs/design/valuta-standaard-design.md` §1-4);
`description` varchar(1000) null; `article_fingerprint`, `price_fingerprint`,
`combined_fingerprint` `${hash.type}`; `mutation_key_prefix` varchar(160) =
`<delivery_id>:<revision_id>:<identity_hash_hex>`; `classification` varchar(30) null
(NEW/CHANGED/UNCHANGED/DUPLICATE_IN_DELIVERY); `created_at`.
Index `(batch_id, identity_hash)` (bewust NIET unique), `(batch_id, classification)`.
Geen ruwe brontekst per record.
Config: `catalogimport.screening.stage-batch-size` (2000).

Duplicaatdetectie set-based ná staging (`group by identity_hash having count(*) > 1`);
elke betrokken rij krijgt issue `DUPLICATE_IDENTITY_IN_DELIVERY`, batch → BLOCKED.
Nooit "laatste wint". `ImportValueRules.rejectDuplicateIdentity` (in-memory Set) vervalt.

## 3. `catalog_source_state` (JDBC-only)

`id` identity; `import_link_id` fk; `identity_hash`; `identity_supplier/_group/_reference`;
`identity_discount_code` null; `identity_discount_state`; `identity_profile_kind`;
drie fingerprints; `base_price` numeric(24,6); `base_price_currency`; `state_origin`
(`BASELINE_ACCEPTED` | `PUBLISHED`); `last_change_delivery_id`, `last_change_batch_id` fk;
`active` boolean default true (Fase 2 zet nooit false); `accepted_by`, `accepted_at`;
`created_at`, `updated_at`. `uk_catalog_source_state_identity unique (import_link_id, identity_hash)`.
Een ONGEWIJZIGDE regel raakt de bronstaat nooit aan. Eerste levering = LEFT JOIN op lege
tabel → alles NEW (geen speciaal codepad). Hashcollisie (zelfde hash, andere componenten)
→ batch BLOCKED `IDENTITY_HASH_COLLISION`.
De screening zelf schrijft NOOIT in `catalog_source_state`; enkel de geauditeerde actie
`accept-baseline` doet dat (beslist, zie decisions.md en §17).

## 4. `import_mutation` (= de centrale PSIMPORT001-lijst; vermeld dit in de javadoc)

Kolommen: `id`; `batch_id`, `delivery_id`, `import_link_id`, `definition_revision_id` fk not
null; `task_run_id` null; `action_type` (`CREATE`|`UPDATE`|`IMPORT_MARKER`); `target_domain`
(`OFFER`|`IMPORT`); `status`; `status_reason` varchar(200); identiteitskolommen (null voor
marker); `identity_hash` (NIET gemapt in JPA); `before_combined_fingerprint`,
`after_combined_fingerprint` (NIET gemapt); `domain_mask` varchar(100) (bv. `PRICE`);
`before_base_price`/`after_base_price` numeric(24,6) null; `base_price_currency`;
`source_state_id` fk null; `delivery_file_id` fk null; `source_row_number` bigint null;
`result_summary` varchar(1000) (enkel marker); `idempotency_key` varchar(200) not null;
`created_at`.
Constraints: `uk_import_mutation_idempotency unique (idempotency_key)`;
`ck_import_mutation_marker`: (marker ⇒ target_domain='IMPORT', identiteit null,
status='RECORDED') OR (CREATE/UPDATE ⇒ target_domain<>'IMPORT', supplier/group/reference
not null); indexen `(batch_id, status)`, `(import_link_id, identity_hash)`.
Geen `publication_bundle_id` (komt additief in Fase 5).
`MutationStatus` volledig gedeclareerd: PLANNED, BLOCKED, AWAITING_APPROVAL,
READY_FOR_PUBLICATION, IN_PROGRESS, PUBLISHED, TECHNICALLY_FAILED, REJECTED, EXPIRED,
SKIPPED, RECORDED. Fase 2 zet alleen PLANNED, RECORDED, SKIPPED.
UPDATE = enkel hash-/domeinniveau + voor/na-basisprijs (geen veldniveau-diff; Fase 3).

> **Important technical constraint discovered (2026-09-24 — C4 uitgevoerd)**
>
> `identity_hash` is in de JPA-entiteit `ImportMutation` bewust niet gemapt (databasespecifiek binair type; enkel voor set-based SQL). De waarde verlaat de database alleen via een gerichte query (`MutationDao.findIdentityHashes`) die byte-vergelijking doet (geen `encode(...,'hex')`). Deze grens (binaire hash blijft in de database, alleen hex-representatie naar de applicatie voor filters) blijft standhouden ook wanneer later endpoints zoals `GET /batches/{id}/mutations?identityHash=` filter-ondersteuning krijgen.

`idempotency_key`:
- inhoudelijk: `<delivery_id>:<definition_revision_id>:<identity_hash_hex>:OFFER`
- marker: `<delivery_id>:<definition_revision_id>:MARKER`
Herlevering (nieuwe Delivery) blokkeert dus NIET; dezelfde Delivery onder dezelfde revisie
twee keer screenen wél (service checkt vooraf → 409 `DELIVERY_ALREADY_SCREENED_WITH_THIS_REVISION`).
Hervatten: INSERT ... `not exists (select 1 from import_mutation where idempotency_key = ...)`.
Hex niet via DB-functie maar via `mutation_key_prefix` uit de stage.
Marker: exact één per AFGERONDE screening (SCREENED of BLOCKED), in dezelfde transactie als de
eindtransitie; FAILED schrijft geen marker. `result_summary` bv.
`outcome=SCREENED;completenessProven=false;completenessReason=PHASE2_NO_COMPLETENESS_CONTRACT;fileSha256=<hex>`.

## 5. `import_row_issue`

`id`; `batch_id`, `delivery_file_id` fk; `row_number` bigint; `issue_code` varchar(60);
`field_name` varchar(200) null; `severity` varchar(20) default 'ERROR'; `source_value`
varchar(200) (afgekapt); `message` varchar(500); `created_at`. Index `(batch_id, issue_code)`.
Cap `catalogimport.screening.max-recorded-row-issues` (1000): daarboven batch BLOCKED
`TOO_MANY_ROW_ISSUES` met aantallen per code in `blocked_reason`.

## 6. Archivering + manifest

`DeliveryArchiveStore` (Service, geen interface): property `catalogimport.archive.root`
(verplicht; tests gebruiken `@TempDir`). `store(InputStream, originalFileName)` → pad
`<root>/<yyyy>/<MM>/<dd>/<uuid>/<sanitizedFileName>` (basename, geen `..`/scheidingstekens,
max 200), `archive_reference` = pad relatief aan root; streaming via `DigestInputStream`
(SHA-256 + byteteller tijdens schrijven, 8 KiB buffer); read-only zetten waar mogelijk.
`open(ref)`, `deleteQuietly(ref)` (enkel opruiming bij falende registratie). Bytes NOOIT in DB.
`delivery.manifest_reference` = null. Delivery-velden: expected_file_count=1, actual=1;
expected_record_count/expected_byte_size uit optioneel requestveld (null indien afwezig, nooit
0); actual_* gezet; `completeness_proven` ALTIJD false. Mismatch → BLOCKED
`RECORD_COUNT_MISMATCH` / `BYTE_SIZE_MISMATCH` (byte-check vóór parsen).

## 7. Bronconfiguratie op `import_definition_revision` (additief, changeset 002-1)

`structure_format` varchar(20) default 'CSV' (check in ('CSV')); `structure_charset`
varchar(40) default 'UTF-8'; `structure_delimiter` varchar(1) not null (geen default na
migratie); `structure_quote_char` varchar(1) default '"'; `structure_has_header` boolean
default true; `structure_header_line_number` int default 1; `structure_field_reference_kind`
varchar(20) default 'HEADER_NAME' (`HEADER_NAME`|`COLUMN_INDEX`);
`structure_expected_column_count` int null; `access_delivery_set_kind` varchar(30) default
'UNDECLARED' (`FULL_SNAPSHOT`|`DELTA`|`UNDECLARED`); `record_base_price_field` varchar(200)
null; `record_description_field` varchar(200) null; `record_canonicalisation_version` int
default 1; check: `structure_has_header = false ⇒ structure_field_reference_kind='COLUMN_INDEX'`.
Defaults enkel als migratiehulp; daarna `drop default` waar de app de waarde expliciet moet
zetten. De bestaande `identity_*_field`-kolommen blijven en dragen headernaam of
1-gebaseerde kolomindex. Fase 3 voegt een aparte `import_field_mapping`-tabel toe; sjablonen
+ bookmarks (bindend, decisions.md) voegen tabellen toe zonder deze tabel te wijzigen.

## 8. Parse en normalisatie

`CsvRecordStreamer` (pure klasse): streaming `BufferedReader`, charset uit config, geen
autodetectie; BOM verwijderen én melden (`SOURCE_BOM_REMOVED`, WARNING); header op
`structure_header_line_number`, regels ervoor = prefix (geteld); één fysieke lijn = één
record, gequote newline NIET ondersteund (`CSV_UNCLOSED_QUOTE`); header moet alle
gedeclareerde velden bevatten (trim + case-insensitief) anders BLOCKED
`HEADER_FIELD_MISSING:<veld>`; verkeerd kolomaantal in header → BLOCKED
`HEADER_COLUMN_COUNT_MISMATCH`; datalijn met ander kolomaantal → rijfout
`ROW_COLUMN_COUNT_MISMATCH` (nooit aanvullen/afkappen); lege staartregel overslaan en tellen;
regellengte > `catalogimport.screening.max-line-length` (100000) → `ROW_TOO_LONG`
(controle na readLine; beperkte bescherming, zie constraint hieronder).
`CandidateNormaliser` (pure klasse): trim (geen case-folding/NFC, canonicalisatieversie 1);
lege leverancier/groep/referentie → `IDENTITY_COMPONENT_EMPTY`; THREE_PART → kortingscode
niet gelezen (null/NOT_USED); FOUR_PART: ontbrekende kolom → BLOCKED
`CONFIG_DISCOUNT_FIELD_MISSING`, lege waarde → ""/EMPTY.
Canoniek: scheidingsteken ``, niet-gemapt-marker ` `; THREE_PART:
`<v> supplier group reference`; FOUR_PART: `<v> supplier group discount reference`.
`identity_hash` = SHA-256(canoniek, UTF-8). `article_fingerprint` = SHA-256(`<v>` ⎵
description ?? ` `); `price_fingerprint` = SHA-256(`<v>` ⎵ price.setScale(6).toPlainString()
⎵ currency ?? ` `); `combined_fingerprint` = SHA-256(identity_hash ‖ article ‖ price).
Prijs via `ImportValueRules.decimal()`: nooit float, nooit stil 0, schaal > 6 geweigerd,
dan `setScale(6, HALF_UP)`.
`ImportValueRules`-wijzigingen: eigen `ImportValueException(code, field, rawValue, message)`
i.p.v. IllegalArgumentException (codes PRICE_MISSING/PRICE_UNREADABLE/PRICE_SCALE_EXCEEDED
worden `issue_code`); `sha256(byte[])` toevoegen (sha256Hex delegeert); `canonical(int version,
String... parts)` toevoegen; `rejectDuplicateIdentity` verwijderen (melden in rapport);
`parseCsvLine(String line, char delimiter, char quote)`; geen `decimalOrNull`.

> Important technical constraint discovered
> `readLine()` heeft geen bovengrens; een bestand zonder regeleinde kan het heap uitputten
> vóór validatie. Echte begrensde lezer hoort bij het connector-/uploadcontract (§16.6).

## 9. Transactie- en foutgrenzen

A. Archiveren: geen DB-transactie (wees-object bij crash is onschuldig). B. Registratie
(`TaskRun`+`Delivery`+`DeliveryFile`+`ImportBatch(RECEIVED)`): één korte JPA-transactie.
C. Staging: microbatch-commit; crash ⇒ batch FAILED, staging+issues van die batch weg,
nieuwe poging met `attempt_no+1`. D. Duplicaat-/collisiedetectie: read-only, herhaalbaar.
E. Delta + mutatiegeneratie: chunk-commit (`mutation-chunk-size` 5000), hervatbaar via
idempotency_key (sinds stap 4 elke schrijvende transactie gefenced op de verwerkingsclaim, zie hieronder). F. Afronding (tellers, marker, SCREENED/BLOCKED, TaskRun COMPLETED): één
transactie. Orchestrator NIET `@Transactional`; gebruikt `TransactionTemplate`.
Fouttypes: technische/bronfout ⇒ batch+TaskRun FAILED, geen marker, geen mutaties.
Contract-/structuurfout (leeg bestand `SOURCE_FILE_EMPTY`, header-only
`SOURCE_NO_DATA_RECORDS`, ontbrekend headerveld, kolomtelling, aantalsmismatch,
duplicaat-identiteit, hashcollisie, te veel rijfouten) ⇒ BLOCKED, 0 inhoudelijke mutaties,
1 marker `outcome=BLOCKED`. Inhoudelijk ongeldige regel ⇒ enkel die regel verworpen (issue),
batch → SCREENED met `rejected_record_count > 0`.
`ScreeningRecoveryService` op `ApplicationReadyEvent`: SCREENING → FAILED
`SCREENING_INTERRUPTED` (staging+issues weg); MUTATING → hervatbaar via
`POST /batches/{id}/continue`. Sinds stap 4 is dit **claim-bewust** (zie "Verwerkingsclaim" hieronder).

### Verwerkingsclaim per batch (stap 4/5, docs/decisions.md 2026-10-01/02)

Vervangt de oude beperking "recovery veronderstelt precies één applicatie-instantie" (geen lease/heartbeat;
`task_run.locked_by/locked_at` blijven ongebruikt). Er is geen advisory lock: de claim staat op de batchrij.

- **Kolommen (changeset `017-batch-processing-claim.sql`, additief):** op `import_batch` vier nullable kolommen
  `processing_claim_token` uuid, `processing_claimed_at`, `processing_heartbeat_at` (timestamptz) en
  `processing_claimed_by` varchar(100), formaat `<instanceId>/<bootId>` (gesplitst op de laatste `/`). Twee checks:
  `ck_import_batch_claim_open` (token gezet ⇒ `open_marker is true`: een terminale overgang moet de claim dus in
  dezelfde transactie vrijgeven) en `ck_import_batch_claim_complete` (de vier velden samen gezet of samen leeg).
- **Levend of dood** (`ImportBatch.isProcessingClaimAlive`, `BatchProcessingClaims.isAlive`): levend = token gezet,
  heartbeat niet ouder dan de lease (`catalogimport.screening.claim-lease`, default `PT60M`, ISO-8601; ongeldig of
  niet-positief ⇒ de applicatie start niet), en niet van een **vorige boot van dezelfde instantie** (zelfde
  `instanceId`, andere `bootId` ⇒ dood, zonder op de lease te wachten). `bootId` is een UUID die bij elke JVM-start
  nieuw is; `instance-id` (`catalogimport.instance-id`) is default de hostnaam.
- **Claim nemen:** `start()` (RECEIVED → SCREENING) en `resume()` (`continue`) lezen de batch onder schrijfslot
  (`findByIdForUpdate`, wacht op een bezet slot) en nemen de claim in dezelfde transactie. Levende claim ⇒ 409
  `BATCH_BEING_PROCESSED`; een dode claim wordt overgenomen met een nieuwe token (de vorige houder valt bij zijn
  volgende fencing-update buiten). `start()` neemt het schrijfslot ook zelf: een gelijktijdige tweede `screen()`
  krijgt `BATCH_NOT_SCREENABLE` (status is dan al SCREENING).
- **Fencing per schrijvende transactie:** elke schrijvende transactie na de start (`inClaim`/`inClaimWithoutResult`)
  begint met `ImportBatchRepository.touchProcessingClaim` (`update ... set processing_heartbeat_at where id and token`).
  0 rijen ⇒ `ClaimLostException` ⇒ rollback en stoppen: er wordt niets geschreven en niets opgeruimd (ook geen
  `fail()`-opruiming, de batch is dan van de nieuwe houder); de aanroeper krijgt 409 `BATCH_BEING_PROCESSED`.
- **Vrijgeven:** in dezelfde transactie als elke eindstatus (SCREENED, BLOCKED, FAILED) en bij een technische
  onderbreking zonder eindstatus (batch blijft MUTATING, of SCREENING bij een mislukte blokkade): de claim gaat dan
  meteen vrij (gefenced, `releaseQuietly`), zodat `continue` direct kan; lukt dat vrijgeven niet, dan loopt de lease af.
- **Claim-bewust opstartherstel:** per batch een eigen transactie met compare-and-set onder schrijfslot (status
  nog zoals waargenomen, token nog exact de waargenomen token, claim dood). SCREENING + dode claim ⇒ FAILED
  `SCREENING_INTERRUPTED` met vrijgave in dezelfde wijziging; MUTATING + dode claim ⇒ token gewist, batch blijft
  hervatbaar. Een levende claim (ook een nieuwe claim die intussen genomen werd) blijft ongemoeid en staat niet in
  `RecoveryReport.resumableBatchIds`.
- **API:** `BatchDetail` heeft additief het veld `boolean processingActive` (= `BatchProcessingClaims.isAlive`);
  `false` betekent veilig te herstarten of nooit een claim gehad. Geen gebruikerskolom op de claim.

> Important technical constraint discovered (stap 4, docs/decisions.md 2026-10-02)
>
> (a) De fencing-update moet het **eerste statement** van de transactie zijn. Het is een JPQL-bulkupdate die de
> persistentiecontext niet bijwerkt: een batch die vóór de update al geladen was, draagt nog de oude heartbeat en
> schrijft die bij de flush terug.
>
> (b) De heartbeat wordt enkel op transactiegrenzen ververst (begin van elke gefencede transactie), er is geen
> achtergrondthread. Een stap zonder commit die langer duurt dan de lease verliest haar claim: een andere worker mag
> de batch dan overnemen en de eerstvolgende fencing-update van de oorspronkelijke worker faalt.
>
> (c) `instance-id` moet uniek zijn per draaiende instantie: twee instanties met dezelfde id hebben verschillende
> `bootId`'s, dus beschouwt elk de levende claim van de ander als claim van een "vorige boot van dezelfde instantie"
> (dood) en neemt hem over. Verder hoogstens 63 tekens (claimed_by is varchar(100) = id + `/` + 36 tekens bootId),
> geen `/`, geen controletekens, niet leeg; anders start de applicatie niet.
> Omgevingsvariabele: `CATALOGIMPORT_INSTANCEID` (Spring relaxed binding). Containers met dezelfde hostnaam moeten
> hem expliciet zetten.

### Globale slotvolgorde (stap 4/5, docs/decisions.md 2026-10-01)

**Bundel → run → koppeling(en, oplopend id) → batch(es, oplopend id).** Een taakslot wordt nooit genomen terwijl een
van deze vastgehouden wordt. Alle plaatsen die meerdere sloten nemen (accept-baseline §17/§18, `addBatches` van de
bundel, screening/hervatten) volgen deze volgorde; andere passages verwijzen hiernaar.

## 10. Service/Web

Service: `DeliveryArchiveStore`, `DeliveryIntakeService`, `DeliveryScreeningService`
(orchestratie, geen SQL/parselogica), `CsvRecordStreamer`, `CandidateNormaliser`,
`SourceStructureConfig`(record)+`SourceStructureConfigFactory`, `SourceStateBaselineService`,
`ScreeningRecoveryService`. Dao: Spring Data-repos + JdbcTemplate-`@Repository`'s
`CandidateStageDao`, `RowIssueDao`, `MutationDao`, `SourceStateDao`. Web:
`CatalogImportDeliveryController`. Service geeft records terug (geen JPA-entiteiten).
REST (inline records): `POST /api/catalog-import/tasks/{taskId}/deliveries` (multipart `file`,
`deliveryReference` verplicht, `uploadedBy` verplicht, `expectedRecordCount?`,
`expectedByteSize?`) → 201; `GET /api/catalog-import/deliveries/{id}`;
`GET /api/catalog-import/batches/{id}/mutations?actionType=&page=&size=`;
`GET /api/catalog-import/batches/{id}/issues?page=&size=`;
`POST /api/catalog-import/batches/{id}/accept-baseline` (`{acceptedBy, reason}`; enkel vanuit
SCREENED; atomair, 409 `BASELINE_ACCEPTANCE_IN_PROGRESS`/`BATCH_BEING_PROCESSED` bij een bezet slot, zie §17) ; `POST /api/catalog-import/batches/{id}/continue`. Screening synchroon in de POST
(Fase 5: asynchroon). Manuele levering: taak met `TaskTriggerType.MANUAL`;
`Delivery.idempotencyKey = "manual:" + deliveryReference`; zelfde referentie + identieke hash
⇒ 200 met bestaande deliveryId; zelfde referentie + andere inhoud ⇒ 409
`DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT` (archiefobject opruimen); één `TaskRun` per
upload, gelijktijdige upload botst op `uk_task_run_concurrency` ⇒ 409 `TASK_RUN_IN_PROGRESS`;
geen actieve revisie ⇒ 409 `NO_ACTIVE_REVISION`; geen prijsveld ⇒ 409
`CONFIG_PRICE_FIELD_MISSING`. Geen autorisatie in Fase 2 (uploadedBy/acceptedBy zijn
requestvelden; Keycloak/Prodis-permissies in Fase 5). `ApiExceptionHandler`: `NotFoundException`
→404, `ConflictException`→409 (Service-module, stabiele `code`); responsbody krijgt extra veld
`code` naast `error` (additief; melden in rapport).

> **Important technical constraint discovered (2026-09-24 — Frontend batchdetail)**
>
> `MaxUploadSizeExceededException` (door Spring gegenereerd wanneer de upload groter is dan `max-file-size` of `max-request-size`) is niet in `ApiExceptionHandler` afgevangen. Een te grote upload geeft dus een 413 Payload Too Large zonder `code`-veld, terwijl het antwoord normaal altijd `{error, code}` bevat. Dit maakt de client-side foutafhandeling incoherent.

> **Important technical constraint discovered (2026-09-24 — Frontend batchdetail, V2)**
>
> `POST /api/catalog-import/batches/{id}/continue` is de enige schrijfactie (mutatiegeneratie hervatten na onderbrekking) zonder request-body. Daardoor staat niet op naam vast wie de hervatting uitvoert — geen `continuedBy` audit-veld. In Fase 2/3/4 is dit geen probleem (geen authenticatie). Zodra Fase 5 geverifieerde identiteiten invoert, zal dit een gat in de audittrail vormen.

## 11. Tests (allemaal in `Web/src/test`, `mvn -pl Web -am test`, unieke codes per test wegens gedeelde H2)

`CsvRecordStreamerTest`, `CandidateNormaliserTest` (unit); `ScreeningSchemaTest`
(constraints); `DeliveryScreeningFlowTest`: (a) eerste levering N regels → N CREATE/OFFER/PLANNED
+ exact 1 IMPORT_MARKER/RECORDED, archief met juiste SHA-256/byteSize, completeness_proven=false;
(b) na accept-baseline zelfde inhoud als nieuwe Delivery → 0 inhoudelijke mutaties, 1 marker,
unchanged_count=N, source state ongewijzigd; (c) dubbele identiteit → BLOCKED, issue per rij,
0 mutaties, 1 marker BLOCKED; (d) onleesbare/lege prijs → die rijen verworpen, nergens prijs 0;
retry zelfde referentie identiek → 200 dezelfde deliveryId; andere inhoud → 409; tweede
screening zelfde Delivery+revisie → 409 (DAO-niveau: DataIntegrityViolation); mutatiegeneratie
tweemaal draaien → geen duplicaten; lege file; header-only; expectedRecordCount mismatch;
prijswijziging na baseline → 1 UPDATE (before/after, domain_mask=PRICE); twee ImportLinks met
dezelfde leverancierssleutel → geen kruisupdate. Performance (100k/1M) en
PostgreSQL-specifieke tests: buiten scope, door de mens.

## 12. Bouwstappen (strikt sequentieel, één commit per stap)

2a bouwer-gemiddeld: changeset 002 (zes changesets met rollback), master-changelog +
property, entiteiten `ImportBatch`/`ImportMutation`/`ImportRowIssue`, enums, Spring
Data-repos, `ScreeningSchemaTest`. 2b bouwer-gemiddeld: `DeliveryArchiveStore`,
`DeliveryIntakeService`, upload-endpoint, exceptions + handler, upload/retry/conflicttests.
2c bouwer-zwaar: `ImportValueRules`-wijzigingen, `CsvRecordStreamer`, `CandidateNormaliser`,
`SourceStructureConfig(+Factory)`, `CandidateStageDao`, `RowIssueDao`, microbatch in
`DeliveryScreeningService`, unittests. 2d bouwer-zwaar: `MutationDao` (duplicaat, collisie,
delta, classificatie), chunk-commits, idempotentie, marker, terminale transitie, flowtests
(a)/(c)/(d) + herstart. 2e bouwer-gemiddeld: `SourceStateDao` + `SourceStateBaselineService` +
accept-baseline, `ScreeningRecoveryService` + continue, status-/mutatie-/issue-endpoints,
flowtest (b) + rest.

## 13. Aannames

A1 source_scope_id = import_link_id. A2 één fysieke lijn = één record. A3 header-only blokkeert.
A4 ongeldige rij blokkeert enkel die rij. A5 marker-status RECORDED. A6 completeness_proven
altijd false. A7 FAILED schrijft geen marker. A8 canonicalisatie v1 = trim. A9 synchroon.
A10 geen autorisatie. A11 issue-cap 1000. A12 staging-retentie niet in Fase 2. A13 tests in
Web/src/test.

## 14. Documentatievaststelling

`docs/requirements/catalog-import*.md`, `docs/design/catalog-import-design.md`,
`docs/analysis/catalog-import-impact.md` en `docs/stories/catalog-import-proefpublicatie.md`
beschrijven de vervangen proefversie en gelden als achterhaald; `-acceptance.md` is niet
bruikbaar als Definition of Done in Fase 7 (enkel het testcommando blijft geldig). Herschrijven
na Fase 3.

## 15. Aanvullingen uit stap 2c (geïmplementeerd, hoofdsessie akkoord)

- Een bronwaarde met het canonieke scheidingsteken `U+001F` of de niet-gemapt-marker `U+0000`
  wordt verworpen (`CANONICAL_CONTROL_CHARACTER`), anders kunnen twee aanbiedingen dezelfde
  `identity_hash` krijgen.
- Waarden die niet in de doelkolommen passen (identiteit > 200, omschrijving > 1000, prijs buiten
  numeric(24,6)) worden per rij verworpen (`VALUE_TOO_LONG`, `PRICE_OUT_OF_RANGE`), nooit afgekapt.
- Wijkt het aantal gelezen bytes af van `delivery_file.byte_size`, dan is het archiefobject
  beschadigd: technische fout, batch FAILED.
- Elke volledig lege regel wordt overgeslagen en geteld (niet enkel de staartregel); een regel met
  alleen spaties blijft een (foute) datalijn.
- TaskRun-status bij een BLOCKED batch is `COMPLETED` (de uitvoering verliep normaal; het oordeel
  staat op `import_batch`); technische fout ⇒ `FAILED`. Een BLOCKED batch behoudt staging en issues;
  enkel FAILED ruimt ze op.
- `base_price_currency` blijft in Fase 2 altijd null. In Fase 3 wordt het gevuld volgens de valuta-standaard: herkomst SOURCE (bronveld), LINK_DEFAULT (vaste waarde per koppeling), of SYSTEM_DEFAULT (EUR); zie `docs/design/valuta-standaard-design.md`.
- Uploadlimiet configureerbaar via `CATALOG_MAX_UPLOAD_SIZE` (default 1GB); `-parameters` staat aan
  in de root-pom.

## 16. Aanvullingen uit stap 2d (geïmplementeerd, hoofdsessie akkoord)

- Volgorde: hashcollisie vóór duplicaatdetectie (een kapotte identiteit is geen dubbele levering).
  Beide blokkeren de levering: 0 inhoudelijke mutaties, 1 marker `outcome=BLOCKED`.
- Elke bij een duplicaat betrokken rij (ook de eerste) krijgt een issue; `duplicate_identity_count` =
  aantal betrokken rijen. Reconciliatie: `valid_record_count = new + changed + unchanged + duplicate_identity_count`.
- Elke BLOCKED-batch (ook de 2c-gevallen) schrijft precies één marker in dezelfde transactie als de
  BLOCKED-transitie; FAILED schrijft geen marker en geen mutaties.
- Technische fout tijdens mutatiegeneratie ⇒ batch blijft `MUTATING` (hervatbaar via
  `continueMutating`), niet FAILED; enkel de stagingfase levert FAILED. Een steken gebleven MUTATING-batch
  houdt `open_marker` en de TaskRun-concurrency-token vast tot 2e (endpoint + opstartrecovery) er is.
  (Stap 4/5, docs/decisions.md 2026-10-01/02: de verwerkingsclaim op zo'n batch komt bij de technische
  onderbreking meteen vrij, zodat `continue` direct mogelijk is; zie §9.)
- Upload-POST screent synchroon; bij FAILED antwoordt hij 201 met batchstatus FAILED en
  `blockedCode=SCREENING_FAILED` (de levering bestaat en is gearchiveerd; 500 zou heruploaden uitnodigen).
- Delta als `update ... case` met `exists`/`not exists` (portabel H2/PostgreSQL); issue-cap cumulatief.
- Config: `catalogimport.screening.mutation-chunk-size` (default 5000).

## 17. Aanvullingen uit stap 2e (geïmplementeerd, hoofdsessie akkoord)

- `accept-baseline` enkel vanuit SCREENED (anders 409 `BATCH_NOT_ACCEPTABLE`; onbekend 404
  `BATCH_NOT_FOUND`); `acceptedBy`/`reason` verplicht, `system` (elke schrijfwijze) geweigerd; te
  lange waarden geweigerd, nooit afgekapt. NEW ⇒ insert-select, CHANGED ⇒ gebatchte update,
  UNCHANGED ⇒ niet aangeraakt (ook `updated_at` niet); `identity_profile_kind` uit de revisie.
  Mutaties ⇒ SKIPPED (`BASELINE_ACCEPTED_WITHOUT_PUBLICATION`), batch ⇒ BASELINE_ACCEPTED.
- Changeset `003-import-batch-baseline-audit.sql`: additieve kolommen `baseline_accepted_by`,
  `baseline_accepted_at`, `baseline_accept_reason` op `import_batch` (audit persistent).
- Extra guard `SOURCE_STATE_CHANGED_SINCE_SCREENING` (409): twee SCREENED-batches van dezelfde
  koppeling kunnen naast elkaar bestaan; het aanvaarden van de oudere mag de nieuwere bronstaat
  niet stilzwijgend overschrijven. Een bronstaatrij die al gelijk is aan de kandidaat van de regel telt niet als
  stale (idempotente insert/update, A16). (Stap 5/S5-d, docs/decisions.md 2026-10-01/02: de check geldt nu ook voor
  UNCHANGED-regels, zie het blok hieronder; de oude formulering "eigen onderbroken poging telt niet als stale"
  is vervallen: een accept-baseline is niet meer hervatbaar, zie het blok "atomair" hieronder.)

> **Important business rule discovered**
>
> Een UNCHANGED-regel is een bewering over de bronstaat op het moment van de controle ("al gelijk aan de levering"). Wijzigt een andere aanvaarding die bronstaat daarna, dan is de bewering vals: de aanvaarding wordt geweigerd (409 `SOURCE_STATE_CHANGED_SINCE_SCREENING`) in plaats van de regel stil over te slaan. Bewijs: `stage.combined_fingerprint`; geen extra opslag. De uitzondering "bronstaat = kandidaat" blijft (A16, idempotente insert/update).

- Recovery op opstart (`catalogimport.screening.recovery-on-startup`, default true): SCREENING ⇒
  FAILED `SCREENING_INTERRUPTED` (staging/issues weg, TaskRun FAILED); MUTATING blijft hervatbaar via
  `POST /batches/{id}/continue` (enkel vanuit MUTATING, anders 409 `BATCH_NOT_RESUMABLE`).
  (Stap 4/5, docs/decisions.md 2026-10-01/02: de beperking "veronderstelt één applicatie-instantie" is vervallen;
  het herstel is claim-bewust en raakt enkel batches met een dode verwerkingsclaim aan, zie §9.)
- **Accept-baseline is atomair (stap 5, docs/decisions.md 2026-10-01/02).** De volledige aanvaarding is één
  `TransactionTemplate`-transactie (alles of niets), niet meer hervatbaar per chunk. Volgorde: (1) de koppeling
  vergrendelen met NOWAIT, bezet ⇒ 409 `BASELINE_ACCEPTANCE_IN_PROGRESS` (serialisatiepunt tussen twee aanvaardingen
  op dezelfde koppeling, ook van verschillende batches); (2) de batch vergrendelen met NOWAIT, bezet (bv. een
  gelijktijdige bundelopname of verwerking) ⇒ 409 `BATCH_BEING_PROCESSED`; (3) onder beide sloten `requireScreened`,
  geen actief bundellidmaatschap en de stale-check; (4) chunklus (`mutation-chunk-size`) enkel om het geheugen te
  begrenzen, niet de transactie; (5) open inhoudelijke mutaties ⇒ SKIPPED en batch ⇒ BASELINE_ACCEPTED met audit.
  Slotvolgorde: zie "Globale slotvolgorde" in §9. Een fout eender waar draait alles terug: geen bronstaatrij,
  referentiestaatrij, prijscomponent of prijsobservatie blijft achter en de batch blijft SCREENED; wie opnieuw
  aanvaardt begint van nul (de schrijfqueries blijven idempotent, maar dat is geen hervatmechanisme meer). Tijdens een
  lopende aanvaarding op een koppeling krijgt ook een al aanvaarde batch van die koppeling `BASELINE_ACCEPTANCE_IN_PROGRESS`.
  `PublicationBundleService.addOneBatch` neemt voortaan het batchslot (NOWAIT, batch-ids oplopend; bezet ⇒ 409
  `BATCH_BEING_PROCESSED`; R-BAS-02).

> Important technical constraint discovered (stap 5, docs/decisions.md 2026-10-02)
>
> (a) Een lock-fout (SQLState 55P03 of 40P01) breekt in PostgreSQL de lopende transactie af (daarna `25P02`). De
> vertaling naar 409 gebeurt daarom **buiten** `TransactionTemplate.execute` (`LockFailures.translate`), nooit in de
> callback. (b) De ouder moet vóór het kind vergrendeld worden en de batch mag pas onder haar eigen slot geladen
> worden: de koppeling wordt via `ImportBatchRepository.findImportLinkIdById` (enkel de id) gevonden, zodat er geen
> oudere, niet-vergrendelde batch in de persistentiecontext blijft. (c) Hibernate 6 vertaalt `PESSIMISTIC_WRITE` naar
> `FOR NO KEY UPDATE` (volgens decisions.md; niet in code of test vastgelegd, de repositorycommentaar spreekt van
> `FOR UPDATE NOWAIT`): FK-checks en uploads voor de koppeling worden niet geblokkeerd, updates van de koppeling
> wachten wel. (d) Eén transactie over de hele levering is lang bij ~1M regels; de prestatietest op PostgreSQL is nog
> open bij de mens (de duur staat wel in de logregel).
- Leesendpoints: `/batches/{id}`, `/batches/{id}/mutations`, `/batches/{id}/issues`; paginering
  0-gebaseerd, default 50, max 200; mutatielijst zonder hashkolommen.
- ~~Bekend restrisico: twee gelijktijdige accepts van dezelfde batch kunnen voor de verliezer een
  500 (unieke bronstaatconstraint) geven.~~ Achterhaald (stap 5): het koppelingsslot (NOWAIT) serialiseert
  gelijktijdige aanvaardingen; de verliezer krijgt 409 `BASELINE_ACCEPTANCE_IN_PROGRESS` en niets wordt geschreven.

## 18. Aanvullingen na Fase 5-AUTH (geïmplementeerd, mens akkoord op terugschrijven 2026-09-26)

Bron: `docs/design/fase5-auth-design.md` §11 (C3, C9) en `docs/design/frontend-scherm3-bundel-design.md` §18.

- Sinds Fase 5-AUTH vereist elk `/api/**`-verzoek een login (BFF, sessiecookie). Voor de upload
  (`POST /tasks/{taskId}/deliveries`) is `uploadedBy` optioneel geworden: de uploader komt uit de login (token-username in
  `import_batch.created_by`, OIDC-subject in `import_batch.created_by_subject`); een meegegeven afwijkende naam is
  400 `ACTOR_FIELD_MISMATCH`, `system` is 403 `SYSTEM_ACTOR_FORBIDDEN`, beide vóór 404/409 en vóór er iets gearchiveerd of
  geregistreerd wordt. `accept-baseline`: `acceptedBy` idem (`import_batch.baseline_accepted_by_subject`).
  `POST /batches/{id}/continue` bewaart nog altijd niets, enkel een logregel met username en subject.
  De Service-intake blijft `requireText` gebruiken voor `uploadedBy` (laat `system` toe); de Web-laag sluit dit af.

> **Important technical constraint discovered** (C3)
>
> De `CsrfFilter` loopt vóór de autorisatie. Een CSRF-resolver die op een requestparameter terugvalt (Spring-default) roept
> `getParameter` aan en laat Tomcat een multipart-body tot 1 GB (`spring.servlet.multipart.max-file-size`) volledig inlezen,
> ook bij anonieme verzoeken en nog vóór er iets geweigerd wordt. Daarom leest `SecurityConfiguration`
> (`HeaderOnlySpaCsrfTokenRequestHandler`) het CSRF-token uitsluitend uit de header `X-XSRF-TOKEN`; een requestparameter
> wordt nooit geraadpleegd.

> **Important technical constraint discovered** (C9)
>
> `catalog_reference_state` (changeset 004) heeft geen batch-FK (enkel `source_state_id` en `import_link_id`). De
> ondertekenaar van een referentierij is daardoor alleen te herleiden via `(import_link_id, accepted_at)` =
> `import_batch.(import_link_id, baseline_accepted_at)`. Dat werkt alleen omdat `SourceStateBaselineService` in
> `acceptBaseline` één `acceptedAt`-instant gebruikt voor alles wat de aanvaarding schrijft, een fragiele koppeling.
> (Stap 5, docs/decisions.md 2026-10-01/02: de aanvaarding is nu één transactie, alles of niets; `acceptedAt` wordt
> één keer vóór de transactie bepaald en elke schrijfquery krijgt dezelfde waarde, dus de gelijkheid van `acceptedAt`
> over bronstaat, referenties, observaties en batch is nu vanzelf waar; een gedeeltelijk geschreven referentiestaat met
> een ander instant na een hervatting kan niet meer voorkomen.) Daarom heeft
> deze tabel (net als `catalog_source_state`, `catalog_price_observation` en `task_run.triggered_by`) bewust geen eigen
> `*_by_subject`-kolom (changeset 007).

> **Important technical constraint discovered**
>
> `POST /tasks/{taskId}/deliveries` archiveert, registreert en screent synchroon binnen één HTTP-verzoek, terwijl
> `spring.servlet.multipart.max-file-size` op 1 GB staat. Eén upload kan dus minuten of langer duren; de client mag geen
> timeout zetten en een tussenliggende proxy met een standaardtimeout kan de aanroep afbreken terwijl de server doorwerkt.
> De idempotente herhaling (zelfde `deliveryReference`/inhoud) is de herstelroute.
