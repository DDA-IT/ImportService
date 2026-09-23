/**
 * `/bundles/:bundleId` (index) — stand, tellers, audit en de actieknoppen. Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §8, §9.1, §9.3, §10.5, §10.6.
 *
 * De knoppen werken in deze bouwstap (F7) nog niet — bevriezen/annuleren volgen in F10 — maar hebben
 * al wel de juiste poort: een verboden actie wordt **uitgeschakeld getoond met de reden erbij**, nooit
 * verborgen (§9.1). Een toegestane actie is in F7 nog uitgeschakeld met een eigen, aparte reden ("volgt
 * in een latere bouwstap"), zodat een knop nooit iets aanbiedt wat vandaag niet werkt.
 */

import { useState } from 'react';
import * as bundlesApi from '../../api/bundles.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { bundleActionGate } from './bundlePolicy.ts';
import { useBundleDetailContext } from './BundleDetailPage.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
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

const NOT_YET_IMPLEMENTED = 'Nog niet beschikbaar: deze knop krijgt zijn werking in een latere bouwstap.';

export function BundleOverviewTab() {
  const { bundle } = useBundleDetailContext();
  const [copied, setCopied] = useState(false);

  const isAssembling = bundle.status === 'ASSEMBLING';

  // §9.3: het aantal nog te beslissen mutaties staat in géén teller op BundleDetail. Twee goedkope
  // aanroepen met size=1 en `totalElements`, uitsluitend zolang de bundel ASSEMBLING is.
  const plannedKey = isAssembling ? `bundle-planned-count:${bundle.id}` : 'skip-planned';
  const planned = useQuery(plannedKey, (signal) =>
    isAssembling
      ? bundlesApi
          .bundleMutations(bundle.id, { status: 'PLANNED', page: 0, size: 1 }, signal)
          .then((result) => result.totalElements)
      : Promise.resolve(null),
  );
  const awaitingKey = isAssembling ? `bundle-awaiting-count:${bundle.id}` : 'skip-awaiting';
  const awaiting = useQuery(awaitingKey, (signal) =>
    isAssembling
      ? bundlesApi
          .bundleMutations(bundle.id, { status: 'AWAITING_APPROVAL', page: 0, size: 1 }, signal)
          .then((result) => result.totalElements)
      : Promise.resolve(null),
  );

  const freezeGate = bundleActionGate(bundle.status, 'FREEZE');
  const cancelGate = bundleActionGate(bundle.status, 'CANCEL');

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
                <dt>Wacht op beslissing (PLANNED)</dt>
                <dd>
                  {planned.error !== null ? '—' : <Count value={planned.data ?? null} />}
                </dd>
              </div>
              <div className={styles.counter}>
                <dt>Wacht op goedkeuring (AWAITING_APPROVAL)</dt>
                <dd>
                  {awaiting.error !== null ? '—' : <Count value={awaiting.data ?? null} />}
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
            <button type="button" className={styles.actionButton} disabled title={freezeGate.allowed ? NOT_YET_IMPLEMENTED : freezeGate.reason}>
              Bevriezen
            </button>
            <p className={styles.actionReason}>{freezeGate.allowed ? NOT_YET_IMPLEMENTED : freezeGate.reason}</p>
          </div>
          <div className={styles.actionRow}>
            <button type="button" className={styles.actionButton} disabled title={cancelGate.allowed ? NOT_YET_IMPLEMENTED : cancelGate.reason}>
              Annuleren
            </button>
            <p className={styles.actionReason}>{cancelGate.allowed ? NOT_YET_IMPLEMENTED : cancelGate.reason}</p>
          </div>
        </div>
      </section>

      {planned.error !== null && <ErrorBanner error={planned.error} />}
      {awaiting.error !== null && <ErrorBanner error={awaiting.error} />}
    </div>
  );
}
