# Ontwerp — Materialisatiewizard: van importsjabloon naar leveranciersgebonden definitie + koppeling

Bron: denker-zwaar-ontwerp van 2026-09-23, volgend op de bindende domeinmodelbeslissing van diezelfde
dag. Bindend naast `docs/decisions.md`, `business-analyse-leveranciersbibliotheken.md` §14.14-§14.20,
`docs/design/fase3-rules-design.md` en `docs/design/fase4-publication-bundle-design.md`.

## 0. Vooraf — wat al vastligt en wat dit ontwerp toevoegt

Het domeinmodel is gebouwd (changeset `006-import-template-bookmark.sql`, entiteiten
`ImportDefinitionBookmark`, `ImportDefinitionBookmarkUsage`, `ImportDefinitionBookmarkValue`,
`ImportLinkBookmarkValue`, enums `BookmarkValueScope`/`BookmarkDataType`/`BookmarkUsagePlace`,
`DefinitionUsageType`). Er is vandaag **geen enkele service, repository of endpoint** bovenop die vier
tabellen: `Dao` kent geen bookmark-repository, `SetupService` kent geen bookmark-opdracht. Een sjabloon
kan dus alleen via SQL of testcode ontstaan. Dit ontwerp beschrijft de service-/REST-laag die daar
bovenop komt.

Eén spanning tussen de documenten is hier leidend en wordt **niet** door dit ontwerp beslecht (zie §12,
vraag Q2): §14.15 eist "elke leverancier een eigen importdefinitie", terwijl `docs/decisions.md`
2026-09-23 keuze 2 een gedeelde, uit een sjabloon afgeleide definitie uitdrukkelijk toestaat. De
scheidslijn is de bookmarkscope. Het canonieke voorbeeld uit §14.16 (`DETAILLEVERANCIER`, een
per-leverancier verschillende waarde die als *vaste mapping* wordt toegepast) valt precies op die lijn.

## 1. Regelinventaris

**Materialisatie (R-MAT).** Uit één sjabloonrevisie + ingevulde bookmarkwaarden ontstaat een nieuwe
`ImportDefinition` (`usage_type = OWN_DEFINITION`, `based_on_definition_id` = het sjabloon), één
`ImportDefinitionRevision` (nummer 1, status `DRAFT`, `based_on_revision_id` = de sjabloonrevisie), een
kopie van alle `import_field_mapping`-, `import_record_filter`- en
`import_revision_field_criticality`-rijen van die sjabloonrevisie, en één `ImportLink`. Materialisatie
**activeert nooit** (§14.16 stap 4: "een nieuwe *concept*-importdefinitie"; stap 6: eerst de gewone
screening). Materialisatie maakt **geen** `CatalogImportTask`, geen `Delivery`, geen `ImportBatch` en
raakt geen enkele tabel van Fase 2-4 aan.

**Snapshot, geen verwijzing (R-MAT-02).** §14.16: "Na stap 4 bestaat geen runtime-afhankelijkheid meer
waarbij een latere bookmarkwijziging in het sjabloon oude importen ongemerkt verandert." Elke
DEFINITION-scope waarde wordt **letterlijk** in de doelkolom van de afgeleide revisie geschreven én als
`import_definition_bookmark_value`-rij bewaard met `source_template_revision_id`. Er blijft na
materialisatie geen enkel leespad van de runtime naar de sjabloonrevisie.

**Declaraties worden meegekopieerd (R-MAT-03).** De **LINK-scope** declaratierijen
(`import_definition_bookmark` + hun `_usage`-rijen) worden meegekopieerd naar de afgeleide revisie. De
DEFINITION-scope declaraties worden **niet** gekopieerd: die zijn opgelost, hun spoor leeft in
`import_definition_bookmark_value`. Motivering: de controle "zijn alle verplichte LINK-bookmarks
ingevuld" moet bij het starten van een levering beantwoord kunnen worden zonder het sjabloon te lezen —
precies wat R-MAT-02 verbiedt. De tabel dwingt dit niet af (de FK staat naar `import_definition_revision`,
niet naar "een sjabloonrevisie"), dus dit is additief en vraagt geen migratie.

**Scope (R-BMK-01, bindend, 23/09 keuze 1).** `DEFINITION` wordt bij materialisatie in de afgeleide
revisie vastgezet; `LINK` wordt per `ImportLink` ingevuld. Er is geen default en die komt er ook niet.

**Verplichte bookmarks blokkeren (R-BMK-02, bindend, 23/09 keuze 6).** Een niet ingevulde verplichte
bookmark blokkeert op drie plaatsen, telkens met de bestaande `CONFIG_*`-foutfamilie: bij
materialisatie, bij het activeren van de afgeleide revisie, en bij de start van een batch/levering.
Een verplichte bookmark wordt **niet** bevredigd door `""` (zie R-VAL-04).

**`null` ≠ `""` (R-BMK-03).** Afwezigheid van een rij betekent "niet ingevuld"; een rij met
`value_text = ''` betekent "uitdrukkelijk leeg". Dezelfde regel als de aanbiedingsidentiteit
(`docs/decisions.md` 2026-09-18). De materialisatie mag die twee nooit op één toestand laten vallen.

**Detailleverancier ≠ bibliotheekzoekleverancier (R-BMK-04, §14.15/§15.2 punt 3).** `BIB_ZOEKLEVERANCIER`
wordt **nooit** automatisch gelijkgesteld aan de leverancier van de koppeling of aan
`DETAILLEVERANCIER`. Ontbreekt de bookmark, dan blijft `import_link.library_search_supplier_code` `null`.
Nooit stil afleiden.

**Geen sjabloon met een koppeling (R-SHR-01).** Al hard afgedwongen door changeset `006-5`
(samengestelde FK + check). De service controleert dit ook, maar rekent er niet op: een sjabloon dat
toch een `ImportLink` zou krijgen, krijgt leveringen, batches en uiteindelijk een publicatie.

