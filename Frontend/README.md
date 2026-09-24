# CatalogImport Frontend

React + TypeScript + Vite frontend voor het CatalogImport-project. Een losstaand SPA-project in `Frontend/`, buiten de Maven-reactor. Praat uitsluitend via REST/JSON met de `Web`-module van CatalogImport.

Zie `docs/design/frontend-scherm3-bundel-design.md` voor het volledige ontwerp.

## Starten voor lokale ontwikkeling

De frontend werkt via de Vite-devproxy die `/api` doorleidt naar `http://localhost:8081` (de `Web`-module). Tot Fase 5 (Keycloak) is dit de enige manier om de frontend te draaien — geen CORS-configuratie, geen statische uitlevering.

### Vereisten

- Node.js (LTS aanbevolen)
- Backend lokaal draaiend op poort 8081 (zie `Web/src/main/resources/application-local.yml`)

### Starten

```bash
cd Frontend
npm install        # slechts eerste keer nodig
npm run dev        # start devserver op http://localhost:5173
```

De Vite-devproxy stuurt requests van `/api/*` door naar `http://localhost:8081/api/*`. Zorg dat de backend daar luistert.

Sluit de frontend af met Ctrl+C.

## Andere commando's

```bash
npm run build      # typecheck + bundelen naar dist/
npm run lint       # oxlint
npm test           # vitest (eenheids- en integratietests)
```

## Handmatige testscenario's

Onderstaande scenario's controleren het samenspel met de echte backend. Ze volgen `docs/design/frontend-scherm3-bundel-design.md` §14.2.

### Voorbereiding

1. Backend draait op poort 8081
2. Frontend runt op poort 5173 via `npm run dev`
3. Open http://localhost:5173 in een browser
4. Voer een naam in bij "Ingelogd als" (zichtbaar in de app shell)

### Scenario's

1. **Bundel aanmaken; tweemaal dezelfde referentie ⇒ idempotent, met melding**
   - Ga naar "Publicatiebundels"
   - Klik "Nieuwe bundel"
   - Vul `bundleReference` (bv. `TEST-001`), selecteer een `targetMode`, klik "Aanmaken"
   - Bundel verschijnt in de lijst
   - Herhaal dezelfde referentie → server geeft melding "Bundel bestond al"

2. **Kandidaten toevoegen; een al toegevoegde batch nogmaals ⇒ fout, niets veranderd**
   - Open een bundel (status ASSEMBLING)
   - Klik tabblad "Leden"
   - Selecteer kandidaten, klik "Toevoegen"
   - Bij succes: tabel "Leden" toont ze
   - Selecteer dezelfde batch nogmaals, klik "Toevoegen" → server: `BATCH_ALREADY_IN_BUNDLE`, selectie blijft intact

3. **Eén mutatie goedkeuren; dezelfde nogmaals door dezelfde persoon ⇒ idempotent**
   - Open bundel → tabblad "Mutaties"
   - Mutatie met status `AWAITING_APPROVAL` kiezen
   - Klik "Goedkeuren", bevestig in de dialoog
   - Melding: "Goedkeuring vastgelegd" (of idempotentiemelding als het zelfde account meteen)
   - Klik opnieuw "Goedkeuren" met dezelfde naam → melding "geen tweede regel geschreven"

4. **Goedkeuring herzien naar afkeuring ⇒ reden verplicht, beide regels in register**
   - Open bundel → tabblad "Mutaties"
   - Mutatie die al goedgekeurd is (status `READY_FOR_PUBLICATION`, `decidedBy` ingevuld)
   - Klik "Afkeuren" (herzien)
   - Dialoog toont: "U keert een eerdere beslissing om"
   - Reden wordt verplicht (knop uit tot ingevuld)
   - Na bevestiging: tabblad "Beslissingen" toont beide regels (oorspronkelijke goedkeuring + afkeuring)

5. **Groepsactie met zichtbare filter ⇒ aantal klopt, BLOCKED-mutaties blijven ongewijzigd**
   - Open bundel → tabblad "Mutaties"
   - Zet een filter (bv. status = `AWAITING_APPROVAL`)
   - Klik "Groep goedkeuren (N)" of "Groep afkeuren (N)" (in het toolbar onder de tabel)
   - Dialoog toont het aantal mutaties dat eraan voldoet, en zegt welke mutaties de groepsactie nooit raakt
   - Bevestig
   - Tabblad "Beslissingen" toont een rij met `decisionScope = GROUP`, `decisionKind = APPROVE` of `REJECT`, en het aantal betrokken mutaties

