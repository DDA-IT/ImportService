/**
 * E1 — de woorden van de nieuwe bewerkervaring "Bestand en velden" (beslissingslog 2026-10-23 "Nieuwe bewerkervaring ...",
 * mock `docs/mock/setup-redesign-mock.html`): het Velden-grid, de schakelaar Bekijken|Bewerken en het zijpaneel voor
 * de herkenning en de basiskolommen. Op één plaats naast de andere schermteksten; geen codes of ruwe ids.
 */

export type IdentityRowCode = 'SUPPLIER' | 'SUPPLIER_GROUP' | 'SUPPLIER_REFERENCE' | 'DISCOUNT_CODE';

export const EDITOR_TEXT = {
  gridLabel: 'Velden van deze versie',
  groups: {
    identity: 'Herkenning van het artikel',
    basis: 'Basis',
    extra: (n: number) => `Extra velden (${n})`,
  },
  head: {
    field: 'Veld',
    source: 'Bron',
    operation: 'Bewerking',
    critical: 'Kritiek',
    required: 'Verplicht',
    status: 'Status',
    actions: 'Acties',
  },
  identityLabels: {
    SUPPLIER: 'Leverancier',
    SUPPLIER_GROUP: 'Groep',
    SUPPLIER_REFERENCE: 'Referentie',
    DISCOUNT_CODE: 'Kortingscode',
  } satisfies Record<IdentityRowCode, string>,
  statusOk: 'Oké',
  statusMissing: 'Ontbreekt',
  noOperation: 'Geen bewerking',
  translationChip: (n: number) => `Vertaling (${n})`,
  notCritical: '—',
  uppercaseLabel: (on: boolean) => `Hoofdletters negeren: ${on ? 'ja' : 'nee'}`,
  uppercaseChange: 'Hoofdletters negeren',

  // Schakelaar en alleen-lezen
  switchLabel: 'Weergave van deze versie',
  view: 'Bekijken',
  edit: 'Bewerken',
  frozen: (status: 'ACTIVE' | 'SUPERSEDED' | 'OTHER') =>
    status === 'ACTIVE'
      ? 'Deze versie is actief en kan niet gewijzigd worden.'
      : status === 'SUPERSEDED'
        ? 'Deze versie is vervangen en kan niet gewijzigd worden.'
        : 'Deze versie kan niet gewijzigd worden.',
  openDraft: (n: number | null) => (n === null ? 'Open het concept' : `Open het concept (versie ${n})`),
  editRowLabel: (field: string) => `Wijzigen: ${field}`,
  saved: 'Bewaard in het concept',

  // Zijpaneel
  panelTitle: (field: string) => `Wijzigen: ${field}`,
  panelClose: 'Paneel sluiten',
  apply: 'Toepassen',
  cancel: 'Annuleren',
  applying: 'Bezig…',
  discardTitle: 'Wijzigingen weggooien?',
  discardBody: 'U heeft wijzigingen in dit paneel die nog niet bewaard zijn.',
  discardConfirm: 'Weggooien',
  discardKeep: 'Verder bewerken',
  nothingChanged: 'Er is niets gewijzigd.',
  sourceLabel: 'Bron',
  sourceColumn: 'Kolom uit het bestand',
  sourceFixed: 'Vaste waarde',
  sourceNone: 'Niet gebruikt',
  columnLabel: 'Kolom',
  columnHint: 'De naam of het nummer van de kolom, zoals die in het bestand staat.',
  clearHint: 'Laat leeg om dit veld niet meer te gebruiken.',
  fixedSupplierLabel: 'Vaste leverancier',
  fixedGroupLabel: 'Vaste groep',
  identityWarning:
    'Deze wijziging raakt hoe een artikel herkend wordt. De herkenning verandert daardoor, waardoor élk bestaand ' +
    'artikel van deze importdefinitie als nieuw artikel terugkomt (een massale aanmaak). Deze keuze heeft bewust geen ' +
    'standaardwaarde.',
  identityAcknowledge: 'Ja, ik weet dat elk bestaand artikel hierna als nieuw artikel terugkomt',
  uppercaseSwitch: 'Hoofdletters negeren bij het herkennen van een artikel',
  required: 'Dit veld is verplicht en mag niet leeg zijn.',
} as const;

