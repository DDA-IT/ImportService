/**
 * R1 — het leveranciersdetail (`/setup/suppliers/:organisationId`): tabs als nested routes, de stappenbalk en "Volgende
 * stap" op Overzicht, het leesbare "Bestand en velden" zonder klik, de keuzelijst voor een andere versie, de historiek op
 * Versies met "Bekijken", de koppelingen en taken, en de rechten.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { expectNoOldWording } from './oldWording';
import { testIdentityWith } from './testIdentity';
import {
  definition,
  emptySetupState,
  installSetupServer,
  link,
  organisation,
  renderRoutes,
  revisionDetail,
  task,
  type SetupState,
} from './setupServer';

const originalFetch = global.fetch;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  global.fetch = originalFetch;
});

const ACTIVE_MAPPINGS = [
  {
    id: 5001,
    revisionId: 99,
    sequenceNumber: 1,
    targetFieldCode: 'EAN',
    sourceReference: 'BARCODE',
    valueKind: 'SOURCE_FIELD',
    criticality: 'NON_CRITICAL',
    required: false,
    steps: [{ kind: 'PREFIX', config: 'prefix=0' }],
    valueMap: [],
  },
  {
    id: 5002,
    revisionId: 99,
    sequenceNumber: 2,
    targetFieldCode: 'BRAND',
    sourceReference: 'MERK',
    valueKind: 'SOURCE_FIELD',
    criticality: 'NON_CRITICAL',
    required: true,
    steps: [],
    valueMap: [
      { source: 'BOSCH', target: 'Bosch' },
      { source: 'KRAMP', target: 'Kramp' },
    ],
  },
  {
    id: 5003,
    revisionId: 99,
    sequenceNumber: 3,
    targetFieldCode: 'UNIT',
    sourceReference: '',
    valueKind: 'FIXED_VALUE',
    fixedValue: 'ST',
    criticality: 'NON_CRITICAL',
  },
];

const ACTIVE_FILTERS = [
  {
    id: 6001,
    revisionId: 99,
    sequenceNumber: 1,
    sourceReference: 'STATUS',
    operator: 'EQUALS',
    compareValue: 'GEBLOKKEERD',
    outcome: 'EXCLUDE',
  },
];

/** Org 1 met één eigen definitie: versie 3 actief, versie 4 concept, versie 2 vervangen. */
function supplierState(): SetupState {
  const state = emptySetupState();
  state.organisations = [organisation(1, { code: 'VROOAM', name: 'Vrooam BV' })];
  state.definitions = [definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: 99 })];
  state.links = [link(501, 10, { code: 'VROOAM', name: 'Vrooam koppeling' }), link(502, 10, { code: 'VROOAM2', name: 'Tweede koppeling' })];
  state.tasks = [task(7, 501, 10, { name: 'Handmatige levering', activeRevisionId: 99, lastRunFinishedAt: '2026-10-20T10:00:00Z' })];
  state.revisions[10] = [
    { id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT' },
    { id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' },
    { id: 98, definitionId: 10, revisionNumber: 2, status: 'SUPERSEDED' },
  ];
  state.details[99] = revisionDetail(99, 10, {
    revisionNumber: 3,
    status: 'ACTIVE',
    mappings: ACTIVE_MAPPINGS,
    filters: ACTIVE_FILTERS,
  });
  state.details[100] = revisionDetail(100, 10, { revisionNumber: 4, status: 'DRAFT', recordBasePriceField: 'NIEUWE_PRIJS' });
  state.details[98] = revisionDetail(98, 10, { revisionNumber: 2, status: 'SUPERSEDED', recordBasePriceField: 'OUDE_PRIJS' });
  return state;
}

async function open(path: string, state: SetupState = supplierState(), identity?: Parameters<typeof renderRoutes>[1]) {
  installSetupServer(state);
  const view = renderRoutes(path, identity);
  await screen.findByRole('navigation', { name: 'Onderdelen van de leverancier' });
  return view;
}

describe('leveranciersdetail: kop en tabs', () => {
  it('toont naam, kruimelpad en vier tabs met aria-current op de actieve', async () => {
    await open('/setup/suppliers/1');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Vrooam BV');
    const nav = screen.getByRole('navigation', { name: 'Onderdelen van de leverancier' });
    const links = within(nav).getAllByRole('link');
    expect(links.map((l) => l.textContent)).toEqual(['Overzicht', 'Bestand en velden', 'Versies', 'Koppelingen en taken']);
    expect(links[0]).toHaveAttribute('aria-current', 'page');
    expect(links[1]).not.toHaveAttribute('aria-current');
    expect(screen.getByRole('link', { name: 'Leveranciers' })).toHaveAttribute('href', '/setup');
  });

  it('een tabblad wisselt via de route en zet aria-current mee', async () => {
    const { router } = await open('/setup/suppliers/1');
    const nav = screen.getByRole('navigation', { name: 'Onderdelen van de leverancier' });
    fireEvent.click(within(nav).getByRole('link', { name: 'Versies' }));
    await screen.findByTestId('supplier-versions');
    expect(router.state.location.pathname).toBe('/setup/suppliers/1/versions');
    expect(within(nav).getByRole('link', { name: 'Versies' })).toHaveAttribute('aria-current', 'page');
    expect(within(nav).getByRole('link', { name: 'Overzicht' })).not.toHaveAttribute('aria-current');
  });

  it('een onbekende leverancier toont een melding', async () => {
    installSetupServer(supplierState());
    renderRoutes('/setup/suppliers/999');
    expect(await screen.findByRole('alert')).toHaveTextContent('bestaat niet');
  });

  it('technische details bevatten de ids en staan standaard dicht', async () => {
    await open('/setup/suppliers/1');
    const details = screen.getByText('Technische details (voor support)').closest('details')!;
    expect(details).not.toHaveAttribute('open');
    expect(details).toHaveTextContent('Organisatie-id');
    expect(details).toHaveTextContent('100, 99, 98');
    expect(details).toHaveTextContent('501, 502');
  });
});

describe('tab Overzicht', () => {
  it('toont de stappenbalk met de huidige stap, de kaarten en de gegevens', async () => {
    await open('/setup/suppliers/1');
    const steps = screen.getByRole('navigation', { name: 'Waar past dit in de keten?' });
    const items = within(steps).getAllByRole('listitem');
    expect(items).toHaveLength(6);
    // Een openstaand concept: de huidige stap is de importdefinitie (versie).
    expect(items[1]).toHaveAttribute('aria-current', 'step');
    expect(items[0]).not.toHaveAttribute('aria-current');
    expect(screen.getByTestId('supplier-card-definitions')).toHaveTextContent('1');
    expect(screen.getByTestId('supplier-card-active')).toHaveTextContent('Versie 3');
    expect(screen.getByTestId('supplier-card-links')).toHaveTextContent('2');
    expect(screen.getByTestId('supplier-card-tasks')).toHaveTextContent('1');
    expect(screen.getByTestId('supplier-card-notready')).toHaveTextContent('1');
    const data = screen.getByTestId('supplier-data');
    expect(data).toHaveTextContent('VROOAM');
    expect(data).toHaveTextContent('Leverancier');
    expect(data).toHaveTextContent('Actief');
  });

  it('"Volgende stap" heeft precies één actie: het concept bekijken', async () => {
    await open('/setup/suppliers/1');
    const banner = screen.getByTestId('next-step');
    expect(banner).toHaveTextContent('versie 4 (concept) controleren en activeren');
    expect(within(banner).getAllByRole('link')).toHaveLength(1);
    expect(within(banner).getByTestId('next-step-action')).toHaveAttribute(
      'href',
      '/setup/suppliers/1/fields?definitie=10&versie=100',
    );
  });

  it('zonder concept en met een koppeling zonder taak is de volgende stap een taak toevoegen', async () => {
    const state = supplierState();
    state.revisions[10] = [{ id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' }];
    state.tasks = [task(7, 501, 10, { activeRevisionId: 99 })];
    await open('/setup/suppliers/1', state);
    const banner = screen.getByTestId('next-step');
    expect(banner).toHaveTextContent('een taak toevoegen aan de koppeling');
    expect(within(banner).getByTestId('next-step-action')).toHaveAttribute(
      'href',
      '/setup/new?organisationId=1&definitionId=10&linkId=502',
    );
    const steps = screen.getByRole('navigation', { name: 'Waar past dit in de keten?' });
    expect(within(steps).getAllByRole('listitem')[3]).toHaveAttribute('aria-current', 'step');
  });

  it('een leverancier zonder importdefinitie: volgende stap is een importdefinitie maken', async () => {
    const state = emptySetupState();
    state.organisations = [organisation(2, { code: 'KRAMP', name: 'Kramp' })];
    await open('/setup/suppliers/2', state);
    expect(screen.getByTestId('next-step')).toHaveTextContent('een importdefinitie voor deze leverancier maken');
    expect(screen.getByTestId('next-step-action')).toHaveAttribute('href', '/setup/new?organisationId=2');
    expect(screen.getByTestId('header-continue')).toHaveAttribute('href', '/setup/new?organisationId=2');
  });

  it('zonder recht Beheren is de actie uitgeschakeld mét reden', async () => {
    const state = emptySetupState();
    state.organisations = [organisation(2)];
    await open('/setup/suppliers/2', state, testIdentityWith('catalogImport.read'));
    const action = screen.getByTestId('next-step-action');
    expect(action).toBeDisabled();
    expect(action).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
  });

  it('een klare leverancier verwijst naar Bestand uploaden', async () => {
    const state = supplierState();
    state.revisions[10] = [{ id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' }];
    state.tasks = [task(7, 501, 10, { activeRevisionId: 99 }), task(8, 502, 10, { activeRevisionId: 99 })];
    await open('/setup/suppliers/1', state);
    const banner = screen.getByTestId('next-step');
    expect(banner).toHaveTextContent('een bestand inlezen');
    expect(within(banner).getByTestId('next-step-action')).toHaveAttribute('href', '/upload');
  });

  it('een inactieve leverancier heeft geen actie', async () => {
    const state = emptySetupState();
    state.organisations = [organisation(3, { active: false })];
    await open('/setup/suppliers/3', state);
    expect(screen.getByTestId('next-step')).toHaveTextContent('deze leverancier is niet actief');
    expect(screen.queryByTestId('next-step-action')).not.toBeInTheDocument();
  });
});

describe('tab Bestand en velden', () => {
  it('toont meteen de actieve versie: bestandsopbouw, herkenning, velden, filters en vertalingen', async () => {
    await open('/setup/suppliers/1/fields');
    const view = await screen.findByTestId('revision-read-view');
    expect(screen.getByRole('heading', { level: 2, name: /Vrooam artikelbestand, versie 3 \(actief\)/ })).toBeInTheDocument();
    // Geen klik nodig: het bewerkblok blijft dicht.
    expect(screen.queryByTestId('revision-detail')).not.toBeInTheDocument();
    expect(screen.queryByTestId('draft-banner')).not.toBeInTheDocument();

    const file = within(view).getByTestId('read-file');
    expect(file).toHaveTextContent('CSV-bestand');
    expect(file).toHaveTextContent('UTF-8');
    expect(file).toHaveTextContent('ja (regel 1)');

    const identity = within(view).getByTestId('read-identity');
    expect(identity).toHaveTextContent('← LEV');
    expect(identity).toHaveTextContent('← REF');
    expect(identity).toHaveTextContent('Niet gebruikt');

    const fields = within(view).getByTestId('read-fields');
    expect(within(fields).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Veld', 'Bron', 'Bewerking', 'Verplicht']);
    const ean = within(fields).getByTestId('read-field-5001');
    expect(ean).toHaveTextContent('EAN-barcode');
    expect(ean).toHaveTextContent('BARCODE');
    expect(ean).toHaveTextContent('Voorvoegsel');
    expect(ean).toHaveTextContent('nee');
    const brand = within(fields).getByTestId('read-field-5002');
    expect(brand).toHaveTextContent('Vertaling (2)');
    expect(brand).toHaveTextContent('ja');
    expect(within(fields).getByTestId('read-field-5003')).toHaveTextContent('vaste waarde ST');
    // Basiskolommen: prijs en omschrijving.
    expect(fields).toHaveTextContent('PRIJS');
    expect(fields).toHaveTextContent('OMSCHRIJVING');

    const filters = within(view).getByTestId('read-filters');
    expect(filters).toHaveTextContent('STATUS');
    expect(filters).toHaveTextContent('GEBLOKKEERD');
    expect(filters).toHaveTextContent('Overslaan');

    const translations = within(view).getByTestId('read-translations');
    expect(translations).toHaveTextContent('BOSCH');
    expect(translations).toHaveTextContent('Bosch');
    expectNoOldWording(document.body, 'bestand en velden');
  });

  it('de versiekeuze toont een andere versie, een concept met een melding', async () => {
    const { router } = await open('/setup/suppliers/1/fields');
    await screen.findByTestId('revision-read-view');
    const select = screen.getByTestId('version-select');
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'Versie 4 (concept)',
      'Versie 3 (actief)',
      'Versie 2 (vervangen)',
    ]);
    expect(select).toHaveValue('99');

    fireEvent.change(select, { target: { value: '100' } });
    expect(await screen.findByTestId('draft-banner')).toHaveTextContent('conceptversie');
    await waitFor(() => expect(screen.getByTestId('read-fields')).toHaveTextContent('NIEUWE_PRIJS'));
    expect(router.state.location.search).toContain('versie=100');
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('versie 4 (concept)');
  });

  it('zonder actieve versie staat het nieuwste concept er met de melding', async () => {
    const state = supplierState();
    state.definitions = [definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: null })];
    state.revisions[10] = [
      { id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT' },
      { id: 98, definitionId: 10, revisionNumber: 2, status: 'SUPERSEDED' },
    ];
    await open('/setup/suppliers/1/fields', state);
    await screen.findByTestId('revision-read-view');
    expect(screen.getByTestId('draft-banner')).toBeInTheDocument();
    expect(screen.getByTestId('version-select')).toHaveValue('100');
  });

  it('?versie= kiest die versie', async () => {
    await open('/setup/suppliers/1/fields?definitie=10&versie=98');
    await screen.findByTestId('revision-read-view');
    expect(screen.getByTestId('version-select')).toHaveValue('98');
    await waitFor(() => expect(screen.getByTestId('read-fields')).toHaveTextContent('OUDE_PRIJS'));
  });

  it('bij meerdere importdefinities staat er een keuzelijst voor de importdefinitie', async () => {
    const state = supplierState();
    state.definitions = [
      definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: 99 }),
      definition(11, 1, { name: 'Vrooam voorraad', activeRevisionId: 110 }),
    ];
    state.revisions[11] = [{ id: 110, definitionId: 11, revisionNumber: 1, status: 'ACTIVE' }];
    state.details[110] = revisionDetail(110, 11, { revisionNumber: 1, recordBasePriceField: 'VOORRAAD_PRIJS' });
    await open('/setup/suppliers/1/fields', state);
    await screen.findByTestId('revision-read-view');
    const select = screen.getByTestId('definition-select');
    expect(within(select).getAllByRole('option')).toHaveLength(2);
    fireEvent.change(select, { target: { value: '11' } });
    await waitFor(() => expect(screen.getByTestId('read-fields')).toHaveTextContent('VOORRAAD_PRIJS'));
  });

  it('een leverancier zonder importdefinitie toont een lege tekst', async () => {
    const state = emptySetupState();
    state.organisations = [organisation(2)];
    await open('/setup/suppliers/2/fields', state);
    expect(screen.getByText('Deze leverancier heeft nog geen importdefinitie.')).toBeInTheDocument();
  });

  it('"Bewerken" opent het bestaande revisiedetail met bewerken en activeren (concept)', async () => {
    await open('/setup/suppliers/1/fields?definitie=10&versie=100');
    await screen.findByTestId('revision-read-view');
    const toggle = screen.getByTestId('open-edit');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const section = await screen.findByTestId('revision-detail');
    expect(await within(section).findByTestId('revision-edit-form')).toBeInTheDocument();
    expect(await within(section).findByTestId('activate-revision')).toHaveTextContent('Versie activeren');
  });

  it('?bewerken=1 opent het bewerkblok meteen', async () => {
    await open('/setup/suppliers/1/fields?definitie=10&versie=99&bewerken=1');
    expect(await screen.findByTestId('revision-detail')).toBeInTheDocument();
    expect(await screen.findByTestId('revision-not-editable-reason')).toBeInTheDocument();
  });
});

describe('tab Versies', () => {
  it('toont de historiek met status en acties, "Bekijken" opent Bestand en velden met die versie', async () => {
    const { router } = await open('/setup/suppliers/1/versions');
    const rows = screen.getAllByTestId(/^version-row-/);
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('Versie 4'),
      expect.stringContaining('Versie 3'),
      expect.stringContaining('Versie 2'),
    ]);
    expect(rows[0]).toHaveTextContent('Concept');
    expect(rows[1]).toHaveTextContent('Actief');
    expect(rows[2]).toHaveTextContent('Vervangen');
    // Opvolger maken enkel op een actieve of vervangen versie, bewerken en activeren enkel op een concept.
    expect(within(rows[1]!).getByTestId('create-successor-99')).toBeEnabled();
    expect(within(rows[0]!).getByTestId('create-successor-100')).toBeDisabled();
    expect(within(rows[0]!).getByTestId('edit-revision-100')).toHaveAttribute(
      'href',
      '/setup/suppliers/1/fields?definitie=10&versie=100&bewerken=1',
    );
    expect(within(rows[1]!).queryByTestId('edit-revision-99')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('open-revision-98'));
    await screen.findByTestId('revision-read-view');
    expect(router.state.location.pathname).toBe('/setup/suppliers/1/fields');
    expect(router.state.location.search).toContain('versie=98');
    expect(screen.getByTestId('version-select')).toHaveValue('98');
    await waitFor(() => expect(screen.getByTestId('read-fields')).toHaveTextContent('OUDE_PRIJS'));
  });

  it('zonder versies staat er een lege tekst', async () => {
    const state = supplierState();
    state.revisions[10] = [];
    await open('/setup/suppliers/1/versions', state);
    expect(screen.getByText('Geen versies voor deze importdefinitie.')).toBeInTheDocument();
  });
});

