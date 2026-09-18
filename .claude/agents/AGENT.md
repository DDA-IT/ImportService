# AGENT.md — Fasegewijze applicatiebouw vanuit een documentenset

Dit bestand stuurt Claude Code bij het bouwen van een applicatie of feature op basis van
een vaste set analyse- en ontwerpdocumenten. Het legt vast: welke documenten er zijn,
wat elk document betekent, in welke fasen er gebouwd wordt, en welke regels op elke
fase van toepassing zijn.

Standaardstack tenzij een document expliciet iets anders zegt: **Java + Spring Boot +
SQL (JPA/Hibernate, Liquibase voor schema)**, gelaagd in `Domain` / `Dao` / `Service` /
`Web`, zoals een Maven-reactor.

Dit bestand vervangt geen nadenken. Het is een werkwijze, geen checklist om blind af te
vinken.

---

## Werkmodus: één chat, alle uitvoering in subagents

Dit bestand stuurt niet alleen wát er gebouwd wordt, maar ook hóe de sessie zelf
georganiseerd is. **Er blijft maar één interactieve chat actief — de hoofdsessie.** De
hoofdsessie denkt niet zelf uitgebreid na en bouwt niet zelf: zij **delegeert elke
inhoudelijke taak** aan een subagent, en doet zelf enkel: met de mens praten, een taak
classificeren, de juiste subagent kiezen, het resultaat beoordelen/integreren, en
rapporteren volgens §4.

### Twee agentfamilies, nooit dezelfde subagent

- **Denker (`denker-*`)** — Fase 0 (intake), Fase 1 (domeinmodel), architectuuranalyses,
  gap-analyses, ontwerpvoorstellen, een keuze maken tussen opties. Levert een **plan of
  beslissing** op, geen productiecode.
- **Bouwer (`bouwer-*`)** — Fase 2 t/m 7: schrijft entiteiten, Liquibase, services, tests;
  voert het gerichte testcommando uit. Werkt altijd op basis van een reeds genomen
  beslissing (van een Denker-subagent of van de mens), nooit op basis van een eigen
  architectuurkeuze.

Dit is dezelfde scheiding als businessregel vs. technische implementatie (§2, principe 2):
een Denker bepaalt het "wat en waarom", een Bouwer enkel het "hoe geschreven".

### Model en effort gekoppeld aan complexiteit, niet vast

Elke familie heeft drie zwaartetrappen. **De hoofdsessie schat de complexiteit in vóór
delegatie** en kiest de bijhorende subagent expliciet — nooit standaard de zwaarste, nooit
standaard de lichtste.

| Trap | Wanneer | Denker | Bouwer |
| --- | --- | --- | --- |
| Licht | Eén goed afgebakende, kleine taak; geen architectuurimpact; één bestand/component | `denker-licht` — Haiku, effort low | `bouwer-licht` — Haiku, effort low |
| Gemiddeld | Eén story/fase uit dit document; meerdere bestanden binnen dezelfde laag; geen open architectuurvraag | `denker-gemiddeld` — Sonnet, effort medium | `bouwer-gemiddeld` — Sonnet, effort medium |
| Zwaar | Raakt meerdere componenten/lagen, financiële logica, of valt onder een §6-criterium | `denker-zwaar` — Opus, effort high | `bouwer-zwaar` — Opus, effort high |

Classificatiesignalen: het aantal geraakte lagen/componenten (§2, principes 5-6), of de
taak onder een §6-criterium valt (architectuur/autorisatie/statusflow/contractbreuk/
financiële reconciliatie), en of er al een vastgelegde beslissing bestaat of er nog één
gemaakt moet worden.

**Parallellisatie:** enkel `*-licht`-taken mogen parallel gestart worden, en alleen als ze
volledig onafhankelijk zijn — geen gedeeld bestand/component, geen afhankelijkheid tussen de
taken. `gemiddeld`- en `zwaar`-taken, en alle stories binnen Fase 6, blijven sequentieel:
juist daar is de kans op gedeelde bestanden of onderlinge afhankelijkheid het grootst
(§2 principe 9, Fase 6).

