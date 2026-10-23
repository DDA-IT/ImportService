/**
 * Het woordenboek van de schermen (NT-5, V7): per domein `code -> { label, uitleg }`. De redactionele
 * bron is `docs/handleiding/begrippen.md` (sectie "Woordenlijst"); deze tabel volgt die tekst letterlijk
 * en `src/test/terms.test.ts` bewaakt dat ze synchroon blijven. Pas dus nooit hier alleen een tekst aan.
 *
 * Regels: `label` is gewoon Nederlands, `uitleg` is één zin, geen ruwe codes, geen Engels. De technische
 * code is nooit de hoofdtekst; ze staat klein in een tooltip of onder "Technische details (voor support)".
 */

export type TermEntry = { label: string; uitleg: string };

export const TERM_DOMAINS = [
  'batchStatus',
  'validationResult',
  'mutationStatus',
  'mutationAction',
  'bundleStatus',
  'revisionStatus',
  'targetMode',
  'organisationType',
  'taskTrigger',
  'identityProfile',
  'fieldReferenceKind',
  'severity',
  'criticality',
  'creationPolicy',
  'issueCaseStatus',
  'publicationRunStatus',
  'revisionField',
  'setupField',
  // NT-10: scherm "Controleren" (gereedheidscontrole + proefinlezing).
  'readinessStatus',
  'readinessCheck',
  'issueCode',
  'deliveryEffect',
  'trialVerdict',
  'trialStage',
  'trialCounter',
  'trialSampleStatus',
  'columnRole',
  'currencyOrigin',
  'thresholdOutcome',
  'trialCheck',
  'trialReason',
  // NT-11a: werkvoorraad, batchdetail, uploadresultaat en de mutatielijst.
  'batchCounter',
  'mutationStatusReason',
  'changePart',
  'discountCodeState',
  'referenceType',
  'issueIncidentKind',
  'issueHandlingStatus',
  // NT-11b: bundelschermen (beslissingsregister, bevriezen, publicatie, tellers van een bundel).
  'decisionKind',
  'decisionScope',
  'selectionFilterField',
  'freezeCheck',
  'contractStatus',
  'bundleCounter',
  // NT-11c: behandelgevallen, revisiedetail (inrichting) en sjablonen.
  'issueDomain',
  'controlLevel',
  'impactScope',
  'issueEventKind',
  'issueEventSource',
  'priceComponent',
  'fieldKey',
  'structureFormat',
  'deliverySetKind',
  'priceControlModel',
  'mappingValueKind',
  'filterOperator',
  'filterOutcome',
  'bookmarkDataType',
  'bookmarkScope',
  'bookmarkPlace',
  'definitionUsage',
  'materialisationMode',
  'materialisationWarning',
  // S9: overname van de legacy-configuratie (ontvangsten, rapport, bewerkingen op een extra veld).
  'legacyReceiptStatus',
  'legacyOutcome',
  'legacyReportStatus',
  'legacySecretOutcome',
  'legacyAction',
  'legacyProdisReference',
  'legacyDefinitionStatus',
  'transformKind',
  'legacyReportCode',
  // P7: publicatie naar de Prodis-database.
  'publicationItemStatus',
  'publicationNotice',
  'publicationItemReason',
  'legacyMaster',
  'parallelRunOutcome',
  'parallelRunStatus',
] as const;
export type TermDomain = (typeof TERM_DOMAINS)[number];

/** De sleutel voor "geen waarde" (bv. een eindoordeel dat nog niet vastgesteld is). */
export const NULL_CODE = 'null';

