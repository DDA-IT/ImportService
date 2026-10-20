/**
 * Licht/donker thema (docs/decisions.md, 2026-10-20). `data-theme` op <html> overschrijft de systeemvoorkeur
 * (zie styles/tokens.css); zonder opgeslagen keuze blijft het attribuut weg en volgt de pagina het systeem.
 */

export type Theme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'catalogimport.theme';

export function readStoredTheme(): Theme | null {
  try {
    const value = window.localStorage.getItem(THEME_STORAGE_KEY);
    return value === 'light' || value === 'dark' ? value : null;
  } catch {
    return null;
  }
}

function writeStoredTheme(theme: Theme) {
  try {
    window.localStorage.setItem(THEME_STORAGE_KEY, theme);
  } catch {
    // opslag geblokkeerd: de keuze geldt enkel voor deze sessie
  }
}

function systemPrefersDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/** Het thema dat nu werkelijk actief is: expliciete keuze op <html>, anders de systeemvoorkeur. */
export function currentTheme(): Theme {
  const attr = document.documentElement.getAttribute('data-theme');
  if (attr === 'light' || attr === 'dark') return attr;
  return systemPrefersDark() ? 'dark' : 'light';
}

/** Past een eventueel opgeslagen keuze toe; aanroepen vóór de eerste render om flitsen te vermijden. */
export function initTheme() {
  const stored = readStoredTheme();
  if (stored !== null) document.documentElement.setAttribute('data-theme', stored);
}

/** Zet het thema expliciet, onthoudt de keuze en geeft het nieuwe thema terug. */
export function setTheme(theme: Theme): Theme {
  document.documentElement.setAttribute('data-theme', theme);
  writeStoredTheme(theme);
  return theme;
}
