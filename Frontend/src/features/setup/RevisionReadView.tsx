/**
 * Het leesbare beeld van één versie van een importdefinitie, voor het tabblad "Bestand en velden" van het
 * leveranciersdetail (R1, mock `vdSection`): Bestandsopbouw, het Velden-grid (Herkenning van het artikel, Basis, Extra
 * velden; `FieldsGrid`, E1: dezelfde lay-out om te lezen en te bewerken), Filters, Vertalingen. Toont enkel wat
 * `RevisionDetail` levert; de woorden komen uit hetzelfde woordenboek en dezelfde bouwstenen als `RevisionDetailSection`
 * (`revisionDetailParts.tsx`). Extra velden bewerken kan sinds E2 in het grid (`MappingPanel`); filters blijven tot E3 in `RevisionDetailSection`.
 */

import type { ReactNode } from 'react';
import type { RevisionDetail, RevisionLegacyOrigin } from '../../api/types.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { Term } from '../../terms/Term.tsx';
import { groupFilters } from './filterGroupModel.ts';
import { LegacyOriginNotice, LegacyTechnicalDetails, usableLegacyOrigin } from './LegacyOriginParts.tsx';
import { FieldsGrid } from './FieldsGrid.tsx';
import type { CoreRowKey } from './fieldsGridModel.ts';
import { FieldKeyTerm, FilterSummary, show } from './revisionDetailParts.tsx';
import styles from './SetupScreens.module.css';
import detailStyles from './RevisionDetailSection.module.css';

const TEXT = SUPPLIER_TEXT;

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
    <section className={styles.vdSection} aria-labelledby="vd-file" data-testid="read-file">
      <h3 id="vd-file">{TEXT.sections.file}</h3>
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

function FiltersSection({ revision }: { revision: RevisionDetail }) {
  return (
    <section className={styles.vdSection} aria-labelledby="vd-filters" data-testid="read-filters">
      <h3 id="vd-filters">{TEXT.sections.filters}</h3>
      {revision.filters.length === 0 ? (
        <p className={styles.rowMeta}>{TEXT.noFilters}</p>
      ) : (
        <ul className={styles.rowList}>
          {groupFilters(revision.filters).map((entry) =>
            entry.kind === 'single' ? (
              <li key={entry.filter.id}>
                <div className={detailStyles.listItem}>
                  <FilterSummary filter={entry.filter} />
                </div>
              </li>
            ) : (
              <li key={`group-${entry.group}`} className={styles.filterGroup}>
                <span className={detailStyles.filterGroupTitle}>
                  Uitsluitingsgroep {entry.group}: alle voorwaarden samen
                </span>
                <ul className={styles.rowList}>
                  {entry.filters.map((filter) => (
                    <li key={filter.id}>
                      <div className={detailStyles.listItem}>
                        <FilterSummary filter={filter} />
                      </div>
                    </li>
                  ))}
                </ul>
              </li>
            ),
          )}
        </ul>
      )}
    </section>
  );
}

function TranslationsSection({ revision }: { revision: RevisionDetail }) {
  const head = TEXT.translationHead;
  const withMap = revision.mappings.filter((mapping) => (mapping.valueMap?.length ?? 0) > 0);
  return (
    <section className={styles.vdSection} aria-labelledby="vd-translations" data-testid="read-translations">
      <h3 id="vd-translations">{TEXT.sections.translations}</h3>
      {withMap.length === 0 ? (
        <p className={styles.rowMeta}>{TEXT.noTranslations}</p>
      ) : (
        withMap.map((mapping) => (
          <div key={mapping.id} className={styles.mapCard}>
            <h4>
              <FieldKeyTerm code={mapping.targetFieldCode} />
            </h4>
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
          </div>
        ))
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
}: RevisionReadViewProps) {
  const legacy = usableLegacyOrigin(legacyOrigin);
  return (
    <div data-testid="revision-read-view">
      <LegacyOriginNotice origin={legacyOrigin} failed={legacyFailed} originVersionNumber={legacyOriginVersionNumber} />
      <FileSection revision={revision} />
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
      <FiltersSection revision={revision} />
      <TranslationsSection revision={revision} />
      {legacy !== null && <LegacyTechnicalDetails origin={legacy} />}
    </div>
  );
}
