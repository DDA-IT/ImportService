# Ontwerp Fase 5-PERM — rechten per actie

Bindend na de beslissingen van 2026-09-25 (Fase 5-opknipping Q3/A2) en 2026-09-26 (lokale rechtenbron eerst, Prodis-adapter laatst; ze
staan in `docs/decisions.md`, waar ook de vier extra keuzes hieronder zijn vastgelegd). Bouwstappen 5B-1 … 5B-7, strikt sequentieel.
Geen changeset: 5-PERM raakt het schema niet.

## 1. Rechten en mapping

`Permission` (enum, Web): `READ` = `catalogImport.read`, `MANAGE` = `catalogImport.manage`, `APPROVE` = `catalogImport.approve`.

**Hiërarchie (keuze mens, 2026-09-26): `APPROVE` impliceert `MANAGE` en `READ`; `MANAGE` impliceert `READ`.** De bron levert de ruwe codes; CatalogImport
leidt het effectieve recht af (één plek: `CurrentActor`/`PermissionService`). `GET /me.permissions` geeft de EFFECTIEVE set terug, zodat de SPA één waarheid heeft.

| Endpoint | Recht |
|---|---|
| `GET /batches`, `/batches/summary`, `/{id}`, `/{id}/mutations`, `/{id}/issues`, `/{id}/issue-groups` | READ |
| `POST /batches/{id}/accept-baseline` | APPROVE |
| `POST /batches/{id}/continue` | MANAGE |
| `GET /bundles`, `/candidates`, `/{id}`, `/{id}/batches`, `/{id}/mutations`, `/{id}/decisions`, `/{id}/freeze-check`, `/{id}/psimport-preview` | READ |
| `POST /bundles`, `POST /bundles/{id}/batches`, `POST /bundles/{id}/batches/{b}/remove` | MANAGE |
| `POST /bundles/{id}/mutations/{m}/approve`, `/reject`, `POST /{id}/decisions`, `/freeze`, `/cancel` | APPROVE |
| `POST /bundles/{id}/publication-runs` (5P-8, zie `fase5-pub-design.md` §4) | APPROVE |
| `GET /bundles/{id}/publication-runs`, `GET /publication-runs/{runId}`, `GET /publication-runs/{runId}/artifact` (5P-8) | READ |
| `GET /publication-runs/{runId}/parallel-run-artifact` (P2c, rapport van de Prodis-parallelrun; zie `publicatie-prodis-db-direct-design.md` §6) | READ |
| `POST /tasks/{taskId}/deliveries` (upload) | MANAGE |
| `GET /local-source/files` (tweede ontvangstweg; bewust MANAGE, niet READ — zie D8, `docs/decisions.md` 2026-09-27) | MANAGE |
| `POST /tasks/{taskId}/deliveries/local-source` (tweede ontvangstweg, inlezen uit servermap) | MANAGE |
| `GET /deliveries/{id}`, `GET /import-links`, `GET /tasks` | READ |
| `GET /import-links/{id}/readiness` (NT-8; gereedheidscontrole zonder bestand, schrijft niets, niet achter de setup-vlag) | READ |
| `GET /setup/overview` (enige setup-pad dat nog achter de setup-vlag staat, §5) | READ |
| `GET /templates`, `/…/bookmarks`, `/…/materialisations`, `GET /links/{id}/bookmark-values` (sinds NT-3 niet meer achter de setup-vlag, §5) | READ |
| `POST /setup/**`, `PATCH /setup/revisions/{id}`, `DELETE /setup/revisions/{id}/mappings/{m}` en `/filters/{f}`, `POST /templates/{id}/materialisations`, `PUT /links/{id}/bookmark-values/{name}` (sinds NT-3 niet meer achter de setup-vlag, §5) | MANAGE |
| `POST /templates/{id}/revisions/{r}/bookmarks`, `POST /templates/{id}/revisions/{r}/bookmarks/{name}/usages` (sjabloonbeheer; blijft achter de setup-vlag, §5) | MANAGE |
| `GET /credentials`, `/credentials/{ref}`, `/credentials/{ref}/events`, `POST /credentials`, `PUT /credentials/{ref}/secret`, `POST /credentials/{ref}/revoke` (K-3; ook de GET's MANAGE, L7b — zie `leveringsconfiguratie-design.md` §6) | MANAGE |
| `GET /connection-profiles`, `/connection-profiles/{id}`, `POST /connection-profiles`, `GET /delivery-configurations`, `/delivery-configurations/{id}`, `POST /delivery-configurations`, `PUT` en `DELETE /tasks/{id}/delivery-configuration` (LC-2; ook lijst en detail MANAGE, zelfde keuze als K-3 — L7b, zie `leveringsconfiguratie-design.md` §6) | MANAGE |
| `POST /connection-profiles/host-key-scan`, `POST /connection-profile-versions/{id}/test`, `POST /delivery-configuration-versions/{id}/test` (K-4a; hostsleutelscan en verbindingstests, niet achter de setup-vlag, in tweede lijn de allowlist `catalogimport.fetch.allowed-hosts` — L4b/L7, zie `leveringsconfiguratie-design.md` §5) | MANAGE |
| `POST /tasks/{taskId}/fetch-runs` (K-4b; "Nu ophalen", synchroon, niet achter de setup-vlag, in tweede lijn de allowlist — L4b/L7a, zie `leveringsconfiguratie-design.md` §4 en §6) | MANAGE |
| `GET /tasks/{taskId}/runs`, `GET /task-runs/{runId}` (K-4b; runlijst van de laatste 20 en rundetail met waarnemingen — bestandsnamen, geen host/login/map, L7b) | READ |
| `POST /task-runs/{runId}/abort` (K-4c; vastgelopen ophaalrun afbreken, body `{reason}`, niet achter de allowlist — herstel moet werken als ophalen uitgezet is; MANAGE zoals "Nu ophalen", geen APPROVE want het is geen bundel-levenscyclusactie) | MANAGE |
| `GET /api/catalog-import/me` | geen recht (`@NoPermissionRequired`) |

`/me` is uitgezonderd: een rechtenloze gebruiker krijgt 200 met `permissions: []` in plaats van een 403 zonder uitleg.

## 2. Poort en bron

- `PermissionSource` (Web): `Set<Permission> permissionsOf(ActorIdentity actor)`; mag `PermissionSourceUnavailableException` gooien. Eén methode zonder
  tokenparameter: de latere Prodis-adapter haalt het access token zelf uit de sessie (`OAuth2AuthorizedClientRepository`).
- Lokale implementatie `ConfiguredPermissionSource`: lijst met toekenningen per username in yml:
  ```yaml
  catalogimport.permissions.grants:
    - username: jan.peeters
      rights: read, manage, approve
  ```
  Matching getrimd en hoofdletterongevoelig. **Geen wildcard, geen default, in geen enkel profiel**: lege configuratie = niemand heeft iets (fail-closed).
  Opstarten faalt als een toekenning `system` met `manage`/`approve` bevat. Het demo- en lokale profiel krijgen een expliciete toekenning.
- Prodis-adapter (5B-7, **gebouwd** 5B-7a-d): zie `docs/design/fase5-perm-5b7-design.md` (bronkeuze `catalogimport.permissions.source`, productiedefault `PRODIS`;
  fail-closed, verloren token = 401; geslaagd antwoord PT30S per sessie gecachet, beslissingslog 2026-10-22 V1 = B, wat de "nooit een gecachet antwoord" hieronder voor deze bron herziet).
  Oorspronkelijke specificatie: relayt het sessie-access-token als `Authorization: Bearer` naar Prodis `GET /api/account`, leest de codes
  `catalogImport.read/.manage/.approve`, negeert de rest; nooit een API-key, nooit `ROLE_DDA_PRODIS_API`/`hasDdaProdisApiBypass()`; per verzoek, korte timeouts; elke
  fout geeft `PermissionSourceUnavailableException` (503 `PERMISSION_SOURCE_UNAVAILABLE`), nooit een lege set en nooit een gecachet antwoord.

## 3. Check, foutcodes en volgorde

Eén `HandlerInterceptor` (`PermissionInterceptor`, geregistreerd in `config`) leest per handlermethode `@RequiresPermission(READ|MANAGE|APPROVE)` of `@NoPermissionRequired`
en gebruikt `CurrentActor.require(Permission)`; de opgehaalde set wordt per verzoek gecachet in een requestattribuut. Geen method security, geen padregels in
`SecurityConfiguration`, geen tweede autorisatielaag: `CurrentActor` blijft buiten `config` de enige lezer van de SecurityContext. Een ontbrekende annotatie is **dicht**
(403 + ERROR-log in strikte modus); een archtest dwingt af dat elke `@*Mapping`-methode in `be.dda.catalogimport.web` één van beide annotaties draagt.

Foutvolgorde (**keuze mens, 2026-09-26: recht eerst**):
1. 401 `AUTHENTICATION_REQUIRED` / 403 `CSRF_TOKEN_INVALID` (filterketen, ongewijzigd)
2. 403 `ACTOR_IDENTITY_INVALID`
3. 403 `SYSTEM_ACTOR_FORBIDDEN` (alleen bij MANAGE/APPROVE, vóór de rechtencheck)
4. 503 `PERMISSION_SOURCE_UNAVAILABLE`
5. 403 `PERMISSION_DENIED` (nieuw, vorm `{error, code}`; de boodschap noemt de ontbrekende rechtcode, nooit de bron of andermans rechten)
6. 400 `ACTOR_FIELD_MISMATCH` / `DECISION_FILTER_UNKNOWN_FIELD`
7. 404/409 (service)

Dit wijzigt de volgorde uit `fase5-auth-design.md` §13.1 (daar kwam 400 vóór 404/409 maar er was nog geen rechtencheck).

## 4. `GET /me` en Frontend

`MeView.permissions` is altijd een lijst (nooit meer `null`): de effectieve rechtcodes van de gebruiker, gesorteerd; geen rechten = `[]`; nooit bron, profiel of andermans rechten.
Bron stuk = 503 `PERMISSION_SOURCE_UNAVAILABLE`. Frontend: `useActor()` krijgt `permissions` + `can(p)`; schrijfknoppen worden uitgeschakeld mét reden (niet verborgen);
`permissions: []` = één vlak "U heeft geen rechten voor CatalogImport" zonder data-fetches; 403 `PERMISSION_DENIED` tijdens een actie toont de code en herlaadt `/me`.
Lokale dev: één regel in `application-local.yml` met de eigen Keycloak-username.

**Additief veld `apps` (productwisselaar, beslissingslog 2026-10-20 "Frontend-ombouw volgens de Prodis-mock + productwisselaar").**
`MeView.apps` is altijd een lijst `[{ "id": "prodis"|"profin"|"prodev", "url": "..." }]`, in die volgorde, zonder CatalogImport zelf;
naam en logo bepaalt de client. Regels zoals Prodis' `product-switcher.tsx`: `prodis` als `catalogimport.app-switcher.prodis-url`
gezet is; `profin` (`prodis-url + /dashboard/profin`) als de relay lukt, de gebruiker in Prodis System Admin is of `profin.access` heeft,
én `prodis-url` gezet is; `prodev` als de relay een geldige (absolute http(s)) `prodevUrl` teruggeeft. Bron: token relay van de
BFF-sessie (`AppSwitcher`/`ProdisAccountRelay`, Web) naar `catalogimport.app-switcher.prodis-account-url` (Prodis `GET /api/account`,
access token zo nodig eerst vernieuwd met het refresh token), die enkel `systemAdmin`, `permissions` (alleen `profin.access`) en
`prodevUrl` leest. **Fail-soft**, in tegenstelling tot de rechtenadapter van §2: elke fout (geen token, refresh geweigerd, timeout,
3xx/4xx/5xx, te groot antwoord, onverwachte JSON) geeft een WARN zonder token/PII en enkel de Prodis-link; `/me` blijft 200 (of 503
enkel door de rechtenbron). Per sessie gecachet (`cache-ttl` 5 min na succes, `failure-cache-ttl` 1 min na een fout, gekoppeld aan
subject + username). **Geen rechtenbron**: `permissions` blijft volledig uit `PermissionSource` komen, ook voor gebruikers zonder
CatalogImport-recht (zij krijgen `permissions: []` en toch `apps`). 5B-7 (§2) blijft een aparte stap; deze relay mag daarvoor niet
hergebruikt worden als bron van `catalogImport.*`.

## 5. Setup-API-vlag: enkel nog voor overzicht en sjabloonbeheer (keuze mens 2026-09-26, herzien 2026-09-30)

**Stand sinds NT-3 (beslissingslog 2026-09-30 "Nieuwe leverancier + taak (NT-spoor)", V2 = a; gedeeltelijke herroeping van 2026-09-26).**
Een gebruiker met `MANAGE` moet zelf een leverancier en een taak kunnen inrichten. Daarom staan de schrijfpaden van de inrichting
(bronorganisatie, definitie, revisie, `PATCH` revisie, mappings/filters/kritiekheid, activeren, opvolger, koppeling, taak), sjablonen lezen +
materialiseren en de bookmarkwaarden van een koppeling **niet meer** achter `catalogimport.setup-api.enabled`: `CatalogImportSetupController`,
`CatalogImportTemplateController` en `CatalogImportLinkController` bestaan altijd, met dezelfde paden, bodies en statuscodes; het recht
(`MANAGE` schrijven, `READ` lezen) is hun enige slot (403 `PERMISSION_DENIED` zonder recht).

**Achter de vlag blijven** enkel `GET /setup/overview` (`CatalogImportSetupOverviewController`) en het sjabloonbeheer — bookmarks en hun usages
declareren (`CatalogImportTemplateDeclarationController`). Voor die drie paden geldt de oorspronkelijke regel van 2026-09-26 nog: met de vlag uit
bestaan ze niet (404, ongeacht de rechten — uitgezonderd `POST /templates/{id}/revisions/{r}/bookmarks`, dat 405 geeft omdat `GET` op hetzelfde
pad sinds NT-3 altijd bestaat; ook dan is er geen handler en wordt er niets geschreven); met de vlag aan is het recht vereist. De vlag blijft een config-property en wordt niet uit
`application*.yml`, README en handleiding verwijderd.

Tests: `SetupApiDisabledTest` (vlag uit: de verplaatste paden geven 401/403/2xx, de drie overgebleven paden 404 ook mét alle rechten),
`PermissionReadEndpointsHttpTest`/`PermissionWriteEndpointsHttpTest` (draaien met de vlag uit), `SetupApiFlagOnlyPermissionHttpTest` (vlag aan +
zonder recht = 403 voor de drie overgebleven paden).
## 6. Audit

Een geweigerde poging wordt gelogd, niet bewaard: één WARN-regel `PERMISSION_DENIED user=<username> required=<code> <METHOD> <padtemplate>`. Geen subject, body of querystring.

## 7. Testen

Standaardtestlogin (`TestActors.as(username)`) krijgt alle drie de rechten via de claim `catalogimport_permissions` (keuze mens: bestaande ~300 aanroepen blijven ongewijzigd); nieuwe overload
`as(username, Permission…)` voor exact de opgegeven set. Per endpointfamilie: zonder recht 403 `PERMISSION_DENIED` én niets geschreven; met recht ongewijzigd; `system` op MANAGE/APPROVE 403 vóór de rechtencheck;
`system` met `.read` 200 op een GET; volgorde (geen recht + verkeerde naam = 403; geen recht + onbekende bundel = 403); hiërarchie (`APPROVE` alleen mag lezen en beheren, `MANAGE` alleen mag lezen maar niet goedkeuren);
`/me` zonder rechten 200 met `[]`; archtest "elke handler geannoteerd"; interceptor zonder annotatie = 403 + ERROR-log.

## 8. Bouwstappen

| Stap | Doel | Trap | Gerichte testklassen |
|---|---|---|---|
| 5B-1 | Fundament + kleinste slice: `Permission`, hiërarchie, `PermissionSource`, `ConfiguredPermissionSource`, `PERMISSION_DENIED`/`PERMISSION_SOURCE_UNAVAILABLE`, `CurrentActor.require`, `PermissionInterceptor` (soepele modus), `TestPermissionSource`/`TestActors`; annoteer de APPROVE-endpoints van bundels (freeze/cancel/decisions/approve/reject) | zwaar | `PermissionHttpTest` (nieuw), `FreezeActorHttpTest`, `BundleActorHttpTest`, `BundleHttpTest`, `BundleGroupDecisionHttpTest` |
| 5B-2 | Overige schrijfendpoints annoteren (upload, continue, accept-baseline, bundel aanmaken/batches, setup, templates, link-PUT) | gemiddeld | `UploadBaselineActorHttpTest`, `BatchBaselineHttpTest`, `DeliveryUploadTest`, `SetupApiFlowTest`, `SetupTemplateLinkActorHttpTest`, `TemplateBookmarkDeclarationTest`, `LinkBookmarkValueTest` |
| 5B-3 | Alle GET's annoteren, `/me` uitzonderen, interceptor naar strikte modus + archtest; de setup-vlag **blijft** (§5) en krijgt extra tests | gemiddeld | `PermissionCoverageTest` (nieuw), `CatalogImportTaskHttpTest`, `PsimportPreviewHttpTest`, `MutationReasonFilterHttpTest`, `MutationIdentityHashHttpTest`, `SecurityHttpTest`, setup-tests |
| 5B-4 | `/me.permissions` vullen (effectieve set, altijd lijst), 503-gedrag | licht | `SecurityHttpTest`, `PermissionHttpTest` |
| 5B-5 | Frontend: `permissions`/`can()`, knoppen uitgeschakeld-met-reden, leeg-rechtenvlak, codes | gemiddeld | `npm test -- --run`, `npm run build`, `tsc -p tsconfig.app.json` |
| 5B-6 | README, handleiding, acceptance-doc, scenario | licht | n.v.t. |
| 5B-7 | Prodis-adapter: **gebouwd** (5B-7a t/m 5B-7d, zie `fase5-perm-5b7-design.md`; 5B-7e optioneel) | zwaar | `ProdisPermissionSourceTest` (stub, geen netwerk) |

Volledige ronde na 5B-3 en na 5B-5 via `scripts/test/run-full-tests.ps1`; klassen los draaien met `-Tests`.

> **Important technical constraint discovered**
>
> Spring Boot bindt `Map<String,String>`-properties met een punt in de sleutel (`jan.peeters`) alleen met bracket-notatie; zonder brackets wordt de sleutel op de punt gesplitst en verdwijnt de
> toekenning stilzwijgend. Bij een rechtenbron is dat onzichtbaar gedrag. Daarom is de configuratievorm een **lijst** met een `username`-veld.

> **Important technical constraint discovered**
>
> Een controller met `@ConditionalOnProperty` op `catalogimport.setup-api.enabled` bestaat als bean alleen met de vlag aan. Een rechtencheck kan daar dus nooit een 403 geven wanneer de vlag uit staat
> (dan 404). Die volgorde "vlag vóór recht" is een gevolg van de beanconditie. **Sinds NT-3 geldt ze enkel nog voor `CatalogImportSetupOverviewController` en `CatalogImportTemplateDeclarationController`**
> (§5); voor de verplaatste inrichtings-, materialisatie- en bookmarkwaardepaden is er geen vlag meer en komt het recht (403) als eerste. Tests leggen beide gevallen apart vast.