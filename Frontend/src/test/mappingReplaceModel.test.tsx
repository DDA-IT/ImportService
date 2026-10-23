/**
 * G2c-3 (E6: de vervangdialoog is weg; het extra-veldpaneel staat in `FieldsEditorMappings.test.tsx`) — het pure formulier
 * van een extra veld (`mappingReplaceModel.ts`) en de vertaaltabel met plakvak (`TableEditor`). Dekt: vooraf ingevuld met de
 * huidige mapping (ook een keten en legacy `values=`), een keten opbouwen met de drie vaste vakken (juiste body), de
 * client-vormvalidatie die blokkeert en het plakken met fouten in het voorbeeld.
 */

import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import type { MappingView } from '../api/types';
import { TableEditor } from '../features/setup/TableEditor';
import {
  buildMappingRequest,
  initialMappingForm,
  mappingProblems,
  parsePaste,
  type MappingForm,
  type TableRow,
} from '../features/setup/mappingReplaceModel';

const PLAIN: MappingView = {
  id: 7,
  revisionId: 100,
  sequenceNumber: 3,
  targetFieldCode: 'EAN',
  sourceReference: 'KOLOM_A',
  valueKind: 'SOURCE_FIELD',
  criticality: 'CRITICAL',
  valueMap: [],
  steps: [],
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
};

const MAPPED: MappingView = {
  ...PLAIN,
  required: true,
  maxLength: 13,
  defaultValue: 'LEEG',
  transformKind: 'MAP',
  transformConfig: 'caseSensitive=true;onMiss=KEEP_SOURCE',
  valueMap: [
    { source: 'A', target: '1' },
    { source: 'B', target: '2' },
  ],
};

const CHAINED: MappingView = {
  ...PLAIN,
  transformKind: 'CHAIN',
  transformConfig: 'decimalSeparator=,',
  steps: [
    { kind: 'SUBSTRING', config: 'start=2;length=3' },
    { kind: 'AFFIX', config: 'prefix=X' },
    { kind: 'MAP', config: 'caseSensitive=true;onMiss=KEEP_SOURCE' },
  ],
  valueMap: [{ source: 'AAA', target: 'Z' }],
};

const label = (kind: string) => kind;

function formOf(mapping: MappingView): MappingForm {
  return initialMappingForm(mapping).form;
}

function bodyOf(form: MappingForm, mapping: MappingView) {
  const built = buildMappingRequest(form, mapping, label, 'An Beslisser');
  if (!('body' in built)) {
    throw new Error(`body verwacht, problemen: ${built.problems.join(' | ')}`);
  }
  return built.body;
}

function problemsOf(form: MappingForm, mapping: MappingView): string {
  return mappingProblems(form, mapping, label).join('\n');
}

afterEach(() => cleanup());

