/**
 * `/templates/:definitionId` — scherm 1b, sjabloon-/materialisatiewizard (S1-F2 alleen-lezen deel,
 * S1-F3 schrijfdeel; `docs/decisions.md` 2026-09-27 "scherm 1a/1b").
 *
 * Laadt eerst de revisies van dit sjabloon via het BESTAANDE, vlagloze `GET /definitions/{id}/revisions`
 * (`api/setup.ts`, S1-B1) zodat de gebruiker een sjabloonrevisie kan kiezen — geen duplicaat-endpoint.
 * Een `DRAFT`-revisie wordt getoond maar uitgeschakeld met een reden (dezelfde "uitgeschakeld-met-reden"
 * conventie als elders, bv. `TASK_NOT_MANUAL` op `UploadPage.tsx`): een DRAFT-sjabloonrevisie heeft haar
 * eigen screening/validatie nog niet doorlopen en is dus niet materialiseerbaar.
 *
 * Bij het kiezen van een revisie: `GET /templates/{definitionId}/revisions/{revisionId}/bookmarks` —
 * dat pad, de materialisatiehistoriek eronder, het materialiseren zelf en de bookmarkwaarden van een
 * koppeling blijven wél achter `catalogimport.setup-api.enabled` en tonen bij een 404-zonder-code de
 * eigen melding uit `setupApiFlag.ts`.
 *
 * S1-F3 voegt drie dingen toe, zonder het leesdeel te herschrijven:
 * - de materialisatiehistoriek wordt één niveau hoger geladen, omdat `MaterialiseForm` diezelfde rijen
 *   als keuzelijst voor hergebruik nodig heeft (één verzoek, geen tweede kopie van dezelfde lijst);
 * - `MaterialiseForm` onder de bookmarkset van de gekozen, niet-DRAFT revisie;
 * - een koppeling-detailweergave: per gematerialiseerde definitie haar koppelingen (via het vlagloze
 *   `GET /import-links?importDefinitionId=`), en per koppeling haar LINK-bookmarkwaarden met de
 *   wijzigactie. Na een geslaagde materialisatie springt het scherm meteen naar de nieuwe koppeling.
 */
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import * as importLinksApi from '../../api/importLinks.ts';
import * as setupApi from '../../api/setup.ts';
import * as templatesApi from '../../api/templates.ts';
import type { MaterialisedDefinitionView, PageResult, RevisionRow } from '../../api/types.ts';
import type { UseQueryResult } from '../../hooks/useQuery.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { Pager } from '../../components/Pager.tsx';
import { StatusBadge } from '../../components/StatusBadge.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { LinkBookmarkValuesSection } from './LinkBookmarkValuesSection.tsx';
import { MaterialiseForm } from './MaterialiseForm.tsx';
import { isSetupApiDisabledError, SETUP_API_DISABLED_MESSAGE } from './setupApiFlag.ts';
import styles from './TemplateDetailPage.module.css';

const DRAFT_REASON = 'een DRAFT-sjabloonrevisie is nog niet materialiseerbaar';

/** De koppeling waarvan de bookmarkwaarden open staan. */
type SelectedLink = { linkId: number; linkCode: string };

/**
 * Bookmarks + niet-blokkerende `problems`-lijst van één gekozen revisie, en — zodra die invulset er is —
 * het materialisatieformulier (S1-F3). Het formulier staat hier omdat het exact dezelfde invulset nodig
 * heeft: zonder de bookmarks van díe revisie kan het niet weten welke velden er gevraagd worden.
 */
