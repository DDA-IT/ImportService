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
import { BatchDeliverySection } from './BatchDeliverySection.tsx';
import { BatchIssueGroupsSection } from './BatchIssueGroupsSection.tsx';
import { BatchIssuesSection } from './BatchIssuesSection.tsx';
import { Count, formatDateTime } from './format.tsx';
import styles from './BatchDetailPage.module.css';

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className={styles.fact}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function BatchOverview({ batch }: { batch: BatchDetail }) {
  return (
    <>
      <div className={styles.header}>
        <h1 className={styles.title}>Batch {batch.batchId}</h1>
        <StatusBadge status={batch.status} />
        {batch.validationResult === null ? (
          <span className={styles.notEstablished} title="Het eindoordeel staat nog niet vast">
            Eindoordeel niet vastgesteld
          </span>
        ) : (
          <StatusBadge status={batch.validationResult} />
        )}
      </div>

      {batch.blockedCode !== null && (
        <div className={styles.blocked} role="alert" data-testid="batch-blocked">
          <strong>Geblokkeerd: {batch.blockedCode}</strong>
          {batch.blockedReason !== null && <p>{batch.blockedReason}</p>}
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
        <Fact label="Creatiebeleid">
          {batch.creationOutcome ?? '—'}
          {batch.creationCandidateCount !== null && batch.creationScopeCount !== null && (
            <span className={styles.secondary}>
              {' '}
              ({batch.creationCandidateCount} van {batch.creationScopeCount})
            </span>
          )}
        </Fact>
      </dl>

      <h2 className={styles.sectionTitle}>Tellers</h2>
      <dl className={styles.counters} data-testid="batch-counters">
        <Fact label="Ruwe records">
          <Count value={batch.rawRecordCount} />
        </Fact>
        <Fact label="Geldig">
          <Count value={batch.validRecordCount} />
        </Fact>
        <Fact label="Verworpen">
          <Count value={batch.rejectedRecordCount} />
        </Fact>
        <Fact label="Buiten scope gefilterd">
          <Count value={batch.filteredOutCount} />
        </Fact>
        <Fact label="Fout vóór filter">
          <Count value={batch.errorBeforeFilterCount} />
        </Fact>
        <Fact label="Dubbele identiteit">
          <Count value={batch.duplicateIdentityCount} />
        </Fact>
        <Fact label="Nieuw">
          <Count value={batch.newCount} />
        </Fact>
        <Fact label="Gewijzigd">
          <Count value={batch.changedCount} />
        </Fact>
        <Fact label="Ongewijzigd">
          <Count value={batch.unchangedCount} />
        </Fact>
        <Fact label="Inhoudsmutaties">
          <Count value={batch.contentMutationCount} />
        </Fact>
        <Fact label="Wacht op goedkeuring">
          <Count value={batch.awaitingApprovalCount} />
        </Fact>
        <Fact label="Identiteitsincidenten">
          <Count value={batch.identityIncidentCount} />
        </Fact>
        <Fact label="Bulkincidenten">
          <Count value={batch.bulkIncidentCount} />
        </Fact>
        <Fact label="Kritieke regels">
          <Count value={batch.criticalLineCount} />
        </Fact>
        <Fact label="Kritieke issues">
          <Count value={batch.criticalIssueCount} />
        </Fact>
        <Fact label="Waarschuwingen">
          <Count value={batch.warningCount} />
        </Fact>
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

  const { data, error, loading } = useQuery(`batch-detail:${params.batchId ?? ''}`, (signal) =>
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
          <BatchDeliverySection deliveryId={data.deliveryId} />
          <BatchIssueGroupsSection
            batchId={batchId}
            selectedGroupId={selectedGroupId}
            onSelectGroup={setSelectedGroupId}
          />
          <BatchIssuesSection batchId={batchId} issueGroupId={selectedGroupId} />
          <h2 className={styles.sectionTitle}>Mutaties</h2>
          <MutationList source={mutationSource} emptyMessage="Deze batch heeft (met deze filter) geen mutaties." />
        </>
      )}
    </div>
  );
}
