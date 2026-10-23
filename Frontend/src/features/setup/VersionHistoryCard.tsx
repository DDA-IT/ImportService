/**
 * De historiek van de versies van één importdefinitie als kaart: elke versie met zijn status en de acties die vandaag
 * bestaan: "Bekijken", "Opvolger maken" (`CreateSuccessorAction`, E2) en bij een concept "Bewerken en activeren".
 * Gedeeld door het tabblad Versies van het leveranciersdetail en dat van het importdefinitiedetail (R2): de aanroeper
 * bepaalt waar "Bekijken" en "Bewerken" naartoe leiden. De lijst toont enkel wat `GET /definitions/{id}/revisions`
 * levert: versienummer en status; wie en wanneer staan niet in die lijst.
 */

import { Link } from 'react-router-dom';
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

export function VersionHistoryCard({ definition, revisions, viewHref, editHref, onSuccessorCreated, note }: VersionHistoryCardProps) {
  const sorted = [...revisions].sort((a, b) => b.revisionNumber - a.revisionNumber);
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
