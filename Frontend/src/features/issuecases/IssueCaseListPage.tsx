/**
 * `/issue-cases` — de behandelgevallenlijst (S2-F1, `docs/design/issue-case-design.md` §6). Volledig
 * alleen-lezen: filters (koppeling, status, ernst, issue-code, laatst-gezien-bereik), paginering, en
 * een zichtbare indicator wanneer `hasUnreviewedRecurrence` waar is (ontwerp §2, "Zichtbaarheid van de
 * onderdrukking" — verplicht als kenmerk in deze lijst).
 *
 * Geen enkele schrijfactie: geen Corrigeren/Afwijzen/Heropenen. Dat is S2-F2. Elke rij linkt door naar
 * het geval-detail `/issue-cases/:caseId`.
 */

import { useState } from 'react';
import { Link } from 'react-router-dom';
import * as issueCasesApi from '../../api/issueCases.ts';
import * as importLinksApi from '../../api/importLinks.ts';
import { ISSUE_CASE_STATUSES, ROW_ISSUE_SEVERITIES } from '../../api/types.ts';
import type { IssueCaseRow, IssueCaseStatus, RowIssueSeverity } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { IssueCodeTerm } from '../../terms/IssueCodeTerm.tsx';
import { Term } from '../../terms/Term.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { DICTIONARY, term } from '../../terms/index.ts';
import styles from './IssueCaseListPage.module.css';
import { formatDateTime } from '../../format.ts';

/**
 * De keuzelijst "Soort vaststelling" (NT-11c): de Nederlandse woorden uit het woordenboek, alfabetisch; de waarde
 * is de technische code die de server exact vergelijkt. Een code die (nog) niet in het woordenboek staat, is dus
 * niet te filteren — de lijst zelf toont hem wel, als "Ander probleem".
 */
const ISSUE_CODE_OPTIONS: readonly { code: string; label: string }[] = Object.entries(DICTIONARY.issueCode)
  .map(([code, entry]) => ({ code, label: entry.label }))
  .sort((a, b) => a.label.localeCompare(b.label, 'nl'));

const NO_STATUS_FILTER = '';
const NO_SEVERITY_FILTER = '';
const NO_IMPORT_LINK_FILTER = '';
type StatusFilter = IssueCaseStatus | typeof NO_STATUS_FILTER;
type SeverityFilter = RowIssueSeverity | typeof NO_SEVERITY_FILTER;
type ImportLinkFilter = number | typeof NO_IMPORT_LINK_FILTER;

/** "YYYY-MM-DD" (uit een `<input type="date">`) naar het begin van die dag in UTC. */
function dateInputToInstantStart(value: string): string | undefined {
  return value === '' ? undefined : `${value}T00:00:00.000Z`;
}

/**
 * "YYYY-MM-DD" naar het begin van de dag erna: de backend leest `lastSeenTo` als een halfopen
 * bovengrens ({@code [lastSeenFrom, lastSeenTo)}), dus "tot en met deze dag" vereist de volgende dag
 * als grens (zelfde regel als `WorkQueuePage`).
 */
