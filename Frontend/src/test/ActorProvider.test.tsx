/**
 * 5A-3 — `ActorProvider`/`useActor` op basis van `GET /me`, 401-afhandeling zonder lus, sessie verlopen
 * tijdens een actie, logout. Zie `docs/design/fase5-auth-design.md` §5-§6.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ActorProvider, LOGIN_ENTRY, useActor } from '../actor/ActorContext';
import { ActorBar } from '../actor/ActorBar';
import { useState } from 'react';
import { ApiError, request } from '../api/http';
import { ErrorBanner } from '../errors/ErrorBanner';

const ME = { username: 'jan.peeters', subject: 'sub-123', displayName: 'Jan Peeters', permissions: ['catalogImport.manage', 'catalogImport.read'] };

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function Probe() {
  const { actor, subject, displayName } = useActor();
  return <p data-testid="probe">{`${actor}|${subject}|${displayName}`}</p>;
}

describe('ActorProvider (GET /me)', () => {
  const originalFetch = global.fetch;
  const originalLocation = window.location;
  let assign: ReturnType<typeof vi.fn>;

  function stubLocation(pathname: string, search = '') {
    assign = vi.fn();
    Object.defineProperty(window, 'location', { configurable: true, value: { pathname, search, assign } });
  }

  function mockFetch(handler: (url: string, init?: RequestInit) => Promise<Response>) {
    global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
      handler(input.toString(), init),
    ) as unknown as typeof fetch;
  }

  beforeEach(() => {
    sessionStorage.clear();
    stubLocation('/');
  });

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    global.fetch = originalFetch;
    Object.defineProperty(window, 'location', { configurable: true, value: originalLocation });
  });

  it('laden: toont "Aanmelden…" en daarna de kinderen met de identiteit uit /me', async () => {
    mockFetch(() => Promise.resolve(json(ME)));
    render(
      <ActorProvider>
        <Probe />
      </ActorProvider>,
    );

    expect(screen.getByText('Aanmelden…')).toBeInTheDocument();
    expect(await screen.findByTestId('probe')).toHaveTextContent('jan.peeters|sub-123|Jan Peeters');
    expect(vi.mocked(global.fetch).mock.calls[0]![0].toString()).toBe('/api/catalog-import/me');
    expect(assign).not.toHaveBeenCalled();
  });

  it('wist bij opstart een oude naam uit sessionStorage', async () => {
    sessionStorage.setItem('catalogimport.actor', 'Oude Naam');
    mockFetch(() => Promise.resolve(json(ME)));
    render(
      <ActorProvider>
        <Probe />
      </ActorProvider>,
    );
    await screen.findByTestId('probe');
    expect(sessionStorage.getItem('catalogimport.actor')).toBeNull();
  });

  it('401 op /me: bewaart enkel het pad en navigeert (volledige pagina) naar de login-entry', async () => {
    stubLocation('/bundles/7', '?tab=mutations');
    mockFetch(() => Promise.resolve(json({ error: 'Authentication required', code: 'AUTHENTICATION_REQUIRED' }, 401)));
    render(
      <ActorProvider>
        <Probe />
      </ActorProvider>,
    );

    await waitFor(() => expect(assign).toHaveBeenCalledWith(LOGIN_ENTRY));
    expect(LOGIN_ENTRY).toBe('/oauth2/authorization/keycloak');
    expect(sessionStorage.getItem('catalogimport.returnTo')).toBe('/bundles/7?tab=mutations');
    expect(screen.queryByTestId('probe')).not.toBeInTheDocument();
  });

  it('geen lus: een tweede 401 na een login-poging toont een foutvlak met code, zonder nieuwe redirect', async () => {
    sessionStorage.setItem('catalogimport.loginAttempt', '1');
    mockFetch(() => Promise.resolve(json({ error: 'Authentication required', code: 'AUTHENTICATION_REQUIRED' }, 401)));
    render(
      <ActorProvider>
        <Probe />
      </ActorProvider>,
    );

    expect(await screen.findByText('Aanmelden vereist')).toBeInTheDocument();
    expect(screen.getByText(/AUTHENTICATION_REQUIRED · HTTP 401 · \/me/)).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
    expect(vi.mocked(global.fetch)).toHaveBeenCalledTimes(1);

    // De gebruiker kiest zelf opnieuw aanmelden.
    fireEvent.click(screen.getByRole('button', { name: 'Opnieuw aanmelden' }));
    expect(assign).toHaveBeenCalledWith(LOGIN_ENTRY);
  });

  it('fout (403 ACTOR_IDENTITY_INVALID): volledig foutvlak mét code, geen redirect', async () => {
    mockFetch(() => Promise.resolve(json({ error: 'bad identity', code: 'ACTOR_IDENTITY_INVALID' }, 403)));
    render(
      <ActorProvider>
        <Probe />
      </ActorProvider>,
    );

    expect(await screen.findByText('Aangemelde identiteit onbruikbaar')).toBeInTheDocument();
    expect(screen.getByText(/ACTOR_IDENTITY_INVALID · HTTP 403/)).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
  });

  it('fout (500 / netwerk): foutvlak, geen redirect', async () => {
    mockFetch(() => Promise.reject(new TypeError('network down')));
    render(
      <ActorProvider>
        <Probe />
      </ActorProvider>,
    );

    expect(await screen.findByText('Geen verbinding met de server')).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
  });

  it('herstelt na de login het pad uit returnTo wanneer we op / landen, en wist het', async () => {
    sessionStorage.setItem('catalogimport.returnTo', '/bundles/3?tab=batches');
    sessionStorage.setItem('catalogimport.loginAttempt', '1');
    mockFetch(() => Promise.resolve(json(ME)));
    const onRestorePath = vi.fn();
    render(
      <ActorProvider onRestorePath={onRestorePath}>
        <Probe />
      </ActorProvider>,
    );

    await screen.findByTestId('probe');
    expect(onRestorePath).toHaveBeenCalledWith('/bundles/3?tab=batches');
    expect(sessionStorage.getItem('catalogimport.returnTo')).toBeNull();
    expect(sessionStorage.getItem('catalogimport.loginAttempt')).toBeNull();
  });

  it('herstelt nooit een extern pad (//host)', async () => {
    sessionStorage.setItem('catalogimport.returnTo', '//evil.example/x');
    mockFetch(() => Promise.resolve(json(ME)));
    const onRestorePath = vi.fn();
    render(
      <ActorProvider onRestorePath={onRestorePath}>
        <Probe />
      </ActorProvider>,
    );

    await screen.findByTestId('probe');
    expect(onRestorePath).not.toHaveBeenCalled();
  });

  it('testnaad identity: geen /me-fetch', () => {
    mockFetch(() => Promise.reject(new Error('mag niet aangeroepen worden')));
    render(
      <ActorProvider identity={{ username: 'x', subject: 's', displayName: null, permissions: [] }}>
        <Probe />
      </ActorProvider>,
    );
    expect(screen.getByTestId('probe')).toHaveTextContent('x|s|null');
    expect(vi.mocked(global.fetch)).not.toHaveBeenCalled();
  });

  it('ActorBar toont "Aangemeld als weergavenaam (username)"', () => {
    render(
      <ActorProvider identity={ME}>
        <ActorBar />
      </ActorProvider>,
    );
    expect(screen.getByText('Jan Peeters (jan.peeters)')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('401 tijdens een actie: sessie-verlopen-melding, geen automatische redirect, knop Opnieuw aanmelden', async () => {
    mockFetch(() => Promise.resolve(json({ error: 'Authentication required', code: 'AUTHENTICATION_REQUIRED' }, 401)));
    render(
      <ActorProvider identity={ME}>
        <ActorBar />
      </ActorProvider>,
    );
    expect(screen.queryByText(/Uw sessie is verlopen/)).not.toBeInTheDocument();

    await expect(request('/bundles/1/freeze', { method: 'POST' })).rejects.toMatchObject({ status: 401 });

    expect(await screen.findByText(/Uw sessie is verlopen/)).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Opnieuw aanmelden' }));
    expect(assign).toHaveBeenCalledWith(LOGIN_ENTRY);
  });

  it('can(): enkel lidmaatschap van de meegeleverde effectieve set; permissions is beschikbaar', () => {
    function CanProbe() {
      const { can, permissions } = useActor();
      return (
        <p data-testid="can">
          {`${can('catalogImport.read')}|${can('catalogImport.manage')}|${can('catalogImport.approve')}|${permissions.join(',')}`}
        </p>
      );
    }
    render(
      <ActorProvider identity={ME}>
        <CanProbe />
      </ActorProvider>,
    );
    // Geen eigen hiërarchie: manage staat in de set, approve niet, dus geen approve (ook al "past" het niet).
    expect(screen.getByTestId('can')).toHaveTextContent('true|true|false|catalogImport.manage,catalogImport.read');
  });

  it('/me met permissions [] geeft can() = false voor alles', async () => {
    mockFetch(() => Promise.resolve(json({ ...ME, permissions: [] })));
    function CanProbe() {
      const { can } = useActor();
      return <p data-testid="can">{`${can('catalogImport.read')}`}</p>;
    }
    render(
      <ActorProvider>
        <CanProbe />
      </ActorProvider>,
    );
    expect(await screen.findByTestId('can')).toHaveTextContent('false');
  });

  it('503 PERMISSION_SOURCE_UNAVAILABLE op /me: foutvlak met code, NIET het geen-rechtenvlak; Opnieuw proberen laadt opnieuw', async () => {
    let calls = 0;
    mockFetch(() => {
      calls += 1;
      return Promise.resolve(
        calls === 1
          ? json({ error: 'Permission source unavailable', code: 'PERMISSION_SOURCE_UNAVAILABLE' }, 503)
          : json(ME),
      );
    });
    render(
      <ActorProvider>
        <Probe />
      </ActorProvider>,
    );

    expect(await screen.findByText('Rechten tijdelijk niet beschikbaar')).toBeInTheDocument();
    expect(screen.getByText(/PERMISSION_SOURCE_UNAVAILABLE · HTTP 503 · \/me/)).toBeInTheDocument();
    expect(screen.queryByText('U heeft geen rechten voor CatalogImport.')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Opnieuw aanmelden' })).not.toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Opnieuw proberen' }));
    expect(await screen.findByTestId('probe')).toHaveTextContent('jan.peeters');
    expect(calls).toBe(2);
  });

  it('403 PERMISSION_DENIED tijdens een actie: toont de code en herlaadt /me precies één keer; UI-spiegel wordt bijgewerkt', async () => {
    let meCalls = 0;
    mockFetch((url) => {
      if (url.endsWith('/me')) {
        meCalls += 1;
        return Promise.resolve(json({ ...ME, permissions: ['catalogImport.read'] }));
      }
      return Promise.resolve(
        json({ error: 'Missing permission catalogImport.manage', code: 'PERMISSION_DENIED' }, 403),
      );
    });
    function Denied() {
      const { can } = useActor();
      const [error, setError] = useState<ApiError | null>(null);
      return (
        <div>
          <p data-testid="manage">{String(can('catalogImport.manage'))}</p>
          <button
            type="button"
            onClick={() => {
              // Twee gelijktijdige geweigerde acties geven nog steeds één herlaad.
              void Promise.allSettled([
                request('/bundles', { method: 'POST' }),
                request('/bundles', { method: 'POST' }).catch((e: unknown) => setError(e as ApiError)),
              ]);
            }}
          >
            Actie
          </button>
          {error !== null && <ErrorBanner error={error} />}
        </div>
      );
    }
    render(
      <ActorProvider identity={ME}>
        <Denied />
      </ActorProvider>,
    );
    expect(screen.getByTestId('manage')).toHaveTextContent('true');

    fireEvent.click(screen.getByRole('button', { name: 'Actie' }));

    expect(await screen.findByText(/PERMISSION_DENIED · HTTP 403 · \/bundles/)).toBeInTheDocument();
    expect(screen.getByText('Recht ontbreekt')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('manage')).toHaveTextContent('false'));
    expect(meCalls).toBe(1);
  });

  it('logout: POST /logout en daarna volledige navigatie naar de logoutUrl', async () => {
    mockFetch(() => Promise.resolve(json({ logoutUrl: 'http://kc/logout?id_token_hint=abc' })));
    render(
      <ActorProvider identity={ME}>
        <ActorBar />
      </ActorProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Afmelden' }));

    await waitFor(() => expect(assign).toHaveBeenCalledWith('http://kc/logout?id_token_hint=abc'));
    const [url, init] = vi.mocked(global.fetch).mock.calls[0]!;
    expect(url.toString()).toBe('/api/catalog-import/logout');
    expect(init?.method).toBe('POST');
  });
});
