import { useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { ActorBar } from './actor/ActorBar.tsx';
import { useActor } from './actor/ActorContext.tsx';
import styles from './App.module.css';

const NO_PERMISSIONS_MESSAGE = 'U heeft geen rechten voor CatalogImport.';

const NAV_ITEMS = [
  { to: '/', label: 'Werkvoorraad', end: true },
  { to: '/upload', label: 'Levering uploaden', end: false },
  { to: '/bundles', label: 'Publicatiebundels', end: false },
  { to: '/setup', label: 'Inrichting', end: false },
  { to: '/templates', label: 'Sjablonen', end: false },
  { to: '/issue-cases', label: 'Behandelgevallen', end: false },
];

function App() {
  const { permissions } = useActor();
  const [menuOpen, setMenuOpen] = useState(false);
  // 5-PERM: zonder enig recht (`/me.permissions = []`) blijven header en menu staan, maar de pagina's worden
  // niet gemount (`Outlet` ontbreekt), zodat hun queries niet starten en geen 403's ophalen.
  const noPermissions = permissions.length === 0;

  return (
    <div className={styles.shell}>
      {/* oxlint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- muis-only sluitlaag; het toetsenbord sluit het menu via de focusbare knop "Menu openen of sluiten" (aria-expanded) en via de navigatielinks */}
      {menuOpen && <div className={styles.backdrop} data-testid="nav-backdrop" onClick={() => setMenuOpen(false)} />}
      <aside className={`${styles.sidebar} ${menuOpen ? styles.sidebarOpen : ''}`}>
        <h1 className={styles.title}>CatalogImport</h1>
        <nav className={styles.nav} aria-label="Hoofdnavigatie">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) => `${styles.navItem} ${isActive ? styles.navItemActive : ''}`}
              onClick={() => setMenuOpen(false)}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className={styles.content}>
        <header className={styles.header}>
          <button
            type="button"
            className={styles.menuButton}
            aria-label="Menu openen of sluiten"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
          >
            &#9776;
          </button>
          <div className={styles.headerActor}>
            <ActorBar />
          </div>
        </header>
        <main className={styles.main}>
          {noPermissions ? (
            <div role="alert" data-testid="no-permissions" className={styles.noPermissions}>
              <p>{NO_PERMISSIONS_MESSAGE}</p>
              <p>Vraag de beheerder om het recht om CatalogImport te lezen (catalogImport.read).</p>
            </div>
          ) : (
            <Outlet />
          )}
        </main>
      </div>
    </div>
  );
}

export default App;
