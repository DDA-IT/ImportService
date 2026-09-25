# Fase 5-AUTH — Keycloak-login (BFF) en geverifieerde identiteit

**Status:** bindend (denker-zwaar-ontwerp 2026-09-25; §9 V1/V2 en gevolg G1 door de mens beantwoord op 2026-09-25,
zie `docs/decisions.md` "Fase 5-AUTH: ontwerp bindend").
**Bindende bronnen:** `docs/decisions.md` 2026-09-25 "Fase 5: opknipping, volgorde en vier beslissingen" (Q1, Q2; Q3/A2 =
5-PERM), 2026-09-20 "vier-ogen NOOIT vereist", 2026-09-23 frontend-slice 1 (Q1 actornaam, Q2 bereikbaarheid), 2026-09-23
D14 (lezen vs. schrijven); `docs/design/frontend-scherm3-bundel-design.md` §2-§7. Referentiepatroon:
`Prodis/Web/.../config/SecurityConfiguration.java`, `Prodis/Web/src/main/resources/config/application.yml:150-160`,
`Prodis/Web/src/test/java/.../config/TestSecurityConfiguration.java`.

**Voldoende gespecificeerd:** loginvorm (BFF, `oauth2Login`, sessiecookie), identiteitsmodel (username in `*_by`, `sub` in
`*_by_subject`, geen hernoeming, geen actortabel), actorveldcontract (optioneel; indien aanwezig gelijk aan identiteit,
anders 400 `ACTOR_FIELD_MISMATCH`), `system` blijft verboden.
**In dit ontwerp ingevuld:** welke tabellen "ondertekend" zijn (§1.2, G1), lokaal werken zonder bypass (§9 V1),
intrekkingstermijn (§9 V2), 401/403-codes en vorm, hoe de Service de actor krijgt.

---

## 1. Inventaris

### 1.1 Schrijfendpoints en hun actorveld

| Endpoint | Controller:regel | Actorveld (request) | Service-validatie |
|---|---|---|---|
| `POST /tasks/{taskId}/deliveries` (multipart) | `CatalogImportDeliveryController.java:77-101` | `uploadedBy` (`@RequestParam`, **verplicht**, r.81) | `DeliveryIntakeService.java:119` `requireText` (**laat `system` toe**, C5) |
| `POST /batches/{id}/accept-baseline` | `CatalogImportBatchController.java:196-200` (record r.46) | `acceptedBy` | `SourceStateBaselineService.java:191` |
| `POST /batches/{id}/continue` | `CatalogImportBatchController.java:207-210` | **geen** | geen actor bewaard (beslissing V2, 2026-09-23) |
| `POST /bundles` | `CatalogImportBundleController.java:186-190` (r.77-78) | `createdBy` | `PublicationBundleService.java:148` |
| `POST /bundles/{id}/batches` | `…BundleController.java:215-218` (r.82) | `addedBy` | `PublicationBundleService.java:200` |
| `POST /bundles/{id}/batches/{b}/remove` | `…BundleController.java:221-225` (r.86) | `removedBy` | `PublicationBundleService.java:248` |
| `POST /bundles/{id}/mutations/{m}/approve` | `…BundleController.java:276-281` (r.93) | `decidedBy` | `BundleDecisionService.java:471` |
| `POST /bundles/{id}/mutations/{m}/reject` | `…BundleController.java:284-289` (r.93) | `decidedBy` | idem |
| `POST /bundles/{id}/decisions` | `…BundleController.java:308-323` (r.107; whitelist r.130) | `decidedBy` | `BundleDecisionService.java:301` |
| `POST /bundles/{id}/freeze` | `…BundleController.java:341-345` (r.116) | `frozenBy` | `BundleFreezeService.java:197` |
| `POST /bundles/{id}/cancel` | `…BundleController.java:369-373` (r.124) | `cancelledBy` | `BundleCancellationService.java:135` |
| `POST /setup/definitions` (vlag) | `CatalogImportSetupController.java:78-82` | geen; service zet `"setup-api"` (`SetupService.java:97,291`) | – |
| `POST /setup/definitions/{id}/revisions` (vlag) | `…SetupController.java:85-90` | `createdBy` (`SetupService.java:129`, default `setup-api` r.316) | `requireText` |
| `POST /setup/revisions/{id}/activate` (vlag) | `…SetupController.java:97-101` (r.63) | `approvedBy` (optioneel, default `setup-api` r.453) | `requireText` |
| `POST /setup/revisions/{id}/mappings`, `/filters`, `/field-criticality` (vlag) | `…SetupController.java:103-122` | `createdBy` (`SetupService.java:142,148,152`; default r.506, 546, 582) | geen |
| `POST /setup/source-organisations`, `/links`, `/tasks` (vlag) | `…SetupController.java:72-76, 124-134` | geen | geen `*_by`-kolommen |
| `POST /templates/{d}/revisions/{r}/bookmarks` (vlag) | `CatalogImportTemplateController.java:95-101` | `createdBy` (`TemplateBookmarkService.java:71`, default r.63/224) | geen |
| `POST /templates/{d}/…/bookmarks/{n}/usages` (vlag) | `…TemplateController.java:104-110` | geen | geen `*_by`-kolom |
| `POST /templates/{d}/materialisations` (vlag) | `…TemplateController.java:136-141` | `materialisedBy` (`TemplateMaterialisationService.java:222`) | r.539 `requireActorName` |
| `PUT /links/{id}/bookmark-values/{name}` (vlag) | `CatalogImportLinkController.java:75-81` (r.52) | `updatedBy` | `LinkBookmarkValueService.java:189` |

