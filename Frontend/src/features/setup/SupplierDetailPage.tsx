/**
 * `/setup/suppliers/:organisationId` — het leveranciersdetail (R1, beslissingslog 2026-10-23): kop met titel en
 * kruimelpad, vier tabs als nested routes (patroon `BundleDetailPage`: NavLink-tabs met `aria-current`) en onderaan
 * "Technische details (voor support)". Laadt de leverancier met haar keten en versies één keer en geeft die via
 * `<Outlet context>` door aan de tabs; elke schrijfactie herlaadt dat pakket via `reload`.
 */

import { Link, NavLink, Outlet, useOutletContext, useParams } from 'react-router-dom';
import { usePermissionGate } from '../../actor/permissions.ts';
import { PERMISSION_READ } from '../../api/types.ts';
import pageCss from '../../components/Page.module.css';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { termLabel } from '../../terms/index.ts';
import { GROUP, PRODUCT, SCREEN } from '../../terms/navigation.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { ContinueSetupAction } from './SetupActions.tsx';
import { loadSupplier, type SupplierBundle } from './setupDashboardData.ts';
import { nextStep } from './setupDashboardModel.ts';
import { supplierHref } from './supplierRoutes.ts';
import { wizardHref } from './wizard/wizardTypes.ts';
import styles from './SetupScreens.module.css';

export type SupplierDetailContext = {
  bundle: SupplierBundle;
  /** Herlaadt de leverancier met haar keten en versies (na activeren, een opvolger, een wijziging). */
  reload: () => void;
};

/** Hulphook voor de tabs: haalt het door `SupplierDetailPage` doorgegeven pakket op. */
export function useSupplierDetailContext(): SupplierDetailContext {
  return useOutletContext<SupplierDetailContext>();
}

const TEXT = SUPPLIER_TEXT;

export function SupplierDetailPage() {
  const readGate = usePermissionGate(PERMISSION_READ);
  const params = useParams<{ organisationId: string }>();
  const organisationId = Number(params.organisationId);
  const valid = Number.isSafeInteger(organisationId) && organisationId > 0;

  // Een bundel van `null` = de organisatie bestaat niet; `data === null` = nog aan het laden.
  const query = useQuery<{ bundle: SupplierBundle | null }>(`setup-supplier:${organisationId}`, async (signal) => ({
    bundle: valid ? await loadSupplier(organisationId, signal) : null,
  }));

  if (!readGate.allowed) {
    return (
      <div className={styles.page}>
        <p className={styles.alert} role="alert">
          {readGate.reason}
        </p>
      </div>
    );
  }

  const bundle = query.data?.bundle ?? null;
  const missing = query.data !== null && bundle === null;

  return (
    <div className={styles.page}>
      {query.error !== null && <ErrorBanner error={query.error} />}
      {query.loading && query.data === null && <p className={styles.loading}>{TEXT.loading}</p>}
      {missing && (
        <p className={styles.alert} role="alert">
          {TEXT.notFound} <Link to="/setup">{SCREEN.leveranciers}</Link>
        </p>
      )}
      {bundle !== null && <Detail bundle={bundle} reload={query.reload} />}
    </div>
  );
}

function Detail({ bundle, reload }: { bundle: SupplierBundle; reload: () => void }) {
  const { supplier } = bundle;
  const organisation = supplier.organisation;
  const id = organisation.id;
  const step = nextStep(supplier, bundle.revisions);

  const continueHref =
    step.kind === 'createDefinition'
      ? wizardHref({ organisationId: id })
      : step.kind === 'createLink'
        ? wizardHref({ organisationId: id, definitionId: step.definitionId })
        : step.kind === 'createTask'
          ? wizardHref({ organisationId: id, definitionId: step.definitionId, linkId: step.linkId })
          : null;

  const firstLink = supplier.ownDefinitions.flatMap((entry) => entry.links)[0];
  const addTaskHref =
    firstLink === undefined
      ? null
      : wizardHref({ organisationId: id, definitionId: firstLink.link.importDefinitionId, linkId: firstLink.link.id });

  const tabs: { to: string; end: boolean; label: string }[] = [
    { to: supplierHref(id), end: true, label: TEXT.tabs.overview },
    { to: `/setup/suppliers/${id}/fields`, end: false, label: TEXT.tabs.fields },
    { to: `/setup/suppliers/${id}/versions`, end: false, label: TEXT.tabs.versions },
    { to: `/setup/suppliers/${id}/links`, end: false, label: TEXT.tabs.links },
  ];

  const definitionIds = supplier.definitions.map((entry) => entry.definition.id).join(', ');
  const revisionIds = supplier.definitions
    .flatMap((entry) => (bundle.revisions[entry.definition.id] ?? []).map((revision) => revision.id))
    .join(', ');
  const linkIds = supplier.definitions.flatMap((entry) => entry.links.map((link) => link.link.id)).join(', ');
  const taskIds = supplier.definitions
    .flatMap((entry) => entry.links.flatMap((link) => link.tasks.map((task) => task.id)))
    .join(', ');

  return (
    <>
      <header className={pageCss.pageHead}>
        <div className={pageCss.pageHeadText}>
          <h1 className={pageCss.title}>
            {organisation.name}
            <span className={styles.titleBadges}>
              <ToneBadge label={termLabel('organisationType', organisation.type, 'Andere soort')} tone="neutral" />
              <ToneBadge
                label={organisation.active ? TEXT.statusActive : TEXT.statusInactive}
                tone={organisation.active ? 'ready' : 'neutral'}
              />
            </span>
          </h1>
          <ol className={pageCss.crumbs}>
            <li>
              <span>{PRODUCT}</span>
            </li>
            <li>
              <span>{GROUP.inrichting}</span>
            </li>
            <li>
              <Link to="/setup">{SCREEN.leveranciers}</Link>
            </li>
            <li>
              <span aria-current="page">{organisation.name}</span>
            </li>
          </ol>
        </div>
        <div className={styles.btnRow}>
          {continueHref !== null && (
            <ContinueSetupAction href={continueHref} testId="header-continue" label={TEXT.continueSetup} />
          )}
          {addTaskHref !== null && <ContinueSetupAction href={addTaskHref} testId="header-add-task" label={TEXT.addTask} />}
        </div>
      </header>

      <nav className={pageCss.tabs} aria-label={TEXT.tabsLabel}>
        {tabs.map((tab) => (
          <NavLink key={tab.to} to={tab.to} end={tab.end} className={pageCss.tab}>
            {tab.label}
          </NavLink>
        ))}
      </nav>

      <Outlet context={{ bundle, reload } satisfies SupplierDetailContext} />

      <div className={styles.techBox}>
        <TechnicalDetails
          items={[
            { name: TEXT.technicalIds.organisation, value: String(id) },
            { name: TEXT.technicalIds.definitions, value: definitionIds === '' ? '—' : definitionIds },
            { name: TEXT.technicalIds.versions, value: revisionIds === '' ? '—' : revisionIds },
            { name: TEXT.technicalIds.links, value: linkIds === '' ? '—' : linkIds },
            { name: TEXT.technicalIds.tasks, value: taskIds === '' ? '—' : taskIds },
          ]}
        />
      </div>
    </>
  );
}
