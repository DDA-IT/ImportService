/**
 * Het leesbare beeld van één versie van een importdefinitie, voor het tabblad "Bestand en velden" van het
 * leveranciersdetail (R1, mock `vdSection`). E3 (beslissingslog 2026-10-23 "Nieuwe bewerkervaring"): ankerchips bovenaan, dan
 * het Velden-grid (`FieldsGrid`, E1/E2: dezelfde lay-out om te lezen en te bewerken), Filters als zinnen, Vertalingen,
 * Bestandsopbouw, Drempels en Invulpunten (`EditorSections`). Toont enkel wat `RevisionDetail` levert; de woorden komen uit
 * hetzelfde woordenboek en dezelfde bouwstenen (`revisionDetailParts`) als de rest van het scherm. E6: sinds het oude "Meer opties" weg is, staat ook Geavanceerd (kritiek per veld, herkomst, technische details) hier.
 */

import { useState, type ReactNode } from 'react';
import type { FilterView, RevisionDetail, RevisionLegacyOrigin, RevisionView } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { INLINE_EDIT_TEXT } from '../../terms/setupEditor.ts';
import { Term } from '../../terms/Term.tsx';
import { LegacyOriginNotice, LegacyTechnicalDetails, usableLegacyOrigin } from './LegacyOriginParts.tsx';
import { AdvancedSection, AnchorChips, BookmarksSection, FiltersSection, SECTION_IDS, ThresholdsSection, TranslationsSection } from './EditorSections.tsx';
import { DiffChip } from './DiffParts.tsx';
import { FileEditForm } from './InlineSectionEditors.tsx';
import type { RevisionDiff } from './revisionDiffModel.ts';
import editor from './FieldsEditor.module.css';
import { FieldsGrid } from './FieldsGrid.tsx';
import type { CoreRowKey } from './fieldsGridModel.ts';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

function noop() {}

function Kv({ field, diff, scalar, children }: { field: string; diff?: RevisionDiff | null; scalar?: string; children: ReactNode }) {
  return (
    <>
      <dt>
        <Term domain="revisionField" code={field} />
      </dt>
      <dd>
        {children}
        <DiffChip kind={diff?.scalarKinds[scalar ?? field]} testId={`diff-chip-${scalar ?? field}`} />
      </dd>
    </>
  );
}

function Unset({ children }: { children: string }) {
  return <span className={styles.unset}>{children}</span>;
}

export type FileSectionProps = {
  revision: RevisionDetail;
  /** E4: Bewerken (alleen op een concept): [Wijzigen] opent de invoervelden. */
  editing?: boolean;
  diff?: RevisionDiff | null;
  onSaved?: (result: RevisionView, message: string) => void;
};

export function FileSection({ revision, editing = false, diff = null, onSaved }: FileSectionProps) {
  const [changing, setChanging] = useState(false);
  const showForm = changing && editing;
  return (
    <section id={SECTION_IDS.file} className={styles.vdSection} aria-labelledby="vd-file" data-testid="read-file">
      <h3 id="vd-file" tabIndex={-1}>{TEXT.sections.file}</h3>
      {showForm ? (
        <FileEditForm
          key={revision.updatedAt ?? 'new'}
          revision={revision}
          onCancel={() => setChanging(false)}
          onSaved={(result) => {
            setChanging(false);
            onSaved?.(result, INLINE_EDIT_TEXT.savedFile);
          }}
        />
      ) : (
        <>
          <dl className={styles.dl}>
            <Kv field="structureFormat">
              <Term domain="structureFormat" code={revision.structureFormat} unknownLabel="Ander formaat" />
            </Kv>
            <Kv field="delimiter" diff={diff}>
              <span className={styles.code}>{revision.structureDelimiter}</span>
            </Kv>
            <Kv field="quoteChar" diff={diff}>{revision.structureQuoteChar === null ? <Unset>{TEXT.notUsed}</Unset> : revision.structureQuoteChar}</Kv>
            <Kv field="charset" diff={diff}>{revision.structureCharset}</Kv>
            <Kv field="hasHeader" diff={diff}>
              {revision.structureHasHeader ? `${TEXT.yes} (regel ${revision.structureHeaderLineNumber})` : TEXT.no}
            </Kv>
            <Kv field="fieldReferenceKind" diff={diff}>
              <Term domain="fieldReferenceKind" code={revision.structureFieldReferenceKind} unknownLabel="Andere herkenning" />
            </Kv>
            {revision.structureExpectedColumnCount !== null && (
              <Kv field="expectedColumnCount" diff={diff}>{revision.structureExpectedColumnCount}</Kv>
            )}
            <Kv field="deliverySetKind">
              <Term domain="deliverySetKind" code={revision.accessDeliverySetKind} unknownLabel="Andere soort" />
            </Kv>
          </dl>
          {editing && (
            <div className={editor.sectionActions}>
              <button
                type="button"
                className={`${button.secondary} ${button.small}`}
                aria-label={INLINE_EDIT_TEXT.changeFile}
                data-testid="file-change"
                onClick={() => setChanging(true)}
              >
                {INLINE_EDIT_TEXT.change}
              </button>
            </div>
          )}
        </>
      )}
    </section>
  );
}

