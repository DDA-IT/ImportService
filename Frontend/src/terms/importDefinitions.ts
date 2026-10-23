/**
 * R2 — de schermteksten van het overzicht Importdefinities (`/templates`) en het importdefinitiedetail
 * (`/templates/:definitionId`), beslissingslog 2026-10-23 "Importdefinitie als schermwoord" en "Naamgeving en redesign
 * inrichting: goedgekeurd". Op één plaats, naast `setupDashboard.ts`, zodat een woordwijziging één aanpassing is.
 * Codes en ruwe ids staan hier nooit als zichtbare tekst; die blijven onder "Technische details (voor support)".
 */

import type { LegacyOriginKind } from '../api/types.ts';
import type { StatusFamily } from '../components/StatusBadge.tsx';
import type { DefinitionOrigin, DefinitionStatus } from '../features/templates/definitionsModel.ts';

export const STATUS_LABEL: Record<DefinitionStatus, string> = {
  ACTIVE: 'Actief',
  CONCEPT: 'Concept',
  NO_VERSION: 'Geen bruikbare versie',
};

export const STATUS_TONE: Record<DefinitionStatus, StatusFamily> = {
  ACTIVE: 'ready',
  CONCEPT: 'awaiting',
  NO_VERSION: 'blocked',
};

export const STATUS_HINT: Record<DefinitionStatus, string> = {
  ACTIVE: 'Er is een actieve versie: de importdefinitie is bruikbaar.',
  CONCEPT: 'Er is nog geen actieve versie, enkel een concept: de importdefinitie is nog niet bruikbaar. Activeer eerst de conceptversie.',
  NO_VERSION: 'Er is geen actieve versie en ook geen concept: de importdefinitie is niet bruikbaar. Maak eerst een versie.',
};

/** Aparte badge naast de status: de leverancier of vereniging van de importdefinitie is niet meer actief. */
export const INACTIVE_LABEL = 'Inactief';
export const INACTIVE_HINT = 'De leverancier of vereniging van deze importdefinitie is inactief.';

export const ORIGIN_LABEL: Record<DefinitionOrigin, string> = {
  LEGACY: 'Uit legacy',
  OWN: 'Eigen',
};

export const ORIGIN_HINT: Record<DefinitionOrigin, string> = {
  LEGACY: 'Deze importdefinitie is gemaakt bij de overname van de configuratie uit legacy.',
  OWN: 'Niet uit de overname van legacy: zelf aangemaakt.',
};

/** Toelichting per soort legacy-overname; de ruwe waarde (`legacyOriginKind`) staat enkel onder "Technische details". */
export const LEGACY_KIND_DETAIL: Record<LegacyOriginKind, string> = {
  MIGRATED_TEMPLATE: 'Herbruikbare importdefinitie, gemaakt bij de overname uit legacy.',
  MIGRATED_SHARED: 'Gedeelde importdefinitie, overgenomen uit legacy en gebaseerd op een herbruikbare importdefinitie.',
  MIGRATED_DETACHED: 'Overgenomen uit legacy en losgekoppeld van de gedeelde importdefinitie.',
};

