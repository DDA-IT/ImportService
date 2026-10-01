/**
 * S1-F1 — `SetupOverviewPage` (scherm 1a, `docs/decisions.md` 2026-09-27 "scherm 1a/1b").
 *
 * Dekt: bronorganisatielijst laadt, uitklappen laadt lazy de definitielijst, uitklappen van een
 * definitie laadt lazy revisies en koppelingen, `activeRevisionId` null vs. gevuld wordt correct
 * getoond, en het koppelingsniveau toont sinds NT-7 de taken van de koppeling.
 *
 * NT-7: takenlijst per koppeling (naam, start, "nog niet klaar"), "Taak toevoegen" (met/zonder Beheren).
 *
 * NT-6: knop "Nieuwe leverancier en taak" (met/zonder Beheren), "Verder inrichten" op organisatie zonder
 * definitie, eigen definitie zonder koppeling en koppeling zonder taak, en het revisiedetail via de adresbalk.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, cleanup, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';
import { SetupOverviewPage } from '../features/setup/SetupOverviewPage';

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

const ORGANISATIONS_RESPONSE = {
  content: [
    { id: 1, code: 'ORG-1', name: 'VROOAM', type: 'PURCHASING_ASSOCIATION', active: true },
    { id: 2, code: 'ORG-2', name: 'Leverancier Twee', type: 'SUPPLIER', active: true },
  ],
  page: 0,
  size: 50,
  totalElements: 2,
  totalPages: 1,
};

const DEFINITIONS_RESPONSE = {
  content: [
    {
      id: 10,
      code: 'DEF-10',
      name: 'Definitie met actieve revisie',
      usageType: 'OWN_DEFINITION',
      sourceOrganisationId: 1,
      sourceOrganisationCode: 'ORG-1',
      activeRevisionId: 99,
    },
    {
      id: 11,
      code: 'DEF-11',
      name: 'Definitie zonder actieve revisie',
      usageType: 'OWN_DEFINITION',
      sourceOrganisationId: 1,
      sourceOrganisationCode: 'ORG-1',
      activeRevisionId: null,
    },
  ],
  page: 0,
  size: 50,
  totalElements: 2,
  totalPages: 1,
};

const REVISIONS_RESPONSE = {
  content: [
    { id: 99, definitionId: 10, revisionNumber: 1, status: 'ACTIVE' },
    { id: 98, definitionId: 10, revisionNumber: 2, status: 'DRAFT' },
  ],
  page: 0,
  size: 50,
  totalElements: 2,
  totalPages: 1,
};

const IMPORT_LINKS_RESPONSE = {
  content: [
    {
      id: 501,
      code: 'LNK-1',
      name: 'Koppeling Een',
      supplierCode: 'SUP1',
      supplierName: 'Leverancier Een',
      libraryCode: 'LIB1',
      active: true,
      importDefinitionId: 10,
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
};

const EMPTY_PAGE = { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 };

/** NT-7: een taak zoals `GET /tasks` ze levert (NT-4-velden inbegrepen). */
const TASK_READY = {
  id: 7,
  name: 'Klare taak',
  active: true,
  triggerType: 'MANUAL',
  preventConcurrentRuns: true,
  importLinkId: 501,
  importLinkCode: 'LNK-1',
  supplierCode: 'SUP1',
  libraryCode: 'LIB1',
  lastRunStartedAt: null,
  lastRunFinishedAt: null,
  importDefinitionId: 10,
  activeRevisionId: 99,
};

