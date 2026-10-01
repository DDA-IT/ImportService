# CatalogImport Frontend

React + TypeScript + Vite frontend voor het CatalogImport-project. Een losstaand SPA-project in `Frontend/`, buiten de Maven-reactor. Praat uitsluitend via REST/JSON met de `Web`-module van CatalogImport.

Zie `docs/design/frontend-scherm3-bundel-design.md` voor het volledige ontwerp.

## Starten voor lokale ontwikkeling

De frontend werkt via de Vite-devproxy die `/api`, `/oauth2` en `/login` doorleidt naar `http://localhost:8081` (de `Web`-module). Voor lokale ontwikkeling is dit de enige manier — geen CORS-configuratie, geen statische uitlevering.

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
4. Meld u aan via Keycloak (de app stuurt u automatisch door); de header toont daarna "Aangemeld als [naam]" met een knop "Afmelden"

### Scenario's

1. **Bundel aanmaken; tweemaal dezelfde referentie ⇒ idempotent, met melding**
   - Ga naar "Publicatiebundels"
   - Klik "Nieuwe bundel"
   - Vul "Bundelreferentie" (bv. `TEST-001`), selecteer een "Doelmodus", klik "Bundel aanmaken"
   - Bundel verschijnt in de lijst
   - Herhaal dezelfde referentie → melding "Deze bundelreferentie bestond al" (er wordt geen nieuwe bundel gemaakt)

2. **Kandidaten toevoegen; een al toegevoegde batch nogmaals ⇒ fout, niets veranderd**
   - Open een bundel (status ASSEMBLING)
   - Klik tabblad "Leden"
   - Selecteer kandidaten, klik "[N] geselecteerde batch(es) toevoegen"
   - Bij succes: tabel "Leden" toont ze
   - Selecteer dezelfde batch nogmaals, klik "[N] geselecteerde batch(es) toevoegen" → server: `BATCH_ALREADY_IN_BUNDLE`, selectie blijft intact

3. **Eén mutatie goedkeuren; dezelfde nogmaals door dezelfde persoon ⇒ idempotent**
   - Open bundel → tabblad "Mutaties"
   - Mutatie met status "Wacht op goedkeuring" (`AWAITING_APPROVAL`) kiezen
   - Klik "Goedkeuren", bevestig in de dialoog
   - Melding: "Goedkeuring vastgelegd" (of idempotentiemelding als het zelfde account meteen)
   - Klik opnieuw "Goedkeuren" met dezelfde naam → melding "Deze beslissing stond al zo op uw naam; er is geen tweede regel geschreven"

4. **Goedkeuring herzien naar afkeuring ⇒ reden verplicht, beide regels in register**
   - Open bundel → tabblad "Mutaties"
   - Mutatie die al goedgekeurd is (status "Goedgekeurd", technisch `READY_FOR_PUBLICATION`; beslisser ingevuld)
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
   - Tabblad "Beslissingen" toont een rij met bereik "Groep mutaties" (technisch `GROUP`), soort "Goedkeuring" of "Afkeuring" (`APPROVE`/`REJECT`), en het aantal betrokken mutaties

6. **Bevriezen met openstaande `AWAITING_APPROVAL` ⇒ blokkade in dialoog**
   - Open bundel → tabblad "Overzicht"
   - Als `awaitingApprovalCount > 0`: knop "Bevriezen" **is aan** (gebruiker mag de dialoog openen)
   - Klik "Bevriezen"
   - Dialoog laadt de voorvlucht (`GET /bundles/{id}/freeze-check`)
   - Als er `AWAITING_APPROVAL`-mutaties zijn, verschijnt een blokkade in de dialoog: "Er wachten nog mutaties op een beslissing"
   - Sluit de dialoog, ga naar "Mutaties", keur alle `AWAITING_APPROVAL`-mutaties goed
   - Terug naar "Overzicht" → open bevriezen opnieuw
   - Ditmaal is de blokkade weg; klik "Bevriezen" in de dialoog (typ de bundelreferentie over)
   - Status wisselt naar "Bevroren" (`FROZEN`), tellers worden vastgesteld, `PLANNED`-mutaties → `READY_FOR_PUBLICATION`
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

11. **Nieuwe leverancier en taak aanmaken (recht Beheren)**
    - Ga naar "Inrichting" en klik "Nieuwe leverancier en taak" (zonder het recht Beheren staat de knop uit, met reden)
    - Doorloop: leverancier → startpunt (zelf beschrijven of vanuit een sjabloon) → beschrijving van het bestand → koppeling → taak → samenvatting
    - Sluit het scherm halverwege; in "Inrichting" staat nu "Verder inrichten" bij de onafgemaakte leverancier en brengt u terug bij de volgende stap
    - Een code die al bestaat geeft een melding met "Doorgaan met de bestaande" (enkel bij dezelfde ouder)
    - Een tweede concept voor dezelfde beschrijving (bv. in een tweede tabblad) wordt geweigerd: 409 `REVISION_DRAFT_ALREADY_EXISTS`, de wizard toont het bestaande concept
    - Zonder actieve versie staat de taak op "Levering uploaden" zichtbaar maar uitgeschakeld ("nog niet klaar: versie niet geactiveerd")

12. **Controleren van een koppeling**
    - Samenvatting van de wizard → "Controleer en activeer de conceptversie" (route `/setup/links/{linkId}/check`)
    - Checklist: alle problemen tegelijk, met veld ("Veld:", "Doelveld:", "Kolom:") en "Wat moet ik doen?"; bij overgeslagen controles de melding "Sommige controles konden nog niet uitgevoerd worden …" (dat betekent niet dat alles in orde is)
    - Proefinlezing: kies een CSV; banner "Er wordt niets opgeslagen of gepubliceerd"; oordeel, tellers ("—" = niet vastgesteld), "Zo lezen we uw bestand", "Niet gecontroleerd in een proef"
    - Activeren zonder geslaagde proefinlezing toont vooraf een waarschuwing; na activeren wordt de checklist herladen
    - "Wat nu?": de eerste echte levering wacht na de screening op uw goedkeuring (`INITIAL_LOAD`)

## Terminologie

Gebruikerstekst is Nederlands en volgt de woordenlijst (`src/terms/`, redactionele bron: `docs/handleiding/begrippen.md`; een test bewaakt dat ze gelijklopen en weert ruwe codes in labels). Gekozen woorden: "invulpunt" (technisch bookmark), "versie" (revisie), "beschrijving van het bestand" (importdefinitie), "leverancier of aankoopvereniging" (bronorganisatie); Batch, Mutatie en Bundel blijven, met "Wat betekent dit?". Technische codes staan onder "Technische details (voor support)" of in een tooltip, nooit als hoofdtekst. Bij een nieuwe tekst: voeg eerst het woord toe in `begrippen.md`.

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

De bundelschermen (scherm 3) zijn gebouwd in Fase 4; later kwamen werkvoorraad, batchdetail, upload, behandelgevallen, Inrichting, Sjablonen, de wizard "Nieuwe leverancier en taak" en "Controleren" bij. Schermen kunnen componenten hergebruiken zonder wijzigingen (zie het `MutationList`-contract in §11 van het ontwerp).

Het label van de knop op het publicatietabblad is "Proefpublicatie starten" (voorheen "Simulatierun starten"; sommige codecommentaren gebruiken nog de oude naam).
