/**
 * E3 — de secties onder het Velden-grid van "Bestand en velden" (mock `docs/mock/setup-redesign-mock.html`, beslissingslog
 * 2026-10-23 "Nieuwe bewerkervaring"): de ankerchips bovenaan, Filters als zinnen (met in Bewerken Wijzigen, Verwijderen,
 * Filter toevoegen en Uitsluitingsgroep toevoegen), Vertalingen (overzicht per veld; wijzigen gebeurt bij het veld) en de
 * alleen-lezen secties Drempels en Invulpunten. Bestandsopbouw blijft `FileSection` van `RevisionReadView`; het bewerken
 * van Bestandsopbouw en Drempels staat sinds E4 in `InlineSectionEditors`; E6 voegt de alleen-lezen sectie Geavanceerd toe.
 */

import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { FilterView, MappingView, RevisionDetail, RevisionView } from '../../api/types.ts';
import * as setupRevisionsApi from '../../api/setupRevisions.ts';
import button from '../../components/Button.module.css';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { formatDateTime } from '../../format.ts';
import { termLabel } from '../../terms/index.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { ADVANCED_TEXT, FILTER_TEXT, INLINE_EDIT_TEXT, SECTION_TEXT } from '../../terms/setupEditor.ts';
import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { Term } from '../../terms/Term.tsx';
import { DiffChip, RemovedStrip } from './DiffParts.tsx';
import { ThresholdsEditForm } from './InlineSectionEditors.tsx';
import type { ChipKind, RevisionDiff } from './revisionDiffModel.ts';
import { AddFilterGroupAction } from './FilterGroupDialog.tsx';
import { groupFilters, type FilterListEntry } from './filterGroupModel.ts';
import { entrySentence, fieldCount, sentenceText, translationCount } from './filterSentenceModel.ts';
import { FlagOffNotice } from './FlagOffNotice.tsx';
import { FieldKeyTerm, Row, bool, keepsSourceOnMiss, show } from './revisionDetailParts.tsx';
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
  advanced: 'ed-geavanceerd',
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
  /** E4: het verschil met de basisversie (chips Nieuw/Gewijzigd en "Niet meer in deze versie"). */
  diff?: RevisionDiff | null;
};

function entryLabel(entry: FilterListEntry): string {
  return entry.kind === 'single' ? FILTER_TEXT.rowLabel(entry.filter.sequenceNumber) : FILTER_TEXT.groupLabel(entry.group);
}

