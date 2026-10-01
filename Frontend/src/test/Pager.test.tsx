/**
 * Stap 2 (d) — `Pager` gebruikt `useId()`: twee Pagers op één pagina hebben unieke ids en elk label
 * koppelt aan de eigen select.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Pager } from '../components/Pager';

afterEach(() => {
  cleanup();
});

describe('Pager', () => {
  it('twee Pagers: unieke ids, label koppelt aan eigen select', () => {
    const onFirst = vi.fn();
    const onSecond = vi.fn();
    render(
      <>
        <Pager page={0} size={25} totalElements={100} onPageChange={vi.fn()} onSizeChange={onFirst} />
        <Pager page={0} size={50} totalElements={100} onPageChange={vi.fn()} onSizeChange={onSecond} />
      </>,
    );

    const selects = screen.getAllByLabelText('Per pagina') as HTMLSelectElement[];
    expect(selects).toHaveLength(2);
    expect(selects[0].id).not.toBe(selects[1].id);
    expect(selects[0].value).toBe('25');
    expect(selects[1].value).toBe('50');

    fireEvent.change(selects[1], { target: { value: '100' } });
    expect(onSecond).toHaveBeenCalledWith(100);
    expect(onFirst).not.toHaveBeenCalled();
  });
});
