/**
 * Tab "Koppelingen en taken" van het leveranciersdetail: één kaart per koppeling (code en naam, bibliotheek, actief,
 * gereedheid) met haar taken (naam, hoe ze start, klaar of niet, laatste inlezing) en de knoppen "Controleren"
 * (`/setup/links/:id/check`, recht Lezen) en "Taak toevoegen" (recht Beheren, stappenplan bij de taakstap). Een
 * koppeling zonder taak toont "Verder inrichten" (NT-6); een importdefinitie zonder koppeling en een leverancier zonder
 * importdefinitie idem. De gereedheid is dezelfde regel als op het dashboard: een taak is klaar als ze actief is en de
 * importdefinitie een actieve versie heeft.
 */

import { Link } from 'react-router-dom';
import type { TaskRow } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { formatDateTime } from '../../format.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { Term } from '../../terms/Term.tsx';
import { linkCheckHref } from './check/linkCheck.ts';
import { AddTaskAction, ContinueSetupAction } from './SetupActions.tsx';
import { isOwnDefinition, type DefinitionModel, type LinkModel } from './setupDashboardModel.ts';
import { useSupplierDetailContext } from './SupplierDetailPage.tsx';
import { wizardHref } from './wizard/wizardTypes.ts';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

function TaskLine({ task, definitionHasActiveVersion }: { task: TaskRow; definitionHasActiveVersion: boolean }) {
  const ready = task.active && definitionHasActiveVersion;
  return (
    <li data-testid={`task-${task.id}`}>
      <span className={styles.grow}>
        <strong>{task.name}</strong> · <Term domain="taskTrigger" code={task.triggerType} />
        <br />
        <span className={styles.rowMeta}>
          {task.lastRunFinishedAt === null ? TEXT.noRun : TEXT.lastRun(formatDateTime(task.lastRunFinishedAt))}
        </span>
      </span>
      {!task.active ? (
        <ToneBadge label={TEXT.taskOff} tone="neutral" />
      ) : ready ? (
        <ToneBadge label={TEXT.taskReady} tone="ready" />
      ) : (
        <span data-testid={`task-not-ready-${task.id}`}>
          <ToneBadge label={TEXT.taskNotReady} tone="awaiting" />
        </span>
      )}
    </li>
  );
}

function LinkCard({ entry, definition, organisationId }: { entry: LinkModel; definition: DefinitionModel['definition']; organisationId: number }) {
  const { link, tasks } = entry;
  const own = isOwnDefinition(definition);
  const hasActiveVersion = definition.activeRevisionId !== null;
  const ready = hasActiveVersion && tasks.some((task) => task.active);
  const href = wizardHref({ organisationId, definitionId: definition.id, linkId: link.id });

  return (
    <section className={`${pageCss.card} ${pageCss.cardPad}`} role="group" aria-labelledby={`link-${link.id}`} data-testid={`link-card-${link.id}`}>
      <div className={styles.linkCardTop}>
        <h3 id={`link-${link.id}`}>
          <span className={styles.code}>{link.code}</span> · {link.name}
        </h3>
        <ToneBadge label={link.active ? TEXT.linkActive : TEXT.linkInactive} tone={link.active ? 'ready' : 'neutral'} />
      </div>
      <p className={styles.rowMeta}>
        {TEXT.linkLibrary(link.libraryCode)} · {TEXT.linkSupplier(link.supplierCode)}
      </p>
      <p>
        <ToneBadge label={ready ? 'Klaar' : 'Nog niet klaar'} tone={ready ? 'ready' : 'awaiting'} />
      </p>
      <div data-testid={`link-tasks-${link.id}`}>
        <h4 className={`${styles.overline} ${styles.overlineTight}`}>
          {TEXT.tasksTitle}
        </h4>
        {tasks.length === 0 ? (
          <p className={styles.empty}>
            {TEXT.noTaskYet}{' '}
            {own && <ContinueSetupAction href={href} testId={`continue-link-${link.id}`} label={TEXT.continueSetup} />}
          </p>
        ) : (
          <ul className={styles.taskList}>
            {tasks.map((task) => (
              <TaskLine key={task.id} task={task} definitionHasActiveVersion={hasActiveVersion} />
            ))}
          </ul>
        )}
      </div>
      <div className={styles.linkActions}>
        <Link
          className={`${button.secondary} ${button.small}`}
          to={linkCheckHref({ linkId: link.id, definitionId: link.importDefinitionId })}
          title={TEXT.checkTitle}
          data-testid={`check-link-${link.id}`}
        >
          {TEXT.check}
        </Link>
        {own && <AddTaskAction href={href} testId={`add-task-${link.id}`} label={TEXT.addTask} />}
      </div>
    </section>
  );
}

export function SupplierLinksTab() {
  const { bundle } = useSupplierDetailContext();
  const { supplier } = bundle;
  const organisationId = supplier.organisation.id;

  if (supplier.ownDefinitions.length === 0 && supplier.linkCount === 0) {
    return (
      <p className={styles.empty} data-testid="supplier-links">
        {TEXT.noDefinition}{' '}
        <ContinueSetupAction
          href={wizardHref({ organisationId })}
          testId={`continue-organisation-${organisationId}`}
          label={TEXT.continueSetup}
        />
      </p>
    );
  }

  return (
    <div data-testid="supplier-links">
      {supplier.definitions.map(({ definition, links }) => (
        <section key={definition.id} aria-labelledby={`links-def-${definition.id}`} className={styles.spaced}>
          <h2 id={`links-def-${definition.id}`} className={styles.sectionTitle}>
            {definition.name}
          </h2>
          {links.length === 0 ? (
            <p className={styles.empty}>
              {TEXT.linksEmpty}{' '}
              {isOwnDefinition(definition) && (
                <ContinueSetupAction
                  href={wizardHref({ organisationId, definitionId: definition.id })}
                  testId={`continue-definition-${definition.id}`}
                  label={TEXT.continueSetup}
                />
              )}
            </p>
          ) : (
            <div className={styles.linkGrid}>
              {links.map((entry) => (
                <LinkCard key={entry.link.id} entry={entry} definition={definition} organisationId={organisationId} />
              ))}
            </div>
          )}
        </section>
      ))}
    </div>
  );
}
