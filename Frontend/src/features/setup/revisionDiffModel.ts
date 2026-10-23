/**
 * E4 — het verschil tussen een concept en de versie waarop het gebaseerd is (beslissingslog 2026-10-23 "Nieuwe
 * bewerkervaring", O4), volledig aan de clientkant berekend uit twee `RevisionDetail`-antwoorden. Puur, zonder React.
 *
 * Wat vergeleken wordt: de extra velden (mappings) op doelveld, de filters op inhoud, en de losse instellingen
 * (herkenning, basis, bestandsopbouw, drempels en prijsbeleid). De teller is het aantal verschillen. Er is geen basisversie
 * (`null`): geen verschillen. Er wordt nooit iets gecorrigeerd of verborgen; een waarde die niet leesbaar is, staat als
 * "niet ingevuld" of "niet gebruikt".
 *
 * Een filter heeft geen vaste sleutel over versies heen (wijzigen = nieuw toevoegen en het oude verwijderen, E3). Een filter
 * dat in de basisversie niet meer voorkomt en in het concept een gelijke kolom heeft die er nog niet was, telt daarom als
 * "gewijzigd"; de rest is "nieuw" of "niet meer in deze versie".
 */

import type { FilterView, MappingView, RevisionDetail } from '../../api/types.ts';
import { termLabel } from '../../terms/index.ts';
import { DIFF_TEXT as TEXT } from '../../terms/setupEditor.ts';
import { basisRows, identityRows, mappingChips, type GridSource } from './fieldsGridModel.ts';
import { filterSentence, sentenceText } from './filterSentenceModel.ts';

export type DiffKind = 'added' | 'changed' | 'removed';
export type DiffSection = 'identity' | 'basis' | 'field' | 'filter' | 'file' | 'thresholds';

export type DiffEntry = {
  /** Doelveld (extra veld), `filter:<id>` of de sleutel van de instelling (bv. `supplier`, `delimiter`). */
  key: string;
  section: DiffSection;
  label: string;
  kind: DiffKind;
  /** De waarde in de basisversie (`null` bij Nieuw). */
  from: string | null;
  /** De waarde in het concept (`null` bij "Niet meer in deze versie"). */
  to: string | null;
};

export type ChipKind = 'added' | 'changed';

export type RevisionDiff = {
  baseRevisionNumber: number | null;
  entries: DiffEntry[];
  /** Het aantal verschillen. */
  count: number;
  /** Extra velden van het concept die nieuw of gewijzigd zijn, op doelveld. */
  fieldKinds: Record<string, ChipKind>;
  /** Losse instellingen (herkenning, basis, bestandsopbouw, drempels) die nieuw of gewijzigd zijn, op sleutel. */
  scalarKinds: Record<string, ChipKind>;
  /** Filters van het concept die nieuw of gewijzigd zijn, op id. */
  filterKinds: Record<number, ChipKind>;
  removedFields: DiffEntry[];
  removedFilters: DiffEntry[];
};

export const EMPTY_DIFF: RevisionDiff = {
  baseRevisionNumber: null,
  entries: [],
  count: 0,
  fieldKinds: {},
  scalarKinds: {},
  filterKinds: {},
  removedFields: [],
  removedFilters: [],
};

// --- extra velden ---

function mappingSignature(mapping: MappingView): string {
  return JSON.stringify([
    mapping.valueKind,
    mapping.sourceReference ?? '',
    mapping.criticality,
    mapping.required ?? null,
    mapping.fixedValue ?? null,
    mapping.defaultValue ?? null,
    mapping.expectedPosition ?? null,
    mapping.maxLength ?? null,
    mapping.decimalScale ?? null,
    mapping.zeroAllowed ?? null,
    mapping.negativeAllowed ?? null,
    mapping.transformKind ?? null,
    mapping.transformConfig ?? null,
    (mapping.steps ?? []).map((step) => [step.kind, step.config ?? null]),
    (mapping.valueMap ?? []).map((entry) => [entry.source, entry.target]),
  ]);
}

/** Een extra veld in één regel: de bron, de bewerkingen en de kritiek. Geen ruwe codes. */
export function mappingText(mapping: MappingView): string {
  const source =
    mapping.valueKind === 'SOURCE_FIELD'
      ? TEXT.column(mapping.sourceReference)
      : mapping.valueKind === 'FIXED_VALUE' && mapping.fixedValue != null
        ? TEXT.fixed(mapping.fixedValue)
        : termLabel('mappingValueKind', mapping.valueKind, 'andere herkomst').toLowerCase();
  const parts = [source, ...mappingChips(mapping).map((chip) => chip.toLowerCase())];
  parts.push(termLabel('criticality', mapping.criticality, 'andere kritiek').toLowerCase());
  if (mapping.required === true) {
    parts.push(TEXT.required);
  }
  return parts.join(' · ');
}

function fieldLabel(code: string): string {
  return termLabel('fieldKey', code, 'Ander veld');
}

