# Legacy-config-bundle v1 — normatief contract

> **Status: normatief (S0, 2026-10-20).** Uitwerking van `legacy-config-bundle-design.md` §1; bij conflict wint dit document voor
> het bundelformaat en de hashes. Twee onafhankelijke implementaties (ProdisDatasyncEngine `CanonicalJson`, CatalogImport
> `LegacyBundleCanonicalHash`) moeten hiermee byte-identieke hashes geven. Referentie: de golden fixtures in
> `Web/src/test/resources/legacy-config/` (ImportService) en `sync-core/src/test/resources/legacy-config/` (ProdisDatasyncEngine),
> byte-identiek in beide repo's.
> Trefwoorden MOET/MAG NIET zijn normatief.
>
> **S4-aanvulling (2026-10-20):** een bundel met `secret` wordt aanvaard (de tijdelijke code `LEGACY_BUNDLE_SECRET_NOT_SUPPORTED`
> vervalt); `secret.value` is hoogstens 1024 tekens (de grens van de credentialopslag); zonder sleutelring bij de ontvanger:
> 409 `SECRETS_NOT_CONFIGURED`. Geen wijziging van formaat, canonicalisatie of hashes (golden hashes ongewijzigd), dus nog altijd
> `version = 1`: tot S4 werd geen enkele bundel met secret aanvaard.
>
> **S4-vervolg (2026-10-21, beslissingslog "Legacy-config S4-vervolg"):** (a) de userinfo-redactie gebeurt ook op de samengestelde
> waarde `Trim(FTP) & Trim(FTP2)` (§2.1 regel 3b/3c) en de ontvanger weigert userinfo in die samengestelde waarde (§2.1 regel 4);
> (b) een secret bij `UNCHANGED` wordt toch overgenomen als de herkomst nog geen credential heeft (§2.1 regel 7, §8). Binnen v1: geen
> nieuw veld, geen andere canonicalisatie; de golden fixtures en hun hashes blijven ongewijzigd (hun `FTP` heeft geen userinfo en er
> is geen `FTP2`). Een bundel die vroeger aanvaard werd met userinfo verdeeld over `FTP`/`FTP2`, wordt nu geweigerd: dat is precies het
> lek dat hiermee dicht gaat.

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
| `secret` | object of `null` | ja | `{ "label": "FTP_password", "value": <string, niet blanco, hoogstens 1024 UTF-16-tekens> }` of `null`; geen ander label en geen andere sleutel toegestaan (400 `LEGACY_BUNDLE_INVALID`, regel `PATTERN`, `LENGTH` of `UNKNOWN_FIELD`) |

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
3. **URL-userinfo.** Definities: een tekst `x` *is een URL* als `^([A-Za-z][A-Za-z0-9+.-]*://)` erop matcht; de *authority* is
   dan de tekst na `://` tot het eerste teken van `/`, `?`, `#` of het einde; `x` *heeft userinfo* als die authority een `@` bevat.
   *Userinfo verwijderen* = alle tekens vanaf het begin van de authority tot en met de **laatste** `@` in de authority weghalen
   (`sftp://klant:geheim@host/dir` wordt `sftp://host/dir`). De verzender past toe, in deze volgorde, op de reeds getrimde waarden
   (§2):
   - **3a. Per regel:** elke INDET-regel met label exact `FTP` of `FTP2` waarvan de waarde userinfo heeft, krijgt de waarde zonder
     userinfo.
   - **3b. Samengesteld** (legacy plakt de bestemming als `Trim(FTP) & Trim(FTP2)`, `Prg_1232` r.300): neem `f` = de waarde van de
     `FTP`-regel en `g` = die van de `FTP2`-regel na 3a (een ontbrekend label = `""`) en `C = f + g`. Heeft `C` userinfo, bepaal dan
     in `C` het bereik `[s, k]` = begin van de authority t/m de laatste `@` erin. Elke regel houdt **zijn eigen** tekens buiten dat
     bereik: `f'` = de tekens van `f` (posities `0 .. |f|-1` in `C`) die niet in `[s, k]` liggen, `g'` = de tekens van `g` (posities
     `|f| .. |C|-1`) die niet in `[s, k]` liggen; beide in hun oorspronkelijke volgorde, **niet** opnieuw getrimd. Een label dat er
     niet was, wordt niet toegevoegd (zijn `""` blijft `""`). Voorbeeld: `FTP = "sftp://klant:geh"`, `FTP2 = "eim@host/x"` →
     `C = "sftp://klant:geheim@host/x"`, `[s, k] = [7, 19]` → `FTP = "sftp://"`, `FTP2 = "host/x"` (samen `sftp://host/x`). Zit de
     userinfo volledig in `FTP`, dan geeft 3b hetzelfde als 3a (en verandert `FTP2` niet).
   - **3c. Dubbele labels:** komt `FTP` of `FTP2` meer dan eens voor, dan wordt 3b herhaald op de eerste combinatie (`FTP`-regel,
     `FTP2`-regel) die nog userinfo heeft, beide in oplopende `sequence` (`(sequence, label)` is uniek) met de `FTP`-regels als
     buitenste lus, tot geen enkele combinatie nog userinfo heeft. Dat eindigt altijd: elke stap verwijdert minstens de `@`.
   `ftpCredentialsRedacted = true` zodra 3a of 3b iets verwijderde. De geredigeerde waarden staan in `details` en zijn wat gehasht
   wordt.
