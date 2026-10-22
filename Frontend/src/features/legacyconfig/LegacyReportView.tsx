/**
 * Het rapport van de controle vooraf (of van de overname), gegroepeerd per soort punt met de blokkerende eerst. Elk punt
 * is een kaart: bovenaan een Nederlandse omschrijving van het legacy-onderdeel, dan het Nederlandse woord en de uitleg
 * uit het woordenboek (`legacyReportCode`). De ruwe code, het element, het doel en de Engelse servertekst staan onder
 * "Technische details (voor support)". Een code die (nog) niet in het woordenboek staat, toont "Ander punt" met de
 * servertekst als uitleg: onbekend wordt getoond, niet weggelaten.
 *
 * Meer dan 5 punten met dezelfde code in één soort worden één groep met "×n" (de onderdelen klappen open). Bij meer dan
 * 8 punten komen er een zoekveld en filterknoppen per soort bij.
 */

import { useId, useState } from 'react';
import type { LegacyConfigReport, LegacyReportItem } from '../../api/types.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { term, termTooltip } from '../../terms/index.ts';
import { LEGACY_CARD_TEXT, LEGACY_REPORT_TEXT } from '../../terms/legacyCards.ts';
import {
  describeLegacyPart,
  FILTER_THRESHOLD,
  groupItemsByCode,
  groupReportItems,
  reportCodeKey,
} from './legacyPolicy.ts';
import styles from './LegacyConfig.module.css';

type Resolved = { known: boolean; label: string; uitleg: string; tooltip: string };

function resolveItem(item: LegacyReportItem): Resolved {
  const resolved = term('legacyReportCode', reportCodeKey(item.code));
  const known = resolved.uitleg !== '';
  return {
    known,
    label: known ? resolved.label : LEGACY_CARD_TEXT.otherPoint,
    uitleg: known ? resolved.uitleg : (item.message ?? 'Voor dit punt bestaat nog geen Nederlandse uitleg.'),
    tooltip: termTooltip({ ...resolved, code: item.code }),
  };
}

function technicalItems(item: LegacyReportItem, known: boolean) {
  return [
    { name: 'Code', value: item.code },
    { name: LEGACY_REPORT_TEXT.legacyPart, value: item.element },
    ...(item.target === null ? [] : [{ name: 'Doel in CatalogImport', value: item.target }]),
    ...(item.message === null || !known ? [] : [{ name: 'Melding van de server (Engels)', value: item.message }]),
  ];
}

function statusClass(status: string): string | undefined {
  switch (status) {
    case 'BLOCKING':
      return styles.itemBlocking;
    case 'WARNING':
      return styles.itemWarning;
    case 'NOT_MIGRATED':
      return styles.itemNotMigrated;
    case 'MIGRATED':
      return styles.itemMigrated;
    default:
      return undefined;
  }
}

function ReportItem({ item, showTitle = true }: { item: LegacyReportItem; showTitle?: boolean }) {
  const resolved = resolveItem(item);
  return (
    <li className={`${styles.item} ${statusClass(item.status) ?? ''}`} data-testid={`legacy-item-${item.code}`}>
      <p className={styles.itemPart}>{describeLegacyPart(item.element)}</p>
      {showTitle && (
        <>
          <h4 className={styles.itemTitle} title={resolved.tooltip}>
            {resolved.label}
          </h4>
          <p className={styles.itemExplanation}>{resolved.uitleg}</p>
        </>
      )}
      <TechnicalDetails items={technicalItems(item, resolved.known)} />
    </li>
  );
}

