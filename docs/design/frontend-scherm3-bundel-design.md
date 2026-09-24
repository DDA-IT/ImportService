# Ontwerp Frontend-slice 1 — Scherm (3) Publicatiebundel + het frontendfundament

Bron: denker-zwaar-ontwerp van 2026-09-23. Bindend naast `docs/decisions.md` (vooral de zes
frontend-blokken van 2026-09-22), `docs/design/fase4-publication-bundle-design.md` en de feitelijke
REST-contracten in `Web/src/main/java/be/dda/catalogimport/web/`. Dit is de eerste echte
frontend-slice; de patronen uit §2 t/m §8 erft elk volgend scherm.

## 0. Vooraf — wat al vastligt, wat dit ontwerp toevoegt

Vastgelegd en niet ter discussie in dit ontwerp:

- React + Vite + TypeScript, losstaand SPA in `Frontend/`, buiten de Maven-reactor, praat uitsluitend
  via REST/JSON met de `Web`-module (`docs/decisions.md` 22/09 "Frontend: stack en locatie").
- Vijf schermen; alleen (2) en (3) zijn in beeld, scherm (0)/(1a)/(1b) zijn uitgesteld
  (22/09 "correctie schermenindeling", "D14", "setup-API productiewaardig maken").
- De koppelingoverstijgende mutatielijst is een **herbruikbaar component**, geen eigen scherm
  (22/09 "koppelingoverstijgende mutatielijst").
