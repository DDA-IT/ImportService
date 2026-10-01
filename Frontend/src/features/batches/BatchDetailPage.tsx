/**
 * `/batches/:batchId` — batchdetail (alleen-lezen), bouwstappen A-F1 en A-F2, zie `docs/decisions.md`
 * 2026-09-23 "Frontend: batchdetail, scherm (2) levering & screening en de §16-uitbreidingen".
 *
 * Geen enkele schrijfactie op dit scherm: `accept-baseline`, `continue` en bundel-opname horen bij
 * bouwstappen B-F2/B-F3. Alle tellers zijn `number | null`; `null` is "niet vastgesteld" en wordt als "—"
 * getoond, nooit als 0.
 */

import { useState } from 'react';
import { NavLink, useParams } from 'react-router-dom';
import * as batchesApi from '../../api/batches.ts';
import type { BatchDetail } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { MutationList } from '../../components/MutationList/MutationList.tsx';
import type { MutationSource } from '../../components/MutationList/types.ts';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { describeIssue } from '../../terms/IssueCodeTerm.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { Term } from '../../terms/Term.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { BatchActions } from './BatchActions.tsx';
import { BatchDeliverySection } from './BatchDeliverySection.tsx';
import { BatchIssueGroupsSection } from './BatchIssueGroupsSection.tsx';
import { BatchIssuesSection } from './BatchIssuesSection.tsx';
import { CounterLabel, Count, formatDateTime } from './format.tsx';
import styles from './BatchDetailPage.module.css';