6. **Bevriezen met openstaande `AWAITING_APPROVAL` ⇒ blokkade in dialoog**
   - Open bundel → tabblad "Overzicht"
   - Als `awaitingApprovalCount > 0`: knop "Bevriezen" **is aan** (gebruiker mag de dialoog openen)
   - Klik "Bevriezen"
   - Dialoog laadt de voorvlucht (`GET /bundles/{id}/freeze-check`)
   - Als er `AWAITING_APPROVAL`-mutaties zijn, verschijnt een blokkade in de dialoog: "Er wachten nog mutaties op een beslissing"
   - Sluit de dialoog, ga naar "Mutaties", keur alle `AWAITING_APPROVAL`-mutaties goed
   - Terug naar "Overzicht" → open bevriezen opnieuw
   - Ditmaal is de blokkade weg; klik "Bevriezen" in de dialoog (typ de bundelreferentie over)
   - Status wisselt naar `FROZEN`, tellers worden vastgesteld, `PLANNED`-mutaties → `READY_FOR_PUBLICATION`
   - Melding: "Bundel is bevroren ... (AUTO_APPROVE_PLANNED)" met hoeveel op uw naam zijn goedgekeurd

7. **Tweede keer bevriezen ⇒ 409 leesbaar getoond**
   - Van vorig scenario: bundel staat op `FROZEN`
   - Open "Overzicht" → knop "Bevriezen" is uit, reden: "Kan niet: de bundel is bevroren"
   - Probeer via de DevTools tóch de endpoint direct aan te roepen (`POST /bundles/{id}/freeze`) → fout 409 `BUNDLE_NOT_ASSEMBLING` verschijnt als banner in de dialoog

8. **Annuleren van een bevroren bundel ⇒ mutaties `EXPIRED`, batches opnieuw bij kandidaten**
   - Van vorig scenario: bundel staat op `FROZEN`
   - Tabblad "Overzicht" → knop "Annuleren" is aan
   - Klik, bevestig
   - Status wisselt naar `CANCELLED`
   - Tabblad "Leden" → batches verdwijnen (status `active = false`, verwijderd door annulering)
   - Tabblad "Mutaties": alle mutaties zijn nu `EXPIRED`

9. **Backend uitzetten, pagina laden ⇒ nette netwerkfout, geen witte pagina**
   - Backend afsluiten (of firewall blokkeren)
   - Frontend neerladen (refresh)
   - Foutbanner verschijnt met leesbare tekst (geen witte pagina, geen exception-stack)

10. **Prijsweergave op een mutatie met veel decimalen ⇒ weergegeven, niet herrekend**
    - Tabblad "Mutaties"
    - Mutatie kiezen met veld `beforeBasePrice` en `afterBasePrice`
    - Beide prijzen tonen in nl-BE-formaat (komma als decimaalseparator)
    - Geen berekend verschil in een kolom "Δ prijs"

## Architectuur-highlights

- **Geen server-state-bibliotheek:** twee eigen hooks (`useQuery`, `useAction`) met expliciete cache-invalidatie. Reden: optimistische updates zijn ongewenst bij financiële beslissingen.
- **Pure CSS met CSS Modules:** geen Tailwind, geen componentenbibliotheek.
- **Stabiele backendfoutcodes:** altijd zichtbaar in de UI, met familie-fallbacks voor toekomstige codes.
- **Frontend rekent niet met bedragen:** geen afronden, geen berekende verschillen, geen aangenomen munteenheden.

Zie `docs/design/frontend-scherm3-bundel-design.md` voor details.

## Ontwikkelaars

- Tests: `npm test` — vitest met @testing-library/react
- Type-veiligheid: strict TypeScript, geen `any`
- Linting: `npm run lint` — Oxlint met React-rules
- Build: `npm run build` — TypeScript + Vite

Alle componenten in `src/` zijn **geïmplementeerd in Fase 4** (scherm 3 Publicatiebundel). Toekomstige schermen kunnen componenten hergebruiken zonder wijzigingen (zie het `MutationList`-contract in §11 van het ontwerp).
