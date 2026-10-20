/**
 * UI-SW — productwisselaar: zonder apps geen menu; met apps echte links in volgorde; Escape en klik buiten sluiten.
 * Gemodelleerd naar Prodis' `product-switcher.spec.tsx`. Geen fetches.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { ActorProvider } from '../actor/ActorContext';
import type { ProductApp } from '../api/me';
import { ProductSwitcher } from '../layout/ProductSwitcher';
import { TEST_IDENTITY } from './testIdentity';

const ALL: ProductApp[] = [
  { id: 'prodis', url: 'http://localhost:9060' },
  { id: 'profin', url: 'https://profin.example.com' },
  { id: 'prodev', url: 'https://prodev.example.com' },
];

function renderSwitcher(apps?: ProductApp[]) {
  const identity = apps === undefined ? { ...TEST_IDENTITY, apps: undefined } : { ...TEST_IDENTITY, apps };
  render(
    <ActorProvider identity={identity}>
      <ProductSwitcher />
    </ActorProvider>,
  );
}

const trigger = () => screen.getByRole('button', { name: 'Wissel van applicatie' });

describe('ProductSwitcher (UI-SW)', () => {
  afterEach(() => cleanup());

  it('toont zonder apps enkel de identiteit, zonder knop of menu', () => {
    renderSwitcher([]);
    expect(screen.getByText('CatalogImport')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Wissel van applicatie' })).not.toBeInTheDocument();
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('behandelt een ontbrekend apps-veld als geen apps', () => {
    renderSwitcher(undefined);
    expect(screen.queryByRole('button', { name: 'Wissel van applicatie' })).not.toBeInTheDocument();
  });

  it('toont met apps een gesloten knop die het menu opent met echte links in volgorde', () => {
    renderSwitcher(ALL);
    expect(trigger()).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    fireEvent.click(trigger());
    expect(trigger()).toHaveAttribute('aria-expanded', 'true');
    const items = within(screen.getByRole('menu')).getAllByRole('menuitem');
    expect(items.map((item) => item.getAttribute('href'))).toEqual([
      'http://localhost:9060',
      'https://profin.example.com',
      'https://prodev.example.com',
    ]);
    expect(items.map((item) => item.tagName)).toEqual(['A', 'A', 'A']);
    expect(items[0]).toHaveTextContent('Prodis');
    expect(items[0]).toHaveTextContent('ERP');
    expect(items[1]).toHaveTextContent('ProFin');
    expect(items[1]).toHaveTextContent('Boekhouding');
    expect(items[2]).toHaveTextContent('ProDev');
    expect(items[2]).toHaveTextContent('Productontwikkeling');
  });

  it('toont enkel de aangeboden apps', () => {
    renderSwitcher(ALL.slice(0, 1));
    fireEvent.click(trigger());
    expect(screen.getAllByRole('menuitem')).toHaveLength(1);
  });

  it('sluit met Escape en zet de focus terug op de knop', () => {
    renderSwitcher(ALL);
    fireEvent.click(trigger());
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger()).toHaveAttribute('aria-expanded', 'false');
    expect(trigger()).toHaveFocus();
  });

  it('sluit bij een klik buiten het menu, niet bij een klik erin', () => {
    renderSwitcher(ALL);
    fireEvent.click(trigger());
    fireEvent.mouseDown(screen.getByText('Wissel van applicatie', { selector: 'div' }));
    expect(screen.getByRole('menu')).toBeInTheDocument();
    fireEvent.mouseDown(document.body);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });
});
