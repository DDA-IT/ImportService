/**
 * UI-3 — app-shell: inklapbare menugroepen, mini-modus (onthouden in localStorage), drawer sluit op Escape,
 * en "Nieuwe leverancier" enkel met het recht Beheren. Geen fetches in de shell.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import App from '../App';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';

function renderShell(identity: ActorIdentity = TEST_IDENTITY) {
  render(
    <ActorProvider identity={identity}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<App />}>
            <Route path="/" element={<div data-testid="page" />} />
            <Route path="/upload" element={<div data-testid="upload" />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

describe('app-shell (UI-3)', () => {
  beforeEach(() => {
    window.localStorage.clear();
    global.fetch = vi.fn(() => Promise.reject(new Error('geen fetch verwacht'))) as unknown as typeof fetch;
  });
  afterEach(() => cleanup());

  it('toont de vier groepen met hun items en markeert het actieve item', () => {
    renderShell();
    expect(screen.getByRole('button', { name: 'Inlezen' })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('link', { name: 'Inlezingen' })).toHaveAttribute('aria-current', 'page');
    for (const name of ['Afwijkingen', 'Bestand uploaden', 'Publicaties', 'Leveranciers', 'Leverancier toevoegen', 'Importdefinities']) {
      expect(screen.getByRole('link', { name })).toBeInTheDocument();
    }
  });

  it('klapt een groep in en uit via de subkop (aria-expanded)', () => {
    renderShell();
    const sub = screen.getByRole('button', { name: 'Publiceren' });
    fireEvent.click(sub);
    expect(sub).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(sub);
    expect(sub).toHaveAttribute('aria-expanded', 'true');
  });

  it('verbergt "Leverancier toevoegen" zonder recht Beheren', () => {
    renderShell(testIdentityWith('catalogImport.read'));
    expect(screen.queryByRole('link', { name: 'Leverancier toevoegen' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Importdefinities' })).toBeInTheDocument();
  });

  it('wisselt de mini-modus en onthoudt de keuze', () => {
    renderShell();
    fireEvent.click(screen.getByRole('button', { name: 'Menu inklappen' }));
    expect(screen.getByRole('button', { name: 'Menu uitklappen' })).toBeInTheDocument();
    expect(window.localStorage.getItem('catalogimport.nav.mini')).toBe('1');
    cleanup();
    renderShell();
    expect(screen.getByRole('button', { name: 'Menu uitklappen' })).toBeInTheDocument();
  });

  it('opent de drawer met de hamburgerknop en sluit op Escape, backdrop en navigatie', () => {
    renderShell();
    const burger = screen.getByRole('button', { name: 'Menu openen of sluiten' });
    fireEvent.click(burger);
    expect(burger).toHaveAttribute('aria-expanded', 'true');
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(burger).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(burger);
    fireEvent.click(screen.getByTestId('nav-backdrop'));
    expect(burger).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(burger);
    fireEvent.click(screen.getByRole('link', { name: 'Bestand uploaden' }));
    expect(burger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('upload')).toBeInTheDocument();
  });

  it('start geen fetches', () => {
    renderShell();
    expect(vi.mocked(global.fetch)).not.toHaveBeenCalled();
  });
});
