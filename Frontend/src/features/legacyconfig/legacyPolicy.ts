/**
 * Pure regels van het overnamescherm (S9): welke volgorde het rapport heeft, hoe een rapportcode een woordenboekcode
 * wordt en wanneer overnemen mag. Geen React, geen fetch.
 */

import { ALLOWED, denied, type Gate } from '../../actor/gate.ts';
import { term } from '../../terms/index.ts';
import { LEGACY_CARD_TEXT } from '../../terms/legacyCards.ts';
import { LEGACY_REPORT_STATUSES, type LegacyApplyChoices, type LegacyConfigReport, type LegacyReportItem } from '../../api/types.ts';

/** De keuzes zoals het formulier ze bijhoudt: een lege tekst betekent "niet gekozen". */
export type LegacyChoicesForm = {
  definitionCodeOverride: string;
  charsetWhenUndeclared: string;
  libraryCode: string;
  detachFromShared: boolean;
  detachedDefinitionCode: string;
};

export const NO_CHOICES: LegacyChoicesForm = { definitionCodeOverride: '', charsetWhenUndeclared: '',
  libraryCode: '',
  detachFromShared: false,
  detachedDefinitionCode: '',
};

/** Enkel de ingevulde keuzes (getrimd), zoals PREVIEW en APPLY ze verwachten; een lege keuze wordt niet meegestuurd. */
export function toChoices(form: LegacyChoicesForm): LegacyApplyChoices {
  const choices: LegacyApplyChoices = {};
  if (form.definitionCodeOverride.trim() !== '') choices.definitionCodeOverride = form.definitionCodeOverride.trim();
  if (form.charsetWhenUndeclared.trim() !== '') choices.charsetWhenUndeclared = form.charsetWhenUndeclared.trim();
  if (form.libraryCode.trim() !== '') choices.libraryCode = form.libraryCode.trim();
  if (form.detachFromShared) {
    choices.detachFromShared = true;
    if (form.detachedDefinitionCode.trim() !== '') choices.detachedDefinitionCode = form.detachedDefinitionCode.trim();
  }
  return choices;
}

