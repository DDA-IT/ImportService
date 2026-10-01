/**
 * NT-7 — het sjabloonpad in het stappenplan "Nieuwe leverancier en taak" (`/setup/new`, stap 2 "Startpunt").
 * Beslissingen: `docs/decisions.md` 2026-09-30 (NT-spoor V3 = a: materialiseren maakt geen taak, V4 = a: een sjabloon
 * bedient enkel zijn eigen bronorganisatie).
 *
 * (De oudere `TemplateWizard.test.tsx` dekt de pagina's Sjablonen van S1-F2; dit bestand het sjabloonpad van NT-7.)
 *
 * Dekt: normaal scenario (sjabloon kiezen, materialiseren, door naar de taakstap, taak maken — geen tweede
 * aanmaak van beschrijving of koppeling), geen sjabloon voor de organisatie (Nederlandse uitleg + "Zelf
 * beschrijven"), sjablonen van een andere organisatie niet getoond, leverancierskeuze bij een aankoopvereniging,
 * en zichtbaarheid van waarschuwingen en ontbrekende verplichte bladwijzers van de koppeling.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { TEST_IDENTITY } from './testIdentity';
import { NewSupplierWizardPage } from '../features/setup/wizard/NewSupplierWizardPage';

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function page(rows: unknown[]) {
  return { content: rows, page: 0, size: 200, totalElements: rows.length, totalPages: 1 };
}

const SUPPLIER = { id: 1, code: 'LEV-A', name: 'Leverancier A', type: 'SUPPLIER', active: true };
const OTHER_SUPPLIER = { id: 2, code: 'LEV-B', name: 'Leverancier B', type: 'SUPPLIER', active: true };
const ASSOCIATION = { id: 3, code: 'VRO', name: 'VROOAM', type: 'PURCHASING_ASSOCIATION', active: true };

const TEMPLATE_A = { id: 50, code: 'TPL-A', name: 'Sjabloon van A', sourceOrganisationId: 1, sourceOrganisationCode: 'LEV-A' };
const TEMPLATE_B = { id: 51, code: 'TPL-B', name: 'Sjabloon van B', sourceOrganisationId: 2, sourceOrganisationCode: 'LEV-B' };
const TEMPLATE_VRO = { id: 60, code: 'TPL-VRO', name: 'Sjabloon van VROOAM', sourceOrganisationId: 3, sourceOrganisationCode: 'VRO' };

const TEMPLATE_REVISIONS = [
  { id: 500, definitionId: 50, revisionNumber: 1, status: 'ACTIVE' },
  { id: 501, definitionId: 50, revisionNumber: 2, status: 'DRAFT' },
];

function materialisationResult(templateId: number, overrides: Record<string, unknown> = {}) {
  return {
    templateDefinitionId: templateId,
    templateRevisionId: 500,
    templateRevisionNumber: 1,
    templateRevisionStatus: 'ACTIVE',
    definitionId: 10,
    definitionCode: 'DEF-10',
    definitionCreated: true,
    definitionRevisionId: 100,
    definitionRevisionNumber: 1,
    definitionRevisionStatus: 'DRAFT',
    importLinkId: 501,
    importLinkCode: 'LNK-A',
    definitionValues: [],
    linkValues: [],
    warnings: [
      {
        code: 'LINK_SEARCH_SUPPLIER_NOT_DERIVED',
        bookmarkName: null,
        message: 'library_search_supplier_code stays null; it is never derived from the supplier',
      },
    ],
    ...overrides,
  };
}

type ServerState = {
  organisations: unknown[];
  templates: unknown[];
  /** Wat de hervatting na het materialiseren terugvindt, per organisatie-id. */
  definitionsByOrganisation: Record<number, unknown[]>;
  materialisationFor: (templateId: number) => unknown;
  missingRequiredNames: string[];
};

function baseState(): ServerState {
  return {
    organisations: [SUPPLIER, OTHER_SUPPLIER, ASSOCIATION],
    templates: [TEMPLATE_A, TEMPLATE_B, TEMPLATE_VRO],
    definitionsByOrganisation: {},
    materialisationFor: (templateId) => materialisationResult(templateId),
    missingRequiredNames: [],
  };
}

