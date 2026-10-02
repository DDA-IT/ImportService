/**
 * `/bundles/:bundleId` (index) — stand, tellers, audit en de actieknoppen. Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §8, §9.1, §9.3, §10.5, §10.6.
 *
 * De actieknoppen bevriezen en annuleren (F10) openen `FreezeDialog`/`CancelDialog`; een verboden actie
 * wordt **uitgeschakeld getoond met de reden erbij**, nooit verborgen (§9.1). Na een geslaagde actie
 * staat de melding hier, en wordt de bundel herladen (§5: het antwoord is bewijs, niet de enige bron).
 *
 * De twee tellers die het bevriezen bepalen — hoeveel mutaties met status "gepland" er op naam van de bevriezer
 * goedgekeurd worden en hoeveel er nog op een beslissing wachten — komen van `BundleDetail` zelf
 * (bouwstap C2, `docs/decisions.md` 2026-09-23 V4: `countPlanned`/`countUndecided`), zodat de UI per
 * constructie toont wat de server zal doen. Niet meer via twee `size=1`-lijstaanroepen (§9.3): die
 * telden ook identiteitsincidenten met dezelfde status mee en konden dus van de server afwijken.
 *
 * NT-11b (V7): tellers via het woordenboek (`bundleCounter`, woord + uitleg in de tooltip); geen ruwe codes
 * in de zichtbare tekst.
 */

import { useState } from 'react';
import { PERMISSION_APPROVE } from '../../api/types.ts';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import { Term } from '../../terms/Term.tsx';
import { term } from '../../terms/index.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { gateTitle } from '../../actor/gate.ts';
import { bundleActionGate } from './bundlePolicy.ts';
import { useBundleDetailContext } from './BundleDetailPage.tsx';
import { CancelDialog } from './CancelDialog.tsx';
import { FreezeDialog } from './FreezeDialog.tsx';
import styles from './BundleOverviewTab.module.css';
import { formatDateTime } from '../../format.ts';

/** "—" met een tooltip voor een niet-vastgestelde teller (`null`), nooit `0` (§9.3). */
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

type OpenDialog = 'freeze' | 'cancel' | null;

