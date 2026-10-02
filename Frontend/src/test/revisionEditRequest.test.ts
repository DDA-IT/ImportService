import { describe, expect, it } from 'vitest';
import type { RevisionDetail } from '../api/types';
import {
  buildRequest,
  hasChanges,
  identityChanged,
  initialForm,
  type FormState,
} from '../features/setup/revisionEditRequest';

const REVISION: RevisionDetail = {
  id: 100,
  definitionId: 10,
  revisionNumber: 2,
  status: 'DRAFT',
  basedOnRevisionId: 99,
  changeReason: 'Nieuwe prijslijst 2027',
  identityProfileKind: 'THREE_PART',
  identitySupplierField: 'LEV',
  identitySupplierGroupField: 'LEVGRP',
  identitySupplierReferenceField: 'LEVREF',
  identityDiscountCodeField: null,
  structureFormat: 'CSV',
  structureCharset: 'UTF-8',
  structureDelimiter: ';',
  structureQuoteChar: '"',
  structureHasHeader: true,
  structureHeaderLineNumber: 1,
  structureFieldReferenceKind: 'HEADER_NAME',
  structureExpectedColumnCount: 12,
  accessDeliverySetKind: 'FULL_SNAPSHOT',
  recordBasePriceField: 'PRIJS',
  recordDescriptionField: 'OMSCHRIJVING',
  recordCurrencyField: null,
  recordCanonicalisationVersion: 1,
  basePriceZeroAllowed: false,
  basePriceNegativeAllowed: false,
  priceDeviationPercent: 20,
  priceDeviationSeverity: 'WARNING',
  priceDerivationTolerance: 0.01,
  priceAvgShortWindow: 3,
  priceAvgLongWindow: 12,
  priceControlModel: 'DEVIATION',
  creationThresholdAbsolute: 100,
  creationThresholdSharePercent: 30,
  maxCriticalRecords: 0,
  maxRejectedRecords: null,
  maxCriticalSharePercent: 5,
  maxRejectedSharePercent: 10,
  bulkIncidentSharePercent: 15,
  accessVersion: 1,
  accessConfigHash: 'aaaa',
  structureVersion: 1,
  structureConfigHash: 'bbbb',
  recordRulesVersion: 1,
  recordRulesConfigHash: 'cccc',
  compositeConfigHash: 'dddd',
  createdAt: '2026-09-28T08:00:00Z',
  createdBy: 'An Beslisser',
  createdBySubject: 'test-sub',
  updatedAt: '2026-09-28T08:00:00Z',
  approvedAt: null,
  approvedBy: null,
  approvedBySubject: null,
  mappings: [],
  filters: [],
  fieldCriticalities: [],
  bookmarks: [],
  bookmarkValues: [],
};

function problemOf(result: ReturnType<typeof buildRequest>): string {
  if (!('problem' in result)) {
    throw new Error('probleem verwacht');
  }
  return result.problem;
}

function bodyOf(result: ReturnType<typeof buildRequest>) {
  if (!('body' in result)) {
    throw new Error('verzoek verwacht');
  }
  return result.body;
}

function formWith(changes: Partial<FormState>): FormState {
  return { ...initialForm(REVISION), ...changes };
}

describe('revisionEditRequest', () => {
  it('een onaangeroerd formulier geeft overal null en hasChanges is false', () => {
    const body = bodyOf(buildRequest(initialForm(REVISION), REVISION, false, 'An'));
    expect(body.createdBy).toBe('An');
    expect(body.acknowledgeIdentityChange).toBeNull();
    expect(body.delimiter).toBeNull();
    expect(body.expectedColumnCount).toBeNull();
    expect(hasChanges(body)).toBe(false);
  });

  it('verstuurt enkel het gewijzigde veld (normaal pad)', () => {
    const body = bodyOf(
      buildRequest(formWith({ delimiter: ',', priceDeviationPercent: '25' }), REVISION, false, 'An'),
    );
    expect(body.delimiter).toBe(',');
    expect(body.priceDeviationPercent).toBe(25);
    expect(body.charset).toBeNull();
    expect(body.maxCriticalSharePercent).toBeNull();
    expect(hasChanges(body)).toBe(true);
  });

  it('een leeggemaakt optioneel veld wordt een uitdrukkelijk lege tekst; al leeg blijft null', () => {
    const body = bodyOf(
      buildRequest(formWith({ descriptionField: '   ', currencyField: '' }), REVISION, false, 'An'),
    );
    expect(body.descriptionField).toBe('');
    expect(body.currencyField).toBeNull();
  });

  it('een leeg verplicht veld en een onleesbaar getal blokkeren', () => {
    expect(problemOf(buildRequest(formWith({ charset: ' ' }), REVISION, false, 'An'))).toContain('verplicht');
    expect(problemOf(buildRequest(formWith({ headerLineNumber: 'x' }), REVISION, false, 'An'))).toContain(
      'geen leesbaar getal',
    );
  });

  it('een leeg getalveld is ongewijzigd, nooit 0', () => {
    const body = bodyOf(buildRequest(formWith({ expectedColumnCount: '' }), REVISION, false, 'An'));
    expect(body.expectedColumnCount).toBeNull();
  });

  it('identiteitswijziging zonder bevestiging blokkeert, met bevestiging gaat ze mee', () => {
    const form = formWith({ supplierField: 'LEVERANCIER' });
    expect(identityChanged(form, REVISION)).toBe(true);
    expect(problemOf(buildRequest(form, REVISION, false, 'An'))).toContain('raakt hoe een artikel herkend wordt');
    const body = bodyOf(buildRequest(form, REVISION, true, 'An'));
    expect(body.supplierField).toBe('LEVERANCIER');
    expect(body.acknowledgeIdentityChange).toBe(true);
  });

  it('identityChanged negeert spaties en vergelijkt de optionele kortingscode genormaliseerd', () => {
    expect(identityChanged(formWith({ supplierField: ' LEV ' }), REVISION)).toBe(false);
    expect(identityChanged(formWith({ discountCodeField: '  ' }), REVISION)).toBe(false);
    expect(identityChanged(formWith({ discountCodeField: 'KORT' }), REVISION)).toBe(true);
    expect(identityChanged(formWith({ identityProfileKind: 'FOUR_PART_WITH_DISCOUNT_CODE' }), REVISION)).toBe(true);
  });

  it('een bevestiging alleen telt niet als wijziging', () => {
    const body = bodyOf(buildRequest(initialForm(REVISION), REVISION, true, 'An'));
    expect(body.acknowledgeIdentityChange).toBe(true);
    expect(hasChanges(body)).toBe(false);
  });
});