function definitionRow(organisation: { id: number; code: string }) {
  return {
    id: 10,
    code: 'DEF-10',
    name: 'Beschrijving uit sjabloon',
    usageType: 'OWN_DEFINITION',
    sourceOrganisationId: organisation.id,
    sourceOrganisationCode: organisation.code,
    activeRevisionId: null,
  };
}

const LINK_ROW = {
  id: 501,
  code: 'LNK-A',
  name: 'Koppeling A',
  supplierCode: 'LEV-A',
  supplierName: 'Leverancier A',
  libraryCode: 'LIB1',
  active: true,
  importDefinitionId: 10,
};

function installServer(state: ServerState) {
  const mock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    const path = url.replace(/^.*\/api\/catalog-import/, '');
    const query = new URL(`http://test${path}`).searchParams;

    if (method === 'POST') {
      const body = (init?.body ? JSON.parse(String(init.body)) : {}) as Record<string, unknown>;
      const materialise = /^\/templates\/(\d+)\/materialisations$/.exec(path);
      if (materialise !== null) {
        return json(state.materialisationFor(Number(materialise[1])), 201);
      }
      if (path === '/setup/tasks') {
        return json(
          { id: 900, linkId: body.linkId, name: body.name, triggerType: 'MANUAL', active: true, preventConcurrentRuns: true },
          201,
        );
      }
      throw new Error(`Onverwachte POST in test: ${path}`);
    }

    let match = /^\/templates\/(\d+)\/revisions\/(\d+)\/bookmarks/.exec(path);
    if (match !== null) {
      return json({ definitionId: Number(match[1]), revisionId: Number(match[2]), bookmarks: [], problems: [] });
    }
    if (/^\/templates\/\d+\/materialisations/.test(path)) {
      return json(page([]));
    }
    if (path.startsWith('/templates')) {
      return json(page(state.templates));
    }
    match = /^\/definitions\/(\d+)\/revisions\/(\d+)/.exec(path);
    if (match !== null) {
      return json({
        id: Number(match[2]),
        definitionId: Number(match[1]),
        revisionNumber: 1,
        status: 'DRAFT',
        creationThresholdSharePercent: 1,
        maxCriticalSharePercent: 1,
        maxRejectedSharePercent: null,
        bulkIncidentSharePercent: 1,
        mappings: [],
        filters: [],
        fieldCriticalities: [],
        bookmarks: [],
        bookmarkValues: [],
      });
    }
    match = /^\/definitions\/(\d+)\/revisions/.exec(path);
    if (match !== null) {
      const definitionId = Number(match[1]);
      // Sjablonen (50, 60) hebben een actieve versie; de gematerialiseerde beschrijving (10) een concept.
      if (definitionId === 50) {
        return json(page(TEMPLATE_REVISIONS));
      }
      if (definitionId === 60) {
        return json(page([{ id: 600, definitionId: 60, revisionNumber: 1, status: 'ACTIVE' }]));
      }
      return json(page([{ id: 100, definitionId, revisionNumber: 1, status: 'DRAFT' }]));
    }
    if (path.startsWith('/definitions')) {
      return json(page(state.definitionsByOrganisation[Number(query.get('sourceOrganisationId'))] ?? []));
    }
    if (path.startsWith('/source-organisations')) {
      return json(page(state.organisations));
    }
    if (path.startsWith('/import-links')) {
      return json(page(query.get('importDefinitionId') === '10' ? [LINK_ROW] : []));
    }
    if (path.startsWith('/tasks')) {
      return json(page([]));
    }
    match = /^\/links\/(\d+)\/bookmark-values/.exec(path);
    if (match !== null) {
      return json({
        importLinkId: Number(match[1]),
        importLinkCode: 'LNK-A',
        activeRevisionId: null,
        lockedByOpenBatch: false,
        values: [],
        missingRequiredNames: state.missingRequiredNames,
      });
    }
    throw new Error(`Onverwachte URL in test: ${url}`);
  });
  global.fetch = mock as unknown as typeof fetch;
  return mock;
}

type Mock = ReturnType<typeof installServer>;

function posts(mock: Mock): Array<{ path: string; body: Record<string, unknown> }> {
  return mock.mock.calls
    .filter((call) => ((call[1] as RequestInit | undefined)?.method ?? 'GET').toUpperCase() === 'POST')
    .map((call) => ({
      path: String(call[0]).replace(/^.*\/api\/catalog-import/, ''),
      body: JSON.parse(String((call[1] as RequestInit).body)) as Record<string, unknown>,
    }));
}

