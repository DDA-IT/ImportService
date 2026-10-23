/**
 * Tab "Overzicht" van het leveranciersdetail: de stappenbalk (waar zit deze leverancier in de keten), kleine kaarten,
 * de ene "Volgende stap" en de gegevens in het Prodis-`dl`-patroon (leeg = cursief "Niet ingesteld"). Toont enkel wat
 * de API levert: bibliotheek, tekenset, wachtwoord en opmerking uit de mock staan niet op de organisatie.
 */

import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { MiniCard } from '../../components/SummaryCards.tsx';
import { READINESS_HINT, READINESS_LABEL, READINESS_TONE, SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { Term } from '../../terms/Term.tsx';
import { ContinueSetupAction } from './SetupActions.tsx';
import { activeRevisionOf, nextStep, type NextStep } from './setupDashboardModel.ts';
import { useSupplierDetailContext } from './SupplierDetailPage.tsx';
import { supplierHref } from './supplierRoutes.ts';
import { wizardHref } from './wizard/wizardTypes.ts';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

function StepBar({ current }: { current: number }) {
  return (
    <nav aria-label={TEXT.stepsLabel}>
      <ol className={styles.chain}>
        {TEXT.steps.map((label, position) => (
          <li key={label} aria-current={position === current ? 'step' : undefined}>
            {label}
          </li>
        ))}
      </ol>
    </nav>
  );
}

function KeyValue({ label, children }: { label: string; children: ReactNode }) {
  const empty = children === null || children === undefined || children === '';
  return (
    <>
      <dt>{label}</dt>
      <dd>{empty ? <span className={styles.unset}>{TEXT.unset}</span> : children}</dd>
    </>
  );
}

function NextStepBanner({ organisationId, step }: { organisationId: number; step: NextStep }) {
  const text = TEXT.next;
  let sentence: string;
  let action: ReactNode = null;
  switch (step.kind) {
    case 'inactive':
      sentence = text.inactive;
      break;
    case 'createDefinition':
      sentence = text.createDefinition;
      action = (
        <ContinueSetupAction
          href={wizardHref({ organisationId })}
          testId="next-step-action"
          label={TEXT.continueSetup}
        />
      );
      break;
    case 'activateDraft':
      sentence = text.activateDraft(step.revisionNumber);
      action = (
        <Link
          className={`${button.secondary} ${button.small}`}
          to={supplierHref(organisationId, 'fields', { definitionId: step.definitionId, revisionId: step.revisionId })}
          data-testid="next-step-action"
        >
          {text.viewVersion(step.revisionNumber)}
        </Link>
      );
      break;
    case 'noActiveVersion':
      sentence = text.activate;
      action = (
        <Link
          className={`${button.secondary} ${button.small}`}
          to={supplierHref(organisationId, 'versions', { definitionId: step.definitionId })}
          data-testid="next-step-action"
        >
          {TEXT.tabs.versions}
        </Link>
      );
      break;
    case 'createLink':
      sentence = text.createLink;
      action = (
        <ContinueSetupAction
          href={wizardHref({ organisationId, definitionId: step.definitionId })}
          testId="next-step-action"
          label={TEXT.continueSetup}
        />
      );
      break;
    case 'createTask':
      sentence = text.createTask;
      action = (
        <ContinueSetupAction
          href={wizardHref({ organisationId, definitionId: step.definitionId, linkId: step.linkId })}
          testId="next-step-action"
          label={TEXT.addTask}
        />
      );
      break;
    case 'reactivateTask':
      sentence = text.reactivateTasks;
      action = (
        <Link className={`${button.secondary} ${button.small}`} to={supplierHref(organisationId, 'links')} data-testid="next-step-action">
          {text.toLinks}
        </Link>
      );
      break;
    case 'ready':
      sentence = text.ready;
      action = (
        <Link className={`${button.secondary} ${button.small}`} to="/upload" data-testid="next-step-action">
          {text.readyAction}
        </Link>
      );
      break;
  }
  return (
    <div className={styles.banner} role="status" data-testid="next-step">
      <p>
        <strong>{TEXT.nextStepLabel}</strong> {sentence}
      </p>
      {action}
    </div>
  );
}

export function SupplierOverviewTab() {
  const { bundle } = useSupplierDetailContext();
  const { supplier } = bundle;
  const organisation = supplier.organisation;
  const step = nextStep(supplier, bundle.revisions);

  const own = supplier.ownDefinitions;
  let activeVersionValue: string;
  if (own.length === 1) {
    const active = activeRevisionOf(bundle.revisions[own[0]!.definition.id] ?? []);
    activeVersionValue = active === null ? TEXT.noActiveVersionValue : TEXT.versionNumber(active.revisionNumber);
  } else {
    const withActive = own.filter((entry) => entry.definition.activeRevisionId !== null).length;
    activeVersionValue = `${withActive}/${own.length}`;
  }
  const idPrefix = `supplier-${organisation.id}`;

  return (
    <div data-testid="supplier-overview">
      <p className={styles.rowMeta}>{TEXT.stepsIntro}</p>
      <StepBar current={step.step} />
      <div className={styles.mini} role="group" aria-label={TEXT.miniLabel}>
        <MiniCard id={`${idPrefix}-definitions`} title={TEXT.cards.definitions} value={own.length} tone="neutral" testId="supplier-card-definitions" />
        <MiniCard
          id={`${idPrefix}-active`}
          title={TEXT.cards.activeVersion}
          value={activeVersionValue}
          tone={own.length > 0 && own.every((entry) => entry.definition.activeRevisionId !== null) ? 'success' : 'neutral'}
          testId="supplier-card-active"
        />
        <MiniCard id={`${idPrefix}-links`} title={TEXT.cards.links} value={supplier.linkCount} tone="neutral" testId="supplier-card-links" />
        <MiniCard id={`${idPrefix}-tasks`} title={TEXT.cards.tasks} value={supplier.taskCount} tone="neutral" testId="supplier-card-tasks" />
        <MiniCard
          id={`${idPrefix}-notready`}
          title={TEXT.cards.notReady}
          value={supplier.reasons.length}
          tone={supplier.reasons.length > 0 ? 'warning' : 'neutral'}
          testId="supplier-card-notready"
        />
      </div>

      <NextStepBanner organisationId={organisation.id} step={step} />

      <section className={`${pageCss.card} ${pageCss.cardPad} ${styles.fieldGroup}`} aria-labelledby={`${idPrefix}-data`}>
        <h3 className={styles.overline} id={`${idPrefix}-data`}>
          {TEXT.groupTitle}
        </h3>
        <dl className={styles.dl} data-testid="supplier-data">
          <KeyValue label={TEXT.keyValue.code}>{organisation.code}</KeyValue>
          <KeyValue label={TEXT.keyValue.kind}>
            <Term domain="organisationType" code={organisation.type} unknownLabel="Andere soort" />
          </KeyValue>
          <KeyValue label={TEXT.keyValue.status}>
            <ToneBadge
              label={organisation.active ? TEXT.statusActive : TEXT.statusInactive}
              tone={organisation.active ? 'ready' : 'neutral'}
            />
          </KeyValue>
          <KeyValue label="Gereedheid">
            <span title={READINESS_HINT[supplier.readiness]}>
              <ToneBadge label={READINESS_LABEL[supplier.readiness]} tone={READINESS_TONE[supplier.readiness]} />
            </span>
          </KeyValue>
        </dl>
      </section>
    </div>
  );
}