describe('vooraf ingevuld', () => {
  it('vult kolom, vlaggen, tabel, hoofdlettergevoelig en onMiss uit de huidige mapping', () => {
    const { form, unsupported } = initialMappingForm(MAPPED);
    expect(unsupported).toBeNull();
    expect(form.sourceReference).toBe('KOLOM_A');
    expect(form.defaultValue).toBe('LEEG');
    expect(form.maxLength).toBe('13');
    expect(form.required).toBe(true);
    expect(form.chain).toBe(false);
    expect(form.single.kind).toBe('MAP');
    expect(form.single.settings).toEqual({ caseSensitive: 'true', onMiss: 'KEEP_SOURCE' });
    expect(form.rows[1]).toEqual({ source: 'B', target: '2' });
  });

  it('opnieuw opslaan zonder wijziging stuurt dezelfde mapping terug (nooit stil iets anders)', () => {
    expect(bodyOf(formOf(MAPPED), MAPPED)).toEqual({
      targetFieldCode: 'EAN',
      sourceReference: 'KOLOM_A',
      sequenceNumber: null,
      valueKind: 'SOURCE_FIELD',
      expectedPosition: null,
      fixedValue: null,
      defaultValue: 'LEEG',
      required: true,
      maxLength: 13,
      decimalScale: null,
      zeroAllowed: false,
      negativeAllowed: false,
      transformKind: 'MAP',
      transformConfig: 'caseSensitive=true;onMiss=KEEP_SOURCE',
      criticality: 'CRITICAL',
      createdBy: 'An Beslisser',
      valueMap: [
        { source: 'A', target: '1' },
        { source: 'B', target: '2' },
      ],
      steps: [],
    });
  });

  it('een keten komt in de drie vaste vakken; de notatie van de mapping blijft behouden', () => {
    const form = formOf(CHAINED);
    expect(form.chain).toBe(true);
    expect(form.stages[0].kind).toBe('SUBSTRING');
    expect(form.stages[0].settings.start).toBe('2');
    expect(form.stages[1].kind).toBe('AFFIX');
    expect(form.stages[1].settings.prefix).toBe('X');
    expect(form.stages[2].kind).toBe('MAP');
    expect(form.mappingExtra).toBe('decimalSeparator=,');

    const body = bodyOf(form, CHAINED);
    expect(body.transformKind).toBe('CHAIN');
    expect(body.transformConfig).toBe('decimalSeparator=,');
    expect(body.steps).toEqual([
      { kind: 'SUBSTRING', config: 'start=2;length=3' },
      { kind: 'AFFIX', config: 'prefix=X' },
      { kind: 'MAP', config: 'caseSensitive=true;onMiss=KEEP_SOURCE' },
    ]);
    expect(body.valueMap).toEqual([{ source: 'AAA', target: 'Z' }]);
  });

  it('een legacy MAP met values= in de configuratie wordt een gewone tabel', () => {
    const legacy: MappingView = { ...PLAIN, transformKind: 'MAP', transformConfig: 'values=A>1|B>;caseSensitive=true' };
    const { form } = initialMappingForm(legacy);
    expect(form.rows).toEqual([
      { source: 'A', target: '1' },
      { source: 'B', target: '' },
    ]);
    expect(form.single.settings).toEqual({ caseSensitive: 'true' });
  });

  it('een weergave zonder de aanmaakvelden is niet veilig te wijzigen', () => {
    const old = { id: 7, revisionId: 100, sequenceNumber: 3, targetFieldCode: 'EAN', sourceReference: 'A', valueKind: 'SOURCE_FIELD', criticality: 'CRITICAL' } as MappingView;
    expect(initialMappingForm(old).unsupported).toContain('niet alle gegevens');
  });
});

