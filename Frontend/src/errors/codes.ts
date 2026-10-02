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
    title: 'Beschrijving van het bestand niet gevonden',
    explanation: 'Deze beschrijving van het bestand bestaat niet (meer), of het id in de link klopt niet.',
    whatNow: 'Ga terug naar het inrichtingsoverzicht en zoek de beschrijving opnieuw op.',
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
    title: 'Referentie is al in gebruik met andere instellingen',
    explanation:
      'Er bestaat al een bundel met deze bundelreferentie, maar met een andere doelmodus, ' +
      'doelmoment of publicatiebeleid dan wat u nu aanmaakt.',
    whatNow: 'Gebruik een andere referentie, of maak de bundel opnieuw aan met dezelfde instellingen als de bestaande.',
  },
  BUNDLE_NOT_ASSEMBLING: {
    title: 'Bundel is niet meer in opbouw',
    explanation:
      'Deze actie mag alleen zolang de bundel nog in opbouw is. Deze bundel is al bevroren, ' +
      'geannuleerd of verder in het proces.',
  },
  BUNDLE_NOT_FROZEN: {
    title: 'Bundel is nog niet bevroren',
    explanation:
      'Deze actie is alleen mogelijk voor een bevroren bundel (bijvoorbeeld het voorbeeldbestand voor ' +
      'Prodis of een publicatierun).',
    whatNow: 'Bevries de bundel eerst, of kies een bevroren bundel.',
  },
  BATCH_ALREADY_IN_BUNDLE: {
    title: 'Batch zit al in een bundel',
    explanation: 'Deze batch is al lid van deze of een andere bundel en kan niet nogmaals toegevoegd worden.',
  },
  BATCH_IN_PUBLICATION_BUNDLE: {
    title: 'Batch zit in een publicatiebundel',
    explanation:
      'Aanvaarden als nulmeting en opname in een bundel sluiten elkaar per batch uit. ' +
      'Deze batch is al lid van een niet-geannuleerde bundel en kan daarom niet als nulmeting aanvaard worden.',
    whatNow: 'Werk de batch af via de bundel, of annuleer de bundel; daarna kan de batch weer als nulmeting aanvaard worden.',
  },
  BATCH_NOT_ACCEPTABLE: {
    title: 'Batch kan niet aanvaard worden',
    explanation:
      'Aanvaarden als nulmeting kan alleen voor een batch die gecontroleerd is, en één keer per batch. Deze batch is ' +
      'al aanvaard of nog niet (of niet meer) gecontroleerd.',
    whatNow: 'Laad de batch opnieuw en controleer de status.',
  },
  BATCH_NOT_RESUMABLE: {
    title: 'Batch kan niet hervat worden',
    explanation:
      'Hervatten kan alleen voor een batch die halverwege onderbroken is (status "Wijzigingen worden bepaald"). ' +
      'Deze batch staat in een andere status.',
    whatNow: 'Laad de batch opnieuw en controleer de status.',
  },
  BATCH_NOT_BUNDLEABLE: {
    title: 'Batch is niet bundelbaar',
    explanation: 'Deze batch voldoet niet aan de voorwaarden om in een publicatiebundel opgenomen te worden.',
  },
  BATCH_VALIDATION_NOT_ESTABLISHED: {
    title: 'Batch is nog niet gevalideerd',
    explanation: 'De controle van deze batch is nog niet afgerond; het eindoordeel staat nog niet vast.',
  },
  BATCH_VALIDATION_BLOCKING: {
    title: 'Batch heeft blokkerende validatiefouten',
    explanation: 'De controle van deze batch vond blokkerende problemen; de batch kan zo niet toegevoegd worden.',
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
      'Deze mutatie is tegengehouden door een kritiek herkenningsprobleem (een verwijzing om het artikel te ' +
      'herkennen klopt niet); in deze fase van de applicatie is daar nog geen beslissing voor mogelijk.',
  },
  IDENTITY_DECISION_NOT_IN_SCOPE: {
    title: 'Identiteitsbeslissing hoort hier niet',
    explanation:
      'Een beslissing over de herkenning van een artikel past de bekende stand van de artikelen aan; dat ' +
      'komt in een latere fase van de applicatie.',
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
    whatNow: 'Beoordeel eerst de mutaties met status "Wacht op goedkeuring".',
    showBackendDetail: true,
  },
  SOURCE_STATE_CHANGED_SINCE_SCREENING: {
    title: 'Bronstaat is veranderd sinds de screening',
    explanation: 'De bekende stand van de artikelen is veranderd sinds de controle van een batch in deze bundel.',
    whatNow: 'Laat de betrokken levering opnieuw controleren.',
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
    explanation: 'Alleen een taak die handmatig start, neemt een handmatig opgeladen levering aan.',
    whatNow: 'Kies een taak die handmatig start.',
  },
  NO_ACTIVE_REVISION: {
    title: 'Geen actieve versie',
    explanation:
      'De beschrijving van het bestand van deze taak heeft geen actieve versie; een levering kan dus niet ' +
      'gecontroleerd worden.',
    whatNow: 'Activeer de conceptversie onder Inrichting (open de versie → "Versie activeren").',
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
      'Deze fase ondersteunt enkel de proefpublicatie en schrijft niets naar Prodis. Publiceren naar de ' +
      'controlebibliotheek of de echte bibliotheek blijft dicht tot de afspraken met het doelsysteem bewezen zijn.',
  },
  PUBLICATION_RUN_IN_PROGRESS: {
    title: 'Er loopt al een publicatierun',
    explanation: 'Deze bundel heeft al een niet-afgeronde publicatierun; wacht tot die klaar is.',
    showBackendDetail: true,
  },
  BUNDLE_CONTENT_CHANGED_SINCE_FREEZE: {
    title: 'Inhoud is veranderd sinds het bevriezen',
    explanation:
      'De inhoud van de bundel wijkt af van wat bij het bevriezen vastgelegd is (de controlesom klopt niet ' +
      'meer); er is niets gepubliceerd.',
    whatNow: 'Onderzoek de wijziging voor u deze bundel opnieuw probeert te publiceren.',
    showBackendDetail: true,
  },
  PUBLICATION_RUN_NOT_FOUND: {
    title: 'Publicatierun niet gevonden',
    explanation: 'Deze publicatierun bestaat niet (meer).',
  },
  PUBLICATION_RUN_ARTIFACT_NOT_AVAILABLE: {
    title: 'Geen artefact beschikbaar',
    explanation: 'Deze run draagt geen artefact; enkel een afgeronde proefrun heeft er een.',
  },
  PUBLICATION_RUN_NOT_STUCK: {
    title: 'Run kan niet afgebroken worden',
    explanation: 'Afbreken kan alleen zolang een run nog wordt voorbereid; deze run is dat niet (meer).',
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
    title: 'Sjabloonversie niet gevonden',
    explanation: 'Deze versie van het sjabloon bestaat niet (meer).',
    whatNow: 'Laad de lijst met versies opnieuw en kies een bestaande versie.',
  },
  DEFINITION_NOT_A_TEMPLATE: {
    title: 'Geen sjabloon',
    explanation: 'Deze beschrijving van het bestand is geen herbruikbaar sjabloon.',
  },
  NO_ACTIVE_TEMPLATE_REVISION: {
    title: 'Geen actieve sjabloonversie',
    explanation: 'Dit sjabloon heeft geen actieve versie; er kan geen sjabloonversie gekozen worden zonder er zelf één op te geven.',
    whatNow: 'Kies expliciet een sjabloonversie.',
  },
  BOOKMARK_NOT_FOUND: {
    title: 'Invulpunt niet gevonden',
    explanation: 'Dit invulpunt bestaat niet (meer) op deze sjabloonversie.',
  },
  // S1-F3 — het schrijfdeel van scherm 1b: materialiseren + waarde van een invulpunt wijzigen. Verschillende
  // codes hieronder zouden via een familie-fallback (`CONFIG_*`, `_NOT_FOUND`, `_IN_USE`) al een
  // algemene zin krijgen; ze staan hier expliciet omdat die zin niet zegt wát de gebruiker moet doen.
  MATERIALISATION_MODE_REQUIRED: {
    title: 'Kies nieuw of hergebruik',
    explanation:
      'Er is niet opgegeven of dit sjabloon een nieuwe beschrijving van het bestand wordt of een bestaande ' +
      'beschrijving hergebruikt. Die keuze heeft bewust geen standaardwaarde: ze bepaalt of twee leveranciers ' +
      'voortaan één configuratie delen.',
    whatNow: 'Kies expliciet "nieuwe beschrijving" of "bestaande beschrijving hergebruiken".',
  },
  REUSE_DEFINITION_REQUIRED: {
    title: 'Geen bestaande beschrijving gekozen',
    explanation: 'Hergebruiken kan alleen met een expliciet gekozen bestaande beschrijving van het bestand.',
    whatNow: 'Kies de bestaande beschrijving waaraan deze koppeling moet hangen.',
  },
  REUSE_DEFINITION_NOT_ALLOWED: {
    title: 'Bestaande beschrijving hoort niet bij "nieuw"',
    explanation:
      'Er is een bestaande beschrijving meegegeven terwijl er een nieuwe beschrijving van het bestand aangemaakt wordt. ' +
      'De server corrigeert dat niet stil: één van de twee is verkeerd.',
    whatNow: 'Kies "bestaande beschrijving hergebruiken", of verwijder de gekozen beschrijving.',
  },
  DEFINITION_SCOPE_VALUE_NOT_ALLOWED_ON_REUSE: {
    title: 'Waarde in de beschrijving kan niet bij hergebruik',
    explanation:
      'Bij het hergebruiken van een bestaande beschrijving van het bestand blijft haar versie ongewijzigd. Een invulpunt dat in ' +
      'de beschrijving zelf vastligt, zou die versie wijzigen en dus de instellingen van elke andere leverancier ' +
      'met die beschrijving mee veranderen.',
    whatNow:
      'Maak een nieuwe beschrijving aan als deze waarde moet verschillen, of laat de waarden in de beschrijving leeg.',
    showBackendDetail: true,
  },
  LINK_FIELD_BOTH_BOOKMARK_AND_EXPLICIT: {
    title: 'Twee bronnen voor dezelfde waarde',
    explanation:
      'Een invulpunt van dit sjabloon vult dit koppelingsveld al. Datzelfde veld nog eens rechtstreeks ' +
      'meegeven zou twee bronnen voor één waarde opleveren.',
    whatNow: 'Vul de waarde enkel bij het invulpunt in.',
    showBackendDetail: true,
  },
  CONFIG_REQUIRED_BOOKMARK_MISSING: {
    title: 'Verplicht invulpunt niet ingevuld',
    explanation:
      'Eén of meer verplichte invulpunten hebben geen waarde. Een uitdrukkelijk lege waarde ("") geldt ' +
      'niet als invulling van een verplicht invulpunt.',
    whatNow: 'Vul de genoemde invulpunten in en probeer opnieuw.',
    showBackendDetail: true,
  },
  CONFIG_BOOKMARK_VALUE_INVALID: {
    title: 'Ongeldige waarde voor een invulpunt',
    explanation:
      'De ingevulde waarde past niet bij het type, het patroon of de keuzelijst van dit invulpunt.',
    whatNow: 'Pas de waarde aan volgens de servertekst hieronder.',
    showBackendDetail: true,
  },
  CONFIG_BOOKMARK_VALUE_TOO_LONG: {
    title: 'Waarde van het invulpunt is te lang',
    explanation: 'De ingevulde waarde is langer dan de kolom waarin ze terechtkomt.',
    whatNow: 'Kort de waarde in.',
    showBackendDetail: true,
  },
  DEFINITION_CODE_IN_USE: {
    title: 'Code van de beschrijving is al in gebruik',
    explanation:
      'Er bestaat al een beschrijving van het bestand met deze code bij deze leverancier of aankoopvereniging. Er is niets ' +
      'aangemaakt.',
    whatNow: 'Kies een andere code voor de beschrijving, of hergebruik de bestaande beschrijving.',
    showBackendDetail: true,
  },
  LINK_CODE_IN_USE: {
    title: 'Koppelingscode is al in gebruik',
    explanation: 'Er bestaat al een koppeling met deze code. Er is niets aangemaakt.',
    whatNow: 'Kies een andere koppelingscode.',
    showBackendDetail: true,
  },
  LINK_SCOPE_IN_USE: {
    title: 'Deze leverancier hangt al aan deze beschrijving',
    explanation:
      'Voor deze beschrijving bestaat al een koppeling met dezelfde leverancier en dezelfde bibliotheek. Er ' +
      'is niets aangemaakt.',
    whatNow: 'Gebruik de bestaande koppeling, of kies een andere leverancier/bibliotheek.',
    showBackendDetail: true,
  },
  DEFINITION_NOT_FROM_TEMPLATE: {
    title: 'Beschrijving komt niet uit dit sjabloon',
    explanation:
      'Alleen een beschrijving van het bestand die zelf uit dit sjabloon aangemaakt is, kan hier hergebruikt worden.',
    whatNow: 'Kies een beschrijving uit de historiek van dit sjabloon.',
  },
  TEMPLATE_REVISION_MISMATCH_ON_REUSE: {
    title: 'Andere sjabloonversie dan de gekozen beschrijving',
    explanation:
      'De gekozen bestaande beschrijving is bevroren op een andere sjabloonversie dan de versie die u nu ' +
      'gekozen heeft. Stil hergebruiken zou de sjabloonversievergelijking half en onzichtbaar uitvoeren.',
    whatNow:
      'Kies de sjabloonversie waarop die beschrijving bevroren is, of maak een nieuwe beschrijving aan.',
    showBackendDetail: true,
  },
  DEFINITION_NOT_SHAREABLE: {
    title: 'Beschrijving is niet deelbaar',
    explanation:
      'Deze beschrijving van het bestand heeft een invulpunt dat per koppeling ingevuld wordt, op een plaats die in de versie ' +
      'zelf ligt (en dus mee bepaalt hoe een artikel herkend wordt). Zo’n beschrijving mag nooit door twee ' +
      'leveranciers gedeeld worden.',
    whatNow: 'Maak een nieuwe beschrijving aan voor deze leverancier.',
    showBackendDetail: true,
  },
  TEMPLATE_REVISION_NOT_MATERIALISABLE: {
    title: 'Deze sjabloonversie kan niet gebruikt worden',
    explanation:
      'Alleen een actieve of vervangen sjabloonversie kan gebruikt worden om een beschrijving van het bestand aan te maken; een ' +
      'concept heeft haar eigen controle nog niet doorlopen.',
    whatNow: 'Kies een actieve (of bewust een oudere, vervangen) sjabloonversie.',
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
      'Deze koppeling heeft een open batch (levering die nog loopt). De waarde van een invulpunt wijzigen zou ' +
      'bepalen wat er in die lopende levering gefilterd, gemapt en gepubliceerd wordt, met andere ' +
      'configuratie dan waarmee ze begonnen is. De waarde is niet gewijzigd.',
    whatNow: 'Wacht tot de batch afgerond is, of annuleer/publiceer ze eerst.',
  },
  BOOKMARK_UNKNOWN: {
    title: 'Onbekend invulpunt',
    explanation:
      'Deze naam van een invulpunt is niet gedeclareerd op de betrokken versie. Een onbekende naam wordt nooit ' +
      'stil genegeerd.',
    whatNow: 'Laad de lijst met invulpunten opnieuw; ze is mogelijk gewijzigd.',
    showBackendDetail: true,
  },
  BOOKMARK_SCOPE_MISMATCH: {
    title: 'Dit invulpunt geldt niet per koppeling',
    explanation:
      'Op een koppeling kunnen alleen waarden ingevuld worden van invulpunten die per koppeling gelden. Een ' +
      'waarde die in de beschrijving van het bestand vastligt, verandert nooit per koppeling.',
    showBackendDetail: true,
  },
  // `NO_ACTIVE_REVISION` (409 bij het wijzigen van een bookmarkwaarde: niets declareert welke bookmarks
  // deze koppeling heeft) staat hierboven al — niet gedupliceerd, de bestaande tekst dekt dit geval.
  // S2-F1 — behandelgeval (issue_case), zie `docs/design/issue-case-design.md` §6 (S2-B3, leesendpoints).
  ISSUE_CASE_NOT_FOUND: {
    title: 'Behandelgeval niet gevonden',
    explanation: 'Dit behandelgeval bestaat niet (meer), of het id in de link klopt niet.',
    whatNow: 'Ga terug naar de lijst met behandelgevallen en zoek het geval opnieuw op.',
  },
  // S2-F2 — de statuswijziging (S2-B2, `POST /issue-cases/{id}/status`, recht MANAGE).
  ISSUE_CASE_STATUS_REQUIRED: {
    title: 'Nieuwe status ontbreekt',
    explanation: 'Er is geen nieuwe status meegegeven. Dit zou via dit scherm niet mogen gebeuren.',
  },
  ISSUE_CASE_STATUS_UNKNOWN: {
    title: 'Onbekende status',
    explanation: 'De meegegeven status is geen geldige waarde voor een behandelgeval.',
  },
  ISSUE_CASE_REASON_REQUIRED: {
    title: 'Reden ontbreekt',
    explanation: 'Elke statuswijziging van een behandelgeval vereist een reden; die ontbreekt.',
    whatNow: 'Vul een reden in.',
  },
  ISSUE_CASE_TRANSITION_NOT_ALLOWED: {
    title: 'Overgang niet toegestaan',
    explanation:
      'Deze statusovergang is niet toegestaan vanuit de huidige status van dit behandelgeval (bijvoorbeeld ' +
      'rechtstreeks van "Gecorrigeerd" naar "Afgewezen", of dezelfde status naar zichzelf).',
    whatNow: 'Laad het geval opnieuw en kies een toegestane actie voor de huidige status.',
  },
  ISSUE_CASE_STATUS_CHANGED: {
    title: 'Status is intussen gewijzigd',
    explanation:
      'De status van dit behandelgeval is sinds het laden gewijzigd (bv. door een andere gebruiker of ' +
      'een systeemheropening); er is niets opgeslagen.',
    whatNow: 'Laad het geval opnieuw en probeer de actie opnieuw.',
  },
  // S1-F4 — het schrijfdeel van scherm 1a: opvolgrevisie maken, een DRAFT wijzigen, activeren
  // (`docs/design/revision-successor-design.md` §5, §6). Geen enkele van deze codes stond hier al.
  // `REVISION_NOT_FOUND` en `REVISION_NOT_ACTIVATABLE` bestaan al langer in de backend, maar zijn pas
  // vanaf dit scherm bereikbaar: ze krijgen hier hun eerste Nederlandse tekst, want de
  // familie-fallback ("Niet gevonden." / "De bewerking is geweigerd in de huidige toestand.") zegt
  // niet wát de gebruiker moet doen.
  REVISION_NOT_FOUND: {
    title: 'Versie niet gevonden',
    explanation:
      'Deze versie van de beschrijving van het bestand bestaat niet (meer), of ze hoort bij een andere ' +
      'beschrijving dan die in de link.',
    whatNow: 'Laad het inrichtingsoverzicht opnieuw en kies een bestaande versie.',
  },
  REVISION_NOT_CLONEABLE: {
    title: 'Van deze versie kan geen opvolger gemaakt worden',
    explanation:
      'Een opvolger wordt gekopieerd uit de actieve versie of uit een vervangen versie. Een concept (dat ' +
      'zelf nog bewerkt wordt) en elke andere status zijn geen geldige bron.',
    whatNow:
      'Kies de actieve versie, of bewust een oudere vervangen versie om naar terug te draaien. Is dit al ' +
      'een concept, dan hoeft er geen opvolger: bewerk dit concept zelf.',
    showBackendDetail: true,
  },
  CHANGE_REASON_REQUIRED: {
    title: 'Wijzigingsreden ontbreekt',
    explanation:
      'Een opvolgende versie vraagt altijd een reden: zonder opgave blijft er niets na over waarom deze ' +
      'configuratie veranderde. Er is niets aangemaakt.',
    whatNow: 'Vul een wijzigingsreden in en probeer opnieuw.',
  },
  REVISION_DRAFT_ALREADY_EXISTS: {
    title: 'Er staat al een concept open',
    explanation:
      'Deze beschrijving van het bestand heeft al een openstaand concept. Er is er hoogstens één toegelaten, ' +
      'zodat nooit onduidelijk is welke werkversie geactiveerd gaat worden. Er is niets aangemaakt.',
    whatNow:
      'Werk het bestaande concept af (activeren) of laat het door een beheerder verwijderen; de server ' +
      'noemt hieronder het versienummer.',
    showBackendDetail: true,
  },
  REVISION_NOT_EDITABLE: {
    title: 'Deze versie is niet bewerkbaar',
    explanation:
      'Alleen een concept wordt gewijzigd. Een actieve of vervangen versie blijft bevroren: ' +
      'batches die eronder gecontroleerd zijn, moeten reproduceerbaar blijven. Er is niets opgeslagen.',
    whatNow: 'Maak een opvolger van deze versie en breng de wijziging daarin aan.',
  },
  REVISION_CANONICALISATION_CHANGE_BLOCKED: {
    title: 'Herkenningsversie kan niet gewijzigd worden',
    explanation:
      'Voor deze beschrijving van het bestand zijn er al aanvaarde artikelen. De herkenningsversie zit vooraan ' +
      'in de sleutel waarmee elk artikel herkend wordt, dus na deze wijziging zou élk bestaand artikel ' +
      'als nieuw terugkomen (een massale aanmaak) en daar is geen migratie voor. Er is niets opgeslagen — ' +
      'ook niet de andere velden die in hetzelfde verzoek meekwamen.',
    whatNow:
      'Deze weigering is niet te omzeilen: er is geen bevestiging die ze opheft. Zet de ' +
      'herkenningsversie terug op haar oorspronkelijke waarde; een hogere versie hoort op een ' +
      'koppeling die nog niets aanvaard heeft.',
    showBackendDetail: true,
  },
  IDENTITY_CHANGE_NOT_ACKNOWLEDGED: {
    title: 'Identiteitswijziging niet bevestigd',
    explanation:
      'Deze wijziging raakt hoe een artikel herkend wordt (het herkenningsprofiel of een kolom die meetelt ' +
      'bij de herkenning). Daardoor komt elk bestaand artikel als nieuw terug. Die keuze heeft bewust ' +
      'geen standaardwaarde. Er is niets opgeslagen.',
    whatNow: 'Vink de bevestiging aan als dit werkelijk de bedoeling is, en verstuur opnieuw.',
  },
  REVISION_ACTIVATION_CONFLICT: {
    title: 'Gelijktijdige activatie',
    explanation:
      'Een andere versie van deze beschrijving van het bestand is op hetzelfde moment geactiveerd. Er is ' +
      'niets geactiveerd en niets als vervangen gemarkeerd.',
    whatNow: 'Laad de lijst met versies opnieuw en kijk welke versie nu actief is voor u het opnieuw probeert.',
  },
  REVISION_NOT_ACTIVATABLE: {
    title: 'Deze versie kan niet geactiveerd worden',
    explanation:
      'Activeren gaat alleen vanuit een concept. Deze versie is al actief, of staat in een andere status.',
    whatNow: 'Laad de lijst met versies opnieuw en controleer de status.',
  },
  MAPPING_NOT_FOUND: {
    title: 'Veldmapping niet gevonden',
    explanation:
      'Deze veldmapping bestaat niet (meer) op deze versie. Een mapping van een andere versie is hier ' +
      'geen geldig doel.',
    whatNow: 'Laad de versie opnieuw.',
  },
  FILTER_NOT_FOUND: {
    title: 'Recordfilter niet gevonden',
    explanation: 'Dit recordfilter bestaat niet (meer) op deze versie.',
    whatNow: 'Laad de versie opnieuw.',
  },
  CONFIG_BOOKMARK_PLACE_UNRESOLVED: {
    title: 'Een invulpunt steunt nog op deze rij',
    explanation:
      'Een declaratie van een invulpunt in deze versie verwijst naar de mapping of het filter dat u wilde ' +
      'verwijderen. Zonder dat doel blijft er een invulpunt staan waarvan de waarde nergens landt. Er ' +
      'is niets verwijderd.',
    whatNow:
      'Dit scherm wijzigt bewust geen declaraties van invulpunten. Laat de declaratie door sjabloonbeheer ' +
      'aanpassen, of laat deze rij staan.',
    showBackendDetail: true,
  },
  // NT-6 — stappenplan "Nieuwe leverancier en taak". Sinds NT-3 dragen de 400's van de inrichting een code
  // `<VELD>_REQUIRED|_TOO_LONG|_INVALID`. De titel is bewust een korte, volledige aanwijzing: het
  // stappenplan toont ze letterlijk bij het betrokken veld. Geen ruwe codes in de tekst (V7).
  SOURCE_ORGANISATION_CODE_IN_USE: {
    title: 'Deze code is al in gebruik',
    explanation: 'Er bestaat al een leverancier of aankoopvereniging met deze code. Er is niets aangemaakt.',
    whatNow: 'Kies een andere code, of ga verder met de bestaande organisatie.',
  },
  TASK_NAME_IN_USE: {
    title: 'Deze taaknaam is al in gebruik',
    explanation: 'Deze koppeling heeft al een taak met deze naam. Er is niets aangemaakt.',
    whatNow: 'Kies een andere naam, of ga verder met de bestaande taak.',
  },
  CODE_REQUIRED: {
    title: 'Vul een code in',
    explanation: 'De code is verplicht. Het verzoek is niet uitgevoerd.',
  },
  CODE_TOO_LONG: {
    title: 'De code is te lang',
    explanation: 'Een code heeft hoogstens 50 tekens. Het verzoek is niet uitgevoerd.',
  },
  NAME_REQUIRED: {
    title: 'Vul een naam in',
    explanation: 'De naam is verplicht. Het verzoek is niet uitgevoerd.',
  },
  NAME_TOO_LONG: {
    title: 'De naam is te lang',
    explanation: 'Een naam heeft hoogstens 200 tekens. Het verzoek is niet uitgevoerd.',
  },
  TYPE_REQUIRED: {
    title: 'Kies de soort organisatie',
    explanation: 'Kies of het om een leverancier of een aankoopvereniging gaat. Het verzoek is niet uitgevoerd.',
  },
  SOURCE_ORGANISATION_CODE_REQUIRED: {
    title: 'Kies een leverancier',
    explanation: 'Er is geen leverancier of aankoopvereniging opgegeven. Het verzoek is niet uitgevoerd.',
  },
  SOURCE_ORGANISATION_CODE_TOO_LONG: {
    title: 'De code van de leverancier is te lang',
    explanation: 'Een code heeft hoogstens 50 tekens. Het verzoek is niet uitgevoerd.',
  },
  DELIMITER_REQUIRED: {
    title: 'Kies een scheidingsteken',
    explanation: 'Het teken tussen de kolommen ontbreekt. De versie is niet opgeslagen.',
  },
  DELIMITER_TOO_LONG: {
    title: 'Het scheidingsteken is precies één teken',
    explanation: 'Een scheidingsteken bestaat uit één enkel teken. De versie is niet opgeslagen.',
  },
  QUOTE_CHAR_TOO_LONG: {
    title: 'Het aanhalingsteken is precies één teken',
    explanation: 'Een aanhalingsteken bestaat uit één enkel teken. De versie is niet opgeslagen.',
  },
  CHARSET_REQUIRED: {
    title: 'Kies een tekenset',
    explanation: 'Zonder tekenset kan het bestand niet gelezen worden. De versie is niet opgeslagen.',
  },
  CHARSET_TOO_LONG: {
    title: 'De naam van de tekenset is te lang',
    explanation: 'De naam van een tekenset heeft hoogstens 40 tekens. De versie is niet opgeslagen.',
  },
  FIELD_REFERENCE_KIND_REQUIRED: {
    title: 'Kies hoe de kolommen herkend worden',
    explanation: 'Kies of kolommen op naam of op positie herkend worden. De versie is niet opgeslagen.',
  },
  FIELD_REFERENCE_KIND_TOO_LONG: {
    title: 'Kies hoe de kolommen herkend worden',
    explanation: 'Deze keuze is niet geldig. De versie is niet opgeslagen.',
  },
  SUPPLIER_FIELD_REQUIRED: {
    title: 'Vul de kolom voor de leverancier in',
    explanation: 'Deze kolom is nodig om een artikel te herkennen. De versie is niet opgeslagen.',
  },
  SUPPLIER_FIELD_TOO_LONG: {
    title: 'De kolom voor de leverancier is te lang',
    explanation: 'Een kolomaanduiding heeft hoogstens 200 tekens. De versie is niet opgeslagen.',
  },
  SUPPLIER_GROUP_FIELD_REQUIRED: {
    title: 'Vul de kolom voor de groep in',
    explanation: 'Deze kolom is nodig om een artikel te herkennen. De versie is niet opgeslagen.',
  },
  SUPPLIER_GROUP_FIELD_TOO_LONG: {
    title: 'De kolom voor de groep is te lang',
    explanation: 'Een kolomaanduiding heeft hoogstens 200 tekens. De versie is niet opgeslagen.',
  },
  SUPPLIER_REFERENCE_FIELD_REQUIRED: {
    title: 'Vul de kolom voor de referentie in',
    explanation: 'Deze kolom is nodig om een artikel te herkennen. De versie is niet opgeslagen.',
  },
  SUPPLIER_REFERENCE_FIELD_TOO_LONG: {
    title: 'De kolom voor de referentie is te lang',
    explanation: 'Een kolomaanduiding heeft hoogstens 200 tekens. De versie is niet opgeslagen.',
  },
  DISCOUNT_CODE_FIELD_REQUIRED: {
    title: 'Vul de kolom voor de kortingscode in',
    explanation:
      'Bij herkenning met kortingscode is deze kolom verplicht. De versie is niet opgeslagen.',
  },
  DISCOUNT_CODE_FIELD_INVALID: {
    title: 'Geen kolom voor de kortingscode bij deze herkenning',
    explanation:
      'Bij herkenning zonder kortingscode wordt geen kortingscodekolom gelezen. De versie is niet opgeslagen.',
    whatNow: 'Laat de kolom leeg, of kies herkenning met kortingscode.',
  },
  DISCOUNT_CODE_FIELD_TOO_LONG: {
    title: 'De kolom voor de kortingscode is te lang',
    explanation: 'Een kolomaanduiding heeft hoogstens 200 tekens. De versie is niet opgeslagen.',
  },
  BASE_PRICE_FIELD_REQUIRED: {
    title: 'Vul de kolom voor de basisprijs in',
    explanation: 'Zonder prijskolom kan een levering niet gelezen worden. De versie is niet opgeslagen.',
  },
  BASE_PRICE_FIELD_TOO_LONG: {
    title: 'De kolom voor de basisprijs is te lang',
    explanation: 'Een kolomaanduiding heeft hoogstens 200 tekens. De versie is niet opgeslagen.',
  },
  DESCRIPTION_FIELD_TOO_LONG: {
    title: 'De kolom voor de omschrijving is te lang',
    explanation: 'Een kolomaanduiding heeft hoogstens 200 tekens. De versie is niet opgeslagen.',
  },
  CURRENCY_FIELD_TOO_LONG: {
    title: 'De kolom voor de valuta is te lang',
    explanation: 'Een kolomaanduiding heeft hoogstens 200 tekens. De versie is niet opgeslagen.',
  },
  LIBRARY_CODE_REQUIRED: {
    title: 'Vul de doelbibliotheek in',
    explanation: 'Een koppeling hoort altijd bij één Prodis-bibliotheek. Er is niets aangemaakt.',
  },
  LIBRARY_CODE_TOO_LONG: {
    title: 'De code van de doelbibliotheek is te lang',
    explanation: 'De code van een bibliotheek heeft hoogstens 20 tekens. Er is niets aangemaakt.',
  },
  LIBRARY_SEARCH_SUPPLIER_CODE_TOO_LONG: {
    title: 'De leverancierscode in de bibliotheek is te lang',
    explanation: 'Deze code heeft hoogstens 50 tekens. Er is niets aangemaakt.',
  },
  LINK_CURRENCY_INVALID: {
    title: 'Ongeldige standaardvaluta',
    explanation:
      'Een valuta is een code van precies drie hoofdletters, bijvoorbeeld EUR. De waarde wordt nooit ' +
      'automatisch aangepast; er is niets aangemaakt.',
    whatNow: 'Vul drie hoofdletters in, of laat het veld leeg voor euro.',
  },
  DEFINITION_ID_REQUIRED: {
    title: 'De beschrijving van het bestand ontbreekt',
    explanation: 'Er is geen beschrijving van het bestand meegegeven. Dit zou via dit scherm niet mogen gebeuren.',
  },
  LINK_ID_REQUIRED: {
    title: 'De koppeling ontbreekt',
    explanation: 'Er is geen koppeling meegegeven. Dit zou via dit scherm niet mogen gebeuren.',
  },
  // NT-10 — proefinlezing (`POST /revisions/{id}/trial-reads`, contract `docs/design/proefinlezing-design.md` §3).
  FILE_REQUIRED: {
    title: 'Kies een bestand',
    explanation: 'Er is geen bestand meegestuurd. Er is niets gelezen en niets opgeslagen.',
    whatNow: 'Kies een bestand en start de proef opnieuw.',
  },
  LINK_NOT_OF_REVISION_DEFINITION: {
    title: 'Koppeling en versie horen niet bij elkaar',
    explanation:
      'Deze koppeling hoort bij een andere beschrijving van het bestand dan de gekozen versie. Er is niets gelezen.',
    whatNow: 'Herlaad de pagina en kies een versie van de beschrijving van deze koppeling.',
  },
  // Analyse-opvolging stap 3a — `ApiExceptionHandler` geeft deze twee fouten nu een eigen code. Zelfde tekst als
  // de regel "413 zonder code" hieronder, zodat een upload boven de limiet er hetzelfde uitziet als voorheen.
  UPLOAD_TOO_LARGE: {
    title: 'Bestand te groot',
    explanation: 'De server weigert dit bestand omdat het groter is dan de toegelaten uploadgrootte.',
    whatNow: 'Splits het bestand, of vraag de beheerder de maximale uploadgrootte te verhogen.',
  },
  REQUEST_BODY_UNREADABLE: {
    title: 'Verzoek niet leesbaar',
    explanation:
      'De server kon de meegestuurde gegevens niet lezen (ontbrekend, onvolledig of met een onverwachte waarde). ' +
      'Er is niets opgeslagen. Dit zou via dit scherm niet mogen gebeuren.',
    whatNow: 'Herlaad de pagina en probeer het opnieuw; blijft het gebeuren, verwittig dan de beheerder.',
  },
  BATCH_BEING_PROCESSED: {
    title: 'Deze batch wordt al verwerkt',
    explanation: 'Deze batch wordt op dit moment al verwerkt. Probeer het later opnieuw.',
  },
  BASELINE_ACCEPTANCE_IN_PROGRESS: {
    title: 'Aanvaarding loopt nog',
    explanation: 'Er loopt al een aanvaarding als nulmeting voor deze koppeling. Probeer het later opnieuw.',
  },
};

