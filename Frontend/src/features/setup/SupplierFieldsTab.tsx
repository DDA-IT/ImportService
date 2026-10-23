/**
 * Tab "Bestand en velden" van het leveranciersdetail: de actieve versie (of anders het nieuwste concept, met een
 * melding) van de importdefinitie van de leverancier staat meteen leesbaar op het scherm, zonder klik. Een andere
 * versie kies je met de keuzelijst of via `?versie=`; hebben er meerdere importdefinities, dan staat er een keuzelijst
 * voor de importdefinitie (`?definitie=`).
 *
 * Bewerken blijft waar het was: de knop "Bewerken" opent de bestaande `RevisionDetailSection` (bewerken van een
 * concept, extra velden en filters verwijderen, activeren met de verplichte waarschuwing). Een opvolger maken staat bij
 * het tabblad Versies. `?bewerken=1` opent het bewerkblok meteen.
 */

import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import * as setupApi from '../../api/setup.ts';
import button from '../../components/Button.module.css';
import pageCss from '../../components/Page.module.css';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { term } from '../../terms/index.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { RevisionDetailSection } from './RevisionDetailSection.tsx';
import { RevisionReadView } from './RevisionReadView.tsx';
import { defaultRevisionOf, isOwnDefinition } from './setupDashboardModel.ts';
import { useSupplierDetailContext } from './SupplierDetailPage.tsx';
import { parseId } from './supplierRoutes.ts';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

export function SupplierFieldsTab() {
  const { bundle, reload } = useSupplierDetailContext();
  const [params, setParams] = useSearchParams();
  const editRequested = params.get('bewerken') === '1';
  const [editOpen, setEditOpen] = useState(editRequested);
  // Een adres met `bewerken=1` opent het bewerkblok ook als dit tabblad al getoond werd (ander `versie=`).
  useEffect(() => {
    if (editRequested) {
      setEditOpen(true);
    }
  }, [editRequested, params]);
  // Telt op na een wijziging in het bewerkblok, zodat het leesbeeld hieronder opnieuw laadt.
  const [readVersion, setReadVersion] = useState(0);

  const definitions = [...bundle.supplier.definitions].sort(
    (a, b) => Number(isOwnDefinition(b.definition)) - Number(isOwnDefinition(a.definition)),
  );
  if (definitions.length === 0) {
    return <p className={styles.empty}>{TEXT.noDefinition}</p>;
  }

  const requestedDefinition = parseId(params.get('definitie'));
  const entry = definitions.find((candidate) => candidate.definition.id === requestedDefinition) ?? definitions[0]!;
  const definition = entry.definition;
  const revisions = bundle.revisions[definition.id] ?? [];
  const revision = defaultRevisionOf(revisions, parseId(params.get('versie')));

  function select(next: { definitionId: number; revisionId?: number }) {
    const nextParams = new URLSearchParams();
    nextParams.set('definitie', String(next.definitionId));
    if (next.revisionId !== undefined) {
      nextParams.set('versie', String(next.revisionId));
    }
    setEditOpen(false);
    setParams(nextParams, { replace: true });
  }

  return (
    <div data-testid="supplier-fields">
      {definitions.length > 1 && (
        <p>
          <label className={styles.selectLabel}>
            {TEXT.definitionSelectLabel}
            <select
              value={definition.id}
              onChange={(event) => select({ definitionId: Number(event.target.value) })}
              data-testid="definition-select"
            >
              {definitions.map((candidate) => (
                <option key={candidate.definition.id} value={candidate.definition.id}>
                  {candidate.definition.name}
                </option>
              ))}
            </select>
          </label>
        </p>
      )}
      {!isOwnDefinition(definition) && <p className={styles.rowMeta}>{TEXT.reusableNote}</p>}

      {revision === null ? (
        <p className={styles.empty}>{TEXT.noVersions}</p>
      ) : (
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
                onChange={(event) => select({ definitionId: definition.id, revisionId: Number(event.target.value) })}
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
          <ReadView definitionId={definition.id} revisionId={revision.id} version={readVersion} />

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
                    reload();
                  }}
                />
              </div>
            )}
          </div>
        </section>
      )}
    </div>
  );
}

function ReadView({ definitionId, revisionId, version }: { definitionId: number; revisionId: number; version: number }) {
  const detail = useQuery(`supplier-revision-read:${definitionId}:${revisionId}:${version}`, (signal) =>
    setupApi.getRevisionDetail(definitionId, revisionId, signal),
  );
  return (
    <>
      {detail.error !== null && <ErrorBanner error={detail.error} />}
      {detail.loading && detail.data === null && <p className={styles.loading}>{TEXT.loading}</p>}
      {detail.data !== null && <RevisionReadView revision={detail.data} />}
    </>
  );
}