- `accept-baseline` hoort op scherm (2), niet op scherm (3) (22/09 "plaatsing accept-baseline vs.
  bundel-opname"). Scherm (3) toont de uitsluiting alleen als uitleg bij een 409.
- Geen authenticatie tot Fase 5; actorvelden zijn requestvelden (fase 4-ontwerp §9, A30).

Wat dit ontwerp toevoegt: de mapstructuur, de API-client, de foutcodevertaling, het server-state-model,
de stijlaanpak, het actor-model, scherm (3) zelf, het herbruikbare mutatielijst-component, en de
bouwvolgorde. Eén tegenstrijdigheid tussen documenten is **niet** gevonden; wél vier plekken waar de
backend iets niet levert dat dit scherm nodig heeft (§16) en vier vragen die bij de mens horen (§17).

## 1. Feitelijke inventaris (gelezen, niet aangenomen)

### 1.1 Wat er vandaag in `Frontend/` staat

Een kale Vite-template, verder niets:

- `package.json`: dependencies alleen `react` + `react-dom`; dev `@vitejs/plugin-react`, `oxlint`,
  `typescript`, `vite`, `@types/*`. Scripts: `dev`, `build` (`tsc -b && vite build`), `lint`, `preview`.
  **Geen router, geen HTTP-bibliotheek, geen state-bibliotheek, geen testrunner, geen UI-/stijlpakket.**
- `vite.config.ts`: een devproxy `/api → http://localhost:8081` staat er al. Dat is bepalend voor §3:
  in ontwikkeling volstaat een **relatieve** basis-URL; er is geen CORS nodig en ook geen CORS aanwezig.
- `src/`: `main.tsx` (StrictMode + createRoot), `App.tsx` (placeholder "No screens implemented yet"),
  `index.css` + `App.css` uit de template. `index.css` is een **landingspagina**-stylesheet
  (`#root { width: 1126px; text-align: center }`, `h1 { font-size: 56px }`) — onbruikbaar voor een
  toepassing met tabellen en formulieren, en dus te vervangen, niet uit te breiden.
- `src/assets/` bevat `hero.png`, `react.svg`, `vite.svg`; `public/` bevat `favicon.svg`, `icons.svg`.
  Template-rommel, mag weg.
- `tsconfig.app.json` staat strak (`noUnusedLocals`, `noUnusedParameters`, `verbatimModuleSyntax`,
  `erasableSyntaxOnly`) maar bevat **geen** `"strict": true`. Dat zetten we aan (§2.4).
- `.oxlintrc.json` met twee react-regels; type-aware linting staat uit.

### 1.2 Wat de backend werkelijk levert voor dit scherm

Gelezen uit `CatalogImportBundleController`, `CatalogImportBatchController`, `BundleQueryService`,
`PublicationBundleService`, `BundleDecisionService`, `BundleFreezeService`, `BundleCancellationService`,
`BatchQueryService`, `PageResult`, `ApiExceptionHandler`.

Basispad `/api/catalog-import`. Paginering overal `page` (0-gebaseerd), `size` (default 50, max 200);
antwoord `PageResult<T> { content, page, size, totalElements, totalPages }`.

| Methode | Pad | Antwoord |
|---|---|---|
| GET | `/bundles?status&page&size` | `PageResult<BundleSummary>` |
| POST | `/bundles` | `BundleReference` (idempotent op `bundleReference`) |
| GET | `/bundles/{id}` | `BundleDetail` (live tellers zolang ASSEMBLING) |
| GET | `/bundles/candidates?importLinkId&page&size` | `PageResult<BundleCandidate>` |
| GET | `/bundles/{id}/batches?page&size` | `PageResult<BundleBatchRow>` |
| POST | `/bundles/{id}/batches` | `List<Membership>` (alles-of-niets) |
| POST | `/bundles/{id}/batches/{batchId}/remove` | `Membership` |
| GET | `/bundles/{id}/mutations?status&batchId&actionType&page&size` | `PageResult<MutationRow>` |
| GET | `/bundles/{id}/decisions?page&size` | `PageResult<DecisionRow>` |
| POST | `/bundles/{id}/mutations/{mutationId}/approve` | `MutationDecisionView` |
| POST | `/bundles/{id}/mutations/{mutationId}/reject` | `MutationDecisionView` |
| POST | `/bundles/{id}/decisions` | `GroupDecisionView` |
| POST | `/bundles/{id}/freeze` | `BundleDetail` |
| POST | `/bundles/{id}/cancel` | `BundleDetail` |
| GET | `/batches/{id}/mutations?actionType&page&size` | `PageResult<MutationRow>` (scherm 2) |
| POST | `/batches/{id}/accept-baseline` | `BaselineAcceptance` (scherm 2) |

Vormen die het scherm draagt (letterlijk uit de records):

- `BundleSummary`: `id, bundleReference, description, status, targetMode, targetMoment,
  publicationPolicy, createdBy, createdAt, frozenBy, frozenAt, frozenReason, cancelledBy, cancelledAt,
  cancelledReason, batchCount, contentMutationCount, readyCount, rejectedCount, blockedCount,
  expiredCount, identityIncidentCount, bulkIncidentCount, criticalIssueCount, warningCount` — alle
  tellers `Long` en dus **nullable**.
- `BundleDetail` = `BundleSummary` + `staleMutationCount` + `contentHash` (hex, alleen bij FROZEN).
- `BundleBatchRow`: `id, bundleId, batchId, importLinkId, addedBy, addedAt, removedBy, removedAt,
  removedReason, active, batchStatus, batchContentMutationCount`.
- `BundleCandidate`: `batchId, importLinkId, status, validationResult, contentMutationCount, finishedAt`.
- `MutationRow` (27 velden, **identiek** voor `/batches/{id}/mutations` en `/bundles/{id}/mutations` —
  dat is precies wat het herbruikbare component mogelijk maakt): `id, batchId, actionType, targetDomain,
  status, statusReason, identitySupplier, identitySupplierGroup, identitySupplierReference,
  identityDiscountCode, identityDiscountState, domainMask, beforeBasePrice, afterBasePrice,
  basePriceCurrency, referenceType, beforeReferenceValue, afterReferenceValue, sourceStateId,
  sourceRowNumber, resultSummary, idempotencyKey, createdAt, decidedBy, decidedAt, decidedFromStatus,
  decisionId`.
- `DecisionRow`: `id, bundleId, mutationId, decisionKind, decisionScope, selectionFilter,
  previousStatus, newStatus, affectedCount, decidedBy, decidedAt, reason`.
- `MutationDecisionView`: `{ mutation: MutationRow, decision: DecisionRow, idempotent: boolean }`.
- `GroupDecisionView`: `{ decisionId: number|null, affectedCount: number, selectionFilter: string }`.
- `DecisionFilter` (request): `{ batchId?, status?, statusReason?, actionType? }`, minstens één veld.

Foutantwoorden (`ApiExceptionHandler`):

- 404/409 → `{ "error": "<Engelse tekst>", "code": "<STABIELE_CODE>" }`
- 400 via `BadRequestException` → mét `code` (vandaag alleen `DECISION_FILTER_REQUIRED`)
- 400 via `IllegalArgumentException`/`IllegalStateException` → **`{ "error": ... }` zonder `code`**
- 500 → standaard Spring-body, **zonder message** (`server.error.include-message: never`)

Relevante codes voor dit scherm: `BUNDLE_NOT_FOUND`, `BATCH_NOT_FOUND`, `BATCH_NOT_IN_BUNDLE`,
`MUTATION_NOT_IN_BUNDLE`, `BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE`, `BUNDLE_NOT_ASSEMBLING`,
`BATCH_ALREADY_IN_BUNDLE`, `BATCH_NOT_BUNDLEABLE`, `BATCH_VALIDATION_NOT_ESTABLISHED`,
`BATCH_VALIDATION_BLOCKING`, `BATCH_HAS_DECIDED_MUTATIONS`, `MUTATION_NOT_DECIDABLE`,
`MUTATION_BLOCKED_BY_IDENTITY_INCIDENT`, `IDENTITY_DECISION_NOT_IN_SCOPE`, `DECISION_FILTER_REQUIRED`,
`BUNDLE_EMPTY`, `BUNDLE_HAS_UNDECIDED_MUTATIONS`, `SOURCE_STATE_CHANGED_SINCE_SCREENING`,
`BUNDLE_OFFER_CONFLICT`, `OFFER_ALREADY_IN_ANOTHER_BUNDLE`, `BUNDLE_CONTENT_CHANGED_DURING_FREEZE`,
`BUNDLE_NOT_CANCELLABLE`, `BUNDLE_CONTENT_CHANGED_DURING_CANCEL`.

## 2. Fundament — mapstructuur en naamconventies

```
Frontend/
  vite.config.ts            bestaat; proxy /api → :8081 blijft ongewijzigd
  vitest.config.ts          nieuw (F3), of een test-blok in vite.config.ts
  src/
    main.tsx                root + router + ActorProvider
    App.tsx                 app shell (kop, navigatie, <Outlet/>, globaal foutpaneel)
    routes.tsx              één plek met alle routes van alle schermen
    styles/
      reset.css             vervangt de template-landingspagina-CSS
      tokens.css            kleuren, spacing, statuskleuren als custom properties
    api/
      http.ts               request(), ApiError, basis-URL — de enige plek met fetch()
      types.ts              1:1 met de Java-records; enums als string-unions
      bundles.ts            alle /bundles-aanroepen
      batches.ts            alle /batches-aanroepen
    errors/
      codes.ts              code → Nederlandse tekst; familie-fallbacks
      ErrorBanner.tsx       toont titel, uitleg, en ALTIJD de technische code
    hooks/
      useQuery.ts           lezen + herladen + afbreken
      useAction.ts          schrijven + bezig/fout/klaar
    actor/
      ActorContext.tsx      wie tekent er (zolang er geen Keycloak is)
      ActorBar.tsx
    components/             schermoverstijgend; weet niets van een scherm
      DataTable.tsx  Pager.tsx  StatusBadge.tsx  ConfirmDialog.tsx  Field.tsx
      MutationList/
        MutationList.tsx  MutationList.module.css  types.ts
    features/
      bundles/              scherm (3)
        BundleListPage.tsx  CreateBundleForm.tsx  BundleDetailPage.tsx
        BundleOverviewTab.tsx  BundleBatchesTab.tsx  BundleMutationsTab.tsx
        BundleDecisionsTab.tsx  FreezeDialog.tsx  CancelDialog.tsx
        GroupDecisionDialog.tsx  bundlePolicy.ts
      deliveries/           scherm (2), later
```

**De drie regels die deze indeling dragen** (dit is de erfenis voor elk volgend scherm):

1. `api/` kent de backend maar geen React (geen hooks, geen JSX). `features/` kent een scherm.
   `components/` kent geen van beide en mag nooit uit `features/` importeren.
2. Een module in `components/` die iets van de backend nodig heeft, krijgt dat **via props**, nooit door
   zelf `api/` te importeren. Dat is de eis die het mutatielijst-component in twee schermen laat passen
   (§11) en is geen stijlvoorkeur maar het contract van dat component.
3. `errors/codes.ts` is de **enige** plek waar een backendcode Nederlandse tekst wordt. Geen enkele
   component schrijft zelf een foutzin.

Naamgeving: PascalCase voor componentbestanden, camelCase voor de rest. **Bezinning in de code blijft
Engels** (`bundleId`, `approve`, `MutationRow`) zodat het woordenboek van de backend niet halverwege
verandert; Nederlands staat uitsluitend in zichtbare labels en in `errors/codes.ts`. Om dezelfde reden
zijn ook de routepaden Engels (`/bundles/:bundleId/mutations`).

### 2.4 Compilerinstellingen

`tsconfig.app.json` krijgt `"strict": true` en `"noUncheckedIndexedAccess": true` erbij. Reden: de
backend levert tientallen nullable `Long`-tellers; zonder `strict` verdwijnt precies het onderscheid
tussen "0" en "niet vastgesteld" dat de backend bewust maakt (fase 4-ontwerp §2: "alle tellers bigint
nullable"). Dat onderscheid moet in de UI zichtbaar blijven (§9.3).

## 3. Fundament — de API-client

### 3.1 Basis-URL

```ts
const BASE = import.meta.env.VITE_API_BASE_URL ?? '/api/catalog-import';
```

Standaard relatief: in ontwikkeling doet de bestaande Vite-devproxy de rest, in een eventuele latere
uitlevering achter dezelfde oorsprong werkt het ongewijzigd. `VITE_API_BASE_URL` bestaat alleen om
tegen een andere poort te kunnen werken; er komt **geen** hardgecodeerde `http://localhost:8081` in de
broncode. Er wordt geen `.env`-bestand gecommit.

### 3.2 `request()` en `ApiError`

```ts
export class ApiError extends Error {
  readonly status: number;                 // 0 = netwerkfout of afgebroken
  readonly code: string | null;            // de stabiele backendcode, null als het antwoord er geen droeg
  readonly backendMessage: string | null;  // het "error"-veld: Engelse ontwikkelaarstekst
  readonly path: string;                   // welk pad geweigerd heeft
}

export function request<T>(path: string, init?: RequestInit & { signal?: AbortSignal }): Promise<T>;
```

Gedrag, vastgelegd:

- Voegt `Accept: application/json` toe, en `Content-Type: application/json` zodra er een body is.
- `204` → `null as T`.
- Niet-ok → body als JSON proberen te lezen → `new ApiError(status, body.code ?? null, body.error ?? null)`.
  Lukt het lezen niet (HTML-foutpagina, lege body), dan `code = null`, `backendMessage = null`.
- Netwerkfout of `AbortError` → `ApiError` met `status = 0`. **Nooit** een kale `Error` of een
  afgevangen `undefined`.
- Geen retry, geen timeout in deze slice. Elke aanroep van scherm (3) is klein; automatisch opnieuw
  proberen op een `POST` is bij beslissingen bovendien ongewenst (elke poging kan een nieuwe
  beslissingsregel schrijven — de backend is idempotent voor dezelfde beslisser+doelstatus, maar dat
  is een eigenschap van de server, geen vrijbrief voor de client).

`api/bundles.ts` en `api/batches.ts` bevatten één functie per endpoint, met exact de types uit §3.3.
**Geen component roept ooit rechtstreeks `fetch` of `request` aan.**

### 3.3 Typering van de contracten

Handgeschreven types in `api/types.ts`, 1:1 met de Java-records, met een commentaarregel per type die
naar de bron verwijst (`// be.dda.catalogimport.service.BundleQueryService.BundleDetail`).

Vertaalregels, altijd dezelfde:

| Java | TypeScript | Waarom |
|---|---|---|
| `long`, `int` | `number` | veilig binnen `Number.MAX_SAFE_INTEGER` |
| `Long`, `Integer` | `number \| null` | "niet vastgesteld" ≠ 0; zie §9.3 |
| `String` (nullable) | `string \| null` | |
| `Instant` | `string` (ISO-8601) | geen Jackson-configuratie aanwezig ⇒ ISO-tekst |
| `BigDecimal` | `number \| null` | zie het constraintblok in §18 |
| enum | string-union + `as const`-array | zie hieronder |
| `PageResult<T>` | `PageResult<T>` | letterlijk overgenomen |

```ts
export const MUTATION_STATUSES = ['PLANNED','BLOCKED','AWAITING_APPROVAL','READY_FOR_PUBLICATION',
  'IN_PROGRESS','PUBLISHED','TECHNICALLY_FAILED','REJECTED','EXPIRED','SKIPPED','RECORDED'] as const;
export type MutationStatus = typeof MUTATION_STATUSES[number];
```

De `as const`-array is er omdat de UI die waarden moet kunnen **opsommen** (filterkeuzelijsten) en niet
alleen controleren. Ontvangt de UI een waarde die er niet in staat (een latere backenduitbreiding), dan
toont ze die letterlijk in plaats van te crashen of stil weg te laten — zelfde principe als de
onbekende foutcode (§4).

**Geen codegeneratie.** Er is geen springdoc/OpenAPI in de `Web`-module (gecontroleerd: geen enkele
`pom.xml` noemt springdoc, openapi of swagger). Springdoc toevoegen + `openapi-typescript` draaien zou
twee nieuwe afhankelijkheden en een generatiestap kosten voor twee controllers die met de hand in ~150
regels te typeren zijn. **Herzieningstrigger:** zodra een derde scherm erbij komt of de types tweemaal
uit de pas lopen met de backend, is generatie goedkoper dan onderhoud — dan opnieuw beoordelen.

## 4. Fundament — foutcodes in de UI

Uitgangspunt uit de opdracht en uit de backend zelf: de stabiele codes zijn bewust gemaakt en mogen
niet verdwijnen achter "er ging iets mis".

`errors/codes.ts`:

```ts
type CodeEntry = { title: string; explanation: string; whatNow?: string };
export const CODE_MESSAGES: Record<string, CodeEntry>;
export function describe(error: ApiError): {
  title: string; explanation: string; whatNow: string | null; technical: string;
};
```

Vijf regels, in deze volgorde:

1. **De code staat altijd in beeld.** `ErrorBanner` toont onderaan een technische regel:
   `<code> · HTTP <status> · <pad>` — ook wanneer de code bekend is en er een mooie Nederlandse zin
   boven staat. Een gebruiker die belt, noemt die code; een ontwikkelaar herkent hem meteen.
2. **Bekende code** → titel + uitleg + "wat nu" uit `CODE_MESSAGES`. Voorbeeld:
   `BUNDLE_HAS_UNDECIDED_MUTATIONS` → *"Er wachten nog mutaties op een beslissing"* / *"Bevriezen mag
   die vraag niet stilzwijgend beantwoorden."* / *"Beoordeel eerst de mutaties met status
   AWAITING_APPROVAL."*
3. **Onbekende code** → familie-fallback op basis van het achtervoegsel/voorvoegsel, zodat een later
   toegevoegde backendcode nog steeds begrijpelijk is: `CONFIG_*` → "de configuratie van de
   importdefinitie is onvolledig of ongeldig"; `*_NOT_FOUND` → "niet gevonden"; `*_IN_USE` → "die code
   of scope is al in gebruik"; `*_CHANGED*` → "de toestand is ondertussen veranderd; lees opnieuw";
   overig 409 → "de bewerking is geweigerd in de huidige toestand". Titel draagt dan altijd de code
   letterlijk: *"Geweigerd (BUNDLE_XYZ)"*.
4. **400 zonder code** → titel "Ongeldige invoer", en de `backendMessage` **letterlijk** getoond
   (Engelse ontwikkelaarstekst, bv. *"Missing reason: rejecting mutations always requires one"*), met
   de vermelding dat de server hier geen stabiele code levert. Liever ruwe Engelse tekst die klopt dan
   een verzonnen Nederlandse zin die de oorzaak raadt. Zie het constraintblok in §18.
5. **500 of `status = 0`** → generieke zin plus de eerlijke toevoeging dat er geen details zijn (de
   server stuurt bewust geen foutdetails), en de suggestie het serverlogboek te raadplegen.

Waar de melding verschijnt: **bij de actie die ze veroorzaakte**, niet in een globale toast. Een 409 op
bevriezen hoort in de bevriesdialoog, niet in een hoekje van het scherm. Alleen een fout bij het laden
van een hele pagina vult het paginavlak.

## 5. Fundament — server-state zonder bibliotheek (met motivering)

**Beslissing: geen TanStack Query, geen Redux, geen SWR in deze slice.** Twee zelfgeschreven hooks van
samen ongeveer honderd regels:

```ts
// lezen
function useQuery<T>(key: string, load: (signal: AbortSignal) => Promise<T>)
  : { data: T | null; error: ApiError | null; loading: boolean; reload: () => void };

// schrijven
function useAction<A extends unknown[], R>(run: (...args: A) => Promise<R>)
  : { execute: (...args: A) => Promise<R | undefined>; pending: boolean; error: ApiError | null;
      reset: () => void };
```

Motivering (AGENT.md §2 principe 4):

- Wat scherm (3) nodig heeft is: laden bij binnenkomst, opnieuw laden na een actie, en een verzoek
  afbreken als de gebruiker wegnavigeert. Dat is het volledige lijstje. Cache-deling tussen
  componenten, achtergrondherhaling, optimistische updates en oneindige lijsten — de functies waar
  TanStack Query voor bestaat — heeft dit scherm niet.
- Erger: **optimistische updates zijn hier ongewenst.** Een goedkeuring is een financiële,
  geauditeerde handeling. De UI mag nooit "goedgekeurd" tonen voordat de server dat bevestigd heeft;
  een teruggerolde optimistische update zou betekenen dat iemand even een onwaarheid zag over iets
  waarvoor hij tekent. Een bibliotheek die dat patroon aanmoedigt is hier een risico, geen gemak.
- Cache-invalidatie wordt **expliciet**, niet magisch: na elke schrijfactie roept de pagina de
  `reload()` aan van precies de queries die kunnen veranderd zijn. Voor scherm (3) is die afhankelijkheid
  klein en exact opschrijfbaar:

| Actie | Herladen |
|---|---|
| batches toevoegen/verwijderen | bundeldetail, ledenlijst, kandidatenlijst, mutatielijst |
| mutatie goedkeuren/afkeuren/herzien | bundeldetail (tellers), mutatielijst (huidige pagina), beslissingsregister |
| groepsactie | bundeldetail, mutatielijst, beslissingsregister |
| bevriezen | bundeldetail (antwoord is al de nieuwe `BundleDetail`), alle tabbladen |
| annuleren | idem |

Het antwoord van `freeze`/`cancel` is zelf de volledige nieuwe `BundleDetail`; dat antwoord wordt
rechtstreeks in de state gezet **en** daarna wordt de detailquery herladen — het antwoord is bewijs,
niet de enige bron.

**Herzieningstrigger (schrijf dit op, zodat niemand later hoeft te gissen):** komt er een derde scherm
dat dezelfde gegevens toont als een bestaand scherm (gedeelde cache), of komt er achtergrondverversing
of offline-gedrag bij, dan is TanStack Query goedkoper dan deze hooks verder uitbouwen. Zolang elke
pagina haar eigen gegevens laadt, niet.

Eén detail dat de hooks wél moeten doen en dat vaak vergeten wordt: `useQuery` breekt het lopende
verzoek af (`AbortController`) bij het wisselen van `key` of bij unmount, en negeert het antwoord van
een verouderd verzoek. Zonder dat toont een snel klikkende gebruiker de pagina van een vorige bundel.

## 6. Fundament — stijl en UI

**Beslissing: gewone CSS met CSS Modules; geen Tailwind, geen componentenbibliotheek.**

- CSS Modules (`*.module.css`) zitten in Vite ingebouwd, kosten geen afhankelijkheid en geven scoping,
  zodat een component nooit de stijl van een ander lekt.
- `styles/tokens.css` bevat de custom properties: kleuren, spacing, en — belangrijk — de **statuskleuren**
  als één woordenboek (`--status-planned`, `--status-awaiting`, `--status-ready`, `--status-rejected`,
  `--status-blocked`, `--status-expired`, `--status-neutral`). Elke status krijgt daarmee overal dezelfde
  kleur; een statuskleur wordt nooit ad hoc in een component gekozen.
- **Kleur is nooit de enige drager van betekenis**: `StatusBadge` toont altijd de statustekst zelf.
  Dit is geen toegankelijkheidsvinkje maar een businessvereiste: "geblokkeerd" en "afgekeurd" mogen niet
  van elkaar te onderscheiden zijn door alleen een tint.
- De template-`index.css` en `App.css` worden **vervangen** door `reset.css` + `tokens.css`. De
  bestaande stylesheet is voor een gecentreerde landingspagina van 1126px breed; een bundelscherm met
  brede tabellen zou daarin onbruikbaar zijn.
- Tailwind of MUI zouden een build-/leerkost toevoegen voor een intern scherm dat uit tabellen,
  statusbadges, formulieren en dialogen bestaat. Herzieningstrigger: zodra er een ontwerper met een
  huisstijl bij komt, of zodra er meer dan ~15 schermen zijn.

Eén gedeelde `DataTable` (kolomdefinities als data, geen generieke tabelmotor) en één `Pager`
(vorige/volgende + "x-y van n" + keuze 25/50/100/200, begrensd door de `MAX_PAGE_SIZE` van 200 die de
backend afdwingt).

## 7. Fundament — de actor zolang er geen authenticatie is

De backend eist bij elke schrijfactie een naam: `createdBy`, `addedBy`, `removedBy`, `decidedBy`,
`frozenBy`, `cancelledBy`, en op scherm (2) `acceptedBy`/`uploadedBy`. Validatie: niet leeg, ≤100 tekens,
nooit letterlijk `system`. Die naam belandt in een append-only register met een bewaartermijn van
zeven jaar.

Ontwerp (met §17 Q1 als voorbehoud):

- `ActorContext` houdt één naam vast voor de hele applicatie, bewaard in **`sessionStorage`**, niet in
  `localStorage`. Een naam die dagen later nog voorgevuld staat op een gedeelde machine is precies hoe
  iemand ongemerkt op naam van een collega tekent.
- `ActorBar` staat permanent in de app shell en toont wie er "tekent", met een duidelijke waarschuwing:
  *"Er is nog geen authenticatie. Deze naam wordt ongecontroleerd in het beslissingsregister bewaard."*
- **Elke bevestigingsdialoog toont de naam opnieuw en laat hem ter plekke wijzigen.** De naam wordt
  nooit onzichtbaar meegestuurd. Wie tekent, ziet waarvoor en onder welke naam.
- Clientvalidatie spiegelt de servervalidatie (niet leeg, ≤100, niet `system`, hoofdletterongevoelig)
  maar **vervangt** ze niet: een 400 van de server wordt altijd afgehandeld.
- Eén plek, `actor/ActorContext.tsx`, zodat Fase 5 die ene module vervangt door een Keycloak-identiteit
  en geen enkel formulier hoeft te wijzigen.

## 8. Scherm (3) — routes en views

| Route | View | Inhoud |
|---|---|---|
| `/bundles` | `BundleListPage` | lijst + statusfilter + "nieuwe bundel" |
| `/bundles/:bundleId` | `BundleDetailPage` → `BundleOverviewTab` | stand, tellers, audit, acties |
| `/bundles/:bundleId/batches` | `BundleBatchesTab` | leden + kandidaten toevoegen/verwijderen |
| `/bundles/:bundleId/mutations` | `BundleMutationsTab` | `MutationList` + groepsactie |
| `/bundles/:bundleId/decisions` | `BundleDecisionsTab` | beslissingsregister, alleen-lezen |

`/` leidt door naar `/bundles`. Een onbekende route toont een nette 404-pagina, geen witte pagina.

**Router: `react-router` wordt toegevoegd** — één afhankelijkheid, bewust, als uitzondering op §2
principe 4. Motivering: een bundel onder beoordeling moet deelbaar zijn met een collega
(`/bundles/42/mutations`), en de tabbladen moeten in de geschiedenis van de browser staan. Zelf
routing schrijven betekent een opgelost probleem herbouwen dat bij elk volgend scherm meegroeit.
*Instructie voor de Bouwer:* controleer bij het installeren welke major er binnenkomt en welk
importpad die versie voorschrijft (`react-router` dan wel `react-router-dom`) — niet aannemen, maar
opzoeken in het geïnstalleerde pakket.

`BundleDetailPage` laadt `GET /bundles/{id}` één keer en geeft die `BundleDetail` aan alle tabbladen
door. De tabbladen laden zelf hun eigen lijst. Zo staat de bundelstatus — de bron van alle
actiebeslissingen (§9) — op één plek.

## 9. Scherm (3) — statussen, acties en wat per status mag

### 9.1 De statusmatrix

`bundlePolicy.ts` bevat één pure functie; hier is de tabel die ze implementeert. Ze is een **spiegel**
van de backend, nooit de bron van waarheid: een geweigerde 409 wordt altijd getoond, ook wanneer de
poort "toegestaan" zei.

| Actie | ASSEMBLING | FROZEN | CANCELLED | PUBLISHING / PARTIALLY_PUBLISHED / PUBLISHED / PUBLICATION_FAILED |
|---|---|---|---|---|
| batches toevoegen/verwijderen | ✔ | ✘ `BUNDLE_NOT_ASSEMBLING` | ✘ | ✘ (alleen-lezen) |
| mutatie goedkeuren/afkeuren | ✔ | ✘ `BUNDLE_NOT_ASSEMBLING` | ✘ | ✘ |
| beslissing herzien | ✔ (individueel, reden verplicht) | ✘ | ✘ | ✘ |
| groepsactie | ✔ | ✘ | ✘ | ✘ |
| bevriezen | ✔ | ✘ `BUNDLE_NOT_ASSEMBLING` | ✘ | ✘ |
| annuleren | ✔ | **✔** | ✘ `BUNDLE_NOT_CANCELLABLE` | ✘ |
| alles lezen | ✔ | ✔ | ✔ | ✔ |

De vier Fase 5-statussen kunnen vandaag niet voorkomen (Fase 4 zet ze niet), maar de UI moet ze
**verdragen**: alleen-lezen plus de melding *"Deze bundel staat in een status die deze versie van het
scherm niet bedient (`PUBLISHING`)."* Geen crash, geen lege pagina, geen knop die een 409 uitlokt.

Een verboden actie wordt **uitgeschakeld getoond met de reden erbij**, niet verborgen. Een verdwenen
knop laat een gebruiker gissen; een uitgeschakelde knop met *"Kan niet: de bundel is bevroren"* legt de
statusflow uit terwijl hij werkt.

### 9.2 Beslisbaarheid per mutatie

Tweede pure functie in `bundlePolicy.ts`, gespiegeld op `BundleDecisionService`:

| Situatie | Goedkeuren/afkeuren | Toelichting in de UI |
|---|---|---|
| bundel ASSEMBLING, `actionType ∈ {CREATE, UPDATE}`, `status ∈ {PLANNED, AWAITING_APPROVAL}` | ✔ | afkeuren vereist altijd een reden |
| idem, `status ∈ {READY_FOR_PUBLICATION, REJECTED}` | ✔ als **herziening**, alleen individueel | reden verplicht, ook bij goedkeuren |
| `status = BLOCKED` | ✘ | "geblokkeerd door een kritiek identiteitsincident; Fase 4 kent hier geen beslispad" (`MUTATION_BLOCKED_BY_IDENTITY_INCIDENT`) |
| `actionType = IDENTITY_REFERENCE_INCIDENT` | ✘ | "een identiteitsbeslissing schrijft in de referentiestaat; dat komt in een latere fase" (`IDENTITY_DECISION_NOT_IN_SCOPE`) |
| `actionType = IMPORT_MARKER` | ✘ | "geen inhoudelijke mutatie" |
| overige statussen (`EXPIRED`, `SKIPPED`, `RECORDED`, ...) | ✘ | `MUTATION_NOT_DECIDABLE` |

Deze tabel is één-op-één testbaar (§14) en is de belangrijkste businesslogica die in de frontend leeft.
Daarom staat ze in een aparte, pure module en niet verspreid door JSX.

### 9.3 Tellers, en het verschil tussen 0 en "niet vastgesteld"

De tellers zijn nullable en dat betekent iets. `null` wordt getoond als **"—"** met een tooltip *"niet
vastgesteld"*; nooit als `0`. Concreet op het overzichtstabblad:

- ASSEMBLING: `contentMutationCount`, `readyCount`, `rejectedCount`, `blockedCount`,
  `identityIncidentCount` zijn **live berekend**; `expiredCount`, `bulkIncidentCount`,
  `criticalIssueCount`, `warningCount` komen van de rij en zijn dus doorgaans `null`. De UI zegt dat
  expliciet: *"vastgesteld bij het bevriezen"*.
- FROZEN/CANCELLED: alle tien komen van de rij — de getallen waarvoor getekend is. De UI meldt
  *"vastgesteld op <frozenAt>"* zodat niemand denkt dat ze meebewegen.
- `staleMutationCount > 0` bij ASSEMBLING is een **waarschuwing, geen blokkade**: *"De bronstaat is
  verschoven sinds de screening; bevriezen zal geweigerd worden (`SOURCE_STATE_CHANGED_SINCE_SCREENING`).
  Screen de levering opnieuw."* Bij FROZEN is hij `null` en wordt hij niet getoond.
- `contentHash` wordt bij FROZEN getoond, afgekort tot de eerste 16 tekens met een kopieerknop voor de
  volledige waarde. Hij wordt nooit geïnterpreteerd, alleen weergegeven.
- Het aantal **nog te beslissen** mutaties staat in géén teller (zie §16.2). Het overzicht haalt het op
  met twee goedkope aanroepen `GET /bundles/{id}/mutations?status=AWAITING_APPROVAL&size=1` en
  `...?status=PLANNED&size=1` en gebruikt `totalElements`. Dat is precies het getal dat bepaalt of
  bevriezen kan (`BUNDLE_HAS_UNDECIDED_MUTATIONS`) en hoeveel `PLANNED`-mutaties bij het bevriezen
  automatisch goedgekeurd worden op naam van de bevriezer.

### 9.4 Financiële weergave — wat de frontend nooit doet

Uit AGENT.md §2 principe 8, hier hard gemaakt:

- De frontend **rekent nooit** met bedragen. Geen totalen, geen gemiddelden, en in het bijzonder
  **geen berekend verschil tussen `beforeBasePrice` en `afterBasePrice`** — hoe verleidelijk een kolom
  "Δ prijs" ook is. Wil men dat ooit, dan berekent de backend het.
- Bedragen worden getoond zoals ze binnenkomen, met `Intl.NumberFormat('nl-BE')` en de bijbehorende
  `basePriceCurrency` ernaast. Ontbreekt `basePriceCurrency`, dan wordt het bedrag zónder munteenheid
  getoond, nooit met een aangenomen EUR.
- `null` prijs is "—", nooit "0,00".

## 10. Scherm (3) — de acties, met hun bevestiging

### 10.1 Bundel aanmaken (`POST /bundles`)

Formulier: `bundleReference` (verplicht), `description`, `targetMode` (**verplicht, geen
voorselectie** — de backend heeft bewust geen default, en de UI mag dat niet ongedaan maken),
`targetMoment` (optioneel), `publicationPolicy` (optioneel), `createdBy` (uit de ActorContext).

Bij `PRODUCTION` verschijnt een expliciete waarschuwing (zie §17 Q3). Bij een herhaalde referentie is
de backend idempotent en krijgt de gebruiker de bestaande bundel te zien, met de melding dat die al
bestond — niet stil "aangemaakt". Bij `BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE` (409) toont de UI
dat de referentie al bestaat met een andere scope.

### 10.2 Batches toevoegen en verwijderen

Het ledenstabblad toont links de leden (`GET /bundles/{id}/batches`, inclusief de verwijderde, met hun
`removedBy`/`removedReason` — dat is audit, dat verbergen we niet) en rechts de kandidaten
(`GET /bundles/candidates`, optioneel gefilterd op `importLinkId`).

Toevoegen is **alles-of-niets** aan de backendkant. De UI zegt dat vóór de aanroep: *"Weigert één batch,
dan wordt er niets toegevoegd."* Bij een 409 (`BATCH_ALREADY_IN_BUNDLE`, `BATCH_NOT_BUNDLEABLE`,
`BATCH_VALIDATION_NOT_ESTABLISHED`, `BATCH_VALIDATION_BLOCKING`) wordt de code met zijn uitleg getoond
en verandert de selectie niet, zodat de gebruiker er één kan afvinken en opnieuw kan proberen.

Verwijderen vereist een reden (verplicht) en kan 409 `BATCH_HAS_DECIDED_MUTATIONS` geven — de uitleg
daarbij is: *"Deze batch draagt al beslissingen; verwijderen zou een ondertekende beslissing uit het
dossier laten verdwijnen."*

### 10.3 Individueel goedkeuren, afkeuren en herzien

Loopt via het mutatielijst-component (§11). Regels:

- Goedkeuren: reden optioneel — **behalve** bij een herziening, dan verplicht.
- Afkeuren: reden altijd verplicht.
- Herziening (de mutatie draagt al een `decisionId` en gaat naar de tegengestelde status): de dialoog
  benoemt dat expliciet — *"U keert een eerdere beslissing van <decidedBy> van <decidedAt> om.
  Beide beslissingen blijven in het register staan."* Reden verplicht.
- Antwoord met `idempotent: true`: de UI meldt *"Deze beslissing stond al zo op uw naam; er is geen
  tweede regel geschreven."* Niet stil doen alsof er iets gebeurd is.

### 10.4 De groepsactie (`POST /bundles/{id}/decisions`)

Dit is de zwaarste hefboom van het scherm: één klik kan honderdduizenden mutaties goedkeuren zonder
tweede goedkeurder (fase 4-ontwerp §13 benoemt dat risico letterlijk). Ontwerp:

1. **De filter van de groepsactie is exact de filter die op dat moment in de mutatielijst staat.** Er
   is geen tweede, apart filterformulier. Wat de gebruiker ziet, is wat hij beslist.
2. Daaruit volgt een harde beperking: de UI biedt **alleen** de filtervelden aan die de mutatielijst
   ook kan tonen — `batchId`, `status`, `actionType`. Het veld `statusReason`, dat de backend in
   `DecisionFilter` wél aanvaardt, wordt in deze slice **niet** aangeboden, omdat
   `GET /bundles/{id}/mutations` er niet op kan filteren en de gebruiker dus niet kan zien waarvoor hij
   tekent (§16.1, met een voorstel voor een kleine backenduitbreiding).
3. De dialoog toont het aantal betrokken regels (`totalElements` van de huidige lijst, gebruikt als bovengrens omdat er geen droogloop-endpoint bestaat) en de zin:
   *"Deze actie raakt nooit een geblokkeerde mutatie, een identiteitsincident, de importmarkering of een
   mutatie die al een beslissing draagt."* — dat is wat de backend garandeert en wat de gebruiker moet
   weten om het getal te kunnen duiden.
4. Een lege filter is in de UI onmogelijk (de knop is uit); komt er toch een 400
   `DECISION_FILTER_REQUIRED`, dan wordt die getoond.
5. Antwoord `affectedCount = 0`, `decisionId = null`: *"Er voldeed niets (meer) aan de selectie; er is
   bewust geen beslissingsregel geschreven."*
6. **Geen typ-bevestiging** hier: een groepsbeslissing is binnen `ASSEMBLING` per mutatie te herzien.
   Wel de reden-eis van de backend (verplicht bij afkeuren).

> **Important technical constraint discovered** (F9, 2026-09-24)
>
> De backend (Spring/Jackson) negeert onbekende JSON-velden in `DecideGroupRequest.filter` in plaats van ze te weigeren. Een filterveld dat de client meestuurt maar de server niet kent, valt dus stil weg — voor de groepsactie betekent dat een bredere selectie dan de gebruiker ziet. Clientzijdig afgevangen in `Frontend/src/features/bundles/groupDecisionFilter.ts` (`toDecisionFilter` neemt elk veld expliciet over; een nieuw veld in `MutationFilter` dat daar niet wordt overgenomen, laat de typecheck falen). Open beslissing: moet `DecisionFilter` serverzijdig onbekende velden weigeren? (contractkeuze, nog niet genomen)

### 10.5 Bevriezen (`POST /bundles/{id}/freeze`)

Bevriezen is de zwaarste, deels onomkeerbare stap: alle resterende `PLANNED`-mutaties worden in bulk
goedgekeurd **op naam van de bevriezer**, de tellers en de bundelhash worden vastgezet, en de bundel
aanvaardt daarna niets meer. Bewust niet-idempotent.

Voorvlucht (de dialoog laadt eerst `GET /bundles/{id}` opnieuw en toont):

- het aantal `PLANNED` dat **mee-goedgekeurd wordt op uw naam** — het belangrijkste getal in de hele
  dialoog, opgehaald zoals in §9.3;
- het aantal `AWAITING_APPROVAL`: is dat > 0, dan is de bevriesknop uit met de reden erbij (de backend
  zou `BUNDLE_HAS_UNDECIDED_MUTATIONS` geven);
- `batchCount = 0` → uit, reden `BUNDLE_EMPTY`;
- `staleMutationCount > 0` → waarschuwing dat de server zal weigeren met
  `SOURCE_STATE_CHANGED_SINCE_SCREENING`;
- de blijvende waarschuwing: *"Publiceren bestaat nog niet (Fase 5). Een bevroren bundel blokkeert de
  betrokken aanbiedingen voor elke andere bundel tot ze gepubliceerd of geannuleerd wordt."* — dat is
  het risico dat fase 4-ontwerp §14 zelf benoemt als "nog geen signalering".

Bevestiging: `frozenBy` (zichtbaar/bewerkbaar), `reason` (verplicht), **en de gebruiker moet de
`bundleReference` overtypen**. Die wrijving is er alleen bij bevriezen en annuleren, om één reden: dat
zijn de twee acties die binnen de applicatie niet meer ongedaan te maken zijn.

Conflicten (`BUNDLE_OFFER_CONFLICT`, `OFFER_ALREADY_IN_ANOTHER_BUNDLE`) zijn **niet** vooraf te
voorspellen — er is geen endpoint dat ze berekent (§16.3). De backend zet tot tien voorbeelden in de
foutmelding; de UI toont die melding volledig en leesbaar (met behoud van regelafbrekingen), plus de
uitleg *"Twee publiceerbare mutaties op dezelfde aanbieding: keur er één af, of publiceer/annuleer eerst
de andere bundel."*

### 10.6 Annuleren (`POST /bundles/{id}/cancel`)

Mag vanuit `ASSEMBLING` én `FROZEN`. Gevolg: elke niet-terminale mutatie van de actieve leden wordt
`EXPIRED` en **wordt nooit meer herleefd**; de batches komen vrij voor `accept-baseline` of een andere
bundel.

De dialoog zegt dat letterlijk, met het aantal mutaties dat vervalt, plus: *"Dit maakt de beslissingen
in deze bundel niet ongedaan in het register — ze blijven bewaard — maar de mutaties zelf zijn daarna
definitief vervallen."* Zelfde bevestigingsvorm als bevriezen: actor + verplichte reden +
`bundleReference` overtypen.

### 10.7 Het beslissingsregister

Alleen-lezen, chronologisch, gepagineerd. Kolommen: tijdstip, `decisionKind`, `decisionScope`, beslisser,
`affectedCount`, `previousStatus → newStatus`, `selectionFilter`, reden, en bij scope `MUTATION` een
verwijzing naar de mutatie. Nergens een knop. De uitleg boven de lijst: *"Append-only: een herziening
staat hier als extra regel naast de beslissing die ze herziet."*

## 11. Het herbruikbare mutatielijst-component

Dit is het component uit de beslissing van 22/09. Het wordt hier gebouwd voor
`/bundles/{id}/mutations` en moet **zonder wijziging** op `/batches/{id}/mutations` passen. Dat is
haalbaar omdat bouwstap 4c de twee antwoorden bewust identiek gemaakt heeft (beide `PageResult<MutationRow>`
met dezelfde 27 velden).

### 11.1 Het contract

```ts
export type MutationQuery = {
  status?: MutationStatus; batchId?: number; actionType?: MutationActionType;
  page: number; size: number;
};

export type MutationSource = {
  key: string;                       // stabiele identiteit, bv. 'bundle:42' of 'batch:17'
  fetchPage: (query: MutationQuery, signal: AbortSignal) => Promise<PageResult<MutationRow>>;
  supportedFilters: ReadonlyArray<'status' | 'batchId' | 'actionType'>;
};

export type Gate = { allowed: true } | { allowed: false; reason: string };

export type MutationRowAction = {
  id: string;
  label: string;
  variant: 'primary' | 'danger' | 'neutral';
  reasonRequirement: (row: MutationRow) => 'required' | 'optional';
  gate: (row: MutationRow) => Gate;
  run: (row: MutationRow, input: { actor: string; reason: string | null }) => Promise<unknown>;
  confirmTitle?: (row: MutationRow) => string;
  confirmBody?: (row: MutationRow) => React.ReactNode;
};

export type MutationListProps = {
  source: MutationSource;
  rowActions?: readonly MutationRowAction[];   // weggelaten = zuivere leeslijst
  initialQuery?: Partial<MutationQuery>;
  emptyMessage?: string;
  onAfterAction?: () => void;                  // de ouder ververst zijn eigen gegevens
};
```

### 11.2 Wat het component wél doet

Filterbalk (alleen de velden uit `supportedFilters`), tabel met de `MutationRow`-kolommen inclusief de
vier beslissingsvelden, paginering, laad-/lege-/foutstatus, en per rij de doorgegeven acties — telkens
met de bevestigingsdialoog die reden en actor verzamelt. Na een geslaagde actie: de huidige pagina
herladen en `onAfterAction()` aanroepen.

Kolommen: `id`, `batchId`, `actionType`, `status` (+ `statusReason`), identiteit (leverancier /
leveranciersgroep / referentie / kortingscode), `beforeBasePrice → afterBasePrice` met munteenheid,
`domainMask`, referentievelden bij een incident, `sourceRowNumber`, en de beslissing
(`decidedBy`, `decidedAt`, `decidedFromStatus`, `decisionId`). De beslissingskolommen staan er **altijd**,
ook op scherm (2): het is hetzelfde record, en in een batchlijst is "al beslist in een bundel" juist
nuttige informatie.

### 11.3 Wat het component bewust NIET weet

Dit lijstje is het eigenlijke ontwerp; het is wat het herbruikbaar maakt.

1. **Niet of zijn gegevens van een bundel of van een batch komen.** Het kent geen `bundleId` en geen
   `batchId` als eigen prop — alleen `source.fetchPage`.
2. **Niet welk HTTP-pad het aanroept.** Het importeert niets uit `api/`. Dat maakt het ook
   triviaal testbaar met een verzonnen bron (§14).
3. **Niet welke acties toegestaan zijn.** Het vraagt het aan `gate(row)` en toont een uitgeschakelde
   knop met de meegegeven reden. De hele beslisbaarheidsmatrix van §9.2 woont in
   `features/bundles/bundlePolicy.ts`, buiten het component.
4. **Niet wat er na een actie ververst moet worden** buiten zichzelf. Het roept `onAfterAction()` aan;
   de ouder weet welke tellers en tabbladen verouderd zijn.
5. **Niet welke filters er bestaan**, alleen welke deze bron ondersteunt. Scherm (2) geeft
   `['actionType']` mee — `GET /batches/{id}/mutations` kan vandaag niet op `status` filteren — en de
   statusfilter verschijnt daar simpelweg niet, zonder één regel wijziging in het component.
6. **Niet wie er tekent.** De actor komt uit de gedeelde `ActorContext` (fundament, §7), niet uit een
   prop van een scherm.

### 11.4 Gebruik op scherm (2), vooruitgedacht

```ts
// scherm (3)
{ key: `bundle:${bundleId}`, fetchPage: (q, s) => api.bundleMutations(bundleId, q, s),
  supportedFilters: ['status', 'batchId', 'actionType'] }

// scherm (2), later, zonder wijziging aan het component
{ key: `batch:${batchId}`, fetchPage: (q, s) => api.batchMutations(batchId, q, s),
  supportedFilters: ['actionType'] }
```

Scherm (2) geeft **geen** `rowActions` mee: op batchniveau bestaat er geen beslispad per mutatie
(beslissen gebeurt uitsluitend binnen een bundel). Het component wordt daar vanzelf een leeslijst.

### 11.5 Bewuste grens: geen groepering op wijzigingsgroep

Het fase 4-ontwerp definieert de wijzigingsgroep als `(batch_id, identity_hash)`. In de UI is die groep
**niet** te reconstrueren: `identity_hash` staat niet in `MutationRow`, en groeperen op de
identiteitsvelden zou alleen binnen één opgehaalde pagina (max. 200 rijen) kunnen — wat een groep zou
kunnen splitsen over paginagrenzen en dus een **verkeerd** beeld geeft van atomiciteit. Dat is erger dan
geen groepering. De lijst blijft daarom vlak. Als groepering ooit nodig is, hoort ze aan de serverkant
(§16.4).

## 12. Wat scherm (2) straks nodig heeft (kort, geen ontwerp)

Scherm (2) "levering & screening" is pas aan de beurt ná de materialisatiewizard (bouwstappen 5b-5e) —
`docs/decisions.md` 22/09 "Volgorde: sjabloon+bookmarks vóór scherm 2".

**Bestaat al en wordt hergebruikt:** `POST /tasks/{taskId}/deliveries` (multipart),
`GET /deliveries/{id}`, `GET /batches/{id}`, `/mutations`, `/issues`, `/issue-groups`,
`POST /batches/{id}/accept-baseline`, `POST /batches/{id}/continue`.

**Fundament dat ongewijzigd meegaat:** `MutationList`, `ConfirmDialog` (accept-baseline vraagt
`acceptedBy` + verplichte reden — exact dezelfde vorm), `errors/codes.ts` (aanvullen met de
`CONFIG_*`-familie en `BATCH_IN_PUBLICATION_BUNDLE`), `StatusBadge`, `DataTable`, `Pager`,
`ActorContext`, `useQuery`/`useAction`.

**Nieuw nodig aan frontendkant:**

1. Een **uploadcomponent** met voortgang en een expliciete "dit kan lang duren"-toestand (zie het
   constraintblok in §18: de upload screent synchroon).
2. Een **declaratief, uit de backend gegenereerd formulier** voor de materialisatiewizard: de
   bookmarkinvulset komt uit `GET /templates/{d}/revisions/{r}/bookmarks` met naam, label, uitleg,
   type, scope, verplicht, default, keuzelijst, patroon en volgorde, plus een `problems`-lijst. Dat is
   het tweede grote fundamentstuk na dit ontwerp en verdient een eigen Denker-analyse.
3. Een **issue-/issuegroeplijst** met de uitdrukkelijke waarschuwing die de backend-javadoc al geeft:
   de getoonde regels zijn *voorbeelden* (begrensd door `max-sample-rows-per-code`); het werkelijke
   aantal staat alleen in `GET /batches/{id}/issue-groups`. Een telling over de issuelijst is
   systematisch te laag — dat moet in de UI staan, niet alleen in javadoc.
4. De **keuze accept-baseline vs. bundel-opname** naast de batch (22/09), met de 409
   `BATCH_IN_PUBLICATION_BUNDLE` als uitleg waarom de ene route de andere uitsluit.

**Wat de backend voor scherm (2) nog niet heeft:** er is geen lijst-/zoekendpoint voor leveringen,
batches of taken. Scherm (2) kan vandaag alleen starten vanaf "ik heb net geüpload" of "ik ken het
batch-id"; de enige bestaande batchlijst is `GET /bundles/candidates`, en die toont alleen
bundel-waardige batches. Dat is dezelfde vaststelling die tot het uitstel van scherm (0) leidde
(22/09 "D14") en verandert daar niets aan — het betekent wel dat scherm (2) een instapweg mist.

## 13. Bewuste grenzen van deze slice

Wat er **niet** komt, met reden:

- Geen dashboard/werkvoorraad (22/09 "D14 uitgesteld").
- Geen beheer-/inrichtingsscherm (22/09 "setup-API productiewaardig maken": na Fase 5/Keycloak).
- Geen publiceren — dat bestaat niet in de backend (Fase 5).
- Geen beslispad voor `BLOCKED`/`IDENTITY_REFERENCE_INCIDENT` (fase 4-ontwerp §3.6).
- Geen afhankelijkheid van de setup-API: die staat standaard uit, dus scherm (3) moet volledig werken
  met `catalogimport.setup-api.enabled=false`. Gevolg: koppelingen worden als **nummer** getoond
  (§16.5).
- Geen deep-linking van filter-/paginastatus in de URL (wel van bundel en tabblad). Later additief.
- Geen meertaligheid: Nederlandse labels hardgecodeerd, met één uitzondering — `errors/codes.ts` is al
  een woordenboek en zou een i18n-laag makkelijk aankunnen.
- Geen offline-/hersteltoestand, geen achtergrondverversing.
- Geen toegankelijkheidsaudit; wel de basis (labels aan invoervelden, focusbeheer in dialogen,
  status nooit alleen door kleur).

## 14. Testplan — eerlijk over de kosten

Er is vandaag **geen** testinfrastructuur in `Frontend/`. De minimale kost om überhaupt iets te kunnen
testen: drie dev-afhankelijkheden (`vitest`, `@testing-library/react`, `jsdom`, plus
`@testing-library/jest-dom` als vierde voor leesbare assertions) en een configblok. Dat is de prijs; ik
stel voor die te betalen, want de alternatieven zijn "nul geautomatiseerde tests" of "een volledige
e2e-opstelling", en die laatste kost een veelvoud.

**Bewust niet:** Playwright/Cypress (vraagt een draaiende backend, een Postgres met gezaaide gegevens
en een eigen CI-stap — meer opzetwerk dan de hele slice), en MSW (netwerk-mocking; overbodig zolang de
API-laag één module is die met `vi.mock` te vervangen is; herzieningstrigger: zodra twee of meer
schermen dezelfde fetch-stubs kopiëren).

### 14.1 Wat wél geautomatiseerd wordt (vijf groepen, allemaal goedkoop en hoogrenderend)

| # | Onderwerp | Soort | Waarom dit wél |
|---|---|---|---|
| T1 | `errors/codes.ts` — bekende code, onbekende code met familie-fallback, 400 zonder code, 500 zonder body, netwerkfout | pure functie | Dit is de belofte "de stabiele code verdwijnt nooit". Een test per pad, geen opzet nodig. |
| T2 | `bundlePolicy.ts` — de volledige matrix uit §9.1 en §9.2, tabelgedreven over alle 7 bundelstatussen × 11 mutatiestatussen × 4 actietypes | pure functie | De enige businesslogica in de frontend. Verkeerd = een knop die iets aanbiedt wat een 409 wordt, of erger: die een verboden actie suggereert. |
| T3 | `api/http.ts` — `ApiError` ontleedt `code`/`error` correct, 204 geeft null, afbreken geeft `status = 0` | unit met `fetch`-stub | Het fundament waar alles op leunt. |
| T4 | `ConfirmDialog` — bevestigen geblokkeerd zonder verplichte reden; zonder actor; typ-bevestiging eist de exacte referentie | component | Dit is de bescherming tegen een onomkeerbare klik. |
| T5 | `MutationList` — rendert rijen uit een **verzonnen** bron; pager roept `fetchPage` met de juiste `page`/`size`; een rij met `gate.allowed = false` toont een uitgeschakelde knop mét reden; een actie roept `run` met actor + reden en daarna `onAfterAction` | component | Bewijst rechtstreeks de herbruikbaarheidseigenschap uit §11.3: het component draait volledig zonder `api/`. |

Plus twee poortwachters die geen tests zijn maar wel elke bouwstap afsluiten: `npm run build`
(`tsc -b` — typefouten tegen de contracttypes zijn hier de goedkoopste vangst) en `npm run lint`.

### 14.2 Wat bewust handmatig blijft

Alles wat een draaiende backend met gegevens nodig heeft. Reden: de waarde zit in het samenspel met
echte data, en dat namaken kost meer dan het oplevert bij één scherm. Het blijft wel **gescript**, zodat
het herhaalbaar is — deze lijst hoort in `Frontend/README.md`:

1. Bundel aanmaken; tweemaal dezelfde referentie ⇒ idempotent, met de melding dat ze al bestond.
2. Kandidaten toevoegen; een al toegevoegde batch nogmaals ⇒ `BATCH_ALREADY_IN_BUNDLE`, niets veranderd.
3. Eén mutatie goedkeuren; dezelfde nogmaals door dezelfde persoon ⇒ `idempotent: true`, geen tweede
   regel in het register.
4. Een goedkeuring herzien naar afkeuren ⇒ reden verplicht, beide regels zichtbaar in het register.
5. Groepsactie met de zichtbare filter ⇒ `affectedCount` komt overeen met wat de lijst toonde; een
   `BLOCKED`-mutatie in de lijst blijft ongewijzigd.
6. Bevriezen met een openstaande `AWAITING_APPROVAL` ⇒ knop uit met reden; na beslissen ⇒ bevriezen
   lukt, `PLANNED` staat op `READY_FOR_PUBLICATION` op naam van de bevriezer, tellers en hash staan vast.
7. Tweede keer bevriezen ⇒ 409 `BUNDLE_NOT_ASSEMBLING`, leesbaar getoond.
8. Annuleren van een bevroren bundel ⇒ mutaties `EXPIRED`, batches weer zichtbaar bij de kandidaten.
9. Backend uitzetten en een pagina laden ⇒ nette netwerkfout, geen witte pagina.
10. Prijsweergave op een mutatie met veel decimalen ⇒ wordt weergegeven, niet herrekend.

Testcommando's: `npm test` (vitest, in `Frontend/`), `npm run build`, `npm run lint`. De
Maven-reactor wordt hier **niet** aangeraakt; het gerichte backendcommando uit de vorige fasen
(`mvn -pl Web -am test`) blijft gelden voor de eventuele backendstap B1 (§15).

## 15. Bouwstappen

Strikt sequentieel — elke stap raakt bestanden van de vorige. Eén commit per stap. Geen parallellisatie:
F1 t/m F10 delen `routes.tsx`, de app shell en `api/types.ts`.

| # | Zwaarte | Resultaat | Acceptatiecriterium |
|---|---|---|---|
| **F1** | bouwer-licht | Opruimen + app shell: template-`index.css`/`App.css`/`hero.png`/`react.svg`/`vite.svg` weg, `styles/reset.css` + `tokens.css`, `App.tsx` als shell met kop en navigatie, `routes.tsx` met `/bundles` en `/bundles/:bundleId` als lege pagina's, `react-router` toegevoegd, `strict: true` in `tsconfig.app.json`. | `npm run build` en `npm run lint` slagen; beide routes openen; geen enkel template-bestand blijft over. |
| **F2** | bouwer-gemiddeld | API-fundament: `api/http.ts` (`request`, `ApiError`), `api/types.ts` (alle types uit §1.2, 1:1 met de records), `api/bundles.ts` (alle 14 bundel-endpoints), `api/batches.ts` (mutaties + accept-baseline), `hooks/useQuery.ts`, `hooks/useAction.ts`. | Compileert; elke functie in `bundles.ts` komt overeen met een methode in `CatalogImportBundleController` (één-op-één na te lopen); geen component importeert `fetch`. |
| **F3** | bouwer-licht | Testinfrastructuur: `vitest` + `@testing-library/react` + `jsdom` (+ `jest-dom`), `npm test`, plus tests **T3**. | `npm test` draait groen; T3 dekt code/geen-code/204/netwerkfout. |
| **F4** | bouwer-gemiddeld | `errors/codes.ts` + `ErrorBanner` (incl. familie-fallbacks en de altijd zichtbare technische regel) + tests **T1**. | Elke code uit §1.2 heeft een Nederlandse tekst; een verzonnen code geeft nog steeds een leesbare melding mét de code; T1 groen. |
| **F5** | bouwer-gemiddeld | `ActorContext` + `ActorBar` (sessionStorage, waarschuwing, clientvalidatie) en de gedeelde primitieven `DataTable`, `Pager`, `StatusBadge`, `Field`, `ConfirmDialog` (reden verplicht/optioneel, actor, typ-bevestiging) + tests **T4**. | T4 groen; `ConfirmDialog` laat niet bevestigen zonder verplichte reden, zonder actor, of met een verkeerd overgetypte referentie. |
| **F6** | bouwer-gemiddeld | `BundleListPage` (lijst, statusfilter, paginering) + `CreateBundleForm` (`targetMode` verplicht zonder voorselectie, idempotentiemelding). | Een bundel aanmaken en terugzien werkt tegen de echte backend; een tweede identieke aanmaak meldt "bestond al". |
| **F7** | bouwer-gemiddeld | `BundleDetailPage` + `bundlePolicy.ts` (§9.1 + §9.2) + `BundleOverviewTab` (tellers met "—" voor null, stale-waarschuwing, hash, audit, actieknoppen nog zonder werking maar mét correcte poort) + `BundleBatchesTab` (leden, kandidaten, toevoegen, verwijderen met reden) + tests **T2**. | T2 groen over de volledige matrix; uitgeschakelde knoppen tonen altijd een reden; een 409 bij toevoegen laat de selectie ongemoeid. |
| **F8** | **bouwer-zwaar** | `components/MutationList/` volgens §11 + `BundleMutationsTab` met individuele goedkeuring/afkeuring/herziening + tests **T5**. Zwaar: raakt de financiële beslislogica en zet meteen het herbruikbaarheidscontract vast voor scherm (2). | T5 groen mét een verzonnen bron (bewijst dat het component `api/` niet importeert); `BLOCKED`/incident/marker tonen een uitgeschakelde knop met de juiste uitleg; `idempotent: true` wordt als zodanig gemeld. |
| **F9** | **bouwer-zwaar** | `GroupDecisionDialog`: filter = exact de zichtbare lijstfilter, aantal vooraf, geen `statusReason`, waarschuwing over de hefboom, `affectedCount = 0` netjes gemeld. | De dialoog kan geen filter versturen die van de getoonde lijst afwijkt (aantoonbaar in code én met een test op de filter-naar-request-omzetting). |
| **F10** | **bouwer-zwaar** | `FreezeDialog` + `CancelDialog` volgens §10.5/§10.6: voorvlucht met het `PLANNED`-aantal, blokkades vooraf, typ-bevestiging, volledige weergave van de conflict-409's. | Bevriezen met een openstaande `AWAITING_APPROVAL` is vooraf geblokkeerd met reden; de 409-tekst met conflictvoorbeelden wordt volledig en leesbaar getoond; de typ-bevestiging werkt. |
| **F11** | bouwer-licht | `BundleDecisionsTab` (alleen-lezen register) + `Frontend/README.md` bijwerken met de handmatige testlijst uit §14.2 en de startinstructies. | Register toont een herziening als aparte regel naast de oorspronkelijke beslissing; README beschrijft `npm run dev` + de vereiste backend op 8081. |
| **B1** | bouwer-licht (**backend**, apart) | Deterministische sortering op `GET /bundles` en `GET /bundles/{id}/batches` (zie §16.6 en §17 Q4, pas ná akkoord). | `mvn -pl Web -am test` groen; paginering levert bij herhaling dezelfde volgorde. |

Rapport aan de mens na **F5** (fundament af) en na **F10** (het onomkeerbare deel af).

## 16. Wat de bestaande backend niet levert voor dit scherm

1. **Geen `statusReason`-filter op de mutatielijst**, terwijl `DecisionFilter` die filter wél aanvaardt.
   Gevolg: het meest bruikbare groepsscenario uit het fase 4-ontwerp ("keur alles goed wat om déze reden
   wachtte", bv. `BULK_PRICE_INCIDENT`) kan in de UI niet getoond en dus niet aangeboden worden.
   **Voorstel:** een additieve `statusReason`-queryparameter op `GET /bundles/{id}/mutations` (en, voor
   scherm 2, op `GET /batches/{id}/mutations`). Klein, additief, breekt niets.
2. **Geen teller voor `PLANNED` en `AWAITING_APPROVAL`** op `BundleDetail` — `BundleMutationTotals`
   zegt in commentaar zelf "hebben geen eigen teller op de bundel". Juist die twee bepalen of bevriezen
   kan en hoeveel er bij het bevriezen automatisch goedgekeurd wordt op naam van de bevriezer. De UI
   behelpt zich met twee `size=1`-aanroepen op `totalElements`. **Voorstel:** twee tellers additief
   toevoegen aan het live-deel van `BundleDetail` (niet aan de bevroren rij — die is vastgesteld).
3. **Geen voorspelling van de conflictcontrole.** `BUNDLE_OFFER_CONFLICT` en
   `OFFER_ALREADY_IN_ANOTHER_BUNDLE` blijken pas bij het bevriezen. Een "kan deze bundel bevroren
   worden?"-endpoint (droogloop) zou de zwaarste actie van het scherm voorspelbaar maken. Vandaag toont
   de UI alleen de 409 met haar tot tien voorbeelden. Niet blokkerend, wel de grootste gebruiksruwheid.
4. **`identity_hash` staat niet in `MutationRow`**, waardoor de wijzigingsgroep `(batch_id, identity_hash)`
   in de UI niet te tonen is (§11.5).
5. **Geen naam of code bij `importLinkId`.** `BundleBatchRow` en `BundleCandidate` geven alleen een
   nummer; leveranciers-/koppelingsnamen zitten uitsluitend in `GET /setup/overview`, dat standaard uit
   staat en volgens de beslissing van 22/09 tot na Fase 5 een ontwikkelhulp blijft. Scherm (3) toont dus
   "koppeling #7". Werkbaar, maar het is precies het soort detail dat een gebruiker nodig heeft om een
   bundel te begrijpen. **Voorstel:** `importLinkCode`/`supplierCode` additief op `BundleBatchRow` en
   `BundleCandidate`.
6. **Paginering zonder sortering** op `GET /bundles` en `GET /bundles/{id}/batches` — zie het
   constraintblok hieronder.
7. **Geen lijstendpoint voor leveringen/batches/taken** (raakt vooral scherm 2, §12).

## 17. Vragen aan de mens — beantwoord op 2026-09-23

**Alle vier beantwoord conform de aanbeveling.** Kort: Q1 → **B** (één keer per browsersessie,
`sessionStorage`, altijd zichtbaar en per bevestiging wijzigbaar); Q2 → **A** (tot Fase 5 uitsluitend
lokaal `npm run dev`, niets uitgeleverd, geen CORS, geen statische uitlevering via de `Web`-module);
Q3 → **A** (alle drie de `targetMode`-waarden, zonder voorselectie, met een expliciete waarschuwing bij
`PRODUCTION` dat een latere publicatiefase die bundel als echte publicatie behandelt); Q4 → **A**
(bouwstap B1 voegt deterministische sortering toe). De oorspronkelijke vraagstelling met alle opties
blijft hieronder staan als motivering.

### Q1 — Waar komt de actornaam vandaan? (autorisatie/auditeerbaarheid) — beslist: B

De namen in `decidedBy`/`frozenBy`/`cancelledBy` belanden ongecontroleerd in een append-only register
met zeven jaar bewaartermijn. Vastgelegd is dát het requestvelden zijn; niet hóe de UI eraan komt.

- **A.** Per actie opnieuw intypen. Maximale bewustwording, maximale irritatie bij een reeks
  beslissingen.
- **B (aanbevolen).** Eén keer per browsersessie invullen (`sessionStorage`), permanent zichtbaar in de
  app shell, **en in elke bevestigingsdialoog opnieuw getoond en ter plekke wijzigbaar**. Verdwijnt bij
  het sluiten van de browser.
- **C.** Uit een configuratie-/omgevingswaarde. Dan is het geen handtekening meer maar een machinenaam —
  onwenselijk bij een beslissingsregister.

Aanbeveling **B**, met de expliciete waarschuwing in beeld. Bewust **niet** `localStorage`: een naam die
dagen later nog voorgevuld staat op een gedeelde machine is precies hoe iemand ongemerkt op naam van een
collega tekent. Risico dat hoe dan ook blijft: er is geen enkele verificatie tot Fase 5.

### Q2 — Hoe wordt deze frontend buiten een ontwikkelmachine bediend? (architectuur) — beslist: A

Feitelijk: er is **geen** CORS-configuratie en **geen** Spring Security in de `Web`-module (gecontroleerd:
nul treffers op `@CrossOrigin`, `addCorsMappings`, `SecurityFilterChain`). Vandaag werkt de frontend
uitsluitend via de Vite-devproxy.

- **A (aanbevolen).** De frontend blijft tot Fase 5 uitdrukkelijk een ontwikkelhulpmiddel: alleen
  `npm run dev` tegen een lokale backend. Dat staat zo in `Frontend/README.md` en er wordt niets
  uitgeleverd.
- **B.** `dist/` als statische resources laten serveren door de `Web`-module. Werkt zonder CORS, maar
  bindt de frontend aan de Maven-build en raakt daarmee de beslissing van 22/09 ("buiten de
  Maven-reactor, geen bundeling in de Maven-build") — die zou de mens dan deels moeten herroepen.
- **C.** Aparte reverse proxy plus een CORS-configuratie in `Web`. Nieuwe infrastructuur én een nieuwe
  securityoppervlakte terwijl er nog geen authenticatie is.

Aanbeveling **A** nu; B of C beslissen samen met Keycloak in Fase 5. Belangrijk om dit *nu* te zeggen:
zonder authenticatie zou een bereikbare frontend betekenen dat iedereen die het netwerkadres kent een
publicatiebundel kan bevriezen.

### Q3 — Mag de UI vandaag `targetMode = PRODUCTION` aanbieden? (statusflow/scope) — beslist: A

Fase 5 bestaat niet; niets publiceert. Maar een bundel die vandaag bevroren wordt met
`targetMode = PRODUCTION` is precies de bundel die Fase 5 straks als echte publicatie naar
ProDisWebbase/Pervasive oppakt.

- **A (aanbevolen).** Alle drie de waarden aanbieden, zonder voorselectie (zoals de backend), met een
  duidelijke waarschuwing bij `PRODUCTION`: *"Deze bundel wordt door een latere publicatiefase als echte
  publicatie behandeld."*
- **B.** `PRODUCTION` in de UI verbergen tot Fase 5.

Aanbeveling **A**: de backend aanvaardt B's verboden waarde toch, dus B geeft schijnveiligheid terwijl
het de gebruiker het onderscheid ontneemt. Maar het gevolg — nu getekende `PRODUCTION`-bundels worden
later zonder nieuwe vraag echt gepubliceerd — is een statusflow-uitspraak die bij de mens hoort.

### Q4 — Mag `GET /bundles` en `GET /bundles/{id}/batches` een sortering krijgen? (contract) — beslist: A

`BundleQueryService.listBundles` en `getBundleBatches` gebruiken een `Pageable` **zonder** sortering (de
javadoc van `getBundleBatches` zegt "oplopend op id", maar de code geeft `Sort.unsorted()` mee). Zonder
`order by` mag PostgreSQL tussen twee pagina-aanvragen een andere volgorde teruggeven; dan kan een
bundel op twee pagina's tegelijk staan of helemaal ontbreken.

- **A (aanbevolen).** Bouwstap B1: sorteren op `id` aflopend (bundels) respectievelijk oplopend
  (lidmaatschappen, zoals de javadoc al beweert). Additief in gedrag, maar het legt wél een tot nu toe
  ongedefinieerde volgorde vast — daarom de vraag.
- **B.** Laten staan en in de UI melden dat de volgorde niet gegarandeerd is. Onwerkbaar bij
  paginering.

Aanbeveling **A**, als een aparte backend-commit vóór of na F6.

## 18. Ontdekkingen

> **Important technical constraint discovered**
>
> `BundleQueryService.listBundles` en `BundleQueryService.getBundleBatches` pagineren met een
> `Pageable` zonder sortering (`pageRequest(page, size)` → `Sort.unsorted()`), terwijl
> `getBundleMutations` (`Sort.by("id")`) en `getBundleDecisions` (`Sort.by("decidedAt","id")`) dat wél
> doen. Zonder `order by` is de rijvolgorde in PostgreSQL niet gedefinieerd tussen twee queries: bij het
> doorbladeren kan dezelfde bundel twee keer verschijnen of overgeslagen worden. De javadoc van
> `getBundleBatches` zegt bovendien "oplopend op id", wat de code niet afdwingt. Voorstel: terugschrijven
> naar `docs/design/fase4-publication-bundle-design.md` §3 en corrigeren in bouwstap B1 (zie §17 Q4).

> **Important technical constraint discovered**
>
> Het foutcontract is niet uniform. `ApiExceptionHandler` geeft `{error, code}` bij 404, 409 en bij
> `BadRequestException`, maar `{error}` **zonder code** bij elke `IllegalArgumentException`/
> `IllegalStateException` — en dat is precies de categorie waar de meeste gewone invoerfouten in vallen
> (lege reden, lege of `system` als actor, ontbrekende `targetMode`, lege batchlijst, ongeldige
> paginering). Die `error`-teksten zijn Engelse ontwikkelaarsteksten. Bovendien staat
> `server.error.include-message: never` in `application.yml`, zodat een 500 helemaal geen message
> draagt. Gevolg voor de UI: voor die 400's kan geen stabiele Nederlandse tekst gekozen worden en wordt
> de Engelse servertekst letterlijk getoond (§4 regel 4). Een latere, additieve verbetering zou zijn om
> ook deze validaties een stabiele code te geven (bv. `VALIDATION_FAILED` met een veldnaam). Voorstel:
> terugschrijven naar `docs/design/fase4-publication-bundle-design.md` §3.

> **Important technical constraint discovered**
>
> `CatalogImportDeliveryController.upload` archiveert, registreert **en screent synchroon** binnen één
> HTTP-verzoek (de javadoc bevestigt dit: "asynchroon volgt in fase 5"), terwijl
> `spring.servlet.multipart.max-file-size` op 1 GB staat en de businessanalyse leveringen tot een miljoen
> regels beschrijft. Eén upload kan dus minuten tot langer duren. Gevolg voor scherm (2): de
> uploadaanroep mag geen clienttimeout krijgen, moet een expliciete "dit kan lang duren, laat dit
> tabblad open"-toestand tonen, en een tussenliggende proxy met een standaardtimeout zal deze aanroep
> afbreken terwijl de server gewoon doorwerkt. Voorstel: terugschrijven naar
> `docs/design/fase2-screening-design.md`.

> **Important technical constraint discovered**
>
> `BigDecimal`-velden (`beforeBasePrice`, `afterBasePrice`) worden zonder Jackson-configuratie
> (gecontroleerd: nergens een `ObjectMapper`-configuratie of `jackson`-property) als JSON-**getal**
> geserialiseerd. `JSON.parse` in de browser maakt daar een IEEE-754-double van: achterliggende nullen
> gaan verloren (`12.3400` → `12.34`) en bij meer dan ~15 significante cijfers gaat precisie verloren.
> Maatregel in dit ontwerp: de frontend rekent nooit met bedragen en toont in het bijzonder **geen
> berekend prijsverschil** (§9.4). Wil men ooit exacte weergave inclusief schaal, dan moeten deze velden
> als string geserialiseerd worden — dat is een contractwijziging en dus een aparte beslissing.

## 19. Aannames (A39-A45)

- **A39** De Vite-devproxy blijft de enige verbindingsweg; de basis-URL is relatief.
- **A40** Eén `ActorContext` voor de hele applicatie, `sessionStorage`, altijd zichtbaar en per
  bevestiging wijzigbaar (onder voorbehoud van Q1).
- **A41** Geen server-state-bibliotheek; twee eigen hooks met expliciete invalidatie, met de
  herzieningstrigger uit §5.
- **A42** `react-router` is de enige nieuwe runtime-afhankelijkheid van deze slice; CSS Modules zonder
  UI-bibliotheek.
- **A43** Handgeschreven contracttypes, geen OpenAPI-generatie, met de herzieningstrigger uit §3.3.
- **A44** De UI-poortwachters (`bundlePolicy.ts`) zijn een spiegel van de backend, nooit de bron van
  waarheid: elke 409 wordt ook afgehandeld wanneer de poort "toegestaan" zei.
- **A45** Scherm (3) werkt volledig met `catalogimport.setup-api.enabled=false`; koppelingen worden als
  nummer getoond zolang §16.5 niet opgelost is.
