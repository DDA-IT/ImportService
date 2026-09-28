/**
 * De enige plek waar een backend-foutcode Nederlandse tekst wordt. Geen enkele andere module
 * schrijft zelf een foutzin — zie `docs/design/frontend-scherm3-bundel-design.md` §4 en §2 regel 3.
 */

import type { ApiError } from '../api/http';

export type CodeEntry = {
  title: string;
  explanation: string;
  whatNow?: string;
  /**
   * De servertekst (`error`) van deze code draagt concrete gegevens die de Nederlandse uitleg niet kan
   * bevatten — de conflictvoorbeelden (tot tien) of het aantal betrokken mutaties. Die tekst wordt dan
   * volledig en letterlijk mee getoond (§10.5: "de UI toont die melding volledig en leesbaar"), nooit
   * samengevat of ingekort.
   */
  showBackendDetail?: true;
};

/**
 * Alle stabiele backendcodes relevant voor dit scherm, uit
 * `docs/design/frontend-scherm3-bundel-design.md` §1.2.
 */
export const CODE_MESSAGES: Record<string, CodeEntry> = {
  BUNDLE_NOT_FOUND: {
    title: 'Bundel niet gevonden',
    explanation: 'Deze publicatiebundel bestaat niet (meer), of het id in de link klopt niet.',
    whatNow: 'Ga terug naar de bundellijst en zoek de bundel opnieuw op.',
  },
  BATCH_NOT_FOUND: {
    title: 'Batch niet gevonden',
    explanation: 'Deze batch (levering) bestaat niet (meer).',
  },
  DEFINITION_NOT_FOUND: {
    title: 'Importdefinitie niet gevonden',
    explanation: 'Deze importdefinitie bestaat niet (meer), of het id in de link klopt niet.',
    whatNow: 'Ga terug naar het inrichtingsoverzicht en zoek de definitie opnieuw op.',
  },
  DELIVERY_NOT_FOUND: {
    title: 'Levering niet gevonden',
    explanation: 'Deze levering bestaat niet (meer).',
  },
  BATCH_NOT_IN_BUNDLE: {
    title: 'Batch zit niet in deze bundel',
    explanation: 'De opgevraagde batch is geen (actief) lid van deze bundel.',
  },
  MUTATION_NOT_IN_BUNDLE: {
    title: 'Mutatie zit niet in deze bundel',
    explanation: 'Deze mutatie hoort niet bij een batch die lid is van deze bundel.',
  },
  BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE: {
    title: 'Referentie is al in gebruik met een andere scope',
    explanation:
      'Er bestaat al een bundel met deze bundelreferentie, maar met een andere doelmodus, ' +
      'doelmoment of publicatiebeleid dan wat u nu aanmaakt.',
    whatNow: 'Gebruik een andere referentie, of maak de bundel opnieuw aan met dezelfde scope als de bestaande.',
  },
  BUNDLE_NOT_ASSEMBLING: {
    title: 'Bundel is niet meer in opbouw',
    explanation:
      'Deze actie mag alleen zolang de bundel de status ASSEMBLING heeft. Deze bundel is al bevroren, ' +
      'geannuleerd of verder in het proces.',
  },
  BUNDLE_NOT_FROZEN: {
    title: 'Bundel is nog niet bevroren',
    explanation:
      'Deze actie is alleen mogelijk voor een bundel met status FROZEN (bv. de PSIMPORT-preview of ' +
      'een publicatierun).',
    whatNow: 'Bevries de bundel eerst, of kies een bevroren bundel.',
  },
  BATCH_ALREADY_IN_BUNDLE: {
    title: 'Batch zit al in een bundel',
    explanation: 'Deze batch is al lid van deze of een andere bundel en kan niet nogmaals toegevoegd worden.',
  },
  BATCH_IN_PUBLICATION_BUNDLE: {
    title: 'Batch zit in een publicatiebundel',
    explanation:
      'Aanvaarden als nulmeting (accept-baseline) en opname in een bundel sluiten elkaar per batch uit. ' +
      'Deze batch is al lid van een niet-geannuleerde bundel en kan daarom niet als nulmeting aanvaard worden.',
    whatNow: 'Werk de batch af via de bundel, of annuleer de bundel; daarna is accept-baseline weer mogelijk.',
  },
  BATCH_NOT_ACCEPTABLE: {
    title: 'Batch kan niet aanvaard worden',
    explanation:
      'Aanvaarden als nulmeting kan alleen vanuit de status SCREENED, en één keer per batch. Deze batch is ' +
      'al aanvaard of nog niet (of niet meer) gescreend.',
    whatNow: 'Laad de batch opnieuw en controleer de status.',
  },
  BATCH_NOT_RESUMABLE: {
    title: 'Batch kan niet hervat worden',
    explanation: 'Hervatten (continue) kan alleen vanuit de status MUTATING. Deze batch staat in een andere status.',
    whatNow: 'Laad de batch opnieuw en controleer de status.',
  },
  BATCH_NOT_BUNDLEABLE: {
    title: 'Batch is niet bundelbaar',
    explanation: 'Deze batch voldoet niet aan de voorwaarden om in een publicatiebundel opgenomen te worden.',
  },
  BATCH_VALIDATION_NOT_ESTABLISHED: {
    title: 'Batch is nog niet gevalideerd',
    explanation: 'De screening van deze batch is nog niet afgerond; het validatieresultaat staat nog niet vast.',
  },
  BATCH_VALIDATION_BLOCKING: {
    title: 'Batch heeft blokkerende validatiefouten',
    explanation: 'De screening van deze batch bevat blokkerende problemen; de batch kan zo niet toegevoegd worden.',
  },
  BATCH_HAS_DECIDED_MUTATIONS: {
    title: 'Batch draagt al beslissingen',
    explanation:
      'Deze batch draagt al beslissingen; verwijderen zou een ondertekende beslissing uit het dossier ' +
      'laten verdwijnen.',
  },
  MUTATION_NOT_DECIDABLE: {
    title: 'Mutatie is niet beslisbaar',
    explanation: 'Deze mutatie staat in een status waarin goedkeuren of afkeuren niet mogelijk is.',
  },
  MUTATION_BLOCKED_BY_IDENTITY_INCIDENT: {
    title: 'Geblokkeerd door een identiteitsincident',
    explanation:
      'Deze mutatie is geblokkeerd door een kritiek identiteitsincident; deze fase van de applicatie ' +
      'kent hier geen beslispad.',
  },
  IDENTITY_DECISION_NOT_IN_SCOPE: {
    title: 'Identiteitsbeslissing hoort hier niet',
    explanation:
      'Een identiteitsbeslissing schrijft in de referentiestaat; dat komt in een latere fase van de ' +
      'applicatie.',
  },
  DECISION_FILTER_REQUIRED: {
    title: 'Filter ontbreekt',
    explanation:
      'Een groepsbeslissing vereist minstens één filterveld (batch, status, soort, statusreden of ' +
      'wijzigingsgroep).',
  },
  DECISION_FILTER_UNKNOWN_FIELD: {
    title: 'Onbekend filterveld',
    explanation:
      'De groepsbeslissing bevat een veld dat de server niet kent; ze is geweigerd zodat ze niet ' +
      'meer mutaties raakt dan je ziet.',
    showBackendDetail: true,
  },
  BUNDLE_EMPTY: {
    title: 'Bundel is leeg',
    explanation: 'Deze bundel heeft geen (actieve) leden en kan zo niet bevroren worden.',
    whatNow: 'Voeg eerst minstens één batch toe.',
  },
  BUNDLE_HAS_UNDECIDED_MUTATIONS: {
    title: 'Er wachten nog mutaties op een beslissing',
    explanation: 'Bevriezen mag die vraag niet stilzwijgend beantwoorden.',
    whatNow: 'Beoordeel eerst de mutaties met status AWAITING_APPROVAL.',
    showBackendDetail: true,
  },
  SOURCE_STATE_CHANGED_SINCE_SCREENING: {
    title: 'Bronstaat is veranderd sinds de screening',
    explanation: 'De bronstaat is verschoven sinds de screening van een batch in deze bundel.',
    whatNow: 'Screen de betrokken levering opnieuw.',
    showBackendDetail: true,
  },
  BUNDLE_OFFER_CONFLICT: {
    title: 'Conflict tussen aanbiedingen',
    explanation:
      'Twee publiceerbare mutaties in deze bundel raken dezelfde aanbieding. De backend geeft hieronder ' +
      'tot tien voorbeelden.',
    whatNow: 'Keur er één af, of publiceer/annuleer eerst de andere bundel.',
    showBackendDetail: true,
  },
  OFFER_ALREADY_IN_ANOTHER_BUNDLE: {
    title: 'Aanbieding zit al in een andere bundel',
    // Spiegel van PublicationBundleDao.findCrossBundleOfferConflicts: elke andere bundel die niet
    // CANCELLED is telt mee — dus ook een bundel die nog in opbouw is, niet enkel een bevroren.
    explanation:
      'Een aanbieding in deze bundel staat ook publiceerbaar in een andere bundel die niet geannuleerd ' +
      'is (in opbouw of bevroren). De backend geeft hieronder tot tien voorbeelden.',
    whatNow: 'Keur één kant af, of publiceer/annuleer eerst de andere bundel.',
    showBackendDetail: true,
  },
  BUNDLE_CONTENT_CHANGED_DURING_FREEZE: {
    title: 'Inhoud is veranderd tijdens het bevriezen',
    explanation: 'De inhoud van de bundel is tussen het laden en het bevestigen van deze actie gewijzigd.',
    whatNow: 'Laad de bundel opnieuw en probeer opnieuw te bevriezen.',
    showBackendDetail: true,
  },
  BUNDLE_NOT_CANCELLABLE: {
    title: 'Bundel kan niet geannuleerd worden',
    explanation: 'Deze bundel staat in een status waarin annuleren niet meer mogelijk is.',
  },
  TASK_NOT_FOUND: {
    title: 'Taak niet gevonden',
    explanation: 'De gekozen taak bestaat niet (meer).',
    whatNow: 'Laad de takenlijst opnieuw en kies een bestaande taak.',
  },
  TASK_NOT_MANUAL: {
    title: 'Taak is niet manueel',
    explanation: 'Alleen een taak met trigger MANUAL neemt een handmatig geüploade levering aan.',
    whatNow: 'Kies een manuele taak.',
  },
  NO_ACTIVE_REVISION: {
    title: 'Geen actieve revisie',
    explanation: 'De importdefinitie van deze taak heeft geen actieve revisie; een levering kan dus niet gescreend worden.',
    whatNow: 'Activeer eerst een revisie (materialisatie van het sjabloon).',
  },
  TASK_RUN_IN_PROGRESS: {
    title: 'Er loopt al een uitvoering voor deze taak',
    explanation: 'Deze taak laat geen gelijktijdige uitvoeringen toe en er loopt er al een.',
    whatNow: 'Wacht tot de lopende uitvoering klaar is. Was dat uw eigen upload die wegviel? Herhaal dan met dezelfde referentie.',
  },
  DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT: {
    title: 'Referentie is al gebruikt voor een ander bestand',
    explanation:
      'Bij deze taak bestaat al een levering met deze referentie, maar met een andere bestandsinhoud. ' +
      'Er is niets opgeslagen.',
    whatNow: 'Geef dit bestand een nieuwe referentie.',
  },
  BUNDLE_CONTENT_CHANGED_DURING_CANCEL: {
    title: 'Inhoud is veranderd tijdens het annuleren',
    explanation: 'De inhoud van de bundel is tussen het laden en het bevestigen van deze actie gewijzigd.',
    whatNow: 'Laad de bundel opnieuw en probeer opnieuw te annuleren.',
    showBackendDetail: true,
  },
  AUTHENTICATION_REQUIRED: {
    title: 'Aanmelden vereist',
    explanation: 'Uw sessie is verlopen of u bent niet aangemeld. Er is niets opgeslagen.',
    whatNow: 'Meld u opnieuw aan en probeer het daarna nogmaals.',
  },
  ACCESS_DENIED: {
    title: 'Toegang geweigerd',
    explanation: 'U hebt geen toegang tot deze actie.',
  },
  ACTOR_FIELD_MISMATCH: {
    title: 'Andere gebruiker aangemeld',
    explanation: 'U bent intussen als iemand anders aangemeld; er is niets opgeslagen. Herlaad de pagina.',
    whatNow: 'Herlaad de pagina en probeer opnieuw.',
  },
  SYSTEM_ACTOR_FORBIDDEN: {
    title: 'Deze gebruikersnaam mag niet tekenen',
    explanation: 'De aangemelde gebruikersnaam "system" mag lezen, maar geen acties uitvoeren die ondertekend worden.',
  },
  ACTOR_IDENTITY_INVALID: {
    title: 'Aangemelde identiteit onbruikbaar',
    explanation:
      'Uw login levert geen bruikbare gebruikersnaam of identificator (ontbrekend of te lang). Er is niets opgeslagen.',
    whatNow: 'Neem contact op met de beheerder van de aanmelding.',
  },
  PERMISSION_DENIED: {
    title: 'Recht ontbreekt',
    explanation:
      'U heeft niet het recht om deze actie uit te voeren; er is niets opgeslagen. Uw rechten kunnen ' +
      'ingetrokken zijn: ze worden opnieuw opgehaald.',
    whatNow: 'Vraag de beheerder om het ontbrekende recht, dat de server hieronder noemt.',
    showBackendDetail: true,
  },
  PERMISSION_SOURCE_UNAVAILABLE: {
    title: 'Rechten tijdelijk niet beschikbaar',
    explanation:
      'De bron van uw rechten is niet bereikbaar. Dat is iets anders dan geen rechten hebben: er is niets ' +
      'opgeslagen en er is niets over uw rechten besloten.',
    whatNow: 'Probeer het zo meteen opnieuw; blijft het duren, verwittig dan de beheerder.',
  },
  CSRF_TOKEN_INVALID: {
    title: 'Beveiligingstoken ongeldig',
    explanation: 'Het beveiligingstoken van uw sessie ontbreekt of is verlopen. Er is niets opgeslagen.',
    whatNow: 'Herlaad de pagina en probeer opnieuw.',
  },
  PUBLICATION_MODE_REQUIRED: {
    title: 'Doelmodus ontbreekt',
    explanation: 'Een publicatierun vraagt altijd een expliciete doelmodus; die werd niet meegegeven.',
  },
  PUBLICATION_MODE_UNKNOWN: {
    title: 'Onbekende doelmodus',
    explanation: 'De meegegeven doelmodus is geen geldige waarde.',
  },
  PUBLICATION_MODE_NOT_ENABLED: {
    title: 'Doelmodus staat dicht',
    explanation:
      'Deze fase ondersteunt enkel SIMULATION en schrijft niets naar ProDisWebbase, PSIMPORT of ' +
      'Pervasive. TRIAL_LIBRARY en PRODUCTION blijven dicht tot het verwerkingscontract bewezen is.',
  },
  PUBLICATION_RUN_IN_PROGRESS: {
    title: 'Er loopt al een publicatierun',
    explanation: 'Deze bundel heeft al een niet-afgeronde publicatierun; wacht tot die klaar is.',
    showBackendDetail: true,
  },
  BUNDLE_CONTENT_CHANGED_SINCE_FREEZE: {
    title: 'Inhoud is veranderd sinds het bevriezen',
    explanation:
      'De herberekende bundelhash wijkt af van de hash die bij het bevriezen bewaard is; er is niets ' +
      'gepubliceerd.',
    whatNow: 'Onderzoek de wijziging voor u deze bundel opnieuw probeert te publiceren.',
    showBackendDetail: true,
  },
  PUBLICATION_RUN_NOT_FOUND: {
    title: 'Publicatierun niet gevonden',
    explanation: 'Deze publicatierun bestaat niet (meer).',
  },
  PUBLICATION_RUN_ARTIFACT_NOT_AVAILABLE: {
    title: 'Geen artefact beschikbaar',
    explanation: 'Deze run draagt geen artefact; enkel een SIMULATED-run heeft er een.',
  },
  PUBLICATION_RUN_NOT_STUCK: {
    title: 'Run kan niet afgebroken worden',
    explanation: 'Afbreken kan alleen zolang een run nog op PREPARING staat; deze run is dat niet (meer).',
    whatNow: 'Laad de lijst opnieuw.',
  },
  LOCAL_SOURCE_NOT_CONFIGURED: {
    title: 'Ontvangstweg niet ingesteld',
    explanation: 'Deze ontvangstweg is niet ingesteld op deze omgeving.',
    whatNow: 'Werk gewoon via "Bestand van mijn computer".',
  },
  LOCAL_SOURCE_FILE_NAME_INVALID: {
    title: 'Bestandsnaam ongeldig',
    explanation: 'De opgegeven bestandsnaam is ongeldig. Dit zou via deze lijst niet mogen gebeuren.',
  },
  LOCAL_SOURCE_FILE_NOT_FOUND: {
    title: 'Bestand niet gevonden',
    explanation: 'Dit bestand staat niet meer in de servermap.',
    whatNow: 'Laad de lijst opnieuw.',
  },
  LOCAL_SOURCE_FILE_NOT_REGULAR: {
    title: 'Geen leesbaar bestand',
    explanation: 'Dit is geen gewoon bestand (bijvoorbeeld een symbolische koppeling of een map).',
  },
  LOCAL_SOURCE_FILE_CHANGED: {
    title: 'Bestand is gewijzigd',
    explanation: 'Het bestand is gewijzigd terwijl het gelezen werd. Er is niets opgeslagen.',
    whatNow: 'Probeer opnieuw.',
  },
  LOCAL_SOURCE_DIRECTORY_UNAVAILABLE: {
    title: 'Servermap niet leesbaar',
    explanation: 'De servermap is momenteel niet leesbaar.',
    whatNow: 'Probeer later opnieuw.',
    showBackendDetail: true,
  },
  // S1-F2 — sjabloon-/materialisatiewizard (scherm 1b, alleen-lezen deel), zie `docs/decisions.md`
  // 2026-09-27 "scherm 1a/1b".
  TEMPLATE_NOT_FOUND: {
    title: 'Sjabloon niet gevonden',
    explanation: 'Dit sjabloon bestaat niet (meer), of het id in de link klopt niet.',
    whatNow: 'Ga terug naar de sjablonenlijst en kies het sjabloon opnieuw.',
  },
  TEMPLATE_REVISION_NOT_FOUND: {
    title: 'Sjabloonrevisie niet gevonden',
    explanation: 'Deze revisie van het sjabloon bestaat niet (meer).',
    whatNow: 'Laad de revisielijst opnieuw en kies een bestaande revisie.',
  },
  DEFINITION_NOT_A_TEMPLATE: {
    title: 'Geen sjabloon',
    explanation: 'Deze importdefinitie is geen herbruikbaar sjabloon (REUSABLE_TEMPLATE).',
  },
  NO_ACTIVE_TEMPLATE_REVISION: {
    title: 'Geen actieve sjabloonrevisie',
    explanation: 'Dit sjabloon heeft geen actieve revisie; er kan geen sjabloonversie gekozen worden zonder er zelf één op te geven.',
    whatNow: 'Kies expliciet een sjabloonrevisie.',
  },
  BOOKMARK_NOT_FOUND: {
    title: 'Bookmark niet gevonden',
    explanation: 'Deze bookmark bestaat niet (meer) op deze sjabloonrevisie.',
  },
  // S1-F3 — het schrijfdeel van scherm 1b: materialiseren + bookmarkwaarde wijzigen. Verschillende
  // codes hieronder zouden via een familie-fallback (`CONFIG_*`, `_NOT_FOUND`, `_IN_USE`) al een
  // algemene zin krijgen; ze staan hier expliciet omdat die zin niet zegt wát de gebruiker moet doen.
  MATERIALISATION_MODE_REQUIRED: {
    title: 'Kies nieuw of hergebruik',
    explanation:
      'Er is niet opgegeven of dit sjabloon een nieuwe definitie wordt of een bestaande definitie ' +
      'hergebruikt. Die keuze heeft bewust geen standaardwaarde: ze bepaalt of twee leveranciers ' +
      'voortaan één configuratie delen.',
    whatNow: 'Kies expliciet "nieuwe definitie" of "bestaande definitie hergebruiken".',
  },
  REUSE_DEFINITION_REQUIRED: {
    title: 'Geen bestaande definitie gekozen',
    explanation: 'Hergebruiken kan alleen met een expliciet gekozen bestaande definitie.',
    whatNow: 'Kies de bestaande definitie waaraan deze koppeling moet hangen.',
  },
  REUSE_DEFINITION_NOT_ALLOWED: {
    title: 'Bestaande definitie hoort niet bij "nieuw"',
    explanation:
      'Er is een bestaande definitie meegegeven terwijl er een nieuwe definitie gematerialiseerd wordt. ' +
      'De server corrigeert dat niet stil: één van de twee is verkeerd.',
    whatNow: 'Kies "bestaande definitie hergebruiken", of verwijder de gekozen definitie.',
  },
  DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE: {
    title: 'Definitiewaarde kan niet bij hergebruik',
    explanation:
      'Bij het hergebruiken van een bestaande definitie blijft haar revisie ongewijzigd. Een bookmark met ' +
      'scope DEFINITION zou die revisie wijzigen en dus de configuratie van elke andere leverancier op ' +
      'die definitie mee veranderen.',
    whatNow:
      'Materialiseer een nieuwe definitie als deze waarde moet verschillen, of laat de definitiewaarden leeg.',
    showBackendDetail: true,
  },
  LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT: {
    title: 'Twee bronnen voor dezelfde waarde',
    explanation:
      'Een bookmark van dit sjabloon vult dit koppelingsveld al. Datzelfde veld nog eens rechtstreeks ' +
      'meegeven zou twee bronnen voor één waarde opleveren.',
    whatNow: 'Vul de waarde enkel bij de bookmark in.',
    showBackendDetail: true,
  },
  CONFIG_REQUIRED_BOOKMARK_MISSING: {
    title: 'Verplichte bookmark niet ingevuld',
    explanation:
      'Eén of meer verplichte bookmarks hebben geen waarde. Een uitdrukkelijk lege waarde ("") geldt ' +
      'niet als invulling van een verplichte bookmark.',
    whatNow: 'Vul de genoemde bookmarks in en probeer opnieuw.',
    showBackendDetail: true,
  },
  CONFIG_BOOKMARK_VALUE_INVALID: {
    title: 'Ongeldige bookmarkwaarde',
    explanation:
      'De ingevulde waarde past niet bij het type, het patroon of de keuzelijst van deze bookmark.',
    whatNow: 'Pas de waarde aan volgens de servertekst hieronder.',
    showBackendDetail: true,
  },
  CONFIG_BOOKMARK_VALUE_TOO_LONG: {
    title: 'Bookmarkwaarde te lang',
    explanation: 'De ingevulde waarde is langer dan de kolom waarin ze terechtkomt.',
    whatNow: 'Kort de waarde in.',
    showBackendDetail: true,
  },
  DEFINITION_CODE_IN_USE: {
    title: 'Definitiecode is al in gebruik',
    explanation:
      'Er bestaat al een importdefinitie met deze code bij deze bronorganisatie. Er is niets ' +
      'gematerialiseerd.',
    whatNow: 'Kies een andere definitiecode, of hergebruik de bestaande definitie.',
    showBackendDetail: true,
  },
  LINK_CODE_IN_USE: {
    title: 'Koppelingscode is al in gebruik',
    explanation: 'Er bestaat al een importkoppeling met deze code. Er is niets gematerialiseerd.',
    whatNow: 'Kies een andere koppelingscode.',
    showBackendDetail: true,
  },
  LINK_SCOPE_IN_USE: {
    title: 'Deze leverancier hangt al aan deze definitie',
    explanation:
      'Voor deze definitie bestaat al een koppeling met dezelfde leverancier en dezelfde bibliotheek. Er ' +
      'is niets gematerialiseerd.',
    whatNow: 'Gebruik de bestaande koppeling, of kies een andere leverancier/bibliotheek.',
    showBackendDetail: true,
  },
  DEFINITION_NOT_FROM_TEMPLATE: {
    title: 'Definitie komt niet uit dit sjabloon',
    explanation:
      'Alleen een definitie die zelf uit dit sjabloon gematerialiseerd is, kan hier hergebruikt worden.',
    whatNow: 'Kies een definitie uit de materialisatiehistoriek van dit sjabloon.',
  },
  TEMPLATE_REVISION_MISMATCH_ON_REUSE: {
    title: 'Andere sjabloonversie dan de gekozen definitie',
    explanation:
      'De gekozen bestaande definitie is bevroren op een andere sjabloonversie dan de versie die u nu ' +
      'gekozen heeft. Stil hergebruiken zou de sjabloonversievergelijking half en onzichtbaar uitvoeren.',
    whatNow:
      'Kies de sjabloonrevisie waarop die definitie bevroren is, of materialiseer een nieuwe definitie.',
    showBackendDetail: true,
  },
  DEFINITION_NOT_SHAREABLE: {
    title: 'Definitie is niet deelbaar',
    explanation:
      'Deze definitie draagt een LINK-bookmark op een plaats die op revisieniveau ligt (en dus mee de ' +
      'aanbiedingsidentiteit bepaalt). Zo’n definitie mag nooit door twee leveranciers gedeeld worden.',
    whatNow: 'Materialiseer een nieuwe definitie voor deze leverancier.',
    showBackendDetail: true,
  },
  TEMPLATE_REVISION_NOT_MATERIALISABLE: {
    title: 'Deze sjabloonrevisie is niet materialiseerbaar',
    explanation:
      'Alleen een ACTIVE of SUPERSEDED sjabloonrevisie kan gematerialiseerd worden; een DRAFT-revisie ' +
      'heeft haar eigen screening en validatie nog niet doorlopen.',
    whatNow: 'Kies een actieve (of bewust een oudere, superseded) sjabloonrevisie.',
  },
  SOURCE_ORGANISATION_NOT_FOUND: {
    title: 'Organisatie niet gevonden',
    explanation: 'De opgegeven leveranciers-/organisatiecode bestaat niet.',
    whatNow: 'Controleer de code in het inrichtingsoverzicht.',
    showBackendDetail: true,
  },
  LINK_NOT_FOUND: {
    title: 'Koppeling niet gevonden',
    explanation: 'Deze importkoppeling bestaat niet (meer), of het id klopt niet.',
    whatNow: 'Laad de koppelingenlijst opnieuw.',
  },
  LINK_BOOKMARK_LOCKED_BY_OPEN_BATCH: {
    title: 'Koppeling is vergrendeld door een open levering',
    explanation:
      'Deze koppeling heeft een open batch (levering die nog loopt). Een bookmarkwaarde wijzigen zou ' +
      'bepalen wat er in die lopende levering gefilterd, gemapt en gepubliceerd wordt, met andere ' +
      'configuratie dan waarmee ze begonnen is. De waarde is niet gewijzigd.',
    whatNow: 'Wacht tot de batch afgerond is, of annuleer/publiceer ze eerst.',
  },
  BOOKMARK_UNKNOWN: {
    title: 'Onbekende bookmark',
    explanation:
      'Deze bookmarknaam is niet gedeclareerd op de betrokken revisie. Een onbekende naam wordt nooit ' +
      'stil genegeerd.',
    whatNow: 'Laad de bookmarklijst opnieuw; ze is mogelijk gewijzigd.',
    showBackendDetail: true,
  },
  BOOKMARK_SCOPE_MISMATCH: {
    title: 'Verkeerde scope voor deze bookmark',
    explanation:
      'Op een koppeling kunnen alleen LINK-bookmarkwaarden ingevuld worden. Een DEFINITION-waarde ligt ' +
      'vast in de revisie en verandert nooit per koppeling.',
    showBackendDetail: true,
  },
  // `NO_ACTIVE_REVISION` (409 bij het wijzigen van een bookmarkwaarde: niets declareert welke bookmarks
  // deze koppeling heeft) staat hierboven al — niet gedupliceerd, de bestaande tekst dekt dit geval.
};

