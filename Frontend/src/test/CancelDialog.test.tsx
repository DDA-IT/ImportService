/**
 * F10 — annuleren (`CancelDialog`, geopend vanuit `BundleOverviewTab`), zie
 * `docs/design/frontend-scherm3-bundel-design.md` §10.6.
 *
 * Wat hier aantoonbaar gemaakt wordt:
 * - de dialoog toont het aantal mutaties dat vervalt uit `BundleDetail.expirableCount` (backendtelling, C7),
 *   zonder lijstaanroepen;
 * - zonder getal (null) kan niet geannuleerd worden; 0 is een geldig getal;
 * - de typ-bevestiging werkt; sluiten verstuurt niets;
 * - een fout geeft geen succesmelding; het happy path herlaadt de bundel.
 *
 * Draait tegen de echte API-laag (`api/bundles.ts` + `api/http.ts`) met een `fetch`-stub.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { BundleDetail } from '../api/types';
import { BundleOverviewTab } from '../features/bundles/BundleOverviewTab';

const REFERENCE = 'BND-2026-001';

function bundle(overrides: Partial<BundleDetail> = {}): BundleDetail {
  return {
    id: 42,
    bundleReference: REFERENCE,
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
    batchCount: 2,
    contentMutationCount: 12,
    readyCount: 4,
    rejectedCount: 1,
    blockedCount: 0,
    expiredCount: null,
    identityIncidentCount: 1,
    bulkIncidentCount: null,
    criticalIssueCount: null,
    warningCount: null,
    staleMutationCount: 0,
    contentHash: null,
    plannedCount: 3,
    awaitingApprovalCount: 3,
    expirableCount: 10,
    ...overrides,
  };
}

function frozenBundle(): BundleDetail {
  return bundle({
    status: 'FROZEN',
    frozenBy: 'An Beslisser',
    frozenAt: '2026-09-23T10:00:00Z',
    frozenReason: 'Klaar',
    staleMutationCount: null,
    contentHash: 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789',
    plannedCount: null,
    awaitingApprovalCount: null,
    expirableCount: 12,
  });
}

/** Het antwoord van `POST /cancel`: de geannuleerde `BundleDetail`. */
function cancelledBundle(): BundleDetail {
  return bundle({
    status: 'CANCELLED',
    cancelledBy: 'An Beslisser',
    cancelledAt: '2026-09-24T11:00:00Z',
    cancelledReason: 'Verkeerde levering',
    staleMutationCount: null,
    plannedCount: null,
    awaitingApprovalCount: null,
    expirableCount: null,
  });
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function renderOverview(detail: BundleDetail) {
  const reloadBundle = vi.fn();
  render(
    <ActorProvider>
      <MemoryRouter initialEntries={['/bundles/42']}>
        <Routes>
          <Route path="/bundles/:bundleId" element={<Outlet context={{ bundle: detail, reloadBundle }} />}>
            <Route index element={<BundleOverviewTab />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
  return { reloadBundle };
}

function fetchCalls() {
  return vi.mocked(global.fetch).mock.calls.map((call) => ({
    url: call[0]?.toString() ?? '',
    method: call[1]?.method ?? 'GET',
    body: call[1]?.body,
  }));
}

function posts() {
  return fetchCalls().filter((call) => call.method === 'POST');
}


async function openCancelDialog(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: 'Annuleren' }));
  return screen.findByRole('dialog');
}

function waitForCount(dialog: HTMLElement): Promise<HTMLElement> {
  return within(dialog).findByTestId('cancel-expiring-count');
}

/** Er is geen enkele GET meer: het getal komt uit `BundleDetail.expirableCount` (C7). */
function gets() {
  return fetchCalls().filter((call) => call.method === 'GET');
}

function fillConfirmation(dialog: HTMLElement, typed: string = REFERENCE, reason = 'Verkeerde levering') {
  fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
  fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: reason } });
  fireEvent.change(within(dialog).getByLabelText(/Typ "BND-2026-001"/), { target: { value: typed } });
}