export function FiltersSection({ revision, editing, onAdd, onEdit, onChanged, onReload, diff = null }: FiltersSectionProps) {
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
            const chip: ChipKind | undefined =
              diff === null
                ? undefined
                : entry.kind === 'single'
                  ? diff.filterKinds[entry.filter.id]
                  : entry.filters.every((row) => diff.filterKinds[row.id] === 'added')
                    ? 'added'
                    : entry.filters.some((row) => diff.filterKinds[row.id] !== undefined)
                      ? 'changed'
                      : undefined;
            const key = entry.kind === 'single' ? `filter-${entry.filter.id}` : `group-${entry.group}`;
            return (
              <li key={key} data-testid={key}>
                <div className={editor.sentence}>
                  <span data-testid="filter-sentence">{sentence.text}</span>
                  <DiffChip kind={chip} testId="filter-diff-chip" />
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
      <RemovedStrip entries={diff?.removedFilters ?? []} testId="removed-filters" />
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

// --- Drempels (E4: bewerkbaar) en Invulpunten (alleen-lezen) ---

function Kv({ field, diff, children }: { field: string; diff?: RevisionDiff | null; children: ReactNode }) {
  return (
    <>
      <dt>
        <Term domain="revisionField" code={field} />
      </dt>
      <dd>
        {children}
        <DiffChip kind={diff?.scalarKinds[field]} testId={`diff-chip-${field}`} />
      </dd>
    </>
  );
}

const THRESHOLD_KEYS = [
  'creationThresholdAbsolute',
  'creationThresholdSharePercent',
  'maxCriticalSharePercent',
  'maxRejectedSharePercent',
  'bulkIncidentSharePercent',
  'basePriceZeroAllowed',
  'basePriceNegativeAllowed',
  'priceDeviationPercent',
  'priceDeviationSeverity',
  'priceDerivationTolerance',
] as const;

export type ThresholdsSectionProps = {
  revision: RevisionDetail;
  /** Bewerken (alleen op een concept): [Wijzigen] opent de invoervelden. */
  editing?: boolean;
  diff?: RevisionDiff | null;
  /** Na een geslaagde PATCH: de aanroeper toont de melding en herlaadt. */
  onSaved?: (result: RevisionView, message: string) => void;
};

export function ThresholdsSection({ revision, editing = false, diff = null, onSaved }: ThresholdsSectionProps) {
  // Ingeklapt, tenzij een waarde afwijkt van de basisversie.
  const differs = diff !== null && THRESHOLD_KEYS.some((key) => diff.scalarKinds[key] !== undefined);
  const [open, setOpen] = useState(differs);
  // De basisversie laadt apart en kan later binnenkomen dan het detail: dan klapt de sectie alsnog open.
  useEffect(() => {
    if (differs) {
      setOpen(true);
    }
  }, [differs]);
  const [changing, setChanging] = useState(false);
  const showForm = changing && editing;
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
      <details
        className={editor.thresholds}
        open={open}
        data-testid="thresholds-details"
        onToggle={(event) => setOpen((event.currentTarget as HTMLDetailsElement).open)}
      >
        <summary>{SECTION_TEXT.thresholdsSummary}</summary>
        {showForm ? (
          <ThresholdsEditForm
            key={revision.updatedAt ?? 'new'}
            revision={revision}
            onCancel={() => setChanging(false)}
            onSaved={(result) => {
              setChanging(false);
              onSaved?.(result, INLINE_EDIT_TEXT.savedThresholds);
            }}
          />
        ) : (
          <>
            <dl className={styles.dl}>
              <Kv field="creationThresholdAbsolute" diff={diff}>{String(revision.creationThresholdAbsolute)}</Kv>
              <Kv field="creationThresholdSharePercent" diff={diff}>{show(revision.creationThresholdSharePercent)}</Kv>
              <Kv field="maxCriticalSharePercent" diff={diff}>{show(revision.maxCriticalSharePercent)}</Kv>
              <Kv field="maxRejectedSharePercent" diff={diff}>{show(revision.maxRejectedSharePercent)}</Kv>
              <Kv field="bulkIncidentSharePercent" diff={diff}>{show(revision.bulkIncidentSharePercent)}</Kv>
              <Kv field="basePriceZeroAllowed" diff={diff}>{bool(revision.basePriceZeroAllowed)}</Kv>
              <Kv field="basePriceNegativeAllowed" diff={diff}>{bool(revision.basePriceNegativeAllowed)}</Kv>
              <Kv field="priceDeviationPercent" diff={diff}>{show(revision.priceDeviationPercent)}</Kv>
              <Kv field="priceDeviationSeverity" diff={diff}>
                {termLabel('severity', revision.priceDeviationSeverity, 'Andere ernst')}
              </Kv>
              <Kv field="priceDerivationTolerance" diff={diff}>{show(revision.priceDerivationTolerance)}</Kv>
            </dl>
            {editing && (
              <div className={editor.sectionActions}>
                <button
                  type="button"
                  className={`${button.secondary} ${button.small}`}
                  aria-label={INLINE_EDIT_TEXT.changeThresholds}
                  data-testid="thresholds-change"
                  onClick={() => {
                    setOpen(true);
                    setChanging(true);
                  }}
                >
                  {INLINE_EDIT_TEXT.change}
                </button>
              </div>
            )}
          </>
        )}
      </details>
    </section>
  );
}

export function BookmarksSection({ revision }: { revision: RevisionDetail }) {
  const labels = new Map(revision.bookmarks.map((bookmark) => [bookmark.name, bookmark.label] as const));
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
          <ul className={styles.rowList} data-testid="revision-bookmarks">
            {revision.bookmarks.map((bookmark) => (
              <li key={bookmark.id}>
                {/* De technische naam staat in de tooltip (V7), niet als zichtbare tekst. */}
                <div className={styles.rowMain} title={`Technische naam: ${bookmark.name}`}>
                  {bookmark.label}
                </div>
                <div className={styles.rowMeta}>
                  <Term domain="bookmarkDataType" code={bookmark.dataType} unknownLabel="Ander soort waarde" /> ·{' '}
                  <Term domain="bookmarkScope" code={bookmark.valueScope} unknownLabel="Andere geldigheid" /> ·{' '}
                  {bookmark.required ? SECTION_TEXT.bookmarkRequired : SECTION_TEXT.bookmarkOptional}
                </div>
              </li>
            ))}
          </ul>
          <p className={styles.rowMeta} data-testid="bookmark-boundary-note">
            {SECTION_TEXT.bookmarksNote}
          </p>
        </>
      )}
      {revision.bookmarkValues.length > 0 && (
        <>
          <h4>{SECTION_TEXT.bookmarkValuesHeading}</h4>
          <ul className={styles.rowList} data-testid="revision-bookmark-values">
            {revision.bookmarkValues.map((value) => (
              <li key={value.bookmarkName}>
                <div className={styles.rowMain} title={`Technische naam: ${value.bookmarkName}`}>
                  {labels.get(value.bookmarkName) ?? SECTION_TEXT.bookmarkValueNoDeclaration}: {show(value.valueText)}
                </div>
                <div className={styles.rowMeta}>
                  <Term domain="bookmarkDataType" code={value.dataType} unknownLabel="Ander soort waarde" /> ·{' '}
                  {SECTION_TEXT.bookmarkFilled(show(value.filledBy), formatDateTime(value.filledAt))}
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}

// --- Geavanceerd (E6, alleen-lezen) ---

/**
 * Wat het oude "Meer opties" nog toonde en het grid niet: de kritiek-overrules per veld, de herkomst en goedkeuring en de
 * technische herkomstgegevens (versienummers en vingerafdrukken). Ingeklapt; enkel lezen (er is geen verwijderendpoint voor
 * een kritiek-overrule en bookmarkdeclaraties blijven bewust alleen-lezen).
 */
export function AdvancedSection({ revision }: { revision: RevisionDetail }) {
  return (
    <section
      id={SECTION_IDS.advanced}
      className={styles.vdSection}
      aria-labelledby="vd-advanced"
      data-testid="read-advanced"
    >
      <h3 id="vd-advanced" tabIndex={-1}>
        {ADVANCED_TEXT.heading}
      </h3>
      <details className={editor.thresholds} data-testid="advanced-details">
        <summary>{ADVANCED_TEXT.summary}</summary>
        <h4>{ADVANCED_TEXT.criticalityHeading(revision.fieldCriticalities.length)}</h4>
        {revision.fieldCriticalities.length === 0 ? (
          <p className={styles.rowMeta}>{ADVANCED_TEXT.criticalityNone}</p>
        ) : (
          <ul className={styles.rowList} data-testid="revision-field-criticalities">
            {revision.fieldCriticalities.map((row) => (
              <li key={row.fieldKey}>
                <div className={styles.rowMain}>
                  <FieldKeyTerm code={row.fieldKey} />:{' '}
                  <Term domain="criticality" code={row.criticality} unknownLabel="Andere kritiek" />
                </div>
              </li>
            ))}
          </ul>
        )}
        <p className={styles.rowMeta}>{ADVANCED_TEXT.criticalityNote}</p>

        <h4>{ADVANCED_TEXT.originHeading}</h4>
        <dl data-testid="revision-origin">
          <Row label={ADVANCED_TEXT.createdAt} value={formatDateTime(revision.createdAt)} />
          <Row label={ADVANCED_TEXT.createdBy} value={show(revision.createdBy)} />
          <Row label={ADVANCED_TEXT.updatedAt} value={formatDateTime(revision.updatedAt)} />
          <Row label={ADVANCED_TEXT.approvedAt} value={formatDateTime(revision.approvedAt)} />
          <Row label={ADVANCED_TEXT.approvedBy} value={show(revision.approvedBy)} />
        </dl>
        <p className={styles.rowMeta}>{ADVANCED_TEXT.hashNote}</p>
        <TechnicalDetails
          items={[
            { name: 'accessVersion', value: String(revision.accessVersion) },
            { name: 'accessConfigHash', value: show(revision.accessConfigHash) },
            { name: 'structureVersion', value: String(revision.structureVersion) },
            { name: 'structureConfigHash', value: show(revision.structureConfigHash) },
            { name: 'recordRulesVersion', value: String(revision.recordRulesVersion) },
            { name: 'recordRulesConfigHash', value: show(revision.recordRulesConfigHash) },
            { name: 'compositeConfigHash', value: show(revision.compositeConfigHash) },
            { name: 'maxCriticalRecords (niet meer in gebruik)', value: String(revision.maxCriticalRecords) },
            { name: 'maxRejectedRecords (niet meer in gebruik)', value: show(revision.maxRejectedRecords) },
          ]}
        />
      </details>
    </section>
  );
}
