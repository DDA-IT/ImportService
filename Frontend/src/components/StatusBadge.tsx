/**
 * Toont een statuswaarde. Kleur is nooit de enige drager van betekenis (§6): de statustekst zelf
 * staat er **altijd** bij, ook wanneer de status onbekend is (bv. een latere backenduitbreiding) —
 * zie ook de `as const`-aanpak in `api/types.ts` §3.3: onbekend wordt getoond, niet weggelaten.
 */

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
};

export type StatusBadgeProps = { status: string };

export function StatusBadge({ status }: StatusBadgeProps) {
  const family = STATUS_FAMILY[status] ?? 'neutral';
  return <span className={`${styles.badge} ${styles[family]}`}>{status}</span>;
}
