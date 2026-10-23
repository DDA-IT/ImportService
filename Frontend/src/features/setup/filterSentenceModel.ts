/**
 * E3 — de pure kant van de sectie Filters in "Bestand en velden": een filter als Nederlandse zin (met de bijzonderheden als
 * gedempt achtervoegsel), het formulier van het zijpaneel en de body van `POST /setup/revisions/{id}/filters`.
 *
 * Er is geen PUT voor een filter (gap G2 van de beslissing "Nieuwe bewerkervaring"): wijzigen is een nieuw filter
 * toevoegen en daarna het oude verwijderen (de bouwsteen in `FilterPanel`). De client controleert enkel de vorm; de
 * inhoud (`CONFIG_FILTER_INVALID`, `COMPARE_VALUE_REQUIRED`, `MATCH_GROUP_INVALID`) beslist de server.
 */

import type { CreateFilterRequest } from '../../api/setupRevisions.ts';
import type { FilterView, RevisionDetail } from '../../api/types.ts';
import { termLabel } from '../../terms/index.ts';
import { FILTER_TEXT as TEXT } from '../../terms/setupEditor.ts';
import { basisRows, identityRows, mappingHasSource } from './fieldsGridModel.ts';
import { MAX_COMPARE_VALUE_LENGTH, MAX_FILTER_COLUMN_LENGTH, type FilterListEntry } from './filterGroupModel.ts';

export const FILTER_OUTCOMES = ['EXCLUDE', 'REJECT', 'INCLUDE'] as const;
export const FILTER_OPERATORS = ['EQUALS', 'NOT_EQUALS', 'BEGINS_WITH', 'ENDS_WITH', 'CONTAINS', 'NOT_CONTAINS'] as const;
export const NULL_BEHAVIOURS = ['EXCLUDE', 'REJECT', 'COMPARE_AS_EMPTY'] as const;
export const MISSING_COLUMN_BEHAVIOURS = ['BLOCK', 'EXCLUDE', 'REJECT'] as const;

export const DEFAULT_NULL_BEHAVIOUR = 'EXCLUDE';
export const DEFAULT_MISSING_COLUMN_BEHAVIOUR = 'BLOCK';

export type FilterSentence = {
  /** De zin zelf, zonder achtervoegsel. */
  text: string;
  /** De bijzonderheden (hoofdlettergevoelig, lege cel, ontbrekende kolom), of `null` als alles standaard is. */
  suffix: string | null;
};

function outcomeHead(outcome: string): string {
  return (TEXT.heads as Record<string, string>)[outcome] ?? TEXT.unknownHead;
}

/** `kolom 3` bij herkenning op nummer, `kolom 'Prijs'` bij herkenning op naam. */
export function columnPhrase(reference: string, referenceKind: string): string {
  return TEXT.column(reference, referenceKind);
}

function condition(filter: FilterView, referenceKind: string): string {
  const column = columnPhrase(filter.sourceReference, referenceKind);
  // In een groep betekent "gelijk aan niets" dat de kolom leeg is (legacy PSIMPEXCL).
  if (filter.matchGroup != null && filter.operator === 'EQUALS' && (filter.compareValue ?? '') === '') {
    return `${column} ${TEXT.isEmpty}`;
  }
  const operator = termLabel('filterOperator', filter.operator, 'volgens een andere vergelijking met');
  return `${column} ${operator} ${TEXT.valueQuoted(filter.compareValue ?? '')}`;
}

function suffixOf(filters: readonly FilterView[]): string | null {
  const parts: string[] = [];
  const caseCount = filters.filter((filter) => filter.caseSensitive === true).length;
  if (caseCount === filters.length && caseCount > 0) {
    parts.push(TEXT.suffixes.caseSensitive);
  } else if (caseCount > 0) {
    parts.push(TEXT.suffixes.caseSensitiveSome);
  }
  if (filters.some((filter) => filter.trimBeforeCompare === false)) {
    parts.push(TEXT.suffixes.noTrim);
  }
  // Een groepsrij is altijd "lege cel = lege tekst": dat is geen bijzonderheid om te melden.
  const single = filters.length === 1 && filters[0]!.matchGroup == null ? filters[0]! : null;
  if (single !== null) {
    const nullText = (TEXT.suffixes.nullBehaviour as Record<string, string>)[single.nullBehaviour ?? ''];
    if (nullText !== undefined) {
      parts.push(nullText);
    }
  }
  for (const filter of filters) {
    const missingText = (TEXT.suffixes.missingColumn as Record<string, string>)[filter.missingColumnBehaviour ?? ''];
    if (missingText !== undefined && !parts.includes(missingText)) {
      parts.push(missingText);
    }
  }
  return parts.length === 0 ? null : parts.join(', ');
}

/** Eén los filter als zin: "Sla regels over waar kolom 3 is niet gelijk aan "BENL"". */
export function filterSentence(filter: FilterView, referenceKind: string): FilterSentence {
  return {
    text: `${outcomeHead(filter.outcome)} ${condition(filter, referenceKind)}`,
    suffix: suffixOf([filter]),
  };
}

