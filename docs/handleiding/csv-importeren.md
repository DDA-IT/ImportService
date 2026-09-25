# Een CSV-bestand importeren

Praktische handleiding voor het aanleveren van één CSV-levering. Aanvullend bij
[`README.md`](README.md), [`standaardflows.md`](standaardflows.md) (flow 2 en 3) en
[`begrippen.md`](begrippen.md); wat daar staat herhaal ik niet volledig.

Statuslabels zoals in de hoofdhandleiding: **Beschikbaar**, **Alleen via API**, **In aanbouw**. Wat ik niet
kon nagaan (er draaide geen backend) staat als **nog te verifiëren**.

## 1. Waar gaat dit over, en wat is "het pad"?

De applicatie leest **geen willekeurig bestandspad op de server**. Een CSV wordt **geüpload** als
multipart-bestand via `POST /api/catalog-import/tasks/{taskId}/deliveries`
(`CatalogImportDeliveryController`). De server archiveert het bestand onder `catalogimport.archive.root`
(`DeliveryArchiveStore`) en screent het **synchroon**, in hetzelfde verzoek.

Het "pad" is dus het pad op **uw eigen machine**, dat u aan `curl` (`-F "file=@pad"`) of aan het uploadscherm
meegeeft. Een server-side inbox/pad-import en een scheduler bestaan niet (uitgesteld, zie README sectie 1).

Status: upload via API **Alleen via API**; uploadscherm **Beschikbaar** (zie 4.4, lokaal via frontend `npm run dev`).

## 2. Voorbereiding (checklist)