/** Teksten van de zoekende keuzelijst voor Prodis-waarden (leverancier en groep). */
export const COMBOBOX_TEXT = {
  supplierPlaceholder: 'Zoek op nummer of naam',
  groupPlaceholder: 'Kies of typ een groep',
  noResults: 'Geen resultaten in Prodis',
  loading: 'Bezig met zoeken…',
  chosen: (label: string) => `Gekozen: ${label}`,
  notFound: 'Niet gevonden in Prodis — wordt bij het bewaren gecontroleerd',
  unavailable:
    'De lijst van Prodis is nu niet beschikbaar (Prodis is niet ingesteld of niet bereikbaar). Typ de waarde zelf; ' +
    'bij het bewaren wordt ze gecontroleerd of er komt een waarschuwing dat ze niet gecontroleerd is.',
  supplierUnknownForGroups:
    'Deze leverancier bestaat niet in Prodis, dus er is geen lijst met groepen. Typ de groep zelf.',
  noSupplierForGroup: 'Er is geen leverancier bekend om groepen van op te zoeken. Typ de groep zelf.',
  optionLabel: (number: string, name: string | null) => (name ? `${number} · ${name}` : number),
} as const;

/**
 * E2 — de woorden van het zijpaneel voor een extra veld (mapping) en van "Veld toevoegen" (beslissingslog 2026-10-23
 * "Nieuwe bewerkervaring", mock `docs/mock/setup-redesign-mock.html`). De namen van de bewerkingen komen van de mock en het
 * ketenvak waarin de server ze toelaat; de bewerking zelf blijft die van `transformKind` in het woordenboek.
 */