type FamilyFallback = { match: (code: string) => boolean; explanation: string };

/**
 * Familie-fallbacks voor een onbekende code, op basis van voor-/achtervoegsel — zie §4 regel 3. De
 * titel draagt in dat geval altijd de code letterlijk.
 */
const FAMILY_FALLBACKS: FamilyFallback[] = [
  {
    match: (code) => code.startsWith('CONFIG_') || code.startsWith('CONFIG'),
    explanation: 'De configuratie van de importdefinitie is onvolledig of ongeldig.',
  },
  {
    match: (code) => code.endsWith('_NOT_FOUND'),
    explanation: 'Niet gevonden.',
  },
  {
    match: (code) => code.endsWith('_IN_USE'),
    explanation: 'Die code of scope is al in gebruik.',
  },
  {
    match: (code) => code.includes('_CHANGED'),
    explanation: 'De toestand is ondertussen veranderd; lees opnieuw.',
  },
];

const GENERIC_409_EXPLANATION = 'De bewerking is geweigerd in de huidige toestand.';

/** Formatteert de technische regel die §4 regel 1 altijd verplicht: `<code> · HTTP <status> · <pad>`. */
function technicalLine(code: string | null, status: number, path: string): string {
  return `${code ?? 'geen code'} · HTTP ${status} · ${path}`;
}

