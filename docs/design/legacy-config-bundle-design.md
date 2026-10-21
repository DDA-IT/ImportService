# Legacy-config-migratie — bundel v1, push, PREVIEW/APPLY, Prodis-leesbron en datasync-job

> **Status: VOORSTEL (2026-10-20), uitvoerbaar ontwerp van denker-zwaar.** Steunt op de mens-beslissingen van 2026-10-20 in
> `docs/decisions.md` ("focus, bron, secrets, definities" en "datasync-aanpak, Prodis-DB lezen, sjablonen"). Verfijnt
> `legacy-config-migratie-design.md` en vervangt `prodis-db-publicatie-en-datasync-design.md` §4 (envelopversleuteling en
> bestandstransport zijn door de mens vervangen door push over TLS). Open bij de mens: **M1–M3** (§9). S0 kan starten;
> S1 na M2; S6 na M3; S7 na M1. Stand van de bouw: zie §8.

## 0. Tegenstrijdigheden en gevolgen

| # | Botsing | Gevolg |
|---|---|---|
| T1 | Beslissing 2026-10-20 "één `REUSABLE_TEMPLATE` met per interface een koppeling" vs. changeset 006-5 (sjabloon kan nooit aan een `import_link` hangen; decisions 2026-09-23 keuze 7) en `sjabloon-materialisatie-design.md` §4 A4 (DRAFT-sjabloonrevisie niet materialiseerbaar). APPLY maakt enkel DRAFT. | Letterlijk niet uitvoerbaar → **M1** (geverifieerd door de hoofdsessie) |
| T2 | `prodis-db-…-design.md` §4 (envelop, bestand) vs. beslissing 2026-10-20 (leesbaar over TLS, push) | Beslissing geldt; dit document vervangt §4 |
| T3 | `fase5-auth-design` + decisions 2026-09-25: BFF, geen resource-server; Bearer enkel via latere aparte beslissing | 2026-10-20 ("eigen technische client") is die beslissing → JWT-resource-server op **één pad** (§3); invulling = M2 |
| T4 | Beslissing "enkel `legacy_master = PRODIS`" vs. `LegacyMasterSyncGuard.java` r.55 (weigert enkel `NONE`, laat `PROFAB` door) | De job controleert zelf op `PRODIS` (§7) |
| T5 | Interface → definitie: niet via `ImportDefinition` maar via INDET-label `ArtImp_Importsoort` (`Prg_1232` r.407 → r.518; `ImportDefinition` enkel in dode aanroep `if FALSE`, r.515) | Bundel gebruikt `ArtImp_Importsoort` (geverifieerd door de hoofdsessie) |
| T6 | V6 (secret bij herhaalde sync) niet beslist | Aanname A1 |

## 1. Bundelcontract v1 ("legacy-config-bundle", versie 1)

Eén bundel per interface. De job exporteert alle INHEAD-interfaces waarvan INDET `ArtImp_Importsoort` niet leeg is (A6).

```json
{
  "schema": "legacy-config-bundle",
  "version": 1,
  "origin": { "environment": "PROD", "dossier": "KLANT01", "legacyMaster": "PRODIS" },
  "exportedAt": "2026-10-21T10:15:00+02:00",
  "exporter": { "job": "LegacyConfigExportJob", "appVersion": "1.4.2" },
  "contentHash": "<sha256-hex>",
  "definitionContentHash": "<sha256-hex | null>",
  "interface": {
    "code": "KRAMP_ART",
    "header": { "direction": "I", "importExportType": "CSV", "executeAutomatic": true,
                "executeEvery": "D", "executionTime": "060000", "endTime": "000000",
                "weekdays": [true,true,true,true,true,false,false], "dayOfWeek": "",
                "dayOfMonth": 0, "monthOfYear": 0, "specificUser": "", "info": "…" },
    "details": [ { "sequence": 1, "label": "FTP", "value": "sftp.example.test" },
                 { "sequence": 2, "label": "FTP_login", "value": "klant01" },
                 { "sequence": 100, "label": "ArtImp_Importsoort", "value": "VROOAM2" } ],
    "omittedSecretLabels": ["API_AUTH_PASSWORD"],
    "ftpCredentialsRedacted": false
  },
  "definitionStatus": "PRESENT",
  "definition": {
    "code": "VROOAM2", "description": "VROOAM catalogus CSV",
    "updateFile": "", "replacementCode": "", "deleteCode": "",
    "lines": [ { "number": 9, "field": "Leverancier", "column": 3, "position": 0, "length": 0,
                 "includeExclude": "0", "includeExcludeData": "", "prefix": "", "suffix": "",
                 "value": "", "fill": "", "calculation": "", "calculationValue": "", "xmlElementPath": "" } ],
    "maps": [ { "number": 7, "key": "A", "value": "B", "reserveAlpha": "" } ],
    "excludes": [ { "supplier": "", "supplierGroup": "", "discountCode": "", "supplierReference": "" } ]
  },
  "secret": { "label": "FTP_password", "value": "<plaintext, enkel over TLS>" }
}
```

**Velden per legacy-tabel** (tekst `trim` van Magic-padding, getallen als integer):
- **INHEAD (DS 270):** enkel configuratie; nooit run-toestand/audit (`Date/Time/User last run`, `Running?`, `MaxRunTime`, `Reserve*`, `User/Date/Time Create/Update`).
- **INDET (DS 271):** `sequence`, `label`, `value`, zonder auditkolommen. `FTP_password` gaat enkel naar `secret`. Elk ander
  label dat matcht op `(?i)pass|secret|apikey|api_key|token|_pw$` gaat niet mee; enkel de labelnaam in `omittedSecretLabels`.
  Een `FTP`/`FTP2`-waarde met `://user:pass@` wordt zonder userinfo doorgegeven, met `ftpCredentialsRedacted=true`; sinds het
  S4-vervolg (2026-10-21) ook als de userinfo pas in de samengestelde waarde `Trim(FTP) & Trim(FTP2)` ontstaat (contract §2.1
  regel 3b/3c: elke regel houdt zijn eigen niet-verwijderde tekens). De ontvanger weigert een samengestelde waarde met userinfo
  (400 `SECRET_IN_DETAILS`).
- **PSIMDHFD / PSIMPDEF / PSIMPDEFMAP / PSIMPEXCL (DS 253/252/254/257):** zoals in het schema; `IMPORT`/`EXCL_Code` weggelaten
  (zit in `definition.code`); `maps` met alle `NUMMER`s.
- **`definitionStatus`:** `PRESENT` | `NOT_REFERENCED` (label leeg) | `MISSING` (code niet in PSIMDHFD) | `TABLE_UNAVAILABLE` (geen DDF).

**Herkomstsleutel:** `(origin.environment, origin.dossier, interface.code)`; environment en dossier uit de jobconfig.

**Inhoudshash**
- `contentHash` = SHA-256 (hex, kleine letters) over de canonieke JSON van `{interface, definitionStatus, definition}`;
  uitgesloten: `secret`, `exportedAt`, `exporter`, `origin`, beide hashvelden.
- `definitionContentHash` = SHA-256 over de canonieke `definition` zonder `code` en `description`; `null` als `definitionStatus ≠ PRESENT`.
- Canoniek: UTF-8, geen witruimte, sleutels lexicografisch; arrays gesorteerd (`details` op sequence+label, `lines` op number,
  `maps` op number+key, `excludes` op alle vier velden); minimale JSON-escaping; enkel integers.
- CatalogImport herberekent beide; afwijking → 400 `LEGACY_BUNDLE_HASH_MISMATCH`. Golden fixture (zonder echt secret) + verwachte
  hashes in beide repo's.

**Gedeelde definities** herkent CatalogImport (nooit de job): meerdere ontvangsten van dezelfde `(environment, dossier)` met
dezelfde `definition.code` of dezelfde `definitionContentHash`.

**Versiebeleid:** `version ≠ 1` → 400 `LEGACY_BUNDLE_VERSION_UNSUPPORTED`; velden toevoegen enkel met v2; onbekende velden in v1 → 400 (patroon C6).

## 2. CatalogImport-endpoints (`/api/catalog-import/legacy-config`)

