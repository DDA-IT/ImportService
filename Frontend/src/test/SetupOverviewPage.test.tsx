/**
 * R1 — het dashboard "Leveranciers" (`/setup`, `SetupOverviewPage`): kaartenrooster met één detailpaneel, de platte
 * leverancierslijst met zoek en filters, de bulkaanroepen met doorbladeren, de rechten (Beheren) en de oude adresvorm
 * `?definitionId=&revisionId=` die doorverwijst naar het leveranciersdetail.
 *
 * Bewaakt ook de behouden testids uit de oude boom (`source-organisations`, `continue-*`, `check-link-*`,
 * `new-supplier-button`).
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { expectNoOldWording } from './oldWording';
import { testIdentityWith } from './testIdentity';
import {
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

const originalFetch = global.fetch;

function dashboardState(): SetupState {
  const state = emptySetupState();
  state.organisations = [
    organisation(1, { code: 'VROOAM', name: 'Vrooam', type: 'PURCHASING_ASSOCIATION' }),
    organisation(2, { code: 'KRAMP', name: 'Kramp Groep' }),
    organisation(3, { code: 'OUDLEV', name: 'Oude leverancier', active: false }),
    organisation(4, { code: 'INTERCARS', name: 'Inter Cars' }),
  ];
  state.definitions = [
    definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: 99 }),
    definition(11, 1, { name: 'Vrooam voorraad' }),
    definition(20, 4, { name: 'Inter Cars artikelbestand' }),
    definition(30, 2, { name: 'Kramp Pricat', usageType: 'REUSABLE_TEMPLATE', activeRevisionId: 301 }),
  ];
  state.links = [link(501, 10), link(502, 20)];
  state.tasks = [task(7, 501, 10, { name: 'Handmatige levering', activeRevisionId: 99 })];
  state.receipts = [
    {
      id: 5,
      originEnvironment: 'PROD',
      originDossier: 'D1',
      interfaceCode: 'KRAMP_ART',
      status: 'RECEIVED',
      receivedAt: '2026-10-22T08:00:00Z',
    },
    {
      id: 6,
      originEnvironment: 'PROD',
      originDossier: 'D1',
      interfaceCode: 'WILMINK',
      status: 'RECEIVED',
      receivedAt: '2026-10-21T08:00:00Z',
    },
  ];
  return state;
}

beforeEach(() => {
  global.fetch = originalFetch;
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  global.fetch = originalFetch;
});

async function dashboard(state: SetupState = dashboardState(), path = '/setup', identity?: Parameters<typeof renderRoutes>[1]) {
  const calls = installSetupServer(state);
  const view = renderRoutes(path, identity);
  await screen.findByTestId('setup-cards');
  return { calls, ...view };
}

describe('dashboard Leveranciers', () => {
  it('toont de kop, het kruimelpad en de knop "Leverancier toevoegen" met het stappenplan', async () => {
    await dashboard();
    expect(screen.getByRole('heading', { level: 1, name: 'Leveranciers' })).toBeInTheDocument();
    const crumbs = screen.getAllByRole('listitem').map((item) => item.textContent);
    expect(crumbs).toEqual(expect.arrayContaining(['CatalogImport', 'Inrichting']));
    const add = screen.getByTestId('new-supplier-button');
    expect(add).toHaveAttribute('href', '/setup/new');
    expect(add).toHaveTextContent('Leverancier toevoegen');
    expectNoOldWording(document.body, 'dashboard Leveranciers');
  });

  it('kaarten: telt organisaties, klare taken, wat ontbreekt, ontvangsten en importdefinities', async () => {
    await dashboard();
    expect(screen.getByTestId('setup-card-count-suppliers')).toHaveTextContent('4');
    expect(screen.getByTestId('setup-card-suppliers')).toHaveTextContent('3 leveranciers, 1 aankoopvereniging');
    expect(screen.getByTestId('setup-card-count-ready')).toHaveTextContent('1');
    expect(screen.getByTestId('setup-card-count-notReady')).toHaveTextContent('5');
    expect(screen.getByTestId('setup-card-notReady')).toHaveTextContent('2 zonder actieve versie');
    expect(screen.getByTestId('setup-card-notReady')).toHaveTextContent('1 koppeling zonder taak');
    expect(screen.getByTestId('setup-card-notReady')).toHaveTextContent('2 zonder koppeling');
    expect(screen.getByTestId('setup-card-count-legacy')).toHaveTextContent('2');
    expect(screen.getByTestId('setup-card-count-definitions')).toHaveTextContent('4');
    expect(screen.getByTestId('setup-card-definitions')).toHaveTextContent('1 herbruikbaar, 3 eigen');
    expect(within(screen.getByTestId('setup-card-legacy')).getByRole('link', { name: 'Alle ontvangsten' })).toHaveAttribute(
      'href',
      '/setup/legacy-config',
    );
    expect(within(screen.getByTestId('setup-card-definitions')).getByRole('link', { name: 'Alle importdefinities' })).toHaveAttribute(
      'href',
      '/templates',
    );
  });

  it('"Nog niet klaar" staat standaard open met reden en acties per rij', async () => {
    await dashboard();
    const button = within(screen.getByTestId('setup-card-notReady')).getByRole('button');
    expect(button).toHaveAttribute('aria-expanded', 'true');
    const rows = screen.getByTestId('not-ready-rows');
    expect(within(rows).getByText('Nog geen importdefinitie')).toBeInTheDocument();
    expect(within(rows).getAllByText('Versie niet geactiveerd')).toHaveLength(2);
    expect(within(rows).getByTestId('continue-definition-11')).toHaveAttribute('href', '/setup/new?organisationId=1&definitionId=11');
    expect(within(rows).getByText('Nog geen taak')).toBeInTheDocument();
    expect(within(rows).getByTestId('continue-organisation-2')).toHaveAttribute('href', '/setup/new?organisationId=2');
    expect(within(rows).getByTestId('continue-link-502')).toHaveAttribute(
      'href',
      '/setup/new?organisationId=4&definitionId=20&linkId=502',
    );
    expect(within(rows).getAllByTestId('check-link-502')[0]).toHaveAttribute('href', '/setup/links/502/check?definitionId=20');
    // De bron van de kaart-id's: paneel en kaart verwijzen naar elkaar.
    expect(button).toHaveAttribute('aria-controls', screen.getByTestId('setup-card-panel').id);
  });

  it('een kaart klapt het paneel open en dicht (aria-expanded) en toont dan de leveranciers', async () => {
    await dashboard();
    const button = within(screen.getByTestId('setup-card-suppliers')).getByRole('button');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    const panel = screen.getByTestId('setup-card-panel');
    expect(within(panel).getByText('Vrooam')).toBeInTheDocument();
    expect(within(panel).getAllByRole('link', { name: 'Naar leverancier' })[0]).toHaveAttribute('href', '/setup/suppliers/1');
    fireEvent.click(button);
    expect(screen.queryByTestId('setup-card-panel')).not.toBeInTheDocument();
  });

  it('de lege kaart "Nog niet klaar" is uitgeschakeld met de reden in tekst', async () => {
    const state = emptySetupState();
    state.organisations = [organisation(1)];
    state.definitions = [definition(10, 1, { activeRevisionId: 99 })];
    state.links = [link(501, 10)];
    state.tasks = [task(7, 501, 10, { activeRevisionId: 99 })];
    await dashboard(state);
    const card = screen.getByTestId('setup-card-notReady');
    expect(within(card).getByRole('button')).toBeDisabled();
    expect(card).toHaveTextContent('Niets dat nog ontbreekt');
    expect(screen.queryByTestId('setup-card-panel')).not.toBeInTheDocument();
  });

  it('de kaart "Overname uit legacy" bestaat enkel met het recht Beheren', async () => {
    await dashboard(dashboardState(), '/setup', testIdentityWith('catalogImport.read'));
    expect(screen.queryByTestId('setup-card-legacy')).not.toBeInTheDocument();
    expect(screen.getByTestId('setup-card-suppliers')).toBeInTheDocument();
  });

  it('zonder recht Beheren is "Leverancier toevoegen" en "Verder inrichten" uitgeschakeld mét reden', async () => {
    await dashboard(dashboardState(), '/setup', testIdentityWith('catalogImport.read'));
    const add = screen.getByTestId('new-supplier-button');
    expect(add.tagName).toBe('BUTTON');
    expect(add).toBeDisabled();
    expect(add).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByTestId('new-supplier-blocked')).toHaveTextContent("U heeft het recht 'Beheren'");
    const cont = screen.getByTestId('continue-organisation-2');
    expect(cont.tagName).toBe('BUTTON');
    expect(cont).toBeDisabled();
    expect(cont).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
  });

  it('de lijst toont per leverancier soort, status, koppelingen en gereedheid, en opent het detail', async () => {
    await dashboard();
    const body = screen.getByTestId('source-organisations');
    const vrooam = within(body).getByTestId('supplier-row-1');
    expect(vrooam).toHaveTextContent('VROOAM');
    expect(vrooam).toHaveTextContent('Aankoopvereniging');
    expect(vrooam).toHaveTextContent('Klaar');
    expect(within(vrooam).getByRole('link', { name: 'Openen: Vrooam' })).toHaveAttribute('href', '/setup/suppliers/1');
    expect(within(body).getByTestId('supplier-row-2')).toHaveTextContent('Nog niet ingericht');
    expect(within(body).getByTestId('supplier-row-3')).toHaveTextContent('Inactief');
    expect(within(body).getByTestId('supplier-row-4')).toHaveTextContent('Nog niet klaar');
    // Koppelingen-kolom
    expect(within(vrooam).getAllByRole('cell')[4]).toHaveTextContent('1');
  });

  it('zoek en filters op soort en status beperken de lijst', async () => {
    await dashboard();
    fireEvent.change(screen.getByTestId('supplier-search'), { target: { value: 'kramp' } });
    let body = screen.getByTestId('source-organisations');
    expect(within(body).getAllByRole('row')).toHaveLength(1);
    expect(within(body).getByTestId('supplier-row-2')).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('supplier-search'), { target: { value: '' } });
    fireEvent.change(screen.getByTestId('supplier-filter-kind'), { target: { value: 'PURCHASING_ASSOCIATION' } });
    body = screen.getByTestId('source-organisations');
    expect(within(body).getAllByRole('row')).toHaveLength(1);
    expect(within(body).getByTestId('supplier-row-1')).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('supplier-filter-kind'), { target: { value: '' } });
    fireEvent.change(screen.getByTestId('supplier-filter-status'), { target: { value: 'inactive' } });
    body = screen.getByTestId('source-organisations');
    expect(within(body).getByTestId('supplier-row-3')).toBeInTheDocument();
    expect(within(body).queryByTestId('supplier-row-1')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('supplier-search'), { target: { value: 'bestaat niet' } });
    expect(screen.getByText('Geen leveranciers die bij de zoekopdracht passen.')).toBeInTheDocument();
  });

  it('bladert door alle pagina\'s van de bulkaanroepen en toont het totaal', async () => {
    const state = emptySetupState();
    state.organisations = Array.from({ length: 205 }, (_, index) => organisation(index + 1));
    const { calls } = await dashboard(state);
    expect(screen.getByTestId('setup-card-count-suppliers')).toHaveTextContent('205');
    const orgCalls = calls.urls.filter((url) => url.includes('/source-organisations'));
    expect(orgCalls).toHaveLength(2);
    expect(orgCalls[0]).toContain('size=200');
    expect(screen.getByText('1-25 van 205')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Volgende' }));
    expect(screen.getByText('26-50 van 205')).toBeInTheDocument();
  });

  it('een lege installatie toont een lege lijst en geen paneel', async () => {
    await dashboard(emptySetupState());
    expect(screen.getByTestId('setup-card-count-suppliers')).toHaveTextContent('0');
    expect(screen.getByText('Geen leveranciers of aankoopverenigingen gevonden.')).toBeInTheDocument();
  });

  it('toont een foutmelding als een bulkaanroep mislukt, zonder een half dashboard', async () => {
    installSetupServer(dashboardState(), (url) =>
      url.includes('/tasks') ? jsonResponse({ error: 'stuk', code: 'INTERNAL' }, 500) : null,
    );
    renderRoutes('/setup');
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByTestId('setup-cards')).not.toBeInTheDocument();
  });
});

describe('dashboard: tweede fase met de controle van de koppelingen (F4)', () => {
  function readinessCalls(calls: { urls: string[] }): string[] {
    return calls.urls.filter((url) => url.includes('/import-links/readiness'));
  }

  it('een koppeling die de controle niet doorstaat haalt de leverancier uit "Klaar" en komt als reden in het paneel', async () => {
    const state = dashboardState();
    state.readiness = {
      501: {
        ready: false,
        problems: [{ code: 'NO_ACTIVE_REVISION', status: 'PROBLEM', subject: { type: 'DEFINITION', id: 10 }, detail: 'x' }],
      },
    };
    const { calls } = await dashboard(state);
    // Alleen koppelingen van eigen definities van actieve organisaties (501 en 502).
    expect(readinessCalls(calls)).toHaveLength(1);
    expect(decodeURIComponent(readinessCalls(calls)[0]!)).toContain('linkIds=501,502');
    const row = within(screen.getByTestId('source-organisations')).getByTestId('supplier-row-1');
    expect(row).toHaveTextContent('Nog niet klaar');
    expect(screen.getByTestId('setup-card-count-ready')).toHaveTextContent('0');
    expect(
      within(screen.getByTestId('not-ready-rows')).getByText(
        'De controle van de koppeling is niet geslaagd: er is nog geen versie in gebruik',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('link-checks-unavailable')).not.toBeInTheDocument();
  });

  it('250 koppelingen geven 3 verzoeken (100, 100, 50)', async () => {
    const state = emptySetupState();
    state.organisations = [organisation(1)];
    state.definitions = [definition(10, 1, { activeRevisionId: 99 })];
    state.links = Array.from({ length: 250 }, (_, index) => link(index + 1, 10));
    state.tasks = [task(7, 1, 10, { activeRevisionId: 99 })];
    const { calls } = await dashboard(state);
    const requests = readinessCalls(calls);
    expect(requests).toHaveLength(3);
    const sizes = requests.map((url) => decodeURIComponent(url.split('linkIds=')[1]!).split(',').length).sort((x, y) => y - x);
    expect(sizes).toEqual([100, 100, 50]);
    expect(within(screen.getByTestId('source-organisations')).getByTestId('supplier-row-1')).toHaveTextContent('Klaar');
  });

  it('zonder koppelingen van eigen definities is er geen tweede fase', async () => {
    const state = emptySetupState();
    state.organisations = [organisation(1)];
    const { calls } = await dashboard(state);
    expect(readinessCalls(calls)).toHaveLength(0);
    expect(screen.queryByTestId('link-checks-unavailable')).not.toBeInTheDocument();
  });

  it('mislukt de controle, dan blijft de heuristiek en staat er een niet-blokkerende melding', async () => {
    installSetupServer(dashboardState(), (url) =>
      url.includes('/import-links/readiness') ? jsonResponse({ error: 'stuk', code: 'INTERNAL' }, 500) : null,
    );
    renderRoutes('/setup');
    await screen.findByTestId('setup-cards');
    expect(await screen.findByTestId('link-checks-unavailable')).toHaveTextContent(
      'De controle van de koppelingen kon niet geladen worden',
    );
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    // Heuristiek: Vrooam blijft "Klaar" (actieve taak met actieve versie).
    expect(within(screen.getByTestId('source-organisations')).getByTestId('supplier-row-1')).toHaveTextContent('Klaar');
    expect(screen.getByTestId('setup-card-count-ready')).toHaveTextContent('1');
  });
});

describe('oude adresvorm ?definitionId=&revisionId=', () => {
  it('verwijst door naar Bestand en velden van de juiste leverancier, met Bewerken aan', async () => {
    const state = dashboardState();
    state.revisions[10] = [
      { id: 99, definitionId: 10, revisionNumber: 1, status: 'ACTIVE' },
      { id: 98, definitionId: 10, revisionNumber: 2, status: 'DRAFT' },
    ];
    state.details[98] = revisionDetail(98, 10, { revisionNumber: 2, status: 'DRAFT' });
    state.details[99] = revisionDetail(99, 10, { revisionNumber: 1, status: 'ACTIVE' });
    installSetupServer(state);
    const { router } = renderRoutes('/setup?definitionId=10&revisionId=98');
    expect(await screen.findByTestId('mode-edit')).toHaveAttribute('aria-pressed', 'true');
    expect(await screen.findByTestId('concept-activate')).toBeInTheDocument();
    await waitFor(() => expect(router.state.location.pathname).toBe('/setup/suppliers/1/fields'));
    expect(router.state.location.search).toContain('versie=98');
    expect(router.state.location.search).toContain('definitie=10');
    expect(screen.getByTestId('draft-banner')).toBeInTheDocument();
  });

  it('meldt een versie die bij geen enkele leverancier hoort', async () => {
    installSetupServer(dashboardState());
    renderRoutes('/setup?definitionId=999&revisionId=1');
    expect(await screen.findByRole('alert')).toHaveTextContent('hoort bij geen enkele leverancier');
  });
});
