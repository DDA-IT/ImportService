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
| `POST /tasks/{taskId}/deliveries` (upload) | MANAGE |
| `GET /local-source/files` (tweede ontvangstweg; bewust MANAGE, niet READ — zie D8, `docs/decisions.md` 2026-09-27) | MANAGE |
| `POST /tasks/{taskId}/deliveries/local-source` (tweede ontvangstweg, inlezen uit servermap) | MANAGE |
| `GET /deliveries/{id}`, `GET /import-links`, `GET /tasks` | READ |
| `GET /setup/overview`, `GET /templates`, `/…/bookmarks`, `/…/materialisations`, `GET /links/{id}/bookmark-values` | READ |
| `POST /setup/**`, `POST /templates/**`, `PUT /links/{id}/bookmark-values/{name}` | MANAGE |
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
- Prodis-adapter (5B-7, specificatie; extern geblokkeerd): relayt het sessie-access-token als `Authorization: Bearer` naar Prodis `GET /api/account`, leest de codes
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

## 5. Setup-API-vlag blijft als echte beveiliging (keuze mens, 2026-09-26, herzien)

De vlag `catalogimport.setup-api.enabled` en de `@ConditionalOnProperty` op `CatalogImportSetupController`, `CatalogImportTemplateController` en `CatalogImportLinkController` **blijven**
en gelden als een tweede, onafhankelijke beveiliging naast `MANAGE`/`READ` (dubbele bescherming). Een eerdere keuze om de vlag te laten vervallen is op 2026-09-26 door de mens herroepen.
Gevolgen: met de vlag uit bestaan die endpoints niet (404, ongeacht de rechten); met de vlag aan is `MANAGE` (schrijven) of `READ` (lezen) vereist (403 `PERMISSION_DENIED` zonder recht).
De vlag is geen tijdelijke schakelaar meer: ze wordt niet uit `application*.yml`, README en handleiding verwijderd, en `SetupApiDisabledTest` blijft bestaan (vlag uit = 404, ook mét `.manage`).
Er komen extra tests bij: vlag aan + zonder recht = 403; vlag aan + met recht = ongewijzigd gedrag.
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
| 5B-7 | Prodis-adapter (eerst een eigen Denker-stap; extern geblokkeerd op audience `account` en de seed van de drie rechten in Prodis) | zwaar | `ProdisPermissionSourceTest` (stub, geen netwerk) |

Volledige ronde na 5B-3 en na 5B-5 via `scripts/test/run-full-tests.ps1`; klassen los draaien met `-Tests`.

> **Important technical constraint discovered**
>
> Spring Boot bindt `Map<String,String>`-properties met een punt in de sleutel (`jan.peeters`) alleen met bracket-notatie; zonder brackets wordt de sleutel op de punt gesplitst en verdwijnt de
> toekenning stilzwijgend. Bij een rechtenbron is dat onzichtbaar gedrag. Daarom is de configuratievorm een **lijst** met een `username`-veld.

> **Important technical constraint discovered**
>
> De setup-, template- en linkcontrollers bestaan als bean alleen met `catalogimport.setup-api.enabled=true` (`@ConditionalOnProperty`). Een rechtencheck kan daar dus nooit een 403 geven wanneer de vlag uit staat
> (dan 404). Die volgorde "vlag vóór recht" is een gevolg van de beanconditie en is bewust behouden (§5): de vlag is de buitenste beveiliging, het recht de binnenste. Tests moeten beide gevallen apart vastleggen.