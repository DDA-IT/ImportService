/**
 * `/bundles/:bundleId/decisions` — alleen-lezen beslissingsregister, zie
 * `docs/design/frontend-scherm3-bundel-design.md` §10.7, §8.
 *
 * Append-only register van alle beslissingen in chronologische volgorde. Een herziening staat hier
 * als extra regel naast de beslissing die ze herziet; ze overschrijft die niet.
 *
 * NT-11b (V7): soort, bereik, statussen en filter staan in gewoon Nederlands (woordenboek); de ruwe
 * filtertekst zoals de server ze bewaart, staat onder "Technische details (voor support)".
 */

import { useState } from 'react';
import * as bundlesApi from '../../api/bundles.ts';
import type { DecisionRow } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { Term } from '../../terms/Term.tsx';
import type { TermDomain } from '../../terms/index.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { useBundleDetailContext } from './BundleDetailPage.tsx';
import styles from './BundleDecisionsTab.module.css';

function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}

/**
 * Bevriezen en annuleren gaan over de status van de bundel; alle andere beslissingen (ook de automatische
 * goedkeuring bij het bevriezen, die als bereik "hele bundel" bewaard wordt) over de status van mutaties.
 * Daarom kiest de soort van de beslissing het woordenboek, niet het bereik.
 */
function statusDomain(row: DecisionRow): TermDomain {
  return row.decisionKind === 'FREEZE' || row.decisionKind === 'CANCEL' ? 'bundleStatus' : 'mutationStatus';
}

function StatusTransition({ row }: { row: DecisionRow }) {
  const domain = statusDomain(row);
  return (
    <div>
      {row.previousStatus === null || row.previousStatus === undefined ? (
        '—'
      ) : (
        <StatusBadge status={row.previousStatus} domain={domain} />
      )}
      {' → '}
      {row.newStatus === null || row.newStatus === undefined ? (
        '—'
      ) : (
        <StatusBadge status={row.newStatus} domain={domain} />
      )}
    </div>
  );
}

function DecisionScopeLabel({ row }: { row: DecisionRow }) {
  if (row.decisionScope === 'MUTATION' && row.mutationId !== null) {
    return <>Mutatie {row.mutationId}</>;
  }
  return <Term domain="decisionScope" code={row.decisionScope} unknownLabel="Ander bereik" />;
}

/** Het woordenboek voor de waarde van een filterveld; `batchId` en `identityHash` hebben er geen. */
const FILTER_VALUE_DOMAIN: Record<string, { domain: TermDomain; unknownLabel: string } | undefined> = {
  status: { domain: 'mutationStatus', unknownLabel: 'Andere status' },
  statusReason: { domain: 'mutationStatusReason', unknownLabel: 'Andere reden' },
  actionType: { domain: 'mutationAction', unknownLabel: 'Andere soort' },
};

/**
 * De filter van een groepsbeslissing, bewaard als `veld=waarde;veld=waarde`, in gewoon Nederlands. De tekst zoals
 * de server ze bewaart (inclusief de sleutel van een wijzigingsgroep) staat onder "Technische details".
 */
function SelectionFilterText({ filter }: { filter: string }) {
  const parts = filter
    .split(';')
    .filter((part) => part !== '')
    .map((part) => {
      const separator = part.indexOf('=');
      return separator > 0 ? { key: part.substring(0, separator), value: part.substring(separator + 1) } : { key: part, value: '' };
    });
  return (
    <div className={styles.filter}>
      {parts.map((part, index) => {
        const valueTerm = FILTER_VALUE_DOMAIN[part.key];
        return (
          <span key={`${part.key}-${index}`}>
            {index > 0 && '; '}
            <Term domain="selectionFilterField" code={part.key} unknownLabel="Ander filterdeel" />
            {part.key === 'identityHash' ? ': één wijzigingsgroep' : ': '}
            {valueTerm !== undefined ? (
              <Term domain={valueTerm.domain} code={part.value} unknownLabel={valueTerm.unknownLabel} />
            ) : part.key === 'identityHash' ? null : (
              part.value
            )}
          </span>
        );
      })}
      <TechnicalDetails items={[{ name: 'Filter zoals bewaard', value: <code>{filter}</code> }]} />
    </div>
  );
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
    {
      key: 'decisionKind',
      header: 'Soort',
      render: (row) => <Term domain="decisionKind" code={row.decisionKind} unknownLabel="Andere beslissing" />,
    },
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
      render: (row) => (row.selectionFilter === null ? '—' : <SelectionFilterText filter={row.selectionFilter} />),
    },
    {
      key: 'reason',
      header: 'Reden',
      render: (row) => row.reason === null ? '—' : row.reason,
    },
  ];

  return (
    <div className={styles.tab}>
      <WhatIsThis>
        <p>
          Het beslissingsregister is het logboek van de bundel: wie wat goedgekeurd, afgekeurd, bevroren of geannuleerd
          heeft, en wanneer. Er wordt alleen bijgeschreven, nooit aangepast: een herziening staat als extra regel naast
          de beslissing die ze herziet.
        </p>
      </WhatIsThis>

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