describe('een keten opbouwen', () => {
  it('uitknippen, voor-/achtervoegsel en MAP met tabel geven de juiste body', () => {
    const form = formOf(PLAIN);
    form.chain = true;
    form.stages = [
      { kind: 'SUBSTRING', settings: { start: '2', length: '4' }, extra: '' },
      { kind: 'AFFIX', settings: { prefix: 'ART-', suffix: '-X' }, extra: '' },
      { kind: 'MAP', settings: { caseSensitive: 'true', onMiss: 'KEEP_SOURCE' }, extra: '' },
    ];
    form.rows = [{ source: 'ART-1234-X', target: 'NIEUW' }];
    const body = bodyOf(form, PLAIN);
    expect(body.transformKind).toBe('CHAIN');
    expect(body.transformConfig).toBeNull();
    expect(body.steps).toEqual([
      { kind: 'SUBSTRING', config: 'start=2;length=4' },
      { kind: 'AFFIX', config: 'prefix=ART-;suffix=-X' },
      { kind: 'MAP', config: 'caseSensitive=true;onMiss=KEEP_SOURCE' },
    ]);
    expect(body.valueMap).toEqual([{ source: 'ART-1234-X', target: 'NIEUW' }]);
  });

  it('een lege keten blokkeert; één vak is genoeg; een rekenbewerking vraagt precies één bron', () => {
    const form = formOf(PLAIN);
    form.chain = true;
    expect(problemsOf(form, PLAIN)).toContain('minstens één bewerking');

    form.stages = [
      { kind: '', settings: {}, extra: '' },
      { kind: '', settings: {}, extra: '' },
      { kind: 'ADD', settings: {}, extra: '' },
    ];
    expect(problemsOf(form, PLAIN)).toContain('precies één van');
    form.stages[2] = { kind: 'ADD', settings: { operand: '1,5', operandField: 'ANDERE' }, extra: '' };
    expect(problemsOf(form, PLAIN)).toContain('precies één van');
    form.stages[2] = { kind: 'ADD', settings: { operand: '1,5' }, extra: '' };
    expect(problemsOf(form, PLAIN)).toBe('');
    expect(bodyOf(form, PLAIN).steps).toEqual([{ kind: 'ADD', config: 'operand=1,5' }]);
  });

  it('een enkele soort zonder keten: SPLIT met scheidingsteken "|" en deel 2', () => {
    const form = formOf(PLAIN);
    form.single = { kind: 'SPLIT', settings: { separator: '|', index: '2' }, extra: '' };
    const body = bodyOf(form, PLAIN);
    expect(body.transformKind).toBe('SPLIT');
    expect(body.transformConfig).toBe('separator=|;index=2');
    expect(body.steps).toEqual([]);
    expect(body.valueMap).toEqual([]);
  });

  it('MAP_CONTAINS vraagt minstens één rij met een nieuwe waarde en kent geen instellingen', () => {
    const form = formOf(PLAIN);
    form.single = { kind: 'MAP_CONTAINS', settings: {}, extra: '' };
    expect(problemsOf(form, PLAIN)).toContain('minstens één rij');
    form.rows = [
      { source: 'ROOD', target: '' },
      { source: 'BLAUW', target: 'B' },
    ];
    expect(problemsOf(form, PLAIN)).toContain('Vertaaltabel rij 1: bij omzetten op een deel');
    form.rows = [{ source: 'BLAUW', target: 'B' }];
    const body = bodyOf(form, PLAIN);
    expect(body.transformKind).toBe('MAP_CONTAINS');
    expect(body.valueMap).toEqual([{ source: 'BLAUW', target: 'B' }]);
    expect(body.transformConfig).toBeNull();
  });
});

describe('client-validatie (enkel vorm) blokkeert', () => {
  it('lege kolom, onleesbare getallen en een dubbele bronwaarde: geen body', () => {
    const form = formOf(MAPPED);
    form.sourceReference = '  ';
    form.maxLength = '12x';
    form.decimalScale = '-1';
    form.rows = [
      { source: 'A', target: 'a;b' },
      { source: 'A', target: '2' },
    ];
    const problems = problemsOf(form, MAPPED);
    expect(problems).toContain('Vul de kolom in het bronbestand in.');
    expect(problems).toContain('Bij de maximale lengte hoort een heel getal');
    expect(problems).toContain('Bij het aantal decimalen hoort een heel getal');
    expect(problems).toContain('Vertaaltabel rij 2: de bronwaarde "A" komt meer dan één keer voor.');
    expect('problems' in buildMappingRequest(form, MAPPED, label, 'An')).toBe(true);
  });

  it('een ";" in een voorvoegsel kan niet bewaard worden; te lange vertaalwaarden en een lange configuratie ook niet', () => {
    const form = formOf(PLAIN);
    form.single = { kind: 'PREFIX', settings: { prefix: 'A;B' }, extra: '' };
    expect(problemsOf(form, PLAIN)).toContain('een ";" kan hier niet bewaard worden');
    form.single = { kind: 'PREFIX', settings: { prefix: 'x'.repeat(1001) }, extra: '' };
    expect(problemsOf(form, PLAIN)).toContain('langer dan 1000 tekens');

    form.single = { kind: 'MAP', settings: {}, extra: '' };
    form.rows = [{ source: 'x'.repeat(51), target: 'y'.repeat(51) }];
    const text = problemsOf(form, PLAIN);
    expect(text).toContain('de bronwaarde is langer dan 50 tekens');
    expect(text).toContain('de nieuwe waarde is langer dan 50 tekens');
  });
});

