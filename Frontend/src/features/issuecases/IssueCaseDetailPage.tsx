/**
 * `/issue-cases/:caseId` — het behandelgeval-detail (S2-F1/S2-F2, `docs/design/issue-case-design.md`
 * §6). Toont de classificatie, status, tellers en de reden/actor van de laatste beslissing, plus de
 * gekoppelde waarnemingen (`observations`) en de volledige gebeurtenissengeschiedenis (`events`,
 * chronologisch).
 *
 * Sinds S2-F2: de afhandelacties (Corrigeren/Afwijzen/Heropenen, recht MANAGE) staan hier op het
 * detail via `IssueCaseActions`, nooit op de lijst (`IssueCaseListPage` blijft alleen-lezen). Na een
 * geslaagde actie herladen zowel het geval als de gebeurtenissengeschiedenis.
 */

import { NavLink, useParams } from 'react-router-dom';
import * as issueCasesApi from '../../api/issueCases.ts';
import type { IssueCaseEventRow, IssueCaseObservationRow, IssueCaseRow } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { IssueCodeTerm } from '../../terms/IssueCodeTerm.tsx';
import { Term } from '../../terms/Term.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { IssueCaseActions } from './IssueCaseActions.tsx';
import styles from './IssueCaseDetailPage.module.css';

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className={styles.fact}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}

function CaseOverview({ issueCase }: { issueCase: IssueCaseRow }) {
  return (
    <>
      <div className={styles.header}>
        <h1 className={styles.title}>Behandelgeval #{issueCase.id}</h1>
        <StatusBadge status={issueCase.status} domain="issueCaseStatus" />
        {issueCase.hasUnreviewedRecurrence && (
          <span className={styles.recurrenceBadge} data-testid="issue-case-recurrence">
            Nieuwe waarneming
          </span>
        )}
      </div>

      <WhatIsThis>
        Een behandelgeval bundelt dezelfde vaststelling uit meerdere leveringen, zodat u ze één keer beoordeelt.
        Een afgewezen geval blijft onderdrukt zolang een identieke vaststelling terugkomt; een nieuwe waarneming
        na uw beslissing wordt zichtbaar gemaakt.
      </WhatIsThis>

      <dl className={styles.facts} data-testid="issue-case-facts">
        <Fact label="Koppeling">#{issueCase.importLinkId}</Fact>
        <Fact label="Soort vaststelling">
          <IssueCodeTerm code={issueCase.issueCode} />
        </Fact>
        <Fact label="Ernst">
          <Term domain="severity" code={issueCase.severity} unknownLabel="Andere ernst" />
        </Fact>
        <Fact label="Onderwerp">
          <Term domain="issueDomain" code={issueCase.issueDomain} unknownLabel="Ander onderwerp" />
        </Fact>
        <Fact label="Waar vastgesteld">
          <Term domain="controlLevel" code={issueCase.controlLevel} unknownLabel="Ander niveau" />
        </Fact>
        <Fact label="Gevolg voor">
          <Term domain="impactScope" code={issueCase.impactScope} unknownLabel="Andere reikwijdte" />
        </Fact>
        <Fact label="Soort samenvatting">
          <Term domain="issueIncidentKind" code={issueCase.incidentKind} unknownLabel="Andere samenvatting" />
        </Fact>
        {issueCase.priceComponentCode !== null && (
          <Fact label="Prijsonderdeel">
            <Term domain="priceComponent" code={issueCase.priceComponentCode} unknownLabel="Ander prijsonderdeel" />
          </Fact>
        )}
        {issueCase.referenceType !== null && (
          <Fact label="Soort verwijzing">
            <Term domain="referenceType" code={issueCase.referenceType} unknownLabel="Andere verwijzing" />
          </Fact>
        )}
      </dl>
      <TechnicalDetails
        items={[
          { name: 'Code van de vaststelling', value: issueCase.issueCode },
          { name: 'Signatuur', value: issueCase.signature },
          ...(issueCase.priceComponentCode === null
            ? []
            : [{ name: 'Code van het prijsonderdeel', value: issueCase.priceComponentCode }]),
        ]}
      />

      <h2 className={styles.sectionTitle}>Tellers</h2>
      <dl className={styles.facts} data-testid="issue-case-counters">
        <Fact label="Waarnemingen">{issueCase.observationCount}</Fact>
        <Fact label="Totaal aantal">{issueCase.totalOccurrenceCount}</Fact>
        <Fact label="Heropend">{issueCase.reopenCount}</Fact>
        <Fact label="Eerst gezien">{formatDateTime(issueCase.firstSeenAt)}</Fact>
        <Fact label="Laatst gezien">{formatDateTime(issueCase.lastSeenAt)}</Fact>
      </dl>

      <h2 className={styles.sectionTitle}>Laatste beslissing</h2>
      {issueCase.statusChangedAt === null ? (
        <p className={styles.notEstablished} data-testid="issue-case-no-decision">
          Nog geen beslissing genomen.
        </p>
      ) : (
        <dl className={styles.facts} data-testid="issue-case-decision">
          <Fact label="Gewijzigd op">{formatDateTime(issueCase.statusChangedAt)}</Fact>
          <Fact label="Door">{issueCase.statusChangedBy ?? 'systeem'}</Fact>
          <Fact label="Reden">{issueCase.statusReason ?? '—'}</Fact>
        </dl>
      )}
    </>
  );
}

