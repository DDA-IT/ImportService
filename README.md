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

Daarbovenop bestaan publicatiebundels (Fase 4: opnemen, beslissen, bevriezen, annuleren) en een
lokale **frontend** (`Frontend/`, alleen een ontwikkelhulpmiddel): werkvoorraad (`/`), uploadscherm
(`/upload`, navigatielink "Levering uploaden"), batchdetail (`/batches/:batchId`, met `accept-baseline`,
opname in een bundel en "Batch hervatten") en het bundelscherm (`/bundles`, ook groepsbeslissing, freeze en
cancel). Zie de [handleiding](docs/handleiding/README.md).

**Nieuwe leverancier en taak (NT-spoor, recht `MANAGE`):** een gebruiker richt zelf een leverancier in via
Inrichting → "Nieuwe leverancier en taak" (`/setup/new`: leverancier → startpunt zelf beschrijven of vanuit een
sjabloon → beschrijving van het bestand → koppeling → manuele taak → samenvatting; "Verder inrichten" hervat; hoogstens
één conceptversie per beschrijving, anders 409 `REVISION_DRAFT_ALREADY_EXISTS`). Daarna **Controleren** per koppeling
(`/setup/links/{linkId}/check`): een checklist met álle problemen tegelijk (`GET /import-links/{id}/readiness`, `READ`;
overgeslagen controles als `INFO_CONFIG_CHECKS_SKIPPED`), een **proefinlezing** die niets opslaat
(`POST /revisions/{id}/trial-reads`, `MANAGE`, zie `docs/design/proefinlezing-design.md`), activeren met een
waarschuwing zonder geslaagde proef, en "Wat nu?": de eerste echte levering wacht op goedkeuring. De schermteksten
volgen de woordenlijst in `docs/handleiding/begrippen.md` (invulpunt, versie, beschrijving van het bestand,
leverancier of aankoopvereniging; Batch, Mutatie en Bundel blijven).

Nieuw: `GET /api/catalog-import/bundles/{id}/psimport-preview` (JSON, of `?format=csv`) toont voor een
**FROZEN** bundel een read-only projectie van wat later naar PSIMPORT zou gaan. Het antwoord draagt
`previewOnly` en `UNVERIFIED_FIELD_INVENTORY`: het is een **niet-contractuele preview**, geen echt
PSIMPORT-formaat, en schrijft niets weg (zie `docs/design/fase4-publication-bundle-design.md` §16 en
`docs/decisions.md`, 2026-09-25).

**SFTP-ophaling (K-4b, enkel backend):** een taak met een Leveringsconfiguratie kan handmatig "Nu ophalen"
via `POST /api/catalog-import/tasks/{id}/fetch-runs` (recht MANAGE; synchroon, één bestand per run, allowlist
`catalogimport.fetch.allowed-hosts` verplicht); runs via `GET /tasks/{id}/runs` en `GET /task-runs/{id}` (READ).
Zie `docs/design/leveringsconfiguratie-design.md` §4 en §6.

**Nog niet aanwezig:** een scheduler (geen automatische of periodieke ophaling), frontendschermen voor
verbindingen en "Nu ophalen" (F-1/F-2), connectors voor API/XLSX/XML, de Prodis-koppeling voor rechten
(5B-7, zie "Rechten (5-PERM)"), een echt PSIMPORT-formaat, en een scherm om invulpunten in een sjabloon te
declareren (enkel de API, achter de setup-vlag). Van publicatie naar ProDisWebbase/Pervasive (Fase 5, 5-PUB) is deel **a**
(bundelsnapshot, PSIMPORT-preview, SIMULATION-publicatierun via `publication_run`) al gebouwd; deel **b/c**
(écht schrijven naar TRIAL_LIBRARY/PRODUCTION) is nog niet gebouwd — geblokkeerd op het externe
verwerkingscontract 252 IMPORT/1179 (zie `docs/openstaande-externe-punten.md`).

