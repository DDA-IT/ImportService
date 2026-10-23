/**
 * Het leesbare beeld van één versie van een importdefinitie, voor het tabblad "Bestand en velden" van het
 * leveranciersdetail (R1, mock `vdSection`). E3 (beslissingslog 2026-10-23 "Nieuwe bewerkervaring"): ankerchips bovenaan, dan
 * het Velden-grid (`FieldsGrid`, E1/E2: dezelfde lay-out om te lezen en te bewerken), Filters als zinnen, Vertalingen,
 * Bestandsopbouw, Drempels en Invulpunten (`EditorSections`). Toont enkel wat `RevisionDetail` levert; de woorden komen uit
 * hetzelfde woordenboek en dezelfde bouwstenen als `RevisionDetailSection`.
 */

import type { ReactNode } from 'react';
import type { FilterView, RevisionDetail, RevisionLegacyOrigin } from '../../api/types.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { Term } from '../../terms/Term.tsx';
import { LegacyOriginNotice, LegacyTechnicalDetails, usableLegacyOrigin } from './LegacyOriginParts.tsx';
import { AnchorChips, BookmarksSection, FiltersSection, SECTION_IDS, ThresholdsSection, TranslationsSection } from './EditorSections.tsx';
import { FieldsGrid } from './FieldsGrid.tsx';
import type { CoreRowKey } from './fieldsGridModel.ts';
import styles from './SetupScreens.module.css';

const TEXT = SUPPLIER_TEXT;

function noop() {}

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

function Unset({ children }: { children: string }) {
  return <span className={styles.unset}>{children}</span>;
}

export function FileSection({ revision }: { revision: RevisionDetail }) {
  return (
    <section id={SECTION_IDS.file} className={styles.vdSection} aria-labelledby="vd-file" data-testid="read-file">
      <h3 id="vd-file" tabIndex={-1}>{TEXT.sections.file}</h3>
      <dl className={styles.dl}>
        <Kv field="structureFormat">
          <Term domain="structureFormat" code={revision.structureFormat} unknownLabel="Ander formaat" />
        </Kv>
        <Kv field="delimiter">
          <span className={styles.code}>{revision.structureDelimiter}</span>
        </Kv>
        <Kv field="quoteChar">{revision.structureQuoteChar === null ? <Unset>{TEXT.notUsed}</Unset> : revision.structureQuoteChar}</Kv>
        <Kv field="charset">{revision.structureCharset}</Kv>
        <Kv field="hasHeader">
          {revision.structureHasHeader ? `${TEXT.yes} (regel ${revision.structureHeaderLineNumber})` : TEXT.no}
        </Kv>
        <Kv field="fieldReferenceKind">
          <Term domain="fieldReferenceKind" code={revision.structureFieldReferenceKind} unknownLabel="Andere herkenning" />
        </Kv>
        {revision.structureExpectedColumnCount !== null && (
          <Kv field="expectedColumnCount">{revision.structureExpectedColumnCount}</Kv>
        )}
        <Kv field="deliverySetKind">
          <Term domain="deliverySetKind" code={revision.accessDeliverySetKind} unknownLabel="Andere soort" />
        </Kv>
      </dl>
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
        />
      </div>
      <FiltersSection
        revision={revision}
        editing={editing}
        onAdd={onAddFilter}
        onEdit={onEditFilter}
        onChanged={onFiltersChanged ?? noop}
        onReload={onReload ?? noop}
      />
      <TranslationsSection revision={revision} editing={editing} onEditMapping={onEditMapping} />
      <FileSection revision={revision} />
      <ThresholdsSection revision={revision} />
      <BookmarksSection revision={revision} />
      {legacy !== null && <LegacyTechnicalDetails origin={legacy} />}
    </div>
  );
}
