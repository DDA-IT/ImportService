/**
 * `/` — Scherm 0, de werkvoorraad (D14, bouwstap S0-F1), zie `docs/decisions.md` 2026-09-23
 * "Frontend: D14 opgepakt, eerste verticale slice Scherm 0 (werkvoorraad)".
 *
 * Volledig alleen-lezen: telblokken uit `GET /batches/summary` + een filterbare, gepagineerde tabel
 * uit `GET /batches`. Geen enkele schrijfactie op dit scherm (accept-baseline en bundel-opname horen
 * op scherm (2)/(3) — expliciete scope-grens uit de beslissing).
 *
 * Elke rij linkt door naar het batchdetail `/batches/:batchId` (A-F1).
 */

import { useState } from 'react';
import { Link } from 'react-router-dom';
import * as batchesApi from '../../api/batches.ts';
import * as importLinksApi from '../../api/importLinks.ts';
import { IMPORT_BATCH_STATUSES, VALIDATION_RESULTS } from '../../api/types.ts';
import type { BatchRow, ImportBatchStatus, ValidationResult } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import styles from './WorkQueuePage.module.css';

const NO_STATUS_FILTER = '';
const NO_VALIDATION_FILTER = '';
const NO_IMPORT_LINK_FILTER = '';
type StatusFilter = ImportBatchStatus | typeof NO_STATUS_FILTER;
type ValidationFilter = ValidationResult | typeof NO_VALIDATION_FILTER;
type ImportLinkFilter = number | typeof NO_IMPORT_LINK_FILTER;

/**
 * Een teller is `null` wanneer de backend hem (nog) niet vastgesteld heeft — nooit hetzelfde als `0`
 * (zelfde regel als scherm 3, zie `BundleListPage`). Getoond als "—" met een tooltip.
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

function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}

/** "YYYY-MM-DD" (uit een `<input type="date">`) naar het begin van die dag in UTC. */
function dateInputToInstantStart(value: string): string | undefined {
  return value === '' ? undefined : `${value}T00:00:00.000Z`;
}

/**
 * "YYYY-MM-DD" naar het begin van de dag erna: de backend leest `createdTo` als een halfopen
 * bovengrens ({@code [createdFrom, createdTo)}), dus "tot en met deze dag" vereist de volgende dag als
 * grens.
 */
