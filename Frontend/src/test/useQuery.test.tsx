/**
 * Stap 2 (a) — `useQuery` zet `data` op `null` bij een sleutelwissel, en behoudt `data` bij een
 * `reload()` met dezelfde sleutel (docs/decisions.md "Analyse-opvolging stap 2").
 */

import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { useQuery } from '../hooks/useQuery';

afterEach(() => {
  cleanup();
});

type Deferred = { promise: Promise<string>; resolve: (value: string) => void };

function deferred(): Deferred {
  let resolve!: (value: string) => void;
  const promise = new Promise<string>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe('useQuery', () => {
  it('sleutelwissel: data is null tot het antwoord voor de nieuwe sleutel binnen is', async () => {
    const pending: Record<string, Deferred> = { a: deferred(), b: deferred() };
    const { result, rerender } = renderHook(({ k }) => useQuery(k, () => pending[k]!.promise), {
      initialProps: { k: 'a' },
    });

    await act(async () => {
      pending.a!.resolve('antwoord-a');
    });
    await waitFor(() => expect(result.current.data).toBe('antwoord-a'));

    rerender({ k: 'b' });
    // Meteen na de wissel: nooit nog de data van de vorige sleutel.
    expect(result.current.data).toBeNull();

    await act(async () => {
      pending.b!.resolve('antwoord-b');
    });
    await waitFor(() => expect(result.current.data).toBe('antwoord-b'));
  });

  it('reload met dezelfde sleutel behoudt data tijdens het herladen', async () => {
    let calls = 0;
    const second = deferred();
    const { result } = renderHook(() =>
      useQuery('zelfde', () => {
        calls += 1;
        return calls === 1 ? Promise.resolve('eerste') : second.promise;
      }),
    );
    await waitFor(() => expect(result.current.data).toBe('eerste'));

    act(() => {
      result.current.reload();
    });
    await waitFor(() => expect(calls).toBe(2));
    expect(result.current.data).toBe('eerste');
    expect(result.current.loading).toBe(true);

    await act(async () => {
      second.resolve('tweede');
    });
    await waitFor(() => expect(result.current.data).toBe('tweede'));
  });

  it('A -> B -> A: het oude A-resultaat komt niet terug tijdens het herladen', async () => {
    const calls: Deferred[] = [];
    const { result, rerender } = renderHook(({ k }) =>
      useQuery(k, () => {
        const d = deferred();
        calls.push(d);
        return d.promise;
      }), { initialProps: { k: 'a' } });

    await act(async () => {
      calls[0]!.resolve('a1');
    });
    await waitFor(() => expect(result.current.data).toBe('a1'));

    rerender({ k: 'b' });
    rerender({ k: 'a' });
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(result.current.data).toBeNull();
  });
});
