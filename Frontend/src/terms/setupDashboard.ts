/**
 * R1 — de schermteksten van het dashboard Leveranciers (`/setup`) en het leveranciersdetail
 * (`/setup/suppliers/:organisationId`), beslissingslog 2026-10-23 "Naamgeving en redesign inrichting: goedgekeurd".
 * Op één plaats, naast het woordenboek, zodat een woordwijziging één aanpassing is. Gebruikte woorden: Importdefinitie,
 * Inlezing, Publicatie. Codes en ruwe ids staan hier nooit; die blijven onder "Technische details (voor support)".
 */

import type { StatusFamily } from '../components/StatusBadge.tsx';
import type { NotReadyReasonKind, Readiness } from '../features/setup/setupDashboardModel.ts';

export const READINESS_LABEL: Record<Readiness, string> = {
  READY: 'Klaar',
  NOT_READY: 'Nog niet klaar',
  NOT_SET_UP: 'Nog niet ingericht',
  INACTIVE: 'Inactief',
};

export const READINESS_TONE: Record<Readiness, StatusFamily> = {
  READY: 'ready',
  NOT_READY: 'awaiting',
  NOT_SET_UP: 'neutral',
  INACTIVE: 'neutral',
};

export const READINESS_HINT: Record<Readiness, string> = {
  READY: 'Er is een koppeling met een actieve taak, en de importdefinitie ervan heeft een actieve versie.',
  NOT_READY: 'Er ontbreekt nog iets voordat er een levering kan binnenkomen.',
  NOT_SET_UP: 'Er is nog geen importdefinitie voor deze leverancier.',
  INACTIVE: 'Deze leverancier is niet actief.',
};

export const REASON_TEXT: Record<NotReadyReasonKind, string> = {
  NO_ACTIVE_VERSION: 'Versie niet geactiveerd',
  LINK_WITHOUT_TASK: 'Nog geen taak',
  NO_ACTIVE_TASK: 'Alle taken staan uit',
  NO_LINK: 'Nog geen koppeling',
  NO_DEFINITION: 'Nog geen importdefinitie',
  LINK_CHECK_FAILED: 'De controle van de koppeling is niet geslaagd',
};

