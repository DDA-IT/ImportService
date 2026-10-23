/**
 * E2 — extra velden (mappings) bewerken in het Velden-grid van "Bestand en velden" (beslissingslog 2026-10-23 "Nieuwe
 * bewerkervaring", O1/O8): het potlood per extra veld, het zijpaneel (kolom, bewerkingschips, vertaaltabel, kritiek,
 * verplicht, Meer) met `PUT /setup/revisions/{id}/mappings/{mid}`, [Niet meer gebruiken] met bevestiging (`DELETE`, 409
 * `CONFIG_BOOKMARK_*`), "Veld toevoegen" en "Nog niet gekoppeld" met `POST .../mappings` (409 `MAPPING_TARGET_IN_USE`) en de
 * weergavegroep Verkoopprijs 1-5 (%).
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { emptyMappingForm } from './mappingFixtures';
import { ActorProvider } from '../actor/ActorContext';
import type { FieldCatalogResponse, RevisionDetail } from '../api/types';
import { TEST_IDENTITY } from './testIdentity';
import { catalogueFromResponse } from '../features/setup/fieldsGridModel';
import { MappingPanel, type MappingPanelMode } from '../features/setup/MappingPanel';
import {
  defaultFieldCatalog,
  definition,
  emptySetupState,
  installSetupServer,
  jsonResponse,
  link,
  organisation,
  renderRoutes,
  revisionDetail,
  task,
  type SetupState,
} from './setupServer';
import { initialMappingForm } from '../features/setup/mappingReplaceModel';
import { activeOps, setOp, unsupportedKinds } from '../features/setup/mappingPanelModel';

const originalFetch = global.fetch;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  global.fetch = originalFetch;
});

function mapping(id: number, code: string, over: Record<string, unknown> = {}) {
  return {
    id,
    revisionId: 100,
    sequenceNumber: id - 5000,
    targetFieldCode: code,
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

const EAN = mapping(5001, 'EAN', {
  // Een referentieveld is altijd kritiek (par. 15.1).
  criticality: 'CRITICAL',
  sourceReference: 'BARCODE',
  transformKind: 'CHAIN',
  steps: [{ kind: 'PREFIX', config: 'prefix=0' }],
});
const BRAND = mapping(5002, 'BRAND', { sourceReference: '', criticality: 'CRITICAL', required: true });
const UNIT = mapping(5003, 'UNIT', {
  sourceReference: 'EENHEID',
  transformKind: 'MAP',
  valueMap: [{ source: 'PCE', target: 'ST' }],
});

function state(mappings: Record<string, unknown>[], detail: Record<string, unknown> = {}): SetupState {
  const s = emptySetupState();
  s.organisations = [organisation(1, { code: 'VROOAM', name: 'Vrooam BV' })];
  s.definitions = [
    definition(10, 1, {
      name: 'Vrooam artikelbestand',
      activeRevisionId: 99,
      draftRevisionId: 100,
      draftRevisionNumber: 4,
    }),
  ];
  s.links = [link(501, 10)];
  s.tasks = [task(7, 501, 10, { activeRevisionId: 99 })];
  s.revisions[10] = [
    { id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT' },
    { id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' },
  ];
  s.details[99] = revisionDetail(99, 10, { revisionNumber: 3, status: 'ACTIVE', identityUppercase: false });
  s.details[100] = revisionDetail(100, 10, {
    revisionNumber: 4,
    status: 'DRAFT',
    identityUppercase: false,
    identitySupplierFixedValue: null,
    identitySupplierGroupFixedValue: null,
    mappings,
    ...detail,
  });
  return s;
}

type Call = { method: string; url: string; body: Record<string, unknown> | null };
type Options = { put?: () => Response; post?: () => Response; del?: () => Response };

function serve(s: SetupState, options: Options = {}): Call[] {
  const calls: Call[] = [];
  installSetupServer(s, (url, init) => {
    const method = (init?.method ?? 'GET').toUpperCase();
    const mappingUrl = /\/setup\/revisions\/100\/mappings(\/(\d+))?$/.exec(url);
    if (mappingUrl === null || method === 'GET') {
      return null;
    }
    const body = init?.body === undefined ? null : (JSON.parse(String(init.body)) as Record<string, unknown>);
    calls.push({ method, url, body });
    if (method === 'PUT') {
      return options.put?.() ?? jsonResponse({ ...(body ?? {}), id: 9001, revisionId: 100, sequenceNumber: 1 });
    }
    if (method === 'POST') {
      return options.post?.() ?? jsonResponse({ ...(body ?? {}), id: 9002, revisionId: 100, sequenceNumber: 9 }, 201);
    }
    return options.del?.() ?? new Response(null, { status: 204 });
  });
  return calls;
}

async function openEditor(s: SetupState, options: Options = {}, path = '/setup/suppliers/1/fields?definitie=10&versie=100&bewerken=1') {
  const calls = serve(s, options);
  renderRoutes(path);
  await screen.findByTestId('read-fields');
  return calls;
}

async function openMappingPanel(label: string): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: `Wijzigen: ${label}` }));
  return screen.findByRole('dialog', { name: `Wijzigen: ${label}` });
}

function apply(panel: HTMLElement) {
  fireEvent.click(within(panel).getByTestId('panel-apply'));
}

function expectNoRawCodes(root: HTMLElement) {
  const text = root.textContent ?? '';
  expect(text).not.toMatch(/\b[A-Z]{2,}_[A-Z_]+\b/);
}

describe('potlood bij extra velden', () => {
  it('staat enkel in Bewerken, per doelveld', async () => {
    await openEditor(state([EAN, BRAND]), {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    expect(screen.queryByRole('button', { name: 'Wijzigen: EAN-barcode' })).not.toBeInTheDocument();
    expect(screen.queryByTestId('add-field')).not.toBeInTheDocument();
    expect(screen.queryByTestId('unmapped-toggle')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('mode-edit'));
    expect(screen.getByRole('button', { name: 'Wijzigen: EAN-barcode' })).toBeInTheDocument();
    expect(screen.getByTestId('edit-mapping-BRAND')).toBeInTheDocument();
    expect(screen.getByTestId('add-field')).toHaveTextContent('Veld toevoegen');
  });
});

describe('extra veld wijzigen', () => {
  it('kolom en bewerkingschips: Toepassen stuurt de PUT-body, toont "Bewaard in het concept" en sluit het paneel', async () => {
    const calls = await openEditor(state([EAN, BRAND]));
    const panel = await openMappingPanel('EAN-barcode');
    expectNoRawCodes(panel);
    // Een kopregel (kolomnamen): het label past zich aan.
    const column = within(panel).getByLabelText('Kolomnaam');
    expect(column).toHaveValue('BARCODE');
    expect(within(panel).getByTestId('op-fix')).toHaveAttribute('aria-pressed', 'true');
    expect(within(panel).getByTestId('op-upper')).toHaveAttribute('aria-pressed', 'false');
    expect(within(panel).getByTestId('op-fix-prefix')).toHaveValue('0');

    fireEvent.change(column, { target: { value: 'EAN_KOLOM' } });
    fireEvent.click(within(panel).getByTestId('op-upper'));
    expect(within(panel).getByTestId('op-upper')).toHaveAttribute('aria-pressed', 'true');
    apply(panel);

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.method).toBe('PUT');
    expect(calls[0]!.url).toMatch(/\/setup\/revisions\/100\/mappings\/5001$/);
    expect(calls[0]!.body).toMatchObject({
      targetFieldCode: 'EAN',
      sourceReference: 'EAN_KOLOM',
      valueKind: 'SOURCE_FIELD',
      transformKind: 'CHAIN',
      criticality: 'CRITICAL',
      required: false,
      createdBy: 'An Beslisser',
      steps: [
        { kind: 'PREFIX', config: 'prefix=0' },
        { kind: 'UPPER', config: null },
      ],
    });
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bewaard in het concept');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('bij herkenning op kolomnummer heet het veld Kolomnummer', async () => {
    await openEditor(state([EAN], { structureFieldReferenceKind: 'COLUMN_INDEX' }));
    const panel = await openMappingPanel('EAN-barcode');
    expect(within(panel).getByLabelText('Kolomnummer')).toBeInTheDocument();
    expect(within(panel).queryByLabelText('Kolomnaam')).not.toBeInTheDocument();
  });

  it('delen door: één waardebewerking (de andere sluiten elkaar uit) en uitknippen samen met een voorvoegsel', async () => {
    const calls = await openEditor(state([mapping(5001, 'AKP_PCT', { sourceReference: 'PRIJS' })]));
    const panel = await openMappingPanel('Aankoopprijs (%)');
    fireEvent.click(within(panel).getByTestId('op-divide'));
    fireEvent.change(within(panel).getByTestId('op-divide-operand'), { target: { value: '100' } });
    fireEvent.click(within(panel).getByTestId('op-percentage'));
    // Percentage vervangt Delen door.
    expect(within(panel).getByTestId('op-divide')).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(within(panel).getByTestId('op-divide'));
    fireEvent.change(within(panel).getByTestId('op-divide-operand'), { target: { value: '100' } });
    fireEvent.click(within(panel).getByTestId('op-cut'));
    fireEvent.change(within(panel).getByTestId('op-cut-start'), { target: { value: '2' } });
    fireEvent.change(within(panel).getByTestId('op-cut-length'), { target: { value: '5' } });
    apply(panel);

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.body).toMatchObject({
      transformKind: 'CHAIN',
      steps: [
        { kind: 'SUBSTRING', config: 'start=2;length=5' },
        { kind: 'DIVIDE', config: 'operand=100' },
      ],
    });
  });

  it('vertaling: tabel met plakken en "Bij een onbekende waarde" komen in de body', async () => {
    const calls = await openEditor(state([UNIT]));
    const panel = await openMappingPanel('Eenheid');
    expect(within(panel).getByTestId('op-translate')).toHaveAttribute('aria-pressed', 'true');
    expect(within(panel).getByTestId('value-map-source-0')).toHaveValue('PCE');
    expect(within(panel).getByTestId('op-translate-onMiss')).toHaveValue('REJECT');

    fireEvent.click(within(panel).getByTestId('value-map-add'));
    fireEvent.change(within(panel).getByTestId('value-map-source-1'), { target: { value: 'MTR' } });
    fireEvent.change(within(panel).getByTestId('value-map-target-1'), { target: { value: 'M' } });
    fireEvent.change(within(panel).getByTestId('value-map-paste'), { target: { value: 'KGM\tKG' } });
    fireEvent.click(within(panel).getByTestId('value-map-apply'));
    expect(within(panel).getByTestId('value-map-source-2')).toHaveValue('KGM');
    fireEvent.change(within(panel).getByTestId('op-translate-onMiss'), { target: { value: 'KEEP_SOURCE' } });
    apply(panel);

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.body).toMatchObject({
      targetFieldCode: 'UNIT',
      transformKind: 'MAP',
      transformConfig: 'onMiss=KEEP_SOURCE',
      valueMap: [
        { source: 'PCE', target: 'ST' },
        { source: 'MTR', target: 'M' },
        { source: 'KGM', target: 'KG' },
      ],
      steps: [],
    });
  });

  it('een vertaling zonder rijen wordt niet verstuurd: de melding staat in het paneel', async () => {
    const calls = await openEditor(state([EAN]));
    const panel = await openMappingPanel('EAN-barcode');
    fireEvent.click(within(panel).getByTestId('op-translate'));
    apply(panel);
    expect(await within(panel).findByTestId('panel-problems')).toHaveTextContent('Vertaaltabel: voeg minstens één rij toe.');
    expect(calls).toHaveLength(0);
  });

  it('kritiek, verplicht en Meer komen in de body', async () => {
    const calls = await openEditor(state([BRAND]));
    const panel = await openMappingPanel('Merk');
    expect(within(panel).getByText('Wordt in Prodis beheerd; een levering overschrijft dit niet.')).toBeInTheDocument();
    expect(within(panel).getByRole('radio', { name: 'Kritiek' })).toBeChecked();
    expect(within(panel).getByRole('radio', { name: 'Ja' })).toBeChecked();

    // Zonder kolom is er een melding; niets verstuurd.
    apply(panel);
    expect(await within(panel).findByTestId('panel-problems')).toHaveTextContent('Vul de kolom in het bronbestand in.');
    expect(calls).toHaveLength(0);

    fireEvent.change(within(panel).getByLabelText('Kolomnaam'), { target: { value: 'MERK' } });
    fireEvent.click(within(panel).getByRole('radio', { name: 'Niet kritiek' }));
    fireEvent.click(within(panel).getByRole('radio', { name: 'Nee' }));
    fireEvent.click(within(panel).getByTestId('panel-more'));
    fireEvent.change(within(panel).getByTestId('more-maxLength'), { target: { value: '40' } });
    fireEvent.change(within(panel).getByTestId('more-defaultValue'), { target: { value: 'ONBEKEND' } });
    fireEvent.click(within(panel).getByTestId('more-zeroAllowed'));
    apply(panel);

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.body).toMatchObject({
      sourceReference: 'MERK',
      criticality: 'NON_CRITICAL',
      required: false,
      maxLength: 40,
      defaultValue: 'ONBEKEND',
      zeroAllowed: true,
      negativeAllowed: false,
    });
  });

  it('zonder wijziging staat er een melding en wordt er niets verstuurd', async () => {
    const calls = await openEditor(state([EAN]));
    const panel = await openMappingPanel('EAN-barcode');
    apply(panel);
    expect(await within(panel).findByTestId('panel-problem')).toHaveTextContent('Er is niets gewijzigd');
    expect(calls).toHaveLength(0);
  });

  it('een vaste waarde is een gewoon tekstveld (geen Prodis-lijst voor referentievelden)', async () => {
    const calls = await openEditor(state([EAN]));
    const panel = await openMappingPanel('EAN-barcode');
    fireEvent.click(within(panel).getByRole('radio', { name: 'Vaste waarde' }));
    expect(within(panel).queryByRole('combobox')).not.toBeInTheDocument();
    fireEvent.change(within(panel).getByTestId('mapping-fixed'), { target: { value: '8712345678906' } });
    apply(panel);
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.body).toMatchObject({ valueKind: 'FIXED_VALUE', fixedValue: '8712345678906', sourceReference: null });
  });

  it('een bewerking die de chips niet kennen blijft ongewijzigd en de chips staan uit', async () => {
    const split = mapping(5001, 'EAN', { transformKind: 'SPLIT', transformConfig: 'separator=-;index=2' });
    const calls = await openEditor(state([split]));
    const panel = await openMappingPanel('EAN-barcode');
    expect(within(panel).getByTestId('mapping-odd-ops')).toHaveTextContent('Splitsen');
    expect(within(panel).getByTestId('op-upper')).toBeDisabled();
    // Een referentieveld is altijd kritiek: de keuze is vergrendeld (EF1).
    expect(within(panel).queryByRole('radio', { name: 'Niet kritiek' })).not.toBeInTheDocument();
    expect(within(panel).getByRole('radio', { name: 'Kritiek (altijd)' })).toBeDisabled();
    fireEvent.click(within(panel).getByRole('radio', { name: 'Ja' }));
    apply(panel);
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.body).toMatchObject({ transformKind: 'SPLIT', transformConfig: 'separator=-;index=2', criticality: 'CRITICAL' });
  });

  it('een serverfout (400) blijft in het paneel, met de ingevulde waarde', async () => {
    const calls = await openEditor(state([EAN]), {
      put: () => jsonResponse({ error: 'kapot', code: 'CONFIG_TRANSFORM_INVALID' }, 400),
    });
    const panel = await openMappingPanel('EAN-barcode');
    fireEvent.change(within(panel).getByLabelText('Kolomnaam'), { target: { value: 'ANDERS' } });
    apply(panel);
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(await within(panel).findByRole('alert')).toBeInTheDocument();
    expect(within(panel).getByLabelText('Kolomnaam')).toHaveValue('ANDERS');
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();
  });

  it('Escape met een wijziging vraagt "Wijzigingen weggooien?"', async () => {
    const calls = await openEditor(state([EAN]));
    const panel = await openMappingPanel('EAN-barcode');
    fireEvent.change(within(panel).getByLabelText('Kolomnaam'), { target: { value: 'ANDERS' } });
    fireEvent.keyDown(panel, { key: 'Escape' });
    expect(await screen.findByText('Wijzigingen weggooien?')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Weggooien' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Wijzigen: EAN-barcode' })).not.toBeInTheDocument());
    expect(calls).toHaveLength(0);
  });
});

describe('Niet meer gebruiken', () => {
  it('vraagt een bevestiging, toont een 409 CONFIG_BOOKMARK_* en verwijdert bij de tweede poging', async () => {
    let attempts = 0;
    const calls = await openEditor(state([EAN, BRAND]), {
      del: () => {
        attempts += 1;
        return attempts === 1
          ? jsonResponse({ error: 'bookmark', code: 'CONFIG_BOOKMARK_PLACE_UNRESOLVED' }, 409)
          : new Response(null, { status: 204 });
      },
    });
    const panel = await openMappingPanel('EAN-barcode');
    fireEvent.click(within(panel).getByTestId('panel-unused'));
    const confirm = await screen.findByRole('dialog', { name: 'Dit veld niet meer gebruiken?' });
    expect(confirm).toHaveTextContent('EAN-barcode verhuist naar "Nog niet gekoppeld"');
    expect(calls).toHaveLength(0);

    fireEvent.click(within(confirm).getByRole('button', { name: 'Niet meer gebruiken' }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(await within(confirm).findByText('Een invulpunt steunt nog op deze rij')).toBeInTheDocument();
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();

    fireEvent.click(within(confirm).getByRole('button', { name: 'Niet meer gebruiken' }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]).toMatchObject({ method: 'DELETE' });
    expect(calls[1]!.url).toMatch(/\/setup\/revisions\/100\/mappings\/5001$/);
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bewaard in het concept');
    await waitFor(() => expect(screen.queryByTestId('field-panel')).not.toBeInTheDocument());
  });

  it('Bron "Niet gebruiken" + Toepassen opent dezelfde bevestiging; Behouden verwijdert niets', async () => {
    const calls = await openEditor(state([EAN]));
    const panel = await openMappingPanel('EAN-barcode');
    fireEvent.click(within(panel).getByRole('radio', { name: 'Niet gebruiken' }));
    apply(panel);
    const confirm = await screen.findByRole('dialog', { name: 'Dit veld niet meer gebruiken?' });
    fireEvent.click(within(confirm).getByRole('button', { name: 'Behouden' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Dit veld niet meer gebruiken?' })).not.toBeInTheDocument());
    expect(calls).toHaveLength(0);
  });
});

describe('Veld toevoegen en Nog niet gekoppeld', () => {
  it('stap 1 zoekt in de lijst en grijst gekoppelde velden uit; stap 2 is hetzelfde paneel; POST-body', async () => {
    const calls = await openEditor(state([EAN]));
    fireEvent.click(screen.getByTestId('add-field'));
    const panel = await screen.findByRole('dialog', { name: 'Veld toevoegen' });
    for (const group of ['Prijzen', 'Referenties', 'Overig']) {
      expect(within(panel).getByRole('heading', { name: group })).toBeInTheDocument();
    }
    expect(within(panel).getByTestId('add-item-EAN')).toHaveTextContent('al gekoppeld');
    expect(within(panel).queryByRole('button', { name: 'Kiezen: EAN-barcode' })).not.toBeInTheDocument();
    expect(within(panel).queryByTestId('panel-apply')).not.toBeInTheDocument();
    expectNoRawCodes(panel);

    fireEvent.change(within(panel).getByLabelText('Zoek een veld'), { target: { value: 'pim' } });
    expect(within(panel).queryByTestId('add-item-CAB_ID')).not.toBeInTheDocument();
    fireEvent.click(within(panel).getByRole('button', { name: 'Kiezen: PIM-nummer' }));

    expect(await screen.findByRole('dialog', { name: 'Veld toevoegen: PIM-nummer' })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Kolomnaam'), { target: { value: 'PIM' } });
    fireEvent.click(screen.getByTestId('panel-apply'));

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.method).toBe('POST');
    expect(calls[0]!.url).toMatch(/\/setup\/revisions\/100\/mappings$/);
    expect(calls[0]!.body).toMatchObject({
      targetFieldCode: 'PIM_ID',
      sourceReference: 'PIM',
      sequenceNumber: null,
      valueKind: 'SOURCE_FIELD',
      transformKind: 'NONE',
      criticality: 'CRITICAL',
      required: false,
      createdBy: 'An Beslisser',
      valueMap: [],
      steps: [],
    });
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bewaard in het concept');
  });

  it('[Terug naar de lijst] keert van stap 2 terug naar stap 1', async () => {
    await openEditor(state([EAN]));
    fireEvent.click(screen.getByTestId('add-field'));
    fireEvent.click(await screen.findByTestId('add-pick-CAB_ID'));
    fireEvent.click(await screen.findByTestId('panel-back'));
    expect(await screen.findByRole('dialog', { name: 'Veld toevoegen' })).toBeInTheDocument();
    expect(screen.getByTestId('add-field-list')).toBeInTheDocument();
  });

  it('"Nog niet gekoppeld (n)" is ingeklapt, noemt Merk en Eenheid als Prodis-beheerd en Koppelen slaat stap 1 over', async () => {
    await openEditor(state([EAN]));
    const toggle = screen.getByTestId('unmapped-toggle');
    // 15 velden buiten Herkenning en Basis, waarvan EAN gekoppeld is.
    expect(toggle).toHaveTextContent('Nog niet gekoppeld (14)');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('unmapped-BRAND')).not.toBeInTheDocument();

    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('unmapped-BRAND')).toHaveTextContent('Wordt in Prodis beheerd; een levering overschrijft dit niet.');
    expect(screen.getByTestId('unmapped-UNIT')).toHaveTextContent('Niet gekoppeld');
    expect(screen.queryByTestId('unmapped-EAN')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Koppelen: Merk' }));
    expect(await screen.findByRole('dialog', { name: 'Veld toevoegen: Merk' })).toBeInTheDocument();
    expect(screen.queryByTestId('panel-back')).not.toBeInTheDocument();
  });

  it('409 MAPPING_TARGET_IN_USE staat als eigen melding in het paneel', async () => {
    const calls = await openEditor(state([EAN]), {
      post: () => jsonResponse({ error: 'in use', code: 'MAPPING_TARGET_IN_USE' }, 409),
    });
    fireEvent.click(screen.getByTestId('add-field'));
    fireEvent.click(await screen.findByTestId('add-pick-CAB_ID'));
    fireEvent.change(await screen.findByLabelText('Kolomnaam'), { target: { value: 'CAB' } });
    fireEvent.click(screen.getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(await screen.findByTestId('mapping-target-in-use')).toHaveTextContent('Dit veld is al gekoppeld in deze versie');
    expect(screen.getByLabelText('Kolomnaam')).toHaveValue('CAB');
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();
  });
});

describe('Verkoopprijs 1-5 (%)', () => {
  const vkp = (extra: Record<string, unknown> = {}) =>
    [1, 2, 3, 4, 5].map((n) =>
      mapping(5010 + n, `VKP${n}_PCT`, { sourceReference: 'VKP', transformKind: 'NONE', ...(n === 5 ? extra : {}) }),
    );

  it('toont de vijf gelijke velden als één uitklapbare groep (ook in Bekijken); bewerken per rij', async () => {
    await openEditor(state([...vkp(), EAN]), {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    const extra = screen.getByTestId('read-extra');
    expect(extra).toHaveTextContent('Extra velden (6)');
    const group = within(extra).getByTestId('vkp-group');
    expect(group).toHaveTextContent('Verkoopprijzen 1–5 (%)');
    expect(group).toHaveTextContent('VKP');
    expect(within(extra).queryByTestId('read-field-5011')).not.toBeInTheDocument();
    expect(within(extra).getByTestId('read-field-5001')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('vkp-toggle'));
    for (const id of [5011, 5012, 5013, 5014, 5015]) {
      expect(within(extra).getByTestId(`read-field-${id}`)).toBeInTheDocument();
    }
    expect(screen.queryByRole('button', { name: 'Wijzigen: Verkoopprijs 1 (%)' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('mode-edit'));
    expect(screen.getByRole('button', { name: 'Wijzigen: Verkoopprijs 3 (%)' })).toBeInTheDocument();
    // De groep zelf heeft geen potlood: enkel weergave.
    expect(within(screen.getByTestId('vkp-group')).queryByRole('button', { name: /^Wijzigen/ })).not.toBeInTheDocument();
  });

  it('is er een verschil in bron of bewerking, dan staan de vijf velden los', async () => {
    await openEditor(state(vkp({ sourceReference: 'ANDERS' })), {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    expect(screen.queryByTestId('vkp-group')).not.toBeInTheDocument();
    expect(screen.getByTestId('read-field-5015')).toBeInTheDocument();
  });
});

describe('mappingPanelModel', () => {
  it('zet bewerkingen aan en uit en kiest de weergave (één bewerking of keten)', () => {
    const { form } = initialMappingForm(emptyMappingForm());
    expect(activeOps(form).size).toBe(0);
    const upper = setOp(form, 'upper', true);
    expect(upper.chain).toBe(false);
    expect(upper.single.kind).toBe('UPPER');
    const both = setOp(upper, 'cut', true);
    expect(both.chain).toBe(true);
    expect([...activeOps(both)].sort()).toEqual(['cut', 'upper']);
    // Een waardebewerking vervangt de andere.
    const divide = setOp(both, 'divide', true);
    expect([...activeOps(divide)].sort()).toEqual(['cut', 'divide']);
    // Een keten blijft een keten zolang er een bewerking in staat; leeg wordt "geen bewerking".
    const cutOnly = setOp(divide, 'divide', false);
    expect(cutOnly.chain).toBe(true);
    const none = setOp(cutOnly, 'cut', false);
    expect(none.chain).toBe(false);
    expect(none.single.kind).toBe('NONE');
  });

  it('laat een onbekende bewerking ongemoeid', () => {
    const { form } = initialMappingForm(emptyMappingForm({ transformKind: 'SPLIT', transformConfig: 'separator=-;index=2' }));
    expect(unsupportedKinds(form)).toEqual(['SPLIT']);
    expect(setOp(form, 'upper', true)).toBe(form);
  });
});

describe('Koppelen aan veld vanuit de kolomweergave (initialSource)', () => {
  function renderPanel(mode: MappingPanelMode, initialSource?: string) {
    return render(
      <ActorProvider identity={TEST_IDENTITY}>
        <MappingPanel
          revision={revisionDetail(100, 10, { status: 'DRAFT', mappings: [EAN] }) as unknown as RevisionDetail}
          catalogue={catalogueFromResponse(defaultFieldCatalog() as unknown as FieldCatalogResponse)}
          mode={mode}
          initialSource={initialSource}
          opener={null}
          onClose={() => {}}
          onSaved={() => {}}
        />
      </ActorProvider>,
    );
  }

  it('vult na het kiezen van het veld de kolom van het kolomvoorbeeld vooraf in als bron', async () => {
    renderPanel({ kind: 'add', code: null }, 'Merk');
    fireEvent.click(await screen.findByTestId('add-pick-CAB_ID'));
    expect(await screen.findByLabelText('Kolomnaam')).toHaveValue('Merk');
  });

  it('vult ook vooraf in als het veld al vaststaat (Koppelen in "Nog niet gekoppeld")', async () => {
    renderPanel({ kind: 'add', code: 'PIM_ID' }, '7');
    expect(await screen.findByLabelText('Kolomnaam')).toHaveValue('7');
  });

  it('zonder initialSource start de bron leeg', async () => {
    renderPanel({ kind: 'add', code: null });
    fireEvent.click(await screen.findByTestId('add-pick-CAB_ID'));
    expect(await screen.findByLabelText('Kolomnaam')).toHaveValue('');
  });

  it('bij wijzigen van een bestaand veld blijft de bestaande bron staan', async () => {
    renderPanel({ kind: 'edit', code: 'EAN' }, 'Merk');
    expect(await screen.findByLabelText('Kolomnaam')).toHaveValue(EAN.sourceReference as string);
  });
});
