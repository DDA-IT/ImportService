# CatalogImport — storyset op basis van businessanalyse 2

## Uitgangspunt

Deze stories vertalen de vernieuwde businessanalyse naar kleine verticale slices. Het huidige project
blijft het vertrekpunt: één CSV wordt gearchiveerd, gescreend en vergeleken met een lokale bronstaat.
De stories veronderstellen geen directe ERP-schrijfbevoegdheid.

Een story met een open projectbesluit mag in schaduwmodus worden uitgewerkt, maar niet in productie
worden vrijgegeven voordat het besluit is vastgelegd. Zie ook
[`../analysis/current-project-vs-businessanalyse-2.md`](../analysis/current-project-vs-businessanalyse-2.md),
[`../../Businessanalyse_artikelimport_en_prijsacceptatie-2.md`](../../Businessanalyse_artikelimport_en_prijsacceptatie-2.md)
en [`../decisions.md`](../decisions.md).

## Fase A — ontvangst en broncontract

### ST-01 — Bronprofiel en leveringsenvelop

**Als** integratiebeheerder wil ik een versieerbaar bronprofiel en leveringsenvelop vastleggen,
**zodat** scope, onderdelen, volgorde en volledigheid van een levering expliciet zijn.

**Afhankelijk van:** D02 en D09.

**Acceptatiecriteria:**

- Het profiel bevat kanaal, formaatversie, identiteit, tijdzone, datasettype, scope en
  volledig-of-delta-betekenis.
- Een levering bewaart bron-ID, inhoudshash, bronvolgnummer, bronaanmaaktijd en
  volledigheidsbewijs afzonderlijk van ontvangsttijd. Ontbrekende bronmetadata wordt niet verzonnen.
- Dezelfde bytes zijn alleen idempotent binnen dezelfde bron, scope en betekenisvolle metadata;
  hergebruik van een leverings-ID met andere inhoud is een zichtbaar versieconflict.

### ST-02 — Samengestelde levering en veilige afwezigheid

**Als** catalogusbeheerder wil ik artikel-, prijs- en relatiebestanden als één logische levering
verwerken, **zodat** een ontbrekend afhankelijk onderdeel niet als verwijdering of nulprijs geldt.

**Afhankelijk van:** ST-01, D04 en D09.

**Acceptatiecriteria:**

- Eén levering kan meerdere bestanden bevatten; onafhankelijke scopes mogen afronden terwijl
  afhankelijke gegevens wachten.
- Ontbrekende onderdelen, pagina's of noodzakelijke deltaberichten krijgen een concrete
  *wacht op volledigheid/voorganger*-uitkomst.
- Afwezigheid veroorzaakt uitsluitend uitfasering na bewezen volledige scope, bronbeleid en een
  eventuele respijtperiode. Een gedeeltelijke levering verwijdert nooit gegevens.

### ST-03 — Uniform bronrecord voor CSV en XML

**Als** integratiebeheerder wil ik CSV en XML naar hetzelfde interne bronrecord normaliseren,
**zodat** validatie en acceptatie niet van het fysieke formaat afhangen.

**Afhankelijk van:** ST-01 en de pilotformaten uit D02.

**Acceptatiecriteria:**

- De adapter bewaart origineel bestand, locatie, parseerresultaat en normalisatieversie.
- Geneste CSV-lijsten en XML-kindelementen leveren dezelfde interne relatie-/lijststructuur op.
- Een lijstfout blokkeert minimaal de betrokken relatie of wijzigingsgroep wanneer de
  hoofdidentiteit leesbaar is.

## Fase B — catalogusdomein en relaties

### ST-04 — Centrale artikelidentiteit en leveranciersaanbieding

**Als** databeheerder wil ik een centraal artikel scheiden van een brongebonden
leveranciersaanbieding, **zodat** leverancierprijzen en herkomst niet worden vermengd.

**Afhankelijk van:** D01 en D03.

**Acceptatiecriteria:**

- Een centraal artikel heeft een stabiele interne identiteit en levenscyclus; een aanbieding bevat
  leverancier, catalogusscope, externe code en eigen geldigheid.
- De externe identiteit bevat minimaal leverancier, catalogusscope en artikelcode; hergebruik vraagt
  generatie of geldigheidsafbakening.
- Code, EAN of tekstscore is hoogstens matchbewijs. Meerdere kandidaten of tegenstrijdige kenmerken
  leiden tot review, niet automatische fusie.

### ST-05 — Relaties, supplementen en leveranciers-BOM-versies

**Als** databeheerder wil ik bronrelaties en leveranciers-BOM's versieerbaar beheren,
**zodat** alternatieven, toeslagen en samengestelde artikelen correct blijven.

**Afhankelijk van:** ST-04, D01 en D04.

**Acceptatiecriteria:**

- Een relatie heeft type, richting, bron, voorwaarden en geldigheid; zij is niet impliciet
  wederkerig of transitief.
