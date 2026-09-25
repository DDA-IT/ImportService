/**
 * Foutgroepen van een batch (A-F2): gelijksoortige vaststellingen samengevat, met het **werkelijke**
 * aantal (`occurrenceCount`). Dit is de enige bron van waarheid voor aantallen; de voorbeeldrijen uit
 * `GET /batches/{id}/issues` zijn begrensd per foutcode en dus systematisch te laag.
 */

import { useState } from 'react';
import * as batchesApi from '../../api/batches.ts';
import type { IssueGroupRow } from '../../api/types.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Pager } from '../../components/Pager.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { Count } from './format.tsx';
import styles from './BatchDetailPage.module.css';

export function BatchIssueGroupsSection({
  batchId,
  selectedGroupId,
  onSelectGroup,
}: {
  batchId: number;
  selectedGroupId: number | null;
  onSelectGroup: (groupId: number | null) => void;
}) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const { data, error, loading } = useQuery(`batch-issue-groups:${batchId}:${page}:${size}`, (signal) =>
    batchesApi.batchIssueGroups(batchId, { page, size }, signal),
  );

  const columns: readonly DataTableColumn<IssueGroupRow>[] = [
    { key: 'issueCode', header: 'Foutcode', render: (row) => row.issueCode },
    { key: 'severity', header: 'Ernst', render: (row) => row.severity },
    { key: 'incidentKind', header: 'Soort', render: (row) => row.incidentKind },
    {
      key: 'occurrenceCount',
      header: 'Werkelijk aantal',
      render: (row) => row.occurrenceCount,
      align: 'right',
    },
    {
      key: 'recordedSampleCount',
      header: 'Bewaarde voorbeelden',
      render: (row) => row.recordedSampleCount,
      align: 'right',
    },
    {
      key: 'share',
      header: 'Aandeel in scope',
      render: (row) =>
        row.sharePercent === null || row.scopeRecordCount === null ? (
          <Count value={null} />
        ) : (
          `${row.sharePercent}% van ${row.scopeRecordCount}`
        ),
      align: 'right',
    },
    {
      key: 'bulkIncident',
      header: 'Bulkincident',
      render: (row) => (row.bulkIncident ? <strong>Ja</strong> : 'Nee'),
    },
    { key: 'handlingStatus', header: 'Afhandeling', render: (row) => row.handlingStatus },
    {
      key: 'samples',
      header: 'Voorbeelden',
      render: (row) => (
        <button
          type="button"
          aria-pressed={selectedGroupId === row.id}
          onClick={() => onSelectGroup(selectedGroupId === row.id ? null : row.id)}
        >
          {selectedGroupId === row.id ? 'Toon alle problemen' : 'Toon voorbeelden'}
        </button>
      ),
    },
  ];

  return (
    <section data-testid="batch-issue-groups">
      <h2 className={styles.sectionTitle}>Foutgroepen</h2>
      {error !== null && <ErrorBanner error={error} />}
      {loading && data === null && error === null && <p className={styles.loading}>Bezig met laden…</p>}
      {data !== null && error === null && (
        <>
          <DataTable
            columns={columns}
            rows={data.content}
            rowKey={(row) => row.id}
            emptyMessage="Deze batch heeft geen foutgroepen."
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