**Beveiliging (5-AUTH, gebouwd):** de applicatie is een Backend-for-Frontend met Keycloak-login
(`SecurityConfiguration`) en een sessiecookie. Elk `/api/**`-verzoek zonder login geeft
**401 `AUTHENTICATION_REQUIRED`** (nooit een redirect). Schrijfaanroepen (`POST`/`PUT`/...) vereisen daarnaast
een CSRF-header `X-XSRF-TOKEN` met de waarde van de cookie `XSRF-TOKEN` (anders 403 `CSRF_TOKEN_INVALID`). De
actornaam komt uit het token (`preferred_username`) en wordt zo vastgelegd; de actorvelden in een request
(`uploadedBy`, `acceptedBy`, ...) zijn optioneel, en als ze er zijn moeten ze gelijk zijn aan die naam (anders
400 `ACTOR_FIELD_MISMATCH`). Er zijn **geen bearer-tokens en geen omzeiling** voor scripts
(`docs/design/fase5-auth-design.md`, §9 V1 = A1): API-aanroepen gaan via de browsersessie (zie hieronder).
Rechten per actie (5-PERM) zijn gebouwd: een login alleen volstaat niet, zie "Rechten (5-PERM)" hieronder.

### Rechten (5-PERM)

Ontwerp: [`docs/design/fase5-perm-design.md`](docs/design/fase5-perm-design.md) (§1 heeft de volledige
endpointmapping). Elke handlermethode in `be.dda.catalogimport.web` draagt `@RequiresPermission` of
`@NoPermissionRequired`; een `PermissionInterceptor` controleert dat per verzoek.

| Recht | Code | Typische acties |
|---|---|---|
| `READ` | `catalogImport.read` | alle `GET`-endpoints (batches, bundels, taken, preview, setup-overzicht, ...) |
| `MANAGE` | `catalogImport.manage` | upload, batch hervatten (`continue`), bundel aanmaken, batches toevoegen/verwijderen, setup/templates/bookmarkwaarden, proefinlezing (`POST /revisions/{id}/trial-reads`) |
| `APPROVE` | `catalogImport.approve` | `accept-baseline`, mutaties goed-/afkeuren, groepsbeslissingen, bevriezen, annuleren, publicatierun aanvragen (`POST /bundles/{id}/publication-runs`) |

`GET /api/catalog-import/me` is uitgezonderd (geen recht nodig): een gebruiker zonder rechten krijgt 200 met een
lege lijst in plaats van een 403 zonder uitleg.

- **Hiërarchie (keuze mens):** `APPROVE` impliceert `MANAGE` en `READ`; `MANAGE` impliceert `READ`.
  De bron levert ruwe codes, CatalogImport leidt het effectieve recht af.
- **Fail-closed:** geen wildcard, geen default, in geen enkel profiel. Lege configuratie = niemand heeft iets. Een
  handlermethode zonder annotatie is dicht (403 + ERROR-log). `system` mag lezen maar nooit `manage`/`approve`:
  de applicatie start niet als een toekenning dat vraagt.
- **`GET /me`:** `permissions` is altijd een lijst met de **effectieve** rechtcodes (gesorteerd, `[]` = geen rechten).

**Lokaal rechten krijgen.** De lokale rechtenbron (`ConfiguredPermissionSource`) leest toekenningen uit YAML. Vul in
`application-local.yml` en/of `application-demo.yml` bij `catalogimport.permissions.grants` uw eigen Keycloak
`preferred_username` in:

```yaml
catalogimport:
  permissions:
    grants:
      - username: uw.keycloak.username
        rights: read, manage, approve
```

De placeholder `vul-hier-je-keycloak-username-in` komt met niemand overeen: zonder uw eigen username krijgt u
**403 `PERMISSION_DENIED`** en toont de UI "U heeft geen rechten voor CatalogImport". Matching is getrimd en
hoofdletterongevoelig. Een YAML-lijst wordt tussen profielen **vervangen**, niet samengevoegd: staat `grants` in
meerdere actieve profielen, dan geldt alleen de lijst van het laatst geladen profiel. Een blanco username of een
onbekende rechtwaarde laat de applicatie niet starten.

**Foutvolgorde (recht eerst):** 401 `AUTHENTICATION_REQUIRED` / 403 `CSRF_TOKEN_INVALID` -> 403
`ACTOR_IDENTITY_INVALID` -> 403 `SYSTEM_ACTOR_FORBIDDEN` (bij manage/approve) -> 503
`PERMISSION_SOURCE_UNAVAILABLE` -> 403 `PERMISSION_DENIED` -> 400 `ACTOR_FIELD_MISMATCH` -> 404/409. Een geweigerde
poging wordt gelogd (WARN, zonder body of querystring), niet bewaard.

