# Proefinlezing (trial read) — contract NT-9a

Status: bindend contract voor NT-9 (denker-gemiddeld, 2026-09-30; door de hoofdsessie overgenomen, geen §6-vragen).
Bronnen: `docs/decisions.md` 2026-09-30 (V1 = C+A+D, V6, V7, A4; NT-8 incl. beperking "één CONFIG_* per fabriek").

## 0. Feiten waarop het contract rust (code, 2026-09-30)

1. Screening: configuratie via twee fabrieken (`DeliveryScreeningService` ~546-548), streamen met `StagingSink` (~656-748), identiteit/drempels
   daarna in de databank (~923-947).
2. Herbruikbare rijlogica: beslisboom per record (~697-734: filter → normaliseren → valid of issue) en tellerregels `addIssue` (~814-842: ERROR =
   `rejected`, of `errorBeforeFilter` bij fout vóór filter én er zijn filters; `CriticalLineCounter`; `IssueTally`).
3. Structuurblokkades = `ScreeningBlockedException` in `CsvRecordStreamer.read` of in `sink.record` (`CONFIG_FIELD_NOT_RESOLVED`,
   `CONFIG_DISCOUNT_FIELD_MISSING`, `CONFIG_MAPPING_SOURCE_UNRESOLVED`, `FILTER_COLUMN_MISSING`). Bij blokkade tijdens lezen zijn de tellers `null`, nooit 0.
4. Voorrang: config vóór lezen → leesblokkades → `SOURCE_NO_DATA_RECORDS` → `IDENTITY_HASH_COLLISION` → `DUPLICATE_IDENTITY_IN_DELIVERY` → drempels
   (kritiek wint van verworpen).
5. `ThresholdEvaluator` is puur; scope = `raw - filteredOut`; `criticalRecordCount = criticalLineCount + identityIncidentCount` (identiteitsincidenten
   hangen aan de bronstaat).
6. Creatiedrempel vraagt bronstaat (`countActiveByImportLinkId`, `countCreationCandidates`).
7. Ook bronstaat-/historiekafhankelijk: prijsafwijking (E3), referentiecontrole (E2), hash-collisie tegen bronstaat, manifesttellingen.
8. Duplicaatidentiteit: `CandidateStageDao.countDuplicateRows` telt inclusief eerste voorkomst, enkel over geldige in-scope kandidaten.
9. Groepering: `GROUP_MIN_OCCURRENCES = 10`; bulk: `aantal*100 > bulk_incident_share_percent * scope` (GENERIC, scope `raw - filtered`).
10. `linkId` beïnvloedt het lezen enkel via de vaste valuta (`SourceStructureConfig.linkDefaultCurrency`; `CONFIG_LINK_CURRENCY_INVALID`).
11. Bladwijzerwaarden van de koppeling spelen geen rol bij het parsen.
12. `ReadSummary` bevat geen headerkolomnamen → additieve sinkhook nodig.
13. Decodering is stil (U+FFFD); een verkeerde tekenset geeft ook in de screening geen fout.
14. Multipartlimiet `CATALOG_MAX_UPLOAD_SIZE` (1 GB); `MaxUploadSizeExceededException` → 413 zonder `code`.

## 1. Request

`POST /api/catalog-import/revisions/{revisionId}/trial-reads`, `multipart/form-data`, `@RequiresPermission(MANAGE)`, niet achter de setup-vlag.
- `revisionId`: DRAFT, ACTIVE of SUPERSEDED (geen 409 op status).
- `file` (verplicht; `required=false` + eigen 400 `FILE_REQUIRED`).
- `linkId` (optioneel): enkel voor de vaste valuta. Zonder `linkId` → SYSTEM_DEFAULT (EUR), expliciet in het antwoord.
- Geen `deliveryReference`, `uploadedBy`, verwachte aantallen.

## 2. Antwoord (altijd 200 bij een voltooide proef, ook als de levering zou blokkeren)

Structuur- en configfouten zijn het resultaat, geen HTTP-fout. Tellers `Long` of `null` (`null` = niet vastgesteld, nooit stil 0).

