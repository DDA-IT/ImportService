/**
 * `/templates` — het overzicht "Importdefinities" (R2, beslissingslog 2026-10-23 "Importdefinitie als schermwoord" en
 * "Naamgeving en redesign inrichting: goedgekeurd", mock `docs/mock/setup-redesign-mock.html` route `#templates`).
 * De route blijft `/templates`; enkel de zichtbare naam verandert. Het scherm toont ALLE importdefinities, eigen
 * (`OWN_DEFINITION`) en herbruikbaar (`REUSABLE_TEMPLATE`), in een kaartenrooster met één paneel en één platte lijst.
 *
 * Gegevens: drie bulkaanroepen met doorbladeren (organisaties, importdefinities, koppelingen; allemaal vlagloos). Wat
 * een herbruikbare importdefinitie gebruikt (`GET /templates/{id}/materialisations`, achter de inrichtings-API-vlag)
 * wordt enkel geladen voor de herbruikbare importdefinities op de getoonde pagina, zodat er geen verzoek per rij van
 * de hele lijst ontstaat; een fout daarvan toont "—" en een melding, nooit een kapot scherm.
 *
 * Herkomst "Uit legacy" komt uit `DefinitionRow.legacyOriginKind`; status, versienummers en Inactief uit de uitgebreide `DefinitionRow` (B1).
 */

import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { usePermissionGate } from '../../actor/permissions.ts';
import { PERMISSION_READ } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { Pager } from '../../components/Pager.tsx';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { DetailPanel, SummaryCards, type CardModel } from '../../components/SummaryCards.tsx';
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
import { GROUP, PRODUCT, SCREEN } from '../../terms/navigation.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { FlagOffNotice } from '../setup/FlagOffNotice.tsx';
import { supplierHref } from '../setup/supplierRoutes.ts';
import { definitionHref } from './definitionRoutes.ts';
import { loadDefinitionOverview, loadMaterialisations, type MaterialisationsById } from './definitionsData.ts';
import { filterDefinitions, NO_FILTERS, openDraftNumberOf, type DefinitionEntry, type DefinitionFilters } from './definitionsModel.ts';
import { isSetupApiDisabledError } from './setupApiFlag.ts';
import styles from '../setup/SetupScreens.module.css';

const BASE_ID = 'definitions-overview';
const PANEL_ROWS = 10;
const TEXT = DEFINITIONS_TEXT;

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
        <span aria-current="page">{SCREEN.importdefinities}</span>
      </li>
    </ol>
  );
}

function ConceptPanel({ concepts }: { concepts: readonly DefinitionEntry[] }) {
  const shown = concepts.slice(0, PANEL_ROWS);
  return (
    <>
      <ul className={styles.rowList} data-testid="concept-rows">
        {shown.map((entry) => (
          <li key={entry.definition.id} data-testid={`concept-row-${entry.definition.id}`}>
            <div className={styles.rowMain}>
              <span className={styles.code}>{entry.definition.code}</span> · <strong>{entry.definition.name}</strong> ·{' '}
              <span className={styles.rowMeta}>
                {entry.reusable ? TEXT.kindReusable : TEXT.kindOwn}, {TEXT.conceptRowMeta}
                {entry.definition.draftRevisionNumber != null && ` · ${TEXT.conceptVersion(entry.definition.draftRevisionNumber)}`}
              </span>
            </div>
            <div className={styles.btnRow}>
              <Link
                className={`${button.secondary} ${button.small}`}
                to={definitionHref(entry.definition.id)}
                aria-label={TEXT.openLabel(entry.definition.name)}
              >
                {TEXT.open}
              </Link>
            </div>
          </li>
        ))}
      </ul>
      {concepts.length > shown.length && <p className={styles.moreNote}>{TEXT.moreList(shown.length, concepts.length)}</p>}
    </>
  );
}

/** "Gebruikt door" en "Koppelingen" van één rij: eigen uit de bulklijsten, herbruikbaar uit de materialisaties. */
function usageCells(entry: DefinitionEntry, usage: { data: MaterialisationsById | null; failed: boolean }) {
  if (!entry.reusable) {
    return {
      usedBy: entry.organisation === null ? TEXT.usageUnknown : entry.organisation.name,
      links: String(entry.links.length),
    };
  }
  const rows = usage.data?.[entry.definition.id];
  if (rows === undefined) {
    const placeholder = usage.failed ? TEXT.usageUnknown : TEXT.usageLoading;
    return { usedBy: placeholder, links: placeholder, title: usage.failed ? TEXT.usageUnknownHint : undefined };
  }
  return {
    usedBy: rows.length === 0 ? TEXT.usedByNone : TEXT.usedByImportDefinitions(rows.length),
    links: String(rows.reduce((sum, row) => sum + row.importLinkCount, 0)),
  };
}