function confirmButton(dialog: HTMLElement): HTMLElement {
  return within(dialog).getByRole('button', { name: 'Bundel annuleren' });
}

describe('CancelDialog (F10, §10.6)', () => {
  const originalFetch = global.fetch;
  let cancelResponse: () => Response;

  beforeEach(() => {
    cancelResponse = () => jsonResponse(cancelledBundle());
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      if (method === 'POST' && url.endsWith('/bundles/42/cancel')) {
        return Promise.resolve(cancelResponse());
      }
      return Promise.reject(new Error(`Onverwachte aanroep in test: ${method} ${url}`));
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('F10.C1: toont het aantal mutaties dat vervalt, per status uitgesplitst, vóór er iets verstuurd wordt', async () => {
    renderOverview(bundle());
    const dialog = await openCancelDialog();

    const total = await waitForCount(dialog);
    expect(total.textContent).toContain('10 mutaties');
    expect(total.textContent).toContain('vervallen (EXPIRED)');
    expect(total.textContent).toContain('nooit meer herleefd');

    // De letterlijke zin uit §10.6.
    expect(
      within(dialog).getByText(/Dit maakt de beslissingen in deze bundel niet ongedaan in het register/),
    ).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('F10.C2: geen enkele lijstaanroep meer — het getal komt uit BundleDetail.expirableCount (C7)', async () => {
    renderOverview(bundle());
    const dialog = await openCancelDialog();
    await waitForCount(dialog);

    expect(gets()).toHaveLength(0);
    expect(within(dialog).queryByRole('button', { name: 'Opnieuw tellen' })).not.toBeInTheDocument();
  });

  it('F10.C3: enkelvoud bij precies één vervallende mutatie', async () => {
    renderOverview(bundle({ expirableCount: 1 }));
    const dialog = await openCancelDialog();

    const total = await waitForCount(dialog);
    expect(total.textContent).toContain('1 mutatie vervalt');
  });

  it('F10.C4: ontbreekt het getal (null), dan geen getal getoond en geen annulering', async () => {
    renderOverview(bundle({ expirableCount: null }));
    const dialog = await openCancelDialog();

    expect(within(dialog).queryByTestId('cancel-expiring-count')).not.toBeInTheDocument();
    fillConfirmation(dialog);
    expect(confirmButton(dialog)).toBeDisabled();
    expect(within(dialog).getByTestId('confirm-blocked-reason').textContent).toContain('niet vastgesteld');
    fireEvent.submit(dialog);
    expect(posts()).toHaveLength(0);
  });

  it('F10.C4b: 0 vervallende mutaties is een geldig getal (geen null): getoond en annuleren toegelaten', async () => {
    renderOverview(bundle({ expirableCount: 0 }));
    const dialog = await openCancelDialog();

    expect((await waitForCount(dialog)).textContent).toContain('0 mutaties vervallen');
    fillConfirmation(dialog);
    expect(confirmButton(dialog)).toBeEnabled();
  });

  it('F10.C5: een verkeerde typ-bevestiging verstuurt niets', async () => {
    renderOverview(bundle());
    const dialog = await openCancelDialog();
    await waitForCount(dialog);

    for (const wrong of ['', 'bnd-2026-001', ' BND-2026-001', 'BND-2026-0011']) {
      fillConfirmation(dialog, wrong);
      expect(confirmButton(dialog)).toBeDisabled();
      fireEvent.click(confirmButton(dialog));
      fireEvent.submit(dialog);
    }
    expect(posts()).toHaveLength(0);

    fillConfirmation(dialog, REFERENCE);
    expect(confirmButton(dialog)).toBeEnabled();
  });

  it('F10.C6: sluiten zonder annuleren verstuurt niets en meldt niets', async () => {
    const { reloadBundle } = renderOverview(bundle());
    const dialog = await openCancelDialog();
    await waitForCount(dialog);
    fillConfirmation(dialog);

    fireEvent.click(within(dialog).getByRole('button', { name: 'Sluiten zonder annuleren' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(reloadBundle).not.toHaveBeenCalled();
  });

  it('F10.C7: een 409 BUNDLE_NOT_CANCELLABLE blijft in de dialoog staan mét code, zonder succesmelding', async () => {
    cancelResponse = () =>
      jsonResponse(
        { error: 'Bundle 42 is CANCELLED; only an ASSEMBLING or FROZEN bundle can be cancelled', code: 'BUNDLE_NOT_CANCELLABLE' },
        409,
      );
    const { reloadBundle } = renderOverview(bundle());
    const dialog = await openCancelDialog();
    await waitForCount(dialog);
    fillConfirmation(dialog);
    fireEvent.click(confirmButton(dialog));

    expect(await within(dialog).findByText('Bundel kan niet geannuleerd worden')).toBeInTheDocument();
    expect(within(dialog).getByText('BUNDLE_NOT_CANCELLABLE · HTTP 409 · /bundles/42/cancel')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(reloadBundle).not.toHaveBeenCalled();
    // Geen herhaling van het annuleren.
    expect(posts()).toHaveLength(1);
  });

  it('F10.C8: een 400 zonder code (ontbrekende reden aan serverkant) wordt letterlijk getoond, geen succes', async () => {
    cancelResponse = () => jsonResponse({ error: 'reason must not be longer than 500 characters' }, 400);
    const { reloadBundle } = renderOverview(bundle());
    const dialog = await openCancelDialog();
    await waitForCount(dialog);
    fillConfirmation(dialog);
    fireEvent.click(confirmButton(dialog));

    expect(await within(dialog).findByText('reason must not be longer than 500 characters')).toBeInTheDocument();
    expect(within(dialog).getByText(/geen code · HTTP 400/)).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(reloadBundle).not.toHaveBeenCalled();
  });

  it('F10.C9: happy path — verstuurt actor en reden, meldt het resultaat van de server en herlaadt de bundel', async () => {
    const { reloadBundle } = renderOverview(bundle());
    const dialog = await openCancelDialog();
    await waitForCount(dialog);
    fillConfirmation(dialog, REFERENCE, '  Verkeerde levering  ');
    fireEvent.click(confirmButton(dialog));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(posts()).toHaveLength(1);
    const post = posts()[0]!;
    expect(post.url).toBe('/api/catalog-import/bundles/42/cancel');
    expect(JSON.parse(String(post.body))).toEqual({ cancelledBy: 'An Beslisser', reason: 'Verkeerde levering' });

    const notice = screen.getByRole('status').textContent ?? '';
    expect(notice).toContain(`Bundel ${REFERENCE} is geannuleerd door An Beslisser`);
    expect(notice).toContain('EXPIRED');
    expect(reloadBundle).toHaveBeenCalledTimes(1);
  });

  it('F10.C10: een bevroren bundel kan geannuleerd worden; de telling vindt de goedgekeurde mutaties', async () => {
    renderOverview(frozenBundle());
    const dialog = await openCancelDialog();

    expect((await waitForCount(dialog)).textContent).toContain('12 mutaties');
    fillConfirmation(dialog);
    expect(confirmButton(dialog)).toBeEnabled();
  });

  it('F10.C11: een geannuleerde bundel biedt annuleren niet aan (uitgeschakeld mét reden), en telt niets', async () => {
    renderOverview(cancelledBundle());

    const button = await screen.findByRole('button', { name: 'Annuleren' });
    expect(button).toBeDisabled();
    expect(button.getAttribute('title')).toContain('BUNDLE_NOT_CANCELLABLE');
    fireEvent.click(button);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(fetchCalls()).toHaveLength(0);
  });
});
