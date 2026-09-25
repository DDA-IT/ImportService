/**
 * B-F3 — `continue` in `BatchActions`, zie `docs/decisions.md` 2026-09-23 (stap 9, V2).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { BatchDetail } from '../api/types';
import { BatchActions } from '../features/batches/BatchActions';

function batch(status: string): BatchDetail {
  return { batchId: 101, status } as unknown as BatchDetail;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

describe('BatchActions continue (B-F3)', () => {
  const originalFetch = global.fetch;
  let continueResponse: () => Promise<Response>;

  beforeEach(() => {
    continueResponse = () => Promise.resolve(jsonResponse({ batchId: 101, status: 'SCREENED' }));
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString();
      const method = init?.method ?? 'GET';
      if (method === 'POST' && url.endsWith('/batches/101/continue')) return continueResponse();
      return Promise.reject(new Error(`Onverwachte aanroep: ${method} ${url}`));
    }) as unknown as typeof fetch;
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    global.fetch = originalFetch;
  });

  function renderActions(status = 'MUTATING') {
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

  const posts = () => vi.mocked(global.fetch).mock.calls.filter((call) => call[1]?.method === 'POST');

  it('normaal: bevestiging vermeldt de naamloosheid, POST zonder body, batch wordt herladen', async () => {
    const { onChanged } = renderActions();
    fireEvent.click(screen.getByRole('button', { name: 'Batch hervatten' }));
    expect(screen.getByText(/niet op naam vastgelegd/)).toBeInTheDocument();
    expect(posts()).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: 'Hervatten bevestigen' }));
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(posts()).toHaveLength(1);
    const call = posts()[0]!;
    expect(call[0].toString()).toBe('/api/catalog-import/batches/101/continue');
    expect(call[1]?.body).toBeUndefined();
    expect(screen.getByRole('status').textContent).toContain('SCREENED');
  });

  it.each(['RECEIVED', 'SCREENED', 'BLOCKED', 'FAILED', 'BASELINE_ACCEPTED'])(
    'toont geen hervatknop bij status %s',
    (status) => {
      renderActions(status);
      expect(screen.queryByRole('button', { name: 'Batch hervatten' })).not.toBeInTheDocument();
    },
  );

  it('409 BATCH_NOT_RESUMABLE: vertaalde fout, geen herlaad', async () => {
    continueResponse = () =>
      Promise.resolve(jsonResponse({ error: 'Batch 101 is in status SCREENED', code: 'BATCH_NOT_RESUMABLE' }, 409));
    const { onChanged } = renderActions();
    fireEvent.click(screen.getByRole('button', { name: 'Batch hervatten' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hervatten bevestigen' }));

    expect(await screen.findByText('Batch kan niet hervat worden')).toBeInTheDocument();
    expect(screen.getByText(/BATCH_NOT_RESUMABLE · HTTP 409/)).toBeInTheDocument();
    expect(onChanged).not.toHaveBeenCalled();
  });

  it('dubbele klik levert één POST', async () => {
    let release: (r: Response) => void = () => {};
    continueResponse = () => new Promise<Response>((resolve) => (release = resolve));
    const { onChanged } = renderActions();
    fireEvent.click(screen.getByRole('button', { name: 'Batch hervatten' }));
    const button = screen.getByRole('button', { name: 'Hervatten bevestigen' });
    fireEvent.click(button);
    fireEvent.click(button);
    await waitFor(() => expect(posts()).toHaveLength(1));
    expect(button).toBeDisabled();
    release(jsonResponse({ batchId: 101, status: 'SCREENED' }));
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(posts()).toHaveLength(1);
  });
});
