/**
 * E3 — de secties onder het Velden-grid van "Bestand en velden" (mock `docs/mock/setup-redesign-mock.html`, beslissingslog
 * 2026-10-23 "Nieuwe bewerkervaring"): de ankerchips bovenaan, Filters als zinnen (met in Bewerken Wijzigen, Verwijderen,
 * Filter toevoegen en Uitsluitingsgroep toevoegen), Vertalingen (overzicht per veld; wijzigen gebeurt bij het veld) en de
 * alleen-lezen secties Drempels en Invulpunten. Bestandsopbouw blijft `FileSection` van `RevisionReadView`; het bewerken
 * van Bestandsopbouw, Drempels en Invulpunten volgt in E4.
 */

import { useRef, useState, type ReactNode } from 'react';
import type { FilterView, MappingView, RevisionDetail } from '../../api/types.ts';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import button from '../../components/Button.module.css';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { termLabel } from '../../terms/index.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { FILTER_TEXT, SECTION_TEXT } from '../../terms/setupEditor.ts';
import { Term } from '../../terms/Term.tsx';
import { AddFilterGroupAction } from './FilterGroupDialog.tsx';
import { groupFilters, type FilterListEntry } from './filterGroupModel.ts';
import { entrySentence, fieldCount, sentenceText, translationCount } from './filterSentenceModel.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { FieldKeyTerm, bool, keepsSourceOnMiss, show } from './revisionDetailParts.tsx';
import { isSetupApiDisabledError, SETUP_WRITE_API_DISABLED_MESSAGE } from './setupWriteFlag.ts';
import editor from './FieldsEditor.module.css';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

/** De ids van de secties waar de ankerchips naartoe scrollen. */
export const SECTION_IDS = {
  fields: 'ed-velden',
  filters: 'ed-filters',
  translations: 'ed-vertalingen',
  file: 'ed-bestand',
  thresholds: 'ed-drempels',
  bookmarks: 'ed-invulpunten',
} as const;

type SectionKey = keyof typeof SECTION_IDS;

function scrollToSection(key: SectionKey) {
  const target = document.getElementById(SECTION_IDS[key]);
  if (target === null) {
    return;
  }
  target.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  // Een kop is geen tabstop, maar de focus mag er wel naartoe: een toetsenbordgebruiker landt zo in de sectie.
  target.querySelector<HTMLElement>('h3')?.focus({ preventScroll: true });
}

export function AnchorChips({ revision }: { revision: RevisionDetail }) {
  const items: Array<[SectionKey, string]> = [
    ['fields', SECTION_TEXT.chips.fields(fieldCount(revision))],
    ['filters', SECTION_TEXT.chips.filters(revision.filters.length)],
    ['translations', SECTION_TEXT.chips.translations(translationCount(revision))],
    ['file', SECTION_TEXT.chips.file],
    ['thresholds', SECTION_TEXT.chips.thresholds],
    ['bookmarks', SECTION_TEXT.chips.bookmarks(revision.bookmarks.length)],
  ];
  return (
    <nav className={editor.anchorChips} aria-label={SECTION_TEXT.navLabel} data-testid="anchor-chips">
      {items.map(([key, label]) => (
        <button
          key={key}
          type="button"
          className={`${editor.chip} ${editor.chipButton}`}
          data-testid={`anchor-${key}`}
          onClick={() => scrollToSection(key)}
        >
          {label}
        </button>
      ))}
    </nav>
  );
}

// --- Filters ---

export type FiltersSectionProps = {
  revision: RevisionDetail;
  /** Bewerken (alleen op een concept): Wijzigen, Verwijderen, Filter toevoegen en Uitsluitingsgroep toevoegen. */
  editing: boolean;
  onAdd?: (opener: HTMLElement) => void;
  onEdit?: (filter: FilterView, opener: HTMLElement) => void;
  /** Na een geslaagde verwijdering: melding en herladen. */
  onChanged: () => void;
  /** Enkel herladen (de groepsdialoog meldt zelf; een deels mislukte verwijdering ook). */
  onReload: () => void;
};

function entryLabel(entry: FilterListEntry): string {
  return entry.kind === 'single' ? FILTER_TEXT.rowLabel(entry.filter.sequenceNumber) : FILTER_TEXT.groupLabel(entry.group);
}

