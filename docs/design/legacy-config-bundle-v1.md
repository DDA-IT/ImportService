# Legacy-config-bundle v1 — normatief contract

> **Status: normatief (S0, 2026-10-20).** Uitwerking van `legacy-config-bundle-design.md` §1; bij conflict wint dit document voor
> het bundelformaat en de hashes. Twee onafhankelijke implementaties (ProdisDatasyncEngine `CanonicalJson`, CatalogImport
> `LegacyBundleCanonicalHash`) moeten hiermee byte-identieke hashes geven. Referentie: de golden fixtures in
> `Web/src/test/resources/legacy-config/` (ImportService) en `sync-core/src/test/resources/legacy-config/` (ProdisDatasyncEngine),
> byte-identiek in beide repo's.
> Trefwoorden MOET/MAG NIET zijn normatief.

## 1. Envelop

Eén JSON-document (UTF-8, zonder BOM) per interface. Alle sleutels hieronder MOETEN aanwezig zijn tenzij "optioneel"; nulwaarden
staan als expliciete `null` waar vermeld. **Onbekende sleutels op elk niveau zijn ongeldig** (400 `LEGACY_BUNDLE_INVALID`).

| Pad | Type | Verplicht | Regels |
|---|---|---|---|
| `schema` | string | ja | exact `"legacy-config-bundle"` |
| `version` | integer | ja | exact `1`, anders 400 `LEGACY_BUNDLE_VERSION_UNSUPPORTED` (versiecontrole vóór de rest van de validatie, zodra `schema` en `version` leesbaar zijn) |
| `origin.environment` | string | ja | 1-40 tekens, niet leeg |
| `origin.dossier` | string | ja | 1-40 tekens, niet leeg |
| `origin.legacyMaster` | string | ja | exact `"PRODIS"` |
| `exportedAt` | string | ja | ISO-8601 datum-tijd met offset (`2026-10-21T10:15:00+02:00`) |
| `exporter.job` | string | ja | 1-100 tekens |
| `exporter.appVersion` | string | ja | 1-50 tekens |
| `contentHash` | string | ja | `^[0-9a-f]{64}$` |
| `definitionContentHash` | string of `null` | ja | `^[0-9a-f]{64}$` als `definitionStatus = PRESENT`, anders `null` |
| `interface` | object | ja | zie §2 |
| `definitionStatus` | string | ja | `PRESENT`, `NOT_REFERENCED`, `MISSING` of `TABLE_UNAVAILABLE` |
| `definition` | object of `null` | ja | object (§3) als `PRESENT`, anders `null` |
| `secret` | object of `null` | ja | `{ "label": "FTP_password", "value": <string, niet leeg> }` of `null`; geen andere label toegestaan |

**Herkomstsleutel:** `(origin.environment, origin.dossier, interface.code)`.

**`definitionStatus`:** `PRESENT` (definitie gevonden) | `NOT_REFERENCED` (INDET-label `ArtImp_Importsoort` leeg/afwezig) | `MISSING`
(label verwijst naar een code die niet in PSIMDHFD staat) | `TABLE_UNAVAILABLE` (definitietabellen niet beschikbaar in de DDF).

## 2. `interface` (INHEAD DS 270 + INDET DS 271)

Magic-padding is getrimd (trailing en leading spaties weg); getallen zijn integers. Nooit run-toestand of audit
(`Date/Time/User last run`, `Running?`, `MaxRunTime`, `Reserve*`, `User/Date/Time Create/Update`).

