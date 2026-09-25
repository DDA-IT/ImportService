/**
 * S0-F1 — `WorkQueuePage` (Scherm 0, D14, werkvoorraad), zie `docs/decisions.md` 2026-09-23
 * "Frontend: D14 opgepakt, eerste verticale slice Scherm 0 (werkvoorraad)".
 *
 * Dekt: telblokken tonen correct inclusief de "niet vastgesteld"-tegel, filters worden doorgegeven
 * aan de juiste querystringparameters, paginering werkt.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { WorkQueuePage } from '../features/workqueue/WorkQueuePage';

function renderPage() {
  return render(
    <MemoryRouter>
      <WorkQueuePage />
    </MemoryRouter>,
  );
}

const SUMMARY_RESPONSE = {
  total: 7,
  byStatus: [
    { status: 'RECEIVED', count: 2 },
    { status: 'SCREENED', count: 5 },
  ],
  byValidationResult: [
    { validationResult: 'VALID', count: 4 },
    { validationResult: 'BLOCKING', count: 1 },
    { validationResult: null, count: 2 },
  ],
};

const IMPORT_LINKS_RESPONSE = {
  content: [
    { id: 1, code: 'LNK-1', name: 'Koppeling Een', supplierCode: 'SUP1', supplierName: 'Leverancier Een', libraryCode: 'LIB1', active: true },
    { id: 2, code: 'LNK-2', name: 'Koppeling Twee', supplierCode: 'SUP2', supplierName: 'Leverancier Twee', libraryCode: 'LIB2', active: true },
  ],
  page: 0,
  size: 200,
  totalElements: 2,
  totalPages: 1,
};

function batchesResponse(overrides: Partial<typeof BASE_BATCHES_RESPONSE> = {}) {
  return { ...BASE_BATCHES_RESPONSE, ...overrides };
}

const BASE_BATCHES_RESPONSE = {
  content: [
    {
      batchId: 101,
      deliveryId: 1,
      importLinkId: 1,
      importLinkCode: 'LNK-1',
      supplierCode: 'SUP1',
      libraryCode: 'LIB1',
      attemptNo: 1,
      status: 'SCREENED',
      validationResult: 'VALID',
      createdAt: '2026-09-20T10:00:00Z',
      startedAt: '2026-09-20T10:00:05Z',
      finishedAt: '2026-09-20T10:01:00Z',
      rawRecordCount: 100,
      validRecordCount: 95,
      rejectedRecordCount: 5,
      contentMutationCount: 10,
      awaitingApprovalCount: 3,
      criticalLineCount: 0,
      criticalIssueCount: 0,
      warningCount: 1,
      bulkIncidentCount: 0,
      identityIncidentCount: 0,
      blockedCode: null,
      baselineAcceptedBy: null,
      baselineAcceptedAt: null,
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
};

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

describe('WorkQueuePage', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/batches/summary')) {
        return Promise.resolve(jsonResponse(SUMMARY_RESPONSE));
      }
      if (url.includes('/import-links')) {
        return Promise.resolve(jsonResponse(IMPORT_LINKS_RESPONSE));
      }
      if (url.includes('/batches')) {
        return Promise.resolve(jsonResponse(batchesResponse()));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('S0-F1.1: toont de telblokken, inclusief een eigen tegel voor "niet vastgesteld"', async () => {
    renderPage();

    const tiles = await screen.findByTestId('summary-tiles');
    expect(within(tiles).getByText('Totaal')).toBeInTheDocument();
    expect(within(tiles).getByText('7')).toBeInTheDocument();
    expect(within(tiles).getByText('RECEIVED')).toBeInTheDocument();
    expect(within(tiles).getByText('SCREENED')).toBeInTheDocument();
    expect(within(tiles).getByText('VALID')).toBeInTheDocument();
    expect(within(tiles).getByText('BLOCKING')).toBeInTheDocument();

    // De null-rij ("niet vastgesteld") krijgt een eigen tegel en toont het werkelijke aantal (2), nooit 0.
    const notEstablishedLabel = within(tiles).getByText('Niet vastgesteld');
    expect(notEstablishedLabel).toBeInTheDocument();
    const notEstablishedTile = notEstablishedLabel.closest('div');
    expect(notEstablishedTile).not.toBeNull();
    expect(within(notEstablishedTile as HTMLElement).getByText('2')).toBeInTheDocument();
  });

  it('A-F1: elke rij linkt door naar het batchdetail', async () => {
    renderPage();
    const link = await screen.findByRole('link', { name: '#101' });
    expect(link).toHaveAttribute('href', '/batches/101');
  });

  it('S0-F1.2: geeft de statusfilter door als querystringparameter aan GET /batches', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('LNK-1')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'BLOCKED' } });

    await waitFor(() => {
      const calls = vi.mocked(global.fetch).mock.calls.map((call) => call[0]?.toString() ?? '');
      expect(calls.some((url) => url.includes('/batches?') && url.includes('status=BLOCKED'))).toBe(true);
    });
  });

  it('S0-F1.3: geeft de koppelingsfilter door op basis van het gekozen import-link-id', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('LNK-1')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Koppeling'), { target: { value: '2' } });

    await waitFor(() => {
      const calls = vi.mocked(global.fetch).mock.calls.map((call) => call[0]?.toString() ?? '');
      expect(calls.some((url) => url.includes('/batches?') && url.includes('importLinkId=2'))).toBe(true);
    });
  });

  it('S0-F1.4: geeft het datumbereik door als createdFrom/createdTo', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('LNK-1')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Aangemaakt vanaf'), { target: { value: '2026-09-01' } });
    fireEvent.change(screen.getByLabelText('Aangemaakt tot en met'), { target: { value: '2026-09-20' } });

    await waitFor(() => {
      const calls = vi.mocked(global.fetch).mock.calls.map((call) => call[0]?.toString() ?? '');
      const call = calls.find(
        (url) => url.includes('/batches?') && url.includes('createdFrom=') && url.includes('createdTo='),
      );
      expect(call).toBeDefined();
      expect(call).toContain(encodeURIComponent('2026-09-01T00:00:00.000Z'));
      // Halfopen bovengrens: "tot en met 20/09" wordt de start van 21/09.
      expect(call).toContain(encodeURIComponent('2026-09-21T00:00:00.000Z'));
    });
  });

  it('S0-F1.5: paginering geeft de gekozen pagina/grootte door en toont de samenvatting', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/batches/summary')) {
        return Promise.resolve(jsonResponse(SUMMARY_RESPONSE));
      }
      if (url.includes('/import-links')) {
        return Promise.resolve(jsonResponse(IMPORT_LINKS_RESPONSE));
      }
      if (url.includes('/batches')) {
        return Promise.resolve(
          jsonResponse(batchesResponse({ page: 0, size: 50, totalElements: 120, totalPages: 3 })),
        );
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    renderPage();
    await waitFor(() => expect(screen.getByText('1-50 van 120')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Volgende' }));

    await waitFor(() => {
      const calls = vi.mocked(global.fetch).mock.calls.map((call) => call[0]?.toString() ?? '');
      expect(calls.some((url) => url.includes('/batches?') && url.includes('page=1'))).toBe(true);
    });
  });
});