**Setup-API-vlag (sinds het NT-spoor beperkt).** De inrichtpaden (`/setup/...` behalve `/setup/overview`,
sjablonen lezen en afleiden, koppelingen) zijn **niet meer** achter `catalogimport.setup-api.enabled`: ze vragen enkel
`MANAGE` (lezen: `READ`). Achter de vlag blijven `GET /setup/overview` en het declareren van invulpunten/gebruik in een
sjabloon; vlag uit = `POST /templates/{d}/revisions/{r}/bookmarks` geeft 405 (het GET-pad op dezelfde URL bestaat altijd),
de overige declaratiepaden en `/setup/overview` 404, ongeacht rechten. Nieuwe lees-endpoint:
`GET /api/catalog-import/import-links/{id}/readiness` (`READ`).

**Nog niet gebouwd (5B-7):** de Prodis-koppeling (het sessietoken doorgeven aan `GET /api/account` en de codes
`catalogImport.read/.manage/.approve` lezen). Ze is extern geblokkeerd op de audience `account` en de seed van die
drie rechten in Prodis. Tot dan is de lokale YAML-bron de enige bron.

### Lokaal aanmelden

- Lokaal draait altijd de **Prodis-Keycloak** op `http://localhost:9080` (realm `prodis`, client
  `catalog-import`). De client moet de redirect-URI's `http://localhost:8081/login/oauth2/code/keycloak` en
  `http://localhost:5173/login/oauth2/code/keycloak` toelaten.
- Zet `CATALOG_OIDC_CLIENT_SECRET` in de omgeving; het secret heeft **geen default** en de backend start niet
  zonder (`CATALOG_OIDC_CLIENT_ID` en `CATALOG_OIDC_ISSUER_URI` hebben lokaal wel defaults, zie
  `application-local.yml`/`application-demo.yml`).
- Werk via de Frontend: open `http://localhost:5173`; u wordt naar Keycloak gestuurd en na de login terug. De
  Vite-proxy stuurt `/api`, `/oauth2` en `/login` door naar :8081.
- De geautomatiseerde tests draaien **zonder** Keycloak (`TestSecurityConfiguration`, niet in het artefact).

### Hoe voert u de API-voorbeelden hieronder uit?

De voorbeelden hieronder staan als **verzoekvorm** (`METHODE pad` + JSON-body). Voer ze uit in een aangemelde
browsersessie:

- **`GET`**: open `http://localhost:5173/<pad>` in het tabblad waarin u bent aangemeld (het antwoord is JSON).
- **`POST`/`PUT`** (JSON): in de browserconsole van dat tabblad, met de CSRF-header uit de cookie:
  ```js
  const xsrf = decodeURIComponent(document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1]);
  await fetch('/api/catalog-import/batches/1/accept-baseline', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf },
    body: JSON.stringify({ reason: 'nulmeting van de demoketen' })
  }).then(r => r.json());
  ```
- **Uploaden**: via het uploadscherm (`/upload`) in de UI.
- **Scripten** (curl, CI) kan **voorlopig niet**: er is geen token-flow voor scripts. Dat is een aparte, latere
  beslissing (§9 V1 in het ontwerp).

Het actorveld mag u weglaten; de naam komt uit uw login.

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
`CATALOG_DB_PASSWORD`); Liquibase voert de migraties uit. Ook lokaal is PostgreSQL nodig: de profielen
`local` en `demo` verbinden met `jdbc:postgresql://localhost:5432/catalog_import`
(gebruiker/wachtwoord `catalog_import`, zie `application-local.yml` en `application-demo.yml`). H2 volstaat
**niet**.

## Lokaal starten met het demoprofiel

Het profiel `demo` gebruikt dezelfde PostgreSQL-database als `local`, **zet de setup-vlag aan** (nodig voor
sjabloonbeheer en `GET /setup/overview`; de overige inrichting werkt ook zonder) en maakt bij het opstarten één volledige voorbeeldketen aan (bronorganisatie `DEMO`, definitie
`DEMO-CSV`, actieve revisie, koppeling `DEMO-LINK`, taak *Demo manuele levering*). In de logregels staat
de `taskId` en hoe je de eerste voorbeeldlevering uploadt (via het uploadscherm in een aangemelde sessie; een
`curl` zonder sessie geeft sinds 5-AUTH 401).

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
poort: `-Dspring-boot.run.arguments=--server.port=8099` (of `CATALOG_SERVER_PORT`).

