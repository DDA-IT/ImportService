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
