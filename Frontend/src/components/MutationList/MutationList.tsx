/**
 * Het herbruikbare mutatielijst-component (bouwstap F8), zie
 * `docs/design/frontend-scherm3-bundel-design.md` §11 en `docs/decisions.md` 2026-09-22
 * ("koppelingoverstijgende mutatielijst"), 2026-09-23 en 2026-09-24 (C1/C4).
 *
 * Wat dit component **wel** doet: de filterbalk (alleen de velden uit `source.supportedFilters`), de
 * tabel met de `MutationRow`-kolommen inclusief de vier beslissingsvelden, de paginering, de laad-/
 * lege-/foutstatus, en per rij de doorgegeven acties met hun bevestigingsdialoog.
 *
 * Wat het bewust **niet** weet (§11.3, dit is wat het herbruikbaar maakt):
 * 1. of zijn gegevens van een bundel of van een batch komen — het kent geen `bundleId`/`batchId`;
 * 2. welk HTTP-pad het aanroept — het importeert geen enkele functie uit `api/` (alleen contract-
 *    *types*, die bij het compileren verdwijnen);
 * 3. welke acties toegestaan zijn — dat vraagt het aan `action.gate(row)`;
 * 4. wat er na een actie buiten zichzelf ververst moet worden — het roept `onAfterAction()` aan;
 * 5. welke filters er bestaan, alleen welke deze bron ondersteunt;
 * 6. wie er tekent — de actor komt uit de gedeelde `ActorContext`.
 *
 * Twee harde regels uit het ontwerp die hier zichtbaar zijn:
 * - **Geen berekening op bedragen** (§9.4, AGENT.md §2 principe 8): `beforeBasePrice` en
 *   `afterBasePrice` worden beide getoond, het verschil wordt nooit uitgerekend.
 * - **`null` is "—", nooit 0** (§9.3): "niet vastgesteld" en "nul" zijn verschillende dingen.
 * - **Geen groepering aan de clientkant** (§11.5): op `identityHash` klikken zet het serverzijdige
 *   filter, zodat de hele wijzigingsgroep uit de server komt en niet uit één opgehaalde pagina.
 * - **Eén bron van waarheid voor de filter** (F9, §10.4 punt 1): de optionele `toolbar`-slot krijgt
 *   exact het filterobject waaruit ook de query naar de bron gebouwd wordt, plus het aantal dat de
 *   lijst voor díe filter toont. Zo kan een groepsactie geen andere selectie versturen dan de lijst.
 */

import { useState } from 'react';
import { MUTATION_ACTION_TYPES, MUTATION_STATUSES, type MutationRow } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { useAction } from '../../hooks/useAction.ts';
import { useActor } from '../../actor/ActorContext.tsx';
import { ConfirmDialog } from '../ConfirmDialog.tsx';
import { DataTable, type DataTableColumn } from '../DataTable.tsx';
import { Pager } from '../Pager.tsx';
import { StatusBadge } from '../StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import type { MutationFilter, MutationListProps, MutationQuery, MutationRowAction } from './types.ts';
import styles from './MutationList.module.css';

/** Hoeveel tekens van de identiteitshash zichtbaar zijn; de volledige waarde staat in de tooltip. */
const HASH_PREVIEW_LENGTH = 12;

/** "niet vastgesteld" — nooit als 0 getoond (§9.3). */
function Dash() {
  return (
    <span className={styles.dash} title="niet vastgesteld">
      —
    </span>
  );
}

function text(value: string | null) {
  return value === null || value === '' ? <Dash /> : <>{value}</>;
}

function count(value: number | null) {
  return value === null ? <Dash /> : <>{value}</>;
}

function dateTime(value: string | null) {
  return value === null ? <Dash /> : <>{new Date(value).toLocaleString('nl-BE')}</>;
}

/**
 * Toont een bedrag zoals het binnenkomt: nl-BE-scheidingstekens, geen afronding, geen aangenomen
 * munteenheid (§9.4). `null` is "—", nooit "0,00".
 */
function amount(value: number | null, currency: string | null) {
  if (value === null) {
    return <Dash />;
  }
  const formatted = new Intl.NumberFormat('nl-BE', { maximumFractionDigits: 20 }).format(value);
  return <>{currency === null || currency === '' ? formatted : `${formatted} ${currency}`}</>;
}

type FilterState = {
  status: string;
  batchId: string;
  actionType: string;
  statusReason: string;
  identityHash: string;
};

const EMPTY_FILTERS: FilterState = { status: '', batchId: '', actionType: '', statusReason: '', identityHash: '' };

