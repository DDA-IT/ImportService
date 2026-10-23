/**
 * Tab "Overzicht" van het importdefinitiedetail (R2, mock `tTabOverzicht`): de stappenbalk (waar zit een importdefinitie in
 * de keten), kleine kaarten (actieve versie, versies, invulpunten, gebruikt door, koppelingen), de gegevens in het
 * Prodis-`dl`-patroon en de kaart "Bestandsopbouw" met een knop naar "Bestand en velden". Toont enkel wat de API levert:
 * "Opmerking" uit de mock heeft geen bron en staat er niet.
 */

import { Link } from 'react-router-dom';
import * as setupApi from '../../api/setup.ts';
import pageCss from '../../components/Page.module.css';
import button from '../../components/Button.module.css';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { MiniCard } from '../../components/SummaryCards.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import {
  DEFINITIONS_TEXT,
  INACTIVE_HINT,
  INACTIVE_LABEL,
  ORIGIN_HINT,
  ORIGIN_LABEL,
  STATUS_HINT,
  STATUS_LABEL,
  STATUS_TONE,
} from '../../terms/importDefinitions.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { FileSection } from '../setup/RevisionReadView.tsx';
import { activeRevisionOf, defaultRevisionOf, STEP_DEFINITION } from '../setup/setupDashboardModel.ts';
import { KeyValue, StepBar } from '../setup/SupplierOverviewTab.tsx';
import { definitionHref } from './definitionRoutes.ts';
import { OverwriteFlagRow } from './OverwriteFlagRow.tsx';
import { isInactiveOrganisation, openDraftNumberOf } from './definitionsModel.ts';
import { useDefinitionDetailContext } from './TemplateDetailPage.tsx';
import styles from '../setup/SetupScreens.module.css';

const TEXT = DEFINITIONS_TEXT;

function FileCard({ definitionId, revisionId }: { definitionId: number; revisionId: number }) {
  const detail = useQuery(`definition-overview-file:${definitionId}:${revisionId}`, (signal) =>
    setupApi.getRevisionDetail(definitionId, revisionId, signal),
  );
  return (
    <>
      {detail.error !== null && <ErrorBanner error={detail.error} />}
      {detail.loading && detail.data === null && <p className={styles.loading}>{TEXT.loading}</p>}
      {detail.data !== null && <FileSection revision={detail.data} />}
    </>
  );
}

export function DefinitionOverviewTab() {
  const { bundle, reusable, status, origin, extras, reload } = useDefinitionDetailContext();
  const { definition, revisions, organisation, links } = bundle;
  const id = definition.id;
  const idPrefix = `definition-${id}`;

  const active = activeRevisionOf(revisions);
  const shown = defaultRevisionOf(revisions, null);

  // Wat uit de vlaggedekte aanvulling komt: '…' tijdens het laden, '—' als ze niet beschikbaar is.
  const unknown = '—';
  const loadingValue = '…';
  let placeholderCount: string | number = reusable ? loadingValue : 0;
  let usedByCount: string | number = reusable ? loadingValue : organisation === null ? 0 : 1;
  let linkCount: string | number = reusable ? loadingValue : links.length;
  if (reusable && extras.data !== null) {
    const { materialisations, bookmarks } = extras.data;
    usedByCount = materialisations.ok ? materialisations.data.length : unknown;
    linkCount = materialisations.ok ? materialisations.data.reduce((sum, row) => sum + row.importLinkCount, 0) : unknown;
    placeholderCount = bookmarks === null ? 0 : bookmarks.ok ? bookmarks.data.bookmarks.length : unknown;
  }

  return (
    <div data-testid="definition-overview">
      <p className={styles.rowMeta}>{reusable ? TEXT.stepsIntroReusable : TEXT.stepsIntroOwn}</p>
      <StepBar current={STEP_DEFINITION} />
      <div className={styles.mini} role="group" aria-label={TEXT.miniLabel}>
        <MiniCard
          id={`${idPrefix}-active`}
          title={TEXT.mini.activeVersion}
          value={active === null ? TEXT.noActive : TEXT.versionNumber(active.revisionNumber)}
          tone={active !== null ? 'success' : status === 'CONCEPT' ? 'warning' : 'neutral'}
          testId="definition-card-active"
        />
        <MiniCard id={`${idPrefix}-versions`} title={TEXT.mini.versions} value={revisions.length} tone="neutral" testId="definition-card-versions" />
        {reusable && (
          <MiniCard
            id={`${idPrefix}-placeholders`}
            title={TEXT.mini.placeholders}
            value={placeholderCount}
            tone="neutral"
            testId="definition-card-placeholders"
          />
        )}
        <MiniCard
          id={`${idPrefix}-usedby`}
          title={reusable ? TEXT.mini.usedByReusable : TEXT.mini.usedByOwn}
          value={usedByCount}
          tone="neutral"
          testId="definition-card-usedby"
        />
        <MiniCard id={`${idPrefix}-links`} title={TEXT.mini.links} value={linkCount} tone="neutral" testId="definition-card-links" />
      </div>

      <section className={`${pageCss.card} ${pageCss.cardPad} ${styles.fieldGroup}`} aria-labelledby={`${idPrefix}-data`}>
        <h3 className={styles.overline} id={`${idPrefix}-data`}>
          {TEXT.groupTitle}
        </h3>
        <dl className={styles.dl} data-testid="definition-data">
          <KeyValue label={TEXT.keyValue.code}>{definition.code}</KeyValue>
          <KeyValue label={TEXT.keyValue.name}>{definition.name}</KeyValue>
          <KeyValue label={TEXT.keyValue.kind}>{reusable ? TEXT.kindReusableFull : TEXT.kindOwnFull}</KeyValue>
          <KeyValue label={reusable ? TEXT.keyValue.source : TEXT.keyValue.supplier}>
            {organisation === null ? null : organisation.name}
          </KeyValue>
          <KeyValue label={SUPPLIER_TEXT.keyValue.status}>
            <span title={STATUS_HINT[status]}>
              <ToneBadge label={STATUS_LABEL[status]} tone={STATUS_TONE[status]} />
            </span>
            {openDraftNumberOf(definition) !== null && (
              <>
                {' '}
                <ToneBadge label={TEXT.draftOpen(openDraftNumberOf(definition)!)} tone="awaiting" />
              </>
            )}
            {isInactiveOrganisation(definition) && (
              <>
                {' '}
                <span title={INACTIVE_HINT}>
                  <ToneBadge label={INACTIVE_LABEL} tone="neutral" />
                </span>
              </>
            )}
          </KeyValue>
          <KeyValue label={TEXT.keyValue.origin}>
            <span title={ORIGIN_HINT[origin]}>{ORIGIN_LABEL[origin]}</span>
          </KeyValue>
          <OverwriteFlagRow
            definitionId={id}
            enabled={definition.overwriteCreationDefaults === true}
            linkCount={typeof linkCount === 'number' ? linkCount : null}
            onChanged={reload}
          />
        </dl>
      </section>

      <section className={`${pageCss.card} ${pageCss.cardPad} ${styles.fieldGroup}`} aria-label={TEXT.fileCardTitle} data-testid="definition-file-card">
        {shown === null ? (
          <p className={styles.empty}>{TEXT.fileCardNone}</p>
        ) : (
          <>
            <FileCard definitionId={id} revisionId={shown.id} />
            <div className={styles.btnRow}>
              <Link
                className={`${button.secondary} ${button.small}`}
                to={definitionHref(id, 'fields', { revisionId: shown.id })}
                data-testid="view-fields"
              >
                {TEXT.viewFields}
              </Link>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