| Methode | Pad | Wie | Gedrag |
|---|---|---|---|
| POST | `/receipts` | enkel technische client (§3) | vergeleken met de **laatste** ontvangst van dezelfde herkomst (ongeacht status): zelfde `contentHash` → 200 `UNCHANGED` met id en status van die rij, niets geschreven; anders 201 `RECEIVED` (nieuwe rij, ook bij A → B → A); een open ontvangst → `SUPERSEDED`, `APPLIED`/`DISCARDED` ongemoeid. Antwoord: contract §8 |
| GET | `/receipts?status=&interface=&page=` | MANAGE | `PageResult`, zonder details |
| GET | `/receipts/{id}` | MANAGE | Bundel zonder secret, met `secretOutcome` en `credentialRef` (MANAGE wegens host/login, L7b) |
| GET | `/receipts/{id}/preview?definitionCodeOverride=&charsetWhenUndeclared=&libraryCode=` | MANAGE | Rapport (§5) + `previewFingerprint`; schrijft niets. De optionele queryparameters zijn dezelfde keuzes als in de APPLY-body (S2): het rapport en dus de fingerprint hangen ervan af |
| POST | `/receipts/{id}/apply` | MANAGE | `{previewFingerprint, reason, definitionCodeOverride?, charsetWhenUndeclared?, libraryCode?}` → 200 + `{report, receiptStatus, configurationFindings}`. `libraryCode` is additief (beslissingslog 2026-10-20 "Legacy-config S2", V1 = c): tot S5 door de mens gegeven, nooit het ruwe `ArtImp_BibNr` |
| POST | `/receipts/{id}/discard` | MANAGE | `{reason}` → `DISCARDED` (latere slice) |

Niet achter `catalogimport.setup-api.enabled` (precedent L7a). 403 vóór 400/404/409 (5-PERM V1).

**Foutcodes:**
- 400 `LEGACY_BUNDLE_INVALID` (JSON-paden, nooit waarden), `LEGACY_BUNDLE_VERSION_UNSUPPORTED`, `LEGACY_BUNDLE_HASH_MISMATCH`, `LEGACY_APPLY_REASON_REQUIRED`, `LEGACY_APPLY_REASON_TOO_LONG` (S2, > 500 tekens)
- 413 `LEGACY_BUNDLE_TOO_LARGE` (`catalogimport.legacy-config.max-bundle-bytes`, default 2 MB)
- 404 `LEGACY_RECEIPT_NOT_FOUND`
- 409 `SECRETS_NOT_CONFIGURED` (bundel met secret zonder sleutelring: hele push geweigerd; sinds S4, vervangt de S1-code 400
  `LEGACY_BUNDLE_SECRET_NOT_SUPPORTED`), `LEGACY_RECEIPT_NOT_APPLICABLE`, `LEGACY_PREVIEW_STALE`, `LEGACY_APPLY_BLOCKED` (met blokkerende codes), `PRODIS_DB_NOT_CONFIGURED`
- 503 `PRODIS_DB_UNAVAILABLE`

**Idempotentie (beslissingslog 2026-10-20 "Legacy-config S1: idempotentie tegenover de laatste ontvangst"):** een push wordt enkel
vergeleken met de laatste ontvangst (`max(id)`) van dezelfde herkomst, ongeacht status; zelfde hash → 200 `UNCHANGED`, anders altijd een
nieuwe rij. Invariant: de laatste ontvangst is nooit `SUPERSEDED`. Serialisatiepunt is enkel `uk_legacy_config_receipt_open`: eerst de
open ontvangst met rijslot lezen, dan de laatste ontvangst; een race op de open-marker → hele transactie opnieuw (begrensd). APPLY: rijslot + statusguard; tweede APPLY 409; verouderde of ontbrekende `previewFingerprint` → 409 `LEGACY_PREVIEW_STALE`.

**Secret door de request (K-3-patroon, decisions 2026-09-29 afwijking 3):**
1. Body zelf lezen (`HttpServletRequest` → `byte[]` met limiet), nooit `@RequestBody`.
2. Eigen `ObjectMapper` met `INCLUDE_SOURCE_IN_LOCATION` uit; parsefout → `LEGACY_BUNDLE_INVALID` met enkel JSON-pad.
3. Record `LegacySecret(label, value)` met `toString()` → `[REDACTED]`; enkel als parameter naar `CredentialService`, nooit in een entiteit.
4. `bundle_json` opnieuw geserialiseerd zonder `secret`; nooit de ruwe body bewaren.
5. `byte[]` na parsen overschrijven (`Arrays.fill`).
6. Controleren dat geen request-loggingfilter actief is; `logServerErrorDetail=false` geldt al.

**Credential bij ontvangst:**
- Nog geen credential voor de herkomst, protocol SFTP, geldige host → `CredentialService.create(...)` met `SFTP_PASSWORD`,
  `boundHost` uit `FTP`+`FTP2`, label `Legacy <dossier>/<interface>`, reden `Overgenomen uit legacy INDET <interface> (ontvangst <id>)`,
  nieuwe additieve overload met eventbron SYSTEM (`changed_by` null), `created_by = svc:<azp>` → `secretOutcome=STORED`.
- Credential bestaat al → `DISCARDED_ALREADY_PRESENT` (niets ontsleutelen of vergelijken; vervangen = menselijke PUT).
- FTP/onbekend protocol → `DISCARDED_PROTOCOL_UNSUPPORTED`; geen/ongeldige host → `DISCARDED_NO_HOST`.
- UNCHANGED (S4-vervolg 2026-10-21, verfijnt A1): dezelfde regels; zie "Uitwerking S4-vervolg" hieronder.

**Uitwerking S4 (gebouwd 2026-10-20):**
- Volgorde: (1) bundel met secret en geen sleutelring → 409 `SECRETS_NOT_CONFIGURED`, niets bewaard (ook bij `UNCHANGED`);
  (2) `UNCHANGED` → secret weggegooid, niets geschreven (sinds het S4-vervolg: zie hieronder); (3) herkomst (`legacy_config_origin`, rijslot) heeft al een credential,
  ongeacht status (ook `REVOKED`) → `DISCARDED_ALREADY_PRESENT`; (4) `FTP`+`FTP2` leeg → `DISCARDED_NO_HOST`; (5) URL-schema niet
  `sftp` (ook geen schema) → `DISCARDED_PROTOCOL_UNSUPPORTED`; (6) geen geldige host → `DISCARDED_NO_HOST`; anders `STORED`.
- Bestemming = `Trim(FTP) & Trim(FTP2)` (`Prg_1232` r.300), ontleed door `service.support.LegacyFtpLocation`: host zonder poort,
  genormaliseerd met `HostNames`; ongeldig bij lege authority, `@`, poort buiten 1-65535, haken die geen IPv6-literal zijn
  (bv. `[YYYY]`), meer dan één `:` buiten haken, of een dubbel `FTP`/`FTP2`-label. Het `SFTP`-label wordt niet gelezen (A7).
- `credential_id` staat enkel bij `STORED` op de ontvangst (de credential die uit díe ontvangst ontstond) en altijd op de herkomst;
  de herkomstrij ontstaat hier als ze nog niet bestond. Ontvangst, credential, event en herkomst zitten in één transactie; een
  gelijktijdige eerste insert van de herkomst (`uk_legacy_config_origin`) wordt net als `uk_legacy_config_receipt_open` herhaald.
- `secret.value` hoogstens 1024 tekens (grens van `CredentialService`), al bij het parsen getoetst (contract §1).

**Uitwerking S4-vervolg (gebouwd 2026-10-21, beslissingslog "Legacy-config S4-vervolg"):**
- **Secret bij `UNCHANGED`:** stap (2) hierboven vervalt; een `UNCHANGED`-push met secret doorloopt (3)-(6) onder hetzelfde rijslot
  (`findForUpdate` op de herkomst, na het slot op de open ontvangst). `STORED` → nieuwe credential (eventbron SYSTEM, reden
  `Overgenomen uit legacy INDET <interface> (ontvangst <id>, herhaalde push)`), `credential_id` op de herkomst (die zo nodig ontstaat)
  en op de **laatste ontvangst** (de ontvangst die `UNCHANGED` teruggaf): `secret_received = true`, `secret_outcome = STORED`,
  `credential_id`, tenzij die ontvangst al `STORED` is (dan enkel de herkomst). Status en andere velden van de ontvangst blijven
  ongemoeid (ook `APPLIED`). Elke `DISCARDED_*`-uitkomst bij `UNCHANGED` wordt enkel gelogd, niets geschreven (de ontvangst houdt haar
  eerdere uitkomst). Antwoord blijft 200 `UNCHANGED`. Een bestaande credential wordt nooit vervangen (A1). Gelijktijdige
  `UNCHANGED`-pushes: het slot op de open ontvangst of de herkomst serialiseert; anders botst de tweede op `uk_legacy_config_origin`
  en wordt herhaald (ziet dan `DISCARDED_ALREADY_PRESENT`). `secret_received` is daarvoor in JPA wijzigbaar gemaakt (enkel via
  `LegacyConfigReceipt.recordCredentialFromRepeatedPush`); geen Liquibase (de checks `ck_legacy_config_receipt_secret_outcome` en
  `ck_legacy_config_receipt_credential` gelden ongewijzigd).
- **Userinfo over `FTP`+`FTP2`:** contract §2.1 regel 3b/3c/4. `LegacyBundleParser` weigert elke combinatie (`FTP`, `FTP2`) waarvan
  `Trim(f)+Trim(g)` userinfo heeft (400 `SECRET_IN_DETAILS`, pad = de `FTP2`-waarde). Daardoor bereikt een samengestelde userinfo
  nooit `bundle_json`; `LegacyFtpLocation` blijft een `@` in de authority als ongeldige host behandelen (vangnet).
