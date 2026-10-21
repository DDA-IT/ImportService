/**
 * Het rapport van de controle vooraf (of van de overname), gegroepeerd per soort punt met de blokkerende eerst. Elk punt
 * toont het Nederlandse woord en de uitleg uit het woordenboek (`legacyReportCode`); de code, het legacy-onderdeel, het
 * doel en de Engelse servertekst staan onder "Technische details (voor support)". Een code die (nog) niet in het
 * woordenboek staat, toont "Ander punt" met de servertekst als uitleg: onbekend wordt getoond, niet weggelaten.
 */

import type { LegacyConfigReport, LegacyReportItem } from '../../api/types.ts';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { term, termTooltip } from '../../terms/index.ts';
import { groupReportItems, reportCodeKey } from './legacyPolicy.ts';
import styles from './LegacyConfig.module.css';

const UNKNOWN_LABEL = 'Ander punt';

function ReportItem({ item }: { item: LegacyReportItem }) {
  const resolved = term('legacyReportCode', reportCodeKey(item.code));
  const known = resolved.uitleg !== '';
  const details = [
    { name: 'Code', value: item.code },
    { name: 'Onderdeel in legacy', value: item.element },
    ...(item.target === null ? [] : [{ name: 'Doel in CatalogImport', value: item.target }]),
    ...(item.message === null || !known ? [] : [{ name: 'Melding van de server (Engels)', value: item.message }]),
  ];
  return (
    <li className={styles.item} data-testid={`legacy-item-${item.code}`}>
      <span className={styles.itemTitle} title={termTooltip({ ...resolved, code: item.code })}>
        {known ? resolved.label : UNKNOWN_LABEL}
      </span>
      <p className={styles.itemExplanation}>
        {known ? resolved.uitleg : (item.message ?? 'Voor dit punt bestaat nog geen Nederlandse uitleg.')}
      </p>
      <TechnicalDetails items={details} />
    </li>
  );
}

export function LegacyReportView({ report }: { report: LegacyConfigReport }) {
  const groups = groupReportItems(report.items);
  if (groups.length === 0) {
    return <p className={styles.muted}>Het rapport bevat geen punten.</p>;
  }
  return (
    <div data-testid="legacy-report">
      {groups.map((group) => (
        <section
          key={group.status}
          className={`${styles.group} ${group.status === 'BLOCKING' ? styles.blockingGroup : ''}`}
          aria-label={term('legacyReportStatus', group.status).label}
          data-testid={`legacy-group-${group.status}`}
        >
          <h4 className={styles.groupTitle}>
            <StatusBadge status={group.status} domain="legacyReportStatus" /> ({group.items.length})
          </h4>
          <ul className={styles.items}>
            {group.items.map((item, index) => (
              // Positie als sleutel: hetzelfde punt kan voor meerdere velden voorkomen.
              <ReportItem key={`${index}:${item.element}:${item.code}`} item={item} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
