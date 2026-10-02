/**
 * Wie tekent er: de geverifieerde identiteit uit `GET /me` (Fase 5-AUTH, BFF-login via Keycloak). Zie
 * `docs/design/fase5-auth-design.md` §5-§6.
 *
 * - Geen `sessionStorage`-naam en geen handmatig naamveld meer; de naam komt altijd van de server.
 *   Een oude waarde onder `catalogimport.actor` wordt bij opstart eenmalig gewist.
 * - Toestanden: laden, aangemeld, fout (volledig foutvlak mét code). 401 op `/me` bij opstart =
 *   volledige pagina-navigatie naar de login-entry, met het huidige pad in
 *   `sessionStorage['catalogimport.returnTo']` (enkel een pad, geen identiteit).
 * - Geen lus: vóór de redirect wordt een vlag gezet; komt `/me` daarna opnieuw met 401 terug, dan volgt
 *   een foutvlak met knop "Opnieuw aanmelden" in plaats van nogmaals te redirecten.
 * - 401 tijdens een actie = `sessionExpired` (geen automatische redirect: getypte redenen mogen niet
 *   verloren gaan); `ActorBar` toont de melding en de knop "Opnieuw aanmelden".
 * - `identity` is een testnaad: slaat de fetch over.
 */

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ApiError, setPermissionDeniedHandler, setUnauthenticatedHandler } from '../api/http';
import * as meApi from '../api/me';
import type { Permission } from '../api/types';
import { ErrorBanner } from '../errors/ErrorBanner';
import button from '../components/Button.module.css';

const OLD_ACTOR_KEY = 'catalogimport.actor';
const RETURN_TO_KEY = 'catalogimport.returnTo';
const LOGIN_ATTEMPT_KEY = 'catalogimport.loginAttempt';
export const LOGIN_ENTRY = '/oauth2/authorization/keycloak';

export type ActorIdentity = {
  username: string;
  subject?: string | null;
  displayName?: string | null;
  /** De EFFECTIEVE rechtcodes uit `/me` (de backend past de hiërarchie toe; `[]` = geen rechten). */
  permissions: readonly string[];
};

export type ActorContextValue = {
  /** De geverifieerde gebruikersnaam (`username` uit `/me`). */
  actor: string;
  subject: string | null;
  displayName: string | null;
  /** De effectieve rechtcodes zoals `/me` ze leverde (een UI-spiegel; de server blijft de waarheid). */
  permissions: readonly string[];
  /** Enkel een lidmaatschapstest op `permissions`: de UI houdt geen eigen rechtenhiërarchie bij. */
  can: (permission: Permission) => boolean;
  /** `true` zodra een actie een 401 kreeg: de sessie is verlopen. */
  sessionExpired: boolean;
  /** Volledige pagina-navigatie naar de login-entry (bewaart het huidige pad als `returnTo`). */
  reauthenticate: () => void;
  /** `POST /logout`, dan navigatie naar de `logoutUrl` van Keycloak. */
  logout: () => Promise<void>;
};

function safeStorage<T>(fn: () => T, fallback: T): T {
  try {
    return fn();
  } catch {
    // sessionStorage kan geweigerd zijn (privémodus); de flow blijft dan werken zonder returnTo/vlag.
    return fallback;
  }
}

function goToLogin(): void {
  safeStorage(() => {
    sessionStorage.setItem(RETURN_TO_KEY, window.location.pathname + window.location.search);
    sessionStorage.setItem(LOGIN_ATTEMPT_KEY, '1');
  }, undefined);
  window.location.assign(LOGIN_ENTRY);
}

/** Een terug te zetten pad moet een lokaal pad zijn (geen `//host`, geen absolute URL). */
function isLocalPath(path: string): boolean {
  return path.startsWith('/') && !path.startsWith('//') && !path.startsWith('/\\');
}

const ActorContext = createContext<ActorContextValue | null>(null);

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; identity: ActorIdentity }
  | { status: 'error'; error: ApiError };

export type ActorProviderProps = {
  children: ReactNode;
  /** Testnaad: een vaste identiteit; `/me` wordt dan niet opgehaald. */
  identity?: ActorIdentity;
  /** Herstelt na de login het pad uit `returnTo` (alleen aangeroepen als we op `/` landen). */
  onRestorePath?: (path: string) => void;
};