function CodeGroup({ items, status }: { items: LegacyReportItem[]; status: string }) {
  const first = items[0]!;
  const resolved = resolveItem(first);
  return (
    <li className={`${styles.item} ${statusClass(status) ?? ''}`} data-testid={`legacy-code-group-${reportCodeKey(first.code)}`}>
      <h4 className={styles.itemTitle} title={resolved.tooltip}>
        {resolved.label} <span className={styles.itemCount}>×{items.length}</span>
      </h4>
      <p className={styles.itemExplanation}>{resolved.uitleg}</p>
      <details className={styles.groupDetails}>
        <summary className={styles.groupSummary}>{LEGACY_REPORT_TEXT.showParts(items.length)}</summary>
        <ul className={styles.items}>
          {items.map((item, index) => (
            // Positie als sleutel: hetzelfde punt kan voor meerdere velden voorkomen.
            <ReportItem key={`${index}:${item.element}:${item.code}`} item={item} showTitle={false} />
          ))}
        </ul>
      </details>
    </li>
  );
}

function matchesQuery(item: LegacyReportItem, query: string): boolean {
  const needle = query.trim().toLowerCase();
  if (needle === '') return true;
  const resolved = resolveItem(item);
  return [resolved.label, resolved.uitleg, item.element, item.code, describeLegacyPart(item.element)].some((text) =>
    text.toLowerCase().includes(needle),
  );
}

export type LegacyReportViewProps = {
  /** Het rapport; `items` mag de te tonen punten beperken (bv. één kaart). */
  report: LegacyConfigReport;
  items?: readonly LegacyReportItem[];
};

export function LegacyReportView({ report, items }: LegacyReportViewProps) {
  const all = items ?? report.items;
  const searchId = useId();
  const [query, setQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | null>(null);

  const allGroups = groupReportItems(all);
  if (allGroups.length === 0) {
    return <p className={styles.muted}>{LEGACY_REPORT_TEXT.empty}</p>;
  }
  const filterable = all.length > FILTER_THRESHOLD;
  const shown = allGroups
    .filter((group) => !filterable || statusFilter === null || group.status === statusFilter)
    .map((group) => ({ status: group.status, items: filterable ? group.items.filter((item) => matchesQuery(item, query)) : group.items }))
    .filter((group) => group.items.length > 0);

  return (
    <div data-testid="legacy-report">
      {filterable && (
        <div className={styles.filterBar} data-testid="legacy-report-filter">
          <label className={styles.filterLabel} htmlFor={searchId}>
            {LEGACY_REPORT_TEXT.searchLabel}
          </label>
          <input
            id={searchId}
            type="search"
            className={styles.filterInput}
            value={query}
            placeholder={LEGACY_REPORT_TEXT.searchPlaceholder}
            onChange={(event) => setQuery(event.target.value)}
          />
          <div className={styles.filterChips} role="group" aria-label={LEGACY_REPORT_TEXT.statusFilterLabel}>
            <button
              type="button"
              className={styles.filterChip}
              aria-pressed={statusFilter === null}
              onClick={() => setStatusFilter(null)}
            >
              {LEGACY_REPORT_TEXT.all} ({all.length})
            </button>
            {allGroups.map((group) => (
              <button
                key={group.status}
                type="button"
                className={styles.filterChip}
                aria-pressed={statusFilter === group.status}
                onClick={() => setStatusFilter(statusFilter === group.status ? null : group.status)}
              >
                {term('legacyReportStatus', group.status).label} ({group.items.length})
              </button>
            ))}
          </div>
        </div>
      )}
      {shown.length === 0 && <p className={styles.muted}>{LEGACY_REPORT_TEXT.noMatch}</p>}
      {shown.map((group) => (
        <section
          key={group.status}
          className={styles.group}
          role="group"
          aria-label={term('legacyReportStatus', group.status).label}
          data-testid={`legacy-group-${group.status}`}
        >
          <h3 className={styles.groupTitle}>
            <StatusBadge status={group.status} domain="legacyReportStatus" /> ({group.items.length})
          </h3>
          <ul className={styles.items}>
            {groupItemsByCode(group.items).map((entry, index) =>
              entry.kind === 'single' ? (
                <ReportItem key={`${index}:${entry.item.element}:${entry.item.code}`} item={entry.item} />
              ) : (
                <CodeGroup key={`group:${entry.key}`} items={entry.items} status={group.status} />
              ),
            )}
          </ul>
        </section>
      ))}
    </div>
  );
}
