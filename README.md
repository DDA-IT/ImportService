# Catalog Import

Gecontroleerde screening van leverancierscatalogi als voorbereiding op latere publicatie. Een
**levering** (vandaag: een handmatig geüpload CSV-bestand) wordt ongewijzigd gearchiveerd,
gescreend tegen een bevroren **importdefinitierevisie**, en levert een **mutatieplan** op — nooit een
stille wijziging. Wat afwijkt
(onleesbare prijs, lege identiteitscomponent, dubbele identiteit, ongewone prijssprong, te veel nieuwe
artikelen) komt als **regelprobleem**, **beoordeling** of **blokkade** naar boven.

De aanbiedingsidentiteit is `leverancier + leveranciersgroep + leveranciersreferentie` (optioneel
uitgebreid met kortingscode). Bibliotheek en bronorganisatie zijn scope, geen sleutel.

De modules volgen de Prodis-indeling: `Domain` (model), `Dao` (JPA-repositories), `Service`
(transactionele businessflow), `Web` (Spring Boot, REST, Liquibase).

## Wat werkt vandaag

Fase 1 tot en met 3 zijn af: datamodel, upload/archivering met SHA-256-idempotentie, screening
(structuur, identiteit, prijs, referenties, recordfilters, veldmappings), regels en drempels,
foutgroepen, mutatieplan, en het aanvaarden van een levering als nulmeting van de bronstaat.

**Nog niet aanwezig:** scheduler en connectors (SFTP/API/XLSX/XML), authenticatie en permissies
(Keycloak, Fase 5), publicatie naar ProDisWebbase/Pervasive, publicatiebundels en een gebruikersinterface.
Er is dus geen enkele autorisatiecontrole: `uploadedBy` en `acceptedBy` zijn voorlopig gewone
requestvelden.

## Documentatie en roadmap

De vernieuwde businessanalyse beschrijft het volledige doelplatform; zij is geen beschrijving van
wat vandaag al geïmplementeerd is. Gebruik de documenten als volgt:

- [`docs/analysis/current-project-vs-businessanalyse-2.md`](docs/analysis/current-project-vs-businessanalyse-2.md)
  vergelijkt de actuele broncode met de vernieuwde analyse en benoemt de ontbrekende onderdelen;
- [`docs/stories/catalog-import-v2.md`](docs/stories/catalog-import-v2.md) vertaalt die verschillen
  naar vijftien gefaseerde stories met afhankelijkheden en acceptatiecriteria;
- [`docs/decisions.md`](docs/decisions.md) bevat reeds genomen architectuur- en businessbesluiten;
- [`Businessanalyse_artikelimport_en_prijsacceptatie-2.md`](Businessanalyse_artikelimport_en_prijsacceptatie-2.md)
  is de functionele doelanalyse, inclusief open besluiten D01–D15 en de PSIMPORT-veldcatalogus.

Oudere documenten over lokale `LibraryOffer`-/`SupplierCatalog`-publicatie zijn als historisch
gemarkeerd. De huidige `accept-baseline` blijft een geauditeerde nulmeting van de lokale bronstaat en
is geen goedkeuring of bevestiging van een Prodis-publicatie.

Een Nederlandstalige gebruikers- en beheerdershandleiding staat in [`docs/handleiding/README.md`](docs/handleiding/README.md).

## Vereisten

Java 21 en Maven 3.9+. PostgreSQL is de standaarddatabase (`CATALOG_DB_URL`, `CATALOG_DB_USERNAME`,
`CATALOG_DB_PASSWORD`); Liquibase voert de migraties uit. Voor lokaal uitproberen volstaat H2 in
het geheugen — zie hieronder.

## Lokaal starten met het demoprofiel

Het profiel `demo` zet een in-memory H2-database klaar, opent de H2-console, **zet de setup-API aan**
en maakt bij het opstarten één volledige voorbeeldketen aan (bronorganisatie `DEMO`, definitie
`DEMO-CSV`, actieve revisie, koppeling `DEMO-LINK`, taak *Demo manuele levering*). In de logregels staat
de `taskId` met een kant-en-klaar `curl`-commando.

```bash
mvn -pl Web -am install -DskipTests
mvn -pl Web spring-boot:run -Dspring-boot.run.profiles=local,demo
```

In PowerShell werken dezelfde commando's; zet de `-D`-parameter tussen aanhalingstekens:

```powershell
mvn -pl Web -am install -DskipTests
mvn -pl Web spring-boot:run "-Dspring-boot.run.profiles=local,demo"
```

Twee commando's, geen één: `mvn -pl Web -am spring-boot:run` faalt met *Unable to find a suitable main
class*, omdat `spring-boot:run` dan óók op de aggregator-pom uitgevoerd wordt. De eerste regel zet de
modules `Domain`, `Dao` en `Service` in de lokale repository; daarna draait alleen `Web`. Een andere
poort: `-Dspring-boot.run.arguments=--server.port=8099`.

