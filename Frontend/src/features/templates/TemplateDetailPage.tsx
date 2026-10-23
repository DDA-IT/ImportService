/**
 * `/templates/:definitionId` — het importdefinitiedetail (R2, beslissingslog 2026-10-23 "Naamgeving en redesign
 * inrichting: goedgekeurd", mock `#template/<CODE>`): kop met naam, soort, status en herkomst, vier tabs als nested routes
 * (patroon `SupplierDetailPage`) en onderaan "Technische details (voor support)". Het detail neemt elk definitie-id aan,
 * eigen (`OWN_DEFINITION`) en herbruikbaar (`REUSABLE_TEMPLATE`); de route blijft `/templates/:definitionId`.
 *
 * Laadt de importdefinitie met haar versies en koppelingen één keer (alle vlagloze lijstendpoints) en geeft die via
 * `<Outlet context>` door aan de tabs. Voor een herbruikbare importdefinitie komt daar de vlaggedekte aanvulling bij
 * (wie ze gebruikt, haar invulpunten); een fout daarvan toont enkel een melding in de tab die het nodig heeft.
 *
 * Acties in de kop: "Leverancier toevoegen op basis van deze importdefinitie" (alleen herbruikbaar; opent het
 * stappenplan met `templateId`; uitgeschakeld bij een concept of zonder recht Beheren) en "Opvolger maken" (de
 * bestaande `CreateSuccessorAction`, op de actieve versie, anders de nieuwste). Het materialiseerformulier zelf staat
 * niet meer op deze pagina: het stappenplan gebruikt hetzelfde `MaterialiseForm`.
 */

import { Link, NavLink, Outlet, useNavigate, useOutletContext, useParams } from 'react-router-dom';
import { gateTitle } from '../../actor/gate.ts';
import { usePermissionGate } from '../../actor/permissions.ts';
import { PERMISSION_MANAGE, PERMISSION_READ, type DefinitionRow, type RevisionRow } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery, type UseQueryResult } from '../../hooks/useQuery.ts';
import {
  DEFINITIONS_TEXT,
  INACTIVE_HINT,
  INACTIVE_LABEL,
  LEGACY_KIND_DETAIL,
  ORIGIN_HINT,
  ORIGIN_LABEL,
  STATUS_HINT,
  STATUS_LABEL,
  STATUS_TONE,
} from '../../terms/importDefinitions.ts';
import { GROUP, PRODUCT, SCREEN } from '../../terms/navigation.ts';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { CreateSuccessorAction } from '../setup/CreateSuccessorAction.tsx';
import { defaultRevisionOf, isOwnDefinition } from '../setup/setupDashboardModel.ts';
import { wizardHref } from '../setup/wizard/wizardTypes.ts';
import { definitionHref } from './definitionRoutes.ts';
import {
  loadDefinitionBundle,
  loadReusableExtras,
  type DefinitionBundle,
  type ReusableExtras,
} from './definitionsData.ts';
import {
  definitionOriginOf,
  definitionStatusOf,
  isInactiveOrganisation,
  legacyKindOf,
  openDraftNumberOf,
  type DefinitionOrigin,
  type DefinitionStatus,
} from './definitionsModel.ts';
import styles from '../setup/SetupScreens.module.css';

const TEXT = DEFINITIONS_TEXT;

export type DefinitionDetailContext = {
  bundle: DefinitionBundle;
  reusable: boolean;
  status: DefinitionStatus;
  origin: DefinitionOrigin;
  /** De vlaggedekte aanvulling; `data` is `null` zolang ze laadt (en voor een eigen importdefinitie altijd leeg). */
  extras: UseQueryResult<ReusableExtras>;
  /** Herlaadt de importdefinitie met haar versies en koppelingen (na activeren, een opvolger, een wijziging). */
  reload: () => void;
};

/** Hulphook voor de tabs: haalt het door `TemplateDetailPage` doorgegeven pakket op. */
export function useDefinitionDetailContext(): DefinitionDetailContext {
  return useOutletContext<DefinitionDetailContext>();
}

