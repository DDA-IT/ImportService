/**
 * Het leesbare beeld van één versie van een importdefinitie, voor het tabblad "Bestand en velden" van het
 * leveranciersdetail (R1, mock `vdSection`): Bestandsopbouw, Herkenning van het artikel, Velden, Filters, Vertalingen.
 * Toont enkel wat `RevisionDetail` (E1) levert; de woorden komen uit hetzelfde woordenboek en dezelfde bouwstenen als
 * `RevisionDetailSection` (`revisionDetailParts.tsx`). Bewerken blijft in `RevisionDetailSection`.
 */

import type { ReactNode } from 'react';
import type { MappingView, RevisionDetail } from '../../api/types.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { Term } from '../../terms/Term.tsx';
import { groupFilters } from './filterGroupModel.ts';
import { FieldKeyTerm, FilterSummary, show, transformStepsText } from './revisionDetailParts.tsx';
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

/** Een bron die een kolom of een vaste waarde kan zijn: "← kolom X" of de vaste waarde. */
function SourceValue({ column, fixed }: { column: string | null; fixed?: string | null }) {
  if (column !== null && column !== '') {
    return (
      <>
        ← <span className={styles.code}>{column}</span>
      </>
    );
  }
  if (fixed !== undefined && fixed !== null && fixed !== '') {
    return <>{TEXT.fixedValue(fixed)}</>;
  }
  return <Unset>{TEXT.notUsed}</Unset>;
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

function IdentitySection({ revision }: { revision: RevisionDetail }) {
  return (
    <section className={styles.vdSection} aria-labelledby="vd-identity" data-testid="read-identity">
      <h3 id="vd-identity">{TEXT.sections.identity}</h3>
      <dl className={styles.dl}>
        <Kv field="identityProfileKind">
          <Term domain="identityProfile" code={revision.identityProfileKind} unknownLabel="Andere herkenning" />
        </Kv>
        <dt>
          <Term domain="fieldKey" code="SUPPLIER" />
        </dt>
        <dd>
          <SourceValue column={revision.identitySupplierField} fixed={revision.identitySupplierFixedValue} />
        </dd>
        <dt>
          <Term domain="fieldKey" code="SUPPLIER_GROUP" />
        </dt>
        <dd>
          <SourceValue column={revision.identitySupplierGroupField} fixed={revision.identitySupplierGroupFixedValue} />
        </dd>
        <dt>
          <Term domain="fieldKey" code="SUPPLIER_REFERENCE" />
        </dt>
        <dd>
          <SourceValue column={revision.identitySupplierReferenceField} />
        </dd>
        <dt>
          <Term domain="fieldKey" code="DISCOUNT_CODE" />
        </dt>
        <dd>
          <SourceValue column={revision.identityDiscountCodeField} />
        </dd>
        {revision.identityUppercase !== undefined && (
          <Kv field="identityUppercase">{revision.identityUppercase ? TEXT.yes : TEXT.no}</Kv>
        )}
      </dl>
    </section>
  );
}

function mappingSource(mapping: MappingView): ReactNode {
  if (mapping.valueKind === 'SOURCE_FIELD') {
    return <span className={styles.code}>{mapping.sourceReference}</span>;
  }
  if (mapping.valueKind === 'FIXED_VALUE' && mapping.fixedValue != null) {
    return TEXT.fixedValue(mapping.fixedValue);
  }
  return <Term domain="mappingValueKind" code={mapping.valueKind} unknownLabel="Andere herkomst" />;
}

function mappingOperation(mapping: MappingView): string {
  const steps = transformStepsText(mapping);
  if (steps !== '') {
    return steps;
  }
  const translations = mapping.valueMap?.length ?? 0;
  return translations > 0 ? `Vertaling (${translations})` : TEXT.unknown;
}

function mappingRequired(mapping: MappingView): string {
  return mapping.required === undefined ? TEXT.unknown : mapping.required ? TEXT.yes : TEXT.no;
}

function FieldsSection({ revision }: { revision: RevisionDetail }) {
  const head = TEXT.fieldsHead;
  const base: { code: string; column: string; required: boolean }[] = [
    { code: 'BASE_PRICE', column: revision.recordBasePriceField, required: true },
  ];
  if (revision.recordDescriptionField !== null) {
    base.push({ code: 'DESCRIPTION', column: revision.recordDescriptionField, required: false });
  }
  if (revision.recordCurrencyField !== null) {
    base.push({ code: 'CURRENCY', column: revision.recordCurrencyField, required: false });
  }
  return (
    <section className={styles.vdSection} aria-labelledby="vd-fields" data-testid="read-fields">
      <h3 id="vd-fields">{TEXT.sections.fields}</h3>
      <div className={styles.tableWrap}>
        <table className={`${styles.table} ${styles.stack}`}>
          <thead>
            <tr>
              <th>{head.field}</th>
              <th>{head.source}</th>
              <th>{head.operation}</th>
              <th>{head.required}</th>
            </tr>
          </thead>
          <tbody>
            {base.map((row) => (
              <tr key={`base-${row.code}`}>
                <td data-label={head.field}>
                  <strong>
                    <FieldKeyTerm code={row.code} />
                  </strong>
                </td>
                <td data-label={head.source}>
                  <span className={styles.code}>{row.column}</span>
                </td>
                <td data-label={head.operation}>{TEXT.unknown}</td>
                <td data-label={head.required}>{row.required ? TEXT.yes : TEXT.no}</td>
              </tr>
            ))}
            {revision.mappings.map((mapping) => (
              <tr key={mapping.id} data-testid={`read-field-${mapping.id}`}>
                <td data-label={head.field}>
                  <strong>
                    <FieldKeyTerm code={mapping.targetFieldCode} />
                  </strong>
                </td>
                <td data-label={head.source}>{mappingSource(mapping)}</td>
                <td data-label={head.operation}>{mappingOperation(mapping)}</td>
                <td data-label={head.required}>{mappingRequired(mapping)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {revision.mappings.length === 0 && <p className={styles.moreNote}>{TEXT.noFields}</p>}
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

export function RevisionReadView({ revision }: { revision: RevisionDetail }) {
  return (
    <div data-testid="revision-read-view">
      <FileSection revision={revision} />
      <IdentitySection revision={revision} />
      <FieldsSection revision={revision} />
      <FiltersSection revision={revision} />
      <TranslationsSection revision={revision} />
    </div>
  );
}
