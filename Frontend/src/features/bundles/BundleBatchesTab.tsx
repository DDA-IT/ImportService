/**
 * `/bundles/:bundleId/batches` — leden en kandidaten, zie
 * `docs/design/frontend-scherm3-bundel-design.md` §8, §10.2.
 *
 * Leden (`GET /bundles/{id}/batches`) tonen **ook** de verwijderde leden mét hun `removedBy`/
 * `removedReason` — dat is audit en wordt niet verborgen. Toevoegen is aan de backendkant
 * alles-of-niets; de UI zegt dat vóór de aanroep en laat bij een 409 de selectie ongemoeid, zodat de
 * gebruiker er één kan afvinken en opnieuw kan proberen. Verwijderen vereist altijd een reden.
 */

import { useState } from 'react';
import * as bundlesApi from '../../api/bundles.ts';
import * as importLinksApi from '../../api/importLinks.ts';
import type { BundleBatchRow, BundleCandidate, ImportLinkRow } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { useAction } from '../../hooks/useAction.ts';
import { useActor, validateActorName } from '../../actor/ActorContext.tsx';
import { DataTable, type DataTableColumn } from '../../components/DataTable.tsx';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { bundleActionGate } from './bundlePolicy.ts';
import { useBundleDetailContext } from './BundleDetailPage.tsx';
import styles from './BundleBatchesTab.module.css';

function formatDateTime(iso: string | null): string {
  return iso === null ? '—' : new Date(iso).toLocaleString('nl-BE');
}

function Count({ value }: { value: number | null }) {
  if (value === null) {
    return (
      <span className={styles.count} title="niet vastgesteld">
        —
      </span>
    );
  }
  return <>{value}</>;
}

/**
 * Geeft het leesbare label voor een koppeling terug via de bestaande
 * `GET /import-links`-API. Valt terug op `#id` als het label niet gevonden wordt.
 * Zie §16.5 van het scherm-3-ontwerp.
 */
function getImportLinkLabel(importLinkId: number, links: ImportLinkRow[] | undefined): string {
  if (!links) {
    return `#${importLinkId}`;
  }
  const link = links.find((l) => l.id === importLinkId);
  if (!link) {
    return `#${importLinkId}`;
  }
  return link.code;
}