function DefinitionList({ entries }: { entries: readonly DefinitionEntry[] }) {
  const [filters, setFilters] = useState<DefinitionFilters>(NO_FILTERS);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const filtered = useMemo(() => filterDefinitions(entries, filters), [entries, filters]);
  const pageRows = filtered.slice(page * size, (page + 1) * size);
  const reusableIds = pageRows.filter((entry) => entry.reusable).map((entry) => entry.definition.id);
  const columns = TEXT.columns;

  // Alleen de herbruikbare importdefinities van de getoonde pagina: nooit een verzoek per rij van de hele lijst.
  const usage = useQuery<MaterialisationsById>(`definitions-usage:${reusableIds.join(',')}`, (signal) =>
    loadMaterialisations(reusableIds, signal),
  );

  function update(next: Partial<DefinitionFilters>) {
    setFilters((current) => ({ ...current, ...next }));
    setPage(0);
  }

  return (
    <section className={styles.section} aria-labelledby="definition-list-title">
      <h2 id="definition-list-title" className={styles.sectionTitle}>
        {TEXT.listTitle}
      </h2>
      <div className={`${pageCss.card} ${styles.cardClip}`}>
        <div className={styles.toolbar}>
          <label className={`${styles.toolbarField} ${styles.toolbarSearch}`}>
            {TEXT.searchLabel}
            <input
              type="search"
              value={filters.query}
              placeholder={TEXT.searchPlaceholder}
              onChange={(event) => update({ query: event.target.value })}
              data-testid="definition-search"
            />
          </label>
          <label className={styles.toolbarField}>
            {TEXT.filterKind}
            <select value={filters.kind} onChange={(event) => update({ kind: event.target.value })} data-testid="definition-filter-kind">
              <option value="">{TEXT.filterAll}</option>
              <option value="REUSABLE">{TEXT.kindReusable}</option>
              <option value="OWN">{TEXT.kindOwn}</option>
            </select>
          </label>
          <label className={styles.toolbarField}>
            {TEXT.filterStatus}
            <select value={filters.status} onChange={(event) => update({ status: event.target.value })} data-testid="definition-filter-status">
              <option value="">{TEXT.filterAll}</option>
              <option value="ACTIVE">{STATUS_LABEL.ACTIVE}</option>
              <option value="CONCEPT">{STATUS_LABEL.CONCEPT}</option>
              <option value="NO_VERSION">{STATUS_LABEL.NO_VERSION}</option>
              <option value="INACTIVE">{TEXT.filterInactive}</option>
            </select>
          </label>
          <label className={styles.toolbarField}>
            {TEXT.filterOrigin}
            <select value={filters.origin} onChange={(event) => update({ origin: event.target.value })} data-testid="definition-filter-origin">
              <option value="">{TEXT.filterAll}</option>
              <option value="OWN">{ORIGIN_LABEL.OWN}</option>
              <option value="LEGACY">{ORIGIN_LABEL.LEGACY}</option>
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
                <th>{columns.supplier}</th>
                <th>{columns.activeVersion}</th>
                <th>{columns.usedBy}</th>
                <th>{columns.links}</th>
                <th>{columns.origin}</th>
                <th>
                  <span className={styles.srOnly}>{columns.action}</span>
                </th>
              </tr>
            </thead>
            <tbody data-testid="definitions">
              {pageRows.length === 0 ? (
                <tr>
                  <td colSpan={9} className={styles.empty}>
                    {entries.length === 0 ? TEXT.emptyList : TEXT.noMatch}
                  </td>
                </tr>
              ) : (
                pageRows.map((entry) => {
                  const { definition, organisation } = entry;
                  const cells = usageCells(entry, { data: usage.data, failed: usage.error !== null });
                  return (
                    <tr key={definition.id} data-testid={`definition-row-${definition.id}`}>
                      <td data-label={columns.code} className={styles.code}>
                        {definition.code}
                      </td>
                      <td data-label={columns.name}>
                        <strong>{definition.name}</strong>
                      </td>
                      <td data-label={columns.kind}>
                        <ToneBadge label={entry.reusable ? TEXT.kindReusable : TEXT.kindOwn} tone={entry.reusable ? 'planned' : 'neutral'} />
                      </td>
                      <td data-label={columns.supplier}>
                        {organisation === null ? (
                          TEXT.usageUnknown
                        ) : (
                          <Link to={supplierHref(organisation.id)}>{organisation.name}</Link>
                        )}
                      </td>
                      <td data-label={columns.activeVersion}>
                        <span title={STATUS_HINT[entry.status]}>
                          <ToneBadge label={STATUS_LABEL[entry.status]} tone={STATUS_TONE[entry.status]} />
                        </span>
                        {definition.activeRevisionNumber != null && (
                          <span className={styles.rowMeta}> {TEXT.versionNumber(definition.activeRevisionNumber)}</span>
                        )}
                        {openDraftNumberOf(definition) !== null && (
                          <span className={styles.rowMeta}> · {TEXT.draftOpen(openDraftNumberOf(definition)!)}</span>
                        )}
                        {entry.inactive && (
                          <>
                            {' '}
                            <span title={INACTIVE_HINT}>
                              <ToneBadge label={INACTIVE_LABEL} tone="neutral" />
                            </span>
                          </>
                        )}
                      </td>
                      <td data-label={columns.usedBy} title={'title' in cells ? cells.title : undefined}>
                        {cells.usedBy}
                      </td>
                      <td data-label={columns.links} title={'title' in cells ? cells.title : undefined}>
                        {cells.links}
                      </td>
                      <td data-label={columns.origin}>
                        <span title={ORIGIN_HINT[entry.origin]}>
                          <ToneBadge label={ORIGIN_LABEL[entry.origin]} tone="neutral" />
                        </span>
                      </td>
                      <td>
                        <Link
                          className={`${button.secondary} ${button.small}`}
                          to={definitionHref(definition.id)}
                          aria-label={TEXT.openLabel(definition.name)}
                          data-testid={`open-definition-${definition.id}`}
                        >
                          {TEXT.open}
                        </Link>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
        {usage.error !== null &&
          (isSetupApiDisabledError(usage.error) ? (
            <FlagOffNotice message={TEXT.usedBy.flagOff} className={styles.alert} />
          ) : (
            <ErrorBanner error={usage.error} />
          ))}
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

export function TemplateListPage() {
  const readGate = usePermissionGate(PERMISSION_READ);
  const overview = useQuery('definitions-overview', (signal) => loadDefinitionOverview(signal));
  // `undefined` = nog geen eigen keuze: "Concept" staat open zodra er iets te activeren valt.
  const [openId, setOpenId] = useState<string | null | undefined>(undefined);

  if (!readGate.allowed) {
    return (
      <div className={styles.page}>
        <p className={styles.alert} role="alert">
          {readGate.reason}
        </p>
      </div>
    );
  }

  const data = overview.data;
  let body = null;
  if (data !== null) {
    const { totals } = data;
    const concepts = data.entries.filter((entry) => entry.status === 'CONCEPT');
    const cards: CardModel[] = [
      {
        id: 'all',
        title: TEXT.cards.all,
        count: totals.all,
        subLine: TEXT.allSub(totals.active, totals.concept, totals.noVersion),
        tone: 'neutral',
        empty: totals.all === 0,
        noButton: true,
      },
      {
        id: 'active',
        title: TEXT.cards.active,
        count: totals.active,
        subLine: TEXT.activeSub(totals.activeReusable, totals.activeOwn),
        tone: 'success',
        empty: totals.active === 0,
        noButton: true,
      },
      {
        id: 'concept',
        title: TEXT.cards.concept,
        count: totals.concept,
        topLine: totals.concept === 0 ? TEXT.conceptNone : undefined,
        subLine: totals.concept === 0 ? undefined : TEXT.conceptSub,
        tone: 'warning',
        empty: totals.concept === 0,
        showLabel: TEXT.showConcepts,
      },
      {
        id: 'reusable',
        title: TEXT.cards.reusable,
        count: totals.reusable,
        subLine: TEXT.reusableSub,
        tone: 'neutral',
        empty: totals.reusable === 0,
        noButton: true,
      },
    ];
    const effectiveOpen = openId === undefined ? (concepts.length > 0 ? 'concept' : null) : openId;
    const showPanel = effectiveOpen === 'concept' && concepts.length > 0;
    body = (
      <>
        <SummaryCards
          baseId={BASE_ID}
          cards={cards}
          openId={effectiveOpen}
          onToggle={(id) => setOpenId(effectiveOpen === id ? null : id)}
          texts={{ show: TEXT.show, hide: TEXT.hide, gridLabel: TEXT.gridLabel }}
          testIdPrefix="definition-card"
          gridTestId="definition-cards"
        />
        {showPanel && (
          <DetailPanel baseId={BASE_ID} cardId="concept" title={TEXT.cards.concept} testId="definition-card-panel">
            <ConceptPanel concepts={concepts} />
          </DetailPanel>
        )}
        <DefinitionList entries={data.entries} />
      </>
    );
  }

  return (
    <div className={styles.page}>
      <header className={pageCss.pageHead}>
        <div className={pageCss.pageHeadText}>
          <h1 className={pageCss.title}>{SCREEN.importdefinities}</h1>
          <Crumbs />
        </div>
      </header>
      <p className={styles.intro}>{TEXT.intro}</p>
      <WhatIsThis>{TEXT.what}</WhatIsThis>

      {overview.error !== null && <ErrorBanner error={overview.error} />}
      {overview.loading && overview.data === null && <p className={styles.loading}>{TEXT.loading}</p>}
      {body}
    </div>
  );
}