describe('tab Koppelingen en taken', () => {
  it('toont een kaart per koppeling met haar taken, gereedheid en acties', async () => {
    await open('/setup/suppliers/1/links');
    const first = screen.getByTestId('link-card-501');
    expect(first).toHaveTextContent('VROOAM');
    expect(first).toHaveTextContent('Bibliotheek LIB1');
    expect(first).toHaveTextContent('Actief');
    expect(first).toHaveTextContent('Klaar');
    const tasks = within(first).getByTestId('link-tasks-501');
    expect(within(tasks).getByTestId('task-7')).toHaveTextContent('Handmatige levering');
    expect(within(tasks).getByTestId('task-7')).toHaveTextContent('Handmatig');
    expect(within(tasks).getByTestId('task-7')).toHaveTextContent('laatste inlezing');
    expect(within(first).getByTestId('check-link-501')).toHaveAttribute('href', '/setup/links/501/check?definitionId=10');
    expect(within(first).getByTestId('add-task-501')).toHaveAttribute(
      'href',
      '/setup/new?organisationId=1&definitionId=10&linkId=501',
    );

    const second = screen.getByTestId('link-card-502');
    expect(second).toHaveTextContent('Nog niet klaar');
    expect(within(second).getByTestId('continue-link-502')).toHaveAttribute(
      'href',
      '/setup/new?organisationId=1&definitionId=10&linkId=502',
    );
  });

  it('een taak van een importdefinitie zonder actieve versie is "nog niet klaar"', async () => {
    const state = supplierState();
    state.definitions = [definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: null })];
    state.tasks = [task(8, 501, 10, { name: 'Nieuwe taak', activeRevisionId: null })];
    await open('/setup/suppliers/1/links', state);
    expect(screen.getByTestId('task-not-ready-8')).toHaveTextContent('Niet klaar: versie niet geactiveerd');
  });

  it('zonder recht Beheren zijn "Taak toevoegen" en "Verder inrichten" uitgeschakeld mét reden, Controleren blijft', async () => {
    await open('/setup/suppliers/1/links', supplierState(), testIdentityWith('catalogImport.read'));
    const add = screen.getByTestId('add-task-501');
    expect(add.tagName).toBe('BUTTON');
    expect(add).toBeDisabled();
    expect(add).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByTestId('continue-link-502')).toBeDisabled();
    expect(screen.getByTestId('check-link-501').tagName).toBe('A');
  });

  it('een importdefinitie zonder koppeling biedt "Verder inrichten"', async () => {
    const state = supplierState();
    state.links = [];
    state.tasks = [];
    await open('/setup/suppliers/1/links', state);
    expect(screen.getByText(/nog geen koppelingen/)).toBeInTheDocument();
    expect(screen.getByTestId('continue-definition-10')).toHaveAttribute(
      'href',
      '/setup/new?organisationId=1&definitionId=10',
    );
  });
});
