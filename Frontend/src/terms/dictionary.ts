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
    PLANNED: { label: 'Gepland', uitleg: 'Wordt bij het bevriezen van de bundel mee goedgekeurd.' },
    AWAITING_APPROVAL: { label: 'Wacht op goedkeuring', uitleg: 'Vraagt een uitdrukkelijke beslissing.' },
    READY_FOR_PUBLICATION: { label: 'Goedgekeurd', uitleg: 'Goedgekeurd en klaar om gepubliceerd te worden.' },
    REJECTED: { label: 'Afgekeurd', uitleg: 'Afgekeurd; er is altijd een reden vastgelegd.' },
    BLOCKED: {
      label: 'Tegengehouden',
      uitleg: 'Kritiek herkenningsprobleem; geen beslissing mogelijk.',
    },
    SKIPPED: {
      label: 'Overgeslagen',
      uitleg: 'Niet gepubliceerd omdat de batch als nulmeting aanvaard is.',
    },
    RECORDED: { label: 'Vastgelegd', uitleg: 'Legt vast dat de controle afgerond is; geen echte wijziging.' },
    EXPIRED: { label: 'Vervallen', uitleg: 'Door het annuleren van de bundel.' },
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
      uitleg: 'Batches toevoegen en wijzigingen beoordelen kan nog.',
    },
    FROZEN: { label: 'Bevroren', uitleg: 'Afgesloten en klaar voor publicatie; nog te annuleren.' },
    CANCELLED: { label: 'Geannuleerd', uitleg: 'Niet doorgegaan; de batches zijn weer vrij.' },
    PUBLISHING: { label: 'Wordt gepubliceerd', uitleg: 'De publicatie van de bundel loopt.' },
    PARTIALLY_PUBLISHED: { label: 'Deels gepubliceerd', uitleg: 'Een deel van de bundel is gepubliceerd.' },
    PUBLISHED: { label: 'Gepubliceerd', uitleg: 'De hele bundel is gepubliceerd.' },
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
    AWAITING_REVIEW: { label: 'Wacht op beoordeling', uitleg: 'Een mens moet dit geval nog bekijken.' },
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
      label: 'Beschrijving van het bestand',
      uitleg: 'Legt vast hoe het bestand van deze organisatie eruitziet en hoe het gelezen wordt.',
    },
    revision: {
      label: 'Versie',
      uitleg: 'Een vastgelegde stand van de beschrijving; een nieuwe versie wordt pas gebruikt na activeren.',
    },
    link: {
      label: 'Koppeling',
      uitleg: 'Verbindt de beschrijving van het bestand met één leverancier en één Prodis-bibliotheek.',
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
      uitleg: 'De beschrijving van het bestand heeft een actieve versie waarmee leveringen gelezen worden.',
    },
    NO_ACTIVE_REVISION: {
      label: 'Er is nog geen versie in gebruik',
      uitleg: 'Zonder actieve versie van de beschrijving kan geen enkele levering gelezen worden.',
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
      uitleg: 'Alle waarden die de beschrijving per koppeling vraagt, zijn ingevuld.',
    },
    CONFIG_REQUIRED_BOOKMARK_MISSING: {
      label: 'Verplichte invulpunten zijn niet ingevuld',
      uitleg: 'De beschrijving vraagt een of meer waarden die nog leeg zijn; zolang ze ontbreken, wordt een levering geweigerd.',
    },
    READY_DRAFT_ACTIVATABLE: {
      label: 'De conceptversie kan geactiveerd worden',
      uitleg: 'De controle die bij het activeren gebeurt, vindt geen fouten in deze conceptversie.',
    },
    INFO_NO_DRAFT_REVISION: {
      label: 'Er is geen conceptversie om te activeren',
      uitleg: 'Er is geen versie in gebruik en ook geen concept; maak eerst een nieuwe versie van de beschrijving.',
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
      label: 'Fout in de beschrijving van het bestand',
      uitleg: 'De beschrijving van het bestand is onvolledig of ongeldig.',
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
      uitleg: 'De beschrijving zegt niet welk teken de kolommen scheidt.',
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
      uitleg: 'De beschrijving zegt niet in welke kolom de prijs staat.',
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
      uitleg: 'Een kolom die de beschrijving verwacht, staat niet in de kopregel van het bestand.',
    },
    HEADER_DUPLICATE_FIELD: {
      label: 'Kolomnaam dubbel',
      uitleg: 'Dezelfde kolomnaam komt meer dan eens voor in de kopregel.',
    },
    HEADER_COLUMN_COUNT_MISMATCH: {
      label: 'Ander aantal kolommen',
      uitleg: 'De kopregel heeft een ander aantal kolommen dan de beschrijving verwacht.',
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
      uitleg: 'Het bestand bevat een kolom die de beschrijving niet kent; ze wordt niet gebruikt.',
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
      uitleg: 'Een prijs van nul is voor deze beschrijving niet toegelaten.',
    },
    PRICE_NEGATIVE_NOT_ALLOWED: {
      label: 'Negatieve prijs niet toegelaten',
      uitleg: 'Een negatieve prijs is voor deze beschrijving niet toegelaten.',
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
      label: 'Beschrijving van het bestand',
      uitleg: 'Het probleem zit in de ingestelde beschrijving, nog voor het bestand gelezen werd.',
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
      uitleg: 'Regels die door de filters van de beschrijving overgeslagen worden.',
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
      uitleg: 'Deze regel valt buiten de filters van de beschrijving en wordt overgeslagen.',
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
    NOT_APPLICABLE: { label: 'Geen grens ingesteld', uitleg: 'Voor deze beschrijving geldt hier geen grens.' },
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
      uitleg: 'Regels die door de filters van de beschrijving van het bestand overgeslagen worden.',
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
    APPROVE: { label: 'Goedkeuring', uitleg: 'Een mutatie of een groep mutaties is goedgekeurd.' },
    REJECT: { label: 'Afkeuring', uitleg: 'Een mutatie of een groep mutaties is afgekeurd.' },
    AUTO_APPROVE_PLANNED: {
      label: 'Automatische goedkeuring bij het bevriezen',
      uitleg: 'Bij het bevriezen zijn de mutaties die nog gepland stonden in één keer goedgekeurd op naam van wie bevroor.',
    },
    FREEZE: { label: 'Bevriezing', uitleg: 'De bundel is bevroren: afgesloten en klaar voor publicatie.' },
    CANCEL: { label: 'Annulering', uitleg: 'De bundel is geannuleerd.' },
  },
  // NT-11b: waar een beslissing in het beslissingsregister over ging.
  decisionScope: {
    MUTATION: { label: 'Eén mutatie', uitleg: 'De beslissing ging over één mutatie.' },
    GROUP: { label: 'Groep mutaties', uitleg: 'De beslissing ging over alle mutaties die aan een filter voldeden.' },
    BUNDLE: { label: 'Hele bundel', uitleg: 'De beslissing ging over de bundel als geheel.' },
  },
  // NT-11b: de onderdelen van de filter die bij een groepsbeslissing bewaard wordt. De code is de naam van het filterveld.
  selectionFilterField: {
    batchId: { label: 'Batch', uitleg: 'Enkel mutaties van deze batch.' },
    status: { label: 'Status', uitleg: 'Enkel mutaties met deze status.' },
    statusReason: { label: 'Reden van de status', uitleg: 'Enkel mutaties met deze reden.' },
    actionType: { label: 'Soort', uitleg: 'Enkel mutaties van deze soort.' },
    identityHash: { label: 'Wijzigingsgroep', uitleg: 'Enkel de wijzigingen van één artikel in één batch.' },
  },
  // NT-11b: waarom een bundel (nog) niet bevroren kan worden. Een code van de voorcontrole, dezelfde die het bevriezen zelf zou geven.
  freezeCheck: {
    BUNDLE_NOT_ASSEMBLING: {
      label: 'De bundel is niet meer in opbouw',
      uitleg: 'Enkel een bundel die nog in opbouw is, kan bevroren worden.',
    },
    BUNDLE_EMPTY: {
      label: 'De bundel heeft geen batches',
      uitleg: 'Een bundel zonder actieve batch kan niet bevroren worden.',
    },
    BUNDLE_HAS_UNDECIDED_MUTATIONS: {
      label: 'Er wachten nog mutaties op een beslissing',
      uitleg: 'Bevriezen mag een openstaande vraag niet stilzwijgend beantwoorden; beslis eerst over deze mutaties.',
    },
    SOURCE_STATE_CHANGED_SINCE_SCREENING: {
      label: 'De bekende gegevens zijn veranderd sinds de controle',
      uitleg: 'Wat we over de artikelen wisten, is na de controle van de levering gewijzigd; controleer de levering opnieuw.',
    },
    BUNDLE_OFFER_CONFLICT: {
      label: 'Dezelfde aanbieding zit in meer dan één batch',
      uitleg: 'Twee batches van deze bundel willen hetzelfde artikel publiceren; keur één kant af.',
    },
    OFFER_ALREADY_IN_ANOTHER_BUNDLE: {
      label: 'Een aanbieding zit ook in een andere bundel',
      uitleg: 'Hetzelfde artikel kan in een andere open of bevroren bundel gepubliceerd worden; keur één kant af, of publiceer of annuleer eerst de andere bundel.',
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
    batchCount: { label: 'Batches', uitleg: 'Het aantal geleverde bestanden (batches) dat deel uitmaakt van de bundel.' },
    contentMutationCount: {
      label: 'Mutaties',
      uitleg: 'Het aantal voorgestelde wijzigingen in de bundel, zonder de afgeronde controles.',
    },
    readyCount: { label: 'Goedgekeurd', uitleg: 'Mutaties die goedgekeurd zijn en klaarstaan om gepubliceerd te worden.' },
    rejectedCount: { label: 'Afgekeurd', uitleg: 'Mutaties die afgekeurd zijn.' },
    blockedCount: {
      label: 'Tegengehouden',
      uitleg: 'Mutaties met een kritiek herkenningsprobleem; daar is geen beslissing over mogelijk.',
    },
    identityIncidentCount: {
      label: 'Herkenningsproblemen',
      uitleg: 'Artikelen waarvan een kritieke verwijzing, zoals een streepjescode, gewijzigd, verdwenen of hergebruikt is.',
    },
    expiredCount: { label: 'Vervallen', uitleg: 'Mutaties die vervallen zijn door het annuleren van de bundel.' },
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
      uitleg: 'Mutaties met status gepland; bij het bevriezen worden ze in één keer goedgekeurd op uw naam.',
    },
    awaitingApprovalCount: {
      label: 'Wacht op beslissing',
      uitleg: 'Mutaties die een uitdrukkelijke beslissing vragen; zolang die er niet is, kan de bundel niet bevroren worden.',
    },
    staleMutationCount: {
      label: 'Gegevens veranderd sinds de controle',
      uitleg: 'Mutaties waarvan de bekende artikelgegevens na de controle van de levering veranderd zijn.',
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
      uitleg: 'Gaat over de beschrijving van het bestand en de rechten daarop.',
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
      label: 'De beschrijving van het bestand',
      uitleg: 'De beschrijving van het bestand zelf moet aangepast worden.',
    },
    LIBRARY: {
      label: 'De bibliotheek',
      uitleg: 'Raakt de bibliotheek, bijvoorbeeld een verwijzing die aan een ander artikel hangt.',
    },
  },
  // NT-11c: het soort gebeurtenis in de geschiedenis van een behandelgeval.
  issueEventKind: {
    CREATED: { label: 'Aangemaakt', uitleg: 'Het behandelgeval is voor het eerst aangemaakt.' },
    STATUS_CHANGE: { label: 'Status gewijzigd', uitleg: 'De status van het behandelgeval is gewijzigd.' },
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
    FIXED_VALUE: { label: 'Vaste waarde', uitleg: 'De waarde ligt vast in de beschrijving van het bestand.' },
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
      label: 'Voor de hele beschrijving',
      uitleg: 'De waarde wordt bij het materialiseren vastgelegd en geldt voor elke koppeling van de beschrijving.',
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
      label: 'Eigen beschrijving',
      uitleg: 'Een beschrijving van het bestand van één organisatie, met eigen versies en koppelingen.',
    },
    REUSABLE_TEMPLATE: {
      label: 'Herbruikbaar sjabloon',
      uitleg: 'Een model waaruit beschrijvingen van bestanden gemaakt worden; het krijgt nooit zelf een koppeling of levering.',
    },
  },
  // NT-11c: de twee manieren om uit een sjabloon te materialiseren.
  materialisationMode: {
    NEW_DEFINITION: {
      label: 'Nieuw — een nieuwe beschrijving met conceptversie en koppeling maken',
      uitleg: 'Er komt een nieuwe beschrijving van het bestand met een conceptversie en een koppeling.',
    },
    REUSE_DEFINITION: {
      label: 'Hergebruik — alleen een koppeling toevoegen aan een bestaande, deelbare beschrijving',
      uitleg: 'Er komt alleen een koppeling bij een bestaande beschrijving die gedeeld mag worden; de beschrijving blijft ongewijzigd.',
    },
  },
  // NT-11c: de meldingen bij een geslaagde materialisatie.
  materialisationWarning: {
    OPTIONAL_BOOKMARK_NOT_FILLED: {
      label: 'Een optionele waarde is niet ingevuld',
      uitleg: 'Een optionele waarde is leeg gebleven; de standaardwaarde van het sjabloon geldt, als die er is.',
    },
    LINK_SEARCH_SUPPLIER_NOT_DERIVED: {
      label: 'Leverancierscode in de bibliotheek is niet ingevuld',
      uitleg: 'Die code wordt nooit automatisch uit de leverancier afgeleid; vul ze zo nodig zelf in.',
    },
  },
};