```json
{
  "revisionId": 12, "revisionNumber": 1, "revisionStatus": "DRAFT|ACTIVE|SUPERSEDED",
  "linkId": 5,
  "file": { "byteSize": 123456, "sha256": "hex", "fileName": "…" },
  "currencyDefault": { "value": "EUR", "origin": "LINK_DEFAULT|SYSTEM_DEFAULT" },
  "verdict": {
    "result": "WOULD_BLOCK|NO_BLOCKER_FOUND",
    "blockedCode": "…|null", "blockedReason": "…|null",
    "fieldName": "…|null", "sourceValue": "…|null", "expectedValue": "…|null",
    "stage": "CONFIGURATION|READING|FILE_LEVEL|IDENTITY|THRESHOLD|null"
  },
  "counters": {
    "rawRecordCount": 0, "validRecordCount": 0, "rejectedRecordCount": 0,
    "filteredOutCount": 0, "errorBeforeFilterCount": 0, "criticalLineCount": 0,
    "duplicateIdentityCount": 0, "scopeRecordCount": 0,
    "physicalLineCount": 0, "prefixLineCount": 0, "skippedBlankLineCount": 0, "columnCount": 0,
    "linesWithReplacementCharacter": 0,
    "issueOccurrencesBySeverity": { "CRITICAL": 0, "ERROR": 0, "WARNING": 0, "INFO": 0 }
  },
  "header": {
    "referenceKind": "HEADER_NAME|COLUMN_INDEX", "hasHeader": true, "headerLineNumber": 1,
    "expectedColumnCount": null, "foundColumnCount": 9, "foundColumns": ["…"],
    "expectedColumns": [ { "reference": "…", "role": "IDENTITY|PRICE|CURRENCY|DESCRIPTION|DISCOUNT|MAPPING|FILTER",
                           "required": true, "foundAtPosition": 3 } ],
    "missingRequired": ["…"], "missingOptionalFilterColumns": ["…"],
    "extraColumns": [ { "name": "…", "position": 9 } ],
    "shifted": [ { "reference": "…", "expectedPosition": 2, "foundPosition": 3 } ]
  },
  "sampleRows": [ {
    "lineNumber": 2, "status": "VALID|REJECTED|FILTERED_OUT|UNREADABLE",
    "rawValues": ["…"], "sourceValue": "…|null",
    "interpreted": {
      "supplier": "…", "supplierGroup": "…", "supplierReference": "…",
      "discountCode": "…|null", "discountState": "NOT_USED|EMPTY|VALUE",
      "identityHash": "hex",
      "basePriceRaw": "12,50", "basePrice": "12.500000",
      "currency": "EUR", "currencyOrigin": "SOURCE|LINK_DEFAULT|SYSTEM_DEFAULT",
      "description": "…",
      "mappedFields": { "<targetFieldCode>": "…" },
      "priceComponents": [ { "componentCode": "…", "sourceAmount": "…", "percentage": "…", "currency": "…", "status": "…" } ],
      "references": [ { "referenceType": "…", "valueRaw": "…", "valueNormalised": "…|null" } ]
    },
    "filter": { "kind": "IN_SCOPE|FILTERED_OUT|REJECTED", "decidingSequenceNumber": 1 },
    "issues": [ { "code": "…", "severity": "…", "fieldName": "…", "sourceValue": "…", "message": "…" } ]
  } ],
  "sampleRowsTruncated": true,
  "issueGroups": [ {
    "code": "…", "fieldName": "…|null", "severity": "…", "domain": "…", "controlLevel": "…",
    "deliveryEffect": "NONE|REVIEW|BLOCK", "occurrenceCount": 0,
    "grouped": true, "bulkIncident": false, "sharePercent": "…|null",
    "examples": [ { "lineNumber": 7, "fieldName": "…", "sourceValue": "…", "message": "…" } ],
    "examplesTruncated": false
  } ],
  "thresholds": {
    "scopeRecordCount": 0, "bulkIncidentSharePercent": "1",
    "critical": { "count": 0, "identityIncidentsEvaluated": false, "thresholdPercent": "1", "sharePercent": "…|null",
                  "outcome": "NOT_APPLICABLE|UNDETERMINED|WITHIN|EXCEEDED", "countIsLowerBound": true },
    "rejected": { "count": 0, "thresholdPercent": "…|null", "sharePercent": "…|null",
                  "outcome": "NOT_APPLICABLE|UNDETERMINED|WITHIN|EXCEEDED" }
  },
  "configProblems": [ { "code": "CONFIG_…", "message": "…", "fieldName": "…|null", "revisionField": "…|null" } ],
  "configChecksSkippedBecause": [ "CONFIG_…" ],
  "notEvaluated": [ { "check": "…", "status": "INFO", "reason": "…" } ],
  "columnPreview": {
    "status": "AVAILABLE|LAYOUT_INVALID|FILE_EMPTY|HEADER_BEYOND_PREVIEW",
    "layout": { "charset": "UTF-8|null", "delimiter": ";|null", "quoteChar": "\"|null", "hasHeader": true,
                "headerLineNumber": 1, "referenceKind": "HEADER_NAME|COLUMN_INDEX|null" },
    "headerLineNumber": 1,
    "columns": [ { "position": 1, "header": "…|null" } ],
    "rows": [ { "lineNumber": 2, "cells": ["…"], "unreadable": false } ],
    "columnsTruncated": false, "rowsTruncated": false
  }
}
```

