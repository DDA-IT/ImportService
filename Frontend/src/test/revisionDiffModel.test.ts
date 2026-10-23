/**
 * E4 — het verschil tussen een concept en zijn basisversie (client-side): extra velden op doelveld, filters op inhoud en de
 * losse instellingen; zonder basisversie is er geen verschil.
 */

import { describe, expect, it } from 'vitest';
import type { FilterView, MappingView, RevisionDetail } from '../api/types';
import { computeRevisionDiff } from '../features/setup/revisionDiffModel';
import { revisionDetail } from './setupServer';

function detail(id: number, over: Record<string, unknown> = {}): RevisionDetail {
  return revisionDetail(id, 10, { structureFieldReferenceKind: 'COLUMN_INDEX', ...over }) as unknown as RevisionDetail;
}

function mapping(id: number, code: string, over: Partial<MappingView> = {}): MappingView {
  return {
    id,
    revisionId: 1,
    sequenceNumber: id,
    targetFieldCode: code,
    sourceReference: '7',
    valueKind: 'SOURCE_FIELD',
    criticality: 'NON_CRITICAL',
    required: false,
    valueMap: [],
    steps: [],
    ...over,
  };
}

function filter(id: number, over: Partial<FilterView> = {}): FilterView {
  return {
    id,
    revisionId: 1,
    sequenceNumber: id,
    sourceReference: '3',
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

describe('computeRevisionDiff', () => {
  it('zonder basisversie is er geen verschil', () => {
    const diff = computeRevisionDiff(detail(2, { mappings: [mapping(1, 'BRAND')] }), null);
    expect(diff.count).toBe(0);
    expect(diff.entries).toEqual([]);
    expect(diff.baseRevisionNumber).toBeNull();
  });

  it('twee gelijke versies hebben geen verschil', () => {
    const base = detail(1, { revisionNumber: 3, mappings: [mapping(1, 'BRAND')], filters: [filter(5)] });
    const current = detail(2, { revisionNumber: 4, mappings: [mapping(9, 'BRAND')], filters: [filter(8)] });
    const diff = computeRevisionDiff(current, base);
    expect(diff.count).toBe(0);
    expect(diff.baseRevisionNumber).toBe(3);
  });

  it('extra velden: nieuw, gewijzigd en niet meer in deze versie, op doelveld', () => {
    const base = detail(1, { mappings: [mapping(1, 'BRAND'), mapping(2, 'EAN', { sourceReference: '5' })] });
    const current = detail(2, {
      mappings: [mapping(11, 'BRAND', { sourceReference: '9' }), mapping(12, 'UNIT', { valueKind: 'FIXED_VALUE', fixedValue: 'ST', sourceReference: '' })],
    });
    const diff = computeRevisionDiff(current, base);
    expect(diff.fieldKinds).toEqual({ BRAND: 'changed', UNIT: 'added' });
    expect(diff.removedFields.map((entry) => entry.key)).toEqual(['EAN']);
    expect(diff.count).toBe(3);
    const brand = diff.entries.find((entry) => entry.key === 'BRAND')!;
    expect(brand.kind).toBe('changed');
    expect(brand.from).toContain('kolom 7');
    expect(brand.to).toContain('kolom 9');
    expect(diff.entries.find((entry) => entry.key === 'UNIT')!.to).toContain('vaste waarde ST');
  });

  it('een gewijzigde kritiek of vertaling telt als gewijzigd', () => {
    const base = detail(1, { mappings: [mapping(1, 'BRAND'), mapping(2, 'EAN')] });
    const current = detail(2, {
      mappings: [
        mapping(11, 'BRAND', { criticality: 'CRITICAL' }),
        mapping(12, 'EAN', { valueMap: [{ source: 'A', target: 'B' }] }),
      ],
    });
    expect(computeRevisionDiff(current, base).fieldKinds).toEqual({ BRAND: 'changed', EAN: 'changed' });
  });

  it('filters: gelijke inhoud met een ander id is gelijk; zelfde kolom met andere waarde is gewijzigd; de rest nieuw of weg', () => {
    const base = detail(1, { filters: [filter(1), filter(2, { sourceReference: '5', compareValue: 'X' }), filter(3, { sourceReference: '8' })] });
    const current = detail(2, {
      filters: [filter(21), filter(22, { sourceReference: '5', compareValue: 'Y' }), filter(23, { sourceReference: '40', operator: 'CONTAINS' })],
    });
    const diff = computeRevisionDiff(current, base);
    expect(diff.filterKinds).toEqual({ 22: 'changed', 23: 'added' });
    expect(diff.removedFilters).toHaveLength(1);
    expect(diff.removedFilters[0]!.from).toContain('kolom 8');
    expect(diff.count).toBe(3);
  });

  it('losse instellingen: herkenning, basis, bestandsopbouw en drempels', () => {
    const base = detail(1, { identitySupplierField: 'LEV', recordDescriptionField: null, structureDelimiter: ';', maxRejectedSharePercent: null });
    const current = detail(2, {
      identitySupplierField: null,
      identitySupplierFixedValue: '1234',
      recordDescriptionField: 'OMS',
      structureDelimiter: ',',
      maxRejectedSharePercent: 5,
    });
    const diff = computeRevisionDiff(current, base);
    expect(diff.scalarKinds).toEqual({
      supplier: 'changed',
      description: 'added',
      delimiter: 'changed',
      maxRejectedSharePercent: 'added',
    });
    expect(diff.count).toBe(4);
    const supplier = diff.entries.find((entry) => entry.key === 'supplier')!;
    expect(supplier.from).toBe('kolom LEV');
    expect(supplier.to).toBe('vaste waarde 1234');
    // Volgorde: herkenning, basis, bestandsopbouw, drempels.
    expect(diff.entries.map((entry) => entry.section)).toEqual(['identity', 'basis', 'file', 'thresholds']);
  });
});
