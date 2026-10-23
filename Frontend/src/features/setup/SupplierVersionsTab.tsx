/**
 * Tab "Versies" van het leveranciersdetail: de historiek van de versies per importdefinitie, elk met zijn status en de
 * acties die vandaag bestaan: "Bekijken" (opent Bestand en velden met die versie), "Opvolger maken"
 * (`CreateSuccessorAction`, E2) en bij een concept "Bewerken en activeren" (opent Bestand en velden met het bewerkblok
 * open). De lijst toont enkel wat `GET /definitions/{id}/revisions` levert: versienummer en status; wie en wanneer
 * staan niet in die lijst.
 */

import { Link, useNavigate } from 'react-router-dom';
import { StatusBadge, ToneBadge } from '../../components/StatusBadge.tsx';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { DASHBOARD_TEXT, SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { CreateSuccessorAction } from './CreateSuccessorAction.tsx';
import { activeRevisionOf, isOwnDefinition } from './setupDashboardModel.ts';
import { useSupplierDetailContext } from './SupplierDetailPage.tsx';
import { supplierHref } from './supplierRoutes.ts';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

export function SupplierVersionsTab() {
  const { bundle, reload } = useSupplierDetailContext();
  const navigate = useNavigate();
  const organisationId = bundle.supplier.organisation.id;
  const definitions = bundle.supplier.definitions;

  if (definitions.length === 0) {
    return <p className={styles.empty}>{TEXT.noDefinition}</p>;
  }

  return (
    <div data-testid="supplier-versions">
      {definitions.map(({ definition }) => {
        const revisions = [...(bundle.revisions[definition.id] ?? [])].sort((a, b) => b.revisionNumber - a.revisionNumber);
        const active = activeRevisionOf(revisions);
        const own = isOwnDefinition(definition);
        return (
          <section
            key={definition.id}
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
              </div>
              <ToneBadge label={own ? DASHBOARD_TEXT.definitionKindOwn : DASHBOARD_TEXT.definitionKindReusable} tone="neutral" />
            </div>
            <div className={styles.cardBody}>
              {revisions.length === 0 ? (
                <p className={`${styles.empty} ${styles.cardBodyEmpty}`}>
                  {TEXT.versionsEmpty}
                </p>
              ) : (
                revisions.map((revision) => (
                  <div key={revision.id} className={styles.versionRow} data-testid={`version-row-${revision.id}`}>
                    <span className={styles.versionName}>{TEXT.versionNumber(revision.revisionNumber)}</span>
                    <StatusBadge status={revision.status} domain="revisionStatus" />
                    <span className={styles.btnRow}>
                      <Link
                        className={`${button.secondary} ${button.small}`}
                        to={supplierHref(organisationId, 'fields', { definitionId: definition.id, revisionId: revision.id })}
                        aria-label={TEXT.viewLabel(revision.revisionNumber)}
                        data-testid={`open-revision-${revision.id}`}
                      >
                        {TEXT.view}
                      </Link>
                      <CreateSuccessorAction
                        revision={revision}
                        onCreated={(created) => {
                          // De nieuwe versie wordt meteen geopend (bewerkblok open); de lijst herlaadt mee.
                          reload();
                          navigate(
                            supplierHref(organisationId, 'fields', {
                              definitionId: definition.id,
                              revisionId: created.id,
                              edit: true,
                            }),
                          );
                        }}
                      />
                      {revision.status === 'DRAFT' && (
                        <Link
                          className={`${button.secondary} ${button.small}`}
                          to={supplierHref(organisationId, 'fields', {
                            definitionId: definition.id,
                            revisionId: revision.id,
                            edit: true,
                          })}
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
      })}
    </div>
  );
}
