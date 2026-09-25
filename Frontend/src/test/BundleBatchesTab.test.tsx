/**
 * BundleBatchesTab — de leden- en kandidaatlijst in `BundleDetailPage` (scherm 3).
 * Dekt: import-linknamen worden correct weergegeven via `GET /import-links`,
 * en het systeem valt terug op `#id` als het label niet gevonden wordt.
 * Zie §16.5 van `docs/design/frontend-scherm3-bundel-design.md`.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, waitFor } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { BundleDetail } from '../api/types';
import { BundleBatchesTab } from '../features/bundles/BundleBatchesTab';

function bundle(overrides: Partial<BundleDetail> = {}): BundleDetail {
  return {
    id: 1,
    bundleReference: 'BUNDLE-001',
    description: null,
    status: 'ASSEMBLING',
    targetMode: 'SIMULATION',
    targetMoment: null,
    publicationPolicy: null,
    createdBy: 'testuser',
    createdAt: '2026-09-20T10:00:00Z',
    frozenBy: null,
    frozenAt: null,
    frozenReason: null,
    cancelledBy: null,
    cancelledAt: null,
    cancelledReason: null,
    batchCount: 2,
    contentMutationCount: 10,
    readyCount: 5,
    rejectedCount: 1,
    blockedCount: 0,
    expiredCount: 0,
    identityIncidentCount: 0,
    bulkIncidentCount: 0,
    criticalIssueCount: 1,
    warningCount: 2,
    staleMutationCount: 0,
    contentHash: null,
    plannedCount: 3,
    awaitingApprovalCount: 2,
    expirableCount: null,
    ...overrides,
  };
}

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

const MEMBERS_RESPONSE = {
  content: [
    {
      id: 1,
      bundleId: 1,
      batchId: 101,
      importLinkId: 1,
      addedBy: 'testuser',
      addedAt: '2026-09-20T10:00:00Z',
      removedBy: null,
      removedAt: null,
      removedReason: null,
      active: true,
      batchStatus: 'SCREENED',
      batchContentMutationCount: 5,
    },
    {
      id: 2,
      bundleId: 1,
      batchId: 102,
      importLinkId: 2,
      addedBy: 'testuser',
      addedAt: '2026-09-20T10:15:00Z',
      removedBy: null,
      removedAt: null,
      removedReason: null,
      active: true,
      batchStatus: 'SCREENED',
      batchContentMutationCount: 5,
    },
  ],
  page: 0,
  size: 50,
  totalElements: 2,
  totalPages: 1,
};

const CANDIDATES_RESPONSE = {
  content: [
    {
      batchId: 103,
      importLinkId: 3,
      status: 'RECEIVED',
      validationResult: null,
      contentMutationCount: null,
      finishedAt: null,
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

function renderTab(detail: BundleDetail) {
  return render(
    <ActorProvider>
      <MemoryRouter initialEntries={['/bundles/1/batches']}>
        <Routes>
          <Route
            path="/bundles/:bundleId"
            element={<Outlet context={{ bundle: detail, reloadBundle: () => {} }} />}
          >
            <Route path="batches" element={<BundleBatchesTab />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

describe('BundleBatchesTab', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    global.fetch = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/bundles/1/batches')) {
        return Promise.resolve(jsonResponse(MEMBERS_RESPONSE));
      }
      if (url.includes('/bundles/candidates')) {
        return Promise.resolve(jsonResponse(CANDIDATES_RESPONSE));
      }
      if (url.includes('/import-links')) {
        return Promise.resolve(jsonResponse(IMPORT_LINKS_RESPONSE));
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

  it('§16.5.1: toont de koppelingscode (LNK-1, LNK-2) in plaats van het ID voor bekende koppelingen', async () => {
    renderTab(bundle());

    // Wacht tot de leden zijn geladen
    const lnk1 = await screen.findByText('LNK-1');
    expect(lnk1).toBeInTheDocument();

    const lnk2 = screen.getByText('LNK-2');
    expect(lnk2).toBeInTheDocument();
  });

  it('§16.5.2: valt terug op #id als de koppeling niet gevonden wordt (fallback voor onbekende koppelingen)', async () => {
    renderTab(bundle());

    // De eerste twee batches hebben bekende koppelingen (1 en 2)
    await screen.findByText('LNK-1');
    expect(screen.getByText('LNK-2')).toBeInTheDocument();

    // Wacht tot de kandidaten zijn geladen om te zien of het fallback label verschijnt
    await waitFor(() => {
      const fallbackLabel = screen.queryByText('#3');
      expect(fallbackLabel).toBeInTheDocument();
    });
  });
});