function initialFilters(initial: Partial<MutationQuery> | undefined): FilterState {
  return {
    status: initial?.status ?? '',
    batchId: initial?.batchId === undefined ? '' : String(initial.batchId),
    actionType: initial?.actionType ?? '',
    statusReason: initial?.statusReason ?? '',
    identityHash: initial?.identityHash ?? '',
  };
}

export function MutationList({
  source,
  rowActions,
  initialQuery,
  emptyMessage = 'Geen mutaties gevonden.',
  onAfterAction,
  toolbar,
}: MutationListProps) {
  const { actor } = useActor();
  const supports = (name: keyof FilterState) => source.supportedFilters.includes(name);

  // `applied` is wat er nu in de lijst staat en dus naar de server gaat; `draft` is wat de gebruiker in
  // de tekstvelden typt maar nog niet toegepast heeft. Keuzelijsten passen zichzelf meteen toe.
  const [applied, setApplied] = useState<FilterState>(() => initialFilters(initialQuery));
  const [draft, setDraft] = useState<FilterState>(() => initialFilters(initialQuery));
  const [page, setPage] = useState(initialQuery?.page ?? 0);
  const [size, setSize] = useState(initialQuery?.size ?? 50);
  const [pendingAction, setPendingAction] = useState<{ action: MutationRowAction; row: MutationRow } | null>(null);

  /**
   * De toegepaste filter: enkel de filters die deze bron ondersteunt (§11.3 punt 5), lege waarden
   * weggelaten zodat een blanco filter nooit als parameter meereist.
   *
   * Eén bron van waarheid (F9, ontwerp §10.4 punt 1): de query naar de bron én de `toolbar`-slot
   * krijgen allebei dit ene object. Er bestaat geen tweede plek die een filter samenstelt.
   */
  const filter: MutationFilter = buildFilter();
  const query: MutationQuery = { ...filter, page, size };

  function buildFilter(): MutationFilter {
    const next: MutationFilter = {};
    if (supports('status') && applied.status !== '') {
      next.status = applied.status as NonNullable<MutationQuery['status']>;
    }
    if (supports('batchId') && applied.batchId.trim() !== '') {
      const parsed = Number(applied.batchId);
      // Onbruikbare invoer wordt niet stil op 0 gezet (AGENT.md §2 principe 3): dan gaat er gewoon geen
      // batchId-filter mee en meldt de balk dat het getal ongeldig is.
      if (Number.isInteger(parsed) && parsed > 0) {
        next.batchId = parsed;
      }
    }
    if (supports('actionType') && applied.actionType !== '') {
      next.actionType = applied.actionType as NonNullable<MutationQuery['actionType']>;
    }
    if (supports('statusReason') && applied.statusReason.trim() !== '') {
      next.statusReason = applied.statusReason.trim();
    }
    if (supports('identityHash') && applied.identityHash.trim() !== '') {
      next.identityHash = applied.identityHash.trim();
    }
    return next;
  }

  const batchIdInvalid =
    supports('batchId') && applied.batchId.trim() !== '' && query.batchId === undefined;

  const key = [
    'mutations',
    source.key,
    query.status ?? '',
    query.batchId ?? '',
    query.actionType ?? '',
    query.statusReason ?? '',
    query.identityHash ?? '',
    query.page,
    query.size,
  ].join('|');

  // Het resultaat draagt de sleutel waarvoor het geladen werd. `useQuery` houdt de vorige gegevens vast
  // tijdens het herladen (en zet `loading` pas in een effect), dus zonder deze sleutel zou de toolbar
  // één render lang het aantal van een vorige filter kunnen tonen naast de nieuwe filter.
  const list = useQuery(key, (signal) => source.fetchPage(query, signal).then((page) => ({ key, page })));
  const current = list.data?.page ?? null;
  const listedCount =
    list.data !== null && list.data.key === key && !list.loading && list.error === null
      ? list.data.page.totalElements
      : null;

  const runner = useAction(
    (action: MutationRowAction, row: MutationRow, input: { actor: string; reason: string | null }) =>
      action.run(row, input),
  );

  function applyFilters(next: FilterState) {
    setApplied(next);
    setDraft(next);
    setPage(0);
  }

  /** §11.5: de hele wijzigingsgroep opvragen doet de **server**, via het `identityHash`-filter. */
  function showChangeGroup(hash: string) {
    applyFilters({ ...applied, identityHash: hash });
  }

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    if (pendingAction === null) {
      return;
    }
    const result = await runner.execute(pendingAction.action, pendingAction.row, input);
    if (result === undefined) {
      // Mislukt: de dialoog blijft open met de foutmelding (inclusief de stabiele backendcode).
      return;
    }
    setPendingAction(null);
    runner.reset();
    list.reload();
    onAfterAction?.();
  }

  const actions = rowActions ?? [];

  const columns: readonly DataTableColumn<MutationRow>[] = buildColumns();

  function buildColumns(): readonly DataTableColumn<MutationRow>[] {
    const base: DataTableColumn<MutationRow>[] = [
      { key: 'id', header: 'Mutatie', render: (row) => row.id, align: 'right' },
      { key: 'batchId', header: 'Batch', render: (row) => row.batchId, align: 'right' },
      { key: 'actionType', header: 'Soort', render: (row) => row.actionType },
      {
        key: 'status',
        header: 'Status',
        render: (row) => (
          <span className={styles.statusCell}>
            <StatusBadge status={row.status} />
            {row.statusReason !== null && <span className={styles.statusReason}>{row.statusReason}</span>}
          </span>
        ),
      },
      {
        key: 'identity',
        header: 'Identiteit',
        render: (row) => (
          <span className={styles.identity}>
            <span>Leverancier: {text(row.identitySupplier)}</span>
            <span>Groep: {text(row.identitySupplierGroup)}</span>
            <span>Referentie: {text(row.identitySupplierReference)}</span>
            <span>
              Korting: {text(row.identityDiscountCode)}
              {row.identityDiscountState !== null && ` (${row.identityDiscountState})`}
            </span>
          </span>
        ),
      },
      {
        key: 'basePrice',
        header: 'Basisprijs (voor → na)',
        align: 'right',
        // Bewust twee waarden naast elkaar en NOOIT een berekend verschil (§9.4).
        render: (row) => (
          <span className={styles.price}>
            {amount(row.beforeBasePrice, row.basePriceCurrency)}
            {' → '}
            {amount(row.afterBasePrice, row.basePriceCurrency)}
          </span>
        ),
      },
      { key: 'domainMask', header: 'Domeinmasker', render: (row) => text(row.domainMask) },
      {
        key: 'reference',
        header: 'Koppelreferentie',
        // Alleen gevuld op een IDENTITY_REFERENCE_INCIDENT; anders drie keer "—", geen lege cel.
        render: (row) => (
          <span className={styles.identity}>
            <span>Soort: {text(row.referenceType)}</span>
            <span>Voor: {text(row.beforeReferenceValue)}</span>
            <span>Na: {text(row.afterReferenceValue)}</span>
          </span>
        ),
      },
      { key: 'sourceRowNumber', header: 'Bronregel', render: (row) => count(row.sourceRowNumber), align: 'right' },
      {
        key: 'identityHash',
        header: 'Wijzigingsgroep',
        render: (row) => {
          const hash = row.identityHash;
          if (hash === null) {
            // Per definitie zo voor de IMPORT_MARKER: die draagt geen identiteit (C4).
            return <Dash />;
          }
          return (
            <button
              type="button"
              className={styles.hashButton}
              title={`Toon de hele wijzigingsgroep — identityHash ${hash}`}
              aria-label={`Toon de hele wijzigingsgroep ${hash}`}
              onClick={() => showChangeGroup(hash)}
            >
              {hash.slice(0, HASH_PREVIEW_LENGTH)}…
            </button>
          );
        },
      },
      {
        key: 'decision',
        // De vier beslissingsvelden staan er ALTIJD, ook op scherm (2): het is hetzelfde record, en in
        // een batchlijst is "al beslist in een bundel" juist nuttige informatie (§11.2).
        header: 'Beslissing',
        render: (row) => (
          <span className={styles.identity}>
            <span>Door: {text(row.decidedBy)}</span>
            <span>Op: {dateTime(row.decidedAt)}</span>
            <span>Vanuit: {text(row.decidedFromStatus)}</span>
            <span>Beslissing #: {count(row.decisionId)}</span>
          </span>
        ),
      },
    ];

    if (actions.length === 0) {
      return base;
    }

    return [
      ...base,
      {
        key: 'actions',
        header: 'Acties',
        render: (row) => (
          <span className={styles.rowActions}>
            {actions.map((action) => {
              const gate = action.gate(row);
              return (
                <button
                  key={action.id}
                  type="button"
                  className={
                    action.variant === 'danger'
                      ? styles.dangerButton
                      : action.variant === 'primary'
                        ? styles.primaryButton
                        : styles.neutralButton
                  }
                  disabled={!gate.allowed}
                  // Een verboden actie wordt uitgeschakeld getoond MET de reden, niet verborgen (§9.1).
                  title={gate.allowed ? undefined : gate.reason}
                  aria-label={gate.allowed ? `${action.label} mutatie ${row.id}` : `${action.label} mutatie ${row.id}: ${gate.reason}`}
                  onClick={() => {
                    runner.reset();
                    setPendingAction({ action, row });
                  }}
                >
                  {action.label}
                </button>
              );
            })}
          </span>
        ),
      },
    ];
  }

  const pendingRequirement =
    pendingAction === null ? 'optional' : pendingAction.action.reasonRequirement(pendingAction.row);

  return (
    <div className={styles.list}>
      <form
        className={styles.filters}
        onSubmit={(event) => {
          event.preventDefault();
          applyFilters(draft);
        }}
      >
        {supports('status') && (
          <label className={styles.filter} htmlFor="mutation-filter-status">
            Status
            <select
              id="mutation-filter-status"
              value={draft.status}
              onChange={(event) => applyFilters({ ...draft, status: event.target.value })}
            >
              <option value="">Alle</option>
              {MUTATION_STATUSES.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
        )}

        {supports('actionType') && (
          <label className={styles.filter} htmlFor="mutation-filter-action-type">
            Soort
            <select
              id="mutation-filter-action-type"
              value={draft.actionType}
              onChange={(event) => applyFilters({ ...draft, actionType: event.target.value })}
            >
              <option value="">Alle</option>
              {MUTATION_ACTION_TYPES.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
        )}

        {supports('batchId') && (
          <label className={styles.filter} htmlFor="mutation-filter-batch-id">
            Batch
            <input
              id="mutation-filter-batch-id"
              type="text"
              inputMode="numeric"
              value={draft.batchId}
              onChange={(event) => setDraft({ ...draft, batchId: event.target.value })}
            />
          </label>
        )}

        {supports('statusReason') && (
          <label className={styles.filter} htmlFor="mutation-filter-status-reason">
            Statusreden
            <input
              id="mutation-filter-status-reason"
              type="text"
              value={draft.statusReason}
              onChange={(event) => setDraft({ ...draft, statusReason: event.target.value })}
            />
          </label>
        )}

        {supports('identityHash') && (
          <label className={styles.filter} htmlFor="mutation-filter-identity-hash">
            Wijzigingsgroep (identityHash)
            <input
              id="mutation-filter-identity-hash"
              type="text"
              value={draft.identityHash}
              onChange={(event) => setDraft({ ...draft, identityHash: event.target.value })}
            />
          </label>
        )}

        <button type="submit" className={styles.filterButton}>
          Filteren
        </button>
        <button
          type="button"
          className={styles.filterButton}
          onClick={() => applyFilters(EMPTY_FILTERS)}
        >
          Filters wissen
        </button>
      </form>

      {batchIdInvalid && (
        <p className={styles.notice}>
          "{applied.batchId}" is geen geldig batchnummer; er wordt niet op batch gefilterd.
        </p>
      )}
      {query.statusReason !== undefined && (
        <p className={styles.notice}>
          Statusreden filtert op exacte, hoofdlettergevoelige gelijkheid; een onbekende reden geeft een
          lege lijst.
        </p>
      )}
      {query.identityHash !== undefined && (
        <p className={styles.notice}>
          Gefilterd op wijzigingsgroep <code>{query.identityHash}</code>. De server bepaalt de groep; er
          wordt niets in de browser gegroepeerd.
        </p>
      )}

      {toolbar?.({ filter, listedCount, reload: list.reload })}

      {list.error !== null && <ErrorBanner error={list.error} />}
      {list.loading && current === null && <p className={styles.loading}>Bezig met laden…</p>}

      {current !== null && (
        <>
          <DataTable
            columns={columns}
            rows={current.content}
            rowKey={(row) => row.id}
            emptyMessage={emptyMessage}
          />
          <Pager
            page={current.page}
            size={current.size}
            totalElements={current.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}

      <ConfirmDialog
        open={pendingAction !== null}
        title={
          pendingAction === null
            ? ''
            : (pendingAction.action.confirmTitle?.(pendingAction.row) ??
              `${pendingAction.action.label} — mutatie ${pendingAction.row.id}`)
        }
        body={pendingAction === null ? undefined : pendingAction.action.confirmBody?.(pendingAction.row)}
        reasonRequirement={pendingRequirement}
        variant={pendingAction?.action.variant === 'danger' ? 'danger' : 'primary'}
        confirmLabel={pendingAction?.action.label ?? 'Bevestigen'}
        pending={runner.pending}
        error={runner.error !== null ? <ErrorBanner error={runner.error} /> : undefined}
        onConfirm={handleConfirm}
        onCancel={() => {
          setPendingAction(null);
          runner.reset();
        }}
      />

      {actor === '' && actions.length > 0 && (
        <p className={styles.notice}>
          Er is nog geen naam ingevuld; een beslissing vraagt die in de bevestiging opnieuw.
        </p>
      )}
    </div>
  );
}
