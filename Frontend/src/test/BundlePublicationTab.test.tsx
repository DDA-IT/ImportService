/**
 * `BundlePublicationTab` — de SIMULATION-publicatierun van 5-PUB-a, met de **echte** API-laag en een
 * `fetch`-stub, naar het patroon van `BundleMutationsTab.test.tsx`. Zie `docs/decisions.md` 2026-09-27
 * "Ontwerp bindend: Frontend publicatierun (SIMULATION) op scherm (3)".
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { TEST_IDENTITY } from './testIdentity';
import type { BundleDetail, PublicationRunView } from '../api/types';
import { BundlePublicationTab } from '../features/bundles/BundlePublicationTab';

function bundle(overrides: Partial<BundleDetail> = {}): BundleDetail {
  return {
    id: 42,
    bundleReference: 'BND-2026-001',
    description: null,
    status: 'FROZEN',
    targetMode: 'SIMULATION',
    targetMoment: null,
    publicationPolicy: null,
    createdBy: 'An Beslisser',
    createdAt: '2026-09-20T09:00:00Z',
    frozenBy: 'An Beslisser',
    frozenAt: '2026-09-21T09:00:00Z',
    frozenReason: 'klaar',
    cancelledBy: null,
    cancelledAt: null,
    cancelledReason: null,
    batchCount: 1,
    contentMutationCount: 2,
    readyCount: 2,
    rejectedCount: 0,
    blockedCount: 0,
    expiredCount: 0,
    identityIncidentCount: 0,
    bulkIncidentCount: 0,
    criticalIssueCount: 0,
    warningCount: 0,
    staleMutationCount: null,
    contentHash: 'ab'.repeat(32),
    plannedCount: null,
    awaitingApprovalCount: null,
    expirableCount: 0,
    ...overrides,
  };
}

function run(overrides: Partial<PublicationRunView> = {}): PublicationRunView {
  return {
    id: 1,
    bundleId: 42,
    targetMode: 'SIMULATION',
    attempt: 1,
    status: 'SIMULATED',
    requestedBy: 'An Beslisser',
    requestedAt: '2026-09-27T10:00:00Z',
    startedAt: '2026-09-27T10:00:01Z',
    finishedAt: '2026-09-27T10:00:02Z',
    bundleContentHash: 'ab'.repeat(32),
    snapshotHash: 'cd'.repeat(32),
    payloadHash: 'ef'.repeat(32),
    artifactSha256: '12'.repeat(32),
    artifactByteSize: 321,
    rowCount: 5,
    incompleteRowCount: 0,
    failureCode: null,
    failureMessage: null,
    simulationOnly: true,
    writesToProdis: false,
    contractStatus: 'UNVERIFIED_FIELD_INVENTORY',
    previewSpecVersion: 'v1',
    snapshotSpecVersion: 'v1',
    ...overrides,
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function renderTab(detail: BundleDetail) {
  return render(
    <ActorProvider identity={TEST_IDENTITY}>
      <MemoryRouter initialEntries={['/bundles/42/publication']}>
        <Routes>
          <Route
            path="/bundles/:bundleId"
            element={<Outlet context={{ bundle: detail, reloadBundle: () => {} }} />}
          >
            <Route path="publication" element={<BundlePublicationTab />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

describe('BundlePublicationTab', () => {
  const originalFetch = global.fetch;
  let runs: PublicationRunView[] = [];
  let requestResponse: { body: unknown; status: number } = { body: run(), status: 200 };

  beforeEach(() => {
    runs = [];
    requestResponse = { body: run(), status: 200 };
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if ((init?.method ?? 'GET') === 'POST' && url.includes('/publication-runs')) {
        return Promise.resolve(jsonResponse(requestResponse.body, requestResponse.status));
      }
      if (url.includes('/publication-runs')) {
        return Promise.resolve(jsonResponse(runs));
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

  it('buiten FROZEN: geen actieknop, wel uitleg — geen crash, geen lege pagina', async () => {
    renderTab(bundle({ status: 'ASSEMBLING' }));

    expect(await screen.findByText(/alleen gestart worden voor een bevroren bundel/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Simulatierun starten' })).not.toBeInTheDocument();
  });

  it('FROZEN zonder actieve run: de knop is beschikbaar en start een run', async () => {
    renderTab(bundle());
    const button = await screen.findByRole('button', { name: 'Simulatierun starten' });
    expect(button).toBeEnabled();

    fireEvent.click(button);

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('geslaagd'));
    const post = vi.mocked(global.fetch).mock.calls.find((call) => (call[1]?.method ?? 'GET') === 'POST');
    expect(post).toBeDefined();
    expect(post?.[0]?.toString()).toContain('/bundles/42/publication-runs');
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ targetMode: 'SIMULATION' });
  });

  it('een al-actieve run (PREPARING) blokkeert de knop, met reden en run-id', async () => {
    runs = [run({ id: 9, status: 'PREPARING' })];
    renderTab(bundle());

    const button = await screen.findByRole('button', { name: 'Simulatierun starten' });
    expect(button).toBeDisabled();
    expect(button.getAttribute('title')).toContain('PUBLICATION_RUN_IN_PROGRESS');
    expect(button.getAttribute('title')).toContain('#9');
  });

  it('een SIMULATED-run toont de downloadlinks en de artefacthash', async () => {
    runs = [run({ id: 5, status: 'SIMULATED', artifactSha256: 'ff'.repeat(32), artifactByteSize: 999 })];
    renderTab(bundle());

    await screen.findByText(/SHA-256/);
    expect(screen.getByText(/999 byte/)).toBeInTheDocument();

    const artifactLink = screen.getByRole('link', { name: 'Download het runartefact (CSV)' });
    expect(artifactLink.getAttribute('href')).toContain('/publication-runs/5/artifact');

    const previewLink = screen.getByRole('link', { name: 'Bekijk de volledige PSIMPORT-preview (CSV)' });
    expect(previewLink.getAttribute('href')).toContain('/bundles/42/psimport-preview?format=csv');
  });

  it('een FAILED-run toont failureCode/failureMessage als platte tekst', async () => {
    runs = [
      run({ id: 6, status: 'FAILED', artifactSha256: null, failureCode: 'ARTIFACT_WRITE_FAILED', failureMessage: 'disk full' }),
    ];
    renderTab(bundle());

    expect(await screen.findByText(/ARTIFACT_WRITE_FAILED/)).toBeInTheDocument();
    expect(screen.getByText('disk full')).toBeInTheDocument();
  });

  it('incompleteRowCount > 0 toont de onvolledigheidsmelding', async () => {
    runs = [run({ id: 8, status: 'SIMULATED', incompleteRowCount: 3 })];
    renderTab(bundle());

    expect(await screen.findByText(/3 regel\(en\) zijn onvolledig/)).toBeInTheDocument();
  });
});
