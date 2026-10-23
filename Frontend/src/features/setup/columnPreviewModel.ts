/**
 * E5b — "Per kolom van het bestand": zuiver model. Het kolomvoorbeeld van de proefinlezing (`TrialReadResult.columnPreview`,
 * EB5) wordt hier gekoppeld aan de versie die NU op het scherm staat (de koppeling kolom → veld/filter doet de frontend, niet
 * de server): wat voedt elke kolom (herkenning, basis, extra velden, filters), welke verwachte kolommen staan niet in het
 * bestand, en is het voorbeeld verouderd omdat de bestandsopbouw sindsdien gewijzigd is.
 *
 * Herkenningsregel (zoals de lezer): `HEADER_NAME` = getrimde, hoofdletterongevoelige naam; `COLUMN_INDEX` = positie vanaf 1;
 * een positie buiten het bestand telt als "niet gevonden". Niets wordt bewaard; dit model rekent enkel.
 */

import type { ColumnPreview, FilterView, MappingView, RevisionDetail } from '../../api/types.ts';
import { COLUMN_PREVIEW_TEXT as TEXT } from '../../terms/columnPreview.ts';
import { termLabel } from '../../terms/index.ts';
import { basisRows, identityRows } from './fieldsGridModel.ts';

export const HEADER_NAME = 'HEADER_NAME';
export const COLUMN_INDEX = 'COLUMN_INDEX';

/** Meer dan dit aantal opeenvolgende niet-gebruikte kolommen wordt als één regel samengevat. */
export const MAX_UNUSED_RUN = 5;
/** Aantal voorbeeldwaarden per kolom. */
export const SAMPLE_COUNT = 3;

export type ColumnUseKind = 'identity' | 'basis' | 'mapping' | 'filter';

/** Waar een kolom naartoe gaat: de zichtbare naam (via het woordenboek) en de soort. */
export type ColumnUse = { kind: ColumnUseKind; label: string };

export type ColumnSample = { kind: 'value'; text: string } | { kind: 'unreadable'; lineNumber: number };

export type PreviewColumnView = {
  position: number;
  header: string | null;
  samples: ColumnSample[];
  uses: ColumnUse[];
};

/** Een verwachte bron (veld of filter) die niet in het bestand gevonden werd. */
export type MissingSource = { label: string; reference: string; byName: boolean };

export type ColumnPreviewModel = {
  columns: PreviewColumnView[];
  missing: MissingSource[];
  outdated: boolean;
};

/** Eén regel in de tabel: een kolom, of een samengevatte reeks niet-gebruikte kolommen. */
export type PreviewTableItem =
  | { kind: 'column'; column: PreviewColumnView }
  | { kind: 'run'; from: number; to: number; columns: PreviewColumnView[] };

type Reference = { use: ColumnUse; reference: string };

/** Een extra veld met een vaste waarde of zonder bron leest geen kolom. */
function mappingReads(mapping: MappingView): boolean {
  return (mapping.sourceReference ?? '').trim() !== '';
}

function filterLabel(filter: FilterView): string {
  return filter.matchGroup == null ? TEXT.filterLabel(filter.sequenceNumber) : TEXT.groupLabel(filter.matchGroup);
}

/** Alle plaatsen in de versie die een kolom van het bestand lezen, in schermvolgorde. */
export function referencesOf(revision: RevisionDetail): Reference[] {
  const refs: Reference[] = [];
  for (const row of identityRows(revision)) {
    if (row.source.kind === 'column') {
      refs.push({ use: { kind: 'identity', label: row.label }, reference: row.source.column });
    }
  }
  for (const row of basisRows(revision)) {
    if (row.source.kind === 'column') {
      refs.push({ use: { kind: 'basis', label: row.label }, reference: row.source.column });
    }
  }
  for (const mapping of revision.mappings) {
    if (mappingReads(mapping)) {
      refs.push({
        use: { kind: 'mapping', label: termLabel('fieldKey', mapping.targetFieldCode, 'Extra veld') },
        reference: mapping.sourceReference,
      });
    }
  }
  for (const filter of revision.filters) {
    if ((filter.sourceReference ?? '').trim() !== '') {
      refs.push({ use: { kind: 'filter', label: filterLabel(filter) }, reference: filter.sourceReference });
    }
  }
  return refs;
}

/** De positie (vanaf 1) die een verwijzing aanwijst, of `null`; een nummer buiten het bestand wijst niets aan. */
export function resolvePosition(
  reference: string,
  referenceKind: string,
  columns: readonly { position: number; header: string | null }[],
): number | null {
  const wanted = reference.trim();
  if (wanted === '') {
    return null;
  }
  if (referenceKind === COLUMN_INDEX) {
    if (!/^\d+$/.test(wanted)) {
      return null;
    }
    const position = Number(wanted);
    return columns.some((column) => column.position === position) ? position : null;
  }
  const lower = wanted.toLowerCase();
  const found = columns.find((column) => column.header !== null && column.header.trim().toLowerCase() === lower);
  return found === undefined ? null : found.position;
}

