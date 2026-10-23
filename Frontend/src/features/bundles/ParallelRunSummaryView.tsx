/**
 * De samenvatting van de laatste parallelrun (P2c) van een bundel: wat er bij echt publiceren met Prodis zou
 * gebeuren (zou aangepast worden, conflict, geweigerd, ...) en welke meldingen er zijn (afrondingsverschillen,
 * afgeleide prijzen die afwijken, ...). Conflicten, weigeringen en afrondingsverschillen staan er altijd
 * expliciet bij als ze er zijn — nooit verborgen. Een parallelrun die niet gelukt is, wordt zo gezegd:
 * "niet vergeleken" is nooit hetzelfde als "geen afwijkingen".
 */

import { apiUrl } from '../../api/http.ts';
import type { ParallelRunSummary } from '../../api/types.ts';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { term } from '../../terms/index.ts';
import styles from './ProdisPublication.module.css';

const OUTCOME_ORDER = ['WOULD_UPDATE', 'UNCHANGED', 'CONFLICT', 'REJECTED', 'NOT_SENT'];
const PROBLEM_OUTCOMES = ['CONFLICT', 'REJECTED'];

export type ParallelRunSummaryViewProps = {
  /** Het run-id van de proefpublicatie waaraan deze parallelrun hangt (voor de downloadlink van het rapport). */
  runId: number;
  summary: ParallelRunSummary | null | undefined;
};

export function ParallelRunSummaryView({ runId, summary }: ParallelRunSummaryViewProps) {
  if (summary === null || summary === undefined) {
    return (
      <div className={styles.summary} data-testid="parallel-run-summary">
        <p className={styles.summaryTitle}>Proefvergelijking met Prodis</p>
        <p>
          Er is nog geen proefvergelijking met Prodis voor deze publicatie. Start eerst een proefpublicatie om te zien wat
          er zou gebeuren.
        </p>
      </div>
    );
  }

  if (summary.status !== 'PARALLEL_RUN_COMPLETED') {
    return (
      <div className={styles.summary} data-testid="parallel-run-summary">
        <p className={styles.summaryTitle}>Proefvergelijking met Prodis</p>
        <p className={styles.problem}>
          {term('parallelRunStatus', summary.status).label}: er is niets vergeleken, dus u weet nog niet wat er zou
          gebeuren.
        </p>
        <TechnicalDetails
          items={[
            { name: 'Stand', value: summary.status },
            { name: 'Melding van de server', value: summary.statusMessage ?? '—' },
          ]}
        />
      </div>
    );
  }

  const outcomes = summary.outcomeCounts ?? {};
  const notices = summary.noticeCounts ?? {};
  const outcomeKeys = [...OUTCOME_ORDER, ...Object.keys(outcomes).filter((key) => !OUTCOME_ORDER.includes(key))];
  const rounding = notices.PRICE_ROUNDING_DIFFERENCE ?? 0;
  const drift = notices.DERIVED_PRICE_DRIFT ?? 0;
  const noticeKeys = Object.keys(notices);

  return (
    <div className={styles.summary} data-testid="parallel-run-summary">
      <p className={styles.summaryTitle}>
        Proefvergelijking met Prodis ({summary.itemCount ?? 0} artikel(en), er is niets geschreven)
      </p>
      <dl className={styles.counts}>
        {outcomeKeys.map((key) => {
          const count = outcomes[key] ?? 0;
          const problem = PROBLEM_OUTCOMES.includes(key) && count > 0;
          return (
            <div key={key}>
              <dt title={term('parallelRunOutcome', key).uitleg}>{term('parallelRunOutcome', key).label}</dt>
              <dd className={problem ? styles.problem : undefined}>{count}</dd>
            </div>
          );
        })}
      </dl>
      {(outcomes.CONFLICT ?? 0) > 0 && (
        <p className={styles.attention}>
          {outcomes.CONFLICT} artikel(en) hebben een conflict: een waarde in Prodis wijkt af van de verwachting. Die
          worden bij echt publiceren niet aangepast.
        </p>
      )}
      {(outcomes.REJECTED ?? 0) > 0 && (
        <p className={styles.attention}>
          {outcomes.REJECTED} artikel(en) zouden geweigerd worden; de reden staat per artikel in het resultaat.
        </p>
      )}
      {rounding > 0 && (
        <p className={styles.attention}>
          {rounding} melding(en) over een afrondingsverschil: Prodis rekent een verkoopprijs anders af dan het bedrag in
          de levering. Er wordt niets stilzwijgend bijgesteld.
        </p>
      )}
      {drift > 0 && (
        <p className={styles.attention}>
          {drift} melding(en) over afgeleide prijzen in Prodis die niet overeenkomen met de basisprijs en percentages.
        </p>
      )}
      {noticeKeys.length > 0 && (
        <ul>
          {noticeKeys.map((key) => (
            <li key={key} title={term('publicationNotice', key).uitleg}>
              {term('publicationNotice', key).label}: {notices[key]}
            </li>
          ))}
        </ul>
      )}
      <p>
        <a href={apiUrl(`/publication-runs/${runId}/parallel-run-artifact`)}>Download het volledige rapport (JSON)</a>
      </p>
    </div>
  );
}