/** De versie waar "Opvolger maken" van uitgaat: de actieve, anders de nieuwste. */
function successorBase(revisions: readonly RevisionRow[]): RevisionRow | null {
  const active = revisions.find((revision) => revision.status === 'ACTIVE');
  if (active !== undefined) {
    return active;
  }
  return [...revisions].sort((a, b) => b.revisionNumber - a.revisionNumber)[0] ?? null;
}

function AddSupplierAction({ definition, status }: { definition: DefinitionRow; status: DefinitionStatus }) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const unusable = status !== 'ACTIVE';
  if (unusable || !manageGate.allowed) {
    const reason = unusable ? TEXT.addSupplierConcept : gateTitle(manageGate);
    return (
      <button type="button" className={button.primary} disabled title={reason} data-testid="add-supplier-button">
        {TEXT.addSupplier}
      </button>
    );
  }
  return (
    <Link
      className={button.primary}
      to={wizardHref({ organisationId: definition.sourceOrganisationId, templateId: definition.id })}
      data-testid="add-supplier-button"
    >
      {TEXT.addSupplier}
    </Link>
  );
}

export function TemplateDetailPage() {
  const readGate = usePermissionGate(PERMISSION_READ);
  const params = useParams<{ definitionId: string }>();
  const definitionId = Number(params.definitionId);
  const valid = Number.isSafeInteger(definitionId) && definitionId > 0;

  // Een pakket van `null` = de importdefinitie bestaat niet; `data === null` = nog aan het laden.
  const query = useQuery<{ bundle: DefinitionBundle | null }>(`definition-detail:${definitionId}`, async (signal) => ({
    bundle: valid ? await loadDefinitionBundle(definitionId, signal) : null,
  }));
  const bundle = query.data?.bundle ?? null;
  const reusable = bundle !== null && !isOwnDefinition(bundle.definition);
  const bookmarkRevisionId = reusable ? (defaultRevisionOf(bundle.revisions, null)?.id ?? null) : null;
  const extrasKey = reusable ? `definition-extras:${definitionId}:${bookmarkRevisionId ?? 'none'}` : 'definition-extras:none';
  const extras = useQuery<ReusableExtras>(extrasKey, async (signal) =>
    reusable
      ? loadReusableExtras(definitionId, bookmarkRevisionId, signal)
      : { materialisations: { ok: true, data: [] }, bookmarks: null },
  );

  if (!readGate.allowed) {
    return (
      <div className={styles.page}>
        <p className={styles.alert} role="alert">
          {readGate.reason}
        </p>
      </div>
    );
  }

  const missing = query.data !== null && bundle === null;

  return (
    <div className={styles.page}>
      {query.error !== null && <ErrorBanner error={query.error} />}
      {query.loading && query.data === null && <p className={styles.loading}>{TEXT.loading}</p>}
      {missing && (
        <p className={styles.alert} role="alert">
          {TEXT.notFound} <Link to="/templates">{SCREEN.importdefinities}</Link>
        </p>
      )}
      {bundle !== null && (
        <Detail
          bundle={bundle}
          extras={extras}
          reload={() => {
            query.reload();
            extras.reload();
          }}
        />
      )}
    </div>
  );
}

