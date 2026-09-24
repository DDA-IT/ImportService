/**
 * De groepsactie op scherm (3) — bouwstap F9, zie `docs/design/frontend-scherm3-bundel-design.md` §10.4
 * en `docs/decisions.md` 2026-09-24 (C5: `identityHash` en `statusReason` horen in de groepsfilter).
 *
 * Dit is de zwaarste hefboom van het scherm: één bevestiging kan duizenden mutaties goedkeuren zonder
 * tweede goedkeurder. Daarom:
 *
 * 1. **De filter is exact de zichtbare lijstfilter.** Dit component heeft geen eigen filterformulier en
 *    geen eigen filterstate: het krijgt `filter` van `MutationList` (de toolbar-slot, hetzelfde object
 *    waaruit de lijst haar query bouwt) en zet die ongewijzigd om met `toDecisionFilter`. Bij het
 *    openen van de bevestiging wordt die filter vastgeklikt: wat in de dialoog staat, is letterlijk wat
 *    verstuurd wordt.
 * 2. **Het aantal staat vóór de bevestiging in beeld**: `totalElements` van de lijst met precies deze
 *    filter (§10.4 punt 3). Het is een bovengrens — C5 garandeert `affectedCount ≤ totalElements` — en
 *    de dialoog zegt waarom het werkelijke aantal lager kan uitvallen.
 * 3. **Geen typ-bevestiging** (§10.4 punt 6): binnen `ASSEMBLING` is elke groepsbeslissing per mutatie
 *    te herzien. Wel de redeneis van de backend: verplicht bij afkeuren.
 * 4. **Geen stille success**: een fout blijft in de open dialoog staan met haar stabiele code; een
 *    antwoord met `affectedCount = 0` wordt als zodanig gemeld (§10.4 punt 5).
 */

import { useState, type ReactNode } from 'react';
import * as bundlesApi from '../../api/bundles.ts';
import type {
  DecideGroupRequest,
  DecisionFilter,
  GroupDecisionView,
  PublicationBundleStatus,
} from '../../api/types.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import type { MutationFilter } from '../../components/MutationList/types.ts';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { groupDecisionGate } from './bundlePolicy.ts';
import { toDecisionFilter } from './groupDecisionFilter.ts';
import styles from './GroupDecisionDialog.module.css';

type GroupDecisionKind = 'APPROVE' | 'REJECT';

export type GroupDecisionDialogProps = {
  bundleId: number;
  bundleStatus: PublicationBundleStatus;
  /** De toegepaste filter van de lijst (`MutationListToolbarContext.filter`), nooit een kopie. */
  filter: MutationFilter;
  /** `totalElements` van de lijst met precies deze filter, of `null` zolang dat niet vaststaat. */
  listedCount: number | null;
  /** Na een geslaagde groepsbeslissing: de melding voor de gebruiker; de ouder ververst lijst en tellers. */
  onDecided: (message: string) => void;
};

/** Wat er bij het openen van de bevestiging vastgeklikt wordt. */
type Pending = { kind: GroupDecisionKind; filter: DecisionFilter; listedCount: number };

const LABELS: Record<GroupDecisionKind, { verb: string; past: string }> = {
  APPROVE: { verb: 'goedkeuren', past: 'goedgekeurd' },
  REJECT: { verb: 'afkeuren', past: 'afgekeurd' },
};

function mutationsWord(count: number): string {
  return count === 1 ? 'mutatie' : 'mutaties';
}