const OBSERVATION_COLUMNS: readonly DataTableColumn<IssueCaseObservationRow>[] = [
  {
    key: 'batchId',
    header: 'Batch',
    render: (row) => <NavLink to={`/batches/${row.batchId}`}>#{row.batchId}</NavLink>,
  },
  { key: 'deliveryId', header: 'Levering', render: (row) => `#${row.deliveryId}` },
  { key: 'attemptNo', header: 'Poging', render: (row) => row.attemptNo, align: 'right' },
  { key: 'definitionRevisionId', header: 'Versie van de beschrijving', render: (row) => `#${row.definitionRevisionId}` },
  { key: 'occurrenceCount', header: 'Aantal', render: (row) => row.occurrenceCount, align: 'right' },
  { key: 'firstDetectedAt', header: 'Eerst vastgesteld', render: (row) => formatDateTime(row.firstDetectedAt) },
  { key: 'lastDetectedAt', header: 'Laatst vastgesteld', render: (row) => formatDateTime(row.lastDetectedAt) },
];

const EVENT_COLUMNS: readonly DataTableColumn<IssueCaseEventRow>[] = [
  { key: 'changedAt', header: 'Tijdstip', render: (row) => formatDateTime(row.changedAt) },
  {
    key: 'eventKind',
    header: 'Soort',
    render: (row) => <Term domain="issueEventKind" code={row.eventKind} unknownLabel="Andere gebeurtenis" />,
  },
  {
    key: 'transition',
    header: 'Overgang',
    render: (row) => (
      <>
        {row.previousStatus !== null && (
          <>
            <Term domain="issueCaseStatus" code={row.previousStatus} unknownLabel="Andere status" /> →{' '}
          </>
        )}
        <Term domain="issueCaseStatus" code={row.newStatus} unknownLabel="Andere status" />
      </>
    ),
  },
  {
    key: 'source',
    header: 'Bron',
    render: (row) => <Term domain="issueEventSource" code={row.source} unknownLabel="Andere bron" />,
  },
  { key: 'changedBy', header: 'Door', render: (row) => row.changedBy ?? 'systeem' },
  { key: 'reason', header: 'Reden', render: (row) => row.reason },
  {
    key: 'observationBatchId',
    header: 'Batch',
    render: (row) => (row.observationBatchId === null ? '—' : `#${row.observationBatchId}`),
  },
];

export function IssueCaseDetailPage() {
  const params = useParams<{ caseId: string }>();
  const caseId = Number(params.caseId);
  const validId = Number.isInteger(caseId) && caseId > 0;

  const { data, error, loading, reload } = useQuery(`issue-case-detail:${params.caseId ?? ''}`, (signal) =>
    validId ? issueCasesApi.getIssueCase(caseId, signal) : Promise.reject(new Error('invalid issue case id')),
  );

  const observations = useQuery(`issue-case-observations:${params.caseId ?? ''}`, (signal) =>
    validId
      ? issueCasesApi.getIssueCaseObservations(caseId, signal)
      : Promise.reject(new Error('invalid issue case id')),
  );

  const events = useQuery(`issue-case-events:${params.caseId ?? ''}`, (signal) =>
    validId ? issueCasesApi.getIssueCaseEvents(caseId, signal) : Promise.reject(new Error('invalid issue case id')),
  );

  // Na een geslaagde afhandelactie: het geval zelf én de gebeurtenissengeschiedenis herladen (nieuwe
  // status/reden/actor resp. de nieuwe STATUS_CHANGE-regel). De waarnemingen wijzigen niet door een
  // menselijke statuswijziging (§4: geen effect op observation_count/tellers), dus die blijven staan.
  function handleActionsChanged() {
    reload();
    events.reload();
  }

  return (
    <div className={styles.page}>
      <p className={styles.breadcrumb}>
        <NavLink to="/issue-cases">Behandelgevallen</NavLink>
      </p>

      {!validId && (
        <p role="alert" className={styles.notEstablished}>
          Ongeldig gevalnummer: “{params.caseId}”.
        </p>
      )}
      {validId && error !== null && <ErrorBanner error={error} />}
      {validId && loading && data === null && error === null && <p className={styles.loading}>Bezig met laden…</p>}

      {validId && data !== null && error === null && (
        <>
          <CaseOverview issueCase={data} />
          <IssueCaseActions issueCase={data} onChanged={handleActionsChanged} />

          <h2 className={styles.sectionTitle}>Waarnemingen</h2>
          {observations.error !== null && <ErrorBanner error={observations.error} />}
          {observations.data !== null && (
            <div data-testid="issue-case-observations">
              <DataTable
                columns={OBSERVATION_COLUMNS}
                rows={observations.data}
                rowKey={(row) => row.issueGroupId}
                emptyMessage="Geen waarnemingen (meer) gekoppeld aan dit geval."
              />
            </div>
          )}

          <h2 className={styles.sectionTitle}>Gebeurtenissen</h2>
          {events.error !== null && <ErrorBanner error={events.error} />}
          {events.data !== null && (
            <div data-testid="issue-case-events">
              <DataTable
                columns={EVENT_COLUMNS}
                rows={events.data}
                rowKey={(row) => row.id}
                emptyMessage="Geen gebeurtenissen."
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}