1. Backend draait: zie README sectie 9.2 (twee commando's, poort 8081 met profiel `local`).
2. Er is een **actieve revisie** van de importdefinitie (anders 409 `NO_ACTIVE_REVISION`), met een
   basisprijsveld (anders 409 `CONFIG_PRICE_FIELD_MISSING`).
3. Er is een **koppeling** en een **MANUAL-taak** (flow 1; met profiel `demo` staat er een voorbeeldketen,
   taak *Demo manuele levering*, en wordt de `taskId` bij het opstarten gelogd).
4. U kent het `taskId`. Opzoeken (altijd bereikbaar, ook zonder setup-vlag):

```bash
BASE=http://localhost:8081
curl -s "$BASE/api/catalog-import/tasks"
```

Elke rij bevat `id` (het `taskId`), `name`, `active`, `triggerType` (moet `MANUAL` zijn), `importLinkCode`,
`supplierCode`, `libraryCode`, `lastRunStartedAt`, `lastRunFinishedAt`. Filters: `importLinkId`, `active`,
`page`, `size`.

5. Er loopt geen andere uitvoering op dezelfde taak (anders 409 `TASK_RUN_IN_PROGRESS`).

## 3. Eisen aan het CSV-bestand

Bijna alles hangt af van de **actieve revisie** van de definitie, niet van de upload. Het bestand moet dus
passen bij die revisie.

| Eigenschap | Wat geldt |
| --- | --- |
| Codering | Komt uit de revisie (standaard `UTF-8`); er wordt **nooit geraden**. Een BOM vooraan wordt verwijderd en gemeld als waarschuwing `SOURCE_BOM_REMOVED`. |
| Scheidingsteken | Precies één teken, uit de revisie (in het scenario `;`). Een ander teken in het bestand geeft een verkeerd kolomaantal. |
| Aanhalingsteken | Uit de revisie (optioneel). Een aanhalingsteken dat op dezelfde regel niet sluit: regel verworpen (`CSV_UNCLOSED_QUOTE`). |
| Header | Volgens `hasHeader` en het headerregelnummer van de revisie. Regels vóór de headerregel worden overgeslagen. |
| Eén record = één regel | Ingebedde regeleinden in een veld worden **niet ondersteund**. |
| Lege regels | Volledig lege regels worden overgeslagen en geteld; een regel met alleen spaties is een (foute) datalijn. |
| Kolomaantal | Elke regel moet even veel kolommen hebben als de header; anders wordt de regel verworpen (`ROW_COLUMN_COUNT_MISMATCH`), nooit aangevuld of afgekapt. Maximale regellengte standaard 100 000 tekens (`ROW_TOO_LONG`). |
| Identiteit | Leverancier, groep en referentie (kolomnamen uit de revisie: `supplierField`, `supplierGroupField`, `supplierReferenceField`). Een lege component verwerpt de regel (`IDENTITY_COMPONENT_EMPTY`). Twee regels met dezelfde identiteit: hele levering geblokkeerd (`DUPLICATE_IDENTITY_IN_DELIVERY`). |
| Prijs | Kolom uit `basePriceField`. Komma of punt als decimaalteken (tenzij de revisie er één verklaart; dan is het andere teken een fout). Leeg of onleesbaar: regel verworpen (`PRICE_MISSING` / `PRICE_UNREADABLE`); nooit 0. Te veel decimalen: `PRICE_SCALE_EXCEEDED`. |
| Omschrijving, valuta | Alleen aanwezig als de revisie ze mapt (`descriptionField`, `currencyField`); dan moet de kolom in de header staan. |
| Extra kolommen | Alleen gelezen als een **mapping** ernaar verwijst. Een onbekende kolom achteraan is een waarschuwing (`HEADER_UNKNOWN_COLUMN`); niets gaat verloren of wordt stil gebruikt. |
| Lege waarde versus `null` | Een lege cel is een lege tekst, geen `null`. Een verplicht veld dat leeg is, is een fout (`VALUE_MISSING`). Het woord `null` in een cel is gewoon tekst. |
| Grootte | Maximaal `1GB` (`CATALOG_MAX_UPLOAD_SIZE`); de verwerking is synchroon, dus grote bestanden duren lang. |

**Headercontroles** (kolommen worden op **naam** gevonden, hoofdletter/spatie-normalisatie: exacte regels
**nog te verifiëren**):

| Situatie | Uitkomst |
| --- | --- |
| Gedeclareerde kolom ontbreekt | levering `BLOCKED`, `HEADER_FIELD_MISSING:<veld>` |
| Dezelfde kolomnaam twee keer | `BLOCKED`, `HEADER_DUPLICATE_FIELD` |
| Ander kolomaantal dan de revisie declareert | `BLOCKED`, `HEADER_COLUMN_COUNT_MISMATCH` |
| Bestand leeg / eindigt voor de headerregel | `BLOCKED`, `SOURCE_FILE_EMPTY` / `HEADER_LINE_MISSING` |
| Identiteits-, prijs- of referentiekolom op verwachte positie is een andere kolom | `BLOCKED`, `HEADER_FIELD_SEMANTIC_CHANGE` |
| Kolom staat op een andere positie dan verwacht | waarschuwing `HEADER_FIELD_SHIFTED`; gelezen op naam |
| Header, geen enkele datalijn | `BLOCKED`, `SOURCE_NO_DATA_RECORDS` |

Daarnaast blokkeert een overschreden drempel de hele levering (`CRITICAL_RECORD_THRESHOLD_EXCEEDED`, zie
README 6.5). Welke drempels gelden staat op de revisie.

### Voorbeeld (uit `scripts/scenario/levering-1.csv`)

```csv
leverancier;groep;referentie;omschrijving;prijs;valuta;ean
SCN;BOOR;S-1;Boormachine 500W;149,50;EUR;5411234600011
SCN;HAMER;S-6;Moker 2kg;abc;EUR;5411234600066
```

Dit bestand heeft 7 datalijnen: 6 geldig, 1 afgewezen (regel 7, prijs `abc` gaf `PRICE_UNREADABLE`). Bij
een **eerste levering** op een koppeling is het resultaat `INITIAL_LOAD`: de creaties wachten op
goedkeuring. De revisie van dit scenario zet daarvoor de drempels op 10 en 25 procent (kleine bestanden).
Meer voorbeeldbestanden: `docs/samples/01` t/m `05` (o.a. `04-met-fouten.csv`, `05-dubbele-identiteit.csv`).

## 4. Uploaden

Parameters (multipart):

| Veld | Verplicht | Betekenis |
| --- | --- | --- |
| `file` | ja | het CSV-bestand; de bestandsnaam is verplicht (max. 500 tekens) |
| `deliveryReference` | ja | uw referentie voor deze levering (max. 190 tekens), zie sectie 5 |
| `uploadedBy` | ja | uw naam (max. 100 tekens); niet gecontroleerd, er is geen authenticatie |
| `expectedRecordCount` | nee | verwacht aantal datalijnen; wijkt het af, dan `BLOCKED` met `RECORD_COUNT_MISMATCH` |
| `expectedByteSize` | nee | verwachte bestandsgrootte in bytes; wijkt ze af, dan `BLOCKED` met `BYTE_SIZE_MISMATCH` |

Een negatieve waarde voor de twee verwachtingen, of een ontbrekend verplicht veld, geeft 400.

### 4.1 curl in bash of Git Bash

```bash
BASE=http://localhost:8081
TASK_ID=<id uit GET /tasks>
curl -s -w '\n[HTTP %{http_code}]\n' \
  -F "file=@/pad/naar/levering.csv" \
  -F "deliveryReference=LEVERANCIER-2026-09-24" \
  -F "uploadedBy=uw.naam" \
  "$BASE/api/catalog-import/tasks/$TASK_ID/deliveries"
```

Optioneel erbij: `-F "expectedRecordCount=7" -F "expectedByteSize=421"`. Dit is dezelfde aanroep als in
`scripts/scenario/manual-upload-scenario.sh`. In Git Bash werkt `/c/Users/naam/levering.csv`;
`C:/Users/naam/levering.csv` werkt doorgaans ook (**nog te verifiëren**).

### 4.2 PowerShell

Gebruik `curl.exe` (niet `curl`, dat is een alias voor `Invoke-WebRequest`):

```powershell
$BASE = "http://localhost:8081"
$TASK_ID = <id>
curl.exe -s -w "`n[HTTP %{http_code}]`n" `
  -F "file=@C:\Users\naam\Documents\levering.csv" `
  -F "deliveryReference=LEVERANCIER-2026-09-24" `
  -F "uploadedBy=uw.naam" `
  "$BASE/api/catalog-import/tasks/$TASK_ID/deliveries"
```

`Invoke-RestMethod -Form @{ file = Get-Item .\levering.csv; ... }` bestaat alleen vanaf PowerShell 7;
Windows PowerShell 5.1 kent `-Form` niet. Beide PowerShell-varianten heb ik niet uitgevoerd (**nog te
verifiëren**); de `curl.exe`-variant volgt de bewezen bash-aanroep.

### 4.3 Valkuilen met Windows-paden

- Backslashes: `curl.exe` accepteert `C:\map\bestand.csv` in `-F "file=@..."`. In bash moet een backslash
  verdubbeld of vervangen worden door `/`.
- Spaties in het pad: zet het **hele** `-F`-argument tussen dubbele aanhalingstekens
  (`-F "file=@C:\Mijn documenten\levering.csv"`).
- Aanhalingstekens, `;` of `,` in de bestandsnaam: vermijd ze. Het bestandsnaamveld wordt in de header
  van het multipartverzoek gezet (gedrag met vreemde tekens: **nog te verifiëren**).
- Een fout pad geeft een curl-fout ("Failed to open/read local data") en er wordt niets verstuurd.
- Zet geen `Content-Type: application/json` mee; `-F` regelt `multipart/form-data` zelf.

### 4.4 Uploadscherm (bouwstap B-F1)

**Beschikbaar**: frontend lokaal via `npm run dev`, route `/upload`.

Het scherm biedt een webformulier voor het uploaden van een CSV-bestand. Stappen:

1. **Voorbereiding:** Backend draait (poort 8081, profiel `local` en `demo` voor de demogegevens). Frontend
   draait lokaal (`npm run dev`, poort 5173).
2. **Scherm openen:** Browse naar `http://localhost:5173/upload`.
3. **Taak kiezen:** Vervolgkeuzelijst met beschikbare taken. Alleen taken met trigger type `MANUAL` zijn kiesbaar;
   niet-manuele taken staan grijs met reden ("niet manueel" / "inactief"). Het scherm maakt **geen** taak aan —
   dat gebeurt bij de inrichting van de koppeling (flow 1 en 1B van [`standaardflows.md`](standaardflows.md)).
4. **Bestand kiezen:** Klik "Selecteer bestand" en kies uw CSV. De bestandsnaam moet max. 500 tekens zijn.
5. **Referentie bepalen:** De `deliveryReference` wordt **automatisch afgeleid** van bestandsnaam en inhoud.
   De hash wordt berekend van de bestandsinhoud (deterministische SHA-256, eerste 12 hexadecimale tekens).
   Vorm: `<bestandsnaam>#<hash>`, zodat het geheel max. 190 tekens is. U kunt de referentie aanpassen;
   hetzelfde bestand met dezelfde referentie heruploden is veilig (idempotent, zie sectie 5 en flow 3 van
   [`standaardflows.md`](standaardflows.md)).
6. **Verwachtingen (optioneel):** Vul in hoeveel datalijnen u verwacht (`expectedRecordCount`) en hoe groot het
   bestand moet zijn in bytes (`expectedByteSize`). Wijkt het af, dan wordt de levering geblokkeerd met
   `RECORD_COUNT_MISMATCH` of `BYTE_SIZE_MISMATCH`.
7. **Actor:** De naam "Geüpload door" komt van de actorbalk bovenaan (u vult dat eenmalig in, wordt opgeslagen
   in de sessie).
8. **Uploaden:** Klik "Uploaden". De knop staat uit zolang de upload en screening nog bezig zijn.

**Twee fasen met tijdteller:**

- **Fase 1 — Uploaden:** het bestand wordt naar de server gestuurd.
- **Fase 2 — Screenen:** de server leest en beoordeelt elke regel.

Beide fasen lopen in één HTTP-verzoek (synchroon). Er is geen voortgangsbalk — de voortgang is principieel niet
meetbaar. Dit kan minuten duren bij grote bestanden. **Laat het tabblad open.**

**Herstelroute bij netwerkfout:**

Valt de verbinding weg: herhaal met **dezelfde referentie** en **hetzelfde bestand**. De server antwoordt dan
met HTTP 200 en de bestaande levering, zonder opnieuw te screenen. Wijzig de referentie niet.

**Foutmeldingen:**

| Fout | Oorzaak | Wat te doen |
| --- | --- | --- |
| "Taak niet gevonden" | `taskId` bestaat niet | taak opnieuw kiezen |
| "Taak is niet manueel" | geselecteerde taak is niet `MANUAL` | een manuele taak kiezen |
| "Geen actieve revisie" | de definitie van deze taak heeft geen actieve revisie | revisie activeren (beheerder/setup) |
| "Referentie is al gebruikt voor een ander bestand" (HTTP 409) | dezelfde `deliveryReference` met ander bestand | nieuwe referentie kiezen |
| "Bestand te groot" (HTTP 413) | bestand groter dan limiet (standaard 1 GB, `CATALOG_MAX_UPLOAD_SIZE`) | bestand splitsen of limiet verhogen |
| "Onverwachte serverfout" (HTTP 500) | technische fout op de server | serverlog lezen; herhaal met dezelfde referentie (veilig) |
| "Geen verbinding" (netwerk/status 0) | verzoek kon niet verstuurd of antwoord niet ontvangen | herhaal met dezelfde referentie (veilig idempotent) |

**Na succes:**

Het scherm toont het resultaat: `Levering #{id}` en `Batch #{id}`. Klik de batchnummer door naar het
batchdetail (`/batches/{id}`, **Beschikbaar** — `Frontend/src/features/upload/UploadPage.tsx`,
`<Link to={/batches/${delivery.batchId}}>`; zie README 4.2); via API: `curl $A/batches/{id}`, zie sectie 6
van [`README.md`](README.md).

De batch staat op status `SCREENED` (gereed), `BLOCKED` (levering onbruikbaar) of `FAILED` (technische fout).
Tellers: ruwe records, geldige, afgewezen, dubbele identiteiten, nieuw, gewijzigd, ongewijzigd,
inhoudsmutaties.

**Vervolg:**

Een `SCREENED` batch kan op twee manieren verder, en nooit op allebei (zie sectie 7 van `README.md`):
- **accept-baseline** — nulmeting van de bronstaat (geen publicatie).
- **Opnemen in een publicatiebundel** — voorbereiding voor publicatie.

Beide acties staan **niet** op het uploadscherm zelf, maar wel op het batchdetail waar u via de batchlink
hierboven terechtkomt (**Beschikbaar** — `Frontend/src/features/batches/BatchActions.tsx`): een `SCREENED`
batch toont daar de knoppen "Aanvaarden als nulmeting" en "Opnemen in bundel", elk met een verplichte reden
en een typ-bevestiging. Alternatief: de API (flow 4 en 5 van [`standaardflows.md`](standaardflows.md)).

## 5. De `deliveryReference`

- **Uniek per levering, per taak.** Intern wordt de sleutel `manual:<referentie>`.
- **Identiek bestand, zelfde referentie: 200**, dezelfde levering en batch, **geen nieuwe screening**. Dit is
  ook de **herstelroute** als de verbinding wegviel tijdens een lange upload: herhaal met dezelfde
  referentie.
- **Zelfde referentie, ander bestand: 409** `DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT`. Kies een
  nieuwe referentie.
- **Nieuwe referentie, zelfde inhoud: nieuwe batch** (in het scenario: geen wijzigingen).
- Kies een **deterministische** referentie (bv. de bestandsnaam of leveranciers-/periodecode),
  **nooit met tijdstempel**: dan is een herhaalde aanroep geen retry maar een dubbele levering.

## 6. Het antwoord lezen en het resultaat bekijken

Statuscode **201** = nieuwe levering; **200** = idempotente herhaling. Beide bevatten dezelfde velden:

| Veld | Betekenis |
| --- | --- |
| `deliveryId`, `batchId` | de levering en haar batch (voor de vervolgcalls) |
| `status` | `SCREENED`, `BLOCKED` (met `blockedCode`) of `FAILED` |
| `blockedCode` | reden bij `BLOCKED` of `FAILED` (bv. `HEADER_FIELD_MISSING:prijs`, `SCREENING_FAILED`) |
| `rawRecordCount`, `validRecordCount`, `rejectedRecordCount` | gelezen, geldige en afgewezen datalijnen |
| `duplicateIdentityCount` | regels met een dubbele identiteit |
| `newCount`, `changedCount`, `unchangedCount` | nieuw, gewijzigd, ongewijzigd t.o.v. de bronstaat |
| `contentMutationCount` | inhoudelijke mutaties (zonder marker) |

Een teller die `null` is, is **niet vastgesteld**; lees dat nooit als 0 (README 6.2). Het antwoord bevat
`validationResult` niet; dat staat op de batch.

Resultaat bekijken (alle `GET`, altijd bereikbaar):

```bash
A=$BASE/api/catalog-import
curl -s "$A/batches/$BATCH_ID"                  # status, validationResult, alle tellers
curl -s "$A/batches/$BATCH_ID/issues"           # voorbeeldregels per fout (max. 200 per code)
curl -s "$A/batches/$BATCH_ID/issue-groups"     # echte aantallen per soort fout (vanaf 10 gelijksoortige)
curl -s "$A/batches/$BATCH_ID/mutations?size=50"
curl -s "$A/deliveries/$DELIVERY_ID"
```

**Scherm 0 (werkvoorraad)** op `http://localhost:5173/` — **Beschikbaar**, alleen-lezen: de batch staat
bovenaan (nieuwste eerst). Doorklikken naar het batchdetail werkt (**Beschikbaar**, zie boven).

Verwacht voor `levering-1.csv` (echt gedraaid via het scenario): 7 regels, 6 geldig, 1 afgewezen (prijs
`abc`), `INITIAL_LOAD`; identieke herupload geeft 200 met dezelfde batch. Voor `levering-2.csv` **na**
`accept-baseline`: 1 `UPDATE` (S-1), 1 `CREATE` (S-8), 5 ongewijzigd, 1 afgewezen.

Volledige lijst van statussen, oordelen en tellers: [`begrippen.md`](begrippen.md) en README sectie 6.

## 7. Volgende stap

Een `SCREENED` batch kan op twee manieren verder, en nooit op allebei: **accept-baseline** (nulmeting) of
opnemen in een **publicatiebundel** (flow 3 en 4 in [`standaardflows.md`](standaardflows.md)). Een tweede
levering vóór `accept-baseline` is opnieuw een `INITIAL_LOAD`.

## 8. Probleemoplossing

| Symptoom | Oorzaak | Oplossing |
| --- | --- | --- |
| 404 `TASK_NOT_FOUND` | `taskId` bestaat niet | opnieuw opzoeken met `GET /tasks` |
| 409 `TASK_NOT_MANUAL` | taak heeft niet `triggerType=MANUAL` | een MANUAL-taak gebruiken |
| 409 `NO_ACTIVE_REVISION` | definitie heeft geen actieve revisie | revisie activeren (flow 1) |
| 409 `CONFIG_PRICE_FIELD_MISSING` / `CONFIG_REQUIRED_BOOKMARK_MISSING` | revisie zonder prijsveld, of verplichte LINK-bookmark ontbreekt; er is niets gearchiveerd | revisie of bookmarkwaarde corrigeren |
| 409 `TASK_RUN_IN_PROGRESS` | een eerdere uitvoering op deze taak is niet afgerond | wachten; blijft het staan, batchstatus onderzoeken (README 7 punt 15) |
| 409 `DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT` | referentie eerder gebruikt met ander bestand | nieuwe referentie |
| 400 (zonder `code`) | ontbrekend of te lang veld, negatieve verwachting | parameters controleren |
| 201 met `status=BLOCKED` en `HEADER_*` | header past niet bij de revisie | bestand of revisie aanpassen; nieuwe referentie bij ander bestand |
| 201 met `status=BLOCKED`, `RECORD_COUNT_MISMATCH` / `BYTE_SIZE_MISMATCH` | opgegeven verwachting klopt niet met het bestand | verwachting of bestand controleren. Het is een screeningblokkade (201 met `blockedCode`), geen HTTP-fout; HTTP-status **nog te verifiëren** |
| 201 met `status=FAILED`, `blockedCode=SCREENING_FAILED` | technische fout tijdens screening; levering is wél aangemaakt en gearchiveerd | serverlog lezen; **niet** onder een nieuwe referentie herupload maken zonder eerst de oorzaak te kennen |
| 413 of 500 zonder body | bestand groter dan de grens (1 GB) | bestand splitsen of `CATALOG_MAX_UPLOAD_SIZE` verhogen; exacte statuscode bij te groot bestand **nog te verifiëren** |
| Verbinding valt weg tijdens screening | de synchrone verwerking kan doorlopen op de server | herhaal met **dezelfde** referentie en hetzelfde bestand (200 = bestaande uitkomst) |
| Veel regels afgewezen (`PRICE_UNREADABLE`) | decimaalnotatie of tekst in de prijskolom | `/issues` bekijken, bestand corrigeren, nieuwe referentie |
| Batch `BLOCKED` op `CRITICAL_RECORD_THRESHOLD_EXCEEDED` | te veel fouten op kritieke kolommen t.o.v. de drempel | bestand corrigeren of drempel op nieuwe revisie bewust verhogen |
