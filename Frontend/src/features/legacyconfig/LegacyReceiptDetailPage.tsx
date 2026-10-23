/**
 * `/setup/legacy-config/:receiptId` — het detail van één ontvangst (S9, design §2 en §5), als kaartenoverzicht
 * (docs/decisions.md 2026-10-22): een kop met status, herkomstregel en acties, een inklapbare kaart met de keuzes voor de
 * controle, en een rooster met kaarten (Blokkeert, Waarschuwingen, Overgenomen/gepland, Wachtwoord, Importsoort,
 * Bestemming) waarvan er één tegelijk als detailpaneel onder het rooster openklapt.
 *
 * - De controle vooraf wordt enkel opgehaald voor een ontvangst met status `RECEIVED`; de andere statussen zijn niet
 *   meer toepasbaar. Ze schrijft nooit iets. Bij een overgenomen ontvangst komen de kaarten uit het bewaarde rapport.
 * - De keuzes (andere code, tekenset, bibliotheek) horen bij de controle: een andere keuze = een andere controle en dus
 *   een andere `previewFingerprint`. Dezelfde keuzes gaan mee bij het overnemen.
 * - Na een geslaagde overname toont het scherm het rapport van die overname en de configuratiebevindingen van de nieuwe
 *   conceptversie; er is niets geactiveerd (dat doet de mens in de inrichting).
 * - Alle endpoints vragen `MANAGE`; zonder dat recht staat hier enkel de reden en wordt er niets opgehaald.
 * - Een wachtwoord staat nergens op dit scherm: enkel de uitkomst, of er een verwijzing is en de namen van geheime labels.
 */

