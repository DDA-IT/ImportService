# Catalog import — acceptatiecriteria

> Status: historische acceptatieset voor de eerdere proefversie. De actuele vergelijking met de
> vernieuwde businessanalyse staat in
> [`../analysis/current-project-vs-businessanalyse-2.md`](../analysis/current-project-vs-businessanalyse-2.md).

## Functioneel

- Een gebruiker kan een bron en een unieke importdefinitieversie aanmaken.
- Een geldige CSV met de geconfigureerde vier kolommen kan worden geüpload, gepland, goedgekeurd en gepubliceerd.
- Een eerste geldige aanbieding wordt als CREATE gepubliceerd in de geconfigureerde controlebibliotheek.
- Een bestaande aanbieding met gewijzigde groep of prijs wordt als UPDATE gepubliceerd; een identieke actieve aanbieding veroorzaakt geen mutatie.
- Een lege header, ontbrekende vereiste kolom, lege kernwaarde, dubbele aanbiedingsidentiteit of onleesbare prijs blokkeert het batch en wijzigt niets in de bibliotheek.
- Een prijs wordt nooit impliciet nul wegens een parsefout.
- Een identieke bestandsinhoud voor dezelfde definitie geeft hetzelfde batch terug en veroorzaakt geen dubbele aanbieding.
- Bij bestaande bibliotheekomvang blokkeert een batch met meer dan 100 CREATE-mutaties of meer CREATE-mutaties dan de bestaande omvang.
- Publicatie zonder `PLANNED` status of met approver `system` wordt geweigerd.

## Traceerbaarheid en controles

- Support kan per batch definitie, hash, originele inhoud, bestandsnaam, ontvangsttijd, aantallen en eindstatus terugvinden.
- Per gepubliceerde mutatie is de kandidaat en vorige prijs (waar van toepassing) terug te vinden.
- De unieke databaseconstraints voorkomen dubbelresultaten, ook buiten de applicatielogica.

## Bestaande geautomatiseerde verificatie

`Web/src/test/java/be/dda/catalogimport/web/CatalogImportFlowTest.java` dekt momenteel:

- initiële publicatie plus hash-idempotentie;
- blokkade voor een onleesbare prijs;
- blokkade voor een bulkcreatie tegen bestaande scope.

Uit te voeren gerichte testcommando: zie "Aanvullingen: testinstructies na Fase 5-AUTH" hieronder — niet
`mvn -pl Web -am test` zonder `-Dtest=...` op een gevulde `public`-database (o.a. `ImportControlSchemaTest`
en `ScreeningSchemaTest` slagen alleen op een lege database). Gebruik `mvn -pl Web -am test "-Dtest=<klassen>"
-Dsurefire.failIfNoSpecifiedTests=false` gericht, of `.\scripts\test\run-full-tests.ps1` voor een volledige
ronde in een eigen schema.

## Aanvullingen: testinstructies na Fase 5-AUTH (mens akkoord op terugschrijven 2026-09-26)

- **Gericht (per stap of klasse):** `mvn -pl Web -am test "-Dtest=<klassen>" -Dsurefire.failIfNoSpecifiedTests=false`.
  Draai niet veel `@SpringBootTest`-klassen tegelijk (ongeveer 4 per run), anders raken de PostgreSQL-verbindingen op
  (*remaining connection slots are reserved…*).
- **Volledige of herhaalde testronde van de Web-module:** `.\scripts\test\run-full-tests.ps1` (PowerShell, vanuit de
  projectroot). De tests draaien tegen PostgreSQL (profiel `local`) en een deel ervan gebruikt vaste codes zonder op te
  ruimen, dus slaagt alleen op een lege database. Het script maakt daarom een **eigen schema** (default `ci_fulltest`, nooit
  `public`) leeg en draait de tests daar, met kleine verbindingspools (`-PoolSize 3`) en een **contextcache van 2**
  (`-CacheSize 2`). Opties: `-Tests A,B` (gericht), `-NoReset`, `-DropAfter`, `-Schema`. Het log staat in
  `Web/target/full-test.log`; "BUILD SUCCESS" zegt niets omdat falende tests de build niet breken (het script toont per klasse
  wat faalt en eindigt met exit 1 bij falende tests). Zie ook `README.md` ("Volledige testronde in een eigen schema").
