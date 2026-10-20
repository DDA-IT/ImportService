# Legacy-config-migratie — bundel v1, push, PREVIEW/APPLY, Prodis-leesbron en datasync-job

> **Status: VOORSTEL (2026-10-20), uitvoerbaar ontwerp van denker-zwaar.** Steunt op de mens-beslissingen van 2026-10-20 in
> `docs/decisions.md` ("focus, bron, secrets, definities" en "datasync-aanpak, Prodis-DB lezen, sjablonen"). Verfijnt
> `legacy-config-migratie-design.md` en vervangt `prodis-db-publicatie-en-datasync-design.md` §4 (envelopversleuteling en
> bestandstransport zijn door de mens vervangen door push over TLS). Open bij de mens: **M1–M3** (§9). S0 kan starten;
> S1 na M2; S6 na M3; S7 na M1. Niets gebouwd, niets getest.

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
  Een `FTP`/`FTP2`-waarde met `://user:pass@` wordt zonder userinfo doorgegeven, met `ftpCredentialsRedacted=true`.
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
- 409 `SECRETS_NOT_CONFIGURED` (bundel met secret zonder sleutelring: hele push geweigerd), `LEGACY_RECEIPT_NOT_APPLICABLE`, `LEGACY_PREVIEW_STALE`, `LEGACY_APPLY_BLOCKED` (met blokkerende codes), `PRODIS_DB_NOT_CONFIGURED`
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
- FTP/onbekend protocol → `DISCARDED_PROTOCOL_UNSUPPORTED`; geen/ongeldige host → `DISCARDED_NO_HOST`; UNCHANGED → weggegooid.

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
  (tekst, voor het rapport); `template_definition_id` FK null, `import_definition_id` FK; uniek `(env, dossier, supplier_code, definition_content_hash)`;
  `created_at`/`updated_at`. Een definitie met zo'n rij is "gemigreerd"; zonder is ze handmatig en nooit migratiedoel. Bij een gewijzigde
  legacy-definitie wordt de hash ter plaatse bijgewerkt (historiek in 020-4).
- **020-4 `legacy_config_revision_origin`:** `revision_id` uniek FK, `receipt_id` FK, `definition_content_hash` ("kwam de laatste revisie uit de migratie"),
  `migrated_content_hash` (SHA-256 over wat APPLY op de revisie zette: door legacy bepaalde velden, alle mappings, alle filters; basis voor
  `DEFINITION_UNCHANGED` en `MANUAL_CHANGES_SINCE_MIGRATION`), `created_at`.
- **021-1:** `legacy_config_receipt.applied_reason` varchar(500), nullable (de reden van APPLY; ook in `apply_report_json` en `change_reason`).

**022-identity-fixed-and-uppercase.sql** (S6, hangt af van M3; was 021): op `import_definition_revision`
`identity_supplier_fixed_value` varchar(50) null, `identity_supplier_group_fixed_value` varchar(50) null,
`identity_uppercase` boolean not null default false; `drop not null` op `identity_supplier_field` en
`identity_supplier_group_field`; check per deel "precies één van kolom of vaste waarde". Bestaande rijen voldoen;
rollback enkel zolang geen rij een vaste waarde heeft.

**023-field-value-map** (S8; was 022) `import_field_value_map`: `mapping_id` FK, `sequence`, `source_value` varchar(50),
`target_value` varchar(50); uniek `(mapping_id, source_value)`; vervangt `transform_config varchar(1000)` voor MAP.

## 5. Vertaalregels bundel → CatalogImport