function diffMappings(current: RevisionDetail, base: RevisionDetail, out: DiffEntry[], result: RevisionDiff) {
  const baseByCode = new Map(base.mappings.map((mapping) => [mapping.targetFieldCode, mapping]));
  const currentCodes = new Set(current.mappings.map((mapping) => mapping.targetFieldCode));
  for (const mapping of current.mappings) {
    const before = baseByCode.get(mapping.targetFieldCode);
    const label = fieldLabel(mapping.targetFieldCode);
    if (before === undefined) {
      out.push({ key: mapping.targetFieldCode, section: 'field', label, kind: 'added', from: null, to: mappingText(mapping) });
      result.fieldKinds[mapping.targetFieldCode] = 'added';
    } else if (mappingSignature(before) !== mappingSignature(mapping)) {
      out.push({
        key: mapping.targetFieldCode,
        section: 'field',
        label,
        kind: 'changed',
        from: mappingText(before),
        to: mappingText(mapping),
      });
      result.fieldKinds[mapping.targetFieldCode] = 'changed';
    }
  }
  for (const mapping of base.mappings) {
    if (!currentCodes.has(mapping.targetFieldCode)) {
      const entry: DiffEntry = {
        key: mapping.targetFieldCode,
        section: 'field',
        label: fieldLabel(mapping.targetFieldCode),
        kind: 'removed',
        from: mappingText(mapping),
        to: null,
      };
      out.push(entry);
      result.removedFields.push(entry);
    }
  }
}

// --- filters ---

function filterSignature(filter: FilterView): string {
  return JSON.stringify([
    filter.sourceReference,
    filter.operator,
    filter.compareValue ?? '',
    filter.outcome,
    filter.matchGroup ?? null,
    filter.caseSensitive ?? false,
    filter.trimBeforeCompare ?? true,
    filter.nullBehaviour ?? '',
    filter.missingColumnBehaviour ?? '',
  ]);
}

function diffFilters(current: RevisionDetail, base: RevisionDetail, out: DiffEntry[], result: RevisionDiff) {
  const unmatchedBase = [...base.filters];
  const unmatchedCurrent: FilterView[] = [];
  for (const filter of current.filters) {
    const signature = filterSignature(filter);
    const index = unmatchedBase.findIndex((candidate) => filterSignature(candidate) === signature);
    if (index >= 0) {
      unmatchedBase.splice(index, 1);
    } else {
      unmatchedCurrent.push(filter);
    }
  }
  const sentenceOfCurrent = (filter: FilterView) => sentenceText(filterSentence(filter, current.structureFieldReferenceKind));
  const sentenceOfBase = (filter: FilterView) => sentenceText(filterSentence(filter, base.structureFieldReferenceKind));
  for (const filter of unmatchedCurrent) {
    const pairIndex = unmatchedBase.findIndex((candidate) => candidate.sourceReference === filter.sourceReference);
    if (pairIndex >= 0) {
      const [before] = unmatchedBase.splice(pairIndex, 1);
      out.push({
        key: `filter:${filter.id}`,
        section: 'filter',
        label: TEXT.filterLabel,
        kind: 'changed',
        from: sentenceOfBase(before!),
        to: sentenceOfCurrent(filter),
      });
      result.filterKinds[filter.id] = 'changed';
    } else {
      out.push({
        key: `filter:${filter.id}`,
        section: 'filter',
        label: TEXT.filterLabel,
        kind: 'added',
        from: null,
        to: sentenceOfCurrent(filter),
      });
      result.filterKinds[filter.id] = 'added';
    }
  }
  for (const filter of unmatchedBase) {
    const entry: DiffEntry = {
      key: `filter:${filter.id}`,
      section: 'filter',
      label: TEXT.filterLabel,
      kind: 'removed',
      from: sentenceOfBase(filter),
      to: null,
    };
    out.push(entry);
    result.removedFilters.push(entry);
  }
}

// --- losse instellingen ---

type Scalar = { key: string; section: DiffSection; label: string; text: string; empty: boolean };

function sourceText(source: GridSource): { text: string; empty: boolean } {
  if (source.kind === 'column') {
    return { text: TEXT.column(source.column), empty: false };
  }
  if (source.kind === 'fixed') {
    return { text: TEXT.fixed(source.value), empty: false };
  }
  return { text: TEXT.notUsed, empty: true };
}

function plain(value: string | number | null | undefined): { text: string; empty: boolean } {
  return value === null || value === undefined || value === ''
    ? { text: TEXT.notFilled, empty: true }
    : { text: String(value), empty: false };
}

function yesNo(value: boolean | undefined): { text: string; empty: boolean } {
  return { text: value === true ? TEXT.yes : TEXT.no, empty: false };
}

function setting(code: string): string {
  return termLabel('revisionField', code, 'Andere instelling');
}