### Regels voor de hoofdsessie

1. Nooit zelf Fase 1-7-werk uitvoeren (geen entiteiten schrijven, geen code wijzigen, geen
   documentanalyse die neerkomt op Fase 0-werk). Altijd delegeren.
2. Vóór delegatie: complexiteit inschatten (tabel hierboven), dan de subagent expliciet
   kiezen bij het aanroepen van de Agent-tool — niet aan automatische delegatie overlaten
   wanneer de trap ertoe doet.
3. Een Bouwer start pas nadat de bijhorende Denker-beslissing er is (van een
   Denker-subagent, of van de mens zelf via een §6-vraag). Nooit een Bouwer een
   architectuurkeuze laten maken die aan een Denker of aan de mens toekomt.
4. De hoofdsessie beoordeelt elk subagentresultaat tegen de Definition of Done van de
   betrokken fase (§3) vóór ze het als afgerond rapporteert.
5. Het rapport (§4) schrijft de hoofdsessie zelf, op basis van wat de subagent teruggeeft —
   niet de subagent.

### Escalatielus: wat er gebeurt als een Bouwer stopt

Een Bouwer die tijdens het werk een ontwerpkeuze tegenkomt die niet al vastligt (een
§6-criterium), stopt en rapporteert dat aan de hoofdsessie in plaats van te gokken (zie
`bouwer-*.md`). De hoofdsessie volgt dan altijd deze lus, nooit een kortere weg:

1. **Pauzeer** de lopende Bouwer-taak — niet laten doorwerken op een aanname.
2. **Classificeer** de opengevallen vraag met de tabel hierboven en dispatch een passende
   Denker-subagent; raakt de vraag iets dat volgens §6 bij de mens hoort, stel de vraag
   rechtstreeks aan de mens in plaats van een Denker in te zetten.
3. **Leg de beslissing vast** in het beslissingslog (zie hieronder) zodra ze er is —
   vóórdat de Bouwer herstart.
4. **Hervat** de oorspronkelijke taak met dezelfde of een nieuwe Bouwer-subagent, met de
   nieuwe beslissing expliciet in de delegatieprompt (niet impliciet ervan uitgaan dat de
   Bouwer dit al "weet").

De hoofdsessie beantwoordt de opengevallen vraag nooit zelf zonder Denker of mens — dat is
opnieuw architectuurwerk in de hoofdsessie, wat regel 1 hierboven al verbiedt.

### Beslissingslog

Een Denker-beslissing (of een mens-beslissing via een §6-vraag) bestaat anders alleen in
dat ene gesprek: zonder logboek is ze weg zodra de context comprimeert of een nieuwe sessie
start. Daarom:

- Elke beslissing die de hoofdsessie accepteert, wordt — vóórdat de bijhorende Bouwer start —
  weggeschreven naar `docs/decisions.md` (aanmaken als het nog niet bestaat), als een blok:
  ```
  ## <datum> — <fase/onderwerp>
  **Vraag:** ...
  **Beslissing:** ...
  **Bron:** <denker-subagent of "mens"> / <brondocument(en)>
  ```
- De hoofdsessie schrijft dit zelf weg, net als het rapport in §4 — nooit de subagent.
- Bij Fase 0-intake: lees `docs/decisions.md` als het bestaat, als onderdeel van de
  documentenset (§1). Eerdere beslissingen zijn bindend tenzij de mens ze expliciet herroept.

### Git & commits

- Eén commit per afgeronde fase of story, niet per subagent-aanroep en niet per bestand.
  De commitboodschap verwijst naar de fase/story en het brondocument.
- De hoofdsessie commit zelf, pas nadat ze het subagentresultaat tegen de Definition of
  Done heeft goedgekeurd (regel 4 hierboven) — nooit de subagent zelf.
