# 5B-7 — Prodis-adapter als rechtenbron

> **Status: VOORSTEL (2026-10-22), denker-zwaar.** Verfijnt `fase5-perm-design.md` §2 (r.55-57), §4 (r.93-96) en de bouwtabel
> (r.137). Open bij de mens: **V1–V4** (§7). 5B-7a t/m 5B-7c kunnen gebouwd worden met de huidige bindende keuzes; 5B-7d wacht op V2.

## 1. Huidige toestand
- **Login** via de Prodis-realm met client `web_app` werkt (`application-local.yml:14-18`); tokens staan in de sessie
  (`SecurityConfiguration.java:90-93`).
- **`ProdisAccountRelay`** doet al het transport naar Prodis `GET /api/account`: access token uit de sessie met refresh
  (r.115-126, 251-269), Bearer, geen redirects, maximale body, korte timeouts (r.128-145, 232-243), WARN zonder token (r.212-225).
  Fail-soft en sessiecache zitten in `AppSwitcher` (r.87-111).
- **Rechten** komen vandaag enkel uit `ConfiguredPermissionSource` (YAML); `CurrentActor.effectivePermissions` cachet per request (r.180-195).
- **Externe blokkades opgelost in Prodis-code:**
  - Seed `catalogImport.read/.manage/.approve`: `Dao/.../changelog/20261020120000_seed_catalog_import_rights.xml` (in `master.xml:542`
    en `UserManagementDefaultsInitializer.java:155-157`), gecommit op Prodis `dev` (`6e617bd73`, geverifieerd door de hoofdsessie).
  - Audience: Prodis aanvaardt `account` en `api://default` (`prodis/Web/.../config/application.yml:229-231`,
    `SecurityConfiguration.java:275-282`); in `prodis-realm.json` draagt het `web_app`-token `aud: account` via de standaardrol
    `default-roles-jhipster` (r.77-85, 399) en de scope `roles` met audience-resolve (r.754, 1206-1213). Lokaal dus aanvaard
    (handmatig te bevestigen; bij fout logt Prodis `Invalid audience`).
  - Nog nodig: een Prodis-rol met `catalogImport.*` toekennen aan gebruikers (beheerdata); productierealm (§6).

## 2. `ProdisPermissionSource` (Web)
- **Hergebruik transport, niet de semantiek.** Het verbod in fase5-perm r.95-96 gaat over fail-soft/cache/`Optional.empty()`.
  Trek het transport uit `ProdisAccountRelay` in een package-private `ProdisAccountClient` (geparste JSON-root of getypte fout met
  veilige reden). `ProdisAccountRelay` blijft de fail-soft-wrapper (gedrag en tests ongewijzigd); `ProdisPermissionSource` is een
  fail-closed-wrapper en gebruikt nooit `AppSwitcher` of diens cache.
- **`permissionsOf(actor)`:**
  - request/response uit `RequestContextHolder`; buiten een request → `PermissionSourceUnavailableException`;
  - principal via `OAuth2AuthorizeRequest.principal(actor.username())` (geen lezing van de SecurityContext, geen cyclus met `CurrentActor`);
  - strikte parsing: `permissions` moet een array van strings zijn; ontbreekt/`null`/verkeerd type → 503, nooit een lege set
    (`AdminUserDTO` zet het veld altijd, `AccountResource.java:77-79`);
  - mapping enkel op **exacte** volledige code (`Permission.code().equals(trimmed)`), niet via `Permission.parse`; rest genegeerd;
    hiërarchie blijft in `Permission.effective` (approve ⊃ manage ⊃ read);
  - `systemAdmin` niet gelezen (Prodis geeft System Admin al alle codes, `PermissionService.java:124-126`; zie V3).
- **Fouten → altijd 503 `PERMISSION_SOURCE_UNAVAILABLE`** (tenzij V4 = B): geen geautoriseerde client, geweigerde refresh,
  timeout, verbindingsfout, 3xx/4xx/5xx, te grote body, geen JSON.