export const DASHBOARD_TEXT = {
  intro: 'Hier ziet u in één blik welke leveranciers en koppelingen klaar zijn voor levering en wat nog moet gebeuren.',
  what:
    'Een leverancier heeft een of meer koppelingen. Een koppeling heeft taken die bepalen hoe een levering binnenkomt. ' +
    'Een taak is klaar zodra de bijhorende versie van de importdefinitie geactiveerd is. Een importdefinitie beschrijft ' +
    'hoe een leveranciersbestand gelezen wordt (in legacy de importsoort). Een herbruikbare importdefinitie wordt door ' +
    'meerdere leveranciers of koppelingen gedeeld.',
  loading: 'Bezig met laden…',
  linkChecksUnavailable:
    'De controle van de koppelingen kon niet geladen worden. De gereedheid hieronder is een inschatting op basis van versies en taken; laad de pagina opnieuw om de controle te herhalen.',
  gridLabel: 'Samenvatting van de leveranciers',
  show: 'Tonen',
  hide: 'Verbergen',
  cards: {
    suppliers: 'Leveranciers en verenigingen',
    ready: 'Klaar voor levering',
    notReady: 'Nog niet klaar',
    legacy: 'Overname uit legacy',
    definitions: 'Importdefinities',
  },
  suppliersSub: (suppliers: number, associations: number) =>
    `${suppliers} ${suppliers === 1 ? 'leverancier' : 'leveranciers'}, ${associations} ${
      associations === 1 ? 'aankoopvereniging' : 'aankoopverenigingen'
    }`,
  showSuppliers: 'Toon leveranciers',
  readySub: (n: number) => `${n} ${n === 1 ? 'taak' : 'taken'} met actieve versie`,
  showReady: 'Toon taken',
  readyNone: 'Nog geen enkele taak is klaar voor levering',
  notReadyNone: 'Niets dat nog ontbreekt',
  showNotReady: 'Toon wat ontbreekt',
  notReadySub: (counts: { noVersion: number; noTask: number; noLink: number; other: number }) => {
    const parts: string[] = [];
    if (counts.noVersion > 0) {
      parts.push(`${counts.noVersion} zonder actieve versie`);
    }
    if (counts.noTask > 0) {
      parts.push(`${counts.noTask} ${counts.noTask === 1 ? 'koppeling' : 'koppelingen'} zonder taak`);
    }
    if (counts.noLink > 0) {
      parts.push(`${counts.noLink} zonder koppeling`);
    }
    if (counts.other > 0) {
      parts.push(`${counts.other} andere`);
    }
    return parts.join(', ');
  },
  legacySub: (n: number) => `${n} ${n === 1 ? 'ontvangst' : 'ontvangsten'} te beoordelen`,
  legacyNone: 'Geen ontvangsten te beoordelen',
  legacyUnknown: 'De ontvangsten konden niet geladen worden',
  showLegacy: 'Toon ontvangsten',
  legacyAction: 'Beoordelen',
  legacyAll: 'Alle ontvangsten',
  definitionsSub: (reusable: number, own: number) => `${reusable} herbruikbaar, ${own} eigen`,
  showDefinitions: 'Toon importdefinities',
  allDefinitions: 'Alle importdefinities',
  moreSuppliers: (shown: number, total: number) => `Toont ${shown} van ${total}; de volledige lijst staat hieronder.`,
  moreList: (shown: number, total: number) => `Toont ${shown} van ${total}.`,
  moreDefinitions: (shown: number, total: number) =>
    `Toont ${shown} van ${total}; de volledige lijst staat onder Importdefinities.`,
  listTitle: 'Leveranciers',
  searchLabel: 'Zoek op code of naam',
  searchPlaceholder: 'Zoek op code of naam',
  filterKind: 'Soort',
  filterStatus: 'Status',
  filterAll: 'Alle',
  statusActive: 'Actief',
  statusInactive: 'Inactief',
  columns: {
    code: 'Code',
    name: 'Naam',
    kind: 'Soort',
    status: 'Status',
    links: 'Koppelingen',
    readiness: 'Gereedheid',
    action: 'Actie',
  },
  open: 'Openen',
  openLabel: (name: string) => `Openen: ${name}`,
  toSupplier: 'Naar leverancier',
  check: 'Controleren',
  continueSetup: 'Verder inrichten',
  emptyList: 'Geen leveranciers of aankoopverenigingen gevonden.',
  noMatch: 'Geen leveranciers die bij de zoekopdracht passen.',
  definitionKindReusable: 'Herbruikbare importdefinitie',
  definitionKindOwn: 'Eigen importdefinitie',
  versionActive: (id: number) => `actieve versie #${id}`,
  noActiveVersion: 'geen actieve versie',
  triggerManual: 'handmatig',
  redirectResolving: 'De versie wordt opgezocht…',
  redirectNotFound: 'Deze versie hoort bij geen enkele leverancier of aankoopvereniging die hier bekend is.',
  blockedReasonId: 'new-supplier-blocked',
} as const;

