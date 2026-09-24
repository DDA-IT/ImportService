/**
 * F10 — bevriezen (`FreezeDialog`, geopend vanuit `BundleOverviewTab`), zie
 * `docs/design/frontend-scherm3-bundel-design.md` §10.5 en `docs/decisions.md` 2026-09-23 (C2/C3/V4).
 *
 * Acceptatiecriteria van F10 die hier aantoonbaar gemaakt worden:
 * - voorvlucht met het `PLANNED`-aantal, en blokkades vooraf;
 * - bevriezen met een openstaande `AWAITING_APPROVAL` is vooraf geblokkeerd, met een zichtbare reden;
 * - de 409-tekst met conflictvoorbeelden wordt volledig en leesbaar getoond;
 * - de typ-bevestiging werkt.
 *
 * Draait tegen de echte API-laag (`api/bundles.ts` + `api/http.ts`) met een `fetch`-stub, zodat ook de
 * paden en de request-body's gecontroleerd worden.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { BundleDetail, FreezePreflight } from '../api/types';
import { BundleOverviewTab } from '../features/bundles/BundleOverviewTab';

const REFERENCE = 'BND-2026-001';
const HASH = 'abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789';

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
    readyCount: 5,
    rejectedCount: 0,
    blockedCount: 0,
    expiredCount: null,
    identityIncidentCount: 0,
    bulkIncidentCount: null,
    criticalIssueCount: null,
    warningCount: null,
    staleMutationCount: 0,
    contentHash: null,
    plannedCount: 7,
    awaitingApprovalCount: 0,
    ...overrides,
  };
}

/** Het antwoord van `POST /freeze`: de bevroren `BundleDetail` (tellers van de rij, hash gevuld). */
function frozenBundle(): BundleDetail {
  return bundle({
    status: 'FROZEN',
    frozenBy: 'An Beslisser',
    frozenAt: '2026-09-24T10:00:00Z',
    frozenReason: 'Klaar voor publicatie',
    readyCount: 12,
    expiredCount: 0,
    staleMutationCount: null,
    contentHash: HASH,
    plannedCount: null,
    awaitingApprovalCount: null,
  });
}

function preflight(overrides: Partial<FreezePreflight> = {}): FreezePreflight {
  return {
    freezable: true,
    blockerCodes: [],
    batchCount: 2,
    plannedCount: 7,
    awaitingApprovalCount: 0,
    staleMutationCount: 0,
    inBundleConflicts: [],
    crossBundleConflicts: [],
    ...overrides,
  };
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

function preflightLoads() {
  return fetchCalls().filter((call) => call.method === 'GET' && call.url.endsWith('/bundles/42/freeze-check'));
}

async function openFreezeDialog(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: 'Bevriezen' }));
  return screen.findByRole('dialog');
}

/** Wacht tot de voorvlucht in beeld staat (het PLANNED-aantal is dan zichtbaar). */
function waitForPreflight(dialog: HTMLElement): Promise<HTMLElement> {
  return within(dialog).findByTestId('freeze-planned-count');
}

function fillConfirmation(dialog: HTMLElement, typed: string = REFERENCE, reason = 'Klaar voor publicatie') {
  fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
  fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: reason } });
  fireEvent.change(within(dialog).getByLabelText(/Typ "BND-2026-001"/), { target: { value: typed } });
}

function confirmButton(dialog: HTMLElement): HTMLElement {
  return within(dialog).getByRole('button', { name: 'Bundel bevriezen' });
}

/** Tien voorbeelden zoals `BundleFreezeService.describeInBundle` ze opbouwt, en de volledige 409-tekst. */
const IN_BUNDLE_EXAMPLES = Array.from(
  { length: 10 },
  (_, index) => `link 7 offer SUP${index}/GRP${index}/REF-${index} in 2 batches (e.g. ${100 + index} and ${200 + index})`,
);
const OFFER_CONFLICT_MESSAGE =
  'Bundle 42 contains the same offer in more than one batch, which would make the last import silently win: ' +
  IN_BUNDLE_EXAMPLES.join(', ') +
  '. Reject one side before freezing';