export function BundleBatchesTab() {
  const { bundle, reloadBundle } = useBundleDetailContext();
  const { actor } = useActor();

  const [membersPage, setMembersPage] = useState(0);
  const [membersSize, setMembersSize] = useState(50);
  const [candidatesPage, setCandidatesPage] = useState(0);
  const [candidatesSize, setCandidatesSize] = useState(50);
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set());
  const [removeTarget, setRemoveTarget] = useState<BundleBatchRow | null>(null);

  const membersKey = `bundle-batches:${bundle.id}:${membersPage}:${membersSize}`;
  const members = useQuery(membersKey, (signal) =>
    bundlesApi.batches(bundle.id, { page: membersPage, size: membersSize }, signal),
  );

  const candidatesKey = `bundle-candidates:${candidatesPage}:${candidatesSize}`;
  const candidates = useQuery(candidatesKey, (signal) =>
    bundlesApi.candidates({ page: candidatesPage, size: candidatesSize }, signal),
  );

  const linksKey = 'import-links:all';
  const links = useQuery(linksKey, (signal) => importLinksApi.listImportLinks({ size: 200 }, signal));

  const addGate = bundleActionGate(bundle.status, 'ADD_BATCHES');
  const removeGate = bundleActionGate(bundle.status, 'REMOVE_BATCH');

  const addAction = useAction(() =>
    bundlesApi.addBatches(bundle.id, { batchIds: [...selected], addedBy: actor }),
  );
  const removeAction = useAction((batchId: number, reason: string, removedBy: string) =>
    bundlesApi.removeBatch(bundle.id, batchId, { removedBy, reason }),
  );

  function toggleSelected(batchId: number) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(batchId)) {
        next.delete(batchId);
      } else {
        next.add(batchId);
      }
      return next;
    });
  }

  async function handleAdd() {
    if (selected.size === 0) {
      return;
    }
    const result = await addAction.execute();
    if (result === undefined) {
      // 409 of andere fout: selectie blijft ongewijzigd zodat de gebruiker er één kan afvinken en
      // opnieuw kan proberen (§10.2).
      return;
    }
    setSelected(new Set());
    members.reload();
    candidates.reload();
    reloadBundle();
  }

  async function handleRemoveConfirm(input: { actor: string; reason: string | null }) {
    if (removeTarget === null || input.reason === null) {
      return;
    }
    const result = await removeAction.execute(removeTarget.batchId, input.reason, input.actor);
    if (result === undefined) {
      return;
    }
    setRemoveTarget(null);
    removeAction.reset();
    members.reload();
    candidates.reload();
    reloadBundle();
  }

  const memberColumns: readonly DataTableColumn<BundleBatchRow>[] = [
    { key: 'batchId', header: 'Batch', render: (row) => row.batchId },
    { key: 'importLinkId', header: 'Koppeling', render: (row) => getImportLinkLabel(row.importLinkId, links.data?.content) },
    { key: 'batchStatus', header: 'Batchstatus', render: (row) => <StatusBadge status={row.batchStatus} /> },
    {
      key: 'batchContentMutationCount',
      header: 'Mutaties',
      render: (row) => <Count value={row.batchContentMutationCount} />,
      align: 'right',
    },
    { key: 'addedBy', header: 'Toegevoegd door', render: (row) => row.addedBy },
    { key: 'addedAt', header: 'Toegevoegd op', render: (row) => formatDateTime(row.addedAt) },
    {
      key: 'active',
      header: 'Actief',
      render: (row) => (row.active ? 'Ja' : 'Nee'),
    },
    {
      key: 'removed',
      header: 'Verwijderd',
      render: (row) =>
        row.active
          ? '—'
          : `${row.removedBy ?? '—'} op ${formatDateTime(row.removedAt)}${
              row.removedReason !== null ? ` — reden: ${row.removedReason}` : ''
            }`,
    },
    {
      key: 'remove',
      header: '',
      render: (row) =>
        row.active ? (
          <button
            type="button"
            className={styles.rowButton}
            disabled={!removeGate.allowed}
            title={removeGate.allowed ? undefined : removeGate.reason}
            onClick={() => setRemoveTarget(row)}
          >
            Verwijderen
          </button>
        ) : (
          '—'
        ),
    },
  ];

  const candidateColumns: readonly DataTableColumn<BundleCandidate>[] = [
    {
      key: 'select',
      header: '',
      render: (row) => (
        <input
          type="checkbox"
          checked={selected.has(row.batchId)}
          disabled={!addGate.allowed}
          onChange={() => toggleSelected(row.batchId)}
          aria-label={`Batch ${row.batchId} selecteren`}
        />
      ),
    },
    { key: 'batchId', header: 'Batch', render: (row) => row.batchId },
    { key: 'importLinkId', header: 'Koppeling', render: (row) => getImportLinkLabel(row.importLinkId, links.data?.content) },
    { key: 'status', header: 'Status', render: (row) => <StatusBadge status={row.status} /> },
    {
      key: 'validationResult',
      header: 'Eindoordeel',
      render: (row) => (row.validationResult === null ? 'Niet vastgesteld' : <StatusBadge status={row.validationResult} />),
    },
    {
      key: 'contentMutationCount',
      header: 'Mutaties',
      render: (row) => <Count value={row.contentMutationCount} />,
      align: 'right',
    },
    { key: 'finishedAt', header: 'Afgerond op', render: (row) => formatDateTime(row.finishedAt) },
  ];

  const actorError = validateActorName(actor);

  return (
    <div className={styles.tab}>
      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Leden</h2>
        {members.error !== null && <ErrorBanner error={members.error} />}
        {members.loading && members.data === null && <p className={styles.loading}>Bezig met laden…</p>}
        {members.data !== null && (
          <>
            <DataTable
              columns={memberColumns}
              rows={members.data.content}
              rowKey={(row) => row.id}
              emptyMessage="Deze bundel heeft nog geen leden."
            />
            <Pager
              page={members.data.page}
              size={members.data.size}
              totalElements={members.data.totalElements}
              onPageChange={setMembersPage}
              onSizeChange={(next) => {
                setMembersSize(next);
                setMembersPage(0);
              }}
            />
          </>
        )}
      </section>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Kandidaten toevoegen</h2>
        {!addGate.allowed && <p className={styles.gateReason}>{addGate.reason}</p>}
        <p className={styles.notice}>Weigert één batch, dan wordt er niets toegevoegd.</p>

        {candidates.error !== null && <ErrorBanner error={candidates.error} />}
        {candidates.loading && candidates.data === null && <p className={styles.loading}>Bezig met laden…</p>}
        {candidates.data !== null && (
          <>
            <DataTable
              columns={candidateColumns}
              rows={candidates.data.content}
              rowKey={(row) => row.batchId}
              emptyMessage="Geen kandidaten gevonden."
            />
            <Pager
              page={candidates.data.page}
              size={candidates.data.size}
              totalElements={candidates.data.totalElements}
              onPageChange={setCandidatesPage}
              onSizeChange={(next) => {
                setCandidatesSize(next);
                setCandidatesPage(0);
              }}
            />
          </>
        )}

        <div className={styles.addRow}>
          <p>
            Toegevoegd door: <strong>{actor === '' ? '(nog niet ingevuld)' : actor}</strong> — wijzig dit
            hierboven bij "Ingelogd als".
          </p>
          {actorError !== null && <p className={styles.gateReason}>{actorError}</p>}
          {addAction.error !== null && <ErrorBanner error={addAction.error} />}
          <button
            type="button"
            className={styles.addButton}
            disabled={!addGate.allowed || selected.size === 0 || actorError !== null || addAction.pending}
            title={addGate.allowed ? undefined : addGate.reason}
            onClick={handleAdd}
          >
            {addAction.pending ? 'Bezig…' : `${selected.size} geselecteerde batch(es) toevoegen`}
          </button>
        </div>
      </section>

      <ConfirmDialog
        open={removeTarget !== null}
        title={`Batch ${removeTarget?.batchId ?? ''} verwijderen`}
        body="Deze batch draagt mogelijk al beslissingen; verwijderen met beslissingen wordt door de server geweigerd."
        reasonRequirement="required"
        variant="danger"
        pending={removeAction.pending}
        error={removeAction.error !== null ? <ErrorBanner error={removeAction.error} /> : undefined}
        onConfirm={handleRemoveConfirm}
        onCancel={() => {
          setRemoveTarget(null);
          removeAction.reset();
        }}
      />
    </div>
  );
}