### 1.2 Kolommen `*_by` en welke een `*_by_subject` krijgen

| Tabel.kolom | Changeset:regel | Gevuld door | `*_by_subject` |
|---|---|---|---|
| `publication_bundle.created_by` / `frozen_by` / `cancelled_by` | 005:33 / 35 / 38 | bundel aanmaken, bevriezen, annuleren | **ja** |
| `publication_bundle_batch.added_by` / `removed_by` | 005:89 / 91 | batches toevoegen/verwijderen | **ja** |
| `publication_decision.decided_by` | 005:140 | elke beslissing, ook `FREEZE`/`AUTO_APPROVE_PLANNED`/`CANCEL` (`BundleFreezeService.java:382,405`) | **ja** |
| `import_batch.created_by` | 002:81 | upload (`DeliveryIntakeService.java:193`) | **ja** |
| `import_batch.baseline_accepted_by` | 003:10 | accept-baseline | **ja** |
| `import_definition.created_by` | 001:27 | setup, materialisatie | **ja** (G1) |
| `import_definition_revision.created_by` / `approved_by` | 001:60 / 63 | setup, activeren, materialisatie | **ja** (G1) |
| `import_field_mapping.created_by` | 004:213 | setup, materialisatie | **ja** (G1) |
| `import_record_filter.created_by` | 004:272 | idem | **ja** (G1) |
| `import_revision_field_criticality.created_by` | 004:835 | idem | **ja** (G1) |
| `import_definition_bookmark.created_by` | 006:56 | declareren, materialisatie | **ja** (G1) |
| `import_definition_bookmark_value.filled_by` | 006:164 | materialisatie | **ja** (G1) |
| `import_link_bookmark_value.filled_by` / `updated_by` | 006:204 / 206 | materialisatie, bookmark-PUT | **ja** (G1) |
| `import_mutation.decided_by` | 005:176 | JDBC-kopie (`PublicationBundleDao.java:253,319,644`) | **nee** — via `decision_id` → `publication_decision` |
| `catalog_source_state.accepted_by` | 002:173 | bulkkopie bij accept-baseline | **nee** — via `last_change_batch_id` → `import_batch` |
| `catalog_price_observation.accepted_by` | 004:503 | bulkkopie | **nee** — via `batch_id` |
| `catalog_reference_state.accepted_by` | 004:585 | bulkkopie | **nee** — geen batch-FK, zie C9 |
| `task_run.triggered_by` | 001:125 | kopie uploader (`DeliveryIntakeService.java:170`) | **nee** — via `import_batch.task_run_id` |
| `task_run.locked_by` | 001:123 | nooit gezet | **nee** — technisch, geen actor |

**G1 (door de mens bevestigd):** Q1 zegt "oude rijen blijven `NULL` = vóór Fase 5, niet geverifieerd". Dat is alleen waar
als **elke** kolom die na 5-AUTH uit een geverifieerde requestactor gevuld wordt, ook een subject krijgt — dus ook de
configuratietabellen (de setup-/materialisatie-API vereist na 5A-1 ook login). Bulkkopieën krijgen geen kolom: de
ondertekeningsrij waarnaar ze verwijzen draagt het subject al, en zonder kolom is er geen `NULL` die iets anders betekent.
`NULL` betekent na 5-AUTH exact: "geen geverifieerde identiteit" (rijen vóór Fase 5, `DemoDataSeeder`
(`CREATED_BY = "demo-seeder"`, `DemoDataSeeder.java:58`), directe Service-aanroepen in tests).

---

## 2. Security-configuratie (Web)

### 2.1 Dependencies (`Web/pom.xml`)
- **`spring-boot-starter-oauth2-client`** (bevat `spring-security-config/-core/-oauth2-client/-oauth2-jose`; `-web`
  transitief). Voldoende voor BFF + `oauth2Login`.
- **Niet** `spring-boot-starter-oauth2-resource-server`: de BFF aanvaardt geen bearer-tokens → één ingang, geen
  audience-validatie op inkomende tokens. (Prodis heeft die tweede ingang, `SecurityConfiguration.java:189`; voor
  CatalogImport is dat een aparte, latere beslissing — §9 V1.)
- `spring-boot-starter-security` apart toevoegen is overbodig.
- **`spring-security-test`**, scope `test`.
- Versies uit de Boot-BOM (3.4.5, `pom.xml:9`).
- **Service/Dao/Domain-poms ongewijzigd** — compile-time garantie dat Service niet aan Spring Security gekoppeld raakt.