function Detail({
  bundle,
  extras,
  reload,
}: {
  bundle: DefinitionBundle;
  extras: UseQueryResult<ReusableExtras>;
  reload: () => void;
}) {
  const navigate = useNavigate();
  const { definition, revisions } = bundle;
  const id = definition.id;
  const reusable = !isOwnDefinition(definition);
  const status = definitionStatusOf(definition);
  const origin = definitionOriginOf(definition);
  const inactive = isInactiveOrganisation(definition);
  const draftOpen = openDraftNumberOf(definition);
  const legacyKind = legacyKindOf(definition);
  const legacyCodes = definition.legacyDefinitionCodes ?? [];
  const base = successorBase(revisions);
  const newestDraft = [...revisions]
    .filter((revision) => revision.status === 'DRAFT')
    .sort((a, b) => b.revisionNumber - a.revisionNumber)[0];

  const tabs: { to: string; end: boolean; label: string }[] = [
    { to: definitionHref(id), end: true, label: TEXT.tabs.overview },
    { to: `/templates/${id}/fields`, end: false, label: TEXT.tabs.fields },
    { to: `/templates/${id}/versions`, end: false, label: TEXT.tabs.versions },
    { to: `/templates/${id}/used-by`, end: false, label: TEXT.tabs.usedBy },
  ];

  const revisionIds = revisions.map((revision) => revision.id).join(', ');
  const materialised = extras.data?.materialisations.ok === true ? extras.data.materialisations.data : [];
  const materialisedIds = materialised.map((row) => row.definitionId).join(', ');

  return (
    <>
      <header className={pageCss.pageHead}>
        <div className={pageCss.pageHeadText}>
          <h1 className={pageCss.title}>
            {definition.name}
            <span className={styles.titleBadges}>
              <ToneBadge label={reusable ? TEXT.kindReusable : TEXT.kindOwn} tone={reusable ? 'planned' : 'neutral'} />
              <span title={STATUS_HINT[status]}>
                <ToneBadge label={STATUS_LABEL[status]} tone={STATUS_TONE[status]} />
              </span>
              {draftOpen !== null && <ToneBadge label={TEXT.draftOpen(draftOpen)} tone="awaiting" />}
              {inactive && (
                <span title={INACTIVE_HINT}>
                  <ToneBadge label={INACTIVE_LABEL} tone="neutral" />
                </span>
              )}
              <span title={ORIGIN_HINT[origin]}>
                <ToneBadge label={TEXT.originPrefix(ORIGIN_LABEL[origin])} tone="neutral" />
              </span>
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
              <Link to="/templates">{SCREEN.importdefinities}</Link>
            </li>
            <li>
              <span aria-current="page">{definition.name}</span>
            </li>
          </ol>
        </div>
        <div className={styles.btnRow}>
          {base === null ? (
            <button type="button" className={button.secondary} disabled title={TEXT.createSuccessorNone} data-testid="header-successor-none">
              Opvolger maken
            </button>
          ) : (
            <CreateSuccessorAction
              revision={base}
              onCreated={(created) => {
                // De nieuwe versie wordt meteen geopend (bewerkblok open); het pakket herlaadt mee.
                reload();
                navigate(definitionHref(id, 'fields', { revisionId: created.id, edit: true }));
              }}
            />
          )}
          {reusable && <AddSupplierAction definition={definition} status={status} />}
        </div>
      </header>

      {status !== 'ACTIVE' && (
        <div className={styles.banner} role="status" data-testid="concept-banner">
          <p>
            <strong>{TEXT.nextStepLabel}</strong>{' '}
            {newestDraft === undefined ? TEXT.nextStepNoVersion : TEXT.nextStepDraft(newestDraft.revisionNumber)}
          </p>
          {newestDraft !== undefined && (
            <Link
              className={`${button.secondary} ${button.small}`}
              to={definitionHref(id, 'fields', { revisionId: newestDraft.id })}
              data-testid="concept-banner-action"
            >
              {TEXT.viewVersion(newestDraft.revisionNumber)}
            </Link>
          )}
        </div>
      )}

      <nav className={pageCss.tabs} aria-label={TEXT.tabsLabel}>
        {tabs.map((tab) => (
          <NavLink key={tab.to} to={tab.to} end={tab.end} className={pageCss.tab}>
            {tab.label}
          </NavLink>
        ))}
      </nav>

      <Outlet
        context={
          {
            bundle,
            reusable,
            status,
            origin,
            extras,
            reload,
          } satisfies DefinitionDetailContext
        }
      />

      <div className={styles.techBox}>
        <TechnicalDetails
          items={[
            { name: TEXT.technicalIds.definition, value: String(id) },
            { name: TEXT.technicalIds.usageType, value: definition.usageType },
            { name: TEXT.technicalIds.versions, value: revisionIds === '' ? '—' : revisionIds },
            ...(legacyKind === null ? [] : [{ name: TEXT.technicalIds.legacyOrigin, value: `${legacyKind}: ${LEGACY_KIND_DETAIL[legacyKind]}` }]),
            ...(legacyCodes.length === 0 ? [] : [{ name: TEXT.technicalIds.legacyCodes, value: legacyCodes.join(', ') }]),
            ...(reusable ? [{ name: TEXT.technicalIds.materialised, value: materialisedIds === '' ? '—' : materialisedIds }] : []),
          ]}
        />
      </div>
    </>
  );
}
