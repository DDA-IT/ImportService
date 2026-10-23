/**
 * Bouwstenen voor de kolom "Uit legacy" in het leesbeeld (F2): de banner en notities boven het leesbeeld, de lijst
 * "Niet overgenomen uit legacy" onder de tabel en de technische details. Het getoonde is het bedoelde legacy-doel
 * (decisions 2026-10-23 B2), geen bewijs van wat de overname deed.
 */

import { Link } from 'react-router-dom';
import { useActor } from '../../actor/ActorContext.tsx';
import type { LegacyOriginLine, RevisionDetail, RevisionLegacyOrigin } from '../../api/types.ts';
import { PERMISSION_MANAGE } from '../../api/types.ts';
import { formatDateTime } from '../../format.ts';
import { LEGACY_ORIGIN_TEXT as TEXT } from '../../terms/legacyOrigin.ts';
import { TechnicalDetails, type TechnicalDetailItem } from '../../terms/TechnicalDetails.tsx';
import { describeLine, technicalLine, uncoveredLines } from './legacyOrigin.ts';
import styles from './SetupScreens.module.css';

/** De herkomst die het leesbeeld gebruikt: enkel als de versie uit legacy komt en de regels leesbaar zijn. */
export function usableLegacyOrigin(origin: RevisionLegacyOrigin | null | undefined): RevisionLegacyOrigin | null {
  return origin !== null && origin !== undefined && origin.fromLegacy && origin.linesAvailable ? origin : null;
}

export function legacyColumnLabel(origin: RevisionLegacyOrigin): string {
  return origin.changedSinceMigration === true ? TEXT.columnAtMigration : TEXT.column;
}

/** Banner, herkomstnotitie en melding bij een mislukte of onvolledige herkomst; boven de secties van het leesbeeld. */
export function LegacyOriginNotice({
  origin,
  failed,
  originVersionNumber,
}: {
  origin: RevisionLegacyOrigin | null | undefined;
  failed: boolean;
  /** Versienummer van de herkomstversie (bij geërfd), als de aanroeper het kent. */
  originVersionNumber?: number | null;
}) {
  if (failed) {
    return (
      <p className={styles.rowMeta} role="status" data-testid="legacy-origin-failed">
        {TEXT.loadFailed}
      </p>
    );
  }
  if (origin === null || origin === undefined || !origin.fromLegacy) {
    return null;
  }
  return (
    <div data-testid="legacy-origin-notice">
      {origin.changedSinceMigration === true && (
        <div className={styles.banner} role="status" data-testid="legacy-changed-banner">
          <p>{TEXT.changedBanner}</p>
        </div>
      )}
      {origin.originKind === 'INHERITED' && (
        <p className={styles.rowMeta} data-testid="legacy-origin-kind">
          {TEXT.inheritedFrom(originVersionNumber ?? null)}
        </p>
      )}
      {origin.originKind === 'TEMPLATE_SOURCE' && (
        <p className={styles.rowMeta} data-testid="legacy-origin-kind">
          {TEXT.templateSource}
        </p>
      )}
      {!origin.linesAvailable && (
        <p className={styles.rowMeta} data-testid="legacy-lines-unavailable">
          {TEXT.linesUnavailable}
        </p>
      )}
    </div>
  );
}

/** De legacy-beschrijving in een cel of naast een waarde: gedempt; leeg als er geen regel bij de rij hoort. */
export function LegacyHint({ line }: { line: LegacyOriginLine | null }) {
  if (line === null) {
    return <span className={styles.unset}>—</span>;
  }
  return <span className={styles.rowMeta}>{describeLine(line)}</span>;
}

/** "Niet overgenomen uit legacy": regels zonder rij in het leesbeeld. */
export function UncoveredLegacyLines({ origin, revision }: { origin: RevisionLegacyOrigin; revision: RevisionDetail }) {
  const uncovered = uncoveredLines(origin.lines, revision);
  if (uncovered.length === 0) {
    return null;
  }
  return (
    <div data-testid="legacy-uncovered">
      <h4>{TEXT.notTakenOverTitle}</h4>
      <ul className={styles.rowList}>
        {uncovered.map(({ line, reason }) => (
          <li key={line.number}>
            <span className={styles.rowMeta}>
              {TEXT.lineNumber(line.number)} · {line.legacyFieldName} · {reason}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Interface, importsoort, ontvangst (link enkel met Beheren), data en de ruwe waarden per regel. */
export function LegacyTechnicalDetails({ origin }: { origin: RevisionLegacyOrigin }) {
  const { can } = useActor();
  const items: TechnicalDetailItem[] = [];
  if (origin.interfaceCode !== null) {
    items.push({ name: TEXT.technical.interfaceCode, value: origin.interfaceCode });
  }
  if (origin.legacyDefinitionCode !== null) {
    items.push({ name: TEXT.technical.definitionCode, value: origin.legacyDefinitionCode });
  }
  if (origin.receiptId !== null) {
    items.push({
      name: TEXT.technical.receipt,
      value: can(PERMISSION_MANAGE) ? (
        <Link to={`/setup/legacy-config/${origin.receiptId}`} data-testid="legacy-receipt-link">
          #{origin.receiptId}
        </Link>
      ) : (
        <span data-testid="legacy-receipt-plain">#{origin.receiptId}</span>
      ),
    });
  }
  if (origin.originKind !== null) {
    items.push({ name: TEXT.technical.origin, value: origin.originKind });
  }
  if (origin.originRevisionId !== null) {
    items.push({ name: TEXT.technical.originRevision, value: String(origin.originRevisionId) });
  }
  if (origin.exportedAt !== null) {
    items.push({ name: TEXT.technical.exportedAt, value: formatDateTime(origin.exportedAt) });
  }
  if (origin.appliedAt !== null) {
    items.push({ name: TEXT.technical.appliedAt, value: formatDateTime(origin.appliedAt) });
  }
  if (origin.lines.length > 0) {
    items.push({
      name: TEXT.technical.lines,
      value: (
        <ul>
          {origin.lines.map((line) => (
            <li key={line.number}>
              {line.number}: {technicalLine(line)}
            </li>
          ))}
        </ul>
      ),
    });
  }
  return <TechnicalDetails items={items} />;
}
