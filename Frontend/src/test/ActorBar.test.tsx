/** UI-4 — ActorBar als accountmenu: knop, popover (rechten in leesbare labels), sluiten, afmelden. */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActorProvider } from '../actor/ActorContext';
import { ActorBar } from '../actor/ActorBar';
import { request } from '../api/http';

const ME = {
  username: 'jan.peeters',
  subject: 's',
  displayName: 'Jan Peeters',
  permissions: ['catalogImport.read', 'catalogImport.manage'],
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function renderBar(identity: typeof ME = ME) {
  return render(
    <div>
      <p>buiten</p>
      <ActorProvider identity={identity}>
        <ActorBar />
      </ActorProvider>
    </div>,
  );
}

describe('ActorBar accountmenu', () => {
  const originalFetch = global.fetch;
  const originalLocation = window.location;
  beforeEach(() => {
    global.fetch = vi.fn() as unknown as typeof fetch;
  });
  afterEach(() => {
    cleanup();
    global.fetch = originalFetch;
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  });

  it('knop: initialen, toegankelijke naam, aria-haspopup, aria-expanded; popover dicht; geen fetch', () => {
    renderBar();
    const trigger = screen.getByRole('button', { name: 'Jan Peeters (jan.peeters)' });
    expect(trigger).toHaveAttribute('aria-haspopup', 'true');
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByText('JP')).toBeInTheDocument();
    expect(screen.queryByText('Mijn rechten')).not.toBeInTheDocument();
    expect(vi.mocked(global.fetch)).not.toHaveBeenCalled();
  });

  it('zonder weergavenaam: initialen uit de login, login niet dubbel getoond', () => {
    renderBar({ ...ME, displayName: '' });
    expect(screen.getByText('JP')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'jan.peeters' }));
    // sr-only tekst + naam in de knop (aria-hidden) + naam in de popover; geen aparte loginregel
    expect(screen.getAllByText('jan.peeters')).toHaveLength(3);
  });

  it('openen toont naam, login en rechten als leesbare labels (geen ruwe codes)', () => {
    renderBar();
    const trigger = screen.getByRole('button', { name: 'Jan Peeters (jan.peeters)' });
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('Mijn rechten')).toBeInTheDocument();
    expect(screen.getByText('Lezen')).toBeInTheDocument();
    expect(screen.getByText('Beheren')).toBeInTheDocument();
    expect(screen.queryByText('Goedkeuren')).not.toBeInTheDocument();
    expect(screen.queryByText(/catalogImport\./)).not.toBeInTheDocument();
  });

  it('sluit op klik buiten, niet op klik binnen', () => {
    renderBar();
    fireEvent.click(screen.getByRole('button', { name: 'Jan Peeters (jan.peeters)' }));
    fireEvent.mouseDown(screen.getByText('Mijn rechten'));
    expect(screen.getByText('Mijn rechten')).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByText('buiten'));
    expect(screen.queryByText('Mijn rechten')).not.toBeInTheDocument();
  });

  it('Escape sluit en zet de focus terug op de knop', () => {
    renderBar();
    const trigger = screen.getByRole('button', { name: 'Jan Peeters (jan.peeters)' });
    fireEvent.click(trigger);
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByText('Mijn rechten')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
  });

  it('Afmelden sluit het menu en roept de afmeldlogica aan', async () => {
    const assign = vi.fn();
    Object.defineProperty(window, 'location', { configurable: true, value: { pathname: '/', search: '', assign } });
    global.fetch = vi.fn(() => Promise.resolve(json({ logoutUrl: 'http://kc/out' }))) as unknown as typeof fetch;
    renderBar();
    fireEvent.click(screen.getByRole('button', { name: 'Jan Peeters (jan.peeters)' }));
    fireEvent.click(screen.getByRole('button', { name: 'Afmelden' }));
    expect(screen.queryByText('Mijn rechten')).not.toBeInTheDocument();
    await waitFor(() => expect(assign).toHaveBeenCalledWith('http://kc/out'));
  });

  it('sessie-verlopen-melding staat buiten de popover (menu dicht)', async () => {
    global.fetch = vi.fn(() =>
      Promise.resolve(json({ error: 'x', code: 'AUTHENTICATION_REQUIRED' }, 401)),
    ) as unknown as typeof fetch;
    renderBar();
    await expect(request('/x', { method: 'POST' })).rejects.toMatchObject({ status: 401 });
    expect(await screen.findByText(/Uw sessie is verlopen/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Opnieuw aanmelden' })).toBeInTheDocument();
    expect(screen.queryByText('Mijn rechten')).not.toBeInTheDocument();
  });
});