function BookmarkSetSection({
  definitionId,
  revision,
  materialisations,
  onMaterialised,
}: {
  definitionId: number;
  revision: RevisionRow;
  materialisations: MaterialisedDefinitionView[];
  onMaterialised: (definitionIdOfLink: number, link: SelectedLink) => void;
}) {
  const key = `template-bookmarks:${definitionId}:${revision.id}`;
  const bookmarkSet = useQuery(key, (signal) => templatesApi.getBookmarkSet(definitionId, revision.id, signal));

  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>Bookmarks — revisie #{revision.id}</h2>

      {bookmarkSet.error !== null &&
        (isSetupApiDisabledError(bookmarkSet.error) ? (
          <p role="alert" className={styles.flagOff}>
            {SETUP_API_DISABLED_MESSAGE}
          </p>
        ) : (
          <ErrorBanner error={bookmarkSet.error} />
        ))}
      {bookmarkSet.loading && bookmarkSet.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {bookmarkSet.data !== null && (
        <>
          {bookmarkSet.data.problems.length > 0 && (
            <ul className={styles.problems} role="alert" data-testid="bookmark-problems">
              {bookmarkSet.data.problems.map((problem, index) => (
                <li key={`${problem.code}-${problem.bookmarkName}-${index}`}>
                  {problem.message}
                  <span className={styles.problemDetail}>
                    ({problem.code} · {problem.bookmarkName})
                  </span>
                </li>
              ))}
            </ul>
          )}
          {bookmarkSet.data.bookmarks.length === 0 ? (
            <p className={styles.empty}>Geen bookmarks gedeclareerd op deze revisie.</p>
          ) : (
            <ul className={styles.list}>
              {bookmarkSet.data.bookmarks.map((bookmark) => (
                <li key={bookmark.id} className={styles.listItem}>
                  <span className={styles.code}>{bookmark.name}</span>
                  <span>{bookmark.label}</span>
                  <span>
                    {bookmark.dataType} / {bookmark.valueScope}
                  </span>
                  <span>{bookmark.required ? 'verplicht' : 'optioneel'}</span>
                </li>
              ))}
            </ul>
          )}

          <MaterialiseForm
            definitionId={definitionId}
            templateRevisionId={revision.id}
            templateRevisionNumber={revision.revisionNumber}
            templateRevisionStatus={revision.status}
            bookmarks={bookmarkSet.data.bookmarks}
            materialisations={materialisations}
            onMaterialised={(result) =>
              onMaterialised(result.definitionId, {
                linkId: result.importLinkId,
                linkCode: result.importLinkCode,
              })
            }
          />
        </>
      )}
    </section>
  );
}

/**
 * Materialisatiehistoriek: welke definities al uit dit sjabloon gematerialiseerd zijn. De query zelf
 * leeft in `TemplateDetailPage` (S1-F3) omdat `MaterialiseForm` dezelfde rijen nodig heeft.
 */
function MaterialisationHistorySection({
  query,
  onPageChange,
  onSizeChange,
  onShowLinks,
}: {
  query: UseQueryResult<PageResult<MaterialisedDefinitionView>>;
  onPageChange: (next: number) => void;
  onSizeChange: (next: number) => void;
  onShowLinks: (definitionId: number) => void;
}) {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>Materialisatiehistoriek</h2>

      {query.error !== null &&
        (isSetupApiDisabledError(query.error) ? (
          <p role="alert" className={styles.flagOff}>
            {SETUP_API_DISABLED_MESSAGE}
          </p>
        ) : (
          <ErrorBanner error={query.error} />
        ))}
      {query.loading && query.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {query.data !== null && (
        <>
          {query.data.content.length === 0 ? (
            <p className={styles.empty}>Dit sjabloon is nog niet gematerialiseerd.</p>
          ) : (
            <ul className={styles.list} data-testid="materialisations">
              {query.data.content.map((row) => (
                <li key={row.definitionId} className={styles.listItem}>
                  <span className={styles.code}>{row.definitionCode}</span>
                  <span>{row.definitionName}</span>
                  <span>
                    {row.definitionRevisionNumber !== null
                      ? `revisie #${row.definitionRevisionNumber}`
                      : 'geen herkomstrevisie'}
                  </span>
                  <span>{row.importLinkCount} koppeling(en)</span>
                  <span>
                    {row.shareable
                      ? 'deelbaar'
                      : `niet deelbaar${row.blockingBookmarkName !== null ? ` (${row.blockingBookmarkName})` : ''}`}
                  </span>
                  <button
                    type="button"
                    className={styles.revisionButton}
                    data-testid={`show-links-${row.definitionId}`}
                    onClick={() => onShowLinks(row.definitionId)}
                  >
                    Koppelingen
                  </button>
                </li>
              ))}
            </ul>
          )}
          <Pager
            page={query.data.page}
            size={query.data.size}
            totalElements={query.data.totalElements}
            onPageChange={onPageChange}
            onSizeChange={onSizeChange}
          />
        </>
      )}
    </section>
  );
}

/**
 * De koppelingen van één gematerialiseerde definitie, via het vlagloze `GET /import-links`
 * (`api/importLinks.ts`, S1-B1-uitbreiding met `importDefinitionId`). Het kiezen van een koppeling opent
 * haar LINK-bookmarkwaarden.
 */