describe('FreezeDialog (F10, §10.5)', () => {
  const originalFetch = global.fetch;
  let preflightResponse: () => Response;
  let freezeResponse: () => Response;

  beforeEach(() => {
    preflightResponse = () => jsonResponse(preflight());
    freezeResponse = () => jsonResponse(frozenBundle());
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      if (method === 'GET' && url.endsWith('/bundles/42/freeze-check')) {
        return Promise.resolve(preflightResponse());
      }
      if (method === 'POST' && url.endsWith('/bundles/42/freeze')) {
        return Promise.resolve(freezeResponse());
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

  it('F10.F1: de voorvlucht toont het PLANNED-aantal dat op naam van de bevriezer goedgekeurd wordt, vóór er iets verstuurd wordt', async () => {
    preflightResponse = () => jsonResponse(preflight({ plannedCount: 1234 }));
    renderOverview(bundle());

    const dialog = await openFreezeDialog();
    const planned = await waitForPreflight(dialog);
    expect(planned.textContent).toContain('1234 mutaties');
    expect(planned.textContent).toContain('PLANNED');
    expect(planned.textContent).toContain('uw naam');

    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    expect(within(dialog).getByTestId('freeze-planned-count').textContent).toContain('uw naam (An Beslisser)');

    // De blijvende waarschuwing uit §10.5 staat in beeld.
    expect(within(dialog).getByText(/Publiceren bestaat nog niet \(Fase 5\)/)).toBeInTheDocument();
    expect(preflightLoads()).toHaveLength(1);
    expect(posts()).toHaveLength(0);
  });

  it('F10.F2: een openstaande AWAITING_APPROVAL blokkeert bevriezen vooraf, met een zichtbare reden', async () => {
    preflightResponse = () =>
      jsonResponse(
        preflight({ freezable: false, blockerCodes: ['BUNDLE_HAS_UNDECIDED_MUTATIONS'], awaitingApprovalCount: 3 }),
      );
    renderOverview(bundle({ awaitingApprovalCount: 3 }));

    // Al op het overzicht aangekondigd (C2-teller), zonder dat de knop de voorvlucht verbergt.
    expect(await screen.findByText(/de voorvlucht zal bevriezen blokkeren/)).toBeInTheDocument();

    const dialog = await openFreezeDialog();
    await waitForPreflight(dialog);
    const blockers = within(dialog).getByRole('list', { name: 'Blokkades' });
    expect(blockers.textContent).toContain('BUNDLE_HAS_UNDECIDED_MUTATIONS');
    expect(blockers.textContent).toContain('3 mutaties');
    expect(blockers.textContent).toContain('AWAITING_APPROVAL');

    // Ook met alles correct ingevuld blijft bevestigen onmogelijk, met de reden bij de knop.
    fillConfirmation(dialog);
    expect(confirmButton(dialog)).toBeDisabled();
    expect(within(dialog).getByTestId('confirm-blocked-reason').textContent).toContain('blokkade');

    fireEvent.click(confirmButton(dialog));
    fireEvent.submit(dialog);
    expect(posts()).toHaveLength(0);
  });

  it('F10.F3: de conflictvoorbeelden van de voorvlucht staan volledig in beeld en blokkeren', async () => {
    const crossExample = 'link 7 offer SUP1/GRP1/REF-1 (batch 101 here, batch 301 in bundle 9)';
    preflightResponse = () =>
      jsonResponse(
        preflight({
          freezable: false,
          blockerCodes: ['BUNDLE_OFFER_CONFLICT', 'OFFER_ALREADY_IN_ANOTHER_BUNDLE'],
          inBundleConflicts: IN_BUNDLE_EXAMPLES,
          crossBundleConflicts: [crossExample],
        }),
      );
    renderOverview(bundle());

    const dialog = await openFreezeDialog();
    await waitForPreflight(dialog);
    const inBundle = within(dialog).getByRole('list', { name: 'Conflicten binnen deze bundel' });
    expect(within(inBundle).getAllByRole('listitem').map((item) => item.textContent)).toEqual(IN_BUNDLE_EXAMPLES);
    const crossBundle = within(dialog).getByRole('list', { name: 'Conflicten met een andere bundel' });
    expect(within(crossBundle).getByRole('listitem').textContent).toBe(crossExample);

    fillConfirmation(dialog);
    expect(confirmButton(dialog)).toBeDisabled();
  });

  it('F10.F4: een verkeerde typ-bevestiging verstuurt niets; pas de exacte referentie zet de knop aan', async () => {
    renderOverview(bundle());
    const dialog = await openFreezeDialog();
    await waitForPreflight(dialog);

    for (const wrong of ['', 'bnd-2026-001', 'BND-2026-001 ', 'BND-2026-00']) {
      fillConfirmation(dialog, wrong);
      expect(confirmButton(dialog)).toBeDisabled();
      fireEvent.click(confirmButton(dialog));
      fireEvent.submit(dialog);
    }
    expect(posts()).toHaveLength(0);

    fillConfirmation(dialog, REFERENCE);
    expect(confirmButton(dialog)).toBeEnabled();
  });

  it('F10.F5: sluiten zonder bevriezen verstuurt niets en meldt niets', async () => {
    const { reloadBundle } = renderOverview(bundle());
    const dialog = await openFreezeDialog();
    await waitForPreflight(dialog);
    fillConfirmation(dialog);

    fireEvent.click(within(dialog).getByRole('button', { name: 'Sluiten zonder bevriezen' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(posts()).toHaveLength(0);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(reloadBundle).not.toHaveBeenCalled();
  });

  it('F10.F6: een 409 met conflictvoorbeelden wordt volledig en letterlijk getoond, in de open dialoog', async () => {
    freezeResponse = () => jsonResponse({ error: OFFER_CONFLICT_MESSAGE, code: 'BUNDLE_OFFER_CONFLICT' }, 409);
    const { reloadBundle } = renderOverview(bundle());
    const dialog = await openFreezeDialog();
    await waitForPreflight(dialog);
    fillConfirmation(dialog);
    fireEvent.click(confirmButton(dialog));

    const detail = await within(dialog).findByTestId('error-detail');
    // Volledig: exact de servertekst, niet ingekort, met alle tien voorbeelden.
    expect(detail.textContent).toBe(OFFER_CONFLICT_MESSAGE);
    for (const example of IN_BUNDLE_EXAMPLES) {
      expect(detail.textContent).toContain(example);
    }
    // Met de uitleg uit §10.5 en altijd de technische regel met de stabiele code.
    expect(within(dialog).getByText('Keur er één af, of publiceer/annuleer eerst de andere bundel.')).toBeInTheDocument();
    expect(within(dialog).getByText('BUNDLE_OFFER_CONFLICT · HTTP 409 · /bundles/42/freeze')).toBeInTheDocument();

    // Geen succes, geen herhaling van het bevriezen; wel een verse voorvlucht (een lezing).
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(reloadBundle).not.toHaveBeenCalled();
    expect(posts()).toHaveLength(1);
    await waitFor(() => expect(preflightLoads()).toHaveLength(2));
  });

  it('F10.F7: de regelafbrekingen van een conflictmelding blijven behouden (OFFER_ALREADY_IN_ANOTHER_BUNDLE)', async () => {
    const message =
      'Bundle 42 contains offers that are also publishable in another open or frozen bundle:\n' +
      'link 7 offer SUP1/GRP1/REF-1 (batch 101 here, batch 301 in bundle 9)\n' +
      'link 7 offer SUP2/GRP2/REF-2 (batch 102 here, batch 302 in bundle 9)\n' +
      'Reject one side, or publish or cancel the other bundle first';
    freezeResponse = () => jsonResponse({ error: message, code: 'OFFER_ALREADY_IN_ANOTHER_BUNDLE' }, 409);
    renderOverview(bundle());
    const dialog = await openFreezeDialog();
    await waitForPreflight(dialog);
    fillConfirmation(dialog);
    fireEvent.click(confirmButton(dialog));

    const detail = await within(dialog).findByTestId('error-detail');
    expect(detail.textContent).toBe(message);
    expect(within(dialog).getByText(/OFFER_ALREADY_IN_ANOTHER_BUNDLE · HTTP 409/)).toBeInTheDocument();
  });

  it('F10.F8: een andere 409 (bundel intussen bevroren) geeft geen succesmelding', async () => {
    freezeResponse = () =>
      jsonResponse(
        { error: 'Bundle 42 is FROZEN; only an ASSEMBLING bundle can be frozen', code: 'BUNDLE_NOT_ASSEMBLING' },
        409,
      );
    const { reloadBundle } = renderOverview(bundle());
    const dialog = await openFreezeDialog();
    await waitForPreflight(dialog);
    fillConfirmation(dialog);
    fireEvent.click(confirmButton(dialog));

    expect(await within(dialog).findByText('Bundel is niet meer in opbouw')).toBeInTheDocument();
    expect(within(dialog).getByText(/BUNDLE_NOT_ASSEMBLING · HTTP 409/)).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(reloadBundle).not.toHaveBeenCalled();
    expect(posts()).toHaveLength(1);
  });

  it('F10.F9: een 500 zonder body geeft geen succesmelding en geen verzonnen oorzaak', async () => {
    freezeResponse = () => new Response('', { status: 500 });
    const { reloadBundle } = renderOverview(bundle());
    const dialog = await openFreezeDialog();
    await waitForPreflight(dialog);
    fillConfirmation(dialog);
    fireEvent.click(confirmButton(dialog));

    expect(await within(dialog).findByText('Onverwachte serverfout')).toBeInTheDocument();
    expect(within(dialog).getByText(/geen code · HTTP 500/)).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(reloadBundle).not.toHaveBeenCalled();
    expect(posts()).toHaveLength(1);
  });

  it('F10.F10: happy path — verstuurt actor en reden, meldt het resultaat van de server en herlaadt de bundel', async () => {
    const { reloadBundle } = renderOverview(bundle());
    const dialog = await openFreezeDialog();
    await waitForPreflight(dialog);
    fillConfirmation(dialog, REFERENCE, '  Klaar voor publicatie  ');
    fireEvent.click(confirmButton(dialog));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(posts()).toHaveLength(1);
    const post = posts()[0]!;
    expect(post.url).toBe('/api/catalog-import/bundles/42/freeze');
    expect(JSON.parse(String(post.body))).toEqual({ frozenBy: 'An Beslisser', reason: 'Klaar voor publicatie' });

    const notice = screen.getByRole('status').textContent ?? '';
    expect(notice).toContain(`Bundel ${REFERENCE} is bevroren door An Beslisser`);
    expect(notice).toContain(`bundelhash ${HASH.slice(0, 16)}`);
    expect(reloadBundle).toHaveBeenCalledTimes(1);
  });

  it('F10.F11: zonder voorvlucht (laden mislukt) is bevriezen uit; opnieuw controleren herstelt dat', async () => {
    let attempts = 0;
    preflightResponse = () => {
      attempts += 1;
      return attempts === 1 ? new Response('', { status: 500 }) : jsonResponse(preflight());
    };
    renderOverview(bundle());
    const dialog = await openFreezeDialog();

    expect(await within(dialog).findByText('Onverwachte serverfout')).toBeInTheDocument();
    expect(within(dialog).queryByTestId('freeze-planned-count')).not.toBeInTheDocument();
    fillConfirmation(dialog);
    expect(confirmButton(dialog)).toBeDisabled();
    expect(within(dialog).getByTestId('confirm-blocked-reason').textContent).toContain('voorvlucht');

    fireEvent.click(within(dialog).getByRole('button', { name: 'Opnieuw controleren' }));
    await waitForPreflight(dialog);
    expect(confirmButton(dialog)).toBeEnabled();
    expect(posts()).toHaveLength(0);
  });

  it('F10.F12: het overzicht toont de C2-tellers van BundleDetail, zonder extra lijstaanroepen', async () => {
    renderOverview(bundle({ plannedCount: 5, awaitingApprovalCount: 0 }));

    const planned = await screen.findByText('Wordt bij bevriezen goedgekeurd (PLANNED)');
    expect(planned.nextElementSibling?.textContent).toBe('5');
    const awaiting = screen.getByText('Wacht op beslissing (AWAITING_APPROVAL)');
    expect(awaiting.nextElementSibling?.textContent).toBe('0');
    expect(fetchCalls()).toHaveLength(0);
  });

  it('F10.F13: een bevroren bundel biedt bevriezen niet aan (uitgeschakeld mét reden), en laadt geen voorvlucht', async () => {
    renderOverview(frozenBundle());

    const button = await screen.findByRole('button', { name: 'Bevriezen' });
    expect(button).toBeDisabled();
    expect(button.getAttribute('title')).toContain('BUNDLE_NOT_ASSEMBLING');
    expect(screen.getByText('Kan niet: de bundel is bevroren (BUNDLE_NOT_ASSEMBLING).')).toBeInTheDocument();

    fireEvent.click(button);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(fetchCalls()).toHaveLength(0);
  });
});
