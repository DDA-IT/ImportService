/**
 * De historiek van de versies van één importdefinitie als kaart: elke versie met zijn status en de acties die vandaag
 * bestaan: "Bekijken", "Opvolger maken" (`CreateSuccessorAction`, E2) en bij een concept "Bewerken en activeren".
 * Gedeeld door het tabblad Versies van het leveranciersdetail en dat van het importdefinitiedetail (R2): de aanroeper
 * bepaalt waar "Bekijken" en "Bewerken" naartoe leiden. Per versie ook wanneer en door wie ze gemaakt (en bij een actieve of
 * vervangen versie: goedgekeurd) is, de reden van de wijziging, een markering bij een overname uit legacy en op welke versie
 * ze gebaseerd is (beslissingslog 2026-10-23 "API-uitbreiding voor de inrichtingsschermen", punt 2).
 */

import { Link } from 'react-router-dom';
import { formatDateTime } from '../../format.ts';
import type { DefinitionRow, RevisionRow, RevisionView } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { StatusBadge, ToneBadge } from '../../components/StatusBadge.tsx';
import { DASHBOARD_TEXT, SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import type { ReactNode } from 'react';
import { CreateSuccessorAction } from './CreateSuccessorAction.tsx';
import { activeRevisionOf, isOwnDefinition } from './setupDashboardModel.ts';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

export type VersionHistoryCardProps = {
  definition: DefinitionRow;
  revisions: readonly RevisionRow[];
  viewHref: (revision: RevisionRow) => string;
  editHref: (revision: RevisionRow) => string;
  /** Na een geslaagde opvolger: de ouder herlaadt en opent de nieuwe versie. */
  onSuccessorCreated: (created: RevisionView) => void;
  /** Extra regel onder de ondertitel (bv. "Gebaseerd op herbruikbare importdefinitie …"). */
  note?: ReactNode;
};

/** Wanneer en door wie, reden, herkomst en basisversie van één versie; enkel wat de API ingevuld heeft. */
function RevisionMeta({ revision, numberById }: { revision: RevisionRow; numberById: ReadonlyMap<number, number> }) {
  const created = [
    revision.createdAt === undefined || revision.createdAt === null ? null : formatDateTime(revision.createdAt),
    revision.createdBy ?? null,
  ].filter((part): part is string => part !== null);
  const approved =
    (revision.status === 'ACTIVE' || revision.status === 'SUPERSEDED') && revision.approvedAt != null
      ? TEXT.versionApproved(formatDateTime(revision.approvedAt), revision.approvedBy ?? null)
      : null;
  const basedOnNumber = revision.basedOnRevisionId == null ? undefined : numberById.get(revision.basedOnRevisionId);
  const reason = revision.changeReason == null || revision.changeReason.trim() === '' ? null : revision.changeReason;
  const parts = [
    created.length > 0 ? created.join(' · ') : null,
    approved,
    basedOnNumber === undefined ? null : TEXT.versionBasedOn(basedOnNumber),
  ].filter((part): part is string => part !== null);
  if (parts.length === 0 && reason === null && revision.fromLegacy !== true) {
    return null;
  }
  return (
    <span className={`${styles.rowMeta} ${styles.versionMeta}`} data-testid={`version-meta-${revision.id}`}>
      {parts.join(' · ')}
      {revision.fromLegacy === true && (
        <>
          {parts.length > 0 && ' · '}
          <ToneBadge label={TEXT.versionFromLegacy} tone="neutral" />
        </>
      )}
      {reason !== null && (
        <>
          <br />
          <span data-testid={`version-reason-${revision.id}`}>{reason}</span>
        </>
      )}
    </span>
  );
}

export function VersionHistoryCard({ definition, revisions, viewHref, editHref, onSuccessorCreated, note }: VersionHistoryCardProps) {
  const sorted = [...revisions].sort((a, b) => b.revisionNumber - a.revisionNumber);
  const numberById = new Map(revisions.map((revision) => [revision.id, revision.revisionNumber]));
  const active = activeRevisionOf(sorted);
  const own = isOwnDefinition(definition);
  return (
    <section
      className={`${pageCss.card} ${styles.cardClip} ${styles.spaced}`}
      aria-labelledby={`versions-${definition.id}`}
    >
      <div className={pageCss.cardHead}>
        <div>
          <h2 id={`versions-${definition.id}`} className={pageCss.subtitle}>
            {definition.name}
          </h2>
          <p className={styles.rowMeta}>
            {own ? DASHBOARD_TEXT.definitionKindOwn : DASHBOARD_TEXT.definitionKindReusable} ·{' '}
            {active === null ? DASHBOARD_TEXT.noActiveVersion : `actieve ${TEXT.versionNumber(active.revisionNumber).toLowerCase()}`}
          </p>
          {note}
        </div>
        <ToneBadge label={own ? DASHBOARD_TEXT.definitionKindOwn : DASHBOARD_TEXT.definitionKindReusable} tone="neutral" />
      </div>
      <div className={styles.cardBody}>
        {sorted.length === 0 ? (
          <p className={`${styles.empty} ${styles.cardBodyEmpty}`}>{TEXT.versionsEmpty}</p>
        ) : (
          sorted.map((revision) => (
            <div key={revision.id} className={styles.versionRow} data-testid={`version-row-${revision.id}`}>
              <span className={styles.versionName}>{TEXT.versionNumber(revision.revisionNumber)}</span>
              <StatusBadge status={revision.status} domain="revisionStatus" />
              <RevisionMeta revision={revision} numberById={numberById} />
              <span className={styles.btnRow}>
                <Link
                  className={`${button.secondary} ${button.small}`}
                  to={viewHref(revision)}
                  aria-label={TEXT.viewLabel(revision.revisionNumber)}
                  data-testid={`open-revision-${revision.id}`}
                >
                  {TEXT.view}
                </Link>
                <CreateSuccessorAction revision={revision} onCreated={onSuccessorCreated} />
                {revision.status === 'DRAFT' && (
                  <Link
                    className={`${button.secondary} ${button.small}`}
                    to={editHref(revision)}
                    data-testid={`edit-revision-${revision.id}`}
                  >
                    {TEXT.editAndActivate}
                  </Link>
                )}
              </span>
            </div>
          ))
        )}
      </div>
    </section>
  );
}