export function FiltersSection({ revision, editing, onAdd, onEdit, onChanged, onReload }: FiltersSectionProps) {
  const referenceKind = revision.structureFieldReferenceKind;
  const entries = groupFilters(revision.filters);
  const [removing, setRemoving] = useState<FilterListEntry | null>(null);
  const [partial, setPartial] = useState<{ done: number; total: number } | null>(null);
  const done = useRef(0);

  // 204 levert geen body: `true` is het teken van een geslaagde verwijdering (zoals DeleteChildRowAction).
  const remover = useAction(async (entry: FilterListEntry) => {
    const rows = entry.kind === 'single' ? [entry.filter] : entry.filters;
    done.current = 0;
    for (const row of rows) {
      await setupRevisionsApi.deleteFilter(revision.id, row.id);
      done.current += 1;
    }
    return true as const;
  });

  async function confirmRemove() {
    if (removing === null) {
      return;
    }
    const total = removing.kind === 'single' ? 1 : removing.filters.length;
    setPartial(null);
    const result = await remover.execute(removing);
    if (result === undefined) {
      // Mislukt: bij een groep kunnen al enkele voorwaarden weg zijn; dan herlaadt het scherm en staat dat in de melding.
      if (done.current > 0) {
        setPartial({ done: done.current, total });
        onReload();
      }
      return;
    }
    setRemoving(null);
    onChanged();
  }

  function closeRemove() {
    setRemoving(null);
    setPartial(null);
    remover.reset();
  }

  const removeError =
    remover.error === null ? undefined : (
      <>
        {partial !== null && (
          <p role="alert" data-testid="filter-delete-partial">
            {FILTER_TEXT.removePartial(partial.done, partial.total)}
          </p>
        )}
        {isSetupApiDisabledError(remover.error) ? (
          <FlagOffNotice message={SETUP_WRITE_API_DISABLED_MESSAGE} />
        ) : (
          <ErrorBanner error={remover.error} />
        )}
      </>
    );

  return (
    <section
      id={SECTION_IDS.filters}
      className={styles.vdSection}
      aria-labelledby="vd-filters"
      data-testid="read-filters"
    >
      <h3 id="vd-filters" tabIndex={-1}>
        {TEXT.sections.filters}
      </h3>
      {entries.length === 0 ? (
        <p className={styles.rowMeta}>{FILTER_TEXT.none}</p>
      ) : (
        <ul className={styles.rowList} data-testid="filter-sentences">
          {entries.map((entry) => {
            const sentence = entrySentence(entry, referenceKind);
            const label = entryLabel(entry);
            const key = entry.kind === 'single' ? `filter-${entry.filter.id}` : `group-${entry.group}`;
            return (
              <li key={key} data-testid={key}>
                <div className={editor.sentence}>
                  <span data-testid="filter-sentence">{sentence.text}</span>
                  {sentence.suffix !== null && (
                    <span className={editor.sentenceSuffix} data-testid="filter-suffix">
                      ({sentence.suffix})
                    </span>
                  )}
                </div>
                {editing && (
                  <span className={styles.btnRow}>
                    {entry.kind === 'single' && (
                      <button
                        type="button"
                        className={`${button.secondary} ${button.small}`}
                        aria-label={FILTER_TEXT.changeLabel(label)}
                        data-testid={`filter-change-${entry.filter.id}`}
                        onClick={(event) => onEdit?.(entry.filter, event.currentTarget)}
                      >
                        {FILTER_TEXT.change}
                      </button>
                    )}
                    <button
                      type="button"
                      className={`${button.dangerOutlined} ${button.small}`}
                      aria-label={FILTER_TEXT.removeLabel(label)}
                      data-testid={`filter-remove-${key}`}
                      onClick={() => {
                        remover.reset();
                        setPartial(null);
                        setRemoving(entry);
                      }}
                    >
                      {FILTER_TEXT.remove}
                    </button>
                  </span>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {editing && (
        <div className={editor.sectionActions}>
          <button
            type="button"
            className={`${button.secondary} ${button.small}`}
            data-testid="filter-add"
            onClick={(event) => onAdd?.(event.currentTarget)}
          >
            {FILTER_TEXT.add}
          </button>
          <AddFilterGroupAction revision={revision} onChanged={onReload} />
        </div>
      )}
      {removing !== null && (
        <ConfirmDialog
          open
          title={removing.kind === 'single' ? FILTER_TEXT.removeTitle : FILTER_TEXT.removeGroupTitle}
          body={
            <div>
              <p>
                <strong>{sentenceText(entrySentence(removing, referenceKind))}</strong>
              </p>
              <p>{removing.kind === 'single' ? FILTER_TEXT.removeBody : FILTER_TEXT.removeGroupBody}</p>
              <p className={styles.rowMeta}>{FILTER_TEXT.removeBookmarkNote}</p>
            </div>
          }
          reasonRequirement="none"
          confirmLabel={FILTER_TEXT.removeConfirm}
          cancelLabel={FILTER_TEXT.removeKeep}
          variant="danger"
          pending={remover.pending}
          error={removeError}
          onConfirm={() => void confirmRemove()}
          onCancel={closeRemove}
        />
      )}
    </section>
  );
}

// --- Vertalingen ---

export type TranslationsSectionProps = {
  revision: RevisionDetail;
  editing: boolean;
  onEditMapping?: (code: string, opener: HTMLElement) => void;
};

export function TranslationsSection({ revision, editing, onEditMapping }: TranslationsSectionProps) {
  const head = TEXT.translationHead;
  const withMap: MappingView[] = revision.mappings.filter((mapping) => (mapping.valueMap?.length ?? 0) > 0);
  return (
    <section
      id={SECTION_IDS.translations}
      className={styles.vdSection}
      aria-labelledby="vd-translations"
      data-testid="read-translations"
    >
      <h3 id="vd-translations" tabIndex={-1}>
        {TEXT.sections.translations}
      </h3>
      {withMap.length === 0 ? (
        <p className={styles.rowMeta}>{SECTION_TEXT.translationsNone}</p>
      ) : (
        withMap.map((mapping) => (
          <div key={mapping.id} className={styles.mapCard} data-testid={`translation-${mapping.targetFieldCode}`}>
            <h4>
              <FieldKeyTerm code={mapping.targetFieldCode} />
            </h4>
            <p className={styles.rowMeta}>
              {SECTION_TEXT.translationMeta(mapping.valueMap?.length ?? 0, keepsSourceOnMiss(mapping))}
            </p>
            <div className={styles.tableWrap}>
              <table className={`${styles.table} ${styles.stack}`}>
                <thead>
                  <tr>
                    <th>{head.source}</th>
                    <th>{head.target}</th>
                  </tr>
                </thead>
                <tbody>
                  {(mapping.valueMap ?? []).map((entry, position) => (
                    <tr key={`${position}-${entry.source}`}>
                      <td data-label={head.source}>{show(entry.source)}</td>
                      <td data-label={head.target}>{show(entry.target)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {editing && (
              <p>
                <button
                  type="button"
                  className={`${button.secondary} ${button.small}`}
                  aria-label={SECTION_TEXT.changeAtFieldLabel(termLabel('fieldKey', mapping.targetFieldCode, 'Ander veld'))}
                  data-testid={`translation-change-${mapping.targetFieldCode}`}
                  onClick={(event) => onEditMapping?.(mapping.targetFieldCode, event.currentTarget)}
                >
                  {SECTION_TEXT.changeAtField}
                </button>
              </p>
            )}
          </div>
        ))
      )}
    </section>
  );
}

// --- Drempels en Invulpunten (alleen-lezen tot E4) ---

function Kv({ field, children }: { field: string; children: ReactNode }) {
  return (
    <>
      <dt>
        <Term domain="revisionField" code={field} />
      </dt>
      <dd>{children}</dd>
    </>
  );
}

export function ThresholdsSection({ revision }: { revision: RevisionDetail }) {
  return (
    <section
      id={SECTION_IDS.thresholds}
      className={styles.vdSection}
      aria-labelledby="vd-thresholds"
      data-testid="read-thresholds"
    >
      <h3 id="vd-thresholds" tabIndex={-1}>
        {SECTION_TEXT.chips.thresholds}
      </h3>
      <details className={editor.thresholds}>
        <summary>{SECTION_TEXT.thresholdsSummary}</summary>
        <dl className={styles.dl}>
          <Kv field="creationThresholdAbsolute">{String(revision.creationThresholdAbsolute)}</Kv>
          <Kv field="creationThresholdSharePercent">{show(revision.creationThresholdSharePercent)}</Kv>
          <Kv field="maxCriticalSharePercent">{show(revision.maxCriticalSharePercent)}</Kv>
          <Kv field="maxRejectedSharePercent">{show(revision.maxRejectedSharePercent)}</Kv>
          <Kv field="bulkIncidentSharePercent">{show(revision.bulkIncidentSharePercent)}</Kv>
          <Kv field="basePriceZeroAllowed">{bool(revision.basePriceZeroAllowed)}</Kv>
          <Kv field="basePriceNegativeAllowed">{bool(revision.basePriceNegativeAllowed)}</Kv>
          <Kv field="priceDeviationPercent">{show(revision.priceDeviationPercent)}</Kv>
        </dl>
      </details>
    </section>
  );
}

export function BookmarksSection({ revision }: { revision: RevisionDetail }) {
  return (
    <section
      id={SECTION_IDS.bookmarks}
      className={styles.vdSection}
      aria-labelledby="vd-bookmarks"
      data-testid="read-bookmarks"
    >
      <h3 id="vd-bookmarks" tabIndex={-1}>
        {SECTION_TEXT.bookmarksHeading}
      </h3>
      {revision.bookmarks.length === 0 ? (
        <p className={styles.rowMeta}>{SECTION_TEXT.bookmarksNone}</p>
      ) : (
        <>
          <ul className={styles.rowList}>
            {revision.bookmarks.map((bookmark) => (
              <li key={bookmark.id}>
                <div className={styles.rowMain}>{bookmark.label}</div>
              </li>
            ))}
          </ul>
          <p className={styles.rowMeta}>{SECTION_TEXT.bookmarksNote}</p>
        </>
      )}
    </section>
  );
}