/** `true` als de bestandsopbouw van het voorbeeld verschilt van die van de versie op het scherm. */
export function isPreviewOutdated(preview: ColumnPreview, revision: RevisionDetail): boolean {
  const layout = preview.layout;
  const upper = (value: string | null) => (value === null ? null : value.trim().toUpperCase());
  if (upper(layout.charset) !== upper(revision.structureCharset)) {
    return true;
  }
  if (layout.delimiter !== revision.structureDelimiter) {
    return true;
  }
  if ((layout.quoteChar ?? null) !== (revision.structureQuoteChar ?? null)) {
    return true;
  }
  if (layout.hasHeader !== revision.structureHasHeader) {
    return true;
  }
  return layout.hasHeader && layout.headerLineNumber !== revision.structureHeaderLineNumber;
}

/** De eerste `SAMPLE_COUNT` niet-lege waarden van een kolom; een onleesbare regel onderweg wordt als markering getoond. */
export function samplesOf(preview: ColumnPreview, position: number): ColumnSample[] {
  const samples: ColumnSample[] = [];
  let values = 0;
  for (const row of preview.rows) {
    if (values >= SAMPLE_COUNT) {
      break;
    }
    if (row.unreadable || row.cells === null) {
      samples.push({ kind: 'unreadable', lineNumber: row.lineNumber });
      continue;
    }
    const cell = row.cells[position - 1];
    if (cell !== undefined && cell.trim() !== '') {
      samples.push({ kind: 'value', text: cell });
      values += 1;
    }
  }
  return samples;
}

export function buildColumnPreviewModel(preview: ColumnPreview, revision: RevisionDetail): ColumnPreviewModel {
  const referenceKind = revision.structureFieldReferenceKind;
  const usesByPosition = new Map<number, ColumnUse[]>();
  const missing: MissingSource[] = [];
  const hasNames = preview.columns.some((column) => column.header !== null);
  const lastPosition = preview.columns.reduce((max, column) => Math.max(max, column.position), 0);

  for (const { use, reference } of referencesOf(revision)) {
    const position = resolvePosition(reference, referenceKind, preview.columns);
    if (position !== null) {
      const list = usesByPosition.get(position) ?? [];
      if (!list.some((existing) => existing.label === use.label && existing.kind === use.kind)) {
        list.push(use);
      }
      usesByPosition.set(position, list);
      continue;
    }
    if (preview.columns.length === 0) {
      continue;
    }
    if (referenceKind === COLUMN_INDEX) {
      // Een nummer voorbij de afgekapte kolomlijst kan niet beoordeeld worden: dan niets beweren.
      const asNumber = /^\d+$/.test(reference.trim()) ? Number(reference.trim()) : null;
      if (asNumber !== null && preview.columnsTruncated && asNumber > lastPosition) {
        continue;
      }
      missing.push({ label: use.label, reference: reference.trim(), byName: false });
    } else if (hasNames) {
      missing.push({ label: use.label, reference: reference.trim(), byName: true });
    }
  }

  const columns = preview.columns.map<PreviewColumnView>((column) => ({
    position: column.position,
    header: column.header,
    samples: samplesOf(preview, column.position),
    uses: usesByPosition.get(column.position) ?? [],
  }));
  return { columns, missing, outdated: isPreviewOutdated(preview, revision) };
}

/** Rijen van de tabel: reeksen van meer dan `MAX_UNUSED_RUN` opeenvolgende niet-gebruikte kolommen worden samengevat. */
export function tableItems(columns: readonly PreviewColumnView[]): PreviewTableItem[] {
  const items: PreviewTableItem[] = [];
  let run: PreviewColumnView[] = [];
  const flush = () => {
    if (run.length > MAX_UNUSED_RUN) {
      items.push({ kind: 'run', from: run[0]!.position, to: run[run.length - 1]!.position, columns: run });
    } else {
      for (const column of run) {
        items.push({ kind: 'column', column });
      }
    }
    run = [];
  };
  for (const column of columns) {
    if (column.uses.length === 0) {
      run.push(column);
    } else {
      flush();
      items.push({ kind: 'column', column });
    }
  }
  flush();
  return items;
}

/** Wat bij "Koppelen aan veld…" als bron geldt: de kolomnaam, of het kolomnummer bij herkenning op nummer. */
export function sourceForColumn(column: { position: number; header: string | null }, referenceKind: string): string {
  if (referenceKind === COLUMN_INDEX || column.header === null || column.header.trim() === '') {
    return String(column.position);
  }
  return column.header.trim();
}

/** De regels die niet gelezen konden worden in de voorbeeldregels (voor een melding boven de tabel). */
export function unreadableLines(preview: ColumnPreview): number[] {
  return preview.rows.filter((row) => row.unreadable || row.cells === null).map((row) => row.lineNumber);
}
