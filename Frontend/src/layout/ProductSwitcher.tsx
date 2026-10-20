import { useEffect, useId, useRef, useState } from 'react';
import { useActor } from '../actor/ActorContext.tsx';
import type { ProductApp, ProductAppId } from '../api/me.ts';
import styles from './ProductSwitcher.module.css';

/** Naam, logo en ondertitel per app (ondertitels uit de Prodis-locales, `productSwitcher.*.subtitle`). */
const APP_INFO: Record<ProductAppId, { name: string; logo: string; subtitle: string }> = {
  prodis: { name: 'Prodis', logo: '/logo/logo-single-prodis.svg', subtitle: 'ERP' },
  profin: { name: 'ProFin', logo: '/logo/logo-single-profin.png', subtitle: 'Boekhouding' },
  prodev: { name: 'ProDev', logo: '/logo/logo-single-prodev.svg', subtitle: 'Productontwikkeling' },
};

function Identity() {
  return (
    <>
      <span className={styles.mark} aria-hidden="true">
        CI
      </span>
      <span className={styles.text}>
        <span className={styles.name}>CatalogImport</span>
        <span className={styles.sub}>Inlezen van catalogi</span>
      </span>
    </>
  );
}

function ChevronIcon() {
  return (
    <svg className={styles.chevron} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <path d="m7 15 5 5 5-5M7 9l5-5 5 5" />
    </svg>
  );
}

/**
 * Productwisselaar linksboven (Prodis' `product-switcher`, mock `.brand`/`.popover`): toont CatalogImport als huidige
 * app; met `apps` uit `/me` ook een menu met echte links (zelfde tab). Zonder apps: enkel de identiteit, geen menu.
 * In de mini-modus blijft het merkteken over; het menu werkt nog.
 */
export function ProductSwitcher() {
  const { apps } = useActor();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) return undefined;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    const onPointerDown = (event: MouseEvent) => {
      if (rootRef.current !== null && !rootRef.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('mousedown', onPointerDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('mousedown', onPointerDown);
    };
  }, [open]);

  const known = apps.filter((app: ProductApp) => app.id in APP_INFO);
  if (known.length === 0) {
    return (
      <div className={styles.root}>
        <div className={styles.brand}>
          <Identity />
        </div>
      </div>
    );
  }

  return (
    <div className={styles.root} ref={rootRef}>
      <button
        ref={buttonRef}
        type="button"
        className={styles.brand}
        aria-label="Wissel van applicatie"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((current) => !current)}
      >
        <Identity />
        <ChevronIcon />
      </button>
      {open && (
        <div className={styles.popover}>
          <div className={styles.popLabel}>Wissel van applicatie</div>
          <hr className={styles.dashed} />
          <ul id={menuId} role="menu" className={styles.list} aria-label="Wissel van applicatie">
            {known.map((app) => {
              const info = APP_INFO[app.id];
              return (
                <li key={app.id} role="none">
                  <a role="menuitem" className={styles.item} href={app.url} aria-label={`${info.name}, ${info.subtitle}`}>
                    <img className={styles.logo} src={info.logo} alt="" />
                    <span className={styles.itemText}>
                      <span className={styles.name}>{info.name}</span>
                      <span className={styles.sub}>{info.subtitle}</span>
                    </span>
                  </a>
                </li>
              );
            })}
          </ul>
        </div>
      )}
    </div>
  );
}