function renderWizard(path: string) {
  return render(
    <ActorProvider identity={TEST_IDENTITY}>
      <MemoryRouter initialEntries={[path]}>
        <NewSupplierWizardPage />
      </MemoryRouter>
    </ActorProvider>,
  );
}

function pickTemplate(id: number) {
  fireEvent.change(screen.getByLabelText(/^Sjabloon/), { target: { value: String(id) } });
}

async function fillMaterialiseForm() {
  await screen.findByTestId('materialise-form');
  fireEvent.change(screen.getByLabelText(/^Nieuw of hergebruik/), { target: { value: 'NEW_DEFINITION' } });
  fireEvent.change(screen.getByLabelText(/^Code van de beschrijving/), { target: { value: 'DEF-10' } });
  fireEvent.change(screen.getByLabelText(/^Naam van de beschrijving/), { target: { value: 'Beschrijving uit sjabloon' } });
  fireEvent.change(screen.getByLabelText(/^Koppelingscode/), { target: { value: 'LNK-A' } });
  fireEvent.change(screen.getByLabelText(/^Koppelingsnaam/), { target: { value: 'Koppeling A' } });
  fireEvent.change(screen.getByLabelText(/^Bibliotheekcode/), { target: { value: 'LIB1' } });
}

describe('NewSupplierWizardPage — sjabloonpad (NT-7)', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('normaal: sjabloon van de eigen organisatie kiezen, materialiseren, door naar de taakstap en de taak maken', async () => {
    const state = baseState();
    state.definitionsByOrganisation[1] = [definitionRow(SUPPLIER)];
    state.missingRequiredNames = ['BESTANDS_PREFIX'];
    const mock = installServer(state);
    renderWizard('/setup/new?organisationId=1');

    fireEvent.click(await screen.findByRole('button', { name: 'Vanuit een sjabloon van deze leverancier of aankoopvereniging' }));

    // V4 = a: enkel het sjabloon van dezelfde organisatie; dat van LEV-B ontbreekt.
    await screen.findByRole('option', { name: /TPL-A/ });
    expect(screen.queryByRole('option', { name: /TPL-B/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /TPL-VRO/ })).not.toBeInTheDocument();

    pickTemplate(50);
    // De actieve versie is voorgeselecteerd; het concept van het sjabloon is niet kiesbaar.
    await screen.findByRole('option', { name: /Versie 1/ });
    expect((screen.getByLabelText(/^Versie van het sjabloon/) as HTMLSelectElement).value).toBe('500');
    expect(screen.queryByRole('option', { name: /Versie 2/ })).not.toBeInTheDocument();

    await fillMaterialiseForm();
    // De leverancier van stap 1 is voorgevuld (en blijft aanpasbaar).
    expect((screen.getByLabelText(/^Leverancierscode/) as HTMLInputElement).value).toBe('LEV-A');
    fireEvent.click(screen.getByRole('button', { name: 'Materialiseren' }));

    // Door naar de taakstap, met de nieuwe koppeling; er is nog geen taak gemaakt.
    await screen.findByRole('heading', { name: /Stap 5/ });
    const created = await screen.findByTestId('wizard-materialised');
    expect(created).toHaveTextContent('DEF-10');
    expect(created).toHaveTextContent('LNK-A');
    expect(created).toHaveTextContent('Er is nog geen taak');
    // Waarschuwing in het Nederlands, geen ruwe code of Engelse servertekst.
    expect(within(created).getByTestId('wizard-materialised-warnings')).toHaveTextContent(
      'Ze wordt nooit automatisch uit de leverancier afgeleid',
    );
    expect(created).not.toHaveTextContent('LINK_SEARCH_SUPPLIER_NOT_DERIVED');
    // Een ontbrekend verplicht invulpunt van de koppeling blijft zichtbaar.
    expect(await screen.findByTestId('link-missing-required')).toHaveTextContent(
      'Een nieuwe levering wordt geweigerd zolang deze lijst niet leeg is',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Taak aanmaken' }));
    const summary = await screen.findByTestId('wizard-summary');
    expect(summary).toHaveTextContent('DEF-10');

    // Precies twee POST's: de materialisatie en de taak — nooit een tweede beschrijving of koppeling.
    const sent = posts(mock);
    expect(sent.map((post) => post.path)).toEqual(['/templates/50/materialisations', '/setup/tasks']);
    expect(sent[0]!.body).toMatchObject({
      templateRevisionId: 500,
      mode: 'NEW_DEFINITION',
      definitionCode: 'DEF-10',
      linkCode: 'LNK-A',
      supplierOrganisationCode: 'LEV-A',
      libraryCode: 'LIB1',
    });
    expect(sent[1]!.body).toMatchObject({ linkId: 501 });
  });

  it('geen sjabloon voor de organisatie: Nederlandse uitleg en de weg naar "Zelf beschrijven"', async () => {
    const state = baseState();
    // Enkel sjablonen van andere organisaties.
    state.templates = [TEMPLATE_B];
    installServer(state);
    renderWizard('/setup/new?organisationId=1');

    fireEvent.click(await screen.findByRole('button', { name: 'Vanuit een sjabloon van deze leverancier of aankoopvereniging' }));

    const explanation = await screen.findByTestId('wizard-no-templates');
    expect(explanation).toHaveTextContent('Er is geen sjabloon voor LEV-A');
    expect(explanation).toHaveTextContent('Zelf beschrijven');
    expect(screen.queryByLabelText(/^Sjabloon/)).not.toBeInTheDocument();
    expect(screen.queryByTestId('materialise-form')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Zelf beschrijven' }));
    expect(await screen.findByRole('heading', { name: /Stap 3/ })).toBeInTheDocument();
  });

  it('aankoopvereniging: eerst de leverancier kiezen (enkel leveranciers); die vult "Leverancierscode" voor', async () => {
    const state = baseState();
    state.definitionsByOrganisation[3] = [definitionRow(ASSOCIATION)];
    const mock = installServer(state);
    renderWizard('/setup/new?organisationId=3');

    fireEvent.click(await screen.findByRole('button', { name: 'Vanuit een sjabloon van deze leverancier of aankoopvereniging' }));
    await screen.findByRole('option', { name: /TPL-VRO/ });
    pickTemplate(60);

    const supplierSelect = await screen.findByLabelText(/^Leverancier van de koppeling/);
    // Zonder gekozen leverancier is er nog geen formulier (geen stille keuze).
    expect(screen.queryByTestId('materialise-form')).not.toBeInTheDocument();
    // Enkel leveranciers, niet de aankoopvereniging zelf.
    await within(supplierSelect as HTMLElement).findByRole('option', { name: /LEV-A/ });
    expect(within(supplierSelect as HTMLElement).queryByRole('option', { name: /VROOAM/ })).not.toBeInTheDocument();

    fireEvent.change(supplierSelect, { target: { value: 'LEV-B' } });
    await fillMaterialiseForm();
    expect((screen.getByLabelText(/^Leverancierscode/) as HTMLInputElement).value).toBe('LEV-B');
    fireEvent.click(screen.getByRole('button', { name: 'Materialiseren' }));

    await screen.findByRole('heading', { name: /Stap 5/ });
    const sent = posts(mock);
    expect(sent).toHaveLength(1);
    expect(sent[0]!.path).toBe('/templates/60/materialisations');
    expect(sent[0]!.body.supplierOrganisationCode).toBe('LEV-B');
  });

  it('een mislukte materialisatie (409) blijft bij het formulier, zonder door te gaan naar de taakstap', async () => {
    installServer(baseState());
    const original = global.fetch;
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('/materialisations') && (init?.method ?? 'GET').toUpperCase() === 'POST') {
        return json({ error: 'Link code already in use', code: 'LINK_CODE_IN_USE' }, 409);
      }
      return (original as typeof fetch)(input, init);
    }) as unknown as typeof fetch;
    renderWizard('/setup/new?organisationId=1');

    fireEvent.click(await screen.findByRole('button', { name: 'Vanuit een sjabloon van deze leverancier of aankoopvereniging' }));
    await screen.findByRole('option', { name: /TPL-A/ });
    pickTemplate(50);
    await fillMaterialiseForm();
    fireEvent.click(screen.getByRole('button', { name: 'Materialiseren' }));

    // Nederlandse uitleg; de tekst zegt "niets aangemaakt" (niet "gematerialiseerd").
    expect(await screen.findByText('Koppelingscode is al in gebruik')).toBeInTheDocument();
    expect(screen.getByText(/Er is niets aangemaakt/)).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /Stap 5/ })).not.toBeInTheDocument();
  });
});
