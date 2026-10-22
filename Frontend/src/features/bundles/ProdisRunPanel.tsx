/**
 * Het deel van een publicatierun naar Prodis (`targetMode` PRODUCTION) in de runlijst (P7): de stand van de
 * legacy-meester bij de start (met de vaste waarschuwing), de tellers per artikelstatus, de voortgang en de
 * bediening van de continue-lus ("Pauzeren" / "Verder publiceren"), en de itemresultaten.
 *
 * Tellers van conflicten, geweigerde en onzekere artikelen staan er altijd bij als ze er zijn: een publicatie die
 * "klaar" is met conflicten of weigeringen is niet hetzelfde als een publicatie die helemaal is doorgevoerd.
 */

import { useState } from 'react';
import { denied, gateTitle, type Gate } from '../../actor/gate.ts';
import { PUBLICATION_ITEM_STATUSES, type PublicationItemStatus, type PublicationRunView } from '../../api/types.ts';
import { term } from '../../terms/index.ts';
import button from '../../components/Button.module.css';
import { ProdisItemResults } from './ProdisItemResults.tsx';
import {
  DATASYNC_WARNING,
  finishedItems,
  isContinuable,
  itemCount,
  normalizeLegacyMaster,
  showsDatasyncWarning,
  totalItems,
} from './prodisPublication.ts';
import type { ContinueLoop } from './useContinueLoop.ts';
import styles from './ProdisPublication.module.css';

const PROBLEM_STATUSES: PublicationItemStatus[] = ['CONFLICT', 'REJECTED', 'RESULT_UNKNOWN'];

export type ProdisRunPanelProps = {
  run: PublicationRunView;
  loop: ContinueLoop;
  /** Poort voor "Verder publiceren" (recht APPROVE). */
  continueGate: Gate;
  /** Verandert telkens de continue-lus gestopt is, zodat de itemlijst herlaadt. */
  refreshToken: number;
};

export function ProdisRunPanel({ run, loop, continueGate, refreshToken }: ProdisRunPanelProps) {
  const [showItems, setShowItems] = useState(false);
  const loopHere = loop.runId === run.id;
  const running = loopHere && loop.running;
  const total = totalItems(run);
  const finished = finishedItems(run);
  const master = run.legacyMasterAtStart === undefined || run.legacyMasterAtStart === null ? null : normalizeLegacyMaster(run.legacyMasterAtStart);
  const hasCounts = run.itemCounts !== undefined && run.itemCounts !== null;
  const problems = PROBLEM_STATUSES.filter((status) => itemCount(run, status) > 0);
  const resumeGate: Gate = continueGate.allowed && loop.running ? denied('Er wordt al gepubliceerd.') : continueGate;

  return (
    <div data-testid={`prodis-run-${run.id}`}>
      {master !== null && (
        <p className={styles.status}>
          Stand van de legacy-meester bij de start: {term('legacyMaster', master).label}.
        </p>
      )}
      {master !== null && showsDatasyncWarning(master) && <p className={styles.warning}>{DATASYNC_WARNING}</p>}

      {hasCounts && (
        <>
          <dl className={styles.counts}>
            {PUBLICATION_ITEM_STATUSES.map((status) => {
              const count = itemCount(run, status);
              return (
                <div key={status}>
                  <dt title={term('publicationItemStatus', status).uitleg}>{term('publicationItemStatus', status).label}</dt>
                  <dd className={PROBLEM_STATUSES.includes(status) && count > 0 ? styles.problem : undefined}>{count}</dd>
                </div>
              );
            })}
          </dl>
          {total > 0 && (
            <div className={styles.progressBlock}>
              <progress className={styles.progress} max={total} value={finished} aria-label="Voortgang van de publicatie" />
              <p className={styles.status}>
                {finished} van {total} artikel(en) afgehandeld.
              </p>
            </div>
          )}
        </>
      )}

      {run.done === true && problems.length > 0 && (
        <p className={styles.attention}>
          De publicatie is klaar, maar niet alles is doorgevoerd:{' '}
          {problems
            .map((status) => `${itemCount(run, status)} × ${term('publicationItemStatus', status).label.toLowerCase()}`)
            .join(', ')}
          . Bekijk de resultaten per artikel.
        </p>
      )}
      {run.status === 'RECOVERY_REQUIRED' && (
        <p className={styles.attention}>
          Deze publicatie vraagt herstel voordat ze verder kan. Bekijk de resultaten per artikel en neem contact op met
          support.
        </p>
      )}

      <div className={styles.actions}>
        {running && (
          <>
            <span className={styles.status} role="status">
              {loop.pausing
                ? 'Pauzeren… de lopende stap wordt nog afgewacht.'
                : loop.waitingBusy
                  ? 'De publicatie is even bezig; er wordt zo meteen opnieuw geprobeerd…'
                  : 'Bezig met publiceren…'}
            </span>
            <button type="button" className={button.secondary} onClick={loop.pause} disabled={loop.pausing}>
              Pauzeren
            </button>
          </>
        )}
        {!running && isContinuable(run) && (
          <>
            <button
              type="button"
              className={button.secondary}
              disabled={!resumeGate.allowed}
              title={gateTitle(resumeGate)}
              onClick={() => loop.start(run.id)}
            >
              Verder publiceren
            </button>
            {!resumeGate.allowed && <span className={styles.status}>{resumeGate.reason}</span>}
          </>
        )}
        <button type="button" className={`${button.secondary} ${button.small}`} onClick={() => setShowItems((open) => !open)}>
          {showItems ? 'Verberg de resultaten per artikel' : 'Toon de resultaten per artikel'}
        </button>
      </div>


      {showItems && <ProdisItemResults runId={run.id} refreshToken={refreshToken} />}
    </div>
  );
}
