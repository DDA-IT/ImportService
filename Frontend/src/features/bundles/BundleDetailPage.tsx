/**
 * `/bundles/:bundleId` en de tabbladen eronder, zie
 * `docs/design/frontend-scherm3-bundel-design.md` §8.
 *
 * Laadt `GET /bundles/{id}` **één keer** (`useQuery`) en geeft die `BundleDetail` via
 * `<Outlet context>` door aan de tabbladen — de tabbladen laden zelf hun eigen lijst, maar de
 * bundelstatus (de bron van alle actiebeslissingen, §9) staat zo op één plek.
 */

import { Link, NavLink, Outlet, useOutletContext, useParams } from 'react-router-dom';
import * as bundlesApi from '../../api/bundles.ts';
import type { BundleDetail } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { BackIcon } from '../../components/icons.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import pageCss from '../../components/Page.module.css';
import styles from './BundleDetailPage.module.css';
import { GROUP, PRODUCT, SCREEN, TAB_PUBLICATIE } from '../../terms/navigation.ts';

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
      {data === null && (
        <p className={styles.breadcrumb}>
          <NavLink to="/bundles">{SCREEN.publicaties}</NavLink>
        </p>
      )}

      {error !== null && <ErrorBanner error={error} />}
      {loading && data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {data !== null && (
        <>
          <header className={`${pageCss.pageHead} ${styles.crumbHead}`}>
            <div className={pageCss.pageHeadText}>
              <ol className={pageCss.crumbs}>
                <li>
                  <span>{PRODUCT}</span>
                </li>
                <li>
                  <span>{GROUP.publiceren}</span>
                </li>
                <li>
                  <Link to="/bundles">{SCREEN.publicaties}</Link>
                </li>
                <li>
                  <span aria-current="page">Publicatie #{bundleId}</span>
                </li>
              </ol>
            </div>
          </header>
          <div className={styles.detailHead}>
            <Link to="/bundles" className={styles.backButton} aria-label="Terug naar de publicaties">
              <BackIcon />
            </Link>
            <div className={styles.detailId}>
              <h1 className={pageCss.title}>Publicatie #{bundleId}</h1>
              <div className={styles.chips}>
                <span data-testid="bundle-reference">{data.bundleReference}</span>
                <StatusBadge status={data.status} domain="bundleStatus" />
              </div>
            </div>
          </div>

          <nav className={pageCss.tabs} aria-label="Onderdelen van de publicatie">
            <NavLink to={`/bundles/${bundleId}`} end className={pageCss.tab}>
              Overzicht
            </NavLink>
            <NavLink to={`/bundles/${bundleId}/batches`} className={pageCss.tab}>
              {TAB_PUBLICATIE.inlezingen}
            </NavLink>
            <NavLink to={`/bundles/${bundleId}/mutations`} className={pageCss.tab}>
              {TAB_PUBLICATIE.wijzigingen}
            </NavLink>
            <NavLink to={`/bundles/${bundleId}/decisions`} className={pageCss.tab}>
              Beslissingen
            </NavLink>
            <NavLink to={`/bundles/${bundleId}/publication`} className={pageCss.tab}>
              Publicatie
            </NavLink>
          </nav>

          <Outlet context={{ bundle: data, reloadBundle: reload } satisfies BundleDetailContext} />
        </>
      )}
    </div>
  );
}