export const DICTIONARY: Record<TermDomain, Record<string, TermEntry>> = {
  batchStatus: {
    RECEIVED: { label: 'Ontvangen', uitleg: 'Geregistreerd; het lezen is nog niet gestart.' },
    SCREENING: { label: 'Wordt gecontroleerd', uitleg: 'Het bestand wordt regel per regel gelezen.' },
    MUTATING: {
      label: 'Wijzigingen worden bepaald',
      uitleg: 'Vergelijking met de bekende stand; kan hervat worden.',
    },
    SCREENED: { label: 'Gecontroleerd', uitleg: 'Klaar; de voorgestelde wijzigingen staan klaar.' },
    BLOCKED: { label: 'Tegengehouden', uitleg: 'De levering is als geheel onbruikbaar.' },
    FAILED: {
      label: 'Technisch mislukt',
      uitleg: 'Er ging technisch iets mis; opnieuw proberen is mogelijk.',
    },
    BASELINE_ACCEPTED: {
      label: 'Aanvaard als nulmeting',
      uitleg: 'Aanvaard als vertrekpunt; er is niets gepubliceerd.',
    },
  },
  validationResult: {
    VALID: { label: 'Geldig', uitleg: 'Er is niets van betekenis gevonden.' },
    VALID_WITH_WARNINGS: {
      label: 'Geldig met opmerkingen',
      uitleg: 'Er zijn alleen opmerkingen; de levering is bruikbaar.',
    },
    REVIEW_REQUIRED: {
      label: 'Beoordeling nodig',
      uitleg: 'Een mens moet kijken, bijvoorbeeld bij veel nieuwe artikelen of een kritieke fout.',
    },
    BLOCKING: {
      label: 'Blokkerend',
      uitleg: 'Er is een kritiek probleem; zonder ingreep gaat er niets door.',
    },
    [NULL_CODE]: {
      label: 'Nog niet bepaald',
      uitleg: 'Het eindoordeel is nog niet vastgesteld; lees dit nooit als geldig.',
    },
  },
  mutationStatus: {
    PLANNED: { label: 'Gepland', uitleg: 'Wordt bij het bevriezen van de publicatie mee goedgekeurd.' },
    AWAITING_APPROVAL: { label: 'Wacht op goedkeuring', uitleg: 'Vraagt een uitdrukkelijke beslissing.' },
    READY_FOR_PUBLICATION: { label: 'Goedgekeurd', uitleg: 'Goedgekeurd en klaar om gepubliceerd te worden.' },
    REJECTED: { label: 'Afgekeurd', uitleg: 'Afgekeurd; er is altijd een reden vastgelegd.' },
    BLOCKED: {
      label: 'Tegengehouden',
      uitleg: 'Kritiek herkenningsprobleem; geen beslissing mogelijk.',
    },
    SKIPPED: {
      label: 'Overgeslagen',
      uitleg: 'Niet gepubliceerd omdat de inlezing als nulmeting aanvaard is.',
    },
    RECORDED: { label: 'Vastgelegd', uitleg: 'Legt vast dat de controle afgerond is; geen echte wijziging.' },
    EXPIRED: { label: 'Vervallen', uitleg: 'Door het annuleren van de publicatie.' },
    IN_PROGRESS: { label: 'Wordt gepubliceerd', uitleg: 'De publicatie van deze wijziging loopt.' },
    PUBLISHED: { label: 'Gepubliceerd', uitleg: 'Deze wijziging is gepubliceerd.' },
    TECHNICALLY_FAILED: {
      label: 'Technisch mislukt',
      uitleg: 'De publicatie van deze wijziging is technisch mislukt.',
    },
  },
  mutationAction: {
    CREATE: { label: 'Nieuw artikel', uitleg: 'Een artikel dat nog niet bekend was.' },
    UPDATE: {
      label: 'Wijziging',
      uitleg: 'Een bekend artikel waarvan iets veranderd is, bijvoorbeeld de prijs.',
    },
    IDENTITY_REFERENCE_INCIDENT: {
      label: 'Herkenningsprobleem',
      uitleg:
        'Een kritieke verwijzing om het artikel te herkennen is gewijzigd, verdwenen, hergebruikt of dubbelzinnig; er wijzigt niets.',
    },
    IMPORT_MARKER: {
      label: 'Afgeronde controle',
      uitleg: 'Legt vast dat de controle van de levering klaar is, ook als er niets veranderde.',
    },
  },
  bundleStatus: {
    ASSEMBLING: {
      label: 'In opbouw',
      uitleg: 'Inlezingen toevoegen en wijzigingen beoordelen kan nog.',
    },
    FROZEN: { label: 'Bevroren', uitleg: 'Afgesloten en klaar voor publicatie; nog te annuleren.' },
    CANCELLED: { label: 'Geannuleerd', uitleg: 'Niet doorgegaan; de inlezingen zijn weer vrij.' },
    PUBLISHING: { label: 'Wordt gepubliceerd', uitleg: 'De publicatie loopt.' },
    PARTIALLY_PUBLISHED: { label: 'Deels gepubliceerd', uitleg: 'Een deel van de publicatie is gepubliceerd.' },
    PUBLISHED: { label: 'Gepubliceerd', uitleg: 'De hele publicatie is gepubliceerd.' },
    PUBLICATION_FAILED: { label: 'Publicatie mislukt', uitleg: 'De publicatie is technisch mislukt.' },
  },
  revisionStatus: {
    DRAFT: { label: 'Concept', uitleg: 'Nog aan te passen en nog niet gebruikt voor leveringen.' },
    SCREENING: { label: 'Wordt gecontroleerd', uitleg: 'Een testbestand wordt gecontroleerd.' },
    REVIEW_REQUIRED: { label: 'Beoordeling nodig', uitleg: 'Een mens moet de uitkomst van de test bekijken.' },
    PENDING_APPROVAL: { label: 'Wacht op goedkeuring', uitleg: 'Wacht op een uitdrukkelijke goedkeuring.' },
    ACTIVE: {
      label: 'Actief',
      uitleg: 'Hiermee worden nieuwe leveringen gelezen; er is er hoogstens één.',
    },
    SUPERSEDED: {
      label: 'Vervangen',
      uitleg: 'Niet meer in gebruik, blijft leesbaar om oude leveringen te verklaren.',
    },
    WITHDRAWN: { label: 'Ingetrokken', uitleg: 'Ingetrokken en kan niet meer in gebruik genomen worden.' },
  },
  targetMode: {
    SIMULATION: { label: 'Proefpublicatie', uitleg: 'Er wordt niets naar Prodis geschreven.' },
    TRIAL_LIBRARY: {
      label: 'Controlebibliotheek',
      uitleg: 'De publicatie gaat naar een bibliotheek om te controleren, niet naar de echte.',
    },
    PRODUCTION: { label: 'Echte publicatie', uitleg: 'De publicatie gaat naar de echte bibliotheek.' },
  },
  organisationType: {
    SUPPLIER: { label: 'Leverancier', uitleg: 'Een bedrijf dat zijn eigen bestand levert.' },
    PURCHASING_ASSOCIATION: {
      label: 'Aankoopvereniging',
      uitleg: 'Een organisatie die bestanden levert namens meerdere leveranciers.',
    },
  },
  taskTrigger: {
    MANUAL: { label: 'Handmatig', uitleg: 'U start elke levering zelf door een bestand op te laden.' },
    SCHEDULED: { label: 'Gepland', uitleg: 'Leveringen worden automatisch op vaste tijden opgehaald.' },
  },
  identityProfile: {
    THREE_PART: {
      label: 'Leverancier + groep + referentie',
      uitleg: 'Zo herkennen we hetzelfde artikel in de volgende levering.',
    },
    FOUR_PART_WITH_DISCOUNT_CODE: {
      label: 'Leverancier + groep + referentie + kortingscode',
      uitleg: 'Zo herkennen we hetzelfde artikel in de volgende levering, met de kortingscode erbij.',
    },
  },
  fieldReferenceKind: {
    HEADER_NAME: {
      label: 'Kolommen op naam',
      uitleg: 'De kolommen worden herkend aan de naam in de eerste regel van het bestand.',
    },
    COLUMN_INDEX: {
      label: 'Kolommen op positie',
      uitleg: 'De kolommen worden herkend aan hun volgnummer; nodig als het bestand geen kolomnamen heeft.',
    },
  },
  severity: {
    CRITICAL: {
      label: 'Kritiek',
      uitleg: 'Onbetrouwbare herkenning of kritieke verwijzing; de regel wordt vastgehouden voor beoordeling.',
    },
    BLOCKING: { label: 'Blokkerend', uitleg: 'De hele levering is onbruikbaar.' },
    ERROR: { label: 'Fout', uitleg: 'De regel wordt verworpen.' },
    WARNING: { label: 'Waarschuwing', uitleg: 'Ter info; de regel blijft geldig.' },
    INFO: { label: 'Informatie', uitleg: 'Puur ter informatie.' },
  },
  criticality: {
    CRITICAL: {
      label: 'Kritiek',
      uitleg: 'Een fout in deze kolom vraagt altijd een beoordeling door een mens.',
    },
    NON_CRITICAL: { label: 'Niet kritiek', uitleg: 'Een fout in deze kolom verwerpt enkel de regel.' },
  },
  creationPolicy: {
    AUTOMATIC: {
      label: 'Automatisch',
      uitleg: 'Nieuwe artikelen blijven binnen de drempel en worden zonder tussenkomst gepland.',
    },
    INITIAL_LOAD: {
      label: 'Eerste levering',
      uitleg: 'Deze koppeling had nog geen artikelen; alle nieuwe artikelen wachten op goedkeuring.',
    },
    THRESHOLD_EXCEEDED: {
      label: 'Drempel overschreden',
      uitleg: 'Er zijn meer nieuwe artikelen dan de toegelaten drempel; ze wachten op goedkeuring.',
    },
    [NULL_CODE]: { label: 'Nog niet beoordeeld', uitleg: 'Het beleid voor nieuwe artikelen is nog niet beoordeeld.' },
  },
  issueCaseStatus: {
    AWAITING_REVIEW: { label: 'Wacht op beoordeling', uitleg: 'Een mens moet deze afwijking nog bekijken.' },
    CORRECTED: { label: 'Gecorrigeerd', uitleg: 'Door een mens gecorrigeerd.' },
    REJECTED: {
      label: 'Afgewezen',
      uitleg: 'Door een mens afgewezen; een identieke herlevering blijft onderdrukt.',
    },
    AUTO_RESOLVED: { label: 'Vanzelf opgelost', uitleg: 'Zonder tussenkomst opgelost; wordt vandaag nog niet gebruikt.' },
  },
  publicationRunStatus: {
    REQUESTED: { label: 'Aangevraagd', uitleg: 'De publicatierun is aangevraagd maar nog niet gestart.' },
    PREPARING: { label: 'Wordt voorbereid', uitleg: 'Het bestand voor de publicatie wordt opgebouwd.' },
    PUBLISHING: {
      label: 'Wordt gepubliceerd',
      uitleg: 'De artikelen worden naar Prodis geschreven; dit kan in meerdere stappen verlopen.',
    },
    SIMULATED: {
      label: 'Proef afgerond',
      uitleg: 'Het bestand is geschreven, maar er is niets echt aangepast.',
    },
    FAILED: { label: 'Technisch mislukt', uitleg: 'De publicatierun is technisch mislukt.' },
    WAITING_FOR_TARGET_CONTRACT: {
      label: 'Wacht op afspraken met het doelsysteem',
      uitleg: 'Er moet eerst afgesproken worden hoe het doelsysteem het bestand verwacht.',
    },
    READY_FOR_DELIVERY: { label: 'Klaar om aan te leveren', uitleg: 'Het bestand kan aan het doelsysteem aangeleverd worden.' },
    WRITTEN_TO_PSIMPORT: { label: 'Naar Prodis weggeschreven', uitleg: 'Het bestand is voor Prodis klaargezet.' },
    RESULT_UNKNOWN: { label: 'Uitkomst onbekend', uitleg: 'Het is niet duidelijk wat er met de publicatie gebeurd is.' },
    APPLIED: { label: 'Doorgevoerd', uitleg: 'Echt verwerkt in het doelsysteem.' },
    REJECTED_BY_PRODIS: { label: 'Geweigerd door Prodis', uitleg: 'Prodis heeft de publicatie geweigerd.' },
    RECOVERY_REQUIRED: { label: 'Herstel nodig', uitleg: 'Er is een herstelactie nodig voordat het verder kan.' },
  },
  // NT-6: de velden van een versie van de beschrijving van het bestand (revisie). De code is de veldnaam
  // uit het verzoek; hier gaat het om het woord en de uitleg bij het veld, niet om een waarde.
  revisionField: {
    delimiter: {
      label: 'Scheidingsteken',
      uitleg: 'Het teken tussen de kolommen, bijvoorbeeld een puntkomma of een komma.',
    },
    quoteChar: {
      label: 'Aanhalingsteken',
      uitleg: 'Het teken rond een waarde waarin het scheidingsteken voorkomt.',
    },
    charset: {
      label: 'Tekenset',
      uitleg: 'Hoe letters gecodeerd zijn; vreemde tekens zoals Ã© wijzen op een verkeerde keuze.',
    },
    hasHeader: { label: 'Kopregel', uitleg: 'Een regel met de namen van de kolommen boven de gegevens.' },
    headerLineNumber: {
      label: 'Regelnummer van de kopregel',
      uitleg: 'Op welke regel de kolomnamen staan; de regels erboven worden overgeslagen.',
    },
    fieldReferenceKind: {
      label: 'Kolommen herkennen',
      uitleg: 'Of een kolom herkend wordt aan haar naam of aan haar volgnummer.',
    },
    identityProfileKind: {
      label: 'Herkenning van een artikel',
      uitleg: 'Welke kolommen samen bepalen dat het in een volgende levering om hetzelfde artikel gaat.',
    },
    supplierFixedValue: {
      label: 'Vaste leverancier',
      uitleg: 'Een vaste leverancier voor alle regels, in plaats van een kolom.',
    },
    supplierGroupFixedValue: {
      label: 'Vaste groep',
      uitleg: 'Een vaste artikelgroep voor alle regels, in plaats van een kolom.',
    },
    identityUppercase: {
      label: 'Herkenning in hoofdletters',
      uitleg: 'Leverancier, groep en referentie worden in hoofdletters vergeleken.',
    },
    identitySourceColumn: {
      label: 'Uit een kolom',
      uitleg: 'De leverancier of de groep staat in een kolom van het bestand.',
    },
    identitySourceFixed: {
      label: 'Vaste waarde',
      uitleg: 'Dezelfde leverancier of groep voor alle regels van het bestand, zonder kolom.',
    },
    supplierField: { label: 'Kolom leverancier', uitleg: 'De kolom met de leverancier van het artikel.' },
    supplierGroupField: { label: 'Kolom groep', uitleg: 'De kolom met de artikelgroep van de leverancier.' },
    supplierReferenceField: {
      label: 'Kolom referentie',
      uitleg: 'De kolom met het artikelnummer van de leverancier.',
    },
    discountCodeField: {
      label: 'Kolom kortingscode',
      uitleg: 'De kolom met de kortingscode, die mee bepaalt om welk artikel het gaat.',
    },
    basePriceField: { label: 'Kolom basisprijs', uitleg: 'De kolom met de prijs van het artikel.' },
    descriptionField: {
      label: 'Kolom omschrijving',
      uitleg: 'De kolom met de omschrijving van het artikel; mag leeg blijven.',
    },
    currencyField: {
      label: 'Kolom valuta',
      uitleg: 'De kolom met de munt van de prijs; zonder deze kolom geldt de standaardvaluta van de koppeling.',
    },
    canonicalisationVersion: {
      label: 'Herkenningsversie',
      uitleg: 'Bepaalt hoe een artikel herkend wordt; na de eerste aanvaarde levering niet meer te wijzigen.',
    },
    creationThresholdSharePercent: {
      label: 'Drempel nieuwe artikelen (%)',
      uitleg: 'Boven dit aandeel nieuwe artikelen wacht het aanmaken op een goedkeuring.',
    },
    maxCriticalSharePercent: {
      label: 'Maximum ter beoordeling (%)',
      uitleg: 'Boven dit aandeel regels die een beoordeling vragen, wordt de hele levering tegengehouden.',
    },
    maxRejectedSharePercent: {
      label: 'Maximum verworpen regels (%)',
      uitleg: 'Grens voor het aandeel verworpen regels; niet ingesteld betekent dat er geen grens geldt.',
    },
    bulkIncidentSharePercent: {
      label: 'Drempel bulkincident (%)',
      uitleg: 'Vanaf dit aandeel worden gelijksoortige vaststellingen samen als één incident gemeld.',
    },
    // NT-11c: de overige velden van het revisiedetail en het bewerkformulier.
    expectedColumnCount: {
      label: 'Verwacht aantal kolommen',
      uitleg: 'Het aantal kolommen dat het bestand hoort te hebben; is het niet ingesteld, dan wordt het aantal niet getoetst.',
    },
    structureFormat: { label: 'Bestandsformaat', uitleg: 'Het soort bestand dat gelezen wordt.' },
    deliverySetKind: {
      label: 'Soort levering',
      uitleg: 'Of elke levering het volledige aanbod bevat of alleen de wijzigingen.',
    },
    basePriceZeroAllowed: {
      label: 'Prijs nul toegelaten',
      uitleg: 'Of een basisprijs van nul is toegelaten; anders wordt zo een regel verworpen.',
    },
    basePriceNegativeAllowed: {
      label: 'Negatieve prijs toegelaten',
      uitleg: 'Of een negatieve basisprijs is toegelaten; anders wordt zo een regel verworpen.',
    },
    priceDeviationPercent: {
      label: 'Grens voor prijsafwijking (%)',
      uitleg: 'Wijkt een prijs meer dan dit percentage af van eerdere prijzen, dan wordt de regel als afwijkend gemeld.',
    },
    priceDeviationSeverity: {
      label: 'Ernst van een prijsafwijking',
      uitleg: 'Hoe zwaar een te grote prijsafwijking weegt.',
    },
    priceDerivationTolerance: {
      label: 'Tolerantie bij afgeleide prijzen',
      uitleg: 'Hoeveel een afgeleide prijs mag afwijken van wat de onderdelen opleveren; het bedrag wordt letterlijk doorgegeven en nooit afgerond.',
    },
    priceAvgShortWindow: {
      label: 'Korte periode voor het prijsgemiddelde',
      uitleg: 'Het aantal goedgekeurde prijzen waarover het korte gemiddelde wordt berekend.',
    },
    priceAvgLongWindow: {
      label: 'Lange periode voor het prijsgemiddelde',
      uitleg: 'Het aantal goedgekeurde prijzen waarover het lange gemiddelde wordt berekend.',
    },
    priceControlModel: {
      label: 'Manier van prijscontrole',
      uitleg: 'Het model waarmee prijsafwijkingen worden opgespoord.',
    },
    creationThresholdAbsolute: {
      label: 'Drempel nieuwe artikelen (aantal)',
      uitleg: 'Tot dit aantal nieuwe artikelen worden ze zonder tussenkomst aangemaakt.',
    },
    changeReason: { label: 'Wijzigingsreden', uitleg: 'Waarom deze versie is aangemaakt of gewijzigd.' },
  },
  // NT-6: de begrippen van het stappenplan "Nieuwe leverancier en taak" buiten de revisie.
  setupField: {
    sourceOrganisation: {
      label: 'Leverancier of aankoopvereniging',
      uitleg: 'De organisatie die het bestand aanlevert.',
    },
    code: { label: 'Code', uitleg: 'Een korte, unieke code waarmee u dit onderdeel later terugvindt.' },
    definition: {
      label: 'Importdefinitie',
      uitleg: 'Legt vast hoe het bestand van deze organisatie eruitziet en hoe het gelezen wordt.',
    },
    revision: {
      label: 'Versie',
      uitleg: 'Een vastgelegde stand van de importdefinitie; een nieuwe versie wordt pas gebruikt na activeren.',
    },
    link: {
      label: 'Koppeling',
      uitleg: 'Verbindt de importdefinitie met één leverancier en één Prodis-bibliotheek.',
    },
    supplierCode: {
      label: 'Leverancier van de koppeling',
      uitleg: 'De leverancier wiens artikelen via deze koppeling binnenkomen.',
    },
    libraryCode: {
      label: 'Doelbibliotheek',
      uitleg: 'De Prodis-bibliotheek waarin de gegevens uiteindelijk terechtkomen.',
    },
    librarySearchSupplierCode: {
      label: 'Leverancierscode in de bibliotheek',
      uitleg: 'De code waaronder Prodis deze leverancier zoekt; wordt nooit automatisch ingevuld.',
    },
    defaultCurrency: {
      label: 'Standaardvaluta',
      uitleg: 'Geldt als het bestand geen munt vermeldt; leeg betekent euro.',
    },
    task: { label: 'Taak', uitleg: 'De ingang waarop u leveringen oplaadt.' },
    preventConcurrentRuns: {
      label: 'Gelijktijdige uitvoeringen voorkomen',
      uitleg: 'Er wordt nooit meer dan één levering tegelijk voor deze taak verwerkt.',
    },
  },
  // NT-10: de toestand van één regel van de checklist (gereedheidscontrole, NT-8).
  readinessStatus: {
    OK: { label: 'In orde', uitleg: 'Deze controle is geslaagd; hier hoeft u niets te doen.' },
    PROBLEM: {
      label: 'Probleem',
      uitleg: 'Zolang dit niet opgelost is, kan de koppeling geen leveringen ontvangen.',
    },
    INFO: { label: 'Ter info', uitleg: 'Goed om te weten; dit houdt leveringen niet tegen.' },
  },
  // NT-10: de regels van de checklist. Een probleem draagt dezelfde code als de upload of het activeren zou geven.
  readinessCheck: {
    READY_LINK_ACTIVE: { label: 'De koppeling staat aan', uitleg: 'De koppeling is als actief ingesteld.' },
    INFO_LINK_INACTIVE: {
      label: 'De koppeling staat uit',
      uitleg: 'De koppeling is als niet actief ingesteld; een opgeladen levering wordt toch aangenomen.',
    },
    READY_ACTIVE_REVISION: {
      label: 'Er is een versie in gebruik',
      uitleg: 'De importdefinitie heeft een actieve versie waarmee leveringen gelezen worden.',
    },
    NO_ACTIVE_REVISION: {
      label: 'Er is nog geen versie in gebruik',
      uitleg: 'Zonder actieve versie van de importdefinitie kan geen enkele levering gelezen worden.',
    },
    READY_PRICE_FIELD: {
      label: 'De prijskolom is ingesteld',
      uitleg: 'De versie in gebruik weet in welke kolom de prijs staat.',
    },
    CONFIG_PRICE_FIELD_MISSING: {
      label: 'De prijskolom ontbreekt',
      uitleg: 'De versie in gebruik zegt niet in welke kolom de prijs staat; zo kan geen enkele regel gelezen worden.',
    },
    READY_LINK_BOOKMARKS: {
      label: 'De verplichte invulpunten van de koppeling zijn ingevuld',
      uitleg: 'Alle waarden die de importdefinitie per koppeling vraagt, zijn ingevuld.',
    },
    CONFIG_REQUIRED_BOOKMARK_MISSING: {
      label: 'Verplichte invulpunten zijn niet ingevuld',
      uitleg: 'De importdefinitie vraagt een of meer waarden die nog leeg zijn; zolang ze ontbreken, wordt een levering geweigerd.',
    },
    READY_DRAFT_ACTIVATABLE: {
      label: 'De conceptversie kan geactiveerd worden',
      uitleg: 'De controle die bij het activeren gebeurt, vindt geen fouten in deze conceptversie.',
    },
    INFO_NO_DRAFT_REVISION: {
      label: 'Er is geen conceptversie om te activeren',
      uitleg: 'Er is geen versie in gebruik en ook geen concept; maak eerst een nieuwe versie van de importdefinitie.',
    },
    READY_TASK_ACCEPTS_UPLOAD: {
      label: 'De taak neemt opgeladen bestanden aan',
      uitleg: 'Op deze taak kunt u een levering opladen.',
    },
    LINK_HAS_NO_TASK: {
      label: 'Er is nog geen taak',
      uitleg: 'Zonder taak is er geen ingang om een levering op te laden.',
    },
    TASK_NOT_MANUAL: {
      label: 'De taak start niet handmatig',
      uitleg: 'Deze taak haalt leveringen automatisch op en neemt geen opgeladen bestand aan.',
    },
    TASK_HAS_DELIVERY_CONFIGURATION: {
      label: 'De taak haalt leveringen zelf op',
      uitleg: 'Aan deze taak hangt een instelling om leveringen op te halen; ze neemt daarom geen opgeladen bestand aan.',
    },
    INFO_LIBRARY_NOT_VERIFIED: {
      label: 'De doelbibliotheek is niet nagekeken',
      uitleg: 'Of de bibliotheekcode echt bestaat in Prodis, wordt hier niet gecontroleerd.',
    },
    CONFIG_INVALID: {
      label: 'Fout in de importdefinitie',
      uitleg: 'De importdefinitie is onvolledig of ongeldig.',
    },
    REVISION_NOT_ACTIVATABLE: {
      label: 'Deze versie kan niet geactiveerd worden',
      uitleg: 'Activeren gaat alleen vanuit een concept; deze versie is al actief of vervangen.',
    },
    INFO_CONFIG_CHECKS_SKIPPED: {
      label: 'Niet alle controles konden uitgevoerd worden',
      uitleg:
        'Sommige controles konden nog niet uitgevoerd worden omdat ze afhangen van een fout hierboven; na herstel kunnen er nog fouten bijkomen.',
    },
  },
  // NT-10: wat de screening en de proefinlezing vaststellen (blokkade, regelfout, melding). Een code met een
  // veldnaam erachter (zoals een ontbrekende kolom) wordt op het deel vóór de dubbele punt opgezocht.
  issueCode: {
    CONFIG_FORMAT_UNSUPPORTED: {
      label: 'Bestandsformaat niet ondersteund',
      uitleg: 'Het ingestelde bestandsformaat kan niet gelezen worden.',
    },
    CONFIG_CHARSET_UNKNOWN: {
      label: 'Onbekende tekenset',
      uitleg: 'De ingestelde tekenset bestaat niet of wordt niet ondersteund.',
    },
    CONFIG_DELIMITER_MISSING: {
      label: 'Scheidingsteken ontbreekt',
      uitleg: 'De importdefinitie zegt niet welk teken de kolommen scheidt.',
    },
    CONFIG_DELIMITER_INVALID: {
      label: 'Ongeldig scheidingsteken',
      uitleg: 'Het ingestelde scheidingsteken kan niet gebruikt worden.',
    },
    CONFIG_QUOTE_INVALID: {
      label: 'Ongeldig aanhalingsteken',
      uitleg: 'Het ingestelde aanhalingsteken kan niet gebruikt worden.',
    },
    CONFIG_HEADER_LINE_INVALID: {
      label: 'Ongeldig regelnummer van de kopregel',
      uitleg: 'Het ingestelde regelnummer van de kopregel kan niet gebruikt worden.',
    },
    CONFIG_FIELD_REFERENCE_KIND_INVALID: {
      label: 'Ongeldige manier om kolommen te herkennen',
      uitleg: 'De keuze tussen kolommen op naam of op positie is niet geldig.',
    },
    CONFIG_HEADER_REFERENCE_INCONSISTENT: {
      label: 'Kopregel en kolomherkenning passen niet samen',
      uitleg: 'Kolommen op naam herkennen kan alleen als het bestand een kopregel heeft.',
    },
    CONFIG_IDENTITY_FIELD_MISSING: {
      label: 'Kolom voor de herkenning ontbreekt',
      uitleg: 'Een van de kolommen waarmee een artikel herkend wordt, is niet ingesteld.',
    },
    CONFIG_PRICE_FIELD_MISSING: {
      label: 'Prijskolom ontbreekt',
      uitleg: 'De importdefinitie zegt niet in welke kolom de prijs staat.',
    },
    CONFIG_COLUMN_COUNT_INVALID: {
      label: 'Ongeldig aantal kolommen',
      uitleg: 'Het ingestelde aantal kolommen kan niet gebruikt worden.',
    },
    CONFIG_FIELD_REFERENCE_INVALID: {
      label: 'Ongeldige kolomaanduiding',
      uitleg: 'Een kolom is aangeduid op een manier die niet past bij de gekozen kolomherkenning.',
    },
    CONFIG_CANONICALISATION_VERSION_UNSUPPORTED: {
      label: 'Herkenningsversie niet ondersteund',
      uitleg: 'Deze toepassing kent de ingestelde herkenningsversie niet.',
    },
    CONFIG_CANONICALISATION_VERSION_REQUIRED: {
      label: 'Herkenningsversie ontbreekt',
      uitleg: 'Voor deze instelling is een herkenningsversie nodig.',
    },
    CONFIG_LINK_CURRENCY_INVALID: {
      label: 'Ongeldige standaardvaluta',
      uitleg: 'De standaardvaluta van de koppeling is geen code van drie hoofdletters; ze wordt nooit stil vervangen.',
    },
    CONFIG_DISCOUNT_FIELD_MISSING: {
      label: 'Kolom kortingscode ontbreekt',
      uitleg:
        'Bij herkenning met kortingscode moet de kolom voor de kortingscode ingesteld zijn en in het bestand staan.',
    },
    CONFIG_FIELD_NOT_RESOLVED: {
      label: 'Kolom niet gevonden',
      uitleg: 'Een ingestelde kolom kon in het bestand niet teruggevonden worden.',
    },
    CONFIG_COLUMN_INDEX_OUT_OF_RANGE: {
      label: 'Kolomnummer buiten het bestand',
      uitleg: 'Een ingesteld kolomnummer is groter dan het aantal kolommen in het bestand.',
    },
    CONFIG_MAPPING_TARGET_UNKNOWN: {
      label: 'Onbekend doelveld',
      uitleg: 'Een extra veld verwijst naar een doelveld dat niet bestaat.',
    },
    CONFIG_MAPPING_SOURCE_UNRESOLVED: {
      label: 'Bron van een extra veld niet gevonden',
      uitleg: 'Een extra veld haalt zijn waarde uit een kolom of invulpunt dat niet bestaat.',
    },
    CONFIG_MAPPING_DUPLICATE_TARGET: {
      label: 'Doelveld dubbel gebruikt',
      uitleg: 'Twee extra velden schrijven naar hetzelfde doelveld.',
    },
    CONFIG_MAPPING_TYPE_INCOMPATIBLE: {
      label: 'Soort waarde past niet',
      uitleg: 'Een extra veld levert een soort waarde die het doelveld niet aanneemt.',
    },
    CONFIG_FIELD_MAPPING_DUPLICATES_REVISION: {
      label: 'Veld dubbel ingesteld',
      uitleg: 'Een extra veld herhaalt een veld dat de versie zelf al leest.',
    },
    CONFIG_IDENTITY_CLASS_CONFLICT: {
      label: 'Botsing met de herkenning',
      uitleg: 'Een extra veld botst met de kolommen waarmee een artikel herkend wordt.',
    },
    CONFIG_OWNER_NOT_CHANGEABLE: {
      label: 'Veld hier niet in te stellen',
      uitleg: 'Dit doelveld kan vanuit deze bron niet gevuld worden.',
    },
    CONFIG_PRICE_COMPONENT_DUPLICATE: {
      label: 'Prijsonderdeel dubbel',
      uitleg: 'Hetzelfde prijsonderdeel is meer dan eens ingesteld.',
    },
    CONFIG_TRANSFORM_INVALID: {
      label: 'Ongeldige omzetting',
      uitleg: 'De omzetting van een extra veld is niet correct ingesteld.',
    },
    CONFIG_FILTER_INVALID: { label: 'Ongeldig filter', uitleg: 'Een filter op de regels is niet correct ingesteld.' },
    CONFIG_FIELD_CRITICALITY_INVALID: {
      label: 'Ongeldige instelling kritiek',
      uitleg: 'Een kolom is op een niet toegelaten manier als kritiek of niet kritiek ingesteld.',
    },
    CONFIG_PRICE_CONTROL_MODEL_UNSUPPORTED: {
      label: 'Prijscontrole niet ondersteund',
      uitleg: 'De gekozen manier van prijscontrole bestaat in deze versie van de toepassing niet.',
    },
    CONFIG_BOOKMARK_VALUE_INVALID: {
      label: 'Ongeldige waarde van een invulpunt',
      uitleg: 'Een ingevulde waarde past niet bij wat het invulpunt toelaat.',
    },
    CONFIG_BOOKMARK_VALUE_TOO_LONG: {
      label: 'Waarde van een invulpunt te lang',
      uitleg: 'Een ingevulde waarde is langer dan toegelaten.',
    },
    CONFIG_BOOKMARK_SCOPE_PLACE_CONFLICT: {
      label: 'Invulpunt op de verkeerde plaats',
      uitleg: 'Een invulpunt is ingesteld op een plaats die niet bij zijn soort past.',
    },
    CONFIG_BOOKMARK_WITHOUT_PLACE: {
      label: 'Invulpunt zonder bestemming',
      uitleg: 'Een invulpunt zegt niet waar zijn waarde terechtkomt.',
    },
    CONFIG_BOOKMARK_PLACE_UNRESOLVED: {
      label: 'Bestemming van een invulpunt bestaat niet',
      uitleg: 'Een invulpunt verwijst naar een veld of filter dat niet (meer) bestaat.',
    },
    CONFIG_BOOKMARK_PLACE_NOT_SUPPORTED: {
      label: 'Bestemming van een invulpunt niet ondersteund',
      uitleg: 'Een invulpunt verwijst naar een plaats die niet gevuld kan worden.',
    },
    CONFIG_IDENTITY_SOURCE_CONFLICT: {
      label: 'Kolom en vaste waarde tegelijk',
      uitleg: 'Voor leverancier of groep is zowel een kolom als een vaste waarde ingesteld; kies er één.',
    },
    MAPPING_VALUE_PASSED_THROUGH: {
      label: 'Waarde ongewijzigd doorgelaten',
      uitleg: 'De waarde stond niet in de omzettingstabel en is, zoals ingesteld, ongewijzigd doorgelaten.',
    },
    SOURCE_FILE_EMPTY: { label: 'Het bestand is leeg', uitleg: 'Het bestand bevat geen enkele regel.' },
    SOURCE_NO_DATA_RECORDS: {
      label: 'Geen regels met gegevens',
      uitleg: 'Het bestand bevat geen enkele regel met gegevens, hooguit een kopregel.',
    },
    SOURCE_BOM_REMOVED: {
      label: 'Onzichtbaar beginteken genegeerd',
      uitleg: 'Het bestand begon met een onzichtbaar teken dat de tekenset aanduidt; dat teken is genegeerd.',
    },
    HEADER_LINE_MISSING: {
      label: 'Kopregel ontbreekt',
      uitleg: 'Het bestand eindigt voordat de ingestelde kopregel bereikt is.',
    },
    HEADER_FIELD_MISSING: {
      label: 'Verplichte kolom ontbreekt',
      uitleg: 'Een kolom die de importdefinitie verwacht, staat niet in de kopregel van het bestand.',
    },
    HEADER_DUPLICATE_FIELD: {
      label: 'Kolomnaam dubbel',
      uitleg: 'Dezelfde kolomnaam komt meer dan eens voor in de kopregel.',
    },
    HEADER_COLUMN_COUNT_MISMATCH: {
      label: 'Ander aantal kolommen',
      uitleg: 'De kopregel heeft een ander aantal kolommen dan de importdefinitie verwacht.',
    },
    HEADER_FIELD_SHIFTED: {
      label: 'Kolom verschoven',
      uitleg: 'Een verwachte kolom staat op een andere plaats; ze wordt op naam teruggevonden.',
    },
    HEADER_FIELD_SEMANTIC_CHANGE: {
      label: 'Kolom van betekenis veranderd',
      uitleg: 'Op de plaats van een belangrijke kolom staat een andere kolomnaam; doorgaan zou de verkeerde kolom lezen.',
    },
    HEADER_UNKNOWN_COLUMN: {
      label: 'Onbekende kolom',
      uitleg: 'Het bestand bevat een kolom die de importdefinitie niet kent; ze wordt niet gebruikt.',
    },
    ROW_COLUMN_COUNT_MISMATCH: {
      label: 'Regel met een ander aantal kolommen',
      uitleg: 'Deze regel heeft meer of minder kolommen dan de kopregel en wordt verworpen.',
    },
    ROW_TOO_LONG: { label: 'Regel te lang', uitleg: 'Deze regel is langer dan toegelaten en wordt verworpen.' },
    CSV_UNCLOSED_QUOTE: {
      label: 'Aanhalingsteken niet gesloten',
      uitleg: 'In deze regel wordt een aanhalingsteken geopend maar niet gesloten; de regel wordt verworpen.',
    },
    VALUE_MISSING: { label: 'Waarde ontbreekt', uitleg: 'Een verplichte waarde is leeg; de regel wordt verworpen.' },
    CANONICAL_CONTROL_CHARACTER: {
      label: 'Onzichtbaar stuurteken',
      uitleg: 'Een waarde bevat een onzichtbaar stuurteken; de regel wordt verworpen.',
    },
    VALUE_TOO_LONG: {
      label: 'Waarde te lang',
      uitleg: 'Een waarde is langer dan toegelaten; ze wordt nooit ingekort en de regel wordt verworpen.',
    },
    VALUE_TYPE_MISMATCH: {
      label: 'Verkeerde soort waarde',
      uitleg: 'Een waarde is niet van de verwachte soort, bijvoorbeeld tekst waar een getal hoort.',
    },
    DATE_UNREADABLE: { label: 'Datum onleesbaar', uitleg: 'Een datum kon niet gelezen worden.' },
    DATE_AMBIGUOUS: {
      label: 'Datum dubbelzinnig',
      uitleg: 'Een datum kan op meer dan één manier gelezen worden; er wordt niet gegokt.',
    },
    VALUE_DEFAULT_APPLIED: {
      label: 'Standaardwaarde gebruikt',
      uitleg: 'Een lege waarde is aangevuld met de ingestelde standaardwaarde.',
    },
    TRANSFORM_FAILED: { label: 'Omzetting mislukt', uitleg: 'Een waarde kon niet omgezet worden zoals ingesteld.' },
    TRANSFORM_DIVIDE_BY_ZERO: {
      label: 'Deling door nul',
      uitleg: 'Een omzetting zou door nul delen; de regel wordt verworpen.',
    },
    MAPPING_VALUE_UNKNOWN: {
      label: 'Onbekende waarde',
      uitleg: 'Een waarde staat niet in de ingestelde vertaallijst.',
    },
    FILTER_RECORD_REJECTED: {
      label: 'Verworpen door een filter',
      uitleg: "Volgens een filter hoort zo'n regel niet te bestaan; ze wordt verworpen en geteld.",
    },
    FILTER_COLUMN_MISSING: {
      label: 'Filterkolom ontbreekt',
      uitleg: 'De kolom waarop gefilterd wordt, staat niet in het bestand.',
    },
    IDENTITY_COMPONENT_EMPTY: {
      label: 'Herkenning onvolledig',
      uitleg: 'Leverancier, groep of referentie is leeg, dus het artikel kan niet herkend worden.',
    },
    PRICE_MISSING: { label: 'Prijs ontbreekt', uitleg: 'De prijs is leeg; ze wordt nooit op nul gezet.' },
    PRICE_UNREADABLE: { label: 'Prijs onleesbaar', uitleg: 'De prijs is geen leesbaar getal.' },
    PRICE_SCALE_EXCEEDED: {
      label: 'Te veel cijfers na de komma',
      uitleg: 'De prijs heeft meer cijfers na de komma dan toegelaten; ze wordt nooit afgerond.',
    },
    PRICE_OUT_OF_RANGE: { label: 'Prijs te groot', uitleg: 'De prijs is te groot om te bewaren.' },
    PRICE_ZERO_NOT_ALLOWED: {
      label: 'Prijs nul niet toegelaten',
      uitleg: 'Een prijs van nul is voor deze importdefinitie niet toegelaten.',
    },
    PRICE_NEGATIVE_NOT_ALLOWED: {
      label: 'Negatieve prijs niet toegelaten',
      uitleg: 'Een negatieve prijs is voor deze importdefinitie niet toegelaten.',
    },
    PRICE_CURRENCY_MISMATCH: {
      label: 'Valuta past niet',
      uitleg: 'De valuta van een prijsonderdeel verschilt van die van de prijs.',
    },
    PRICE_PERCENTAGE_NOT_COMPUTABLE: {
      label: 'Percentage niet te berekenen',
      uitleg: 'Een percentage van een prijsonderdeel kan niet berekend worden.',
    },
    PRICE_DERIVATION_MISMATCH: {
      label: 'Prijzen kloppen niet met elkaar',
      uitleg: 'Een afgeleide prijs wijkt meer af dan toegelaten van wat de onderdelen opleveren.',
    },
    PRICE_PERCENTAGE_OUT_OF_RANGE: {
      label: 'Percentage buiten bereik',
      uitleg: 'Een percentage ligt buiten het toegelaten bereik.',
    },
    PRICE_DEVIATION_EXCEEDED: {
      label: 'Grote prijsafwijking',
      uitleg: 'De prijs wijkt sterk af van eerdere prijzen; de regel blijft geldig maar valt op.',
    },
    PRICE_REFERENCE_NOT_AVAILABLE: {
      label: 'Geen eerdere prijzen',
      uitleg: 'Er zijn nog geen eerdere prijzen om mee te vergelijken.',
    },
    DUPLICATE_IDENTITY_IN_DELIVERY: {
      label: 'Hetzelfde artikel meermaals',
      uitleg: 'Hetzelfde artikel staat meer dan eens in dit bestand; er wordt nooit zomaar de laatste regel genomen.',
    },
    DUPLICATE_REFERENCE_IN_DELIVERY: {
      label: 'Dezelfde verwijzing bij verschillende artikelen',
      uitleg: 'Een verwijzing zoals een streepjescode staat bij meer dan één artikel in dit bestand.',
    },
    IDENTITY_HASH_COLLISION: {
      label: 'Onbetrouwbare herkenning',
      uitleg: 'Twee verschillende artikelen krijgen dezelfde herkenningssleutel; de herkenning is niet te vertrouwen.',
    },
    IDENTITY_REFERENCE_INCIDENT: {
      label: 'Herkenningsprobleem',
      uitleg: 'Een verwijzing om het artikel te herkennen is gewijzigd, verdwenen of dubbelzinnig.',
    },
    REFERENCE_LINK_PROPOSED: {
      label: 'Mogelijk hetzelfde artikel',
      uitleg: 'Een nieuw artikel lijkt via een verwijzing op een bekend artikel; dit is ter info.',
    },
    BULK_PRICE_INCIDENT: {
      label: 'Veel gelijke prijsafwijkingen',
      uitleg: 'Zoveel gelijksoortige prijsafwijkingen dat ze samen als één gebeurtenis beoordeeld worden.',
    },
    BULK_IDENTITY_INCIDENT: {
      label: 'Veel gelijke herkenningsproblemen',
      uitleg: 'Zoveel gelijksoortige herkenningsproblemen dat ze samen als één gebeurtenis beoordeeld worden.',
    },
    INITIAL_LOAD_REQUIRES_APPROVAL: {
      label: 'Eerste levering vraagt goedkeuring',
      uitleg: 'Deze koppeling had nog geen artikelen; alle nieuwe artikelen wachten op een goedkeuring.',
    },
    BULK_CREATION_INCIDENT: {
      label: 'Veel nieuwe artikelen',
      uitleg: 'Er zijn meer nieuwe artikelen dan de drempel toelaat; ze wachten op een goedkeuring.',
    },
    CRITICAL_RECORD_THRESHOLD_EXCEEDED: {
      label: 'Te veel regels ter beoordeling',
      uitleg: 'Het aandeel regels dat een beoordeling vraagt, ligt boven de ingestelde grens.',
    },
    REJECTED_RECORD_THRESHOLD_EXCEEDED: {
      label: 'Te veel verworpen regels',
      uitleg: 'Het aandeel verworpen regels ligt boven de ingestelde grens.',
    },
    BYTE_SIZE_MISMATCH: {
      label: 'Andere bestandsgrootte dan verwacht',
      uitleg: 'Het bestand is groter of kleiner dan bij het opladen opgegeven.',
    },
    RECORD_COUNT_MISMATCH: {
      label: 'Ander aantal regels dan verwacht',
      uitleg: 'Het bestand bevat meer of minder regels dan bij het opladen opgegeven.',
    },
    ROW_ISSUE_RECORDING_CAPPED: {
      label: 'Niet alle voorbeelden bewaard',
      uitleg: 'Er zijn meer voorvallen dan er voorbeelden bewaard worden; de aantallen kloppen wel.',
    },
    SCREENING_FAILED: {
      label: 'Technische fout bij het controleren',
      uitleg: 'De controle van de levering is technisch mislukt; opnieuw proberen is mogelijk.',
    },
    SCREENING_INTERRUPTED: {
      label: 'Controle onderbroken',
      uitleg: 'De controle van de levering werd onderbroken voordat ze klaar was.',
    },
  },
  // NT-10: wat een vaststelling met de levering als geheel doet.
  deliveryEffect: {
    NONE: { label: 'Geen gevolg voor de levering', uitleg: 'Dit raakt enkel de regel zelf of is ter info.' },
    REVIEW: {
      label: 'Vraagt een beoordeling',
      uitleg: 'De levering gaat niet ongezien door; een mens moet ze bekijken.',
    },
    BLOCK: { label: 'Houdt de levering tegen', uitleg: 'De levering wordt als geheel tegengehouden.' },
  },
  // NT-10: het oordeel van een proefinlezing.
  trialVerdict: {
    WOULD_BLOCK: {
      label: 'Zou tegengehouden worden',
      uitleg: 'Een echte levering met dit bestand zou als geheel tegengehouden worden.',
    },
    NO_BLOCKER_FOUND: {
      label: 'Zou aanvaard worden',
      uitleg: 'De proef vond niets dat een echte levering met dit bestand zou tegenhouden.',
    },
  },
  // NT-10: in welke stap een proefinlezing de blokkade vond.
  trialStage: {
    CONFIGURATION: {
      label: 'Importdefinitie',
      uitleg: 'Het probleem zit in de ingestelde importdefinitie, nog voor het bestand gelezen werd.',
    },
    READING: {
      label: 'Lezen van het bestand',
      uitleg: 'Het probleem trad op bij het lezen van de kopregel of van de regels.',
    },
    FILE_LEVEL: {
      label: 'Het bestand als geheel',
      uitleg: 'Het probleem gaat over het bestand als geheel, bijvoorbeeld geen enkele regel met gegevens.',
    },
    IDENTITY: {
      label: 'Herkenning van artikelen',
      uitleg: 'Het probleem gaat over het herkennen van artikelen, bijvoorbeeld hetzelfde artikel twee keer.',
    },
    THRESHOLD: {
      label: 'Grens overschreden',
      uitleg: 'Te veel regels hebben een probleem in verhouding tot het hele bestand.',
    },
  },
  // NT-10: de tellers van een proefinlezing. De code is de naam van de teller in het antwoord.
  trialCounter: {
    rawRecordCount: {
      label: 'Gelezen regels',
      uitleg: 'Alle regels met gegevens, zonder kopregel en zonder lege regels.',
    },
    validRecordCount: { label: 'Geldig', uitleg: 'Regels die volledig gelezen konden worden.' },
    rejectedRecordCount: { label: 'Verworpen', uitleg: 'Regels met een fout; zo een regel wordt niet verwerkt.' },
    filteredOutCount: {
      label: 'Buiten filter',
      uitleg: 'Regels die door de filters van de importdefinitie overgeslagen worden.',
    },
    errorBeforeFilterCount: {
      label: 'Fout vóór het filter',
      uitleg: 'Regels met een fout die al optrad voordat de filters konden beslissen.',
    },
    criticalLineCount: {
      label: 'Kritieke regels',
      uitleg: 'Regels met een fout in een kritieke kolom; die vragen een beoordeling door een mens.',
    },
    duplicateIdentityCount: {
      label: 'Dubbele artikelen',
      uitleg: 'Regels waarvan het artikel meer dan eens in het bestand staat, de eerste keer inbegrepen.',
    },
    scopeRecordCount: {
      label: 'Regels binnen filter',
      uitleg: 'Gelezen regels min de regels buiten filter; hierop worden de grenzen berekend.',
    },
    physicalLineCount: {
      label: 'Regels in het bestand',
      uitleg: 'Alle regels van het bestand, ook de kopregel en lege regels.',
    },
    prefixLineCount: {
      label: 'Regels vóór de kopregel',
      uitleg: 'Regels boven de kopregel die overgeslagen worden.',
    },
    skippedBlankLineCount: { label: 'Lege regels', uitleg: 'Lege regels die overgeslagen worden.' },
    columnCount: { label: 'Kolommen', uitleg: 'Het aantal kolommen dat in het bestand gevonden is.' },
    linesWithReplacementCharacter: {
      label: 'Regels met onleesbare tekens',
      uitleg: 'Regels met tekens die met de gekozen tekenset niet gelezen konden worden.',
    },
  },
  // NT-10: wat er met één voorbeeldregel van een proefinlezing zou gebeuren.
  trialSampleStatus: {
    VALID: { label: 'Geldig', uitleg: 'Deze regel zou gelezen en verwerkt worden.' },
    REJECTED: { label: 'Verworpen', uitleg: 'Deze regel zou verworpen worden; de reden staat erbij.' },
    FILTERED_OUT: {
      label: 'Buiten filter',
      uitleg: 'Deze regel valt buiten de filters van de importdefinitie en wordt overgeslagen.',
    },
    UNREADABLE: { label: 'Onleesbaar', uitleg: 'Deze regel kon niet in kolommen gesplitst worden.' },
  },
  // NT-10: waarvoor een verwachte kolom dient.
  columnRole: {
    IDENTITY: { label: 'Herkenning', uitleg: 'Deze kolom bepaalt mee om welk artikel het gaat.' },
    PRICE: { label: 'Prijs', uitleg: 'De kolom met de prijs.' },
    CURRENCY: { label: 'Valuta', uitleg: 'De kolom met de munt van de prijs.' },
    DESCRIPTION: { label: 'Omschrijving', uitleg: 'De kolom met de omschrijving van het artikel.' },
    DISCOUNT: { label: 'Kortingscode', uitleg: 'De kolom met de kortingscode.' },
    MAPPING: { label: 'Extra veld', uitleg: 'Een kolom die naar een extra veld overgenomen wordt.' },
    FILTER: { label: 'Filter', uitleg: 'Een kolom waarop de regels gefilterd worden.' },
  },
  // NT-10: waar de valuta van een prijs vandaan komt.
  currencyOrigin: {
    SOURCE: { label: 'Uit het bestand', uitleg: 'De valuta staat in het bestand zelf.' },
    LINK_DEFAULT: {
      label: 'Standaard van de koppeling',
      uitleg: 'Het bestand vermeldt geen valuta; de standaardvaluta van de koppeling geldt.',
    },
    SYSTEM_DEFAULT: {
      label: 'Euro (standaard)',
      uitleg: 'Het bestand en de koppeling vermelden geen valuta; dan geldt euro.',
    },
  },
  // NT-10: de uitkomst van een leveringsgrens.
  thresholdOutcome: {
    NOT_APPLICABLE: { label: 'Geen grens ingesteld', uitleg: 'Voor deze importdefinitie geldt hier geen grens.' },
    UNDETERMINED: {
      label: 'Niet vast te stellen',
      uitleg: 'Er zijn geen regels om het aandeel op te berekenen.',
    },
    WITHIN: { label: 'Binnen de grens', uitleg: 'Het aandeel ligt niet boven de ingestelde grens.' },
    EXCEEDED: {
      label: 'Grens overschreden',
      uitleg: 'Het aandeel ligt boven de ingestelde grens; een echte levering zou tegengehouden worden.',
    },
  },
  // NT-10: controles die een proefinlezing niet kan uitvoeren.
  trialCheck: {
    CREATION_POLICY: {
      label: 'Te veel nieuwe artikelen',
      uitleg: 'Of er te veel nieuwe artikelen zijn, kan pas bij een echte levering, want dan kennen we de bestaande artikelen.',
    },
    IDENTITY_HASH_COLLISION_AGAINST_SOURCE_STATE: {
      label: 'Botsing met bekende artikelen',
      uitleg: 'Of een herkenningssleutel botst met een al bekend artikel, blijkt pas bij een echte levering.',
    },
    REFERENCE_CONTROL: {
      label: 'Controle van verwijzingen',
      uitleg: 'Of verwijzingen zoals streepjescodes gewijzigd of hergebruikt zijn, blijkt pas bij een echte levering.',
    },
    DUPLICATE_REFERENCE_IN_DELIVERY: {
      label: 'Dezelfde verwijzing bij verschillende artikelen',
      uitleg: 'Deze controle zit nog niet in de proefinlezing.',
    },
    PRICE_DEVIATION: {
      label: 'Prijsafwijking',
      uitleg: 'Of een prijs sterk afwijkt, vraagt eerdere prijzen; een proef vergelijkt daar niet mee.',
    },
    MANIFEST_COUNTS: {
      label: 'Verwacht aantal regels en bestandsgrootte',
      uitleg: 'Die geeft u pas op bij het opladen van een echte levering.',
    },
    IDENTITY_HASH_COLLISION_IN_FILE: {
      label: 'Botsing van herkenningssleutels in het bestand',
      uitleg: 'Deze controle houdt de proefinlezing niet bij.',
    },
    IDENTITY_INCIDENTS_IN_CRITICAL_THRESHOLD: {
      label: 'Herkenningsproblemen in de grens voor beoordeling',
      uitleg: 'Die tellen pas mee bij een echte levering; de proef telt enkel de kritieke regels.',
    },
    DUPLICATE_IDENTITY_IN_DELIVERY: {
      label: 'Hetzelfde artikel meermaals',
      uitleg: 'Het bestand bevat te veel artikelen om allemaal bij te houden; dubbele artikelen zijn niet volledig nagegaan.',
    },
  },
  // NT-10: waarom een proefinlezing een controle niet uitvoert.
  trialReason: {
    NO_SOURCE_STATE: {
      label: 'Geen bekende artikelen',
      uitleg: 'Een proef vergelijkt niet met de artikelen die al bekend zijn.',
    },
    NOT_IMPLEMENTED_V1: { label: 'Nog niet in de proef', uitleg: 'Deze controle zit nog niet in de proefinlezing.' },
    NO_PRICE_HISTORY: { label: 'Geen eerdere prijzen', uitleg: 'Een proef vergelijkt niet met eerdere prijzen.' },
    NO_MANIFEST: { label: 'Geen verwachte aantallen', uitleg: 'Bij een proef geeft u geen verwachte aantallen op.' },
    NOT_TRACKED: { label: 'Niet bijgehouden', uitleg: 'De proef houdt deze gegevens niet bij.' },
    TRACKING_LIMIT_REACHED: {
      label: 'Grens bereikt',
      uitleg: 'Het bestand bevat meer artikelen dan de proef kan bijhouden.',
    },
  },
  // NT-11a: de tellers van een batch (werkvoorraad, batchdetail en uploadresultaat). De code is de veldnaam in het antwoord.
  batchCounter: {
    rawRecordCount: {
      label: 'Gelezen regels',
      uitleg: 'Alle regels met gegevens in het bestand, zonder kopregel en zonder lege regels.',
    },
    validRecordCount: { label: 'Geldige regels', uitleg: 'Regels die volledig gelezen konden worden.' },
    rejectedRecordCount: { label: 'Verworpen regels', uitleg: 'Regels met een fout; zo een regel wordt niet verwerkt.' },
    filteredOutCount: {
      label: 'Buiten filter',
      uitleg: 'Regels die door de filters van de importdefinitie overgeslagen worden.',
    },
    errorBeforeFilterCount: {
      label: 'Fout vóór het filter',
      uitleg: 'Regels met een fout die al optrad voordat de filters konden beslissen.',
    },
    duplicateIdentityCount: {
      label: 'Dubbele artikelen',
      uitleg: 'Regels waarvan het artikel meer dan eens in het bestand staat, de eerste keer inbegrepen.',
    },
    newCount: { label: 'Nieuwe artikelen', uitleg: 'Artikelen die nog niet bekend waren.' },
    changedCount: { label: 'Gewijzigde artikelen', uitleg: 'Bekende artikelen waarvan iets veranderd is.' },
    unchangedCount: { label: 'Ongewijzigde artikelen', uitleg: 'Bekende artikelen die niet veranderd zijn.' },
    contentMutationCount: {
      label: 'Voorgestelde wijzigingen',
      uitleg: 'Het aantal wijzigingen dat de levering voorstelt aan de artikelen, zonder de afgeronde controle zelf.',
    },
    awaitingApprovalCount: {
      label: 'Wacht op goedkeuring',
      uitleg: 'Wijzigingen die een uitdrukkelijke beslissing van een mens vragen.',
    },
    identityIncidentCount: {
      label: 'Herkenningsproblemen',
      uitleg: 'Artikelen waarvan een kritieke verwijzing, zoals een streepjescode, gewijzigd, verdwenen of hergebruikt is.',
    },
    bulkIncidentCount: {
      label: 'Gebundelde meldingen',
      uitleg: 'Groepen van zoveel gelijksoortige vaststellingen dat ze samen als één gebeurtenis beoordeeld worden.',
    },
    criticalLineCount: {
      label: 'Kritieke regels',
      uitleg: 'Regels met een fout in een kritieke kolom; die vragen een beoordeling door een mens.',
    },
    criticalIssueCount: {
      label: 'Kritieke problemen',
      uitleg: 'Vaststellingen van de hoogste ernst; ze houden een regel vast voor beoordeling.',
    },
    warningCount: {
      label: 'Waarschuwingen',
      uitleg: 'Vaststellingen ter info; de betrokken regel blijft geldig.',
    },
  },
  // NT-11a: waarom een wijziging in haar status staat. Bevat ook de vier soorten herkenningsprobleem.
  mutationStatusReason: {
    INITIAL_LOAD_REQUIRES_APPROVAL: {
      label: 'Eerste levering vraagt goedkeuring',
      uitleg: 'Deze koppeling had nog geen artikelen; het nieuwe artikel wacht op goedkeuring.',
    },
    BULK_CREATION_INCIDENT: {
      label: 'Veel nieuwe artikelen',
      uitleg: 'Er zijn meer nieuwe artikelen dan de drempel toelaat; dit artikel wacht op goedkeuring.',
    },
    BULK_PRICE_INCIDENT: {
      label: 'Veel gelijke prijsafwijkingen',
      uitleg: 'Zoveel gelijksoortige prijsafwijkingen in deze levering dat ze samen beoordeeld worden.',
    },
    BULK_IDENTITY_INCIDENT: {
      label: 'Veel gelijke herkenningsproblemen',
      uitleg: 'Zoveel gelijksoortige herkenningsproblemen in deze levering dat ze samen beoordeeld worden.',
    },
    BASELINE_ACCEPTED_WITHOUT_PUBLICATION: {
      label: 'Overgeslagen door de nulmeting',
      uitleg: 'De levering is als nulmeting aanvaard; deze wijziging is overgeslagen en niet gepubliceerd.',
    },
    CHANGED: {
      label: 'Verwijzing gewijzigd',
      uitleg: 'Een verwijzing van dit artikel, zoals een streepjescode, is gewijzigd.',
    },
    REMOVED: {
      label: 'Verwijzing verdwenen',
      uitleg: 'Een verwijzing van dit artikel, zoals een streepjescode, staat niet meer in de levering.',
    },
    REUSED: {
      label: 'Verwijzing hergebruikt',
      uitleg: 'Een verwijzing die al bij een ander artikel hoort, staat nu bij dit artikel.',
    },
    AMBIGUOUS: {
      label: 'Verwijzing dubbelzinnig',
      uitleg: 'Een verwijzing hoort volgens de levering bij meer dan één artikel.',
    },
  },
  // NT-11a: wat een wijziging raakt (het masker van een wijziging). Een prijsonderdeel draagt er een code achter.
  changePart: {
    ARTICLE: { label: 'Artikelgegevens', uitleg: 'De gegevens van het artikel zelf, niet de prijs.' },
    PRICE: { label: 'Basisprijs', uitleg: 'De basisprijs van het artikel.' },
  },
  // NT-11a: toestand van de kortingscode in de herkenning van een artikel.
  discountCodeState: {
    NOT_USED: { label: 'Niet van toepassing', uitleg: 'Deze herkenning gebruikt geen kortingscode.' },
    EMPTY: { label: 'Leeg', uitleg: 'De kolom met de kortingscode is ingesteld, maar de waarde is leeg.' },
    VALUE: { label: 'Ingevuld', uitleg: 'De kortingscode heeft een waarde.' },
  },
  // NT-11a: het soort verwijzing waarmee een artikel herkend wordt (kritieke verwijzingen).
  referenceType: {
    EAN: { label: 'EAN (streepjescode)', uitleg: 'De streepjescode van het artikel.' },
    PIM_ID: { label: 'PIM-nummer', uitleg: 'Het nummer van het artikel in het productinformatiesysteem.' },
    CAB_ID: { label: 'CAB-nummer', uitleg: 'Het CAB-nummer van het artikel.' },
    E_MARK_ARTICLE_REFERENCE: {
      label: 'E-merk met artikelnummer',
      uitleg: 'De combinatie van het E-merk en het artikelnummer.',
    },
  },
  // NT-11a: het soort samenvatting van een foutgroep.
  issueIncidentKind: {
    GENERIC: { label: 'Gewone foutgroep', uitleg: 'Dezelfde fout op hetzelfde veld, samengevat.' },
    PRICE: { label: 'Prijsafwijkingen', uitleg: 'Gelijksoortige prijsafwijkingen binnen dezelfde levering, samengevat.' },
    IDENTITY: {
      label: 'Herkenningsproblemen',
      uitleg: 'Gelijksoortige problemen met verwijzingen, zoals streepjescodes, samengevat.',
    },
    CREATION: { label: 'Nieuwe artikelen', uitleg: 'Samenvatting van het aantal nieuwe artikelen tegenover de drempel.' },
  },
  // NT-11a: hoe ver de behandeling van een vaststelling staat.
  issueHandlingStatus: {
    DETECTED: { label: 'Vastgesteld', uitleg: 'Door de controle vastgesteld; nog niet behandeld.' },
    AUTO_RESOLVED: { label: 'Vanzelf opgelost', uitleg: 'Door een volgende verwerking vanzelf opgelost.' },
    AWAITING_REVIEW: { label: 'Wacht op beoordeling', uitleg: 'Een mens moet dit nog bekijken.' },
    CORRECTED: { label: 'Gecorrigeerd', uitleg: 'Door een beheerder gecorrigeerd.' },
    ACCEPTED_FOR_BATCH: { label: 'Eenmalig aanvaard', uitleg: 'Aanvaard voor alleen deze levering.' },
    ACCEPTED_BY_RULE: { label: 'Aanvaard via een regel', uitleg: 'Aanvaard op grond van een vastgelegde uitzonderingsregel.' },
    REJECTED: { label: 'Afgewezen', uitleg: 'Afgewezen; de betrokken gegevens gaan niet door.' },
    EXPIRED_EXCEPTION: { label: 'Uitzondering verlopen', uitleg: 'De uitzondering die deze vaststelling onderdrukte, is verlopen.' },
    REOPENED: { label: 'Heropend', uitleg: 'Opnieuw geopend na een eerdere afhandeling.' },
  },
  // NT-11b: wat voor beslissing er in het beslissingsregister van een bundel staat.
  decisionKind: {
    APPROVE: { label: 'Goedkeuring', uitleg: 'Een wijziging of een groep wijzigingen is goedgekeurd.' },
    REJECT: { label: 'Afkeuring', uitleg: 'Een wijziging of een groep wijzigingen is afgekeurd.' },
    AUTO_APPROVE_PLANNED: {
      label: 'Automatische goedkeuring bij het bevriezen',
      uitleg: 'Bij het bevriezen zijn de wijzigingen die nog gepland stonden in één keer goedgekeurd op naam van wie bevroor.',
    },
    FREEZE: { label: 'Bevriezing', uitleg: 'De publicatie is bevroren: afgesloten en klaar voor publicatie.' },
    CANCEL: { label: 'Annulering', uitleg: 'De publicatie is geannuleerd.' },
  },
  // NT-11b: waar een beslissing in het beslissingsregister over ging.
  decisionScope: {
    MUTATION: { label: 'Eén wijziging', uitleg: 'De beslissing ging over één wijziging.' },
    GROUP: { label: 'Groep wijzigingen', uitleg: 'De beslissing ging over alle wijzigingen die aan een filter voldeden.' },
    BUNDLE: { label: 'Hele publicatie', uitleg: 'De beslissing ging over de publicatie als geheel.' },
  },
  // NT-11b: de onderdelen van de filter die bij een groepsbeslissing bewaard wordt. De code is de naam van het filterveld.
  selectionFilterField: {
    batchId: { label: 'Inlezing', uitleg: 'Enkel wijzigingen van deze inlezing.' },
    status: { label: 'Status', uitleg: 'Enkel wijzigingen met deze status.' },
    statusReason: { label: 'Reden van de status', uitleg: 'Enkel wijzigingen met deze reden.' },
    actionType: { label: 'Soort', uitleg: 'Enkel wijzigingen van deze soort.' },
    identityHash: { label: 'Wijzigingsgroep', uitleg: 'Enkel de wijzigingen van één artikel in één inlezing.' },
  },
  // NT-11b: waarom een bundel (nog) niet bevroren kan worden. Een code van de voorcontrole, dezelfde die het bevriezen zelf zou geven.
  freezeCheck: {
    BUNDLE_NOT_ASSEMBLING: {
      label: 'De publicatie is niet meer in opbouw',
      uitleg: 'Enkel een publicatie die nog in opbouw is, kan bevroren worden.',
    },
    BUNDLE_EMPTY: {
      label: 'De publicatie heeft geen inlezingen',
      uitleg: 'Een publicatie zonder actieve inlezing kan niet bevroren worden.',
    },
    BUNDLE_HAS_UNDECIDED_MUTATIONS: {
      label: 'Er wachten nog wijzigingen op een beslissing',
      uitleg: 'Bevriezen mag een openstaande vraag niet stilzwijgend beantwoorden; beslis eerst over deze wijzigingen.',
    },
    SOURCE_STATE_CHANGED_SINCE_SCREENING: {
      label: 'De bekende gegevens zijn veranderd sinds de controle',
      uitleg: 'Wat we over de artikelen wisten, is na de controle van de levering gewijzigd; controleer de levering opnieuw.',
    },
    BUNDLE_OFFER_CONFLICT: {
      label: 'Dezelfde aanbieding zit in meer dan één inlezing',
      uitleg: 'Twee inlezingen van deze publicatie willen hetzelfde artikel publiceren; keur één kant af.',
    },
    OFFER_ALREADY_IN_ANOTHER_BUNDLE: {
      label: 'Een aanbieding zit ook in een andere publicatie',
      uitleg: 'Hetzelfde artikel kan in een andere open of bevroren publicatie gepubliceerd worden; keur één kant af, of publiceer of annuleer eerst de andere publicatie.',
    },
  },
  // NT-11b: de stand van de afspraken met Prodis over het bestand van een publicatie.
  contractStatus: {
    UNVERIFIED_FIELD_INVENTORY: {
      label: 'Velden nog niet bevestigd',
      uitleg: 'De lijst met velden voor het importbestand van Prodis is nog niet door Prodis bevestigd; het bestand is een voorbeeld.',
    },
  },
  // NT-11b: de tellers van een bundel op het overzicht. De code is de veldnaam in het antwoord van de server.
  bundleCounter: {
    batchCount: { label: 'Inlezingen', uitleg: 'Het aantal geleverde bestanden (inlezingen) dat deel uitmaakt van de publicatie.' },
    contentMutationCount: {
      label: 'Wijzigingen',
      uitleg: 'Het aantal voorgestelde wijzigingen in de publicatie, zonder de afgeronde controles.',
    },
    readyCount: { label: 'Goedgekeurd', uitleg: 'Wijzigingen die goedgekeurd zijn en klaarstaan om gepubliceerd te worden.' },
    rejectedCount: { label: 'Afgekeurd', uitleg: 'Wijzigingen die afgekeurd zijn.' },
    blockedCount: {
      label: 'Tegengehouden',
      uitleg: 'Wijzigingen met een kritiek herkenningsprobleem; daar is geen beslissing over mogelijk.',
    },
    identityIncidentCount: {
      label: 'Herkenningsproblemen',
      uitleg: 'Artikelen waarvan een kritieke verwijzing, zoals een streepjescode, gewijzigd, verdwenen of hergebruikt is.',
    },
    expiredCount: { label: 'Vervallen', uitleg: 'Wijzigingen die vervallen zijn door het annuleren van de publicatie.' },
    bulkIncidentCount: {
      label: 'Gebundelde meldingen',
      uitleg: 'Groepen van zoveel gelijksoortige vaststellingen dat ze samen als één gebeurtenis beoordeeld worden.',
    },
    criticalIssueCount: {
      label: 'Kritieke problemen',
      uitleg: 'Vaststellingen van de hoogste ernst; ze houden een regel vast voor beoordeling.',
    },
    warningCount: { label: 'Waarschuwingen', uitleg: 'Vaststellingen ter info; de betrokken regel blijft geldig.' },
    plannedCount: {
      label: 'Wordt bij bevriezen goedgekeurd',
      uitleg: 'Wijzigingen met status gepland; bij het bevriezen worden ze in één keer goedgekeurd op uw naam.',
    },
    awaitingApprovalCount: {
      label: 'Wacht op beslissing',
      uitleg: 'Wijzigingen die een uitdrukkelijke beslissing vragen; zolang die er niet is, kan de publicatie niet bevroren worden.',
    },
    staleMutationCount: {
      label: 'Gegevens veranderd sinds de controle',
      uitleg: 'Wijzigingen waarvan de bekende artikelgegevens na de controle van de levering veranderd zijn.',
    },
  },
  // NT-11c: waar een vaststelling over gaat (het onderwerp) bij een behandelgeval.
  issueDomain: {
    DELIVERY_SOURCE: {
      label: 'De levering zelf',
      uitleg: 'Gaat over het aangeleverde bestand als geheel, zoals aantallen of volledigheid.',
    },
    STRUCTURE_DATASET: {
      label: 'Opbouw van het bestand',
      uitleg: 'Gaat over de kopregel, de kolommen en de opbouw van de regels.',
    },
    IDENTITY_REFERENCE: {
      label: 'Herkenning van artikelen',
      uitleg: 'Gaat over het herkennen van een artikel en over verwijzingen zoals streepjescodes.',
    },
    MAPPING_VALIDATION: {
      label: 'Gegevens en omzettingen',
      uitleg: 'Gaat over verplichte waarden, soorten waarden, lengtes en omzettingen.',
    },
    PRICE: { label: 'Prijzen', uitleg: 'Gaat over bedragen, percentages en prijsafwijkingen.' },
    COMPLETENESS_DELETE: {
      label: 'Volledigheid en verdwenen artikelen',
      uitleg: 'Gaat over artikelen die ontbreken of verdwenen zijn; wordt vandaag nog niet gebruikt.',
    },
    PUBLICATION_TECHNICAL: {
      label: 'Technische fout bij het verwerken',
      uitleg: 'Gaat over technische fouten bij het verwerken of publiceren.',
    },
    AUTHORISATION_CONFIG: {
      label: 'Instellingen en rechten',
      uitleg: 'Gaat over de importdefinitie en de rechten daarop.',
    },
    SUPPLEMENT: {
      label: 'Aanvullingen op een aanbieding',
      uitleg: 'Gaat over aanvullingen bij een aanbieding; wordt vandaag nog niet gebruikt.',
    },
  },
  // NT-11c: op welk niveau een vaststelling gedaan is.
  controlLevel: {
    DELIVERY: { label: 'Het hele bestand', uitleg: 'Vastgesteld op het bestand als geheel, bijvoorbeeld een leeg bestand.' },
    STRUCTURE: {
      label: 'Kopregel en kolommen',
      uitleg: 'Vastgesteld op de opbouw van het bestand, zoals de kopregel of het aantal kolommen.',
    },
    RECORD: { label: 'Eén regel', uitleg: 'Vastgesteld op één regel van het bestand; alleen die regel wordt verworpen.' },
  },
  // NT-11c: hoe ver de gevolgen van een vaststelling reiken.
  impactScope: {
    RECORD: { label: 'Alleen deze regel', uitleg: 'Enkel de betrokken regel van het bestand is getroffen.' },
    DELIVERY: { label: 'De hele levering', uitleg: 'Geen enkel artikel uit dit bestand is bruikbaar.' },
    DEFINITION: {
      label: 'De importdefinitie',
      uitleg: 'De importdefinitie zelf moet aangepast worden.',
    },
    LIBRARY: {
      label: 'De bibliotheek',
      uitleg: 'Raakt de bibliotheek, bijvoorbeeld een verwijzing die aan een ander artikel hangt.',
    },
  },
  // NT-11c: het soort gebeurtenis in de geschiedenis van een behandelgeval.
  issueEventKind: {
    CREATED: { label: 'Aangemaakt', uitleg: 'De afwijking is voor het eerst aangemaakt.' },
    STATUS_CHANGE: { label: 'Status gewijzigd', uitleg: 'De status van de afwijking is gewijzigd.' },
  },
  // NT-11c: wie een gebeurtenis in de geschiedenis van een behandelgeval veroorzaakte.
  issueEventSource: {
    HUMAN: { label: 'Een mens', uitleg: 'Een gebruiker nam deze beslissing.' },
    SYSTEM: { label: 'Het systeem', uitleg: 'Het systeem deed dit vanzelf, bijvoorbeeld een automatische heropening.' },
  },
  // NT-11c: het prijsonderdeel waar een vaststelling over gaat.
  priceComponent: {
    BASE_PRICE: { label: 'Basisprijs', uitleg: 'De basisprijs van het artikel.' },
    AKP: { label: 'Aankoopprijs', uitleg: 'De aankoopprijs, als percentage van de basisprijs.' },
    VKP1: { label: 'Verkoopprijs 1', uitleg: 'Verkoopprijs 1, als percentage van de basisprijs.' },
    VKP2: { label: 'Verkoopprijs 2', uitleg: 'Verkoopprijs 2, als percentage van de basisprijs.' },
    VKP3: { label: 'Verkoopprijs 3', uitleg: 'Verkoopprijs 3, als percentage van de basisprijs.' },
    VKP4: { label: 'Verkoopprijs 4', uitleg: 'Verkoopprijs 4, als percentage van de basisprijs.' },
    VKP5: { label: 'Verkoopprijs 5', uitleg: 'Verkoopprijs 5, als percentage van de basisprijs.' },
    VKP_GROSS: { label: 'Brutoverkoopprijs', uitleg: 'De brutoverkoopprijs, als percentage van de basisprijs.' },
  },
  // NT-11c: de velden waarnaar een extra veld schrijft (veldcatalogus) en de velden met een kritiek-overrule.
  fieldKey: {
    BASE_PRICE: { label: 'Basisprijs', uitleg: 'De basisprijs van het artikel.' },
    AKP_PCT: { label: 'Aankoopprijs (%)', uitleg: 'De aankoopprijs in procent van de basisprijs.' },
    VKP1_PCT: { label: 'Verkoopprijs 1 (%)', uitleg: 'Verkoopprijs 1 in procent van de basisprijs.' },
    VKP2_PCT: { label: 'Verkoopprijs 2 (%)', uitleg: 'Verkoopprijs 2 in procent van de basisprijs.' },
    VKP3_PCT: { label: 'Verkoopprijs 3 (%)', uitleg: 'Verkoopprijs 3 in procent van de basisprijs.' },
    VKP4_PCT: { label: 'Verkoopprijs 4 (%)', uitleg: 'Verkoopprijs 4 in procent van de basisprijs.' },
    VKP5_PCT: { label: 'Verkoopprijs 5 (%)', uitleg: 'Verkoopprijs 5 in procent van de basisprijs.' },
    VKP_GROSS_PCT: { label: 'Brutoverkoopprijs (%)', uitleg: 'De brutoverkoopprijs in procent van de basisprijs.' },
    EAN: { label: 'EAN-barcode', uitleg: 'De streepjescode van het artikel.' },
    PIM_ID: { label: 'PIM-nummer', uitleg: 'Het nummer van het artikel in het productinformatiesysteem.' },
    CAB_ID: { label: 'CAB-nummer', uitleg: 'Het CAB-nummer van het artikel.' },
    E_MARK_ARTICLE_REFERENCE: {
      label: 'E-merk met artikelnummer',
      uitleg: 'De combinatie van het E-merk en het artikelnummer.',
    },
    E_SUPPLIER: { label: 'Externe leveranciersidentiteit', uitleg: 'De identiteit van de leverancier bij een ander systeem.' },
    SUPPLIER_BARCODE: { label: 'Leveranciersbarcode', uitleg: 'De streepjescode die de leverancier zelf gebruikt.' },
    DESCRIPTION: { label: 'Omschrijving', uitleg: 'De omschrijving van het artikel.' },
    BRAND: { label: 'Merk', uitleg: 'Het merk van het artikel; een gebruiker van Prodis beheert dit, een levering overschrijft het niet.' },
    UNIT: { label: 'Eenheid', uitleg: 'De eenheid van het artikel; een gebruiker van Prodis beheert dit, een levering overschrijft het niet.' },
    SUPPLIER: { label: 'Leverancier', uitleg: 'De leverancier van het artikel.' },
    SUPPLIER_GROUP: { label: 'Groep van de leverancier', uitleg: 'De artikelgroep van de leverancier.' },
    SUPPLIER_REFERENCE: { label: 'Artikelnummer van de leverancier', uitleg: 'Het artikelnummer van de leverancier.' },
    DISCOUNT_CODE: { label: 'Kortingscode', uitleg: 'De kortingscode die mee bepaalt om welk artikel het gaat.' },
    CURRENCY: { label: 'Valuta', uitleg: 'De munt van de prijs.' },
  },
  // NT-11c: het bestandsformaat van de beschrijving van het bestand.
  structureFormat: {
    CSV: { label: 'CSV-bestand', uitleg: 'Een tekstbestand met kolommen, gescheiden door een vast teken.' },
  },
  // NT-11c: of een levering het volledige aanbod of alleen wijzigingen bevat.
  deliverySetKind: {
    FULL_SNAPSHOT: { label: 'Volledig aanbod', uitleg: 'Elke levering bevat het volledige aanbod van de leverancier.' },
    DELTA: { label: 'Enkel wijzigingen', uitleg: 'Elke levering bevat alleen wat gewijzigd is.' },
    UNDECLARED: {
      label: 'Niet opgegeven',
      uitleg: 'Er is niet vastgelegd of een levering het volledige aanbod of alleen wijzigingen bevat.',
    },
  },
  // NT-11c: de manier van prijscontrole.
  priceControlModel: {
    DEVIATION: {
      label: 'Afwijking van eerdere prijzen',
      uitleg: 'Een prijs wordt vergeleken met de vorige prijs en met gemiddelden over twee periodes.',
    },
    BOXPLOT: {
      label: 'Uitschieters (nog niet beschikbaar)',
      uitleg: 'Opsporing van uitschieters met een boxplot; wordt in deze versie nog niet ondersteund.',
    },
  },
  // NT-11c: waar de waarde van een extra veld vandaan komt.
  mappingValueKind: {
    SOURCE_FIELD: { label: 'Kolom uit het bestand', uitleg: 'De waarde komt uit een kolom van het bestand.' },
    FIXED_VALUE: { label: 'Vaste waarde', uitleg: 'De waarde ligt vast in de importdefinitie.' },
    BOOKMARK: {
      label: 'Waarde die per koppeling ingevuld wordt',
      uitleg: 'De waarde wordt per koppeling ingevuld; wordt in deze versie nog niet ondersteund.',
    },
    DERIVED: {
      label: 'Afgeleide waarde',
      uitleg: 'De waarde wordt afgeleid uit andere velden; wordt in deze versie nog niet ondersteund.',
    },
  },
  // NT-11c: de vergelijking van een recordfilter.
  filterOperator: {
    EQUALS: { label: 'is gelijk aan', uitleg: 'De waarde in de kolom is precies gelijk aan de vergelijkingswaarde.' },
    NOT_EQUALS: { label: 'is niet gelijk aan', uitleg: 'De waarde in de kolom is niet gelijk aan de vergelijkingswaarde.' },
    BEGINS_WITH: { label: 'begint met', uitleg: 'De waarde in de kolom begint met de vergelijkingswaarde.' },
    ENDS_WITH: { label: 'eindigt op', uitleg: 'De waarde in de kolom eindigt op de vergelijkingswaarde.' },
    CONTAINS: { label: 'bevat', uitleg: 'De waarde in de kolom bevat de vergelijkingswaarde.' },
    NOT_CONTAINS: { label: 'bevat niet', uitleg: 'De waarde in de kolom bevat de vergelijkingswaarde niet.' },
  },
  // NT-11c: wat een recordfilter met een regel doet als hij past.
  filterOutcome: {
    INCLUDE: {
      label: 'Meenemen',
      uitleg: 'Regels die passen, horen bij de levering; regels die bij geen enkel meeneem-filter passen, vallen erbuiten.',
    },
    EXCLUDE: {
      label: 'Overslaan',
      uitleg: 'Regels die passen, worden overgeslagen en niet verder gecontroleerd; dat is geen fout.',
    },
    REJECT: { label: 'Verwerpen', uitleg: 'Regels die passen, horen niet te bestaan; ze worden verworpen en geteld.' },
  },
  // NT-11c: het soort waarde van een invulpunt van een sjabloon (het woord "bookmark" of "invulpunt" staat hier bewust niet in).
  bookmarkDataType: {
    TEXT: { label: 'Tekst', uitleg: 'Vrije tekst; voorloopnullen en hoofdletters blijven behouden.' },
    INTEGER: { label: 'Geheel getal', uitleg: 'Een getal zonder cijfers na de komma.' },
    DECIMAL: { label: 'Decimaal getal', uitleg: 'Een getal met cijfers na de komma.' },
    DATE: { label: 'Datum', uitleg: 'Een datum zonder tijdstip.' },
    BOOLEAN: { label: 'Ja of nee', uitleg: 'Een keuze tussen ja en nee.' },
    ENUM: { label: 'Keuze uit een lijst', uitleg: 'Een keuze uit een vaste lijst met toegelaten waarden.' },
    SUPPLIER_REFERENCE: { label: 'Verwijzing naar een leverancier', uitleg: 'De code van een bestaande leverancier of aankoopvereniging.' },
    LIBRARY_REFERENCE: { label: 'Verwijzing naar een bibliotheek', uitleg: 'De code van een Prodis-bibliotheek.' },
    POLICY_PROFILE_REFERENCE: { label: 'Verwijzing naar een prijsprofiel', uitleg: 'Een bestaand prijs- of beleidsprofiel van een versie.' },
  },
  // NT-11c: voor wie een ingevulde waarde van een sjabloon geldt.
  bookmarkScope: {
    DEFINITION: {
      label: 'Voor de hele importdefinitie',
      uitleg: 'De waarde wordt bij het materialiseren vastgelegd en geldt voor elke koppeling van de importdefinitie.',
    },
    LINK: { label: 'Per koppeling', uitleg: 'De waarde wordt voor elke koppeling apart ingevuld.' },
  },
  // NT-11c: waar een ingevulde waarde van een sjabloon terechtkomt.
  bookmarkPlace: {
    FIELD_MAPPING_FIXED_VALUE: {
      label: 'Vaste waarde van een extra veld',
      uitleg: 'De waarde wordt de vaste waarde van een extra veld.',
    },
    RECORD_FILTER_COMPARE_VALUE: {
      label: 'Vergelijkingswaarde van een filter',
      uitleg: 'De waarde wordt de vergelijkingswaarde van een filter op de regels.',
    },
    REVISION_IDENTITY_FIELD: {
      label: 'Kolom voor de herkenning',
      uitleg: 'De waarde bepaalt een van de kolommen waarmee een artikel herkend wordt.',
    },
    REVISION_PRICE_POLICY: {
      label: 'Prijsbeleid van de versie',
      uitleg: 'De waarde bepaalt een instelling van het prijsbeleid, zoals de grens voor prijsafwijking.',
    },
    LINK_LIBRARY_CODE: { label: 'Doelbibliotheek van de koppeling', uitleg: 'De waarde wordt de doelbibliotheek van de koppeling.' },
    LINK_SEARCH_SUPPLIER: {
      label: 'Leverancierscode in de bibliotheek',
      uitleg: 'De waarde wordt de code waaronder Prodis de leverancier zoekt; ze wordt nooit uit de leverancier afgeleid.',
    },
    LINK_SUPPLIER_ORGANISATION: {
      label: 'Leverancier van de koppeling',
      uitleg: 'De waarde wordt de leverancier van de koppeling.',
    },
  },
  // NT-11c: waarvoor een beschrijving van een bestand dient (eigen beschrijving of sjabloon).
  definitionUsage: {
    OWN_DEFINITION: {
      label: 'Eigen importdefinitie',
      uitleg: 'Een importdefinitie van één organisatie, met eigen versies en koppelingen.',
    },
    REUSABLE_TEMPLATE: {
      label: 'Herbruikbare importdefinitie',
      uitleg: 'Een model waaruit importdefinities van bestanden gemaakt worden; het krijgt nooit zelf een koppeling of levering.',
    },
  },
  // NT-11c: de twee manieren om uit een sjabloon te materialiseren.
  materialisationMode: {
    NEW_DEFINITION: {
      label: 'Nieuw — een nieuwe importdefinitie met conceptversie en koppeling maken',
      uitleg: 'Er komt een nieuwe importdefinitie met een conceptversie en een koppeling.',
    },
    REUSE_DEFINITION: {
      label: 'Hergebruik — alleen een koppeling toevoegen aan een bestaande, deelbare importdefinitie',
      uitleg: 'Er komt alleen een koppeling bij een bestaande importdefinitie die gedeeld mag worden; de importdefinitie blijft ongewijzigd.',
    },
  },
  // NT-11c: de meldingen bij een geslaagde materialisatie.
  materialisationWarning: {
    OPTIONAL_BOOKMARK_NOT_FILLED: {
      label: 'Een optionele waarde is niet ingevuld',
      uitleg: 'Een optionele waarde is leeg gebleven; de standaardwaarde van de importdefinitie geldt, als die er is.',
    },
    LINK_SEARCH_SUPPLIER_NOT_DERIVED: {
      label: 'Leverancierscode in de bibliotheek is niet ingevuld',
      uitleg: 'Die code wordt nooit automatisch uit de leverancier afgeleid; vul ze zo nodig zelf in.',
    },
  },
  // S9: Overname van de legacy-configuratie: status van een ontvangst.
  legacyReceiptStatus: {
    RECEIVED: {
      label: 'Ontvangen',
      uitleg: 'Deze configuratie is binnengekomen en wacht op uw beslissing; er is nog niets overgenomen.',
    },
    APPLIED: {
      label: 'Overgenomen',
      uitleg: 'De configuratie is overgenomen in CatalogImport.',
    },
    SUPERSEDED: {
      label: 'Vervangen',
      uitleg: 'Er is een nieuwere ontvangst van dezelfde interface; deze kan niet meer overgenomen worden.',
    },
    DISCARDED: {
      label: 'Terzijde gelegd',
      uitleg: 'Een beheerder heeft deze ontvangst terzijde gelegd; ze wordt niet overgenomen.',
    },
  },
  // S9: Overname van de legacy-configuratie: uitkomst van de controle vooraf.
  legacyOutcome: {
    APPLICABLE: {
      label: 'Klaar om over te nemen',
      uitleg: 'Er blokkeert niets; u kunt de configuratie overnemen.',
    },
    BLOCKED: {
      label: 'Geblokkeerd',
      uitleg: 'Minstens één punt blokkeert; los dat eerst op voor u kunt overnemen.',
    },
  },
  // S9: Overname van de legacy-configuratie: soort punt in het rapport.
  legacyReportStatus: {
    BLOCKING: {
      label: 'Blokkeert',
      uitleg: 'Dit punt moet eerst opgelost worden voor de configuratie overgenomen kan worden.',
    },
    WARNING: {
      label: 'Let op',
      uitleg: 'Dit wordt overgenomen of weggelaten met een verschil in gedrag dat u moet beoordelen.',
    },
    NOT_MIGRATED: {
      label: 'Wordt niet overgenomen',
      uitleg: 'Dit onderdeel wordt bewust niet overgenomen; er verandert niets stilzwijgend.',
    },
    MIGRATED: {
      label: 'Wordt overgenomen',
      uitleg: 'Dit onderdeel wordt overgenomen naar CatalogImport.',
    },
  },
  // S9: Overname van de legacy-configuratie: wat er met het wachtwoord gebeurde.
  legacySecretOutcome: {
    STORED: {
      label: 'Wachtwoord veilig bewaard',
      uitleg:
        'Het wachtwoord is versleuteld bewaard en alleen via een verwijzing te gebruiken; het gebruik voor FTP en API volgt later.',
    },
    DISCARDED_ALREADY_PRESENT: {
      label: 'Wachtwoord niet overgenomen: bestaat al',
      uitleg: 'Voor deze interface bestaat al een wachtwoord; het nieuwe is weggegooid, vervangen doet u zelf.',
    },
    DISCARDED_PROTOCOL_UNSUPPORTED: {
      label: 'Wachtwoord niet overgenomen: protocol',
      uitleg:
        'Een wachtwoord wordt bewaard voor SFTP, FTP en API; bij een lokaal bestand of een onduidelijk of tegenstrijdig protocol is het weggegooid.',
    },
    DISCARDED_NO_HOST: {
      label: 'Wachtwoord niet overgenomen: geen server',
      uitleg: 'Er was geen geldige server om het wachtwoord aan te koppelen; het is weggegooid.',
    },
    null: {
      label: 'Geen wachtwoord ontvangen',
      uitleg: 'Bij deze ontvangst zat geen wachtwoord.',
    },
  },
  // S9: Overname van de legacy-configuratie: wat er met een onderdeel gebeurt.
  legacyAction: {
    CREATE: {
      label: 'Wordt aangemaakt',
      uitleg: 'Dit bestaat nog niet en wordt nieuw aangemaakt.',
    },
    REUSE: {
      label: 'Wordt hergebruikt',
      uitleg: 'Dit bestaat al en wordt ongewijzigd hergebruikt.',
    },
    SUCCESSOR_DRAFT: {
      label: 'Nieuwe conceptversie',
      uitleg: 'Er komt een nieuwe conceptversie naast de bestaande; de actieve versie blijft ongemoeid.',
    },
    UNCHANGED: {
      label: 'Blijft ongewijzigd',
      uitleg: 'De inhoud is gelijk aan wat al overgenomen was; er verandert niets.',
    },
    NONE: {
      label: 'Geen',
      uitleg: 'Hier is geen actie voor nodig of mogelijk.',
    },
    REPOINT: {
      label: 'Wordt verlegd',
      uitleg: 'De bestaande koppeling blijft bestaan maar wijst voortaan naar de eigen importdefinitie.',
    },
  },
  // S9: Overname van de legacy-configuratie: controle tegen Prodis.
  legacyProdisReference: {
    VERIFIED: {
      label: 'Gecontroleerd in Prodis',
      uitleg: 'Leverancier en bibliotheek zijn nagekeken in de Prodis-database.',
    },
    UNAVAILABLE: {
      label: 'Prodis niet bereikbaar',
      uitleg: 'De Prodis-database kon niet gelezen worden; leverancier en bibliotheek zijn niet nagekeken.',
    },
    NOT_CONFIGURED: {
      label: 'Niet gecontroleerd',
      uitleg: 'De controle tegen Prodis staat niet aan; leverancier en bibliotheek zijn niet nagekeken.',
    },
  },
  // S9: Overname van de legacy-configuratie: staat van de importsoort in een ontvangst.
  legacyDefinitionStatus: {
    PRESENT: {
      label: 'Importsoort aanwezig',
      uitleg: 'De importsoort van de interface is gevonden en meegestuurd.',
    },
    NOT_REFERENCED: {
      label: 'Geen importsoort gekoppeld',
      uitleg: 'De interface verwijst naar geen enkele importsoort.',
    },
    MISSING: {
      label: 'Importsoort niet gevonden',
      uitleg: 'De interface verwijst naar een importsoort die niet bestaat.',
    },
    TABLE_UNAVAILABLE: {
      label: 'Tabel met importsoorten niet beschikbaar',
      uitleg: 'De tabel met importsoorten kon bij het exporteren niet gelezen worden.',
    },
  },
  // S9: Bewerkingen op een extra veld (stappen van een keten).
  transformKind: {
    NONE: {
      label: 'Geen bewerking',
      uitleg: 'De waarde wordt zoals ze is overgenomen.',
    },
    FIXED_VALUE: {
      label: 'Vaste waarde',
      uitleg: 'Het veld krijgt altijd dezelfde waarde.',
    },
    PREFIX: {
      label: 'Voorvoegsel',
      uitleg: 'Er wordt tekst vóór de waarde gezet.',
    },
    SUFFIX: {
      label: 'Achtervoegsel',
      uitleg: 'Er wordt tekst achter de waarde gezet.',
    },
    AFFIX: {
      label: 'Voor- en achtervoegsel',
      uitleg: 'Er wordt tekst voor en achter de waarde gezet; een lege waarde blijft leeg.',
    },
    CONCAT: {
      label: 'Samenvoegen',
      uitleg: 'Meerdere kolommen worden aan elkaar gezet.',
    },
    SPLIT: {
      label: 'Splitsen',
      uitleg: 'Een deel van de waarde wordt genomen na een scheidingsteken.',
    },
    SUBSTRING: {
      label: 'Deel van de tekst',
      uitleg: 'Enkel een stuk van de waarde, vanaf een positie en met een lengte, wordt genomen.',
    },
    UPPER: {
      label: 'Hoofdletters',
      uitleg: 'De waarde wordt in hoofdletters gezet.',
    },
    MAP: {
      label: 'Waarde omzetten',
      uitleg: 'Een waarde wordt omgezet met een omzettingstabel.',
    },
    MAP_CONTAINS: {
      label: 'Omzetten op een deel van de waarde',
      uitleg: 'De eerste regel van de tabel waarvan de sleutel in de waarde voorkomt, bepaalt de nieuwe waarde.',
    },
    ADD: {
      label: 'Optellen',
      uitleg: 'Er wordt een vast getal bij de waarde opgeteld.',
    },
    SUBTRACT: {
      label: 'Aftrekken',
      uitleg: 'Er wordt een vast getal van de waarde afgetrokken.',
    },
    MULTIPLY: {
      label: 'Vermenigvuldigen',
      uitleg: 'De waarde wordt met een vast getal vermenigvuldigd.',
    },
    DIVIDE: {
      label: 'Delen',
      uitleg: 'De waarde wordt door een vast getal gedeeld.',
    },
    PERCENTAGE: {
      label: 'Percentage',
      uitleg: 'Er wordt een percentage van de waarde genomen of bijgeteld.',
    },
    CHAIN: {
      label: 'Keten van bewerkingen',
      uitleg: 'Meerdere bewerkingen worden na elkaar uitgevoerd, in een vaste volgorde.',
    },
  },
  // S9: Overname van de legacy-configuratie: punten in het rapport.
  legacyReportCode: {
    DEFINITION_NOT_PRESENT: {
      label: 'Geen importsoort om over te nemen',
      uitleg: 'De interface verwijst niet naar een bestaande importsoort, of de gegevens ervan ontbreken; zonder die importdefinitie valt er niets over te nemen.',
    },
    INTERFACE_SETTING_DUPLICATED: {
      label: 'Instelling van de interface komt meerdere keren voor',
      uitleg: 'Legacy leest er maar één van en niet te bepalen welke; maak de interface eerst eenduidig.',
    },
    SUPPLIER_NOT_DECLARED: {
      label: 'Leverancier ontbreekt in de interface',
      uitleg: 'Zonder leverancier kan niet bepaald worden voor wie de koppeling is.',
    },
    SUPPLIER_CODE_TOO_LONG: {
      label: 'Leveranciersnummer is te lang',
      uitleg: 'Het leveranciersnummer van de interface past niet in CatalogImport.',
    },
    LIBRARY_NOT_DECLARED: {
      label: 'Bibliotheek ontbreekt in de interface',
      uitleg: 'Zonder bibliotheeknummer kan de bibliotheek van de koppeling niet bepaald worden.',
    },
    STRUCTURE_FORMAT_UNSUPPORTED: {
      label: 'Bestandsformaat niet ondersteund',
      uitleg: 'Enkel CSV met komma, puntkomma of tab wordt overgenomen; dit formaat of scheidingsteken niet.',
    },
    CHARSET_NOT_DECLARED: {
      label: 'Tekenset niet opgegeven',
      uitleg: 'De interface noemt geen tekenset; de tekenset die je zelf koos is gebruikt.',
    },
    CHARSET_DEFAULTED_UTF8: {
      label: 'Tekenset niet opgegeven, UTF-8 gebruikt',
      uitleg: 'De interface noemt geen tekenset; legacy gebruikt dan altijd UTF-8, dus dat is hier overgenomen.',
    },
    CHARSET_UNSUPPORTED: {
      label: 'Tekenset niet ondersteund',
      uitleg: 'De opgegeven tekenset bestaat niet of wordt niet ondersteund.',
    },
    SKIP_HEADER_LINES_INVALID: {
      label: 'Aantal over te slaan regels is ongeldig',
      uitleg: 'Het aantal kopregels van de interface is geen bruikbaar getal.',
    },
    IDENTITY_PROFILE_UNSUPPORTED: {
      label: 'Herkenning van artikelen niet ondersteund',
      uitleg: 'De sleutelinstellingen van de interface kunnen niet overgenomen worden (komt enkel nog voor in oudere rapporten).',
    },
    IDENTITY_GROUP_INHERITANCE_NOT_MIGRATED: {
      label: 'Groep van het bestaande artikel wordt niet overgenomen',
      uitleg: 'De interface neemt geen leveranciersgroep in de herkenning op (Key_LevGrp = N). Regels zonder groep worden hier verworpen; legacy nam de groep van het bestaande bibliotheekartikel over.',
    },
    ARTICLE_TARGET_UNSUPPORTED: {
      label: 'Doel van de interface niet ondersteund',
      uitleg: 'De interface schrijft in legacy naar de artikelen en niet naar de bibliotheek; dat wordt niet overgenomen.',
    },
    IDENTITY_FLAG_WITHOUT_EFFECT: {
      label: 'Sleutelinstelling heeft geen effect',
      uitleg: 'Deze sleutelinstelling (leverancier, leveranciersreferentie of barcode) heeft in legacy geen effect en is niet overgenomen.',
    },
    CURRENCY_RATE_NOT_SUPPORTED: {
      label: 'Wisselkoers niet ondersteund',
      uitleg: 'Een wisselkoers zou prijzen omrekenen; een financiële omrekening wordt niet overgenomen.',
    },
    LEGACY_FIELD_DUPLICATED: {
      label: 'Veld komt meerdere keren voor',
      uitleg: 'Hetzelfde veldnummer staat meer dan eens in de importsoort; kies er eerst één.',
    },
    IDENTITY_FIELD_MISSING: {
      label: 'Kolom voor de herkenning ontbreekt',
      uitleg: 'Een van de velden waarmee een artikel herkend wordt, heeft geen kolom of vaste waarde.',
    },
    IDENTITY_FIXED_VALUE_TOO_LONG: {
      label: 'Vaste waarde is te lang',
      uitleg: 'De vaste leverancier of groep is langer dan wat CatalogImport bewaart.',
    },
    IDENTITY_FIXED_VALUE_UNSUPPORTED: {
      label: 'Vaste waarde niet ondersteund',
      uitleg: 'Enkel voor leverancier en groep kan een vaste waarde de herkenning bepalen.',
    },
    IDENTITY_TRANSFORM_UNSUPPORTED: {
      label: 'Bewerking op de herkenning niet ondersteund',
      uitleg: 'Een kolom voor de herkenning wordt in legacy bewerkt; dat wordt niet overgenomen.',
    },
    SUPPLIER_CHECK_SUPPLIER_IS_FOREIGN_KEY: {
      label: 'Leverancier staat zelf als code voor een andere leverancier',
      uitleg: 'Het leveranciersnummer van de interface staat in de leverancierskaart als code voor een andere leverancier; een controle kan niet beide aanvaarden. Pas de kaart aan.',
    },
    SUPPLIER_CHECK_MULTIPLE_KEYS: {
      label: 'Meerdere codes voor dezelfde leverancier',
      uitleg: 'De leverancierskaart bevat meer dan een code voor deze leverancier; de controle ondersteunt een code.',
    },
    BASE_PRICE_FIELD_MISSING: {
      label: 'Kolom basisprijs ontbreekt',
      uitleg: 'De basisprijs heeft in de importsoort geen kolom.',
    },
    BASE_PRICE_TRANSFORM_UNSUPPORTED: {
      label: 'Bewerking op de basisprijs niet ondersteund',
      uitleg: 'De basisprijs wordt in legacy bewerkt of vast ingesteld; een prijs wordt nooit stilzwijgend aangepast.',
    },
    LEGACY_EXCLUDE_EXCLUDES_ALL: {
      label: 'Uitsluiting sluit alles uit',
      uitleg: 'Een uitsluitingsrij blijkt elke regel uit te sluiten; dat zou de hele levering wegfilteren.',
    },
    DEFINITION_CODE_CONFLICT_MANUAL: {
      label: 'Code van de importdefinitie is al in gebruik',
      uitleg: 'Een andere, handmatig gemaakte importdefinitie heeft al deze code; kies een andere code of los het conflict zelf op.',
    },
    TEMPLATE_CODE_CONFLICT_MANUAL: {
      label: 'Code van de importdefinitie is al in gebruik',
      uitleg: 'Een andere definitie gebruikt al de code die de importdefinitie zou krijgen; kies een andere code.',
    },
    DEFINITION_CODE_OVERRIDE_INVALID: {
      label: 'Gekozen code van de importdefinitie is ongeldig',
      uitleg: 'De code die u koos is te lang of bevat tekens die niet mogen.',
    },
    SOURCE_ORGANISATION_TYPE_CONFLICT: {
      label: 'Leverancier bestaat al als ander soort organisatie',
      uitleg: 'Er is al een organisatie met dit nummer die geen leverancier is; die wordt nooit omgezet.',
    },
    LINK_CODE_CONFLICT_MANUAL: {
      label: 'Code van de koppeling is al in gebruik',
      uitleg: 'Een handmatig gemaakte koppeling heeft al de code die hier zou ontstaan.',
    },
    LINK_SCOPE_CONFLICT_MANUAL: {
      label: 'Er bestaat al een koppeling voor deze leverancier en bibliotheek',
      uitleg: 'Dezelfde combinatie van leverancier en bibliotheek is al in gebruik; er wordt nooit stil samengevoegd.',
    },
    LINK_DIFFERS_NOT_CHANGED: {
      label: 'Bestaande koppeling wijkt af',
      uitleg: 'De bestaande koppeling is anders dan wat de legacy-interface zegt; ze wordt nooit aangepast.',
    },
    SHARED_DEFINITION_DIVERGED: {
      label: 'Gedeelde importdefinitie wijkt af',
      uitleg: 'Deze interface wijkt af van de importdefinitie die ze met andere interfaces deelt; u kunt ze loskoppelen tot een eigen importdefinitie.',
    },
    DETACH_FROM_SHARED: {
      label: 'Wordt losgekoppeld van de gedeelde importdefinitie',
      uitleg: 'Deze interface krijgt een eigen importdefinitie; de koppeling wordt verlegd en de actieve versie van de gedeelde importdefinitie wordt meteen als eigen versie actief. De nieuwe inhoud wordt een conceptversie.',
    },
    DETACH_NOT_NEEDED: {
      label: 'Loskoppelen is niet nodig',
      uitleg: 'Deze interface wijkt niet af van een gedeelde importdefinitie, dus er valt niets los te koppelen; de keuze wordt genegeerd.',
    },
    DETACHED_DEFINITION: {
      label: 'Eigen importdefinitie na loskoppelen',
      uitleg: 'Deze interface heeft een eigen importdefinitie gekregen en deelt ze met geen andere interface.',
    },
    DETACH_OPEN_BATCH: {
      label: 'Loskoppelen kan niet: er staat nog een bestand open',
      uitleg: 'Voor deze koppeling staat nog een bestand dat niet afgerond is. Rond het eerst af en probeer opnieuw.',
    },
    DETACH_RUN_IN_PROGRESS: {
      label: 'Loskoppelen kan niet: er loopt een inname',
      uitleg: 'Voor deze koppeling loopt op dit moment een inname. Wacht tot ze klaar is en probeer opnieuw.',
    },
    DETACHED_DEFINITION_CODE_CONFLICT: {
      label: 'Code van de eigen importdefinitie is al in gebruik',
      uitleg: 'Een andere importdefinitie gebruikt al de code die de eigen importdefinitie zou krijgen; kies een andere code.',
    },
    DETACHED_DEFINITION_CODE_INVALID: {
      label: 'Gekozen code van de eigen importdefinitie is ongeldig',
      uitleg: 'De code die u koos is te lang of bevat tekens die niet mogen.',
    },
    MANUAL_CHANGES_SINCE_MIGRATION: {
      label: 'Importdefinitie is na de vorige overname handmatig gewijzigd',
      uitleg: 'De laatste versie wijkt af van wat de vorige overname schreef; die wijzigingen worden nooit overschreven.',
    },
    OPEN_DRAFT_EXISTS: {
      label: 'Er is al een conceptversie',
      uitleg: 'Rond de bestaande conceptversie eerst af of verwijder ze voor er een nieuwe komt.',
    },
    REVISION_NOT_CLONEABLE: {
      label: 'Laatste versie kan niet gekopieerd worden',
      uitleg: 'Van de laatste versie kan geen nieuwe conceptversie gemaakt worden.',
    },
    PRODIS_SUPPLIER_NOT_FOUND: {
      label: 'Leverancier niet gevonden in Prodis',
      uitleg: 'Dit leveranciersnummer bestaat niet in de Prodis-database.',
    },
    PRODIS_SUPPLIER_AMBIGUOUS: {
      label: 'Leverancier komt meerdere keren voor in Prodis',
      uitleg: 'Dit leveranciersnummer is in Prodis niet uniek; er kan niet gekozen worden.',
    },
    PRODIS_SUPPLIER_GROUP_NOT_FOUND: {
      label: 'Groep niet gevonden in Prodis',
      uitleg: 'De vaste groep bestaat niet bij deze leverancier in Prodis.',
    },
    PRODIS_SUPPLIER_NAME_TOO_LONG: {
      label: 'Naam van de leverancier is te lang',
      uitleg: 'De naam in Prodis past niet in CatalogImport en wordt nooit afgekapt.',
    },
    LIBRARY_CODE_INVALID: {
      label: 'Bibliotheekcode is ongeldig',
      uitleg: 'De bibliotheekcode heeft een vorm die niet gebruikt kan worden.',
    },
    LIBRARY_CODE_NOT_RESOLVED: {
      label: 'Bibliotheek niet eenduidig bepaald',
      uitleg: 'De bibliotheek van de legacy-interface is niet eenduidig te vinden; kies er zelf een uit de lijst.',
    },
    LIBRARY_CODE_NOT_IN_PRODIS: {
      label: 'Gekozen bibliotheek hoort niet bij de leverancier',
      uitleg: 'De bibliotheekcode die u koos bestaat niet bij deze leverancier in Prodis.',
    },
    PRODIS_REFERENCE_NOT_VERIFIED: {
      label: 'Niet gecontroleerd in Prodis',
      uitleg: 'Prodis is niet ingesteld of niet bereikbaar; leverancier, groep of bibliotheek zijn niet nagekeken en een nieuwe leverancier krijgt een tijdelijke naam.',
    },
    PRODIS_REFERENCE_AMBIGUOUS: {
      label: 'Code komt meerdere keren voor in Prodis',
      uitleg: 'De code is in Prodis niet uniek. Ze is wel bewaard; welke rij Prodis gebruikt bij het aanmaken van een artikel, beslist Prodis zelf.',
    },
    CREATION_DEFAULT_VAT: {
      label: 'Btw-code als standaard bij aanmaak',
      uitleg: 'De btw-code van de interface is overgenomen als standaard voor artikels die CatalogImport voor deze koppeling aanmaakt.',
    },
    CREATION_DEFAULT_UNIT: {
      label: 'Eenheid als standaard bij aanmaak',
      uitleg: 'De eenheid van de interface is overgenomen als standaard voor artikels die CatalogImport voor deze koppeling aanmaakt.',
    },
    CREATION_DEFAULT_FROM_COLUMN_NOT_MIGRATED: {
      label: 'Standaard uit een kolom niet overgenomen',
      uitleg: 'Legacy haalt de btw-code of eenheid uit een kolom van het bestand; dat wordt niet overgenomen. U kunt de standaard zelf instellen bij de koppeling.',
    },
    CREATION_DEFAULT_NOT_YET_SUPPORTED: {
      label: 'Standaard bij aanmaak nog niet ondersteund',
      uitleg: 'Deze instelling van de interface kan in deze versie nog niet overgenomen worden. U kunt de standaard zelf instellen bij de koppeling.',
    },
    CREATION_DEFAULT_DIFFERS_NOT_CHANGED: {
      label: 'Standaard verschilt van legacy, niet gewijzigd',
      uitleg: 'De koppeling heeft al een andere waarde dan de interface; die blijft staan. Zet bij de importdefinitie "standaarden overschrijven" aan om ze toch te vervangen.',
    },
    CREATION_DEFAULT_OVERWRITTEN: {
      label: 'Standaard overschreven',
      uitleg: 'De waarde van de koppeling is vervangen door die van de interface, omdat de importdefinitie de standaarden laat overschrijven.',
    },
    CREATION_DEFAULT_MANUAL_OVERWRITTEN: {
      label: 'Handmatig ingestelde standaard overschreven',
      uitleg: 'De waarde van de koppeling was handmatig ingesteld en is toch vervangen door die van de interface, omdat de importdefinitie de standaarden laat overschrijven.',
    },
    CREATION_DEFAULT_DUPLICATED: {
      label: 'Standaard bij aanmaak komt meerdere keren voor',
      uitleg: 'De interface vult de btw-code of eenheid meerdere keren in; legacy bepaalt niet welke geldt. De koppeling krijgt daarom geen standaard; stel die zelf in bij de koppeling.',
    },
    CREATION_DEFAULT_VALUE_EMPTY: {
      label: 'Standaard bij aanmaak is leeg',
      uitleg: 'De interface geeft geen vaste waarde op voor de btw-code of eenheid. De koppeling krijgt geen standaard; stel die zelf in bij de koppeling.',
    },
    CREATION_DEFAULT_VALUE_TOO_LONG: {
      label: 'Standaard bij aanmaak is te lang',
      uitleg: 'De vaste waarde in de interface is langer dan 50 tekens. Ze wordt nooit afgekapt; de koppeling krijgt geen standaard. Stel zelf een korte code in bij de koppeling.',
    },
    PRODIS_VAT_CODE: {
      label: 'Btw-code gevonden in Prodis',
      uitleg: 'Deze btw-code bestaat bij de actieve btw-codes in Prodis en is als standaard bij aanmaak overgenomen.',
    },
    PRODIS_UNIT: {
      label: 'Eenheid gevonden in Prodis',
      uitleg: 'Deze eenheid bestaat bij de actieve eenheden in Prodis en is als standaard bij aanmaak overgenomen.',
    },
    PRODIS_VAT_CODE_NOT_FOUND: {
      label: 'Btw-code niet gevonden in Prodis',
      uitleg: 'Deze btw-code bestaat niet bij de actieve btw-codes in Prodis.',
    },
    PRODIS_UNIT_NOT_FOUND: {
      label: 'Eenheid niet gevonden in Prodis',
      uitleg: 'Deze eenheid bestaat niet bij de actieve eenheden in Prodis.',
    },
    SEPARATOR_NOT_DECLARED: {
      label: 'Scheidingsteken niet opgegeven',
      uitleg: 'De interface noemt geen scheidingsteken; zoals legacy wordt de puntkomma gebruikt.',
    },
    DECIMAL_COMMA_BASE_PRICE: {
      label: 'Decimale komma bij de basisprijs',
      uitleg: 'De interface leest getallen met een decimale komma; de basisprijs leest komma en punt beide als decimaalteken en weigert een duizendtalscheiding.',
    },
    INCLUDE_EXCLUDE_CODE_NOT_MIGRATED: {
      label: 'Onbekende code voor insluiten of uitsluiten',
      uitleg: 'Deze code kent legacy niet; regels die ze zou filteren worden hier niet gefilterd.',
    },
    DESCRIPTION_TRANSFORM_NOT_MIGRATED: {
      label: 'Omschrijving wordt in legacy bewerkt',
      uitleg: 'De omschrijving is vast of bewerkt; de kolom omschrijving wordt daarom niet overgenomen.',
    },
    TRANSFORM_VALUE_NOT_EXPRESSIBLE: {
      label: 'Waarde of tekst past niet',
      uitleg: 'Een vaste waarde, voor- of achtervoegsel is te lang of bevat een teken dat niet bewaard kan worden; het veld wordt niet aangemaakt.',
    },
    LEGACY_SUBSTRING_INVALID: {
      label: 'Ongeldige positie of lengte',
      uitleg: 'Een negatieve positie of lengte kan niet overgenomen worden; het veld wordt niet aangemaakt.',
    },
    LEGACY_CALC_VALUE_INVALID: {
      label: 'Ongeldige waarde bij een berekening',
      uitleg: 'Het getal van de berekening ontbreekt of is niet bruikbaar; het veld wordt niet aangemaakt.',
    },
    CALC_ROUNDING_MAY_DIFFER: {
      label: 'Afronding kan verschillen',
      uitleg: 'Legacy rekent met een andere nauwkeurigheid; resultaten kunnen in de laatste decimalen verschillen.',
    },
    MAP_CONTAINS_MISS_NOT_EMPTY: {
      label: 'Onbekende waarde wordt verworpen',
      uitleg: 'Legacy geeft hier een lege waarde als niets in de tabel past; CatalogImport verwerpt zo een regel.',
    },
    LEGACY_CALC_NOT_MIGRATED: {
      label: 'Berekening nog niet ondersteund',
      uitleg: 'Deze berekening van de legacy-definitie wordt nog niet overgenomen; het veld wordt niet aangemaakt.',
    },
    MAP_ENTRY_TOO_LONG: {
      label: 'Omzetting te lang',
      uitleg: 'Een sleutel of waarde in de omzettingstabel is te lang en wordt nooit afgekapt; het veld wordt niet aangemaakt.',
    },
    MAP_KEY_AMBIGUOUS: {
      label: 'Sleutel in de omzettingstabel komt meerdere keren voor',
      uitleg: 'Dezelfde sleutel heeft meerdere waarden; de waarde met het laagste nummer wint.',
    },
    MAP_EMPTY: {
      label: 'Omzettingstabel is leeg',
      uitleg: 'Er zijn geen bruikbare omzettingen; het veld wordt niet aangemaakt.',
    },
    SUPPLIER_CHECK_SOURCE_IS_SUPPLIER: {
      label: 'Kaart bevat geen code voor deze leverancier',
      uitleg: 'De kolom met de leverancier moet het leveranciersnummer zelf bevatten, zoals het misspad in legacy; regels met een andere code worden verworpen.',
    },
    LEGACY_FILTER_PADDING_UNVERIFIED: {
      label: 'Opvulling bij een filter niet nagekeken',
      uitleg: 'Of legacy bij dit filter opvulspaties meetelt, is niet bevestigd; controleer het resultaat.',
    },
    INCLUDE_FILTER_WITHOUT_COLUMN: {
      label: 'Filter zonder kolom',
      uitleg: 'Een insluit- of uitsluitregel heeft geen kolom om op te filteren; er komt geen filter.',
    },
    INCLUDE_EXCLUDE_SUBSTRING_NOT_MIGRATED: {
      label: 'Filter op een deel van de waarde',
      uitleg: 'Een filter op positie en lengte wordt niet overgenomen; er komt geen filter.',
    },
    INCLUDE_FILTER_EMPTY_VALUE_NOT_MIGRATED: {
      label: 'Filter met een lege vergelijkwaarde',
      uitleg: 'Een filter dat op een lege waarde vergelijkt, wordt niet overgenomen.',
    },
    INCLUDE_EXCLUDE_WITH_FIXED_VALUE: {
      label: 'Filter op een veld met vaste waarde',
      uitleg: 'Het veld heeft een vaste waarde, dus filteren heeft geen zin; er komt geen filter.',
    },
    LEGACY_EXCLUDE_NOT_EXPRESSIBLE: {
      label: 'Uitsluiting kan niet uitgedrukt worden',
      uitleg: 'Een uitsluitingsrij vergelijkt een kortingscode die in deze importdefinitie niet beschikbaar is; de rij wordt niet overgenomen.',
    },
    DEFINITION_CODE_OVERRIDDEN: {
      label: 'Gekozen code voor de importdefinitie',
      uitleg: 'U koos een andere code dan die van legacy; de importdefinitie krijgt de gekozen code.',
    },
    DEFINITION_CODE_OVERRIDE_NOT_USED: {
      label: 'Gekozen code niet gebruikt',
      uitleg: 'Er wordt een bestaande gedeelde importdefinitie hergebruikt, dus uw gekozen code is niet nodig.',
    },
    LEGACY_DEFINITION_CODE_CHANGED: {
      label: 'Code van de importsoort is veranderd',
      uitleg: 'De interface verwijst nu naar een andere importsoort dan bij de vorige overname.',
    },
    PRODIS_SUPPLIER_NAME_EMPTY: {
      label: 'Leverancier heeft geen naam in Prodis',
      uitleg: 'De leverancier krijgt een tijdelijke naam met het nummer; pas die later aan.',
    },
    LIBRARY_CODE_NOT_LEGACY_MATCH: {
      label: 'Andere bibliotheek dan legacy',
      uitleg: 'De bibliotheek die u koos is niet die van het legacy-bibliotheeknummer.',
    },
    IDENTITY_CHANGE: {
      label: 'Herkenning van artikelen verandert',
      uitleg: 'De nieuwe conceptversie herkent artikelen anders; met overnemen bevestigt u die wijziging.',
    },
    LEGACY_CONTROL_CODE_NOT_MIGRATED: {
      label: 'Beheercode van legacy',
      uitleg: 'Dit is een instelling voor het beheer van de importsoort in legacy en geen onderdeel van de importdefinitie.',
    },
    CONNECTION_NOT_MIGRATED: {
      label: 'Verbinding wordt niet aangemaakt',
      uitleg: 'Een verbindingsprofiel vraagt een eigen keuze; het rapport toont enkel een voorstel met server, map en login.',
    },
    PUBLICATION_PARAMETER_NOT_MIGRATED: {
      label: 'Parameter voor publicatie niet overgenomen',
      uitleg: 'Een instelling voor de publicatie naar Prodis hoort niet bij de importdefinitie.',
    },
    INTERFACE_SETTING_NOT_MIGRATED: {
      label: 'Instelling van de interface niet overgenomen',
      uitleg: 'Voor deze instelling bestaat in CatalogImport geen plaats.',
    },
    SECRET_LABEL_OMITTED: {
      label: 'Geheime instelling weggelaten',
      uitleg: 'Een wachtwoord of sleutel wordt nooit doorgegeven; vul ze zo nodig zelf in.',
    },
    SCHEDULE_NOT_MIGRATED: {
      label: 'Planning niet overgenomen',
      uitleg: 'De legacy-planning wordt niet overgenomen; de taak wordt handmatig gestart.',
    },
    DISCOUNT_CODE_NOT_IN_KEY: {
      label: 'Kortingscode hoort niet bij de herkenning',
      uitleg: 'De interface gebruikt de kortingscode niet om artikelen te herkennen.',
    },
    LEGACY_FIELD_NOT_MIGRATED: {
      label: 'Veld zonder doel',
      uitleg: 'Voor dit legacy-veld bestaat in CatalogImport geen doelveld.',
    },
    LEGACY_IGNORED_WITH_FIXED_VALUE: {
      label: 'Genegeerd door de vaste waarde',
      uitleg: 'Bij een vaste waarde negeert legacy de rest van de regel; dat doet CatalogImport ook.',
    },
    LEGACY_LINE_WITHOUT_SOURCE: {
      label: 'Regel zonder kolom',
      uitleg: 'De legacy-regel heeft geen kolom en geen vaste waarde; er is niets om over te nemen.',
    },
    MAP_CONTAINS_ROW_NOT_MIGRATED: {
      label: 'Rij van de omzettingstabel overgeslagen',
      uitleg: 'Een rij met een lege sleutel of een lege waarde wordt niet overgenomen.',
    },
    LEGACY_MAP_UNUSED: {
      label: 'Omzettingstabel niet gebruikt',
      uitleg: 'De tabel hoort bij een veld dat niet omgezet wordt, en wordt dus niet overgenomen.',
    },
    SUPPLIER_MAP_ROWS_OTHER_SUPPLIERS: {
      label: 'Kaartrijen van andere leveranciers niet overgenomen',
      uitleg: 'Een bestand bevat een leverancier; de rijen van de leverancierskaart voor andere leveranciers worden niet overgenomen.',
    },
    LEGACY_FILL_NOT_MIGRATED: {
      label: 'Opvulling niet overgenomen',
      uitleg: 'De opvulling van een veld wordt niet overgenomen.',
    },
    LEGACY_XML_PATH_NOT_MIGRATED: {
      label: 'XML-pad niet overgenomen',
      uitleg: 'Enkel CSV-bestanden worden overgenomen, dus een XML-pad niet.',
    },
    LEGACY_EXCLUDES_SKIPPED: {
      label: 'Uitsluitingen worden niet toegepast',
      uitleg: 'De interface zegt de uitsluitingen over te slaan, dus ze worden niet overgenomen.',
    },
    LEGACY_EXCLUDE_NEVER_MATCHES: {
      label: 'Uitsluiting past nooit',
      uitleg: 'Een uitsluitingsrij kan nooit overeenkomen en wordt niet overgenomen.',
    },
    TEMPLATE_NOT_UPDATED: {
      label: 'Importdefinitie blijft ongewijzigd',
      uitleg: 'De importdefinitie van de groep blijft zoals ze was; pas ze zo nodig zelf aan.',
    },
    SOURCE_ORGANISATION_NAME_KEPT: {
      label: 'Naam van de leverancier blijft',
      uitleg: 'De bestaande leverancier behoudt haar naam en wordt nooit hernoemd.',
    },
    DEFINITION: {
      label: 'Importsoort wordt overgenomen',
      uitleg: 'De legacy-importsoort wordt een importdefinitie in CatalogImport.',
    },
    SOURCE_ORGANISATION: {
      label: 'Leverancier wordt overgenomen',
      uitleg: 'De leverancier van de interface wordt als leverancier in CatalogImport gebruikt; bestaat ze al, dan wordt ze hergebruikt.',
    },
    DELIMITER: {
      label: 'Scheidingsteken wordt overgenomen',
      uitleg: 'Het scheidingsteken van de interface wordt dat van het bestand.',
    },
    CHARSET: {
      label: 'Tekenset wordt overgenomen',
      uitleg: 'De tekenset van de interface wordt die van het bestand.',
    },
    HEADER: {
      label: 'Kopregel wordt overgenomen',
      uitleg: 'Het aantal over te slaan regels bepaalt of er een kopregel is en op welke regel ze staat.',
    },
    IDENTITY_PROFILE: {
      label: 'Herkenning van artikelen wordt overgenomen',
      uitleg: 'De sleutelinstellingen van de interface bepalen welke delen een artikel herkennen.',
    },
    IDENTITY_FIELD: {
      label: 'Kolom voor de herkenning wordt overgenomen',
      uitleg: 'De kolom voor leverancier, groep, referentie of kortingscode wordt overgenomen.',
    },
    IDENTITY_FIXED_VALUE: {
      label: 'Vaste waarde voor de herkenning',
      uitleg: 'Leverancier of groep krijgt een vaste waarde in plaats van een kolom, zoals in legacy.',
    },
    IDENTITY_UPPERCASE: {
      label: 'Herkenning in hoofdletters',
      uitleg: 'Leverancier, groep en referentie worden in hoofdletters vergeleken, zoals legacy altijd doet; de kortingscode niet.',
    },
    BASE_PRICE: {
      label: 'Kolom basisprijs wordt overgenomen',
      uitleg: 'De kolom met de basisprijs wordt overgenomen.',
    },
    DESCRIPTION: {
      label: 'Kolom omschrijving wordt overgenomen',
      uitleg: 'De kolom met de omschrijving wordt overgenomen.',
    },
    MAPPING: {
      label: 'Extra veld wordt overgenomen',
      uitleg: 'Een veld van de legacy-definitie wordt een extra veld, eventueel met bewerkingen.',
    },
    MAP_TABLE: {
      label: 'Omzettingstabel wordt overgenomen',
      uitleg: 'De omzettingen van de legacy-definitie worden bij het veld bewaard.',
    },
    MAP_MISS_KEEPS_SOURCE: {
      label: 'Onbekende waarden blijven ongewijzigd',
      uitleg: 'Zoals in legacy gaat een waarde die niet in de omzettingstabel staat ongewijzigd door; elke keer wordt het gemeld.',
    },
    RECORD_FILTER: {
      label: 'Filter op de regels wordt overgenomen',
      uitleg: 'Een insluit- of uitsluitregel van de legacy-definitie wordt een filter op de regels.',
    },
    RECORD_FILTER_GROUP: {
      label: 'Uitsluiting wordt overgenomen',
      uitleg: 'Een uitsluitingsrij wordt een groep filters waarvan alle voorwaarden samen moeten kloppen.',
    },
    SUPPLIER_CHECK_FILTER: {
      label: 'Leverancierscontrole wordt overgenomen',
      uitleg: 'De leverancierskaart wordt een controle op de regels: een regel met een andere code dan die van deze leverancier wordt zichtbaar verworpen.',
    },
    PRODIS_SUPPLIER: {
      label: 'Leverancier gevonden in Prodis',
      uitleg: 'Het leveranciersnummer bestaat precies één keer in Prodis.',
    },
    PRODIS_SUPPLIER_GROUP: {
      label: 'Groep gevonden in Prodis',
      uitleg: 'De vaste groep bestaat bij de leverancier in Prodis.',
    },
    LIBRARY_CODE: {
      label: 'Bibliotheek wordt overgenomen',
      uitleg: 'De bibliotheek van de leverancier waarin de koppeling komt, is bepaald.',
    },
    SHARED_DEFINITION: {
      label: 'Gedeelde importdefinitie wordt hergebruikt',
      uitleg: 'Een andere interface met dezelfde importsoort gebruikt al een gedeelde importdefinitie; enkel koppeling en taak komen erbij.',
    },
    DEFINITION_UNCHANGED: {
      label: 'Importdefinitie blijft ongewijzigd',
      uitleg: 'De legacy-inhoud is gelijk aan wat de vorige overname schreef; er komt geen nieuwe versie.',
    },
    SUCCESSOR_DRAFT: {
      label: 'Nieuwe conceptversie',
      uitleg: 'De legacy-definitie is gewijzigd; er komt een nieuwe conceptversie die u zelf nakijkt en activeert.',
    },
  },
  publicationItemStatus: {
    PENDING: {
      label: 'Wacht',
      uitleg: 'Dit artikel is nog niet naar Prodis gestuurd.',
    },
    SENT: {
      label: 'Wordt verwerkt',
      uitleg: 'Dit artikel is onderweg naar Prodis; het resultaat is nog niet bekend.',
    },
    APPLIED: {
      label: 'Doorgevoerd',
      uitleg: 'De wijziging is in Prodis aangebracht.',
    },
    UNCHANGED: {
      label: 'Ongewijzigd',
      uitleg: 'Prodis bevatte al de gewenste waarden; er is niets aangepast.',
    },
    REJECTED: {
      label: 'Geweigerd',
      uitleg: 'Het artikel is niet aangepast omdat het niet kan of mag; de reden staat erbij.',
    },
    CONFLICT: {
      label: 'Conflict',
      uitleg: 'Een waarde in Prodis is intussen veranderd; er is niets geschreven.',
    },
    RESULT_UNKNOWN: {
      label: 'Uitkomst onbekend',
      uitleg: 'Het is niet zeker of de wijziging in Prodis is aangekomen; opnieuw proberen is veilig.',
    },
    NOT_SENT: {
      label: 'Niet verstuurd',
      uitleg: 'Dit artikel is bewust niet naar Prodis gestuurd.',
    },
  },
  publicationNotice: {
    PRICE_ROUNDING_DIFFERENCE: {
      label: 'Afrondingsverschil',
      uitleg: 'Prodis rekent de verkoopprijs met zijn eigen afronding uit; die wijkt af van het bedrag in de levering.',
    },
    DERIVED_PRICE_DRIFT: {
      label: 'Afgeleide prijzen wijken af',
      uitleg: 'De afgeleide prijzen in Prodis komen niet overeen met wat uit de basisprijs en percentages volgt.',
    },
    VALUE_CHANGED_IN_PRODIS: {
      label: 'Waarde in Prodis veranderd',
      uitleg: 'Een waarde in Prodis is anders dan toen de wijziging werd voorbereid; er is niets overschreven.',
    },
    EMPTY_KEEPS_EXISTING: {
      label: 'Leeg veld: bestaande waarde blijft',
      uitleg: 'De levering had hier niets ingevuld; de bestaande waarde in Prodis blijft staan.',
    },
    ZERO_KEEPS_EXISTING: {
      label: 'Nul: bestaande waarde blijft',
      uitleg: 'De levering had hier 0 ingevuld; zoals in legacy blijft de bestaande waarde in Prodis staan.',
    },
    DESCRIPTION_OVERWRITTEN: {
      label: 'Omschrijving overschreven',
      uitleg: 'De omschrijving in Prodis wordt door die uit de levering vervangen.',
    },
    DESCRIPTION_TOO_LONG: {
      label: 'Omschrijving te lang',
      uitleg: 'De omschrijving is langer dan Prodis toelaat.',
    },
    PRECISION_LOSS: {
      label: 'Precisieverlies',
      uitleg: 'Een bedrag heeft meer decimalen dan Prodis kan bewaren.',
    },
    EXCEEDS_LEGACY_PRECISION: {
      label: 'Meer decimalen dan legacy',
      uitleg: 'Een bedrag heeft meer decimalen dan legacy toelaat.',
    },
    EXPECTED_NOT_AVAILABLE: {
      label: 'Verwachte waarde ontbreekt',
      uitleg: 'Er is geen verwachte waarde om de stand in Prodis mee te vergelijken.',
    },
    SNAPSHOT_INCOMPLETE: {
      label: 'Bevroren gegevens onvolledig',
      uitleg: 'De bevroren gegevens van dit artikel zijn onvolledig.',
    },
  },
  publicationItemReason: {
    VALUE_CHANGED_IN_PRODIS: {
      label: 'Waarde in Prodis veranderd',
      uitleg: 'Een waarde in Prodis is anders dan toen de wijziging werd voorbereid; er is niets overschreven.',
    },
    ARTICLE_NOT_FOUND: {
      label: 'Artikel niet gevonden',
      uitleg: 'Het artikel bestaat niet in Prodis; nieuwe artikelen aanmaken kan nog niet.',
    },
    ARTICLE_AMBIGUOUS: {
      label: 'Artikel niet eenduidig',
      uitleg: 'Er zijn meerdere artikelen in Prodis die bij deze herkenning passen.',
    },
    ARTICLE_ALREADY_EXISTS: {
      label: 'Artikel bestaat al',
      uitleg: 'Het artikel bestaat al in Prodis.',
    },
    SUPPLIER_AMBIGUOUS: {
      label: 'Leverancier niet eenduidig',
      uitleg: 'Het leveranciersnummer komt niet precies één keer voor in Prodis.',
    },
    CREATE_NOT_SUPPORTED_IN_V1: {
      label: 'Aanmaken nog niet ondersteund',
      uitleg: 'Het aanmaken van nieuwe artikelen in Prodis kan in deze versie nog niet.',
    },
    PRODIS_ROW_SHAPE_UNSUPPORTED: {
      label: 'Onverwachte vorm in Prodis',
      uitleg: 'De gegevens van het artikel in Prodis hebben een vorm die niet ondersteund wordt.',
    },
    IDENTITY_INCOMPLETE: {
      label: 'Herkenning onvolledig',
      uitleg: 'Leverancier, groep of referentie ontbreekt.',
    },
    ACTION_UNSUPPORTED: {
      label: 'Soort wijziging niet ondersteund',
      uitleg: 'Dit soort wijziging kan niet naar Prodis geschreven worden.',
    },
    CURRENCY_NOT_SUPPORTED_BY_PRODIS: {
      label: 'Valuta niet ondersteund',
      uitleg: 'Prodis kan deze valuta niet bewaren.',
    },
  },
  legacyMaster: {
    NONE: {
      label: 'Geen meester',
      uitleg: 'Er is geen systeem dat de wijzigingen later terugzet.',
    },
    PRODIS: {
      label: 'Prodis',
      uitleg: 'Prodis is de meester; de volgende datasync zet de wijzigingen terug naar de legacy-waarden.',
    },
    PROFAB: {
      label: 'ProFab',
      uitleg: 'ProFab is de meester; de volgende datasync zet de wijzigingen terug naar de legacy-waarden.',
    },
    UNKNOWN: {
      label: 'Onbekend',
      uitleg: 'Het is niet vast te stellen wie de meester is; ga ervan uit dat een datasync de wijzigingen terugzet.',
    },
  },
  parallelRunOutcome: {
    WOULD_UPDATE: {
      label: 'Zou aangepast worden',
      uitleg: 'Bij echt publiceren zou dit artikel in Prodis gewijzigd worden.',
    },
    UNCHANGED: {
      label: 'Zou ongewijzigd blijven',
      uitleg: 'Prodis bevat al de gewenste waarden.',
    },
    CONFLICT: {
      label: 'Conflict',
      uitleg: 'Een waarde in Prodis wijkt af van de verwachting; er zou niets geschreven worden.',
    },
    REJECTED: {
      label: 'Zou geweigerd worden',
      uitleg: 'Het artikel kan niet naar Prodis geschreven worden; de reden staat bij het artikel.',
    },
    NOT_SENT: {
      label: 'Zou niet verstuurd worden',
      uitleg: 'Dit artikel zou bewust niet naar Prodis gaan.',
    },
  },
  parallelRunStatus: {
    PARALLEL_RUN_COMPLETED: {
      label: 'Vergelijking afgerond',
      uitleg: 'Alle artikelen zijn met Prodis vergeleken; er is niets geschreven.',
    },
    PARALLEL_RUN_NOT_CONFIGURED: {
      label: 'Leesverbinding niet ingesteld',
      uitleg: 'De alleen-lezen verbinding met Prodis is niet ingesteld; er is niets vergeleken.',
    },
    PARALLEL_RUN_UNAVAILABLE: {
      label: 'Prodis niet bereikbaar',
      uitleg: 'Prodis kon niet gelezen worden; er is niets vergeleken.',
    },
    PARALLEL_RUN_NO_SNAPSHOT: {
      label: 'Geen bevroren gegevens',
      uitleg: 'De publicatie heeft geen bevroren gegevens om te vergelijken.',
    },
    PARALLEL_RUN_FAILED: {
      label: 'Vergelijking mislukt',
      uitleg: 'De vergelijking is technisch mislukt; er is niets vergeleken.',
    },
    PARALLEL_RUN_SUMMARY_UNREADABLE: {
      label: 'Samenvatting onleesbaar',
      uitleg: 'De samenvatting van de vergelijking kon niet gelezen worden.',
    },
  },
};