/** De filter, veld per veld, zoals hij verstuurd wordt. */
function FilterSummary({ filter }: { filter: DecisionFilter }) {
  const entries: Array<[string, ReactNode]> = [];
  if (filter.batchId !== undefined) {
    entries.push(['Batch', filter.batchId]);
  }
  if (filter.status !== undefined) {
    entries.push(['Status', filter.status]);
  }
  if (filter.actionType !== undefined) {
    entries.push(['Soort', filter.actionType]);
  }
  if (filter.statusReason !== undefined) {
    entries.push(['Statusreden', filter.statusReason]);
  }
  if (filter.identityHash !== undefined) {
    entries.push(['Wijzigingsgroep', <code key="hash">{filter.identityHash}</code>]);
  }
  if (entries.length === 0) {
    return <p className={styles.filterEmpty}>Geen filter ingesteld.</p>;
  }
  return (
    <dl className={styles.filterList} aria-label="Filter van de groepsactie">
      {entries.map(([label, value]) => (
        <div key={label} className={styles.filterEntry}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function resultMessage(result: GroupDecisionView, pending: Pending): string {
  if (result.affectedCount === 0 || result.decisionId === null) {
    // §10.4 punt 5, letterlijk.
    return 'Er voldeed niets (meer) aan de selectie; er is bewust geen beslissingsregel geschreven.';
  }
  const base =
    `Groepsbeslissing #${result.decisionId} vastgelegd: ${result.affectedCount} ` +
    `${mutationsWord(result.affectedCount)} ${LABELS[pending.kind].past} (filter: ${result.selectionFilter}).`;
  if (result.affectedCount < pending.listedCount) {
    return (
      `${base} De lijst toonde ${pending.listedCount}; de overige vielen buiten wat een groepsactie raakt ` +
      '(geblokkeerd, identiteitsincident, importmarkering of al beslist).'
    );
  }
  return base;
}

export function GroupDecisionDialog({ bundleId, bundleStatus, filter, listedCount, onDecided }: GroupDecisionDialogProps) {
  const [pending, setPending] = useState<Pending | null>(null);
  const runner = useAction((body: DecideGroupRequest) => bundlesApi.decideGroup(bundleId, body));

  // Eén omzetting, uit de filter die de lijst zelf gebruikt; nooit samengesteld uit iets anders.
  const decisionFilter = toDecisionFilter(filter);
  const gate = groupDecisionGate(bundleStatus, decisionFilter, listedCount);

  function open(kind: GroupDecisionKind) {
    if (!gate.allowed || listedCount === null) {
      return;
    }
    runner.reset();
    setPending({ kind, filter: decisionFilter, listedCount });
  }

  function close() {
    setPending(null);
    runner.reset();
  }

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    if (pending === null) {
      return;
    }
    const result = await runner.execute({
      decisionKind: pending.kind,
      decidedBy: input.actor,
      reason: input.reason,
      filter: pending.filter,
    });
    if (result === undefined) {
      // Mislukt: de dialoog blijft open met de foutmelding (inclusief de stabiele backendcode).
      return;
    }
    const message = resultMessage(result, pending);
    close();
    onDecided(message);
  }

  const countLabel = listedCount === null ? '…' : String(listedCount);

  return (
    <section className={styles.panel} aria-label="Groepsactie">
      <div className={styles.header}>
        <h3 className={styles.title}>Groepsactie op de gefilterde lijst</h3>
        <p className={styles.explanation}>
          Beslist over <strong>alle</strong> mutaties die aan de filter hieronder voldoen — niet alleen over
          de zichtbare pagina. De filter is die van de lijst; pas hem daar aan.
        </p>
      </div>

      <FilterSummary filter={decisionFilter} />

      <div className={styles.actions}>
        <button
          type="button"
          className={styles.primaryButton}
          disabled={!gate.allowed}
          title={gate.allowed ? undefined : gate.reason}
          onClick={() => open('APPROVE')}
        >
          Groep goedkeuren ({countLabel})
        </button>
        <button
          type="button"
          className={styles.dangerButton}
          disabled={!gate.allowed}
          title={gate.allowed ? undefined : gate.reason}
          onClick={() => open('REJECT')}
        >
          Groep afkeuren ({countLabel})
        </button>
      </div>
      {/* Een verboden actie wordt uitgeschakeld getoond MET de reden, als tekst — niet alleen in een tooltip. */}
      {!gate.allowed && <p className={styles.gateReason}>{gate.reason}</p>}

      <ConfirmDialog
        open={pending !== null}
        title={
          pending === null
            ? ''
            : `${pending.listedCount} ${mutationsWord(pending.listedCount)} ${LABELS[pending.kind].verb} (groepsactie)`
        }
        body={
          pending === null ? undefined : (
            <div className={styles.dialogBody}>
              <p className={styles.count}>
                De lijst toont <strong>{pending.listedCount}</strong> {mutationsWord(pending.listedCount)} met
                deze filter. De actie raakt er hoogstens zoveel.
              </p>
              <FilterSummary filter={pending.filter} />
              <p>
                Deze actie raakt nooit een geblokkeerde mutatie, een identiteitsincident, de importmarkering of
                een mutatie die al een beslissing draagt.
              </p>
              <p className={styles.warning}>
                Eén bevestiging beslist over al deze mutaties tegelijk, op uw naam en zonder tweede
                goedkeurder. Zolang de bundel in opbouw is, kan elke mutatie nadien nog individueel herzien
                worden.
              </p>
            </div>
          )
        }
        reasonRequirement={pending?.kind === 'REJECT' ? 'required' : 'optional'}
        variant={pending?.kind === 'REJECT' ? 'danger' : 'primary'}
        confirmLabel={pending === null ? 'Bevestigen' : `Groep ${LABELS[pending.kind].verb}`}
        pending={runner.pending}
        error={runner.error !== null ? <ErrorBanner error={runner.error} /> : undefined}
        onConfirm={handleConfirm}
        onCancel={close}
      />
    </section>
  );
}
