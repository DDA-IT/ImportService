/**
 * `/bundles` — de bundellijst, zie `docs/design/frontend-scherm3-bundel-design.md` §8/§9.3/§10.1.
 *
 * `GET /bundles` met statusfilter en paginering via `useQuery` + `DataTable` + `Pager`. Een fout toont
 * `ErrorBanner`; een lege lijst toont een nette lege toestand (via `DataTable.emptyMessage`). Na een
 * geslaagde bundel-aanmaak (`CreateBundleForm`) wordt de lijst expliciet herladen via `reload()` (§5:
 * geen magische cache-invalidatie).
 */

import { useState } from 'react';
import { Link } from 'react-router-dom';
import * as bundlesApi from '../../api/bundles.ts';
import { PUBLICATION_BUNDLE_STATUSES } from '../../api/types.ts';
import type { BundleSummary, PublicationBundleStatus } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Field } from '../../components/Field.tsx';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { Term } from '../../terms/Term.tsx';
import { term } from '../../terms/index.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { CreateBundleForm } from './CreateBundleForm.tsx';
import pageCss from '../../components/Page.module.css';
import styles from './BundleListPage.module.css';
import { formatDateTime } from '../../format.ts';
import { GROUP, PRODUCT, SCREEN } from '../../terms/navigation.ts';

const NO_STATUS_FILTER = '';
type StatusFilter = PublicationBundleStatus | typeof NO_STATUS_FILTER;

/**
 * Een teller is `null` wanneer de backend hem (nog) niet vastgesteld heeft — dat is nooit hetzelfde als
 * `0` (§9.3). Getoond als "—" met een tooltip, nooit als `0`.
 */
function Count({ value }: { value: number | null }) {
  if (value === null) {
    return (
      <span className={styles.count} title="niet vastgesteld">
        —
      </span>
    );
  }
  return <>{value}</>;
}

const COLUMNS: readonly DataTableColumn<BundleSummary>[] = [
  {
    key: 'bundleReference',
    header: 'Referentie',
    render: (row) => <Link to={`/bundles/${row.id}`}>{row.bundleReference}</Link>,
  },
  { key: 'description', header: 'Omschrijving', render: (row) => row.description ?? '—' },
  { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} domain="bundleStatus" /> },
  {
    key: 'targetMode',
    header: 'Doelmodus',
    render: (row) => <Term domain="targetMode" code={row.targetMode} />,
  },
  {
    key: 'batchCount',
    header: 'Inlezingen',
    render: (row) => <Count value={row.batchCount} />,
    align: 'right',
  },
  {
    key: 'contentMutationCount',
    header: 'Wijzigingen',
    render: (row) => <Count value={row.contentMutationCount} />,
    align: 'right',
  },
  { key: 'createdBy', header: 'Aangemaakt door', render: (row) => row.createdBy },
  { key: 'createdAt', header: 'Aangemaakt op', render: (row) => formatDateTime(row.createdAt) },
];

export function BundleListPage() {
  const [status, setStatus] = useState<StatusFilter>(NO_STATUS_FILTER);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const key = `bundles:${status}:${page}:${size}`;
  const { data, error, loading, reload } = useQuery(key, (signal) =>
    bundlesApi.list({ status: status === NO_STATUS_FILTER ? undefined : status, page, size }, signal),
  );

  function handleStatusChange(next: StatusFilter) {
    setStatus(next);
    setPage(0);
  }

  function handleSizeChange(next: number) {
    setSize(next);
    setPage(0);
  }

  return (
    <div className={styles.page}>
      <header className={pageCss.pageHead}>
        <div className={pageCss.pageHeadText}>
          <h1 className={pageCss.title}>{SCREEN.publicaties}</h1>
          <ol className={pageCss.crumbs}>
            <li>
              <span>{PRODUCT}</span>
            </li>
            <li>
              <span>{GROUP.publiceren}</span>
            </li>
            <li>
              <span aria-current="page">{SCREEN.publicaties}</span>
            </li>
          </ol>
        </div>
      </header>

      <WhatIsThis>
        <p>
          Een publicatie verzamelt goedgekeurde wijzigingen die samen gepubliceerd worden. U voegt er gecontroleerde
          leveringen (inlezingen) aan toe, beoordeelt de wijzigingen, en bevriest de publicatie wanneer alles klaar is.
          Bevriezen sluit de publicatie af: daarna kan er niets meer bij of af, alleen nog geannuleerd worden.
        </p>
        <p>
          Bij het aanmaken kiest u de doelmodus: een proefpublicatie schrijft niets naar Prodis, een controlebibliotheek
          is bedoeld om te controleren, en een echte publicatie gaat naar de echte bibliotheek.
        </p>
      </WhatIsThis>

      <div className={`${pageCss.card} ${pageCss.cardPad} ${styles.formCard}`}>
        <CreateBundleForm onCreated={reload} />
      </div>

      <div className={pageCss.card}>
        <div className={`${pageCss.toolbar} ${styles.toolbar}`} role="search">
          <Field label="Status" htmlFor="bundle-status-filter">
            <select
              id="bundle-status-filter"
              value={status}
              onChange={(event) => handleStatusChange(event.target.value as StatusFilter)}
            >
              <option value={NO_STATUS_FILTER}>Alle</option>
              {PUBLICATION_BUNDLE_STATUSES.map((option) => (
                <option key={option} value={option}>
                  {term('bundleStatus', option).label}
                </option>
              ))}
            </select>
          </Field>
        </div>

        {error !== null && <ErrorBanner error={error} />}
        {loading && data === null && <p className={styles.loading}>Bezig met laden…</p>}

        {data !== null && (
          <>
            <DataTable
              columns={COLUMNS}
              rows={data.content}
              rowKey={(row) => row.id}
              emptyMessage="Geen publicaties gevonden."
            />
            <Pager
              page={data.page}
              size={data.size}
              totalElements={data.totalElements}
              onPageChange={setPage}
              onSizeChange={handleSizeChange}
            />
          </>
        )}
      </div>
    </div>
  );
}