- PREVIEW/APPLY: `planned.credential = {credentialRef (herkomst), secretOutcome (deze ontvangst)}` of `null`; APPLY koppelt geen
  profiel (L5); het item `CONNECTION_NOT_MIGRATED` toont protocol/host/poort/map/`FTP_directory`/login + `credentialRef`
  (`target = external_credential.credentialRef=<uuid>`). Geen Liquibase nodig (020 had alle kolommen en checks al).

## 3. Authenticatie technische client

- **CatalogImport (Web):** tweede `SecurityFilterChain` `@Order(1)`, `securityMatcher(POST /api/catalog-import/legacy-config/receipts)`,
  `oauth2ResourceServer(jwt)` met de issuer van de bestaande realm, stateless, CSRF uit enkel voor deze matcher; nieuwe dependency
  `spring-boot-starter-oauth2-resource-server`.
- **Validator:** `aud` bevat `catalogimport.technical-clients.legacy-config.audience`; `azp` = `….client-id`; client role
  `….required-role` (voorstel `legacy-config-push`) in `resource_access.<audience>.roles`.
- **Afscherming:** de BFF-chain bedient dit pad nooit (sessie met MANAGE → 401); het token werkt op geen ander pad; geen
  `ConfiguredPermissionSource`, geen Prodis-relay; nooit `hasDdaProdisApiBypass()`. Annotatie `@TechnicalClientOnly("legacy-config-push")`
  + uitbreiding `PermissionCoverageTest`. `ActorIdentity("svc:"+azp, sub)`; `system` blijft verboden.
- **Bij Prodis/infra aan te vragen** (nieuw extern punt; vervangt "geen service-account" van punt 1): confidential client
  `prodis-datasync-catalogimport` (service account aan, standard flow uit); client role `legacy-config-push` op de CatalogImport-client,
  toegekend aan dat service account; audience-mapper; secret in de omgeving van de datasync; lokaal uitbreiding `prodis-realm.json`
  (Prodis-repo, niet door ons gecommit; lokale audience `web_app`).

## 4. Datamodel (Liquibase, additief, in Web)

**020-legacy-config-migration.sql**
- **020-1 `legacy_config_receipt`:** id; `origin_environment`, `origin_dossier` (varchar 40); `interface_code` (varchar 20);
  `bundle_version` int; `content_hash`, `definition_content_hash` (varchar 64); `legacy_definition_code` (varchar 10) null;
  `bundle_json` text (zonder secret); `secret_received` bool; `secret_outcome` varchar 40 + check; `credential_id` FK null;
  `status` (RECEIVED | APPLIED | SUPERSEDED | DISCARDED) + check; `open_marker` (TRUE enkel bij RECEIVED); `exported_at`;
  `received_at/by/by_subject`; `superseded_by_receipt_id` FK; `applied_at/by/by_subject`; `apply_report_json` text; `discarded_*`.
  Uniek: enkel `(env, dossier, interface_code, open_marker)`; `content_hash` is per herkomst bewust **niet** uniek (A → B → A = nieuwe
  ontvangst). Niet-unieke index `(env, dossier, interface_code, id desc)` voor de laatste ontvangst.