- Destructieve git-operaties (`reset --hard`, force-push, een checkout die wijzigingen
  weggooit) op het werk van een subagent gebeuren nooit stilzwijgend: eerst melden aan de
  mens wát er misging en waarom een revert nodig lijkt, dan pas uitvoeren.
- Bij twijfel of iets een aparte commit verdient: liever een commit te veel dan meerdere
  stories die samen in één commit verdwijnen — dat maakt het beslissingslog en het rapport
  (§4) moeilijker te herleiden naar wat er precies veranderde.

### Vereiste om dit te laten werken

Een subagent laadt automatisch de volledige `CLAUDE.md`-hiërarchie van het project, **niet
automatisch dit `AGENT.md`-bestand**, tenzij het via `CLAUDE.md` wordt geïmporteerd. Zonder
die koppeling kennen de Denker/Bouwer-subagents deze fasegewijze regels niet. Zorg dus voor
één regel in `CLAUDE.md` in de projectroot:

```
@AGENT.md
```

De zes subagentbestanden (`.claude/agents/denker-*.md`, `.claude/agents/bouwer-*.md`)
herhalen daarom enkel hun rolspecifieke instructies — de rest komt via `CLAUDE.md` mee.

---

## 0. Uitgangspunt

- De mens levert een **documentenset** (zie hieronder). Die set is de specificatie.
  Code is de implementatie ervan, nooit andersom.
- Als een document ontbreekt of tegenstrijdig is met een ander document: dat blokkeert
  niet automatisch. Werk door tot het punt waar de ambiguïteit een architectuur- of
  business-beslissing raakt, en vraag dan pas.
- Er wordt **nooit** een parallelle architectuur opgezet als de bestaande applicatie al
  een passend patroon heeft (lagen, foutafhandeling, DTO- of recordgebruik,
  naamconventies). Extend, kopieer niet.
- Er wordt gebouwd in **kleine verticale slices**, niet in een groot ontwerp vooraf.

---

## 1. De documentenset (input-contract)

Een feature wordt beschreven met een deelverzameling van onderstaande documenttypes.
Herken ze op inhoud, niet enkel op bestandsnaam. Typische naamgeving:
`<feature>.md`, `<feature>-business-rules.md`, `<feature>-design.md`,
`<feature>-impact.md`, `<feature>-acceptance.md`,
`<feature>-<doelsysteem>-story.md`, `<feature>-<doelsysteem>-integration.md`,
`<feature>-<doelsysteem>-stories.md`.

| # | Document | Beantwoordt | Verplichte inhoud |
|---|---|---|---|
| 1 | **Probleem & doel** (`<feature>.md`) | Waarom bestaat dit? Voor wie? | Bedrijfsprobleem, gebruiker/rol, gewenst resultaat, voorbeelden (geldig/ongeldig/herverwerking), expliciete "buiten scope" |
| 2 | **Business rules** (`-business-rules.md`) | Wat moet waar zijn, altijd? | Identiteit & traceerbaarheid, validatieregels, status-/levenscyclusregels, `Important business rule discovered`-blokken, open uitzonderingen |
| 3 | **Design** (`-design.md`) | Hoe is het technisch gebouwd? | Verantwoordelijkheden per component, datamodel (entiteiten + relaties + identiteitssleutel), verwerkingsflow met foutafhandeling, "eerste verticale versie", bewuste ontwerpgrenzen voor later |
| 4 | **Impact** (`-impact.md`) | Wat raakt dit in het bestaande systeem? | Architectuur/lagen, betrokken componenten met verantwoordelijkheid, datastroom, bestaande REST-contracten, compatibiliteitsrisico's en conventies |
| 5 | **Acceptance** (`-acceptance.md`) | Wanneer is het klaar? | Functionele criteria, traceerbaarheid/controles, bestaande geautomatiseerde tests + het gerichte testcommando |
| 6 | **Deploy-story** (`-story.md`) | Waar en hoe landt dit in productie? | Deploy-doel/module, hergebruikte bestaande data/entiteiten, gedrag stap-voor-stap, acceptatiecriteria, kleinste eerste verticale slice, gerichte tests, nog te beslissen punten |
| 7 | **Architectuuranalyse / integratie** (`-integration.md`) | Hoe past dit in het grotere systeem? | Doel & afbakening, logische structuur/eigendom, module- en databasegrens, externe data/koppelingen, operationele keten, flow met statusbetekenis, kritieke fouten/meldingen, security & rechten, integriteits-/retry-/auditregels, architectuurmogelijkheden, openstaande beslissingen |
| 8 | **Bouwblokken & stories** (`-stories.md`) | In welke volgorde bouwen we? | Hoofdcontract, per story: nieuw blok, acceptatiecriteria, afhankelijkheden, risico's; een **voorgestelde bouwvolgorde** aan het einde |

