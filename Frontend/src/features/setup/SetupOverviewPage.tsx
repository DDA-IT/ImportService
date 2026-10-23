/**
 * `/setup` — het dashboard "Leveranciers" (R1, beslissingslog 2026-10-23 "Redesign inrichtingsoverzicht /setup" en
 * "Naamgeving en redesign inrichting: goedgekeurd", mock `docs/mock/setup-redesign-mock.html`). Vervangt de geneste
 * boom van scherm 1a: een kaartenrooster met één detailpaneel en daaronder één platte leverancierslijst. Het
 * leveranciersdetail (`/setup/suppliers/:organisationId`) draagt wat vroeger in de boom stond.
 *
 * Gegevens: vier bulkaanroepen met doorbladeren (organisaties, importdefinities, koppelingen, taken) die client-side
 * gegroepeerd worden in `setupDashboardModel.ts`; geen `/setup/overview`. Elke kaart telt enkel wat de API levert.
 *
 * "Overname uit legacy" (alleen met het recht Beheren; die endpoints vragen het) telt de ontvangsten met status
 * `RECEIVED`. De oude adresvorm `?definitionId=…&revisionId=…` blijft werken als doorverwijzing naar het
 * leveranciersdetail.
 */

import { useMemo, useState, type ReactNode } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import { usePermissionGate } from '../../actor/permissions.ts';
import * as legacyConfigApi from '../../api/legacyConfig.ts';
import { PERMISSION_MANAGE, PERMISSION_READ } from '../../api/types.ts';
import type { DefinitionRow, LegacyReceiptRow, TaskRow } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { Pager } from '../../components/Pager.tsx';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { DetailPanel, SummaryCards, type CardModel } from '../../components/SummaryCards.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { formatDateTime } from '../../format.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { GROUP, PRODUCT, SCREEN } from '../../terms/navigation.ts';
import { DASHBOARD_TEXT, READINESS_LABEL, READINESS_TONE, REASON_TEXT } from '../../terms/setupDashboard.ts';
import { term } from '../../terms/index.ts';
import { Term } from '../../terms/Term.tsx';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { linkCheckHref } from './check/linkCheck.ts';
import { ContinueSetupAction, NewSupplierButton } from './SetupActions.tsx';
import { loadDashboard } from './setupDashboardData.ts';
import {
  isOwnDefinition,
  type DashboardModel,
  type NotReadyReason,
  type SupplierModel,
} from './setupDashboardModel.ts';
import { parseId, supplierHref } from './supplierRoutes.ts';
import { wizardHref } from './wizard/wizardTypes.ts';
import styles from './SetupScreens.module.css';

const BASE_ID = 'setup-dashboard';
const PANEL_ROWS = 5;
const REASON_ROWS = 25;
const DEFINITION_ROWS = 6;

type SupplierIndex = Map<number, SupplierModel>;

function indexSuppliers(model: DashboardModel): SupplierIndex {
  return new Map(model.suppliers.map((supplier) => [supplier.organisation.id, supplier]));
}

function Crumbs() {
  return (
    <ol className={pageCss.crumbs}>
      <li>
        <span>{PRODUCT}</span>
      </li>
      <li>
        <span>{GROUP.inrichting}</span>
      </li>
      <li>
        <span aria-current="page">{SCREEN.leveranciers}</span>
      </li>
    </ol>
  );
}

/** Eén rij van het paneel: code · naam · uitleg links, de acties rechts. */
function PanelRow({
  code,
  name,
  meta,
  testId,
  children,
}: {
  code?: string;
  name: string;
  meta: string;
  testId?: string;
  children?: ReactNode;
}) {
  return (
    <li data-testid={testId}>
      <div className={styles.rowMain}>
        {code !== undefined && <span className={styles.code}>{code}</span>}
        {code !== undefined && ' · '}
        <strong>{name}</strong> · <span className={styles.rowMeta}>{meta}</span>
      </div>
      <div className={styles.btnRow}>{children}</div>
    </li>
  );
}

function SmallLink({ to, children, testId, label }: { to: string; children: string; testId?: string; label?: string }) {
  return (
    <Link className={`${button.secondary} ${button.small}`} to={to} data-testid={testId} aria-label={label}>
      {children}
    </Link>
  );
}