- Een leveranciers-BOM bewaart kop, versie, basishoeveelheid, eenheid, componentregels en
  bronverwijzingen. De volledige resulterende BOM-versie is één acceptatie-eenheid.
- Onbekende verplichte componenten, ongeldige omrekening en verboden cycli laten alleen die BOM
  wachten. Eigen productie-BOM's en eigen kits blijven buiten scope.

### ST-06 — Bronvoorrang en bibliotheekindeling

**Als** databeheerder wil ik per veldgroep bepalen welke bron gezag heeft,
**zodat** fabrikantkenmerken, leveranciersprijzen en eigen bibliotheekindeling elkaar niet toevallig
overschrijven.

**Afhankelijk van:** ST-04 en D03.

**Acceptatiecriteria:**

- Delta wordt eerst per bron tegen de geaccepteerde bronstaat bepaald; pas daarna wordt de
  centrale voorkeurswaarde gekozen.
- Override, primaire bron, vervangende bron en gelijkwaardig conflict zijn zichtbaar en versieerbaar.
- Bibliotheeklidmaatschappen en lokale presentatiewaarden zijn expliciete regels of overrides met
  een verschiloverzicht.

## Fase C — prijsvoorwaarden en geldigheid

### ST-07 — Prijscontext, verpakking en volledige voorwaarden

**Als** prijsverantwoordelijke wil ik prijzen inclusief contract, verpakking, eenheid, geldigheid en
staffels opslaan, **zodat** een bedrag altijd in de juiste economische context wordt beoordeeld.

**Afhankelijk van:** D01, D04, D05 en D08.

**Acceptatiecriteria:**

- De context bevat aanbieding, contract/aankoopvereniging, prijssoort, valuta, belastingbasis,
  promotiecontext, verpakking, prijs- en besteleenheid.
- Brutoprijs, korting, toeslag, netto bedrag en staffels worden afzonderlijk bewaard met vaste
  rekenvolgorde en afronding.
- Een gewijzigde doosinhoud, minimum, bestelveelvoud, staffelgrens, prijsbasis of geldigheid is een
  betekenisvolle delta, ook wanneer de stukprijs gelijk blijft.

### ST-08 — Deterministische prijsbeoordeling met bewijs

**Als** prijsverantwoordelijke wil ik een prijsafwijking kunnen verklaren en bevestigen,
**zodat** grote maar onderbouwde wijzigingen niet worden afgewezen en kleine opeenvolgende stijgingen
niet onzichtbaar blijven.

**Afhankelijk van:** ST-07, D06 en D07.

**Acceptatiecriteria:**

- Historiek bevat uitsluitend aanvaarde waarden uit dezelfde vergelijkingscontext; openstaande of
  afgewezen kandidaten zijn nooit referentiemateriaal.
- De beoordeling bewaart gebruikte versie-ID's, referenties, prijsanker, regelversie,
  beoordelingsmoment en berekening. De kandidaat telt nooit mee in zijn eigen historie.
- Een onbekende koers, verpakkingsfactor of verplichte prijscomponent leidt tot wachten of review,
  nooit tot een geschatte prijs.

### ST-09 — Geldigheid en hercontrole bij activering

**Als** catalogusbeheerder wil ik toekomstige prijsversies veilig inplannen,
**zodat** een prijs niet te vroeg actief wordt of na gewijzigde afhankelijkheden blind wordt toegepast.

**Afhankelijk van:** ST-07 en ST-08.

**Acceptatiecriteria:**

- Import-, voorstel- en prijsstatus zijn afzonderlijk. Prijsversies zijn ingepland, actief, verlopen
  of ingetrokken.
- Geldigheidsintervallen zijn halfopen en datumprijzen gebruiken de afgesproken brontijdzone.
- Vóór activering worden context, bevoegdheid, bewijs en afhankelijkheden opnieuw gecontroleerd.
  Verlopen prijzen worden nooit stil verlengd.

## Fase D — beoordeling en gebruikerswerk

### ST-10 — Acceptatie per wijzigingsgroep

**Als** databeheerder of prijsverantwoordelijke wil ik een samenhangende wijzigingsgroep accepteren,
weigeren of laten wachten, **zodat** prijs, verpakking en verplichte toeslag niet gedeeltelijk worden
gepubliceerd.

**Afhankelijk van:** ST-05, ST-07 en D10.

**Acceptatiecriteria:**

- Standaardgroepen zijn identiteit, beschrijving, prijsvoorwaarden, relaties en indeling;
  afhankelijkheden kunnen groepen samenvoegen.
- Een besluit bewaart oude/kandidaatwaarde, basisversie, regelversie, actor, reden, bewijs,
  tijdstip en impactscope.
- Afwijzing is een expliciete einduitkomst. Een wachtende of afgewezen groep blokkeert geen
  onafhankelijke, geldige groep.

### ST-11 — Behandelgevallen, rollen en dashboards