Regels:
- **Tellers** 1-op-1 met de screening (`ImportBatch`-kolommen raw/valid/rejected/filtered_out/error_before_filter/critical_line/duplicate_identity);
  `physicalLineCount`, `prefixLineCount`, `skippedBlankLineCount`, `columnCount`, `linesWithReplacementCharacter` zijn proef-extra's.
  Leesblokkade → alle tellers `null` (uitz. `SOURCE_NO_DATA_RECORDS`: raw = 0). Configfout → alles `null`, secties leeg.
- **Verdict**: eerste treffer in de screeningvolgorde (feit 4). `blockedReason` = technische (Engelse) tekst voor "Technische details" (V7);
  de Nederlandse tekst komt uit de frontendwoordenlijst.
- **Drempels**: kritiek met `count = criticalLineCount` als ondergrens (identiteitsincidenten niet evalueerbaar): `EXCEEDED` is zeker een blokkade,
  `WITHIN` garandeert niets. Verworpen volledig evalueerbaar; `thresholdPercent=null` = niet geconfigureerd.
- **Duplicaat**: in geheugen, zelfde telvoorschrift als `countDuplicateRows`; grens `max-tracked-identities`; bij bereiken zonder gezien duplicaat
  → teller `null` en `notEvaluated` `TRACKING_LIMIT_REACHED`. Voorbeeld = latere voorkomst met "first occurrence on line X".
- **Issuegroepen**: per (code, logisch veld) via `IssueTally`; classificatie via `ImportIssueCatalog.classify`; groepen onder 10 blijven zichtbaar
  (`grouped=false`); `bulkIncident` met dezelfde formule; `occurrenceCount` altijd het werkelijke aantal; INFO-notices op geldige regels mee.
- **Voorbeeldrijen**: eerste N datarecords ongeacht lot; `interpreted` enkel voor VALID; prijs als `basePriceRaw` + `basePrice` (`toPlainString`),
  nooit gecorrigeerd; `rawValues` per cel gekapt op 200 tekens.
- **Header**: `missingRequired` = alle ontbrekende verplichte kolommen (via sinkhook), verdict blijft de eerste treffer; `extraColumns` alle
  niet-verwachte; bij COLUMN_INDEX of zonder header `foundColumns = null`.
- **configProblems**: bij een configuratiefout vóór het lezen alle onafhankelijke configuratiefouten in fabrieksvolgorde (NT-14, via
  `ChainConfigurationChecks.configurationReport`; item = `code`, `message`, `fieldName`, `revisionField`); `[0].code == verdict.blockedCode`.
  Afhankelijke controles die niet beoordeeld werden: codes van de grondoorzaken in `configChecksSkippedBecause` (leeg zonder). Een
  `CONFIG_*` uit de leesfase (bv. header) blijft één item.
- **notEvaluated** (INFO): `CREATION_POLICY` (NO_SOURCE_STATE), `IDENTITY_HASH_COLLISION_AGAINST_SOURCE_STATE` (NO_SOURCE_STATE),
  `REFERENCE_CONTROL` (NO_SOURCE_STATE, enkel bij gemapte referenties), `DUPLICATE_REFERENCE_IN_DELIVERY` (NOT_IMPLEMENTED_V1),
  `PRICE_DEVIATION` (NO_PRICE_HISTORY), `MANIFEST_COUNTS` (NO_MANIFEST), `IDENTITY_HASH_COLLISION_IN_FILE` (NOT_TRACKED),
  `IDENTITY_INCIDENTS_IN_CRITICAL_THRESHOLD` (NO_SOURCE_STATE). Geen `validationResult` in het antwoord.