/** Alle losse instellingen van een versie als lijst; dezelfde volgorde voor beide versies. */
export function scalarsOf(revision: RevisionDetail): Scalar[] {
  const list: Scalar[] = [];
  const add = (key: string, section: DiffSection, label: string, value: { text: string; empty: boolean }) =>
    list.push({ key, section, label, ...value });
  for (const row of identityRows(revision)) {
    add(row.key, 'identity', row.label, sourceText(row.source));
  }
  add('uppercase', 'identity', setting('identityUppercase'), yesNo(revision.identityUppercase));
  for (const row of basisRows(revision)) {
    add(row.key, 'basis', row.label, sourceText(row.source));
  }
  add('delimiter', 'file', setting('delimiter'), plain(revision.structureDelimiter));
  add('quoteChar', 'file', setting('quoteChar'), revision.structureQuoteChar === null ? { text: TEXT.notUsed, empty: true } : plain(revision.structureQuoteChar));
  add('charset', 'file', setting('charset'), plain(revision.structureCharset));
  add(
    'hasHeader',
    'file',
    setting('hasHeader'),
    revision.structureHasHeader ? { text: TEXT.headerLine(revision.structureHeaderLineNumber), empty: false } : yesNo(false),
  );
  add(
    'fieldReferenceKind',
    'file',
    setting('fieldReferenceKind'),
    { text: termLabel('fieldReferenceKind', revision.structureFieldReferenceKind, 'Andere herkenning'), empty: false },
  );
  add('expectedColumnCount', 'file', setting('expectedColumnCount'), plain(revision.structureExpectedColumnCount));
  add('canonicalisationVersion', 'file', setting('canonicalisationVersion'), plain(revision.recordCanonicalisationVersion));
  add('creationThresholdAbsolute', 'thresholds', setting('creationThresholdAbsolute'), plain(revision.creationThresholdAbsolute));
  add('creationThresholdSharePercent', 'thresholds', setting('creationThresholdSharePercent'), plain(revision.creationThresholdSharePercent));
  add('maxCriticalSharePercent', 'thresholds', setting('maxCriticalSharePercent'), plain(revision.maxCriticalSharePercent));
  add('maxRejectedSharePercent', 'thresholds', setting('maxRejectedSharePercent'), plain(revision.maxRejectedSharePercent));
  add('bulkIncidentSharePercent', 'thresholds', setting('bulkIncidentSharePercent'), plain(revision.bulkIncidentSharePercent));
  add('basePriceZeroAllowed', 'thresholds', setting('basePriceZeroAllowed'), yesNo(revision.basePriceZeroAllowed));
  add('basePriceNegativeAllowed', 'thresholds', setting('basePriceNegativeAllowed'), yesNo(revision.basePriceNegativeAllowed));
  add('priceDeviationPercent', 'thresholds', setting('priceDeviationPercent'), plain(revision.priceDeviationPercent));
  add(
    'priceDeviationSeverity',
    'thresholds',
    setting('priceDeviationSeverity'),
    { text: termLabel('severity', revision.priceDeviationSeverity, 'Andere ernst'), empty: false },
  );
  add('priceDerivationTolerance', 'thresholds', setting('priceDerivationTolerance'), plain(revision.priceDerivationTolerance));
  return list;
}

function diffScalars(current: RevisionDetail, base: RevisionDetail, out: DiffEntry[], result: RevisionDiff) {
  const before = new Map(scalarsOf(base).map((scalar) => [scalar.key, scalar]));
  for (const scalar of scalarsOf(current)) {
    const old = before.get(scalar.key);
    if (old === undefined || old.text === scalar.text) {
      continue;
    }
    const kind: ChipKind = old.empty && !scalar.empty ? 'added' : 'changed';
    out.push({ key: scalar.key, section: scalar.section, label: scalar.label, kind, from: old.text, to: scalar.text });
    result.scalarKinds[scalar.key] = kind;
  }
}

const SECTION_ORDER: DiffSection[] = ['identity', 'basis', 'field', 'filter', 'file', 'thresholds'];

/** Het verschil van `current` met zijn basisversie; zonder basisversie is er geen verschil. */
export function computeRevisionDiff(current: RevisionDetail, base: RevisionDetail | null): RevisionDiff {
  if (base === null) {
    return { ...EMPTY_DIFF, entries: [], fieldKinds: {}, scalarKinds: {}, filterKinds: {}, removedFields: [], removedFilters: [] };
  }
  const result: RevisionDiff = {
    baseRevisionNumber: base.revisionNumber,
    entries: [],
    count: 0,
    fieldKinds: {},
    scalarKinds: {},
    filterKinds: {},
    removedFields: [],
    removedFilters: [],
  };
  const entries: DiffEntry[] = [];
  diffScalars(current, base, entries, result);
  diffMappings(current, base, entries, result);
  diffFilters(current, base, entries, result);
  // Stabiel sorteren op sectie: herkenning, basis, velden, filters, bestandsopbouw, drempels.
  result.entries = SECTION_ORDER.flatMap((section) => entries.filter((entry) => entry.section === section));
  result.count = result.entries.length;
  return result;
}