export function BundleOverviewTab() {
  const { bundle, reloadBundle } = useBundleDetailContext();
  const [copied, setCopied] = useState(false);
  const [openDialog, setOpenDialog] = useState<OpenDialog>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const isAssembling = bundle.status === 'ASSEMBLING';

  const approveGate = usePermissionGate(PERMISSION_APPROVE);
  const freezeGate = withPermission(approveGate, bundleActionGate(bundle.status, 'FREEZE'));
  const cancelGate = withPermission(approveGate, bundleActionGate(bundle.status, 'CANCEL'));

  // Een aankondiging, geen blokkade: de knop blijft aan zodat de voorcontrole (de bron van de blokkades,
  // inclusief de conflicten die hier niet te zien zijn) bekeken kan worden. De dialoog blokkeert.
  const undecided = isAssembling ? bundle.awaitingApprovalCount : null;
  const freezeHint =
    undecided !== null && undecided > 0
      ? `Er ${undecided === 1 ? 'wacht' : 'wachten'} nog ${undecided} ${undecided === 1 ? 'mutatie' : 'mutaties'} ` +
        'op een beslissing; de voorcontrole zal bevriezen blokkeren.'
      : `Keurt de resterende mutaties met status "${term('mutationStatus', 'PLANNED').label}" goed op uw naam en legt de bundel vast. Eerst volgt een voorcontrole.`;

  function openDialogFor(dialog: Exclude<OpenDialog, null>) {
    setNotice(null);
    setOpenDialog(dialog);
  }

  /** Na een geslaagde bevriezing/annulering: melden, sluiten, en expliciet herladen (§5). */
  function handleCompleted(message: string) {
    setOpenDialog(null);
    setNotice(message);
    reloadBundle();
  }

  async function handleCopyHash() {
    if (bundle.contentHash === null) {
      return;
    }
    try {
      await navigator.clipboard.writeText(bundle.contentHash);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Klembord kan geweigerd zijn (bv. geen permissie); de hash staat nog altijd zichtbaar op het
      // scherm, dus dit is geen functionele blokkade.
    }
  }

  /** Eén teller met woord en uitleg; `later` zet er "vastgesteld bij het bevriezen" onder zolang de bundel in opbouw is. */
  function counter(code: string, value: number | null, later = false) {
    return (
      <div className={styles.counter} key={code}>
        <dt>
          <Term domain="bundleCounter" code={code} />
        </dt>
        <dd>
          <Count value={value} />
        </dd>
        {later && isAssembling && <p className={styles.counterHint}>vastgesteld bij het bevriezen</p>}
      </div>
    );
  }

  return (
    <div className={styles.tab}>
      <WhatIsThis>
        <p>
          Dit is de samenvatting van de bundel: hoeveel leveringen (batches) en wijzigingen (mutaties) erin zitten en in
          welke toestand ze staan.
        </p>
        <p>
          Bevriezen sluit de bundel af. De mutaties die nog gepland staan, worden in één keer goedgekeurd op uw naam,
          de getallen en de vingerafdruk van de bundel liggen vast, en er kan niets meer bij of af. Een bevroren bundel
          kan alleen nog geannuleerd worden.
        </p>
        <p>
          Annuleren laat de mutaties die nog niet klaar zijn vervallen en geeft de batches weer vrij voor een andere
          bundel.
        </p>
      </WhatIsThis>

      {notice !== null && (
        <p className={styles.resultNotice} role="status">
          {notice}
        </p>
      )}

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Tellers</h2>
        {!isAssembling && (
          <p className={styles.notice}>
            {bundle.status === 'FROZEN' && bundle.frozenAt !== null
              ? `Vastgesteld bij het bevriezen op ${formatDateTime(bundle.frozenAt)}. Deze getallen bewegen niet meer mee.`
              : 'Deze getallen zijn vastgesteld en bewegen niet meer mee.'}
          </p>
        )}
        <dl className={styles.counters}>
          {counter('batchCount', bundle.batchCount)}
          {counter('contentMutationCount', bundle.contentMutationCount)}
          {counter('readyCount', bundle.readyCount)}
          {counter('rejectedCount', bundle.rejectedCount)}
          {counter('blockedCount', bundle.blockedCount)}
          {counter('identityIncidentCount', bundle.identityIncidentCount)}
          {counter('expiredCount', bundle.expiredCount, true)}
          {counter('bulkIncidentCount', bundle.bulkIncidentCount, true)}
          {counter('criticalIssueCount', bundle.criticalIssueCount, true)}
          {counter('warningCount', bundle.warningCount, true)}
          {isAssembling && (
            <>
              {counter('plannedCount', bundle.plannedCount)}
              {counter('awaitingApprovalCount', bundle.awaitingApprovalCount)}
            </>
          )}
        </dl>

        {isAssembling && bundle.staleMutationCount !== null && bundle.staleMutationCount > 0 && (
          <p className={styles.staleWarning} role="alert">
            De bekende artikelgegevens zijn veranderd sinds de controle van de levering ({bundle.staleMutationCount}{' '}
            mutatie(s)); bevriezen zal geweigerd worden. Controleer de levering opnieuw.
          </p>
        )}

        {bundle.contentHash !== null && (
          <p className={styles.hash}>
            Vingerafdruk van de bundel: <code>{bundle.contentHash.slice(0, 16)}…</code>{' '}
            <button type="button" className={styles.copyButton} onClick={handleCopyHash}>
              {copied ? 'Gekopieerd' : 'Kopieer de volledige vingerafdruk'}
            </button>
          </p>
        )}
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Wie heeft wat gedaan</h2>
        <dl className={styles.audit}>
          <div className={styles.auditRow}>
            <dt>Aangemaakt door</dt>
            <dd>
              {bundle.createdBy} op {formatDateTime(bundle.createdAt)}
            </dd>
          </div>
          {bundle.frozenBy !== null && (
            <div className={styles.auditRow}>
              <dt>Bevroren door</dt>
              <dd>
                {bundle.frozenBy} op {formatDateTime(bundle.frozenAt)}
                {bundle.frozenReason !== null && ` — reden: ${bundle.frozenReason}`}
              </dd>
            </div>
          )}
          {bundle.cancelledBy !== null && (
            <div className={styles.auditRow}>
              <dt>Geannuleerd door</dt>
              <dd>
                {bundle.cancelledBy} op {formatDateTime(bundle.cancelledAt)}
                {bundle.cancelledReason !== null && ` — reden: ${bundle.cancelledReason}`}
              </dd>
            </div>
          )}
        </dl>
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Acties</h2>
        <div className={styles.actions}>
          <div className={styles.actionRow}>
            <button
              type="button"
              className={styles.actionButton}
              disabled={!freezeGate.allowed}
              title={gateTitle(freezeGate)}
              onClick={() => openDialogFor('freeze')}
            >
              Bevriezen
            </button>
            <p className={styles.actionReason}>{freezeGate.allowed ? freezeHint : freezeGate.reason}</p>
          </div>
          <div className={styles.actionRow}>
            <button
              type="button"
              className={styles.actionButton}
              disabled={!cancelGate.allowed}
              title={gateTitle(cancelGate)}
              onClick={() => openDialogFor('cancel')}
            >
              Annuleren
            </button>
            <p className={styles.actionReason}>
              {cancelGate.allowed
                ? 'Laat de niet-afgeronde mutaties definitief vervallen en geeft de batches vrij.'
                : cancelGate.reason}
            </p>
          </div>
        </div>
      </section>

      {/* Pas gemount bij het openen: elke opening begint met een verse voorcontrole en zonder oude fout. */}
      {openDialog === 'freeze' && (
        <FreezeDialog bundle={bundle} onClose={() => setOpenDialog(null)} onFrozen={handleCompleted} />
      )}
      {openDialog === 'cancel' && (
        <CancelDialog bundle={bundle} onClose={() => setOpenDialog(null)} onCancelled={handleCompleted} />
      )}
    </div>
  );
}