**Bewust uitgesteld:** sjabloonversievergelijking/diff ("vergelijk met nieuwere sjabloonversie",
§14.16, schermkaart §15.6 nr. 15) en bulkcreatie uit een lijst bookmarkwaarden. Beide zijn in
`docs/decisions.md` 2026-09-23 expliciet buiten deze bouwstap geplaatst en worden hier **niet**
vooruitgebouwd. Zie §9.

## 2. Datamodel — geen schemawijziging

Deze bouwstap voegt **geen enkele kolom, tabel of constraint** toe. Alles wat nodig is, bestaat al in
001-006. Concreet gebruikt de materialisatie:

| Rij die ontstaat | Tabel | Sleutelvelden |
|---|---|---|
| Afgeleide definitie | `import_definition` | `usage_type='OWN_DEFINITION'`, `based_on_definition_id`=sjabloon, `source_organisation_id`=**overgenomen van het sjabloon** |
| Afgeleide revisie | `import_definition_revision` | `revision_number=1`, `status='DRAFT'`, `based_on_revision_id`=sjabloonrevisie, vier hashes **herberekend** |
| Kopie van de regels | `import_field_mapping`, `import_record_filter`, `import_revision_field_criticality` | zelfde `sequence_number`/`field_key`, met DEFINITION-waarden ingevuld |
| Gekopieerde LINK-declaraties | `import_definition_bookmark` + `_usage` | alleen `value_scope='LINK'` |
| DEFINITION-snapshot | `import_definition_bookmark_value` | `(definition_revision_id, bookmark_name)`, `source_template_revision_id` |
| Koppeling | `import_link` | `definition_usage_type` blijft via de default `OWN_DEFINITION` (kolom is bewust niet gemapt) |
| LINK-waarden | `import_link_bookmark_value` | `(import_link_id, bookmark_name)` |

**Bronorganisatie is niet kiesbaar.** `docs/decisions.md` 2026-09-23 keuze 4 houdt
`source_organisation_id` `NOT NULL` op een sjabloon en zet een bronoverstijgend sjabloon buiten scope.
De afgeleide definitie erft daarom de bronorganisatie van het sjabloon (de VROOAM-aankoopvereniging die
de bestanden levert); de concrete leverancier zit op de `ImportLink` — exact de interpretatie die op
2026-09-18 bevestigd is. Het verzoek draagt dus **geen** bronorganisatie.

**Hashes moeten herberekend worden.** Een DEFINITION-bookmark op `REVISION_IDENTITY_FIELD` verandert
`identity_*_field` en dus `record_rules_config_hash` en `composite_config_hash`. De canonieke
hashberekening zit vandaag als private `hash(...)` in `SetupService`. Ze wordt — net als de
`ActorNames`-refactor in Fase 4b — verplaatst naar een gedeelde package-private helper
`RevisionConfigHashes` in `Service`, met byte-identiek gedrag en zonder publieke signatuurwijziging.
Zonder die herberekening dragen twee inhoudelijk verschillende revisies dezelfde configuratiehash, wat
de hele §14.14-traceerbaarheid stilzwijgend onwaar maakt.

## 3. Service-ontwerp

**Nieuwe klassen in `Dao`:** `ImportDefinitionBookmarkRepository`,
`ImportDefinitionBookmarkUsageRepository`, `ImportDefinitionBookmarkValueRepository`,
`ImportLinkBookmarkValueRepository` — gewone Spring Data-repo's. Geen `JdbcTemplate`-DAO: het gaat om
tientallen rijen per materialisatie, niet om bulk (contrast met `CandidateStageDao`).

**Nieuwe klassen in `Service`:**
- `TemplateBookmarkService` — declareren en lezen van bookmarks op een sjabloonrevisie (DRAFT-only,
  via de bestaande `editableRevision`-regel).
- `TemplateMaterialisationService` — de materialisatie zelf. Eén `@Transactional`-methode, zelfde stijl
  als `SetupService` (geen `TransactionTemplate`/`findByIdForUpdate`: er is geen statusflow om te
  serialiseren, en de enige echte race — twee gelijktijdige materialisaties met dezelfde definitie- of
  koppelingscode — wordt door de bestaande unieke constraints opgevangen).
- `LinkBookmarkValueService` — lezen/wijzigen van LINK-waarden op een bestaande koppeling, met het slot
  van keuze 5.
- `support/BookmarkDeclarations` — package-private validator van de declaratie-integriteit (§4 fase C).
  Wordt door zowel `TemplateBookmarkService` (bij declareren) als `TemplateMaterialisationService`
  (defensief) aangeroepen: één implementatie, twee aanroepplaatsen.
- `support/BookmarkValueRules` — type-, patroon-, keuzelijst- en lengtecontrole van één waarde.
- `support/RevisionConfigHashes` — zie §2.

**Bestaande klassen die geraakt worden:**
- `SetupService.activateRevision` krijgt de DEFINITION-scope volledigheidscontrole (R-BMK-02).
- `DeliveryIntakeService.resolve` krijgt de LINK-scope volledigheidscontrole, pal naast de bestaande
  `CONFIG_PRICE_FIELD_MISSING`-controle (zelfde vorm, zelfde plaats, zelfde foutfamilie).
- `SetupService` levert `hash(...)` af aan `RevisionConfigHashes`.
Geen enkele bestaande publieke methode, kolom, API-veld of configuratie-property wordt hernoemd.

**Transactiegrens.** Eén transactie per materialisatie: alles of niets. Een half gematerialiseerde
definitie (regels gekopieerd, koppeling niet) zou een definitie zonder eigenaar achterlaten die er
geldig uitziet. Een `DataIntegrityViolationException` op `uk_import_definition_code`/`uk_import_link_code`
/`uk_import_link_scope` wordt vertaald naar de bestaande 409-codes `DEFINITION_CODE_IN_USE`,
`LINK_CODE_IN_USE`, `LINK_SCOPE_IN_USE` — zelfde patroon als `DeliveryIntakeService` al doet voor
`uk_task_run_concurrency`.