function ReasonRow({ reason, supplier }: { reason: NotReadyReason; supplier: SupplierModel }) {
  const organisation = supplier.organisation;
  const definitionEntry = supplier.definitions.find((entry) => entry.definition.id === reason.definitionId);
  const linkEntry = definitionEntry?.links.find((entry) => entry.link.id === reason.linkId);
  const firstLink = linkEntry ?? definitionEntry?.links[0];
  const name =
    reason.kind === 'LINK_WITHOUT_TASK' || reason.kind === 'NO_ACTIVE_TASK'
      ? (linkEntry?.link.name ?? organisation.name)
      : reason.kind === 'NO_DEFINITION'
        ? organisation.name
        : (definitionEntry?.definition.name ?? organisation.name);
  const checkLink =
    firstLink === undefined ? null : (
      <SmallLink
        to={linkCheckHref({ linkId: firstLink.link.id, definitionId: firstLink.link.importDefinitionId })}
        testId={`check-link-${firstLink.link.id}`}
      >
        {DASHBOARD_TEXT.check}
      </SmallLink>
    );

  let actions: ReactNode;
  switch (reason.kind) {
    case 'NO_DEFINITION':
      actions = (
        <>
          <ContinueSetupAction
            href={wizardHref({ organisationId: organisation.id })}
            testId={`continue-organisation-${organisation.id}`}
            label={DASHBOARD_TEXT.continueSetup}
          />
          <SmallLink to={supplierHref(organisation.id)}>{DASHBOARD_TEXT.toSupplier}</SmallLink>
        </>
      );
      break;
    case 'NO_LINK':
      actions = (
        <>
          <ContinueSetupAction
            href={wizardHref({ organisationId: organisation.id, definitionId: reason.definitionId })}
            testId={`continue-definition-${reason.definitionId}`}
            label={DASHBOARD_TEXT.continueSetup}
          />
          <SmallLink to={supplierHref(organisation.id)}>{DASHBOARD_TEXT.toSupplier}</SmallLink>
        </>
      );
      break;
    case 'LINK_WITHOUT_TASK':
      actions = (
        <>
          <ContinueSetupAction
            href={wizardHref({
              organisationId: organisation.id,
              definitionId: reason.definitionId,
              linkId: reason.linkId,
            })}
            testId={`continue-link-${reason.linkId}`}
            label={DASHBOARD_TEXT.continueSetup}
          />
          {checkLink}
        </>
      );
      break;
    case 'NO_ACTIVE_VERSION':
      actions = (
        <>
          {checkLink}
          <SmallLink to={supplierHref(organisation.id, 'versions', { definitionId: reason.definitionId })}>
            {DASHBOARD_TEXT.toSupplier}
          </SmallLink>
        </>
      );
      break;
    case 'NO_ACTIVE_TASK':
      actions = (
        <>
          {checkLink}
          <SmallLink to={supplierHref(organisation.id, 'links')}>{DASHBOARD_TEXT.toSupplier}</SmallLink>
        </>
      );
      break;
  }

  return (
    <PanelRow code={organisation.code} name={name} meta={REASON_TEXT[reason.kind]}>
      {actions}
    </PanelRow>
  );
}

type PanelContent = { title: string; body: ReactNode };

function SuppliersPanel({ model }: { model: DashboardModel }) {
  const shown = model.suppliers.slice(0, PANEL_ROWS);
  return (
    <>
      <ul className={styles.rowList}>
        {shown.map((supplier) => (
          <PanelRow
            key={supplier.organisation.id}
            code={supplier.organisation.code}
            name={supplier.organisation.name}
            meta={`${supplier.organisation.type === 'SUPPLIER' ? 'Leverancier' : 'Aankoopvereniging'}, ${supplier.linkCount} ${
              supplier.linkCount === 1 ? 'koppeling' : 'koppelingen'
            }`}
          >
            <SmallLink to={supplierHref(supplier.organisation.id)}>{DASHBOARD_TEXT.toSupplier}</SmallLink>
          </PanelRow>
        ))}
      </ul>
      {model.suppliers.length > shown.length && (
        <p className={styles.moreNote}>{DASHBOARD_TEXT.moreSuppliers(shown.length, model.suppliers.length)}</p>
      )}
    </>
  );
}