type FamilyFallback = { match: (code: string) => boolean; title: string; explanation: string };

/**
 * Familie-fallbacks voor een onbekende code, op basis van voor-/achtervoegsel — zie §4 regel 3. De titel is
 * Nederlands per familie; de code zelf staat enkel in de technische regel onder "Technische details (voor
 * support)" (V7, NT-11a).
 */
const FAMILY_FALLBACKS: FamilyFallback[] = [
  {
    match: (code) => code.startsWith('CONFIG_') || code.startsWith('CONFIG'),
    title: 'De beschrijving van het bestand klopt niet',
    explanation: 'De beschrijving van het bestand is onvolledig of ongeldig.',
  },
  {
    match: (code) => code.endsWith('_NOT_FOUND'),
    title: 'Niet gevonden',
    explanation: 'Niet gevonden.',
  },
  {
    match: (code) => code.endsWith('_IN_USE'),
    title: 'Al in gebruik',
    explanation: 'Die code of combinatie is al in gebruik.',
  },
  {
    match: (code) => code.includes('_CHANGED'),
    title: 'Intussen veranderd',
    explanation: 'De toestand is ondertussen veranderd; lees opnieuw.',
  },
];

const GENERIC_409_TITLE = 'De bewerking is geweigerd';
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
      title: family ? family.title : GENERIC_409_TITLE,
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
      whatNow: 'Splits het bestand, of vraag de beheerder de maximale uploadgrootte te verhogen.',
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
      whatNow: 'Deze melding komt rechtstreeks van de server en is niet vertaald.',
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