De frontend start u met `npm --prefix Frontend install` (eenmalig) en `npm --prefix Frontend run dev`; die
proxyt `/api`, `/oauth2` en `/login` naar `http://localhost:8081`. Meld u aan via `http://localhost:5173`
(zie "Lokaal aanmelden" hierboven).

De onderstaande voorbeeldsessie gebruikt de backend op poort 8081 (het profiel `local`) en `taskId=1` (de
waarde uit de logregel bij het opstarten). Alle voorbeelden zijn **verzoekvormen** die u in een aangemelde
browsersessie uitvoert (zie "Hoe voert u de API-voorbeelden hieronder uit?"); paden zijn relatief aan
`http://localhost:5173`. De verwachte antwoorden zijn ongewijzigd.

## Voorbeeldsessie

De voorbeeldbestanden staan in `docs/samples/` en gebruiken de kolommen van de demorevisie:
`leverancier;groep;referentie;omschrijving;prijs;valuta;ean`.

### 1. Eerste levering — alles is nieuw

In de UI: `/upload`, kies taak *Demo manuele levering* en het bestand `docs/samples/01-eerste-levering.csv`,
referentie `REF-01`. Verzoekvorm (multipart, enkel via de UI):

```
POST /api/catalog-import/tasks/1/deliveries    file=01-eerste-levering.csv, deliveryReference=REF-01
```

```json
{"deliveryId":1,"batchId":1,"status":"SCREENED","blockedCode":null,"rawRecordCount":10,
 "validRecordCount":10,"rejectedRecordCount":0,"newCount":10,"contentMutationCount":10}
```

De screening loopt synchroon mee in de upload. Tien geldige regels, tien nieuwe aanbiedingen.

### 2. De batch bekijken

```
GET /api/catalog-import/batches/1      (UI: batchdetail /batches/1)
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

```
GET /api/catalog-import/batches/1/mutations?size=2
```

Elke `CREATE` staat op `"status":"AWAITING_APPROVAL"` met
`"statusReason":"INITIAL_LOAD_REQUIRES_APPROVAL"`. De elfde rij in de lijst is de `IMPORT_MARKER`: het
bewijs dat deze levering verwerkt is, ook wanneer er niets veranderde.

De vaststellingen staan in `/issues` (per foutcode worden er hoogstens 200 voorbeeldrijen bewaard) en de
samenvatting per foutsoort in `/issue-groups` (daar staan de **werkelijke** aantallen):

```
GET /api/catalog-import/batches/1/issues
GET /api/catalog-import/batches/1/issue-groups
```

Bij deze eerste levering is er precies één melding: `INITIAL_LOAD_REQUIRES_APPROVAL`. De lijst met
foutgroepen is hier leeg: een groep ontstaat pas vanaf tien gelijksoortige vaststellingen — met tien
regels in een voorbeeldbestand komt het daar niet van.

### 3. De nulmeting aanvaarden

In de UI: batchdetail `/batches/1`, knop "Aanvaarden als nulmeting". Verzoekvorm (met sessie en CSRF-header):

```
POST /api/catalog-import/batches/1/accept-baseline
{"reason":"nulmeting van de demoketen"}
```

```json
{"batchId":1,"status":"BASELINE_ACCEPTED","skippedMutationCount":10}
```

Hiermee wordt de bronstaat vastgelegd (`state_origin = BASELINE_ACCEPTED`) en krijgen de tien mutaties
`SKIPPED` met reden `BASELINE_ACCEPTED_WITHOUT_PUBLICATION` — er ís nog geen publicatie. Eén bevoegde
persoon volstaat; `reason` is verplicht. `acceptedBy` is optioneel: de naam komt uit uw login (een afwijkende
waarde geeft 400 `ACTOR_FIELD_MISMATCH`; `system` is verboden).

### 4. Exact hetzelfde bestand opnieuw — nul wijzigingen

In de UI: `/upload` met `docs/samples/02-zelfde-levering-nogmaals.csv`, referentie `REF-02`.

```
POST /api/catalog-import/tasks/1/deliveries    file=02-zelfde-levering-nogmaals.csv, deliveryReference=REF-02
```

```json
{"batchId":2,"status":"SCREENED","newCount":0,"changedCount":0,"unchangedCount":10,"contentMutationCount":0}
```

Tien ongewijzigde regels, geen enkele inhoudelijke mutatie. (Een upload met **dezelfde**
`deliveryReference` én dezelfde inhoud is een retry: die antwoordt met 200 en de bestaande levering,
zonder tweede batch. Dezelfde referentie met ándere inhoud geeft 409
`DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT`.)

### 5. Eén gewijzigde prijs

In de UI: `/upload` met `docs/samples/03-prijswijziging.csv`, referentie `REF-03`; daarna de mutaties van batch 3.

```
POST /api/catalog-import/tasks/1/deliveries    file=03-prijswijziging.csv, deliveryReference=REF-03
GET  /api/catalog-import/batches/3/mutations?actionType=UPDATE
```

Eén `UPDATE` voor `A-1001` met `"domainMask":"PRICE"`, `beforeBasePrice 149.50` en
`afterBasePrice 179.50`, status `PLANNED`. De sprong is 20% en dus groter dan de toegelaten 15%: in
`/issues` staat `PRICE_DEVIATION_EXCEEDED` (ernst `WARNING`, met de vorige waarde en de gemiddelden van
de laatste 50 en 200 goedgekeurde waarden erbij) en de batch krijgt
`"validationResult":"VALID_WITH_WARNINGS"`. Een waarschuwing houdt de levering niet tegen; een
kritieke lijnfout wel (stap 6).

### 6. Een levering met fouten

In de UI: `/upload` met `docs/samples/04-met-fouten.csv`, referentie `REF-04`.

```
POST /api/catalog-import/tasks/1/deliveries    file=04-met-fouten.csv, deliveryReference=REF-04
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