- **columnPreview** (EB5/G3, beslissingslog 2026-10-23 "Editor-API-gaten"; additief): wat er in het begin van het bestand
  staat, zodat de bewerker een kolom aan een veld of filter kan koppelen (die koppeling doet de frontend tegen de actuele
  versie). Onafhankelijk van het verdict: ook bij een configuratiefout (bv. `CONFIG_PRICE_FIELD_MISSING`) of een
  headerblokkade (bv. `HEADER_FIELD_MISSING:*`) staat het er.
  - Technisch: een byte-tap tussen de hash en de streamer houdt de eerste ≤ `preview-max-bytes` bytes bij; ook het
    leeglezen ná een blokkade of zonder lezen (configuratiefout) loopt door de tap. Ná het lezen: decoderen met de
    tekenset van de revisie, BOM vooraan weg, regels splitsen zoals `BufferedReader.readLine` (`\n`, `\r\n`, `\r`),
    prefixregels vóór de header en volledig lege regels overslaan, splitsen met `ImportValueRules.parseCsvLine`.
    Streamer, screening en `sampleRows` blijven ongewijzigd.
  - `layout` komt uit `SourceStructureConfigFactory.layoutOf(revision)`: dezelfde regels S1-S6 als `from()` (formaat,
    tekenset, scheidingsteken, aanhalingsteken, headerregel, verwijzingswijze), maar zonder de veldconfiguratie. Een
    ongeldig onderdeel is `null`; `referenceKind = null` bij een onbekende waarde (geen leesvoorwaarde).
    `layout.headerLineNumber` is de geconfigureerde waarde.
  - `status`: `LAYOUT_INVALID` bij een ongeldig formaat, tekenset, scheidingsteken, aanhalingsteken of headerregel
    (kolommen en regels leeg; de oorzaak staat in `configProblems`); `FILE_EMPTY` bij 0 bytes;
    `HEADER_BEYOND_PREVIEW` wanneer de headerregel na het einde van het bestand of na het bekeken begin valt;
    anders `AVAILABLE`.
  - `headerLineNumber`: de gebruikte headerregel, `null` zonder header. `columns`: met header de headercellen (getrimd,
    zoals `header.foundColumns`); zonder header de posities 1..n met `header = null`, n = het grootste aantal cellen in
    de getoonde regels. Een headerregel die niet te parsen is (aanhalingsteken niet gesloten): `columns` leeg, de
    regels staan er wel.
  - `rows`: de eerste ≤ `preview-rows` niet-lege datalijnen, `lineNumber` fysiek. Een regel langer dan
    `screening.max-line-length` of die niet te parsen is: `unreadable = true`, `cells = null`. Een ander kolomaantal
    wordt getoond zoals gelezen (nooit aangevuld of afgekapt). Cellen en headernamen gekapt op 200 tekens; hoogstens
    `preview-max-columns` kolommen en cellen per regel (`columnsTruncated`).
  - `rowsTruncated`: er zijn meer datalijnen dan getoond, of het bestand is groter dan `preview-max-bytes` (de regel die
    op die grens afgebroken is, valt weg; nooit half getoond).
  - Een vastebreedtebestand (posities) bestaat niet: `structure_format` is enkel `CSV` (databasecheck en S1); een
    ander formaat geeft `LAYOUT_INVALID`.
  - Niets bewaard, niets gelogd (de ene INFO-logregel blijft zonder inhoud); hetzelfde bestand tegen dezelfde revisie
    geeft een byte-gelijk antwoord.

## 3. Grenzen en fouten

- Bestandsgrootte: bestaande multipartlimiet (geen extra property).
- Properties: `catalogimport.trial-read.sample-rows` (20; ≤0 → default), `catalogimport.trial-read.max-issue-examples-per-code` (5),
  `catalogimport.trial-read.max-tracked-identities` (2000000); `catalogimport.screening.max-line-length` hergebruikt.