export const MAPPING_TEXT = {
  sourceLabel: 'Bron',
  sourceColumn: 'Kolom uit het bestand',
  sourceFixed: 'Vaste waarde',
  sourceNone: 'Niet gebruiken',
  columnNumber: 'Kolomnummer',
  columnName: 'Kolomnaam',
  columnNumberHint: 'Het nummer van de kolom in het bestand (de eerste kolom is 1).',
  columnNameHint: 'De naam van de kolom, zoals die in de kopregel van het bestand staat.',
  fixedValueLabel: 'Vaste waarde',
  fixedValueHint: 'Elk artikel krijgt deze waarde, wat er ook in het bestand staat.',
  operationLabel: 'Bewerking',
  ops: {
    cut: 'Uitknippen',
    fix: 'Voorvoegsel/achtervoegsel',
    upper: 'Hoofdletters',
    translate: 'Vertaling',
    divide: 'Delen door',
    multiply: 'Vermenigvuldigen met',
    percentage: 'Percentage',
  },
  opsHint: 'Uitknippen en voor-/achtervoegsel kunnen samen met één van de andere bewerkingen; die andere sluiten elkaar uit.',
  unsupportedOps: (labels: string[]) =>
    `Dit veld heeft ook de bewerking ${labels.join(', ')}. Die kan in dit paneel nog niet gewijzigd worden en blijft ongewijzigd bewaard.`,
  settings: {
    prefix: 'Voorvoegsel',
    suffix: 'Achtervoegsel',
    start: 'Uitknippen vanaf positie (1 = het begin)',
    length: 'Aantal tekens (leeg = de rest)',
    operandDivide: 'Delen door (getal)',
    operandMultiply: 'Vermenigvuldigen met (getal)',
    operandField: 'Of: een kolom',
    base: 'Percentage van (getal)',
    baseField: 'Of: een kolom',
    caseSensitive: 'Hoofdlettergevoelig',
  },
  translationLabel: 'Vertaling: waarde in bestand → waarde in Prodis',
  onMissLabel: 'Bij een onbekende waarde',
  onMissReject: 'Verwerpen',
  onMissKeep: 'Onveranderd laten',
  criticalLabel: 'Kritiek',
  critical: 'Kritiek',
  notCritical: 'Niet kritiek',
  requiredLabel: 'Verplicht',
  yes: 'Ja',
  no: 'Nee',
  more: 'Meer',
  moreLabels: {
    maxLength: 'Maximale lengte',
    decimalScale: 'Aantal decimalen',
    zeroAllowed: 'Nul toegestaan',
    negativeAllowed: 'Negatief toegestaan',
    defaultValue: 'Standaardwaarde (als de kolom leeg is)',
    expectedPosition: 'Verwachte positie in het bestand',
  },
  unusedButton: 'Niet meer gebruiken',
  unusedTitle: 'Dit veld niet meer gebruiken?',
  unusedBody: (field: string) =>
    `${field} verhuist naar "Nog niet gekoppeld". U kunt het later opnieuw koppelen.`,
  unusedBookmarkNote:
    'Steunt een invulpunt van dit concept nog op dit veld, dan weigert de server en wordt er niets verwijderd.',
  unusedConfirm: 'Niet meer gebruiken',
  unusedKeep: 'Behouden',
  problems: 'Los eerst dit op:',
  targetInUse: 'Dit veld is al gekoppeld in deze versie. Wijzig de bestaande rij in plaats van het opnieuw toe te voegen.',

  // Veld toevoegen
  addButton: 'Veld toevoegen',
  addTitle: 'Veld toevoegen',
  addTitleFor: (field: string) => `Veld toevoegen: ${field}`,
  searchLabel: 'Zoek een veld',
  searchPlaceholder: 'Bijvoorbeeld EAN',
  noResults: 'Geen velden gevonden.',
  groupLabels: { prices: 'Prijzen', references: 'Referenties', other: 'Overig' },
  alreadyMapped: 'al gekoppeld',
  choose: 'Kiezen',
  chooseLabel: (field: string) => `Kiezen: ${field}`,
  back: 'Terug naar de lijst',
  prodisManaged: 'Wordt in Prodis beheerd; een levering overschrijft dit niet.',
  unmappedGroup: (n: number) => `Nog niet gekoppeld (${n})`,
  notMapped: 'Niet gekoppeld',
  map: 'Koppelen',
  mapLabel: (field: string) => `Koppelen: ${field}`,
  vkpGroup: 'Verkoopprijzen 1–5 (%)',
  vkpHint: 'Alle vijf hebben dezelfde bron en bewerking.',
  vkpDiffers: 'Verschilt',
  expand: 'Uitklappen',
  collapse: 'Inklappen',
} as const;

/**
 * E3 — de woorden van de sectie Filters (zinnen), het zijpaneel om een filter toe te voegen of te wijzigen en de
 * ankerchips bovenaan "Bestand en velden" (beslissingslog 2026-10-23 "Nieuwe bewerkervaring", mock
 * `docs/mock/setup-redesign-mock.html`). De namen van de uitkomsten en vergelijkingen blijven die van het woordenboek
 * (`filterOutcome`, `filterOperator`); hier staan de zinsdelen en de keuzes van "Meer".
 */