In de UI: `/upload` met `docs/samples/05-dubbele-identiteit.csv`, referentie `REF-05`.

```
POST /api/catalog-import/tasks/1/deliveries    file=05-dubbele-identiteit.csv, deliveryReference=REF-05
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
percentage per versie hoger (de wizard vraagt de drempels niet; ze volgen de standaard en worden met een nieuwe versie aangepast) — een zichtbare, geauditeerde keuze.

## Een eigen keten aanmaken (setup-API)

> **Waarschuwing.** De inrichtpaden zijn sinds het NT-spoor **niet meer** achter de setup-vlag: ze vereisen een
> login en het recht `catalogImport.manage` (schrijven) of `.read` (lezen), zie "Rechten (5-PERM)". Wie `manage`
> heeft kan een importdefinitie en haar drempels bepalen en dus de controle op een catalogus uitschakelen: ken
> dat recht bewust toe. Achter `catalogimport.setup-api.enabled` (default `false`; het `demo`-profiel zet ze aan)
> blijven enkel `GET /setup/overview` en het declareren van invulpunten/gebruik in een sjabloon. Zet die vlag
> niet aan in een omgeving met echte gegevens.

Gebruikers doen dit normaal in de UI (Inrichting → "Nieuwe leverancier en taak", daarna "Controleren"; zie
hierboven). Onderstaande verzoekvormen zijn de API erachter. Staat de vlag uit, dan antwoordt
`GET /setup/overview` met 404 (na login) en `POST /templates/{d}/revisions/{r}/bookmarks` met 405.

Verzoekvormen (basis `/api/catalog-import/setup`; JSON-body; uit te voeren in een aangemelde browsersessie met
`X-XSRF-TOKEN`, zie hierboven):

```
POST /api/catalog-import/setup/source-organisations
     {"code":"ACME","name":"ACME gereedschap","type":"SUPPLIER"}
POST /api/catalog-import/setup/definitions
     {"sourceOrganisationCode":"ACME","code":"ACME-CSV","name":"ACME catalogus","usageType":"OWN_DEFINITION"}
POST /api/catalog-import/setup/definitions/2/revisions
     {"delimiter":";","hasHeader":true,"identityProfileKind":"THREE_PART","supplierField":"leverancier","supplierGroupField":"groep","supplierReferenceField":"referentie","basePriceField":"prijs","descriptionField":"omschrijving","currencyField":"valuta","canonicalisationVersion":2,"creationThresholdSharePercent":10,"maxCriticalSharePercent":25}
POST /api/catalog-import/setup/revisions/2/mappings
     {"targetFieldCode":"EAN","sourceReference":"ean","sequenceNumber":1}
POST /api/catalog-import/setup/revisions/2/activate
     {}                                  (approvedBy is optioneel; de naam komt uit uw login)
POST /api/catalog-import/setup/links
     {"definitionId":2,"code":"ACME-LINK","name":"ACME koppeling","supplierCode":"ACME","libraryCode":"PSARF012","defaultCurrency":"EUR"}  (defaultCurrency optioneel, ISO-4217, drie letters; standaard EUR als afwezig)