- **Timeouts:** `catalogimport.permissions.prodis.connect-timeout`/`read-timeout`, default PT2S, strikt positief.
- **Logging:** één WARN `Permission source (Prodis) unavailable (<reden>)` met enkel klassenaam/foutcode/HTTP-status; nooit token,
  body of username.
- **Tokenvernieuwing:** bestaande manager; access token leeft 300 s (`prodis-realm.json:12`).
- **Cache:** per request (bestaat); per sessie enkel bij V1 = B.

## 3. YAML ↔ Prodis
- Unie afgeraden (twee bronnen van waarheid, tegen 2026-09-18).
- **Selecteerbaar:** `catalogimport.permissions.source` = `CONFIGURED|PRODIS` (onbekend = opstartfout); precies één
  `PermissionSource`-bean; `PRODIS` zonder `catalogimport.permissions.prodis.account-url` (`${CATALOG_PRODIS_ACCOUNT_URL:}`) =
  opstartfout; `PRODIS` met niet-lege `grants` → WARN "grants genegeerd".
- Tests: `TestPermissionSource` (`@Primary`) blijft, met een uitschakelvlag zodat één HTTP-test de echte adapter gebruikt.
- Demo/lokaal zonder Prodis: `CONFIGURED`; lokaal met Prodis: opt-in `CATALOG_PERMISSION_SOURCE=PRODIS`. Default: V2.

## 4. Technische client en `system`
Onaangeroerd: `svc:` via `CurrentActor.technicalClient` (r.211-232) en `PermissionInterceptor` (r.70), zonder `PermissionSource`;
`system` krijgt `SYSTEM_ACTOR_FORBIDDEN` vóór de bron bevraagd wordt (`CurrentActor.java:156-159`).

## 5. Frontend
`permissions`/`can()` en 503-afhandeling (`ActorContext.tsx:206`, `codes.ts:257`) ongewijzigd. De lege-rechtentekst (`App.tsx:24`)
noemt de bron niet (`/me` mag die niet prijsgeven, fase5-perm §4); optioneel neutrale toevoeging "Vraag uw beheerder om rechten toe
te kennen." Bij V4 = B volstaat de bestaande 401-afhandeling (`http.ts:128-129`).

## 6. Bouwvolgorde (strikt sequentieel)

| Slice | Inhoud | Trap | Gerichte test |
|---|---|---|---|
| 5B-7a | `ProdisAccountClient` uittrekken; relay = fail-soft-wrapper, gedrag identiek | gemiddeld | `mvn -pl Web -am test -Dtest=ProdisAccountRelayTest,MeAppSwitcherHttpTest,AppSwitcherTest -Dsurefire.failIfNoSpecifiedTests=false` |
| 5B-7b | `source`-enum + `prodis.*`-properties met validatie, beanselectie, `ProdisPermissionSource` | zwaar | `-Dtest=ProdisPermissionSourceTest,ConfiguredPermissionSourceTest,PermissionHierarchyTest` (stub `FakeProdisServer`) |
| 5B-7c | HTTP-integratie met `source=PRODIS`, `TestPermissionSource` uit | gemiddeld | `-Dtest=ProdisPermissionHttpTest,MePermissionSourceUnavailableHttpTest,PermissionHttpTest,PermissionCoverageTest,SecurityHttpTest` |
| 5B-7d | yml-profielen, README/handleiding, fase5-perm §2 + r.137, extern punt 2 doorhalen | licht | n.v.t. |
| 5B-7e | (optioneel) neutrale lege-rechtentekst | licht | `npm test -- --run`, `tsc -p tsconfig.app.json` |