**Minimaal vereist om te starten:** documenten 1, 2, 3 en 5 (probleem, business rules,
design, acceptance). Documenten 4, 6, 7, 8 zijn nodig zodra de feature een bestaande
applicatie raakt of naar een groter systeem uitgroeit. Ontbreken die, dan bouw je enkel
de proefversie/verticale slice en meld je expliciet dat integratie- en
bouwvolgordedocumenten nog ontbreken voor een volgende fase.

---

## 2. Vaste werkprincipes (gelden in élke fase)

1. **Begrijp voor je wijzigt.** Lees de betrokken code, het datamodel en de bestaande
   tests voordat je iets aanraakt. Vat kort samen wat je denkt dat er gebeurt, dan pas
   implementeren.
2. **Business rule ≠ technische implementatie.** Houd in je uitleg en in commit-/PR-
   beschrijvingen apart: business rule, technische implementatie, data, uitzonderingen.
3. **Denk in de volledige flow**, niet enkel de regel code die gevraagd is:
   input → validatie → matching → business logic → persistentie → output →
   foutafhandeling → logging. Voor integraties: extern systeem → bericht → import →
   mapping → validatie → interne data → verwerking → business-resultaat →
   status-/foutterugkoppeling.
4. **Eenvoud boven abstractie.** Geen nieuwe frameworks, geen extra interface-laag, geen
   generiek systeem voor een specifiek probleem — tenzij het design-document dat expliciet
   vraagt.
5. **Compatibiliteit is een harde eis.** Voor je iets aanraakt: wie roept dit aan, wat
   roept dit zelf aan, welke databasedependencies, config, bestaande tests. Geen publieke
   methode, DB-kolom, API-veld of config-property hernoemen zonder dit expliciet te
   melden.
6. **Databasekeuzes zijn voor de lange termijn.** Denk bij elk nieuw model aan primary/
   foreign keys, business-referenties, statussen, timestamps, auditeerbaarheid,
   dubbelpreventie, idempotentie, reconciliatie, historiek. Bewaar niets enkel omdat het
   nu gemakkelijk uitkomt.
7. **Integraties gaan nooit uit van "één keer aankomen".** Authenticatie, paginering,
   retries, dubbele berichten, idempotentie, externe/interne ID's, mapping, validatie,
   logging, foutafhandeling, herverwerking, reconciliatie — telkens expliciet afwegen.
8. **Financiële data wordt nooit stilzwijgend aangepast.** Bedragen, btw, totalen,
   betalingsallocaties, afrondingen, creditnota's, boekhoudreferenties: elke afwijking
   expliciet tonen, nooit verborgen corrigeren.
9. **Klein en incrementeel bouwen.** Stap 1 bestaande implementatie begrijpen → stap 2
   gewenst gedrag vastleggen → stap 3 betrokken componenten bepalen → stap 4 kleinste
   samenhangende wijziging implementeren → stap 5 gericht compileren/testen → stap 6
   falen inspecteren → stap 7 verder. Niet twintig ongerelateerde bestanden tegelijk
   wijzigen tenzij de architectuur dat echt vereist.
