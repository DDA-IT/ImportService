# CatalogImport — standaardflows

Stand: 2026-09-24. Wordt bijgewerkt zodra de resterende schermen klaar zijn. Hoofdhandleiding:
[`README.md`](README.md). Begrippen: [`begrippen.md`](begrippen.md).

## Lees dit eerst

- **UI-route** staat alleen bij een flow als het scherm bestaat (zie sectie 4 van de hoofdhandleiding).
  Anders staat er alleen de **API-route**.
- Alle API-voorbeelden zijn afgeleid van `scripts/scenario/manual-upload-scenario.sh` en de bestanden
  `scripts/scenario/levering-1.csv` en `levering-2.csv`. Er draaide bij het schrijven geen backend.
  De getallen zijn **afgeleid uit de CSV-bestanden en de code**, niet uit een opgeslagen uitvoer van het
  scenario. Waar ik iets niet kon bevestigen, staat **nog te verifiëren**.
- Variabelen voor de voorbeelden (bash). De basis-URL is poort 8081 (profiel `local`):

```bash
BASE=http://localhost:8081
A=$BASE/api/catalog-import          # gewone API
S=$BASE/api/catalog-import/setup    # setup-API (alleen met profiel demo)
J='Content-Type: application/json'
```

- In PowerShell: `curl.exe` in plaats van `curl`, en JSON tussen dubbele aanhalingstekens met `\"`.
- De actor (`uploadedBy`, `acceptedBy`, `decidedBy`, `frozenBy`, ...) is een zelf gekozen naam, niet
  `system` en niet leeg. Er is geen authenticatie.

De leverancierscode `SCN` en de referenties `S-1` ... `S-8` komen uit het scenario. Het scenario gebruikt
een unieke suffix (`SFX`, standaard een tijdstempel) in codes zodat u het herhaaldelijk kunt draaien.

**Overzicht**