**Bewust niet idempotent.** Een herhaalde materialisatie botst op de bestaande unieke sleutels en geeft
409; er ontstaat nooit een duplicaat. Er komt géén nieuwe idempotentiesleutel of -kolom voor: de wizard
is per §14.18 een eenmalige, interactieve handeling ("Frequentie: eenmalig of bij structurele
wijziging"), en bevriezen in Fase 4 is om dezelfde reden bewust niet idempotent. Dit is een bewuste
afwijking van het `Delivery`/`PublicationBundle`-patroon, niet een vergetelheid.

## 4. Validatie — checks, volgorde, foutcodes

De volgorde is: goedkoop en structureel eerst, dan de integriteit van het sjabloon, dan de waarden, dan
pas schrijven. Niets wordt geschreven voordat fase A t/m E volledig geslaagd is.

### Fase A — sjabloon en sjabloonversie oplossen
| # | Check | Fout |
|---|---|---|
| A1 | definitie bestaat | 404 `TEMPLATE_NOT_FOUND` |
| A2 | `usage_type = REUSABLE_TEMPLATE` | 409 `DEFINITION_NOT_A_TEMPLATE` |
| A3 | opgegeven `templateRevisionId` hoort bij deze definitie; zonder opgave wordt de `ACTIVE` revisie genomen | 404 `TEMPLATE_REVISION_NOT_FOUND` / 409 `NO_ACTIVE_TEMPLATE_REVISION` |
| A4 | die revisie heeft status `ACTIVE` of `SUPERSEDED` (niet `DRAFT`) | 409 `TEMPLATE_REVISION_NOT_MATERIALISABLE` |

**Beslist door de mens (Q3, 2026-09-23):** ook uit een `SUPERSEDED` sjabloonrevisie mag gematerialiseerd
worden — volgt §14.16 letterlijk ("de juiste sjabloonversie kiezen"). Enkel `DRAFT` (nog niet
doorgelopen screening/validatie van het sjabloon zelf) blijft geweigerd. Het antwoord van
`GET .../bookmarks` en het antwoord van de materialisatie tonen daarom altijd expliciet welke
sjabloonrevisie effectief gebruikt is (`templateRevisionNumber`, `templateRevisionStatus` in
`MaterialisationView`), zodat een materialisatie uit een oudere versie nooit stilzwijgend gebeurt.

### Fase B — vorm van het verzoek (400, `BadRequestException` met stabiele code)
| # | Check | Fout |
|---|---|---|
| B1 | `mode` aanwezig (`NEW_DEFINITION`\|`REUSE_DEFINITION`), geen default | `MATERIALISATION_MODE_REQUIRED` |
| B2 | `reuseDefinitionId` aanwezig ⇔ `mode = REUSE_DEFINITION` | `REUSE_DEFINITION_REQUIRED` / `REUSE_DEFINITION_NOT_ALLOWED` |
| B3 | `materialisedBy` via de bestaande `ActorNames`-regel (niet leeg, ≤100, nooit `system`) | bestaande 400 |
| B4 | `definitionCode`/`definitionName` (bij NEW) en `linkCode`/`linkName` aanwezig en binnen lengte | bestaande 400 |

`mode` heeft bewust geen default, om exact dezelfde reden als `target_mode` op de publicatiebundel: een
stil geraden "nieuw" of "hergebruik" bepaalt of twee leveranciers voortaan één configuratie delen.

### Fase C — integriteit van de sjabloondeclaratie (409, `CONFIG_*`)
Deze fase is de **defensieve** controle. Zie het "Important technical constraint discovered"-blok in
§13: de regel "een DEFINITION-scope bookmark mag nooit een per-leverancier verschillende waarde dragen"
is volgens de bindende beslissing bij het *declareren* afgedwongen, maar er bestaat vandaag geen
declaratiepad en de database kan de regel niet uitdrukken. Materialisatie vertrouwt daarom **niet** op
een guard die er niet is.

| # | Check | Fout |
|---|---|---|
| C1 | geen DEFINITION-scope bookmark met een `LINK_*`-plaats | `CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT` |
| C2 | elke bookmark heeft ≥1 `_usage`-rij | `CONFIG_BOOKMARK_WITHOUT_PLACE` |
| C3 | elke `_usage`-rij wijst naar een bestaand doel in déze revisie (doelveldcode bestaat als mapping; filtervolgnummer bestaat; identiteitssleutel is een `RevisionCriticalityField`-sleutel) | `CONFIG_BOOKMARK_PLACE_UNRESOLVED` |
| C4 | de plaats wordt in deze bouwstap ondersteund (zie §9) | `CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED` |
| C5 | LINK-scope bookmark met een revisieniveau-plaats: **afhankelijk van vraag Q2** | `CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT` of `DEFINITION_NOT_SHAREABLE` bij hergebruik |

C1 is eenrichtingsverkeer en staat los van Q2: een koppelingskolom is per constructie per koppeling, dus
`LINK_LIBRARY_CODE`/`LINK_SEARCH_SUPPLIER`/`LINK_SUPPLIER_ORGANISATION` als DEFINITION declareren is
altijd een declaratiefout.

De kost van fase C is enkele tientallen rijen lezen. De kost van haar weglaten is dat een
leveranciersnummer van leverancier A stil in de gedeelde definitie van leverancier B terechtkomt en als
aanbiedingsidentiteit gepubliceerd wordt. Die verhouding rechtvaardigt de dubbele controle ruimschoots.

### Fase D — de ingevulde waarden
| # | Check | Fout |
|---|---|---|
| D1 | elke aangeleverde naam is in deze sjabloonrevisie gedeclareerd | 400 `BOOKMARK_UNKNOWN` — **nooit stil negeren** |
| D2 | de scope van elke aangeleverde naam past bij de modus (bij `REUSE` zijn DEFINITION-waarden verboden) | 400 `DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE` / `BOOKMARK_SCOPE_MISMATCH` |
| D3 | elke **verplichte** bookmark in scope heeft een waarde, of een `default_value` | 400 `CONFIG_REQUIRED_BOOKMARK_MISSING` |
| D4 | een verplichte bookmark wordt niet bevredigd door `""` | 400 `CONFIG_REQUIRED_BOOKMARK_MISSING` |
| D5 | type klopt (`INTEGER`/`DECIMAL` parsebaar, `DATE` ISO-8601, `BOOLEAN` true/false, `ENUM` ∈ `allowed_values`), `validation_pattern` matcht | 400 `CONFIG_BOOKMARK_VALUE_INVALID` |
| D6 | de waarde past in de **doelkolom**, niet alleen in `value_text` | 400 `CONFIG_BOOKMARK_VALUE_TOO_LONG` |
| D7 | een `LINK_*`-plaats wordt niet tegelijk door een bookmark én door een expliciet requestveld gevuld | 400 `LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT` |

D5 dwingt principe 8 van AGENT.md af in de bookmarklaag: een niet-parsebare `DECIMAL` wordt **nooit**
stil 0 of leeg — ze blokkeert.

D6 is geen theorie: `value_text` is `varchar(500)`, maar `import_link.library_code` is `varchar(20)` en
`library_search_supplier_code` `varchar(50)`. Zonder deze controle slaagt de bookmarkrij en faalt de
koppeling pas op een databasefout (500 in plaats van een leesbaar antwoord).

D7 regelt de verhouding tussen bookmark en requestveld eenduidig: declareert het sjabloon een
`LINK_LIBRARY_CODE`-bookmark, dan **is** die bookmark het invoerveld voor `libraryCode` en mag het
requestveld niet meegegeven worden; declareert het sjabloon er geen, dan is `libraryCode` een gewoon
verplicht requestveld (de kolom is `NOT NULL`). Zo bestaat er nooit twee bronnen voor dezelfde waarde.

**Waarheidsbron na materialisatie:** voor de drie `LINK_*`-plaatsen is de **kolom op `ImportLink`** de
enige waarheid voor de runtime; de `import_link_bookmark_value`-rij is het auditspoor van wat de wizard
invulde. Beide worden in dezelfde transactie geschreven.

### Fase E — uniciteit en hergebruik
Bestaande codes: 409 `DEFINITION_CODE_IN_USE`, `LINK_CODE_IN_USE`, `LINK_SCOPE_IN_USE`; 404
`SOURCE_ORGANISATION_NOT_FOUND` bij een onbekende leverancierscode. Hergebruikspecifiek: zie §6.

### Fase F — schrijven, en daarna nog eens valideren
Na het schrijven en vóór het einde van de transactie draait de materialisatie
`SourceStructureConfigFactory` + `ImportMappingConfigFactory` op de afgeleide revisie — dezelfde
fabrieken die de screening én `SetupService.addMapping`/`activateRevision` gebruiken. Een
`ScreeningBlockedException` wordt hier vertaald naar 400 met de `CONFIG_*`-code in de boodschap en de
hele transactie rolt terug. Een afgeleide definitie die pas bij de eerste levering blijkt te blokkeren,
is onbruikbaar.

## 5. REST-contract

Nieuwe controller `CatalogImportTemplateController` onder `/api/catalog-import/templates`, plus een
kleine `CatalogImportLinkController` onder `/api/catalog-import/links` voor de twee
bookmarkwaarde-endpoints. Inline `record`-requests/responses, `PageResult` voor lijsten, de bestaande
`ApiExceptionHandler` — exact de stijl van `CatalogImportBundleController`. **Er gaat geen JPA-entiteit
naar buiten.**

| Methode | Pad | Doel |
|---|---|---|
| `GET` | `/templates` | sjablonen (alleen `REUSABLE_TEMPLATE`), gepagineerd |
| `GET` | `/templates/{definitionId}/revisions/{revisionId}/bookmarks` | de invulset voor het scherm: naam, label, uitleg, type, scope, verplicht, default, keuzelijst, patroon, volgorde, plaatsen — plus een `problems`-lijst met de fase C-bevindingen, **leesbaar zonder te werpen** (§14.16 stap 2) |
| `POST` | `/templates/{definitionId}/revisions/{revisionId}/bookmarks` | declareren (DRAFT-only) |
| `POST` | `/templates/{definitionId}/revisions/{revisionId}/bookmarks/{name}/usages` | een toegelaten plaats toevoegen (witte lijst) |
| `GET` | `/templates/{definitionId}/materialisations` | welke definities/koppelingen uit dit sjabloon voortkwamen — de keuzelijst voor "hergebruik" |
| `POST` | `/templates/{definitionId}/materialisations` | **de operatie**; 201 |
| `GET` | `/links/{linkId}/bookmark-values` | ingevulde LINK-waarden, met per rij `declared` (false = wees, zie §7) |
| `PUT` | `/links/{linkId}/bookmark-values/{name}` | wijzigen, met het slot van keuze 5 |

**Request van de materialisatie:**
```
MaterialiseRequest(
    Long    templateRevisionId,      // null = de ACTIVE sjabloonrevisie
    MaterialisationMode mode,        // NEW_DEFINITION | REUSE_DEFINITION, geen default
    Long    reuseDefinitionId,       // alleen bij REUSE_DEFINITION
    String  definitionCode,          // alleen bij NEW_DEFINITION
    String  definitionName,
    String  changeReason,            // optioneel; zonder opgave een vaste zin met sjabloon + revisie
    String  linkCode, String linkName,
    String  supplierOrganisationCode,      // tenzij een LINK_SUPPLIER_ORGANISATION-bookmark bestaat
    String  libraryCode,                   // tenzij een LINK_LIBRARY_CODE-bookmark bestaat
    String  librarySearchSupplierCode,     // tenzij een LINK_SEARCH_SUPPLIER-bookmark bestaat
    List<BookmarkValue> bookmarkValues,    // (name, value) — value "" is een bewuste lege waarde
    String  materialisedBy)
```

**Antwoord:**
```
MaterialisationView(
    long templateDefinitionId, long templateRevisionId, int templateRevisionNumber,
    long definitionId, String definitionCode, boolean definitionCreated,
    long definitionRevisionId, int definitionRevisionNumber, String definitionRevisionStatus,
    long importLinkId, String importLinkCode,
    List<AppliedValue> definitionValues,   // (name, dataType, value, placeKind, targetHint)
    List<AppliedValue> linkValues,
    List<Warning> warnings)                // (code, bookmarkName, message)
```
`definitionCreated` onderscheidt nieuw van hergebruikt — dezelfde stijl als `IntakeResult.created`.
`warnings` is getypeerd, geen vrije tekst; codes: `OPTIONAL_BOOKMARK_NOT_FILLED` (plaats behoudt de
sjabloonwaarde), `LINK_SEARCH_SUPPLIER_NOT_DERIVED` (R-BMK-04, expliciet gemeld in plaats van stil
afgeleid).

**Statuscodes samengevat.** 201 bij materialisatie; 200 bij lezen en bij `PUT`. 404:
`TEMPLATE_NOT_FOUND`, `TEMPLATE_REVISION_NOT_FOUND`, `DEFINITION_NOT_FOUND`, `LINK_NOT_FOUND`,
`SOURCE_ORGANISATION_NOT_FOUND`. 409: `DEFINITION_NOT_A_TEMPLATE`,
`TEMPLATE_REVISION_NOT_MATERIALISABLE`, `NO_ACTIVE_TEMPLATE_REVISION`, `DEFINITION_NOT_FROM_TEMPLATE`,
`TEMPLATE_REVISION_MISMATCH_ON_REUSE`, `DEFINITION_NOT_SHAREABLE`, `CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT`,
`CONFIG_BOOKMARK_WITHOUT_PLACE`, `CONFIG_BOOKMARK_PLACE_UNRESOLVED`, `CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED`,
`DEFINITION_CODE_IN_USE`, `LINK_CODE_IN_USE`, `LINK_SCOPE_IN_USE`, `REVISION_NOT_EDITABLE`,
`LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH`. 400 met code: `MATERIALISATION_MODE_REQUIRED`,
`REUSE_DEFINITION_REQUIRED`, `REUSE_DEFINITION_NOT_ALLOWED`, `DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE`,
`BOOKMARK_UNKNOWN`, `BOOKMARK_SCOPE_MISMATCH`, `CONFIG_REQUIRED_BOOKMARK_MISSING`,
`CONFIG_BOOKMARK_VALUE_INVALID`, `CONFIG_BOOKMARK_VALUE_TOO_LONG`, `LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT`.

> `CONFIG_REQUIRED_BOOKMARK_MISSING` draagt bewust twee statussen: **400** bij materialisatie (de
> aanroeper liet een veld weg en kan het herstellen) en **409** bij het activeren van een revisie of bij
> de start van een levering (de serverstand is onvolledig) — daar precies zoals het bestaande
> `CONFIG_PRICE_FIELD_MISSING` in `DeliveryIntakeService.resolve`. Eén regel, één code, status naar wie
> ze kan oplossen.

## 6. Hergebruik: "materialiseer nieuw" versus "koppel aan een bestaande gedeelde definitie"

Het onderscheid is **altijd expliciet** (`mode`), nooit afgeleid uit de aanwezigheid van een veld. Bij
`REUSE_DEFINITION` geldt:

1. De hergebruikte definitie bestaat, heeft `usage_type = OWN_DEFINITION` (anders vangt `006-5` het
   alsnog af) en heeft een revisie.
2. Ze stamt uit **dit** sjabloon: `based_on_definition_id` = de sjabloondefinitie. Anders 409
   `DEFINITION_NOT_FROM_TEMPLATE`. Een willekeurige definitie aan een koppeling hangen is geen
   sjabloonwerk; daar bestaat `POST /setup/links` al voor.
3. Ze stamt uit **dezelfde sjabloonversie** als het verzoek: `revision.based_on_revision_id` =
   `templateRevisionId`. Anders 409 `TEMPLATE_REVISION_MISMATCH_ON_REUSE`. Stil een definitie
   hergebruiken die op een oudere sjabloonversie bevroren is, terwijl de gebruiker een nieuwere koos, is
   precies de sjabloonversievergelijking die buiten scope staat — die mag hier niet half en onzichtbaar
   gebeuren.
4. Er worden **geen** DEFINITION-scope waarden aanvaard (D2) en er wordt **geen** nieuwe revisie
   gemaakt: de bestaande revisie blijft byte-identiek. Alleen `import_link` +
   `import_link_bookmark_value` ontstaan.
5. De definitie moet deelbaar zijn — de invulling hiervan hangt af van vraag Q2 (§12). Is ze dat niet,
   dan 409 `DEFINITION_NOT_SHAREABLE` met vermelding van de bookmark die het verhindert.

`GET /templates/{id}/materialisations` levert de keuzelijst waarmee het scherm de gebruiker beide
opties kan voorleggen, inclusief per bestaande definitie: sjabloonversie, aantal koppelingen en
deelbaarheid.

## 7. Blokkeerpunten, slot en reproduceerbaarheid (23/09 keuzes 5 en 6)

**Bij activeren (`SetupService.activateRevision`).** Naast de bestaande configuratievalidatie: elke
verplichte **DEFINITION**-scope bookmark van deze revisie heeft een `import_definition_bookmark_value`-rij
→ anders 409 `CONFIG_REQUIRED_BOOKMARK_MISSING`. LINK-scope kan hier niet beoordeeld worden (er is nog
geen of meer dan één koppeling). Een mapping met `value_kind = BOOKMARK` in een te activeren revisie
wordt al door `ImportMappingConfigFactory` geweigerd (`CONFIG_MAPPING_SOURCE_UNRESOLVED`); dat blijft
zo — **`FieldValueKind.BOOKMARK` verschijnt nooit in een actieve revisie**, materialisatie schrijft
altijd `FIXED_VALUE` met de werkelijke waarde.

**Bij de start van een levering (`DeliveryIntakeService.resolve`).** Voor de koppeling van de taak:
elke verplichte LINK-scope bookmark die op de actieve revisie gedeclareerd staat, heeft een
`import_link_bookmark_value`-rij met een niet-lege waarde → anders 409
`CONFIG_REQUIRED_BOOKMARK_MISSING`, vóór er iets gearchiveerd wordt. Zie vraag Q4 (§12): dit is de
"weiger de upload"-lezing; de alternatieve lezing ("levering aanvaarden en de batch `BLOCKED` zetten")
ligt bij de mens.

**Wezen.** Een `import_link_bookmark_value`-rij waarvan de naam in de huidige actieve revisie niet meer
gedeclareerd is, telt **niet** als ingevuld en wordt ook **niet** toegepast. Ze blijft staan als
auditmateriaal en wordt in `GET /links/{id}/bookmark-values` met `declared = false` getoond — precies
zoals de entiteit-javadoc eist ("moet zo'n wees tonen en niet stilzwijgend toepassen").

**Slot op LINK-waarden.** `PUT /links/{id}/bookmark-values/{name}` weigert met 409
`LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH` zolang de koppeling een niet-terminale batch heeft. De wijziging
zelf gaat altijd via `ImportLinkBookmarkValue.recordChange(...)`, zodat `previous_value_text`,
`updated_by` en `updated_at` samen geschreven worden (de twee databasechecks beletten een halve audit).

**`import_batch.bookmark_values_hash`.** Bij het aanmaken van de batch (`DeliveryIntakeService.register`)
wordt een SHA-256 gezet over de **LINK-scope waarden die op dat moment gelden**, canoniek als
`naam ␟ waarde ␞`, gesorteerd op naam; `null` wanneer de koppeling geen enkele bookmarkwaarde heeft.
Alleen LINK-waarden: de DEFINITION-waarden liggen al vast in de revisie waarnaar de batch verwijst en
kunnen per definitie niet meer bewegen. De kolom is niet op `ImportBatch` gemapt (bewust, changeset
006-5/006-6) en wordt via één gerichte JDBC-update gezet — zelfde redenering als de vijf-kolom-update
bij een bundelbeslissing: een volledige JPA-`save()` zou andere kolommen kunnen meeschrijven.

## 8. Levenscyclus

```
sjabloon (ImportDefinition, REUSABLE_TEMPLATE)
  └─ sjabloonrevisie ACTIVE  ──materialiseren──►  afgeleide definitie (OWN_DEFINITION)
                                                    └─ revisie 1, DRAFT
                                                         │ (screening/test, bestaande route)
                                                         ▼ activateRevision  [CONFIG_REQUIRED_BOOKMARK_MISSING]
                                                       revisie 1, ACTIVE
                                                         │
                                                       ImportLink ──► levering  [CONFIG_REQUIRED_BOOKMARK_MISSING]
```
Geen enkele bestaande enumwaarde wordt toegevoegd of van betekenis veranderd: `RevisionStatus`,
`DefinitionUsageType`, `FieldValueKind`, `BookmarkValueScope`, `BookmarkDataType`, `BookmarkUsagePlace`
blijven exact zoals ze zijn. De enige nieuwe enum is `MaterialisationMode` (twee waarden), die
uitsluitend in het REST-contract leeft en niet in de database.

## 9. Bewuste grenzen — wat dit ontwerp níét bouwt

1. **Sjabloonversievergelijking / diff** (§14.16 "vergelijk met nieuwere sjabloonversie", §14.19
   "Kopiëren, overnemen en vergelijken", schermkaart nr. 15). Expliciet uitgesteld op 2026-09-23. De
   gegevens om het later te bouwen zijn er wél: `based_on_revision_id` en
   `import_definition_bookmark_value.source_template_revision_id`.
2. **Bulkcreatie** uit een lijst bookmarkwaarden (§14.16). Idem uitgesteld. Het ontwerp verhindert het
   niet: de operatie is per constructie herhaalbaar per rij.
3. **`DELIVERY_FILE_SELECTION` / `BESTANDS_PREFIX`** — niet in `BookmarkUsagePlace` (keuze 3), omdat er
   geen Leveringsconfiguratie-entiteit bestaat. **Gevolg dat zichtbaar moet blijven:** het canonieke
   §14.16-voorbeeld — VROOAM levert `ABP4` en `ABP9` in dezelfde folder en de bookmark onderscheidt ze —
   is vandaag niet te materialiseren. Een sjabloon kan leveranciers nu alleen onderscheiden via
   recordfilter, vaste mappingwaarde of koppelingsvelden, niet via bestandsselectie.
4. **`REVISION_PRICE_POLICY`** wordt in deze bouwstap geweigerd met
   `CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED`. `PRIJSBELEID` is in §14.16 een "geselecteerd beleidsprofiel"
   en er bestaat geen beleidsprofiel-entiteit; de enige realiseerbare invulling zou zijn dat een wizard
   per leverancier de afwijkings- en creatiedrempels zet — precies de bevoegdheid waarvoor
   `CatalogImportSetupController` al waarschuwt dat ze de controle op een catalogus kan uitschakelen.
   Later additief, met een expliciete beslissing.
5. **Bronoverstijgend sjabloon** (keuze 4) — buiten scope.
6. **Autorisatie** — Fase 5/Keycloak. `materialisedBy` is voorlopig een requestveld met dezelfde
   validatie als `acceptedBy`/`decidedBy` (aanname A30 van Fase 4). Zie vraag Q1.
7. **`CatalogImportTask`** wordt niet aangemaakt. §14.18 plaatst planning bij de Leveringsconfiguratie,
   niet bij de wizard, en "maak nooit méér aan dan het document toelaat". Gevolg: een pas
   gematerialiseerde koppeling kan pas een handmatige levering ontvangen nadat er via `POST
   /setup/tasks` een taak bij gemaakt is — wat vandaag alleen met de ontwikkelhulp-API kan (zie Q1).

## 10. Testplan

Alle tests in `Web/src/test`, gericht commando **`mvn -pl Web -am test`**.

| Testklasse | Kernbewijzen |
|---|---|
| `TemplateBookmarkDeclarationTest` | naamformaat-check (beide dialecten), unieke naam/volgorde per revisie, ENUM zonder waardenlijst geweigerd, DEFINITION+`LINK_*` geweigerd, bookmark zonder plaats geweigerd, declareren op een niet-DRAFT revisie geweigerd |
| `TemplateMaterialisationTest` | happy path: definitie+revisie+mappings+filters+koppeling+waardenrijen ontstaan; afgeleide revisie is `DRAFT`; `based_on_*` gevuld; `source_template_revision_id` gevuld; de vier hashes verschillen van die van het sjabloon zodra een waarde een revisieveld raakt |
| `TemplateMaterialisationValidationTest` | per rij uit §4: ontbrekende verplichte bookmark, `""` op een verplichte bookmark, onbekende naam, ENUM buiten de lijst, patroonfout, te lange waarde voor `library_code`, dubbele invoer (bookmark **en** requestveld) |
| `TemplateMaterialisationAtomicityTest` | een fout in fase F laat **geen enkele** rij achter (definitie, revisie, mappings, koppeling, waarden allemaal weg); tweede materialisatie met dezelfde code → 409, nooit een duplicaat |
| `TemplateReuseTest` | hergebruik maakt geen tweede revisie; DEFINITION-waarde bij hergebruik geweigerd; vreemde definitie geweigerd; sjabloonversieverschil geweigerd |
| `TemplateGuardTest` | een `ImportLink` naar een `REUSABLE_TEMPLATE` faalt op de databaseguard `006-5`, ook wanneer de servicecontrole omzeild wordt |
| `LinkBookmarkValueTest` | slot bij een open batch; `recordChange` vult de drie auditvelden samen; wees-rij (`declared=false`) wordt getoond en niet toegepast |
| `TemplateBlockingPointTest` | activeren zonder verplichte DEFINITION-waarde → 409; levering starten zonder verplichte LINK-waarde → 409, en er is niets gearchiveerd |
| `TemplateMaterialisationHttpTest` | statuscodes en stabiele `code`-velden van §5 |

**Regressie die ongewijzigd groen moet blijven:** `SetupApiFlowTest`, `DeliveryUploadTest`,
`DeliveryScreeningFlowTest`, `ScreeningSchemaTest`, `ValidationResultTest`, `BatchBaselineHttpTest`,
`BundleHttpTest`. Bijzonder aandachtspunt: de verhuizing van de hashberekening naar
`RevisionConfigHashes` mag geen enkele bestaande hash veranderen — dat wordt met een expliciete
byte-vergelijking bewezen.

**Buiten scope (de mens draait dit zelf):** PostgreSQL-variant van de naam-check en van `${hash.type}`;
prestatie bij een sjabloon met honderden mappingrijen.

## 11. Bouwstappen — strikt sequentieel, één commit per stap

Geen parallellisatie: 5a-5f raken telkens dezelfde bestanden (`SetupService`, `DeliveryIntakeService`,
de nieuwe controller).

- **5a — bouwer-gemiddeld.** Vier Spring Data-repo's; `BookmarkDeclarations`; `BookmarkValueRules`;
  `RevisionConfigHashes` (verhuizing uit `SetupService`, byte-identiek). Geen endpoint, geen
  gedragswijziging. DoD: compileert, alle bestaande tests groen, hashes bewijsbaar ongewijzigd.
- **5b — bouwer-gemiddeld.** `TemplateBookmarkService` + de declaratie-/leesendpoints; fase C-checks;
  `TemplateBookmarkDeclarationTest`. **Rapport aan de mens na deze stap.**
- **5c — bouwer-zwaar (kleinste verticale slice).** `TemplateMaterialisationService` en `POST
  /templates/{id}/materialisations`, **alleen** `mode = NEW_DEFINITION`, **alleen** de plaatsen
  `FIELD_MAPPING_FIXED_VALUE`, `RECORD_FILTER_COMPARE_VALUE`, `LINK_LIBRARY_CODE` en
  `LINK_SUPPLIER_ORGANISATION`. Eén sjabloon met één verplichte `CULTUUR` (recordfilter) en één
  `DOELBIBLIOTHEEK` (koppeling) levert aantoonbaar een werkende definitie + koppeling op.
  `TemplateMaterialisationTest`, `TemplateMaterialisationValidationTest`,
  `TemplateMaterialisationAtomicityTest`, `TemplateGuardTest`.
- **5d — bouwer-zwaar.** `REUSE_DEFINITION` + `GET /templates/{id}/materialisations` + de
  deelbaarheidsregel **zoals de mens vraag Q2 beantwoordt**; `TemplateReuseTest`.
- **5e — bouwer-gemiddeld.** `REVISION_IDENTITY_FIELD` en `LINK_SEARCH_SUPPLIER`; de `problems`- en
  `warnings`-uitvoer; `TemplateMaterialisationHttpTest`.
- **5f — bouwer-zwaar (meerdere lagen).** Keuzes 5 en 6: `LinkBookmarkValueService` + de twee
  link-endpoints met het slot; `bookmark_values_hash` bij `register`; de blokkeerpunten in
  `activateRevision` en `resolve`; `LinkBookmarkValueTest`, `TemplateBlockingPointTest`.
  **Rapport aan de mens na deze stap.**

## 12. Vragen aan de mens — beantwoord op 2026-09-23

**Q1 (autorisatie/architectuur) — beslist: achter `catalogimport.setup-api.enabled`.** De
materialisatie-API (en alle endpoints uit §5) volgt dezelfde vlag als `CatalogImportSetupController`:
standaard uit, geen authenticatie. Gevolg: de gematerialiseerde koppeling is pas bruikbaar nadat ook
`POST /setup/tasks` gedraaid is, dus de hele keten blijft ontwikkelhulp tot Fase 5/Keycloak — geen
wijziging nodig t.o.v. het ontwerp in §5.

**Q2 (datamodel/identiteit) — beslist: toegestaan, dan niet deelbaar.** Een LINK-scope bookmark op een
revisieniveau-plaats (het `DETAILLEVERANCIER`-geval) mag. De resulterende definitie wordt daarna
geweigerd bij `REUSE_DEFINITION` (409 `DEFINITION_NOT_SHAREABLE`) — zoals fase C5/§6 punt 5 al
ontworpen waren. Geen wijziging nodig; het ontwerp in §4 (C5) en §6 (punt 5) is hiermee definitief.

**Q3 (statusflow) — beslist: ook `SUPERSEDED` toegestaan.** Wijkt af van de aanvankelijk strengere
`ACTIVE`-only-controle; zie het aangepaste fase A4 in §4. Enkel `DRAFT` sjabloonrevisies blijven
geweigerd.

**Q4 (statusflow) — beslist: upload weigeren (409) vóór archivering.** Zoals ontworpen in §7
("Bij de start van een levering"), identiek aan de bestaande `CONFIG_PRICE_FIELD_MISSING`-controle.
Geen wijziging nodig.

**Q5 (contract) — bevestigd.** LINK-scope bookmarkdeclaraties worden meegekopieerd naar elke afgeleide
revisie (R-MAT-03, §1 en §6 punt 4). Dit breidt de betekenis van `import_definition_bookmark` uit van
"declaratie op een sjabloonrevisie" naar "declaratie op elke revisie" — vastgelegd in
`docs/decisions.md` 2026-09-23.

Alle vijf vragen zijn hiermee gesloten. Bouwstappen 5a t/m 5f (§11) kunnen zonder verdere
architectuurvragen sequentieel starten.

## 13. Ontdekkingen

> **Important technical constraint discovered**
>
> `docs/decisions.md` 2026-09-23 keuze 2 stelt dat de regel "een DEFINITION-scope bookmark mag nooit een
> per-leverancier verschillende waarde dragen" *bij het declareren* afgedwongen moet worden, "niet pas
> bij materialisatie ontdekt". Feitelijk kan materialisatie daar vandaag niet op steunen: (1) er bestaat
> geen declaratiepad — geen repository, geen service, geen endpoint, dus bookmarks komen alleen via SQL
> of testcode in de database; (2) de database kan de regel niet uitdrukken, zoals de javadoc van
> `ImportDefinitionBookmark` zelf vaststelt ("de database kan de scope niet tegen de toegelaten
> `BookmarkUsagePlace`-rijen afwegen binnen één check"); (3) `006-5` guardt alleen dat een sjabloon geen
> `ImportLink` krijgt, niets over scope. Maatregel in dit ontwerp: de scope-/plaatsregel wordt één keer
> geïmplementeerd (`BookmarkDeclarations`) en **twee keer aangeroepen** — bij declareren én defensief bij
> materialiseren (fase C). Voorstel om dit terug te schrijven naar
> `docs/decisions.md` bij keuze 2 en naar `business-analyse-leveranciersbibliotheken.md` §14.20
> ("Bookmarks"-testgroep).

> **Important business rule discovered**
>
> De canonieke §14.16-bookmark `BESTANDS_PREFIX` — het hele bestaansmotief van §14.15 (VROOAM levert
> `ABP4` en `ABP9` voor verschillende leveranciers in dezelfde folder) — is met het huidige model **niet
> te materialiseren**, omdat `DELIVERY_FILE_SELECTION` bewust uit `BookmarkUsagePlace` gelaten is tot er
> een Leveringsconfiguratie-entiteit bestaat (keuze 3). Gevolg voor de business: een sjabloon kan
> vandaag twee leveranciers alleen onderscheiden via recordfilter, vaste mappingwaarde of
> koppelingsvelden — niet via welk bestand opgehaald wordt. Zolang de selectie handmatig gebeurt
> (manuele upload) is dat geen blokkade; zodra er server-side ophaling komt, wordt het er wél één.
> Voorstel om dit expliciet in `business-analyse-leveranciersbibliotheken.md` §14.16 te noteren als
> volgordeafhankelijkheid: Leveringsconfiguratie vóór volautomatische VROOAM-multileverancierimport.

> **Important business rule discovered**
>
> §14.16 geeft `DETAILLEVERANCIER` als bookmark met als gebruik "vaste mapping of controle". In het
> huidige model is de leverancier deel van de **aanbiedingsidentiteit**
> (`leverancier + leveranciersgroep + leveranciersreferentie`, beslissingslog 2026-09-18). Een
> bookmarkwaarde die via `FIELD_MAPPING_FIXED_VALUE` op het leveranciersveld landt, bepaalt dus mee de
> identiteit van elke aanbieding in die levering. Dat maakt de scopekeuze voor die ene bookmark geen
> configuratiedetail maar een identiteitsbeslissing — de aanleiding voor vraag Q2 in §12.

## 14. Aannames (A31-A38)

- **A31** De afgeleide definitie erft de bronorganisatie van het sjabloon; het verzoek draagt er geen
  (keuze 4).
- **A32** LINK-scope declaraties worden meegekopieerd naar de afgeleide revisie, DEFINITION-scope niet
  (R-MAT-03; ter bevestiging, zie Q5).
- **A33** Materialisatie levert altijd een `DRAFT`-revisie op en activeert nooit (§14.16 stap 4/6).
- **A34** Voor een `LINK_*`-plaats is de kolom op `ImportLink` de waarheid voor de runtime; de
  bookmarkwaarderij is auditspoor. Ze worden in één transactie geschreven.
- **A35** Materialisatie is bewust niet idempotent; duplicaatpreventie komt van de bestaande unieke
  constraints, er komt geen nieuwe idempotentiesleutel.
- **A36** `bookmark_values_hash` dekt alleen de LINK-scope waarden; DEFINITION-waarden liggen vast via
  het revisie-id op de batch.
- **A37** `REVISION_PRICE_POLICY` wordt in deze bouwstap geweigerd (`CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED`),
  niet stil genegeerd.
- **A38** Geen autorisatie; `materialisedBy` is een requestveld met dezelfde validatie als `decidedBy`
  (voortzetting van Fase 4 A30), tot Keycloak in Fase 5.
