# CatalogImport — end-to-end tests (Playwright)

Browsertests die de **echte frontend** (Vite, 5173) tegen de **echte backend** (Spring Boot, 8081) en
**PostgreSQL** draaien. Dit is een eigen npm-project, los van `Frontend/`.

## Uitgangspunten

- Elke suite maakt zijn **eigen data via de API** met unieke codes (tijdstempel + willekeur, base36). De database
  is persistent en gedeeld; er wordt niets opgeruimd en herhaalde runs botsen niet.
- **1 worker**, geen retries (gedeelde database). Trace wordt bewaard bij een eerste retry.
- De backend wordt **niet** door Playwright gestart (te traag). De frontend wel, als hij nog niet draait
  (`reuseExistingServer`); een reeds draaiende dev-server wordt hergebruikt en niet gestopt.
- Een `globalSetup` faalt met een duidelijke melding als `http://localhost:8081/api/catalog-import/batches/summary`
  geen 200 geeft.
- De inrichting (bronorganisatie, definitie, koppeling, taak) gebruikt de setup-API: die staat alleen aan in
  profiel `demo`.

## Voorbereiding (eenmalig)

```bash
cd e2e
npm install
npx playwright install chromium
```

## Backend losgekoppeld starten (Windows)

Achtergrondprocessen in Git Bash sterven vaak; start daarom via PowerShell `Start-Process`:

```powershell
mvn -pl Web -am install -DskipTests          # eerst, en niet tegelijk met de run
Start-Process -FilePath cmd.exe -WindowStyle Hidden -WorkingDirectory C:\Users\Willem\IdeaProjects\CatalogImport `
  -ArgumentList '/c','mvn -pl Web spring-boot:run -Dspring-boot.run.profiles=local,demo > C:\tmp\e2e-backend.log 2>&1'
```

Opstarten duurt 1-4 minuten (grote demo-database); wacht tot `curl http://localhost:8081/api/catalog-import/batches/summary`
`200` geeft. Stoppen: zoek het proces op poort 8081 (`netstat -ano | findstr :8081`) en `taskkill /PID <pid> /T /F`
(een `cmd`/`mvn`-boom: gebruik `/T`). Controleer daarna dat de poort vrij is.

## Commando's

```bash
cd e2e
npm test                                   # alle tests
npx playwright test tests/bundles.spec.ts  # één bestand
npx playwright test -g "goedkeuring"       # op titel
npm run test:headed                        # zichtbare browser
npm run report                             # HTML-rapport (playwright-report/)
npm run typecheck
```

## Structuur

- `helpers/api.ts` — API-helpers (keten inrichten, CSV uploaden, accept-baseline, bundel aanmaken, batch toevoegen,
  `createBundleScenario`). CSV-inhoud wordt in code opgebouwd (kolommen van `scripts/scenario/levering-1.csv`).
- `helpers/global-setup.ts` — backend-check.
- `tests/workqueue.spec.ts` — Scherm 0 (`/`).
- `tests/bundles.spec.ts` — `/bundles`, `/bundles/:id`, `/bundles/:id/mutations`.

## Een test toevoegen

1. Nieuw bestand `tests/<naam>.spec.ts`.
2. Maak data in `test.beforeAll` met de helpers (`createChain`, `uploadCsv`, ...); gebruik nooit vaste codes.
3. Gebruik toegankelijke selectors (`getByRole`, `getByLabel`, `getByText`); de actornaam vul je vooraf in met
   `page.addInitScript(() => sessionStorage.setItem('catalogimport.actor', ...))`.
4. Asserties over "mijn" rijen gaan via de unieke code; de database bevat ook data van andere runs.
5. Draai eerst gericht, dan tweemaal de hele suite.

## Bekende beperkingen van de omgeving

- De koppelingsfilter van de werkvoorraad toont maar de eerste 200 koppelingen (er zijn >1700); een verse
  koppeling is daarom niet via de UI te selecteren. De koppelingstest is beperkt tot de negatieve kant.
- De UI toont de batchtellers (raw/valid/rejected) nog niet; die worden via de API gecontroleerd.

## TODO — scenario's die op nog niet bestaande UI wachten (bewust geen tests)

- `test.fixme` Uploadscherm (scherm 2): CSV uploaden met twee fasen en tijdteller; idempotente herhaling.
- `test.fixme` Batchdetail (`/batches/:id`): tellers, issues, foutgroepen, doorklik vanaf de werkvoorraad.
- `test.fixme` accept-baseline en bundel-opname vanaf de batch (typ-bevestiging).
- `test.fixme` `continue` op een `MUTATING` batch.
- `test.fixme` Groepsbeslissing-dialoog (F9): toepassen op exact de zichtbare filter.
- `test.fixme` Bevriezen (freeze-check, typ-bevestiging) en annuleren (F10).
- `test.fixme` Beslissingsregister-tabblad (F11).
