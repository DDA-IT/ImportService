/**
 * Het blok "Bestand en velden" van één importdefinitie: de gekozen versie (de actieve, anders het nieuwste concept) staat
 * meteen leesbaar op het scherm, met een keuzelijst voor een andere versie, een conceptmelding en de knop "Bewerken"
 * die de bestaande `RevisionDetailSection` opent (bewerken van een concept, extra velden en filters verwijderen,
 * activeren met de verplichte waarschuwing). Gedeeld door het leveranciersdetail en het importdefinitiedetail (R2); de
 * aanroeper bepaalt hoe een andere versie gekozen wordt (adresbalk) en wat er na een wijziging herlaadt.
 */

import { useEffect, useState, type ReactNode } from 'react';
import * as setupApi from '../../api/setup.ts';
import type { DefinitionRow, RevisionRow } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { term } from '../../terms/index.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { RevisionDetailSection } from './RevisionDetailSection.tsx';
import { RevisionReadView } from './RevisionReadView.tsx';
import { defaultRevisionOf } from './setupDashboardModel.ts';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

export type DefinitionFieldsPanelProps = {
  definition: DefinitionRow;
  revisions: readonly RevisionRow[];
  /** De gevraagde versie uit de adresbalk (anders de actieve, anders het nieuwste concept). */
  requestedRevisionId: number | null;
  onSelectRevision: (revisionId: number) => void;
  /** `true` als het adres vraagt om het bewerkblok meteen te openen (`bewerken=1`). */
  editRequested: boolean;
  /** Verandert bij elke adreswijziging, zodat een adres met `bewerken=1` het blok ook opent als het tabblad al open stond. */
  addressKey: string;
  /** Na een wijziging in het bewerkblok: de ouder herlaadt de versielijst. */
  onChanged: () => void;
  /** Extra inhoud onder het leesbeeld (bv. de invulpunten van een herbruikbare importdefinitie). */
  extra?: (revision: RevisionRow) => ReactNode;
};

export function DefinitionFieldsPanel({
  definition,
  revisions,
  requestedRevisionId,
  onSelectRevision,
  editRequested,
  addressKey,
  onChanged,
  extra,
}: DefinitionFieldsPanelProps) {
  const [editOpen, setEditOpen] = useState(editRequested);
  useEffect(() => {
    if (editRequested) {
      setEditOpen(true);
    }
  }, [editRequested, addressKey]);
  // Telt op na een wijziging in het bewerkblok, zodat het leesbeeld hieronder opnieuw laadt.
  const [readVersion, setReadVersion] = useState(0);

  const revision = defaultRevisionOf(revisions, requestedRevisionId);
  if (revision === null) {
    return <p className={styles.empty}>{TEXT.noVersions}</p>;
  }

  return (
    <section className={`${pageCss.card} ${pageCss.cardPad}`} aria-labelledby="fields-heading">
      <h2 id="fields-heading" className={pageCss.subtitle} tabIndex={-1}>
        {TEXT.headingFor(
          definition.name,
          revision.revisionNumber,
          term('revisionStatus', revision.status).label.toLowerCase(),
        )}
      </h2>
      <div className={styles.vdHead}>
        <label className={styles.selectLabel}>
          {TEXT.versionSelectLabel}
          <select
            value={revision.id}
            onChange={(event) => {
              setEditOpen(false);
              onSelectRevision(Number(event.target.value));
            }}
            data-testid="version-select"
          >
            {[...revisions]
              .sort((a, b) => b.revisionNumber - a.revisionNumber)
              .map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {TEXT.versionOption(candidate.revisionNumber, term('revisionStatus', candidate.status).label.toLowerCase())}
                </option>
              ))}
          </select>
        </label>
      </div>
      {revision.status === 'DRAFT' && (
        <div className={styles.banner} role="status" data-testid="draft-banner">
          <p>{TEXT.draftBanner}</p>
        </div>
      )}
      <ReadView definitionId={definition.id} revisionId={revision.id} version={readVersion} revisions={revisions} />
      {extra?.(revision)}

      <div className={styles.editBlock}>
        <div className={styles.btnRow}>
          <button
            type="button"
            className={`${button.secondary} ${button.small}`}
            aria-expanded={editOpen}
            aria-controls="supplier-edit-block"
            data-testid="open-edit"
            onClick={() => setEditOpen((open) => !open)}
          >
            {editOpen ? TEXT.closeEdit : TEXT.edit}
          </button>
          <span className={styles.rowMeta}>{TEXT.editHint}</span>
        </div>
        {editOpen && (
          <div id="supplier-edit-block">
            <RevisionDetailSection
              key={revision.id}
              definitionId={definition.id}
              sourceOrganisationCode={definition.sourceOrganisationCode}
              revisionId={revision.id}
              onRevisionChanged={() => {
                setReadVersion((current) => current + 1);
                onChanged();
              }}
            />
          </div>
        )}
      </div>
    </section>
  );
}

function ReadView({
  definitionId,
  revisionId,
  version,
  revisions,
}: {
  definitionId: number;
  revisionId: number;
  version: number;
  revisions: readonly RevisionRow[];
}) {
  const detail = useQuery(`supplier-revision-read:${definitionId}:${revisionId}:${version}`, (signal) =>
    setupApi.getRevisionDetail(definitionId, revisionId, signal),
  );
  // De legacy-herkomst laadt apart en lui: een fout of een trage herkomst blokkeert het leesbeeld nooit.
  const origin = useQuery(`supplier-revision-legacy-origin:${definitionId}:${revisionId}:${version}`, (signal) =>
    setupApi.getRevisionLegacyOrigin(definitionId, revisionId, signal),
  );
  const originRevisionId = origin.data?.originRevisionId ?? null;
  const originVersionNumber =
    originRevisionId === null ? null : (revisions.find((candidate) => candidate.id === originRevisionId)?.revisionNumber ?? null);
  return (
    <>
      {detail.error !== null && <ErrorBanner error={detail.error} />}
      {detail.loading && detail.data === null && <p className={styles.loading}>{TEXT.loading}</p>}
      {detail.data !== null && (
        <RevisionReadView
          revision={detail.data}
          legacyOrigin={origin.data}
          legacyFailed={origin.error !== null}
          legacyOriginVersionNumber={originVersionNumber}
        />
      )}
    </>
  );
}
