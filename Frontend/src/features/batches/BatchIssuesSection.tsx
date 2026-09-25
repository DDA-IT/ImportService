/**
 * Problemen (voorbeeldrijen) van een batch (A-F2). De backend bewaart per foutcode hoogstens
 * `catalogimport.screening.max-sample-rows-per-code` voorbeelden: een telling over deze lijst is dus
 * systematisch te laag. Het werkelijke aantal staat in de foutgroepen — dat staat expliciet in de UI.
 */

import { useState } from 'react';
import * as batchesApi from '../../api/batches.ts';
import type { IssueRow } from '../../api/types.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Pager } from '../../components/Pager.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import styles from './BatchDetailPage.module.css';

const COLUMNS: readonly DataTableColumn<IssueRow>[] = [
  {
    key: 'rowNumber',
    header: 'Rij',
    // `null` = leverings-/structuurprobleem dat bij geen enkele bronregel hoort.
    render: (row) => (row.rowNumber === null ? <span title="Hoort bij geen bronregel">levering</span> : row.rowNumber),
    align: 'right',
  },
  { key: 'issueCode', header: 'Foutcode', render: (row) => row.issueCode },
  { key: 'severity', header: 'Ernst', render: (row) => row.severity },
  { key: 'fieldName', header: 'Veld', render: (row) => row.fieldName ?? '—' },
  { key: 'sourceValue', header: 'Bronwaarde', render: (row) => row.sourceValue ?? '—' },
  { key: 'expectedValue', header: 'Verwacht', render: (row) => row.expectedValue ?? '—' },
  { key: 'message', header: 'Melding', render: (row) => row.message ?? '—' },
  { key: 'issueGroupId', header: 'Groep', render: (row) => row.issueGroupId ?? '—', align: 'right' },
];

export function BatchIssuesSection({ batchId, issueGroupId }: { batchId: number; issueGroupId: number | null }) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const { data, error, loading } = useQuery(
    `batch-issues:${batchId}:${issueGroupId ?? ''}:${page}:${size}`,
    (signal) => batchesApi.batchIssues(batchId, { issueGroupId: issueGroupId ?? undefined, page, size }, signal),
  );

  return (
    <section data-testid="batch-issues">
      <h2 className={styles.sectionTitle}>Problemen (voorbeelden)</h2>
      <p className={styles.warningNote} role="note">
        Dit zijn voorbeeldrijen: per foutcode wordt slechts een beperkt aantal bewaard. Het werkelijke aantal staat
        in de foutgroepen hierboven; een telling over deze lijst is te laag.
      </p>
      {issueGroupId !== null && <p>Gefilterd op foutgroep {issueGroupId}.</p>}
      {error !== null && <ErrorBanner error={error} />}
      {loading && data === null && error === null && <p className={styles.loading}>Bezig met laden…</p>}
      {data !== null && error === null && (
        <>
          <DataTable
            columns={COLUMNS}
            rows={data.content}
            rowKey={(row) => row.id}
            emptyMessage="Geen problemen vastgesteld."
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
    </section>
  );
}