function ReadyPanel({ model }: { model: DashboardModel }) {
  const rows: { task: TaskRow; supplier: SupplierModel }[] = [];
  for (const supplier of model.suppliers) {
    for (const task of supplier.readyTasks) {
      rows.push({ task, supplier });
    }
  }
  const shown = rows.slice(0, PANEL_ROWS);
  return (
    <>
      <ul className={styles.rowList}>
        {shown.map(({ task, supplier }) => (
          <PanelRow
            key={task.id}
            code={supplier.organisation.code}
            name={task.name}
            meta={`${task.importLinkCode}, ${term('taskTrigger', task.triggerType).label.toLowerCase()}`}
          >
            <SmallLink to={supplierHref(supplier.organisation.id)}>{DASHBOARD_TEXT.toSupplier}</SmallLink>
          </PanelRow>
        ))}
      </ul>
      {rows.length > shown.length && <p className={styles.moreNote}>{DASHBOARD_TEXT.moreList(shown.length, rows.length)}</p>}
    </>
  );
}

function NotReadyPanel({ model, index }: { model: DashboardModel; index: SupplierIndex }) {
  const shown = model.reasons.slice(0, REASON_ROWS);
  return (
    <>
      <ul className={styles.rowList} data-testid="not-ready-rows">
        {shown.map((reason) => {
          const supplier = index.get(reason.organisationId);
          return supplier === undefined ? null : (
            <ReasonRow
              key={`${reason.kind}-${reason.definitionId ?? 0}-${reason.linkId ?? 0}-${reason.organisationId}`}
              reason={reason}
              supplier={supplier}
            />
          );
        })}
      </ul>
      {model.reasons.length > shown.length && (
        <p className={styles.moreNote}>{DASHBOARD_TEXT.moreList(shown.length, model.reasons.length)}</p>
      )}
    </>
  );
}

function LegacyPanel({ receipts, total }: { receipts: readonly LegacyReceiptRow[]; total: number }) {
  return (
    <>
      <ul className={styles.rowList}>
        {receipts.map((receipt) => (
          <PanelRow
            key={receipt.id}
            code={receipt.interfaceCode}
            name={`Ontvangst ${formatDateTime(receipt.receivedAt)}`}
            meta="Nog te beoordelen"
          >
            <SmallLink to={`/setup/legacy-config/${receipt.id}`}>{DASHBOARD_TEXT.legacyAction}</SmallLink>
          </PanelRow>
        ))}
      </ul>
      {total > receipts.length && <p className={styles.moreNote}>{DASHBOARD_TEXT.moreList(receipts.length, total)}</p>}
    </>
  );
}

function DefinitionsPanel({ definitions, total }: { definitions: readonly DefinitionRow[]; total: number }) {
  return (
    <>
      <ul className={styles.rowList}>
        {definitions.map((definition) => (
          <PanelRow
            key={definition.id}
            code={definition.code}
            name={definition.name}
            meta={`${
              isOwnDefinition(definition) ? DASHBOARD_TEXT.definitionKindOwn : DASHBOARD_TEXT.definitionKindReusable
            }, ${
              definition.activeRevisionId === null
                ? DASHBOARD_TEXT.noActiveVersion
                : DASHBOARD_TEXT.versionActive(definition.activeRevisionId)
            }`}
          >
            <SmallLink
              to={
                isOwnDefinition(definition)
                  ? supplierHref(definition.sourceOrganisationId, 'fields', { definitionId: definition.id })
                  : `/templates/${definition.id}`
              }
            >
              {DASHBOARD_TEXT.open}
            </SmallLink>
          </PanelRow>
        ))}
      </ul>
      {total > definitions.length && (
        <p className={styles.moreNote}>{DASHBOARD_TEXT.moreDefinitions(definitions.length, total)}</p>
      )}
    </>
  );
}

type LegacyState = { total: number; receipts: LegacyReceiptRow[] } | 'error' | null;

type SupplierFilters = { query: string; kind: string; status: string };

function filterSuppliers(suppliers: readonly SupplierModel[], filters: SupplierFilters): SupplierModel[] {
  const query = filters.query.trim().toLowerCase();
  return suppliers.filter((supplier) => {
    const { organisation } = supplier;
    if (
      query !== '' &&
      !organisation.code.toLowerCase().includes(query) &&
      !organisation.name.toLowerCase().includes(query)
    ) {
      return false;
    }
    if (filters.kind !== '' && organisation.type !== filters.kind) {
      return false;
    }
    if (filters.status === 'active' && !organisation.active) {
      return false;
    }
    if (filters.status === 'inactive' && organisation.active) {
      return false;
    }
    return true;
  });
}