| Flow | Onderwerp | Status |
| --- | --- | --- |
| [1](#flow-1--nieuwe-leverancier-of-koppeling-inrichten) | Nieuwe leverancier/koppeling inrichten | Alleen via API (achter de setup-vlag) |
| [2](#flow-2--handmatig-een-csv-uploaden-en-het-resultaat-lezen) | CSV uploaden en resultaat lezen | Alleen via API (uploadscherm in aanbouw) |
| [3](#flow-3--herupload-en-idempotentie) | Herupload en idempotentie | Alleen via API |
| [4](#flow-4--eerste-levering-aanvaarden-als-nulmeting-en-daarna-een-delta-levering) | Nulmeting en delta-levering | Alleen via API |
| [5](#flow-5--batch-in-een-publicatiebundel-beslissen-controleren-bevriezen-annuleren) | Bundel, beslissen, bevriezen, annuleren | Deels beschikbaar in de UI |
| [6](#flow-6--een-geblokkeerde-of-mislukte-batch-onderzoeken-en-hervatten) | Geblokkeerd/mislukt onderzoeken en hervatten | Alleen via API |
| [7](#flow-7--de-werkvoorraad-gebruiken) | Werkvoorraad gebruiken | Beschikbaar |
| [8](#flow-8--back-up-en-hersteltest-draaien) | Back-up en hersteltest | Scripts (ops) |

---

## Flow 1 — Nieuwe leverancier of koppeling inrichten

**Status: Alleen via API.** Er is geen inrichtingsscherm; een productiewaardig beheerscherm volgt pas na
Fase 5/Keycloak (`docs/decisions.md`, 2026-09-22).

**Doel:** een keten aanmaken waarmee u bestanden van een nieuwe leverancier kunt uploaden.

**Voorwaarden:**

- De backend draait met het profiel `demo` (of de vlag `catalogimport.setup-api.enabled=true`). Zonder die
  vlag antwoordt elk `/setup`-pad met 404. **Zet de vlag nooit aan met echte gegevens**: er is geen
  authenticatie en wie de API bereikt, kan drempels bepalen en dus de controle uitschakelen.
- Vereist voor een werkende keten, in deze volgorde: **bronorganisatie** → **definitie** → **revisie** (met
  mappings) → revisie **activeren** → **koppeling** → **MANUAL-taak**. Zonder actieve revisie geeft een
  upload 409 `NO_ACTIVE_REVISION`; zonder taak is er geen `taskId` om naar te uploaden.

### 1A. Via de setup-API (rechtstreeks)

Neem de `id` uit elk antwoord over naar de volgende stap. Onderstaande volgorde en velden komen uit het
scenarioscript (`$OC` is een unieke bronorganisatiecode, `$DEF`, `$REV`, `$LINK` zijn ids uit de antwoorden).

1. **Bronorganisatie:**
   ```bash
   curl -X POST $S/source-organisations -H "$J" -d '{"code":"SCN1","name":"Scenario 1","type":"SUPPLIER"}'
   ```
2. **Definitie** (`usageType` `OWN_DEFINITION` voor een eigen definitie):
   ```bash
   curl -X POST $S/definitions -H "$J" -d '{"sourceOrganisationCode":"SCN1","code":"SCN1-CSV","name":"Scenario 1","usageType":"OWN_DEFINITION"}'
   ```
3. **Revisie** (start als `DRAFT`). Het scenario gebruikt een CSV met `;`, een header, een driedelige
   identiteit en percentages voor de drempels:
   ```bash
   curl -X POST $S/definitions/$DEF/revisions -H "$J" -d '{"delimiter":";","hasHeader":true,"identityProfileKind":"THREE_PART","supplierField":"leverancier","supplierGroupField":"groep","supplierReferenceField":"referentie","basePriceField":"prijs","descriptionField":"omschrijving","currencyField":"valuta","canonicalisationVersion":2,"creationThresholdSharePercent":10,"maxCriticalSharePercent":25}'
   ```
   De drempels 10 en 25 zijn bewust ruimer dan de standaard (1): bij een klein bestand valt bij 1% elke
   creatie of fout boven de drempel (zie sectie 6.5 van de hoofdhandleiding).
4. **Mappings** (extra kolommen, bv. de EAN). Alleen mogelijk op een `DRAFT`-revisie:
   ```bash
   curl -X POST $S/revisions/$REV/mappings -H "$J" -d '{"targetFieldCode":"EAN","sourceReference":"ean","sequenceNumber":1}'
   ```
   Filters (`/revisions/{id}/filters`) en kritiek-overrules (`/revisions/{id}/field-criticality`) bestaan
   ook, maar het scenario gebruikt ze niet.
5. **Revisie activeren** (bevriest ze; een vorige actieve revisie wordt `SUPERSEDED`):
   ```bash
   curl -X POST $S/revisions/$REV/activate -H "$J" -d '{"approvedBy":"beheerder@example.test"}'
   ```
6. **Koppeling** (leverancier + bibliotheek; `PSARF012` is de bibliotheekcode uit het scenario):
   ```bash
   curl -X POST $S/links -H "$J" -d '{"definitionId":'$DEF',"code":"SCN1-LINK","name":"Scenario 1","supplierCode":"SCN1","libraryCode":"PSARF012"}'
   ```
7. **Taak** (het aanmaken van de taak levert een `MANUAL`-taak; zie de opmerking hieronder):
   ```bash
   curl -X POST $S/tasks -H "$J" -d '{"linkId":'$LINK',"name":"Scenario manuele levering"}'
   ```
8. **Controle**: `curl $S/overview` toont de hele boom met het `taskId`. Alleen-lezen en altijd bereikbaar
   (ook zonder setup-vlag): `curl "$A/tasks"` (taken), `curl "$A/import-links"` (koppelingen).

**Verwachte uitkomst:** een `taskId` waar u naartoe kunt uploaden (flow 2).

**Veelvoorkomende fouten:**

| Melding | Oorzaak |
| --- | --- |
| 404 op `/setup/...` | setup-API staat uit (geen `demo`-profiel) |
| 409 `*_CODE_IN_USE` (bv. `DEFINITION_CODE_IN_USE`, `LINK_CODE_IN_USE`) | code bestaat al; kies een andere |
| 400/409 `CONFIG_*` bij mapping of activeren | de revisie is onvolledig of tegenstrijdig, bv. `CONFIG_FIELD_MAPPING_DUPLICATES_REVISION` (u mapt een veld dat de revisie al bepaalt) |
| 409 `REVISION_NOT_EDITABLE` | mappings/filters kunnen alleen op een `DRAFT` |
| `CONFIG_CANONICALISATION_VERSION_REQUIRED` | versie 2 is verplicht zodra een munt gelezen wordt of een referentie (EAN/PIM/CAB) gemapt is |

Voor een tweede leverancier met dezelfde bestandsopbouw hergebruikt u een eigen nieuwe keten (of het
sjabloon, zie 1B). Het **demoprofiel** maakt bij het opstarten al een volledige keten aan (`DEMO`,
`DEMO-CSV`, `DEMO-LINK`, taak *Demo manuele levering*) en logt de `taskId`.

### 1B. Via de sjabloonwizard-backend (materialisatie)

**Status: Alleen via API**, achter dezelfde setup-vlag. Een sjabloon is een herbruikbare definitie
(`usageType` `REUSABLE_TEMPLATE`) met bookmarks (invulvelden). Het endpoint `templates` maakt daaruit in één
transactie een leveranciersgebonden definitie, een revisie 1 (`DRAFT`) en een koppeling.

1. `GET $A/templates` — de beschikbare sjablonen.
2. `GET $A/templates/{definitionId}/revisions/{revisionId}/bookmarks` — welke bookmarks er ingevuld moeten
   worden (met een lijst `problems`).
3. `POST $A/templates/{definitionId}/materialisations` met o.a. `templateRevisionId`, `mode`
   (`NEW_DEFINITION` of `REUSE_DEFINITION`, verplicht, geen default), `definitionCode`, `definitionName`,
   `linkCode`, `linkName`, `supplierOrganisationCode`, `libraryCode`, `bookmarkValues`, `materialisedBy`.
   Bij `REUSE_DEFINITION` (+ `reuseDefinitionId`) ontstaat alleen een koppeling; een definitie met een
   per-leverancier waarde is niet deelbaar (409 `DEFINITION_NOT_SHAREABLE`).
4. **De materialisatie maakt géén taak** (beslissing 2026-09-23, V1). De revisie is `DRAFT`: activeer ze
   (stap 5 van 1A) en maak daarna een taak (stap 7 van 1A).
5. Een LINK-bookmarkwaarde later wijzigen: `PUT $BASE/api/catalog-import/links/{linkId}/bookmark-values/{name}`
   met `{"value":"...","updatedBy":"..."}`. Dat wordt met 409 `LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH`
   geweigerd zolang de koppeling een open batch heeft.

De exacte antwoordvelden van de materialisatie (onder meer de id's van de nieuwe definitie/revisie/koppeling)
heb ik niet nagelezen: **nog te verifiëren**. Zie `docs/design/sjabloon-materialisatie-design.md`.

---

## Flow 2 — Handmatig een CSV uploaden en het resultaat lezen

**Status: Alleen via API.** Het uploadscherm is in aanbouw.

**Doel:** een leveranciersbestand laten screenen en het oordeel lezen.

**Voorwaarden:** een keten uit flow 1 met een `MANUAL`-taak (`$TASK`), en een CSV met de kolommen van de
actieve revisie. Het voorbeeldbestand `scripts/scenario/levering-1.csv`:

```
leverancier;groep;referentie;omschrijving;prijs;valuta;ean
SCN;BOOR;S-1;Boormachine 500W;149,50;EUR;5411234600011
...
SCN;HAMER;S-6;Moker 2kg;abc;EUR;5411234600066    <- prijs onleesbaar
SCN;TANG;S-7;Combinatietang 180mm;15,75;EUR;5411234600073
```

Zeven datalijnen. Lijn S-6 heeft `abc` als prijs.

**Stappen:**

1. Zoek het `taskId` (`curl "$A/tasks"`, of `overview`, of de logregel bij het opstarten van het demoprofiel).
2. Upload het bestand met een zelfgekozen, unieke `deliveryReference`:
   ```bash
   curl -F "file=@scripts/scenario/levering-1.csv" -F "deliveryReference=SCN-1" -F "uploadedBy=uw.naam" \
        $A/tasks/$TASK/deliveries
   ```
   Het antwoord komt pas als de screening klaar is (synchroon). Bij een gewone uitkomst: **HTTP 201**.
3. Lees het antwoord (`deliveryId`, `batchId`, `status`, `blockedCode`, tellers). Verwacht voor dit bestand
   (afgeleid uit de CSV, **niet** uit een echte run):

   | veld | verwachte waarde | betekenis |
   | --- | --- | --- |
   | `status` | `SCREENED` | de verwerking is klaar |
   | `rawRecordCount` | 7 | zeven datalijnen |
   | `validRecordCount` | 6 | zes geldige regels |
   | `rejectedRecordCount` | 1 | S-6 (`PRICE_UNREADABLE`) |
   | `newCount` | 6 | alles is nieuw |
   | `contentMutationCount` | 6 | zes `CREATE`-mutaties (zonder marker) |

4. Lees de batch: `curl $A/batches/$BATCHID`. Verwacht: `validationResult` = `REVIEW_REQUIRED`
   (een kritieke regel met een onleesbare prijs, en wachtende creaties), `creationOutcome` = `INITIAL_LOAD`,
   `creationScopeCount` = 0 (er was niets om tegen af te wegen), `criticalLineCount` = 1,
   `awaitingApprovalCount` = 6. Dat de kritieke lijn uit de onleesbare prijs volgt, is de lezing van het
   voorbeeld in `README.md` (hoofdmap); ik heb ze niet zelf gerund (**nog te verifiëren**).
5. Lees de mutaties: `curl "$A/batches/$BATCHID/mutations?size=50"`. Zes `CREATE`-regels met status
   `AWAITING_APPROVAL` en `statusReason` `INITIAL_LOAD_REQUIRES_APPROVAL`, plus een `IMPORT_MARKER` (status
   `RECORDED`).
6. Lees de problemen: `curl $A/batches/$BATCHID/issues` (voorbeeldregels; hier `PRICE_UNREADABLE` op de
   regel van S-6, en de melding `INITIAL_LOAD_REQUIRES_APPROVAL`) en `curl $A/batches/$BATCHID/issue-groups`
   (leeg: een groep ontstaat pas vanaf 10 gelijksoortige vaststellingen).
7. Filter de mutaties zo nodig: `?status=PLANNED`, `?actionType=CREATE`,
   `?statusReason=INITIAL_LOAD_REQUIRES_APPROVAL` (exact, hoofdlettergevoelig), `?identityHash=<hex>`.

**Voorbeeld met een foute regel:** dat is precies het bovenstaande: S-6 heeft een onleesbare prijs. Die
regel wordt **verworpen** en nooit als prijs 0 opgeslagen. Een fout op een kritieke kolom betekent
`REVIEW_REQUIRED`; blijft het aandeel kritieke regels boven `maxCriticalSharePercent`, dan wordt de hele
levering `BLOCKED` (`CRITICAL_RECORD_THRESHOLD_EXCEEDED`). Hier: 1 van 7 (circa 14%) is onder de 25% van het
scenario. Een levering met een dubbele identiteit is `BLOCKED` (zie flow 6).

**Verwachte uitkomst:** een `SCREENED` batch met een eindoordeel en een mutatieplan. Er is nog niets
aanvaard of gepubliceerd.

**Veelvoorkomende fouten:**

| Melding | Oorzaak / oplossing |
| --- | --- |
| 404 `TASK_NOT_FOUND` | verkeerd `taskId` |
| 409 `TASK_NOT_MANUAL` | de taak is niet `MANUAL` |
| 409 `NO_ACTIVE_REVISION` | activeer eerst een revisie (flow 1) |
| 409 `TASK_RUN_IN_PROGRESS` | er loopt al een uitvoering voor deze taak |
| 409 `DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT` | referentie al gebruikt met een ander bestand; kies een nieuwe |
| 400 zonder code | ontbrekend veld (`file`, `deliveryReference`, `uploadedBy`) of ongeldige waarde |
| 413 zonder code | bestand te groot (limiet standaard 1 GB, `CATALOG_MAX_UPLOAD_SIZE`) |
| geen antwoord / time-out | upload en screening zijn synchroon; herhaal met **dezelfde** referentie (veilig, zie flow 3) |

---

## Flow 3 — Herupload en idempotentie

**Status: Alleen via API.**

**Doel:** weten wat er gebeurt als u hetzelfde bestand nog eens aanbiedt, bijvoorbeeld na een afgebroken
verzoek.

**Regel:** de `deliveryReference` is de idempotentiesleutel per taak.

| Situatie | Resultaat |
| --- | --- |
| Zelfde referentie, **identiek** bestand | **200**, de bestaande levering en batch; **geen nieuwe screening** |
| Zelfde referentie, **ander** bestand | 409 `DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT` |
| **Nieuwe** referentie, zelfde bestand | een nieuwe levering en batch, gescreend tegen de dan bestaande bronstaat (vóór `accept-baseline`: opnieuw een `INITIAL_LOAD`; erna: 0 wijzigingen) |

**Stappen (uit het scenario, stap 5a):**

1. Upload `levering-1.csv` met referentie `SCN-1` (zie flow 2). Antwoord: 201, batch 1.
2. Herhaal exact hetzelfde verzoek:
   ```bash
   curl -F "file=@scripts/scenario/levering-1.csv" -F "deliveryReference=SCN-1" -F "uploadedBy=uw.naam" \
        $A/tasks/$TASK/deliveries
   ```
3. Verwacht: **HTTP 200** met dezelfde `batchId`. Er ontstaat geen tweede batch.

**Veelvoorkomende fouten:** de referentie hergebruiken voor een gecorrigeerd bestand (dat is 409: geef het
gecorrigeerde bestand een nieuwe referentie); veronderstellen dat een 200 een nieuwe screening betekent
(dat is niet zo: ook een eerdere mislukte batch wordt niet opnieuw gescreend, zie flow 6).

---

## Flow 4 — Eerste levering aanvaarden als nulmeting en daarna een delta-levering

**Status: Alleen via API.** (accept-baseline in de UI is in aanbouw.)

**Doel:** de bronstaat vastleggen zodat een volgende levering enkel de verschillen toont.

**Voorwaarden:** een `SCREENED` batch (flow 2) die **niet** in een bundel zit. Denk eraan: deze keuze en
"opnemen in een bundel" sluiten elkaar per batch uit en zijn binnen de applicatie onomkeerbaar. `accept-baseline`
is een **nulmeting van de lokale bronstaat** en geen publicatie: er gaat niets naar Prodis.

**Stappen:**

1. Neem batch 1 uit flow 2 (`levering-1.csv`, 7 lijnen, 6 geldig, 1 afgewezen).
2. **Aanvaard als nulmeting** (verplicht: `acceptedBy` en `reason`; `acceptedBy` niet `system`):
   ```bash
   curl -X POST $A/batches/$B1/accept-baseline -H "$J" \
        -d '{"acceptedBy":"uw.naam","reason":"Scenario: eerste levering als baseline"}'
   ```
   Verwacht: `{"batchId":..,"status":"BASELINE_ACCEPTED","skippedMutationCount":6}`. De zes `CREATE`-mutaties
   worden `SKIPPED` met reden `BASELINE_ACCEPTED_WITHOUT_PUBLICATION`; de `IMPORT_MARKER` blijft `RECORDED`.
   De bronstaat bevat nu zes aanbiedingen. Eén bevoegde persoon volstaat (geen vier-ogen).
   De afgewezen regel S-6 komt niet in de bronstaat.
3. Controleer: `curl $A/batches/$B1` (status `BASELINE_ACCEPTED`) en `curl "$A/batches/$B1/mutations?size=50"`.
4. **Upload de delta-levering** `levering-2.csv` (nieuwe referentie!):
   ```bash
   curl -F "file=@scripts/scenario/levering-2.csv" -F "deliveryReference=SCN-2" -F "uploadedBy=uw.naam" \
        $A/tasks/$TASK/deliveries
   ```
   Verschillen met levering 1: S-1 kost nu `159,50` in plaats van `149,50`, en er is een nieuwe regel S-8.
5. Verwacht voor deze tweede batch (afgeleid uit de CSV en de bronstaat van stap 2):

   | veld | verwachte waarde |
   | --- | --- |
   | `rawRecordCount` | 8 |
   | `validRecordCount` | 7 |
   | `rejectedRecordCount` | 1 (S-6 blijft `abc`) |
   | `changedCount` | 1 (S-1: prijs 149,50 naar 159,50, domeinmasker `PRICE`) |
   | `newCount` | 1 (S-8) |
   | `unchangedCount` | 5 |

   Mutaties: één `UPDATE` (S-1) en één `CREATE` (S-8), plus de marker. De vijf ongewijzigde regels geven
   **geen** mutatie: dat is de bedoeling van de nulmeting.
6. Lees de statussen van die mutaties: `curl "$A/batches/$B2/mutations?size=50"`.
   - De `UPDATE` is `PLANNED`.
   - De `CREATE` van S-8 staat naar verwachting op `AWAITING_APPROVAL` met reden `BULK_CREATION_INCIDENT`:
     met `creationThresholdSharePercent` 10 en 6 bestaande aanbiedingen is 10% van 6 gelijk aan 0,6 en 1 creatie
     ligt daarboven. Dit volgt uit de drempelregel, ik heb het niet in een echte run gezien (**nog te
     verifiëren**); het scenario vermeldt wel dat een bevriezing daarna 409 `BUNDLE_HAS_UNDECIDED_MUTATIONS`
     geeft, wat hiermee consistent is.
   - `validationResult` naar verwachting `REVIEW_REQUIRED` (**nog te verifiëren**).
   - Of de prijssprong van 6,7% een `PRICE_DEVIATION_EXCEEDED`-waarschuwing geeft, hangt af van de ingestelde
     afwijkingsdrempel van de revisie; het scenario stelt die niet expliciet in. **Niet vastgesteld.**

**Zelfde bestand opnieuw na de nulmeting** (met een nieuwe referentie): 0 inhoudelijke mutaties, alle geldige
regels `unchangedCount`. (Demo-voorbeeld in het hoofd-`README.md`: 10 ongewijzigde regels.)

**Veelvoorkomende fouten:**

| Melding | Oorzaak |
| --- | --- |
| 409 `BATCH_NOT_ACCEPTABLE` | batch is niet (meer) `SCREENED`, bv. al aanvaard |
| 409 `BATCH_IN_PUBLICATION_BUNDLE` | batch zit al in een bundel |
| 400 | `reason` of `acceptedBy` ontbreekt, of `acceptedBy` is `system` |
| 409 `SOURCE_STATE_CHANGED_SINCE_SCREENING` | de bronstaat veranderde sinds de screening van deze batch (een andere batch van dezelfde koppeling werd eerst aanvaard); opnieuw uploaden |
| Tweede levering toont weer alles als nieuw / `INITIAL_LOAD` | de eerste levering werd nog niet aanvaard; de bronstaat is leeg |

---

## Flow 5 — Batch in een publicatiebundel, beslissen, controleren, bevriezen, annuleren

**Status: Deels beschikbaar in de UI.** Beschikbaar: bundel aanmaken, batches toevoegen/verwijderen,
mutatielijst met filters, individueel goedkeuren/afkeuren. Alleen via API: groepsbeslissing,
freeze-check, bevriezen, annuleren, beslissingsregister (de dialogen zijn in aanbouw).

**Doel:** de mutaties van een batch beoordelen en de bundel bevriezen ("klaar voor publicatie").
Publiceren zelf bestaat nog niet (Fase 5).

**Voorwaarden:** een `SCREENED` batch met eindoordeel ongelijk aan `BLOCKING` en `null`, die niet
aanvaard en niet in een andere bundel is. In dit scenario is dat de batch van levering 2 (`$B2`).

### 5.1 Bundel aanmaken en batch toevoegen

UI-route: **Publicatiebundels** → formulier "Nieuwe bundel" (bundelreferentie, doelmodus; kies
`SIMULATION` bij twijfel) → in de bundel het tabblad **Leden** → onder "Kandidaten toevoegen" de batch
selecteren en toevoegen.

API-route:

1. Kandidaten (batches die in aanmerking komen): `curl "$A/bundles/candidates"` (optioneel `?importLinkId=`).
2. Bundel aanmaken (idempotent op `bundleReference`; `targetMode` is verplicht):
   ```bash
   curl -X POST $A/bundles -H "$J" \
        -d '{"bundleReference":"SCN-BUNDLE-1","description":"Scenario","targetMode":"SIMULATION","createdBy":"uw.naam"}'
   ```
   Het antwoord bevat het id (`$BID`).
3. Batch(es) toevoegen (alles of niets):
   ```bash
   curl -X POST $A/bundles/$BID/batches -H "$J" -d '{"batchIds":['$B2'],"addedBy":"uw.naam"}'
   ```
4. Bekijken: `curl $A/bundles/$BID`, `curl "$A/bundles/$BID/mutations?size=50"`,
   `curl $A/bundles/$BID/batches`.
5. Een batch weer verwijderen (reden verplicht; alleen zolang er geen beslissing op haar mutaties staat):
   ```bash
   curl -X POST $A/bundles/$BID/batches/$B2/remove -H "$J" -d '{"removedBy":"uw.naam","reason":"vergissing"}'
   ```

Vanaf hier kan de batch **niet meer** met `accept-baseline` aanvaard worden (409
`BATCH_IN_PUBLICATION_BUNDLE`), tot de bundel geannuleerd is.

### 5.2 Mutaties beslissen

**Individueel** — UI: bundel → tabblad **Mutaties** → knop goedkeuren of afkeuren op de rij. API:

```bash
# goedkeuren (reden optioneel, verplicht bij een herziening)
curl -X POST $A/bundles/$BID/mutations/$MID/approve -H "$J" -d '{"decidedBy":"uw.naam","reason":"bewust goedgekeurd"}'
# afkeuren (reden altijd verplicht)
curl -X POST $A/bundles/$BID/mutations/$MID/reject  -H "$J" -d '{"decidedBy":"uw.naam","reason":"prijs onjuist"}'
```

Effect: `READY_FOR_PUBLICATION` (goedgekeurd) of `REJECTED`. De beslissing (wie, wanneer, van welke status)
staat op de mutatie en als een regel in het register. Dezelfde beslissing door dezelfde persoon opnieuw:
200 met `idempotent: true`, geen tweede regel. Een herziening (bv. een eerdere afkeuring goedkeuren)
vraagt een reden en laat beide regels staan.

**Groepsbeslissing (alleen via API)** — één handeling over een gefilterde selectie:

```bash
# alle PLANNED mutaties van de bundel goedkeuren
curl -X POST $A/bundles/$BID/decisions -H "$J" \
     -d '{"decisionKind":"APPROVE","decidedBy":"uw.naam","reason":"Scenario","filter":{"status":"PLANNED"}}'
```

Het antwoord toont `decisionId`, `affectedCount` en `selectionFilter`. Regels:

- **Minstens één filterveld** is verplicht, anders 400 `DECISION_FILTER_REQUIRED`. De velden:
  `batchId`, `status` (enkel `PLANNED` of `AWAITING_APPROVAL`), `statusReason` (exact, hoofdlettergevoelig,
  bv. `BULK_PRICE_INCIDENT`), `actionType` (enkel `CREATE` of `UPDATE`), `identityHash` (één wijzigingsgroep).
  Dit zijn dezelfde filters als de mutatielijst; `affectedCount` is nooit groter dan het aantal in die lijst.
- De actie raakt **alleen** `CREATE`/`UPDATE`-mutaties in `PLANNED` of `AWAITING_APPROVAL` die **nog geen
  beslissing** dragen. Ze raakt nooit `BLOCKED`, een identiteitsincident, de `IMPORT_MARKER` of iets wat al
  beslist is. Een filter kan dit alleen versmallen.
- **Let op:** zonder statusfilter raakt de actie dus ook `AWAITING_APPROVAL`. Wilt u alleen de gewone
  wijzigingen, filter dan op `"status":"PLANNED"` (zoals hierboven). Wilt u wachtende creaties bewust
  goedkeuren, filter dan gericht (bv. `"status":"AWAITING_APPROVAL","statusReason":"BULK_CREATION_INCIDENT"`).
- Een `REJECT` vraagt altijd een reden.
- Raakt de actie niets: `decisionId` is `null`, `affectedCount` 0, en er wordt **geen** register-regel
  geschreven. Een herhaling van dezelfde actie is veilig: ze vindt niets meer.
- Filteren op één wijzigingsgroep: `"filter":{"identityHash":"<64 hex-tekens>"}` (hoofdletterongevoelig;
  een ongeldige hash raakt 0 mutaties en is geen fout).

In het scenario: het `PLANNED`-filter keurt de ene `UPDATE` (S-1) goed. De `CREATE` van S-8 blijft
`AWAITING_APPROVAL` en moet apart beslist worden (zie 5.4).

### 5.3 Vooraf controleren met de freeze-check

```bash
curl $A/bundles/$BID/freeze-check
```

Antwoord: `freezable` (true/false), `blockerCodes` (de codes die bevriezen zou geven), `batchCount`,
`plannedCount` (hoeveel `PLANNED` er op uw naam mee goedgekeurd zou worden), `awaitingApprovalCount`,
`staleMutationCount`, `inBundleConflicts` en `crossBundleConflicts` (leesbare voorbeelden, hoogstens tien).
Het is een **momentopname zonder slot**: `freezable = true` is geen garantie; `freeze` controleert alles
opnieuw. Een niet-`ASSEMBLING` bundel is geen fout maar `freezable = false`. Onbekende bundel: 404
`BUNDLE_NOT_FOUND`.

### 5.4 Bevriezen (onomkeerbaar)

`freeze` weigert zolang er mutaties op `AWAITING_APPROVAL` staan. Zo verloopt het in het scenario:

1. Bevriezen zonder de wachtende creatie te beslissen:
   ```bash
   curl -X POST $A/bundles/$BID/freeze -H "$J" -d '{"frozenBy":"uw.naam","reason":"Scenario: simulatie bevriezen"}'
   ```
   Verwacht: **409 `BUNDLE_HAS_UNDECIDED_MUTATIONS`**.
2. De wachtende mutatie zoeken en individueel goedkeuren:
   ```bash
   curl "$A/bundles/$BID/mutations?status=AWAITING_APPROVAL"
   curl -X POST $A/bundles/$BID/mutations/$MID/approve -H "$J" -d '{"decidedBy":"uw.naam","reason":"bulkcreatie bewust goedgekeurd"}'
   ```
3. Opnieuw bevriezen (zelfde verzoek als stap 1). Verwacht: HTTP 200 met de volledige bundel, `status`
   `FROZEN`, tellers en `contentHash` (bundelhash).

Wat bevriezen doet, in één transactie: de voorwaarden controleren, alle resterende `PLANNED`-mutaties in
bulk goedkeuren **op naam van de bevriezer** (beslissingssoort `AUTO_APPROVE_PLANNED`), de tellers en de
bundelhash vaststellen, en de bundel afsluiten. Bij een fout blijft de bundel `ASSEMBLING`. Daarna kunnen er
geen leden of beslissingen meer bij. `frozenBy` en `reason` zijn verplicht.

Wat bevriezen **niet** belet: `BLOCKED`-mutaties en identiteitsincidenten (die hebben in Fase 4 geen
beslispad).

Bekijken: `curl $A/bundles/$BID` en het register: `curl $A/bundles/$BID/decisions`. Dat register is
alleen-toevoegen: een herziening voegt een regel toe.

### 5.5 Annuleren (onomkeerbaar)

Annuleren kan vanuit `ASSEMBLING` **en** vanuit `FROZEN` (zolang Fase 5 niet begonnen is met publiceren):

```bash
curl -X POST $A/bundles/$BID/cancel -H "$J" -d '{"cancelledBy":"uw.naam","reason":"verkeerde batch"}'
```

Effect: elke niet-terminale mutatie van de actieve leden wordt `EXPIRED`, de batches komen **vrij** (weer
bruikbaar voor `accept-baseline` of een andere bundel), de bundel wordt `CANCELLED`. Een tweede annulering
is 409 `BUNDLE_NOT_CANCELLABLE`. De UI-knop bestaat nog niet werkend (uitgeschakeld).

**Veelvoorkomende fouten:**

| Melding | Oorzaak / oplossing |
| --- | --- |
| 409 `BATCH_NOT_BUNDLEABLE` | batch is niet `SCREENED` (bv. al aanvaard) |
| 409 `BATCH_VALIDATION_BLOCKING` / `_NOT_ESTABLISHED` | eindoordeel `BLOCKING` of `null`; eerst oplossen |
| 409 `BATCH_ALREADY_IN_BUNDLE` | zit al in een bundel |
| 409 `BUNDLE_NOT_ASSEMBLING` | bundel is al bevroren of geannuleerd |
| 409 `BUNDLE_EMPTY` | geen actieve leden |
| 409 `BUNDLE_HAS_UNDECIDED_MUTATIONS` | er staan nog `AWAITING_APPROVAL`-mutaties |
| 409 `SOURCE_STATE_CHANGED_SINCE_SCREENING` | bronstaat veranderde na de screening: opnieuw screenen |
| 409 `BUNDLE_OFFER_CONFLICT` | twee mutaties in de bundel raken dezelfde aanbieding: één afkeuren |
| 409 `OFFER_ALREADY_IN_ANOTHER_BUNDLE` | die aanbieding is al bevroren in een andere bundel |
| 400 `DECISION_FILTER_REQUIRED` | groepsbeslissing zonder filter |
| 400 zonder code | reden of naam ontbreekt, of de actor is `system` |

---

## Flow 6 — Een geblokkeerde of mislukte batch onderzoeken en hervatten

**Status: Alleen via API.** (Batchdetail en `continue`-knop zijn in aanbouw.)

**Doel:** begrijpen waarom een batch niet `SCREENED` is en wat u eraan kunt doen.

**Stappen:**

1. Zoek de batch op in de werkvoorraad (flow 7) of via `curl "$A/batches?status=BLOCKED"`
   (`status=FAILED`, `status=MUTATING` idem).
2. Lees de batch: `curl $A/batches/$BATCHID`. Kijk naar `status`, `validationResult`, `blockedCode` en
   `blockedReason`.
3. Lees de details: `curl $A/batches/$BATCHID/issues` en `.../issue-groups`; de levering met
   `curl $A/deliveries/$DELIVERYID`.
4. Beslis op basis van de status:

**`BLOCKED`** — de levering is onbruikbaar; er zijn geen inhoudelijke mutaties (wel een marker).
Voorbeeld uit het hoofd-`README.md` (`docs/samples/05-dubbele-identiteit.csv`): `blockedCode`
`DUPLICATE_IDENTITY_IN_DELIVERY` — dezelfde aanbieding staat twee keer in het bestand. Dan is
`validationResult` `BLOCKING` en blijven `newCount`, `changedCount` en `unchangedCount` `null` ("niet
vastgesteld", nooit 0). Andere oorzaken: een structuurfout in de kop (`HEADER_*`), een leeg bestand
(`SOURCE_FILE_EMPTY`), een niet-kloppend aantal regels of bytes, of een drempel (bv.
`CRITICAL_RECORD_THRESHOLD_EXCEEDED`). Oplossing: corrigeer het **bestand** (of de configuratie via een nieuwe
revisie) en upload met een **nieuwe** `deliveryReference`. Dezelfde referentie zou de bestaande, geblokkeerde
levering teruggeven (flow 3). Een `BLOCKED` batch kan niet in een bundel (409 `BATCH_VALIDATION_BLOCKING`).

**`FAILED`** — een technische fout of onderbreking (bv. `SCREENING_INTERRUPTED` na een herstart, of
`SCREENING_FAILED`). Er is geen marker en er zijn geen mutaties. Bij het opstarten wordt een batch die op
`SCREENING` bleef staan automatisch `FAILED` gezet. Zoek de oorzaak in het serverlogboek. Om opnieuw te
screenen: upload het bestand opnieuw met een **nieuwe** referentie. De exacte herstelroute voor een
`FAILED` batch (en of de taakuitvoering vrijkomt) heb ik niet uitgezocht: **nog te verifiëren**.

**`MUTATING`** — de screening stopte halverwege de mutatiegeneratie; de batch is **hervatbaar** (de voortgang
is per chunk vastgelegd):

```bash
curl -X POST $A/batches/$BATCHID/continue
```

Antwoord: de eindstatus en tellers. Een technische fout blijft een 500; de batch blijft dan hervatbaar en u
mag dezelfde aanroep herhalen. Een batch die niet op `MUTATING` staat: 409 `BATCH_NOT_RESUMABLE`.

**`continue` wordt niet op naam vastgelegd.** Het endpoint heeft geen actorveld en is de enige
schrijfactie zonder naam. Noteer zelf wie wat hervatte als dat nodig is.

**Veelvoorkomende fouten:** proberen dezelfde referentie opnieuw te gebruiken na een correctie
(`DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT` of een 200 met de oude, geblokkeerde batch); een teller
`null` lezen als 0; denken dat `continue` iets doet op een `FAILED` batch (dat kan niet).

---

## Flow 7 — De werkvoorraad gebruiken

**Status: Beschikbaar** (alleen-lezen).

**Doel:** overzicht over alle batches en zien wat aandacht vraagt.

**UI-stappen (scherm 0, `/`):**

1. Open de app; de startpagina is de **Werkvoorraad**. Vul eerst uw naam in de actorbalk in (nodig voor
   schrijfacties elders, niet voor deze pagina).
2. Lees de **telblokken**: totaal, per status (`SCREENED`, `BLOCKED`, `FAILED`, ...) en per eindoordeel
   (`VALID`, `VALID_WITH_WARNINGS`, `REVIEW_REQUIRED`, `BLOCKING`). De tegel **"Niet vastgesteld"** telt
   batches zonder eindoordeel. Lees die tegel nooit als "in orde".
3. Verfijn met de **filters**: status, eindoordeel, koppeling, "aangemaakt vanaf", "aangemaakt tot en
   met" (die dag inbegrepen).
4. Lees de tabel: status, eindoordeel, koppeling (de code; de tooltip toont leverancier en bibliotheek),
   aangemaakt op, kritieke issues, "wacht op goedkeuring" en blokkeerreden. Een "—" is een niet-vastgestelde
   teller, geen 0.
5. Bij veel resultaten: bladeren en het aantal per pagina wijzigen.

**Eerst kijken naar:** `BLOCKED` en `FAILED` (flow 6), eindoordeel `REVIEW_REQUIRED` of "Niet vastgesteld", en
batches met een hoge teller bij "wacht op goedkeuring".

**API-route:** `curl "$A/batches?status=SCREENED&validationResult=REVIEW_REQUIRED&size=20"` (parameters:
`status`, `validationResult`, `importLinkId`, `createdFrom`, `createdTo`, `page`, `size`, vast gesorteerd op
`id` aflopend) en `curl $A/batches/summary` (optioneel `?importLinkId=`).

**Beperking:** een rij in de tabel linkt nog niet door naar een batchdetail (in aanbouw). Noteer het
`batchId` en gebruik de API (flow 2, 6).

---

## Flow 8 — Back-up en hersteltest draaien

**Status: scripts, voor ops.** Doel: RPO ≤ 24 uur en RTO ≤ 4 uur (beslissing 2026-09-23). Ontwerp:
[`../design/backup-herstel-design.md`](../design/backup-herstel-design.md).

**Voorwaarden:** `pg_dump`/`pg_restore`/`psql` beschikbaar; de libpq-variabelen ingesteld
(`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`; leidt ze af uit de JDBC-URL; het wachtwoord niet als
argument doorgeven); optioneel `CATALOG_BACKUP_DIR` (standaard `/var/backups/catalog-import` op Linux,
`C:\backups\catalog-import` op Windows). Voor de hersteltest: een rol met `CREATEDB` (eenmalig aanmaken door een
beheerder: `psql -d postgres -v restore_password='<geheim>' -f scripts/backup/create-restore-role.sql`).

**Stappen:**

1. **Back-up nemen** (als `catalog_import`):
   ```bash
   scripts/backup/backup-postgres.sh          # Linux
   powershell -File scripts\backup\backup-postgres.ps1   # Windows
   ```
   Resultaat: een `pg_dump -Fc`-bestand in `$CATALOG_BACKUP_DIR/daily/` plus een `pg_restore --list`-controle.
   Retentie: laatste 7 dagelijkse dumps; zondag ook een kopie naar `weekly/` (laatste 5).
2. **Hersteltest** (met `PGUSER=catalog_import_restore`):
   ```bash
   scripts/backup/restore-and-verify.sh            # meest recente dagelijkse dump
   scripts/backup/restore-and-verify.sh <pad-naar-dump> --keep   # eigen dump, scratch-database bewaren
   ```
   (PowerShell: `restore-and-verify.ps1`, met `-Keep`.) Het script herstelt in de scratch-database
   `catalog_import_restoretest` (nooit in de echte database), controleert dat Liquibase "up to date" is en dat de
   kerntabellen rijen bevatten, en ruimt op tenzij u `--keep` opgaf.
3. **Uitkomst lezen:** exitcode 0 en geen `FAIL`-regel = geslaagd. Exitcode 1 met een `FAIL`-regel = de dump is
   niet bruikbaar of er ontbreken rechten (bv. geen `CREATEDB`).
4. **Plannen:** productie (cron): back-up `0 2 * * *`, hersteltest `0 3 * * 0`. Windows: `schtasks` (zie het
   ontwerpdocument, sectie 6). Zet de `PG*`-variabelen in de omgeving van de geplande taak.

Een volledige hersteltest duurde in de repetitie circa 3,5 minuten, ruim binnen de 4 uur van het RTO.

**Veelvoorkomende fouten:** het `CREATEDB`-recht ontbreekt (de hersteltest stopt met `FAIL`); het
wachtwoord staat niet in de omgeving van de geplande taak; de dump in `daily/` is ouder dan 24 uur omdat de
geplande back-up niet draait (dan overschrijdt u het RPO). Of de planning op de echte productieomgeving is
ingericht, is **nog te verifiëren**; de scripts zijn gerepeteerd, maar niet noodzakelijk op productie ingericht.
