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
  moreOptions: 'Meer opties: extra velden, filters en activeren',
  moreOptionsHint: 'Extra velden en filters beheert u hier nog in de uitgebreide weergave; daar activeert u het concept ook.',
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
