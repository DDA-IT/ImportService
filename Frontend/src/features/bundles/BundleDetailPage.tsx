/**
 * `/bundles/:bundleId` en de tabbladen eronder, zie
 * `docs/design/frontend-scherm3-bundel-design.md` §8.
 *
 * Laadt `GET /bundles/{id}` **één keer** (`useQuery`) en geeft die `BundleDetail` via
 * `<Outlet context>` door aan de tabbladen — de tabbladen laden zelf hun eigen lijst, maar de
 * bundelstatus (de bron van alle actiebeslissingen, §9) staat zo op één plek.
 */

import { NavLink, Outlet, useOutletContext, useParams } from 'react-router-dom';
import * as bundlesApi from '../../api/bundles.ts';
import type { BundleDetail } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import styles from './BundleDetailPage.module.css';

export type BundleDetailContext = {
  bundle: BundleDetail;
  /** Herlaadt exact de bundeldetailquery (tellers, status, audit) — de aanroeper weet zelf welke
   * andere queries (leden, kandidaten, mutaties, beslissingen) ook herladen moeten worden (§5). */
  reloadBundle: () => void;
};

/** Hulphook voor de tabbladen: haalt de door `BundleDetailPage` doorgegeven `BundleDetail` op. */
export function useBundleDetailContext(): BundleDetailContext {
  return useOutletContext<BundleDetailContext>();
}

export function BundleDetailPage() {
  const params = useParams<{ bundleId: string }>();
  const bundleId = Number(params.bundleId);

  const key = `bundle-detail:${bundleId}`;
  const { data, error, loading, reload } = useQuery(key, (signal) => bundlesApi.get(bundleId, signal));

  return (
    <div className={styles.page}>
      <p className={styles.breadcrumb}>
        <NavLink to="/bundles">Publicatiebundels</NavLink>
      </p>

      {error !== null && <ErrorBanner error={error} />}
      {loading && data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {data !== null && (
        <>
          <div className={styles.header}>
            <h1 className={styles.title}>{data.bundleReference}</h1>
            <StatusBadge status={data.status} />
          </div>

          <nav className={styles.tabs}>
            <NavLink
              to={`/bundles/${bundleId}`}
              end
              className={({ isActive }) => (isActive ? styles.tabActive : styles.tab)}
            >
              Overzicht
            </NavLink>
            <NavLink
              to={`/bundles/${bundleId}/batches`}
              className={({ isActive }) => (isActive ? styles.tabActive : styles.tab)}
            >
              Leden
            </NavLink>
            <NavLink
              to={`/bundles/${bundleId}/mutations`}
              className={({ isActive }) => (isActive ? styles.tabActive : styles.tab)}
            >
              Mutaties
            </NavLink>
            <NavLink
              to={`/bundles/${bundleId}/decisions`}
              className={({ isActive }) => (isActive ? styles.tabActive : styles.tab)}
            >
              Beslissingen
            </NavLink>
          </nav>

          <Outlet context={{ bundle: data, reloadBundle: reload } satisfies BundleDetailContext} />
        </>
      )}
    </div>
  );
}