function dateInputToInstantEndExclusive(value: string): string | undefined {
  if (value === '') {
    return undefined;
  }
  const next = new Date(`${value}T00:00:00.000Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString();
}

const COLUMNS: readonly DataTableColumn<BatchRow>[] = [
  { key: 'batchId', header: 'Batch', render: (row) => <Link to={`/batches/${row.batchId}`}>#{row.batchId}</Link> },
  { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
  {
    key: 'validationResult',
    header: 'Eindoordeel',
    render: (row) =>
      row.validationResult === null ? (
        <span className={styles.notEstablished} title="Het eindoordeel staat nog niet vast">
          Niet vastgesteld
        </span>
      ) : (
        <StatusBadge status={row.validationResult} />
      ),
  },
  {
    key: 'importLinkCode',
    header: 'Koppeling',
    render: (row) => (
      <span title={`Leverancier ${row.supplierCode} · bibliotheek ${row.libraryCode}`}>{row.importLinkCode}</span>
    ),
  },
  { key: 'createdAt', header: 'Aangemaakt op', render: (row) => formatDateTime(row.createdAt) },
  {
    key: 'criticalIssueCount',
    header: 'Kritieke issues',
    render: (row) => <Count value={row.criticalIssueCount} />,
    align: 'right',
  },
  {
    key: 'awaitingApprovalCount',
    header: 'Wacht op goedkeuring',
    render: (row) => <Count value={row.awaitingApprovalCount} />,
    align: 'right',
  },
  {
    key: 'blockedCode',
    header: 'Blokkeerreden',
    render: (row) => row.blockedCode ?? '—',
  },
];

export function WorkQueuePage() {
  const [status, setStatus] = useState<StatusFilter>(NO_STATUS_FILTER);
  const [validationResult, setValidationResult] = useState<ValidationFilter>(NO_VALIDATION_FILTER);
  const [importLinkId, setImportLinkId] = useState<ImportLinkFilter>(NO_IMPORT_LINK_FILTER);
  const [createdFrom, setCreatedFrom] = useState('');
  const [createdTo, setCreatedTo] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const importLinkFilterValue = importLinkId === NO_IMPORT_LINK_FILTER ? undefined : importLinkId;

  const summaryKey = `batches-summary:${importLinkFilterValue ?? ''}`;
  const summary = useQuery(summaryKey, (signal) => batchesApi.batchSummary({ importLinkId: importLinkFilterValue }, signal));

  const linksKey = 'import-links:all';
  const links = useQuery(linksKey, (signal) => importLinksApi.listImportLinks({ size: 200 }, signal));

  const listKey = `batches:${status}:${validationResult}:${importLinkFilterValue ?? ''}:${createdFrom}:${createdTo}:${page}:${size}`;
  const list = useQuery(listKey, (signal) =>
    batchesApi.listBatches(
      {
        status: status === NO_STATUS_FILTER ? undefined : status,
        validationResult: validationResult === NO_VALIDATION_FILTER ? undefined : validationResult,
        importLinkId: importLinkFilterValue,
        createdFrom: dateInputToInstantStart(createdFrom),
        createdTo: dateInputToInstantEndExclusive(createdTo),
        page,
        size,
      },
      signal,
    ),
  );

  function resetPageAnd<T>(setter: (value: T) => void) {
    return (value: T) => {
      setter(value);
      setPage(0);
    };
  }

  const handleStatusChange = resetPageAnd(setStatus);
  const handleValidationChange = resetPageAnd(setValidationResult);
  const handleImportLinkChange = resetPageAnd(setImportLinkId);
  const handleCreatedFromChange = resetPageAnd(setCreatedFrom);
  const handleCreatedToChange = resetPageAnd(setCreatedTo);

  function handleSizeChange(next: number) {
    setSize(next);
    setPage(0);
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Werkvoorraad</h1>

      {summary.error !== null && <ErrorBanner error={summary.error} />}
      {summary.data !== null && (
        <div className={styles.tiles} data-testid="summary-tiles">
          <div className={styles.tile}>
            <span className={styles.tileLabel}>Totaal</span>
            <span className={styles.tileValue}>{summary.data.total}</span>
          </div>
          {summary.data.byStatus.map((entry) => (
            <div className={styles.tile} key={`status-${entry.status}`}>
              <span className={styles.tileLabel}>{entry.status}</span>
              <span className={styles.tileValue}>{entry.count}</span>
            </div>
          ))}
          {summary.data.byValidationResult.map((entry) => (
            <div className={styles.tile} key={`validation-${entry.validationResult ?? 'NONE'}`}>
              <span className={styles.tileLabel}>{entry.validationResult ?? 'Niet vastgesteld'}</span>
              <span className={styles.tileValue}>{entry.count}</span>
            </div>
          ))}
        </div>
      )}

      <div className={styles.filters}>
        <label htmlFor="workqueue-status-filter">Status</label>
        <select
          id="workqueue-status-filter"
          value={status}
          onChange={(event) => handleStatusChange(event.target.value as StatusFilter)}
        >
          <option value={NO_STATUS_FILTER}>Alle</option>
          {IMPORT_BATCH_STATUSES.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>

        <label htmlFor="workqueue-validation-filter">Eindoordeel</label>
        <select
          id="workqueue-validation-filter"
          value={validationResult}
          onChange={(event) => handleValidationChange(event.target.value as ValidationFilter)}
        >
          <option value={NO_VALIDATION_FILTER}>Alle</option>
          {VALIDATION_RESULTS.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>

        <label htmlFor="workqueue-link-filter">Koppeling</label>
        <select
          id="workqueue-link-filter"
          value={importLinkId}
          onChange={(event) =>
            handleImportLinkChange(event.target.value === NO_IMPORT_LINK_FILTER ? '' : Number(event.target.value))
          }
        >
          <option value={NO_IMPORT_LINK_FILTER}>Alle</option>
          {links.data?.content.map((link) => (
            <option key={link.id} value={link.id}>
              {link.code} — {link.name}
            </option>
          ))}
        </select>

        <label htmlFor="workqueue-created-from">Aangemaakt vanaf</label>
        <input
          id="workqueue-created-from"
          type="date"
          value={createdFrom}
          onChange={(event) => handleCreatedFromChange(event.target.value)}
        />

        <label htmlFor="workqueue-created-to">Aangemaakt tot en met</label>
        <input
          id="workqueue-created-to"
          type="date"
          value={createdTo}
          onChange={(event) => handleCreatedToChange(event.target.value)}
        />
      </div>

      {links.error !== null && <ErrorBanner error={links.error} />}
      {list.error !== null && <ErrorBanner error={list.error} />}
      {list.loading && list.data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {list.data !== null && (
        <>
          <DataTable
            columns={COLUMNS}
            rows={list.data.content}
            rowKey={(row) => row.batchId}
            emptyMessage="Geen batches gevonden."
          />
          <Pager
            page={list.data.page}
            size={list.data.size}
            totalElements={list.data.totalElements}
            onPageChange={setPage}
            onSizeChange={handleSizeChange}
          />
        </>
      )}
    </div>
  );
}
