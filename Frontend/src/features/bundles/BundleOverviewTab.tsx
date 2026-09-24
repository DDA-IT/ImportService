/**
 * `/bundles/:bundleId` (index) — stand, tellers, audit en de actieknoppen. Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §8, §9.1, §9.3, §10.5, §10.6.
 *
 * De actieknoppen bevriezen en annuleren (F10) openen `FreezeDialog`/`CancelDialog`; een verboden actie
 * wordt **uitgeschakeld getoond met de reden erbij**, nooit verborgen (§9.1). Na een geslaagde actie
 * staat de melding hier, en wordt de bundel herladen (§5: het antwoord is bewijs, niet de enige bron).
 *
 * De twee tellers die het bevriezen bepalen — hoeveel `PLANNED` er op naam van de bevriezer goedgekeurd
 * wordt en hoeveel `AWAITING_APPROVAL` het bevriezen blokkeert — komen van `BundleDetail` zelf
 * (bouwstap C2, `docs/decisions.md` 2026-09-23 V4: `countPlanned`/`countUndecided`), zodat de UI per
 * constructie toont wat de server zal doen. Niet meer via twee `size=1`-lijstaanroepen (§9.3): die
 * telden ook identiteitsincidenten met dezelfde status mee en konden dus van de server afwijken.
 */

import { useState } from 'react';
import { bundleActionGate } from './bundlePolicy.ts';
import { useBundleDetailContext } from './BundleDetailPage.tsx';
import { CancelDialog } from './CancelDialog.tsx';
import { FreezeDialog } from './FreezeDialog.tsx';
import styles from './BundleOverviewTab.module.css';

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

function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}

type OpenDialog = 'freeze' | 'cancel' | null;

export function BundleOverviewTab() {
  const { bundle, reloadBundle } = useBundleDetailContext();
  const [copied, setCopied] = useState(false);
  const [openDialog, setOpenDialog] = useState<OpenDialog>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const isAssembling = bundle.status === 'ASSEMBLING';

  const freezeGate = bundleActionGate(bundle.status, 'FREEZE');
  const cancelGate = bundleActionGate(bundle.status, 'CANCEL');

  // Een aankondiging, geen blokkade: de knop blijft aan zodat de voorvlucht (de bron van de blokkades,
  // inclusief de conflicten die hier niet te zien zijn) bekeken kan worden. De dialoog blokkeert.
  const undecided = isAssembling ? bundle.awaitingApprovalCount : null;
  const freezeHint =
    undecided !== null && undecided > 0
      ? `Er ${undecided === 1 ? 'wacht' : 'wachten'} nog ${undecided} ${undecided === 1 ? 'mutatie' : 'mutaties'} ` +
        'op een beslissing (AWAITING_APPROVAL); de voorvlucht zal bevriezen blokkeren ' +
        '(BUNDLE_HAS_UNDECIDED_MUTATIONS).'
      : 'Keurt de resterende PLANNED-mutaties goed op uw naam en legt de bundel vast. Eerst volgt een voorvlucht.';

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

  return (
    <div className={styles.tab}>
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
          <div className={styles.counter}>
            <dt>Batches</dt>
            <dd>
              <Count value={bundle.batchCount} />
            </dd>
          </div>
          <div className={styles.counter}>
            <dt>Mutaties</dt>
            <dd>
              <Count value={bundle.contentMutationCount} />
            </dd>
          </div>
          <div className={styles.counter}>
            <dt>Gereed</dt>
            <dd>
              <Count value={bundle.readyCount} />
            </dd>
          </div>
          <div className={styles.counter}>
            <dt>Afgekeurd</dt>
            <dd>
              <Count value={bundle.rejectedCount} />
            </dd>
          </div>
          <div className={styles.counter}>
            <dt>Geblokkeerd</dt>
            <dd>
              <Count value={bundle.blockedCount} />
            </dd>
          </div>
          <div className={styles.counter}>
            <dt>Identiteitsincidenten</dt>
            <dd>
              <Count value={bundle.identityIncidentCount} />
            </dd>
          </div>
          <div className={styles.counter}>
            <dt>Vervallen</dt>
            <dd>
              <Count value={bundle.expiredCount} />
            </dd>
            {isAssembling && <p className={styles.counterHint}>vastgesteld bij het bevriezen</p>}
          </div>
          <div className={styles.counter}>
            <dt>Bulkincidenten</dt>
            <dd>
              <Count value={bundle.bulkIncidentCount} />
            </dd>
            {isAssembling && <p className={styles.counterHint}>vastgesteld bij het bevriezen</p>}
          </div>
          <div className={styles.counter}>
            <dt>Kritieke issues</dt>
            <dd>
              <Count value={bundle.criticalIssueCount} />
            </dd>
            {isAssembling && <p className={styles.counterHint}>vastgesteld bij het bevriezen</p>}
          </div>
          <div className={styles.counter}>
            <dt>Waarschuwingen</dt>
            <dd>
              <Count value={bundle.warningCount} />
            </dd>
            {isAssembling && <p className={styles.counterHint}>vastgesteld bij het bevriezen</p>}
          </div>
          {isAssembling && (
            <>
              <div className={styles.counter}>
                <dt>Wordt bij bevriezen goedgekeurd (PLANNED)</dt>
                <dd>
                  <Count value={bundle.plannedCount} />
                </dd>
              </div>
              <div className={styles.counter}>
                <dt>Wacht op beslissing (AWAITING_APPROVAL)</dt>
                <dd>
                  <Count value={bundle.awaitingApprovalCount} />
                </dd>
              </div>
            </>
          )}
        </dl>

        {isAssembling && bundle.staleMutationCount !== null && bundle.staleMutationCount > 0 && (
          <p className={styles.staleWarning} role="alert">
            De bronstaat is verschoven sinds de screening ({bundle.staleMutationCount} mutatie(s));
            bevriezen zal geweigerd worden (SOURCE_STATE_CHANGED_SINCE_SCREENING). Screen de levering
            opnieuw.
          </p>
        )}

        {bundle.contentHash !== null && (
          <p className={styles.hash}>
            Bundelhash: <code>{bundle.contentHash.slice(0, 16)}…</code>{' '}
            <button type="button" className={styles.copyButton} onClick={handleCopyHash}>
              {copied ? 'Gekopieerd' : 'Kopieer volledige hash'}
            </button>
          </p>
        )}
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Audit</h2>
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
              title={freezeGate.allowed ? undefined : freezeGate.reason}
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
              title={cancelGate.allowed ? undefined : cancelGate.reason}
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

      {/* Pas gemount bij het openen: elke opening begint met een verse voorvlucht en zonder oude fout. */}
      {openDialog === 'freeze' && (
        <FreezeDialog bundle={bundle} onClose={() => setOpenDialog(null)} onFrozen={handleCompleted} />
      )}
      {openDialog === 'cancel' && (
        <CancelDialog bundle={bundle} onClose={() => setOpenDialog(null)} onCancelled={handleCompleted} />
      )}
    </div>
  );
}
