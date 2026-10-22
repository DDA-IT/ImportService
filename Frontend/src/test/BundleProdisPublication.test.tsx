/**
 * P7 — de publicatie naar de Prodis-database in `BundlePublicationTab`: de knop, de bevestiging met modus en
 * waarschuwing, de continue-lus (voortgang, BUSY-retry, pauzeren/hervatten, fouten) en de itemresultaten.
 * Echte API-laag met een `fetch`-stub, naar het patroon van `BundlePublicationTab.test.tsx`.
 * Zie `docs/design/publicatie-prodis-db-direct-design.md` par. 5/6 (P7) en `docs/decisions.md` 2026-10-22.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Outlet, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';
import {
  PERMISSION_MANAGE,
  PERMISSION_READ,
  type BundleDetail,
  type PageResult,
  type ParallelRunSummary,
  type PublicationItemView,
  type PublicationRunView,
} from '../api/types';
import { BundlePublicationTab } from '../features/bundles/BundlePublicationTab';
import { DATASYNC_WARNING, LOOP_TIMING } from '../features/bundles/prodisPublication';

function bundle(overrides: Partial<BundleDetail> = {}): BundleDetail {
  return {
    id: 42,
    bundleReference: 'BND-2026-001',
    description: null,
    status: 'FROZEN',
    targetMode: 'PRODUCTION',
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

function parallel(overrides: Partial<ParallelRunSummary> = {}): ParallelRunSummary {
  return {
    parallelRunSpecVersion: 1,
    status: 'PARALLEL_RUN_COMPLETED',
    statusMessage: null,
    writesToProdis: false,
    legacyMaster: 'PRODIS',
    legacyMasterAvailability: 'VERIFIED',
    legacyMasterWarningCode: null,
    legacyMasterWarning: null,
    itemCount: 10,
    outcomeCounts: { WOULD_UPDATE: 6, UNCHANGED: 1, CONFLICT: 2, REJECTED: 1, NOT_SENT: 0 },
    reasonCounts: { VALUE_CHANGED_IN_PRODIS: 2 },
    noticeCounts: { PRICE_ROUNDING_DIFFERENCE: 3, VALUE_CHANGED_IN_PRODIS: 2 },
    startedAt: '2026-10-20T10:00:00Z',
    finishedAt: '2026-10-20T10:00:05Z',
    reportSha256: '34'.repeat(32),
    reportByteSize: 1234,
    ...overrides,
  };
}

function simulatedRun(overrides: Partial<PublicationRunView> = {}): PublicationRunView {
  return {
    id: 1,
    bundleId: 42,
    targetMode: 'SIMULATION',
    attempt: 1,
    status: 'SIMULATED',
    requestedBy: 'An Beslisser',
    requestedAt: '2026-10-20T10:00:00Z',
    startedAt: '2026-10-20T10:00:01Z',
    finishedAt: '2026-10-20T10:00:02Z',
    bundleContentHash: 'ab'.repeat(32),
    snapshotHash: 'cd'.repeat(32),
    payloadHash: 'ef'.repeat(32),
    artifactSha256: '12'.repeat(32),
    artifactByteSize: 321,
    rowCount: 10,
    incompleteRowCount: 0,
    failureCode: null,
    failureMessage: null,
    simulationOnly: true,
    writesToProdis: false,
    contractStatus: 'UNVERIFIED_FIELD_INVENTORY',
    previewSpecVersion: 'v1',
    snapshotSpecVersion: 'v2',
    parallelRun: parallel(),
    ...overrides,
  };
}

function prodisRun(overrides: Partial<PublicationRunView> = {}): PublicationRunView {
  return simulatedRun({
    id: 7,
    targetMode: 'PRODUCTION',
    attempt: 2,
    status: 'PUBLISHING',
    artifactSha256: null,
    artifactByteSize: null,
    payloadHash: null,
    simulationOnly: false,
    writesToProdis: true,
    parallelRun: null,
    legacyMasterAtStart: 'PRODIS',
    itemCounts: { PENDING: 8, APPLIED: 2 },
    done: false,
    ...overrides,
  });
}

function item(overrides: Partial<PublicationItemView> = {}): PublicationItemView {
  return {
    mutationId: 501,
    identity: { supplierNumber: 'SUP1', groupLegacyCode: 'GRP', supplierReference: 'REF-1', libraryCode: 'LIB' },
    status: 'APPLIED',
    reasonCode: null,
    reasonDetail: null,
    prior: { basePrice: '10', brutPrice: '12', percentages: { '1': '5' } },
    result: { basePrice: '11', brutPrice: '12', percentages: { '1': '5' } },
    warnings: [],
    legacyMaster: 'PRODIS',
    sendCount: 1,
    lastErrorCode: null,
    ...overrides,
  };
}

function page(content: PublicationItemView[]): PageResult<PublicationItemView> {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: 1 };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function renderTab(detail: BundleDetail, identity = TEST_IDENTITY) {
  return render(
    <ActorProvider identity={identity}>
      <MemoryRouter initialEntries={['/bundles/42/publication']}>
        <Routes>
          <Route path="/bundles/:bundleId" element={<Outlet context={{ bundle: detail, reloadBundle: () => {} }} />}>
            <Route path="publication" element={<BundlePublicationTab />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

type Reply = { body: unknown; status?: number };

describe('BundlePublicationTab — publiceren naar Prodis (P7)', () => {
  const originalFetch = global.fetch;
  let runs: PublicationRunView[] = [];
  let startReply: Reply = { body: prodisRun() };
  /** Antwoorden op `continue`, in volgorde; het laatste antwoord blijft gelden. */
  let continueReplies: Reply[] = [];
  let continueDelayMs = 0;
  let itemPages: PageResult<PublicationItemView> = page([]);

  function calls(match: (url: string, method: string) => boolean) {
    return vi
      .mocked(global.fetch)
      .mock.calls.filter((call) => match(call[0]?.toString() ?? '', call[1]?.method ?? 'GET'));
  }
  const continueCalls = () => calls((url, method) => method === 'POST' && url.endsWith('/publication-runs/7/continue'));

  beforeEach(() => {
    LOOP_TIMING.busyWaitMs = 1;
    runs = [];
    startReply = { body: prodisRun() };
    continueReplies = [{ body: prodisRun({ status: 'APPLIED', done: true, itemCounts: { APPLIED: 10 } }) }];
    continueDelayMs = 0;
    itemPages = page([]);
    global.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      const method = init?.method ?? 'GET';
      if (method === 'POST' && url.endsWith('/continue')) {
        const index = Math.min(continueCalls().length - 1, continueReplies.length - 1);
        const reply = continueReplies[index] as Reply;
        if (continueDelayMs > 0) {
          await new Promise((resolve) => setTimeout(resolve, continueDelayMs));
        }
        return jsonResponse(reply.body, reply.status ?? 200);
      }
      if (method === 'POST' && url.endsWith('/bundles/42/publication-runs')) {
        return jsonResponse(startReply.body, startReply.status ?? 200);
      }
      if (url.includes('/publication-runs/7/items')) {
        return jsonResponse(itemPages);
      }
      if (url.endsWith('/bundles/42/publication-runs')) {
        return jsonResponse(runs);
      }
      throw new Error(`Onverwachte URL in test: ${method} ${url}`);
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
    LOOP_TIMING.busyWaitMs = 1500;
  });

  async function openDialog() {
    fireEvent.click(await screen.findByRole('button', { name: 'Publiceren naar Prodis…' }));
    return screen.findByRole('dialog');
  }

  describe('de knop', () => {
    it('staat er niet bij een bundel die niet voor de echte publicatie bedoeld is', async () => {
      renderTab(bundle({ targetMode: 'SIMULATION' }));
      await screen.findByRole('button', { name: 'Proefpublicatie starten' });
      expect(screen.queryByRole('button', { name: 'Publiceren naar Prodis…' })).not.toBeInTheDocument();
    });

    it('staat er niet bij een bundel in opbouw (enkel uitleg)', async () => {
      renderTab(bundle({ status: 'ASSEMBLING' }));
      expect(await screen.findByText(/alleen gestart worden voor een bevroren bundel/)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Publiceren naar Prodis…' })).not.toBeInTheDocument();
    });

    it('is beschikbaar voor APPROVE bij een bevroren bundel met doel PRODUCTION', async () => {
      renderTab(bundle());
      expect(await screen.findByRole('button', { name: 'Publiceren naar Prodis…' })).toBeEnabled();
    });

    it('is uitgeschakeld mét reden zonder het recht APPROVE', async () => {
      renderTab(bundle(), testIdentityWith(PERMISSION_READ, PERMISSION_MANAGE));
      const button = await screen.findByRole('button', { name: 'Publiceren naar Prodis…' });
      expect(button).toBeDisabled();
      expect(button.getAttribute('title')).toContain('Goedkeuren');
    });

    it('is geblokkeerd zolang er al een publicatie loopt', async () => {
      runs = [prodisRun()];
      renderTab(bundle());
      const button = await screen.findByRole('button', { name: 'Publiceren naar Prodis…' });
      await waitFor(() => expect(button).toBeDisabled());
      expect(button.getAttribute('title')).toContain('#7');
    });
  });

  describe('de bevestiging', () => {
    it('toont de modus, de vaste waarschuwing bij PRODIS en de samenvatting van de laatste parallelrun', async () => {
      runs = [simulatedRun()];
      renderTab(bundle());
      const dialog = await openDialog();

      expect(within(dialog).getByText(/Echte publicatie/)).toBeInTheDocument();
      expect(within(dialog).getByText('Prodis', { selector: 'strong' })).toBeInTheDocument();
      expect(within(dialog).getByText(DATASYNC_WARNING)).toBeInTheDocument();

      const summary = within(dialog).getByTestId('parallel-run-summary');
      expect(summary.textContent).toContain('Zou aangepast worden');
      expect(summary.textContent).toMatch(/2 artikel\(en\) hebben een conflict/);
      expect(summary.textContent).toMatch(/1 artikel\(en\) zouden geweigerd worden/);
      expect(summary.textContent).toMatch(/3 melding\(en\) over een afrondingsverschil/);
      expect(summary.textContent).not.toMatch(/[A-Z]+_[A-Z_]+/);
    });

    it('toont geen waarschuwing als er geen legacy-meester is', async () => {
      runs = [simulatedRun({ parallelRun: parallel({ legacyMaster: 'NONE' }) })];
      renderTab(bundle());
      const dialog = await openDialog();
      expect(within(dialog).queryByText(DATASYNC_WARNING)).not.toBeInTheDocument();
    });

    it('zonder parallelrun: modus onbekend, de waarschuwing blijft, en er staat dat er nog niets vergeleken is', async () => {
      runs = [];
      renderTab(bundle());
      const dialog = await openDialog();
      expect(within(dialog).getByText('Onbekend', { selector: 'strong' })).toBeInTheDocument();
      expect(within(dialog).getByText(DATASYNC_WARNING)).toBeInTheDocument();
      expect(within(dialog).getByText(/Er is nog geen proefvergelijking met Prodis/)).toBeInTheDocument();
    });

    it('een parallelrun die niet gelukt is, wordt niet als "geen afwijkingen" getoond', async () => {
      runs = [
        simulatedRun({
          parallelRun: parallel({
            status: 'PARALLEL_RUN_UNAVAILABLE',
            itemCount: null,
            outcomeCounts: null,
            noticeCounts: null,
          }),
        }),
      ];
      renderTab(bundle());
      const dialog = await openDialog();
      expect(within(dialog).getByText(/er is niets vergeleken/)).toBeInTheDocument();
      expect(within(dialog).queryByText('Zou aangepast worden')).not.toBeInTheDocument();
    });

    it('annuleren start niets', async () => {
      renderTab(bundle());
      const dialog = await openDialog();
      fireEvent.click(within(dialog).getByRole('button', { name: 'Annuleren' }));
      expect(calls((_, method) => method === 'POST')).toHaveLength(0);
    });

    it('een weigering bij het starten staat in de bevestiging', async () => {
      startReply = {
        status: 409,
        body: { error: 'Bundle has incomplete rows', code: 'BUNDLE_HAS_INCOMPLETE_ROWS' },
      };
      renderTab(bundle());
      const dialog = await openDialog();
      fireEvent.click(within(dialog).getByRole('button', { name: 'Publiceren naar Prodis' }));

      expect(await within(dialog).findByText('De bundel bevat onvolledige regels')).toBeInTheDocument();
      expect(continueCalls()).toHaveLength(0);
    });
  });

  describe('de continue-lus', () => {
    it('start met modus PRODUCTION en roept continue aan tot de run klaar is', async () => {
      continueReplies = [
        { body: prodisRun({ itemCounts: { PENDING: 4, APPLIED: 6 } }) },
        { body: prodisRun({ itemCounts: { PENDING: 1, APPLIED: 9 } }) },
        { body: prodisRun({ status: 'APPLIED', done: true, itemCounts: { APPLIED: 10 } }) },
      ];
      renderTab(bundle());
      const dialog = await openDialog();
      fireEvent.click(within(dialog).getByRole('button', { name: 'Publiceren naar Prodis' }));

      await waitFor(() => expect(continueCalls()).toHaveLength(3));
      const post = calls((url, method) => method === 'POST' && url.endsWith('/bundles/42/publication-runs'))[0];
      expect(JSON.parse(String(post?.[1]?.body))).toEqual({ targetMode: 'PRODUCTION' });

      // Na het einde: geen verdere aanroepen en geen Pauzeren-knop meer.
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Pauzeren' })).not.toBeInTheDocument());
      expect(continueCalls()).toHaveLength(3);
    });

    it('toont de voortgang met tellers per artikelstatus terwijl het loopt', async () => {
      runs = [prodisRun({ itemCounts: { PENDING: 5, APPLIED: 4, CONFLICT: 1 } })];
      renderTab(bundle());
      const panel = await screen.findByTestId('prodis-run-7');
      expect(panel.textContent).toContain('5 van 10 artikel(en) afgehandeld');
      expect(panel.querySelector('progress')).toHaveAttribute('max', '10');
      expect(panel.textContent).toContain('Conflict');
      expect(within(panel).getByRole('button', { name: 'Verder publiceren' })).toBeEnabled();
    });

    it('PUBLICATION_RUN_BUSY: kort wachten en opnieuw proberen, zonder foutmelding', async () => {
      continueReplies = [
        { status: 409, body: { error: 'busy', code: 'PUBLICATION_RUN_BUSY' } },
        { status: 409, body: { error: 'busy', code: 'PUBLICATION_RUN_BUSY' } },
        { body: prodisRun({ status: 'APPLIED', done: true, itemCounts: { APPLIED: 10 } }) },
      ];
      renderTab(bundle());
      const dialog = await openDialog();
      fireEvent.click(within(dialog).getByRole('button', { name: 'Publiceren naar Prodis' }));

      await waitFor(() => expect(continueCalls()).toHaveLength(3));
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Pauzeren' })).not.toBeInTheDocument());
      expect(screen.queryByText('De publicatie is bezig')).not.toBeInTheDocument();
    });

    it('een andere fout stopt de lus en wordt getoond', async () => {
      continueReplies = [
        { status: 409, body: { error: 'not configured', code: 'PRODIS_WRITE_NOT_CONFIGURED' } },
      ];
      renderTab(bundle());
      const dialog = await openDialog();
      fireEvent.click(within(dialog).getByRole('button', { name: 'Publiceren naar Prodis' }));

      expect(await screen.findByText('Schrijfverbinding met Prodis niet ingesteld')).toBeInTheDocument();
      expect(continueCalls()).toHaveLength(1);
      expect(screen.queryByRole('button', { name: 'Pauzeren' })).not.toBeInTheDocument();
    });

    it('pauzeren stopt het aanroepen; "Verder publiceren" hervat en rondt af', async () => {
      continueDelayMs = 5;
      continueReplies = [{ body: prodisRun({ itemCounts: { PENDING: 6, APPLIED: 4 } }) }];
      runs = [prodisRun({ itemCounts: { PENDING: 6, APPLIED: 4 } })];
      renderTab(bundle());

      // Hervatten van een bestaande run start de lus zonder nieuwe run aan te maken.
      fireEvent.click(await screen.findByRole('button', { name: 'Verder publiceren' }));
      const pause = await screen.findByRole('button', { name: 'Pauzeren' });
      await waitFor(() => expect(continueCalls().length).toBeGreaterThanOrEqual(1));
      fireEvent.click(pause);

      await waitFor(() => expect(screen.queryByRole('button', { name: 'Pauzeren' })).not.toBeInTheDocument());
      const stoppedAt = continueCalls().length;
      await new Promise((resolve) => setTimeout(resolve, 40));
      expect(continueCalls()).toHaveLength(stoppedAt);
      expect(calls((url, method) => method === 'POST' && url.endsWith('/bundles/42/publication-runs'))).toHaveLength(0);

      continueReplies = [{ body: prodisRun({ status: 'APPLIED', done: true, itemCounts: { APPLIED: 10 } }) }];
      const resume = await screen.findByRole('button', { name: 'Verder publiceren' });
      fireEvent.click(resume);
      await waitFor(() => expect(continueCalls().length).toBeGreaterThan(stoppedAt));
      await waitFor(() => expect(screen.queryByRole('button', { name: 'Pauzeren' })).not.toBeInTheDocument());
    });

    it('"Verder publiceren" vraagt het recht APPROVE', async () => {
      runs = [prodisRun()];
      renderTab(bundle(), testIdentityWith(PERMISSION_READ));
      const button = await screen.findByRole('button', { name: 'Verder publiceren' });
      expect(button).toBeDisabled();
    });
  });

  describe('de itemresultaten', () => {
    it('toont per artikel de status, de waarden vóór en na, en meldingen in gewone taal', async () => {
      runs = [prodisRun({ status: 'APPLIED', done: true, itemCounts: { APPLIED: 1, CONFLICT: 1 } })];
      itemPages = page([
        item({
          warnings: [
            { code: 'PRICE_ROUNDING_DIFFERENCE', field: 'salesPrice1', message: 'rounding differs' },
            { code: 'EMPTY_KEEPS_EXISTING', field: 'description', message: 'empty' },
          ],
        }),
        item({
          mutationId: 502,
          status: 'CONFLICT',
          reasonCode: 'VALUE_CHANGED_IN_PRODIS',
          reasonDetail: 'basePrice 10 -> 13',
          result: null,
          warnings: [{ code: 'VALUE_CHANGED_IN_PRODIS', field: 'basePrice', message: 'changed' }],
        }),
      ]);
      renderTab(bundle());
      fireEvent.click(await screen.findByRole('button', { name: 'Toon de resultaten per artikel' }));

      const applied = await screen.findByTestId('prodis-item-501');
      expect(applied.textContent).toContain('SUP1 / GRP / REF-1');
      expect(applied.textContent).toContain('Doorgevoerd');
      expect(applied.textContent).toContain('Basisprijs: 10 → 11');
      expect(applied.textContent).toContain('Afrondingsverschil');
      expect(applied.textContent).toContain('Leeg veld: bestaande waarde blijft');

      const conflict = screen.getByTestId('prodis-item-502');
      expect(conflict.textContent).toContain('Conflict');
      expect(conflict.textContent).toContain('Waarde in Prodis veranderd');
      // De ruwe code en de Engelse servertekst staan enkel onder "Technische details".
      const visible = conflict.cloneNode(true) as HTMLElement;
      visible.querySelectorAll('details').forEach((node) => node.remove());
      expect(visible.textContent).not.toMatch(/[A-Z]+_[A-Z_]+/);
      expect(visible.textContent).not.toContain('basePrice 10 -> 13');
      expect(conflict.querySelector('details')?.textContent).toContain('basePrice 10 -> 13');
    });

    it('filtert op status via de server', async () => {
      runs = [prodisRun({ status: 'APPLIED', done: true, itemCounts: { APPLIED: 1 } })];
      itemPages = page([item()]);
      renderTab(bundle());
      fireEvent.click(await screen.findByRole('button', { name: 'Toon de resultaten per artikel' }));
      await screen.findByTestId('prodis-item-501');

      fireEvent.change(screen.getByLabelText('Toon artikelen met status'), { target: { value: 'CONFLICT' } });
      await waitFor(() =>
        expect(
          calls((url) => url.includes('/publication-runs/7/items')).some((call) => call[0]?.toString().includes('status=CONFLICT')),
        ).toBe(true),
      );
    });

    it('een afgeronde publicatie met conflicten of weigeringen zegt dat niet alles doorgevoerd is', async () => {
      runs = [prodisRun({ status: 'PARTIALLY_PUBLISHED' as never, done: true, itemCounts: { APPLIED: 7, CONFLICT: 2, REJECTED: 1 } })];
      renderTab(bundle());
      const panel = await screen.findByTestId('prodis-run-7');
      expect(panel.textContent).toContain('niet alles is doorgevoerd');
      expect(panel.textContent).toContain('2 × conflict');
      expect(panel.textContent).toContain('1 × geweigerd');
    });

    it('een lege lijst en een fout worden getoond', async () => {
      runs = [prodisRun({ status: 'APPLIED', done: true, itemCounts: { APPLIED: 0 } })];
      itemPages = page([]);
      renderTab(bundle());
      fireEvent.click(await screen.findByRole('button', { name: 'Toon de resultaten per artikel' }));
      expect(await screen.findByText('Geen artikelen met deze status.')).toBeInTheDocument();
    });
  });

  it('de legacy-meester bij de start en de waarschuwing staan bij de run', async () => {
    runs = [prodisRun({ legacyMasterAtStart: 'PROFAB', status: 'APPLIED', done: true })];
    renderTab(bundle({ status: 'PUBLISHED' }));
    const panel = await screen.findByTestId('prodis-run-7');
    expect(panel.textContent).toContain('Stand van de legacy-meester bij de start: ProFab.');
    expect(within(panel).getByText(DATASYNC_WARNING)).toBeInTheDocument();
  });
});
