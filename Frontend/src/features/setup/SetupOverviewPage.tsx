/**
 * `/setup` — scherm 1a, alleen-lezen inrichtingsboom (S1-F1, `docs/decisions.md` 2026-09-27
 * "scherm 1a/1b"): bronorganisatie → definitie → revisie/koppeling.
 *
 * De boom wordt **client-side** opgebouwd uit de drie vlagloze S1-B1-lijstendpoints
 * (`GET /source-organisations`, `GET /definitions`, `GET /definitions/{id}/revisions`) plus het
 * additieve `importDefinitionId`-filter op `GET /import-links` — NIET via het (nog steeds
 * vlaggedekte) `GET /setup/overview`. Elk niveau laadt lazy: een kind-lijst wordt pas bevraagd
 * zodra de gebruiker een rij uitklapt (het kindcomponent wordt dan pas gemount).
 *
 * Taken-niveau bestaat in deze slice niet (geen vlagloos leesendpoint voor taken vandaag): op
 * koppelingsniveau staat in plaats daarvan een vaste, expliciete tekst — bewust niet meegenomen,
 * niet stilzwijgend weggelaten.
 *
 * Volledig alleen-lezen: geen enkele schrijfactie op dit scherm.
 */

import { useState } from 'react';
import * as setupApi from '../../api/setup.ts';
import * as importLinksApi from '../../api/importLinks.ts';
import { PERMISSION_READ } from '../../api/types.ts';
import type { DefinitionRow, ImportLinkRow, RevisionRow, SourceOrganisationRow } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { usePermissionGate } from '../../actor/permissions.ts';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import styles from './SetupOverviewPage.module.css';

const TASKS_UNAVAILABLE_TEXT =
  'Taken zijn alleen zichtbaar met de setup-API-vlag aan (ontwikkelscherm).';

const SOURCE_ORGANISATION_TYPE_LABELS: Record<string, string> = {
  SUPPLIER: 'Leverancier',
  PURCHASING_ASSOCIATION: 'Aankoopvereniging',
};

const DEFINITION_USAGE_TYPE_LABELS: Record<string, string> = {
  OWN_DEFINITION: 'Eigen definitie',
  REUSABLE_TEMPLATE: 'Herbruikbaar sjabloon',
};

function ExpandButton({ expanded, onToggle }: { expanded: boolean; onToggle: () => void }) {
  return (
    <button type="button" className={styles.expandButton} aria-expanded={expanded} onClick={onToggle}>
      {expanded ? '▾ Inklappen' : '▸ Uitklappen'}
    </button>
  );
}

/** Toont de actieve revisie van een definitie — `null` is expliciet "geen actieve revisie", nooit "#0". */
function ActiveRevision({ activeRevisionId }: { activeRevisionId: number | null }) {
  if (activeRevisionId === null) {
    return <span className={styles.noActiveRevision}>geen actieve revisie</span>;
  }
  return <span>actieve revisie: #{activeRevisionId}</span>;
}

