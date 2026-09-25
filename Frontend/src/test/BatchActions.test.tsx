/**
 * B-F2 — `BatchActions` (accept-baseline en bundel-opname op het batchdetail), zie `docs/decisions.md`
 * 2026-09-23 (stap 9) en 2026-09-22 (plaatsing). Draait tegen de echte API-laag met een `fetch`-stub.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { BatchDetail } from '../api/types';
import { ACCEPT_BASELINE_CONFIRMATION, BatchActions } from '../features/batches/BatchActions';

function batch(status: string): BatchDetail {
  return { batchId: 101, status } as unknown as BatchDetail;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

const BUNDLE = { id: 42, bundleReference: 'BND-1', status: 'ASSEMBLING' };

describe('BatchActions (B-F2)', () => {
  const originalFetch = global.fetch;
  let baselineResponse: () => Promise<Response>;
  let addResponse: () => Response;

  beforeEach(() => {
    baselineResponse = () =>
      Promise.resolve(
        jsonResponse({ batchId: 101, status: 'BASELINE_ACCEPTED', acceptedBy: 'An', skippedMutationCount: 6 }),
      );
    addResponse = () => jsonResponse([{ id: 1, bundleId: 42, batchId: 101 }]);
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString();
      const method = init?.method ?? 'GET';
      if (method === 'POST' && url.endsWith('/batches/101/accept-baseline')) return baselineResponse();
      if (method === 'POST' && url.endsWith('/bundles/42/batches')) return Promise.resolve(addResponse());
      if (method === 'GET' && url.includes('/bundles?')) {
        return Promise.resolve(jsonResponse({ content: [BUNDLE], page: 0, size: 100, totalElements: 1, totalPages: 1 }));
      }
      return Promise.reject(new Error(`Onverwachte aanroep: ${method} ${url}`));
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    global.fetch = originalFetch;
  });

  function renderActions(status = 'SCREENED') {
    const onChanged = vi.fn();
    render(
      <ActorProvider>
        <MemoryRouter>
          <BatchActions batch={batch(status)} onChanged={onChanged} />
        </MemoryRouter>
      </ActorProvider>,
    );
    return { onChanged };
  }

  function posts() {
    return vi.mocked(global.fetch).mock.calls.filter((call) => call[1]?.method === 'POST');
  }

  function openBaseline(): HTMLElement {
    fireEvent.click(screen.getByRole('button', { name: 'Aanvaarden als nulmeting' }));
    return screen.getByRole('dialog');
  }

  function fill(dialog: HTMLElement, reason = 'Nulmeting', typed = ACCEPT_BASELINE_CONFIRMATION) {
    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: reason } });
    fireEvent.change(within(dialog).getByLabelText(/Typ "/), { target: { value: typed } });
  }

  const confirm = (dialog: HTMLElement) => within(dialog).getByRole('button', { name: 'Aanvaarden als nulmeting' });

  it('normaal: verstuurt acceptedBy en reden, meldt het resultaat en herlaadt de batch', async () => {
    const { onChanged } = renderActions();
    const dialog = openBaseline();
    fill(dialog);
    fireEvent.click(confirm(dialog));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(posts()).toHaveLength(1);
    const call = posts()[0]!;
    expect(call[0].toString()).toBe('/api/catalog-import/batches/101/accept-baseline');
    expect(JSON.parse(String(call[1]?.body))).toEqual({ acceptedBy: 'An Beslisser', reason: 'Nulmeting' });
    expect(screen.getByRole('status').textContent).toContain('6 mutaties');
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it.each(['RECEIVED', 'BLOCKED', 'FAILED', 'BASELINE_ACCEPTED'])('toont geen acties bij status %s', (status) => {
    renderActions(status);
    expect(screen.queryByRole('button', { name: 'Aanvaarden als nulmeting' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Opnemen in bundel' })).not.toBeInTheDocument();
  });

  it('ontbrekende reden of typ-bevestiging blokkeert bevestigen', () => {
    renderActions();
    const dialog = openBaseline();
    fill(dialog, '   ', ACCEPT_BASELINE_CONFIRMATION);
    expect(confirm(dialog)).toBeDisabled();
    fill(dialog, 'Nulmeting', 'baseline');
    expect(confirm(dialog)).toBeDisabled();
    fireEvent.submit(dialog);
    expect(posts()).toHaveLength(0);
    fill(dialog);
    expect(confirm(dialog)).toBeEnabled();
  });

  it('409 BATCH_IN_PUBLICATION_BUNDLE: uitleg in de open dialoog, geen herlaad', async () => {
    baselineResponse = () =>
      Promise.resolve(
        jsonResponse({ error: 'Batch 101 is in a bundle', code: 'BATCH_IN_PUBLICATION_BUNDLE' }, 409),
      );
    const { onChanged } = renderActions();
    const dialog = openBaseline();
    fill(dialog);
    fireEvent.click(confirm(dialog));

    expect(await within(dialog).findByText('Batch zit in een publicatiebundel')).toBeInTheDocument();
    expect(within(dialog).getByText(/BATCH_IN_PUBLICATION_BUNDLE · HTTP 409/)).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('409 BATCH_NOT_ACCEPTABLE wordt vertaald getoond', async () => {
    baselineResponse = () =>
      Promise.resolve(jsonResponse({ error: 'Batch 101 is in status BASELINE_ACCEPTED', code: 'BATCH_NOT_ACCEPTABLE' }, 409));
    renderActions();
    const dialog = openBaseline();
    fill(dialog);
    fireEvent.click(confirm(dialog));
    expect(await within(dialog).findByText('Batch kan niet aanvaard worden')).toBeInTheDocument();
  });

  it('dubbele klik levert één POST', async () => {
    let release: (r: Response) => void = () => {};
    baselineResponse = () => new Promise<Response>((resolve) => (release = resolve));
    renderActions();
    const dialog = openBaseline();
    fill(dialog);
    const button = confirm(dialog);
    fireEvent.click(button);
    fireEvent.click(button);
    fireEvent.submit(dialog);
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(posts()).toHaveLength(1);
    release(jsonResponse({ batchId: 101, status: 'BASELINE_ACCEPTED', acceptedBy: 'An', skippedMutationCount: 0 }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(posts()).toHaveLength(1);
  });

  it('bundel-opname: kies een bundel, typ de referentie, batch wordt toegevoegd en herladen', async () => {
    const { onChanged } = renderActions();
    fireEvent.click(screen.getByRole('button', { name: 'Opnemen in bundel' }));
    const dialog = screen.getByRole('dialog');
    const select = await within(dialog).findByRole('combobox');
    const submit = within(dialog).getByRole('button', { name: 'Opnemen in bundel' });
    expect(submit).toBeDisabled();

    fireEvent.change(select, { target: { value: '42' } });
    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    expect(submit).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText(/Typ "BND-1"/), { target: { value: 'BND-1' } });
    fireEvent.click(submit);

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(posts()).toHaveLength(1);
    expect(JSON.parse(String(posts()[0]![1]?.body))).toEqual({ batchIds: [101], addedBy: 'An Beslisser' });
    expect(onChanged).toHaveBeenCalledTimes(1);
  });
});