export const DEFINITIONS_TEXT = {
  intro:
    'Alle importdefinities: hoe elk leveranciersbestand gelezen wordt, eigen en herbruikbaar. Een herbruikbare importdefinitie zelf krijgt nooit een koppeling of levering.',
  what:
    'Een importdefinitie beschrijft hoe een leveranciersbestand gelezen wordt: in legacy de importsoort (PSIMDHFD/PSIMPDEF). ' +
    'Een herbruikbare importdefinitie wordt door meerdere leveranciers of interfaces gedeeld. U maakt uit een herbruikbare ' +
    'importdefinitie een eigen importdefinitie, een conceptversie en een koppeling. Dat heet hier materialiseren. ' +
    'Gedeelde importdefinities uit legacy hangen aan één herbruikbare importdefinitie.',
  loading: 'Bezig met laden…',
  gridLabel: 'Samenvatting van de importdefinities',
  show: 'Tonen',
  hide: 'Verbergen',
  cards: {
    all: 'Importdefinities',
    active: 'Actief',
    concept: 'Concept: nog te activeren',
    reusable: 'Herbruikbaar (gedeeld)',
  },
  allSub: (active: number, concept: number, noVersion: number) =>
    `${active} actief, ${concept} concept${noVersion > 0 ? `, ${noVersion} zonder bruikbare versie` : ''}`,
  activeSub: (reusable: number, own: number) => `${reusable} herbruikbaar, ${own} eigen`,
  conceptSub: 'nog niet bruikbaar — activeer eerst',
  conceptNone: 'Geen enkele importdefinitie wacht nog op activatie',
  showConcepts: 'Toon concepten',
  reusableSub: 'gedeeld door meer dan één leverancier of interface',
  conceptRowMeta: 'Nog geen actieve versie',
  conceptVersion: (n: number) => `Versie ${n} (Concept)`,
  draftOpen: (n: number) => `concept v${n} open`,
  moreList: (shown: number, total: number) => `Toont ${shown} van ${total}.`,
  listTitle: 'Alle importdefinities',
  searchLabel: 'Zoek op code of naam',
  searchPlaceholder: 'Zoek op code of naam',
  filterKind: 'Soort',
  filterStatus: 'Status',
  filterOrigin: 'Herkomst',
  filterAll: 'Alle',
  filterInactive: 'Inactief',
  kindReusable: 'Herbruikbaar',
  kindOwn: 'Eigen',
  kindReusableFull: 'Herbruikbare importdefinitie',
  kindOwnFull: 'Eigen importdefinitie',
  columns: {
    code: 'Code',
    name: 'Naam',
    kind: 'Soort',
    supplier: 'Leverancier',
    activeVersion: 'Actieve versie',
    usedBy: 'Gebruikt door',
    links: 'Koppelingen',
    origin: 'Herkomst',
    action: 'Actie',
  },
  open: 'Openen',
  openLabel: (name: string) => `Openen: ${name}`,
  emptyList: 'Geen importdefinities gevonden.',
  noMatch: 'Geen importdefinities die bij de zoekopdracht en filters passen.',
  noActive: 'Geen',
  versionNumber: (n: number) => `Versie ${n}`,
  usageUnknown: '—',
  usageLoading: '…',
  usageUnknownHint: 'Het gebruik van een herbruikbare importdefinitie kon niet geladen worden.',
  usedByNone: 'Nog niet gebruikt',
  usedByImportDefinitions: (n: number) => `${n} ${n === 1 ? 'importdefinitie' : 'importdefinities'}`,
  usageLoadFailed: 'Het gebruik van de herbruikbare importdefinities kon niet geladen worden.',

  // Detail
  notFound: 'Deze importdefinitie bestaat niet.',
  tabsLabel: 'Onderdelen van de importdefinitie',
  tabs: { overview: 'Overzicht', fields: 'Bestand en velden', versions: 'Versies', usedBy: 'Gebruikt door' },
  originPrefix: (label: string) => `Herkomst: ${label}`,
  addSupplier: 'Leverancier toevoegen op basis van deze importdefinitie',
  addSupplierConcept: 'Een concept van een importdefinitie is nog niet bruikbaar. Activeer het eerst.',
  createSuccessorNone: 'Er is nog geen versie om een opvolger van te maken.',
  nextStepLabel: 'Volgende stap:',
  nextStepDraft: (n: number) => `versie ${n} controleren en activeren`,
  nextStepNoVersion: 'een versie maken en activeren',
  viewVersion: (n: number) => `Versie ${n} bekijken`,
  stepsIntroOwn: 'Zo hangt het samen: een eigen importdefinitie hoort bij één leverancier; daaraan hangen koppelingen en taken.',
  stepsIntroReusable:
    'Zo hangt het samen: een herbruikbare importdefinitie wordt door meerdere leveranciers gedeeld; zij bouwen daarop hun koppelingen en taken.',
  miniLabel: 'Samenvatting van de importdefinitie',
  mini: {
    activeVersion: 'Actieve versie',
    versions: 'Versies',
    placeholders: 'Invulpunten',
    usedByReusable: 'Importdefinities die het gebruiken',
    usedByOwn: 'Leveranciers die het gebruiken',
    links: 'Koppelingen',
  },
  groupTitle: 'Gegevens',
  keyValue: {
    code: 'Code',
    name: 'Naam',
    kind: 'Soort',
    supplier: 'Leverancier',
    source: 'Bron',
    origin: 'Herkomst',
  },
  fileCardTitle: 'Bestandsopbouw',
  fileCardNone: 'Er is nog geen versie om de bestandsopbouw van te tonen.',
  viewFields: 'Velden bekijken',
  reusableNote: 'Dit is een herbruikbare importdefinitie. Ze krijgt zelf nooit een koppeling of levering.',
  placeholders: {
    title: (n: number) => `Invulpunten van versie ${n}`,
    explain: 'Een invulpunt is een waarde die je pas invult bij het gebruik van de importdefinitie.',
    columns: { name: 'Naam', kind: 'Soort waarde', where: 'Waar ingevuld', required: 'Verplicht' },
    whereDefinition: 'Bij de importdefinitie',
    whereLink: 'Per koppeling',
    whereOther: 'Andere geldigheid',
    yes: 'Ja',
    no: 'Nee',
    none: 'Er zijn geen invulpunten gedeclareerd op deze versie.',
    problems: 'Aandachtspunten bij de invulpunten',
  },
  usedBy: {
    none: 'Nog door geen enkele leverancier gebruikt.',
    reusableTitle: 'Gedeelde importdefinitie',
    cardTitle: (name: string) => name,
    tagShared: 'Gedeeld',
    tagOwn: 'Eigen',
    basedOn: (n: number | null) =>
      n === null
        ? 'Zonder herkomstversie van de herbruikbare importdefinitie'
        : `Gebaseerd op versie ${n} van de herbruikbare importdefinitie`,
    linkCount: (n: number) => `${n} ${n === 1 ? 'koppeling' : 'koppelingen'}`,
    shareable: 'Deelbaar',
    notShareable: 'Niet deelbaar',
    notShareableWarning:
      'Delen is niet mogelijk voor importdefinities waar een invulpunt per koppeling verschilt, en dat de gedeelde versie zou veranderen.',
    notShareableWarningNamed: (label: string) =>
      `Delen is niet mogelijk: het invulpunt “${label}” verschilt per koppeling en zou de gedeelde versie veranderen.`,
    toSupplier: 'Naar leverancier',
    linksLoading: 'Koppelingen laden…',
    noLinks: 'Deze importdefinitie heeft nog geen koppelingen.',
    linkActive: 'Actief',
    linkInactive: 'Niet actief',
    linkLibrary: (code: string) => `bibliotheek ${code}`,
    showValues: 'Invulpunten van de koppeling',
    ownSupplier: (name: string) => `${name} (eigen importdefinitie)`,
    ownNote: 'Eigen importdefinitie van deze leverancier',
    loadFailed: 'Het gebruik van deze importdefinitie kon niet geladen worden.',
    flagOff:
      'De gegevens over het gebruik van een herbruikbare importdefinitie zijn niet beschikbaar op deze omgeving: de inrichtings-API staat niet open.',
  },
  basedOn: 'Gebaseerd op herbruikbare importdefinitie',
  technicalIds: {
    definition: 'Definitie-id',
    versions: 'Versie-ids',
    usageType: 'Gebruikssoort',
    materialised: 'Gebruikt door (definitie-ids)',
    legacyOrigin: 'Herkomst uit legacy',
    legacyCodes: 'Legacy-definities',
  },
} as const;
