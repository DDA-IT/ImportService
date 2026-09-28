/**
 * S1-F1 — `SetupOverviewPage` (scherm 1a, `docs/decisions.md` 2026-09-27 "scherm 1a/1b").
 *
 * Dekt: bronorganisatielijst laadt, uitklappen laadt lazy de definitielijst, uitklappen van een
 * definitie laadt lazy revisies en koppelingen, `activeRevisionId` null vs. gevuld wordt correct
 * getoond, en het koppelingsniveau toont de vaste taken-tekst in plaats van een taken-lijst.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { TEST_IDENTITY } from './testIdentity';
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

function renderPage() {
  return render(
    <ActorProvider identity={TEST_IDENTITY}>
      <MemoryRouter initialEntries={['/setup']}>
        <SetupOverviewPage />
      </MemoryRouter>
    </ActorProvider>,
  );
}

describe('SetupOverviewPage', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/definitions/10/revisions')) {
        return Promise.resolve(jsonResponse(REVISIONS_RESPONSE));
      }
      if (url.includes('/import-links')) {
        return Promise.resolve(jsonResponse(IMPORT_LINKS_RESPONSE));
      }
      if (url.includes('/definitions')) {
        return Promise.resolve(jsonResponse(DEFINITIONS_RESPONSE));
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
    expect(screen.getByText('actieve revisie: #99')).toBeInTheDocument();
    expect(screen.getByText('geen actieve revisie')).toBeInTheDocument();
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

    expect(await screen.findByText('Revisie #1')).toBeInTheDocument();
    expect(screen.getByText('Revisie #2')).toBeInTheDocument();
    expect(await screen.findByText('LNK-1')).toBeInTheDocument();
  });

  it('S1-F1.5: taken-niveau toont de vaste tekst in plaats van een takenlijst', async () => {
    renderPage();
    await screen.findByText('ORG-1');
    fireEvent.click((await screen.findAllByRole('button', { name: /Uitklappen/ }))[0]!);
    await screen.findByText('DEF-10');

    const definitionButtons = await screen.findAllByRole('button', { name: /Uitklappen/ });
    fireEvent.click(definitionButtons[0]!);

    await screen.findByText('LNK-1');
    await waitFor(() => {
      expect(
        screen.getByText('Taken zijn alleen zichtbaar met de setup-API-vlag aan (ontwikkelscherm).'),
      ).toBeInTheDocument();
    });
  });
});
