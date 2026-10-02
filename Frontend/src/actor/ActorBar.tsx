/**
 * Staat permanent in de app shell. Toont de geverifieerde identiteit (uit `GET /me`) met een knop om af
 * te melden; bij een verlopen sessie de melding met "Opnieuw aanmelden". Zie
 * `docs/design/fase5-auth-design.md` §6.
 */

import { useState } from 'react';
import { formatActor, useActor } from './ActorContext';
import button from '../components/Button.module.css';
import styles from './ActorBar.module.css';

export function ActorBar() {
  const actorContext = useActor();
  const { sessionExpired, reauthenticate, logout } = actorContext;
  const [logoutFailed, setLogoutFailed] = useState(false);

  async function handleLogout() {
    setLogoutFailed(false);
    try {
      await logout();
    } catch {
      setLogoutFailed(true);
    }
  }

  return (
    <div className={styles.bar}>
      <span className={styles.current}>
        Aangemeld als <strong>{formatActor(actorContext)}</strong>
      </span>
      <button type="button" className={`${button.primary} ${button.small}`} onClick={handleLogout}>
        Afmelden
      </button>
      {logoutFailed && (
        <p className={styles.error} role="alert">
          Afmelden is mislukt; probeer opnieuw.
        </p>
      )}
      {sessionExpired && (
        <p className={styles.error} role="alert">
          Uw sessie is verlopen.{' '}
          <button type="button" className={`${button.primary} ${button.small}`} onClick={reauthenticate}>
            Opnieuw aanmelden
          </button>
        </p>
      )}
    </div>
  );
}
