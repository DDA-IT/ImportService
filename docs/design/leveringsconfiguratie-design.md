# Ontwerp — Leveringsconfiguratie en verbindingsprofiel (SFTP), automatisch ophalen

Bindend na `docs/decisions.md` 2026-09-29 "Leveringsconfiguratie en verbindingsprofiel (SFTP): ontwerp bindend (L1-L8)" (door de mens aanvaard: acht keuzes conform aanbeveling plus aannames A1-A19). Dit document werkt dat besluit concreet uit; het is geen nieuwe architectuurkeuze. Bronnen: `business-analyse-leveranciersbibliotheken.md` (hierna BA) §14.2, §14.12, §14.14, §14.17, §14.20, §16.6, §16.8; `docs/design/credentials-sleutelbeheer-design.md`; `docs/decisions.md` 2026-09-25 (A2), 2026-09-26, 2026-09-27, 2026-09-29.

## 0. Doel, afbakening en status

**Doel.** CatalogImport haalt een leveringsbestand zelf op bij een leverancier via SFTP, in plaats van dat een mens het uploadt of in de servermap plaatst. Daarvoor zijn nodig: een versleuteld opgeslagen credential (K-2/K-3), een verbindingsprofiel (host, poort, login, vastgepinde hostsleutel), een leveringsconfiguratie (map, bestandscondities, ouderdom, maximale grootte) en een ophaalrun die het bestand door de bestaande intake- en screeningketen stuurt.

**Kern van het ontwerp.** Het model heeft al `catalog_import_task` (`trigger_type` MANUAL/SCHEDULED, `trigger_expression` cron) en `task_run` (`uk_task_run_concurrency`, `locked_by`/`locked_at`); `delivery.task_id` en `delivery.task_run_id` bestaan. Een ophaalrun is dus een `task_run` met extra kolommen. De leveringsconfiguratie (DC) hangt aan de taak. Verbindingsprofiel en DC zijn eigen entiteiten met onveranderlijke versies; een taak wijst naar één DC-versie; de credential is een los object dat DB-afgedwongen aan één host gebonden is.

**In scope.** Credentials-schema en -endpoints (K-2/K-3), profielen, DC's, taakkoppeling, hostsleutelscan en verbindingstest, handmatig "Nu ophalen" (synchroon), runlijst, frontend `/connections` en koppeling op scherm 2.

**Buiten scope (bewust).**
- Scheduler/periodieke ophaling: eigen ontwerpstap na K-4 (S-0/S-1). "Geen scheduler" blijft voorlopig staan.
- API-bronnen, SSH-sleutelauthenticatie (`SSH_PRIVATE_KEY` is enkel gereserveerd), FTP/FTPS.
- Verplaatsen of verwijderen van bestanden bij de leverancier (`post_fetch_action` = enkel `LEAVE`).
- Opvolgversies van profiel/DC via de UI (later; schema ondersteunt `based_on_version_id` al).
- Meerdelige leveringen (A15: één bestand per levering), recursie in submappen (A17), wildcards/regex in condities.
- Verplichte servertest voor activatie (A16).
- Secrets-managerintegratie en productiehosting (zie §12).

**Status.** Ontwerp bindend; stappen DC-0 (dit document), K-2a (changeset 013), K-2b (herversleutelen), K-3 (credential-endpoints), LC-1, LC-2 en K-4a afgerond; K-4b (changeset 015, ophaalrun, intake-refactor, runlijst) gebouwd, verificatie door de hoofdsessie. "Geen server-side ophaling" (2026-09-26) is daarmee vervallen: handmatig "Nu ophalen" bestaat (backend). "Geen scheduler" blijft staan. K-4c (herstel vastgelopen ophaalrun) is gebouwd, verificatie door de hoofdsessie. De overige bouwstappen uit §10 (F-1, F-2, S-0/S-1) staan open.

## 1. Beslissingen en aannames

`docs/decisions.md` 2026-09-29 is bindend; onderstaande samenvatting vervangt dat niet.

### 1.1 Beslissingen L1-L8

| # | Besluit |
|---|---|
| **L1** | Herroeping van de 27/09-ontwerpgrens ("geen Leveringsconfiguratie-entiteit, geen acquisitieservice") voor dit spoor. "Geen scheduler" blijft. |
| **L2** | DC hangt aan de taak (`catalog_import_task.delivery_configuration_version_id`), niet aan `import_link` of de revisie. Planning blijft `trigger_expression` op de taak. De revisie houdt van laag 1 enkel `access_delivery_set_kind`; een DC-wijziging hoogt `access_version` niet op. De ophaalrun is een `task_run`, geen aparte `fetch_run`. |
| **L3** | Onveranderlijke versierijen (kop + versie) voor profiel en DC; een taak neemt een nieuwe versie expliciet over; een versie in gebruik wijzigt nooit. |
| **L4** | (a) Credential DB-afgedwongen aan één host gebonden (`bound_host` + samengestelde FK); host wijzigen = wachtwoord opnieuw invoeren. (b) Fail-closed property `catalogimport.fetch.allowed-hosts` (exacte hostnamen, beheerd door infra): niet gezet = 404 `FETCH_NOT_CONFIGURED`; host buiten de lijst = `FETCH_HOST_NOT_ALLOWED`; DNS eenmaal resolven en op dat IP verbinden. |
| **L5** | Verplichte vastgepinde hostsleutel (algoritme + SHA-256-vingerafdruk per profielversie); scan-endpoint toont de vingerafdruk, de mens vergelijkt via een ander kanaal en bevestigt; daarna strikt (`SFTP_HOST_KEY_MISMATCH`, nooit auto-accept); sleutelwissel = nieuwe profielversie. |
| **L6** | Bestandskeuze: eerste run na koppelen neemt enkel het recentste matchende bestand (oudere = `OLDER_THAN_WATERMARK`); daarna chronologisch, oudste nieuwe eerst (bij gelijke mtime op naam), één bestand per run, `pending_file_count` zichtbaar; nooit een bestand ouder dan het laatst opgehaalde; overgeslagen bestanden blijven zichtbaar (`fetch_file_observation`). |
| **L7** | (a) Niet achter `catalogimport.setup-api.enabled`; MANAGE, in tweede lijn de allowlist. (b) Detail-GET's met host, login, map en testlistings vragen MANAGE; READ ziet enkel code, naam, status, `secretSet` en runs (bewuste afwijking van A2 "read = alle GET's", op grond van BA r.2210 en precedent D8). |
| **L8** | Apache MINA SSHD (`sshd-core` + `sshd-sftp`) als runtime-afhankelijkheid in Service (enkel client); embedded server enkel in test-scope; versie pinnen. |

### 1.2 Aannames A1-A19

Het beslisdossier zelf is niet in de repo opgenomen; `docs/decisions.md` (2026-09-29) vat het samen. De aannames hieronder zijn
door de hoofdsessie gecontroleerd tegen het dossier (A1, A2, A5, A8 en A14 aangevuld/gecorrigeerd).

| # | Aanname |
|---|---|
| A1 | Geen planning in de Leveringsconfiguratie; planning blijft `trigger_expression` op de taak (L2). |
| A2 | Verbindingsprofiel en Leveringsconfiguratie zijn aparte entiteiten (BA r.2190-2200); een credential wordt gedeeld via referentie, nooit gekopieerd (BA r.2094, r.2397). |
| A3 | Na het ophalen enkel `LEAVE`: bron blijft ongemoeid. |
| A4 | Idempotentiesleutel = remote object per taak: `sftp:<eerste 32 hex van sha256(lower(host) ":" port ":" absoluutPad)>:<mtimeEpochSec>:<byteSize>` in `uk_delivery_idempotency`, zonder DC-versie-ID. |
| A5 | v1 enkel wachtwoordauthenticatie (`SFTP_PASSWORD`). `PRIVATE_KEY` volgt als server-gegenereerd ed25519-paar waarvan enkel de publieke sleutel getoond wordt; sleutels met passphrase worden niet ondersteund. |
| A6 | `UNDECRYPTABLE` is afgeleid, niet opgeslagen; credential-status enkel `ACTIVE`/`REVOKED`. |
| A7 | `secret_key_check` fail-fast bij opstart voor de actieve sleutel; sleutel-ID's met omgevingsprefix. |
| A8 | Intrekken (crypto-shred) is altijd toegestaan, ook wanneer profielversies de credential gebruiken; het antwoord toont het gebruik. |
| A9 | "Nu ophalen" is synchroon en zonder automatische retry. |
| A10 | Upload en servermap geweigerd op een taak met DC (409 `TASK_HAS_DELIVERY_CONFIGURATION`). |
| A11 | Hoogstens één DC-taak per koppeling (service-regel, geen DB-constraint). |
| A12 | Defaults: min. ouderdom 300 s, globale cap 1 GB, time-outs connect/auth/idle 15/15/60 s, listing-cap 10 000. |
| A13 | `delivery.source_kind = SFTP` (check verbreden). |
| A14 | De actornaam bij geplande (scheduler-)runs wordt pas in de scheduler-stap (S-0) beslist. |
| A15 | Eén bestand per levering. |
| A16 | Nog geen verplichte servertest voor activatie; testresultaten worden wel gelogd. |
| A17 | Remote map absoluut, geen `..`, geen recursie. |
| A18 | Listing nooit stil afgekapt (`FETCH_LISTING_TOO_LARGE`). |
| A19 | `credential_ref` is een server-side gegenereerde UUID. |

## 2. Tegenstrijdigheden tussen bronnen (T1-T13)

