/**
 * Schermteksten van de kolom "Uit legacy" in het leesbeeld van een versie (docs/decisions.md 2026-10-23, B2/F2).
 * Het is het bedoelde doel van de legacy-regel, geen bewijs van wat de overname exact deed. Codes staan hier nooit; de
 * ruwe waarden staan onder "Technische details (voor support)".
 */

export const LEGACY_CALCULATION_TEXT = {
  add: (operand: string) => `+ ${operand}`,
  subtract: (operand: string) => `− ${operand}`,
  multiply: (operand: string) => `× ${operand}`,
  divide: (operand: string) => `÷ ${operand}`,
  percentOfColumn: (column: string) => `% van kolom ${column}`,
  percentOfColumnUnknown: '% van een andere kolom',
  translation: 'vertaling',
  translationContains: 'vertaling (bevat)',
  uppercase: 'hoofdletters',
  generic: 'berekening in legacy',
} as const;

export const LEGACY_ORIGIN_TEXT = {
  column: 'Uit legacy',
  columnAtMigration: 'Uit legacy (bij overname)',
  changedBanner: 'Herkomst van de overgenomen versie; deze versie is sindsdien gewijzigd.',
  inheritedFrom: (n: number | null) => (n === null ? 'geërfd van een eerdere versie' : `geërfd van versie ${n}`),
  templateSource: 'uit de herbruikbare importdefinitie',
  loadFailed: 'De herkomst uit legacy kon niet geladen worden; de versie zelf is wel volledig te zien.',
  linesUnavailable: 'De legacy-regels van deze versie zijn niet meer beschikbaar.',
  notTakenOverTitle: 'Niet overgenomen uit legacy',
  toLinkTitle: 'Naar de koppeling (standaard bij aanmaak)',
  toLinkReason: 'standaard bij aanmaak van de koppeling (btw-code / eenheid)',
  noTarget: 'geen doelveld',
  notInThisVersion: 'niet aanwezig in deze versie',
  receiptLink: (id: number) => `Ontvangst #${id}`,
  lineNumber: (n: number) => `regel ${n}`,
  column1: (n: number) => `kolom ${n}`,
  positionRange: (from: number, to: number) => `positie ${from}–${to}`,
  positionFrom: (from: number) => `vanaf positie ${from}`,
  xml: (path: string) => `XML ${path}`,
  fixed: (value: string) => `vaste waarde ${value}`,
  prefix: (value: string) => `voorvoegsel ${value}`,
  suffix: (value: string) => `achtervoegsel ${value}`,
  technical: {
    interfaceCode: 'Interface (legacy)',
    definitionCode: 'Importsoort (legacy)',
    receipt: 'Ontvangst',
    origin: 'Herkomst',
    originRevision: 'Versie-id van de herkomst',
    exportedAt: 'Geëxporteerd op',
    appliedAt: 'Overgenomen op',
    lines: 'Legacy-regels (ruwe waarden)',
  },
} as const;