**Als** beheerder wil ik een gerichte werkvoorraad met bevoegde gebruikers,
**zodat** gedeelde technische oorzaken één keer worden opgelost en uitzonderingen traceerbaar zijn.

**Afhankelijk van:** ST-10, D10 en D14.

**Acceptatiecriteria:**

- Een behandelgeval heeft oorzaak, eigenaar, prioriteit, status, afhankelijkheden, actie en
  gereedcriterium.
- Een bulkfout of ontbrekende ERP-context levert één gedeeld behandelgeval met zichtbare gevolgen,
  niet duizenden identieke taken.
- Gecontroleerde handelingen bewaren geverifieerde identiteit, rol en reden. Vier-ogen geldt alleen
  wanneer het vastgelegde beleid dit vereist; een requestveld met een naam is geen bewijs.

## Fase E — ERP en gecontroleerde Prodis-publicatie

### ST-12 — ERP-leescontract en doelconflict

**Als** integratiebeheerder wil ik ERP-referenties betrouwbaar uitlezen,
**zodat** CatalogImport ERP-eigenaarschap respecteert en geen tweede ERP wordt.

**Afhankelijk van:** D03 en D11.

**Acceptatiecriteria:**

- Per dataset zijn eigenaar, leesbron, sleutel, actualiteit, foutgedrag en gebruik vastgelegd.
- Ontbrekende ERP-context blokkeert alleen afhankelijke operationele functies; bronopslag en
  onafhankelijke screening mogen doorgaan.
- Teruggelezen eigen publicaties worden via oorsprong en correlatie herkend, zonder synchronisatielus
  of onterecht prijsbewijs.

### ST-13 — PSIMPORT-projectie en veilige vrijgave

**Als** integratiebeheerder wil ik een geaccepteerde wijziging als volledige PSIMPORT-opdracht
projecteren, **zodat** Prodis alleen consistente, toegestane wijzigingen verwerkt.

**Afhankelijk van:** ST-10, ST-11, ST-12 en D11.

**Acceptatiecriteria:**

- Ieder PSIMPORT-veld heeft een versieerbare invulregel met eigenaar, datatype, bron/methode,
  null-/wis-/behoudgedrag, afhankelijkheden en foutclassificatie.
- Een prijsdelta bewaart niet-betrokken ERP-velden aantoonbaar of gebruikt een bewezen patchprotocol;
  leeg of nul betekent nooit impliciet behouden.
- Alleen een complete, geaccepteerde en actuele projectie krijgt de bevestigde verwerkingsactie en
  `ARIMP_Verwerken=True`. Wachtende, afgewezen of vervangen kandidaten leveren geen uitvoerbare rij.

### ST-14 — Toepassingsresultaat, reconciliatie en herstel

**Als** controleur wil ik het werkelijke Prodis-resultaat aan iedere opdracht kunnen koppelen,
**zodat** een time-out of verdwenen tijdelijke rij niet als geslaagde toepassing geldt.

**Afhankelijk van:** ST-13 en D11–D13.

**Acceptatiecriteria:**

- Het afleverregister bewaart opdracht-ID, bron/import, doelidentiteit, actie, configuratieversies,
  payloadhash, toegewezen record, pogingen en resultaat.
- Een opdracht kent minimaal de statussen klaar voor projectie, wacht op conflict/contract, klaar
  voor aflevering, geschreven, resultaat onbekend, toegepast, afgewezen en herstel vereist.
- Onbekend resultaat wordt gericht gereconcilieerd vóór herhaling. Correctie na een foute toepassing
  is een nieuwe traceerbare opdracht; dubbele create/delete of blind overschrijven is verboden.

## Fase F — kwaliteitssturing en vrijgave

### ST-15 — Pilot, capaciteit en vrijgavebewijs

**Als** proceseigenaar wil ik een representatieve schaduwrun en meetbare vrijgavecriteria,
**zodat** productie niet alleen op een geslaagde demo wordt gebaseerd.

**Afhankelijk van:** D12, D13 en D15.

**Acceptatiecriteria:**

- De pilot bevat grote bestanden, fouten, identiteitsconflicten, verpakkingen, staffels,
  leveranciers-BOM's, prijsuitschieters en toepasselijke PSIMPORT-acties.
- Tests bewijzen idempotentie, parallelle verwerking, technische herstart, doelconflict,
  gedeeltelijke levering en veilige resultaatsreconciliatie.
- Productievrijgave toetst de toepasselijke NF01–NF10; een schaduwtest schrijft geen operationele
  PSIMPORT-rij met `Verwerken=True`.

## Buiten scope

Deze stories realiseren geen automatische onderhandeling, bestelling, betaling,
voorraadoptimalisatie, berekening van verkoopmarges, wijziging van historische orders/facturen,
eigen productie-BOM's of eigen kitbeheer. Een zelflerend prijsmodel dat acceptatiepoorten omzeilt
valt eveneens buiten scope.
