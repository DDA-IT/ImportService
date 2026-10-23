/**
 * R1 — het leveranciersdetail (`/setup/suppliers/:organisationId`): tabs als nested routes, de stappenbalk en "Volgende
 * stap" op Overzicht, het leesbare "Bestand en velden" zonder klik, de keuzelijst voor een andere versie, de historiek op
 * Versies met "Bekijken", de koppelingen en taken, en de rechten.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { formatDateTime } from '../format';
import { expectNoOldWording } from './oldWording';
import { testIdentityWith } from './testIdentity';
import {
  definition,
  emptySetupState,
  installSetupServer,
  jsonResponse,
  legacyLine,
  legacyOrigin,
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
  // F4: koppeling 502 heeft geen taak en keurt de servercontrole dus af.
  state.readiness = {
    502: {
      ready: false,
      problems: [{ code: 'LINK_HAS_NO_TASK', status: 'PROBLEM', subject: { type: 'LINK', id: 502 }, detail: 'Link has no task' }],
    },
  };
  state.revisions[10] = [
    {
      id: 100,
      definitionId: 10,
      revisionNumber: 4,
      status: 'DRAFT',
      createdAt: '2026-10-23T09:15:00Z',
      createdBy: 'an.peeters',
      approvedAt: null,
      approvedBy: null,
      changeReason: null,
      basedOnRevisionId: 99,
      fromLegacy: false,
    },
    {
      id: 99,
      definitionId: 10,
      revisionNumber: 3,
      status: 'ACTIVE',
      createdAt: '2026-10-20T08:00:00Z',
      createdBy: 'jan.janssens',
      approvedAt: '2026-10-21T10:31:00Z',
      approvedBy: 'an.peeters',
      changeReason: 'Prijskolom aangepast',
      basedOnRevisionId: 98,
      fromLegacy: false,
    },
    {
      id: 98,
      definitionId: 10,
      revisionNumber: 2,
      status: 'SUPERSEDED',
      createdAt: '2026-09-01T07:00:00Z',
      createdBy: 'overname',
      approvedAt: '2026-09-01T07:05:00Z',
      approvedBy: 'overname',
      changeReason: null,
      basedOnRevisionId: null,
      fromLegacy: true,
    },
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

  it('Gegevens: bibliotheek uit de koppelingen, tekenset van de actieve versie en het wachtwoord', async () => {
    const state = supplierState();
    state.links = [
      link(501, 10, { code: 'VROOAM', libraryCode: '181', credentialState: 'NONE' }),
      link(502, 10, { code: 'VROOAM2', libraryCode: '182', credentialState: 'STORED' }),
      link(503, 10, { code: 'VROOAM3', libraryCode: '181', credentialState: 'REVOKED' }),
    ];
    state.details[99] = revisionDetail(99, 10, { revisionNumber: 3, status: 'ACTIVE', structureCharset: 'ISO-8859-1' });
    await open('/setup/suppliers/1', state);
    const data = screen.getByTestId('supplier-data');
    expect(within(data).getByText('Bibliotheek').nextElementSibling).toHaveTextContent(/^181, 182$/);
    await waitFor(() => expect(within(data).getByTestId('supplier-charset')).toHaveTextContent('ISO-8859-1'));
    expect(within(data).getByText('Tekenset')).toBeInTheDocument();
    // Eén bewaard wachtwoord is genoeg.
    expect(within(data).getByTestId('supplier-credential')).toHaveTextContent(/^Veilig bewaard$/);
    expect(data).not.toHaveTextContent('Opmerking');
  });

  it('Gegevens: standaardwaarden (tekenset UTF-8, geen wachtwoord) en ingetrokken zonder bewaard wachtwoord', async () => {
    await open('/setup/suppliers/1');
    const data = screen.getByTestId('supplier-data');
    expect(within(data).getByText('Bibliotheek').nextElementSibling).toHaveTextContent(/^LIB1$/);
    await waitFor(() => expect(within(data).getByTestId('supplier-charset')).toHaveTextContent(/^UTF-8$/));
    expect(within(data).getByTestId('supplier-credential')).toHaveTextContent(/^Geen$/);
    cleanup();
    const revoked = supplierState();
    revoked.links = [link(501, 10, { credentialState: 'REVOKED' }), link(502, 10, { credentialState: 'NONE' })];
    await open('/setup/suppliers/1', revoked);
    expect(within(screen.getByTestId('supplier-data')).getByTestId('supplier-credential')).toHaveTextContent(/^Ingetrokken$/);
  });

  it('Gegevens: zonder actieve versie is de tekenset "Niet ingesteld"; zonder koppelingen ook de bibliotheek', async () => {
    const state = supplierState();
    state.definitions = [definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: null })];
    state.revisions[10] = [];
    state.links = [];
    state.tasks = [];
    await open('/setup/suppliers/1', state);
    const data = screen.getByTestId('supplier-data');
    expect(within(data).getByText('Tekenset').nextElementSibling).toHaveTextContent('Niet ingesteld');
    expect(within(data).getByText('Bibliotheek').nextElementSibling).toHaveTextContent('Niet ingesteld');
    expect(within(data).getByTestId('supplier-credential')).toHaveTextContent(/^Geen$/);
  });

  it('Gegevens: een tekenset die niet geladen kan worden toont een streepje, de rest blijft staan', async () => {
    const state = supplierState();
    delete state.details[99];
    await open('/setup/suppliers/1', state);
    const data = screen.getByTestId('supplier-data');
    await waitFor(() => expect(within(data).getByTestId('supplier-charset')).toHaveTextContent(/^—$/));
    expect(data).toHaveTextContent('VROOAM');
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
    expect(within(fields).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Veld', 'Bron', 'Bewerking', 'Kritiek', 'Verplicht', 'Status']);
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
    expect(filters).toHaveTextContent("Sla regels over waar kolom 'STATUS' is gelijk aan \"GEBLOKKEERD\"");

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

  it('?bewerken=1 zet Bewerken aan op een concept; op een actieve versie blijft het alleen-lezen', async () => {
    await open('/setup/suppliers/1/fields?definitie=10&versie=100&bewerken=1');
    expect(await screen.findByTestId('mode-edit')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('open-edit')).toHaveAttribute('aria-expanded', 'false');
    cleanup();
    await open('/setup/suppliers/1/fields?definitie=10&versie=99&bewerken=1');
    expect(await screen.findByTestId('frozen-note')).toHaveTextContent('Deze versie is actief en kan niet gewijzigd worden');
    expect(screen.queryByTestId('mode-edit')).not.toBeInTheDocument();
    expect(screen.queryByTestId('revision-detail')).not.toBeInTheDocument();
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

  it('elke versie toont datum en auteur; actief en vervangen ook goedgekeurd, reden, overname uit legacy en basisversie', async () => {
    await open('/setup/suppliers/1/versions');
    const draft = screen.getByTestId('version-row-100');
    expect(draft).toHaveTextContent(formatDateTime('2026-10-23T09:15:00Z') + ' · an.peeters');
    expect(draft).toHaveTextContent('gebaseerd op versie 3');
    expect(draft).not.toHaveTextContent('goedgekeurd');
    expect(draft).not.toHaveTextContent('Overname uit legacy');
    const active = screen.getByTestId('version-row-99');
    expect(active).toHaveTextContent(formatDateTime('2026-10-20T08:00:00Z') + ' · jan.janssens');
    expect(active).toHaveTextContent('goedgekeurd op ' + formatDateTime('2026-10-21T10:31:00Z') + ' door an.peeters');
    expect(within(active).getByTestId('version-reason-99')).toHaveTextContent('Prijskolom aangepast');
    expect(active).toHaveTextContent('gebaseerd op versie 2');
    const legacy = screen.getByTestId('version-row-98');
    expect(legacy).toHaveTextContent('Overname uit legacy');
    expect(legacy).not.toHaveTextContent('gebaseerd op');
  });

  it('een versie zonder de nieuwe velden (oudere server) toont enkel nummer en status', async () => {
    const state = supplierState();
    state.revisions[10] = [{ id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' }];
    await open('/setup/suppliers/1/versions', state);
    const row = screen.getByTestId('version-row-99');
    expect(row).toHaveTextContent('Versie 3');
    expect(screen.queryByTestId('version-meta-99')).not.toBeInTheDocument();
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
    expect(await within(first).findByTestId('link-readiness-501')).toHaveTextContent(/^Klaar$/);
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
    await within(second).findByText('Niet klaar: er is nog geen taak');
    expect(within(second).getByTestId('continue-link-502')).toHaveAttribute(
      'href',
      '/setup/new?organisationId=1&definitionId=10&linkId=502',
    );
  });

  it('toont per koppeling het wachtwoord: veilig bewaard, ingetrokken of geen', async () => {
    const state = supplierState();
    state.links = [
      link(501, 10, { code: 'VROOAM', name: 'Vrooam koppeling', credentialState: 'STORED' }),
      link(502, 10, { code: 'VROOAM2', name: 'Tweede koppeling', credentialState: 'REVOKED' }),
      link(503, 10, { code: 'VROOAM3', name: 'Derde koppeling', credentialState: 'NONE' }),
    ];
    state.readiness = {};
    await open('/setup/suppliers/1/links', state);
    expect(screen.getByTestId('link-credential-501')).toHaveTextContent('Wachtwoord: Veilig bewaard');
    expect(screen.getByTestId('link-credential-502')).toHaveTextContent('Wachtwoord: Ingetrokken');
    expect(screen.getByTestId('link-credential-503')).toHaveTextContent('Wachtwoord: Geen');
  });

  it('mislukt de servercontrole, dan blijft de schatting uit versies en taken zonder foutmelding', async () => {
    installSetupServer(supplierState(), (url) =>
      url.includes('/import-links/readiness') ? jsonResponse({ error: 'stuk', code: 'INTERNAL' }, 500) : null,
    );
    renderRoutes('/setup/suppliers/1/links');
    const second = await screen.findByTestId('link-card-502');
    expect(second).toHaveTextContent('Nog niet klaar');
    expect(screen.getByTestId('link-card-501')).toHaveTextContent('Klaar');
    expect(screen.queryByTestId('link-readiness-501')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
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

describe('tab Bestand en velden: kolom "Uit legacy"', () => {
  function withLegacy(over: Record<string, unknown> = {}): SetupState {
    const state = supplierState();
    state.legacyOrigins = {
      99: legacyOrigin(99, {
        lines: [
          legacyLine(1, { targetKind: 'REVISION_FIELD', revisionField: 'identitySupplierField', column: 3 }),
          legacyLine(5, { targetKind: 'REVISION_FIELD', revisionField: 'recordBasePriceField', xmlElementPath: 'Article/Price' }),
          legacyLine(100, { targetKind: 'MAPPING', targetFieldCode: 'EAN', column: 12, prefix: '0' }),
          legacyLine(101, { targetKind: 'MAPPING', targetFieldCode: 'BRAND', position: 21, length: 10, calculation: '7' }),
          legacyLine(26, { column: 7, legacyFieldName: 'RESTVELD' }),
        ],
        ...over,
      }),
    };
    return state;
  }

  async function openFields(state: SetupState, identity?: Parameters<typeof renderRoutes>[1]) {
    installSetupServer(state);
    renderRoutes('/setup/suppliers/1/fields?definitie=10&versie=99', identity);
    await screen.findByTestId('revision-read-view');
  }

  it('toont de kolom met de beschrijving per rij, de niet overgenomen regels en geen banner', async () => {
    await openFields(withLegacy());
    const fields = screen.getByTestId('read-fields');
    await screen.findByTestId('legacy-column-head');
    expect(within(fields).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Veld',
      'Bron',
      'Bewerking',
      'Kritiek',
      'Verplicht',
      'Status',
      'Uit legacy',
    ]);
    expect(within(fields).getByTestId('read-field-5001')).toHaveTextContent('regel 100 · kolom 12 · voorvoegsel 0');
    expect(within(fields).getByTestId('read-field-5002')).toHaveTextContent('regel 101 · positie 21–30 · vertaling');
    expect(within(fields).getByTestId('read-field-5003')).toHaveTextContent('—');
    expect(fields).toHaveTextContent('regel 5 · XML Article/Price');
    expect(screen.getByTestId('read-identity')).toHaveTextContent('regel 1 · kolom 3');
    expect(screen.getByTestId('legacy-uncovered')).toHaveTextContent('regel 26 · RESTVELD · geen doelveld');
    expect(screen.queryByTestId('legacy-changed-banner')).not.toBeInTheDocument();
  });

  it('een versie die niet uit legacy komt heeft geen kolom en geen melding', async () => {
    const calls = installSetupServer(supplierState());
    renderRoutes('/setup/suppliers/1/fields?definitie=10&versie=99');
    await screen.findByTestId('revision-read-view');
    await waitFor(() => expect(calls.urls.some((url) => url.endsWith('/revisions/99/legacy-origin'))).toBe(true));
    const fields = screen.getByTestId('read-fields');
    expect(within(fields).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Veld', 'Bron', 'Bewerking', 'Kritiek', 'Verplicht', 'Status']);
    expect(screen.queryByTestId('legacy-origin-failed')).not.toBeInTheDocument();
    expect(screen.queryByTestId('legacy-uncovered')).not.toBeInTheDocument();
  });

  it('sinds de overname gewijzigd: banner en de kolom "Uit legacy (bij overname)"', async () => {
    await openFields(withLegacy({ changedSinceMigration: true }));
    expect(await screen.findByTestId('legacy-changed-banner')).toHaveTextContent(
      'Herkomst van de overgenomen versie; deze versie is sindsdien gewijzigd.',
    );
    expect(await screen.findByTestId('legacy-column-head')).toHaveTextContent('Uit legacy (bij overname)');
  });

  it('een geërfde herkomst toont "geërfd van versie n"', async () => {
    await openFields(withLegacy({ originKind: 'INHERITED', originRevisionId: 98 }));
    expect(await screen.findByTestId('legacy-origin-kind')).toHaveTextContent('geërfd van versie 2');
  });

  it('de ontvangst is enkel een link met het recht Beheren; zonder staat er gewoon #id', async () => {
    await openFields(withLegacy());
    await screen.findByTestId('legacy-column-head');
    expect(screen.getByTestId('legacy-receipt-link')).toHaveAttribute('href', '/setup/legacy-config/77');
    cleanup();

    await openFields(withLegacy(), testIdentityWith('catalogImport.read'));
    await screen.findByTestId('legacy-column-head');
    expect(screen.queryByTestId('legacy-receipt-link')).not.toBeInTheDocument();
    expect(screen.getByTestId('legacy-receipt-plain')).toHaveTextContent('#77');
  });

  it('een mislukte herkomst blokkeert het leesbeeld niet: geen kolom, wel een kleine melding', async () => {
    installSetupServer(supplierState(), (url) =>
      url.endsWith('/legacy-origin') ? jsonResponse({ error: 'kapot', code: 'INTERNAL' }, 500) : null,
    );
    renderRoutes('/setup/suppliers/1/fields?definitie=10&versie=99');
    expect(await screen.findByTestId('legacy-origin-failed')).toHaveTextContent('De herkomst uit legacy kon niet geladen worden');
    const fields = screen.getByTestId('read-fields');
    expect(within(fields).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Veld', 'Bron', 'Bewerking', 'Kritiek', 'Verplicht', 'Status']);
    expect(within(fields).getByTestId('read-field-5001')).toHaveTextContent('BARCODE');
  });
});

describe('tab Koppelingen en taken: standaarden bij aanmaak (CD-F)', () => {
  const VAT = [
    { code: '3', description: 'BTW21%', percentage: 21, occurrences: 1 },
    { code: '1', description: 'BTW6%', percentage: 6, occurrences: 2 },
  ];
  const UNITS = [
    { code: 'ST', singular: 'Stuk', abbreviation: 'st', occurrences: 1 },
    { code: 'M', singular: 'Meter', abbreviation: 'm', occurrences: 1 },
  ];
  const NOTE = 'Wordt gebruikt zodra CatalogImport artikels aanmaakt. Bestaande artikels worden nooit aangepast.';

  /** Koppeling 501 met een standaard uit legacy, koppeling 502 zonder; beide Prodis-lijsten beschikbaar. */
  function defaultsState(): SetupState {
    const state = supplierState();
    state.links = [
      link(501, 10, {
        code: 'VROOAM',
        name: 'Vrooam koppeling',
        defaultVatCode: '3',
        defaultUnitCode: 'ST',
        creationDefaultsOrigin: 'LEGACY',
        creationDefaultsUpdatedAt: '2026-10-20T10:00:00Z',
        creationDefaultsUpdatedBy: 'overname',
      }),
      link(502, 10, { code: 'VROOAM2', name: 'Tweede koppeling' }),
    ];
    state.readiness = {};
    state.prodisVatCodes = VAT;
    state.prodisUnits = UNITS;
    return state;
  }

  async function openLinks(
    state: SetupState,
    identity?: Parameters<typeof renderRoutes>[1],
    extra?: (url: string, init?: RequestInit) => Response | null,
  ) {
    const calls = installSetupServer(state, extra);
    renderRoutes('/setup/suppliers/1/links', identity);
    await screen.findByTestId('link-card-501');
    return calls;
  }

  async function openEditor(linkId: number) {
    fireEvent.click(screen.getByTestId(`creation-defaults-edit-${linkId}`));
    return screen.findByTestId(`creation-defaults-editor-${linkId}`);
  }

  it('toont btw-code en eenheid met omschrijving, de badge "Uit legacy", wie het wijzigde en de vaste zin', async () => {
    await openLinks(defaultsState());
    const block = screen.getByTestId('creation-defaults-501');
    expect(within(block).getByRole('heading', { name: 'Standaarden bij aanmaak' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('creation-vat-value-501')).toHaveTextContent('3 — BTW21% (21 %)'));
    expect(screen.getByTestId('creation-unit-value-501')).toHaveTextContent('ST — Stuk');
    expect(within(block).getByText('Uit legacy')).toBeInTheDocument();
    expect(screen.getByTestId('creation-defaults-changed-501')).toHaveTextContent(
      `gewijzigd door overname op ${formatDateTime('2026-10-20T10:00:00Z')}`,
    );
    expect(block).toHaveTextContent(NOTE);
    expect(screen.queryByTestId('creation-defaults-overwrite-note-501')).not.toBeInTheDocument();
  });

  it('een koppeling zonder standaard toont "Niet ingesteld", zonder badge en zonder wijzigingsregel', async () => {
    await openLinks(defaultsState());
    const block = screen.getByTestId('creation-defaults-502');
    expect(screen.getByTestId('creation-vat-value-502')).toHaveTextContent('Niet ingesteld');
    expect(screen.getByTestId('creation-unit-value-502')).toHaveTextContent('Niet ingesteld');
    expect(within(block).queryByText('Uit legacy')).not.toBeInTheDocument();
    expect(screen.queryByTestId('creation-defaults-changed-502')).not.toBeInTheDocument();
    expect(block).toHaveTextContent(NOTE);
  });

  it('een handmatig gezette standaard heeft geen badge "Uit legacy" maar toont wel wie ze wijzigde', async () => {
    const state = defaultsState();
    state.links = [
      link(501, 10, {
        defaultVatCode: '1',
        defaultUnitCode: null,
        creationDefaultsOrigin: 'MANUAL',
        creationDefaultsUpdatedAt: '2026-10-22T09:00:00Z',
        creationDefaultsUpdatedBy: 'an.peeters',
      }),
    ];
    await openLinks(state);
    const block = screen.getByTestId('creation-defaults-501');
    expect(within(block).queryByText('Uit legacy')).not.toBeInTheDocument();
    expect(screen.getByTestId('creation-defaults-changed-501')).toHaveTextContent('gewijzigd door an.peeters');
    expect(screen.getByTestId('creation-unit-value-501')).toHaveTextContent('Niet ingesteld');
  });

  it('zonder recht Beheren: enkel de codes, Wijzigen uitgeschakeld mét reden en de Prodis-lijsten worden niet gevraagd', async () => {
    const calls = await openLinks(defaultsState(), testIdentityWith('catalogImport.read'));
    expect(screen.getByTestId('creation-vat-value-501')).toHaveTextContent(/^3$/);
    expect(screen.getByTestId('creation-unit-value-501')).toHaveTextContent(/^ST$/);
    const edit = screen.getByTestId('creation-defaults-edit-501');
    expect(edit).toBeDisabled();
    expect(edit).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(calls.urls.some((url) => url.includes('/prodis-reference/'))).toBe(false);
  });

  it('Wijzigen: keuze uit de gefilterde lijst, "komt n keer voor in Prodis", Opslaan stuurt beide waarden', async () => {
    const calls = await openLinks(defaultsState());
    const editor = await openEditor(501);
    await waitFor(() => expect(within(editor).getAllByRole('combobox')).toHaveLength(2));
    const vat = within(editor).getByLabelText('Btw-code');
    const unit = within(editor).getByLabelText('Eenheid');
    expect(vat).toHaveValue('3');
    expect(unit).toHaveValue('ST');

    fireEvent.change(vat, { target: { value: 'BTW6' } });
    const option = await within(editor).findByRole('option', { name: '1 · BTW6% (6 %) — komt 2 keer voor in Prodis' });
    expect(within(editor).queryByRole('option', { name: /BTW21%/ })).not.toBeInTheDocument();
    fireEvent.click(option);
    expect(vat).toHaveValue('1');

    fireEvent.change(unit, { target: { value: 'meter' } });
    fireEvent.click(await within(editor).findByRole('option', { name: 'M · Meter' }));
    expect(unit).toHaveValue('M');

    fireEvent.click(within(editor).getByTestId('creation-defaults-save-501'));
    await screen.findByTestId('creation-defaults-saved-501');
    expect(calls.requests).toHaveLength(1);
    expect(calls.requests[0]).toMatchObject({
      method: 'PUT',
      body: { vatCode: '1', unitCode: 'M' },
    });
    expect(calls.requests[0]!.url).toContain('/setup/links/501/creation-defaults');
    expect(screen.queryByTestId('creation-defaults-editor-501')).not.toBeInTheDocument();
    // Handmatig gewijzigd: de badge "Uit legacy" verdwijnt en de wijziger staat erbij.
    expect(within(screen.getByTestId('creation-defaults-501')).queryByText('Uit legacy')).not.toBeInTheDocument();
    expect(screen.getByTestId('creation-defaults-changed-501')).toHaveTextContent('gewijzigd door An Beslisser');
    expect(screen.getByTestId('creation-vat-value-501')).toHaveTextContent('1 — BTW6% (6 %)');
    expect(screen.getByTestId('creation-unit-value-501')).toHaveTextContent('M — Meter');
  });

  it('vrije tekst is toegestaan en wordt zo verstuurd (de server controleert)', async () => {
    const calls = await openLinks(defaultsState());
    const editor = await openEditor(502);
    await waitFor(() => expect(within(editor).getAllByRole('combobox')).toHaveLength(2));
    fireEvent.change(within(editor).getByLabelText('Btw-code'), { target: { value: ' 99 ' } });
    fireEvent.click(within(editor).getByTestId('creation-defaults-save-502'));
    await screen.findByTestId('creation-defaults-saved-502');
    expect(calls.requests[0]!.body).toEqual({ vatCode: '99', unitCode: null });
  });

  it('beide velden leegmaken wist de standaard (null) en toont "Niet ingesteld"', async () => {
    const calls = await openLinks(defaultsState());
    const editor = await openEditor(501);
    await waitFor(() => expect(within(editor).getAllByRole('combobox')).toHaveLength(2));
    fireEvent.change(within(editor).getByLabelText('Btw-code'), { target: { value: '' } });
    fireEvent.change(within(editor).getByLabelText('Eenheid'), { target: { value: '  ' } });
    fireEvent.click(within(editor).getByTestId('creation-defaults-save-501'));
    await screen.findByTestId('creation-defaults-saved-501');
    expect(calls.requests[0]!.body).toEqual({ vatCode: null, unitCode: null });
    expect(screen.getByTestId('creation-vat-value-501')).toHaveTextContent('Niet ingesteld');
    expect(screen.getByTestId('creation-unit-value-501')).toHaveTextContent('Niet ingesteld');
  });

  it('Annuleren sluit de editor zonder te bewaren', async () => {
    const calls = await openLinks(defaultsState());
    const editor = await openEditor(501);
    fireEvent.click(within(editor).getByRole('button', { name: 'Annuleren' }));
    expect(screen.queryByTestId('creation-defaults-editor-501')).not.toBeInTheDocument();
    expect(calls.requests).toHaveLength(0);
  });

  it('toont de waarschuwingen van de server na het bewaren, in gewoon Nederlands', async () => {
    const state = defaultsState();
    state.creationDefaultsWarnings = [
      { code: 'PRODIS_REFERENCE_AMBIGUOUS', field: 'vatCode', message: 'VAT code 1 occurs more than once' },
      { code: 'PRODIS_REFERENCE_NOT_VERIFIED', field: 'unitCode', message: 'unit ST not verified' },
    ];
    await openLinks(state);
    const editor = await openEditor(501);
    fireEvent.click(within(editor).getByTestId('creation-defaults-save-501'));
    const warnings = await screen.findByTestId('creation-defaults-warnings-501');
    expect(warnings).toHaveTextContent('Btw-code: Code komt meerdere keren voor in Prodis');
    expect(warnings).toHaveTextContent('Eenheid: Niet gecontroleerd in Prodis');
    expect(warnings).not.toHaveTextContent('PRODIS_REFERENCE');
    expect(screen.getByTestId('creation-defaults-saved-501')).toHaveTextContent('Standaarden bij aanmaak opgeslagen.');
  });

  it('Prodis-lijst niet beschikbaar (409): gewone tekstvelden met de melding, vrije tekst blijft te bewaren', async () => {
    const state = defaultsState();
    state.prodisVatCodes = undefined;
    state.prodisUnits = undefined;
    const calls = await openLinks(state);
    const editor = await openEditor(502);
    expect(within(editor).queryByRole('combobox')).not.toBeInTheDocument();
    expect(within(editor).getByTestId('creation-vat-502-unavailable')).toHaveTextContent('Prodis-lijst niet beschikbaar');
    expect(within(editor).getByTestId('creation-unit-502-unavailable')).toHaveTextContent('Prodis-lijst niet beschikbaar');
    fireEvent.change(within(editor).getByLabelText('Btw-code'), { target: { value: '3' } });
    fireEvent.change(within(editor).getByLabelText('Eenheid'), { target: { value: 'ST' } });
    fireEvent.click(within(editor).getByTestId('creation-defaults-save-502'));
    await screen.findByTestId('creation-defaults-saved-502');
    expect(calls.requests[0]!.body).toEqual({ vatCode: '3', unitCode: 'ST' });
    // Zonder lijst blijft de weergave bij de code.
    expect(screen.getByTestId('creation-vat-value-502')).toHaveTextContent(/^3$/);
  });

  it('Prodis-lijst onbereikbaar (503) geeft dezelfde terugval en geen schermfout', async () => {
    await openLinks(defaultsState(), undefined, (url) =>
      url.includes('/prodis-reference/') ? jsonResponse({ error: 'down', code: 'PRODIS_DB_UNAVAILABLE' }, 503) : null,
    );
    const editor = await openEditor(501);
    await within(editor).findByTestId('creation-vat-501-unavailable');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('een afgekeurde code (400) staat als nette fout bij de editor; de editor blijft open en niets is bewaard', async () => {
    const calls = await openLinks(defaultsState(), undefined, (url, init) =>
      init?.method === 'PUT' && url.includes('/creation-defaults')
        ? jsonResponse({ error: 'not found', code: 'PRODIS_VAT_CODE_NOT_FOUND' }, 400)
        : null,
    );
    const editor = await openEditor(501);
    await waitFor(() => expect(within(editor).getAllByRole('combobox')).toHaveLength(2));
    fireEvent.change(within(editor).getByLabelText('Btw-code'), { target: { value: '77' } });
    fireEvent.click(within(editor).getByTestId('creation-defaults-save-501'));
    const alert = await within(editor).findByRole('alert');
    expect(alert).toHaveTextContent('Btw-code niet gevonden in Prodis');
    expect(screen.getByTestId('creation-defaults-editor-501')).toBeInTheDocument();
    expect(screen.queryByTestId('creation-defaults-saved-501')).not.toBeInTheDocument();
    expect(calls.requests).toHaveLength(0);
    expect(screen.getByTestId('creation-vat-value-501')).toHaveTextContent('3 — BTW21% (21 %)');
  });

  it('staat de vlag "standaarden overschrijven" aan, dan zegt een gedempte regel dat een nieuwe overname ze overschrijft', async () => {
    const state = defaultsState();
    state.definitions = [definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: 99, overwriteCreationDefaults: true })];
    await openLinks(state);
    for (const id of [501, 502]) {
      expect(screen.getByTestId(`creation-defaults-overwrite-note-${id}`)).toHaveTextContent(
        'Bij een nieuwe overname uit legacy worden deze waarden overschreven (instelling van de importdefinitie).',
      );
    }
  });
});