| Bundel | Doel (code) | Status |
|---|---|---|
| `definition.code` / `description` | `ImportDefinition(code, description)` `OWN_DEFINITION`; codeconflict met niet-gemigreerde definitie → `DEFINITION_CODE_CONFLICT_MANUAL` (blokkeert, tenzij `definitionCodeOverride`) | B |
| `updateFile` / `replacementCode` / `deleteCode` | rapport `LEGACY_CONTROL_CODE_NOT_MIGRATED` | N |
| INDET `Supplier` | `SourceOrganisation` find-or-create (`SUPPLIER`, code = `Supplier.number`, naam uit Prodis); bron van de definitie en `import_link.supplier_organisation` | B (+S5-validatie) |
| `ArtImp_BibNr` | `import_link.library_code` = `supplier_catalog.scope_key` van de match op `legacy_number` (A3). Tot S5: `libraryCode` uit de APPLY-body (WARNING `PRODIS_REFERENCE_NOT_VERIFIED`); zonder → `LIBRARY_CODE_NOT_RESOLVED` (blokkeert); nooit het ruwe `ArtImp_BibNr`; label afwezig → `LIBRARY_NOT_DECLARED` (blokkeert) | B |
| `Separator` Comma/Semicolon/Tab | `structure_delimiter` `,` / `;` / TAB (`Prg_1232` r.1188-1192); afwezig/leeg → `;` met WARNING `SEPARATOR_NOT_DECLARED` (`Prg_1232` r.1194); anders of XML → `STRUCTURE_FORMAT_UNSUPPORTED` (blokkeert) | B |
| `FILE_ENCODING` | `structure_charset`; leeg → `CHARSET_NOT_DECLARED` (blokkeert, tenzij `charsetWhenUndeclared`) — nooit raden | B |
| `SkipHeaderLines` n | n=0 → geen header; n≥1 → `has_header`, `header_line_number`=n; altijd `COLUMN_INDEX` | B |
| `DecimalComma`=Y | `decimalSeparator=,` op numerieke mappings; werking voor basisprijs te verifiëren (`ImportValueRules.decimal`) | B / verifiëren |
| PSIMPDEF 9/10/11 met `column` | `identity_*_field` = `column` | B |
| 9/10 met `value` | `identity_*_fixed_value`, gevalideerd tegen Prodis-DB (S5/S6); onbekend → blokkeert | U (S6) |
| altijd (legacy `Upper()`) | `identity_uppercase=true` op gemigreerde revisies | U (S6) |
| 23 | `identity_discount_code_field` + `FOUR_PART`, enkel als `ArtImp_Key_Korting=Y` | B |
| `ArtImp_Key_LevGrp`=N of `Key_Barcode`=Y | `IDENTITY_PROFILE_UNSUPPORTED` (blokkeert; decisions 2026-09-18) | N |
| 26 / 4 | `record_base_price_field` / `record_description_field`; prefix/suffix/CALC op 26 → `BASE_PRICE_TRANSFORM_UNSUPPORTED` (blokkeert) | B |
| 33-37, 40, 43, 12, 219, 221 | mapping met de catalogcode uit `legacy-config-migratie-design.md` §1; één prefix, één suffix of CALC 1-4/A; combinatie → `TRANSFORM_CHAIN_UNSUPPORTED` (rapport, mapping vervalt) | B |
| `position`/`length` ≠ 0, CALC 8/11/12/13/14/15 | rapport per regel (`LEGACY_CALC_<n>_NOT_MIGRATED`) | N / U later |
| CALC 7 + maps | v1: MAP als het in 1000 tekens past; altijd WARNING `MAP_MISS_NO_FALLBACK` | B / U (S8) |
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

**Configuratiehash:** nieuwe revisiekolommen tellen in `RevisionConfigHashes` enkel mee als ze niet de default hebben; bestaande
hashes blijven byte-identiek (byte-vergelijkingstest).

**Rapport (PREVIEW = APPLY-resultaat):** `{receiptId, contentHash, outcome APPLICABLE|BLOCKED, planned{sourceOrganisation,
definition, link, task, credential}, sharedWith, items[{element:"PSIMPDEF#9", status: MIGRATED|NOT_MIGRATED|WARNING|BLOCKING,
code, target, message}]}`; `previewFingerprint` = SHA-256 over het canonieke rapport + id's/versies van geraakte bestaande rijen.

## 6. Alleen-lezen Prodis-datasource

