/**
 * Het ene detailpaneel onder het kaartenrooster: de inhoud hangt van de geopende kaart af. Een wachtwoord staat hier
 * nooit: enkel de naam van geheime instellingen, of er een verwijzing is en wat er bij ontvangst mee gebeurde.
 */

import { forwardRef } from 'react';
import type { LegacyConfigReport, LegacyReceiptDetail } from '../../api/types.ts';
import { Term } from '../../terms/Term.tsx';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { LEGACY_CARD_TEXT } from '../../terms/legacyCards.ts';
import { Definition, Interface } from './BundleSummary.tsx';
import { LegacyReportView } from './LegacyReportView.tsx';
import { PlannedSummary } from './PlannedSummary.tsx';
import { cardIds } from './SummaryCards.tsx';
import type { ReportSummary } from './legacyPolicy.ts';
import styles from './LegacyConfig.module.css';

export type ReportDetailPanelProps = {
  baseId: string;
  cardId: string;
  detail: LegacyReceiptDetail;
  report: LegacyConfigReport | null;
  summary: ReportSummary;
};

function PasswordPanel({ detail, report, summary }: Omit<ReportDetailPanelProps, 'baseId' | 'cardId'>) {
  const omitted = detail.bundle?.interface?.omittedSecretLabels ?? [];
  const hasReference = detail.credentialRef !== null || (report?.planned.credential?.credentialRef ?? null) !== null;
  return (
    <>
      <dl className={styles.facts} data-testid="legacy-password-facts">
        <div className={styles.fact}>
          <dt>Wat er met het wachtwoord gebeurde</dt>
          <dd>
            <Term domain="legacySecretOutcome" code={detail.secretOutcome} unknownLabel="Andere uitkomst" />
          </dd>
        </div>
        <div className={styles.fact}>
          <dt>Verwijzing</dt>
          <dd>{hasReference ? LEGACY_CARD_TEXT.passwordReference : LEGACY_CARD_TEXT.passwordNoReference}</dd>
        </div>
      </dl>
      {omitted.length > 0 && (
        <>
          <h3 className={styles.subTitle}>Geheime instellingen die nooit worden doorgegeven</h3>
          <ul className={styles.plainList} data-testid="legacy-omitted-secrets">
            {omitted.map((label) => (
              <li key={label}>{label}</li>
            ))}
          </ul>
        </>
      )}
      {report !== null && summary.secret.length > 0 && <LegacyReportView report={report} items={summary.secret} />}
      {detail.credentialRef !== null && (
        <TechnicalDetails items={[{ name: 'Verwijzing naar het wachtwoord', value: detail.credentialRef }]} />
      )}
    </>
  );
}

function DefinitionPanel({ detail, report }: Omit<ReportDetailPanelProps, 'baseId' | 'cardId' | 'summary'>) {
  const bundle = detail.bundle;
  const detach = report?.planned.detach ?? null;
  return (
    <>
      {bundle !== null && <Definition bundle={bundle} />}
      {report !== null && (
        <dl className={styles.facts} data-testid="legacy-definition-shared">
          <div className={styles.fact}>
            <dt>Gedeeld met</dt>
            <dd>{report.sharedWith.length === 0 ? 'Geen andere interfaces' : report.sharedWith.join(', ')}</dd>
          </div>
          {detach !== null && (
            <div className={styles.fact}>
              <dt>Loskoppelen</dt>
              <dd>
                Van de gedeelde beschrijving {detach.fromDefinitionCode} naar de eigen beschrijving {detach.toDefinitionCode}
              </dd>
            </div>
          )}
        </dl>
      )}
      <TechnicalDetails
        items={[
          { name: 'Inhoudscontrolegetal', value: detail.receipt.contentHash },
          ...(detail.receipt.definitionContentHash === null
            ? []
            : [{ name: 'Controlegetal van de importsoort', value: detail.receipt.definitionContentHash }]),
          { name: 'Versie van het formaat', value: String(detail.receipt.bundleVersion) },
        ]}
      />
    </>
  );
}

export const ReportDetailPanel = forwardRef<HTMLElement, ReportDetailPanelProps>(function ReportDetailPanel(
  { baseId, cardId, detail, report, summary },
  ref,
) {
  const ids = cardIds(baseId, cardId);
  let body: React.ReactNode = null;
  if (cardId === 'blocking' && report !== null) {
    body = <LegacyReportView report={report} items={summary.blocking} />;
  } else if (cardId === 'warnings' && report !== null) {
    body = <LegacyReportView report={report} items={[...summary.warning, ...summary.notMigrated]} />;
  } else if (cardId === 'migrated' && report !== null) {
    body = (
      <>
        <PlannedSummary report={report} />
        <LegacyReportView report={report} items={summary.migrated} />
      </>
    );
  } else if (cardId === 'password') {
    body = <PasswordPanel detail={detail} report={report} summary={summary} />;
  } else if (cardId === 'definition') {
    body = <DefinitionPanel detail={detail} report={report} />;
  } else if (cardId === 'target' && detail.bundle !== null) {
    body = <Interface bundle={detail.bundle} />;
  }
  return (
    <section
      ref={ref}
      id={ids.panel}
      role="region"
      aria-labelledby={ids.title}
      className={styles.detailPanel}
      data-testid={`legacy-panel-${cardId}`}
    >
      {body}
    </section>
  );
});
