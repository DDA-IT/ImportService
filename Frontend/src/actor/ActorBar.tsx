/**
 * Accountmenu in de header van de app shell (Prodis-mock: avatar + naam, popover met rechten en "Afmelden").
 * Toont de geverifieerde identiteit (uit `GET /me`, via de context: geen extra fetch); bij een verlopen sessie
 * blijft de melding met "Opnieuw aanmelden" buiten de popover zichtbaar. Zie `docs/design/fase5-auth-design.md` §6.
 */

import { useEffect, useId, useRef, useState } from 'react';
import { formatActor, useActor } from './ActorContext';
import { permissionLabel } from './permissions';
import { LogoutIcon } from '../components/icons';
import button from '../components/Button.module.css';
import styles from './ActorBar.module.css';

function initials(displayName: string | null, actor: string): string {
  const name = displayName !== null && displayName.trim() !== '' ? displayName : actor;
  const parts = name.split(/[\s._-]+/).filter((part) => part !== '');
  const letters = parts.length > 1 ? parts[0]![0]! + parts[parts.length - 1]![0]! : (parts[0] ?? '?').slice(0, 2);
  return letters.toUpperCase();
}

export function ActorBar() {
  const actorContext = useActor();
  const { actor, displayName, permissions, sessionExpired, reauthenticate, logout } = actorContext;
  const [logoutFailed, setLogoutFailed] = useState(false);
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const popoverId = useId();

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: MouseEvent) {
      if (rootRef.current !== null && !rootRef.current.contains(event.target as Node)) setOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    }
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  async function handleLogout() {
    setLogoutFailed(false);
    setOpen(false);
    try {
      await logout();
    } catch {
      setLogoutFailed(true);
    }
  }

  const hasDisplayName = displayName !== null && displayName !== '' && displayName !== actor;
  const name = hasDisplayName ? displayName : actor;

  return (
    <div className={styles.bar}>
      <div className={styles.account} ref={rootRef}>
        <button
          type="button"
          ref={triggerRef}
          className={styles.accountBtn}
          aria-haspopup="true"
          aria-expanded={open}
          aria-controls={open ? popoverId : undefined}
          onClick={() => setOpen((value) => !value)}
        >
          <span className={styles.avatarRing} aria-hidden="true">
            <span className={styles.avatar}>{initials(displayName, actor)}</span>
          </span>
          <span className={styles.srOnly}>{formatActor(actorContext)}</span>
          <span className={styles.accountId} aria-hidden="true">
            <span className={styles.idName}>{name}</span>
            {hasDisplayName && <span className={styles.idLogin}>{actor}</span>}
          </span>
        </button>
        {open && (
          <div id={popoverId} className={styles.popover} role="group" aria-label="Accountmenu">
            <div className={styles.popHead}>
              <div className={styles.popName}>{name}</div>
              {hasDisplayName && <div className={styles.popLogin}>{actor}</div>}
            </div>
            <hr className={styles.dashed} />
            <section className={styles.rights} aria-labelledby={`${popoverId}-rights`}>
              <h2 id={`${popoverId}-rights`} className={styles.rightsTitle}>
                Mijn rechten
              </h2>
              {permissions.length === 0 ? (
                <p className={styles.rightsEmpty}>Geen rechten.</p>
              ) : (
                <ul className={styles.rightsList}>
                  {permissions.map((code) => (
                    <li key={code}>{permissionLabel(code)}</li>
                  ))}
                </ul>
              )}
            </section>
            <hr className={styles.dashed} />
            <div className={styles.popFoot}>
              <button type="button" className={styles.logout} onClick={handleLogout}>
                <LogoutIcon size={20} />
                Afmelden
              </button>
            </div>
          </div>
        )}
      </div>
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