| Pad | Type | Opmerking |
|---|---|---|
| `interface.code` | string | 1-20 tekens, niet leeg |
| `interface.header.direction` | string | max 1 |
| `interface.header.importExportType` | string | max 20 |
| `interface.header.executeAutomatic` | boolean | |
| `interface.header.executeEvery` | string | max 1 |
| `interface.header.executionTime`, `.endTime` | string | `HHMMSS`-tekst zoals in legacy (6 tekens of leeg) |
| `interface.header.weekdays` | array van exact 7 booleans | volgorde = legacy (maandag..zondag); **nooit gesorteerd** |
| `interface.header.dayOfWeek` | string | max 10, mag leeg |
| `interface.header.dayOfMonth`, `.monthOfYear` | integer | 0..99 |
| `interface.header.specificUser` | string | mag leeg |
| `interface.header.info` | string | mag leeg, vrije tekst |
| `interface.details` | array | INDET-regels, mag leeg |
| `interface.details[].sequence` | integer | >= 0 |
| `interface.details[].label` | string | niet leeg; `(sequence,label)` uniek binnen de bundel (anders `DUPLICATE`) |
| `interface.details[].value` | string | mag leeg |
| `interface.omittedSecretLabels` | array van strings | labelnamen (geen waarden) die door de redactieregel zijn weggelaten; uniek |
| `interface.ftpCredentialsRedacted` | boolean | `true` als uit `FTP` of `FTP2` userinfo is verwijderd |

Alle string-velden zijn korter dan 2000 tekens tenzij hierboven anders vermeld; `details[].value` en `header.info` max 4000.

### 2.1 Secret-regels (MOETEN door beide kanten gelden)

1. Het wachtwoordlabel is exact `FTP_password` (hoofdlettergevoelig). Die INDET-regel komt **nooit** in `details`; de waarde gaat
   enkel naar `secret`. `FTP_password` komt ook niet in `omittedSecretLabels`. Is de waarde leeg of ontbreekt de regel: `secret = null`.
2. Redactieregex (Java-syntaxis, `Matcher.find()` over de **labelnaam**): `(?i)pass|secret|apikey|api_key|token|_pw$`.
   Elk ander INDET-label dat hierop matcht (en niet exact `FTP_password` is) wordt **niet** in `details` opgenomen; enkel de
   labelnaam komt in `omittedSecretLabels` (uniek, ongeacht aantal regels). `(?i)` = ASCII-hoofdletterongevoelig.
3. **URL-userinfo:** voor de INDET-regels met label exact `FTP` of `FTP2`: zoek `^([A-Za-z][A-Za-z0-9+.-]*://)` ; de *authority* is
   de tekst na `://` tot het eerste teken van `/`, `?`, `#` of het einde. Bevat de authority een `@`, dan wordt alles tot en met de
   **laatste** `@` in de authority verwijderd (`sftp://klant:geheim@host/dir` wordt `sftp://host/dir`) en geldt
   `ftpCredentialsRedacted = true`. De geredigeerde waarde staat in `details` en is wat gehasht wordt.
4. De ontvanger MOET weigeren (400 `LEGACY_BUNDLE_INVALID`, regel `SECRET_IN_DETAILS`) als `details` een label bevat dat de
   redactieregex matcht, of een `FTP`/`FTP2`-waarde met userinfo.
5. `secret.value` verschijnt nooit in logs, foutberichten, `toString`, het opgeslagen `bundle_json` of de hashes.

## 3. `definition` (PSIMDHFD DS 253, PSIMPDEF 252, PSIMPDEFMAP 254, PSIMPEXCL 257)

`IMPORT`/`EXCL_Code` zijn weggelaten (zit in `definition.code`).

| Pad | Type | Opmerking |
|---|---|---|
| `definition.code` | string | 1-10 tekens, niet leeg |
| `definition.description` | string | mag leeg |
| `definition.updateFile`, `.replacementCode`, `.deleteCode` | string | mag leeg |
| `definition.lines[]` | object | PSIMPDEF-regel |
| `.number` | integer | >= 0; velduitkomst-nummer (9, 10, 11, 23, 26, 4, 33, ...) |
| `.field` | string | veldnaam zoals in legacy, mag leeg |
| `.column`, `.position`, `.length` | integer | >= 0 |
| `.includeExclude`, `.includeExcludeData`, `.prefix`, `.suffix`, `.value`, `.fill`, `.calculation`, `.calculationValue`, `.xmlElementPath` | string | mogen leeg; `calculation` bevat het CALC-nummer als tekst (`"7"`) |
| `definition.maps[]` | object | PSIMPDEFMAP-regel: `number` (integer >= 0), `key`, `value`, `reserveAlpha` (strings, mogen leeg) |
| `definition.excludes[]` | object | PSIMPEXCL-regel: `supplier`, `supplierGroup`, `discountCode`, `supplierReference` (strings, mogen leeg) |

