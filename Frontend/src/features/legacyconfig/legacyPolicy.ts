/**
 * Pure regels van het overnamescherm (S9): welke volgorde het rapport heeft, hoe een rapportcode een woordenboekcode
 * wordt en wanneer overnemen mag. Geen React, geen fetch.
 */

import { ALLOWED, denied, type Gate } from '../../actor/gate.ts';
import { LEGACY_REPORT_STATUSES, type LegacyApplyChoices, type LegacyConfigReport, type LegacyReportItem } from '../../api/types.ts';

/** De keuzes zoals het formulier ze bijhoudt: een lege tekst betekent "niet gekozen". */
export type LegacyChoicesForm = { definitionCodeOverride: string; charsetWhenUndeclared: string; libraryCode: string };

export const NO_CHOICES: LegacyChoicesForm = { definitionCodeOverride: '', charsetWhenUndeclared: '', libraryCode: '' };

/** Enkel de ingevulde keuzes (getrimd), zoals PREVIEW en APPLY ze verwachten; een lege keuze wordt niet meegestuurd. */
export function toChoices(form: LegacyChoicesForm): LegacyApplyChoices {
  const choices: LegacyApplyChoices = {};
  if (form.definitionCodeOverride.trim() !== '') choices.definitionCodeOverride = form.definitionCodeOverride.trim();
  if (form.charsetWhenUndeclared.trim() !== '') choices.charsetWhenUndeclared = form.charsetWhenUndeclared.trim();
  if (form.libraryCode.trim() !== '') choices.libraryCode = form.libraryCode.trim();
  return choices;
}

/** De sleutel van een rapportcode in het woordenboek: de berekeningscodes (`LEGACY_CALC_<n>_NOT_MIGRATED`) delen één ingang. */
export function reportCodeKey(code: string): string {
  return /^LEGACY_CALC_.+_NOT_MIGRATED$/.test(code) ? 'LEGACY_CALC_NOT_MIGRATED' : code;
}

export type ReportGroup = { status: string; items: LegacyReportItem[] };

/** Het rapport per soort punt, blokkerend eerst; een onbekende soort komt als laatste en blijft zichtbaar. */
export function groupReportItems(items: readonly LegacyReportItem[]): ReportGroup[] {
  const known: string[] = [...LEGACY_REPORT_STATUSES];
  const unknown = [...new Set(items.map((item) => item.status).filter((status) => !known.includes(status)))];
  return [...known, ...unknown]
    .map((status) => ({ status, items: items.filter((item) => item.status === status) }))
    .filter((group) => group.items.length > 0);
}

/**
 * Mag overnemen? Enkel een ontvangst met status `RECEIVED` én een rapport zonder blokkerend punt. De server controleert
 * alles opnieuw (409 `LEGACY_RECEIPT_NOT_APPLICABLE`, `LEGACY_PREVIEW_STALE`, `LEGACY_APPLY_BLOCKED`).
 */
export function applyGate(receiptStatus: string, report: LegacyConfigReport | null): Gate {
  if (receiptStatus !== 'RECEIVED') {
    return denied('Alleen een ontvangst met de status "Ontvangen" kan overgenomen worden.', 'LEGACY_RECEIPT_NOT_APPLICABLE');
  }
  if (report === null) {
    return denied('De controle vooraf is er nog niet; wacht tot ze geladen is.');
  }
  if (report.outcome !== 'APPLICABLE') {
    return denied('Er zijn blokkerende punten in de controle vooraf; los ze eerst op of maak een keuze.', 'LEGACY_APPLY_BLOCKED');
  }
  return ALLOWED;
}

/** Mag verwerpen? Enkel een ontvangst met status `RECEIVED` (de server controleert: 409 `LEGACY_RECEIPT_NOT_APPLICABLE`). */
export function discardPolicyGate(receiptStatus: string): Gate {
  if (receiptStatus !== 'RECEIVED') {
    return denied('Alleen een ontvangst met de status "Ontvangen" kan verworpen worden.', 'LEGACY_RECEIPT_NOT_APPLICABLE');
  }
  return ALLOWED;
}
