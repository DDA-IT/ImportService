/**
 * UI-14 — themaknop: wisselt `data-theme` op <html>, onthoudt de keuze in localStorage en
 * `initTheme` past een opgeslagen keuze toe (zonder keuze volgt de pagina het systeem).
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ThemeButton } from '../layout/ThemeButton';
import { THEME_STORAGE_KEY, initTheme, readStoredTheme } from '../theme/theme';

const root = document.documentElement;

describe('themaknop (UI-14)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    root.removeAttribute('data-theme');
  });
  afterEach(() => {
    cleanup();
    root.removeAttribute('data-theme');
  });

  it('wisselt data-theme en bewaart de keuze', () => {
    render(<ThemeButton />);
    const button = screen.getByRole('button', { name: 'Licht of donker thema wisselen' });
    expect(button).toHaveAttribute('aria-pressed', 'false');

    fireEvent.click(button);
    expect(root.getAttribute('data-theme')).toBe('dark');
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(button).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(button);
    expect(root.getAttribute('data-theme')).toBe('light');
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
    expect(button).toHaveAttribute('aria-pressed', 'false');
  });

  it('initTheme past de opgeslagen keuze toe', () => {
    window.localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    initTheme();
    expect(root.getAttribute('data-theme')).toBe('dark');
    render(<ThemeButton />);
    expect(screen.getByRole('button', { name: 'Licht of donker thema wisselen' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('initTheme laat het systeem beslissen zonder (geldige) opgeslagen keuze', () => {
    initTheme();
    expect(root.hasAttribute('data-theme')).toBe(false);
    window.localStorage.setItem(THEME_STORAGE_KEY, 'paars');
    expect(readStoredTheme()).toBeNull();
    initTheme();
    expect(root.hasAttribute('data-theme')).toBe(false);
  });
});
