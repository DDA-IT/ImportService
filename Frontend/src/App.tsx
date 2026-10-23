import { useEffect, useState, type ComponentType } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { ActorBar } from './actor/ActorBar.tsx';
import { useActor } from './actor/ActorContext.tsx';
import { PERMISSION_MANAGE } from './api/types.ts';
import styles from './App.module.css';
import {
  BundleIcon,
  CaseIcon,
  ChevDIcon,
  ChevIcon,
  LinkIcon,
  MenuIcon,
  QueueIcon,
  SetupIcon,
  SupplierIcon,
  TemplateIcon,
  UploadIcon,
  type IconProps,
} from './components/icons.tsx';
import { ProductSwitcher } from './layout/ProductSwitcher.tsx';
import { ThemeButton } from './layout/ThemeButton.tsx';
import { GROUP, SCREEN, SCREEN_HINT } from './terms/navigation.ts';

const NO_PERMISSIONS_MESSAGE = 'U heeft geen rechten voor CatalogImport.';
const MINI_STORAGE_KEY = 'catalogimport.nav.mini';

interface NavItem {
  to: string;
  label: string;
  /** Eénregelige uitleg, als tooltip. */
  hint?: string;
  end: boolean;
  Icon: ComponentType<IconProps>;
  /** Enkel tonen met dit recht (zelfde recht als de knop "Leverancier toevoegen" op de pagina Leveranciers). */
  requires?: typeof PERMISSION_MANAGE;
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

// N1: namen uit `terms/navigation.ts` (beslissing 2026-10-23); routes en testids ongewijzigd.
const NAV_GROUPS: NavGroup[] = [
  {
    id: 'inlezen',
    title: GROUP.inlezen,
    items: [
      { to: '/', label: SCREEN.inlezingen, hint: SCREEN_HINT.inlezingen, end: true, Icon: QueueIcon },
      { to: '/upload', label: SCREEN.bestandUploaden, hint: SCREEN_HINT.bestandUploaden, end: false, Icon: UploadIcon },
    ],
  },
  {
    id: 'beoordelen',
    title: GROUP.beoordelen,
    items: [{ to: '/issue-cases', label: SCREEN.afwijkingen, hint: SCREEN_HINT.afwijkingen, end: false, Icon: CaseIcon }],
  },
  {
    id: 'publiceren',
    title: GROUP.publiceren,
    items: [{ to: '/bundles', label: SCREEN.publicaties, hint: SCREEN_HINT.publicaties, end: false, Icon: BundleIcon }],
  },
  {
    id: 'inrichting',
    title: GROUP.inrichting,
    items: [
      { to: '/setup', label: SCREEN.leveranciers, hint: SCREEN_HINT.leveranciers, end: true, Icon: SetupIcon },
      {
        to: '/setup/new',
        label: SCREEN.leverancierToevoegen,
        hint: SCREEN_HINT.leverancierToevoegen,
        end: false,
        Icon: SupplierIcon,
        requires: PERMISSION_MANAGE,
      },
      { to: '/templates', label: SCREEN.importdefinities, hint: SCREEN_HINT.importdefinities, end: false, Icon: TemplateIcon },
      { to: '/setup/legacy-config', label: SCREEN.overnameUitLegacy, end: false, Icon: LinkIcon, requires: PERMISSION_MANAGE },
    ],
  },
];

function readMini(): boolean {
  try {
    return window.localStorage.getItem(MINI_STORAGE_KEY) === '1';
  } catch {
    return false;
  }
}

function writeMini(mini: boolean) {
  try {
    window.localStorage.setItem(MINI_STORAGE_KEY, mini ? '1' : '0');
  } catch {
    // opslag geblokkeerd: de keuze geldt enkel voor deze sessie
  }
}

function App() {
  const { permissions, can } = useActor();
  const [menuOpen, setMenuOpen] = useState(false);
  const [mini, setMini] = useState(readMini);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(() => new Set());
  const [scrolled, setScrolled] = useState(false);
  // 5-PERM: zonder enig recht (`/me.permissions = []`) blijven header en menu staan, maar de pagina's worden
  // niet gemount (`Outlet` ontbreekt), zodat hun queries niet starten en geen 403's ophalen.
  const noPermissions = permissions.length === 0;

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 0);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    if (!menuOpen) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [menuOpen]);

  function toggleMini() {
    const next = !mini;
    setMini(next);
    writeMini(next);
  }

  function toggleGroup(id: string) {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  return (
    <div className={`${styles.shell} ${mini ? styles.shellMini : ''}`}>
      {/* oxlint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions -- muis-only sluitlaag; het toetsenbord sluit het menu via Escape, de focusbare menuknop (aria-expanded) en de navigatielinks */}
      {menuOpen && <div className={styles.backdrop} data-testid="nav-backdrop" onClick={() => setMenuOpen(false)} />}
      <aside
        id="app-nav"
        className={`${styles.sidebar} ${mini ? `${styles.sidebarMini} nav-mini` : ''} ${menuOpen ? styles.sidebarOpen : ''}`}
      >
        <div className={styles.navTop}>
          <ProductSwitcher />
        </div>
        <nav className={styles.navScroll} aria-label="Hoofdnavigatie">
          {NAV_GROUPS.map((group) => {
            const isCollapsed = collapsed.has(group.id);
            const items = group.items.filter((item) => !item.requires || can(item.requires));
            return (
              <div key={group.id} className={`${styles.navGroup} ${isCollapsed ? styles.navGroupCollapsed : ''}`}>
                <button
                  type="button"
                  className={styles.navSub}
                  aria-expanded={!isCollapsed}
                  aria-controls={`nav-list-${group.id}`}
                  onClick={() => toggleGroup(group.id)}
                >
                  {isCollapsed ? <ChevIcon className={styles.navSubIcon} /> : <ChevDIcon className={styles.navSubIcon} />}
                  {group.title}
                </button>
                <ul id={`nav-list-${group.id}`} className={styles.navList}>
                  {items.map(({ to, label, hint, end, Icon }) => (
                    <li key={to}>
                      <NavLink to={to} end={end} title={hint} className={styles.navItem} onClick={() => setMenuOpen(false)}>
                        <Icon className={styles.navIcon} />
                        <span className={styles.navText}>{label}</span>
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </nav>
      </aside>
      <button
        type="button"
        className={styles.navToggle}
        aria-label={mini ? 'Menu uitklappen' : 'Menu inklappen'}
        aria-controls="app-nav"
        onClick={toggleMini}
      >
        <ChevIcon size={16} className={styles.navToggleIcon} />
      </button>
      <div className={styles.content}>
        <header className={`${styles.header} ${scrolled ? styles.headerScrolled : ''}`}>
          <button
            type="button"
            className={styles.menuButton}
            aria-label="Menu openen of sluiten"
            aria-controls="app-nav"
            aria-expanded={menuOpen}
            onClick={() => setMenuOpen((open) => !open)}
          >
            <MenuIcon />
          </button>
          <div className={styles.headerActor}>
            <ThemeButton />
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