**Acceptatie 5B-7b:** exacte code toegekend (`approve`, `read`, `CATALOGIMPORT.READ` genegeerd); ontbrekend/verkeerd type → 503;
geweigerde refresh/geen client/timeout/401/500/302/te grote body → 503 zonder token in de log; verlopen token eerst ververst; geldige
lege lijst → lege set; twee aanroepen → twee requests (bij V1 = A).
**Acceptatie 5B-7c:** `/me` toont de set uit Prodis; Prodis down → 503, nooit 403; enkel read → POST 403; approve → lezen en beheren
mogen; `system` bij MANAGE → 403 zonder Prodis-call; hoogstens één Prodis-call per verzoek; `svc:`-push ongewijzigd.
**Handmatige eindtest (mens):** Prodis-backend en Keycloak draaien, `CATALOG_PERMISSION_SOURCE=PRODIS`; gebruiker met Prodis-rol met
`catalogImport.read` krijgt read; intrekken in Prodis geldt bij het volgende verzoek (of na de TTL bij V1 = B).
**Prodis-repo:** `PermissionRightCatalogIT` draaien; rol met `catalogImport.*` toekennen; geen codewijziging.
**Keycloak:** lokaal niets. Productierealm: de CatalogImport-client draagt `aud: account`, bij voorkeur via een expliciete
`oidc-audience-mapper` (included.client.audience=`account`); refresh tokens aan; dezelfde publieke issuer-host als Prodis.

## 7. Vragen voor de mens
- **V1 — Per verzoek of korte sessiecache?** Prodis `GET /api/account` schrijft bij elke call (D1) en de SPA vuurt parallelle GET's.
  A: per verzoek (nu bindend); B: enkel geslaagde antwoorden PT30S per sessie cachen (subject + username), fouten nooit.
  **Aanbeveling B** (intrekking hoogstens 30 s).
- **V2 — Default van `source` in `application.yml`.** A: `PRODIS` (productie start niet zonder `CATALOG_PRODIS_ACCOUNT_URL`);
  B: `CONFIGURED` (zonder grants iedereen 403). Demo/local/ewoutdev blijven `CONFIGURED`. **Aanbeveling A.**
- **V3 — System Admin in Prodis krijgt alle `catalogImport.*`.** Enige consistente uitkomst zonder Prodis-wijziging
  (`PermissionService.java:124-126`, `UserManagementDefaultsInitializer.java:258-260`). **Aanbeveling: bevestigen.**
- **V4 — Verloren token (geweigerde refresh, geen client).** A: 503 (gebruiker blijft op "Opnieuw proberen"); B: 401
  `AUTHENTICATION_REQUIRED` (SPA meldt "sessie verlopen" en laat opnieuw inloggen). **Aanbeveling B** (blijft fail-closed; contractkeuze).

## 8. Ontdekkingen
- *Technisch:* Prodis `GET /api/account` is niet alleen-lezen: `UserService.getUserFromAuthentication` (`@Transactional`,
  `UserService.java:146-171`) roept `syncUserWithIdP` (r.98-137) aan en schrijft `jhi_user`/authorities, vaak bij elke call. Bij de
  eerste parallelle calls van een nieuwe gebruiker kan een unieke-sleutelrace 500 geven (bij ons 503).
- *Business:* een System Admin of JWT met realmrol `realm-admin` heeft in Prodis alle geseede codes (`PermissionService.java:110-126`),
  dus in CatalogImport `approve` en via de hiërarchie alles.
- *Business:* Prodis zoekt de `prodis_user` voor een bearer-token enkel op `sub` of e-mail (`PermissionService.java:278-282`), niet op
  `preferred_username`; een gedeactiveerde gebruiker (r.432-434) krijgt `permissions: []` → geen-rechtenvlak in CatalogImport.
- *Technisch:* `Permission.parse` (`Permission.java:78-92`) aanvaardt korte namen hoofdletterongevoelig; voor een externe bron onveilig
  → exacte match.
- *Technisch:* `web_app` is een public client (`prodis-realm.json:730`); `aud: account` komt enkel uit standaardrol + scope `roles`. Een
  productieclient zonder die rol/scope wordt door Prodis geweigerd (`AudienceValidator.java:24-31`). Access token 300 s, SSO-idle 1800 s:
  een Prodis-/Keycloak-logout beëindigt de CatalogImport-rechten bij de volgende refresh.
- *Technisch:* `PermissionSource.permissionsOf` heeft geen request/response; de adapter haalt ze uit `RequestContextHolder`. De
  standaardclient `catalog-import` en `prodis-datasync-catalogimport` bestaan niet in de Prodis-realm-export.
