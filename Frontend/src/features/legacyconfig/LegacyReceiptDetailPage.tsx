/**
 * `/setup/legacy-config/:receiptId` — het detail van één ontvangst (S9, design §2 en §5): de bundel zonder secret, de
 * controle vooraf (PREVIEW) met de samenvatting van wat aangemaakt of hergebruikt wordt, het rapport per soort punt en
 * het overnemen (APPLY) met verplichte reden.
 *
 * - De controle vooraf wordt enkel opgehaald voor een ontvangst met status `RECEIVED`; de andere statussen zijn niet
 *   meer toepasbaar. Ze schrijft nooit iets.
 * - De keuzes (andere code, tekenset, bibliotheek) horen bij de controle: een andere keuze = een andere controle en dus
 *   een andere `previewFingerprint`. Dezelfde keuzes gaan mee bij het overnemen.
 * - Na een geslaagde overname toont het scherm het rapport van die overname en de configuratiebevindingen van de nieuwe
 *   conceptversie; er is niets geactiveerd (dat doet de mens in de inrichting).
 * - Alle endpoints vragen `MANAGE`; zonder dat recht staat hier enkel de reden en wordt er niets opgehaald.
 */

import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { gateTitle } from '../../actor/gate.ts';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import * as legacyConfigApi from '../../api/legacyConfig.ts';
import { PERMISSION_MANAGE } from '../../api/types.ts';
import type { LegacyApplyResult, LegacyLibraryCandidate, LegacyReceiptDetail } from '../../api/types.ts';
import { BackIcon } from '../../components/icons.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import button from '../../components/Button.module.css';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { formatDateTime } from '../../format.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { Term } from '../../terms/Term.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { ApplyDialog } from './ApplyDialog.tsx';
import { BundleSummary } from './BundleSummary.tsx';
import { applyGate, NO_CHOICES, toChoices, type LegacyChoicesForm } from './legacyPolicy.ts';
import { LegacyReportView } from './LegacyReportView.tsx';
import { PlannedSummary } from './PlannedSummary.tsx';
import { PreviewChoices } from './PreviewChoices.tsx';
import pageCss from '../../components/Page.module.css';
import styles from './LegacyConfig.module.css';

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className={styles.fact}>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

function Origin({ detail }: { detail: LegacyReceiptDetail }) {
  const { receipt } = detail;
  return (
    <>
      <dl className={styles.facts} data-testid="legacy-origin-facts">
        <Fact label="Omgeving">{receipt.originEnvironment}</Fact>
        <Fact label="Dossier">{receipt.originDossier}</Fact>
        <Fact label="Ontvangen op">{formatDateTime(receipt.receivedAt)}</Fact>
        <Fact label="Geëxporteerd op">{formatDateTime(receipt.exportedAt)}</Fact>
        <Fact label="Wachtwoord">
          <Term domain="legacySecretOutcome" code={detail.secretOutcome} unknownLabel="Andere uitkomst" />
        </Fact>
        {receipt.supersededByReceiptId !== null && (
          <Fact label="Vervangen door">
            <Link to={`/setup/legacy-config/${receipt.supersededByReceiptId}`}>ontvangst #{receipt.supersededByReceiptId}</Link>
          </Fact>
        )}
        {receipt.appliedAt !== null && <Fact label="Overgenomen op">{formatDateTime(receipt.appliedAt)}</Fact>}
        {receipt.appliedBy !== null && <Fact label="Overgenomen door">{receipt.appliedBy}</Fact>}
      </dl>
      {detail.credentialRef !== null && (
        <TechnicalDetails items={[{ name: 'Verwijzing naar het wachtwoord', value: detail.credentialRef }]} />
      )}
    </>
  );
}