- **Config:** `catalogimport.prodis-db.url`, `.username`, `.password` (env `CATALOG_PRODIS_DB_*`, geen default);
  `.connect-timeout` PT5S, `.query-timeout` PT10S, `.maximum-pool-size` 2. Geen config → functie uit, preview-items
  `PRODIS_REFERENCE_NOT_VERIFIED`, APPLY zet geen vaste leverancier/groep. Ongeldige config → fail-fast.
- **Lagen:** Web `config/ProdisReadOnlyDatabaseConfiguration` bouwt een `HikariDataSource` (`readOnly=true`), gewikkeld in een eigen
  bean `ProdisReferenceJdbc` — **geen** bean van type `DataSource`/`JdbcTemplate`. Dao `ProdisReferenceDao` (JDBC-projectie):
  `findSupplier(number)`, `findSupplierGroups(supplierNumber)` (`supplier_group.legacy_code` = `lev|grp|korting`, `group_name`,
  `discount_code`), `findSupplierCatalog(supplierNumber, legacyNumber)` (`scope_key`, `legacy_number`, `catalog_name`, `status`).
  Service `ProdisReferenceService` vertaalt fouten. Geen Liquibase, geen `@Transactional` van de hoofd-TM op deze bron.
- **DB-rol (infra):** `catalogimport_ro`, enkel SELECT op `supplier(id, number, name)`, `supplier_group(id, supplier_id,
  group_name, discount_code, legacy_code)`, `supplier_catalog(id, supplier_id, scope_key, legacy_number, catalog_name, status)`,
  `default_transaction_read_only=on`; rechtstreekse tabellen, geen views (A4).
- **Bewaren:** enkel businesscodes; geen Prodis-id's, geen cross-DB-FK.
- **Onbereikbaar:** 503 `PRODIS_DB_UNAVAILABLE` op keuze-endpoints; PREVIEW → `PRODIS_REFERENCE_NOT_VERIFIED`, fingerprint
  `"unverified"`; APPLY mag een DRAFT maken zonder vaste waarden; activeren blokkeert dan op `CONFIG_IDENTITY_FIELD_MISSING` (fail-closed).
- **Keuze-endpoints** (S6, MANAGE): `GET /prodis-reference/suppliers?q=`, `/suppliers/{nr}/groups`, `/suppliers/{nr}/catalogs`.
- **Testen:** H2 in testresources (`prodis-reference-test-schema.sql`), test met onbereikbare URL (503), test zonder config (functie uit).

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
| S5 | ImportService: Web-config, Dao, Service | Alleen-lezen Prodis-bron; validatie leverancier/bibliotheek in PREVIEW | bouwer-zwaar | `…-Dtest=ProdisReferenceDaoTest,ProdisReferenceUnavailableTest,LegacyConfigPreviewTest` | Contexten starten zonder config; 503 bij onbereikbaar |
| S6 (na M3) | ImportService: Liquibase 022, alle lagen | Vaste leverancier/groep + `identity_uppercase`; keuze-endpoints; migratie gebruikt ze | bouwer-zwaar | `…-Dtest=IdentityFixedValueTest,RevisionConfigHashesParityTest,ConfigFactoryParityTest,ScreeningSchemaTest,DeliveryScreeningFlowTest` | Bestaande hashes byte-identiek; uppercase = PSARF-identiteit |
| S7 (na M1) | ImportService: Service | Dedup per (leverancier, hash), gedeelde definitie, `sharedWith`, `SHARED_DEFINITION_DIVERGED` | bouwer-zwaar | `…-Dtest=LegacyConfigSharedDefinitionTest,TemplateGuardTest,TemplateReuseTest` | Drie identieke interfaces → één gedeelde set; guard 006-5 intact |
| S8 | ImportService | 023 maptabel, PSIMPEXCL, transformatieketen (LM-2) | bouwer-zwaar | gericht per klasse | — |
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

**Aannames:** A1 secret bij herhaling nooit vervangen (UNCHANGED → weggegooid; vervangen via menselijke PUT); A2 credential met
eventbron SYSTEM, `created_by=svc:<azp>`; A3 `library_code` = `supplier_catalog.scope_key` via `legacy_number` (tot S5 door de mens als `libraryCode` gegeven, zie §5); A4 rechtstreekse
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