function DefinitionLinksSection({
  definitionId,
  selectedLinkId,
  onSelect,
}: {
  definitionId: number;
  selectedLinkId: number | null;
  onSelect: (link: SelectedLink) => void;
}) {
  const key = `definition-links:${definitionId}`;
  const links = useQuery(key, (signal) =>
    importLinksApi.listImportLinks({ importDefinitionId: definitionId, size: 200 }, signal),
  );

  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>Koppelingen van definitie #{definitionId}</h2>
      {links.error !== null && <ErrorBanner error={links.error} />}
      {links.loading && links.data === null && <p className={styles.loading}>Bezig met laden…</p>}
      {links.data !== null &&
        (links.data.content.length === 0 ? (
          <p className={styles.empty}>Deze definitie heeft nog geen koppelingen.</p>
        ) : (
          <ul className={styles.list} data-testid="definition-links">
            {links.data.content.map((row) => (
              <li key={row.id} className={styles.listItem}>
                <button
                  type="button"
                  className={
                    selectedLinkId === row.id
                      ? `${styles.revisionButton} ${styles.revisionSelected}`
                      : styles.revisionButton
                  }
                  data-testid={`select-link-${row.id}`}
                  onClick={() => onSelect({ linkId: row.id, linkCode: row.code })}
                >
                  {row.code}
                </button>
                <span>{row.name}</span>
                <span>
                  {row.supplierCode} / {row.libraryCode}
                </span>
                <span>{row.active ? 'actief' : 'niet actief'}</span>
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}

export function TemplateDetailPage() {
  const params = useParams<{ definitionId: string }>();
  const definitionId = Number(params.definitionId);

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [selectedRevision, setSelectedRevision] = useState<RevisionRow | null>(null);

  const [historyPage, setHistoryPage] = useState(0);
  const [historySize, setHistorySize] = useState(50);
  const [linksDefinitionId, setLinksDefinitionId] = useState<number | null>(null);
  const [selectedLink, setSelectedLink] = useState<SelectedLink | null>(null);

  const revisionsKey = `template-revisions:${definitionId}:${page}:${size}`;
  const revisions = useQuery(revisionsKey, (signal) =>
    setupApi.listDefinitionRevisions(definitionId, { page, size }, signal),
  );

  const historyKey = `template-materialisations:${definitionId}:${historyPage}:${historySize}`;
  const materialisations = useQuery(historyKey, (signal) =>
    templatesApi.listMaterialisations(definitionId, { page: historyPage, size: historySize }, signal),
  );

  return (
    <div className={styles.page}>
      <p className={styles.breadcrumb}>
        <Link to="/templates">Sjablonen</Link>
      </p>
      <h1 className={styles.title}>Sjabloon #{definitionId}</h1>

      <section className={styles.section}>
        <h2 className={styles.sectionTitle}>Revisie kiezen</h2>
        {revisions.error !== null && <ErrorBanner error={revisions.error} />}
        {revisions.loading && revisions.data === null && <p className={styles.loading}>Bezig met laden…</p>}
        {revisions.data !== null && (
          <>
            {revisions.data.content.length === 0 ? (
              <p className={styles.empty}>Geen revisies voor dit sjabloon.</p>
            ) : (
              <ul className={styles.list} data-testid="template-revisions">
                {revisions.data.content.map((revision: RevisionRow) => {
                  const isDraft = revision.status === 'DRAFT';
                  return (
                    <li key={revision.id} className={styles.listItem}>
                      <button
                        type="button"
                        className={
                          selectedRevision?.id === revision.id
                            ? `${styles.revisionButton} ${styles.revisionSelected}`
                            : styles.revisionButton
                        }
                        disabled={isDraft}
                        title={isDraft ? DRAFT_REASON : undefined}
                        onClick={() => setSelectedRevision(revision)}
                      >
                        Revisie #{revision.revisionNumber}
                      </button>
                      <StatusBadge status={revision.status} />
                      {isDraft && <p className={styles.draftReason}>{DRAFT_REASON}</p>}
                    </li>
                  );
                })}
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
      </section>

      {selectedRevision !== null && (
        <BookmarkSetSection
          definitionId={definitionId}
          revision={selectedRevision}
          materialisations={materialisations.data?.content ?? []}
          onMaterialised={(materialisedDefinitionId, link) => {
            // Expliciete invalidatie (§5): de historiek verandert door deze actie, dus herladen we ze
            // zelf in plaats van op een cache te vertrouwen.
            materialisations.reload();
            setLinksDefinitionId(materialisedDefinitionId);
            setSelectedLink(link);
          }}
        />
      )}

      <MaterialisationHistorySection
        query={materialisations}
        onPageChange={setHistoryPage}
        onSizeChange={(next) => {
          setHistorySize(next);
          setHistoryPage(0);
        }}
        onShowLinks={(id) => {
          setLinksDefinitionId(id);
          setSelectedLink(null);
        }}
      />

      {linksDefinitionId !== null && (
        <DefinitionLinksSection
          definitionId={linksDefinitionId}
          selectedLinkId={selectedLink?.linkId ?? null}
          onSelect={setSelectedLink}
        />
      )}

      {selectedLink !== null && (
        <LinkBookmarkValuesSection linkId={selectedLink.linkId} linkCode={selectedLink.linkCode} />
      )}
    </div>
  );
}
