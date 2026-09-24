/**
 * `/bundles/:bundleId/mutations` — bouwstap F8, zie
 * `docs/design/frontend-scherm3-bundel-design.md` §8, §10.3 en §11.
 *
 * Dit tabblad is een **aanroeper** van het herbruikbare `MutationList`-component: het levert de bron
 * (`GET /bundles/{id}/mutations`, met alle filters die die lijst sinds C1/C4 ondersteunt) en de twee
 * rijacties (goedkeuren/afkeuren, inclusief herziening). De beslisbaarheidsmatrix zelf woont in
 * `bundlePolicy.ts` (§9.2) — niet in het component en niet in deze JSX.
 *
 * Regels uit §10.3 die hier zichtbaar zijn:
 * - goedkeuren: reden optioneel, **behalve** bij een herziening (dan verplicht);
 * - afkeuren: reden altijd verplicht;
 * - een herziening benoemt expliciet wiens eerdere beslissing omgekeerd wordt, en dat beide regels in
 *   het append-only register blijven staan;
 * - `idempotent: true` wordt gemeld ("er is geen tweede regel geschreven"), niet stilgehouden.
 *
 * De groepsactie (§10.4, bouwstap F9) zit in de `toolbar`-slot van de lijst: `GroupDecisionDialog` krijgt
 * daar exact de toegepaste filter en het aantal van de lijst. Dit tabblad stelt zelf geen filter samen.
 */

import { useState } from 'react';
import * as bundlesApi from '../../api/bundles.ts';
import type { MutationRow } from '../../api/types.ts';
import { MutationList } from '../../components/MutationList/MutationList.tsx';
import type { MutationRowAction, MutationSource } from '../../components/MutationList/types.ts';
import { mutationDecisionGate } from './bundlePolicy.ts';
import { GroupDecisionDialog } from './GroupDecisionDialog.tsx';
import { useBundleDetailContext } from './BundleDetailPage.tsx';
import styles from './BundleMutationsTab.module.css';

function formatAmount(value: number | null, currency: string | null): string {
  if (value === null) {
    return '—';
  }
  // Nooit rekenen met bedragen, nooit afronden, nooit een munteenheid aannemen (§9.4).
  const formatted = new Intl.NumberFormat('nl-BE', { maximumFractionDigits: 20 }).format(value);
  return currency === null || currency === '' ? formatted : `${formatted} ${currency}`;
}

function DecisionBody({ row, revision }: { row: MutationRow; revision: boolean }) {
  return (
    <div className={styles.dialogBody}>
      <p>
        Mutatie {row.id} ({row.actionType}, status {row.status}) van batch {row.batchId}.
      </p>
      <p>
        Basisprijs: {formatAmount(row.beforeBasePrice, row.basePriceCurrency)} →{' '}
        {formatAmount(row.afterBasePrice, row.basePriceCurrency)}
      </p>
      {revision && (
        <p className={styles.warning}>
          U keert een eerdere beslissing van {row.decidedBy ?? 'onbekend'} van{' '}
          {row.decidedAt === null ? 'onbekend tijdstip' : new Date(row.decidedAt).toLocaleString('nl-BE')} om.
          Beide beslissingen blijven in het register staan. Een reden is daarom verplicht.
        </p>
      )}
    </div>
  );
}

export function BundleMutationsTab() {
  const { bundle, reloadBundle } = useBundleDetailContext();
  const [notice, setNotice] = useState<string | null>(null);

  const source: MutationSource = {
    key: `bundle:${bundle.id}`,
    fetchPage: (query, signal) => bundlesApi.bundleMutations(bundle.id, query, signal),
    // Deze bron ondersteunt alle vijf; scherm (2) geeft straks een kleinere lijst mee (§11.4) zonder
    // één regel wijziging in het component.
    supportedFilters: ['status', 'batchId', 'actionType', 'statusReason', 'identityHash'],
  };

  function gateFor(row: MutationRow) {
    return mutationDecisionGate(bundle.status, row.actionType, row.status, 'individual');
  }

  function isRevision(row: MutationRow): boolean {
    const gate = gateFor(row);
    return gate.allowed && gate.isRevision === true;
  }

  function report(result: { idempotent: boolean }, label: string) {
    setNotice(
      result.idempotent
        ? `Deze beslissing stond al zo op uw naam; er is geen tweede regel geschreven (${label}).`
        : `${label} vastgelegd in het beslissingsregister.`,
    );
  }

  const rowActions: readonly MutationRowAction[] = [
    {
      id: 'approve',
      label: 'Goedkeuren',
      variant: 'primary',
      // Reden optioneel, behalve bij een herziening: dan verplicht, ook bij goedkeuren (§10.3).
      reasonRequirement: (row) => (isRevision(row) ? 'required' : 'optional'),
      gate: gateFor,
      confirmTitle: (row) =>
        isRevision(row) ? `Beslissing op mutatie ${row.id} herzien naar goedgekeurd` : `Mutatie ${row.id} goedkeuren`,
      confirmBody: (row) => <DecisionBody row={row} revision={isRevision(row)} />,
      run: async (row, input) => {
        const result = await bundlesApi.approve(bundle.id, row.id, {
          decidedBy: input.actor,
          reason: input.reason,
        });
        report(result, 'Goedkeuring');
        return result;
      },
    },
    {
      id: 'reject',
      label: 'Afkeuren',
      variant: 'danger',
      // Afkeuren vereist altijd een reden (§10.3); de server weigert een lege reden ook zelf.
      reasonRequirement: () => 'required',
      gate: gateFor,
      confirmTitle: (row) =>
        isRevision(row) ? `Beslissing op mutatie ${row.id} herzien naar afgekeurd` : `Mutatie ${row.id} afkeuren`,
      confirmBody: (row) => <DecisionBody row={row} revision={isRevision(row)} />,
      run: async (row, input) => {
        const result = await bundlesApi.reject(bundle.id, row.id, {
          decidedBy: input.actor,
          reason: input.reason,
        });
        report(result, 'Afkeuring');
        return result;
      },
    },
  ];

  return (
    <div className={styles.tab}>
      {notice !== null && (
        <p className={styles.notice} role="status">
          {notice}
        </p>
      )}
      <MutationList
        source={source}
        rowActions={rowActions}
        emptyMessage="Deze bundel bevat (met deze filter) geen mutaties."
        onAfterAction={reloadBundle}
        toolbar={({ filter, listedCount, reload }) => (
          <GroupDecisionDialog
            bundleId={bundle.id}
            bundleStatus={bundle.status}
            filter={filter}
            listedCount={listedCount}
            onDecided={(message) => {
              setNotice(message);
              // Expliciete invalidatie (§5): de lijst zelf en de tellers van de bundel.
              reload();
              reloadBundle();
            }}
          />
        )}
      />
    </div>
  );
}