- **020-2 `legacy_config_origin`:** `(env, dossier, interface_code)` uniek; `import_link_id`, `catalog_import_task_id`,
  `credential_id`, `last_applied_receipt_id` (FK's, null).

**021-legacy-config-definition-origin.sql** (S2; changesets heten 020-3/020-4 zoals hieronder, maar staan in een nieuw bestand omdat
020 al gecommit was; beslissingslog 2026-10-20 "Legacy-config S2")
- **020-3 `legacy_config_definition_origin`:** env, dossier, `definition_content_hash`, `supplier_code`, `legacy_definition_codes`
  (tekst, voor het rapport; sinds S7 de gesorteerde, kommagescheiden codes van de leden); `template_definition_id` FK null (sinds S7
  altijd gevuld bij een nieuwe groep: het sjabloon), `import_definition_id` FK (sinds S7: de gedeelde definitie); uniek `(env, dossier, supplier_code, definition_content_hash)`;
  `created_at`/`updated_at`. Een definitie met zo'n rij is "gemigreerd"; zonder is ze handmatig en nooit migratiedoel. Bij een gewijzigde
  legacy-definitie wordt de hash ter plaatse bijgewerkt (historiek in 020-4).
- **020-4 `legacy_config_revision_origin`:** `revision_id` uniek FK, `receipt_id` FK, `definition_content_hash` ("kwam de laatste revisie uit de migratie"),
  `migrated_content_hash` (SHA-256 over wat APPLY op de revisie zette: door legacy bepaalde velden, alle mappings, alle filters; basis voor
  `DEFINITION_UNCHANGED` en `MANUAL_CHANGES_SINCE_MIGRATION`), `created_at`.
- **021-1:** `legacy_config_receipt.applied_reason` varchar(500), nullable (de reden van APPLY; ook in `apply_report_json` en `change_reason`).

**022-identity-fixed-and-uppercase.sql** (S6, hangt af van M3; was 021; **gebouwd 2026-10-21**): op `import_definition_revision`
`identity_supplier_fixed_value` varchar(50) null, `identity_supplier_group_fixed_value` varchar(50) null,
`identity_uppercase` boolean not null default false; `drop not null` op `identity_supplier_field` en
`identity_supplier_group_field`; check per deel "precies één van kolom of vaste waarde". Bestaande rijen voldoen;
rollback enkel zolang geen rij een vaste waarde heeft.
- Uitwerking: changeset **022-1** (de drie kolommen + `ck_import_definition_revision_supplier_fixed_not_blank` /
  `_group_fixed_not_blank`: een vaste waarde is nooit blanco) en **022-2** (`drop not null` +
  `ck_import_definition_revision_supplier_source` / `_group_source`). De rollback van 022-2 zet `not null` terug en faalt dus
  bewust zodra een rij een vaste waarde heeft. **Gemelde constraintwijziging (M3):** de twee kolommen zijn niet meer
  `not null`; de entiteit (`ImportDefinitionRevision`) volgt.
- Bewaard wordt de businesscode zoals gegeven (setup) of zoals legacy ze heeft (migratie, bv. `grp-a`); de
  hoofdletteromzetting gebeurt bij de screening. Geen Prodis-id, geen cross-DB-FK.

**023-field-value-map.sql** (S8a; was 022; **gebouwd 2026-10-21**, `legacy-config-s8-design.md` par. 2a) changeset **023-1**
`import_field_value_map`: id; `mapping_id` FK → `import_field_mapping` (geen cascade: `SetupService.deleteMapping` verwijdert
eerst de rijen); `sequence_number` int > 0; `source_value` varchar(50) not null en niet blanco; `target_value` varchar(50) not
null (`''` toegelaten); `created_at`/`created_by`/`created_by_subject` met koppelcheck zoals 007-4; uniek
`(mapping_id, source_value)` en `(mapping_id, sequence_number)`. Additief: **naast** `values=` in `transform_config` (dat blijft
werken), niet in de plaats ervan; een MAP gebruikt precies één van beide (`CONFIG_TRANSFORM_INVALID` anders). De
hoofdlettergevoeligheid blijft de instelling `caseSensitive` van de mapping.

**024-field-transform-step.sql** (S8c; **gebouwd 2026-10-21**, `legacy-config-s8-design.md` par. 2c) changeset **024-1**
`import_field_transform_step`: id; `mapping_id` FK → `import_field_mapping` (geen cascade: `SetupService.deleteMapping`
verwijdert eerst de stappen, als entiteit); `step_number` int `between 1 and 3`; `transform_kind` varchar(30) not null en
`<> 'CHAIN'`; `transform_config` varchar(1000); `created_at`/`created_by`/`created_by_subject` met koppelcheck zoals 007-4;
uniek `(mapping_id, step_number)`. Additief: enkel een mapping met de nieuwe soort `CHAIN` heeft stappen; die mapping draagt
in `transform_config` enkel nog de notatie. De gesloten volgorde `[SUBSTRING] → [AFFIX|PREFIX|SUFFIX] → [UPPER|MAP|ADD|
SUBTRACT|MULTIPLY|DIVIDE|PERCENTAGE|SPLIT]` bewaakt de configuratievalidatie (`CONFIG_TRANSFORM_INVALID`), niet het schema.
De vertaalrijen van een MAP-stap blijven in `import_field_value_map`, aan de mapping.

## 5. Vertaalregels bundel → CatalogImport

| Bundel | Doel (code) | Status |
|---|---|---|
| `definition.code` / `description` | `ImportDefinition(code, description)` `OWN_DEFINITION` (sinds S7 de gedeelde definitie, `based_on` het sjabloon `<code>-SJ`); codeconflict met een andere definitie → `DEFINITION_CODE_CONFLICT_MANUAL`, met de sjablooncode → `TEMPLATE_CODE_CONFLICT_MANUAL` (beide blokkeren, tenzij `definitionCodeOverride`, sinds S7 hoogstens 47 tekens) | B |
| `updateFile` / `replacementCode` / `deleteCode` | rapport `LEGACY_CONTROL_CODE_NOT_MIGRATED` | N |
| INDET `Supplier` | `SourceOrganisation` find-or-create (`SUPPLIER`, code = `Supplier.number`); bron van de definitie en `import_link.supplier_organisation`. S5 (Prodis geconfigureerd en bereikbaar): exact één `supplier.number` vereist, anders `PRODIS_SUPPLIER_NOT_FOUND` / `PRODIS_SUPPLIER_AMBIGUOUS` (blokkeert; `number` is in Prodis niet uniek); een **nieuwe** organisatie krijgt `supplier.name` (leeg → plaatshouder + WARNING `PRODIS_SUPPLIER_NAME_EMPTY`; > 200 tekens → `PRODIS_SUPPLIER_NAME_TOO_LONG`, nooit afgekapt), een bestaande wordt nooit hernoemd (`SOURCE_ORGANISATION_NAME_KEPT`). Zonder of met onbereikbare Prodis: WARNING `PRODIS_REFERENCE_NOT_VERIFIED`, plaatshouder "Leverancier &lt;nr&gt;" | B (S5 gebouwd) |
| `ArtImp_BibNr` | `import_link.library_code` = `supplier_catalog.scope_key` (A3); nooit het ruwe `ArtImp_BibNr`; label afwezig → `LIBRARY_NOT_DECLARED` (blokkeert). S5 (Prodis geverifieerd, nieuwe koppeling): zonder `libraryCode` de `scope_key` als precies één **`scope_key`** van die leverancier een rij heeft met `legacy_number` = `ArtImp_BibNr` (exact, getrimd; historiekrijen van dezelfde `scope_key` tellen als één); 0 of > 1 → `LIBRARY_CODE_NOT_RESOLVED` (blokkeert) met de kandidaten (bij 0: alle bibliotheken van de leverancier) in `planned.link.libraryCandidates` en in de boodschap; een gegeven `libraryCode` moet een `scope_key` van die leverancier zijn, anders `LIBRARY_CODE_NOT_IN_PRODIS` (blokkeert, met kandidaten); een andere bibliotheek dan de legacy-match → WARNING `LIBRARY_CODE_NOT_LEGACY_MATCH`. Prodis niet geconfigureerd of onbereikbaar: gedrag S2 (`libraryCode` uit de body + WARNING `PRODIS_REFERENCE_NOT_VERIFIED`; zonder → `LIBRARY_CODE_NOT_RESOLVED`) | B (S5 gebouwd) |
| `Separator` Comma/Semicolon/Tab | `structure_delimiter` `,` / `;` / TAB (`Prg_1232` r.1188-1192); afwezig/leeg → `;` met WARNING `SEPARATOR_NOT_DECLARED` (`Prg_1232` r.1194); anders of XML → `STRUCTURE_FORMAT_UNSUPPORTED` (blokkeert) | B |
| `FILE_ENCODING` | `structure_charset`; leeg → `CHARSET_NOT_DECLARED` (blokkeert, tenzij `charsetWhenUndeclared`) — nooit raden | B |
| `SkipHeaderLines` n | n=0 → geen header; n≥1 → `has_header`, `header_line_number`=n; altijd `COLUMN_INDEX` | B |
| `DecimalComma`=Y | `decimalSeparator=,` op numerieke mappings; werking voor basisprijs te verifiëren (`ImportValueRules.decimal`) | B / verifiëren |
| PSIMPDEF 9/10/11 met `column` | `identity_*_field` = `column` | B |
| 9/10 met `value` | `identity_*_fixed_value` (item `IDENTITY_FIXED_VALUE`), gevalideerd tegen Prodis-DB in hoofdletters: leverancier precies één `supplier.number` (`PRODIS_SUPPLIER_NOT_FOUND`/`_AMBIGUOUS` op `PSIMPDEF#9`), groep een `supplier_group.group_name` van de leverancier van de identiteit (vaste leverancier, anders INDET `Supplier`; `PRODIS_SUPPLIER_GROUP_NOT_FOUND`), beide blokkerend; niet geconfigureerd of onbereikbaar → WARNING `PRODIS_REFERENCE_NOT_VERIFIED` (onbereikbaar: "unverified"-fingerprint, zoals S5). Kolom/transformatie op die regel worden genegeerd zoals in legacy (`LEGACY_IGNORED_WITH_FIXED_VALUE`); > 50 tekens → `IDENTITY_FIXED_VALUE_TOO_LONG`. Vaste waarde op 11/23 blijft `IDENTITY_FIXED_VALUE_UNSUPPORTED` | B (S6 gebouwd) |
| altijd (legacy `Upper()`) | `identity_uppercase=true` op gemigreerde revisies (item `IDENTITY_UPPERCASE`): leverancier, groep en referentie, niet de kortingscode (zoals legacy; beslissingslog 2026-10-21) | B (S6 gebouwd) |
| 23 | `identity_discount_code_field` + `FOUR_PART`, enkel als `ArtImp_Key_Korting=Y` | B |
| `ArtImp_Key_LevGrp`=N of `Key_Barcode`=Y | `IDENTITY_PROFILE_UNSUPPORTED` (blokkeert; decisions 2026-09-18) | N |
| 26 / 4 | `record_base_price_field` / `record_description_field`; prefix/suffix/CALC op 26 → `BASE_PRICE_TRANSFORM_UNSUPPORTED` (blokkeert) | B |
| 33-37, 40, 43, 12, 219, 221 | mapping met de catalogcode uit `legacy-config-migratie-design.md` §1. S8c: elke regel met minstens één bewerking wordt `CHAIN` (mapping-`transform_config` = enkel de notatie) met de stappen in legacy-volgorde (`Prg_1170` r.730-732): Positie/Lengte → `SUBSTRING` `start=pos (0→1);length=len (0→500)` (op de ruwe waarde), prefix/suffix (getrimd) → `AFFIX` `prefix=..;suffix=..` (lege waarde blijft leeg), CALC 1-4/A/7/15 → één waardebewerking (`ADD`…`DIVIDE`, `PERCENTAGE`, `MAP` `caseSensitive=true`, `UPPER`). `TRANSFORM_CHAIN_UNSUPPORTED` verdwijnt; prefix/suffix met `;` blijft `TRANSFORM_VALUE_NOT_EXPRESSIBLE`. Vaste `Waarde` → `FIXED_VALUE`; Positie/Lengte, prefix/suffix en CALC ernaast → `LEGACY_IGNORED_WITH_FIXED_VALUE` (zoals legacy/S6). Sjabloon en gedeelde definitie (S7) dragen dezelfde stappen | B (S8c gebouwd) |
| `position`/`length` ≠ 0 op 9-11/26, CALC 8/11/12/13/14 | rapport per regel (`LEGACY_CALC_<n>_NOT_MIGRATED`); op identiteit/basisprijs blijft Positie/Lengte blokkeren; negatieve Positie/Lengte: WARNING `LEGACY_SUBSTRING_INVALID` (verdedigend; het bundelcontract weigert ze al bij ontvangst, `RANGE`) | N / U later |
| CALC 7 + maps | S8a: de vertaalrijen in `import_field_value_map` (aan de mapping): vereniging van alle NUMMERs, volgorde (NUMMER, KEY), sleutel en waarde getrimd, lege sleutel valt weg, laagste NUMMER wint (WARNING `MAP_KEY_AMBIGUOUS`); sleutel of waarde > 50 tekens → WARNING `MAP_ENTRY_TOO_LONG` (nooit afgekapt, mapping vervalt); geen sleutels → `MAP_EMPTY`; altijd WARNING `MAP_MISS_NO_FALLBACK` (tot V1). `MAP_TOO_LARGE` en `TRANSFORM_VALUE_NOT_EXPRESSIBLE` (maps) verdwijnen. Sinds S8c staat `caseSensitive=true` op de MAP-stap van de `CHAIN` (niet meer op de mapping); de opzoeking gebeurt op de waarde mét prefix/suffix, zoals legacy. Sjabloon en gedeelde definitie (S7) dragen dezelfde rijen (`RevisionCopier.copyMappingChildren`) | B (S8a/S8c gebouwd) |
| Include/Exclude 1 | `import_record_filter` INCLUDE/EQUALS; andere codes in rapport | B |
| `excludes` | rapport (S8) | U |
| INHEAD-planning | rapport; taak `MANUAL` "Legacy <interface>" | N |
| `FTP`/`FTP2`/`FTP_login`/`FTP_directory`/`FTP_filename` | geen profiel of DC (L5: hostsleutel verplicht); rapport met voorstel host/poort/map/login/conditie; `[YYYY]`-patronen → rapport | rapport |
| `ArtImp_Para_Koers` ∉ {"", 0, 1} | `CURRENCY_RATE_NOT_SUPPORTED` (blokkeert) | later (Q7) |
| overige ArtImp_/MS_/BIB_/Email_/RFtp_ | rapport `PUBLICATION_PARAMETER_NOT_MIGRATED` | N |

**APPLY (één transactie):** source organisation → definitie (nieuw of opvolgende DRAFT) → koppeling → `CatalogImportTask(MANUAL)`
→ herkomstrijen; daarna `SourceStructureConfigFactory.collect` + `ImportMappingConfigFactory` als rapport (geen worp: DRAFT mag
onvolledig zijn). APPLY activeert nooit.

**Gewijzigde legacy-definitie:** ACTIVE nooit aangeraakt; open DRAFT → `OPEN_DRAFT_EXISTS` (blokkeert); anders opvolgende DRAFT
(`based_on` = laatste revisie) via de bestaande `RevisionCopier`/opvolgerroute. Uitwerking S2 (beslissingslog 2026-10-20 "Legacy-config S2",
V2 = C): levert de ontvangst exact de inhoud die de laatste migratie schreef (`migrated_content_hash`, 020-4) → `DEFINITION_UNCHANGED`,
geen revisie, ontvangst wel `APPLIED`; wijkt de laatste revisie inhoudelijk (velden, mappings, filters) af van wat de laatste migratie
schreef → `MANUAL_CHANGES_SINCE_MIGRATION` (blokkeert); anders letterlijke kopie, waarna APPLY structuur, identiteit, basisprijs,
omschrijving, de vertaalbare mappings en de filters overschrijft; drempels, kritiek-overrules en bookmarks blijven uit de kopie. APPLY
geldt als R-REV-X3-bevestiging (WARNING `IDENTITY_CHANGE`).

**Koppeling/taak bestaat al:** nooit muteren; afwijking → `LINK_DIFFERS_NOT_CHANGED`.
**Conflict met handmatige configuratie:** `uk_import_link_scope` → `LINK_SCOPE_CONFLICT_MANUAL` (blokkeert); nooit stil samenvoegen;
een handmatige definitie wordt nooit migratiedoel.

**Dedup (na M1):** groepsleutel `(env, dossier, Supplier, definitionContentHash)`; nooit over leveranciers heen (bronoverstijgend
sjabloon buiten scope, 2026-09-23 keuze 4). Gedeelde definitie die in één interface wijzigt → `SHARED_DEFINITION_DIVERGED`
(blokkeert; loskoppelen latere slice). Rapport toont `sharedWith`.

**Uitwerking S7 (gebouwd 2026-10-21; beslissingslog "Legacy-config S7: gedeelde definities", A2):**
- **Altijd sjabloon + gedeelde definitie**, ook bij één interface: APPLY van de eerste interface van een groep maakt een
  `REUSABLE_TEMPLATE` `<code>-SJ` (revisie 1 DRAFT met de gemigreerde inhoud, via `SetupService` zoals S2; de mens activeert) en de
  gedeelde `OWN_DEFINITION` `<code>` met `based_on_definition_id` = het sjabloon en revisie 1 als kopie via `RevisionCopier`
  (`based_on_revision_id` = de sjabloonrevisie). De materialisatieroute past niet (weigert een DRAFT-sjabloon, A4); door
  `based_on_revision_id` herkent `TemplateMaterialisationService.resolveReuse` de gedeelde definitie wél zodra het sjabloon
  ACTIVE is (`REUSE_DEFINITION`). Beide revisies worden na het schrijven tegen de geplande inhoudshash gecontroleerd. 020-3 krijgt
  beide id's; 020-4 enkel de revisie van de gedeelde definitie. Het sjabloon krijgt nooit een koppeling (006-5, getest).
- **Groep herkennen (nieuwe interface):** eerst 020-3 op `(env, dossier, Supplier, definitionContentHash)`, anders een 020-3-rij
  van dezelfde leverancier waarvan `legacy_definition_codes` de legacy-code bevat. Op hash gevonden en de geplande inhoud
  (`LegacyRevisionContent`, dus ook de INDET-instellingen structuur/identiteit) = de `migrated_content_hash` van de laatst gemigreerde
  revisie → enkel koppeling + taak (`planned.definition.action=REUSE`, `revisionAction=UNCHANGED`, item `SHARED_DEFINITION`);
  andere geplande inhoud of enkel op code gevonden → `SHARED_DEFINITION_DIVERGED`. `definitionCodeOverride` wordt dan niet gebruikt
  (WARNING `DEFINITION_CODE_OVERRIDE_NOT_USED`).
- **Latere wijziging (bekende interface, (i)):** zijn er andere leden (herkomsten met een koppeling op de gedeelde definitie), dan
  volgt de gedeelde definitie de S2-regels enkel als élk ander lid in zijn **laatste** ontvangst (ongeacht status) dezelfde nieuwe
  `definitionContentHash` heeft; anders, of als enkel de interface-instellingen van dit lid wijzigden (zelfde
  `definitionContentHash`, andere geplande inhoud), `SHARED_DEFINITION_DIVERGED`. Het sjabloon blijft ongemoeid (NOT_MIGRATED
  `TEMPLATE_NOT_UPDATED`); de 020-3-hash schuift ter plaatse mee; leden die daarna komen krijgen `DEFINITION_UNCHANGED`. Heeft de
  nieuwe inhoud al een **andere** groep van dezelfde leverancier → `SHARED_DEFINITION_DIVERGED` (een koppeling verhuist nooit; de
  020-3-sleutel is bezet). Zonder andere leden: exact S2.
- **Zelfde bibliotheek:** een nieuwe koppeling op de gedeelde definitie met dezelfde leverancier en `libraryCode` als een bestaande
  koppeling → `LINK_SCOPE_CONFLICT_MANUAL` al in de preview (`uk_import_link_scope`).
- **`sharedWith`:** de andere leden plus de interfaces waarvan de laatste ontvangst (niet `DISCARDED`) in dezelfde omgeving/dossier
  dezelfde `definitionContentHash` en (uit `bundle_json`) dezelfde `Supplier` heeft; gesorteerd. Staat in het rapport en dus in de
  `previewFingerprint`. Nieuw rapportveld (additief) `planned.template{action CREATE|REUSE|NONE, id, code, revisionId}`; `NONE` = een
  vóór S7 gemigreerde definitie zonder sjabloon (testdata, geen datamigratie).
- **Gelijktijdig:** twee leden van een nieuwe groep tegelijk → de unieke codes (`uk_source_organisation_code`,
  `uk_import_definition_code`, `uk_legacy_config_definition_origin`) laten er één winnen; de ander krijgt 409
  (`SOURCE_ORGANISATION_CODE_IN_USE`/`DEFINITION_CODE_IN_USE`), schrijft niets en sluit na een nieuwe preview aan.
- `SHARED_DEFINITION_NOT_SUPPORTED` vervalt. Loskoppelen tot een eigen definitie: niet in S7 (eigen ontwerp later).

**Configuratiehash:** nieuwe revisiekolommen tellen in `RevisionConfigHashes` enkel mee als ze niet de default hebben; bestaande
hashes blijven byte-identiek (byte-vergelijkingstest).

**Uitwerking S6 (gebouwd 2026-10-21; beslissingslog M3 en "datasync-aanpak ... optie iii"):**
- **Hashes.** `RevisionConfigHashes` (laag `record`) en `LegacyRevisionContent` voegen achteraan
  `identitySupplierFixedValue=<v>`, `identitySupplierGroupFixedValue=<v>`, `identityUppercase=true` toe, **enkel** als niet
  default. Bewezen met letterlijke pre-S6-hashes (`RevisionConfigHashesParityTest`, `LegacyRevisionContentParityTest`). Gevolg
  voor een vóór S6 gemigreerde definitie: dezelfde legacy-inhoud plant nu `identity_uppercase=true`, dus een opvolgende DRAFT met
  `IDENTITY_CHANGE` (nooit `MANUAL_CHANGES_SINCE_MIGRATION`, want de `migrated_content_hash` blijft geldig). `previewFingerprint`
  bevat de gelezen Prodis-leveranciers (`prodisFixedSupplier:`) en -groepen (`prodisGroup:`).
- **Screening.** `SourceStructureConfigFactory` (S9): per deel precies één van kolom of vaste waarde; beide →
  `CONFIG_IDENTITY_SOURCE_CONFLICT` (nieuw, ook in `ImportIssueCatalog`), geen → `CONFIG_IDENTITY_FIELD_MISSING`. Een vaste waarde
  declareert geen kolom (header, kolomindexcontrole, trial-read). `CandidateNormaliser` gebruikt de vaste waarde voor elke regel en
  zet met `identity_uppercase` leverancier, groep en referentie (ook een vaste waarde) in hoofdletters (`Locale.ROOT`, lengtecontrole erna)
  vóór de canonicalisatie; ook de gestagede waarden. Geen canonicalisatieversie 3: zonder S6-velden byte-identiek.
- **Setup-API (additief; gemelde betekeniswijziging `supplierField`).** `CreateRevisionCommand`/`UpdateRevisionCommand`:
  `supplierFixedValue`, `supplierGroupFixedValue` (≤ 50), `identityUppercase`; `RevisionView`/`RevisionDetail`: dezelfde velden,
  `RevisionView.warnings[{code, field, message}]`. `supplierField`/`supplierGroupField` zijn optioneel naast een vaste waarde; beide
  → 400 `SUPPLIER_FIXED_VALUE_INVALID`/`SUPPLIER_GROUP_FIXED_VALUE_INVALID`, geen → 400 `SUPPLIER_FIELD_REQUIRED`/`_GROUP_FIELD_REQUIRED`
  (ongewijzigd). PATCH: `""` wist nu ook `supplierField`/`supplierGroupField` (vroeger 400), zodat wie overstapt de andere bron
  uitdrukkelijk wist; vaste waarden en hoofdletters tellen voor R-REV-X3. Prodis geconfigureerd: de vaste waarde (in hoofdletters als
  `identityUppercase`) moet bestaan → anders 400 `SUPPLIER_FIXED_VALUE_NOT_IN_PRODIS` / `_AMBIGUOUS_IN_PRODIS` /
  `SUPPLIER_GROUP_FIXED_VALUE_NOT_IN_PRODIS` (groep van de vaste leverancier, anders van de bronorganisatie van de definitie);
  onbereikbaar → 503 `PRODIS_DB_UNAVAILABLE`, niets bewaard; niet geconfigureerd → aanvaard met `warnings` =
  `PRODIS_REFERENCE_NOT_VERIFIED` per waarde (niet bewaard). Enkel een gewijzigde vaste waarde (of hoofdletterschakelaar) wordt
  opnieuw nagekeken. De migratie roept `SetupService` aan zonder deze controle (al in de PREVIEW gedaan).
- **Kortingscode (beslissingslog 2026-10-21 "Legacy-config S6: hoofdletters zoals legacy"):** zoals legacy (`Prg_1170` r.768,
  r.863: `Upper()` enkel op 9-11) blijft de kortingscode (veld 23) ongewijzigd, ook met `identity_uppercase`. Een eerste bouwversie
  zette ze wél in hoofdletters met WARNING `DISCOUNT_CODE_UPPERCASED`; beide zijn teruggedraaid.

**Rapport (PREVIEW = APPLY-resultaat):** `{receiptId, contentHash, outcome APPLICABLE|BLOCKED, planned{sourceOrganisation,
definition, link{…, libraryCode, libraryCandidates[{scopeKey, catalogName, legacyNumber, status}]}, task, credential, template (S7)}, sharedWith,
items[{element:"PSIMPDEF#9", status: MIGRATED|NOT_MIGRATED|WARNING|BLOCKING, code, target, message}],
prodisReference VERIFIED|UNAVAILABLE|NOT_CONFIGURED}`; `previewFingerprint` = SHA-256 over het canonieke rapport + id's/versies van
geraakte bestaande rijen + (S5) de gelezen Prodis-rijen (leverancier: `number|name`; alle bibliotheekrijen van die leverancier:
`scope_key|legacy_number|catalog_name|status`). `libraryCandidates` en `prodisReference` zijn additief (S5).

## 6. Alleen-lezen Prodis-datasource

> **Gebouwd in S5 (2026-10-20).** Hieronder de werkelijkheid; afwijkingen van het oorspronkelijke voorstel staan erbij.

- **Config:** `catalogimport.prodis-db.url`, `.username`, `.password` (env `CATALOG_PRODIS_DB_*`, geen default, ook niet in
  `local`/`ewoutdev`: de lokale Docker-database kent enkel de eigenaar `Prodis`, en die hoort niet in de repo);
  `.connect-timeout` PT5S (min. PT0.25S), `.query-timeout` PT10S (min. PT1S), `.maximum-pool-size` 2 (1..10). Alle drie leeg →
  functie uit (preview-items `PRODIS_REFERENCE_NOT_VERIFIED`, gedrag S2). Fail-fast bij: één of twee van de drie gezet, een URL
  die geen JDBC-driver herkent of die een wachtwoord bevat, een ongeldige time-out/poolgrootte, een onbekende sleutel. Geen
  boodschap noemt URL, gebruiker of wachtwoord. Een onbereikbare database laat de applicatie wél starten.
- **Lagen:** Web `config/ProdisReadOnlyDatabaseConfiguration` (+ `ProdisReferenceDatabaseProperties`) bouwt een `HikariDataSource`
  en wikkelt die in de eigen bean `ProdisReferenceJdbc` (Dao) — **geen** bean van type `DataSource`/`JdbcTemplate`/
  `NamedParameterJdbcTemplate` (bewezen in `ProdisReferenceHttpTest`: één DataSource; JPA, Liquibase en `JdbcTemplate` op de
  hoofddatabase). Pool: `readOnly=true`, `autoCommit=true`, `minimumIdle=0`, `initializationFailTimeout=-1` (verbindt pas bij de
  eerste query, houdt niets vast), geen MBeans; voor PostgreSQL ook pgjdbc `readOnly=true`, **`readOnlyMode=always`** (zie par. 10),
  `connectTimeout`, `loginTimeout`, `socketTimeout`, `logServerErrorDetail=false`. Geen `DataSourceUtils`: elke query een eigen
  kortlevende verbinding, nooit gekoppeld aan een transactie(synchronisatie) van de hoofddatabase. Dao `ProdisReferenceDao`
  (alle SQL op één plek): `findSupplier(number)` (lijst, hoogstens 2: `supplier.number` is in Prodis niet uniek),
  `searchSuppliers(text)` (nummerprefix of naam, hoofdletterongevoelig, LIKE-tekens letterlijk, max. 50),
  `findSupplierGroups(supplierNumber)` (`legacy_code` = `lev|grp|korting`, `group_name`, `discount_code`),
  `findSupplierCatalogs(supplierNumber, legacyNumber|null)` (`scope_key`, `legacy_number`, `catalog_name`, `status`; per
  `scope_key` meerdere rijen mogelijk: historiek per status). Service `ProdisReferenceService` (niet `@Transactional`): elke
  `DataAccessException` (verbinding, time-out, rechten, ontbrekende tabel) = onbereikbaar → `ProdisReferenceUnavailableException`
  (503) of `Availability.UNAVAILABLE` in PREVIEW; nooit een lege lijst. Een bibliotheek = één `scope_key` (de `ACTIVE`-rij als die
  er is, anders de nieuwste). Geen Liquibase, geen readiness-indicator (een Prodis-storing haalt CatalogImport niet uit de rotatie).
- **DB-rol (infra, bevestigd tegen de Prodis-Liquibase):** `catalogimport_ro`, `LOGIN`, `default_transaction_read_only=on`, enkel
  `CONNECT` op de database, `USAGE` op schema `public`, en `SELECT (id, number, name) ON supplier`,
  `SELECT (id, supplier_id, group_name, discount_code, legacy_code) ON supplier_group`,
  `SELECT (id, supplier_id, scope_key, legacy_number, catalog_name, status) ON supplier_catalog`; rechtstreekse tabellen, geen views
  (A4). `prodis-reference-test-schema.sql` bevat exact deze kolommen.
- **Bewaren:** enkel businesscodes (`source_organisation.code` = `supplier.number`, `import_link.library_code` = `scope_key`, de
  Prodis-naam als `source_organisation.name` van een nieuwe organisatie); geen Prodis-id's, geen cross-DB-FK.
- **Onbereikbaar:** 503 `PRODIS_DB_UNAVAILABLE` op de keuze-endpoints; PREVIEW → `PRODIS_REFERENCE_NOT_VERIFIED`,
  `prodisReference=UNAVAILABLE` en een fingerprint **`unverified:<sha256>`** (afwijking van het letterlijke `"unverified"`: zo
  blijft de controle op veroudering van de rest van het rapport werken, en een geverifieerde fingerprint begint nooit zo). APPLY
  mag dan een DRAFT maken met de `libraryCode` uit de body (sinds S6 ook met niet-geverifieerde vaste waarden, WARNING
  `PRODIS_REFERENCE_NOT_VERIFIED` op `PSIMPDEF#9/10`), **zolang Prodis onbereikbaar blijft**: is Prodis intussen terug,
  dan geeft dezelfde fingerprint 409 `LEGACY_PREVIEW_STALE`, en omgekeerd (geverifieerde preview, Prodis nu weg → 409).
- **Keuze-endpoints** (S5, MANAGE, niet achter de setup-vlag): `GET /api/catalog-import/prodis-reference/suppliers?q=`
  (max. 100 tekens, anders 400 `PRODIS_REFERENCE_QUERY_INVALID`), `/suppliers/{nr}/groups`, `/suppliers/{nr}/catalogs`
  (één rij per `scopeKey`); 404 `PRODIS_SUPPLIER_NOT_FOUND`, 409 `PRODIS_DB_NOT_CONFIGURED`, 503 `PRODIS_DB_UNAVAILABLE`; 403 vóór alles.
- **Testen:** H2 (enkel test-scope) in testresources (`prodis-reference-test-schema.sql`, schema eenmaal per database, niet via
  `INIT=` zodat een onbruikbare bron te simuleren is); `ProdisReferenceUnavailableTest` met een gesloten poort (503, "unverified");
  `LegacyConfigPreviewTest` zonder config (functie uit, 409); `ProdisReadOnlyDatabaseConfigurationTest` met fail-fast en de
  readOnly-proef tegen echte PostgreSQL (SQLState 25006).

## 7. Datasync-job (`C:\javaProject\ProdisDatasyncEngine`)

**pervasive-dao:** models `LegacyInterfaceHeader`, `LegacyInterfaceDetail` (`toString` zonder value),
`LegacyImportDefinitionHeader`, `LegacyImportDefinitionLine`, `LegacyImportDefinitionMapEntry`, `LegacyImportDefinitionExclude`;
`repository/LegacyConfigPervasive` + `impl/LegacyConfigPervasiveImpl` (patroon `WorkshopMasterDataPervasiveImpl`); tabelnamen via
`pervasive.tables.*` (INHEAD, INDET, PSIMDHFD, PSIMPDEF, PSIMPDEFMAP, PSIMPEXCL); per tabel eerst `X$File`-check; kolommen met
spaties/tekens tussen dubbele quotes, vooraf te verifiëren in `X$Field`. INHEAD/INDET ontbreekt → job faalt; definitietabellen
ontbreken → `definitionStatus=TABLE_UNAVAILABLE`.

**sync-core `job/LegacyConfigExport/`:** `LegacyConfigExportJob` (SyncJob, `ONCE`, niet `@Transactional`);
`LegacyConfigBundleMapper` (puur; scheidt secret; redigeert URL's); records `LegacyConfigBundle` / `LegacySecretValue`
(`toString` → `[REDACTED]`); `CanonicalJson`; `CatalogImportPushClient` (JDK `java.net.http.HttpClient`);
`ClientCredentialsTokenProvider` (Keycloak-tokenendpoint, cache tot vervaltijd); `CatalogImportPushProperties`; `jackson-databind`
als expliciete dependency (versie via BOM). De engine roept vandaag geen externe HTTP aan: dit is de eerste uitgaande HTTP-client.

**Config (`application.yml`, geen secret in de repo):**
```
catalogimport.push: base-url, token-url, client-id, client-secret: ${CATALOGIMPORT_PUSH_CLIENT_SECRET:},
  environment, dossier, connect-timeout PT10S, request-timeout PT30S, max-attempts 3, allow-insecure-http false
sync.jobs.LegacyConfigExportJob: { enabled: false, frequency: once, priority: 200 }
```
`https` verplicht, behalve `allow-insecure-http` in een dev-profiel.

**Guard:** de controller-guard blijft (weigert NONE); de job leest zelf `system_settings.legacy_master` via de Prodis-`JdbcTemplate`
(`job/support/LegacyMasterReader`): enkel `PRODIS` of ontbrekende tabel/kolom; `PROFAB` → weigering met duidelijke melding.

**Flow per interface:** lezen → bundel → push. Retry bij IO/5xx (max 3, backoff); 4xx geen retry; 401 één keer nieuw token.
Fout → `SyncErrorWriter(jobId, interfaceCode, "LEGACY_CONFIG_PUSH", code, recoverable, "HTTP <status> <code>")`, nooit body of
value; door naar de volgende interface. Op het einde bij fouten: `LegacyConfigExportIncompleteException` met enkel aantallen.
Body-`byte[]` na verzending wissen.

**Secret (S4, gebouwd 2026-10-20):** de mapper zet de getrimde `FTP_password`-waarde in `LegacyConfigBundle.secret`
(`LegacySecretValue`, `toString` → `[REDACTED]`; leeg → `null`); de JSON-boom (`document`) houdt `"secret": null` en blijft dus
veilig om te printen, de hashes blijven ongewijzigd. Pas `LegacyConfigBundleBody` schrijft het secret op `$.secret` in de
request-body (streaming, tussenbuffer gewist). Meerdere `FTP_password`-regels met verschillende waarden → bundel niet gebouwd
(`SECRET_AMBIGUOUS`, sync-error zonder waarde). Een 409 `SECRETS_NOT_CONFIGURED` is een niet-herstelbare sync-error; de run gaat door.

**Logging:** enkel interfacecode, `contentHash`, uitkomst, aantallen — ook op DEBUG (productie: `be.dda.prodis: DEBUG`); geen
wire-logging; eigen exceptions zonder body.

**Manueel starten:** `POST /api/jobs/LegacyConfigExportJob/run`, optioneel `?interface=KRAMP_ART&dryRun=true` (dryRun pusht niet,
logt enkel hashes). Parameters nooit met secret (`JobExecutor` r.344-379 logt ze).

**Tests (`*Tests`):** `LegacyConfigPervasiveImplTests`, `LegacyConfigBundleMapperTests`, `CanonicalJsonTests` (golden hash),
`CatalogImportPushClientTests` (stub met `com.sun.net.httpserver`), `LegacyConfigExportJobTests` (PROFAB/NONE geweigerd, dryRun,
doorgaan na fout), canary-test (secret `canary-…` nergens in logs, `sync-errors-*.jsonl` of exceptieboodschap).

## 8. Bouwvolgorde (strikt sequentieel)

| Slice | Repo / lagen | Inhoud | Trap | Gerichte tests | Klaar als |
|---|---|---|---|---|---|
| S0 | beide, docs + testresources | `docs/design/legacy-config-bundle-v1.md` (§1) + golden fixture + verwachte hashes | bouwer-gemiddeld | — | Fixture byte-identiek in beide repo's |
| S1 (na M2) | ImportService: Liquibase 020-1/020-2, Domain, Dao, Web (JWT-chain), Service | POST `/receipts` zonder secret (secret → 400 tot S4), validatie, hashcontrole, idempotentie, GET lijst/detail | bouwer-zwaar | `mvn -pl Web -am test -Dtest=LegacyConfigReceiptHttpTest,LegacyConfigReceiptSchemaTest,LegacyBundleCanonicalHashTest,PermissionCoverageTest,PermissionHttpTest -Dsurefire.failIfNoSpecifiedTests=false` | 401 zonder token, 403 verkeerde rol/azp, 401 voor sessie met MANAGE, 201 → 200 `UNCHANGED`, hash-mismatch 400 |
| S2 | ImportService: Service, Web, 021 (020-3/020-4) | PREVIEW + APPLY één interface → `OWN_DEFINITION`, enkel B-rijen zonder Prodis-validatie/vaste waarden; rapport; opvolgende DRAFT; conflictregels; `previewFingerprint` | bouwer-zwaar | `…-Dtest=LegacyConfigPreviewTest,LegacyConfigApplyTest,SetupApiFlowTest,ConfigFactoryParityTest` | DRAFT + koppeling + MANUAL-taak; ACTIVE ongemoeid; tweede APPLY 409; stale 409; golden VROOAM2 |
| S3 | Datasync: pervasive-dao, sync-core, sync-app-config | Job zonder secret, PRODIS-guard, dryRun | bouwer-zwaar | `./mvnw.cmd -pl pervasive-dao,sync-core -am test -Dtest="LegacyConfig*Tests,CanonicalJsonTests,CatalogImportPushClientTests" -Dsurefire.failIfNoSpecifiedTests=false` | Push tegen stub; golden hash = S0 |
| S4 | beide | Secret over TLS; credential bij ontvangst (SYSTEM-overload); canary-lektests aan beide kanten | bouwer-zwaar | `…-Dtest=LegacyConfigSecretLeakTest,CredentialLeakDetectionTest,CredentialHttpTest,ExternalCredentialSchemaTest` + `LegacyConfigExportJobTests` | Canary nergens behalve als ciphertext; geen tweede credential; `SECRETS_NOT_CONFIGURED` 409 |
| S5 | ImportService: Web-config, Dao, Service, Web | Alleen-lezen Prodis-bron; validatie leverancier/bibliotheek in PREVIEW/APPLY; automatische `libraryCode`; keuze-endpoints (naar voren gehaald uit S6) | bouwer-zwaar | `…-Dtest=ProdisReferenceDaoTest,ProdisReadOnlyDatabaseConfigurationTest,ProdisReferenceUnavailableTest,ProdisReferenceHttpTest,LegacyConfigProdisReferenceTest,LegacyConfigPreviewTest,LegacyConfigApplyTest,PermissionCoverageTest` | Contexten starten zonder config; 503 bij onbereikbaar |
| S6 (na M3; gebouwd 2026-10-21) | ImportService: Liquibase 022, alle lagen | Vaste leverancier/groep + `identity_uppercase`; migratie gebruikt de Prodis-bron (S5); zie §5 "Uitwerking S6" | bouwer-zwaar | `mvn -pl Service -am test -Dtest='be.dda.catalogimport.service.**' -Dsurefire.failIfNoSpecifiedTests=false` (o.a. `IdentityFixedValueTest`, `RevisionConfigHashesParityTest`, `LegacyRevisionContentParityTest`, `ConfigFactoryParityTest`, `RevisionFieldRulesTest`) + `mvn -pl Web -am test -Dtest=IdentityFixedValueHttpTest,IdentityFixedValueNotConfiguredHttpTest,LegacyConfigPreviewTest,LegacyConfigApplyTest,LegacyConfigProdisReferenceTest -Dsurefire.failIfNoSpecifiedTests=false` + `scripts/test/run-full-tests.ps1 -Tests ScreeningSchemaTest,SetupApiFlowTest,DeliveryScreeningFlowTest,...` (vaste codes: schoon schema) | Bestaande hashes byte-identiek; uppercase = PSARF-identiteit (9-11; kortingscode ongewijzigd) |
| S7 (na M1; gebouwd 2026-10-21) | ImportService: Domain, Dao, Service (geen Liquibase) | Altijd sjabloon + gedeelde definitie (A2), dedup per (leverancier, hash of code), `sharedWith`, `planned.template`, `SHARED_DEFINITION_DIVERGED`, `TEMPLATE_NOT_UPDATED`, scopeconflict in de preview; zie §5 "Uitwerking S7" | bouwer-zwaar | `mvn -pl Web -am test -Dtest=LegacyConfigSharedDefinitionTest,TemplateGuardTest,TemplateReuseTest,LegacyConfigApplyTest,LegacyConfigPreviewTest,LegacyConfigProdisReferenceTest,TemplateMaterialisationTest -Dsurefire.failIfNoSpecifiedTests=false` | Drie identieke interfaces → één sjabloon + één gedeelde definitie + drie koppelingen/taken, ongeacht volgorde; guard 006-5 intact; wizard-hergebruik werkt |
| S8 | ImportService | 023 maptabel, 024 transformatieketen, PSIMPEXCL (LM-2); opgeknipt in S8a–S8g (`legacy-config-s8-design.md` par. 4; S8a, S8b en S8c gebouwd 2026-10-21) | bouwer-zwaar | gericht per klasse | — |
| S9 | Frontend | Ontvangsten, rapport, APPLY-dialoog | bouwer-gemiddeld | `npx vitest run src/test/LegacyConfig*` | — |

**Parallel:** S3 kan technisch naast S2 (andere repo, contract vast in S0), maar AGENT.md laat zware taken niet parallel lopen:
sequentieel, tenzij de mens het expliciet toestaat. **Extern parallel aan te vragen:** Keycloak-client, rol, audience (§3);
DB-rol `catalogimport_ro` (§6).

## 9. Vragen voor de mens (blokkeren de bouw)

- **M1 — Delen van definities (blokkeert S7).** "REUSABLE_TEMPLATE met per interface een koppeling" kan niet (T1).
  (A) per (leverancier, inhoudshash) één `REUSABLE_TEMPLATE` (DRAFT, mens activeert) plus één gedeelde `OWN_DEFINITION`
  `based_on` dat sjabloon, met per interface een koppeling en taak; (B) sjabloon + per interface een eigen afgeleide kopie;
  (C) geen sjabloon, enkel de gedeelde definitie. **Advies: A**, dedup per leverancier.
- **M2 — Technische client (blokkeert S1).** JWT-resource-server enkel op het push-pad, client role `legacy-config-push` +
  `azp`/`aud`-controle, nieuwe dependency. Alternatieven: API-sleutel (afgeraden), mTLS (zwaarder voor infra). **Advies: JWT.**
- **M3 — `identity_supplier_field` / `identity_supplier_group_field` nullable (blokkeert S6).** Wijzigt een bestaande
  kolomconstraint en de betekenis van API-veld `supplierField`. **Advies:** `drop not null` + check "precies één van beide",
  API additief uitgebreid; geen sentinelwaarde.

**Aannames:** A1 een bestaande credential wordt bij herhaling nooit vervangen (vervangen via menselijke PUT); verfijnd 2026-10-21:
heeft de herkomst nog geen credential, dan wordt een secret ook bij `UNCHANGED` overgenomen (§2 "Uitwerking S4-vervolg"); A2 credential met
eventbron SYSTEM, `created_by=svc:<azp>`; A3 `library_code` = `supplier_catalog.scope_key` via `legacy_number` (sinds S5 automatisch bij precies één `scope_key` met dat
`legacy_number`; anders, of zonder/met onbereikbare Prodis, door de mens als `libraryCode` gegeven, zie §5); A4 rechtstreekse
SELECT op 3 tabellen; A5 wisselkoers ≠ 1 blokkeert APPLY; A6 export van alle interfaces met `ArtImp_Importsoort`; A7 protocol uit
het URL-schema, `SFTP`-label enkel als hint; A8 enkel MANUAL-taken; A9 ook GET op preview/apply vraagt MANAGE (L7b).

## 10. Voorgestelde ontdekkingsblokken (nog niet teruggeschreven)

> **Important technical constraint discovered**
>
> Een interface verwijst voor de artikelimport naar zijn definitie via INDET-label `ArtImp_Importsoort` (seq 100) →
> `PSIMDHFD.Code` (`Prg_1232` r.407 → aanroep 1223 r.518; `Prg_1472` r.5788). Label `ImportDefinition` (seq 11) wordt enkel in een
> dode aanroep (`if FALSE`, `Prg_1232` r.515) gebruikt. Het wachtwoordlabel is exact `FTP_password` (seq 3, `Prg_1472` r.4197).

> **Important business rule discovered**
>
> Legacy plakt de FTP-bestemming als `FTP`+`FTP2` en vervangt `[LOGIN]`, `[PASSWORD]`, `[YYYY]`, `[YY]`, `[MM]`, `[DD]`
> (`Prg_1232` r.298-300, 459-467). `Separator` kent `Comma`/`Semicolon`/`Tab` (r.1188-1192, default `Comma`, `Prg_1472` r.4970).
> Het label `SFTP` (seq 160, `Prg_1472` r.5215) wordt door 1232 niet gelezen; het protocol is dus niet betrouwbaar uit dat label af te leiden.

> **Important technical constraint discovered**
>
> De Legacy-vault bevat letterlijke wachtwoorden als Magic-defaults (`Prg_1472` r.1254, 1322, 1361). De vault is secret-houdend:
> nooit als bron voor testfixtures gebruiken; infra/de mens beoordeelt of die waarden nog live zijn.

> **Important technical constraint discovered**
>
> Een tweede bean van type `DataSource` of `JdbcTemplate` in CatalogImport laat de Spring Boot-autoconfiguratie van de
> hoofddatabase terugtreden (`@ConditionalOnMissingBean`). De Prodis-leesbron moet in een eigen beantype gewikkeld worden.

> **Important technical constraint discovered** (S5)
>
> pgjdbc dwingt `Connection.setReadOnly(true)` (ook HikariCP `readOnly=true`) standaard enkel af binnen een transactie
> (`readOnlyMode=transaction`); met `autoCommit=true` wordt er dan gewoon geschreven. Pas met `readOnlyMode=always` zet de driver
> `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY` (bewezen: zonder die eigenschap faalt `writesAreRejectedOnPostgres`).
> De DB-rol met `default_transaction_read_only=on` blijft de eerste verdedigingslinie.

> **Important technical constraint discovered** (S5)
>
> In Prodis is `supplier.number` niet uniek (enkel `idx_supplier__number_coalesced`) en heeft `supplier_catalog` per `scope_key`
> meerdere rijen (historiek per `status`; uniek enkel `(supplier_id, catalog_name, scope_key) WHERE status='ACTIVE'`). Matching op
> `legacy_number` telt daarom `scope_key`s, geen rijen, en een dubbel leveranciersnummer blokkeert (nooit stil de eerste nemen).

> **Important technical constraint discovered**
>
> `LegacyMasterSyncGuard` (r.55) weigert enkel `NONE` en laat `PROFAB` door. `JobExecutor` logt de parameters van manuele runs
> (r.344-379) en stacktraces van falende jobs (r.416). Een job die secrets verwerkt moet zelf op `PRODIS` controleren en mag secrets
> nooit in parameters of exceptions zetten.

> **Important technical constraint discovered**
>
> Nieuwe revisiekolommen (vaste identiteit, uppercase) mogen in `RevisionConfigHashes` enkel meetellen als ze niet de default
> hebben; anders veranderen bestaande configuratiehashes en verliest §14.14 zijn traceerbaarheid. Een canonicalisatieversie 3 is
> uitgesloten (`CandidateNormaliser` r.76-82).