| # | Tegenstrijdigheid | Oplossing |
|---|---|---|
| **T1** | BA §14.12/§14.14: laag 1 (SFTP-map, filter, planning) zit in de revisie; code kent `access_version`/`access_config_hash` (001 r.45-46) en `access_delivery_set_kind` (002 r.17). Maar BA §14.17 r.2174-2176, §14.20 r.2433 en r.3289 zeggen dat host, login, folder, bestandsvoorwaarden, planning en credentials niet in de importdefinitie horen. | Eigen object (profiel + DC). De revisie houdt enkel `access_delivery_set_kind`; `access_version`/`access_config_hash` blijven bestaan maar worden door DC-wijzigingen niet opgehoogd (L2). |
| **T2** | BA r.2176/r.2174: koppeling combineert een DC-versie met een definitieversie en planning hoort bij de Leveringsconfiguratie. Bestaand model: planning op de taak. | Planning blijft `trigger_expression` op de taak (L2); de DC-versie wordt aan de taak gehangen, niet aan `import_link`. |
| **T3** | 27/09-ontwerpgrens (geen DC-entiteit, geen acquisitieservice) versus 29/09 dat die nodig heeft. | L1: expliciete herroeping voor dit spoor. |
| **T4** | credentials-design: hostsleutel buiten scope (infrastructuur) versus MITM-risico: een aanvaller kan het wachtwoord onderscheppen. | L5: hostsleutel per profielversie vastgepind in de applicatie. |
| **T5** | credentials-design §5.1: `ciphertext` not null versus §5.3: wissen zet `ciphertext = null`. | Beide kolommen nullable met checks (§3.1). |
| **T6** | Conventies credentials-design §5 (`generated always`, `timestamp`, `varchar(255)`, `text`) versus project (001/007/012: `generated by default`, `timestamp with time zone`, `*_by varchar(100)` + `*_by_subject varchar(255)`). | Gelijktrekken met het project. |
| **T7** | 001 r.134-136: idempotentiesleutel is het remote object, nooit enkel de inhoudshash; `docs/requirements/catalog-import.md` r.54: hash + definitie. | r.54 is historisch (proefversie); voor SFTP geldt het remote object (A4). |
| **T8** | BA r.1565: meerdere matches is een leveringsissue; maar bij `LEAVE` blijven eerdere bestanden staan en matchen ze opnieuw. | L6: watermark en één bestand per run; overgeslagen bestanden zichtbaar in `fetch_file_observation`, geen issue. |
| **T9** | BA r.2210/r.2397: versies in gebruik immutable. Een taak-gebonden configuratie wijzigen door update zou dat breken. | L3: onveranderlijke versierijen. |
| **T10** | A2 (2026-09-25): READ = alle GET's. BA r.2210 (hostnamen/login niet zichtbaar buiten beheerrol) en precedent D8. | L7b: detail-GET's met host/login/map/listings vragen MANAGE. |
| **T11** | De setup-vlag `catalogimport.setup-api.enabled` zou de nieuwe endpoints kunnen afschermen; ophalen moet ook werken wanneer setup uit staat. | L7a: niet achter de vlag; MANAGE + allowlist. |
| **T12** | BA §16.8 noemt een recht `delivery.credentials.view`. | Reeds opgelost door V1 (2026-09-29): geen enkele API geeft een secret terug; het recht vervalt. |
| **T13** | Een bookmarkplaats `DELIVERY_FILE_SELECTION` zou `ck_import_definition_bookmark_usage_place` (006 r.125-129) moeten verbreden. | `bookmark_name` in de conditietabel is nu nullable en ongebruikt; verbreding later, additief. |

## 3. Datamodel

Nieuwe changesets in `Web/src/main/resources/db/changelog/` (master `db.changelog-master.yaml`): **013** (credentials, K-2a), **014** (profiel + DC + taakkoppeling, LC-1), **015** (ophaalrun, K-4b). Alles is additief, behalve **één** verbreding van een bestaande check in 015 (`ck_delivery_source_kind`, 011 r.19-20, van `UPLOAD`/`LOCAL_DIRECTORY` naar + `SFTP`); de tweede verbreding (`ck_import_definition_bookmark_usage_place`, T13) volgt pas later. Beide moeten expliciet gemeld worden in het rapport van de betrokken bouwstap.

Conventies (uit 001/007/012): `id bigint generated by default as identity`; `timestamp with time zone`; `*_by varchar(100)` + `*_by_subject varchar(255)` met check "subject niet null => by niet null"; append-only eventtabellen met `source` HUMAN/SYSTEM waarbij SYSTEM => `changed_by`/`changed_by_subject` null (patroon 012 r.116-120); rollback per changeset.

### 3.1 Changeset 013 (K-2a)

**`external_credential`**

| Kolom | Type | Opmerking |
|---|---|---|
| `id` | bigint identity | PK |
| `credential_ref` | uuid not null | uniek; server-side gegenereerd (A19), deel van de AAD |
| `label` | varchar(200) not null | herkenbare naam, geen geheim |
| `secret_kind` | varchar(40) not null | check `SFTP_PASSWORD`, `SSH_PRIVATE_KEY` (laatste gereserveerd, A5) |
| `bound_host` | varchar(255) not null | genormaliseerd (kleine letters, geen punt achteraan) |
| `ciphertext` | text null | formaat `v1:<keyId>:<base64>`; null na intrekken |
| `encryption_key_id` | varchar(32) null | kopie van keyId, voor herversleutelen |
| `status` | varchar(20) not null | check `ACTIVE`, `REVOKED` (A6: `UNDECRYPTABLE` afgeleid) |
| `secret_updated_at`, `secret_updated_by`, `secret_updated_by_subject` | timestamptz / varchar(100) / varchar(255) | laatste instellen of vervangen |
| `created_at`, `created_by`, `created_by_subject` | idem | |
| `revoked_at`, `revoked_by`, `revoked_by_subject`, `revoked_reason` | idem; reden varchar(500) | |

Checks: `(status = 'ACTIVE') = (ciphertext is not null)`; `(ciphertext is null) = (encryption_key_id is null)`; `status = 'REVOKED'` => `revoked_reason is not null`; `*_by_subject` niet null => `*_by` niet null. Unieke sleutels: `credential_ref`; `(id, secret_kind, bound_host)` (doel van de samengestelde FK in §3.2). Changeset 013-4 (additief) voegt `ck_external_credential_bound_host_normalized` toe: `bound_host` niet leeg, gelijk aan `lower()`, geen punt achteraan, geen witruimte.

**`external_credential_event`** (append-only, geen UPDATE/DELETE): `id`, `credential_id` FK, `event_kind` check `CREATED`/`REPLACED`/`REVOKED`/`REENCRYPTED`, `reason varchar(500) not null`, `source` HUMAN/SYSTEM, `changed_by`, `changed_by_subject`, `changed_at`, `previous_key_id`, `new_key_id`. Check: `HUMAN` => `changed_by` niet null; `SYSTEM` => `changed_by` en `changed_by_subject` null. Nooit een waarde en nooit een hash.

**`secret_key_check`**: `key_id varchar(32)` PK, `check_value text not null`, `created_at`. `check_value` is een bekende tekst versleuteld met eigen AAD `catalogimport|secret_key_check|<keyId>|v1`; dat vereist een uitbreiding van `SecretsService`. De bestaande AAD `catalogimport|external_credential|<credential_ref>|<secret_kind>|v1` blijft ongewijzigd.

### 3.2 Changeset 014 (LC-1)

**`connection_profile`** (kop): `id`, `code varchar(50)` unique, `name varchar(200)`, `protocol` check `SFTP`, `active boolean`, `created_at`, `created_by`, `created_by_subject`, `updated_at`.

**`connection_profile_version`** (onveranderlijk): `id`, `connection_profile_id` FK, `version_number` (unique per profiel), `host varchar(255)` (genormaliseerd), `port int` check 1-65535 (default 22), `username varchar(200)`, `auth_method` check `PASSWORD`/`PRIVATE_KEY`, `credential_id` + `credential_secret_kind` + `credential_host` als samengestelde FK naar `external_credential (id, secret_kind, bound_host)`, `host_key_algorithm varchar(40) not null`, `host_key_fingerprint_sha256 varchar(100) not null`, `based_on_version_id` (self-FK, nullable), `change_reason varchar(500)`, `config_hash varchar(64)`, `created_at`, `created_by`, `created_by_subject`. Checks: `credential_host = host`; `auth_method = 'PASSWORD'` <=> `credential_secret_kind = 'SFTP_PASSWORD'`, analoog `PRIVATE_KEY` <=> `SSH_PRIVATE_KEY`. Effect: een profielversie met eigen host en een credential van een andere host kan niet bestaan (Ontdekking 2).

**`delivery_configuration`** (kop): `id`, `code` unique, `name`, `acquisition_kind` check `SFTP`, `active`, `created_*`, `updated_at`.

**`delivery_configuration_version`** (onveranderlijk): `id`, `delivery_configuration_id` FK, `version_number` (unique per DC), `connection_profile_version_id` FK, `remote_directory varchar(500)` (A17), `selection_mode` check `CONDITIONS`/`ALL_FILES`, `min_file_age_seconds int not null`, `max_file_bytes bigint not null`, `post_fetch_action` check enkel `LEAVE`, `based_on_version_id`, `change_reason`, `config_hash`, `created_*`.

**`delivery_configuration_file_condition`**: `id`, `dc_version_id` FK, `group_number`, `sequence_number`, `condition_kind` check `NAME_EQUALS`/`NAME_STARTS_WITH`/`NAME_ENDS_WITH`/`NAME_CONTAINS`/`EXTENSION_IS` (geen wildcards/regex), `compare_value varchar(200)`, `case_sensitive boolean`, `bookmark_name varchar(100) null` (ongebruikt, T13). Unique `(dc_version_id, group_number, sequence_number)`. Servicecontrole: `ALL_FILES` = geen rijen; `CONDITIONS` = minstens één rij. Semantiek: condities binnen een groep zijn EN, groepen zijn OF.

