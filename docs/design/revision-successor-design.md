# Ontwerp S1-X — Opvolgrevisie aanmaken / wijzigen / activeren

Bron: denker-zwaar-ontwerp van 2026-09-28 (spoor S1-X uit `docs/decisions.md`, entry 2026-09-27
"Heropening scherm 1a/1b", deelvraag A2). Open vragen O1/O2/O3 door de mens beantwoord (zie onderaan).
Bindend naast `docs/decisions.md` (2026-09-27, A1-A3), `docs/design/sjabloon-materialisatie-design.md`,
`docs/design/valuta-standaard-design.md`, `docs/design/issue-case-design.md` (R-CASE-03).

## 0. Uitgangspunt: hergebruik, geen nieuwe clone-logica

Er bestaat al een volledige clone-implementatie
(`TemplateMaterialisationService.copyRevision/copyMappings/copyFilters/copyFieldCriticalities/
copyLinkScopeDeclarations`) en een activatiestap die al doet wat A2 vraagt (`SetupService.activateRevision`:
DRAFT-validatie → vorige ACTIVE naar `SUPERSEDED` → nieuwe op `ACTIVE`, één transactie).
`RevisionStatus.SUPERSEDED` bestaat al. Het echte gat is niet "clonen" maar "wijzigen": de setup-API kan
vandaag alleen toevoegen (`addMapping`/`addFilter`/`addFieldCriticality`), niet wijzigen/verwijderen, en er
is geen leesendpoint voor de inhoud van één revisie.