De onderstaande voorbeeldsessie gebruikt poort 8080 (de standaard) en `taskId=1` (de waarde uit de
logregel bij het opstarten). Gebruikt u PowerShell, schrijf dan **`curl.exe`** in plaats van `curl`:
`curl` is daar een alias voor `Invoke-WebRequest` en begrijpt `-F` niet.

## Voorbeeldsessie

De voorbeeldbestanden staan in `docs/samples/` en gebruiken de kolommen van de demorevisie:
`leverancier;groep;referentie;omschrijving;prijs;valuta;ean`.

### 1. Eerste levering — alles is nieuw

```bash
curl -F "file=@docs/samples/01-eerste-levering.csv" -F "deliveryReference=REF-01" -F "uploadedBy=demo@example.test" http://localhost:8080/api/catalog-import/tasks/1/deliveries
```

```json
{"deliveryId":1,"batchId":1,"status":"SCREENED","blockedCode":null,"rawRecordCount":10,
 "validRecordCount":10,"rejectedRecordCount":0,"newCount":10,"contentMutationCount":10}
```

De screening loopt synchroon mee in de upload. Tien geldige regels, tien nieuwe aanbiedingen.

### 2. De batch bekijken

```bash
curl http://localhost:8080/api/catalog-import/batches/1
```

Belangrijk in het antwoord:

| veld | waarde | betekenis |
| --- | --- | --- |
| `status` | `SCREENED` | de levering is verwerkt; dit zegt nog niets over het oordeel |
| `validationResult` | `REVIEW_REQUIRED` | het inhoudelijke eindoordeel: iemand moet hiernaar kijken |
| `creationOutcome` | `INITIAL_LOAD` | de koppeling had nog geen enkele aanbieding: dit is een initialisatie |
| `creationScopeCount` | `0` | er was niets om tegen af te wegen, dus geen percentage toegepast |
| `awaitingApprovalCount` | `10` | tien mutaties wachten op goedkeuring |

De mutaties zelf (`?actionType=CREATE`, `?page=0&size=50`):

```bash
curl "http://localhost:8080/api/catalog-import/batches/1/mutations?size=2"
```

Elke `CREATE` staat op `"status":"AWAITING_APPROVAL"` met
`"statusReason":"INITIAL_LOAD_REQUIRES_APPROVAL"`. De elfde rij in de lijst is de `IMPORT_MARKER`: het
bewijs dat deze levering verwerkt is, ook wanneer er niets veranderde.

De vaststellingen staan in `/issues` (per foutcode worden er hoogstens 200 voorbeeldrijen bewaard) en de
samenvatting per foutsoort in `/issue-groups` (daar staan de **werkelijke** aantallen):

```bash
curl http://localhost:8080/api/catalog-import/batches/1/issues
curl http://localhost:8080/api/catalog-import/batches/1/issue-groups
```

Bij deze eerste levering is er precies één melding: `INITIAL_LOAD_REQUIRES_APPROVAL`. De lijst met
foutgroepen is hier leeg: een groep ontstaat pas vanaf tien gelijksoortige vaststellingen — met tien
regels in een voorbeeldbestand komt het daar niet van.

### 3. De nulmeting aanvaarden

```bash
curl -X POST http://localhost:8080/api/catalog-import/batches/1/accept-baseline -H "Content-Type: application/json" -d "{\"acceptedBy\":\"demo@example.test\",\"reason\":\"nulmeting van de demoketen\"}"
```

```json
{"batchId":1,"status":"BASELINE_ACCEPTED","skippedMutationCount":10}
```

Hiermee wordt de bronstaat vastgelegd (`state_origin = BASELINE_ACCEPTED`) en krijgen de tien mutaties
`SKIPPED` met reden `BASELINE_ACCEPTED_WITHOUT_PUBLICATION` — er ís nog geen publicatie. Eén bevoegde
persoon volstaat; `acceptedBy` en `reason` zijn verplicht en `acceptedBy` mag niet `system` zijn.

### 4. Exact hetzelfde bestand opnieuw — nul wijzigingen

```bash
curl -F "file=@docs/samples/02-zelfde-levering-nogmaals.csv" -F "deliveryReference=REF-02" -F "uploadedBy=demo@example.test" http://localhost:8080/api/catalog-import/tasks/1/deliveries
```

```json
{"batchId":2,"status":"SCREENED","newCount":0,"changedCount":0,"unchangedCount":10,"contentMutationCount":0}
```

Tien ongewijzigde regels, geen enkele inhoudelijke mutatie. (Een upload met **dezelfde**
`deliveryReference` én dezelfde inhoud is een retry: die antwoordt met 200 en de bestaande levering,
zonder tweede batch. Dezelfde referentie met ándere inhoud geeft 409
`DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT`.)