export const FILTER_TEXT = {
  heading: 'Filters',
  none: 'Geen filters: alle regels worden verwerkt.',
  heads: {
    EXCLUDE: 'Sla regels over waar',
    REJECT: 'Verwerp regels waar',
    INCLUDE: 'Neem alleen regels mee waar',
  },
  unknownHead: 'Pas een filter toe op regels waar',
  column: (reference: string, kind: string) => (kind === 'COLUMN_INDEX' ? `kolom ${reference}` : `kolom '${reference}'`),
  isEmpty: 'is leeg',
  valueQuoted: (value: string) => `"${value}"`,
  and: ' én ',
  groupSuffix: '(alle voorwaarden samen)',
  suffixes: {
    caseSensitive: 'hoofdlettergevoelig',
    caseSensitiveSome: 'hoofdlettergevoelig bij sommige voorwaarden',
    noTrim: 'spaties blijven staan',
    nullBehaviour: {
      REJECT: 'lege cel: regel verwerpen',
      COMPARE_AS_EMPTY: 'lege cel: telt als lege tekst',
    },
    missingColumn: {
      EXCLUDE: 'ontbrekende kolom: alle regels overslaan',
      REJECT: 'ontbrekende kolom: alle regels verwerpen',
    },
  },
  rowLabel: (n: number) => `filter ${n}`,
  groupLabel: (n: number) => `uitsluitingsgroep ${n}`,
  change: 'Wijzigen',
  changeLabel: (what: string) => `Wijzigen: ${what}`,
  remove: 'Verwijderen',
  removeLabel: (what: string) => `Verwijderen: ${what}`,
  add: 'Filter toevoegen',
  removeTitle: 'Filter verwijderen?',
  removeGroupTitle: 'Uitsluitingsgroep verwijderen?',
  removeBody: 'Dit filter wordt uit dit concept verwijderd. Andere versies blijven ongemoeid.',
  removeGroupBody: 'Alle voorwaarden van deze groep worden uit dit concept verwijderd. Andere versies blijven ongemoeid.',
  removeBookmarkNote:
    'Steunt een invulpunt van dit concept nog op dit filter, dan weigert de server en wordt er niets verwijderd.',
  removeConfirm: 'Verwijderen',
  removeKeep: 'Behouden',
  removePartial: (done: number, total: number) =>
    `Er zijn ${done} van de ${total} voorwaarden verwijderd; de rest staat nog in de groep. Verwijder de resterende voorwaarden zelf of maak de groep opnieuw aan.`,

  // Zijpaneel
  panelAddTitle: 'Filter toevoegen',
  panelEditTitle: (n: number) => `Wijzigen: filter ${n}`,
  previewLabel: 'Zo leest het filter',
  outcomeLabel: 'Uitkomst',
  columnNumberLabel: 'Kolomnummer',
  columnNameLabel: 'Kolomnaam',
  columnNumberHint: 'Het nummer van de kolom in het bestand (de eerste kolom is 1).',
  columnNameHint: 'De naam van de kolom, zoals die in de kopregel van het bestand staat.',
  operatorLabel: 'Vergelijking',
  valueLabel: 'Waarde',
  valueHint: 'Waarmee de inhoud van de kolom vergeleken wordt. Voor een lege cel gebruikt u een uitsluitingsgroep.',
  more: 'Meer',
  caseSensitiveLabel: 'Hoofdlettergevoelig',
  nullBehaviourLabel: 'Als de cel leeg is',
  nullBehaviourOptions: {
    EXCLUDE: 'De regel overslaan',
    REJECT: 'De regel verwerpen',
    COMPARE_AS_EMPTY: 'Vergelijken als lege tekst',
  },
  missingColumnLabel: 'Als de kolom niet in het bestand staat',
  missingColumnOptions: {
    BLOCK: 'De levering blokkeren',
    EXCLUDE: 'Alle regels overslaan',
    REJECT: 'Alle regels verwerpen',
  },
  problems: 'Los eerst dit op:',
  problemColumn: 'Vul de kolom in.',
  problemColumnLong: 'De kolom is te lang (hoogstens 50 tekens).',
  problemValue: 'Vul de waarde in.',
  problemValueLong: 'De waarde is te lang (hoogstens 500 tekens).',
  bothExist:
    'Het nieuwe filter is toegevoegd, maar het oude kon niet verwijderd worden. Beide filters staan nu in het concept: ' +
    'verwijder het oude filter zelf, anders gelden ze allebei.',
  close: 'Sluiten',
} as const;