function dateInputToInstantEndExclusive(value: string): string | undefined {
  if (value === '') {
    return undefined;
  }
  const next = new Date(`${value}T00:00:00.000Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString();
}

/**
 * Zichtbare indicator dat dit geval een waarneming draagt ná de laatste beslissing (ontwerp §2). Geen
 * kleur als enige drager: de tekst zelf zegt wat er aan de hand is.
 */
function RecurrenceBadge({ row }: { row: IssueCaseRow }) {
  if (!row.hasUnreviewedRecurrence) {
    return null;
  }
  const title =
    row.statusChangedAt === null
      ? 'Dit geval is nog nooit beoordeeld.'
      : 'Er is een nieuwe waarneming bijgekomen ná de laatste beslissing.';
  return (
    <span className={styles.recurrenceBadge} title={title} data-testid={`recurrence-${row.id}`}>
      Nieuwe waarneming
    </span>
  );
}

const COLUMNS: readonly DataTableColumn<IssueCaseRow>[] = [
  {
    key: 'id',
    header: 'Geval',
    render: (row) => <Link to={`/issue-cases/${row.id}`}>#{row.id}</Link>,
  },
  { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} domain="issueCaseStatus" /> },
  { key: 'issueCode', header: 'Soort vaststelling', render: (row) => <IssueCodeTerm code={row.issueCode} /> },
  {
    key: 'severity',
    header: 'Ernst',
    render: (row) => <Term domain="severity" code={row.severity} unknownLabel="Andere ernst" />,
  },
  { key: 'observationCount', header: 'Waarnemingen', render: (row) => row.observationCount, align: 'right' },
  {
    key: 'totalOccurrenceCount',
    header: 'Totaal aantal',
    render: (row) => row.totalOccurrenceCount,
    align: 'right',
  },
  { key: 'lastSeenAt', header: 'Laatst gezien', render: (row) => formatDateTime(row.lastSeenAt) },
  { key: 'recurrence', header: 'Onderdrukking', render: (row) => <RecurrenceBadge row={row} /> },
];

export function IssueCaseListPage() {
  const [status, setStatus] = useState<StatusFilter>(NO_STATUS_FILTER);
  const [severity, setSeverity] = useState<SeverityFilter>(NO_SEVERITY_FILTER);
  const [importLinkId, setImportLinkId] = useState<ImportLinkFilter>(NO_IMPORT_LINK_FILTER);
  const [issueCode, setIssueCode] = useState('');
  const [lastSeenFrom, setLastSeenFrom] = useState('');
  const [lastSeenTo, setLastSeenTo] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const importLinkFilterValue = importLinkId === NO_IMPORT_LINK_FILTER ? undefined : importLinkId;

  const linksKey = 'import-links:all';
  const links = useQuery(linksKey, (signal) => importLinksApi.listImportLinks({ size: 200 }, signal));

  const listKey = `issue-cases:${status}:${severity}:${importLinkFilterValue ?? ''}:${issueCode}:${lastSeenFrom}:${lastSeenTo}:${page}:${size}`;
  const list = useQuery(listKey, (signal) =>
    issueCasesApi.listIssueCases(
      {
        status: status === NO_STATUS_FILTER ? undefined : status,
        severity: severity === NO_SEVERITY_FILTER ? undefined : severity,
        importLinkId: importLinkFilterValue,
        issueCode: issueCode === '' ? undefined : issueCode,
        lastSeenFrom: dateInputToInstantStart(lastSeenFrom),
        lastSeenTo: dateInputToInstantEndExclusive(lastSeenTo),
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
  const handleSeverityChange = resetPageAnd(setSeverity);
  const handleImportLinkChange = resetPageAnd(setImportLinkId);
  const handleIssueCodeChange = resetPageAnd(setIssueCode);
  const handleLastSeenFromChange = resetPageAnd(setLastSeenFrom);
  const handleLastSeenToChange = resetPageAnd(setLastSeenTo);

  function handleSizeChange(next: number) {
    setSize(next);
    setPage(0);
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Behandelgevallen</h1>
      <WhatIsThis>
        Een behandelgeval bundelt dezelfde vaststelling uit meerdere leveringen, zodat u ze één keer beoordeelt. Een
        afgewezen geval blijft onderdrukt zolang een identieke vaststelling terugkomt; een nieuwe waarneming na uw
        beslissing krijgt de aanduiding &laquo;Nieuwe waarneming&raquo;. Open een geval om het te beoordelen.
      </WhatIsThis>

      <div className={styles.filters}>
        <label htmlFor="issue-case-status-filter">Status</label>
        <select
          id="issue-case-status-filter"
          value={status}
          onChange={(event) => handleStatusChange(event.target.value as StatusFilter)}
        >
          <option value={NO_STATUS_FILTER}>Alle</option>
          {ISSUE_CASE_STATUSES.map((option) => (
            <option key={option} value={option}>
              {term('issueCaseStatus', option).label}
            </option>
          ))}
        </select>

        <label htmlFor="issue-case-severity-filter">Ernst</label>
        <select
          id="issue-case-severity-filter"
          value={severity}
          onChange={(event) => handleSeverityChange(event.target.value as SeverityFilter)}
        >
          <option value={NO_SEVERITY_FILTER}>Alle</option>
          {ROW_ISSUE_SEVERITIES.map((option) => (
            <option key={option} value={option}>
              {term('severity', option).label}
            </option>
          ))}
        </select>

        <label htmlFor="issue-case-link-filter">Koppeling</label>
        <select
          id="issue-case-link-filter"
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

        <label htmlFor="issue-case-issuecode-filter">Soort vaststelling</label>
        <select
          id="issue-case-issuecode-filter"
          value={issueCode}
          onChange={(event) => handleIssueCodeChange(event.target.value)}
        >
          <option value="">Alle</option>
          {ISSUE_CODE_OPTIONS.map((option) => (
            <option key={option.code} value={option.code}>
              {option.label}
            </option>
          ))}
        </select>

        <label htmlFor="issue-case-last-seen-from">Laatst gezien vanaf</label>
        <input
          id="issue-case-last-seen-from"
          type="date"
          value={lastSeenFrom}
          onChange={(event) => handleLastSeenFromChange(event.target.value)}
        />

        <label htmlFor="issue-case-last-seen-to">Laatst gezien tot en met</label>
        <input
          id="issue-case-last-seen-to"
          type="date"
          value={lastSeenTo}
          onChange={(event) => handleLastSeenToChange(event.target.value)}
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
            rowKey={(row) => row.id}
            emptyMessage="Geen behandelgevallen gevonden."
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