describe('parsePaste', () => {
  it('te lange waarden en een dubbele bron in de plak zelf zijn fouten', () => {
    const long = 'x'.repeat(51);
    const result = parsePaste(`${long};1\nA;1\nA;2`, []);
    expect(result.rows).toEqual([{ source: 'A', target: '1' }]);
    expect(result.errors).toEqual([
      'Regel 1: een waarde is langer dan 50 tekens.',
      'Regel 3: de bronwaarde "A" komt al voor.',
    ]);
  });
});

function Harness({ initial }: { initial: TableRow[] }) {
  const [rows, setRows] = useState<TableRow[]>(initial);
  return <TableEditor kind="MAP" rows={rows} onChange={setRows} uid="t" />;
}

describe('de vertaaltabel (TableEditor)', () => {
  it('rijen toevoegen, aanpassen en verwijderen', () => {
    render(<Harness initial={[]} />);
    fireEvent.click(screen.getByTestId('value-map-add'));
    fireEvent.click(screen.getByTestId('value-map-add'));
    fireEvent.change(screen.getByTestId('value-map-source-0'), { target: { value: 'ROOD' } });
    fireEvent.change(screen.getByTestId('value-map-source-1'), { target: { value: 'BLAUW' } });
    fireEvent.change(screen.getByTestId('value-map-target-1'), { target: { value: 'B' } });
    fireEvent.click(screen.getByTestId('value-map-remove-0'));
    expect(screen.queryByTestId('value-map-row-1')).not.toBeInTheDocument();
    expect((screen.getByTestId('value-map-source-0') as HTMLInputElement).value).toBe('BLAUW');
    expect((screen.getByTestId('value-map-target-0') as HTMLInputElement).value).toBe('B');
  });

  it('plakken: het voorbeeld toont de fouten, er wordt niets toegevoegd tot de tekst klopt', () => {
    render(<Harness initial={[{ source: 'A', target: '1' }]} />);

    const bad = ['B\t2', 'enkelekolom', ';leeg', 'A;9', 'C;3;extra', 'D;4'].join('\n');
    fireEvent.change(screen.getByTestId('value-map-paste'), { target: { value: bad } });

    expect(screen.getByTestId('value-map-preview')).toHaveTextContent('2 rijen klaar om toe te voegen, 4 met een fout');
    const errors = within(screen.getByTestId('value-map-preview-errors')).getAllByRole('listitem').map((li) => li.textContent);
    expect(errors).toEqual([
      'Regel 2: verwacht twee kolommen (bron en nieuwe waarde), gevonden 1.',
      'Regel 3: de bronwaarde ontbreekt.',
      'Regel 4: de bronwaarde "A" komt al voor.',
      'Regel 5: verwacht twee kolommen (bron en nieuwe waarde), gevonden 3.',
    ]);
    expect(screen.getByTestId('value-map-apply')).toBeDisabled();
    expect(screen.queryByTestId('value-map-row-1')).not.toBeInTheDocument();

    // Herstellen: enkel geldige regels, tab én puntkomma door elkaar (lege regels tellen niet; een lege nieuwe waarde mag).
    fireEvent.change(screen.getByTestId('value-map-paste'), { target: { value: 'B\t2\nD;4\n\nE\t' } });
    expect(screen.getByTestId('value-map-apply')).toBeEnabled();
    fireEvent.click(screen.getByTestId('value-map-apply'));

    expect((screen.getByTestId('value-map-source-1') as HTMLInputElement).value).toBe('B');
    expect((screen.getByTestId('value-map-target-2') as HTMLInputElement).value).toBe('4');
    expect((screen.getByTestId('value-map-source-3') as HTMLInputElement).value).toBe('E');
    expect((screen.getByTestId('value-map-target-3') as HTMLInputElement).value).toBe('');
    expect((screen.getByTestId('value-map-paste') as HTMLTextAreaElement).value).toBe('');
  });
});
