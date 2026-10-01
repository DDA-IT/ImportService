/**
 * Stap 2 (b) — `useAction.execute` heeft een ref-guard: een tweede aanroep terwijl een actie loopt doet
 * niets (geeft `undefined`, `run` wordt niet nogmaals aangeroepen).
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';
import { useAction } from '../hooks/useAction';

afterEach(() => {
  cleanup();
});

describe('useAction', () => {
  it('twee snelle aanroepen in dezelfde tick leiden tot één run', async () => {
    let resolve!: (value: string) => void;
    const run = vi.fn(
      () =>
        new Promise<string>((r) => {
          resolve = r;
        }),
    );
    const { result } = renderHook(() => useAction(run));

    let first!: Promise<string | undefined>;
    let second!: Promise<string | undefined>;
    act(() => {
      first = result.current.execute();
      second = result.current.execute();
    });

    expect(run).toHaveBeenCalledTimes(1);
    expect(await second).toBeUndefined();

    await act(async () => {
      resolve('klaar');
      await first;
    });
    expect(await first).toBe('klaar');
    expect(result.current.pending).toBe(false);
  });

  it('na afronden kan opnieuw uitgevoerd worden, ook na een fout', async () => {
    const run = vi.fn().mockRejectedValueOnce(new Error('stuk')).mockResolvedValueOnce('ok');
    const { result } = renderHook(() => useAction(run));

    await act(async () => {
      expect(await result.current.execute()).toBeUndefined();
    });
    await act(async () => {
      expect(await result.current.execute()).toBe('ok');
    });
    expect(run).toHaveBeenCalledTimes(2);
  });
});