export const SUPPLIER_TEXT = {
  loading: 'Bezig met laden…',
  notFound: 'Deze leverancier of aankoopvereniging bestaat niet.',
  tabsLabel: 'Onderdelen van de leverancier',
  tabs: {
    overview: 'Overzicht',
    fields: 'Bestand en velden',
    versions: 'Versies',
    links: 'Koppelingen en taken',
  },
  continueSetup: 'Verder inrichten',
  addTask: 'Taak toevoegen',
  stepsLabel: 'Waar past dit in de keten?',
  stepsIntro:
    'Zo hangt het samen: een leverancier heeft een importdefinitie, daaraan hangen koppelingen en taken, en daaruit volgen inlezingen en publicaties.',
  steps: [
    'Leverancier',
    'Importdefinitie (versie = hoe het bestand gelezen wordt)',
    'Koppeling',
    'Taak',
    'Inlezing',
    'Publicatie naar Prodis',
  ],
  cards: {
    definitions: 'Importdefinities',
    activeVersion: 'Actieve versie',
    links: 'Koppelingen',
    tasks: 'Taken',
    notReady: 'Nog niet klaar',
  },
  miniLabel: 'Samenvatting van de leverancier',
  versionNumber: (n: number) => `Versie ${n}`,
  noActiveVersionValue: 'Geen',
  nextStepLabel: 'Volgende stap:',
  next: {
    createDefinition: 'een importdefinitie voor deze leverancier maken',
    createLink: 'een koppeling met een Prodis-bibliotheek maken',
    createTask: 'een taak toevoegen aan de koppeling',
    activate: 'de versie activeren zodat de taak klaar is voor levering',
    activateDraft: (n: number) => `versie ${n} (concept) controleren en activeren`,
    inactive: 'deze leverancier is niet actief; er is niets te doen',
    ready: 'een bestand inlezen',
    readyAction: 'Naar Bestand uploaden',
    viewVersion: (n: number) => `Versie ${n} bekijken`,
    reactivateTasks: 'een taak van de koppeling weer inschakelen',
    toLinks: 'Naar koppelingen en taken',
    check: 'Controleren',
  },
  groupTitle: 'Gegevens',
  keyValue: {
    code: 'Code',
    kind: 'Soort',
    status: 'Status',
    library: 'Bibliotheek',
    charset: 'Tekenset',
    password: 'Wachtwoord',
  },
  charsetLoading: '…',
  charsetUnknown: '—',
  unset: 'Niet ingesteld',
  statusActive: 'Actief',
  statusInactive: 'Inactief',
  noDefinition: 'Deze leverancier heeft nog geen importdefinitie.',
  definitionSelectLabel: 'Importdefinitie',
  versionSelectLabel: 'Andere versie bekijken',
  versionOption: (n: number, status: string) => `Versie ${n} (${status})`,
  headingFor: (name: string, n: number, status: string) =>
    `Zo leest CatalogImport het bestand van ${name}, versie ${n} (${status})`,
  draftBanner: 'Dit is een conceptversie: ze is nog niet actief en wordt nog nergens gebruikt.',
  noVersions: 'Deze importdefinitie heeft nog geen versies.',
  reusableNote:
    'Dit is een herbruikbare importdefinitie. Ze krijgt zelf nooit een koppeling of levering; wijzig ze via Importdefinities.',
  edit: 'Bewerken',
  closeEdit: 'Bewerken sluiten',
  editHint: 'Bewerk een conceptversie, activeer ze of bekijk de volledige details.',
  sections: {
    file: 'Bestandsopbouw',
    identity: 'Herkenning van het artikel',
    fields: 'Velden',
    filters: 'Filters',
    translations: 'Vertalingen',
  },
  fieldsHead: { field: 'Veld', source: 'Bron', operation: 'Bewerking', required: 'Verplicht' },
  yes: 'ja',
  no: 'nee',
  unknown: '—',
  notApplicable: 'Niet van toepassing',
  notUsed: 'Niet gebruikt',
  noFilters: 'Geen filters: alle regels worden verwerkt.',
  noTranslations: 'Geen vertalingen in deze versie.',
  noFields: 'Geen velden in deze versie.',
  fixedValue: (value: string) => `vaste waarde ${value}`,
  translationHead: { source: 'Waarde in bestand', target: 'Wordt' },
  translationsFor: (field: string) => `Vertaling voor ${field}`,
  basePrice: 'Prijs',
  versionsTitle: 'Versies',
  versionsEmpty: 'Geen versies voor deze importdefinitie.',
  view: 'Bekijken',
  viewLabel: (n: number) => `Bekijken: versie ${n}`,
  editAndActivate: 'Bewerken en activeren',
  versionApproved: (when: string, who: string | null) => (who === null ? `goedgekeurd op ${when}` : `goedgekeurd op ${when} door ${who}`),
  versionFromLegacy: 'Overname uit legacy',
  versionBasedOn: (n: number) => `gebaseerd op versie ${n}`,
  linksEmpty: 'Deze leverancier heeft nog geen koppelingen.',
  linkLibrary: (code: string) => `Bibliotheek ${code}`,
  linkSupplier: (code: string) => `leverancier ${code}`,
  linkActive: 'Actief',
  linkInactive: 'Inactief',
  tasksTitle: 'Taken',
  noTaskYet: 'Nog geen taak: zonder taak kan er geen levering opgeladen worden.',
  taskNotReady: 'Niet klaar: versie niet geactiveerd',
  taskReady: 'Klaar',
  taskOff: 'Niet actief',
  lastRun: (finished: string) => `laatste inlezing ${finished}`,
  noRun: 'nog geen inlezing',
  check: 'Controleren',
  checkTitle: 'Is deze koppeling klaar? Checklist, proefinlezing en activeren.',
  technicalIds: {
    organisation: 'Organisatie-id',
    definitions: 'Definitie-id',
    versions: 'Versie-ids',
    links: 'Koppeling-ids',
    tasks: 'Taak-ids',
    selectedVersion: 'Gekozen versie-id',
  },
} as const;