POST /api/catalog-import/setup/tasks
     {"linkId":2,"name":"ACME manuele levering"}
GET  /api/catalog-import/setup/overview                     (achter de setup-vlag)
GET  /api/catalog-import/import-links/2/readiness           (READ: checklist met alle problemen tegelijk)
POST /api/catalog-import/revisions/2/trial-reads            (MANAGE: proefinlezing, multipart `file`; slaat niets op)
```

Een bestaande configuratie wijzigen gebeurt nooit op een bevroren revisie, maar met een **opvolgrevisie**:
een nieuwe `DRAFT` die de `ACTIVE` (of een oudere `SUPERSEDED`) revisie letterlijk kopieert, inclusief
mappings, filters, kritiek-overrules en bookmarks. Daarna bewerkt u die DRAFT en activeert u ze; de vorige
revisie wordt dan `SUPERSEDED` en blijft leesbaar voor de leveringen die eronder gescreend zijn.

```
POST /api/catalog-import/setup/revisions/2/successor
     {"changeReason":"Drempels aanscherpen"}   (changeReason is VERPLICHT; createdBy optioneel)
```

Er kan hoogstens één DRAFT per definitie openstaan, ook bij een nieuwe revisie of gelijktijdige aanvragen (409
`REVISION_DRAFT_ALREADY_EXISTS`; door de server en een unieke databaseconstraint afgedwongen), en
alleen een `ACTIVE` of `SUPERSEDED` revisie kan gekopieerd worden (409 `REVISION_NOT_CLONEABLE`).

Die DRAFT bewerkt u met twee paden (alleen op een `DRAFT`; op een bevroren revisie 409
`REVISION_NOT_EDITABLE`):

```
PATCH  /api/catalog-import/setup/revisions/3
       {"maxCriticalSharePercent":5}        (elk veld dat u niet noemt, blijft ongewijzigd)
