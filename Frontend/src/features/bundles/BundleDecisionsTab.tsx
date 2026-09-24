/**
 * `/bundles/:bundleId/decisions` — alleen-lezen beslissingsregister, zie
 * `docs/design/frontend-scherm3-bundel-design.md` §10.7, §8.
 *
 * Append-only register van alle beslissingen in chronologische volgorde. Een herziening staat hier
 * als extra regel naast de beslissing die ze herziet; ze overschrijft die niet.
 */

import { useState } from 'react';
import * as bundlesApi from '../../api/bundles.ts';
import type { DecisionRow } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useBundleDetailContext } from './BundleDetailPage.tsx';
import styles from './BundleDecisionsTab.module.css';

function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}

function StatusTransition({ row }: { row: DecisionRow }) {
  return (
    <div>
      <StatusBadge status={row.previousStatus} />
      {' → '}
      <StatusBadge status={row.newStatus} />
    </div>
  );
}

function DecisionScopeLabel({ row }: { row: DecisionRow }) {
  const scope = row.decisionScope;
  if (scope === 'MUTATION' && row.mutationId !== null) {
    return <>Mutatie {row.mutationId}</>;
  }
  if (scope === 'GROUP') {
    return <>Groep</>;
  }
  if (scope === 'BUNDLE') {
    return <>Bundel</>;
  }
  return <>{scope}</>;
}

export function BundleDecisionsTab() {
  const { bundle } = useBundleDetailContext();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const key = `bundle-decisions:${bundle.id}:${page}:${size}`;
  const { data, error, loading } = useQuery(key, (signal) =>
    bundlesApi.decisions(bundle.id, { page, size }, signal),
  );

  const columns: readonly DataTableColumn<DecisionRow>[] = [
    { key: 'decidedAt', header: 'Tijdstip', render: (row) => formatDateTime(row.decidedAt) },
    { key: 'decisionKind', header: 'Soort', render: (row) => row.decisionKind },
    { key: 'decisionScope', header: 'Bereik', render: (row) => <DecisionScopeLabel row={row} /> },
    { key: 'decidedBy', header: 'Beslisser', render: (row) => row.decidedBy },
    {
      key: 'affectedCount',
      header: 'Aantal',
      render: (row) => row.affectedCount,
      align: 'right',
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => <StatusTransition row={row} />,
    },
    {
      key: 'selectionFilter',
      header: 'Filter',
      render: (row) => (row.selectionFilter === null ? '—' : <code className={styles.filter}>{row.selectionFilter}</code>),
    },
    {
      key: 'reason',
      header: 'Reden',
      render: (row) => row.reason === null ? '—' : row.reason,
    },
  ];

  return (
    <div className={styles.tab}>
      <p className={styles.explanation}>
        Append-only: een herziening staat hier als extra regel naast de beslissing die ze herziet.
      </p>

      {error !== null && <ErrorBanner error={error} />}
      {loading && data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {data !== null && (
        <>
          <DataTable
            columns={columns}
            rows={data.content}
            rowKey={(row) => row.id}
            emptyMessage="Deze bundel bevat geen beslissingen."
          />
          <Pager
            page={data.page}
            size={data.size}
            totalElements={data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}
    </div>
  );
}