import { useEffect, useId, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { gateTitle } from '../../actor/gate.ts';
import { usePermissionGate, withPermission } from '../../actor/permissions.ts';
import * as legacyConfigApi from '../../api/legacyConfig.ts';
import { PERMISSION_MANAGE } from '../../api/types.ts';
import type { LegacyApplyResult, LegacyConfigReport, LegacyLibraryCandidate, LegacyReceiptDetail } from '../../api/types.ts';
import { BackIcon } from '../../components/icons.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import button from '../../components/Button.module.css';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { formatDateTime } from '../../format.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { term } from '../../terms/index.ts';
import { LEGACY_CARD_TEXT, LEGACY_CARD_TITLES, LEGACY_CHOICES_TEXT, LEGACY_HEAD_TEXT } from '../../terms/legacyCards.ts';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { Term } from '../../terms/Term.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { ApplyDialog } from './ApplyDialog.tsx';
import { DiscardDialog } from './DiscardDialog.tsx';
import { DraftLinks } from './DraftLinks.tsx';
import {
  applyGate,
  detachChoiceVisible,
  discardPolicyGate,
  itemTitle,
  NO_CHOICES,
  summarizeReport,
  toChoices,
  type LegacyChoicesForm,
  type ReportSummary,
} from './legacyPolicy.ts';
import { LegacyReportView } from './LegacyReportView.tsx';
import { PlannedSummary } from './PlannedSummary.tsx';
import { PreviewChoices } from './PreviewChoices.tsx';
import { ReportDetailPanel } from './ReportDetailPanel.tsx';
import { SummaryCards, type CardModel } from './SummaryCards.tsx';
import pageCss from '../../components/Page.module.css';
import styles from './LegacyConfig.module.css';

function Part({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <span className={styles.originPart}>
      {label} <b>{children}</b>
    </span>
  );
}

/** De herkomstregel in de kop: omgeving, dossier en ontvangst, plus wat er daarna met de ontvangst gebeurde. */
function OriginLine({ detail }: { detail: LegacyReceiptDetail }) {
  const { receipt } = detail;
  return (
    <p className={styles.originLine} data-testid="legacy-origin-facts">
      <Part label="Omgeving">{receipt.originEnvironment}</Part>
      <Part label="Dossier">{receipt.originDossier}</Part>
      <Part label="Ontvangen op">{formatDateTime(receipt.receivedAt)}</Part>
      <Part label="Geëxporteerd op">{formatDateTime(receipt.exportedAt)}</Part>
      {receipt.supersededByReceiptId !== null && (
        <span className={styles.originPart}>
          Vervangen door <Link to={`/setup/legacy-config/${receipt.supersededByReceiptId}`}>ontvangst #{receipt.supersededByReceiptId}</Link>
        </span>
      )}
      {receipt.appliedAt !== null && <Part label="Overgenomen op">{formatDateTime(receipt.appliedAt)}</Part>}
      {receipt.appliedBy !== null && <Part label="Overgenomen door">{receipt.appliedBy}</Part>}
    </p>
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
        <DraftLinks planned={result.report.planned} />
        {result.configurationFindings.length > 0 && (
          <>
            <h3 className={styles.subTitle}>Wat nog niet klopt in de nieuwe conceptversie ({result.configurationFindings.length})</h3>
            <ul className={styles.plainList} data-testid="legacy-apply-findings">
              {result.configurationFindings.map((finding, index) => (
                <li key={`${index}:${finding.code}`}>
                  <Term domain="issueCode" code={finding.code} unknownLabel="Fout in de importdefinitie" />
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

/** De kaarten van het rooster, afgeleid van het rapport (indien er een is) en de ontvangen bundel. */
function buildCards(
  detail: LegacyReceiptDetail,
  report: LegacyConfigReport | null,
  summary: ReportSummary,
  applied: boolean,
): CardModel[] {
  const cards: CardModel[] = [];
  const bundle = detail.bundle;

  if (report !== null) {
    const blockingCount = summary.blocking.length;
    cards.push({
      id: 'blocking',
      title: LEGACY_CARD_TITLES.blocking,
      count: blockingCount,
      topLine: summary.firstBlocking !== null ? itemTitle(summary.firstBlocking) : LEGACY_CARD_TEXT.blockingNone,
      tone: blockingCount > 0 ? 'danger' : 'success',
      empty: blockingCount === 0,
    });
    const firstWarning = summary.warning[0] ?? summary.notMigrated[0] ?? null;
    cards.push({
      id: 'warnings',
      title: LEGACY_CARD_TITLES.warnings,
      count: summary.warningsTotal,
      topLine: firstWarning !== null ? itemTitle(firstWarning) : LEGACY_CARD_TEXT.warningsNone,
      subLine:
        summary.warningsTotal === 0
          ? undefined
          : `${LEGACY_CARD_TEXT.warningCount(summary.warning.length)} · ${LEGACY_CARD_TEXT.notMigratedCount(summary.notMigrated.length)}`,
      tone: summary.warningsTotal > 0 ? 'warning' : 'neutral',
      empty: summary.warningsTotal === 0,
    });
    cards.push({
      id: 'migrated',
      title: applied ? LEGACY_CARD_TITLES.migratedApplied : LEGACY_CARD_TITLES.migrated,
      count: summary.migrated.length,
      topLine: summary.migrated[0] !== undefined ? itemTitle(summary.migrated[0]) : LEGACY_CARD_TEXT.migratedNone,
      tone: 'neutral',
      // Ook zonder gemelde punten staat in het paneel wat er aangemaakt of hergebruikt wordt.
      empty: false,
    });
  }

  const omitted = bundle?.interface?.omittedSecretLabels ?? [];
  const passwordEmpty =
    detail.secretOutcome === null && omitted.length === 0 && summary.secret.length === 0 && detail.credentialRef === null;
  const hasReference = detail.credentialRef !== null || (report?.planned.credential?.credentialRef ?? null) !== null;
  cards.push({
    id: 'password',
    title: LEGACY_CARD_TITLES.password,
    count: omitted.length,
    topLine: passwordEmpty
      ? LEGACY_CARD_TEXT.passwordNone
      : `${term('legacySecretOutcome', detail.secretOutcome).label} · ${
          hasReference ? LEGACY_CARD_TEXT.passwordReference : LEGACY_CARD_TEXT.passwordNoReference
        }`,
    tone: 'neutral',
    empty: passwordEmpty,
  });

  const definition = bundle?.definition ?? null;
  const definitionStatus = bundle?.definitionStatus;
  cards.push({
    id: 'definition',
    title: LEGACY_CARD_TITLES.definition,
    count: definition?.lines?.length ?? 0,
    topLine:
      definition === null
        ? definitionStatus !== undefined
          ? term('legacyDefinitionStatus', definitionStatus).label
          : LEGACY_CARD_TEXT.definitionNone
        : `${definition.code ?? '—'}${definitionStatus === undefined ? '' : ` · ${term('legacyDefinitionStatus', definitionStatus).label}`}`,
    subLine:
      definition === null
        ? undefined
        : [
            LEGACY_CARD_TEXT.linesCount(definition.lines?.length ?? 0),
            LEGACY_CARD_TEXT.mapsCount(definition.maps?.length ?? 0),
            LEGACY_CARD_TEXT.excludesCount(definition.excludes?.length ?? 0),
          ].join(' · '),
    tone: 'neutral',
    empty: definition === null,
  });

  const planned = report?.planned ?? null;
  const parts: string[] = [];
  if (planned?.link != null) {
    parts.push(`Koppeling ${planned.link.code}`);
    parts.push(`Bibliotheek ${planned.link.libraryCode ?? LEGACY_CARD_TEXT.libraryUndetermined}`);
  }
  if (planned?.task != null) parts.push(`Taak ${planned.task.name}`);
  const interfaceCode = bundle?.interface?.code;
  cards.push({
    id: 'target',
    title: LEGACY_CARD_TITLES.target,
    count: parts.length,
    topLine:
      bundle === null
        ? LEGACY_CARD_TEXT.targetNone
        : parts.length > 0
          ? parts[0]!
          : interfaceCode !== undefined
            ? `Interface ${interfaceCode}`
            : LEGACY_CARD_TEXT.targetNone,
    subLine: parts.length > 1 ? parts.slice(1).join(' · ') : undefined,
    tone: 'neutral',
    empty: bundle === null,
  });
  return cards;
}

/** Een inklapbare kaart met een kop en een knop, voor de keuzes van de controle. */
function CollapsibleCard({
  title,
  open,
  onToggle,
  children,
}: {
  title: string;
  open: boolean;
  onToggle: () => void;
  children: React.ReactNode;
}) {
  const id = useId();
  return (
    <section className={`${pageCss.card} ${styles.section}`} role="group" aria-labelledby={`${id}-title`}>
      <div className={styles.collapseHead}>
        <h2 id={`${id}-title`} className={pageCss.subtitle}>
          {title}
        </h2>
        <button
          type="button"
          id={`${id}-button`}
          className={`${button.secondary} ${button.small}`}
          aria-expanded={open}
          aria-controls={`${id}-body`}
          aria-labelledby={`${id}-button ${id}-title`}
          onClick={onToggle}
        >
          {open ? LEGACY_CHOICES_TEXT.hide : LEGACY_CHOICES_TEXT.show}
        </button>
      </div>
      {open && (
        <div id={`${id}-body`} className={pageCss.cardPad}>
          {children}
        </div>
      )}
    </section>
  );
}

function DiscardedAudit({ detail }: { detail: LegacyReceiptDetail }) {
  return (
    <section className={`${pageCss.card} ${styles.section}`} aria-label="Verworpen" data-testid="legacy-discarded-audit">
      <div className={pageCss.cardHead}>
        <h2 className={pageCss.subtitle}>Verworpen</h2>
      </div>
      <div className={pageCss.cardPad}>
        <dl className={styles.facts}>
          {detail.discardedAt != null && (
            <div className={styles.fact}>
              <dt>Verworpen op</dt>
              <dd>{formatDateTime(detail.discardedAt)}</dd>
            </div>
          )}
          {detail.discardedBy != null && (
            <div className={styles.fact}>
              <dt>Verworpen door</dt>
              <dd>{detail.discardedBy}</dd>
            </div>
          )}
          {detail.discardedReason != null && (
            <div className={styles.fact}>
              <dt>Reden van het verwerpen</dt>
              <dd>{detail.discardedReason}</dd>
            </div>
          )}
        </dl>
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
  const [discardOpen, setDiscardOpen] = useState(false);
  const [result, setResult] = useState<LegacyApplyResult | null>(null);
  // De kandidaten blijven staan nadat er gekozen is, ook al geeft de server dan geen kandidaten meer.
  const [candidates, setCandidates] = useState<readonly LegacyLibraryCandidate[]>([]);
  // `undefined` = de standaardregel (Blokkeert open als er iets blokkeert); `null` = bewust alles dicht.
  const [chosenCard, setChosenCard] = useState<string | null | undefined>(undefined);
  // `null` = de standaardregel (open zolang er een blokkerend punt is).
  const [choicesToggled, setChoicesToggled] = useState<boolean | null>(null);
  const baseId = useId();
  const panelRef = useRef<HTMLElement | null>(null);
  const userOpened = useRef(false);

  const receipt = detail.data?.receipt ?? null;
  const previewEnabled = receipt !== null && receipt.status === 'RECEIVED' && result === null;
  const choices = toChoices(applied);
  const previewKey = `legacy-preview:${receiptId}:${previewEnabled}:${choices.definitionCodeOverride ?? ''}:${choices.charsetWhenUndeclared ?? ''}:${choices.libraryCode ?? ''}:${choices.detachFromShared === true}:${choices.detachedDefinitionCode ?? ''}`;
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
  const discardGate = withPermission(manageGate, discardPolicyGate(receipt?.status ?? ''));

  // Het rapport achter de kaarten: de controle vooraf, het rapport van de overname die net gelukt is, of het bewaarde rapport.
  const shownReport: LegacyConfigReport | null =
    result?.report ?? (receipt?.status === 'RECEIVED' ? report : (detail.data?.applyReport ?? null));
  const summary = summarizeReport(shownReport);
  const isApplied = result !== null || receipt?.status === 'APPLIED';
  const cards = detail.data === null ? [] : buildCards(detail.data, shownReport, summary, isApplied);
  const wantedCard = chosenCard === undefined ? (summary.blocking.length > 0 ? 'blocking' : null) : chosenCard;
  const openCard = cards.some((card) => card.id === wantedCard && !card.empty) ? wantedCard : null;
  const choicesOpen = choicesToggled ?? summary.blocking.length > 0;

  useEffect(() => {
    // Alleen na een klik, en zonder de focus te verplaatsen: de pagina schuift enkel net genoeg.
    if (userOpened.current && openCard !== null) {
      panelRef.current?.scrollIntoView?.({ block: 'nearest' });
    }
  }, [openCard]);

  function handleToggle(cardId: string) {
    userOpened.current = true;
    setChosenCard(openCard === cardId ? null : cardId);
  }

  function handleApplyChoices(next: LegacyChoicesForm) {
    // Wie keuzes toepast, werkt in dit blok: het blijft open, ook als de nieuwe controle niets meer blokkeert.
    setChoicesToggled(true);
    setApplied(next);
  }

  function handleRerun() {
    setChoicesToggled(true);
    if (JSON.stringify(draft) === JSON.stringify(applied)) {
      preview.reload();
    } else {
      setApplied(draft);
    }
  }

  function handleApplied(next: LegacyApplyResult) {
    setDialogOpen(false);
    setResult(next);
    detail.reload();
  }

  function handleDiscarded() {
    setDiscardOpen(false);
    detail.reload();
  }

  const status = result?.receiptStatus ?? receipt?.status ?? '';
  const actionable = receipt !== null && receipt.status === 'RECEIVED' && result === null;
  const prodisUnavailable = shownReport?.prodisReference === 'UNAVAILABLE';

  return (
    <>
      <header className={styles.detailHead}>
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
              <StatusBadge status={status} domain="legacyReceiptStatus" />
              {previewEnabled && (
                <span aria-live="polite" className={styles.outcomeLive}>
                  {report !== null && (
                    <span data-testid="legacy-outcome">
                      <StatusBadge status={report.outcome} domain="legacyOutcome" />
                    </span>
                  )}
                </span>
              )}
              {shownReport !== null && (
                <span
                  className={prodisUnavailable ? styles.chipWarning : styles.chipPlain}
                  data-testid="legacy-prodis-check"
                >
                  {LEGACY_HEAD_TEXT.prodisCheck}:{' '}
                  <Term domain="legacyProdisReference" code={shownReport.prodisReference} unknownLabel="Andere uitkomst" />
                </span>
              )}
            </div>
          )}
          {detail.data !== null && <OriginLine detail={detail.data} />}
        </div>
        {actionable && (
          <div className={styles.headActions}>
            <button
              type="button"
              className={button.secondary}
              disabled={preview.loading}
              aria-label={LEGACY_HEAD_TEXT.rerunLabel}
              onClick={handleRerun}
            >
              {LEGACY_HEAD_TEXT.rerun}
            </button>
            <button
              type="button"
              className={button.primary}
              disabled={!gate.allowed}
              title={gateTitle(gate)}
              onClick={() => setDialogOpen(true)}
            >
              Overnemen…
            </button>
            <button
              type="button"
              className={button.secondary}
              disabled={!discardGate.allowed}
              title={gateTitle(discardGate)}
              onClick={() => setDiscardOpen(true)}
            >
              Verwerpen…
            </button>
          </div>
        )}
      </header>
      {actionable && report !== null && !gate.allowed && (
        <p className={styles.blockedReason} data-testid="legacy-apply-blocked-reason">
          {gate.reason}
        </p>
      )}
      {receipt !== null && result === null && receipt.status !== 'RECEIVED' && receipt.status !== 'DISCARDED' && (
        <p className={styles.muted} data-testid="legacy-not-applicable">
          Deze ontvangst heeft de status{' '}
          <Term domain="legacyReceiptStatus" code={receipt.status} unknownLabel="Andere status" /> en kan niet meer
          overgenomen worden.
        </p>
      )}

      {detail.error !== null && <ErrorBanner error={detail.error} />}
      {detail.loading && detail.data === null && <p className={styles.loading}>Bezig met laden…</p>}

      {detail.data !== null && (
        <>
          {result !== null && <ApplyResultView result={result} />}

          {result === null && detail.data.receipt.status === 'APPLIED' && detail.data.applyReport != null && (
            <section className={`${pageCss.card} ${styles.section}`} aria-label="Aangemaakte versie">
              <div className={pageCss.cardPad}>
                <DraftLinks planned={detail.data.applyReport.planned} />
              </div>
            </section>
          )}

          {detail.data.receipt.status === 'DISCARDED' && <DiscardedAudit detail={detail.data} />}

          {previewEnabled && (
            <CollapsibleCard
              title={LEGACY_CHOICES_TEXT.title}
              open={choicesOpen}
              onToggle={() => setChoicesToggled(!choicesOpen)}
            >
              <WhatIsThis>
                De controle toont wat het overnemen zou doen, zonder iets te wijzigen. Punten die blokkeren, moet u eerst
                oplossen; vaak met een keuze hieronder. Wat u kiest, gaat mee bij het overnemen.
              </WhatIsThis>
              <PreviewChoices
                draft={draft}
                onDraftChange={setDraft}
                libraryCandidates={candidates}
                detachVisible={detachChoiceVisible(report, draft.detachFromShared || applied.detachFromShared)}
                onApply={handleApplyChoices}
                loading={preview.loading}
              />
            </CollapsibleCard>
          )}

          {previewEnabled && preview.error !== null && <ErrorBanner error={preview.error} />}
          {previewEnabled && preview.loading && <p className={styles.loading}>De controle wordt uitgevoerd…</p>}

          <div className={styles.section}>
            <SummaryCards baseId={baseId} cards={cards} openId={openCard} onToggle={handleToggle} />
            {openCard !== null && (
              <ReportDetailPanel
                ref={panelRef}
                baseId={baseId}
                cardId={openCard}
                detail={detail.data}
                report={shownReport}
                summary={summary}
              />
            )}
          </div>

          {discardOpen && (
            <DiscardDialog
              receipt={detail.data.receipt}
              onClose={() => setDiscardOpen(false)}
              onDiscarded={handleDiscarded}
              onNotApplicable={detail.reload}
            />
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
