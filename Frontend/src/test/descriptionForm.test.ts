import { describe, expect, it } from 'vitest';
import {
  INITIAL,
  revisionBody,
  validateDescription,
  type FormState,
} from '../features/setup/wizard/descriptionForm';

/** Een volledig, geldig driedelig formulier met een puntkomma als scheidingsteken. */
const VALID: FormState = {
  ...INITIAL,
  definitionCode: 'VRO',
  definitionName: 'Vroegop',
  delimiterChoice: ';',
  identityProfileKind: 'THREE_PART',
  supplierField: ' LEV ',
  supplierGroupField: 'LEVGRP',
  supplierReferenceField: 'LEVREF',
  basePriceField: 'PRIJS',
};

describe('validateDescription', () => {
  it('een geldig formulier heeft geen problemen', () => {
    expect(validateDescription(VALID, false)).toEqual({});
  });

  it('een lege code en naam blokkeren enkel zolang de beschrijving niet bewaard is', () => {
    const form = { ...VALID, definitionCode: ' ', definitionName: '' };
    expect(Object.keys(validateDescription(form, false)).sort()).toEqual(['definitionCode', 'definitionName']);
    expect(validateDescription(form, true)).toEqual({});
  });

  it('het scheidingsteken is verplicht en precies één teken', () => {
    expect(validateDescription({ ...VALID, delimiterChoice: '' }, true).delimiterChoice).toContain('Kies');
    expect(
      validateDescription({ ...VALID, delimiterChoice: 'OTHER', delimiterOther: 'ab' }, true).delimiterChoice,
    ).toContain('precies één');
  });

  it('het aanhalingsteken mag niet gelijk zijn aan het scheidingsteken', () => {
    expect(validateDescription({ ...VALID, delimiterChoice: ',', quoteChoice: '"' }, true).quoteChoice).toBeUndefined();
    const form = { ...VALID, delimiterChoice: 'OTHER', delimiterOther: '"', quoteChoice: '"' };
    expect(validateDescription(form, true).quoteChoice).toContain('verschillen');
  });

  it('zonder kopregel moeten kolommen op positie herkend worden', () => {
    const problems = validateDescription({ ...VALID, hasHeader: false }, true);
    expect(problems.fieldReferenceKind).toContain('Zonder kopregel');
    expect(
      validateDescription({ ...VALID, hasHeader: false, fieldReferenceKind: 'COLUMN_INDEX' }, true)
        .supplierField,
    ).toContain('volgnummer');
  });

  it('een kopregelnummer is een geheel getal van minstens 1, nooit stil 1', () => {
    expect(validateDescription({ ...VALID, headerLineNumber: '0' }, true).headerLineNumber).toContain('geheel getal');
    expect(validateDescription({ ...VALID, headerLineNumber: 'x' }, true).headerLineNumber).toContain('geheel getal');
  });

  it('de herkenning heeft geen standaard; de kortingscodekolom hoort enkel bij de vierdelige', () => {
    expect(validateDescription({ ...VALID, identityProfileKind: '' }, true).identityProfileKind).toContain('bewust');
    expect(validateDescription(VALID, true).discountCodeField).toBeUndefined();
    const four = { ...VALID, identityProfileKind: 'FOUR_PART_WITH_DISCOUNT_CODE' as const };
    expect(validateDescription(four, true).discountCodeField).toBe('Deze kolom is verplicht.');
  });

  it('de canonicalisatieversie is 1 of 2', () => {
    expect(validateDescription({ ...VALID, canonicalisationVersion: '3' }, true).canonicalisationVersion).toBe(
      'Kies versie 1 of 2.',
    );
  });
});

describe('revisionBody', () => {
  it('trimt de kolommen, zet lege optionele kolommen op null en geeft de actor mee', () => {
    const body = revisionBody(VALID, 'An');
    expect(body.delimiter).toBe(';');
    expect(body.quoteChar).toBe('"');
    expect(body.charset).toBe('UTF-8');
    expect(body.supplierField).toBe('LEV');
    expect(body.descriptionField).toBeNull();
    expect(body.currencyField).toBeNull();
    expect(body.discountCodeField).toBeNull();
    expect(body.headerLineNumber).toBe(1);
    expect(body.canonicalisationVersion).toBe(2);
    expect(body.createdBy).toBe('An');
  });

  it('vierdelig stuurt de kortingscodekolom mee; zonder kopregel geen regelnummer; geen aanhalingsteken = lege tekst', () => {
    const body = revisionBody(
      {
        ...VALID,
        identityProfileKind: 'FOUR_PART_WITH_DISCOUNT_CODE',
        discountCodeField: ' KORT ',
        hasHeader: false,
        quoteChoice: 'NONE',
        charsetChoice: 'OTHER',
        charsetOther: ' CP850 ',
      },
      'An',
    );
    expect(body.discountCodeField).toBe('KORT');
    expect(body.headerLineNumber).toBeNull();
    expect(body.quoteChar).toBe('');
    expect(body.charset).toBe('CP850');
  });
});