/** E3 — de ankerchips en de kleine secties onder het grid. */
export const SECTION_TEXT = {
  navLabel: 'Naar sectie',
  chips: {
    fields: (n: number) => `Velden ${n}`,
    filters: (n: number) => `Filters ${n}`,
    translations: (n: number) => `Vertalingen ${n}`,
    file: 'Bestandsopbouw',
    thresholds: 'Drempels',
    bookmarks: (n: number) => `Invulpunten ${n}`,
  },
  translationsNone: 'Geen vertalingen in deze versie.',
  translationMeta: (count: number, keep: boolean) =>
    `${count} ${count === 1 ? 'waarde' : 'waarden'} · bij een onbekende waarde: ${keep ? 'onveranderd laten' : 'verwerpen'}`,
  changeAtField: 'Wijzigen bij het veld',
  changeAtFieldLabel: (field: string) => `Wijzigen bij het veld: ${field}`,
  thresholdsSummary: 'Drempels en prijscontrole bekijken',
  bookmarksHeading: 'Invulpunten',
  bookmarksNone: 'Deze versie heeft geen invulpunten.',
  bookmarksNote: 'Invulpunten bekijkt u hier alleen; de waarden stelt u per koppeling in.',
  bookmarkRequired: 'verplicht',
  bookmarkOptional: 'optioneel',
  bookmarkValuesHeading: 'Ingevulde vaste waarden',
  bookmarkValueNoDeclaration: 'Invulpunt zonder declaratie',
  bookmarkFilled: (by: string, at: string) => `ingevuld door ${by} op ${at}`,
} as const;

/** E6 — de sectie Geavanceerd (alleen-lezen): kritiek per veld, herkomst en goedkeuring, technische details. */
export const ADVANCED_TEXT = {
  heading: 'Geavanceerd',
  summary: 'Kritiek per veld, herkomst en technische details bekijken',
  criticalityHeading: (n: number) => `Kritiek per veld aangepast (${n})`,
  criticalityNone: 'Voor deze versie is de kritiek van geen enkel veld apart aangepast.',
  criticalityNote:
    'Hier bekijkt u alleen welke velden een eigen kritiek hebben; de kritiek van een extra veld wijzigt u in het paneel van dat veld.',
  originHeading: 'Herkomst en goedkeuring',
  createdAt: 'Aangemaakt op',
  createdBy: 'Aangemaakt door',
  updatedAt: 'Laatst gewijzigd op',
  approvedAt: 'Goedgekeurd op',
  approvedBy: 'Goedgekeurd door',
  hashNote:
    'De technische herkomstgegevens (versienummers en vingerafdrukken) staan hieronder onder «Technische details». ' +
    'Ze dekken alleen de basisinstellingen — niet de extra velden, filters, kritiek per veld, drempels of het prijsbeleid. ' +
    'Twee versies die enkel daarin verschillen, dragen dus dezelfde samengestelde vingerafdruk; gebruik hem nooit als ' +
    'antwoord op «zijn deze versies gelijk?».',
} as const;

/** E4 — de conceptbalk, het verschil met de basisversie ("Toon wijzigingen") en de impactbanner voor een gedeelde definitie. */
export const CONCEPT_TEXT = {
  barLabel: 'Conceptbalk',
  title: (version: number, basedOn: number | null, count: number | null) =>
    [
      `Concept · versie ${version}`,
      basedOn === null ? null : `gebaseerd op versie ${basedOn}`,
      count === null ? null : `${count} ${count === 1 ? 'wijziging' : 'wijzigingen'}`,
    ]
      .filter((part) => part !== null)
      .join(' · '),
  showChanges: 'Toon wijzigingen',
  hideChanges: 'Verberg wijzigingen',
  baseFailed: 'De basisversie kon niet geladen worden, dus de wijzigingen kunnen niet getoond worden.',
  reasonLabel: 'Reden van deze versie:',
  reasonNone: 'Geen reden opgegeven',
  reasonChange: 'Reden van deze versie wijzigen',
  reasonInput: 'Reden van deze versie',
  reasonSave: 'Opslaan',
  reasonCancel: 'Annuleren',
  steps: ['① Aanpassen', '② Controleren', '③ Activeren'] as const,
  stepsLabel: 'Stappen',
  trialButton: 'Controleren met testbestand',
  activateButton: 'Activeren',
  note: 'Elke wijziging wordt meteen in het concept bewaard. Het concept is nog niet actief.',
  noTrialWarning:
    'Er is met deze versie nog geen geslaagde controle met een testbestand gedaan. Activeren kan, maar controleer bij voorkeur eerst.',
  shared: (names: string[]) =>
    `Deze importdefinitie wordt gedeeld door ${names.length} koppelingen (${names.join(', ')}). ` +
    `Activeren van een nieuwe versie geldt voor alle ${names.length}.`,
} as const;

