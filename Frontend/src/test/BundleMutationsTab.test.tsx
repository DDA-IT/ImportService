/**
 * F8 — `BundleMutationsTab` bovenop `MutationList`, met de **echte** API-laag en een `fetch`-stub.
 *
 * Waar `MutationList.test.tsx` bewijst dat het component zonder `api/` werkt, bewijst dit bestand de
 * andere helft: dat de filters als de juiste **query-parameters** bij
 * `GET /bundles/{id}/mutations` aankomen (inclusief `statusReason` en `identityHash` uit bouwstap
 * C1/C4), dat lege filters niet meereizen, en dat `idempotent: true` gemeld wordt in plaats van
 * stilgehouden (§10.3).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { BundleDetail, MutationRow } from '../api/types';
import { BundleMutationsTab } from '../features/bundles/BundleMutationsTab';

const HASH = 'ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00ff00';

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
    rejectedCount: 0,
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
    expirableCount: 1,
    ...overrides,
  };
}

function mutation(overrides: Partial<MutationRow> = {}): MutationRow {
  return {
    id: 501,
    batchId: 77,
    actionType: 'UPDATE',
    targetDomain: 'OFFER',
    status: 'AWAITING_APPROVAL',
    statusReason: 'BULK_PRICE_INCIDENT',
    identitySupplier: 'SUP1',
    identitySupplierGroup: 'GRP1',
    identitySupplierReference: 'REF-1',
    identityDiscountCode: null,
    identityDiscountState: null,
    domainMask: 'PRICE',
    beforeBasePrice: 10,
    afterBasePrice: 11,
    basePriceCurrency: 'EUR',
    referenceType: null,
    beforeReferenceValue: null,
    afterReferenceValue: null,
    sourceStateId: 5,
    sourceRowNumber: 42,
    resultSummary: null,
    idempotencyKey: 'key-501',
    createdAt: '2026-09-20T10:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decidedFromStatus: null,
    decisionId: null,
    identityHash: HASH,
    ...overrides,
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function mutationsPage(content: MutationRow[]) {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: 1 };
}

function renderTab(detail: BundleDetail) {
  return render(
    <ActorProvider>
      <MemoryRouter initialEntries={['/bundles/42/mutations']}>
        <Routes>
          <Route
            path="/bundles/:bundleId"
            element={<Outlet context={{ bundle: detail, reloadBundle: () => {} }} />}
          >
            <Route path="mutations" element={<BundleMutationsTab />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

function urls(): string[] {
  return vi.mocked(global.fetch).mock.calls.map((call) => call[0]?.toString() ?? '');
}

describe('BundleMutationsTab', () => {
  const originalFetch = global.fetch;
  let rows: MutationRow[] = [mutation()];
  let decisionResponse: unknown = null;

  beforeEach(() => {
    rows = [mutation()];
    decisionResponse = null;
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if ((init?.method ?? 'GET') === 'POST') {
        return Promise.resolve(jsonResponse(decisionResponse));
      }
      if (url.includes('/mutations')) {
        return Promise.resolve(jsonResponse(mutationsPage(rows)));
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

  it('F8.1: laadt de mutatielijst van de bundel zonder lege filterparameters', async () => {
    renderTab(bundle());
    await screen.findByText('AWAITING_APPROVAL');

    const url = urls().find((candidate) => candidate.includes('/bundles/42/mutations'));
    expect(url).toBeDefined();
    expect(url).toContain('page=0');
    expect(url).toContain('size=50');
    // Lege filters reizen niet mee.
    expect(url).not.toContain('status=');
    expect(url).not.toContain('statusReason=');
    expect(url).not.toContain('identityHash=');
    expect(url).not.toContain('batchId=');
  });

  it('F8.2: geeft status en actionType door als query-parameters', async () => {
    renderTab(bundle());
    await screen.findByText('AWAITING_APPROVAL');

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'PLANNED' } });
    await waitFor(() => expect(urls().some((url) => url.includes('status=PLANNED'))).toBe(true));

    fireEvent.change(screen.getByLabelText('Soort'), { target: { value: 'CREATE' } });
    await waitFor(() => expect(urls().some((url) => url.includes('actionType=CREATE'))).toBe(true));
  });

  it('F8.3: geeft statusReason en batchId door als query-parameters (bouwstap C1)', async () => {
    renderTab(bundle());
    await screen.findByText('AWAITING_APPROVAL');

    fireEvent.change(screen.getByLabelText('Statusreden'), { target: { value: 'BULK_PRICE_INCIDENT' } });
    fireEvent.change(screen.getByLabelText('Batch'), { target: { value: '77' } });
    fireEvent.click(screen.getByRole('button', { name: 'Filteren' }));

    await waitFor(() => {
      const url = urls().find((candidate) => candidate.includes('statusReason='));
      expect(url).toBeDefined();
      expect(url).toContain('statusReason=BULK_PRICE_INCIDENT');
      expect(url).toContain('batchId=77');
    });
  });

  it('F8.4: klikken op de wijzigingsgroep stuurt identityHash mee (bouwstap C4)', async () => {
    renderTab(bundle());
    fireEvent.click(await screen.findByRole('button', { name: `Toon de hele wijzigingsgroep ${HASH}` }));

    await waitFor(() => expect(urls().some((url) => url.includes(`identityHash=${HASH}`))).toBe(true));
  });

  it('F8.5: een IMPORT_MARKER toont "—" in plaats van een wijzigingsgroep en is niet beslisbaar', async () => {
    rows = [mutation({ id: 900, actionType: 'IMPORT_MARKER', status: 'RECORDED', identityHash: null })];
    renderTab(bundle());
    await screen.findByText('RECORDED');

    expect(screen.queryByRole('button', { name: /Toon de hele wijzigingsgroep/ })).not.toBeInTheDocument();
    const approve = screen.getByRole('button', { name: /Goedkeuren mutatie 900/ });
    expect(approve).toBeDisabled();
    expect(approve.getAttribute('title')).toContain('geen inhoudelijke mutatie');
  });

  it('F8.6: een BLOCKED-mutatie is niet beslisbaar, met de code in de reden', async () => {
    rows = [mutation({ id: 901, status: 'BLOCKED', statusReason: 'IDENTITY_INCIDENT' })];
    renderTab(bundle());
    await screen.findByText('BLOCKED');

    const approve = screen.getByRole('button', { name: /Goedkeuren mutatie 901/ });
    expect(approve).toBeDisabled();
    expect(approve.getAttribute('title')).toContain('MUTATION_BLOCKED_BY_IDENTITY_INCIDENT');
  });

  it('F8.7: een bevroren bundel biedt geen beslissing aan, met de reden erbij', async () => {
    renderTab(bundle({ status: 'FROZEN', plannedCount: null, awaitingApprovalCount: null }));
    await screen.findByText('AWAITING_APPROVAL');

    const approve = screen.getByRole('button', { name: /Goedkeuren mutatie 501/ });
    expect(approve).toBeDisabled();
    expect(approve.getAttribute('title')).toContain('BUNDLE_NOT_ASSEMBLING');
  });

  it('F8.8: goedkeuren stuurt decidedBy en reden mee en meldt een idempotente herhaling', async () => {
    decisionResponse = { mutation: mutation(), decision: null, idempotent: true };
    renderTab(bundle());

    fireEvent.click(await screen.findByRole('button', { name: /Goedkeuren mutatie 501/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    // Goedkeuren zonder herziening: reden optioneel, bevestigen mag meteen.
    fireEvent.click(within(dialog).getByRole('button', { name: 'Goedkeuren' }));

    await waitFor(() => expect(screen.getByRole('status').textContent).toContain('geen tweede regel geschreven'));

    const post = vi.mocked(global.fetch).mock.calls.find((call) => (call[1]?.method ?? 'GET') === 'POST');
    expect(post).toBeDefined();
    expect(post?.[0]?.toString()).toContain('/bundles/42/mutations/501/approve');
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ decidedBy: 'An Beslisser', reason: null });
  });

  it('F8.9: een herziening eist een reden, ook bij goedkeuren, en benoemt de eerdere beslissing', async () => {
    rows = [
      mutation({
        id: 502,
        status: 'REJECTED',
        decidedBy: 'Eerdere Beslisser',
        decidedAt: '2026-09-21T08:00:00Z',
        decidedFromStatus: 'AWAITING_APPROVAL',
        decisionId: 7,
      }),
    ];
    renderTab(bundle());

    fireEvent.click(await screen.findByRole('button', { name: /Goedkeuren mutatie 502/ }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/herzien naar goedgekeurd/)).toBeInTheDocument();
    expect(within(dialog).getByText(/Eerdere Beslisser/)).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    // Reden verplicht bij een herziening (§10.3), ook al gaat het om goedkeuren.
    expect(within(dialog).getByRole('button', { name: 'Goedkeuren' })).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'na overleg toch goedgekeurd' } });
    expect(within(dialog).getByRole('button', { name: 'Goedkeuren' })).toBeEnabled();
  });

  it('F8.10: afkeuren eist altijd een reden en stuurt die mee', async () => {
    decisionResponse = { mutation: mutation({ status: 'REJECTED' }), decision: null, idempotent: false };
    renderTab(bundle());

    fireEvent.click(await screen.findByRole('button', { name: /Afkeuren mutatie 501/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    expect(within(dialog).getByRole('button', { name: 'Afkeuren' })).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'prijs klopt niet' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Afkeuren' }));

    await waitFor(() => {
      const post = vi.mocked(global.fetch).mock.calls.find((call) => (call[1]?.method ?? 'GET') === 'POST');
      expect(post?.[0]?.toString()).toContain('/bundles/42/mutations/501/reject');
      expect(JSON.parse(String(post?.[1]?.body))).toEqual({
        decidedBy: 'An Beslisser',
        reason: 'prijs klopt niet',
      });
    });
  });

  it('F8.11: een geweigerde beslissing (409) blijft leesbaar mét stabiele code', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if ((init?.method ?? 'GET') === 'POST') {
        return Promise.resolve(jsonResponse({ error: 'mutation not decidable', code: 'MUTATION_NOT_DECIDABLE' }, 409));
      }
      if (url.includes('/mutations')) {
        return Promise.resolve(jsonResponse(mutationsPage(rows)));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    renderTab(bundle());
    fireEvent.click(await screen.findByRole('button', { name: /Goedkeuren mutatie 501/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Goedkeuren' }));

    await waitFor(() => expect(within(dialog).getByText(/MUTATION_NOT_DECIDABLE/)).toBeInTheDocument());
    // Niets gemeld als succes: de dialoog blijft open met de fout.
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