- **Frontend:** in `Frontend/` `npm test -- --run` en `npm run build`. Het juiste typecheckcommando is
  `tsc --noEmit -p tsconfig.app.json`; `tsc --noEmit -p .` controleert niets, omdat het root-tsconfig alleen doorverwijst.
- **Authenticatie in tests:** de HTTP-tests draaien zonder Keycloak via een testconfiguratie in `Web/src/test`
  (`TestSecurityConfiguration`, `TestActors`); zie `docs/design/fase5-auth-design.md` §7. De handmatige acceptatie via de
  browser vereist wel een Keycloak-client `catalog-import` (zie hetzelfde document, §2.3 en C7).

## Aanvullingen: testinstructies na Fase 5-PERM (bouwstappen 5B-1 t/m 5B-5)

Ontwerp: `docs/design/fase5-perm-design.md`. Rechten: `READ`/`MANAGE`/`APPROVE` met hiërarchie (`APPROVE` omvat `MANAGE`
en `READ`; `MANAGE` omvat `READ`). De Prodis-adapter (5B-7) is niet gebouwd en dus ook niet getest.

- **Testsupport (`Web/src/test/.../testsupport`):**
  - `TestPermissionSource` (`@Primary`): leest de rechten uit de claim `catalogimport_permissions` van de testlogin; geen claim
    = lege set. Levert ruwe codes; `CurrentActor` past de hiërarchie toe.
  - `TestActors.as(username)`: alle drie de rechten (zodat de bestaande aanroepen ongewijzigd blijven);
    `TestActors.as(username, Permission...)`: exact de opgegeven ruwe rechten; `TestActors.withoutPermissions(username)`: geen rechten.
    Een test die over rechten gaat, gebruikt de tweede of derde vorm.
- **Testklassen:** `PermissionHttpTest`, `PermissionWriteEndpointsHttpTest`, `PermissionReadEndpointsHttpTest` (per
  endpointfamilie: zonder recht 403 `PERMISSION_DENIED` en niets geschreven; met recht ongewijzigd; foutvolgorde recht eerst),
  `PermissionHierarchyTest`, `ConfiguredPermissionSourceTest`, `CurrentActorPermissionTest`,
  `MePermissionSourceUnavailableHttpTest` (503 `PERMISSION_SOURCE_UNAVAILABLE`), `PermissionInterceptorTest` (config) en
  `PermissionCoverageTest`; `SetupApiDisabledTest` blijft (vlag uit = 404, ook met recht).
- **`PermissionCoverageTest` (architectuurtest, geen database):** elke `@*Mapping`-methode in `be.dda.catalogimport.web` moet
  exact één van `@RequiresPermission` of `@NoPermissionRequired` dragen, en de volledige endpoint-recht-mapping uit ontwerp §1
  staat als verwachte map in de test. Een nieuw endpoint zonder annotatie laat de test dus falen (en is in productie dicht: 403).
- **Een nieuw endpoint toevoegen:** (1) annoteer de handler met `@RequiresPermission(READ|MANAGE|APPROVE)` (of bewust
  `@NoPermissionRequired`); (2) neem het endpoint op in de tabel van ontwerp §1; (3) werk de verwachte map in
  `PermissionCoverageTest` bij; (4) voeg een test toe met `as(user, ...)`: zonder recht 403 en niets geschreven.
- **Gerichte run:** `mvn -pl Web -am test "-Dtest=PermissionCoverageTest,PermissionHttpTest" -Dsurefire.failIfNoSpecifiedTests=false`
  (of `-Tests` in `scripts/test/run-full-tests.ps1`); Frontend: `npm test -- --run`.