export type RevisionReadViewProps = {
  revision: RevisionDetail;
  /** De legacy-herkomst van deze versie (lui geladen); afwezig zolang ze er niet is. */
  legacyOrigin?: RevisionLegacyOrigin | null;
  /** `true` als het laden van de herkomst mislukte: een kleine melding, het leesbeeld blijft. */
  legacyFailed?: boolean;
  /** Versienummer van de herkomstversie (voor "geërfd van versie n"). */
  legacyOriginVersionNumber?: number | null;
  /** E1: Bewerken (alleen op een concept): potloden in het grid en de schakelaar in de groepskop. */
  editing?: boolean;
  onEditRow?: (key: CoreRowKey, opener: HTMLElement) => void;
  onEditUppercase?: (opener: HTMLElement) => void;
  /** E2: extra velden in het grid. */
  onEditMapping?: (code: string, opener: HTMLElement) => void;
  onAddField?: (opener: HTMLElement) => void;
  onMapField?: (code: string, opener: HTMLElement) => void;
  /** E3: filters in de sectie Filters. */
  onAddFilter?: (opener: HTMLElement) => void;
  onEditFilter?: (filter: FilterView, opener: HTMLElement) => void;
  /** Na een geslaagde verwijdering van een filter: melding en herladen. */
  onFiltersChanged?: () => void;
  /** Enkel herladen (groepsdialoog, deels mislukte verwijdering). */
  onReload?: () => void;
  /** E4: het verschil met de basisversie (chips Nieuw/Gewijzigd, "Niet meer in deze versie"). */
  diff?: RevisionDiff | null;
  /** E4: Bestandsopbouw of Drempels is bewaard (de melding); de aanroeper herlaadt. */
  onSectionSaved?: (result: RevisionView, message: string) => void;
};

export function RevisionReadView({
  revision,
  legacyOrigin = null,
  legacyFailed = false,
  legacyOriginVersionNumber = null,
  editing = false,
  onEditRow,
  onEditUppercase,
  onEditMapping,
  onAddField,
  onMapField,
  onAddFilter,
  onEditFilter,
  onFiltersChanged,
  onReload,
  diff = null,
  onSectionSaved,
}: RevisionReadViewProps) {
  const legacy = usableLegacyOrigin(legacyOrigin);
  return (
    <div data-testid="revision-read-view">
      <LegacyOriginNotice origin={legacyOrigin} failed={legacyFailed} originVersionNumber={legacyOriginVersionNumber} />
      <AnchorChips revision={revision} />
      <div id={SECTION_IDS.fields}>
        <FieldsGrid
          revision={revision}
          legacy={legacy}
          editing={editing}
          onEditRow={onEditRow}
          onEditUppercase={onEditUppercase}
          onEditMapping={onEditMapping}
          onAddField={onAddField}
          onMapField={onMapField}
          diff={diff}
        />
      </div>
      <FiltersSection
        revision={revision}
        editing={editing}
        onAdd={onAddFilter}
        onEdit={onEditFilter}
        onChanged={onFiltersChanged ?? noop}
        onReload={onReload ?? noop}
        diff={diff}
      />
      <TranslationsSection revision={revision} editing={editing} onEditMapping={onEditMapping} />
      <FileSection revision={revision} editing={editing} diff={diff} onSaved={onSectionSaved} />
      <ThresholdsSection revision={revision} editing={editing} diff={diff} onSaved={onSectionSaved} />
      <BookmarksSection revision={revision} />
      <AdvancedSection revision={revision} />
      {legacy !== null && <LegacyTechnicalDetails origin={legacy} />}
    </div>
  );
}