function ApplyResultView({ result }: { result: LegacyApplyResult }) {
  return (
    <section className={`${pageCss.card} ${styles.section}`} aria-label="Resultaat van het overnemen" data-testid="legacy-apply-result">
      <div className={pageCss.cardHead}>
        <h2 className={pageCss.subtitle}>De configuratie is overgenomen</h2>
      </div>
      <div className={pageCss.cardPad}>
        <p className={styles.success}>
          De overname is gelukt. Er is niets geactiveerd: kijk de nieuwe conceptversie na in de inrichting en activeer ze
          zelf.
        </p>
        <PlannedSummary report={result.report} />
        {result.configurationFindings.length > 0 && (
          <>
            <h3 className={styles.subTitle}>Wat nog niet klopt in de nieuwe conceptversie ({result.configurationFindings.length})</h3>
            <ul className={styles.plainList} data-testid="legacy-apply-findings">
              {result.configurationFindings.map((finding, index) => (
                <li key={`${index}:${finding.code}`}>
                  <Term domain="issueCode" code={finding.code} unknownLabel="Fout in de beschrijving van het bestand" />
                  <TechnicalDetails
                    items={[
                      { name: 'Code', value: finding.code },
                      ...(finding.fieldName === null ? [] : [{ name: 'Veld', value: finding.fieldName }]),
                      { name: 'Melding van de server (Engels)', value: finding.message },
                    ]}
                  />
                </li>
              ))}
            </ul>
          </>
        )}
        <h3 className={styles.subTitle}>Rapport van de overname</h3>
        <LegacyReportView report={result.report} />
      </div>
    </section>
  );
}