export function ActorProvider({ children, identity, onRestorePath }: ActorProviderProps) {
  const [state, setState] = useState<LoadState>(
    identity !== undefined ? { status: 'ready', identity } : { status: 'loading' },
  );
  const [sessionExpired, setSessionExpired] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const reloading = useRef(false);

  /**
   * Na een 403 `PERMISSION_DENIED`: `/me` opnieuw laden zodat de UI-spiegel bijwerkt. Eén herlaad tegelijk;
   * een mislukte herlaad laat de bestaande stand staan (de server blijft bij elke actie de waarheid).
   */
  const reloadMe = useCallback(() => {
    if (reloading.current) {
      return;
    }
    reloading.current = true;
    meApi
      .getMe()
      .then((me) => setState((current) => (current.status === 'ready' ? { status: 'ready', identity: me } : current)))
      .catch(() => undefined)
      .finally(() => {
        reloading.current = false;
      });
  }, []);

  useEffect(() => {
    setPermissionDeniedHandler(reloadMe);
    return () => setPermissionDeniedHandler(null);
  }, [reloadMe]);

  useEffect(() => {
    safeStorage(() => sessionStorage.removeItem(OLD_ACTOR_KEY), undefined);
  }, []);

  useEffect(() => {
    setUnauthenticatedHandler(() => setSessionExpired(true));
    return () => setUnauthenticatedHandler(null);
  }, []);

  useEffect(() => {
    if (identity !== undefined) {
      return;
    }
    const controller = new AbortController();
    meApi
      .getMe(controller.signal)
      .then((me) => {
        if (controller.signal.aborted) {
          return;
        }
        safeStorage(() => sessionStorage.removeItem(LOGIN_ATTEMPT_KEY), undefined);
        const returnTo = safeStorage(() => sessionStorage.getItem(RETURN_TO_KEY), null);
        if (returnTo !== null) {
          safeStorage(() => sessionStorage.removeItem(RETURN_TO_KEY), undefined);
          if (onRestorePath !== undefined && window.location.pathname === '/' && isLocalPath(returnTo)) {
            onRestorePath(returnTo);
          }
        }
        setState({ status: 'ready', identity: me });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        const apiError = error instanceof ApiError ? error : new ApiError(0, null, null, '/me');
        if (apiError.status === 401) {
          const alreadyTried = safeStorage(() => sessionStorage.getItem(LOGIN_ATTEMPT_KEY) !== null, false);
          if (!alreadyTried) {
            goToLogin();
            return; // de pagina wordt verlaten; blijf in "Aanmelden…"
          }
        }
        setState({ status: 'error', error: apiError });
      });
    return () => controller.abort();
    // oxlint-disable-next-line react/exhaustive-deps -- enkel bij opstart (en "Opnieuw proberen") laden; `identity` en `onRestorePath` ontbreken bewust
  }, [attempt]);

  const reauthenticate = useCallback(() => {
    goToLogin();
  }, []);

  const logout = useCallback(async () => {
    const { logoutUrl } = await meApi.logout();
    window.location.assign(logoutUrl);
  }, []);

  const value = useMemo<ActorContextValue | null>(() => {
    if (state.status !== 'ready') {
      return null;
    }
    const permissions = state.identity.permissions;
    return {
      actor: state.identity.username,
      subject: state.identity.subject ?? null,
      displayName: state.identity.displayName ?? null,
      permissions,
      can: (permission: Permission) => permissions.includes(permission),
      sessionExpired,
      reauthenticate,
      logout,
    };
  }, [state, sessionExpired, reauthenticate, logout]);

  if (state.status === 'loading') {
    return <p role="status">Aanmelden…</p>;
  }
  if (state.status === 'error') {
    // Rechtenbron stuk is iets anders dan "geen rechten": een foutvlak met opnieuw proberen, nooit het
    // geen-rechtenvlak.
    if (state.error.status === 503) {
      return (
        <div>
          <ErrorBanner error={state.error} />
          <button
            type="button"
            className={button.secondary}
            onClick={() => {
              setState({ status: 'loading' });
              setAttempt((n) => n + 1);
            }}
          >
            Opnieuw proberen
          </button>
        </div>
      );
    }
    return (
      <div>
        <ErrorBanner error={state.error} />
        <button
          type="button"
          className={button.secondary}
          onClick={() => {
            safeStorage(() => sessionStorage.removeItem(LOGIN_ATTEMPT_KEY), undefined);
            goToLogin();
          }}
        >
          Opnieuw aanmelden
        </button>
      </div>
    );
  }
  return <ActorContext.Provider value={value}>{children}</ActorContext.Provider>;
}

export function useActor(): ActorContextValue {
  const value = useContext(ActorContext);
  if (value === null) {
    throw new Error('useActor must be used within an ActorProvider');
  }
  return value;
}

/** "Jan Peeters (jan.peeters)" of enkel de gebruikersnaam als er geen weergavenaam is. */
export function formatActor(value: Pick<ActorContextValue, 'actor' | 'displayName'>): string {
  return value.displayName !== null && value.displayName !== '' && value.displayName !== value.actor
    ? `${value.displayName} (${value.actor})`
    : value.actor;
}