10. **Testen is nooit enkel "compileert".** Waar relevant: normaal scenario, ontbrekende
    data, dubbele input, ongeldige input, gedeeltelijke verwerking, retry, onverwacht
    extern antwoord, afrondings-/grensgevallen. Voor financiële/integratieprocessen:
    dezelfde operatie twee keer draaien mag nooit een dubbele transactie opleveren.
11. **Alleen gerichte tests draaien**, nooit een algemene `mvn test` over de hele
    reactor. Gebruik het testcommando uit `-acceptance.md` (typisch
    `mvn -pl <Module> -am test`) en beperk tot de betrokken module(s)/klassen. Is een
    bredere of complexere testronde nodig, meld dat expliciet — de mens draait die zelf.
12. **Challenge, agreeer niet automatisch.** Een voorstel dat datainconsistentie
    veroorzaakt, toekomstige ontwikkeling bemoeilijkt, bestaande functionaliteit
    dupliceert, boekhoudlogica breekt, racecondities creëert, matching onbetrouwbaar
    maakt of traceerbaarheid verliest: benoem het concrete probleem en geef een beter
    alternatief. Maak er geen architectuurdiscussie van voor kleine implementatiekeuzes.

---

## 3. Fasegewijze bouwmethode

Elke fase levert een werkend, compilerend, gericht getest resultaat op — nooit enkel
"compileert". Sla geen fase over; een fase mag wel leeg/triviaal zijn als het document
er niets over zegt (meld dat dan expliciet).

### Fase 0 — Intake van de documentenset
**Input:** alle beschikbare documenten uit sectie 1.
**Doen:**
- Lees ze allemaal voordat je iets bouwt. Bouw een intern beeld van: domeinobjecten,
  identiteitssleutels, statussen, betrokken bestaande componenten, scope-grenzen.
- Vergelijk documenten onderling op tegenstrijdigheden (bv. design zegt iets anders dan
  impact, of business-rules noemt een regel die niet in acceptance terugkomt).
- Verzamel alle `Important business rule discovered` / `Important technical constraint
  discovered`-blokken en alle "open uitzonderingen die nog een businesskeuze vragen" /
  "nog te beslissen" secties.
- Bepaal welke van die open punten de architectuur of het businessgedrag *materieel*
  raken. Alleen die stel je als vraag; de rest markeer je als aanname en werk je mee
  verder.
**Output:** een korte samenvatting (in de PR/reactie, geen apart document tenzij
gevraagd) van wat gebouwd gaat worden, welke bestaande componenten geraakt worden, en
welke aannames gemaakt zijn.
**Stop en vraag wanneer:** een document ontbreekt dat de datamodel- of
autorisatiekeuze bepaalt, of twee documenten elkaar tegenspreken op een punt dat de
architectuur raakt.

### Fase 1 — Domeinmodel
**Input:** business-rules + design (+ impact, indien uitbreiding van bestaande app).
**Doen:**
- Leid entiteiten, relaties, identiteitssleutels en unieke constraints af uit de
  business rules ("de aanbiedingsidentiteit is X + Y", "uniek per bron + versie", ...).
- Leg per entiteit vast: welke velden puur functioneel zijn, en welke puur voor
  auditeerbaarheid/traceerbaarheid (ontvangsttijd, hash, approver, `updatedAt`, ...).
- Volg bestaande naamconventies en tabel-/kolomstijl uit `impact.md` als die er is.
- Schema via Liquibase (of het bestaande migratiemechanisme), nooit `ddl-auto: update`
  in een omgeving waar dat al op `validate` staat.
**Output:** entiteiten + migratie(s), geen business logic nog.
**Definition of done:** module compileert, schema migreert, geen bestaande tabel/kolom
stilzwijgend hernoemd.