**`acquisition_config_event`** (append-only): `id`, `event_kind` check `TASK_BOUND`/`TASK_UNBOUND`/`CONNECTION_TESTED`/`HOST_KEY_SCANNED`/`RETIRED`, `task_id`, `dc_version_id`, `profile_version_id` (alle nullable FK's), `outcome_code`, `detail varchar(500)` (zonder secret, zonder archiefpad), `reason`, `source`, `changed_by`, `changed_by_subject`, `changed_at`.

**Wijziging `catalog_import_task`** (001 r.93-111): `delivery_configuration_version_id bigint null` met FK naar `delivery_configuration_version`. A11 (hoogstens één DC-taak per koppeling) wordt in de service afgedwongen.

### 3.3 Changeset 015 (K-4b)

**Wijziging `task_run`** (001 r.116-130): `trigger_source varchar(20) null` (check `UPLOAD`/`LOCAL_DIRECTORY`/`MANUAL_FETCH`/`SCHEDULED_FETCH`), `delivery_configuration_version_id bigint null` (FK), `outcome_code varchar(60) null`, `outcome_message varchar(500) null`, `pending_file_count int null`. Bestaande rijen blijven `null`; geen backfill.

**`fetch_file_observation`**: `id`, `task_run_id` FK, `remote_file_name varchar(500)`, `byte_size bigint`, `remote_modified_at timestamptz`, `decision` check `SELECTED`/`ALREADY_FETCHED`/`TOO_YOUNG`/`TOO_LARGE`/`DEFERRED`/`OLDER_THAN_WATERMARK`, `delivery_id bigint null` FK. Unique `(task_run_id, remote_file_name)`; index op `delivery_id`.

**Wijziging `delivery`**: `ck_delivery_source_kind` (011) verbreed met `SFTP`. Dit is een drop + add van een check-constraint, de enige niet-additieve stap; bestaande waarden blijven geldig. Rollback zet de oorspronkelijke check terug.

**Stand na K-4b (gebouwd 2026-09-29).** `Web/src/main/resources/db/changelog/015-fetch-run.sql` (in de master), elk deel met rollback: 015-1 de vijf nullable `task_run`-kolommen met `ck_task_run_trigger_source`, `ck_task_run_pending_file_count` (≥ 0), `fk_task_run_dc_version` en index; 015-2 `fetch_file_observation`; 015-3 de verbreding van `ck_delivery_source_kind` (drop + add, zelfde naam; de rollback lukt enkel zolang er geen SFTP-levering bestaat - bewust geen stille omzetting). Geen subjectkolommen (dus `ConfigurationActorSubjectSchemaTest` ongewijzigd), niets hernoemd. Invullingen:
- `fetch_file_observation.remote_modified_at` is **nullable**: een server hoeft geen wijzigingstijd te geven; zo'n bestand krijgt `TOO_YOUNG` (minimale ouderdom niet aantoonbaar) en blijft zichtbaar. Extra checks: `delivery_id` enkel bij `SELECTED` (`ck_fetch_file_observation_delivery`), `byte_size >= 0`, naam niet leeg. Append-only (JPA: geen setters, `updatable = false`).
- Domain: `TaskRunTriggerSource` (+ `forIntake(DeliverySourceKind)`), `FetchFileDecision`, `FetchFileObservation`, `DeliverySourceKind.SFTP`; `TaskRun` kreeg de vijf velden (lui geladen DC-versie, `setOutcome` kort een te lange melding zichtbaar in). Dao: `FetchFileObservationRepository` (per run; watermark), `TaskRunRepository.findTop20ByTaskIdOrderByStartedAtDescIdDesc` en `findTaskIdByRunId`, `DeliveryRepository.findExistingIdempotencyKeys`, `CatalogImportTaskRepository.findByIdForUpdate`.

## 4. Ophaalflow, idempotentie, statussen en audit

### 4.1 Flow (`POST /tasks/{id}/fetch-runs`, synchroon, A9)

1. **Voorcontroles zonder run:** taak bestaat, heeft een DC-versie, fetch is geconfigureerd (allowlist, L4), secrets zijn geconfigureerd, credential is `ACTIVE`, geen lopende run. Anders 409 (of 404 `FETCH_NOT_CONFIGURED`); niets wordt vastgelegd.
2. `task_run` `RUNNING` aanmaken (`trigger_source = MANUAL_FETCH`, `triggered_by` = mens; `uk_task_run_concurrency` blokkeert gelijktijdige runs).
3. Verbinden met de vastgepinde hostsleutel, authenticeren, map lijsten.
4. Condities toepassen, sleutels berekenen, één query tegen `delivery(task_id, idempotency_key)`.
5. Keuze volgens L6 (watermark, oudste nieuwe eerst, één bestand); elk bekeken bestand als `fetch_file_observation`.
6. Streamen naar `DeliveryArchiveStore.store` met byte-guard (`max_file_bytes` en globale cap).
7. Her-stat na download: grootte en mtime ongewijzigd, anders `FETCH_FILE_CHANGED_DURING_TRANSFER`.
8. Intake in de bestaande run: delivery, delivery_file en batch (`source_kind = SFTP`).
9. Screening (zoals nu).
10. De run sluit via screening zoals bij upload; bij "niets nieuw" sluit de run direct met `NO_NEW_FILE`.

**Foutafhandeling.** Een remote fout (verbinding, authenticatie, hostsleutel, map, bestand) is geen HTTP-fout: het antwoord is 201 met een `FAILED`-run en `outcome_code`. Voorcontrole-fouten blijven 4xx.

### 4.2 Idempotentie

Sleutel: `sftp:<eerste 32 hex van sha256(lower(host) ":" port ":" absoluutPad)>:<mtimeEpochSec>:<byteSize>`, uniek per taak (`uk_delivery_idempotency`, 001 r.154), **zonder DC-versie-ID** zodat een nieuwe DC-versie hetzelfde bestand niet opnieuw ophaalt. Zelfde bestand twee keer = één levering (tweede run `NO_NEW_FILE`, waarnemingsrij `ALREADY_FETCHED`); nieuwe mtime of grootte = nieuwe levering. Race: de DB-constraint is de laatste verdediging.

### 4.3 Stabiliteit en limieten

Minimale ouderdom (default 300 s) plus her-stat na download. Maximale grootte vóór download (listing) en tijdens streamen; globale cap 1 GB. Listing-cap 10 000: overschrijding is `FAILED` `FETCH_LISTING_TOO_LARGE`, nooit stil afkappen. Time-outs connect/auth/idle 15/15/60 s (globaal, A12). Retries komen pas in de scheduler-stap (BA §16.6 r.4243: drie pogingen met backoff en jitter, geen retry bij authenticatie- of configuratiefouten).

### 4.4 Statussen en foutcodes

`TaskRunStatus` blijft `COMPLETED`/`FAILED` (plus bestaande `PENDING`/`RUNNING`). `outcome_code`:

- Geslaagd: `FETCHED`, `NO_NEW_FILE`.
- Mislukt: `CONNECTION_FAILED`, `SFTP_HOST_KEY_MISMATCH`, `SFTP_AUTHENTICATION_FAILED`, `CREDENTIAL_REVOKED`, `CREDENTIAL_UNDECRYPTABLE`, `REMOTE_DIRECTORY_NOT_FOUND`, `REMOTE_PERMISSION_DENIED`, `FETCH_FILE_TOO_LARGE`, `FETCH_FILE_CHANGED_DURING_TRANSFER`, `FETCH_TRANSFER_INCOMPLETE`, `FETCH_LISTING_TOO_LARGE`, `FETCH_HOST_NOT_ALLOWED`.
- Herstel (K-4c, `FAILED`): `FETCH_TIMED_OUT` (automatisch bij het opstarten) en `FETCH_MANUALLY_ABORTED` (`POST /task-runs/{id}/abort`); zie §11 "Stand na K-4c".

`outcome_message` (max. 500) bevat nooit een secret, serverbanner of archiefpad.

Upload en servermap blijven ongewijzigd voor taken zonder DC; op een taak mét DC geven ze 409 `TASK_HAS_DELIVERY_CONFIGURATION` (A10).

### 4.5 Audit

`task_run` en `fetch_file_observation` leggen per run vast wie, wanneer, welke DC-versie en welke bestanden bekeken/overgeslagen werden. `external_credential_event` en `acquisition_config_event` zijn append-only. Nooit een waarde of hash van een secret in een log, event, exception of antwoord.

### 4.6 Stand na K-4b (gebouwd 2026-09-29)

`FetchRunService.fetch` volgt §4.1; details in de klassedocumentatie. Keuzes die het ontwerp openliet:

- **Volgorde van de voorcontroles** (niets vastgelegd, niets gecontacteerd): 404 `FETCH_NOT_CONFIGURED` → 404 `TASK_NOT_FOUND` → 409 `TASK_HAS_NO_DELIVERY_CONFIGURATION` (bestaande LC-2-code) → 409 `SECRETS_NOT_CONFIGURED` → 409 `CREDENTIAL_REVOKED` → 409 `NO_ACTIVE_REVISION` / `CONFIG_PRICE_FIELD_MISSING` / `CONFIG_REQUIRED_BOOKMARK_MISSING` → 409 `TASK_RUN_IN_PROGRESS`. `FETCH_NOT_CONFIGURED` vóór de taak-404, zoals bij de K-4a-tests (de functie bestaat dan niet). De **inrichtingscontroles van de upload** (actieve revisie, prijsveld, verplichte bookmarks) zijn toegevoegd aan de voorcontroles: een onvolledig ingerichte taak haalt niets op en archiveert niets (zelfde regel als de upload). Alles onder het rijslot op de taak; de run (`RUNNING`, `MANUAL_FETCH`, `triggered_by` = mens, DC-versie) in dezelfde transactie. Geen `TASK_NOT_MANUAL`-controle: "Nu ophalen" is geen upload en werkt voor elke taak met een DC.
- **Host buiten de allowlist** (bv. de allowlist is na het koppelen verkleind) is een `FAILED`-run met `FETCH_HOST_NOT_ALLOWED` (§4.4), geen 409.
- **Watermark** = de laatst vastgelegde waarneming met een geregistreerde levering van deze taak (`fetch_file_observation` met `delivery_id`), dus het laatst opgehaalde bestand, **per taak** (over DC-versies heen). Vergeleken wordt (wijzigingstijd in epoch-seconden, naam). Strikt kleiner = `OLDER_THAN_WATERMARK`; gelijke positie met een andere grootte is een nieuw remote object (A4) en telt als nieuw. Na herkoppelen naar een andere map kunnen bestanden van vóór de watermark dus nooit meer opgehaald worden (zichtbaar als `OLDER_THAN_WATERMARK`).
- **Beslissing per bestand** (chronologisch, oudste eerst, bij gelijke tijd op naam): geen wijzigingstijd → `TOO_YOUNG`; sleutel bestaat → `ALREADY_FETCHED`; onder de watermark → `OLDER_THAN_WATERMARK`; eerste run (geen watermark) → enkel het recentste nieuwe bestand blijft over, de rest `OLDER_THAN_WATERMARK`; daarna jonger dan `min_file_age_seconds` → `TOO_YOUNG`, groter dan min(`max_file_bytes`, 1 GB) → `TOO_LARGE`, het eerste andere `SELECTED`, de rest `DEFERRED`. Een `TOO_LARGE`-bestand houdt de chronologie niet tegen (het nieuwere wordt gekozen). Is bij de eerste run het recentste bestand te jong of te groot, dan wordt niets opgehaald (`NO_NEW_FILE`).
- **Uitkomsten:** `FETCHED` wordt vastgelegd in de registratietransactie; daarna sluit de screening de run (`COMPLETED`, of `FAILED` bij een technisch mislukte screening - `outcome_code` blijft dan `FETCHED`, de batch toont `SCREENING_FAILED`). Nieuw naast §4.4: `FETCH_INTERNAL_ERROR` (onverwachte interne fout, bv. het lokale archief; de run wordt `FAILED` en het antwoord is 500). Verandert de inrichting tijdens de download (bv. geen actieve revisie meer), dan is de run `FAILED` met die 409-code als `outcome_code`.
- **Archief bij falen:** `DeliveryArchiveStore.store` ruimt zijn eigen gedeeltelijke bestand op als de stream breekt of de byte-guard ingrijpt; is het bestand volledig geschreven maar faalt de her-stat of de registratie, of blijkt het al opgehaald, dan ruimt `FetchRunService` het object expliciet op. Enkel een procescrash tussen archiveren en registreren laat een wees-object achter (zoals bij de upload).
- **Download** (`SftpConnector.fetchOne`): listing en download in één SFTP-sessie; byte-guard in de stream; her-stat (grootte en wijzigingstijd gelijk aan de listing, anders `FETCH_FILE_CHANGED_DURING_TRANSFER`; ook een verdwenen bestand); ontvangen bytes ≠ grootte of afgebroken stream = `FETCH_TRANSFER_INCOMPLETE`; bestand niet leesbaar = `REMOTE_PERMISSION_DENIED`. Een fout van de aanroeper (databank, archief) komt ongewijzigd terug, nooit als `CONNECTION_FAILED`.
- **Transacties:** nooit een transactie open tijdens netwerkverkeer; de keuze (één query op de sleutels + de watermark) is een korte leestransactie tussen listing en download.

**Racevenster A10 opgelost (LC-2 open punt 2).** De registratietransactie van upload en servermap (`DeliveryIntakeService.register`) neemt als eerste lezing hetzelfde rijslot op de taak als de taakkoppeling (`CatalogImportTaskRepository.findByIdForUpdate`; de koppeling vergrendelt alle taken van de koppeling). Wie het slot tweede krijgt, leest de toestand van de eerste pas na diens commit: een upload die haar voorcontrole haalde vlak vóór een koppeling commit, wacht en krijgt daarna 409 `TASK_HAS_DELIVERY_CONFIGURATION` (archief opgeruimd, geen run); komt de registratie eerst, dan ziet de koppeling de lopende run (409 `TASK_RUN_IN_PROGRESS`). De ophaalrun en `registerInRun` nemen hetzelfde slot. Bewezen met `FetchIdempotencyTest.anUploadThatPassedItsPreCheckJustBeforeABindingCommitsNeverStartsARunOnThatTask`.

**Intake-refactor.** De orkestratie intake → screening zit nu in `DeliveryReceptionService.screenIfCreated` (Service), gebruikt door upload, servermap en ophaalrun; de controller roept intake en `screenIfCreated` na elkaar aan (de bronstream is gesloten vóór de screening, zoals voorheen). `DeliveryIntakeService` kreeg `registerInRun` (intake in een bestaande run, zonder `TASK_NOT_MANUAL`/A10-controle, met dezelfde revisiecontroles) en de publieke `requireIntakeConfiguration`; nieuwe upload-/servermapruns krijgen `trigger_source` `UPLOAD`/`LOCAL_DIRECTORY`. Endpoints, statuscodes en antwoorden van upload en servermap zijn ongewijzigd.

## 5. Verbindingstest en hostsleutelscan

- `POST /connection-profiles/host-key-scan` `{host, port}` (MANAGE): verbindt zonder authenticatie en geeft algoritme + SHA-256-vingerafdruk. De mens vergelijkt met de waarde die de leverancier via een ander kanaal gaf.
- `POST /connection-profile-versions/{id}/test` (MANAGE): verbinding, hostsleutelcontrole en authenticatie.
- `POST /delivery-configuration-versions/{id}/test` (MANAGE): idem, plus map lijsten en condities toepassen; geeft naam, grootte en mtime; cap 100 met `truncated`; **geen download**.
- Antwoord altijd 200 `{outcome, failureCode, presentedHostKeyFingerprint, matchedFiles}`; nooit een secret, nooit een serverbanner. Elk resultaat wordt een `acquisition_config_event` `CONNECTION_TESTED` (scan: `HOST_KEY_SCANNED`).
- Geen verplichte servertest voor activatie (A16). Scan en test vallen onder dezelfde allowlist (L4): zonder die begrenzing is dit een SSRF- of poortscanmiddel (Ontdekking 3).

**Stand na K-4a (2026-09-29, gebouwd; tests door de hoofdsessie te draaien).** Alle drie onder `/api/catalog-import`, recht **MANAGE**, niet achter de setup-vlag, 403 vóór alles.

| Endpoint | Antwoord | Foutvolgorde (geen event, niets gecontacteerd) |
|---|---|---|
| `POST /connection-profiles/host-key-scan` `{host, port?}` (port weg = 22) | 200 `{outcome, failureCode, hostKeyAlgorithm, hostKeyFingerprintSha256}` | 400 `CONNECTION_PROFILE_HOST_INVALID`/`_PORT_INVALID` → 404 `FETCH_NOT_CONFIGURED` → **409 `FETCH_HOST_NOT_ALLOWED` (mét event)** |
| `POST /connection-profile-versions/{id}/test` | 200 `{outcome, failureCode, presentedHostKeyFingerprint, matchedFiles: null, truncated: false}` | 404 `FETCH_NOT_CONFIGURED` → 404 `CONNECTION_PROFILE_VERSION_NOT_FOUND` → 409 `SECRETS_NOT_CONFIGURED` |
| `POST /delivery-configuration-versions/{id}/test` | idem, `matchedFiles [{name, size, modifiedAt}]` (hoogstens 100, nieuwste eerst, bij gelijke tijd op naam) + `truncated` | 404 `FETCH_NOT_CONFIGURED` → 404 `DELIVERY_CONFIGURATION_VERSION_NOT_FOUND` → 409 `SECRETS_NOT_CONFIGURED` |

- `outcome` = `OK` of `FAILED`; `failureCode` uit: `CONNECTION_FAILED`, `SFTP_HOST_KEY_MISMATCH`, `SFTP_AUTHENTICATION_FAILED`, `CREDENTIAL_REVOKED`, `CREDENTIAL_UNDECRYPTABLE`, `REMOTE_DIRECTORY_NOT_FOUND`, `REMOTE_PERMISSION_DENIED`, `FETCH_LISTING_TOO_LARGE`, `FETCH_HOST_NOT_ALLOWED` (klasse `FetchOutcomeCodes`). `presentedHostKeyFingerprint` = wat de server toonde, ook bij een mismatch.
- **Volgorde van een test:** allowlist (zonder DNS) → credential ingetrokken (`CREDENTIAL_REVOKED`, niets gecontacteerd) → één DNS-opzoeking + adrescontrole → verbinden + hostsleutel → pas dan ontsleutelen (`CREDENTIAL_UNDECRYPTABLE`) → aanmelden → (DC) map `stat` + lijsten + voorwaarden. Bij een mismatch wordt het wachtwoord niet eens ontsleuteld en is er geen aanmeldpoging.
- **Statuscode-keuze scan buiten de allowlist:** 409 (de host is geldig, maar deze omgeving laat hem niet toe - zelfde familie als `SECRETS_NOT_CONFIGURED`, en 403 is in deze app voorbehouden aan rechten/identiteit). Bij de tests is dezelfde weigering een 200-uitkomst, zoals hierboven voorgeschreven. De 400-vóór-404-volgorde van de scan volgt het precedent van de servermap (`LocalSourceDisabledTest`).
- **Events:** elke scan (ook een geweigerde, SSRF-audit) `HOST_KEY_SCANNED` zonder verwijzingen, `detail` = `host:port` + algoritme/vingerafdruk of de vaste foutmelding; elke test `CONNECTION_TESTED` met `profile_version_id` (DC-test ook `dc_version_id`), `outcome_code` = `OK` of de foutcode, `detail` met vaste tekst (bij mismatch: getoonde en vastgepinde vingerafdruk). HUMAN, actor + subject uit het token, `reason` leeg. Nooit een secret, digest of serverbanner.
- **DC-test:** enkel naam-voorwaarden (EN binnen een groep, OF tussen groepen; `ALL_FILES`); minimale ouderdom en maximale grootte zijn beslissingen van de ophaalrun (K-4b) en tellen niet. Enkel gewone bestanden (geen submappen, geen symlinks); de listing-cap telt álle ingangen (zonder `.`/`..`).
- **Transacties:** nooit een databasetransactie open tijdens netwerkverkeer (leesmomentopname → verbinding → eigen transactie voor het event).

## 6. Endpoints en rechten

| Onderdeel | Endpoint | Recht |
|---|---|---|
| Credentials | `POST /credentials` `{label, secretKind, boundHost, secret, reason}`; `PUT /credentials/{ref}/secret` `{secret, reason}` (event `REPLACED`); `POST /credentials/{ref}/revoke` `{reason}` (crypto-shred, altijd toegestaan, A8) | MANAGE |
| Credentials lezen | `GET /credentials`, `/credentials/{ref}`, `/credentials/{ref}/events`: enkel `label`, `secretKind`, `boundHost`, `status`, `secretSet`, `secretUpdatedAt`, `secretUpdatedBy`, gebruik (aantal profielversies); geen `encryption_key_id` | MANAGE |
| Profielen | `POST /connection-profiles` (kop + v1); `GET` lijst en detail (met versies); scan; test | schrijven/scan/test/detail MANAGE |
| Leveringsconfiguraties | `POST /delivery-configurations` (kop + v1 + condities); `GET` lijst en detail; test | idem |
| Taakkoppeling | `PUT /tasks/{id}/delivery-configuration` `{versionId, reason}`; `DELETE` met reden | MANAGE |
| Ophalen | `POST /tasks/{id}/fetch-runs` | MANAGE |
| Runs | `GET /tasks/{id}/runs`, `GET /task-runs/{id}` | READ |

**Stand na K-3 (2026-09-29).** De credential-endpoints staan onder `/api/catalog-import/credentials` en vragen alle zes `MANAGE` (ook de drie GET's, zoals de tabel voorschrijft). Het antwoord toont `credentialRef` naast de velden hierboven; het **gebruik** (aantal profielversies) ontbreekt nog, omdat de profieltabel pas met LC-1 bestaat, en komt additief in LC-2. Heractiveren van een ingetrokken credential loopt via dezelfde `PUT /credentials/{ref}/secret` (decisions 2026-09-29). Intrekken werkt ook zonder sleutelring (A8). Details, foutcodes en afwijkingen: `credentials-sleutelbeheer-design.md` §6 "Bouwstap K-3".

Opvolgversies van profiel/DC volgen later. Rechten conform L7: READ ziet enkel code, naam, status, `secretSet` en runs; alles met host, login, map of listings vraagt MANAGE. De endpoints staan **niet** achter `catalogimport.setup-api.enabled` (L7a). Nieuwe endpoints moeten in `PermissionCoverageTest` en de HTTP-rechtentests terugkomen.

**Stand na LC-2 (2026-09-29).** Alle endpoints staan onder `/api/catalog-import` en vragen **MANAGE**, ook lijst en detail (zelfde keuze als K-3: één regel voor de hele ophaalconfiguratie; het detail toont host, login, map en vingerafdruk). De lijsten tonen bewust enkel kopgegevens (id, code, naam, soort/protocol, actief, laatste versie-id en -nummer, aanmaak), zodat ze later additief voor READ geopend kunnen worden. Recht eerst: 403 vóór 400/404/409.

| Endpoint | Body / antwoord | Foutcodes |
|---|---|---|
| `POST /connection-profiles` (201) | `{code, name, host, port?, username, authMethod, credentialRef, hostKeyAlgorithm, hostKeyFingerprintSha256, reason}` → kop + `versions[]` (versie 1) | 400 `CONNECTION_PROFILE_CODE_INVALID`, `_NAME_INVALID`, `_HOST_INVALID`, `_PORT_INVALID`, `_USERNAME_INVALID`, `_AUTH_METHOD_INVALID`, `_AUTH_METHOD_NOT_SUPPORTED` (`PRIVATE_KEY`, A5), `_CREDENTIAL_REQUIRED`, `_HOST_KEY_ALGORITHM_INVALID`, `_HOST_KEY_FINGERPRINT_INVALID`, `_REASON_REQUIRED`; 404 `CREDENTIAL_NOT_FOUND` (ook een ref die geen UUID is); 409 `CONNECTION_PROFILE_CREDENTIAL_NOT_ACTIVE`, `_CREDENTIAL_HOST_MISMATCH`, `_CREDENTIAL_KIND_MISMATCH`, `_CODE_EXISTS` |
| `GET /connection-profiles`, `GET /connection-profiles/{id}` | lijst (kop + laatste versie) / detail met alle versies (nieuwste eerst; per versie host, poort, login, methode, `credentialRef`, label en status van de credential, hostsleutel, `configHash`) | 404 `CONNECTION_PROFILE_NOT_FOUND` |
| `POST /delivery-configurations` (201) | `{code, name, connectionProfileVersionId, remoteDirectory, selectionMode, conditionGroups?: [{conditions: [{kind, value, caseSensitive}]}], minFileAgeSeconds?, maxFileBytes?, reason}` → kop + versie 1 met `conditionGroups` | 400 `DELIVERY_CONFIGURATION_CODE_INVALID`, `_NAME_INVALID`, `_PROFILE_VERSION_REQUIRED`, `_REMOTE_DIRECTORY_INVALID`, `_SELECTION_MODE_INVALID`, `_CONDITIONS_REQUIRED`, `_CONDITIONS_NOT_ALLOWED`, `_CONDITION_INVALID`, `_MIN_FILE_AGE_INVALID`, `_MAX_FILE_BYTES_INVALID`, `_REASON_REQUIRED`; 404 `CONNECTION_PROFILE_VERSION_NOT_FOUND`; 409 `DELIVERY_CONFIGURATION_CODE_EXISTS` |
| `GET /delivery-configurations`, `GET /delivery-configurations/{id}` | lijst / detail met versies en voorwaarden | 404 `DELIVERY_CONFIGURATION_NOT_FOUND` |
| `PUT /tasks/{id}/delivery-configuration` (200) | `{versionId, reason}` → `{taskId, taskName, importLinkId, importLinkCode, deliveryConfigurationVersionId, deliveryConfigurationId, deliveryConfigurationCode, deliveryConfigurationVersionNumber}` | 400 `TASK_BINDING_VERSION_REQUIRED`, `TASK_BINDING_REASON_REQUIRED`; 404 `TASK_NOT_FOUND`, `DELIVERY_CONFIGURATION_VERSION_NOT_FOUND`; 409 `TASK_RUN_IN_PROGRESS`, `IMPORT_LINK_HAS_DELIVERY_CONFIGURATION_TASK` (A11) |
| `DELETE /tasks/{id}/delivery-configuration` (200) | body `{reason}` (niet in de querystring: geen vrije tekst in toegangslogs) → dezelfde vorm, DC-velden `null` | 400 `TASK_BINDING_REASON_REQUIRED`; 404 `TASK_NOT_FOUND`; 409 `TASK_HAS_NO_DELIVERY_CONFIGURATION`, `TASK_RUN_IN_PROGRESS` |
| Upload en servermap (bestaand) | ongewijzigd voor taken zonder DC | nieuw: 409 `TASK_HAS_DELIVERY_CONFIGURATION` op een taak met DC (A10) |
| `GET /credentials`, `/credentials/{ref}` (bestaand, additief) | nieuw veld `profileVersionCount` (gebruik; ook na intrekken, A8) | — |

**Stand na K-4b (2026-09-29).** Onder `/api/catalog-import`, niet achter de setup-vlag; recht eerst (403 vóór alles). Er bestond nog geen `/tasks/{id}/runs`; de drie endpoints staan in een eigen `CatalogImportTaskRunController` (de bestaande taakcontrollers zijn ongewijzigd).

| Endpoint | Recht | Antwoord | Foutcodes |
|---|---|---|---|
| `POST /tasks/{id}/fetch-runs` (geen body) | MANAGE | **201** met de run, ook bij een remote fout (`status = FAILED`, `outcomeCode`) of niets nieuws (`COMPLETED`/`NO_NEW_FILE`) | 404 `FETCH_NOT_CONFIGURED`, `TASK_NOT_FOUND`; 409 `TASK_HAS_NO_DELIVERY_CONFIGURATION`, `SECRETS_NOT_CONFIGURED`, `CREDENTIAL_REVOKED`, `NO_ACTIVE_REVISION`, `CONFIG_PRICE_FIELD_MISSING`, `CONFIG_REQUIRED_BOOKMARK_MISSING`, `TASK_RUN_IN_PROGRESS`; 500 bij een interne fout (run `FAILED`/`FETCH_INTERNAL_ERROR`) |
| `GET /tasks/{id}/runs` | READ | de laatste 20 runs (alle soorten), nieuwste eerst, `observations: null` | 404 `TASK_NOT_FOUND` |
| `GET /task-runs/{id}` | READ | één run met `observations` | 404 `TASK_RUN_NOT_FOUND` |

Vorm van een run (`TaskRunView`): `{id, taskId, status, triggerSource, triggeredBy, startedAt, finishedAt, deliveryConfigurationVersionId, outcomeCode, outcomeMessage, pendingFileCount, deliveryId, batchId, batchStatus, observations: [{remoteFileName, byteSize, remoteModifiedAt, decision, deliveryId}]}`. Nooit host, login, map, archiefpad of secret; wel bestandsnamen (READ, zoals `DeliveryView`). De runlijst geeft geen waarnemingen omdat één run er tot 10 000 kan hebben. `fase5-perm-design.md` §1 kreeg twee rijen; `PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest` en `PermissionReadEndpointsHttpTest` zijn uitgebreid.

**Keuzes LC-2** (details: "Bouwstap LC-2" onder de tabel in §10).
- **Vingerafdrukformaat:** OpenSSH-vorm `SHA256:<base64 zonder opvulling>`, exact 43 tekens na het voorvoegsel, 32 bytes, canonieke base64 (heen-en-terug coderen geeft dezelfde tekst); enkel trimmen aan de rand, verder niets aangepast (zoals `ssh-keygen -lf` en `ssh-keyscan host | ssh-keygen -lf -` tonen). **Algoritmen** (vaste lijst, exacte schrijfwijze): `ssh-ed25519`, `ecdsa-sha2-nistp256`, `ecdsa-sha2-nistp384`, `ecdsa-sha2-nistp521`, `rsa-sha2-256`, `rsa-sha2-512`.
- **`config_hash`** (hex SHA-256, 64 tekens) via het bestaande `RevisionConfigHashes.hash(laag, onderdelen...)`: UTF-8 van de laagnaam, gevolgd door elk onderdeel, telkens voorafgegaan door `U+001F`. Stuurtekens worden in alle tekstvelden geweigerd, dus de vorm is eenduidig. Code, naam, reden en actor tellen nooit mee.
  - Profielversie, laag `connection_profile_version/v1`: `host` (genormaliseerd), `port` (decimaal), `username`, `authMethod`, `credentialRef` (UUID, kleine letters), `hostKeyAlgorithm`, `hostKeyFingerprintSha256`.
  - DC-versie, laag `delivery_configuration_version/v1`: profielcode, profielversienummer (businessreferentie, niet het interne id), `remoteDirectory`, `selectionMode`, `minFileAgeSeconds`, `maxFileBytes`, `postFetchAction`, aantal voorwaarden, en per voorwaarde in volgorde (groep, volgnummer): groep, volgnummer, soort, `caseSensitive` (`true`/`false`), waarde.
- **Voorwaarden:** 1-gebaseerd genummerd (`group_number` = positie van de groep, `sequence_number` = positie binnen de groep), EN binnen een groep, OF tussen groepen. Letterlijke waarden: `*`, `?`, `/`, `\` en stuurtekens geweigerd; `EXTENSION_IS` zonder punt vooraan of achteraan (betekenis: de naam eindigt op `"." + waarde`; `tar.gz` mag); `caseSensitive` verplicht (geen stille default).
- **Externe map (A17):** begint met `/`, geen lege, `.`- of `..`-segmenten, geen `\`, geen `/` achteraan (behalve `/` zelf), ≤ 500 tekens.
- **Limieten (A12):** `minFileAgeSeconds` default 300, ≥ 0; `maxFileBytes` default én bovengrens 1 GB = 1024³ bytes (zelfde eenheid als `CATALOG_MAX_UPLOAD_SIZE=1GB`); daarbuiten 400, nooit stil aangepast. `post_fetch_action` is geen invoer: altijd `LEAVE`.
- **Events:** (her/ont)koppelen schrijft `TASK_BOUND`/`TASK_UNBOUND` (HUMAN, actor en subject uit het token, reden, DC-versie én haar profielversie). Herkoppelen = `TASK_UNBOUND` (oude versie) + `TASK_BOUND` (nieuwe). Dezelfde versie opnieuw koppelen = 200 zonder wijziging of event. Het **aanmaken** van een profiel- of DC-versie schrijft **geen** event: de check-lijst van `acquisition_config_event` kent geen passende soort; de aanmaak staat in de versierij zelf (`created_*`, `change_reason`).
- **A11 en lopende run:** servicecontrole, geserialiseerd door alle taken van de koppeling te vergrendelen (`findByImportLinkIdForUpdate`, vaste volgorde); "lopend" = een run in `PENDING`/`RUNNING` (op status, niet enkel op de concurrency-token).
- **A10:** één controle in `DeliveryIntakeService.resolve` (gedeeld door upload en servermap), na `TASK_NOT_MANUAL` en vóór de retry-herkenning: ook een retry van een eerdere upload wordt op een taak met DC geweigerd; er wordt niets gearchiveerd.

## 7. Frontend

- Nieuwe route `/connections` onder "Inrichting" (naast `/setup` en `/templates`, zie `Frontend/src/routes.tsx` r.57-65) met drie tabs:
  - **Credentials:** write-only invoer, maskering, vervangen en intrekken met typ-bevestiging.
  - **Verbindingsprofielen:** stap "hostsleutel scannen" met vingerafdruk vergelijken en bevestigen.
  - **Leveringsconfiguraties:** conditiebouwer en "Test".
- Scherm 2 (upload): taakkoppeling, "Nu ophalen" en runlijst (laatste 20) als derde bron naast upload en servermap; een taak met DC toont enkel die bron.
- Een mislukte run vóór een batch is enkel in de runlijst zichtbaar; een telblok op scherm 0 volgt later.
- Nieuwe foutcodes in `errors/codes.ts` (F-2).

## 8. SFTP-library (L8)

Gekozen: **Apache MINA SSHD** (`sshd-core` + `sshd-sftp`; Apache-2.0). Redenen: client én server (embedded testserver zonder Docker), `ServerKeyVerifier` voor pinning, gebruikt door Spring Integration 6, precedent in DDA (ProfabApi `FtpInboundImporter`; `Prodis/docs/internal/purchasing/purchase-invoice-merge-analysis.md` r.72, niet in deze repo geverifieerd).

Afgewogen en verworpen: mwiede jsch (geen server), sshj (BouncyCastle, geen server), Spring Integration SFTP (nieuw framework, dubbele idempotentie). Prodis zelf gebruikt geen SFTP-library. Versie expliciet pinnen in de root-`dependencyManagement`; of de Boot-BOM MINA beheert is nog te verifiëren (§13). Server enkel in test-scope.

**Stand na K-4a (2026-09-29).**
- De Spring Boot 3.4.5-BOM beheert MINA SSHD **niet** (geverifieerd in `spring-boot-dependencies-3.4.5.pom`). Property `sshd.version` = **2.15.0** in de root-`pom.xml`, met `sshd-common`, `sshd-core` en `sshd-sftp` in `dependencyManagement`; Service hangt `sshd-core` + `sshd-sftp` (compile) aan. Reden voor 2.x ≥ 2.12.1: Terrapin (CVE-2023-48795; strict KEX staat standaard aan sinds 2.12.0) en CVE-2024-41909 (opgelost in 2.12.1). **Open:** de Bouwer had geen toegang tot Maven Central of een CVE-databank; of 2.15.0 op 29/09/2026 nog de recentste 2.x zonder open CVE is, moet vóór productie gecontroleerd worden (bv. OSS Index / `mvn versions:display-dependency-updates`).
- **BouncyCastle voor ed25519:** eigenlijk gebleken dat MINA SSHD voor ed25519-ondersteuning BouncyCastle nodig heeft. Property `bouncycastle.version` = **1.80** (januari 2026-release; stabiel, breed ondersteund), in `dependencyManagement` als `org.bouncycastle:bcprov-jdk18on`. Service hangt het aan met scope `runtime` (MINA detecteert het via de classpath; Service-code roept het niet rechtstreeks aan). **Bewijs:** `SftpConnectorTest.ed25519HostKeysAreSupported` controleert dat ed25519 werkend is.
- Geen verdere test-afhankelijkheid: de serverklassen (`SshServer`, `SftpSubsystemFactory`, `VirtualFileSystemFactory`) zitten in dezelfde sshd-jars en worden enkel in `Web/src/test` gebruikt (`testsupport.SftpTestServer`). Geen `net.i2p:eddsa`.
- Client-instellingen (`SftpConnector`): geen `~/.ssh/config` (`HostConfigEntryResolver.EMPTY`), geen sleutelidentiteiten, enkel wachtwoordauthenticatie via een `PasswordIdentityProvider` (niet `addPasswordIdentity`, die op DEBUG een digest van het wachtwoord logt), per operatie een eigen `SshClient` met een eigen `ServerKeyVerifier`.

## 9. Kleinste verticale slice

Credential aanmaken, hostsleutel scannen, profiel met vastgepinde vingerafdruk, DC (map, één conditiegroep, min. ouderdom, max. grootte), DC testen, aan taak koppelen, "Nu ophalen" = 201 met run, levering (`source_kind = SFTP`), batch, screening, nogmaals ophalen = `NO_NEW_FILE`.

Bewijs met de embedded MINA-server: zelfde bestand 2x = één levering; nieuwe mtime = nieuwe levering; fout wachtwoord, foute hostsleutel, te groot bestand, bestand gewijzigd tijdens transfer en host buiten allowlist falen elk met een eigen code; het secret komt in geen antwoord, log of exception voor.

## 10. Bouwvolgorde (sequentieel)

Gerichte tests: `mvn -pl Web -am test "-Dtest=<klassen>" -Dsurefire.failIfNoSpecifiedTests=false`. Spring-contexttests: `scripts/test/run-full-tests.ps1 -Tests ...` (geïsoleerd schema).

| Stap | Trap | Inhoud | Gerichte tests | Status |
|---|---|---|---|---|
| DC-0 | licht | Dit document, gelijktrekken credentials-design, extern punt 7 | n.v.t. | **Afgerond (2026-09-29)** |
| K-2a | zwaar | Changeset 013, Domain/Dao, `SecretsService` key-check-AAD, startcontrole, lekbewijs | `ExternalCredentialSchemaTest`, `SecretKeyCheckStartupTest`, `CredentialLeakDetectionTest`; regressie `SecretCipherTest`, `SecretsStartupValidationTest`, `SecretsPropertiesTest`, `PermissionHttpTest`, `ConfigurationActorSubjectSchemaTest` | **Afgerond (2026-09-29)** — gebouwde bestanden en afwijkingen: `credentials-sleutelbeheer-design.md` §6 "Bouwstap K-2a" |
| K-2b | gemiddeld | Herversleutelen bij opstart (R2a) | `SecretsRotationTest`; regressie `SecretKeyCheckStartupTest`, `CredentialLeakDetectionTest`, `ExternalCredentialSchemaTest`, `PermissionHttpTest` | **Afgerond (2026-09-29)** — `SecretsRotationService` (verifier eerst expliciet aangeroepen, optimistische guard per rij, `secret_updated_*` ongewijzigd), bestanden, afwijkingen en operatorprocedure: `credentials-sleutelbeheer-design.md` §6 "Bouwstap K-2b" |
| K-3 | zwaar | `CredentialService` + endpoints | `CredentialHttpTest`, `PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest`, `PermissionReadEndpointsHttpTest`; regressie `SecretsRotationTest`, `CredentialLeakDetectionTest`, `ExternalCredentialSchemaTest`, `PermissionHttpTest` | **Afgerond (2026-09-29)** — endpoints, leesrecht MANAGE, foutcodes en afwijkingen (gebruik nog niet in het antwoord, intrekken zonder sleutelring toegestaan, body zelf gelezen tegen lekken): `credentials-sleutelbeheer-design.md` §6 "Bouwstap K-3" |
| LC-1 | gemiddeld | Changeset 014, Domain/Dao | `DeliveryConfigurationSchemaTest`; regressie `ConfigurationActorSubjectSchemaTest`, `ExternalCredentialSchemaTest`, `CredentialHttpTest`, `PermissionHttpTest` | **Afgerond (2026-09-29)** (gebouwd zonder shell: testrun door de hoofdsessie) — zie "Bouwstap LC-1" onder deze tabel |
| LC-2 | zwaar | Profielen, DC's, taakkoppeling | `ConnectionProfileHttpTest`, `DeliveryConfigurationHttpTest`, `TaskBindingHttpTest` + rechtentests (`PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest`, `PermissionReadEndpointsHttpTest`), `CredentialHttpTest`; regressie `DeliveryConfigurationSchemaTest`, `PermissionHttpTest`, `LocalSourceDeliveryTest`, `LocalSourceDisabledTest` | **Afgerond (2026-09-29)** — gerichte tests door de hoofdsessie groen (130/130, zie `docs/decisions.md`) — zie "Bouwstap LC-2" onder deze tabel en §6 "Stand na LC-2" |
| K-4a | zwaar | MINA-adapter, allowlist, scan/test, embedded testserver, BouncyCastle voor ed25519 | `SftpConnectorTest`, `ConnectionTestHttpTest`, `FetchAllowlistTest`; regressie `ConnectionProfileHttpTest`, `DeliveryConfigurationHttpTest`, `PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest`, `PermissionReadEndpointsHttpTest`, `PermissionHttpTest` | **Gebouwd (2026-09-29), gebouwd zonder shell: compileren en testen door de hoofdsessie** — zie "Bouwstap K-4a" onder deze tabel, §5 en §8 "Stand na K-4a" |
| K-4b | zwaar | Changeset 015, ophaalrun, intake-refactor, fetch-endpoint, runlijst, L6 | `FetchRunTest`, `FetchIdempotencyTest`, `SftpDownloadTest`; regressie `DeliveryUploadTest`, `LocalSourceDeliveryTest`, `LocalSourceDisabledTest`, `BatchBaselineHttpTest`, `TaskBindingHttpTest`, `ConnectionTestHttpTest`, `SftpConnectorTest`, `ScreeningRecoveryServiceTest`, `ConfigurationActorSubjectSchemaTest`, `DeliveryConfigurationSchemaTest`, rechtentests | **Gebouwd (2026-09-29), gebouwd zonder shell: compileren en testen door de hoofdsessie** — zie "Bouwstap K-4b" onder deze tabel, §3.3, §4.6 en §6 "Stand na K-4b" |
| K-4c | gemiddeld | Herstel vastgelopen fetch-run | `FetchRunRecoveryTest`; regressie `FetchRunTest`, `FetchIdempotencyTest`, `ScreeningRecoveryServiceTest`, `PublicationRunHttpTest`, `PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest`, `PermissionHttpTest` | **Gebouwd (2026-09-30), gebouwd zonder shell: compileren en testen door de hoofdsessie** — `FetchRunRecoveryService`, zie §11 "Stand na K-4c" |
| F-1 | zwaar | Frontend `/connections` | frontendtests van de module | Open |
| F-2 | gemiddeld | Scherm 2: koppelen, "Nu ophalen", runlijst, `errors/codes.ts` | frontendtests van de module | Open |
| S-0/S-1 | later | Scheduler (eigen ontwerpstap) | n.v.t. | Later |

**Bouwstap LC-1 (afgerond 2026-09-29).** Gebouwd (enkel schema, Domain, Dao; geen service, geen endpoints):
- Changeset `Web/src/main/resources/db/changelog/014-delivery-configuration.sql` (in de master), elk deel met rollback: 014-1 `connection_profile`, 014-2 `connection_profile_version`, 014-3 `delivery_configuration`, 014-4 `delivery_configuration_version`, 014-5 `delivery_configuration_file_condition`, 014-6 `acquisition_config_event` (append-only), 014-7 `catalog_import_task.delivery_configuration_version_id` (nullable, FK, index). Puur additief; de enige aanraking van een bestaande tabel is die ene nullable kolom.
- Domain: `ConnectionProfile`, `ConnectionProfileVersion`, `DeliveryConfiguration`, `DeliveryConfigurationVersion`, `DeliveryConfigurationFileCondition`, `AcquisitionConfigEvent`, package-private `ConfigurationGuard`, enums `ConnectionProtocol`, `ConnectionAuthMethod`, `AcquisitionKind`, `DeliverySelectionMode`, `DeliveryFileConditionKind`, `DeliveryPostFetchAction`, `AcquisitionConfigEventKind`, `AcquisitionConfigEventSource`. `CatalogImportTask` kreeg een nullable, lui geladen `deliveryConfigurationVersion` (getter/setter; bestaand gedrag ongewijzigd).
- Dao: `ConnectionProfileRepository` (`findByCode`), `ConnectionProfileVersionRepository` (versies per kop, per nummer, `findMaxVersionNumber`, `countByCredentialId`), `DeliveryConfigurationRepository` (`findByCode`), `DeliveryConfigurationVersionRepository` (idem), `DeliveryConfigurationFileConditionRepository` (per versie, op groep/volgnummer), `AcquisitionConfigEventRepository` (per taak).
- Tests (`Web/src/test`): `domain.DeliveryConfigurationSchemaTest`; `ConfigurationActorSubjectSchemaTest` bijgewerkt (subjectkolommen 25 op 17 → 30 op 22).

Invullingen van open details (geen enkele wijzigt een bestaand contract):
- **Onveranderlijkheid** in JPA, niet met een databasetrigger: versie-entiteiten en voorwaarden hebben geen setters en elke kolom is `updatable = false` (Domain heeft geen Hibernate-afhankelijkheid, dus geen `@Immutable`); het project kent geen triggers. Bewezen met een reflectietest (geen `set*`-methoden) en een test dat een geforceerde wijziging de database niet bereikt.
- `config_hash varchar(64) not null` op beide versietabellen; `change_reason` nullable; `created_by` not null op koppen en versies (een mens met MANAGE maakt ze), `created_by_subject` nullable met koppelcheck.
- `connection_profile_version`: extra checks `version_number >= 1`, `username` niet leeg, `host_key_algorithm` en `host_key_fingerprint_sha256` niet leeg; `based_on_version_id` als self-FK (nullable) op beide versietabellen; `port` not null default 22.
- `delivery_configuration_version`: `version_number >= 1`, `remote_directory` niet leeg (A17 blijft een servicecontrole).
- `delivery_configuration_file_condition`: `group_number` en `sequence_number` >= 0 (de service kiest 0- of 1-gebaseerd), `compare_value` niet leeg, `case_sensitive` not null zonder default.
- `acquisition_config_event`: `reason`, `detail`, `outcome_code` (varchar 60, zoals `task_run` in 015) en alle drie de verwijzingen nullable; geen "minstens één verwijzing"-check (het ontwerp noemt er geen). `reason` is bewust nullable omdat een test of scan geen menselijke reden heeft.
- Indexen: op elke FK-kolom die niet al door een unieke sleutel gedekt is (`credential_id`, `based_on_version_id` (2x), `connection_profile_version_id`, event-verwijzingen `(x, id)`, `catalog_import_task.delivery_configuration_version_id`).
- De samengestelde FK wordt in de entiteit niet als `@JoinColumns` gemapt maar als drie gewone kolommen (`credentialId`, `credentialSecretKind`, `credentialHost`) die de constructor uit de meegegeven `ExternalCredential` afleidt; de constructor weigert dezelfde fouten als de database (andere host, methode/soort-mismatch, poort, niet-genormaliseerde host) zonder waarden in de melding.
- `ConnectionProfileVersionRepository.countByCredentialId` is toegevoegd voor het gebruik in het credentialantwoord (K-3 afwijking 2, LC-2); niet strikt nodig voor het schema.

**Bouwstap LC-2 (gebouwd 2026-09-29).** Geen schemawijziging, geen nieuwe dependency. Endpoints, foutcodes en keuzes: §6 "Stand na LC-2".
- Service: `ConnectionProfileService`, `DeliveryConfigurationService`, `TaskDeliveryConfigurationService` (patroon `CredentialService`: `TransactionTemplate` per schrijfactie, tijdstempels op microseconden), package-private `AcquisitionConfigInput` (trimmen, lengte, stuurtekens). Hostnormalisatie via `service.support.HostNames`, `config_hash` via het bestaande `service.support.RevisionConfigHashes.hash`. `CredentialService`: `CredentialView` kreeg `profileVersionCount` (additief veld; constructor kreeg `ConnectionProfileVersionRepository`). `DeliveryIntakeService`: A10-controle (`TASK_HAS_DELIVERY_CONFIGURATION`).
- Web: `CatalogImportConnectionProfileController`, `CatalogImportDeliveryConfigurationController`, `CatalogImportTaskBindingController` (eigen controller onder `/tasks`, de bestaande `CatalogImportTaskController` is ongewijzigd). Gewone `@RequestBody` (geen secrets in deze bodies).
- Dao (additief): `CatalogImportTaskRepository.findImportLinkIdByTaskId` en `findByImportLinkIdForUpdate` (rijslot op alle taken van een koppeling), `ConnectionProfileVersionRepository.countPerCredentialId` en `findLatestVersions`, `DeliveryConfigurationVersionRepository.findLatestVersions`, `ConnectionProfileRepository`/`DeliveryConfigurationRepository.findAllByOrderByCodeAscIdAsc`.
- Tests (`Web/src/test`): `ConnectionProfileHttpTest`, `DeliveryConfigurationHttpTest`, `TaskBindingHttpTest` (nieuw); `CredentialHttpTest`, `PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest`, `PermissionReadEndpointsHttpTest` bijgewerkt.
- Bewust niet in LC-2: opvolgversies en een apart "versie overnemen"-endpoint (design §6: later), scan/test (K-4a), ophalen (K-4b), een READ-lijst met enkel code/naam/status, de DC-koppeling in `GET /tasks`, een leesendpoint voor de `acquisition_config_event`-historiek.

**Bouwstap K-4a (gebouwd 2026-09-29).** Geen schemawijziging (de eventsoorten `HOST_KEY_SCANNED`/`CONNECTION_TESTED` bestonden al in 014-6). Nieuwe runtime-afhankelijkheden: Apache MINA SSHD 2.15.0 + BouncyCastle 1.80 voor ed25519-ondersteuning (§8).
- Service: `FetchHostPolicy` (allowlist, één DNS-opzoeking, adrescontrole), `FetchTarget` (goedgekeurd doel; enkel door de policy aan te maken), `SftpConnector` (MINA-client: scan, `verifyLogin`, `listDirectory`; geen download), `FetchFailureException` (vaste melding, geen oorzaak-keten), `FetchOutcomeCodes`, `ConnectionTestService` (scan/tests + events), `support.RemoteFileSelection` (EN/OF-voorwaarden, puur).
- Web: `CatalogImportConnectionTestController` (drie POST's, §5); `application.yml` enkel commentaar voor `catalogimport.fetch.*`.
- **Allowlist-vorm:** `catalogimport.fetch.allowed-hosts` = exacte hostnamen, komma-gescheiden in één string (zoals `catalogimport.secrets.keys`; env `CATALOGIMPORT_FETCH_ALLOWED_HOSTS`), elk genormaliseerd met `HostNames`. Geen wildcards/domeinen/CIDR. Leeg of afwezig = functie uit (404 `FETCH_NOT_CONFIGURED`); een lege ingang, wildcard of ongeldige naam = de applicatie start niet. Een YAML-lijst wordt niet gelezen (functie blijft uit).
- **Loopback-regel:** `catalogimport.fetch.allow-loopback` (default `false`, WARN bij opstart als `true`) laat enkel `127.0.0.0/8` en `::1` toe - nodig voor de embedded testserver. Altijd geweigerd, ook met die toelating: link-local (`169.254/16` met het cloud-metadata-adres, `fe80::/10`), multicast, `0.0.0.0`/`::`, `0.0.0.0/8`, `255.255.255.255`. Is **één** van de geresolvede adressen verboden, dan wordt de host geweigerd; anders wordt op het eerste adres verbonden, als IP-literal zonder naam (de library resolvet niets meer: DNS-rebinding). Private adressen (`10/8`, `192.168/16`, ...) zijn toegelaten als de host in de lijst staat (leverancier via VPN).
- **RSA-afbeelding (LC-2 open punt 3):** de scan rapporteert het onderhandelde signatuuralgoritme, `rsa-sha2-512` bij voorkeur, `rsa-sha2-256` als de server enkel dat kent; nooit `ssh-rsa`. Een vastgepinde RSA-sleutel (256 of 512) laat de client `rsa-sha2-512` en `rsa-sha2-256` aanbieden; vergeleken wordt de vingerafdruk van de sleutel (gelijk voor beide). De LC-2-validatie is niet gewijzigd. Een server die voor RSA enkel `ssh-rsa` (SHA-1) kent, faalt met `CONNECTION_FAILED` (geen gemeenschappelijk algoritme).
- **Vastpinnen:** de client biedt enkel de algoritmen van de vastgepinde sleutelsoort aan (een server met meerdere sleutels toont zo de juiste); heeft de server die soort niet meer, dan is dat `CONNECTION_FAILED` ("no common host key algorithm - possibly not the pinned key type"), geen `SFTP_HOST_KEY_MISMATCH`.
- **Time-outs (A12):** `catalogimport.fetch.connect-timeout` (PT15S, TCP + sleuteluitwisseling tot de hostsleutel), `auth-timeout` (PT15S), `idle-timeout` (PT60S, ook de NIO2-leestime-out), `listing-cap` (10000); niet positief = de applicatie start niet.
- Tests (`Web/src/test`): `service.SftpConnectorTest` (puur, embedded server), `service.FetchAllowlistTest` (context zonder allowlist + pure policytests), `web.ConnectionTestHttpTest` (context met sleutelring en allowlist `127.0.0.1` + loopback-toelating), `testsupport.SftpTestServer`; `PermissionCoverageTest` (+3 rijen), `PermissionWriteEndpointsHttpTest` (403/`system`/recht-eerst voor de drie endpoints; context zet de allowlist expliciet leeg). `fase5-perm-design.md` §1 één rij.
- Niet getest (geen betrouwbare opzet met de virtuele map op Windows): `REMOTE_PERMISSION_DENIED` (afbeelding van SFTP-status 3 staat in `SftpConnector.sftpFailure`).

**Bouwstap K-4b (gebouwd 2026-09-29).** Schema: §3.3 "Stand na K-4b". Flow en keuzes: §4.6. Endpoints: §6 "Stand na K-4b". Geen nieuwe dependency.
- Service: `FetchRunService` (nieuw), `TaskRunQueryService` + `TaskRunView` (nieuw), `DeliveryReceptionService` (nieuw: orkestratie intake → screening), `DeliveryIntakeService` (rijslot in de registratie, `trigger_source`, `registerInRun`, publieke `requireIntakeConfiguration`; bestaande overloads en gedrag ongewijzigd), `SftpConnector` (`fetchOne`, `DownloadPlan`, `FetchResult`, `remotePath`; listing gedeeld, K-4a-gedrag ongewijzigd), `FetchOutcomeCodes` (+ `FETCHED`, `NO_NEW_FILE`, `FETCH_FILE_TOO_LARGE`, `FETCH_FILE_CHANGED_DURING_TRANSFER`, `FETCH_TRANSFER_INCOMPLETE`, `FETCH_INTERNAL_ERROR`), `ConnectionTestService` (selectie van een DC-versie als package-private static gedeeld met de ophaalrun, gedrag ongewijzigd).
- Web: `CatalogImportTaskRunController` (nieuw); `CatalogImportDeliveryController` roept `DeliveryReceptionService.screenIfCreated` aan in plaats van zelf te screenen (contract ongewijzigd).
- Tests (`Web/src/test`): `web.FetchRunTest`, `web.FetchIdempotencyTest` (met gedeelde basis `web.FetchTestSupport`: één context, eigen embedded SFTP-server per klasse), `service.SftpDownloadTest` (puur); `PermissionCoverageTest` (+3), `PermissionWriteEndpointsHttpTest`, `PermissionReadEndpointsHttpTest` bijgewerkt.
- Bewust niet in K-4b: herstel van een vastgelopen `RUNNING`-fetchrun (K-4c), scheduler (S-0/S-1), frontend (F-1/F-2), een telblok op scherm 0.

**Bouwstap K-4c** (herstel vastgelopen ophaalrun; keuzes en beperkingen: §11 "Stand na K-4c"):
- Service: `FetchRunRecoveryService` (nieuw: definitie, `abort`, `recoverStuck`, opstartherstel achter `catalogimport.fetch.recovery-on-startup`); `FetchRunService.close` neemt nu het rijslot van de taak; `FetchOutcomeCodes` + `FETCH_TIMED_OUT`, `FETCH_MANUALLY_ABORTED`.
- Dao: `TaskRunRepository.findByStatusAndTriggerSourceIn` (geen schemawijziging, geen migratie).
- Web: `POST /task-runs/{runId}/abort` in `CatalogImportTaskRunController` (`MANAGE`); `application.yml` `catalogimport.fetch.stuck-after`.
- Tests (`Web/src/test`): `web.FetchRunRecoveryTest`; `PermissionCoverageTest` (+1), `PermissionWriteEndpointsHttpTest` bijgewerkt; `FetchTestSupport` zet `catalogimport.fetch.recovery-on-startup=false`.

## 11. Ontdekkingen

> **Important technical constraint discovered**
>
> `DeliveryIntakeService.resolve` (r.263-...) accepteert enkel `MANUAL`-taken (`TASK_NOT_MANUAL`, r.266-270) en maakt zelf de `TaskRun` aan (r.207-214). De orkestratie intake -> screening zit in de Web-controller (`CatalogImportDeliveryController`, `respond`/`screen`, r.219-245). Een ophaalrun of scheduler moet een intake-overload op een bestaande run kunnen vragen, en de orkestratie moet naar Service verhuizen (K-4b, zwaar).
>
> *Opgelost in K-4b:* `DeliveryIntakeService.registerInRun` (intake in een bestaande run) en `DeliveryReceptionService.screenIfCreated` (orkestratie in Service); zie §4.6.

> **Important technical constraint discovered** (K-4a, teruggeschreven in K-4b)
>
> `SshClient.setUpDefaultClient()` leest standaard `~/.ssh/config` van de gebruiker waaronder de applicatie draait. Een lokaal configbestand kan zo host, poort of een `ProxyJump` omleiden buiten de allowlist (L4b) om. Daarom is `client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY)` **verplicht** op elke client (`SftpConnector.newClient`); een nieuwe clientopbouw mag dat nooit weglaten.

> **Important technical constraint discovered** (K-4a, teruggeschreven in K-4b)
>
> Het wachtwoord wordt aan de sessie gegeven via een `PasswordIdentityProvider` (`session.setPasswordIdentityProvider(PasswordIdentityProvider.wrapPasswords(secret))`), **niet** via `session.addPasswordIdentity(secret)`: die laatste zou op DEBUG een digest van het wachtwoord kunnen loggen (in K-4a niet op de broncode geverifieerd). Het wachtwoord wordt bovendien pas ontsleuteld na een geslaagde hostsleutelcontrole (`PasswordSource`), zodat een mismatch nooit tot een aanmeldpoging of ontsleuteling leidt. Lekbewijs: `ConnectionTestHttpTest` en `FetchRunTest` (MINA-client op DEBUG, onze pakketten op TRACE).

> **Important technical constraint discovered** (K-4b)
>
> Een callback van de aanroeper die binnen de SFTP-sessie draait (keuze met databankquery, schrijven naar het archief), mag nooit door de generieke foutafhandeling van de connector als `CONNECTION_FAILED` vermomd worden: een lokale fout (volle schijf, databank) zou anders als leveranciersprobleem in de run staan. `SftpConnector` geeft zo'n fout daarom ongewijzigd door; de ophaalrun maakt er `FETCH_INTERNAL_ERROR` van (run `FAILED`, antwoord 500).

> **Important technical constraint discovered**
>
> Zonder host-binding kan een MANAGE-gebruiker een profiel met een eigen host en een bestaande `credentialRef` maken en een test starten: het wachtwoord gaat dan naar een eigen server en V1 (geen secret buiten de applicatie) is omzeild. Daarom `bound_host` op de credential en de samengestelde FK vanuit de profielversie (L4a).

> **Important technical constraint discovered**
>
> Scan, test en fetch laten de server verbinden met een door een gebruiker opgegeven host: dat is SSRF en poortscan. Vereist: fail-closed allowlist (`catalogimport.fetch.allowed-hosts`) en DNS eenmaal resolven, op dat IP verbinden (L4b).

> **Important technical constraint discovered**
>
> `uk_delivery_idempotency` is per taak (001 r.154). Hetzelfde bestand via een uploadtaak en een SFTP-taak geeft twee leveringen (mede daarom A10). Nooit een DC-versie-ID in de sleutel opnemen, anders haalt een nieuwe DC-versie hetzelfde bestand opnieuw op.

> **Important business rule discovered**
>
> Bij `LEAVE` haalt een eerste periodieke ophaling zonder vertrekpunt de hele historiek van de leverancier op (precedent `Prodis/docs/internal/sync/purchase-invoice-legacy-sync-analysis.md` r.416, niet in deze repo geverifieerd); oudste-eerst verwerken kan prijzen terugdraaien. Regel L6: eerste run neemt enkel het recentste bestand, daarna chronologisch met watermark. **Voorstel (nog niet doorgevoerd, wacht op akkoord van de mens):** terugschrijven naar BA §14.2 (r.1542) en §16.6 (r.4240).

> **Important technical constraint discovered**
>
> Een crashende ophaalrun laat de `task_run` op `RUNNING` zonder batch. `ScreeningRecoveryService` herstelt enkel via de batch (r.171-173: run naar `FAILED` als er een batch is). Zonder K-4c blokkeert de taak permanent op `TASK_RUN_IN_PROGRESS`.
>
> *Stand na K-4b:* elke fout die de JVM overleeft sluit de run (remote fout `FAILED` met code, interne fout `FAILED`/`FETCH_INTERNAL_ERROR`); enkel een procescrash of een databankfout bij het afsluiten zelf laat de run op `RUNNING`. Dat blijft K-4c.
>
> *Stand na K-4c (2026-09-30):* opgelost door `FetchRunRecoveryService`, met het publicatieprecedent (`PublicationRunService`, beslissingslog 2026-09-27) en `ScreeningRecoveryService` als voorbeeld.
> - **Vastgelopen** = `task_run` met `trigger_source` `MANUAL_FETCH` of `SCHEDULED_FETCH`, `RUNNING`, **zonder** levering (en dus zonder batch: levering, bestand en batch ontstaan in één transactie; een run mét batch is screeningwerk voor `ScreeningRecoveryService`), en `started_at` ouder dan `catalogimport.fetch.stuck-after` (default `PT60M`, zoals `publication-run.stuck-after`). Er is geen heartbeat (ook niet bij het precedent); geen nieuwe gebouwd. Een ongeldige of niet-positieve drempel laat de applicatie niet opstarten (`IllegalStateException`).
> - **Afbreken:** `POST /task-runs/{id}/abort` `{reason}` (`MANAGE`, niet achter de allowlist). Zoals bij de publicatierun **geen tijdsvoorwaarde**: elke `RUNNING` ophaalrun zonder levering mag op elk moment afgebroken worden. Resultaat 200 met de run: `FAILED`, `outcome_code = FETCH_MANUALLY_ABORTED`, `outcome_message = "Manually aborted by <naam>: <reden>"`, `finished_at`, concurrency-token vrij (taak weer vrij). Foutcodes: 400 `FETCH_ABORT_REASON_REQUIRED` (reden leeg; hoogstens 300 tekens, anders 400), 404 `TASK_RUN_NOT_FOUND`, 409 `FETCH_RUN_NOT_STUCK` (run niet `RUNNING`, geen ophaalrun, of met levering). Wie afbrak staat in `outcome_message` en in de log (geen kolom, geen migratie).
> - **Automatisch:** enkel bij het opstarten (`ScreeningRecoveryService`-patroon), achter `catalogimport.fetch.recovery-on-startup` (default aan): vastgelopen runs (met de drempel) worden `FAILED`/`FETCH_TIMED_OUT`. Het publicatieprecedent kent daarnaast een controle bij de eerstvolgende aanvraag; die is voor het ophalen **niet** gebouwd (niet gevraagd). Gevolg: een run die korter dan de drempel geleden crashte, blijft na een herstart tot de drempel verstreken is (volgende herstart) of tot iemand afbreekt op `TASK_RUN_IN_PROGRESS` staan.
> - **Race:** afbreken, herstel, `registerInRun` en het afsluiten van de ophaalrun (`close`, sinds K-4c ook onder het rijslot) nemen allemaal het rijslot op de taak en herlezen daarna de status. Een late afronding van een afgebroken run wordt geweigerd (`TASK_RUN_NOT_RUNNING`, archiefobject opgeruimd) of laat `FAILED` ongemoeid: nooit twee eindtoestanden, nooit een levering bij een afgebroken run.
> - **Bekende beperking, wees-archiefobject:** `DeliveryArchiveStore` kiest een willekeurig uuid-pad zonder run-id; een object dat gedownload maar niet geregistreerd werd (procescrash) is niet betrouwbaar aan zijn run te koppelen. Er wordt niets op basis van een heuristiek verwijderd; het object blijft onschuldig achter (zoals bij de upload). Een afbreking terwijl het proces nog leeft, ruimt het object wel op (de ophaalrun verwerpt de registratie).
> - **Beperking:** veronderstelt één applicatie-instantie (zoals de screeningherstel): zonder heartbeat is een run van een andere, nog werkende instantie niet te onderscheiden van een verweesde.

> **Important technical constraint discovered**
>
> credentials-design §5.1 (`ciphertext not null`) en §5.3 (wissen = `null`) spraken elkaar tegen. Opgelost: nullable kolommen met checks (T5, §3.1); het oude voorlopige model is opgeheven.

> **Important technical constraint discovered**
>
> Een bookmarkplaats `DELIVERY_FILE_SELECTION` vraagt later een verbreding van `ck_import_definition_bookmark_usage_place` (006 r.125-129). Nu is `bookmark_name` nullable en ongebruikt; de verbreding is dan additief.

## 12. Externe punten

Zie `docs/openstaande-externe-punten.md` punt 7 (en punt 6):

- DDA-infra: uitgaande firewall poort 22, vast uitgaand IP (leveranciers whitelisten), beheer van de allowlist-property, productiehosting.
- Eerste leverancier(s): host, poort, login, auth-methode, hostsleutelvingerafdruk via een apart kanaal, map en naamconventie, publicatietijdstip, bewaartermijn bij de leverancier, of verplaatsen/verwijderen ooit gewenst of toegestaan.
- Keuze van de pilotleverancier (VROOAM is het gedeelde-mapgeval; zonder `BESTANDS_PREFIX` enkel bruikbaar met recordfilters).

Deze punten blokkeren de bouwstappen in dev/test niet (embedded testserver), wel de eerste echte ophaling en de productie-inzet.

## 13. Onzekerheden

- ~~Of de Boot-BOM MINA SSHD beheert~~ (K-4a: nee, zie §8). Of ed25519 in de gekozen 2.x zonder BouncyCastle werkt: bewezen of weerlegd door `SftpConnectorTest.ed25519WorksWithTheJdkAloneWithoutBouncyCastle` (§8).
- Klokverschil bij `min_file_age_seconds` (de her-stat vangt het meeste op).
- Synchrone download plus screening van 1 GB binnen HTTP-time-outs (zelfde beperking als upload).
- Meerdere open batches per koppeling bestaan al en worden frequenter.
- A1, A2, A5, A8 en A14 zijn door de hoofdsessie gecontroleerd tegen het beslisdossier en in §1.2 gecorrigeerd.
- Prodis-verwijzingen (`purchase-invoice-merge-analysis.md` r.72, `purchase-invoice-legacy-sync-analysis.md` r.416) staan buiten deze repo en zijn niet opnieuw geverifieerd.