/**
 * Vertaalt een `ApiError` naar de Nederlandse weergave, volgens de vijf regels van §4.
 *
 * `detail` is de letterlijke, volledige servertekst voor een bekende code met `showBackendDetail`
 * (bv. de conflictvoorbeelden van `BUNDLE_OFFER_CONFLICT`), anders `null`. Additief: bestaande
 * aanroepers die `detail` niet lezen, gedragen zich ongewijzigd.
 */
export function describe(error: ApiError): {
  title: string;
  explanation: string;
  whatNow: string | null;
  technical: string;
  detail: string | null;
} {
  const technical = technicalLine(error.code, error.status, error.path);

  // Regel 5: 500 of netwerkfout/afgebroken verzoek (status 0) — de server levert bewust geen details.
  if (error.status === 0 || error.status === 500) {
    return {
      title: error.status === 0 ? 'Geen verbinding met de server' : 'Onverwachte serverfout',
      explanation:
        error.status === 0
          ? 'Het verzoek kon niet verstuurd worden, of het antwoord kwam niet aan. De server geeft in dit ' +
            'geval geen verdere details.'
          : 'De server gaf een onverwachte fout terug. De server stuurt bewust geen foutdetails mee bij dit ' +
            'type fout.',
      whatNow: 'Raadpleeg het serverlogboek voor de precieze oorzaak.',
      technical,
      detail: null,
    };
  }

  // Regel 2 en 3: er is een code.
  if (error.code !== null) {
    const known = CODE_MESSAGES[error.code];
    if (known) {
      return {
        title: known.title,
        explanation: known.explanation,
        whatNow: known.whatNow ?? null,
        technical,
        // Volledig en letterlijk, nooit ingekort: de voorbeelden zijn precies wat de gebruiker nodig heeft.
        detail: known.showBackendDetail === true ? error.backendMessage : null,
      };
    }

    const family = FAMILY_FALLBACKS.find((f) => f.match(error.code!));
    const explanation = family ? family.explanation : GENERIC_409_EXPLANATION;
    return {
      title: `Geweigerd (${error.code})`,
      explanation,
      whatNow: null,
      technical,
      detail: null,
    };
  }

  // 413 zonder code: `MaxUploadSizeExceededException` heeft geen handler die een code levert
  // (zie ontdekking in docs/decisions.md 2026-09-23); enkel de status is betrouwbaar.
  if (error.status === 413 && error.code === null) {
    return {
      title: 'Bestand te groot',
      explanation: 'De server weigert dit bestand omdat het groter is dan de toegelaten uploadgrootte.',
      whatNow: 'Splits het bestand, of vraag de beheerder de limiet (CATALOG_MAX_UPLOAD_SIZE) te verhogen.',
      technical,
      detail: null,
    };
  }

  // Regel 4: 400 zonder code — de Engelse backendMessage letterlijk tonen, geen verzonnen Nederlandse zin.
  if (error.status === 400) {
    return {
      title: 'Ongeldige invoer',
      explanation:
        error.backendMessage ?? 'De server wees dit verzoek af, maar leverde geen verdere toelichting.',
      whatNow: 'De server levert hier geen stabiele foutcode; dit is de letterlijke ontwikkelaarstekst.',
      technical,
      detail: null,
    };
  }

  // Geen van de bovenstaande regels dekt dit geval expliciet (bv. een 404/409 zonder code, wat volgens
  // §1.2 vandaag niet voorkomt). Toon wat er wel is, zonder een oorzaak te verzinnen.
  return {
    title: 'De bewerking is geweigerd',
    explanation: error.backendMessage ?? 'De server leverde geen verdere toelichting bij deze fout.',
    whatNow: null,
    technical,
    detail: null,
  };
}
