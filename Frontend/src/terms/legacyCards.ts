/**
 * Schermteksten van het kaartenoverzicht op het detail van een ontvangst van legacy-configuratie
 * (docs/decisions.md 2026-10-22). Op één plaats, naast het woordenboek, zodat een woordwijziging één aanpassing is.
 * Woordkeuze volgens NT-11d: "importsoort" en "beschrijving van het bestand", nooit "importdefinitie".
 * Codes staan hier nooit; die blijven onder "Technische details (voor support)".
 */

export const LEGACY_CARD_TITLES = {
  blocking: 'Blokkeert',
  warnings: 'Waarschuwingen',
  migrated: 'Wordt overgenomen',
  migratedApplied: 'Overgenomen',
  password: 'Wachtwoord',
  definition: 'Importsoort',
  target: 'Bestemming en verbinding',
} as const;

export const LEGACY_CARD_TEXT = {
  gridLabel: 'Samenvatting van de ontvangst',
  show: 'Tonen',
  hide: 'Verbergen',
  blockingNone: 'Niets blokkeert het overnemen',
  warningsNone: 'Geen waarschuwingen en niets dat wegvalt',
  migratedNone: 'Geen punten gemeld als overgenomen',
  passwordNone: 'Geen wachtwoord ontvangen',
  passwordReference: 'Verwijzing aanwezig',
  passwordNoReference: 'Geen verwijzing',
  definitionNone: 'Geen importsoort in de ontvangen gegevens',
  targetNone: 'Geen bestemming bekend',
  libraryUndetermined: 'bibliotheek nog niet bepaald',
  otherPoint: 'Ander punt',
  warningCount: (n: number) => `${n} let op`,
  notMigratedCount: (n: number) => `${n} niet overgenomen`,
  linesCount: (n: number) => `${n} ${n === 1 ? 'regel' : 'regels'}`,
  mapsCount: (n: number) => `${n} ${n === 1 ? 'omzetting' : 'omzettingen'}`,
  excludesCount: (n: number) => `${n} ${n === 1 ? 'uitsluiting' : 'uitsluitingen'}`,
} as const;

export const LEGACY_CHOICES_TEXT = {
  title: 'Keuzes voor de controle',
  show: 'Tonen',
  hide: 'Verbergen',
  hint: 'Wat u hier kiest, rekent de controle opnieuw en gaat mee bij het overnemen.',
} as const;

export const LEGACY_HEAD_TEXT = {
  rerun: 'Controle opnieuw uitvoeren',
  rerunLabel: 'Controle opnieuw uitvoeren met de huidige keuzes',
  prodisCheck: 'Controle tegen Prodis',
} as const;

export const LEGACY_REPORT_TEXT = {
  empty: 'Het rapport bevat geen punten.',
  searchLabel: 'Zoek in de punten',
  searchPlaceholder: 'Zoek op woord of onderdeel',
  statusFilterLabel: 'Filter op soort punt',
  all: 'Alle',
  noMatch: 'Geen punten die bij de zoekopdracht passen.',
  legacyPart: 'Onderdeel in legacy',
  showParts: (n: number) => `Toon de ${n} onderdelen`,
} as const;
