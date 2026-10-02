# CatalogImport — begrippen en statusreferentie

Stand: 2026-09-25. Hoofdhandleiding:
[`README.md`](README.md). Stappen: [`standaardflows.md`](standaardflows.md).

De enumwaarden hieronder komen uit `Domain/src/main/java/be/dda/catalogimport/domain/`. Een waarde die
"gedeclareerd voor Fase 5" heet, bestaat in het model maar wordt vandaag niet gezet.

**Inhoud:** [Begrippen A-Z](#begrippen-a-z) · [Batchstatus](#batchstatus-importbatchstatus) ·
[Eindoordeel](#eindoordeel-validationresult) · [Creatiebeleid](#creatiebeleid-creationoutcome) ·
[Mutatiesoorten](#mutatiesoorten-mutationactiontype) · [Mutatiestatussen](#mutatiestatussen-mutationstatus) ·
[Statusredenen](#veelvoorkomende-statusredenen-statusreason) · [Bundelstatus](#bundelstatus-publicationbundlestatus) ·
[Doelmodus](#doelmodus-publicationtargetmode) · [Beslissingen](#beslissingen-bundledecisionkind-en-bundledecisionscope) ·
[Ernst](#ernst-van-een-issue-rowissueseverity) · [Versie](#versiestatus-revisionstatus) ·
[Overig](#overige-opsommingen) · [Woordenlijst voor de schermen](#woordenlijst-voor-de-schermen)

## Begrippen A-Z

| Begrip | Uitleg |
| --- | --- |
| **accept-baseline** | Geauditeerde actie die een `SCREENED` batch aanvaardt als nulmeting van de lokale bronstaat. Alles-of-niets: een volledige aanvaarding of geen wijzigingen. Geen publicatie. Kan één keer per batch en sluit bundel-opname uit. Verplicht: `acceptedBy` (niet `system`) en `reason`. |
| **Actor** | De naam die bij een schrijfactie wordt vastgelegd. Sinds 5-AUTH komt die naam uit de Keycloak-login (`preferred_username`); de actorvelden in een request zijn optioneel en moeten, indien aanwezig, gelijk zijn aan die naam (anders 400 `ACTOR_FIELD_MISMATCH`). |
| **Aanbieding** | Eén artikel van een leverancier, geïdentificeerd door de aanbiedingsidentiteit. |
| **Aanbiedingsidentiteit** | Leverancier + leveranciersgroep + leveranciersreferentie, optioneel + kortingscode. Bibliotheek en leverancier of aankoopvereniging zijn scope, geen sleutel. Een lege component verwerpt de regel. |
| **Archief** | Plaats op het bestandssysteem waar het originele bestand ongewijzigd bewaard wordt, met SHA-256. |
| **Batch** | Eén screening van één levering tegen één versie van de beschrijving van het bestand. Eenheid van de werkvoorraad. |
| **Beslissing** | Vastlegging (wie, wanneer, waarom, van welke status) dat een mutatie is goedgekeurd of afgekeurd of dat een bundel is bevroren of geannuleerd. Het register is alleen-toevoegen. |
| **Bevriezen (freeze)** | Sluit een bundel af (`FROZEN`) na controle; keurt resterende `PLANNED` mee goed op naam van de bevriezer. Onomkeerbaar in de applicatie. |
| **Bibliotheek (`libraryCode`)** | Doelbibliotheek van een koppeling (bv. `PSARF012`). Scope, geen deel van de identiteit. |
| **Blokkeerreden (`blockedCode`)** | Reden waarom een batch `BLOCKED` is. |
| **Invulpunt** | Benoemde, getypeerde plaats in een sjabloon waar een waarde ingevuld wordt. Geldt voor de hele beschrijving (bij het aanmaken vastgezet, `DEFINITION`) of per koppeling (`LINK`). |
| **Leverancier of aankoopvereniging** | Wie het bestand aanlevert: `SUPPLIER` of `PURCHASING_ASSOCIATION`. |
| **Bronstaat** | Wat het systeem als huidige stand per aanbieding beschouwt. Alleen `accept-baseline` schrijft er vandaag in (herkomst `BASELINE_ACCEPTED`). |
| **Bulkincident** | Een foutgroep die boven de percentagedrempel valt (bv. `BULK_PRICE_INCIDENT`). Leidt tot beoordeling. |
| **`continue`** | `POST /batches/{id}/continue`: hervat een batch op `MUTATING`. Wordt niet op naam vastgelegd. |
| **Creatiebeleid** | Beoordeelt of nieuwe aanbiedingen zonder tussenkomst aangemaakt mogen worden (`creationOutcome`). |
| **Beschrijving van het bestand (ImportDefinition)** | Beschrijving van hoe een bestand van een leverancier of aankoopvereniging gelezen wordt. Heeft versies. |
| **Delta** | Het verschil tussen een levering en de bronstaat: nieuw, gewijzigd, ongewijzigd. |
| **Doelmodus** | `targetMode` van een bundel: `SIMULATION`, `TRIAL_LIBRARY`, `PRODUCTION`. |
| **Eindoordeel** | `validationResult`; de inhoudelijke uitkomst van een screening; `null` = niet vastgesteld. |
| **Foutgroep (issue group)** | Samenvatting van gelijksoortige vaststellingen met het echte aantal (`occurrenceCount`). Ontstaat vanaf 10 gelijksoortige vaststellingen. |
| **freeze-check** | `GET /bundles/{id}/freeze-check`: droogloop van het bevriezen; momentopname zonder slot. |
| **Groepsbeslissing** | `POST /bundles/{id}/decisions`: één beslissing over een gefilterde selectie. Raakt alleen `CREATE`/`UPDATE` in `PLANNED` of `AWAITING_APPROVAL` zonder beslissing. Minstens één filterveld verplicht. |
| **`identityHash`** | SHA-256 (64 hex-tekens) van de aanbiedingsidentiteit; sleutel van de wijzigingsgroep. `null` bij de `IMPORT_MARKER`. |
| **Importmarker (`IMPORT_MARKER`)** | Mutatie die vastlegt dat een screening is afgerond, ook als er niets veranderde. |
| **INITIAL_LOAD** | Creatiebeleid: de koppeling had nog geen actieve aanbieding; alle creaties wachten op goedkeuring. |
| **Issue** | Eén vaststelling van de screening (rijnummer, code, veld, bronwaarde, ernst). Voorbeeldregels; het werkelijke aantal staat in de foutgroep. |
| **Koppeling (ImportLink)** | Verbindt een beschrijving van het bestand met een leverancier en een bibliotheek. |
| **Kritieke kolom / kritieke lijn** | Een kolom die de configuratie als kritiek markeert; een afgewezen regel met een fout op zo'n kolom is een kritieke lijn en vraagt beoordeling. |
| **Levering (Delivery)** | Eén aangeleverd bestand met een `deliveryReference`. |
| **`deliveryReference`** | Referentie van een levering; idempotentiesleutel per taak. Het uploadscherm stelt `<bestandsnaam>#<12 hex SHA-256>` voor (deterministisch); u kunt ze aanpassen. |
| **`expirableCount`** | Veld van `BundleDetail`: aantal mutaties dat bij annuleren `EXPIRED` wordt. `null` bij een geannuleerde bundel (niet vastgesteld); 0 is geldig. Getoond in de annuleerdialoog. |
| **PREVIEW (PSIMPORT-preview)** | `GET /bundles/{id}/psimport-preview`: read-only, niet-contractuele projectie van een `FROZEN` bundel (`previewOnly`, `UNVERIFIED_FIELD_INVENTORY`). Geen echt PSIMPORT-formaat, geen publicatie. |
| **Mutatie** | Eén voorgestelde wijziging in het mutatieplan van een batch. |
| **Mutatieplan** | De lijst mutaties van een batch. |
| **Nulmeting** | Zie *accept-baseline*. |
| **Publicatiebundel** | Verzameling batches die samen beoordeeld en bevroren wordt. |
| **Versie** | Een versie van een beschrijving van het bestand (`Revision`): `DRAFT` → `ACTIVE` → `SUPERSEDED`. Een batch is gescreend tegen één vaste versie. |
| **Setup-API** | Ontwikkelhulp voor het inrichten van een keten; standaard uit (`catalogimport.setup-api.enabled`); vereist een login en het recht `manage`/`read`; de vlag blijft een aparte, tweede beveiliging (vlag uit = 404). |
| **Recht** | Toestemming voor een soort actie: `catalogImport.read` (Lezen), `.manage` (Beheren: upload, hervatten, bundel aanmaken) of `.approve` (Goedkeuren: aanvaarden, beslissen, bevriezen, annuleren). `approve` omvat `manage` en `read`; `manage` omvat `read`. Zonder recht: 403 `PERMISSION_DENIED`. |
| **Rechtenbron** | Waar de rechten van een gebruiker vandaan komen. Nu een lokale YAML-lijst (`catalogimport.permissions.grants`); de koppeling met Prodis is nog niet gebouwd. Onbereikbaar = 503 `PERMISSION_SOURCE_UNAVAILABLE`. Zonder toekenning heeft niemand iets. |
| **Effectief recht** | Wat u uiteindelijk mag, nadat de hiërarchie is toegepast op de ruwe toekenning; dit geeft `GET /me` terug in `permissions` en het scherm gebruikt het om knoppen uit te schakelen. |
| **Sjabloon** | Beschrijving van het bestand met `usageType = REUSABLE_TEMPLATE`; kan nooit een koppeling of batch krijgen. |
| **Statusreden (`statusReason`)** | Tekstcode die zegt waarom een mutatie in een status staat. Filter is exact en hoofdlettergevoelig. |
| **Taak (CatalogImportTask)** | Ingang voor leveringen op een koppeling. Vandaag alleen `MANUAL`. |
| **Teller** | Aantal op een batch of bundel. `null` = niet vastgesteld, nooit 0. |
| **Werkvoorraad** | Scherm 0: lijst van batches met telblokken en filters. |
| **Wijzigingsgroep** | Alle mutaties met dezelfde `identityHash` (één aanbieding). Filter: `?identityHash=`. |

## Batchstatus (`ImportBatchStatus`)

| Waarde | Terminaal | Betekenis |
| --- | --- | --- |
| `RECEIVED` | nee | geregistreerd, nog niet gestart |
| `SCREENING` | nee | bronbestand wordt gelezen en gestaged |
| `MUTATING` | nee | delta wordt bepaald en mutatielijst gemaakt; hervatbaar (`continue`) |
| `SCREENED` | ja | screening afgerond; mutaties en marker geschreven |
| `BLOCKED` | ja | contract-, structuur-, configuratie- of drempelfout; geen inhoudelijke mutaties, wel een marker |
| `FAILED` | ja | technische fout of onderbreking; geen marker, geen mutaties |
| `BASELINE_ACCEPTED` | ja | aanvaard als nulmeting van de bronstaat |

## Eindoordeel (`ValidationResult`)

| Waarde | Betekenis |
| --- | --- |
| `VALID` | geen probleem van betekenis |
| `VALID_WITH_WARNINGS` | enkel waarschuwingen; bruikbaar |
| `REVIEW_REQUIRED` | menselijke beoordeling nodig (wachtende creaties, bulkincident, fout op een kritieke kolom) |
| `BLOCKING` | kritiek of blokkerend probleem; niets gaat door zonder ingreep |
| `null` | niet vastgesteld — nooit lezen als 0 of geldig |

## Creatiebeleid (`CreationOutcome`)

| Waarde | Betekenis | `CREATE`-mutaties |
| --- | --- | --- |
| `AUTOMATIC` | binnen de drempel of geen creaties | `PLANNED` |
| `INITIAL_LOAD` | koppeling had nog geen actieve aanbieding | `AWAITING_APPROVAL` (`INITIAL_LOAD_REQUIRES_APPROVAL`) |
| `THRESHOLD_EXCEEDED` | creaties boven `creationThresholdSharePercent` van de bestaande omvang | `AWAITING_APPROVAL` (`BULK_CREATION_INCIDENT`) |
| `null` | nog niet beoordeeld | — |

## Mutatiesoorten (`MutationActionType`)

| Waarde | Betekenis |
| --- | --- |
| `CREATE` | nieuwe aanbieding (identiteit onbekend in de bronstaat) |
| `UPDATE` | gewijzigde aanbieding (hash- en domeinniveau, plus basisprijs voor/na; `domainMask` zegt wat wijzigde, bv. `PRICE`) |
| `IDENTITY_REFERENCE_INCIDENT` | een kritieke koppelreferentie (EAN, PIM-ID, CAB-ID, ...) is gewijzigd, verwijderd, hergebruikt of dubbelzinnig; wijzigt niets, wacht op beoordeling; in Fase 4 geen beslispad |
| `IMPORT_MARKER` | vastlegging dat een screening werd afgerond; geen inhoudelijke mutatie |

## Mutatiestatussen (`MutationStatus`)

| Waarde | Betekenis | Wordt vandaag gezet? |
| --- | --- | --- |
| `PLANNED` | gepland; nog geen beslissing (o.a. `UPDATE`, en `CREATE` binnen de drempel) | ja |
| `AWAITING_APPROVAL` | wacht op goedkeuring (`INITIAL_LOAD`, bulk, identiteitsincident) | ja |
| `BLOCKED` | vastgehouden (kritiek referentie-incident); geen goedkeur-/afkeurpad in Fase 4 | ja |
| `READY_FOR_PUBLICATION` | goedgekeurd (individueel, groep of bij bevriezen) | ja |
| `REJECTED` | afgekeurd (reden verplicht) | ja |
| `SKIPPED` | overgeslagen door `accept-baseline` (reden `BASELINE_ACCEPTED_WITHOUT_PUBLICATION`) | ja |
| `RECORDED` | vastgelegd; status van de `IMPORT_MARKER` | ja |
| `EXPIRED` | vervallen door annulering van de bundel | ja |
| `IN_PROGRESS` | publicatie loopt | nee (Fase 5) |
| `PUBLISHED` | gepubliceerd | nee (Fase 5) |
| `TECHNICALLY_FAILED` | publicatie technisch mislukt | nee (Fase 5) |

Beslisbaar (individueel): `PLANNED`, `AWAITING_APPROVAL`, en als herziening `READY_FOR_PUBLICATION` en
`REJECTED`. `EXPIRED` en de statussen van Fase 5 worden vandaag niet gezet door de screening of beslissingen;
dat `EXPIRED` optreedt bij annuleren en `RECORDED`/`SKIPPED` zoals hierboven, volgt uit de code-documentatie.

## Veelvoorkomende statusredenen (`statusReason`)

Bekende waarden (niet uitputtend; **nog te verifiëren** of er meer bestaan):

| Waarde | Betekenis |
| --- | --- |
| `INITIAL_LOAD_REQUIRES_APPROVAL` | creatie wacht omdat de koppeling nog geen bronstaat heeft |
| `BULK_CREATION_INCIDENT` | creaties boven de drempel |
| `BULK_PRICE_INCIDENT` | bulkincident op prijzen |
| `BULK_IDENTITY_INCIDENT` | bulkincident op identiteitsreferenties |
| `BASELINE_ACCEPTED_WITHOUT_PUBLICATION` | mutatie `SKIPPED` door `accept-baseline` |

## Bundelstatus (`PublicationBundleStatus`)

| Waarde | Betekenis | Gebruikt? |
| --- | --- | --- |
| `ASSEMBLING` | in opbouw: batches toevoegen/verwijderen, mutaties beoordelen; enige status die wijzigingen aanvaardt | ja |
| `FROZEN` | bevroren; klaar voor publicatie; nog te annuleren | ja |
| `CANCELLED` | geannuleerd; batches vrij; terminaal | ja |
| `PUBLISHING` | publicatie loopt | nee (Fase 5) |
| `PARTIALLY_PUBLISHED` | deels gepubliceerd | nee (Fase 5) |
| `PUBLISHED` | volledig gepubliceerd; terminaal | nee (Fase 5) |
| `PUBLICATION_FAILED` | publicatie technisch mislukt | nee (Fase 5) |

## Doelmodus (`PublicationTargetMode`)

| Waarde | Betekenis |
| --- | --- |
| `SIMULATION` | proefpublicatie zonder effect op een echte bibliotheek |
| `TRIAL_LIBRARY` | publicatie naar een controlebibliotheek |
| `PRODUCTION` | echte publicatie naar ProDisWebbase/Pervasive (later; in de UI met waarschuwing) |

## Beslissingen (`BundleDecisionKind` en `BundleDecisionScope`)

| `BundleDecisionKind` | Betekenis |
| --- | --- |
| `APPROVE` | mutatie(s) goedgekeurd (individueel of groep) |
| `REJECT` | mutatie(s) afgekeurd; reden altijd verplicht |
| `AUTO_APPROVE_PLANNED` | resterende `PLANNED` bij het bevriezen goedgekeurd op naam van de bevriezer |
| `FREEZE` | bundel bevroren |
| `CANCEL` | bundel geannuleerd |

| `BundleDecisionScope` | Betekenis |
| --- | --- |
| `MUTATION` | precies één mutatie |
| `GROUP` | een groepsactie via een filter; het echte aantal staat in `affectedCount` |
| `BUNDLE` | een actie op de hele bundel (bevriezen, annuleren) |

## Ernst van een issue (`RowIssueSeverity`)

| Waarde | Betekenis |
| --- | --- |
| `CRITICAL` | onbetrouwbare identiteit of kritieke referentie; regel vastgehouden; beoordeling nodig |
| `BLOCKING` | de hele levering is onbruikbaar (contract-, structuur-, configuratie- of drempelfout) |
| `ERROR` | de regel wordt verworpen (of de batch geblokkeerd) |
| `WARNING` | informatief; regel blijft geldig |
| `INFO` | puur informatief |

## Versiestatus (`RevisionStatus`)

| Waarde | Betekenis |
| --- | --- |
| `DRAFT` | concept; bewerkbaar; niet inzetbaar |
| `SCREENING` | screening van een testbestand loopt |
| `REVIEW_REQUIRED` | beoordeling nodig |
| `PENDING_APPROVAL` | wacht op goedkeuring |
| `ACTIVE` | actief; hoogstens één per beschrijving van het bestand; mag voor nieuwe leveringen gebruikt worden |
| `SUPERSEDED` | vervangen; leesbaar, krijgt geen nieuwe leveringen |
| `WITHDRAWN` | ingetrokken; mag niet geactiveerd worden |

De setup-API gebruikt vandaag enkel `DRAFT`, `ACTIVE` en `SUPERSEDED` (via aanmaken en activeren); of de
overige waarden ergens gezet worden is **nog te verifiëren**.

## Overige opsommingen

| Enum | Waarden | Betekenis |
| --- | --- | --- |
| `SourceOrganisationType` | `SUPPLIER`, `PURCHASING_ASSOCIATION` | leverancier of aankoopvereniging |
| `DefinitionUsageType` | `OWN_DEFINITION`, `REUSABLE_TEMPLATE` | eigen beschrijving of herbruikbaar sjabloon |
| `TaskTriggerType` | `MANUAL`, `SCHEDULED` | manueel gestart of gepland (scheduler bestaat nog niet) |
| `TaskRunStatus` | `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` | status van één taakuitvoering; `PENDING` en `RUNNING` houden de concurrency-token vast |
| `SourceStateOrigin` | `BASELINE_ACCEPTED`, `PUBLISHED` | herkomst van een bronstaatrij; `PUBLISHED` is Fase 5 |
| `IdentityProfileKind` | `THREE_PART`, `FOUR_PART_WITH_DISCOUNT_CODE` | opbouw van de aanbiedingsidentiteit: met of zonder kortingscode |
| `Criticality` | `CRITICAL`, `NON_CRITICAL` | kritiek of niet-kritiek per kolom |

## Belangrijke drempelparameters (per versie, altijd een percentage)

| Parameter | Standaard | Betekenis |
| --- | --- | --- |
| `creationThresholdSharePercent` | 1 (**nog te verifiëren** als kolomdefault) | boven dit percentage van de bestaande omvang wachten creaties op goedkeuring |
| `maxCriticalSharePercent` | 1 (idem) | boven dit percentage kritieke lijnen wordt de levering `BLOCKED`; eronder `REVIEW_REQUIRED` |
| `maxRejectedSharePercent` | niet geconfigureerd | drempel voor verworpen regels |
| `bulkIncidentSharePercent` | 1 (idem) | bulkincident boven dit aandeel |

Vergelijking: `aantal × 100 > percentage × omvang`; exact op de grens is niet overschreden. De demo/het
scenario zet `creationThresholdSharePercent` op 10 en `maxCriticalSharePercent` op 25.

## Woordenlijst voor de schermen

Dit is de **enige redactionele bron** voor de woorden en uitleg die de schermen tonen. Het woordenboek in
`Frontend/src/terms/dictionary.ts` volgt deze tabellen letterlijk; `Frontend/src/test/terms.test.ts` faalt als
ze verschillen. Pas een tekst dus eerst hier aan en neem ze dan over in het woordenboek. De kolom *Code* is de
technische waarde (klein, voor support); *Nederlands* en *Uitleg* zijn wat de gebruiker ziet. De lijst hierboven
(Batchstatus, Eindoordeel, ...) blijft de technische referentie.

### Batchstatus

<!-- terms:batchStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `RECEIVED` | Ontvangen | Geregistreerd; het lezen is nog niet gestart. |
| `SCREENING` | Wordt gecontroleerd | Het bestand wordt regel per regel gelezen. |
| `MUTATING` | Wijzigingen worden bepaald | Vergelijking met de bekende stand; kan hervat worden. |
| `SCREENED` | Gecontroleerd | Klaar; de voorgestelde wijzigingen staan klaar. |
| `BLOCKED` | Tegengehouden | De levering is als geheel onbruikbaar. |
| `FAILED` | Technisch mislukt | Er ging technisch iets mis; opnieuw proberen is mogelijk. |
| `BASELINE_ACCEPTED` | Aanvaard als nulmeting | Aanvaard als vertrekpunt; er is niets gepubliceerd. |

### Eindoordeel

<!-- terms:validationResult -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `VALID` | Geldig | Er is niets van betekenis gevonden. |
| `VALID_WITH_WARNINGS` | Geldig met opmerkingen | Er zijn alleen opmerkingen; de levering is bruikbaar. |
| `REVIEW_REQUIRED` | Beoordeling nodig | Een mens moet kijken, bijvoorbeeld bij veel nieuwe artikelen of een kritieke fout. |
| `BLOCKING` | Blokkerend | Er is een kritiek probleem; zonder ingreep gaat er niets door. |
| `null` | Nog niet bepaald | Het eindoordeel is nog niet vastgesteld; lees dit nooit als geldig. |

### Status van een wijziging

<!-- terms:mutationStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `PLANNED` | Gepland | Wordt bij het bevriezen van de bundel mee goedgekeurd. |
| `AWAITING_APPROVAL` | Wacht op goedkeuring | Vraagt een uitdrukkelijke beslissing. |
| `READY_FOR_PUBLICATION` | Goedgekeurd | Goedgekeurd en klaar om gepubliceerd te worden. |
| `REJECTED` | Afgekeurd | Afgekeurd; er is altijd een reden vastgelegd. |
| `BLOCKED` | Tegengehouden | Kritiek herkenningsprobleem; geen beslissing mogelijk. |
| `SKIPPED` | Overgeslagen | Niet gepubliceerd omdat de batch als nulmeting aanvaard is. |
| `RECORDED` | Vastgelegd | Legt vast dat de controle afgerond is; geen echte wijziging. |
| `EXPIRED` | Vervallen | Door het annuleren van de bundel. |
| `IN_PROGRESS` | Wordt gepubliceerd | De publicatie van deze wijziging loopt. |
| `PUBLISHED` | Gepubliceerd | Deze wijziging is gepubliceerd. |
| `TECHNICALLY_FAILED` | Technisch mislukt | De publicatie van deze wijziging is technisch mislukt. |

### Soort wijziging

<!-- terms:mutationAction -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `CREATE` | Nieuw artikel | Een artikel dat nog niet bekend was. |
| `UPDATE` | Wijziging | Een bekend artikel waarvan iets veranderd is, bijvoorbeeld de prijs. |
| `IDENTITY_REFERENCE_INCIDENT` | Herkenningsprobleem | Een kritieke verwijzing om het artikel te herkennen is gewijzigd, verdwenen, hergebruikt of dubbelzinnig; er wijzigt niets. |
| `IMPORT_MARKER` | Afgeronde controle | Legt vast dat de controle van de levering klaar is, ook als er niets veranderde. |

### Status van een bundel

<!-- terms:bundleStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `ASSEMBLING` | In opbouw | Batches toevoegen en wijzigingen beoordelen kan nog. |
| `FROZEN` | Bevroren | Afgesloten en klaar voor publicatie; nog te annuleren. |
| `CANCELLED` | Geannuleerd | Niet doorgegaan; de batches zijn weer vrij. |
| `PUBLISHING` | Wordt gepubliceerd | De publicatie van de bundel loopt. |
| `PARTIALLY_PUBLISHED` | Deels gepubliceerd | Een deel van de bundel is gepubliceerd. |
| `PUBLISHED` | Gepubliceerd | De hele bundel is gepubliceerd. |
| `PUBLICATION_FAILED` | Publicatie mislukt | De publicatie is technisch mislukt. |

### Status van een versie van de inrichting

<!-- terms:revisionStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `DRAFT` | Concept | Nog aan te passen en nog niet gebruikt voor leveringen. |
| `SCREENING` | Wordt gecontroleerd | Een testbestand wordt gecontroleerd. |
| `REVIEW_REQUIRED` | Beoordeling nodig | Een mens moet de uitkomst van de test bekijken. |
| `PENDING_APPROVAL` | Wacht op goedkeuring | Wacht op een uitdrukkelijke goedkeuring. |
| `ACTIVE` | Actief | Hiermee worden nieuwe leveringen gelezen; er is er hoogstens één. |
| `SUPERSEDED` | Vervangen | Niet meer in gebruik, blijft leesbaar om oude leveringen te verklaren. |
| `WITHDRAWN` | Ingetrokken | Ingetrokken en kan niet meer in gebruik genomen worden. |

### Doelmodus

<!-- terms:targetMode -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `SIMULATION` | Proefpublicatie | Er wordt niets naar Prodis geschreven. |
| `TRIAL_LIBRARY` | Controlebibliotheek | De publicatie gaat naar een bibliotheek om te controleren, niet naar de echte. |
| `PRODUCTION` | Echte publicatie | De publicatie gaat naar de echte bibliotheek. |

### Soort organisatie

<!-- terms:organisationType -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `SUPPLIER` | Leverancier | Een bedrijf dat zijn eigen bestand levert. |
| `PURCHASING_ASSOCIATION` | Aankoopvereniging | Een organisatie die bestanden levert namens meerdere leveranciers. |

### Hoe een levering start

<!-- terms:taskTrigger -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `MANUAL` | Handmatig | U start elke levering zelf door een bestand op te laden. |
| `SCHEDULED` | Gepland | Leveringen worden automatisch op vaste tijden opgehaald. |

### Hoe een artikel herkend wordt

<!-- terms:identityProfile -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `THREE_PART` | Leverancier + groep + referentie | Zo herkennen we hetzelfde artikel in de volgende levering. |
| `FOUR_PART_WITH_DISCOUNT_CODE` | Leverancier + groep + referentie + kortingscode | Zo herkennen we hetzelfde artikel in de volgende levering, met de kortingscode erbij. |

### Hoe kolommen herkend worden

<!-- terms:fieldReferenceKind -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `HEADER_NAME` | Kolommen op naam | De kolommen worden herkend aan de naam in de eerste regel van het bestand. |
| `COLUMN_INDEX` | Kolommen op positie | De kolommen worden herkend aan hun volgnummer; nodig als het bestand geen kolomnamen heeft. |

### Ernst van een vaststelling

<!-- terms:severity -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `CRITICAL` | Kritiek | Onbetrouwbare herkenning of kritieke verwijzing; de regel wordt vastgehouden voor beoordeling. |
| `BLOCKING` | Blokkerend | De hele levering is onbruikbaar. |
| `ERROR` | Fout | De regel wordt verworpen. |
| `WARNING` | Waarschuwing | Ter info; de regel blijft geldig. |
| `INFO` | Informatie | Puur ter informatie. |

### Kritiek of niet-kritiek (per kolom)

<!-- terms:criticality -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `CRITICAL` | Kritiek | Een fout in deze kolom vraagt altijd een beoordeling door een mens. |
| `NON_CRITICAL` | Niet kritiek | Een fout in deze kolom verwerpt enkel de regel. |

### Beleid voor nieuwe artikelen

<!-- terms:creationPolicy -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `AUTOMATIC` | Automatisch | Nieuwe artikelen blijven binnen de drempel en worden zonder tussenkomst gepland. |
| `INITIAL_LOAD` | Eerste levering | Deze koppeling had nog geen artikelen; alle nieuwe artikelen wachten op goedkeuring. |
| `THRESHOLD_EXCEEDED` | Drempel overschreden | Er zijn meer nieuwe artikelen dan de toegelaten drempel; ze wachten op goedkeuring. |
| `null` | Nog niet beoordeeld | Het beleid voor nieuwe artikelen is nog niet beoordeeld. |

### Status van een behandelgeval

<!-- terms:issueCaseStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `AWAITING_REVIEW` | Wacht op beoordeling | Een mens moet dit geval nog bekijken. |
| `CORRECTED` | Gecorrigeerd | Door een mens gecorrigeerd. |
| `REJECTED` | Afgewezen | Door een mens afgewezen; een identieke herlevering blijft onderdrukt. |
| `AUTO_RESOLVED` | Vanzelf opgelost | Zonder tussenkomst opgelost; wordt vandaag nog niet gebruikt. |

### Status van een publicatierun

<!-- terms:publicationRunStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `REQUESTED` | Aangevraagd | De publicatierun is aangevraagd maar nog niet gestart. |
| `PREPARING` | Wordt voorbereid | Het bestand voor de publicatie wordt opgebouwd. |
| `SIMULATED` | Proef afgerond | Het bestand is geschreven, maar er is niets echt aangepast. |
| `FAILED` | Technisch mislukt | De publicatierun is technisch mislukt. |
| `WAITING_FOR_TARGET_CONTRACT` | Wacht op afspraken met het doelsysteem | Er moet eerst afgesproken worden hoe het doelsysteem het bestand verwacht. |
| `READY_FOR_DELIVERY` | Klaar om aan te leveren | Het bestand kan aan het doelsysteem aangeleverd worden. |
| `WRITTEN_TO_PSIMPORT` | Naar Prodis weggeschreven | Het bestand is voor Prodis klaargezet. |
| `RESULT_UNKNOWN` | Uitkomst onbekend | Het is niet duidelijk wat er met de publicatie gebeurd is. |
| `APPLIED` | Doorgevoerd | Echt verwerkt in het doelsysteem. |
| `REJECTED_BY_PRODIS` | Geweigerd door Prodis | Prodis heeft de publicatie geweigerd. |
| `RECOVERY_REQUIRED` | Herstel nodig | Er is een herstelactie nodig voordat het verder kan. |

### Velden van de beschrijving van het bestand (versie)

De code is hier de veldnaam van een versie, geen waarde: het gaat om het woord en de uitleg die bij
het veld staan in het stappenplan "Nieuwe leverancier en taak".

<!-- terms:revisionField -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `delimiter` | Scheidingsteken | Het teken tussen de kolommen, bijvoorbeeld een puntkomma of een komma. |
| `quoteChar` | Aanhalingsteken | Het teken rond een waarde waarin het scheidingsteken voorkomt. |
| `charset` | Tekenset | Hoe letters gecodeerd zijn; vreemde tekens zoals Ã© wijzen op een verkeerde keuze. |
| `hasHeader` | Kopregel | Een regel met de namen van de kolommen boven de gegevens. |
| `headerLineNumber` | Regelnummer van de kopregel | Op welke regel de kolomnamen staan; de regels erboven worden overgeslagen. |
| `fieldReferenceKind` | Kolommen herkennen | Of een kolom herkend wordt aan haar naam of aan haar volgnummer. |
| `identityProfileKind` | Herkenning van een artikel | Welke kolommen samen bepalen dat het in een volgende levering om hetzelfde artikel gaat. |
| `supplierField` | Kolom leverancier | De kolom met de leverancier van het artikel. |
| `supplierGroupField` | Kolom groep | De kolom met de artikelgroep van de leverancier. |
| `supplierReferenceField` | Kolom referentie | De kolom met het artikelnummer van de leverancier. |
| `discountCodeField` | Kolom kortingscode | De kolom met de kortingscode, die mee bepaalt om welk artikel het gaat. |
| `basePriceField` | Kolom basisprijs | De kolom met de prijs van het artikel. |
| `descriptionField` | Kolom omschrijving | De kolom met de omschrijving van het artikel; mag leeg blijven. |
| `currencyField` | Kolom valuta | De kolom met de munt van de prijs; zonder deze kolom geldt de standaardvaluta van de koppeling. |
| `canonicalisationVersion` | Herkenningsversie | Bepaalt hoe een artikel herkend wordt; na de eerste aanvaarde levering niet meer te wijzigen. |
| `creationThresholdSharePercent` | Drempel nieuwe artikelen (%) | Boven dit aandeel nieuwe artikelen wacht het aanmaken op een goedkeuring. |
| `maxCriticalSharePercent` | Maximum ter beoordeling (%) | Boven dit aandeel regels die een beoordeling vragen, wordt de hele levering tegengehouden. |
| `maxRejectedSharePercent` | Maximum verworpen regels (%) | Grens voor het aandeel verworpen regels; niet ingesteld betekent dat er geen grens geldt. |
| `bulkIncidentSharePercent` | Drempel bulkincident (%) | Vanaf dit aandeel worden gelijksoortige vaststellingen samen als één incident gemeld. |
| `expectedColumnCount` | Verwacht aantal kolommen | Het aantal kolommen dat het bestand hoort te hebben; is het niet ingesteld, dan wordt het aantal niet getoetst. |
| `structureFormat` | Bestandsformaat | Het soort bestand dat gelezen wordt. |
| `deliverySetKind` | Soort levering | Of elke levering het volledige aanbod bevat of alleen de wijzigingen. |
| `basePriceZeroAllowed` | Prijs nul toegelaten | Of een basisprijs van nul is toegelaten; anders wordt zo een regel verworpen. |
| `basePriceNegativeAllowed` | Negatieve prijs toegelaten | Of een negatieve basisprijs is toegelaten; anders wordt zo een regel verworpen. |
| `priceDeviationPercent` | Grens voor prijsafwijking (%) | Wijkt een prijs meer dan dit percentage af van eerdere prijzen, dan wordt de regel als afwijkend gemeld. |
| `priceDeviationSeverity` | Ernst van een prijsafwijking | Hoe zwaar een te grote prijsafwijking weegt. |
| `priceDerivationTolerance` | Tolerantie bij afgeleide prijzen | Hoeveel een afgeleide prijs mag afwijken van wat de onderdelen opleveren; het bedrag wordt letterlijk doorgegeven en nooit afgerond. |
| `priceAvgShortWindow` | Korte periode voor het prijsgemiddelde | Het aantal goedgekeurde prijzen waarover het korte gemiddelde wordt berekend. |
| `priceAvgLongWindow` | Lange periode voor het prijsgemiddelde | Het aantal goedgekeurde prijzen waarover het lange gemiddelde wordt berekend. |
| `priceControlModel` | Manier van prijscontrole | Het model waarmee prijsafwijkingen worden opgespoord. |
| `creationThresholdAbsolute` | Drempel nieuwe artikelen (aantal) | Tot dit aantal nieuwe artikelen worden ze zonder tussenkomst aangemaakt. |
| `changeReason` | Wijzigingsreden | Waarom deze versie is aangemaakt of gewijzigd. |

### Inrichting: leverancier, koppeling en taak

<!-- terms:setupField -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `sourceOrganisation` | Leverancier of aankoopvereniging | De organisatie die het bestand aanlevert. |
| `code` | Code | Een korte, unieke code waarmee u dit onderdeel later terugvindt. |
| `definition` | Beschrijving van het bestand | Legt vast hoe het bestand van deze organisatie eruitziet en hoe het gelezen wordt. |
| `revision` | Versie | Een vastgelegde stand van de beschrijving; een nieuwe versie wordt pas gebruikt na activeren. |
| `link` | Koppeling | Verbindt de beschrijving van het bestand met één leverancier en één Prodis-bibliotheek. |
| `supplierCode` | Leverancier van de koppeling | De leverancier wiens artikelen via deze koppeling binnenkomen. |
| `libraryCode` | Doelbibliotheek | De Prodis-bibliotheek waarin de gegevens uiteindelijk terechtkomen. |
| `librarySearchSupplierCode` | Leverancierscode in de bibliotheek | De code waaronder Prodis deze leverancier zoekt; wordt nooit automatisch ingevuld. |
| `defaultCurrency` | Standaardvaluta | Geldt als het bestand geen munt vermeldt; leeg betekent euro. |
| `task` | Taak | De ingang waarop u leveringen oplaadt. |
| `preventConcurrentRuns` | Gelijktijdige uitvoeringen voorkomen | Er wordt nooit meer dan één levering tegelijk voor deze taak verwerkt. |

### Controleren: toestand van een punt op de checklist

De tabellen hieronder horen bij het scherm "Controleren" van een koppeling (NT-10): de checklist
(gereedheidscontrole zonder bestand) en de proefinlezing (een bestand op proef lezen, zonder iets op te slaan).

<!-- terms:readinessStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `OK` | In orde | Deze controle is geslaagd; hier hoeft u niets te doen. |
| `PROBLEM` | Probleem | Zolang dit niet opgelost is, kan de koppeling geen leveringen ontvangen. |
| `INFO` | Ter info | Goed om te weten; dit houdt leveringen niet tegen. |

### Controleren: de punten van de checklist

Een probleem draagt dezelfde code als de upload of het activeren van de versie zou geven.

<!-- terms:readinessCheck -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `READY_LINK_ACTIVE` | De koppeling staat aan | De koppeling is als actief ingesteld. |
| `INFO_LINK_INACTIVE` | De koppeling staat uit | De koppeling is als niet actief ingesteld; een opgeladen levering wordt toch aangenomen. |
| `READY_ACTIVE_REVISION` | Er is een versie in gebruik | De beschrijving van het bestand heeft een actieve versie waarmee leveringen gelezen worden. |
| `NO_ACTIVE_REVISION` | Er is nog geen versie in gebruik | Zonder actieve versie van de beschrijving kan geen enkele levering gelezen worden. |
| `READY_PRICE_FIELD` | De prijskolom is ingesteld | De versie in gebruik weet in welke kolom de prijs staat. |
| `CONFIG_PRICE_FIELD_MISSING` | De prijskolom ontbreekt | De versie in gebruik zegt niet in welke kolom de prijs staat; zo kan geen enkele regel gelezen worden. |
| `READY_LINK_BOOKMARKS` | De verplichte invulpunten van de koppeling zijn ingevuld | Alle waarden die de beschrijving per koppeling vraagt, zijn ingevuld. |
| `CONFIG_REQUIRED_BOOKMARK_MISSING` | Verplichte invulpunten zijn niet ingevuld | De beschrijving vraagt een of meer waarden die nog leeg zijn; zolang ze ontbreken, wordt een levering geweigerd. |
| `READY_DRAFT_ACTIVATABLE` | De conceptversie kan geactiveerd worden | De controle die bij het activeren gebeurt, vindt geen fouten in deze conceptversie. |
| `INFO_NO_DRAFT_REVISION` | Er is geen conceptversie om te activeren | Er is geen versie in gebruik en ook geen concept; maak eerst een nieuwe versie van de beschrijving. |
| `READY_TASK_ACCEPTS_UPLOAD` | De taak neemt opgeladen bestanden aan | Op deze taak kunt u een levering opladen. |
| `LINK_HAS_NO_TASK` | Er is nog geen taak | Zonder taak is er geen ingang om een levering op te laden. |
| `TASK_NOT_MANUAL` | De taak start niet handmatig | Deze taak haalt leveringen automatisch op en neemt geen opgeladen bestand aan. |
| `TASK_HAS_DELIVERY_CONFIGURATION` | De taak haalt leveringen zelf op | Aan deze taak hangt een instelling om leveringen op te halen; ze neemt daarom geen opgeladen bestand aan. |
| `INFO_LIBRARY_NOT_VERIFIED` | De doelbibliotheek is niet nagekeken | Of de bibliotheekcode echt bestaat in Prodis, wordt hier niet gecontroleerd. |
| `CONFIG_INVALID` | Fout in de beschrijving van het bestand | De beschrijving van het bestand is onvolledig of ongeldig. |
| `INFO_CONFIG_CHECKS_SKIPPED` | Niet alle controles konden uitgevoerd worden | Sommige controles konden nog niet uitgevoerd worden omdat ze afhangen van een fout hierboven; na herstel kunnen er nog fouten bijkomen. |

### Controleren: vaststellingen bij het lezen van een levering

Wat de screening en de proefinlezing vaststellen: een blokkade, een fout op een regel of een melding. Een code met een
veldnaam erachter (zoals een ontbrekende kolom) wordt op het deel vóór de dubbele punt opgezocht.

<!-- terms:issueCode -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `CONFIG_FORMAT_UNSUPPORTED` | Bestandsformaat niet ondersteund | Het ingestelde bestandsformaat kan niet gelezen worden. |
| `CONFIG_CHARSET_UNKNOWN` | Onbekende tekenset | De ingestelde tekenset bestaat niet of wordt niet ondersteund. |
| `CONFIG_DELIMITER_MISSING` | Scheidingsteken ontbreekt | De beschrijving zegt niet welk teken de kolommen scheidt. |
| `CONFIG_DELIMITER_INVALID` | Ongeldig scheidingsteken | Het ingestelde scheidingsteken kan niet gebruikt worden. |
| `CONFIG_QUOTE_INVALID` | Ongeldig aanhalingsteken | Het ingestelde aanhalingsteken kan niet gebruikt worden. |
| `CONFIG_HEADER_LINE_INVALID` | Ongeldig regelnummer van de kopregel | Het ingestelde regelnummer van de kopregel kan niet gebruikt worden. |
| `CONFIG_FIELD_REFERENCE_KIND_INVALID` | Ongeldige manier om kolommen te herkennen | De keuze tussen kolommen op naam of op positie is niet geldig. |
| `CONFIG_HEADER_REFERENCE_INCONSISTENT` | Kopregel en kolomherkenning passen niet samen | Kolommen op naam herkennen kan alleen als het bestand een kopregel heeft. |
| `CONFIG_IDENTITY_FIELD_MISSING` | Kolom voor de herkenning ontbreekt | Een van de kolommen waarmee een artikel herkend wordt, is niet ingesteld. |
| `CONFIG_PRICE_FIELD_MISSING` | Prijskolom ontbreekt | De beschrijving zegt niet in welke kolom de prijs staat. |
| `CONFIG_COLUMN_COUNT_INVALID` | Ongeldig aantal kolommen | Het ingestelde aantal kolommen kan niet gebruikt worden. |
| `CONFIG_FIELD_REFERENCE_INVALID` | Ongeldige kolomaanduiding | Een kolom is aangeduid op een manier die niet past bij de gekozen kolomherkenning. |
| `CONFIG_CANONICALISATION_VERSION_UNSUPPORTED` | Herkenningsversie niet ondersteund | Deze toepassing kent de ingestelde herkenningsversie niet. |
| `CONFIG_CANONICALISATION_VERSION_REQUIRED` | Herkenningsversie ontbreekt | Voor deze instelling is een herkenningsversie nodig. |
| `CONFIG_LINK_CURRENCY_INVALID` | Ongeldige standaardvaluta | De standaardvaluta van de koppeling is geen code van drie hoofdletters; ze wordt nooit stil vervangen. |
| `CONFIG_DISCOUNT_FIELD_MISSING` | Kolom kortingscode ontbreekt | Bij herkenning met kortingscode moet de kolom voor de kortingscode ingesteld zijn en in het bestand staan. |
| `CONFIG_FIELD_NOT_RESOLVED` | Kolom niet gevonden | Een ingestelde kolom kon in het bestand niet teruggevonden worden. |
| `CONFIG_COLUMN_INDEX_OUT_OF_RANGE` | Kolomnummer buiten het bestand | Een ingesteld kolomnummer is groter dan het aantal kolommen in het bestand. |
| `CONFIG_MAPPING_TARGET_UNKNOWN` | Onbekend doelveld | Een extra veld verwijst naar een doelveld dat niet bestaat. |
| `CONFIG_MAPPING_SOURCE_UNRESOLVED` | Bron van een extra veld niet gevonden | Een extra veld haalt zijn waarde uit een kolom of invulpunt dat niet bestaat. |
| `CONFIG_MAPPING_DUPLICATE_TARGET` | Doelveld dubbel gebruikt | Twee extra velden schrijven naar hetzelfde doelveld. |
| `CONFIG_MAPPING_TYPE_INCOMPATIBLE` | Soort waarde past niet | Een extra veld levert een soort waarde die het doelveld niet aanneemt. |
| `CONFIG_FIELD_MAPPING_DUPLICATES_REVISION` | Veld dubbel ingesteld | Een extra veld herhaalt een veld dat de versie zelf al leest. |
| `CONFIG_IDENTITY_CLASS_CONFLICT` | Botsing met de herkenning | Een extra veld botst met de kolommen waarmee een artikel herkend wordt. |
| `CONFIG_OWNER_NOT_CHANGEABLE` | Veld hier niet in te stellen | Dit doelveld kan vanuit deze bron niet gevuld worden. |
| `CONFIG_PRICE_COMPONENT_DUPLICATE` | Prijsonderdeel dubbel | Hetzelfde prijsonderdeel is meer dan eens ingesteld. |
| `CONFIG_TRANSFORM_INVALID` | Ongeldige omzetting | De omzetting van een extra veld is niet correct ingesteld. |
| `CONFIG_FILTER_INVALID` | Ongeldig filter | Een filter op de regels is niet correct ingesteld. |
| `CONFIG_FIELD_CRITICALITY_INVALID` | Ongeldige instelling kritiek | Een kolom is op een niet toegelaten manier als kritiek of niet kritiek ingesteld. |
| `CONFIG_PRICE_CONTROL_MODEL_UNSUPPORTED` | Prijscontrole niet ondersteund | De gekozen manier van prijscontrole bestaat in deze versie van de toepassing niet. |
| `CONFIG_BOOKMARK_VALUE_INVALID` | Ongeldige waarde van een invulpunt | Een ingevulde waarde past niet bij wat het invulpunt toelaat. |
| `CONFIG_BOOKMARK_VALUE_TOO_LONG` | Waarde van een invulpunt te lang | Een ingevulde waarde is langer dan toegelaten. |
| `CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT` | Invulpunt op de verkeerde plaats | Een invulpunt is ingesteld op een plaats die niet bij zijn soort past. |
| `CONFIG_BOOKMARK_WITHOUT_PLACE` | Invulpunt zonder bestemming | Een invulpunt zegt niet waar zijn waarde terechtkomt. |
| `CONFIG_BOOKMARK_PLACE_UNRESOLVED` | Bestemming van een invulpunt bestaat niet | Een invulpunt verwijst naar een veld of filter dat niet (meer) bestaat. |
| `CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED` | Bestemming van een invulpunt niet ondersteund | Een invulpunt verwijst naar een plaats die niet gevuld kan worden. |
| `SOURCE_FILE_EMPTY` | Het bestand is leeg | Het bestand bevat geen enkele regel. |
| `SOURCE_NO_DATA_RECORDS` | Geen regels met gegevens | Het bestand bevat geen enkele regel met gegevens, hooguit een kopregel. |
| `SOURCE_BOM_REMOVED` | Onzichtbaar beginteken genegeerd | Het bestand begon met een onzichtbaar teken dat de tekenset aanduidt; dat teken is genegeerd. |
| `HEADER_LINE_MISSING` | Kopregel ontbreekt | Het bestand eindigt voordat de ingestelde kopregel bereikt is. |
| `HEADER_FIELD_MISSING` | Verplichte kolom ontbreekt | Een kolom die de beschrijving verwacht, staat niet in de kopregel van het bestand. |
| `HEADER_DUPLICATE_FIELD` | Kolomnaam dubbel | Dezelfde kolomnaam komt meer dan eens voor in de kopregel. |
| `HEADER_COLUMN_COUNT_MISMATCH` | Ander aantal kolommen | De kopregel heeft een ander aantal kolommen dan de beschrijving verwacht. |
| `HEADER_FIELD_SHIFTED` | Kolom verschoven | Een verwachte kolom staat op een andere plaats; ze wordt op naam teruggevonden. |
| `HEADER_FIELD_SEMANTIC_CHANGE` | Kolom van betekenis veranderd | Op de plaats van een belangrijke kolom staat een andere kolomnaam; doorgaan zou de verkeerde kolom lezen. |
| `HEADER_UNKNOWN_COLUMN` | Onbekende kolom | Het bestand bevat een kolom die de beschrijving niet kent; ze wordt niet gebruikt. |
| `ROW_COLUMN_COUNT_MISMATCH` | Regel met een ander aantal kolommen | Deze regel heeft meer of minder kolommen dan de kopregel en wordt verworpen. |
| `ROW_TOO_LONG` | Regel te lang | Deze regel is langer dan toegelaten en wordt verworpen. |
| `CSV_UNCLOSED_QUOTE` | Aanhalingsteken niet gesloten | In deze regel wordt een aanhalingsteken geopend maar niet gesloten; de regel wordt verworpen. |
| `VALUE_MISSING` | Waarde ontbreekt | Een verplichte waarde is leeg; de regel wordt verworpen. |
| `CANONICAL_CONTROL_CHARACTER` | Onzichtbaar stuurteken | Een waarde bevat een onzichtbaar stuurteken; de regel wordt verworpen. |
| `VALUE_TOO_LONG` | Waarde te lang | Een waarde is langer dan toegelaten; ze wordt nooit ingekort en de regel wordt verworpen. |
| `VALUE_TYPE_MISMATCH` | Verkeerde soort waarde | Een waarde is niet van de verwachte soort, bijvoorbeeld tekst waar een getal hoort. |
| `DATE_UNREADABLE` | Datum onleesbaar | Een datum kon niet gelezen worden. |
| `DATE_AMBIGUOUS` | Datum dubbelzinnig | Een datum kan op meer dan één manier gelezen worden; er wordt niet gegokt. |
| `VALUE_DEFAULT_APPLIED` | Standaardwaarde gebruikt | Een lege waarde is aangevuld met de ingestelde standaardwaarde. |
| `TRANSFORM_FAILED` | Omzetting mislukt | Een waarde kon niet omgezet worden zoals ingesteld. |
| `TRANSFORM_DIVIDE_BY_ZERO` | Deling door nul | Een omzetting zou door nul delen; de regel wordt verworpen. |
| `MAPPING_VALUE_UNKNOWN` | Onbekende waarde | Een waarde staat niet in de ingestelde vertaallijst. |
| `FILTER_RECORD_REJECTED` | Verworpen door een filter | Volgens een filter hoort zo'n regel niet te bestaan; ze wordt verworpen en geteld. |
| `FILTER_COLUMN_MISSING` | Filterkolom ontbreekt | De kolom waarop gefilterd wordt, staat niet in het bestand. |
| `IDENTITY_COMPONENT_EMPTY` | Herkenning onvolledig | Leverancier, groep of referentie is leeg, dus het artikel kan niet herkend worden. |
| `PRICE_MISSING` | Prijs ontbreekt | De prijs is leeg; ze wordt nooit op nul gezet. |
| `PRICE_UNREADABLE` | Prijs onleesbaar | De prijs is geen leesbaar getal. |
| `PRICE_SCALE_EXCEEDED` | Te veel cijfers na de komma | De prijs heeft meer cijfers na de komma dan toegelaten; ze wordt nooit afgerond. |
| `PRICE_OUT_OF_RANGE` | Prijs te groot | De prijs is te groot om te bewaren. |
| `PRICE_ZERO_NOT_ALLOWED` | Prijs nul niet toegelaten | Een prijs van nul is voor deze beschrijving niet toegelaten. |
| `PRICE_NEGATIVE_NOT_ALLOWED` | Negatieve prijs niet toegelaten | Een negatieve prijs is voor deze beschrijving niet toegelaten. |
| `PRICE_CURRENCY_MISMATCH` | Valuta past niet | De valuta van een prijsonderdeel verschilt van die van de prijs. |
| `PRICE_PERCENTAGE_NOT_COMPUTABLE` | Percentage niet te berekenen | Een percentage van een prijsonderdeel kan niet berekend worden. |
| `PRICE_DERIVATION_MISMATCH` | Prijzen kloppen niet met elkaar | Een afgeleide prijs wijkt meer af dan toegelaten van wat de onderdelen opleveren. |
| `PRICE_PERCENTAGE_OUT_OF_RANGE` | Percentage buiten bereik | Een percentage ligt buiten het toegelaten bereik. |
| `PRICE_DEVIATION_EXCEEDED` | Grote prijsafwijking | De prijs wijkt sterk af van eerdere prijzen; de regel blijft geldig maar valt op. |
| `PRICE_REFERENCE_NOT_AVAILABLE` | Geen eerdere prijzen | Er zijn nog geen eerdere prijzen om mee te vergelijken. |
| `DUPLICATE_IDENTITY_IN_DELIVERY` | Hetzelfde artikel meermaals | Hetzelfde artikel staat meer dan eens in dit bestand; er wordt nooit zomaar de laatste regel genomen. |
| `DUPLICATE_REFERENCE_IN_DELIVERY` | Dezelfde verwijzing bij verschillende artikelen | Een verwijzing zoals een streepjescode staat bij meer dan één artikel in dit bestand. |
| `IDENTITY_HASH_COLLISION` | Onbetrouwbare herkenning | Twee verschillende artikelen krijgen dezelfde herkenningssleutel; de herkenning is niet te vertrouwen. |
| `IDENTITY_REFERENCE_INCIDENT` | Herkenningsprobleem | Een verwijzing om het artikel te herkennen is gewijzigd, verdwenen of dubbelzinnig. |
| `REFERENCE_LINK_PROPOSED` | Mogelijk hetzelfde artikel | Een nieuw artikel lijkt via een verwijzing op een bekend artikel; dit is ter info. |
| `BULK_PRICE_INCIDENT` | Veel gelijke prijsafwijkingen | Zoveel gelijksoortige prijsafwijkingen dat ze samen als één gebeurtenis beoordeeld worden. |
| `BULK_IDENTITY_INCIDENT` | Veel gelijke herkenningsproblemen | Zoveel gelijksoortige herkenningsproblemen dat ze samen als één gebeurtenis beoordeeld worden. |
| `INITIAL_LOAD_REQUIRES_APPROVAL` | Eerste levering vraagt goedkeuring | Deze koppeling had nog geen artikelen; alle nieuwe artikelen wachten op een goedkeuring. |
| `BULK_CREATION_INCIDENT` | Veel nieuwe artikelen | Er zijn meer nieuwe artikelen dan de drempel toelaat; ze wachten op een goedkeuring. |
| `CRITICAL_RECORD_THRESHOLD_EXCEEDED` | Te veel regels ter beoordeling | Het aandeel regels dat een beoordeling vraagt, ligt boven de ingestelde grens. |
| `REJECTED_RECORD_THRESHOLD_EXCEEDED` | Te veel verworpen regels | Het aandeel verworpen regels ligt boven de ingestelde grens. |
| `BYTE_SIZE_MISMATCH` | Andere bestandsgrootte dan verwacht | Het bestand is groter of kleiner dan bij het opladen opgegeven. |
| `RECORD_COUNT_MISMATCH` | Ander aantal regels dan verwacht | Het bestand bevat meer of minder regels dan bij het opladen opgegeven. |
| `ROW_ISSUE_RECORDING_CAPPED` | Niet alle voorbeelden bewaard | Er zijn meer voorvallen dan er voorbeelden bewaard worden; de aantallen kloppen wel. |
| `SCREENING_FAILED` | Technische fout bij het controleren | De controle van de levering is technisch mislukt; opnieuw proberen is mogelijk. |
| `SCREENING_INTERRUPTED` | Controle onderbroken | De controle van de levering werd onderbroken voordat ze klaar was. |

### Controleren: gevolg voor de levering

<!-- terms:deliveryEffect -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `NONE` | Geen gevolg voor de levering | Dit raakt enkel de regel zelf of is ter info. |
| `REVIEW` | Vraagt een beoordeling | De levering gaat niet ongezien door; een mens moet ze bekijken. |
| `BLOCK` | Houdt de levering tegen | De levering wordt als geheel tegengehouden. |

### Proefinlezing: oordeel

<!-- terms:trialVerdict -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `WOULD_BLOCK` | Zou tegengehouden worden | Een echte levering met dit bestand zou als geheel tegengehouden worden. |
| `NO_BLOCKER_FOUND` | Zou aanvaard worden | De proef vond niets dat een echte levering met dit bestand zou tegenhouden. |

### Proefinlezing: waar de blokkade gevonden werd

<!-- terms:trialStage -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `CONFIGURATION` | Beschrijving van het bestand | Het probleem zit in de ingestelde beschrijving, nog voor het bestand gelezen werd. |
| `READING` | Lezen van het bestand | Het probleem trad op bij het lezen van de kopregel of van de regels. |
| `FILE_LEVEL` | Het bestand als geheel | Het probleem gaat over het bestand als geheel, bijvoorbeeld geen enkele regel met gegevens. |
| `IDENTITY` | Herkenning van artikelen | Het probleem gaat over het herkennen van artikelen, bijvoorbeeld hetzelfde artikel twee keer. |
| `THRESHOLD` | Grens overschreden | Te veel regels hebben een probleem in verhouding tot het hele bestand. |

### Proefinlezing: tellers

De code is de naam van de teller in het antwoord van de proefinlezing.

<!-- terms:trialCounter -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `rawRecordCount` | Gelezen regels | Alle regels met gegevens, zonder kopregel en zonder lege regels. |
| `validRecordCount` | Geldig | Regels die volledig gelezen konden worden. |
| `rejectedRecordCount` | Verworpen | Regels met een fout; zo een regel wordt niet verwerkt. |
| `filteredOutCount` | Buiten filter | Regels die door de filters van de beschrijving overgeslagen worden. |
| `errorBeforeFilterCount` | Fout vóór het filter | Regels met een fout die al optrad voordat de filters konden beslissen. |
| `criticalLineCount` | Kritieke regels | Regels met een fout in een kritieke kolom; die vragen een beoordeling door een mens. |
| `duplicateIdentityCount` | Dubbele artikelen | Regels waarvan het artikel meer dan eens in het bestand staat, de eerste keer inbegrepen. |
| `scopeRecordCount` | Regels binnen filter | Gelezen regels min de regels buiten filter; hierop worden de grenzen berekend. |
| `physicalLineCount` | Regels in het bestand | Alle regels van het bestand, ook de kopregel en lege regels. |
| `prefixLineCount` | Regels vóór de kopregel | Regels boven de kopregel die overgeslagen worden. |
| `skippedBlankLineCount` | Lege regels | Lege regels die overgeslagen worden. |
| `columnCount` | Kolommen | Het aantal kolommen dat in het bestand gevonden is. |
| `linesWithReplacementCharacter` | Regels met onleesbare tekens | Regels met tekens die met de gekozen tekenset niet gelezen konden worden. |

### Proefinlezing: wat er met een voorbeeldregel zou gebeuren

<!-- terms:trialSampleStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `VALID` | Geldig | Deze regel zou gelezen en verwerkt worden. |
| `REJECTED` | Verworpen | Deze regel zou verworpen worden; de reden staat erbij. |
| `FILTERED_OUT` | Buiten filter | Deze regel valt buiten de filters van de beschrijving en wordt overgeslagen. |
| `UNREADABLE` | Onleesbaar | Deze regel kon niet in kolommen gesplitst worden. |

### Proefinlezing: waarvoor een kolom dient

<!-- terms:columnRole -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `IDENTITY` | Herkenning | Deze kolom bepaalt mee om welk artikel het gaat. |
| `PRICE` | Prijs | De kolom met de prijs. |
| `CURRENCY` | Valuta | De kolom met de munt van de prijs. |
| `DESCRIPTION` | Omschrijving | De kolom met de omschrijving van het artikel. |
| `DISCOUNT` | Kortingscode | De kolom met de kortingscode. |
| `MAPPING` | Extra veld | Een kolom die naar een extra veld overgenomen wordt. |
| `FILTER` | Filter | Een kolom waarop de regels gefilterd worden. |

### Herkomst van de valuta

<!-- terms:currencyOrigin -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `SOURCE` | Uit het bestand | De valuta staat in het bestand zelf. |
| `LINK_DEFAULT` | Standaard van de koppeling | Het bestand vermeldt geen valuta; de standaardvaluta van de koppeling geldt. |
| `SYSTEM_DEFAULT` | Euro (standaard) | Het bestand en de koppeling vermelden geen valuta; dan geldt euro. |

### Uitkomst van een leveringsgrens

<!-- terms:thresholdOutcome -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `NOT_APPLICABLE` | Geen grens ingesteld | Voor deze beschrijving geldt hier geen grens. |
| `UNDETERMINED` | Niet vast te stellen | Er zijn geen regels om het aandeel op te berekenen. |
| `WITHIN` | Binnen de grens | Het aandeel ligt niet boven de ingestelde grens. |
| `EXCEEDED` | Grens overschreden | Het aandeel ligt boven de ingestelde grens; een echte levering zou tegengehouden worden. |

### Proefinlezing: niet gecontroleerd in een proef

<!-- terms:trialCheck -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `CREATION_POLICY` | Te veel nieuwe artikelen | Of er te veel nieuwe artikelen zijn, kan pas bij een echte levering, want dan kennen we de bestaande artikelen. |
| `IDENTITY_HASH_COLLISION_AGAINST_SOURCE_STATE` | Botsing met bekende artikelen | Of een herkenningssleutel botst met een al bekend artikel, blijkt pas bij een echte levering. |
| `REFERENCE_CONTROL` | Controle van verwijzingen | Of verwijzingen zoals streepjescodes gewijzigd of hergebruikt zijn, blijkt pas bij een echte levering. |
| `DUPLICATE_REFERENCE_IN_DELIVERY` | Dezelfde verwijzing bij verschillende artikelen | Deze controle zit nog niet in de proefinlezing. |
| `PRICE_DEVIATION` | Prijsafwijking | Of een prijs sterk afwijkt, vraagt eerdere prijzen; een proef vergelijkt daar niet mee. |
| `MANIFEST_COUNTS` | Verwacht aantal regels en bestandsgrootte | Die geeft u pas op bij het opladen van een echte levering. |
| `IDENTITY_HASH_COLLISION_IN_FILE` | Botsing van herkenningssleutels in het bestand | Deze controle houdt de proefinlezing niet bij. |
| `IDENTITY_INCIDENTS_IN_CRITICAL_THRESHOLD` | Herkenningsproblemen in de grens voor beoordeling | Die tellen pas mee bij een echte levering; de proef telt enkel de kritieke regels. |
| `DUPLICATE_IDENTITY_IN_DELIVERY` | Hetzelfde artikel meermaals | Het bestand bevat te veel artikelen om allemaal bij te houden; dubbele artikelen zijn niet volledig nagegaan. |

### Proefinlezing: waarom iets niet gecontroleerd is

<!-- terms:trialReason -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `NO_SOURCE_STATE` | Geen bekende artikelen | Een proef vergelijkt niet met de artikelen die al bekend zijn. |
| `NOT_IMPLEMENTED_V1` | Nog niet in de proef | Deze controle zit nog niet in de proefinlezing. |
| `NO_PRICE_HISTORY` | Geen eerdere prijzen | Een proef vergelijkt niet met eerdere prijzen. |
| `NO_MANIFEST` | Geen verwachte aantallen | Bij een proef geeft u geen verwachte aantallen op. |
| `NOT_TRACKED` | Niet bijgehouden | De proef houdt deze gegevens niet bij. |
| `TRACKING_LIMIT_REACHED` | Grens bereikt | Het bestand bevat meer artikelen dan de proef kan bijhouden. |

### Tellers van een levering

De code is hier de veldnaam van de teller in het antwoord van de server (werkvoorraad, batchdetail en het resultaat na het opladen).

<!-- terms:batchCounter -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `rawRecordCount` | Gelezen regels | Alle regels met gegevens in het bestand, zonder kopregel en zonder lege regels. |
| `validRecordCount` | Geldige regels | Regels die volledig gelezen konden worden. |
| `rejectedRecordCount` | Verworpen regels | Regels met een fout; zo een regel wordt niet verwerkt. |
| `filteredOutCount` | Buiten filter | Regels die door de filters van de beschrijving van het bestand overgeslagen worden. |
| `errorBeforeFilterCount` | Fout vóór het filter | Regels met een fout die al optrad voordat de filters konden beslissen. |
| `duplicateIdentityCount` | Dubbele artikelen | Regels waarvan het artikel meer dan eens in het bestand staat, de eerste keer inbegrepen. |
| `newCount` | Nieuwe artikelen | Artikelen die nog niet bekend waren. |
| `changedCount` | Gewijzigde artikelen | Bekende artikelen waarvan iets veranderd is. |
| `unchangedCount` | Ongewijzigde artikelen | Bekende artikelen die niet veranderd zijn. |
| `contentMutationCount` | Voorgestelde wijzigingen | Het aantal wijzigingen dat de levering voorstelt aan de artikelen, zonder de afgeronde controle zelf. |
| `awaitingApprovalCount` | Wacht op goedkeuring | Wijzigingen die een uitdrukkelijke beslissing van een mens vragen. |
| `identityIncidentCount` | Herkenningsproblemen | Artikelen waarvan een kritieke verwijzing, zoals een streepjescode, gewijzigd, verdwenen of hergebruikt is. |
| `bulkIncidentCount` | Gebundelde meldingen | Groepen van zoveel gelijksoortige vaststellingen dat ze samen als één gebeurtenis beoordeeld worden. |
| `criticalLineCount` | Kritieke regels | Regels met een fout in een kritieke kolom; die vragen een beoordeling door een mens. |
| `criticalIssueCount` | Kritieke problemen | Vaststellingen van de hoogste ernst; ze houden een regel vast voor beoordeling. |
| `warningCount` | Waarschuwingen | Vaststellingen ter info; de betrokken regel blijft geldig. |

### Reden van de status van een wijziging

De technische referentie staat hierboven onder "Veelvoorkomende statusredenen"; dit zijn de woorden voor de schermen. De vier laatste waarden zijn de soorten herkenningsprobleem bij een verwijzing.

<!-- terms:mutationStatusReason -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `INITIAL_LOAD_REQUIRES_APPROVAL` | Eerste levering vraagt goedkeuring | Deze koppeling had nog geen artikelen; het nieuwe artikel wacht op goedkeuring. |
| `BULK_CREATION_INCIDENT` | Veel nieuwe artikelen | Er zijn meer nieuwe artikelen dan de drempel toelaat; dit artikel wacht op goedkeuring. |
| `BULK_PRICE_INCIDENT` | Veel gelijke prijsafwijkingen | Zoveel gelijksoortige prijsafwijkingen in deze levering dat ze samen beoordeeld worden. |
| `BULK_IDENTITY_INCIDENT` | Veel gelijke herkenningsproblemen | Zoveel gelijksoortige herkenningsproblemen in deze levering dat ze samen beoordeeld worden. |
| `BASELINE_ACCEPTED_WITHOUT_PUBLICATION` | Overgeslagen door de nulmeting | De levering is als nulmeting aanvaard; deze wijziging is overgeslagen en niet gepubliceerd. |
| `CHANGED` | Verwijzing gewijzigd | Een verwijzing van dit artikel, zoals een streepjescode, is gewijzigd. |
| `REMOVED` | Verwijzing verdwenen | Een verwijzing van dit artikel, zoals een streepjescode, staat niet meer in de levering. |
| `REUSED` | Verwijzing hergebruikt | Een verwijzing die al bij een ander artikel hoort, staat nu bij dit artikel. |
| `AMBIGUOUS` | Verwijzing dubbelzinnig | Een verwijzing hoort volgens de levering bij meer dan één artikel. |

### Wat een wijziging raakt

Het masker van een wijziging is een lijst gescheiden door komma's. Een prijsonderdeel draagt een code achter een dubbele punt; de schermen tonen dat als "Prijsonderdeel" met die code.

<!-- terms:changePart -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `ARTICLE` | Artikelgegevens | De gegevens van het artikel zelf, niet de prijs. |
| `PRICE` | Basisprijs | De basisprijs van het artikel. |

### Kortingscode in de herkenning van een artikel

<!-- terms:discountCodeState -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `NOT_USED` | Niet van toepassing | Deze herkenning gebruikt geen kortingscode. |
| `EMPTY` | Leeg | De kolom met de kortingscode is ingesteld, maar de waarde is leeg. |
| `VALUE` | Ingevuld | De kortingscode heeft een waarde. |

### Soort verwijzing

De verwijzingen waarmee een artikel herkend wordt. De uitleg bij de PIM- en CAB-nummers is een voorstel en moet door de redactie nagelezen worden.

<!-- terms:referenceType -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `EAN` | EAN (streepjescode) | De streepjescode van het artikel. |
| `PIM_ID` | PIM-nummer | Het nummer van het artikel in het productinformatiesysteem. |
| `CAB_ID` | CAB-nummer | Het CAB-nummer van het artikel. |
| `E_MARK_ARTICLE_REFERENCE` | E-merk met artikelnummer | De combinatie van het E-merk en het artikelnummer. |

### Soort samenvatting van een foutgroep

<!-- terms:issueIncidentKind -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `GENERIC` | Gewone foutgroep | Dezelfde fout op hetzelfde veld, samengevat. |
| `PRICE` | Prijsafwijkingen | Gelijksoortige prijsafwijkingen binnen dezelfde levering, samengevat. |
| `IDENTITY` | Herkenningsproblemen | Gelijksoortige problemen met verwijzingen, zoals streepjescodes, samengevat. |
| `CREATION` | Nieuwe artikelen | Samenvatting van het aantal nieuwe artikelen tegenover de drempel. |

### Behandeling van een vaststelling

<!-- terms:issueHandlingStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `DETECTED` | Vastgesteld | Door de controle vastgesteld; nog niet behandeld. |
| `AUTO_RESOLVED` | Vanzelf opgelost | Door een volgende verwerking vanzelf opgelost. |
| `AWAITING_REVIEW` | Wacht op beoordeling | Een mens moet dit nog bekijken. |
| `CORRECTED` | Gecorrigeerd | Door een beheerder gecorrigeerd. |
| `ACCEPTED_FOR_BATCH` | Eenmalig aanvaard | Aanvaard voor alleen deze levering. |
| `ACCEPTED_BY_RULE` | Aanvaard via een regel | Aanvaard op grond van een vastgelegde uitzonderingsregel. |
| `REJECTED` | Afgewezen | Afgewezen; de betrokken gegevens gaan niet door. |
| `EXPIRED_EXCEPTION` | Uitzondering verlopen | De uitzondering die deze vaststelling onderdrukte, is verlopen. |
| `REOPENED` | Heropend | Opnieuw geopend na een eerdere afhandeling. |

### Soort beslissing in het beslissingsregister van een bundel

<!-- terms:decisionKind -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `APPROVE` | Goedkeuring | Een mutatie of een groep mutaties is goedgekeurd. |
| `REJECT` | Afkeuring | Een mutatie of een groep mutaties is afgekeurd. |
| `AUTO_APPROVE_PLANNED` | Automatische goedkeuring bij het bevriezen | Bij het bevriezen zijn de mutaties die nog gepland stonden in één keer goedgekeurd op naam van wie bevroor. |
| `FREEZE` | Bevriezing | De bundel is bevroren: afgesloten en klaar voor publicatie. |
| `CANCEL` | Annulering | De bundel is geannuleerd. |

### Waarover een beslissing in het beslissingsregister ging

<!-- terms:decisionScope -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `MUTATION` | Eén mutatie | De beslissing ging over één mutatie. |
| `GROUP` | Groep mutaties | De beslissing ging over alle mutaties die aan een filter voldeden. |
| `BUNDLE` | Hele bundel | De beslissing ging over de bundel als geheel. |

### Onderdelen van de filter van een groepsbeslissing

De code is hier de naam van het filterveld zoals de server het bewaart.

<!-- terms:selectionFilterField -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `batchId` | Batch | Enkel mutaties van deze batch. |
| `status` | Status | Enkel mutaties met deze status. |
| `statusReason` | Reden van de status | Enkel mutaties met deze reden. |
| `actionType` | Soort | Enkel mutaties van deze soort. |
| `identityHash` | Wijzigingsgroep | Enkel de wijzigingen van één artikel in één batch. |

### Waarom een bundel nog niet bevroren kan worden

De code is dezelfde als die het bevriezen zelf zou geven.

<!-- terms:freezeCheck -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `BUNDLE_NOT_ASSEMBLING` | De bundel is niet meer in opbouw | Enkel een bundel die nog in opbouw is, kan bevroren worden. |
| `BUNDLE_EMPTY` | De bundel heeft geen batches | Een bundel zonder actieve batch kan niet bevroren worden. |
| `BUNDLE_HAS_UNDECIDED_MUTATIONS` | Er wachten nog mutaties op een beslissing | Bevriezen mag een openstaande vraag niet stilzwijgend beantwoorden; beslis eerst over deze mutaties. |
| `SOURCE_STATE_CHANGED_SINCE_SCREENING` | De bekende gegevens zijn veranderd sinds de controle | Wat we over de artikelen wisten, is na de controle van de levering gewijzigd; controleer de levering opnieuw. |
| `BUNDLE_OFFER_CONFLICT` | Dezelfde aanbieding zit in meer dan één batch | Twee batches van deze bundel willen hetzelfde artikel publiceren; keur één kant af. |
| `OFFER_ALREADY_IN_ANOTHER_BUNDLE` | Een aanbieding zit ook in een andere bundel | Hetzelfde artikel kan in een andere open of bevroren bundel gepubliceerd worden; keur één kant af, of publiceer of annuleer eerst de andere bundel. |

### Afspraken met Prodis over het bestand van een publicatie

<!-- terms:contractStatus -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `UNVERIFIED_FIELD_INVENTORY` | Velden nog niet bevestigd | De lijst met velden voor het importbestand van Prodis is nog niet door Prodis bevestigd; het bestand is een voorbeeld. |

### Tellers van een bundel

De code is hier de veldnaam van de teller in het antwoord van de server (overzicht van een bundel).

<!-- terms:bundleCounter -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `batchCount` | Batches | Het aantal geleverde bestanden (batches) dat deel uitmaakt van de bundel. |
| `contentMutationCount` | Mutaties | Het aantal voorgestelde wijzigingen in de bundel, zonder de afgeronde controles. |
| `readyCount` | Goedgekeurd | Mutaties die goedgekeurd zijn en klaarstaan om gepubliceerd te worden. |
| `rejectedCount` | Afgekeurd | Mutaties die afgekeurd zijn. |
| `blockedCount` | Tegengehouden | Mutaties met een kritiek herkenningsprobleem; daar is geen beslissing over mogelijk. |
| `identityIncidentCount` | Herkenningsproblemen | Artikelen waarvan een kritieke verwijzing, zoals een streepjescode, gewijzigd, verdwenen of hergebruikt is. |
| `expiredCount` | Vervallen | Mutaties die vervallen zijn door het annuleren van de bundel. |
| `bulkIncidentCount` | Gebundelde meldingen | Groepen van zoveel gelijksoortige vaststellingen dat ze samen als één gebeurtenis beoordeeld worden. |
| `criticalIssueCount` | Kritieke problemen | Vaststellingen van de hoogste ernst; ze houden een regel vast voor beoordeling. |
| `warningCount` | Waarschuwingen | Vaststellingen ter info; de betrokken regel blijft geldig. |
| `plannedCount` | Wordt bij bevriezen goedgekeurd | Mutaties met status gepland; bij het bevriezen worden ze in één keer goedgekeurd op uw naam. |
| `awaitingApprovalCount` | Wacht op beslissing | Mutaties die een uitdrukkelijke beslissing vragen; zolang die er niet is, kan de bundel niet bevroren worden. |
| `staleMutationCount` | Gegevens veranderd sinds de controle | Mutaties waarvan de bekende artikelgegevens na de controle van de levering veranderd zijn. |

### Behandelgevallen: waar een vaststelling over gaat

De tabellen van dit deel (tot en met "Sjablonen") horen bij de schermen Behandelgevallen, het detail van een versie
in Inrichting en Sjablonen (NT-11c).

<!-- terms:issueDomain -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `DELIVERY_SOURCE` | De levering zelf | Gaat over het aangeleverde bestand als geheel, zoals aantallen of volledigheid. |
| `STRUCTURE_DATASET` | Opbouw van het bestand | Gaat over de kopregel, de kolommen en de opbouw van de regels. |
| `IDENTITY_REFERENCE` | Herkenning van artikelen | Gaat over het herkennen van een artikel en over verwijzingen zoals streepjescodes. |
| `MAPPING_VALIDATION` | Gegevens en omzettingen | Gaat over verplichte waarden, soorten waarden, lengtes en omzettingen. |
| `PRICE` | Prijzen | Gaat over bedragen, percentages en prijsafwijkingen. |
| `COMPLETENESS_DELETE` | Volledigheid en verdwenen artikelen | Gaat over artikelen die ontbreken of verdwenen zijn; wordt vandaag nog niet gebruikt. |
| `PUBLICATION_TECHNICAL` | Technische fout bij het verwerken | Gaat over technische fouten bij het verwerken of publiceren. |
| `AUTHORISATION_CONFIG` | Instellingen en rechten | Gaat over de beschrijving van het bestand en de rechten daarop. |
| `SUPPLEMENT` | Aanvullingen op een aanbieding | Gaat over aanvullingen bij een aanbieding; wordt vandaag nog niet gebruikt. |

### Behandelgevallen: op welk niveau vastgesteld

<!-- terms:controlLevel -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `DELIVERY` | Het hele bestand | Vastgesteld op het bestand als geheel, bijvoorbeeld een leeg bestand. |
| `STRUCTURE` | Kopregel en kolommen | Vastgesteld op de opbouw van het bestand, zoals de kopregel of het aantal kolommen. |
| `RECORD` | Eén regel | Vastgesteld op één regel van het bestand; alleen die regel wordt verworpen. |

### Behandelgevallen: hoe ver de gevolgen reiken

<!-- terms:impactScope -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `RECORD` | Alleen deze regel | Enkel de betrokken regel van het bestand is getroffen. |
| `DELIVERY` | De hele levering | Geen enkel artikel uit dit bestand is bruikbaar. |
| `DEFINITION` | De beschrijving van het bestand | De beschrijving van het bestand zelf moet aangepast worden. |
| `LIBRARY` | De bibliotheek | Raakt de bibliotheek, bijvoorbeeld een verwijzing die aan een ander artikel hangt. |

### Behandelgevallen: soort gebeurtenis in de geschiedenis

<!-- terms:issueEventKind -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `CREATED` | Aangemaakt | Het behandelgeval is voor het eerst aangemaakt. |
| `STATUS_CHANGE` | Status gewijzigd | De status van het behandelgeval is gewijzigd. |

### Behandelgevallen: wie een gebeurtenis veroorzaakte

<!-- terms:issueEventSource -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `HUMAN` | Een mens | Een gebruiker nam deze beslissing. |
| `SYSTEM` | Het systeem | Het systeem deed dit vanzelf, bijvoorbeeld een automatische heropening. |

### Prijsonderdeel

<!-- terms:priceComponent -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `BASE_PRICE` | Basisprijs | De basisprijs van het artikel. |
| `AKP` | Aankoopprijs | De aankoopprijs, als percentage van de basisprijs. |
| `VKP1` | Verkoopprijs 1 | Verkoopprijs 1, als percentage van de basisprijs. |
| `VKP2` | Verkoopprijs 2 | Verkoopprijs 2, als percentage van de basisprijs. |
| `VKP3` | Verkoopprijs 3 | Verkoopprijs 3, als percentage van de basisprijs. |
| `VKP4` | Verkoopprijs 4 | Verkoopprijs 4, als percentage van de basisprijs. |
| `VKP5` | Verkoopprijs 5 | Verkoopprijs 5, als percentage van de basisprijs. |
| `VKP_GROSS` | Brutoverkoopprijs | De brutoverkoopprijs, als percentage van de basisprijs. |

### Inrichting: velden van een extra veld en kritiek-overrules

De code is hier een veld uit de veldcatalogus (waarnaar een extra veld schrijft) of een veld met een kritiek-overrule.

<!-- terms:fieldKey -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `BASE_PRICE` | Basisprijs | De basisprijs van het artikel. |
| `AKP_PCT` | Aankoopprijs (%) | De aankoopprijs in procent van de basisprijs. |
| `VKP1_PCT` | Verkoopprijs 1 (%) | Verkoopprijs 1 in procent van de basisprijs. |
| `VKP2_PCT` | Verkoopprijs 2 (%) | Verkoopprijs 2 in procent van de basisprijs. |
| `VKP3_PCT` | Verkoopprijs 3 (%) | Verkoopprijs 3 in procent van de basisprijs. |
| `VKP4_PCT` | Verkoopprijs 4 (%) | Verkoopprijs 4 in procent van de basisprijs. |
| `VKP5_PCT` | Verkoopprijs 5 (%) | Verkoopprijs 5 in procent van de basisprijs. |
| `VKP_GROSS_PCT` | Brutoverkoopprijs (%) | De brutoverkoopprijs in procent van de basisprijs. |
| `EAN` | EAN-barcode | De streepjescode van het artikel. |
| `PIM_ID` | PIM-nummer | Het nummer van het artikel in het productinformatiesysteem. |
| `CAB_ID` | CAB-nummer | Het CAB-nummer van het artikel. |
| `E_MARK_ARTICLE_REFERENCE` | E-merk met artikelnummer | De combinatie van het E-merk en het artikelnummer. |
| `E_SUPPLIER` | Externe leveranciersidentiteit | De identiteit van de leverancier bij een ander systeem. |
| `SUPPLIER_BARCODE` | Leveranciersbarcode | De streepjescode die de leverancier zelf gebruikt. |
| `DESCRIPTION` | Omschrijving | De omschrijving van het artikel. |
| `BRAND` | Merk | Het merk van het artikel; een gebruiker van Prodis beheert dit, een levering overschrijft het niet. |
| `UNIT` | Eenheid | De eenheid van het artikel; een gebruiker van Prodis beheert dit, een levering overschrijft het niet. |
| `SUPPLIER` | Leverancier | De leverancier van het artikel. |
| `SUPPLIER_GROUP` | Groep van de leverancier | De artikelgroep van de leverancier. |
| `SUPPLIER_REFERENCE` | Artikelnummer van de leverancier | Het artikelnummer van de leverancier. |
| `DISCOUNT_CODE` | Kortingscode | De kortingscode die mee bepaalt om welk artikel het gaat. |
| `CURRENCY` | Valuta | De munt van de prijs. |

### Inrichting: bestandsformaat

<!-- terms:structureFormat -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `CSV` | CSV-bestand | Een tekstbestand met kolommen, gescheiden door een vast teken. |

### Inrichting: soort levering

<!-- terms:deliverySetKind -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `FULL_SNAPSHOT` | Volledig aanbod | Elke levering bevat het volledige aanbod van de leverancier. |
| `DELTA` | Enkel wijzigingen | Elke levering bevat alleen wat gewijzigd is. |
| `UNDECLARED` | Niet opgegeven | Er is niet vastgelegd of een levering het volledige aanbod of alleen wijzigingen bevat. |

### Inrichting: manier van prijscontrole

<!-- terms:priceControlModel -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `DEVIATION` | Afwijking van eerdere prijzen | Een prijs wordt vergeleken met de vorige prijs en met gemiddelden over twee periodes. |
| `BOXPLOT` | Uitschieters (nog niet beschikbaar) | Opsporing van uitschieters met een boxplot; wordt in deze versie nog niet ondersteund. |

### Inrichting: waar de waarde van een extra veld vandaan komt

<!-- terms:mappingValueKind -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `SOURCE_FIELD` | Kolom uit het bestand | De waarde komt uit een kolom van het bestand. |
| `FIXED_VALUE` | Vaste waarde | De waarde ligt vast in de beschrijving van het bestand. |
| `BOOKMARK` | Waarde die per koppeling ingevuld wordt | De waarde wordt per koppeling ingevuld; wordt in deze versie nog niet ondersteund. |
| `DERIVED` | Afgeleide waarde | De waarde wordt afgeleid uit andere velden; wordt in deze versie nog niet ondersteund. |

### Inrichting: vergelijking van een recordfilter

<!-- terms:filterOperator -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `EQUALS` | is gelijk aan | De waarde in de kolom is precies gelijk aan de vergelijkingswaarde. |
| `NOT_EQUALS` | is niet gelijk aan | De waarde in de kolom is niet gelijk aan de vergelijkingswaarde. |
| `BEGINS_WITH` | begint met | De waarde in de kolom begint met de vergelijkingswaarde. |
| `ENDS_WITH` | eindigt op | De waarde in de kolom eindigt op de vergelijkingswaarde. |
| `CONTAINS` | bevat | De waarde in de kolom bevat de vergelijkingswaarde. |
| `NOT_CONTAINS` | bevat niet | De waarde in de kolom bevat de vergelijkingswaarde niet. |

### Inrichting: wat een recordfilter doet

<!-- terms:filterOutcome -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `INCLUDE` | Meenemen | Regels die passen, horen bij de levering; regels die bij geen enkel meeneem-filter passen, vallen erbuiten. |
| `EXCLUDE` | Overslaan | Regels die passen, worden overgeslagen en niet verder gecontroleerd; dat is geen fout. |
| `REJECT` | Verwerpen | Regels die passen, horen niet te bestaan; ze worden verworpen en geteld. |

### Sjablonen: soort waarde van een invulpunt

Overal heet dit "invulpunt" (beslissing van de mens, zie het beslissingslog); in de tabel zelf staat het woord niet.

<!-- terms:bookmarkDataType -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `TEXT` | Tekst | Vrije tekst; voorloopnullen en hoofdletters blijven behouden. |
| `INTEGER` | Geheel getal | Een getal zonder cijfers na de komma. |
| `DECIMAL` | Decimaal getal | Een getal met cijfers na de komma. |
| `DATE` | Datum | Een datum zonder tijdstip. |
| `BOOLEAN` | Ja of nee | Een keuze tussen ja en nee. |
| `ENUM` | Keuze uit een lijst | Een keuze uit een vaste lijst met toegelaten waarden. |
| `SUPPLIER_REFERENCE` | Verwijzing naar een leverancier | De code van een bestaande leverancier of aankoopvereniging. |
| `LIBRARY_REFERENCE` | Verwijzing naar een bibliotheek | De code van een Prodis-bibliotheek. |
| `POLICY_PROFILE_REFERENCE` | Verwijzing naar een prijsprofiel | Een bestaand prijs- of beleidsprofiel van een versie. |

### Sjablonen: voor wie een ingevulde waarde geldt

<!-- terms:bookmarkScope -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `DEFINITION` | Voor de hele beschrijving | De waarde wordt bij het materialiseren vastgelegd en geldt voor elke koppeling van de beschrijving. |
| `LINK` | Per koppeling | De waarde wordt voor elke koppeling apart ingevuld. |

### Sjablonen: waar een ingevulde waarde terechtkomt

<!-- terms:bookmarkPlace -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `FIELD_MAPPING_FIXED_VALUE` | Vaste waarde van een extra veld | De waarde wordt de vaste waarde van een extra veld. |
| `RECORD_FILTER_COMPARE_VALUE` | Vergelijkingswaarde van een filter | De waarde wordt de vergelijkingswaarde van een filter op de regels. |
| `REVISION_IDENTITY_FIELD` | Kolom voor de herkenning | De waarde bepaalt een van de kolommen waarmee een artikel herkend wordt. |
| `REVISION_PRICE_POLICY` | Prijsbeleid van de versie | De waarde bepaalt een instelling van het prijsbeleid, zoals de grens voor prijsafwijking. |
| `LINK_LIBRARY_CODE` | Doelbibliotheek van de koppeling | De waarde wordt de doelbibliotheek van de koppeling. |
| `LINK_SEARCH_SUPPLIER` | Leverancierscode in de bibliotheek | De waarde wordt de code waaronder Prodis de leverancier zoekt; ze wordt nooit uit de leverancier afgeleid. |
| `LINK_SUPPLIER_ORGANISATION` | Leverancier van de koppeling | De waarde wordt de leverancier van de koppeling. |

### Waarvoor een beschrijving van een bestand dient

<!-- terms:definitionUsage -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `OWN_DEFINITION` | Eigen beschrijving | Een beschrijving van het bestand van één organisatie, met eigen versies en koppelingen. |
| `REUSABLE_TEMPLATE` | Herbruikbaar sjabloon | Een model waaruit beschrijvingen van bestanden gemaakt worden; het krijgt nooit zelf een koppeling of levering. |

### Sjablonen: nieuw of hergebruik

<!-- terms:materialisationMode -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `NEW_DEFINITION` | Nieuw — een nieuwe beschrijving met conceptversie en koppeling maken | Er komt een nieuwe beschrijving van het bestand met een conceptversie en een koppeling. |
| `REUSE_DEFINITION` | Hergebruik — alleen een koppeling toevoegen aan een bestaande, deelbare beschrijving | Er komt alleen een koppeling bij een bestaande beschrijving die gedeeld mag worden; de beschrijving blijft ongewijzigd. |

### Sjablonen: meldingen bij het materialiseren

<!-- terms:materialisationWarning -->
| Code | Nederlands | Uitleg |
| --- | --- | --- |
| `OPTIONAL_BOOKMARK_NOT_FILLED` | Een optionele waarde is niet ingevuld | Een optionele waarde is leeg gebleven; de standaardwaarde van het sjabloon geldt, als die er is. |
| `LINK_SEARCH_SUPPLIER_NOT_DERIVED` | Leverancierscode in de bibliotheek is niet ingevuld | Die code wordt nooit automatisch uit de leverancier afgeleid; vul ze zo nodig zelf in. |
