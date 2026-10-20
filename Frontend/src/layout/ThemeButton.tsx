import { useState } from 'react';
import { MoonIcon, SunIcon } from '../components/icons.tsx';
import { currentTheme, setTheme, type Theme } from '../theme/theme.ts';
import styles from './ThemeButton.module.css';

/** Themaknop in de header (Prodis-mock `#themeBtn`): wisselt licht/donker en onthoudt de keuze. */
export function ThemeButton() {
  const [theme, setThemeState] = useState<Theme>(currentTheme);
  const dark = theme === 'dark';
  return (
    <button
      type="button"
      className={styles.themeButton}
      aria-label="Licht of donker thema wisselen"
      aria-pressed={dark}
      onClick={() => setThemeState(setTheme(dark ? 'light' : 'dark'))}
    >
      {dark ? <SunIcon size={22} /> : <MoonIcon size={22} />}
    </button>
  );
}