/** Koppelingenlijst (lazy) van één definitie. Taken-niveau: vaste tekst, geen endpoint vandaag. */
function ImportLinksList({ definitionId }: { definitionId: number }) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const key = `setup-import-links:${definitionId}:${page}:${size}`;
  const links = useQuery(key, (signal) =>
    importLinksApi.listImportLinks({ importDefinitionId: definitionId, page, size }, signal),
  );

  return (
    <div className={styles.childSection}>
      <h4 className={styles.childTitle}>Koppelingen</h4>
      {links.error !== null && <ErrorBanner error={links.error} />}
      {links.loading && links.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {links.data !== null && (
        <>
          {links.data.content.length === 0 ? (
            <p className={styles.empty}>Geen koppelingen voor deze definitie.</p>
          ) : (
            <ul className={styles.list}>
              {links.data.content.map((link: ImportLinkRow) => (
                <li key={link.id} className={styles.listItem}>
                  <div className={styles.linkRow}>
                    <span className={styles.code}>{link.code}</span>
                    <span>{link.name}</span>
                    <span>
                      leverancier {link.supplierCode} · bibliotheek {link.libraryCode}
                    </span>
                    <span>{link.active ? 'actief' : 'inactief'}</span>
                  </div>
                  <p className={styles.tasksUnavailable} role="note">
                    {TASKS_UNAVAILABLE_TEXT}
                  </p>
                </li>
              ))}
            </ul>
          )}
          <Pager
            page={links.data.page}
            size={links.data.size}
            totalElements={links.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}
    </div>
  );
}

/** Revisielijst (lazy) van één definitie. */
function RevisionsList({ definitionId }: { definitionId: number }) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const key = `setup-revisions:${definitionId}:${page}:${size}`;
  const revisions = useQuery(key, (signal) => setupApi.listDefinitionRevisions(definitionId, { page, size }, signal));

  return (
    <div className={styles.childSection}>
      <h4 className={styles.childTitle}>Revisies</h4>
      {revisions.error !== null && <ErrorBanner error={revisions.error} />}
      {revisions.loading && revisions.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {revisions.data !== null && (
        <>
          {revisions.data.content.length === 0 ? (
            <p className={styles.empty}>Geen revisies voor deze definitie.</p>
          ) : (
            <ul className={styles.list}>
              {revisions.data.content.map((revision: RevisionRow) => (
                <li key={revision.id} className={styles.listItem}>
                  <span>Revisie #{revision.revisionNumber}</span>
                  <StatusBadge status={revision.status} />
                </li>
              ))}
            </ul>
          )}
          <Pager
            page={revisions.data.page}
            size={revisions.data.size}
            totalElements={revisions.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}
    </div>
  );
}

/** Eén definitierij; klapt lazy open naar revisies + koppelingen. */
function DefinitionNode({ definition }: { definition: DefinitionRow }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <li className={styles.listItem}>
      <div className={styles.nodeRow}>
        <ExpandButton expanded={expanded} onToggle={() => setExpanded((v) => !v)} />
        <span className={styles.code}>{definition.code}</span>
        <span>{definition.name}</span>
        <span>{DEFINITION_USAGE_TYPE_LABELS[definition.usageType] ?? definition.usageType}</span>
        <ActiveRevision activeRevisionId={definition.activeRevisionId} />
      </div>
      {expanded && (
        <div className={styles.children}>
          <RevisionsList definitionId={definition.id} />
          <ImportLinksList definitionId={definition.id} />
        </div>
      )}
    </li>
  );
}

/** Definitielijst (lazy) van één bronorganisatie. */
function DefinitionsList({ sourceOrganisationId }: { sourceOrganisationId: number }) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const key = `setup-definitions:${sourceOrganisationId}:${page}:${size}`;
  const definitions = useQuery(key, (signal) =>
    setupApi.listDefinitions({ sourceOrganisationId, page, size }, signal),
  );

  return (
    <div className={styles.childSection}>
      <h3 className={styles.childTitle}>Definities</h3>
      {definitions.error !== null && <ErrorBanner error={definitions.error} />}
      {definitions.loading && definitions.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {definitions.data !== null && (
        <>
          {definitions.data.content.length === 0 ? (
            <p className={styles.empty}>Geen definities voor deze bronorganisatie.</p>
          ) : (
            <ul className={styles.list}>
              {definitions.data.content.map((definition) => (
                <DefinitionNode key={definition.id} definition={definition} />
              ))}
            </ul>
          )}
          <Pager
            page={definitions.data.page}
            size={definitions.data.size}
            totalElements={definitions.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}
    </div>
  );
}

/** Eén bronorganisatierij; klapt lazy open naar de definitielijst. */
function OrganisationNode({ organisation }: { organisation: SourceOrganisationRow }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <li className={styles.listItem}>
      <div className={styles.nodeRow}>
        <ExpandButton expanded={expanded} onToggle={() => setExpanded((v) => !v)} />
        <span className={styles.code}>{organisation.code}</span>
        <span>{organisation.name}</span>
        <span>{SOURCE_ORGANISATION_TYPE_LABELS[organisation.type] ?? organisation.type}</span>
        <span>{organisation.active ? 'actief' : 'inactief'}</span>
      </div>
      {expanded && (
        <div className={styles.children}>
          <DefinitionsList sourceOrganisationId={organisation.id} />
        </div>
      )}
    </li>
  );
}

export function SetupOverviewPage() {
  const readGate = usePermissionGate(PERMISSION_READ);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);

  const key = `setup-source-organisations:${page}:${size}`;
  const organisations = useQuery(key, (signal) => setupApi.listSourceOrganisations({ page, size }, signal));

  if (!readGate.allowed) {
    return (
      <div className={styles.page}>
        <p role="alert">{readGate.reason}</p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Inrichting</h1>
      <p className={styles.intro}>
        Alleen-lezen overzicht van bronorganisaties, importdefinities, revisies en koppelingen. Klik op
        &laquo;Uitklappen&raquo; om een niveau lazy te laden.
      </p>

      {organisations.error !== null && <ErrorBanner error={organisations.error} />}
      {organisations.loading && organisations.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {organisations.data !== null && (
        <>
          {organisations.data.content.length === 0 ? (
            <p className={styles.empty}>Geen bronorganisaties gevonden.</p>
          ) : (
            <ul className={styles.list} data-testid="source-organisations">
              {organisations.data.content.map((organisation) => (
                <OrganisationNode key={organisation.id} organisation={organisation} />
              ))}
            </ul>
          )}
          <Pager
            page={organisations.data.page}
            size={organisations.data.size}
            totalElements={organisations.data.totalElements}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </>
      )}
    </div>
  );
}
