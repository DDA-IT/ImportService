/**
 * Tab "Bestand en velden" van het importdefinitiedetail (R2): de actieve versie (of anders het nieuwste concept) staat
 * meteen leesbaar op het scherm, zonder klik; een andere versie kies je met de keuzelijst of via `?versie=`. "Bewerken"
 * opent de bestaande bewerkpaden, precies als op het leveranciersdetail (`DefinitionFieldsPanel`). Voor een
 * herbruikbare importdefinitie staat onder het leesbeeld ook de tabel met invulpunten van die versie.
 */

import { useSearchParams } from 'react-router-dom';
import * as templatesApi from '../../api/templates.ts';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { describeIssue } from '../../terms/IssueCodeTerm.tsx';
import { DEFINITIONS_TEXT } from '../../terms/importDefinitions.ts';
import { Term } from '../../terms/Term.tsx';
import { DefinitionFieldsPanel } from '../setup/DefinitionFieldsPanel.tsx';
import { FlagOffNotice } from '../setup/FlagOffNotice.tsx';
import { parseId } from '../setup/supplierRoutes.ts';
import { isSetupApiDisabledError } from './setupApiFlag.ts';
import { useDefinitionDetailContext } from './TemplateDetailPage.tsx';
import styles from '../setup/SetupScreens.module.css';

const TEXT = DEFINITIONS_TEXT;
const PLACEHOLDERS = TEXT.placeholders;

function whereFilled(scope: string): string {
  if (scope === 'DEFINITION') {
    return PLACEHOLDERS.whereDefinition;
  }
  if (scope === 'LINK') {
    return PLACEHOLDERS.whereLink;
  }
  return PLACEHOLDERS.whereOther;
}

/** De invulpunten van één versie van een herbruikbare importdefinitie (alleen-lezen; achter de inrichtings-API-vlag). */
function PlaceholdersSection({ definitionId, revisionId, revisionNumber }: { definitionId: number; revisionId: number; revisionNumber: number }) {
  const bookmarkSet = useQuery(`definition-placeholders:${definitionId}:${revisionId}`, (signal) =>
    templatesApi.getBookmarkSet(definitionId, revisionId, signal),
  );
  const columns = PLACEHOLDERS.columns;
  return (
    <section className={styles.vdSection} aria-labelledby="vd-placeholders" data-testid="definition-placeholders">
      <h3 id="vd-placeholders">{PLACEHOLDERS.title(revisionNumber)}</h3>
      <p className={styles.rowMeta}>{PLACEHOLDERS.explain}</p>
      {bookmarkSet.error !== null &&
        (isSetupApiDisabledError(bookmarkSet.error) ? (
          <FlagOffNotice message={TEXT.usedBy.flagOff} className={styles.alert} />
        ) : (
          <ErrorBanner error={bookmarkSet.error} />
        ))}
      {bookmarkSet.loading && bookmarkSet.data === null && <p className={styles.loading}>{TEXT.loading}</p>}
      {bookmarkSet.data !== null && (
        <>
          {bookmarkSet.data.problems.length > 0 && (
            <ul className={styles.problemList} role="alert" data-testid="bookmark-problems" aria-label={PLACEHOLDERS.problems}>
              {bookmarkSet.data.problems.map((problem, index) => {
                const described = describeIssue(problem.code);
                const label = bookmarkSet.data?.bookmarks.find((bookmark) => bookmark.name === problem.bookmarkName)?.label ?? null;
                return (
                  <li
                    key={`${problem.code}-${problem.bookmarkName}-${index}`}
                    title={`technische code: ${problem.code} · ${problem.bookmarkName} · ${problem.message}`}
                  >
                    {described.label}: {described.uitleg}
                    {label !== null && <> (invulpunt «{label}»)</>}
                  </li>
                );
              })}
            </ul>
          )}
          {bookmarkSet.data.bookmarks.length === 0 ? (
            <p className={styles.empty}>{PLACEHOLDERS.none}</p>
          ) : (
            <div className={styles.tableWrap}>
              <table className={`${styles.table} ${styles.stack}`}>
                <thead>
                  <tr>
                    <th>{columns.name}</th>
                    <th>{columns.kind}</th>
                    <th>{columns.where}</th>
                    <th>{columns.required}</th>
                  </tr>
                </thead>
                <tbody data-testid="placeholder-rows">
                  {bookmarkSet.data.bookmarks.map((bookmark) => (
                    <tr key={bookmark.id}>
                      {/* De technische naam staat in de tooltip (V7), niet als zichtbare tekst. */}
                      <td data-label={columns.name} title={`Technische naam: ${bookmark.name}`}>
                        <strong>{bookmark.label}</strong>
                      </td>
                      <td data-label={columns.kind}>
                        <Term domain="bookmarkDataType" code={bookmark.dataType} unknownLabel="Ander soort waarde" />
                      </td>
                      <td data-label={columns.where}>{whereFilled(bookmark.valueScope)}</td>
                      <td data-label={columns.required}>{bookmark.required ? PLACEHOLDERS.yes : PLACEHOLDERS.no}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}

export function DefinitionFieldsTab() {
  const { bundle, reusable, reload } = useDefinitionDetailContext();
  const [params, setParams] = useSearchParams();
  const { definition, revisions } = bundle;

  return (
    <div data-testid="definition-fields">
      {reusable && <p className={styles.rowMeta}>{TEXT.reusableNote}</p>}
      <DefinitionFieldsPanel
        definition={definition}
        revisions={revisions}
        requestedRevisionId={parseId(params.get('versie'))}
        onSelectRevision={(revisionId) => setParams(new URLSearchParams({ versie: String(revisionId) }), { replace: true })}
        editRequested={params.get('bewerken') === '1'}
        addressKey={params.toString()}
        onChanged={reload}
        extra={
          reusable
            ? (revision) => (
                <PlaceholdersSection
                  key={revision.id}
                  definitionId={definition.id}
                  revisionId={revision.id}
                  revisionNumber={revision.revisionNumber}
                />
              )
            : undefined
        }
      />
    </div>
  );
}