### 2.2 Filterketen — één `SecurityFilterChain` in `be.dda.catalogimport.config.SecurityConfiguration`

| Onderdeel | Keuze |
|---|---|
| Autorisatie | `DispatcherType.ERROR` → permitAll; `GET /actuator/health`, `/actuator/info` → permitAll; `/api/**` → `authenticated()`; `anyRequest()` → `authenticated()`. Geen per-actie-autorisatie (= 5-PERM). |
| Entry points | `/api/**` → **401 JSON** `{"error":"Authentication required","code":"AUTHENTICATION_REQUIRED"}`, geen redirect. Al het andere → `LoginUrlAuthenticationEntryPoint("/oauth2/authorization/keycloak")`. |
| Access denied (`/api/**`) | 403 JSON; bij CSRF-fout `code = CSRF_TOKEN_INVALID`, anders `ACCESS_DENIED`. |
| `oauth2Login` | registratie `keycloak`; `defaultSuccessUrl("/", true)`; `authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())` — tokens leven en sterven met de sessie (haakje voor 5-PERM-token-relay, C11). |
| Request cache | `NullRequestCache` (C2). |
| CSRF | `CookieCsrfTokenRepository.withHttpOnlyFalse()` (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`); request-handler = Prodis-`SpaCsrfTokenRequestHandler` (`SecurityConfiguration.java:270-306`) met één afwijking: token **alleen uit de header**, nooit uit een requestparameter (C3). |
| Logout | `POST /api/catalog-import/logout` (met CSRF). Sessie invalideren, `JSESSIONID` wissen. Success-handler (subklasse van `OidcClientInitiatedLogoutSuccessHandler`, `postLogoutRedirectUri = "{baseUrl}/"`) schrijft **200 JSON** `{"logoutUrl": "<end_session_endpoint>?id_token_hint=…&post_logout_redirect_uri=…"}` in plaats van een 302 (een fetch kan geen cross-origin redirect volgen). |
| Sessie | default `IF_REQUIRED`; session fixation → `changeSessionId`. |
| Uit | CORS, `httpBasic`, `formLogin`, `oauth2ResourceServer`. Standaardheaders aan. CSP pas bij statische SPA-uitlevering. |
| Fail-fast | Bij opstarten weigeren als registratie `keycloak` ontbreekt of een lege/onopgeloste (`${…`) client-secret heeft. |

`ApiExceptionHandler` vangt geen fouten op filterniveau; entry point en access-denied-handler schrijven daarom zelf JSON in
exact de vorm `{error, code}` van `ApiExceptionHandler.java:13-15`.

### 2.3 Properties (secret en issuer altijd via env, nooit in de repo)

`application.yml` (additief):
```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            provider: keycloak
            client-id: ${CATALOG_OIDC_CLIENT_ID}
            client-secret: ${CATALOG_OIDC_CLIENT_SECRET}
            authorization-grant-type: authorization_code
            scope: openid, profile, email
        provider:
          keycloak:
            issuer-uri: ${CATALOG_OIDC_ISSUER_URI}
            user-name-attribute: preferred_username
server:
  servlet:
    session:
      timeout: ${CATALOG_SESSION_TIMEOUT:30m}
      cookie: { http-only: true, same-site: lax, secure: ${CATALOG_SESSION_COOKIE_SECURE:true} }
```
`application-local.yml`: `client-id: ${CATALOG_OIDC_CLIENT_ID:catalog-import}`,
`issuer-uri: ${CATALOG_OIDC_ISSUER_URI:http://localhost:9080/realms/prodis}` (lokale Prodis-realm, Prodis
`application.yml:155`), `server.servlet.session.cookie.secure: false`. Het **secret krijgt nergens een default** — lokaal
zet je `CATALOG_OIDC_CLIENT_SECRET`.

`same-site: lax` en niet `strict`: de Keycloak-callback is een top-level GET-navigatie en moet de sessiecookie meedragen.

Vereist in Keycloak (extern, door de mens): client `catalog-import` (confidential, standard flow).
- Redirect-URI's: `http://localhost:8081/login/oauth2/code/keycloak`, `http://localhost:5173/login/oauth2/code/keycloak`.
- Post-logout-URI's: `http://localhost:8081/`, `http://localhost:5173/` (plus productie-URL's).

### 2.4 Lokaal/demo zonder echte Keycloak
**Geen** `local-noauth`-profiel en geen andere omzeiling (§9 V1 = A1). Lokaal draait altijd de Prodis-Keycloak. De
geautomatiseerde tests werken zonder Keycloak via een testconfiguratie die niet in het artefact zit (§7). Het
`demo`-profiel vereist na 5A-1 ook login; `DemoDataSeeder` roept services rechtstreeks aan en blijft werken.

---

## 3. `CurrentActor` — één plek, Service blijft los van Spring Security

**Service** (`be.dda.catalogimport.service.ActorIdentity`, public record):
- `ActorIdentity(String username, String subject)`, met `static unverified(String username)` → `subject = null`.
- `subject` is, indien niet `null`, niet blanco en ≤ 255 tekens.
- De Service blijft de laatste verdedigingslinie: de bestaande `ActorNames.requireActorName` wordt toegepast op `username`.

**Web** (`be.dda.catalogimport.web.CurrentActor`, `@Component`) — de **enige** klasse buiten `config` die
`SecurityContextHolder` leest.
- `ActorIdentity current()`
  - Verwacht `OAuth2AuthenticationToken` met `OidcUser`.
  - `subject` uit `getSubject()`, `username` uit claim `preferred_username`, getrimd.
  - **403 `ACTOR_IDENTITY_INVALID`** als een van beide ontbreekt/blanco is, als username > 100 tekens (`*_by` is
    `varchar(100)`; nooit afkappen) of subject > 255 tekens.
  - Zonder authenticatie: 401 via de entry point (defensief; de keten laat dit niet door).
- `ActorIdentity signer(String requestValue, String fieldName)`
  1. `current()`.
  2. username gelijk aan `system` (hoofdletterongevoelig) → **403 `SYSTEM_ACTOR_FORBIDDEN`**.
  3. `requestValue` niet `null`/blanco en `trim()` ≠ username (hoofdletterongevoelig) → **400 `ACTOR_FIELD_MISMATCH`** via
     de bestaande `BadRequestException(code, …)`. De boodschap noemt enkel de veldnaam, geen waarden.
  4. Retourneert de identiteit. **Bewaard wordt altijd de token-username, nooit de spelling uit het request.**
- 403-fouten via nieuwe Web-exceptie `ActorNotAllowedException(code, message)` + additieve handler in `ApiExceptionHandler`
  (403 `{error, code}`). 5-PERM hergebruikt die vorm.

**Waar de controle gebeurt:** in elke schrijfhandler uit §1.1, als eerste Service-gerelateerde stap. Bij `decideGroup` ná
de bestaande C6-whitelist (`…BundleController.java:311`). `continue` roept alleen `current()` aan voor een logregel met
username/subject; niets persistent (conform V2 van 2026-09-23).

**Hoe de Service de actor krijgt: expliciete parameter via additieve overloads.**
- `freeze(long, ActorIdentity, String)` naast de bestaande `freeze(long, String, String)`; de oude delegeert met
  `ActorIdentity.unverified(…)`.
- Idem voor `createBundle`, `addBatches`, `removeBatch`, `approve`, `reject`, `decideGroup`, `cancel`, `acceptBaseline`,
  `intake`, `LinkBookmarkValueService.setValue`, `SetupService.createDefinition/createRevision/activateRevision/addMapping/
  addFilter/addFieldCriticality`, `TemplateBookmarkService.declareBookmark`,
  `TemplateMaterialisationService.materialise(definitionId, request, actor)` (daar vervangt `actor` binnen de service het
  veld `materialisedBy`; het record `MaterialiseRequest` wijzigt niet).

Waarom: geen publieke methode hernoemd, geen record gewijzigd, de ~45 Service-tests blijven ongewijzigd. De Web-laag
gebruikt uitsluitend de nieuwe overloads (bewezen door de "subject wordt bewaard"-tests, §7). Verworpen: een thread-gebonden
`ActorContext` in de Service (verborgen afhankelijkheid) en een `CurrentActorProvider`-interface die de Service zelf aanroept
(maakt directe aanroepen met expliciete actor onmogelijk). De oude String-overloads krijgen de javadoc "zonder
geverifieerde identiteit; alleen voor tests en `DemoDataSeeder`".

**Contract verruimd (additief, geen breuk):** `uploadedBy` wordt `@RequestParam(required = false)`
(`CatalogImportDeliveryController.java:81`). De andere actorvelden waren al nullable recordvelden. Blanco = afwezig.

---

## 4. Liquibase — `Web/src/main/resources/db/changelog/007-actor-subject.sql`

Hoogste bestaande nummer is 006; include toevoegen aan `db.changelog-master.yaml` na r.22.

Regels voor alle kolommen: volledig additief, `varchar(255)`, **nullable, geen default, geen backfill, geen index**; geen
bestaande kolom of constraint geraakt; 255 = bovengrens `sub` (OIDC Core §2); bij een nullable `*_by` een koppelcheck
`(<x>_by_subject is null or <x>_by is not null)`. Eén changeset per bouwstap (004-patroon), elk met `--rollback`.

- **007-1-bundle-freeze-subject** (5A-2): `publication_bundle.frozen_by_subject` + `ck_publication_bundle_frozen_subject`;
  `publication_decision.decided_by_subject` (`decided_by` is NOT NULL → geen check).
- **007-2-bundle-subject** (5A-4): `publication_bundle.created_by_subject`, `cancelled_by_subject` (+check);
  `publication_bundle_batch.added_by_subject`, `removed_by_subject` (+check).
- **007-3-import-batch-subject** (5A-5): `import_batch.created_by_subject` (+check), `baseline_accepted_by_subject` (+check).
- **007-4-configuration-subject** (5A-6, G1): `import_definition.created_by_subject`;
  `import_definition_revision.created_by_subject`, `approved_by_subject` (+check); `import_field_mapping.created_by_subject`
  (+check); `import_record_filter.created_by_subject` (+check); `import_revision_field_criticality.created_by_subject`
  (+check); `import_definition_bookmark.created_by_subject` (+check); `import_definition_bookmark_value.filled_by_subject`;
  `import_link_bookmark_value.filled_by_subject`, `updated_by_subject` (+check).

Totaal 18 kolommen op 12 tabellen. Entiteiten krijgen in dezelfde stap een nullable `@Column(length = 255)`-veld
(`ddl-auto: validate`, `application.yml:10`). **Het subject komt in geen enkel API-antwoord** (A6).

---

## 5. `GET /api/catalog-import/me`

Controller `CatalogImportMeController`, gebouwd in 5A-1 (vóór de actorcontrole).
```json
{ "username": "jan.peeters", "subject": "3f2c…", "displayName": "Jan Peeters", "permissions": null }
```
- `displayName` = claim `name`, nullable.
- **`permissions` is in 5-AUTH altijd `null`** ("niet vastgesteld", nooit een lege lijst). 5-PERM vult het veld. De SPA
  leidt nooit iets af uit `null`.
- 401 `AUTHENTICATION_REQUIRED` zonder sessie; 403 `ACTOR_IDENTITY_INVALID` bij onbruikbare identiteit. Een `system`-login
  krijgt 200: lezen mag, tekenen niet.
- Het endpoint laat de `XSRF-TOKEN`-cookie zetten, zodat die bestaat vóór de eerste schrijfactie.

**Haakjes voor 5-PERM** (hier niets beslist): `permissions` in `/me`; de geautoriseerde client in de sessie (token relay
naar Prodis `GET /api/account`); de 403-vorm via `ActorNotAllowedException`; per actie voorlopig enkel `authenticated()`.

---

## 6. Frontend

- **`actor/ActorContext.tsx` wordt vervangen:**
  - `STORAGE_KEY 'catalogimport.actor'` (r.15), `setActor` en de `sessionStorage`-logica (r.43-65) verdwijnen. Bij opstart
    één keer `sessionStorage.removeItem('catalogimport.actor')` (geen oude naam op een gedeelde machine).
  - De provider laadt `/me` via nieuw `api/me.ts`.
  - Toestanden: *laden* ("Aanmelden…"), *aangemeld*, *sessie verlopen*, *fout* (volledig foutvlak mét code).
  - `useActor()` levert `{ actor: username, subject, displayName, sessionExpired }`.
  - Optionele prop `identity` als testnaad (slaat de fetch over).
- **Wat zichtbaar blijft:**
  - `ActorBar`: "Aangemeld als *displayName* (*username*)" + knop **Afmelden** (POST logout, dan
    `location.assign(logoutUrl)`). Waarschuwingstekst (`ActorBar.tsx:56-59`) en invoer verdwijnen.
  - `ConfirmDialog`: "U tekent als *displayName* (*username*)", **alleen-lezen**. Invoerveld (r.110-120) en naamvalidatie
    (r.78) verdwijnen. Principe "wie tekent, ziet onder welke naam" blijft. `onConfirm({actor, reason})` houdt zijn vorm.
- **Actorvelden blijven meegestuurd** met de `/me`-username (A4): wie zich intussen in een ander tabblad als iemand anders
  aanmeldt, krijgt `ACTOR_FIELD_MISMATCH` i.p.v. een handtekening onder een naam die hij niet zag. `validateActorName`
  verdwijnt uit `UploadPage.tsx:124`, `BundleBatchesTab.tsx:213`, `CreateBundleForm.tsx:63`.
- **`api/http.ts`** (r.52-60): bij elke niet-GET/HEAD-aanroep header `X-XSRF-TOKEN` met de ruwe waarde van cookie
  `XSRF-TOKEN`; bij status 401 een geregistreerde `onUnauthenticated`-callback van de ActorProvider.
- **401-afhandeling:**
  - 401 op `/me` bij opstart → huidig pad in `sessionStorage['catalogimport.returnTo']` (enkel een pad, geen identiteit),
    dan `location.assign('/oauth2/authorization/keycloak')`. Na login land je op `/` en herstelt de SPA het pad.
  - 401 tijdens een actie → **geen automatische redirect** (getypte redenen zouden verloren gaan). `ActorBar` toont "Uw
    sessie is verlopen" met knop **Opnieuw aanmelden**; de dialoog toont de fout mét code.
- **`errors/codes.ts`**: nieuwe entries `AUTHENTICATION_REQUIRED`, `ACTOR_FIELD_MISMATCH` ("U bent intussen als iemand
  anders aangemeld; er is niets opgeslagen. Herlaad de pagina."), `SYSTEM_ACTOR_FORBIDDEN`, `ACTOR_IDENTITY_INVALID`,
  `CSRF_TOKEN_INVALID`.
- **Vite (`vite.config.ts:11-14`)**: proxy-entries `'/api'`, `'/oauth2'`, `'/login'` naar `http://localhost:8081`, alle
  drie **`changeOrigin: false`** (C10). Host blijft `localhost:5173`, dus Spring bouwt
  `redirect_uri = http://localhost:5173/login/oauth2/code/keycloak`; de callback loopt via de proxy; `JSESSIONID` en
  `XSRF-TOKEN` gelden per host (niet per poort). Geen forward-headers, geen CORS. Wijkt af van frontendontwerp §2 ("proxy
  blijft ongewijzigd") — enkel ontwikkelconfiguratie.

---

## 7. Teststrategie

**Testinfrastructuur zonder Keycloak en zonder netwerk** (`Web/src/test/java/be/dda/catalogimport/testsupport/`):
- **`TestSecurityConfiguration`** — gewone `@Configuration` (geen `@TestConfiguration`), zodat de component-scan van de
  test-classpath hem in **elke** bestaande `@SpringBootTest`-context oppikt zonder de ~45 testklassen te wijzigen (C1).
  Levert:
  1. een nep-`ClientRegistrationRepository` naar het Prodis-patroon (`TestSecurityConfiguration.java:26-55`, met
     `end_session_endpoint`) — de Boot-autoconfiguratie valt weg, geen OIDC-discovery;
  2. een `MockMvcBuilderCustomizer` met `defaultRequest(get("/").with(oidcLogin()` (subject `test-sub-default`,
     **expliciete claim `preferred_username = "test.user"`**) `.with(csrf().asHeader()))`. `asHeader()` is verplicht door
     de header-only-resolutie (C3).
- **`TestActors.as(String username)`** — `oidcLogin()` met `preferred_username = username`, `sub = "test-sub-" + username`.

**Bestaande tests, zo weinig mogelijk wijzigen:**
- 5A-1: **nul wijzigingen** — alle MockMvc-tests draaien standaard als `test.user` met CSRF.
- Vanaf 5A-2, per endpoint dat de mismatchcontrole krijgt: schrijfaanroepen met een andere actornaam krijgen
  `.with(as(X))` (bv. `BundleHttpTest.java:63-66`, vier namen in één flow). Ca. 14 bestanden, ca. 110 aanroepen, grotendeels
  via een handvol helpers.
- Verworpen: een postprocessor die de naam uit de body leest en zich daarmee aanmeldt (magisch; omzeilt in de tests exact
  de mismatchcontrole).
- Standalone-MockMvc (`BundleGroupDecisionHttpTest.java:46-51`): de controllerconstructor krijgt een `CurrentActor`-stub.
- Service-tests: ongewijzigd dankzij de overloads.

**Nieuwe tests:**
- `SecurityHttpTest` (5A-1): anoniem `GET /api/catalog-import/batches` → 401 `AUTHENTICATION_REQUIRED` zonder `Location`;
  anoniem `GET /` → 302 naar `/oauth2/authorization/keycloak`; POST met `csrf().useInvalidToken()` → 403
  `CSRF_TOKEN_INVALID`; `/me` → username/subject/`permissions` null; `/me` zonder `preferred_username` → 403
  `ACTOR_IDENTITY_INVALID`; logout → 200 `logoutUrl`, daarna 401 met dezelfde `MockHttpSession`; `/actuator/health`
  anoniem → 200; **"een expliciete `.with(as(X))` wint van de default"** (merge-volgorde van `defaultRequest`).
- `FreezeActorHttpTest` (5A-2): `frozenBy` weglaten → bewaard = token-username; andere hoofdletters → aanvaard, bewaard =
  token-spelling; andere naam → 400 `ACTOR_FIELD_MISMATCH`, bundel blijft `ASSEMBLING`, geen nieuwe
  `publication_decision`-rij; `publication_bundle.frozen_by_subject` en `decided_by_subject` op **beide** beslissingsrijen
  (`FREEZE`, `AUTO_APPROVE_PLANNED`) = token-`sub` (JDBC); login als `system` → 403 `SYSTEM_ACTOR_FORBIDDEN`, niets geschreven.
- Per volgende stap een kleinere variant per endpoint met dezelfde vier bewijzen: weglaten, mismatch, subject bewaard,
  `system` geweigerd. Upload: `uploadedBy` weglaten → 201 en `import_batch.created_by(_subject)` gevuld.

**Gericht testcommando:**
- per stap: `mvn -pl Web -am test "-Dtest=<klassen van die stap>" -Dsurefire.failIfNoSpecifiedTests=false`;
- **na 5A-1 en na 5A-6**: `mvn -pl Web -am test` (`docs/requirements/catalog-import-acceptance.md:33`), omdat de
  filterketen elke HTTP-test raakt. Nooit de hele reactor.
- Frontend: `npm test -- --run` en `npm run build` in `Frontend/`.

---

## 8. Bouwstappen (strikt sequentieel)

| Stap | Inhoud | Klaar wanneer |
|---|---|---|
| **5A-1** | Dependencies, `SecurityConfiguration` (§2), properties, `CurrentActor.current()`, `/me`, logout, testinfrastructuur, `SecurityHttpTest`. Geen schema, geen actorcontrole. | `SecurityHttpTest` groen; `mvn -pl Web -am test` groen zonder wijziging aan bestaande tests. |
| **5A-2** (kleinste verticale slice) | `ActorIdentity` (Service), `CurrentActor.signer`, drie foutcodes + handler, changeset 007-1, entiteitvelden, `freeze`-overload, freeze-endpoint aangesloten, `.with(as(FREEZER))` in freeze-aanroepen. | `FreezeActorHttpTest`, `BundleHttpTest`, `BundleFreezeTest` groen. |
| **5A-3** | Frontend: ActorContext uit `/me`, CSRF-header, 401-afhandeling, ActorBar met logout, ConfirmDialog alleen-lezen, codes, Vite-proxy, frontendtests (testnaad `identity`). | vitest + build groen. Handmatig (vereist C7): inloggen via :5173, bundel bevriezen, subject in de DB. |
| **5A-4** | Overige bundel-endpoints + 007-2. | Bundeltests + nieuwe actortests groen. |
| **5A-5** | Upload (`uploadedBy` optioneel), accept-baseline, logregel bij `continue`, 007-3. | Upload-/baselinetests groen. |
| **5A-6** | Setup-, template- en link-endpoints (vlag blijft), 007-4 (G1). | Setup-/templatetests groen; `mvn -pl Web -am test` groen. |
| **5A-7** | README, handleiding en `scripts/scenario/manual-upload-scenario.sh` aanpassen volgens V1 = A1 (C8): browsersessie (cookie + `X-XSRF-TOKEN`) of voorlopig handmatig; ontdekkingen terugschrijven na akkoord. | Documentatie klopt met het gedrag. |

5-AUTH wordt **als geheel** uitgerold (A7). Tussen 5A-2 en 5A-6 accepteren niet-omgezette endpoints nog een ongecontroleerde
naam zonder subject; dat mag alleen op een ontwikkelmachine.

---

## 9. Beantwoorde vragen (§6)

**V1 — lokaal/demo zonder echte Keycloak en gescripte toegang: A1 (mens, 2026-09-25).** Geen omzeiling. Lokaal draait
altijd de Prodis-Keycloak (:9080, realm `prodis`) met client `catalog-import`. Tests draaien zonder Keycloak via de
testconfiguratie (niet in het artefact). Scripts en handleiding worden herschreven naar een browsersessie (cookie +
`X-XSRF-TOKEN`) of blijven voorlopig handmatig. Verworpen: A2 `local-noauth`-profiel (zit in het productieartefact, één
profielnaam van een open API). Uitgesteld: A3 bearer-tokens naast de BFF — alleen als aparte, latere beslissing wanneer
gescripte toegang echt nodig is.

**V2 — intrekking van toegang: B1 (mens, 2026-09-25).** Aanvaard tot 5-PERM: toegang blijft tot de sessie-idle-timeout
(30 min, gelijk aan de Keycloak-idle van Prodis, `application.yml:250-252`). In 5-PERM faalt de rechtencheck via token relay
fail-closed zodra het token niet meer ververst kan worden. Geen back-channel logout in 5-AUTH.

---

## 10. Aannames (doorwerken tenzij herroepen)

- **A1** Eén Keycloak-client `catalog-import` in de Prodis-realm (beslissingslog 2026-09-25).
- **A2** `preferred_username` = username. Vergelijken getrimd en hoofdletterongevoelig; bewaard wordt de tokenwaarde.
- **A3** Blanco actorvelden = afwezig.
- **A4** De SPA blijft de `/me`-username meesturen (bescherming tegen tabblad-/sessiewissel).
- **A5** `SYSTEM_ACTOR_FORBIDDEN` en `ACTOR_IDENTITY_INVALID` → 403; `ACTOR_FIELD_MISMATCH` → 400.
- **A6** Subject is audit-only: in geen API-antwoord; idempotentievergelijkingen (bv. dezelfde beslisser) blijven op username.
- **A7** 5-AUTH wordt in één keer uitgerold.
- **A8** Eén instantie → sessies in het geheugen; een herstart meldt iedereen af. Meerdere instanties vragen later Spring Session.
- **A9** Na een mislukte login de standaard `/login?error`-pagina van Spring; geen eigen loginpagina.
- **A10** `continue` blijft zonder persistente actor, enkel een logregel (V2, 2026-09-23).
- **A11** De Service-intake blijft `requireText` gebruiken voor `uploadedBy`; de Web-laag sluit `system` af (C5).
- **A12** De setup-API-vlag blijft een extra bescherming bovenop de login.
- **A13** Statische SPA-uitlevering door `Web` valt buiten 5-AUTH; de keten is erop voorbereid (niet-API-paden → login).

---

## 11. Ontdekkingen

> **Important technical constraint discovered** — C1
>
> `issuer-uri` doet bij opstarten OIDC-discovery (eager): elke `@SpringBootTest` met profiel `local` zou een draaiende
> Keycloak eisen. Prodis vangt dat op met een test-`ClientRegistrationRepository` via een meta-annotatie. CatalogImport heeft
> geen meta-annotatie (~45 klassen gebruiken `@SpringBootTest` + `@ActiveProfiles("local")` rechtstreeks). De
> testconfiguratie moet dus via component-scan van de test-classpath binnenkomen (gewone `@Configuration`), anders moet elke
> testklasse aangepast worden.

> **Important technical constraint discovered** — C2
>
> Met de standaard `HttpSessionRequestCache` bewaart Spring een 401'd `/api/**`-verzoek (zoals `/me`) en stuurt na login de
> browser naar die JSON-URL. Daarom `NullRequestCache` + `defaultSuccessUrl("/", true)`; de SPA herstelt zelf het pad.

> **Important technical constraint discovered** — C3
>
> `CsrfFilter` loopt vóór de autorisatie. Een CSRF-resolver die op een requestparameter terugvalt (Prodis-patroon en
> Spring-default) roept `getParameter` aan en laat Tomcat een multipart-body tot 1 GB (`application.yml:18-19`) volledig
> inlezen — ook bij anonieme verzoeken, nog vóór er iets geweigerd wordt. Daarom CSRF-token **alleen uit de header**;
> tests gebruiken `csrf().asHeader()`. Te verifiëren in 5A-1.

> **Important technical constraint discovered** — C4
>
> Bestaande HTTP-tests tekenen met meerdere namen in één flow (`BundleHttpTest.java:63-66`). Met de mismatchcontrole heeft
> elke schrijfaanroep een identiteit per verzoek nodig.

> **Important technical constraint discovered** — C5
>
> `uploadedBy` wordt met `requireText` gevalideerd, niet met `requireActorName` (`DeliveryIntakeService.java:119`). Vandaag
> is `system` dus toegelaten als uploader, anders dan bij elke andere ondertekening. Na 5-AUTH sluit de Web-laag dit af;
> de Service blijft het toelaten (A11).

> **Important technical constraint discovered** — C6
>
> "`ActorContext` is het enige vervangpunt, geen formulier hoeft te wijzigen" (frontendontwerp §7) klopt maar gedeeltelijk:
> `ConfirmDialog.tsx:60,78,110-120` en `ActorBar.tsx` wijzigen de naam via `setActor`; `UploadPage.tsx:124`,
> `BundleBatchesTab.tsx:213` en `CreateBundleForm.tsx:63` roepen `validateActorName` aan; 14 frontendtests wikkelen
> `ActorProvider` en typen in het naamveld.

> **Important technical constraint discovered** — C7
>
> Het beslissingslog (2026-09-25) noemt de Keycloak-client "extern, blokkeert 5-PUB-b/c, niet 5-AUTH". De geautomatiseerde
> tests van 5-AUTH hebben hem inderdaad niet nodig, maar de **handmatige acceptatie** (5A-3, Fase 7) wel — minstens in de
> lokale realm, met de redirect- en post-logout-URI's uit §2.3.

> **Important technical constraint discovered** — C8
>
> Na 5A-1 werken de curl-voorbeelden niet meer (401/403): `README.md` (23), `docs/handleiding/standaardflows.md` (44),
> `csv-importeren.md` (16), `docs/handleiding/README.md` (2), `scripts/scenario/manual-upload-scenario.sh` (21 voorkomens).

> **Important technical constraint discovered** — C9
>
> `catalog_reference_state` (004:576-598) heeft geen batch-FK. De ondertekenaar van een referentierij is enkel te herleiden
> via `(import_link_id, accepted_at)` = `import_batch.(import_link_id, baseline_accepted_at)`. Dat werkt alleen omdat
> `SourceStateBaselineService` hetzelfde `acceptedAt` gebruikt voor de chunks en voor `finish` (r.194, 203, 245) — een
> fragiele koppeling, ook los van 5-AUTH.

> **Important technical constraint discovered** — C10
>
> De huidige Vite-proxy (`changeOrigin: true`, `vite.config.ts:13`) herschrijft de Host naar :8081; Spring bouwt dan
> `redirect_uri` op :8081 en na login kom je op de backend terecht i.p.v. in de SPA. OIDC via de devproxy vereist
> `changeOrigin: false` en proxy-entries voor `/oauth2` en `/login`.

> **Important technical constraint discovered** — C11
>
> Standaard bewaart `oauth2Login` de tokens in een `InMemoryOAuth2AuthorizedClientService`, gesleuteld op principalnaam:
> tokens overleven een logout en twee sessies van dezelfde gebruiker delen ze. Voor 5-PERM (token relay) is
> `HttpSessionOAuth2AuthorizedClientRepository` nodig.

Terugschrijven (na akkoord van de mens): C3 en C9 naar `docs/design/fase2-screening-design.md`; C6 en C10 naar
`frontend-scherm3-bundel-design.md` §2/§7; C7 naar het beslissingslog.

## 12. Buiten scope
- Rechten per actie en de Prodis-token-relay (5-PERM).
- Statische uitlevering van de SPA en de CSP.
- Een bearer-/machine-ingang.
- Back-channel logout.
- Het subject tonen in UI of API-antwoorden.
- Het afbouwen van de actorvelden (latere, aangekondigde stap, Q2).
- Publicatie (5-PUB).