`lines`, `maps` en `excludes` mogen leeg zijn maar de sleutels zijn verplicht. Meerdere regels met hetzelfde `number` zijn toegestaan.

## 4. Validatie en foutcodes

Volgorde: (1) bodygrootte, (2) JSON-parse, (3) `schema`/`version`, (4) structuur/types/regels, (5) hashcontrole. Eerste fout wint.

| HTTP | Code | Wanneer |
|---|---|---|
| 413 | `LEGACY_BUNDLE_TOO_LARGE` | body > `catalogimport.legacy-config.max-bundle-bytes` (default 2 MB) |
| 400 | `LEGACY_BUNDLE_INVALID` | ongeldige JSON, ontbrekende/onbekende sleutel, verkeerd type, niet-integer getal, patroon/lengte, `SECRET_IN_DETAILS`, `DUPLICATE`, statusregel (§1) geschonden, ongeldige UTF-8 of ongepaarde surrogate. De melding bevat enkel een JSON-pad (`$.interface.details[3].label`, met de index uit de ontvangen volgorde) en een regelcode (`REQUIRED`, `TYPE`, `UNKNOWN_FIELD`, `PATTERN`, `LENGTH`, `RANGE`, `DUPLICATE`, `SECRET_IN_DETAILS`, `STATUS_MISMATCH`, `SYNTAX`), **nooit een waarde**. `SYNTAX` (pad `$` of de parserpositie) = geen leesbare JSON: ongeldige UTF-8, een BOM, afsluitende tekens na het document of een syntaxfout |
| 400 | `LEGACY_BUNDLE_VERSION_UNSUPPORTED` | `version != 1` |
| 400 | `LEGACY_BUNDLE_HASH_MISMATCH` | herberekende `contentHash` of `definitionContentHash` wijkt af (melding: welk van de twee, geen hashes of waarden) |
| 400 | `LEGACY_BUNDLE_SECRET_NOT_SUPPORTED` | **tijdelijk, tot slice S4:** de bundel is geldig maar `secret` is niet `null`; niets bewaard (§8) |

## 5. Versiebeleid

`version = 1` is gesloten: velden toevoegen, verwijderen of de canonicalisatie wijzigen kan enkel met `version = 2` (en een nieuwe
golden fixture). Een ontvanger accepteert alleen de versies die hij kent.

## 6. Canonicalisatie en hashes

### 6.1 Canonieke JSON-serialisatie (`canon(x)`)

Gedefinieerd over een geparste JSON-boom; het resultaat is een tekenreeks die als **UTF-8** (zonder BOM) gehasht wordt.

1. **Geen witruimte** buiten strings (geen spaties, geen nieuwe regels, geen trailing newline).
2. **Objecten:** `{` + leden gescheiden door `,` + `}`; elk lid is `canon-string(sleutel)` `:` `canon(waarde)`. Leden worden gesorteerd
   op sleutel in **UTF-16 code unit-volgorde** (vergelijking van de reeks 16-bit code units, numeriek; dit is `String.compareTo` in
   Java). Sleutels zijn uniek. Leden met waarde `null` worden **niet weggelaten**.
