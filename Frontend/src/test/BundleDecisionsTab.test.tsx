/**
 * F11 — `BundleDecisionsTab` alleen-lezen beslissingsregister.
 *
 * Key tests:
 * - Een herziening staat als aparte regel naast het origineel
 * - Lege lijst
 * - Fout wordt getoond
 * - Null-velden verschijnen als "—"
 * - Paginering werkt
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { BundleDetail, DecisionRow } from '../api/types';
import { BundleDecisionsTab } from '../features/bundles/BundleDecisionsTab';

function bundle(overrides: Partial<BundleDetail> = {}): BundleDetail {
  return {
    id: 42,
    bundleReference: 'BND-2026-001',
    description: null,
    status: 'ASSEMBLING',
    targetMode: 'SIMULATION',
    targetMoment: null,
    publicationPolicy: null,
    createdBy: 'An Beslisser',
    createdAt: '2026-09-20T09:00:00Z',
    frozenBy: null,
    frozenAt: null,
    frozenReason: null,
    cancelledBy: null,
    cancelledAt: null,
    cancelledReason: null,
    batchCount: 1,
    contentMutationCount: 2,
    readyCount: 0,
    rejectedCount: 1,
    blockedCount: 1,
    expiredCount: null,
    identityIncidentCount: 0,
    bulkIncidentCount: null,
    criticalIssueCount: null,
    warningCount: null,
    staleMutationCount: 0,
    contentHash: null,
    plannedCount: 1,
    awaitingApprovalCount: 1,
    ...overrides,
  };
}

function decision(overrides: Partial<DecisionRow> = {}): DecisionRow {
  return {
    id: 1,
    bundleId: 42,
    mutationId: 501,
    decisionKind: 'APPROVE',
    decisionScope: 'MUTATION',
    selectionFilter: null,
    previousStatus: 'AWAITING_APPROVAL',
    newStatus: 'READY_FOR_PUBLICATION',
    affectedCount: 1,
    decidedBy: 'An Beslisser',
    decidedAt: '2026-09-20T10:00:00Z',
    reason: 'Prijs gecontroleerd',
    ...overrides,
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function decisionsPage(content: DecisionRow[], totalElements?: number) {
  const total = totalElements ?? content.length;
  return { content, page: 0, size: 50, totalElements: total, totalPages: Math.ceil(total / 50) };
}

function renderTab(detail: BundleDetail) {
  return render(
    <ActorProvider>
      <MemoryRouter initialEntries={['/bundles/42/decisions']}>
        <Routes>
          <Route
            path="/bundles/:bundleId"
            element={<Outlet context={{ bundle: detail, reloadBundle: () => {} }} />}
          >
            <Route path="decisions" element={<BundleDecisionsTab />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

describe('BundleDecisionsTab', () => {
  const originalFetch = global.fetch;
  let rows: DecisionRow[] = [decision()];

  beforeEach(() => {
    rows = [decision()];
    global.fetch = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/decisions')) {
        return Promise.resolve(jsonResponse(decisionsPage(rows)));
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

  it('F11.1: laadt de beslissingslijst van de bundel', async () => {
    renderTab(bundle());
    await screen.findByText('An Beslisser');
    expect(screen.getByText('Prijs gecontroleerd')).toBeInTheDocument();
  });

  it('F11.2: toont een herziening als aparte regel naast de oorspronkelijke beslissing', async () => {
    rows = [
      decision({ id: 1, decisionKind: 'APPROVE', previousStatus: 'AWAITING_APPROVAL', newStatus: 'READY_FOR_PUBLICATION' }),
      decision({ id: 2, mutationId: 501, decisionKind: 'REJECT', previousStatus: 'READY_FOR_PUBLICATION', newStatus: 'REJECTED', decidedBy: 'Andere Beslisser', decidedAt: '2026-09-20T11:00:00Z' }),
    ];
    renderTab(bundle());

    await screen.findByText('An Beslisser');
    expect(screen.getByText('Andere Beslisser')).toBeInTheDocument();
    // Beide regels moeten zichtbaar zijn
    const rows_text = screen.queryAllByText(/Mutatie 501/);
    expect(rows_text.length).toBeGreaterThanOrEqual(2);
  });

  it('F11.3: toont "—" voor null-waarden', async () => {
    rows = [decision({ mutationId: null, selectionFilter: null, reason: null })];
    renderTab(bundle());

    await screen.findByText('An Beslisser');
    // Null-mutationId toont als "Bundel" (geen specifieke mutatie)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });

  it('F11.4: toont een lege lijst', async () => {
    rows = [];
    renderTab(bundle());

    await waitFor(() => expect(screen.getByText('Deze bundel bevat geen beslissingen.')).toBeInTheDocument());
  });

  it('F11.5: toont een fout bij het laden', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve(jsonResponse({ error: 'Bundle not found', code: 'BUNDLE_NOT_FOUND' }, 404))
    ) as unknown as typeof fetch;

    renderTab(bundle());
    await screen.findByText(/BUNDLE_NOT_FOUND/);
    expect(screen.getByText(/BUNDLE_NOT_FOUND/)).toBeInTheDocument();
  });

  it('F11.6: toont decisionKind en decisionScope correct', async () => {
    rows = [
      decision({ id: 1, decisionKind: 'APPROVE', decisionScope: 'MUTATION', mutationId: 501 }),
      decision({ id: 2, decisionKind: 'FREEZE', decisionScope: 'BUNDLE', mutationId: null }),
      decision({ id: 3, decisionKind: 'AUTO_APPROVE_PLANNED', decisionScope: 'GROUP', mutationId: null }),
    ];
    renderTab(bundle());

    await screen.findByText('APPROVE');
    expect(screen.getByText('FREEZE')).toBeInTheDocument();
    expect(screen.getByText('AUTO_APPROVE_PLANNED')).toBeInTheDocument();
    // Mutatie 501 moet eenmaal zichtbaar zijn (in de eerste rij)
    const mutationRefs = screen.queryAllByText(/Mutatie 501/);
    expect(mutationRefs.length).toBeGreaterThanOrEqual(1);
  });

  it('F11.7: formatteer de datum/tijd correct', async () => {
    rows = [decision({ decidedAt: '2026-09-20T10:30:45Z' })];
    renderTab(bundle());

    // Verwacht format van nl-BE locale (exact afhankelijk van systeem, dus we controleren op kernelementen)
    await screen.findByRole('table');
    const timeCell = screen.getByText(/20\/9\/2026|20-9-2026/);
    expect(timeCell).toBeInTheDocument();
  });

  it('F11.8: paginering werkt', async () => {
    const largeList = Array.from({ length: 60 }, (_, i) =>
      decision({
        id: i + 1,
        decidedAt: `2026-09-20T${String(i + 10).padStart(2, '0')}:00:00Z`,
        decidedBy: `Beslisser-${i + 1}`
      })
    );
    rows = largeList.slice(0, 50); // eerste pagina (50 van 60)

    global.fetch = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/decisions')) {
        // Mock geeft 50 items terug maar zegt dat het totaal 60 is (volgende pagina bestaat)
        return Promise.resolve(jsonResponse(decisionsPage(rows, 60)));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    renderTab(bundle());
    await screen.findByText('Beslisser-1');
    // Pager moet zichtbaar zijn: summary "1-50 van 60" en "Volgende" knop
    expect(screen.getByText(/1-50 van 60/)).toBeInTheDocument();
    const nextButton = screen.getByRole('button', { name: 'Volgende' });
    expect(nextButton).toBeInTheDocument();
    expect(nextButton).not.toBeDisabled();
  });
});