/** Een uitsluitingsgroep als zin: "Sla regels over waar … én … (alle voorwaarden samen)". */
export function groupSentence(filters: readonly FilterView[], referenceKind: string): FilterSentence {
  const first = filters[0];
  const head = outcomeHead(first?.outcome ?? 'EXCLUDE');
  const parts = filters.map((filter) => condition(filter, referenceKind));
  return { text: `${head} ${parts.join(TEXT.and)} ${TEXT.groupSuffix}`, suffix: suffixOf(filters) };
}

export function entrySentence(entry: FilterListEntry, referenceKind: string): FilterSentence {
  return entry.kind === 'single'
    ? filterSentence(entry.filter, referenceKind)
    : groupSentence(entry.filters, referenceKind);
}

/** De zin en het achtervoegsel als één tekst (voor een bevestigingsvraag of een melding). */
export function sentenceText(sentence: FilterSentence): string {
  return sentence.suffix === null ? sentence.text : `${sentence.text} (${sentence.suffix})`;
}

// --- het formulier van het zijpaneel ---

export type FilterForm = {
  outcome: string;
  column: string;
  operator: string;
  value: string;
  caseSensitive: boolean;
  nullBehaviour: string;
  missingColumnBehaviour: string;
};

export function emptyFilterForm(): FilterForm {
  return {
    outcome: 'EXCLUDE',
    column: '',
    operator: 'EQUALS',
    value: '',
    caseSensitive: false,
    nullBehaviour: DEFAULT_NULL_BEHAVIOUR,
    missingColumnBehaviour: DEFAULT_MISSING_COLUMN_BEHAVIOUR,
  };
}

export function formFromFilter(filter: FilterView): FilterForm {
  return {
    outcome: filter.outcome,
    column: filter.sourceReference,
    operator: filter.operator,
    value: filter.compareValue ?? '',
    caseSensitive: filter.caseSensitive === true,
    nullBehaviour: filter.nullBehaviour ?? DEFAULT_NULL_BEHAVIOUR,
    missingColumnBehaviour: filter.missingColumnBehaviour ?? DEFAULT_MISSING_COLUMN_BEHAVIOUR,
  };
}

/** Staat er in "Meer" iets anders dan de standaard (dan staat het vak meteen open)? */
export function hasNonDefaultMore(form: FilterForm): boolean {
  return (
    form.caseSensitive ||
    form.nullBehaviour !== DEFAULT_NULL_BEHAVIOUR ||
    form.missingColumnBehaviour !== DEFAULT_MISSING_COLUMN_BEHAVIOUR
  );
}

/** De zin zoals hij na het bewaren gelezen wordt, als voorbeeld in het paneel. */
export function previewSentence(form: FilterForm, referenceKind: string): FilterSentence {
  const draft: FilterView = {
    id: 0,
    revisionId: 0,
    sequenceNumber: 0,
    sourceReference: form.column.trim() === '' ? '…' : form.column.trim(),
    operator: form.operator,
    compareValue: form.value,
    outcome: form.outcome,
    caseSensitive: form.caseSensitive,
    nullBehaviour: form.nullBehaviour,
    missingColumnBehaviour: form.missingColumnBehaviour,
    matchGroup: null,
  };
  return filterSentence(draft, referenceKind);
}

/** Vormfouten (nooit inhoud); een lege lijst betekent "mag verstuurd worden". */
export function filterProblems(form: FilterForm): string[] {
  const problems: string[] = [];
  if (form.column.trim() === '') {
    problems.push(TEXT.problemColumn);
  } else if (form.column.trim().length > MAX_FILTER_COLUMN_LENGTH) {
    problems.push(TEXT.problemColumnLong);
  }
  if (form.value === '') {
    problems.push(TEXT.problemValue);
  } else if (form.value.length > MAX_COMPARE_VALUE_LENGTH) {
    problems.push(TEXT.problemValueLong);
  }
  return problems;
}

/**
 * De body van de POST. De volgnummers kiest de server (hoogste + 1). Bij een wijziging neemt het nieuwe filter de instelling
 * "spaties verwijderen" van het oude over (het paneel heeft er geen keuze voor); bij een nieuw filter geldt de serverstandaard.
 */
export function buildFilterRequest(form: FilterForm, actor: string, original: FilterView | null): CreateFilterRequest {
  const body: CreateFilterRequest = {
    sequenceNumber: null,
    sourceReference: form.column.trim(),
    operator: form.operator,
    compareValue: form.value,
    outcome: form.outcome,
    caseSensitive: form.caseSensitive,
    nullBehaviour: form.nullBehaviour,
    missingColumnBehaviour: form.missingColumnBehaviour,
    createdBy: actor,
    matchGroup: null,
  };
  if (original !== null && original.trimBeforeCompare !== undefined) {
    body.trimBeforeCompare = original.trimBeforeCompare;
  }
  return body;
}

export function sameForm(a: FilterForm, b: FilterForm): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

// --- tellingen voor de ankerchips ---

/** De velden met een bron: herkenning en basis (kolom of vaste waarde) plus de extra velden met een bron. */
export function fieldCount(revision: RevisionDetail): number {
  const core = [...identityRows(revision), ...basisRows(revision)].filter((row) => row.source.kind !== 'none').length;
  return core + revision.mappings.filter(mappingHasSource).length;
}

/** Het aantal extra velden met een vertaling. */
export function translationCount(revision: RevisionDetail): number {
  return revision.mappings.filter((mapping) => (mapping.valueMap?.length ?? 0) > 0).length;
}