/** NT-6: een minimaal maar volledig revisiedetail (E1) voor het geopende revisiedetail via de adresbalk. */
const DRAFT_DETAIL = {
  id: 98,
  definitionId: 10,
  revisionNumber: 2,
  status: 'DRAFT',
  basedOnRevisionId: null,
  changeReason: null,
  identityProfileKind: 'THREE_PART',
  identitySupplierField: 'LEV',
  identitySupplierGroupField: 'GRP',
  identitySupplierReferenceField: 'REF',
  identityDiscountCodeField: null,
  structureFormat: 'CSV',
  structureCharset: 'UTF-8',
  structureDelimiter: ';',
  structureQuoteChar: '"',
  structureHasHeader: true,
  structureHeaderLineNumber: 1,
  structureFieldReferenceKind: 'HEADER_NAME',
  structureExpectedColumnCount: null,
  accessDeliverySetKind: 'UNDECLARED',
  recordBasePriceField: 'PRIJS',
  recordDescriptionField: null,
  recordCanonicalisationVersion: 2,
  recordCurrencyField: null,
  basePriceZeroAllowed: false,
  basePriceNegativeAllowed: false,
  priceDeviationPercent: 15,
  priceDeviationSeverity: 'WARNING',
  priceDerivationTolerance: 0.01,
  priceAvgShortWindow: 50,
  priceAvgLongWindow: 200,
  priceControlModel: 'DEVIATION',
  creationThresholdAbsolute: 100,
  creationThresholdSharePercent: 1,
  maxCriticalRecords: 0,
  maxRejectedRecords: null,
  maxCriticalSharePercent: 1,
  maxRejectedSharePercent: null,
  bulkIncidentSharePercent: 1,
  accessVersion: 1,
  accessConfigHash: 'a',
  structureVersion: 1,
  structureConfigHash: 'b',
  recordRulesVersion: 1,
  recordRulesConfigHash: 'c',
  compositeConfigHash: 'd',
  createdAt: '2026-09-30T08:00:00Z',
  createdBy: 'An Beslisser',
  createdBySubject: 'test-sub',
  updatedAt: '2026-09-30T08:00:00Z',
  approvedAt: null,
  approvedBy: null,
  approvedBySubject: null,
  mappings: [],
  filters: [],
  fieldCriticalities: [],
  bookmarks: [],
  bookmarkValues: [],
};

function renderPage(identity: ActorIdentity = TEST_IDENTITY, path = '/setup') {
  return render(
    <ActorProvider identity={identity}>
      <MemoryRouter initialEntries={[path]}>
        <SetupOverviewPage />
      </MemoryRouter>
    </ActorProvider>,
  );
}

/** Klapt ORG-1 en daarna DEF-10 (of DEF-11) open. */
async function expandDefinition(code: 'DEF-10' | 'DEF-11') {
  fireEvent.click((await screen.findAllByRole('button', { name: /Uitklappen/ }))[0]!);
  const definitionRow = await screen.findByText(code);
  fireEvent.click(within(definitionRow.closest('li')!).getByRole('button', { name: /Uitklappen/ }));
}