### 5. Eén gewijzigde prijs

```bash
curl -F "file=@docs/samples/03-prijswijziging.csv" -F "deliveryReference=REF-03" -F "uploadedBy=demo@example.test" http://localhost:8080/api/catalog-import/tasks/1/deliveries
curl "http://localhost:8080/api/catalog-import/batches/3/mutations?actionType=UPDATE"
```

Eén `UPDATE` voor `A-1001` met `"domainMask":"PRICE"`, `beforeBasePrice 149.50` en
`afterBasePrice 179.50`, status `PLANNED`. De sprong is 20% en dus groter dan de toegelaten 15%: in
`/issues` staat `PRICE_DEVIATION_EXCEEDED` (ernst `WARNING`, met de vorige waarde en de gemiddelden van
de laatste 50 en 200 goedgekeurde waarden erbij) en de batch krijgt
`"validationResult":"VALID_WITH_WARNINGS"`. Een waarschuwing houdt de levering niet tegen; een
kritieke lijnfout wel (stap 6).

### 6. Een levering met fouten

```bash
curl -F "file=@docs/samples/04-met-fouten.csv" -F "deliveryReference=REF-04" -F "uploadedBy=demo@example.test" http://localhost:8080/api/catalog-import/tasks/1/deliveries
```

```json
{"batchId":4,"status":"SCREENED","rawRecordCount":10,"validRecordCount":8,"rejectedRecordCount":2,"unchangedCount":8}
```

De regel met `prijs = onleesbaar` wordt verworpen met `PRICE_UNREADABLE` — een onleesbare prijs wordt
**nooit** 0 — en de regel met een lege `groep` met `IDENTITY_COMPONENT_EMPTY`. Beide vallen op een
kritieke kolom, dus `criticalLineCount` is 2 en de batch krijgt `REVIEW_REQUIRED`. Twee op tien is 20%
en blijft onder de `maxCriticalSharePercent` van 25 van deze demoketen; daarboven zou de hele levering
`BLOCKED` zijn met `CRITICAL_RECORD_THRESHOLD_EXCEEDED`.

### 7. Een dubbele identiteit blokkeert alles

```bash
curl -F "file=@docs/samples/05-dubbele-identiteit.csv" -F "deliveryReference=REF-05" -F "uploadedBy=demo@example.test" http://localhost:8080/api/catalog-import/tasks/1/deliveries
```

```json
{"batchId":5,"status":"BLOCKED","blockedCode":"DUPLICATE_IDENTITY_IN_DELIVERY","duplicateIdentityCount":2,
 "newCount":null,"changedCount":null,"unchangedCount":null}
```

`validationResult` is `BLOCKING`, er zijn geen inhoudelijke mutaties en de tellers die niet vastgesteld
konden worden blijven `null` — nooit stil 0. Dezelfde aanbieding twee keer in één bestand wordt niet
opgelost door "de laatste regel wint".

### Drempels bij kleine bestanden

De standaarddrempels zijn percentages van de omvang: creatie 1% en records ter beoordeling 1%. Bij een
voorbeeldbestand van tien regels is 1% van 10 gelijk aan 0,1, zodat élke creatie en élke fout meteen
boven de drempel zou liggen. De demorevisie zet daarom `creationThresholdSharePercent` op 10 en
`maxCriticalSharePercent` op 25. Voor een echte, kleine leverancier is dat dezelfde ingreep: zet het
percentage per revisie hoger — een zichtbare, geauditeerde keuze.

## Een eigen keten aanmaken (setup-API)

> **Waarschuwing.** De setup-API is een ontwikkelhulp en staat **standaard uit**
> (`catalogimport.setup-api.enabled`, default `false`; alleen het `demo`-profiel zet ze aan). Er is nog
> geen authenticatie: wie deze endpoints bereikt, kan een importdefinitie en haar drempels bepalen en
> dus de controle op een catalogus uitschakelen. Zet de vlag nooit aan in een omgeving met echte
> gegevens.

Staat de vlag uit, dan bestaat de controller niet en antwoordt elk `/setup`-pad met 404.