### Fase 2 — Kleinste verticale slice
**Input:** de "eerste verticale versie" / "kleinste eerste verticale slice" uit design
of de deploy-story.
**Doen:**
- Eén pad end-to-end: input → validatie (minimaal) → opslag → output. Geen volledige
  business-regelset nog, geen edge cases — enkel het pad dat het document als eerste,
  minimale versie beschrijft.
- Volg de bestaande laagindeling (`Web` roept `Service` aan, `Service` is
  transactioneel, `Dao`/repositories eronder). Geen aparte DTO-laag toevoegen als de
  bestaande code inline records/velden gebruikt, tenzij bewust en gemeld.
**Output:** werkend end-to-end pad, gericht getest (happy path).
**Definition of done:** de voorbeelden uit het probleem-document (geldige input,
verwacht resultaat) werken aantoonbaar.

### Fase 3 — Business rules & validatie
**Input:** business-rules.md volledig.
**Doen:**
- Implementeer élke regel uit het document, inclusief alle "mag niet leeg zijn",
  "moet uniek zijn binnen...", afrondingsregels, blokkeercondities.
- Zet elke regel expliciet om: business rule (wat) → technische implementatie (hoe) →
  data (wat wordt bewaard) → uitzondering (wat als het misloopt). Dit hoeft geen apart
  document te zijn, maar moet herkenbaar zijn in code/commit-uitleg.
- Nooit een numerieke waarde stilzwijgend op 0/leeg zetten bij een parse- of
  validatiefout — expliciet blokkeren/foutmelden.
**Output:** volledige validatie- en regellaag, met een test per regel (geldig,
ontbrekend, dubbel, ongeldig, grensgeval).
**Definition of done:** elke regel uit business-rules.md heeft een aantoonbare test.

### Fase 4 — Statussen, idempotentie & auditeerbaarheid
**Input:** business-rules (statussen), design (verwerkingsflow), acceptance
(traceerbaarheid).
**Doen:**
- Implementeer de volledige statusmachine zoals beschreven (bv. RECEIVED → SCREENED →
  PLANNED → APPROVED → PUBLISHED, of BLOCKED), inclusief welke overgangen *niet*
  gebruikt worden (zoals FAILED die in het model bestaat maar nog niet gezet wordt —
  dat blijf je zo tenzij gevraagd anders).
- Idempotentiesleutel (bv. hash + definitie) als unieke databaseconstraint, niet enkel
  als applicatiecheck — moet ook onder gelijktijdige verwerking standhouden.
- Auditvelden (approver, tijdstip, vorige waarde bij update) persistent, niet enkel in
  een logregel.
**Output:** herverwerking van identieke input is aantoonbaar veilig (test: tweemaal
dezelfde input → geen duplicaat).
**Definition of done:** acceptance.md-punten over traceerbaarheid zijn aantoonbaar waar.

### Fase 5 — Integratie in het bestaande/grotere systeem
**Input:** impact.md en/of integration.md.
**Alleen van toepassing als deze documenten bestaan.** Zo niet: sla deze fase over en
meld dat expliciet in de rapportage (fase 7).
**Doen:**
- Respecteer de module-/databasegrens zoals beschreven. Geen kruislingse afhankelijkheid
  die het document als grens aanmerkt.
- Volg de bestaande security-/rechtenconventie (bv. `PermissionService`,
  rol-per-actie). Voeg geen nieuwe generieke autorisatielaag toe als er al een
  conventie is.
- Herbruik bestaande entiteiten/tabellen die het document als "hergebruikt" aanmerkt in
  plaats van ze te dupliceren.
- Maak nooit méér aan dan het document toelaat (bv. "maakt geen centraal Article aan" —
  dat is een harde grens, geen optimalisatie).
**Output:** de feature werkt binnen de bestaande applicatie, met de bestaande
conventies, zonder de bestaande contracten te breken.
**Definition of done:** bestaande tests op geraakte modules slagen nog steeds; nieuwe
REST-contracten volgen de bestaande contractstijl.