DELETE /api/catalog-import/setup/revisions/3/mappings/7
DELETE /api/catalog-import/setup/revisions/3/filters/4
```

`PATCH` kent dezelfde veldnamen als het aanmaken van een revisie; `null` of afwezig betekent
**ongewijzigd**, en een uitdrukkelijk lege tekst (`""`) maakt alleen een optioneel veld leeg
(kortingscode-, omschrijvings-, munt- en quote-veld). Twee wijzigingen zijn extra beschermd, omdat ze de
aanbiedingsidentiteit veranderen en elke bestaande aanbieding als *nieuw* zouden laten terugkomen:

- `identityProfileKind` of een van de vier `identity*Field`-velden wijzigen vraagt een uitdrukkelijke
  bevestiging `"acknowledgeIdentityChange": true`, anders 409 `IDENTITY_CHANGE_NOT_ACKNOWLEDGED`.
- `canonicalisationVersion` wijzigen kan **niet meer** zodra er voor een koppeling van deze definitie
  aanvaarde bronstaat bestaat: 409 `REVISION_CANONICALISATION_CHANGE_BLOCKED`. Deze blokkade is
  onvoorwaardelijk — er is geen bevestiging die haar opheft, want er bestaat geen migratie voor.

Verwijderen geeft 204, of 404 `MAPPING_NOT_FOUND`/`FILTER_NOT_FOUND` wanneer de rij niet bestaat of bij een
andere revisie hoort. Steunde er een bookmarkdeclaratie op de verwijderde rij, dan volgt 409 met de
bijhorende `CONFIG_BOOKMARK_*`-code en wordt er niets verwijderd.

Gebruik de `id` uit elk antwoord in de volgende stap (hierboven is `2` het id van de tweede definitie,
revisie en koppeling — naast de demoketen). `overview` toont de hele boom met de `taskId` die u bij de
upload nodig hebt (ook `GET /tasks` geeft hem). Scripten kan voorlopig niet (V1 = A1).

Goed om te weten:

- Een revisie ontstaat als `DRAFT`. Mappings, filters (`/filters`) en kritiek-overrules
  (`/field-criticality`) kunnen alleen op een `DRAFT`; `activate` bevriest ze en zet een eventuele
  vorige actieve revisie op `SUPERSEDED`.
- `activate` en elke mapping/filter/overrule lopen door **dezelfde** configuratievalidatie als de
  screening. Een fout geeft 400 met de bestaande code in het bericht, bijvoorbeeld
  `CONFIG_FIELD_MAPPING_DUPLICATES_REVISION` wanneer u een veld mapt dat de revisie zelf al bepaalt.
- `PATCH` en `DELETE` op een DRAFT doen die volledige validatie **niet**: een werkversie mag tussentijds
  onvolledig zijn (anders kon u een mapping nooit vervangen). `activate` valideert alsnog alles, dus een
  DRAFT die u half afwerkt, wordt nooit stil actief.
- Canonicalisatieversie 2 is verplicht zodra er een munt gelezen wordt of een referentie (EAN/PIM/CAB)
  gemapt is; onder versie 1 zou zo'n wijziging buiten de vingerafdruk vallen.
- Een onbekend id geeft 404, een dubbele code 409 (ook bij twee gelijktijdige aanvragen: 409, geen 500), een
  ongeldige waarde 400 — telkens met een stabiele `code` in het antwoord; bij 400 uit de setup-API is dat
  `CONFIG_*` of `<VELD>_REQUIRED|_TOO_LONG|_INVALID`. Een 400 zonder `code` komt van een onleesbare body of een
  ongeldige enumwaarde.
- De checklist en de proefinlezing tonen **alle** configuratiefouten van een concept tegelijk; de screening en
  `activate` blijven bij de eerste fout (zelfde code, status en tekst als voorheen).

## Database en archief

Er is geen H2-console: de gegevens staan in de lokale PostgreSQL-database `catalog_import` en blijven
dus bestaan tussen twee starts. Het bronarchief van het demoprofiel staat op schijf, onder
`${java.io.tmpdir}/catalogimport-demo-archive` (profiel `local`: `C:/tmp/catalogimport-archive`).

## Gerichte tests

De tests draaien zonder Keycloak (`TestSecurityConfiguration`). Nooit de volledige reactor; altijd de
betrokken klassen:

```bash
mvn -pl Web -am test -Dtest="SetupApiDisabledTest,SetupApiFlowTest,DemoDataSeederTest" -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl Web -am test -Dtest="DeliveryUploadTest,DeliveryScreeningFlowTest,AcceptBaselineReviewFlowTest,BatchBaselineHttpTest,ValidationResultTest,ImportControlSchemaTest,ScreeningSchemaTest" -Dsurefire.failIfNoSpecifiedTests=false
```

(In PowerShell: `"-Dtest=SetupApiDisabledTest,SetupApiFlowTest,DemoDataSeederTest"` met aanhalingstekens
rond de hele parameter.)

Veel `@SpringBootTest`-klassen in één Maven-run kunnen de PostgreSQL-verbindingen uitputten (foutmelding
*remaining connection slots are reserved…*). Draai de klassen dan los of in kleine groepen.

### Volledige testronde in een eigen schema

De tests draaien tegen PostgreSQL (profiel `local`). Een deel ervan (o.a. `ImportControlSchemaTest`,
`ScreeningSchemaTest`) gebruikt vaste codes zonder op te ruimen en slaagt dus alleen op een lege database. Gebruik
daarom voor een volledige of herhaalde ronde het script, dat een eigen schema (`ci_fulltest`) leegmaakt en de
tests daar draait, met kleine verbindingspools en een contextcache van 2, zodat de verbindingen niet opraken.
De echte data in `public` blijft onaangeroerd; `public` wordt altijd geweigerd.

```powershell
.\scripts\test\run-full-tests.ps1                                  # alle tests van de Web-module
.\scripts\test\run-full-tests.ps1 -Tests BundleHttpTest,SecurityHttpTest
.\scripts\test\run-full-tests.ps1 -DropAfter                       # schema na afloop verwijderen
```

Het script vereist alleen CREATE op de database (geen CREATEDB), leest `CATALOG_DB_URL`/`_USERNAME`/`_PASSWORD`
(defaults zoals `application-local.yml`) en schrijft het volledige log naar `Web/target/full-test.log`. Het
toont per klasse wat er faalt; "BUILD SUCCESS" zegt niets, want falende tests breken de build niet. Een volledige
ronde duurt lang omdat elke Spring-context opnieuw opstart.

Frontend: `tsc --noEmit -p .` in `Frontend/` controleert niets (het root-tsconfig verwijst enkel door). Gebruik
`tsc --noEmit -p tsconfig.app.json`.

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
