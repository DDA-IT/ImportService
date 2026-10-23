/**
 * De itemresultaten van een publicatie naar Prodis (P7): per artikel de status, de eerdere en de nieuwe waarden
 * in Prodis, en de meldingen in gewone taal. Filter op status, gepagineerd.
 *
 * Wat de mens moet weten staat er altijd: conflicten, weigeringen, afrondingsverschillen en afgeleide prijzen die
 * afwijken worden nooit weggelaten of samengevat. De servertekst bij een melding (Engels) staat enkel onder
 * "Technische details (voor support)".
 */

import { useState } from 'react';
import * as runsApi from '../../api/publicationRuns.ts';
import {
  PUBLICATION_ITEM_STATUSES,
  type ProdisArticleState,
  type PublicationItemStatus,
  type PublicationItemView,
} from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import button from '../../components/Button.module.css';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { term } from '../../terms/index.ts';
import styles from './ProdisPublication.module.css';

/** Meldingen die de mens altijd opvallend moet zien. */
const PROBLEM_NOTICES = ['PRICE_ROUNDING_DIFFERENCE', 'DERIVED_PRICE_DRIFT', 'VALUE_CHANGED_IN_PRODIS'];

export type ProdisChange = { label: string; prior: string; result: string };

function show(value: string | null | undefined): string {
  return value === null || value === undefined || value === '' ? '—' : value;
}

/**
 * De verschillen tussen twee toestanden als `label: prior -> result`. Alleen velden die verschillen of waarvan de
 * nieuwe waarde bekend is en afwijkt van de oude; zonder `result` (nog niet verwerkt) alleen de huidige waarden.
 */
export function describeChanges(prior: ProdisArticleState | null, result: ProdisArticleState | null): ProdisChange[] {
  const changes: ProdisChange[] = [];
  const push = (label: string, before: string | null | undefined, after: string | null | undefined) => {
    if (result === null) {
      if (before !== null && before !== undefined) {
        changes.push({ label, prior: show(before), result: '—' });
      }
      return;
    }
    if ((before ?? null) !== (after ?? null)) {
      changes.push({ label, prior: show(before), result: show(after) });
    }
  };
  push('Basisprijs', prior?.basePrice, result?.basePrice);
  push('Brutoprijs', prior?.brutPrice, result?.brutPrice);
  push('Brutopercentage', prior?.brutPricePercentage, result?.brutPricePercentage);
  const percentageKeys = new Set([...Object.keys(prior?.percentages ?? {}), ...Object.keys(result?.percentages ?? {})]);
  for (const key of [...percentageKeys].sort((a, b) => Number(a) - Number(b))) {
    push(`Verkoopprijs-percentage ${key}`, prior?.percentages?.[key], result?.percentages?.[key]);
  }
  const priceKeys = new Set([...Object.keys(prior?.prices ?? {}), ...Object.keys(result?.prices ?? {})]);
  for (const key of [...priceKeys].sort((a, b) => Number(a) - Number(b))) {
    push(`Verkoopprijs ${key}`, prior?.prices?.[key], result?.prices?.[key]);
  }
  push('Omschrijving', prior?.description, result?.description);
  return changes;
}