```bash
B=http://localhost:8080/api/catalog-import/setup
curl -X POST $B/source-organisations -H "Content-Type: application/json" -d '{"code":"ACME","name":"ACME gereedschap","type":"SUPPLIER"}'
curl -X POST $B/definitions -H "Content-Type: application/json" -d '{"sourceOrganisationCode":"ACME","code":"ACME-CSV","name":"ACME catalogus","usageType":"OWN_DEFINITION"}'
curl -X POST $B/definitions/2/revisions -H "Content-Type: application/json" -d '{"delimiter":";","hasHeader":true,"identityProfileKind":"THREE_PART","supplierField":"leverancier","supplierGroupField":"groep","supplierReferenceField":"referentie","basePriceField":"prijs","descriptionField":"omschrijving","currencyField":"valuta","canonicalisationVersion":2,"creationThresholdSharePercent":10,"maxCriticalSharePercent":25}'
curl -X POST $B/revisions/2/mappings -H "Content-Type: application/json" -d '{"targetFieldCode":"EAN","sourceReference":"ean","sequenceNumber":1}'
curl -X POST $B/revisions/2/activate -H "Content-Type: application/json" -d '{"approvedBy":"beheerder@example.test"}'
curl -X POST $B/links -H "Content-Type: application/json" -d '{"definitionId":2,"code":"ACME-LINK","name":"ACME koppeling","supplierCode":"ACME","libraryCode":"PSARF012"}'
curl -X POST $B/tasks -H "Content-Type: application/json" -d '{"linkId":2,"name":"ACME manuele levering"}'
curl $B/overview
```

Gebruik de `id` uit elk antwoord in de volgende stap (hierboven is `2` het id van de tweede definitie,
revisie en koppeling — naast de demoketen). `overview` toont de hele boom met de `taskId` die u bij de
upload nodig hebt. In PowerShell: `curl.exe`, en zet de JSON tussen dubbele aanhalingstekens met
`\"`-escapes (zoals bij `accept-baseline` hierboven).

Goed om te weten:

- Een revisie ontstaat als `DRAFT`. Mappings, filters (`/filters`) en kritiek-overrules
  (`/field-criticality`) kunnen alleen op een `DRAFT`; `activate` bevriest ze en zet een eventuele
  vorige actieve revisie op `SUPERSEDED`.
- `activate` en elke mapping/filter/overrule lopen door **dezelfde** configuratievalidatie als de
  screening. Een fout geeft 400 met de bestaande code in het bericht, bijvoorbeeld
  `CONFIG_FIELD_MAPPING_DUPLICATES_REVISION` wanneer u een veld mapt dat de revisie zelf al bepaalt.
- Canonicalisatieversie 2 is verplicht zodra er een munt gelezen wordt of een referentie (EAN/PIM/CAB)
  gemapt is; onder versie 1 zou zo'n wijziging buiten de vingerafdruk vallen.
- Een onbekend id geeft 404, een dubbele code 409, een ongeldige waarde 400 — telkens met een stabiele
  `code` in het antwoord (behalve bij 400, waar de tekst in `error` staat).

## H2-console

Het demoprofiel opent `http://localhost:8080/h2-console`. Vul in: JDBC-URL `jdbc:h2:mem:catalogimport`,
gebruiker `sa`, geen wachtwoord. De database leeft in het geheugen: stoppen betekent alles kwijt
(inclusief de demoketen, die bij de volgende start opnieuw wordt aangemaakt). Het bronarchief staat op
schijf, onder `${java.io.tmpdir}/catalogimport-demo-archive`.

## Gerichte tests

Nooit de volledige reactor; altijd de betrokken klassen:

```bash
mvn -pl Web -am test -Dtest="SetupApiDisabledTest,SetupApiFlowTest,DemoDataSeederTest" -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl Web -am test -Dtest="DeliveryUploadTest,DeliveryScreeningFlowTest,AcceptBaselineReviewFlowTest,BatchBaselineHttpTest,ValidationResultTest,ImportControlSchemaTest,ScreeningSchemaTest" -Dsurefire.failIfNoSpecifiedTests=false
```

(In PowerShell: `"-Dtest=SetupApiDisabledTest,SetupApiFlowTest,DemoDataSeederTest"` met aanhalingstekens
rond de hele parameter.)

## Traceerbaarheid en grenzen

Elke levering bewaart de originele bytes in het archief met hun SHA-256, plus ontvangstmoment,
gebruikte definitierevisie, status, tellers en het eindoordeel. Unieke constraints op de
leveringssleutel, de kandidaat-identiteit, de mutatie-idempotentiesleutel en de actieve referentie per
aanbieding voorkomen dubbele verwerking, ook bij gelijktijdigheid. Bestandsinhoud en geheimen worden
nooit gelogd. Bedragen zijn decimalen, nooit floats: een onleesbare of ontbrekende prijs blokkeert de
regel en wordt nooit stilzwijgend 0.

De beslissingen achter dit alles staan in [`docs/decisions.md`](docs/decisions.md). De actuele
vergelijking en vervolgstories staan respectievelijk in
[`docs/analysis/current-project-vs-businessanalyse-2.md`](docs/analysis/current-project-vs-businessanalyse-2.md)
en [`docs/stories/catalog-import-v2.md`](docs/stories/catalog-import-v2.md); oudere ontwerpen in
`docs/design/` zijn waar nodig als historisch gemarkeerd.