3. **Arrays:** `[` + elementen gescheiden door `,` + `]`, in de gesorteerde volgorde van §6.2 (of onveranderd waar vermeld).
4. **Strings:** `"` + tekens + `"`, **zonder enige normalisatie** (geen trim, geen Unicode-normalisatie; lege string blijft `""` en is **niet**
   gelijk aan `null`). Escaping, uitsluitend:
   `"` -> `\"`, `\` -> `\\`, U+0008 -> `\b`, U+0009 -> `\t`, U+000A -> `\n`, U+000C -> `\f`, U+000D -> `\r`, elk ander teken < U+0020 ->
   `\u00xx` met **kleine** hexletters (4 cijfers). **Alle andere tekens (ook `/`, U+007F, niet-ASCII) staan letterlijk, onge-escaped**; teken
   boven U+FFFF als gewone UTF-8-reeks van het volledige code point (geen surrogate-escapes).
5. **Getallen:** enkel integers, in decimale notatie zonder voorloopnullen, zonder `+`, zonder breuk of exponent; `0` is `0`, negatief met `-`
   (`-0` bestaat niet). Een niet-integer getal (`1.0`, `1e2`) in de bundel is ongeldig (`TYPE`); bereik: signed 32-bit.
6. **Booleans:** `true` / `false`. **Null:** `null`.
7. Hex-uitvoer van een hash: SHA-256 over de UTF-8-bytes, **kleine letters**, 64 tekens.

### 6.2 Sortering van arrays (vóór serialisatie)

Vergelijking van strings = UTF-16 code unit-volgorde (zoals 6.1.2); getallen numeriek; sleutels van laag naar hoog; stabiel is niet
vereist omdat de laatste tie-breaker een totale orde geeft. **Laatste tie-breaker** in elke sortering: de `canon()`-tekenreeks van het
element zelf, vergeleken in UTF-16 code unit-volgorde.

| Array | Sorteersleutels (in volgorde) |
|---|---|
| `interface.details` | `sequence`, `label`, `value` |
| `interface.omittedSecretLabels` | de string zelf |
| `definition.lines` | `number`, `column`, `position`, `length`, `field` |
| `definition.maps` | `number`, `key`, `value`, `reserveAlpha` |
| `definition.excludes` | `supplier`, `supplierGroup`, `discountCode`, `supplierReference` |
| `interface.header.weekdays` | **niet sorteren** (positie = weekdag) |

Alle andere arrays (er zijn er geen) behouden hun volgorde.

### 6.3 `contentHash`

`contentHash = hex(SHA-256(UTF-8(canon(H))))` met `H` het object

```
{ "interface": <interface>, "definitionStatus": <string>, "definition": <definition of null> }
```

(Sleutelvolgorde in `H` komt uit de sortering, dus: `definition`, `definitionStatus`, `interface`.) Alle onderliggende velden doen mee,
inclusief `definition.code` en `definition.description`, `omittedSecretLabels` en `ftpCredentialsRedacted`. **Niet in de hash:** `schema`, `version`,
`origin`, `exportedAt`, `exporter`, `contentHash`, `definitionContentHash`, `secret`. Bij `definitionStatus != PRESENT` is `definition` = `null`
(`"definition":null` staat in de canonieke tekst).

### 6.4 `definitionContentHash`

Enkel als `definitionStatus = PRESENT`: `hex(SHA-256(UTF-8(canon(D))))` met `D` = `definition` **zonder** de leden `code` en `description`
(overblijvend: `deleteCode`, `excludes`, `lines`, `maps`, `replacementCode`, `updateFile`), met de sortering van §6.2. Anders: JSON `null`.

### 6.5 Controle door de ontvanger

CatalogImport herberekent beide hashes uit de ontvangen (gevalideerde) bundel met dit algoritme en vergelijkt met `contentHash` /
`definitionContentHash`; afwijking = 400 `LEGACY_BUNDLE_HASH_MISMATCH`. De verzender berekent op de bundel **na** redactie (§2.1).

### 6.6 Referentie-uitkomst

Zie `expected-hashes.json` naast de fixtures. Een implementatie is conform als ze voor `golden-bundle-v1.json` en
`golden-bundle-v1-missing-definition.json` exact de daar vermelde hashes berekent, onafhankelijk van sleutelvolgorde of witruimte in het bestand.
De fixture `golden-bundle-v1.json` oefent bewust: niet-ASCII (`é`, `–`), `"`, `\` en een tabteken in `header.info`; ongesorteerde `details`;
meerdere `maps` met hetzelfde `number`; een `includeExclude`-regel; een secret met de letterlijke canary-waarde `canary-golden-not-a-secret`.

