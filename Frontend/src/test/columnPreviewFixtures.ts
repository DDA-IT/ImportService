/** E5b — gedeelde bouwstenen voor de tests van het kolomvoorbeeld (model en weergave). */

import type { ColumnPreview, FilterView, MappingView, RevisionDetail } from '../api/types';
import { revisionDetail } from './setupServer';

export function revision(over: Record<string, unknown> = {}): RevisionDetail {
  return revisionDetail(100, 10, {
    status: 'DRAFT',
    revisionNumber: 4,
    identitySupplierField: 'LEV',
    identitySupplierGroupField: 'GRP',
    identitySupplierReferenceField: 'REF',
    identityDiscountCodeField: null,
    recordBasePriceField: 'PRIJS',
    recordDescriptionField: 'OMSCHRIJVING',
    recordCurrencyField: null,
    structureFieldReferenceKind: 'HEADER_NAME',
    mappings: [],
    filters: [],
    ...over,
  }) as unknown as RevisionDetail;
}

export function mapping(id: number, code: string, source: string, over: Partial<MappingView> = {}): MappingView {
  return {
    id,
    revisionId: 100,
    sequenceNumber: id,
    targetFieldCode: code,
    sourceReference: source,
    valueKind: 'SOURCE_FIELD',
    criticality: 'NON_CRITICAL',
    required: false,
    valueMap: [],
    steps: [],
    ...over,
  };
}

export function filter(id: number, source: string, over: Partial<FilterView> = {}): FilterView {
  return {
    id,
    revisionId: 100,
    sequenceNumber: id,
    sourceReference: source,
    operator: 'EQUALS',
    compareValue: 'BENL',
    outcome: 'EXCLUDE',
    matchGroup: null,
    caseSensitive: false,
    trimBeforeCompare: true,
    nullBehaviour: 'EXCLUDE',
    missingColumnBehaviour: 'BLOCK',
    ...over,
  };
}

/** Een voorbeeld met kopregel: `headers` worden de kolommen 1..n, `rows` de voorbeeldregels (vanaf regel 2). */
export function preview(headers: (string | null)[], rows: (string[] | 'unreadable')[] = [], over: Partial<ColumnPreview> = {}): ColumnPreview {
  const hasHeader = headers.some((header) => header !== null);
  return {
    status: 'AVAILABLE',
    layout: {
      charset: 'UTF-8',
      delimiter: ';',
      quoteChar: '"',
      hasHeader,
      headerLineNumber: 1,
      referenceKind: hasHeader ? 'HEADER_NAME' : 'COLUMN_INDEX',
    },
    headerLineNumber: hasHeader ? 1 : null,
    columns: headers.map((header, index) => ({ position: index + 1, header })),
    rows: rows.map((row, index) =>
      row === 'unreadable'
        ? { lineNumber: index + 2, cells: null, unreadable: true }
        : { lineNumber: index + 2, cells: row, unreadable: false },
    ),
    columnsTruncated: false,
    rowsTruncated: false,
    ...over,
  };
}
