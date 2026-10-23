/**
 * N1 — de namen van menu, schermtitels en kruimelpaden (beslissingslog 2026-10-23 "Naamgeving en redesign
 * inrichting: goedgekeurd"). Routes, codes, API en testids blijven ongewijzigd; enkel de zichtbare namen.
 */
export const GROUP = {
  inlezen: 'Inlezen',
  beoordelen: 'Beoordelen',
  publiceren: 'Publiceren',
  inrichting: 'Inrichting',
} as const;

export const SCREEN = {
  inlezingen: 'Inlezingen',
  bestandUploaden: 'Bestand uploaden',
  afwijkingen: 'Afwijkingen',
  publicaties: 'Publicaties',
  leveranciers: 'Leveranciers',
  leverancierToevoegen: 'Leverancier toevoegen',
  importdefinities: 'Importdefinities',
  overnameUitLegacy: 'Overname uit legacy',
} as const;

/** Eénregelige uitleg als tooltip (`title`) bij het menu-item. */
export const SCREEN_HINT = {
  inlezingen: 'Alle ingelezen leveranciersbestanden en wat er nog mee moet gebeuren.',
  bestandUploaden: 'Lees een bestand van een leverancier in en laat het controleren.',
  afwijkingen: 'Dezelfde vaststelling uit meerdere bestanden, één keer te beoordelen.',
  publicaties: 'Goedgekeurde wijzigingen samenstellen, bevriezen en naar Prodis publiceren.',
  leveranciers: 'Welke leveranciers kunnen bestanden aanleveren en wat moet nog ingericht worden.',
  leverancierToevoegen: 'Richt een nieuwe leverancier of aankoopvereniging in, stap voor stap.',
  importdefinities: 'Alle importdefinities: hoe elk leveranciersbestand gelezen wordt, eigen en herbruikbaar.',
} as const;

export const PRODUCT = 'CatalogImport';

export const TAB_PUBLICATIE = {
  inlezingen: 'Inlezingen',
  wijzigingen: 'Wijzigingen',
} as const;

export const NOT_FOUND_BACK = `Naar ${SCREEN.inlezingen}`;
