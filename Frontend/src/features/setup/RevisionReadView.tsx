/**
 * Het leesbare beeld van één versie van een importdefinitie, voor het tabblad "Bestand en velden" van het
 * leveranciersdetail (R1, mock `vdSection`): Bestandsopbouw, Herkenning van het artikel, Velden, Filters, Vertalingen.
 * Toont enkel wat `RevisionDetail` (E1) levert; de woorden komen uit hetzelfde woordenboek en dezelfde bouwstenen als
 * `RevisionDetailSection` (`revisionDetailParts.tsx`). Bewerken blijft in `RevisionDetailSection`.
 */

import type { ReactNode } from 'react';
import type { MappingView, RevisionDetail, RevisionLegacyOrigin } from '../../api/types.ts';
import { SUPPLIER_TEXT } from '../../terms/setupDashboard.ts';
import { Term } from '../../terms/Term.tsx';
import { groupFilters } from './filterGroupModel.ts';
import {
  LegacyHint,
  LegacyOriginNotice,
  LegacyTechnicalDetails,
  UncoveredLegacyLines,
  legacyColumnLabel,
  usableLegacyOrigin,
} from './LegacyOriginParts.tsx';
import { lineForRow, type LegacyRowKey } from './legacyOrigin.ts';
import { FieldKeyTerm,FilterSummary, show, transformStepsText } from './revisionDetailParts.tsx';
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

function IdentitySection({ revision, legacy }: { revision: RevisionDetail; legacy: RevisionLegacyOrigin | null }) {
  /** Naast de bron: de legacy-regel van dit revisieveld, gedempt (enkel bij een versie uit legacy). */
  const hint = (revisionField: string) => {
    if (legacy === null) {
      return null;
    }
    const line = lineForRow(legacy.lines, { revisionField });
    return line === null ? null : (
      <>
        {' '}
        <LegacyHint line={line} />
      </>
    );
  };
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
          {hint('identitySupplierField')}
        </dd>
        <dt>
          <Term domain="fieldKey" code="SUPPLIER_GROUP" />
        </dt>
        <dd>
          <SourceValue column={revision.identitySupplierGroupField} fixed={revision.identitySupplierGroupFixedValue} />
          {hint('identitySupplierGroupField')}
        </dd>
        <dt>
          <Term domain="fieldKey" code="SUPPLIER_REFERENCE" />
        </dt>
        <dd>
          <SourceValue column={revision.identitySupplierReferenceField} />
          {hint('identitySupplierReferenceField')}
        </dd>
        <dt>
          <Term domain="fieldKey" code="DISCOUNT_CODE" />
        </dt>
        <dd>
          <SourceValue column={revision.identityDiscountCodeField} />
          {hint('identityDiscountCodeField')}
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

function FieldsSection({ revision, legacy }: { revision: RevisionDetail; legacy: RevisionLegacyOrigin | null }) {
  const head = TEXT.fieldsHead;
  const base: { code: string; column: string; required: boolean; key: LegacyRowKey | null }[] = [
    { code: 'BASE_PRICE', column: revision.recordBasePriceField, required: true, key: { revisionField: 'recordBasePriceField' } },
  ];
  if (revision.recordDescriptionField !== null) {
    base.push({
      code: 'DESCRIPTION',
      column: revision.recordDescriptionField,
      required: false,
      key: { revisionField: 'recordDescriptionField' },
    });
  }
  if (revision.recordCurrencyField !== null) {
    base.push({ code: 'CURRENCY', column: revision.recordCurrencyField, required: false, key: null });
  }
  const legacyLabel = legacy === null ? '' : legacyColumnLabel(legacy);
  const legacyCell = (key: LegacyRowKey | null): ReactNode =>
    legacy === null ? null : (
      <td data-label={legacyLabel} data-testid="legacy-cell">
        <LegacyHint line={key === null ? null : lineForRow(legacy.lines, key)} />
      </td>
    );
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
              {legacy !== null && <th data-testid="legacy-column-head">{legacyLabel}</th>}
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
                {legacyCell(row.key)}
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
                {legacyCell({ mappingCode: mapping.targetFieldCode })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {revision.mappings.length === 0 && <p className={styles.moreNote}>{TEXT.noFields}</p>}
      {legacy !== null && <UncoveredLegacyLines origin={legacy} revision={revision} />}
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
};

export function RevisionReadView({
  revision,
  legacyOrigin = null,
  legacyFailed = false,
  legacyOriginVersionNumber = null,
}: RevisionReadViewProps) {
  const legacy = usableLegacyOrigin(legacyOrigin);
  return (
    <div data-testid="revision-read-view">
      <LegacyOriginNotice origin={legacyOrigin} failed={legacyFailed} originVersionNumber={legacyOriginVersionNumber} />
      <FileSection revision={revision} />
      <IdentitySection revision={revision} legacy={legacy} />
      <FieldsSection revision={revision} legacy={legacy} />
      <FiltersSection revision={revision} />
      <TranslationsSection revision={revision} />
      {legacy !== null && <LegacyTechnicalDetails origin={legacy} />}
    </div>
  );
}