4. De ontvanger MOET weigeren (400 `LEGACY_BUNDLE_INVALID`, regel `SECRET_IN_DETAILS`) als `details` een label bevat dat de
   redactieregex matcht (pad `…details[i].label`), een `FTP`/`FTP2`-waarde met userinfo (pad `…details[i].value`), of een combinatie
   (`FTP`-regel, `FTP2`-regel) — een ontbrekend label telt als `""` — waarvan `Trim(f) + Trim(g)` userinfo heeft (pad: de waarde van
   de `FTP2`-regel van die combinatie, of die van de `FTP`-regel als er geen `FTP2` is). `Trim` = witruimte vooraan en achteraan weg;
   CatalogImport toetst zowel Java `trim()` als `strip()`.
5. `secret.value` verschijnt nooit in logs, foutberichten, `toString`, het opgeslagen `bundle_json` of de hashes. Aan de kant
   van de verzender ook nooit in jobparameters, execution context of foutbestanden; enkel in de request-body over TLS.
6. **Waarde (S4):** de verzender trimt Magic-padding (zoals elk tekstveld, §2); een lege waarde na trim = `secret = null`. Komt
   `FTP_password` meer dan eens voor met verschillende niet-lege waarden, dan wordt de bundel niet gebouwd (de verzender kiest nooit).
7. **Ontvanger (S4, verfijnd in het S4-vervolg 2026-10-21):** het secret wordt meteen versleuteld in de credentialopslag bewaard
   als de herkomst nog **geen** credential heeft en de bestemming SFTP met een geldige host is (zie `legacy-config-bundle-design.md`
   §2 "Credential bij ontvangst"), zowel bij een nieuwe ontvangst (`RECEIVED`) als bij `UNCHANGED`. Het secret telt niet mee in de
   hash: een secret dat later verschijnt zonder andere inhoudswijziging (bv. de inhoud kwam eerst zonder secret binnen), levert
   `UNCHANGED` en wordt dan toch overgenomen. Heeft de herkomst al een credential (ook ingetrokken), dan wordt het secret
   weggegooid, ook als het verschilt: een bestaande credential wordt nooit vervangen (aanname A1: vervangen is een menselijke actie).

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

Volgorde: (1) bodygrootte, (2) JSON-parse, (3) `schema`/`version`, (4) structuur/types/regels, (5) hashcontrole, (6) secret
zonder sleutelring. Eerste fout wint.