- Kolomvoorbeeld (EB5): `catalogimport.trial-read.preview-rows` (10; ≤0 → default; > 50 → de applicatie start niet),
  `catalogimport.trial-read.preview-max-columns` (500; ≤0 → default), `catalogimport.trial-read.preview-max-bytes`
  (1048576 = 1 MiB; ≤0 → default; > 8 MiB → de applicatie start niet). Een grens wordt nooit stil verlaagd. Het
  geheugen per proef groeit met hoogstens `preview-max-bytes` (plus de gedecodeerde tekst daarvan).
- Streaming, nooit het hele bestand in geheugen. Configuratie in één korte leestransactie; lezen zonder open verbinding. Geen timeout-/
  gelijktijdigheidsgrens in v1.
- HTTP: 200 (elke voltooide proef, ook 0-bytes = `SOURCE_FILE_EMPTY`); 400 `FILE_REQUIRED`; 400 `LINK_NOT_OF_REVISION_DEFINITION`;
  403 `PERMISSION_DENIED`; 404 `REVISION_NOT_FOUND`; 404 `LINK_NOT_FOUND`; 413 zonder code (bestaand); 415 (Spring); 500 bij technische leesfout.
  Volgorde: MANAGE → file → revisie → linkId 404 → linkId 400 → lezen.

## 4. Pariteit

Per scenario: proef (met `linkId`) en echte upload van hetzelfde bestand op een verse koppeling zonder bronstaat; vergelijk tellers, `blockedCode`
(WOULD_BLOCK ⇔ BLOCKED; NO_BLOCKER_FOUND ⇔ SCREENED), gegroepeerde issuegroepen, CRITICAL/WARNING-aantallen. Scenario's: geldig, ongeldige prijs,
lege identiteitscomponent, kolomaantalfout, ontbrekende header, dubbele identiteit, bulk (10+ boven 1%), filters, kritieke drempel, verworpen drempel,
leeg bestand, enkel header, bookmark-mapping, vaste valuta, BOM. Plus unit-test op de gedeelde kern en reconciliatie
`raw = valid + rejected + errorBeforeFilter + filteredOut`.

## 5. Beveiliging en privacy

MANAGE; regel in `PermissionCoverageTest`; 403-test. Precies één INFO-logregel (revisieId, linkId, byteSize, raw, verdict, blockedCode) — geen
bestandsnaam, celwaarden, `blockedReason` of `sourceValue`. Lezen rechtstreeks uit `file.getInputStream()`; geen archief, geen entiteiten, enkel
read-only transactie. De server weet dus niet of er een proefinlezing was: de V6-waarschuwing is UI-staat (NT-10).

## 6. Idempotentie

Pure functie: zelfde bestand + revisie (+ linkvaluta) ⇒ byte-gelijk antwoord; geen tijdstempels of id's. Test: tweemaal gelijk; tellingen van
`delivery`, `delivery_file`, `import_batch`, `import_row_issue`, `import_issue_group`, `import_mutation`, `task_run`, `catalog_source_state` en de
archiefmap vóór = na.

## 7. Code en refactor

- `service.TrialReadService` (geneste antwoordrecords), `web.CatalogImportTrialReadController`, `service.support.RecordScreeningCore` (gedeelde
  beslisboom + tellerregels met callback `onCandidate` / `onIssue`).
- Refactor in `DeliveryScreeningService` zonder gedragswijziging, stap voor stap met de bestaande screeningtests groen:
  (4) additieve default-sinkhook `header(lineNumber, columns)` in `CsvRecordStreamer`; (1) beslisboom + tellerregels → `RecordScreeningCore`
  (`StagingSink` wordt adapter; staging/pendingRows/samplecaps blijven); (2) `loadConfiguration(revision, linkDefaultCurrency)`; (5) bulkregel in
  `IssueAggregationService` publiek extraheren; (3) optioneel voorranghelper kritiek/verworpen in `ThresholdEvaluator`.
- Niet aanraken: `DeliveryIntakeService`, `DeliveryReceptionService`, archief, `ChainConfigurationChecks`, `ImportLinkReadinessService`, schema.

## 8. Aannames

A-1 alle drie revisiestatussen; A-2 200 met WOULD_BLOCK; A-3 defaults 5 / 2000000; A-4 niet-geëvalueerde controles als INFO; A-5 stop bij eerste
blokkade; A-6 geen 413-handler, U+FFFD-teller opgenomen; A-7 alle onafhankelijke configfouten (NT-14); afhankelijke controles in `configChecksSkippedBecause`; A-8 geen gelijktijdigheidsgrens.
