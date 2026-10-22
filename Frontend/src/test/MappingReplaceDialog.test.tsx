/**
 * G2c-3 — de vervangdialoog voor één extra veld (`docs/design/g2-design.md` §G2c). Dekt: vooraf ingevuld met de huidige
 * mapping (ook een keten en legacy `values=`), een keten opbouwen met de drie vaste vakken (juiste PUT-body), de
 * vertaaltabel bewerken en plakken (met fouten in het voorbeeld), `caseSensitive`/`onMiss`, de client-vormvalidatie die
 * blokkeert, de serverfout in de dialoog, en dat de knop enkel op een concept en met MANAGE bruikbaar is.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActorProvider } from '../actor/ActorContext';
import type { MappingView, RevisionDetail } from '../api/types';
import { ReplaceMappingAction } from '../features/setup/MappingReplaceDialog';
import { RevisionDetailSection } from '../features/setup/RevisionDetailSection';
import { initialMappingForm, parsePaste } from '../features/setup/mappingReplaceModel';
import { errorResponse, jsonResponse, mockApi } from './legacyConfigFixtures';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';

const REVISION = { id: 100, definitionId: 10, status: 'DRAFT' } as unknown as RevisionDetail;

const FULL_REVISION: RevisionDetail = {
  id: 100,
  definitionId: 10,
  revisionNumber: 1,
  status: 'DRAFT',
  basedOnRevisionId: null,
  changeReason: 'Overname uit legacy',
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
  structureFieldReferenceKind: 'COLUMN_INDEX',
  structureExpectedColumnCount: null,
  accessDeliverySetKind: 'FULL_SNAPSHOT',
  recordBasePriceField: '26',
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
  createdAt: '2026-10-21T08:00:00Z',
  createdBy: 'An Beslisser',
  createdBySubject: 'test-sub',
  updatedAt: null,
  approvedAt: null,
  approvedBy: null,
  approvedBySubject: null,
  mappings: [],
  filters: [],
  fieldCriticalities: [],
  bookmarks: [],
  bookmarkValues: [],
};

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

type Overrides = { identity?: typeof TEST_IDENTITY; revision?: RevisionDetail };

function renderAction(mapping: MappingView, handler: Parameters<typeof mockApi>[0], overrides: Overrides = {}) {
  const api = mockApi(handler);
  const onReplaced = vi.fn();
  render(
    <ActorProvider identity={overrides.identity ?? TEST_IDENTITY}>
      <ReplaceMappingAction revision={overrides.revision ?? REVISION} mapping={mapping} onReplaced={onReplaced} />
    </ActorProvider>,
  );
  return { api, onReplaced };
}

const replaced = (): Response => jsonResponse({ ...PLAIN, id: 8 });

function open() {
  fireEvent.click(screen.getByTestId('replace-mapping-7'));
}

function type(testId: string, value: string) {
  fireEvent.change(screen.getByTestId(testId), { target: { value } });
}

function confirm() {
  fireEvent.click(screen.getByRole('button', { name: 'Extra veld vervangen' }));
}

function put(api: ReturnType<typeof mockApi>) {
  const calls = api.writes();
  expect(calls).toHaveLength(1);
  const call = calls[0]!;
  expect(call.method).toBe('PUT');
  expect(call.url).toContain('/setup/revisions/100/mappings/7');
  return call.body as Record<string, unknown>;
}

afterEach(() => cleanup());

describe('vooraf ingevuld', () => {
  it('toont de huidige mapping: kolom, vlaggen, tabel, hoofdlettergevoelig en onMiss', () => {
    renderAction(MAPPED, replaced);
    open();

    expect((screen.getByTestId('mapping-source') as HTMLInputElement).value).toBe('KOLOM_A');
    expect((screen.getByTestId('mapping-default') as HTMLInputElement).value).toBe('LEEG');
    expect((screen.getByTestId('mapping-max-length') as HTMLInputElement).value).toBe('13');
    expect((screen.getByTestId('mapping-required') as HTMLInputElement).checked).toBe(true);
    expect((screen.getByTestId('mapping-kind') as HTMLSelectElement).value).toBe('MAP');
    expect((screen.getByTestId('stage-single-caseSensitive') as HTMLInputElement).checked).toBe(true);
    expect((screen.getByTestId('stage-single-onMiss') as HTMLSelectElement).value).toBe('KEEP_SOURCE');
    expect((screen.getByTestId('value-map-source-1') as HTMLInputElement).value).toBe('B');
    expect((screen.getByTestId('value-map-target-1') as HTMLInputElement).value).toBe('2');
  });

  it('opnieuw opslaan zonder wijziging stuurt dezelfde mapping terug (nooit stil iets anders)', async () => {
    const { api, onReplaced } = renderAction(MAPPED, replaced);
    open();
    confirm();

    await waitFor(() => expect(onReplaced).toHaveBeenCalledTimes(1));
    expect(put(api)).toEqual({
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

  it('een keten komt in de drie vaste vakken; de notatie van de mapping blijft behouden', async () => {
    const { api, onReplaced } = renderAction(CHAINED, replaced);
    open();

    expect((screen.getByTestId('mapping-kind') as HTMLSelectElement).value).toBe('CHAIN');
    expect((screen.getByTestId('stage-0-kind') as HTMLSelectElement).value).toBe('SUBSTRING');
    expect((screen.getByTestId('stage-0-start') as HTMLInputElement).value).toBe('2');
    expect((screen.getByTestId('stage-1-kind') as HTMLSelectElement).value).toBe('AFFIX');
    expect((screen.getByTestId('stage-1-prefix') as HTMLInputElement).value).toBe('X');
    expect((screen.getByTestId('stage-2-kind') as HTMLSelectElement).value).toBe('MAP');
    expect((screen.getByTestId('mapping-extra') as HTMLInputElement).value).toBe('decimalSeparator=,');

    confirm();
    await waitFor(() => expect(onReplaced).toHaveBeenCalled());
    const body = put(api);
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

  it('een weergave zonder de aanmaakvelden is niet veilig te wijzigen: de dialoog blokkeert', () => {
    const old = { id: 7, revisionId: 100, sequenceNumber: 3, targetFieldCode: 'EAN', sourceReference: 'A', valueKind: 'SOURCE_FIELD', criticality: 'CRITICAL' } as MappingView;
    renderAction(old, replaced);
    open();
    expect(screen.getByTestId('mapping-problems')).toHaveTextContent('niet alle gegevens');
    expect(screen.getByRole('button', { name: 'Extra veld vervangen' })).toBeDisabled();
  });
});

describe('een keten opbouwen', () => {
  it('uitknippen, voor-/achtervoegsel en MAP met tabel geven de juiste PUT-body', async () => {
    const { api, onReplaced } = renderAction(PLAIN, replaced);
    open();

    type('mapping-kind', 'CHAIN');
    type('stage-0-kind', 'SUBSTRING');
    type('stage-0-start', '2');
    type('stage-0-length', '4');
    type('stage-1-kind', 'AFFIX');
    type('stage-1-prefix', 'ART-');
    type('stage-1-suffix', '-X');
    type('stage-2-kind', 'MAP');
    fireEvent.click(screen.getByTestId('stage-2-caseSensitive'));
    type('stage-2-onMiss', 'KEEP_SOURCE');
    fireEvent.click(screen.getByTestId('value-map-add'));
    type('value-map-source-0', 'ART-1234-X');
    type('value-map-target-0', 'NIEUW');

    confirm();
    await waitFor(() => expect(onReplaced).toHaveBeenCalled());
    const body = put(api);
    expect(body.transformKind).toBe('CHAIN');
    expect(body.transformConfig).toBeNull();
    expect(body.steps).toEqual([
      { kind: 'SUBSTRING', config: 'start=2;length=4' },
      { kind: 'AFFIX', config: 'prefix=ART-;suffix=-X' },
      { kind: 'MAP', config: 'caseSensitive=true;onMiss=KEEP_SOURCE' },
    ]);
    expect(body.valueMap).toEqual([{ source: 'ART-1234-X', target: 'NIEUW' }]);
  });

  it('een lege keten blokkeert; één vak is genoeg; een rekenbewerking vraagt precies één bron', async () => {
    const { api, onReplaced } = renderAction(PLAIN, replaced);
    open();
    type('mapping-kind', 'CHAIN');
    expect(screen.getByTestId('mapping-problems')).toHaveTextContent('minstens één bewerking');

    type('stage-2-kind', 'ADD');
    expect(screen.getByTestId('mapping-problems')).toHaveTextContent('precies één van');
    type('stage-2-operand', '1,5');
    type('stage-2-operandField', 'ANDERE');
    expect(screen.getByTestId('mapping-problems')).toHaveTextContent('precies één van');
    type('stage-2-operandField', '');
    expect(screen.queryByTestId('mapping-problems')).not.toBeInTheDocument();

    confirm();
    await waitFor(() => expect(onReplaced).toHaveBeenCalled());
    expect(put(api).steps).toEqual([{ kind: 'ADD', config: 'operand=1,5' }]);
  });

  it('een enkele soort zonder keten: SPLIT met scheidingsteken "|" en deel 2', async () => {
    const { api, onReplaced } = renderAction(PLAIN, replaced);
    open();
    type('mapping-kind', 'SPLIT');
    type('stage-single-separator', '|');
    type('stage-single-index', '2');

    confirm();
    await waitFor(() => expect(onReplaced).toHaveBeenCalled());
    const body = put(api);
    expect(body.transformKind).toBe('SPLIT');
    expect(body.transformConfig).toBe('separator=|;index=2');
    expect(body.steps).toEqual([]);
    expect(body.valueMap).toEqual([]);
  });
});

describe('de vertaaltabel', () => {
  it('rijen toevoegen, aanpassen en verwijderen; MAP_CONTAINS vraagt een nieuwe waarde', async () => {
    const { api, onReplaced } = renderAction(PLAIN, replaced);
    open();
    type('mapping-kind', 'MAP_CONTAINS');
    expect(screen.getByTestId('mapping-problems')).toHaveTextContent('minstens één rij');

    fireEvent.click(screen.getByTestId('value-map-add'));
    fireEvent.click(screen.getByTestId('value-map-add'));
    type('value-map-source-0', 'ROOD');
    type('value-map-source-1', 'BLAUW');
    expect(screen.getByTestId('mapping-problems')).toHaveTextContent('Vertaaltabel rij 1: bij omzetten op een deel');
    type('value-map-target-0', 'R');
    type('value-map-target-1', 'B');
    fireEvent.click(screen.getByTestId('value-map-remove-0'));
    expect(screen.queryByTestId('value-map-row-1')).not.toBeInTheDocument();
    expect((screen.getByTestId('value-map-source-0') as HTMLInputElement).value).toBe('BLAUW');

    confirm();
    await waitFor(() => expect(onReplaced).toHaveBeenCalled());
    const body = put(api);
    expect(body.transformKind).toBe('MAP_CONTAINS');
    expect(body.valueMap).toEqual([{ source: 'BLAUW', target: 'B' }]);
    // MAP_CONTAINS kent geen instellingen: geen hoofdlettergevoeligheid en geen onMiss.
    expect(body.transformConfig).toBeNull();
  });

  it('plakken: het voorbeeld toont de fouten, er wordt niets toegevoegd tot de tekst klopt', () => {
    renderAction({ ...PLAIN, transformKind: 'MAP', valueMap: [{ source: 'A', target: '1' }] }, replaced);
    open();

    const bad = ['B\t2', 'enkelekolom', ';leeg', 'A;9', 'C;3;extra', 'D;4'].join('\n');
    type('value-map-paste', bad);

    const preview = screen.getByTestId('value-map-preview');
    expect(preview).toHaveTextContent('2 rijen klaar om toe te voegen, 4 met een fout');
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
    type('value-map-paste', 'B\t2\nD;4\n\nE\t');
    expect(screen.getByTestId('value-map-apply')).toBeEnabled();
    fireEvent.click(screen.getByTestId('value-map-apply'));

    expect((screen.getByTestId('value-map-source-1') as HTMLInputElement).value).toBe('B');
    expect((screen.getByTestId('value-map-target-2') as HTMLInputElement).value).toBe('4');
    expect((screen.getByTestId('value-map-source-3') as HTMLInputElement).value).toBe('E');
    expect((screen.getByTestId('value-map-target-3') as HTMLInputElement).value).toBe('');
    expect((screen.getByTestId('value-map-paste') as HTMLTextAreaElement).value).toBe('');
  });

  it('parsePaste: te lange waarden en een dubbele bron in de plak zelf zijn fouten', () => {
    const long = 'x'.repeat(51);
    const result = parsePaste(`${long};1\nA;1\nA;2`, []);
    expect(result.rows).toEqual([{ source: 'A', target: '1' }]);
    expect(result.errors).toEqual([
      'Regel 1: een waarde is langer dan 50 tekens.',
      'Regel 3: de bronwaarde "A" komt al voor.',
    ]);
  });
});

describe('client-validatie (enkel vorm) blokkeert', () => {
  it('lege kolom, onleesbare getallen, ";" in een waarde en een dubbele bronwaarde: geen PUT', () => {
    const { api } = renderAction(MAPPED, replaced);
    open();

    type('mapping-source', '  ');
    type('mapping-max-length', '12x');
    type('mapping-decimal-scale', '-1');
    type('stage-single-onMiss', 'REJECT');
    type('value-map-source-1', 'A');
    type('value-map-target-0', 'a;b');

    const problems = screen.getByTestId('mapping-problems');
    expect(problems).toHaveTextContent('Vul de kolom in het bronbestand in.');
    expect(problems).toHaveTextContent('Bij de maximale lengte hoort een heel getal');
    expect(problems).toHaveTextContent('Bij het aantal decimalen hoort een heel getal');
    expect(problems).toHaveTextContent('Vertaaltabel rij 2: de bronwaarde "A" komt meer dan één keer voor.');
    expect(screen.getByRole('button', { name: 'Extra veld vervangen' })).toBeDisabled();
    expect(screen.getByTestId('confirm-blocked-reason')).toBeInTheDocument();
    fireEvent.submit(screen.getByTestId('mapping-replace-dialog').closest('form')!);
    expect(api.writes()).toHaveLength(0);
  });

  it('een ";" in een voorvoegsel kan niet bewaard worden; te lange vertaalwaarden en een lange configuratie ook niet', () => {
    renderAction(PLAIN, replaced);
    open();

    type('mapping-kind', 'PREFIX');
    type('stage-single-prefix', 'A;B');
    expect(screen.getByTestId('mapping-problems')).toHaveTextContent('een ";" kan hier niet bewaard worden');
    type('stage-single-prefix', 'x'.repeat(1001));
    expect(screen.getByTestId('mapping-problems')).toHaveTextContent('langer dan 1000 tekens');

    type('mapping-kind', 'MAP');
    fireEvent.click(screen.getByTestId('value-map-add'));
    type('value-map-source-0', 'x'.repeat(51));
    type('value-map-target-0', 'y'.repeat(51));
    const text = screen.getByTestId('mapping-problems').textContent ?? '';
    expect(text).toContain('de bronwaarde is langer dan 50 tekens');
    expect(text).toContain('de nieuwe waarde is langer dan 50 tekens');
  });
});

describe('antwoord van de server', () => {
  it('een 400 blijft in de open dialoog staan; er wordt niet herladen en niets verborgen', async () => {
    const { api, onReplaced } = renderAction(MAPPED, () =>
      errorResponse(400, 'CONFIG_TRANSFORM_INVALID', 'step 2 (MAP) cannot follow step 3'),
    );
    open();
    confirm();

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('De bewerking van dit extra veld klopt niet');
    expect(alert).toHaveTextContent('step 2 (MAP) cannot follow step 3');
    expect(onReplaced).not.toHaveBeenCalled();
    expect(screen.getByTestId('mapping-replace-dialog')).toBeInTheDocument();
    expect(api.writes()).toHaveLength(1);
  });

  it('een 409 REVISION_NOT_EDITABLE wordt getoond', async () => {
    renderAction(PLAIN, () => errorResponse(409, 'REVISION_NOT_EDITABLE'));
    open();
    confirm();
    expect(await screen.findByRole('alert')).toHaveTextContent('Deze versie is niet bewerkbaar');
  });

  it('een geslaagde vervanging sluit de dialoog en meldt het aan de ouder', async () => {
    const { onReplaced } = renderAction(PLAIN, replaced);
    open();
    confirm();
    await waitFor(() => expect(onReplaced).toHaveBeenCalledTimes(1));
    expect(screen.queryByTestId('mapping-replace-dialog')).not.toBeInTheDocument();
  });
});

describe('wie en wanneer', () => {
  it('zonder MANAGE is de knop uitgeschakeld', () => {
    renderAction(PLAIN, replaced, { identity: testIdentityWith('catalogImport.read') });
    expect(screen.getByTestId('replace-mapping-7')).toBeDisabled();
  });

  it('op een actieve versie is de knop uitgeschakeld', () => {
    renderAction(PLAIN, replaced, { revision: { ...REVISION, status: 'ACTIVE' } as RevisionDetail });
    expect(screen.getByTestId('replace-mapping-7')).toBeDisabled();
  });

  it('het revisiedetail toont de knop alleen op een concept', async () => {
    const detail = (status: RevisionDetail['status']): RevisionDetail => ({ ...FULL_REVISION, status, mappings: [PLAIN] });

    for (const [status, expected] of [
      ['DRAFT', true],
      ['ACTIVE', false],
    ] as const) {
      mockApi(() => jsonResponse(detail(status)));
      const { unmount } = render(
        <ActorProvider identity={TEST_IDENTITY}>
          <RevisionDetailSection definitionId={10} revisionId={100} onRevisionChanged={() => undefined} />
        </ActorProvider>,
      );
      await screen.findByTestId('revision-mappings');
      expect(screen.queryByTestId('replace-mapping-7') !== null).toBe(expected);
      unmount();
    }
  });
});
