/**
 * S2-F1 — `IssueCaseListPage` (`/issue-cases`, `docs/design/issue-case-design.md` §6).
 *
 * Dekt: rijen renderen, een filter (status) geeft de juiste querystringparameter door, en de
 * recurrence-indicator toont enkel wanneer `hasUnreviewedRecurrence` waar is.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { IssueCaseListPage } from '../features/issuecases/IssueCaseListPage';

function renderPage() {
  return render(
    <MemoryRouter>
      <IssueCaseListPage />
    </MemoryRouter>,
  );
}

const IMPORT_LINKS_RESPONSE = {
  content: [
    { id: 1, code: 'LNK-1', name: 'Koppeling Een', supplierCode: 'SUP1', supplierName: 'Leverancier Een', libraryCode: 'LIB1', active: true, importDefinitionId: 1 },
  ],
  page: 0,
  size: 200,
  totalElements: 1,
  totalPages: 1,
};

function baseCaseRow(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: 501,
    importLinkId: 1,
    issueCode: 'PRICE_MISSING',
    signature: 'sig-1',
    severity: 'ERROR',
    issueDomain: 'PRICE',
    controlLevel: 'RECORD',
    impactScope: 'RECORD',
    incidentKind: 'PRICE',
    priceComponentCode: null,
    referenceType: null,
    status: 'AWAITING_REVIEW',
    observationCount: 3,
    totalOccurrenceCount: 120,
    firstSeenAt: '2026-09-01T10:00:00Z',
    lastSeenAt: '2026-09-20T10:00:00Z',
    firstSeenBatchId: 10,
    lastSeenBatchId: 15,
    lastSeenRevisionId: 2,
    reopenCount: 0,
    statusReason: null,
    statusChangedAt: null,
    statusChangedBy: null,
    statusChangedBySubject: null,
    decisionRevisionId: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-20T10:00:00Z',
    hasUnreviewedRecurrence: true,
    ...overrides,
  };
}

function casesResponse(content: ReturnType<typeof baseCaseRow>[]) {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: 1 };
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

describe('IssueCaseListPage', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/import-links')) {
        return Promise.resolve(jsonResponse(IMPORT_LINKS_RESPONSE));
      }
      if (url.includes('/issue-cases')) {
        const reviewed = baseCaseRow({
          id: 502,
          statusChangedAt: '2026-09-21T10:00:00Z',
          statusChangedBy: 'Jan',
          lastSeenAt: '2026-09-10T10:00:00Z',
          hasUnreviewedRecurrence: false,
        });
        return Promise.resolve(jsonResponse(casesResponse([baseCaseRow(), reviewed])));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('S2-F1.1: rendert de rijen van de lijst', async () => {
    renderPage();
    const link501 = await screen.findByRole('link', { name: '#501' });
    expect(link501).toHaveAttribute('href', '/issue-cases/501');
    expect(screen.getByRole('link', { name: '#502' })).toBeInTheDocument();
    // NT-11c: het Nederlandse woord staat in de tabel, de technische code enkel in de tooltip.
    const word = screen.getAllByText('Prijs ontbreekt', { selector: 'span' });
    expect(word.length).toBeGreaterThan(0);
    expect(word[0]).toHaveAttribute('title', expect.stringContaining('PRICE_MISSING'));
  });

  it('S2-F1.2: toont de recurrence-indicator alleen wanneer hasUnreviewedRecurrence waar is', async () => {
    renderPage();
    await screen.findByRole('link', { name: '#501' });

    expect(screen.getByTestId('recurrence-501')).toBeInTheDocument();
    expect(screen.queryByTestId('recurrence-502')).toBeNull();
  });

  it('S2-F1.3: geeft de statusfilter door als querystringparameter aan GET /issue-cases', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('LNK-1 — Koppeling Een')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'REJECTED' } });

    await waitFor(() => {
      const calls = vi.mocked(global.fetch).mock.calls.map((call) => call[0]?.toString() ?? '');
      expect(calls.some((url) => url.includes('/issue-cases?') && url.includes('status=REJECTED'))).toBe(true);
    });
  });

  it('S2-F1.4: geeft de filter op soort vaststelling (een keuzelijst met Nederlandse woorden) door', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('LNK-1 — Koppeling Een')).toBeInTheDocument());

    // De keuzelijst toont het woord; de waarde die naar de server gaat is de technische code.
    const select = screen.getByLabelText('Soort vaststelling') as HTMLSelectElement;
    expect(within(select).getByRole('option', { name: 'Prijs ontbreekt' })).toHaveValue('PRICE_MISSING');
    fireEvent.change(select, { target: { value: 'PRICE_MISSING' } });

    await waitFor(() => {
      const calls = vi.mocked(global.fetch).mock.calls.map((call) => call[0]?.toString() ?? '');
      expect(
        calls.some((url) => url.includes('/issue-cases?') && url.includes('issueCode=PRICE_MISSING')),
      ).toBe(true);
    });
  });
});