function ReceiptBody({ receiptId }: { receiptId: number }) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const detail = useQuery(`legacy-receipt:${receiptId}`, (signal) => legacyConfigApi.getLegacyReceipt(receiptId, signal));
  const [draft, setDraft] = useState<LegacyChoicesForm>(NO_CHOICES);
  const [applied, setApplied] = useState<LegacyChoicesForm>(NO_CHOICES);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [result, setResult] = useState<LegacyApplyResult | null>(null);
  // De kandidaten blijven staan nadat er gekozen is, ook al geeft de server dan geen kandidaten meer.
  const [candidates, setCandidates] = useState<readonly LegacyLibraryCandidate[]>([]);

  const receipt = detail.data?.receipt ?? null;
  const previewEnabled = receipt !== null && receipt.status === 'RECEIVED' && result === null;
  const choices = toChoices(applied);
  const previewKey = `legacy-preview:${receiptId}:${previewEnabled}:${choices.definitionCodeOverride ?? ''}:${choices.charsetWhenUndeclared ?? ''}:${choices.libraryCode ?? ''}`;
  const preview = useQuery(previewKey, (signal) =>
    previewEnabled ? legacyConfigApi.previewLegacyReceipt(receiptId, choices, signal) : Promise.resolve(null),
  );

  const current = !preview.loading && preview.error === null ? preview.data : null;
  const report = current?.report ?? null;
  const offered = report?.planned.link?.libraryCandidates ?? [];
  if (offered.length > 0 && offered !== candidates) {
    setCandidates(offered);
  }
  const gate = withPermission(manageGate, applyGate(receipt?.status ?? '', report));

  function handleApplied(next: LegacyApplyResult) {
    setDialogOpen(false);
    setResult(next);
    detail.reload();
  }

  return (
    <>
      <div className={styles.detailHead}>
        <Link to="/setup/legacy-config" className={styles.backButton} aria-label="Terug naar de ontvangsten">
          <BackIcon />
        </Link>
        <div className={styles.detailId}>
          <h1 className={pageCss.title}>
            Ontvangst #{receiptId}
            {receipt !== null && <> — interface {receipt.interfaceCode}</>}
          </h1>
          {receipt !== null && (
            <div className={styles.chips}>
              <StatusBadge status={result?.receiptStatus ?? receipt.status} domain="legacyReceiptStatus" />
            </div>
          )}
        </div>
      </div>

      {detail.error !== null && <ErrorBanner error={detail.error} />}
      {detail.loading && detail.data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {detail.data !== null && (
        <>
          <section className={pageCss.card} aria-label="Herkomst en status">
            <div className={pageCss.cardHead}>
              <h2 className={pageCss.subtitle}>Herkomst en status</h2>
            </div>
            <div className={pageCss.cardPad}>
              <Origin detail={detail.data} />
            </div>
          </section>

          <section className={`${pageCss.card} ${styles.section}`} aria-label="Ontvangen instellingen">
            <div className={pageCss.cardHead}>
              <h2 className={pageCss.subtitle}>Ontvangen instellingen</h2>
            </div>
            <div className={pageCss.cardPad}>
              <BundleSummary detail={detail.data} />
            </div>
          </section>

          {result !== null && <ApplyResultView result={result} />}

          {result === null && detail.data.receipt.status !== 'RECEIVED' && (
            <section className={`${pageCss.card} ${styles.section}`} aria-label="Overnemen">
              <div className={pageCss.cardPad}>
                <p data-testid="legacy-not-applicable">
                  Deze ontvangst heeft de status{' '}
                  <Term domain="legacyReceiptStatus" code={detail.data.receipt.status} unknownLabel="Andere status" /> en kan niet
                  meer overgenomen worden.
                </p>
              </div>
            </section>
          )}

          {previewEnabled && (
            <section className={`${pageCss.card} ${styles.section}`} aria-label="Controle vooraf">
              <div className={pageCss.cardHead}>
                <h2 className={pageCss.subtitle}>Controle vooraf</h2>
              </div>
              <div className={pageCss.cardPad}>
                <WhatIsThis>
                  De controle toont wat het overnemen zou doen, zonder iets te wijzigen. Punten die blokkeren, moet u
                  eerst oplossen; vaak met een keuze hieronder. Wat u kiest, gaat mee bij het overnemen.
                </WhatIsThis>

                <PreviewChoices
                  draft={draft}
                  onDraftChange={setDraft}
                  libraryCandidates={candidates}
                  onApply={setApplied}
                  loading={preview.loading}
                />

                {preview.error !== null && <ErrorBanner error={preview.error} />}
                {preview.loading && <p className={styles.loading}>De controle wordt uitgevoerd…</p>}

                {report !== null && (
                  <>
                    <p className={styles.chips} data-testid="legacy-outcome">
                      <StatusBadge status={report.outcome} domain="legacyOutcome" />
                    </p>
                    <h3 className={styles.subTitle}>Wat wordt aangemaakt of hergebruikt</h3>
                    <PlannedSummary report={report} />
                    <h3 className={styles.subTitle}>Rapport</h3>
                    <LegacyReportView report={report} />

                    <div className={styles.actions}>
                      <button
                        type="button"
                        className={button.primary}
                        disabled={!gate.allowed}
                        title={gateTitle(gate)}
                        onClick={() => setDialogOpen(true)}
                      >
                        Overnemen…
                      </button>
                      {!gate.allowed && (
                        <p className={styles.blockedReason} data-testid="legacy-apply-blocked-reason">
                          {gate.reason}
                        </p>
                      )}
                    </div>
                  </>
                )}
              </div>
            </section>
          )}

          {dialogOpen && (
            <ApplyDialog
              receipt={detail.data.receipt}
              report={report}
              previewFingerprint={current?.previewFingerprint ?? null}
              choices={choices}
              onClose={() => setDialogOpen(false)}
              onApplied={handleApplied}
              onStale={preview.reload}
              onNotApplicable={detail.reload}
            />
          )}
        </>
      )}
    </>
  );
}

export function LegacyReceiptDetailPage() {
  const params = useParams();
  const manage = usePermissionGate(PERMISSION_MANAGE);
  const receiptId = Number(params.receiptId);
  return (
    <div className={styles.page}>
      {!Number.isInteger(receiptId) || receiptId <= 0 ? (
        <p className={styles.noPermission}>Dit is geen geldig nummer van een ontvangst.</p>
      ) : manage.allowed ? (
        <ReceiptBody key={receiptId} receiptId={receiptId} />
      ) : (
        <div className={`${pageCss.card} ${styles.noPermission}`} data-testid="legacy-no-permission">
          <p>{manage.reason}</p>
          <p>Voor de overname van legacy-configuratie is dit recht nodig, omdat de ontvangen gegevens servers en logins tonen.</p>
        </div>
      )}
    </div>
  );
}