function ItemRow({ item }: { item: PublicationItemView }) {
  const changes = describeChanges(item.prior, item.result);
  const conflict = item.status === 'CONFLICT';
  const identity = item.identity;
  return (
    <li className={`${styles.item} ${conflict ? styles.itemConflict : ''}`} data-testid={`prodis-item-${item.mutationId}`}>
      <div className={styles.itemHeader}>
        <span className={styles.identity}>
          {show(identity.supplierNumber)} / {show(identity.groupLegacyCode)} / {show(identity.supplierReference)}
        </span>
        <span>bibliotheek {show(identity.libraryCode)}</span>
        <StatusBadge status={item.status} domain="publicationItemStatus" />
        {item.sendCount > 1 && <span>{item.sendCount} pogingen</span>}
      </div>
      {item.reasonCode !== null && (
        <p className={conflict || item.status === 'REJECTED' ? styles.problem : undefined}>
          {term('publicationItemReason', item.reasonCode).label}
          {term('publicationItemReason', item.reasonCode).uitleg !== '' &&
            `: ${term('publicationItemReason', item.reasonCode).uitleg}`}
        </p>
      )}
      {changes.length > 0 && (
        <ul className={styles.changes}>
          {changes.map((change) => (
            <li key={change.label}>
              {change.label}: {change.prior} <span className={styles.arrow}>→</span> {change.result}
            </li>
          ))}
        </ul>
      )}
      {item.warnings.length > 0 && (
        <ul className={styles.notices}>
          {item.warnings.map((warning, index) => {
            const resolved = term('publicationNotice', warning.code);
            return (
              <li
                key={`${warning.code}-${warning.field ?? ''}-${index}`}
                className={PROBLEM_NOTICES.includes(warning.code) ? styles.noticeProblem : undefined}
              >
                {resolved.label}
                {resolved.uitleg !== '' && `: ${resolved.uitleg}`}
              </li>
            );
          })}
        </ul>
      )}
      <TechnicalDetails
        items={[
          { name: 'Wijziging', value: String(item.mutationId) },
          { name: 'Reden', value: item.reasonCode ?? '—' },
          { name: 'Detail van de reden', value: item.reasonDetail ?? '—' },
          { name: 'Laatste foutcode', value: item.lastErrorCode ?? '—' },
          { name: 'Stand van de legacy-meester', value: item.legacyMaster ?? '—' },
          {
            name: 'Meldingen',
            value: item.warnings.map((w) => `${w.code}${w.field ? ` (${w.field})` : ''}: ${w.message ?? ''}`).join(' | ') || '—',
          },
        ]}
      />
    </li>
  );
}

export type ProdisItemResultsProps = {
  runId: number;
  /** Verandert wanneer de lijst opnieuw geladen moet worden (bv. nadat de continue-lus stopte). */
  refreshToken: number;
};

export function ProdisItemResults({ runId, refreshToken }: ProdisItemResultsProps) {
  const [status, setStatus] = useState<PublicationItemStatus | ''>('');
  const [page, setPage] = useState(0);
  const key = `prodis-run-items:${runId}:${status}:${page}:${refreshToken}`;
  const { data, error, loading, reload } = useQuery(key, (signal) =>
    runsApi.listRunItems(runId, { status: status === '' ? null : status, page }, signal),
  );
  const selectId = `prodis-item-filter-${runId}`;

  return (
    <div data-testid={`prodis-items-${runId}`}>
      <div className={styles.filterRow}>
        <label htmlFor={selectId}>Toon artikelen met status</label>
        <select
          id={selectId}
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as PublicationItemStatus | '');
            setPage(0);
          }}
        >
          <option value="">Alle</option>
          {PUBLICATION_ITEM_STATUSES.map((value) => (
            <option key={value} value={value}>
              {term('publicationItemStatus', value).label}
            </option>
          ))}
        </select>
        <button type="button" className={`${button.secondary} ${button.small}`} onClick={reload}>
          Vernieuwen
        </button>
      </div>
      {error !== null && <ErrorBanner error={error} />}
      {loading && data === null && <p className={styles.status}>Bezig met laden…</p>}
      {data !== null && data.content.length === 0 && <p className={styles.status}>Geen artikelen met deze status.</p>}
      {data !== null && data.content.length > 0 && (
        <>
          <ul className={styles.itemList}>
            {data.content.map((item) => (
              <ItemRow key={item.mutationId} item={item} />
            ))}
          </ul>
          <div className={styles.filterRow}>
            <button
              type="button"
              className={`${button.secondary} ${button.small}`}
              disabled={data.page <= 0}
              onClick={() => setPage(data.page - 1)}
            >
              Vorige
            </button>
            <span>
              Pagina {data.page + 1} van {Math.max(data.totalPages, 1)} ({data.totalElements} artikel(en))
            </span>
            <button
              type="button"
              className={`${button.secondary} ${button.small}`}
              disabled={data.page + 1 >= data.totalPages}
              onClick={() => setPage(data.page + 1)}
            >
              Volgende
            </button>
          </div>
        </>
      )}
    </div>
  );
}