export const DIFF_TEXT = {
  panelLabel: (base: number) => `Wijzigingen ten opzichte van versie ${base}`,
  heading: (base: number) => `Wijzigingen ten opzichte van versie ${base}`,
  none: (base: number) => `Geen wijzigingen: dit concept is gelijk aan versie ${base}.`,
  was: 'was',
  now: 'nu',
  kinds: { added: 'Nieuw', changed: 'Gewijzigd', removed: 'Niet meer in deze versie' },
  chipAdded: 'Nieuw',
  chipChanged: 'Gewijzigd',
  removedStrip: (labels: string[]) => `Niet meer in deze versie: ${labels.join(', ')}`,
  notFilled: 'niet ingevuld',
  notUsed: 'niet gebruikt',
  required: 'verplicht',
  yes: 'ja',
  no: 'nee',
  fixed: (value: string) => `vaste waarde ${value}`,
  column: (value: string) => `kolom ${value}`,
  headerLine: (n: number) => `ja (regel ${n})`,
  filterLabel: 'Filter',
  sections: {
    identity: 'Herkenning',
    basis: 'Basis',
    field: 'Veld',
    filter: 'Filter',
    file: 'Bestandsopbouw',
    thresholds: 'Drempels',
  },
} as const;

/** E4 — Bestandsopbouw en Drempels inline bewerken. */
export const INLINE_EDIT_TEXT = {
  change: 'Wijzigen',
  changeFile: 'Bestandsopbouw wijzigen',
  changeThresholds: 'Drempels wijzigen',
  save: 'Opslaan',
  saving: 'Bezig…',
  cancel: 'Annuleren',
  nothingChanged: 'Er is niets gewijzigd.',
  notClearable: 'Kan hier niet leeg gemaakt worden',
  notClearableProblem: (field: string) =>
    `"${field}" kan hier niet leeg gemaakt worden. Er is niets verstuurd; vul een getal in of annuleer.`,
  fileType: 'Bestandstype',
  fileTypeFixed: 'Het bestandstype kan niet gewijzigd worden.',
  yes: 'Ja',
  no: 'Nee',
  advanced: 'Geavanceerd',
  advancedWarning:
    'Wijzig de herkenningsversie alleen als u zeker weet wat u doet: ze bepaalt hoe een artikel herkend wordt en is na de ' +
    'eerste aanvaarde levering niet meer te wijzigen.',
  thresholdsIntro: 'Leeg laten betekent: ongewijzigd laten.',
  severityLabel: 'Ernst van een prijsafwijking',
  savedFile: 'Bestandsopbouw bewaard in het concept',
  savedThresholds: 'Drempels bewaard in het concept',
} as const;

/** E4 — het testbestand in de conceptbalk (hergebruikt de proefinlezing). */
export const TRIAL_BOX_TEXT = {
  heading: 'Controleren met testbestand',
  intro:
    'Kies een bestand van de leverancier om te zien hoe deze versie het zou lezen. Er wordt niets opgeslagen of gepubliceerd.',
  fileLabel: 'Testbestand',
  start: 'Controle starten',
  running: 'Bezig…',
  noFile: 'Kies eerst een bestand om te testen.',
  close: 'Sluiten',
  fileName: (name: string) => `Testbestand: ${name}`,
} as const;