function Fact({ label, children }: { label: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className={styles.fact}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

/** De tellers in de volgorde van het scherm; de sleutel is de veldnaam van `BatchDetail` én de woordenboekcode. */
const COUNTER_KEYS = [
  'rawRecordCount',
  'validRecordCount',
  'rejectedRecordCount',
  'filteredOutCount',
  'errorBeforeFilterCount',
  'duplicateIdentityCount',
  'newCount',
  'changedCount',
  'unchangedCount',
  'contentMutationCount',
  'awaitingApprovalCount',
  'identityIncidentCount',
  'bulkIncidentCount',
  'criticalLineCount',
  'criticalIssueCount',
  'warningCount',
] as const satisfies ReadonlyArray<keyof BatchDetail>;

function BatchOverview({ batch }: { batch: BatchDetail }) {
  const blocked = batch.blockedCode === null ? null : describeIssue(batch.blockedCode);
  return (
    <>
      <div className={styles.header}>
        <h1 className={styles.title}>Batch {batch.batchId}</h1>
        <StatusBadge status={batch.status} domain="batchStatus" />
        {batch.validationResult === null ? (
          <span>
            Eindoordeel: <Term domain="validationResult" code={null} />
          </span>
        ) : (
          <StatusBadge status={batch.validationResult} domain="validationResult" />
        )}
      </div>
      <WhatIsThis>
        <p>
          Een batch is de controle van één aangeleverde levering. De status zegt hoe ver die controle staat; het
          eindoordeel zegt wat de controle vond. Een woord met een stippellijn heeft een uitleg: ga er met de muis
          over.
        </p>
      </WhatIsThis>

      {batch.blockedCode !== null && blocked !== null && (
        <div className={styles.blocked} role="alert" data-testid="batch-blocked">
          <strong>Tegengehouden: {blocked.label}</strong>
          <p>{blocked.uitleg}</p>
          <TechnicalDetails
            items={[
              { name: 'Code', value: batch.blockedCode },
              ...(batch.blockedReason !== null ? [{ name: 'Melding van de server', value: batch.blockedReason }] : []),
            ]}
          />
        </div>
      )}

      <dl className={styles.facts} data-testid="batch-facts">
        <Fact label="Koppeling">
          <span title={`Leverancier ${batch.supplierCode} · bibliotheek ${batch.libraryCode}`}>
            {batch.importLinkCode}
          </span>{' '}
          <span className={styles.secondary}>
            ({batch.supplierCode} · {batch.libraryCode})
          </span>
        </Fact>
        <Fact label="Levering">#{batch.deliveryId}</Fact>
        <Fact label="Poging">{batch.attemptNo}</Fact>
        <Fact label="Aangemaakt">
          {formatDateTime(batch.createdAt)}
          {batch.createdBy !== null && ` door ${batch.createdBy}`}
        </Fact>
        <Fact label="Gestart">{formatDateTime(batch.startedAt)}</Fact>
        <Fact label="Afgerond">{formatDateTime(batch.finishedAt)}</Fact>
        <Fact label="Beleid voor nieuwe artikelen">
          <Term domain="creationPolicy" code={batch.creationOutcome} />
          {batch.creationCandidateCount !== null && batch.creationScopeCount !== null && (
            <span className={styles.secondary}>
              {' '}
              ({batch.creationCandidateCount} nieuwe artikelen bij {batch.creationScopeCount} bestaande)
            </span>
          )}
        </Fact>
      </dl>

      <h2 className={styles.sectionTitle}>Tellers</h2>
      <dl className={styles.counters} data-testid="batch-counters">
        {COUNTER_KEYS.map((key) => (
          <Fact key={key} label={<CounterLabel counter={key} />}>
            <Count value={batch[key]} />
          </Fact>
        ))}
      </dl>

      {batch.baselineAcceptedAt !== null && (
        <p className={styles.baseline} data-testid="batch-baseline">
          Nulmeting aanvaard door {batch.baselineAcceptedBy ?? 'onbekend'} op{' '}
          {formatDateTime(batch.baselineAcceptedAt)}
          {batch.baselineAcceptReason !== null && ` — reden: ${batch.baselineAcceptReason}`}
        </p>
      )}
    </>
  );
}

export function BatchDetailPage() {
  const params = useParams<{ batchId: string }>();
  const batchId = Number(params.batchId);
  const validId = Number.isInteger(batchId) && batchId > 0;
  const [selectedGroupId, setSelectedGroupId] = useState<number | null>(null);

  const { data, error, loading, reload } = useQuery(`batch-detail:${params.batchId ?? ''}`, (signal) =>
    validId ? batchesApi.getBatch(batchId, signal) : Promise.reject(new Error('invalid batch id')),
  );

  const mutationSource: MutationSource = {
    key: `batch:${batchId}`,
    fetchPage: (query, signal) => batchesApi.batchMutations(batchId, query, signal),
    // `GET /batches/{id}/mutations` filtert sinds C1/C4 ook op status, statusReason en identityHash; er is
    // geen `batchId`-filter (de batch staat in het pad). Geen rowActions: beslissen gebeurt in een bundel.
    supportedFilters: ['status', 'actionType', 'statusReason', 'identityHash'],
  };

  return (
    <div className={styles.page}>
      <p className={styles.breadcrumb}>
        <NavLink to="/">Werkvoorraad</NavLink>
      </p>

      {!validId && (
        <p role="alert" className={styles.blocked}>
          Ongeldig batchnummer: “{params.batchId}”.
        </p>
      )}
      {validId && error !== null && <ErrorBanner error={error} />}
      {validId && loading && data === null && error === null && <p className={styles.loading}>Bezig met laden…</p>}

      {validId && data !== null && error === null && (
        <>
          <BatchOverview batch={data} />
          <BatchActions batch={data} onChanged={reload} />
          <BatchDeliverySection deliveryId={data.deliveryId} />
          <BatchIssueGroupsSection
            batchId={batchId}
            selectedGroupId={selectedGroupId}
            onSelectGroup={setSelectedGroupId}
          />
          <BatchIssuesSection batchId={batchId} issueGroupId={selectedGroupId} />
          <h2 className={styles.sectionTitle}>Mutaties</h2>
          <WhatIsThis>
            <p>
              Een mutatie is één voorgestelde wijziging aan een artikel: een nieuw artikel, een gewijzigd artikel, een
              herkenningsprobleem of de vastlegging dat de controle klaar is. Hier kunt u ze bekijken; beslissen
              (goedkeuren of afkeuren) gebeurt in een bundel.
            </p>
          </WhatIsThis>
          <MutationList source={mutationSource} emptyMessage="Deze batch heeft (met deze filter) geen mutaties." />
        </>
      )}
    </div>
  );
}