**S1-X-1 is daarom een refactor, geen nieuwbouw**: een gedeelde `RevisionCopier` (`service.support`) die
zowel `TemplateMaterialisationService` als de nieuwe opvolgstap gebruiken — patroon van waarom
`RevisionConfigHashes` uit `SetupService` verhuisde ("twee implementaties zouden op termijn uit elkaar
lopen"). Geen gedragswijziging aan het bestaande materialisatiepad toegestaan.

## 1. Wat wordt gekopieerd bij het aanmaken van een opvolgrevisie

Alle configuratievelden letterlijk; geen enkel audit- of levenscyclusveld:

- **Letterlijk**: alle identiteits-, structuur-, prijs- en drempelvelden, `recordCanonicalisationVersion`
  (nooit automatisch verhoogd), `maxCriticalRecords`/`maxRejectedRecords` (deprecated, wél meekopiëren —
  patroon `copyDeprecatedThresholds`).
- **Herberekend, niet gekopieerd**: `accessConfigHash`/`structureConfigHash`/`recordRulesConfigHash`/
  `compositeConfigHash` via `RevisionConfigHashes.applyAll(copy)` ná het zetten van alle velden.
- **Nieuw/afgeleid**: `revisionNumber` = max + 1 voor de definitie; `status` = `DRAFT`; `basedOnRevision`
  = de bronrevisie; `createdBy`/`createdBySubject` = de klonende gebruiker; `createdAt`/`updatedAt` = nu.
- **Verplicht uit het verzoek, nooit gekopieerd**: `changeReason` (400 `CHANGE_REASON_REQUIRED` zonder) —
  zie ontdekking §9.
- **Nooit gekopieerd, altijd `null`**: `approvedAt`/`approvedBy`/`approvedBySubject` (een DRAFT is niet
  goedgekeurd; `ck_import_definition_revision_approved_subject` verbiedt bovendien een subject zonder naam).

De sjabloonherkomst blijft intact: `TemplateMaterialisationService.resolveReuse`/`listMaterialisations`
filteren op `origin.getImportDefinition().getId()`, wat bij een opvolger de eigen definitie blijft. Dit
wordt in S1-X-2 met een test vastgepind.

## 2. Kindrijen en bookmarkwaarden

**Alle vijf configuratie-kindtabellen letterlijk meekopiëren** (`created_by`/`_subject` = de klonende
gebruiker): `import_field_mapping`, `import_record_filter`, `import_revision_field_criticality`,
`import_definition_bookmark` (+ `_usage`, **alle scopes**, niet alleen LINK — anders verliest een
sjabloon-opvolger al zijn bookmarkdeclaraties), `import_definition_bookmark_value` (DEFINITION-scope,
inclusief `source_template_revision_id`, `filled_by`/`_subject`/`_at` = de klonende gebruiker/nu). Vereist
één nieuwe DAO-methode: `ImportDefinitionBookmarkValueRepository.findByDefinitionRevisionId(Long)`.

**`import_link_bookmark_value` (LINK-scope) wordt niet aangeraakt** — hangt aan de `ImportLink`, niet aan
een revisienummer. Dit geldt alléén zolang bookmarkdeclaraties verbatim meegaan (anders wordt een bestaande
koppelingswaarde stilzwijgend een wees). Daarom:

> **Harde ontwerpgrens S1-X:** S1-F4 krijgt geen endpoint om bookmarkdeclaraties toe te voegen, te wijzigen
> of te verwijderen (consistent met S1-F3). Zo is per constructie bewijsbaar dat een opvolgrevisie nooit een
> bestaande LINK-bookmarkwaarde tot wees maakt.

## 3. Activatiestap

Hergebruik `SetupService.activateRevision` ongewijzigd in opzet. Twee aanvullingen:

- **3a — gelijktijdige activatie geeft vandaag een 500.** Twee transacties die elk een DRAFT van dezelfde
  definitie activeren, botsen op `uk_import_definition_revision_active`. Vang die
  `DataIntegrityViolationException` op en vertaal naar **409 `REVISION_ACTIVATION_CONFLICT`**.
- **3b — laagversienummers krijgen voor het eerst betekenis.** `access_version`/`structure_version`/
  `record_rules_version` staan vandaag altijd op 1 en worden nergens gelezen. Bij activatie, enkel wanneer
  `basedOnRevision` van dezelfde definitie is: per laag `versie = bron.versie + 1` als de laaghash
  verschilt, anders ongewijzigd. Additief, risicoloos (niets leest deze kolommen vandaag).

## 4. Vergrendeling

**Geen slot bij activeren.** `import_batch.definition_revision_id` pint de revisie waaronder gescreend is;
die blijft na activatie leesbaar als `SUPERSEDED`. Alle screeningcode leest de gepinde revisie, nooit de
ACTIVE-revisie opnieuw. De reproduceerbaarheid die een slot zou moeten garanderen, is dus al gegarandeerd
door de datamodelkeuze zelf (in tegenstelling tot LINK-bookmarkwaarden, die geen versiebegrip hebben — vandaar
wél een slot bij S1-F3).

**Onvermijdelijk gevolg, moet zichtbaar zijn:** activeren van een opvolgrevisie heropent (R-CASE-03,
`docs/design/issue-case-design.md` §2) bij de eerstvolgende waarneming alle afgewezen behandelgevallen van
alle koppelingen van die definitie — al aanvaard als bewust gevolg in het issue-case-ontwerp. Scherm 1a moet
dit bij de activatieknop melden.

## 5. Identiteit en compatibiliteit

Het aanmaken/activeren van een opvolgrevisie verandert op zichzelf niets (de kloon is byte-identiek). De
betekenis verandert alléén door een latere wijziging in de DRAFT:

| Wijziging | Gevolg |
|---|---|
| `record_canonicalisation_version` verhoogd | Zwaarste geval: het versienummer zit vooraan in `identity_hash` → elke bestaande aanbieding komt als `NEW` (massa-CREATE), niet migreerbaar |
| `identity_profile_kind` of een `identity_*_field` gewijzigd | Zelfde gevolg: andere canonieke identiteitstekst → massa-`NEW` |
| `record_base_price_field`/`record_currency_field`/prijscomponent | `price_fingerprint` verandert → rijen komen als `CHANGED`, zichtbaar en beoordeelbaar maar kan drempels doen aanslaan |
| alleen drempels/prijsbeleid | geen vingerafdruk verandert; enkel het blokkeergedrag |

**Regels (bindend, O3 = beide invoeren):**
- **R-REV-X1** De kloonstap zelf raakt nooit een identiteits- of prijsbepalend veld aan (verbatim-kopie is
  de garantie).
- **R-REV-X2** De wijzigstap weigert een verhoging van `record_canonicalisation_version` zodra er voor enige
  koppeling van deze definitie al aanvaarde bronstaat bestaat (`catalog_source_state`): 409
  `REVISION_CANONICALISATION_CHANGE_BLOCKED`.
- **R-REV-X3** Een wijziging aan `identity_profile_kind` of een `identity_*_field` vereist een expliciete
  bevestiging in het verzoek (`acknowledgeIdentityChange: true`), anders 409
  `IDENTITY_CHANGE_NOT_ACKNOWLEDGED` (patroon `MATERIALISATION_MODE_REQUIRED`).

## 6. Endpoints en contract

Schrijfpaden op de bestaande `CatalogImportSetupController`, recht `MANAGE`, achter
`catalogimport.setup-api.enabled` (A1: schrijfpaden blijven achter de vlag). Padvorm volgt de bestaande
conventie `/setup/revisions/{revisionId}/…`.

| # | Endpoint | Recht/vlag | Doel |
|---|---|---|---|
| E1 | `GET /api/catalog-import/definitions/{definitionId}/revisions/{revisionId}` | `READ`, buiten de vlag | Revisiedetail (scalairen + mappings + filters + kritiek-overrules + bookmarkdeclaraties), alleen-lezen |
| E2 | `POST /setup/revisions/{revisionId}/successor` | `MANAGE`, achter de vlag | De kloon: nieuwe DRAFT-opvolger. Body: `changeReason` (verplicht), `createdBy`. 201 + revisieweergave |
| E3 | `PATCH /setup/revisions/{revisionId}` | `MANAGE`, achter de vlag | Scalaire velden van een DRAFT wijzigen, `null` = ongewijzigd, plus `acknowledgeIdentityChange`. Herberekent hashes, valideert daarna |
| E4 | `DELETE /setup/revisions/{revisionId}/mappings/{mappingId}` + `.../filters/{filterId}` | `MANAGE`, achter de vlag | Verwijderen van een geërfde kindrij; ná verwijderen `BookmarkDeclarations.findProblems(...)` hercontrole |
| E5 | *(bestaand, ongewijzigd behalve 3a/3b)* `POST /setup/revisions/{revisionId}/activate` | `MANAGE`, achter de vlag | Activeren |

**O1 (bindend: hoogstens één open DRAFT-opvolger per definitie, serviceniveau):** E2 geeft 409
`REVISION_DRAFT_ALREADY_EXISTS` (met het bestaande revisienummer) bij een tweede poging. Geen
databaseconstraint — zou `SetupService.createRevision` breken, die vandaag legitiem meerdere DRAFTs kan
maken.

**O2 (bindend: ACTIVE én SUPERSEDED klonen toegestaan):** E2 aanvaardt een bronrevisie met status `ACTIVE`
of `SUPERSEDED` (geeft "terugdraaien naar een eerdere configuratie" gratis); `DRAFT` en overige statussen
→ 409 `REVISION_NOT_CLONEABLE`.

**Nieuwe foutcodes:** `REVISION_NOT_CLONEABLE`, `CHANGE_REASON_REQUIRED`, `REVISION_DRAFT_ALREADY_EXISTS`,
`REVISION_ACTIVATION_CONFLICT`, `REVISION_CANONICALISATION_CHANGE_BLOCKED`,
`IDENTITY_CHANGE_NOT_ACKNOWLEDGED`, `MAPPING_NOT_FOUND`/`FILTER_NOT_FOUND`. Bestaand hergebruikt:
`REVISION_NOT_FOUND`, `DEFINITION_NOT_FOUND`, `REVISION_NOT_EDITABLE`, `REVISION_NOT_ACTIVATABLE`,
`CONFIG_REQUIRED_BOOKMARK_MISSING`, de `CONFIG_*`/`CONFIG_BOOKMARK_*`-familie. Alle nieuwe codes ook in
`Frontend/src/errors/codes.ts`.

Rechtenregressie verplicht (patroon S2-B2/S2-B3): `PermissionCoverageTest` + `PermissionWriteEndpointsHttpTest`
(E2/E3/E4), `PermissionReadEndpointsHttpTest` (E1).

## 7. Liquibase

**Geen migratie nodig voor S1-X-1 t/m S1-X-4.** `status` is `varchar(40) not null` zonder check-constraint,
`SUPERSEDED` bestaat al, `based_on_revision_id`/`change_reason`/de drie laagversiekolommen/alle
audit-subjectkolommen bestaan al. (O1 koos serviceniveau, dus ook geen `draft_marker`-changeset nodig.)

> **Important technical constraint discovered (S1-X-4)**
>
> `import_definition_revision` heeft geen `updated_by`/`updated_by_subject`-kolom. Sinds E3/E4 bestaat er
> een schrijfactie op financieel/identiteitsbepalende velden (drempels, prijsbeleid, identiteitsvelden) van
> een DRAFT-opvolger, en op het verwijderen van een mapping/filter, zonder dat vastgelegd wordt wíe die
> wijziging deed — enkel `createdBy` (wie de DRAFT aanmaakte) en `approvedBy` (wie activeert) zijn geaudit.
> **Voorstel (na akkoord van de mens, nog niet gebouwd):** een latere, additieve changeset met
> `updated_by varchar(100)` + `updated_by_subject varchar(255)` (patroon changeset 007), gevuld door
> `SetupService.updateRevision`/`deleteMapping`/`deleteFilter`. Geen backfill; bestaande rijen blijven
> `null`. Dit is bewust geen blokkerend punt voor S1-F4 — de bevoegdheid (recht `MANAGE`) is al afgedwongen,
> enkel de audit-trail van "wie precies" ontbreekt nog.

> **Important technical constraint discovered (S1-F4)**
>
> Een tab of spatie als `structureDelimiter`/`quoteChar` kan principieel niet via de setup-API ingesteld
> worden: `SetupService.requireText` weigert blanco en trimt, `optionalText` leest blanco als `null`. Dit is
> bestaand backendgedrag (niet door S1-X geintroduceerd), maar wordt hier voor het eerst zichtbaar via E3
> (`PATCH .../revisions/{id}`) en de bestaande `createRevision`. Gevolg: een tab-gescheiden (TSV-)bron is
> vandaag niet configureerbaar via de setup-API. De Frontend spiegelt dit gedrag (trimt clientzijdig, geen
> workaround). Geen fix in deze ronde, enkel vastgelegd als bekende beperking.

## 8. Bouwvolgorde met complexiteitsinschatting

Strikt sequentieel.

| # | Stap | Trap | Gerichte tests |
|---|---|---|---|
| **S1-X-1** | `RevisionCopier` (service.support), `TemplateMaterialisationService` laat haar eigen copy-methoden erop uitkomen — geen gedragswijziging aan een volledig getest productiepad | `bouwer-zwaar` | `TemplateMaterialisationHttpTest`, `TemplateMaterialisationValidationTest`, `TemplateGuardTest`, `TemplateBlockingPointTest`, `RevisionConfigHashesTest`, `ImportTemplateBookmarkSchemaTest` — alle onveranderd groen |
| **S1-X-2** | `RevisionSuccessorService` (kloon §1+§2, één transactie), nieuwe DAO-methode, endpoint E2, plus 3a/3b in `activateRevision`, O1/O2-afdwinging | `bouwer-zwaar` | nieuwe `RevisionSuccessorTest`+`RevisionSuccessorHttpTest`: kloon byte-identiek, `approved_*` null, `based_on` correct, alle vijf kindtabellen gekopieerd, sjabloonherkomst intact ná twee opvolgrevisies, activeren zet vorige op SUPERSEDED, tweemaal activeren → 409, tweede DRAFT → 409, SUPERSEDED-bron klonen werkt, DRAFT-bron klonen → 409. Regressie: `SetupApiFlowTest`, `LinkBookmarkValueTest`, `PermissionCoverageTest`, `PermissionWriteEndpointsHttpTest` |
| **S1-X-3** | Leesendpoint E1 | `bouwer-gemiddeld` | `CatalogImportSetupQueryHttpTest` uitbreiden, `PermissionCoverageTest`, `PermissionReadEndpointsHttpTest` |
| **S1-X-4** | Wijzigpaden E3+E4, R-REV-X2/R-REV-X3, `BookmarkDeclarations`-hercontrole na delete | `bouwer-zwaar` (financieel/identiteitsbepalend) | drempel wijzigen → hashes ongewijzigd; identiteitsveld zonder bevestiging → 409; canonicalisatieversie met bestaande bronstaat → 409; mapping verwijderen met bookmarkdeclaratie erop → 409; PATCH op ACTIVE → 409 |
| **S1-F4** | Scherm 1a schrijfdeel (opvolger maken → DRAFT bewerken → activeren), R-CASE-03-waarschuwing bij de activatieknop, nieuwe `codes.ts`-entries | `bouwer-zwaar` | Frontend-testsuite + `tsc -p tsconfig.app.json` |

Testcommando: `mvn -pl Web -am test -Dtest=RevisionSuccessorTest,RevisionSuccessorHttpTest,
TemplateMaterialisationHttpTest,TemplateMaterialisationValidationTest,SetupApiFlowTest,
PermissionCoverageTest,PermissionWriteEndpointsHttpTest,PermissionReadEndpointsHttpTest,LinkBookmarkValueTest`
— nooit de volledige reactor.

## 9. Ontdekkingen (teruggeschreven na akkoord van de mens)

> **Important technical constraint discovered**
>
> `RevisionConfigHashes.applyAll` berekent de laaghashes en de samengestelde hash uitsluitend uit de
> scalaire velden van de revisie (identiteitsprofiel, identiteitsvelden, prijs-/omschrijvings-/muntveld,
> canonicalisatieversie, structuurvelden, `access_delivery_set_kind`). Ze dekken NIET de mappings, de
> recordfilters, de kritiek-overrules, de drempels en het prijsbeleid. Twee revisies die uitsluitend in
> drempels, prijsbeleid of mappings verschillen, dragen dus een identieke `composite_config_hash`.
> `composite_config_hash` wordt vandaag nergens in productiecode gelezen (enkel geschreven), dus dit
> veroorzaakt nu geen fout gedrag — maar wie de hash later als "zijn deze configuraties gelijk?" gebruikt,
> krijgt een verkeerd antwoord.

> **Important business rule discovered**
>
> `ImportDefinitionRevision.changeReason` is in de javadoc "Verplicht bij een opvolgrevisie" (§14.20), maar
> de kolom is nullable en geen enkele service dwingt dit vandaag af — `SetupService.createRevision`
> aanvaardt `null`. De opvolgrevisiestap (E2, `CHANGE_REASON_REQUIRED`) is de eerste plaats waar deze regel
> echt afgedwongen wordt. Bestaande rijen blijven `null`; geen backfill, geen `not null`-constraint.

## 10. Open vragen — antwoorden van de mens

- **O1:** hoogstens één open DRAFT-opvolger per definitie, afgedwongen op serviceniveau (409
  `REVISION_DRAFT_ALREADY_EXISTS`), geen databaseconstraint.
- **O2:** klonen mag vanaf `ACTIVE` én `SUPERSEDED` (niet enkel `ACTIVE`); `DRAFT` en overige statussen
  geweigerd.
- **O3:** beide identiteitsbeschermingen (R-REV-X2 en R-REV-X3) worden ingevoerd.

## Relevante bestanden

- `Domain/src/main/java/be/dda/catalogimport/domain/ImportDefinitionRevision.java`
- `Domain/src/main/java/be/dda/catalogimport/domain/RevisionStatus.java`
- `Domain/src/main/java/be/dda/catalogimport/domain/ImportDefinitionBookmarkValue.java`
- `Service/src/main/java/be/dda/catalogimport/service/SetupService.java` (r.467-503 activatie, r.798-820 bookmarkblokkeerpunt)
- `Service/src/main/java/be/dda/catalogimport/service/TemplateMaterialisationService.java` (r.1107-1270 bestaande clone)
- `Service/src/main/java/be/dda/catalogimport/service/LinkBookmarkValueService.java` (slot + wezenlogica)
- `Service/src/main/java/be/dda/catalogimport/service/support/RevisionConfigHashes.java`
- `Service/src/main/java/be/dda/catalogimport/service/support/CandidateNormaliser.java` (r.296-322 identity/price fingerprint)
- `Web/src/main/java/be/dda/catalogimport/web/CatalogImportSetupController.java`
- `Web/src/main/java/be/dda/catalogimport/web/CatalogImportSetupQueryController.java`
- `Web/src/main/resources/db/changelog/001-import-control-core.sql` (r.39-73)
