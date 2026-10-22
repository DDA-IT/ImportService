/**
 * G2c-4 — de pure kant van de dialoog "Uitsluitingsgroep toevoegen" (`docs/design/g2-design.md` §G2c): het voorgestelde
 * groepsnummer, de vormvalidatie, de bodies van de POST-calls en het groeperen van de filterlijst.
 *
 * Elke rij van een groep is `EXCLUDE` + `COMPARE_AS_EMPTY` met `EQUALS` (S8f: enkel zo mag een rij in een groep). Een
 * lege vergelijkwaarde is toegelaten en betekent "de kolom is leeg" (legacy PSIMPEXCL). De client controleert enkel de
 * vorm; `MATCH_GROUP_INVALID`, `CONFIG_FILTER_INVALID` en `COMPARE_VALUE_REQUIRED` beslist de server.
 */

import type { CreateFilterRequest } from '../../api/setupRevisions.ts';
import type { FilterView } from '../../api/types.ts';

export const MAX_GROUP_ROWS = 4;
export const MAX_FILTER_COLUMN_LENGTH = 50;
export const MAX_COMPARE_VALUE_LENGTH = 500;

export type GroupRow = {
  sourceReference: string;
  /** Leeg is toegelaten: de rij betekent dan "deze kolom is leeg". */
  compareValue: string;
  caseSensitive: boolean;
  trimBeforeCompare: boolean;
};

export function emptyGroupRow(): GroupRow {
  return { sourceReference: '', compareValue: '', caseSensitive: false, trimBeforeCompare: true };
}

/** Het hoogste bestaande groepsnummer + 1 (1 als er nog geen groep is). */
export function suggestGroupNumber(filters: readonly FilterView[]): number {
  return filters.reduce((max, filter) => Math.max(max, filter.matchGroup ?? 0), 0) + 1;
}

/** Het groepsnummer uit het invoerveld, of `null` als het geen geheel getal > 0 is. */
export function parseGroupNumber(text: string): number | null {
  const trimmed = text.trim();
  if (!/^\d+$/.test(trimmed)) {
    return null;
  }
  const value = Number(trimmed);
  return Number.isSafeInteger(value) && value > 0 && value <= 2147483647 ? value : null;
}

/** Vormfouten (nooit inhoud): een lege lijst betekent "mag verstuurd worden". */
export function groupProblems(groupText: string, rows: readonly GroupRow[]): string[] {
  const problems: string[] = [];
  if (parseGroupNumber(groupText) === null) {
    problems.push('Het groepsnummer moet een geheel getal groter dan 0 zijn.');
  }
  if (rows.length < 1 || rows.length > MAX_GROUP_ROWS) {
    problems.push(`Een groep heeft 1 tot ${MAX_GROUP_ROWS} voorwaarden.`);
  }
  rows.forEach((row, index) => {
    const nr = index + 1;
    if (row.sourceReference.trim() === '') {
      problems.push(`Voorwaarde ${nr}: vul de kolom in.`);
    } else if (row.sourceReference.trim().length > MAX_FILTER_COLUMN_LENGTH) {
      problems.push(`Voorwaarde ${nr}: de kolom is te lang (hoogstens ${MAX_FILTER_COLUMN_LENGTH} tekens).`);
    }
    if (row.compareValue.length > MAX_COMPARE_VALUE_LENGTH) {
      problems.push(`Voorwaarde ${nr}: de vergelijkwaarde is te lang (hoogstens ${MAX_COMPARE_VALUE_LENGTH} tekens).`);
    }
  });
  return problems;
}

/** De body van één POST. De volgnummers kiest de server (hoogste + 1), dus de rijen komen in invoervolgorde. */
export function buildGroupRequest(row: GroupRow, group: number, actor: string): CreateFilterRequest {
  return {
    sequenceNumber: null,
    sourceReference: row.sourceReference.trim(),
    operator: 'EQUALS',
    compareValue: row.compareValue,
    outcome: 'EXCLUDE',
    caseSensitive: row.caseSensitive,
    trimBeforeCompare: row.trimBeforeCompare,
    nullBehaviour: 'COMPARE_AS_EMPTY',
    createdBy: actor,
    matchGroup: group,
  };
}

export type FilterListEntry =
  | { kind: 'single'; filter: FilterView }
  | { kind: 'group'; group: number; filters: FilterView[] };

/** Rijen met dezelfde groep komen samen op de plaats van de eerste rij van die groep; losse rijen blijven zoals ze zijn. */
export function groupFilters(filters: readonly FilterView[]): FilterListEntry[] {
  const entries: FilterListEntry[] = [];
  const groups = new Map<number, FilterView[]>();
  for (const filter of filters) {
    if (filter.matchGroup == null) {
      entries.push({ kind: 'single', filter });
      continue;
    }
    const existing = groups.get(filter.matchGroup);
    if (existing !== undefined) {
      existing.push(filter);
    } else {
      const rows = [filter];
      groups.set(filter.matchGroup, rows);
      entries.push({ kind: 'group', group: filter.matchGroup, filters: rows });
    }
  }
  return entries;
}
