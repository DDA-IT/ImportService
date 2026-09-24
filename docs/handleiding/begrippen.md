# CatalogImport — begrippen en statusreferentie

Stand: 2026-09-24. Wordt bijgewerkt zodra de resterende schermen klaar zijn. Hoofdhandleiding:
[`README.md`](README.md). Stappen: [`standaardflows.md`](standaardflows.md).

De enumwaarden hieronder komen uit `Domain/src/main/java/be/dda/catalogimport/domain/`. Een waarde die
"gedeclareerd voor Fase 5" heet, bestaat in het model maar wordt vandaag niet gezet.

**Inhoud:** [Begrippen A-Z](#begrippen-a-z) · [Batchstatus](#batchstatus-importbatchstatus) ·
[Eindoordeel](#eindoordeel-validationresult) · [Creatiebeleid](#creatiebeleid-creationoutcome) ·
[Mutatiesoorten](#mutatiesoorten-mutationactiontype) · [Mutatiestatussen](#mutatiestatussen-mutationstatus) ·
[Statusredenen](#veelvoorkomende-statusredenen-statusreason) · [Bundelstatus](#bundelstatus-publicationbundlestatus) ·
[Doelmodus](#doelmodus-publicationtargetmode) · [Beslissingen](#beslissingen-bundledecisionkind-en-bundledecisionscope) ·
[Ernst](#ernst-van-een-issue-rowissueseverity) · [Revisie](#revisiestatus-revisionstatus) ·
[Overig](#overige-opsommingen)

## Begrippen A-Z

| Begrip | Uitleg |
| --- | --- |
| **accept-baseline** | Geauditeerde actie die een `SCREENED` batch aanvaardt als nulmeting van de lokale bronstaat. Geen publicatie. Kan één keer per batch en sluit bundel-opname uit. Verplicht: `acceptedBy` (niet `system`) en `reason`. |
| **Actor** | De naam die bij een schrijfactie wordt vastgelegd. Er is geen authenticatie: het is een zelf ingetypte naam (in de UI: eenmalig per browsersessie, in `sessionStorage`). |
| **Aanbieding** | Eén artikel van een leverancier, geïdentificeerd door de aanbiedingsidentiteit. |
| **Aanbiedingsidentiteit** | Leverancier + leveranciersgroep + leveranciersreferentie, optioneel + kortingscode. Bibliotheek en bronorganisatie zijn scope, geen sleutel. Een lege component verwerpt de regel. |
| **Archief** | Plaats op het bestandssysteem waar het originele bestand ongewijzigd bewaard wordt, met SHA-256. |
| **Batch** | Eén screening van één levering tegen één revisie. Eenheid van de werkvoorraad. |
| **Beslissing** | Vastlegging (wie, wanneer, waarom, van welke status) dat een mutatie is goedgekeurd of afgekeurd of dat een bundel is bevroren of geannuleerd. Het register is alleen-toevoegen. |
| **Bevriezen (freeze)** | Sluit een bundel af (`FROZEN`) na controle; keurt resterende `PLANNED` mee goed op naam van de bevriezer. Onomkeerbaar in de applicatie. |
| **Bibliotheek (`libraryCode`)** | Doelbibliotheek van een koppeling (bv. `PSARF012`). Scope, geen deel van de identiteit. |
| **Blokkeerreden (`blockedCode`)** | Reden waarom een batch `BLOCKED` is. |
| **Bookmark** | Benoemd, getypeerd invulveld in een sjabloon. Heeft een scope: `DEFINITION` (bij materialisatie vastgezet) of `LINK` (per koppeling ingevuld). Alleen via API. |
| **Bronorganisatie** | Wie het bestand aanlevert: `SUPPLIER` of `PURCHASING_ASSOCIATION`. |
| **Bronstaat** | Wat het systeem als huidige stand per aanbieding beschouwt. Alleen `accept-baseline` schrijft er vandaag in (herkomst `BASELINE_ACCEPTED`). |
| **Bulkincident** | Een foutgroep die boven de percentagedrempel valt (bv. `BULK_PRICE_INCIDENT`). Leidt tot beoordeling. |
| **`continue`** | `POST /batches/{id}/continue`: hervat een batch op `MUTATING`. Wordt niet op naam vastgelegd. |
| **Creatiebeleid** | Beoordeelt of nieuwe aanbiedingen zonder tussenkomst aangemaakt mogen worden (`creationOutcome`). |
| **Definitie (ImportDefinition)** | Beschrijving van hoe een bestand van een bronorganisatie gelezen wordt. Heeft revisies. |
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
| **Koppeling (ImportLink)** | Verbindt een definitie met een leverancier en een bibliotheek. |
| **Kritieke kolom / kritieke lijn** | Een kolom die de configuratie als kritiek markeert; een afgewezen regel met een fout op zo'n kolom is een kritieke lijn en vraagt beoordeling. |
| **Levering (Delivery)** | Eén aangeleverd bestand met een `deliveryReference`. |
| **`deliveryReference`** | Door u gekozen referentie; idempotentiesleutel per taak. |
| **Mutatie** | Eén voorgestelde wijziging in het mutatieplan van een batch. |
| **Mutatieplan** | De lijst mutaties van een batch. |
| **Nulmeting** | Zie *accept-baseline*. |
| **Publicatiebundel** | Verzameling batches die samen beoordeeld en bevroren wordt. |
| **Revisie** | Een versie van een definitie: `DRAFT` → `ACTIVE` → `SUPERSEDED`. Een batch is gescreend tegen één vaste revisie. |
| **Setup-API** | Ontwikkelhulp voor het inrichten van een keten; standaard uit; geen authenticatie. |
| **Sjabloon** | Definitie met `usageType = REUSABLE_TEMPLATE`; kan nooit een koppeling of batch krijgen. |
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

## Revisiestatus (`RevisionStatus`)

| Waarde | Betekenis |
| --- | --- |
| `DRAFT` | concept; bewerkbaar; niet inzetbaar |
| `SCREENING` | screening van een testbestand loopt |
| `REVIEW_REQUIRED` | beoordeling nodig |
| `PENDING_APPROVAL` | wacht op goedkeuring |
| `ACTIVE` | actief; hoogstens één per definitie; mag voor nieuwe leveringen gebruikt worden |
| `SUPERSEDED` | vervangen; leesbaar, krijgt geen nieuwe leveringen |
| `WITHDRAWN` | ingetrokken; mag niet geactiveerd worden |

De setup-API gebruikt vandaag enkel `DRAFT`, `ACTIVE` en `SUPERSEDED` (via aanmaken en activeren); of de
overige waarden ergens gezet worden is **nog te verifiëren**.

## Overige opsommingen

| Enum | Waarden | Betekenis |
| --- | --- | --- |
| `SourceOrganisationType` | `SUPPLIER`, `PURCHASING_ASSOCIATION` | leverancier of aankoopvereniging |
| `DefinitionUsageType` | `OWN_DEFINITION`, `REUSABLE_TEMPLATE` | eigen definitie of herbruikbaar sjabloon |
| `TaskTriggerType` | `MANUAL`, `SCHEDULED` | manueel gestart of gepland (scheduler bestaat nog niet) |
| `TaskRunStatus` | `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` | status van één taakuitvoering; `PENDING` en `RUNNING` houden de concurrency-token vast |
| `SourceStateOrigin` | `BASELINE_ACCEPTED`, `PUBLISHED` | herkomst van een bronstaatrij; `PUBLISHED` is Fase 5 |
| `IdentityProfileKind` | o.a. `THREE_PART` | opbouw van de aanbiedingsidentiteit; volledige lijst **nog te verifiëren** |
| `Criticality` | zie code | kritiek of niet-kritiek per kolom; exacte waarden **nog te verifiëren** |

## Belangrijke drempelparameters (per revisie, altijd een percentage)

| Parameter | Standaard | Betekenis |
| --- | --- | --- |
| `creationThresholdSharePercent` | 1 (**nog te verifiëren** als kolomdefault) | boven dit percentage van de bestaande omvang wachten creaties op goedkeuring |
| `maxCriticalSharePercent` | 1 (idem) | boven dit percentage kritieke lijnen wordt de levering `BLOCKED`; eronder `REVIEW_REQUIRED` |
| `maxRejectedSharePercent` | niet geconfigureerd | drempel voor verworpen regels |
| `bulkIncidentSharePercent` | 1 (idem) | bulkincident boven dit aandeel |

Vergelijking: `aantal × 100 > percentage × omvang`; exact op de grens is niet overschreden. De demo/het
scenario zet `creationThresholdSharePercent` op 10 en `maxCriticalSharePercent` op 25.