describe('SetupOverviewPage', () => {
  const originalFetch = global.fetch;
  let tasksForLink501: unknown = EMPTY_PAGE;

  beforeEach(() => {
    tasksForLink501 = EMPTY_PAGE;
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/tasks')) {
        return Promise.resolve(jsonResponse(url.includes('importLinkId=501') ? tasksForLink501 : EMPTY_PAGE));
      }
      if (url.includes('/definitions/10/revisions/98')) {
        return Promise.resolve(jsonResponse(DRAFT_DETAIL));
      }
      if (url.includes('/definitions/10/revisions')) {
        return Promise.resolve(jsonResponse(REVISIONS_RESPONSE));
      }
      if (/\/definitions\/\d+\/revisions/.test(url)) {
        return Promise.resolve(jsonResponse(EMPTY_PAGE));
      }
      if (url.includes('/import-links')) {
        return Promise.resolve(
          jsonResponse(url.includes('importDefinitionId=11') ? EMPTY_PAGE : IMPORT_LINKS_RESPONSE),
        );
      }
      if (url.includes('/definitions')) {
        return Promise.resolve(
          jsonResponse(url.includes('sourceOrganisationId=2') ? EMPTY_PAGE : DEFINITIONS_RESPONSE),
        );
      }
      if (url.includes('/source-organisations')) {
        return Promise.resolve(jsonResponse(ORGANISATIONS_RESPONSE));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('S1-F1.1: toont de bronorganisatielijst', async () => {
    renderPage();

    expect(await screen.findByText('ORG-1')).toBeInTheDocument();
    expect(screen.getByText('VROOAM')).toBeInTheDocument();
    expect(screen.getByText('ORG-2')).toBeInTheDocument();
  });

  it('S1-F1.2: klapt een organisatie uit en laadt lazy de definitielijst', async () => {
    renderPage();
    await screen.findByText('ORG-1');

    // Voor het uitklappen is er geen definitieaanroep geweest.
    expect(
      vi.mocked(global.fetch).mock.calls.some((call) => call[0]?.toString().includes('/definitions')),
    ).toBe(false);

    const buttons = await screen.findAllByRole('button', { name: /Uitklappen/ });
    fireEvent.click(buttons[0]!);

    expect(await screen.findByText('DEF-10')).toBeInTheDocument();
    expect(screen.getByText('DEF-11')).toBeInTheDocument();
  });

  it('S1-F1.3: toont activeRevisionId gevuld en null correct', async () => {
    renderPage();
    await screen.findByText('ORG-1');
    fireEvent.click((await screen.findAllByRole('button', { name: /Uitklappen/ }))[0]!);

    await screen.findByText('DEF-10');
    expect(screen.getByText('actieve versie: #99')).toBeInTheDocument();
    expect(screen.getByText('geen actieve versie')).toBeInTheDocument();
  });

  it('S1-F1.4: klapt een definitie uit en laadt lazy revisies en koppelingen', async () => {
    renderPage();
    await screen.findByText('ORG-1');
    fireEvent.click((await screen.findAllByRole('button', { name: /Uitklappen/ }))[0]!);
    await screen.findByText('DEF-10');

    // Voor het uitklappen van de definitie is er geen revisie-/koppelingaanroep geweest.
    expect(
      vi.mocked(global.fetch).mock.calls.some((call) => call[0]?.toString().includes('/revisions')),
    ).toBe(false);

    // Scope tot de definitielijst zelf: er staat ook nog een (ongerelateerde) "Uitklappen"-knop
    // voor de nog ingeklapte ORG-2 op het scherm, die telt hier niet mee.
    const definitionsList = (await screen.findByText('DEF-10')).closest('ul')!;
    const definitionButtons = within(definitionsList).getAllByRole('button', { name: /Uitklappen/ });
    expect(definitionButtons).toHaveLength(2);
    fireEvent.click(definitionButtons[0]!);

    expect(await screen.findByText('Versie 1')).toBeInTheDocument();
    expect(screen.getByText('Versie 2')).toBeInTheDocument();
    expect(await screen.findByText('LNK-1')).toBeInTheDocument();
  });

  it('S1-F1.5 / NT-7.1: taken-niveau toont de takenlijst van de koppeling (geen vaste tekst meer)', async () => {
    tasksForLink501 = {
      content: [
        { ...TASK_READY, id: 7, name: 'Klare taak', activeRevisionId: 99 },
        { ...TASK_READY, id: 8, name: 'Nieuwe taak', activeRevisionId: null },
      ],
      page: 0,
      size: 200,
      totalElements: 2,
      totalPages: 1,
    };
    renderPage();
    await expandDefinition('DEF-10');

    await screen.findByText('LNK-1');
    const tasks = await screen.findByTestId('link-tasks-501');
    expect(await within(tasks).findByText('Klare taak')).toBeInTheDocument();
    expect(within(tasks).getByText('Nieuwe taak')).toBeInTheDocument();
    // Hoe de taak start: Nederlands woord uit de woordenlijst, geen ruwe code.
    expect(within(within(tasks).getByTestId('task-7')).getByText('Handmatig')).toBeInTheDocument();
    expect(screen.queryByText(/staan nog niet in dit overzicht/)).not.toBeInTheDocument();
    // Gereedheid: enkel de taak zonder actieve versie is "nog niet klaar".
    expect(within(tasks).getByTestId('task-not-ready-8')).toHaveTextContent(
      'nog niet klaar: versie niet geactiveerd',
    );
    expect(within(tasks).queryByTestId('task-not-ready-7')).not.toBeInTheDocument();
    // Er is maar één takenverzoek per koppeling (dezelfde vraag beslist over "Verder inrichten").
    const taskCalls = vi
      .mocked(global.fetch)
      .mock.calls.filter((call) => String(call[0]).includes('/tasks?importLinkId=501'));
    expect(taskCalls).toHaveLength(1);
  });

  it('NT-7.2: koppeling zonder taak toont een Nederlandse uitleg', async () => {
    renderPage();
    await expandDefinition('DEF-10');
    const tasks = await screen.findByTestId('link-tasks-501');
    expect(await within(tasks).findByText(/Nog geen taak/)).toBeInTheDocument();
  });

  it('NT-7.3: "Taak toevoegen" opent het stappenplan bij de taakstap van die koppeling', async () => {
    renderPage();
    await expandDefinition('DEF-10');
    expect(await screen.findByTestId('add-task-501')).toHaveAttribute(
      'href',
      '/setup/new?organisationId=1&definitionId=10&linkId=501',
    );
  });

  it('NT-10: elke koppeling heeft "Controleren" naar het controlescherm, ook met enkel het recht Lezen', async () => {
    renderPage(testIdentityWith('catalogImport.read'));
    await expandDefinition('DEF-10');
    const link = await screen.findByTestId('check-link-501');
    expect(link.tagName).toBe('A');
    expect(link).toHaveTextContent('Controleren');
    expect(link).toHaveAttribute('href', '/setup/links/501/check?definitionId=10');
  });

  it('NT-7.4: zonder Beheren is "Taak toevoegen" uitgeschakeld mét reden, de takenlijst blijft leesbaar', async () => {
    tasksForLink501 = { content: [TASK_READY], page: 0, size: 200, totalElements: 1, totalPages: 1 };
    renderPage(testIdentityWith('catalogImport.read'));
    await expandDefinition('DEF-10');
    const action = await screen.findByTestId('add-task-501');
    expect(action.tagName).toBe('BUTTON');
    expect(action).toBeDisabled();
    expect(action).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByText('Klare taak')).toBeInTheDocument();
  });

  // --- NT-6: stappenplan "Nieuwe leverancier en taak" ---------------------------------------------------

  it('NT-6.1: met Beheren opent "Nieuwe leverancier en taak" het stappenplan', async () => {
    renderPage();
    await screen.findByText('ORG-1');
    const link = screen.getByRole('link', { name: 'Nieuwe leverancier en taak' });
    expect(link).toHaveAttribute('href', '/setup/new');
  });

  it('NT-6.2: zonder Beheren is de knop uitgeschakeld mét reden', async () => {
    renderPage(testIdentityWith('catalogImport.read'));
    await screen.findByText('ORG-1');
    const button = screen.getByRole('button', { name: 'Nieuwe leverancier en taak' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByTestId('new-supplier-blocked')).toHaveTextContent("U heeft het recht 'Beheren'");
  });

  it('NT-6.3: "Verder inrichten" bij een organisatie zonder beschrijving', async () => {
    renderPage();
    await screen.findByText('ORG-2');
    fireEvent.click((await screen.findAllByRole('button', { name: /Uitklappen/ }))[1]!);

    expect(await screen.findByText(/Geen beschrijvingen voor deze leverancier of aankoopvereniging/)).toBeInTheDocument();
    expect(screen.getByTestId('continue-organisation-2')).toHaveAttribute('href', '/setup/new?organisationId=2');
  });

  it('NT-6.4: "Verder inrichten" bij een eigen definitie zonder koppeling', async () => {
    renderPage();
    await expandDefinition('DEF-11');

    expect(await screen.findByText(/Geen koppelingen voor deze beschrijving/)).toBeInTheDocument();
    expect(screen.getByTestId('continue-definition-11')).toHaveAttribute(
      'href',
      '/setup/new?organisationId=1&definitionId=11',
    );
  });

  it('NT-6.5: "Verder inrichten" bij een koppeling zonder taak, niet bij een koppeling met taak', async () => {
    renderPage();
    await expandDefinition('DEF-10');
    expect(await screen.findByTestId('continue-link-501')).toHaveAttribute(
      'href',
      '/setup/new?organisationId=1&definitionId=10&linkId=501',
    );
    cleanup();

    tasksForLink501 = { content: [{ id: 5, name: 'Taak' }], page: 0, size: 1, totalElements: 1, totalPages: 1 };
    vi.mocked(global.fetch).mockClear();
    renderPage();
    await expandDefinition('DEF-10');
    await screen.findByText('LNK-1');
    await waitFor(() =>
      expect(
        vi.mocked(global.fetch).mock.calls.some((call) => String(call[0]).includes('/tasks?importLinkId=501')),
      ).toBe(true),
    );
    // Het antwoord van de takenvraag verwerken voor we besluiten dat de actie ontbreekt.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(screen.queryByTestId('continue-link-501')).not.toBeInTheDocument();
  });

  it('NT-6.6: zonder Beheren is "Verder inrichten" uitgeschakeld mét reden', async () => {
    renderPage(testIdentityWith('catalogImport.read'));
    await expandDefinition('DEF-11');
    const action = await screen.findByTestId('continue-definition-11');
    expect(action.tagName).toBe('BUTTON');
    expect(action).toBeDisabled();
    expect(action).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
  });

  it('NT-6.7: ?definitionId=&revisionId= opent het revisiedetail bovenaan', async () => {
    renderPage(TEST_IDENTITY, '/setup?definitionId=10&revisionId=98');
    const opened = await screen.findByTestId('opened-revision');
    expect(await within(opened).findByTestId('revision-detail')).toBeInTheDocument();
    expect(await within(opened).findByTestId('activate-revision')).toHaveTextContent('Versie activeren');
  });
});