## 7. Herkomst en gedeelde definities (verwijzing)

Gedeelde definities worden enkel door CatalogImport herkend (zelfde `(environment, dossier)` en zelfde `definition.code` of
`definitionContentHash`); zie `legacy-config-bundle-design.md` §5 en het M1-besluit in `docs/decisions.md`. De job herkent nooit zelf iets.

## 8. Antwoord van de push

`POST /api/catalog-import/legacy-config/receipts` (enkel de technische client, `legacy-config-bundle-design.md` §3) antwoordt bij
succes met één JSON-object (beslissingslog 2026-10-20 "Legacy-config S1: idempotentie tegenover de laatste ontvangst"):

| Veld | Type | Betekenis |
|---|---|---|
| `receiptId` | integer | id van de nieuwe ontvangst (`RECEIVED`) of van de laatste ontvangst waarmee de push gelijk is (`UNCHANGED`) |
| `outcome` | string | `RECEIVED` of `UNCHANGED` |
| `status` | string | huidige status van die ontvangst: `RECEIVED`, `APPLIED`, `SUPERSEDED` of `DISCARDED` (bij `UNCHANGED` nooit `SUPERSEDED`, want de laatste ontvangst is dat nooit) |
| `contentHash` | string | de (herberekende) `contentHash` van die ontvangst |
| `supersededReceiptId` | integer of `null` | enkel bij `RECEIVED`: de open ontvangst die hierdoor `SUPERSEDED` werd; anders `null` |

**Idempotentie tegenover de laatste ontvangst.** Een push wordt enkel vergeleken met de **laatste** ontvangst (hoogste id) van dezelfde
herkomst `(origin.environment, origin.dossier, interface.code)`, ongeacht haar status.

| HTTP | `outcome` | Wanneer | Effect |
|---|---|---|---|
| 201 | `RECEIVED` | geen eerdere ontvangst, of de laatste heeft een andere `contentHash` | nieuwe ontvangst `RECEIVED`; een open ontvangst van de herkomst wordt `SUPERSEDED` (`supersededReceiptId` ingevuld); `APPLIED`/`DISCARDED` blijven ongemoeid (`supersededReceiptId = null`) |
| 200 | `UNCHANGED` | de laatste ontvangst heeft dezelfde `contentHash` | niets geschreven; `receiptId` en `status` zijn die van de laatste ontvangst |

Een terugkerende inhoud (A → B → A) is dus een nieuwe ontvangst A'. Invariant: de laatste ontvangst is nooit `SUPERSEDED`; een open
ontvangst is altijd de laatste. Gelijktijdige pushes van dezelfde herkomst leveren hoogstens één nieuwe ontvangst per inhoud op.

**Fouten** hebben de vorm `{"error": <tekst zonder waarden>, "code": <code>}`:
401 `AUTHENTICATION_REQUIRED` (geen of ongeldig token; ook een browsersessie), 403 `ACCESS_DENIED` (geldig token, maar `aud`, `azp` of
client role klopt niet), 413 `LEGACY_BUNDLE_TOO_LARGE`, 400 `LEGACY_BUNDLE_INVALID` / `LEGACY_BUNDLE_VERSION_UNSUPPORTED` /
`LEGACY_BUNDLE_HASH_MISMATCH` (§4), en tot S4 400 `LEGACY_BUNDLE_SECRET_NOT_SUPPORTED`. De verzender herhaalt een 4xx niet (behalve 401
eenmaal met een nieuw token); 5xx en IO-fouten mag hij herhalen, een herhaling is veilig dankzij de idempotentie hierboven.