### Fase 6 — Bouwblokken & stories in volgorde
**Input:** de `-stories.md` met de "voorgestelde bouwvolgorde".
**Alleen van toepassing als dit document bestaat.**
**Doen:**
- Bouw story per story, in de voorgestelde volgorde, niet parallel. Elke story: kleinste
  samenhangende wijziging → compileren → gericht testen op de acceptatiecriteria van
  díe story → volgende.
- Respecteer afhankelijkheden tussen stories expliciet vermeld in het document.
- Bij een "belangrijk risico" genoemd in een story: benoem het opnieuw in je
  rapportage voor die story, ook als je het risico al hebt gemitigeerd.
**Output:** elke story afgerond met zijn eigen acceptatiecriteria aantoonbaar waar,
voor de volgende story start.
**Definition of done:** alle stories uit de bouwvolgorde zijn behandeld, of expliciet
als "nog niet opgepakt, want afhankelijk van X" gemarkeerd.

### Fase 7 — Acceptatie, gerichte tests & rapportage
**Input:** acceptance.md.
**Doen:**
- Loop élk functioneel en traceerbaarheidscriterium uit acceptance.md na tegen wat
  gebouwd is.
- Draai het gerichte testcommando uit het document (bv. `mvn -pl Web -am test`), nooit
  de volledige reactor.
- Rapporteer volgens het vaste format (zie sectie 4).
**Output:** rapportage + expliciete lijst van wat wél gebouwd is, wat bewust nog niet
(met documentverwijzing), en welke open beslissingen uit fase 0 nog bij de mens liggen.

---

## 4. Rapportageformaat (na elke betekenisvolle fase, niet na elke regel code)

```
**Wat is er veranderd**
**Waarom**
**Bestanden/componenten**
**Belangrijk businessgedrag**
**Risico's of aannames**
**Hoe te testen** (incl. het gerichte testcommando)
```
Kort houden tenzij de wijziging complex is.

---

## 5. Ontdekkingen vastleggen

Kom je tijdens het bouwen een regel of beperking tegen die niet in de documentenset
stond? Benoem die expliciet, letterlijk zo:

> **Important business rule discovered**
>
> ...

> **Important technical constraint discovered**
>
> ...

Stel voor om dit terug te schrijven naar `-business-rules.md` (voor businessregels) of
`-design.md` / `-impact.md` (voor technische constraints), zodat kennis die in code zat
verborgen, gedocumenteerd raakt. Schrijf het pas terug na akkoord van de mens — dit
bestand zelf mag je niet stilzwijgend herschrijven.

---

## 6. Wanneer stoppen en vragen vs. doorgaan

**Doorgaan met een expliciete aanname** wanneer:
- de intentie uit de documenten duidelijk is, ook al is de eerste beschrijving geen
  volledige technische spec;
  - het gaat om een detail dat later zonder architectuurimpact aan te passen is.

**Stoppen en vragen** wanneer:
- twee documenten elkaar tegenspreken op datamodel, autorisatie of statusflow;
- een "nog te beslissen"-punt uit de documentenset de databasesleutel, de
  identiteitsdefinitie of de scope van een mutatie bepaalt;
- een wijziging een publieke methode, DB-kolom, API-veld of config-property zou
  hernoemen of een bestaand contract zou breken;
- een financiële berekening niet reconcilieert en de oorzaak niet eenduidig uit de
  documenten of code volgt.

---

## 7. Debuggen tijdens het bouwen

Bij een exception of falende test: niet meteen een fix voorstellen. Eerst: waar
ontstaat de fout, welke operatie liep er, meest waarschijnlijke oorzaak, hoe verifiëren
we die oorzaak — dan pas de fix. Onderscheid steeds **oorzaak** van **symptoom**. Geef
de snelste zinvolle check eerst.
