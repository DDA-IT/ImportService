/**
 * G2c-2 — vaste leverancier/groep en hoofdletters in de pure kant van het bewerken (`revisionEditRequest`,
 * `docs/design/g2-design.md` §G2c). Dekt: "null = ongewijzigd", de identiteitsvergelijking (R-REV-X3) en de blokkade van een
 * lege vaste waarde. E6: de schermtests van het oude `RevisionEditForm` zijn weg; het paneel (kolom of vaste waarde, de
 * Prodis-keuzelijst, bevestiging, terugval naar vrije tekst) staat in `FieldsEditor.test.tsx`.
 */

import { describe, expect, it } from 'vitest';
import type { RevisionDetail } from '../api/types';
import { buildRequest, identityChanged, initialForm } from '../features/setup/revisionEditRequest';

const COLUMN_REVISION = {
  id: 100,
  definitionId: 10,
  revisionNumber: 2,
  status: 'DRAFT',
  basedOnRevisionId: 99,
  changeReason: null,
  identityProfileKind: 'THREE_PART',
  identitySupplierField: 'LEV',
  identitySupplierGroupField: 'LEVGRP',
  identitySupplierReferenceField: 'LEVREF',
  identityDiscountCodeField: null,
  identitySupplierFixedValue: null,
  identitySupplierGroupFixedValue: null,
  identityUppercase: false,
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
  recordDescriptionField: null,
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
} as unknown as RevisionDetail;

const FIXED_REVISION: RevisionDetail = {
  ...COLUMN_REVISION,
  identitySupplierField: null,
  identitySupplierGroupField: null,
  identitySupplierFixedValue: '1234',
  identitySupplierGroupFixedValue: 'GROEP A',
};

describe('revisionEditRequest — vaste waarden en hoofdletters (pure kant)', () => {
  it('onaangeroerd: alles null, ook de nieuwe velden', () => {
    const built = buildRequest(initialForm(FIXED_REVISION), FIXED_REVISION, false, 'An');
    if (!('body' in built)) {
      throw new Error('verzoek verwacht');
    }
    expect(built.body.supplierField).toBeNull();
    expect(built.body.supplierFixedValue).toBeNull();
    expect(built.body.supplierGroupField).toBeNull();
    expect(built.body.supplierGroupFixedValue).toBeNull();
    expect(built.body.identityUppercase).toBeNull();
  });

  it('identityChanged ziet een vaste waarde, een bronwissel en hoofdletters', () => {
    const base = initialForm(FIXED_REVISION);
    expect(identityChanged(base, FIXED_REVISION)).toBe(false);
    expect(identityChanged({ ...base, supplierFixedValue: '9999' }, FIXED_REVISION)).toBe(true);
    expect(identityChanged({ ...base, supplierGroupFixedValue: 'ANDERS' }, FIXED_REVISION)).toBe(true);
    expect(identityChanged({ ...base, identityUppercase: true }, FIXED_REVISION)).toBe(true);
    expect(identityChanged({ ...base, supplierSource: 'COLUMN', supplierField: 'LEV' }, FIXED_REVISION)).toBe(true);
    // De verborgen tekst van de niet-gekozen bron telt niet mee.
    expect(identityChanged({ ...base, supplierField: 'IGNORED' }, FIXED_REVISION)).toBe(false);
  });

  it('een lege vaste waarde blokkeert (nooit stil leeg)', () => {
    const form = { ...initialForm(COLUMN_REVISION), supplierSource: 'FIXED', supplierFixedValue: '  ' };
    expect('problem' in buildRequest(form, COLUMN_REVISION, true, 'An')).toBe(true);
  });
});

describe('revisionEditRequest — "null = ongewijzigd" en de weigeringen (was S1-F4.7-F4.13 op het oude formulier)', () => {
  function bodyOf(form: ReturnType<typeof initialForm>, acknowledge = false) {
    const built = buildRequest(form, COLUMN_REVISION, acknowledge, 'An Beslisser');
    if (!('body' in built)) {
      throw new Error(`verzoek verwacht, probleem: ${built.problem}`);
    }
    return built.body;
  }

  it('een gewone drempelwijziging verstuurt alleen dat ene veld; al de rest is null', () => {
    const body = bodyOf({ ...initialForm(COLUMN_REVISION), maxCriticalSharePercent: '7.5' });
    expect(body.maxCriticalSharePercent).toBe(7.5);
    expect(body.identityProfileKind).toBeNull();
    expect(body.supplierField).toBeNull();
    expect(body.canonicalisationVersion).toBeNull();
    expect(body.delimiter).toBeNull();
    expect(body.basePriceField).toBeNull();
    expect(body.acknowledgeIdentityChange).toBeNull();
    expect(body.createdBy).toBe('An Beslisser');
  });

  it('een onleesbaar getal is een fout, nooit stil 0 of leeg', () => {
    const built = buildRequest({ ...initialForm(COLUMN_REVISION), creationThresholdSharePercent: 'dertig' }, COLUMN_REVISION, false, 'An');
    expect('problem' in built && built.problem).toContain('geen leesbaar getal');
  });

  it('een leeggemaakt verplicht veld wordt nooit als lege waarde verstuurd', () => {
    const built = buildRequest({ ...initialForm(COLUMN_REVISION), basePriceField: '   ' }, COLUMN_REVISION, false, 'An');
    expect('problem' in built && built.problem).toContain('verplicht');
  });

  it('een optioneel veld leegmaken verstuurt een uitdrukkelijk lege waarde; een veld dat al null was blijft ongewijzigd', () => {
    const revision = { ...COLUMN_REVISION, recordDescriptionField: 'OMSCHRIJVING' } as RevisionDetail;
    const built = buildRequest({ ...initialForm(revision), descriptionField: '' }, revision, false, 'An');
    if (!('body' in built)) {
      throw new Error('verzoek verwacht');
    }
    expect(built.body.descriptionField).toBe('');
    expect(built.body.currencyField).toBeNull();
  });

  it('R-REV-X3: een identiteitswijziging vraagt de bevestiging; mét bevestiging gaat acknowledgeIdentityChange: true mee', () => {
    const form = { ...initialForm(COLUMN_REVISION), supplierReferenceField: 'ANDERE_REF' };
    expect(identityChanged(form, COLUMN_REVISION)).toBe(true);
    const refused = buildRequest(form, COLUMN_REVISION, false, 'An');
    expect('problem' in refused && refused.problem).toContain('Bevestig');
    const body = bodyOf(form, true);
    expect(body.supplierReferenceField).toBe('ANDERE_REF');
    expect(body.acknowledgeIdentityChange).toBe(true);
  });
});