| HTTP | Code | Wanneer |
|---|---|---|
| 413 | `LEGACY_BUNDLE_TOO_LARGE` | body > `catalogimport.legacy-config.max-bundle-bytes` (default 2 MB) |
| 400 | `LEGACY_BUNDLE_INVALID` | ongeldige JSON, ontbrekende/onbekende sleutel, verkeerd type, niet-integer getal, patroon/lengte, `SECRET_IN_DETAILS`, `DUPLICATE`, statusregel (§1) geschonden, ongeldige UTF-8 of ongepaarde surrogate. De melding bevat enkel een JSON-pad (`$.interface.details[3].label`, met de index uit de ontvangen volgorde) en een regelcode (`REQUIRED`, `TYPE`, `UNKNOWN_FIELD`, `PATTERN`, `LENGTH`, `RANGE`, `DUPLICATE`, `SECRET_IN_DETAILS`, `STATUS_MISMATCH`, `SYNTAX`), **nooit een waarde**. `SYNTAX` (pad `$` of de parserpositie) = geen leesbare JSON: ongeldige UTF-8, een BOM, afsluitende tekens na het document of een syntaxfout |
| 400 | `LEGACY_BUNDLE_VERSION_UNSUPPORTED` | `version != 1` |
| 400 | `LEGACY_BUNDLE_HASH_MISMATCH` | herberekende `contentHash` of `definitionContentHash` wijkt af (melding: welk van de twee, geen hashes of waarden) |
| 409 | `SECRETS_NOT_CONFIGURED` | de bundel is geldig en draagt een `secret`, maar de ontvanger heeft geen sleutelring: de hele push wordt geweigerd (ook als hij `UNCHANGED` zou zijn), niets bewaard. (De S1-code `LEGACY_BUNDLE_SECRET_NOT_SUPPORTED` bestaat sinds S4 niet meer.) |

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
| 200 | `UNCHANGED` | de laatste ontvangst heeft dezelfde `contentHash` | geen nieuwe ontvangst; `receiptId` en `status` zijn die van de laatste ontvangst. Enige schrijfactie (S4-vervolg): draagt de push een secret, heeft de herkomst nog geen credential en is de bestemming SFTP met geldige host, dan ontstaat de credential (op de herkomst, en op die laatste ontvangst `secretOutcome = STORED` + credential, tenzij ze al `STORED` is); anders niets geschreven |

Een terugkerende inhoud (A → B → A) is dus een nieuwe ontvangst A'. Invariant: de laatste ontvangst is nooit `SUPERSEDED`; een open
ontvangst is altijd de laatste. Gelijktijdige pushes van dezelfde herkomst leveren hoogstens één nieuwe ontvangst per inhoud op.

**Fouten** hebben de vorm `{"error": <tekst zonder waarden>, "code": <code>}`:
401 `AUTHENTICATION_REQUIRED` (geen of ongeldig token; ook een browsersessie), 403 `ACCESS_DENIED` (geldig token, maar `aud`, `azp` of
client role klopt niet), 413 `LEGACY_BUNDLE_TOO_LARGE`, 400 `LEGACY_BUNDLE_INVALID` / `LEGACY_BUNDLE_VERSION_UNSUPPORTED` /
`LEGACY_BUNDLE_HASH_MISMATCH` (§4), 409 `SECRETS_NOT_CONFIGURED` (§4; sinds S4, vervangt de S1-code 400
`LEGACY_BUNDLE_SECRET_NOT_SUPPORTED`). De verzender herhaalt een 4xx niet (behalve 401 eenmaal met een nieuw token); 5xx en IO-fouten
mag hij herhalen, een herhaling is veilig dankzij de idempotentie hierboven (een herhaalde push met secret is `UNCHANGED` en maakt
nooit een tweede credential).

Het antwoord bevat bewust geen `secretOutcome` (ongewijzigd contract); die staat op de ontvangst (`GET /receipts/{id}`, MANAGE).