/** G2a: het loskoppelen is te kiezen zodra het rapport een afwijkende gedeelde beschrijving meldt, of zolang het gekozen is. */
export function detachChoiceVisible(report: LegacyConfigReport | null, detachChosen: boolean): boolean {
  return detachChosen || (report?.items.some((item) => item.code === 'SHARED_DEFINITION_DIVERGED') ?? false);
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

/**
 * Vangnet voor geheime labels: de backend laat geheime labels nooit mee (`omittedSecretLabels`), maar komt er toch zo'n
 * label met een waarde binnen, dan toont het scherm de waarde niet. Zelfde patroon als de bundelparser.
 */
export const SECRET_LABEL = /pass|secret|apikey|api_key|token|_pw$/i;

/** Rapportcodes die over een wachtwoord of geheime instelling gaan. */
const SECRET_REPORT_CODES: readonly string[] = ['SECRET_LABEL_OMITTED'];

/** Hoort dit punt bij het wachtwoord? Op code, of op het label van de interface-instelling waar het over gaat. */
export function isSecretItem(item: LegacyReportItem): boolean {
  if (SECRET_REPORT_CODES.includes(item.code)) return true;
  const label = item.element.startsWith('INDET:') ? item.element.slice('INDET:'.length) : '';
  return label !== '' && SECRET_LABEL.test(label);
}

/** Nederlandse omschrijving van het legacy-onderdeel waar een punt over gaat; de ruwe code blijft in de technische details. */
export function describeLegacyPart(element: string): string {
  const numbered = /^(PSIMPDEF|PSIMPDEFMAP|PSIMPEXCL)#(\d+)$/.exec(element);
  if (numbered !== null) {
    const n = numbered[2];
    if (numbered[1] === 'PSIMPDEF') return `regel ${n} van de importsoort`;
    if (numbered[1] === 'PSIMPDEFMAP') return `omzetting ${n} van de importsoort`;
    return `uitsluiting ${n} van de importsoort`;
  }
  if (element.startsWith('INDET:')) return `interface-instelling ${element.slice('INDET:'.length)}`;
  if (element.startsWith('PSIMPDEF:')) return 'een instelling van de importsoort';
  if (element.startsWith('PSIMDHFD')) return 'de kop van de importsoort';
  if (element === 'PSIMPDEFMAP') return 'de omzettingen van de importsoort';
  if (element === 'PSIMPEXCL') return 'de uitsluitingen van de importsoort';
  if (element === 'INHEAD') return 'de kop van de interface';
  if (element === 'INDET') return 'de instellingen van de interface';
  if (element === 'PLAN:link') return 'de geplande koppeling';
  if (element === 'PLAN:task') return 'de geplande taak';
  if (element === 'PLAN:template') return 'het geplande sjabloon';
  if (element === 'PLAN:definition') return 'de geplande beschrijving van het bestand';
  if (element === 'PLAN:detach') return 'het geplande loskoppelen';
  if (element === 'CONNECTION') return 'de verbinding van de interface';
  return 'een onderdeel van legacy';
}

/** De punten van een rapport per kaart. `null` rapport = nog geen controle: alles leeg. */
export type ReportSummary = {
  blocking: LegacyReportItem[];
  warning: LegacyReportItem[];
  notMigrated: LegacyReportItem[];
  migrated: LegacyReportItem[];
  /** Waarschuwingen + niet overgenomen: de teller van de kaart "Waarschuwingen". */
  warningsTotal: number;
  firstBlocking: LegacyReportItem | null;
  secret: LegacyReportItem[];
};

export function summarizeReport(report: LegacyConfigReport | null): ReportSummary {
  const items = report?.items ?? [];
  const byStatus = (status: string) => items.filter((item) => item.status === status);
  const blocking = byStatus('BLOCKING');
  const warning = byStatus('WARNING');
  const notMigrated = byStatus('NOT_MIGRATED');
  return {
    blocking,
    warning,
    notMigrated,
    migrated: byStatus('MIGRATED'),
    warningsTotal: warning.length + notMigrated.length,
    firstBlocking: blocking[0] ?? null,
    secret: items.filter(isSecretItem),
  };
}

/** Vanaf zoveel gelijke codes in één soort wordt per code gegroepeerd (daaronder blijft elk punt een eigen kaart). */
export const GROUP_BY_CODE_THRESHOLD = 5;
/** Vanaf zoveel punten in het open paneel verschijnen zoeken en filteren. */
export const FILTER_THRESHOLD = 8;

export type ItemEntry =
  | { kind: 'single'; item: LegacyReportItem }
  | { kind: 'group'; key: string; items: LegacyReportItem[] };

/**
 * Binnen één soort punt: komt dezelfde code (de berekeningscodes tellen als één, `reportCodeKey`) méér dan
 * `threshold` keer voor, dan vormen die punten één groep; de rest blijft op de oorspronkelijke plaats staan.
 */
export function groupItemsByCode(items: readonly LegacyReportItem[], threshold: number = GROUP_BY_CODE_THRESHOLD): ItemEntry[] {
  const counts = new Map<string, number>();
  for (const item of items) {
    const key = reportCodeKey(item.code);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  const entries: ItemEntry[] = [];
  const groups = new Map<string, LegacyReportItem[]>();
  for (const item of items) {
    const key = reportCodeKey(item.code);
    if ((counts.get(key) ?? 0) <= threshold) {
      entries.push({ kind: 'single', item });
      continue;
    }
    const existing = groups.get(key);
    if (existing === undefined) {
      const created = [item];
      groups.set(key, created);
      entries.push({ kind: 'group', key, items: created });
    } else {
      existing.push(item);
    }
  }
  return entries;
}

/** Het Nederlandse woord van een punt (de toplijn van een kaart); een onbekende code wordt "Ander punt". */
export function itemTitle(item: LegacyReportItem): string {
  const resolved = term('legacyReportCode', reportCodeKey(item.code));
  return resolved.uitleg !== '' ? resolved.label : LEGACY_CARD_TEXT.otherPoint;
}
