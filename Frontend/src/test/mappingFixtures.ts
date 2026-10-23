import type { MappingView } from '../api/types';

/** Een volledig extra veld (mapping) zonder bewerking, voor de pure modeltests van E2. */
export function emptyMappingForm(over: Partial<MappingView> = {}): MappingView {
  return {
    id: 1,
    revisionId: 100,
    sequenceNumber: 1,
    targetFieldCode: 'EAN',
    sourceReference: 'KOLOM',
    valueKind: 'SOURCE_FIELD',
    criticality: 'NON_CRITICAL',
    expectedPosition: null,
    fixedValue: null,
    defaultValue: null,
    required: false,
    maxLength: null,
    decimalScale: null,
    zeroAllowed: false,
    negativeAllowed: false,
    transformKind: 'NONE',
    transformConfig: null,
    valueMap: [],
    steps: [],
    ...over,
  };
}
