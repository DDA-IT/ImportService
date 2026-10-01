/**
 * Toont een statuswaarde. Kleur is nooit de enige drager van betekenis (§6): de statustekst zelf
 * staat er **altijd** bij, ook wanneer de status onbekend is (bv. een latere backenduitbreiding) —
 * zie ook de `as const`-aanpak in `api/types.ts` §3.3: onbekend wordt getoond, niet weggelaten.
 */

import { useId } from 'react';
import { term, termTooltip, type TermDomain } from '../terms/index.ts';
import termStyles from '../terms/Term.module.css';
import styles from './StatusBadge.module.css';

type StatusFamily = 'planned' | 'awaiting' | 'ready' | 'rejected' | 'blocked' | 'expired' | 'neutral';

// Dekt MutationStatus, PublicationBundleStatus en ImportBatchStatus uit `api/types.ts`. Een status die
// hier niet in staat, valt terug op 'neutral' — nooit een crash, nooit een lege badge.
const STATUS_FAMILY: Record<string, StatusFamily> = {
  // MutationStatus
  PLANNED: 'planned',
  BLOCKED: 'blocked',
  AWAITING_APPROVAL: 'awaiting',
  READY_FOR_PUBLICATION: 'ready',
  IN_PROGRESS: 'awaiting',
  PUBLISHED: 'ready',
  TECHNICALLY_FAILED: 'rejected',
  REJECTED: 'rejected',
  EXPIRED: 'expired',
  SKIPPED: 'neutral',
  RECORDED: 'neutral',
  // PublicationBundleStatus
  ASSEMBLING: 'planned',
  FROZEN: 'ready',
  CANCELLED: 'rejected',
  PUBLISHING: 'awaiting',
  PARTIALLY_PUBLISHED: 'awaiting',
  PUBLICATION_FAILED: 'rejected',
  // ImportBatchStatus
  RECEIVED: 'planned',
  SCREENING: 'awaiting',
  MUTATING: 'awaiting',
  SCREENED: 'ready',
  FAILED: 'rejected',
  BASELINE_ACCEPTED: 'ready',
  // ValidationResult (het eindoordeel, Scherm 0); `null` ("niet vastgesteld") heeft geen entry hier
  // en wordt door de aanroeper apart getoond — een badge veronderstelt altijd een stringwaarde.
  VALID: 'ready',
  VALID_WITH_WARNINGS: 'awaiting',
  REVIEW_REQUIRED: 'blocked',
  BLOCKING: 'rejected',
  // IssueCaseStatus (S2-0)
  AWAITING_REVIEW: 'awaiting',
  CORRECTED: 'ready',
  // REJECTED already mapped above (shared with MutationStatus)
  AUTO_RESOLVED: 'ready',
  // NT-10: checklist (readinessStatus; INFO blijft neutraal), proefinlezing (trialVerdict, trialSampleStatus,
  // thresholdOutcome). VALID en REJECTED staan hierboven al.
  OK: 'ready',
  PROBLEM: 'rejected',
  WOULD_BLOCK: 'rejected',
  NO_BLOCKER_FOUND: 'ready',
  UNREADABLE: 'rejected',
  FILTERED_OUT: 'expired',
  EXCEEDED: 'rejected',
  WITHIN: 'ready',
};

/**
 * `domain` is verplicht: dezelfde code betekent per domein iets anders (`BLOCKED`/`REJECTED` bij een
 * batch, een wijziging of een behandelgeval). De badge toont het Nederlandse woord; de uitleg en de
 * technische code staan in de tooltip (NT-5, V7). Een onbekende code wordt als code getoond.
 */
export type StatusBadgeProps = { status: string; domain: TermDomain };

export function StatusBadge({ status, domain }: StatusBadgeProps) {
  const descriptionId = useId();
  const family = STATUS_FAMILY[status] ?? 'neutral';
  const resolved = term(domain, status);
  const hasUitleg = resolved.uitleg !== '';
  return (
    <>
      <span
        className={`${styles.badge} ${styles[family]}`}
        title={termTooltip(resolved)}
        aria-describedby={hasUitleg ? descriptionId : undefined}
      >
        {resolved.label}
      </span>
      {hasUitleg && (
        <span id={descriptionId} className={termStyles.visuallyHidden}>
          {resolved.uitleg}
        </span>
      )}
    </>
  );
}