function SupplierList({ suppliers }: { suppliers: readonly SupplierModel[] }) {
  const [filters, setFilters] = useState<SupplierFilters>({ query: '', kind: '', status: '' });
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const filtered = useMemo(() => filterSuppliers(suppliers, filters), [suppliers, filters]);
  const pageRows = filtered.slice(page * size, (page + 1) * size);
  const columns = DASHBOARD_TEXT.columns;

  function update(next: Partial<SupplierFilters>) {
    setFilters((current) => ({ ...current, ...next }));
    setPage(0);
  }

  return (
    <section className={styles.section} aria-labelledby="supplier-list-title">
      <h2 id="supplier-list-title" className={styles.sectionTitle}>
        {DASHBOARD_TEXT.listTitle}
      </h2>
      <div className={`${pageCss.card} ${styles.cardClip}`}>
        <div className={styles.toolbar}>
          <label className={`${styles.toolbarField} ${styles.toolbarSearch}`}>
            {DASHBOARD_TEXT.searchLabel}
            <input
              type="search"
              value={filters.query}
              placeholder={DASHBOARD_TEXT.searchPlaceholder}
              onChange={(event) => update({ query: event.target.value })}
              data-testid="supplier-search"
            />
          </label>
          <label className={styles.toolbarField}>
            {DASHBOARD_TEXT.filterKind}
            <select value={filters.kind} onChange={(event) => update({ kind: event.target.value })} data-testid="supplier-filter-kind">
              <option value="">{DASHBOARD_TEXT.filterAll}</option>
              <option value="SUPPLIER">Leverancier</option>
              <option value="PURCHASING_ASSOCIATION">Aankoopvereniging</option>
            </select>
          </label>
          <label className={styles.toolbarField}>
            {DASHBOARD_TEXT.filterStatus}
            <select value={filters.status} onChange={(event) => update({ status: event.target.value })} data-testid="supplier-filter-status">
              <option value="">{DASHBOARD_TEXT.filterAll}</option>
              <option value="active">{DASHBOARD_TEXT.statusActive}</option>
              <option value="inactive">{DASHBOARD_TEXT.statusInactive}</option>
            </select>
          </label>
        </div>
        <div className={styles.tableWrap}>
          <table className={`${styles.table} ${styles.stack}`}>
            <thead>
              <tr>
                <th>{columns.code}</th>
                <th>{columns.name}</th>
                <th>{columns.kind}</th>
                <th>{columns.status}</th>
                <th>{columns.links}</th>
                <th>{columns.readiness}</th>
                <th>
                  <span className={styles.srOnly}>{columns.action}</span>
                </th>
              </tr>
            </thead>
            <tbody data-testid="source-organisations">
              {pageRows.length === 0 ? (
                <tr>
                  <td colSpan={7} className={styles.empty}>
                    {suppliers.length === 0 ? DASHBOARD_TEXT.emptyList : DASHBOARD_TEXT.noMatch}
                  </td>
                </tr>
              ) : (
                pageRows.map((supplier) => (
                  <tr key={supplier.organisation.id} data-testid={`supplier-row-${supplier.organisation.id}`}>
                    <td data-label={columns.code} className={styles.code}>
                      {supplier.organisation.code}
                    </td>
                    <td data-label={columns.name}>
                      <strong>{supplier.organisation.name}</strong>
                    </td>
                    <td data-label={columns.kind}>
                      <Term domain="organisationType" code={supplier.organisation.type} />
                    </td>
                    <td data-label={columns.status}>
                      <ToneBadge
                        label={supplier.organisation.active ? DASHBOARD_TEXT.statusActive : DASHBOARD_TEXT.statusInactive}
                        tone={supplier.organisation.active ? 'ready' : 'neutral'}
                      />
                    </td>
                    <td data-label={columns.links}>{supplier.linkCount}</td>
                    <td data-label={columns.readiness}>
                      <ToneBadge label={READINESS_LABEL[supplier.readiness]} tone={READINESS_TONE[supplier.readiness]} />
                    </td>
                    <td>
                      <Link
                        className={`${button.secondary} ${button.small}`}
                        to={supplierHref(supplier.organisation.id)}
                        aria-label={DASHBOARD_TEXT.openLabel(supplier.organisation.name)}
                        data-testid={`open-supplier-${supplier.organisation.id}`}
                      >
                        {DASHBOARD_TEXT.open}
                      </Link>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        <Pager
          page={page}
          size={size}
          totalElements={filtered.length}
          onPageChange={setPage}
          onSizeChange={(next) => {
            setSize(next);
            setPage(0);
          }}
        />
      </div>
    </section>
  );
}

function DashboardBody({ model, legacy, canManage }: { model: DashboardModel; legacy: LegacyState; canManage: boolean }) {
  const [openId, setOpenId] = useState<string | null | undefined>(undefined);
  const index = useMemo(() => indexSuppliers(model), [model]);
  const { totals } = model;

  const readyCount = totals.readyTasks;
  const reasonCount = model.reasons.length;
  const notReadySub = DASHBOARD_TEXT.notReadySub({
    noVersion: totals.reasonCounts.NO_ACTIVE_VERSION,
    noTask: totals.reasonCounts.LINK_WITHOUT_TASK,
    noLink: totals.reasonCounts.NO_LINK + totals.reasonCounts.NO_DEFINITION,
    other: totals.reasonCounts.NO_ACTIVE_TASK,
  });
  const ownDefinitions = totals.definitions - totals.reusableDefinitions;

  const cards: CardModel[] = [
    {
      id: 'suppliers',
      title: DASHBOARD_TEXT.cards.suppliers,
      count: totals.organisations,
      subLine: DASHBOARD_TEXT.suppliersSub(totals.suppliers, totals.associations),
      tone: 'neutral',
      empty: totals.organisations === 0,
      showLabel: DASHBOARD_TEXT.showSuppliers,
    },
    {
      id: 'ready',
      title: DASHBOARD_TEXT.cards.ready,
      count: readyCount,
      topLine: readyCount === 0 ? DASHBOARD_TEXT.readyNone : undefined,
      subLine: readyCount === 0 ? undefined : DASHBOARD_TEXT.readySub(readyCount),
      tone: 'success',
      empty: readyCount === 0,
      showLabel: DASHBOARD_TEXT.showReady,
    },
    {
      id: 'notReady',
      title: DASHBOARD_TEXT.cards.notReady,
      count: reasonCount,
      topLine: reasonCount === 0 ? DASHBOARD_TEXT.notReadyNone : undefined,
      subLine: reasonCount === 0 ? undefined : notReadySub,
      tone: 'warning',
      empty: reasonCount === 0,
      showLabel: DASHBOARD_TEXT.showNotReady,
    },
  ];
  if (canManage) {
    const legacyTotal = legacy === null || legacy === 'error' ? 0 : legacy.total;
    cards.push({
      id: 'legacy',
      title: DASHBOARD_TEXT.cards.legacy,
      count: legacy === 'error' ? '—' : legacyTotal,
      topLine: legacy === 'error' ? DASHBOARD_TEXT.legacyUnknown : legacyTotal === 0 ? DASHBOARD_TEXT.legacyNone : undefined,
      subLine: legacyTotal > 0 ? DASHBOARD_TEXT.legacySub(legacyTotal) : undefined,
      tone: 'warning',
      empty: legacyTotal === 0,
      showLabel: DASHBOARD_TEXT.showLegacy,
      footer: (
        <p className={styles.cardFooter}>
          <Link to="/setup/legacy-config">{DASHBOARD_TEXT.legacyAll}</Link>
        </p>
      ),
    });
  }
  cards.push({
    id: 'definitions',
    title: DASHBOARD_TEXT.cards.definitions,
    count: totals.definitions,
    subLine: DASHBOARD_TEXT.definitionsSub(totals.reusableDefinitions, ownDefinitions),
    tone: 'neutral',
    empty: totals.definitions === 0,
    showLabel: DASHBOARD_TEXT.showDefinitions,
    footer: (
      <p className={styles.cardFooter}>
        <Link to="/templates">{DASHBOARD_TEXT.allDefinitions}</Link>
      </p>
    ),
  });

  // "Nog niet klaar" staat standaard open zodra er iets ontbreekt; een eigen keuze (ook "alles dicht") wint.
  const effectiveOpen = openId === undefined ? (reasonCount > 0 ? 'notReady' : null) : openId;
  const openCard = cards.find((card) => card.id === effectiveOpen && !card.empty);

  let panel: PanelContent | null = null;
  if (openCard !== undefined) {
    let body: ReactNode = null;
    switch (openCard.id) {
      case 'suppliers':
        body = <SuppliersPanel model={model} />;
        break;
      case 'ready':
        body = <ReadyPanel model={model} />;
        break;
      case 'notReady':
        body = <NotReadyPanel model={model} index={index} />;
        break;
      case 'legacy':
        body = legacy !== null && legacy !== 'error' ? <LegacyPanel receipts={legacy.receipts} total={legacy.total} /> : null;
        break;
      case 'definitions': {
        const all = model.suppliers.flatMap((supplier) => supplier.definitions.map((entry) => entry.definition));
        body = <DefinitionsPanel definitions={all.slice(0, DEFINITION_ROWS)} total={totals.definitions} />;
        break;
      }
    }
    panel = { title: openCard.title, body };
  }

  return (
    <>
      <SummaryCards
        baseId={BASE_ID}
        cards={cards}
        openId={effectiveOpen}
        onToggle={(id) => setOpenId(effectiveOpen === id ? null : id)}
        texts={{ show: DASHBOARD_TEXT.show, hide: DASHBOARD_TEXT.hide, gridLabel: DASHBOARD_TEXT.gridLabel }}
        testIdPrefix="setup-card"
        gridTestId="setup-cards"
      />
      {panel !== null && openCard !== undefined && (
        <DetailPanel baseId={BASE_ID} cardId={openCard.id} title={panel.title} testId="setup-card-panel">
          {panel.body}
        </DetailPanel>
      )}
      <SupplierList suppliers={model.suppliers} />
    </>
  );
}

/**
 * Oude adresvorm `?definitionId=…&revisionId=…` (slotstap van het stappenplan, gereedheidslijst, legacy-overname):
 * zoekt de leverancier van de importdefinitie op en stuurt door naar "Bestand en velden" met die versie en het
 * bewerkblok open (daar staat "Versie activeren").
 */
function LegacyQueryRedirect({ model, definitionId, revisionId }: { model: DashboardModel; definitionId: number; revisionId: number }) {
  const supplier = model.suppliers.find((entry) =>
    entry.definitions.some((definition) => definition.definition.id === definitionId),
  );
  if (supplier === undefined) {
    return (
      <p className={styles.alert} role="alert">
        {DASHBOARD_TEXT.redirectNotFound}
      </p>
    );
  }
  return <Navigate replace to={supplierHref(supplier.organisation.id, 'fields', { definitionId, revisionId, edit: true })} />;
}

export function SetupOverviewPage() {
  const readGate = usePermissionGate(PERMISSION_READ);
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const [params] = useSearchParams();
  const legacyDefinitionId = parseId(params.get('definitionId'));
  const legacyRevisionId = parseId(params.get('revisionId'));

  const dashboard = useQuery('setup-dashboard', (signal) => loadDashboard(signal));
  // De ontvangsten vragen het recht Beheren: zonder dat recht wordt er niets opgehaald.
  const legacy = useQuery<LegacyState>(manageGate.allowed ? 'setup-dashboard-legacy' : 'setup-dashboard-legacy:none', async (signal) => {
    if (!manageGate.allowed) {
      return null;
    }
    try {
      const page = await legacyConfigApi.listLegacyReceipts({ status: 'RECEIVED', page: 0, size: PANEL_ROWS }, signal);
      return { total: page.totalElements, receipts: page.content };
    } catch (cause) {
      if (signal.aborted) {
        throw cause;
      }
      return 'error';
    }
  });

  if (!readGate.allowed) {
    return (
      <div className={styles.page}>
        <p className={styles.alert} role="alert">
          {readGate.reason}
        </p>
      </div>
    );
  }

  const redirecting = legacyDefinitionId !== null && legacyRevisionId !== null;

  return (
    <div className={styles.page}>
      <header className={pageCss.pageHead}>
        <div className={pageCss.pageHeadText}>
          <h1 className={pageCss.title}>{SCREEN.leveranciers}</h1>
          <Crumbs />
        </div>
        <NewSupplierButton />
      </header>
      <p className={styles.intro}>{DASHBOARD_TEXT.intro}</p>
      <WhatIsThis>{DASHBOARD_TEXT.what}</WhatIsThis>

      {dashboard.error !== null && <ErrorBanner error={dashboard.error} />}
      {dashboard.loading && dashboard.data === null && (
        <p className={styles.loading}>{redirecting ? DASHBOARD_TEXT.redirectResolving : DASHBOARD_TEXT.loading}</p>
      )}
      {dashboard.data !== null &&
        (redirecting ? (
          <LegacyQueryRedirect model={dashboard.data} definitionId={legacyDefinitionId} revisionId={legacyRevisionId} />
        ) : (
          <DashboardBody
            model={dashboard.data}
            legacy={manageGate.allowed ? legacy.data : null}
            canManage={manageGate.allowed}
          />
        ))}
    </div>
  );
}
