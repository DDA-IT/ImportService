/**
 * F2 — de zuivere beschrijving van een legacy-regel en de koppeling aan een rij van het leesbeeld
 * (`features/setup/legacyOrigin.ts`).
 */

import { describe, expect, it } from 'vitest';
import type { LegacyOriginLine, RevisionDetail } from '../api/types';
import {
  describeCalculation,
  describeLine,
  linkCreationDefaultLines,
  lineForRow,
  technicalLine,
  uncoveredLines,
} from '../features/setup/legacyOrigin';
import { legacyLine, revisionDetail } from './setupServer';

function line(number: number, over: Record<string, unknown> = {}): LegacyOriginLine {
  return legacyLine(number, over) as unknown as LegacyOriginLine;
}

describe('describeLine: waar de waarde vandaan komt', () => {
  it('kolom, positie, XML en vaste waarde', () => {
    expect(describeLine(line(12, { column: 12 }))).toBe('regel 12 · kolom 12');
    expect(describeLine(line(26, { column: 7 }))).toBe('regel 26 · kolom 7');
    expect(describeLine(line(9, { position: 21, length: 10 }))).toBe('regel 9 · positie 21–30');
    expect(describeLine(line(9, { position: 21, length: 0 }))).toBe('regel 9 · vanaf positie 21');
    expect(describeLine(line(5, { xmlElementPath: 'Article/Price' }))).toBe('regel 5 · XML Article/Price');
    expect(describeLine(line(40, { fixedValue: '100' }))).toBe('regel 40 · vaste waarde 100');
  });

  it('een vaste waarde wint van kolom en XML; een regel zonder bron toont enkel het regelnummer', () => {
    expect(describeLine(line(40, { fixedValue: '100', column: 3, xmlElementPath: 'A/B' }))).toBe('regel 40 · vaste waarde 100');
    expect(describeLine(line(3))).toBe('regel 3');
  });

  it('voorvoegsel en achtervoegsel', () => {
    expect(describeLine(line(12, { column: 4, prefix: '00', suffix: 'X' }))).toBe(
      'regel 12 · kolom 4 · voorvoegsel 00 · achtervoegsel X',
    );
    expect(describeLine(line(12, { column: 4, prefix: '  ', suffix: '' }))).toBe('regel 12 · kolom 4');
  });
});

describe('describeCalculation: de rekenwoorden van de vertaling', () => {
  const cases: [string, string, string | null][] = [
    ['1', '5', '+ 5'],
    ['2', '5', '− 5'],
    ['3', '1.5', '× 1.5'],
    ['4', '2', '÷ 2'],
    ['A', '7;2', '% van kolom 7'],
    ['A', '9', '% van kolom 9'],
    ['A', '', '% van een andere kolom'],
    ['7', '', 'vertaling'],
    ['14', '', 'vertaling (bevat)'],
    ['15', '', 'hoofdletters'],
    ['', '', null],
    ['0', '', null],
  ];
  it.each(cases)('CALC %s (waarde "%s") geeft %s', (calculation, calculationValue, expected) => {
    expect(describeCalculation(line(33, { calculation, calculationValue }))).toBe(expected);
  });

  it('een onbekende berekening is algemeen en toont nooit de ruwe code', () => {
    for (const code of ['8', '9', '10', '11', '12', '13', 'Z']) {
      const text = describeLine(line(33, { column: 7, calculation: code }));
      expect(text).toBe('regel 33 · kolom 7 · berekening in legacy');
    }
  });

  it('komt mee in de beschrijving van de regel', () => {
    expect(describeLine(line(33, { column: 7, calculation: '3', calculationValue: '1.5' }))).toBe('regel 33 · kolom 7 · × 1.5');
  });
});

describe('lineForRow en uncoveredLines', () => {
  const revision = revisionDetail(1, 1, {
    recordDescriptionField: null,
    mappings: [{ id: 1, targetFieldCode: 'SUPPLIER_BARCODE' }],
  }) as unknown as RevisionDetail;
  const lines = [
    line(9, { targetKind: 'REVISION_FIELD', revisionField: 'identitySupplierField', column: 1 }),
    line(12, { targetKind: 'MAPPING', targetFieldCode: 'SUPPLIER_BARCODE', column: 12 }),
    line(33, { targetKind: 'MAPPING', targetFieldCode: 'VKP1_PCT', column: 7 }),
    line(26, { column: 7 }),
    line(8, { targetKind: 'REVISION_FIELD', revisionField: 'recordDescriptionField', column: 2 }),
  ];

  it('een revisieveld-regel hoort bij haar revisieveld, een mappingregel bij haar doelcode', () => {
    expect(lineForRow(lines, { revisionField: 'identitySupplierField' })?.number).toBe(9);
    expect(lineForRow(lines, { mappingCode: 'SUPPLIER_BARCODE' })?.number).toBe(12);
    expect(lineForRow(lines, { revisionField: 'recordBasePriceField' })).toBeNull();
    expect(lineForRow(lines, { mappingCode: 'EAN' })).toBeNull();
  });

  it('regels zonder rij komen in "niet overgenomen", met een reden', () => {
    const uncovered = uncoveredLines(lines, revision);
    expect(uncovered.map((entry) => [entry.line.number, entry.reason])).toEqual([
      [33, 'niet aanwezig in deze versie'],
      [26, 'geen doelveld'],
      [8, 'niet aanwezig in deze versie'],
    ]);
  });
});

describe('LINK_CREATION_DEFAULT: standaard bij aanmaak van de koppeling', () => {
  const revision = revisionDetail(1, 1, {
    recordDescriptionField: null,
    mappings: [],
  }) as unknown as RevisionDetail;
  const vatLine = line(51, { targetKind: 'LINK_CREATION_DEFAULT', fixedValue: '21', column: 0 });
  const unitLine = line(45, { targetKind: 'LINK_CREATION_DEFAULT', fixedValue: 'ST' });
  const lines = [vatLine, unitLine, line(9, { targetKind: 'REVISION_FIELD', revisionField: 'identitySupplierField', column: 1 })];

  it('komt niet in "Niet overgenomen uit legacy", ook niet zonder rij in het leesbeeld', () => {
    expect(uncoveredLines(lines, revision).map((entry) => entry.line.number)).toEqual([]);
  });

  it('wordt herkend als naar de koppeling, in volgorde van de regels', () => {
    expect(linkCreationDefaultLines(lines).map((entry) => entry.number)).toEqual([51, 45]);
    expect(linkCreationDefaultLines([])).toEqual([]);
  });

  it('beschrijving toont de bron zonder ruwe code', () => {
    expect(describeLine(vatLine)).toBe('regel 51 · vaste waarde 21');
    expect(describeLine(unitLine)).toBe('regel 45 · vaste waarde ST');
  });

  it('technische details bevatten het doeltype als ruwe waarde', () => {
    expect(technicalLine(vatLine)).toBe('field=VELD51; target=LINK_CREATION_DEFAULT; fixedValue=21');
  });
});

describe('technicalLine', () => {
  it('geeft enkel gevulde ruwe waarden', () => {
    expect(technicalLine(line(33, { column: 7, calculation: '3', calculationValue: '1.5' }))).toBe(
      'field=VELD33; target=NONE; column=7; calculation=3; calculationValue=1.5',
    );
  });
});
