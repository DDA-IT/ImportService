/**
 * De kleine leesbouwstenen van het revisiedetail, gedeeld door `RevisionDetailSection` (volledig detail met bewerken)
 * en `RevisionReadView` (het leesscherm "Bestand en velden" van het leveranciersdetail): "—" voor een niet vastgestelde
 * waarde, een label/waarde-regel, het woord bij een doelveld, de extra's van een mapping (omzettingen, bewerkingen) en
 * de samenvatting van een filterrij. Eén bron voor de woorden, zodat beide schermen hetzelfde zeggen.
 */

import type { ReactNode } from 'react';
import type { FilterView, MappingView } from '../../api/types.ts';
import { term, termLabel } from '../../terms/index.ts';
import { Term } from '../../terms/Term.tsx';
import styles from './RevisionDetailSection.module.css';

/** "—" voor een waarde die niet vastgesteld is; nooit 0 en nooit een lege plek. */
export function show(value: string | number | null): string {
  if (value === null) {
    return '—';
  }
  if (value === '') {
    return '(uitdrukkelijk leeg)';
  }
  return String(value);
}

export function bool(value: boolean): string {
  return value ? 'ja' : 'nee';
}

/** Eén regel van het overzicht: het Nederlandse woord (met uitleg in de tooltip) en de waarde. */
export function Row({ label, value }: { label: ReactNode; value: ReactNode }) {
  return (
    <div className={styles.figure}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

/** Een regel met een veld uit het woordenboek `revisionField` als label. */
export function FieldRow({ name, value }: { name: string; value: ReactNode }) {
  return <Row label={<Term domain="revisionField" code={name} />} value={value} />;
}

/**
 * Het woord bij een doelveld van een extra veld of een veld met aangepaste kritiek. Een code die niet in de
 * veldcatalogus van het woordenboek staat, toont "Ander veld"; de code zelf blijft in de tooltip.
 */
export function FieldKeyTerm({ code }: { code: string }) {
  return <Term domain="fieldKey" code={code} unknownLabel="Ander veld" />;
}

/** De bewerkingen van een keten als woorden, bv. "Hoofdletters → Vertaling"; leeg zonder stappen. */
export function transformStepsText(mapping: MappingView): string {
  return (mapping.steps ?? []).map((step) => termLabel('transformKind', step.kind, 'andere bewerking')).join(' → ');
}

/** Legacy-config S8c: gaat een onbekende waarde ongewijzigd door (`onMiss=KEEP_SOURCE` in een stap)? */
export function keepsSourceOnMiss(mapping: MappingView): boolean {
  return (mapping.steps ?? []).some((step) => (step.config ?? '').split(';').includes('onMiss=KEEP_SOURCE'));
}

/**
 * Legacy-config S8 (alleen-lezen): het aantal omzettingen (`valueMap`), de bewerkingen van een keten (`steps`) en of een
 * onbekende waarde ongewijzigd doorgaat (`onMiss=KEEP_SOURCE` in de configuratie van een stap). Bewerken valt buiten S9.
 */
export function MappingExtras({ mapping }: { mapping: MappingView }) {
  const valueMapCount = mapping.valueMap?.length ?? 0;
  const stepsText = transformStepsText(mapping);
  return (
    <>
      {valueMapCount > 0 && <span>Omzettingen: {valueMapCount}</span>}
      {stepsText !== '' && <span>Bewerkingen: {stepsText}</span>}
      {keepsSourceOnMiss(mapping) && <span>{term('legacyReportCode', 'MAP_MISS_KEEPS_SOURCE').label}</span>}
    </>
  );
}

/** De vergelijkingswaarde van een filter; "leeg veld" voor een groepsrij zonder waarde. */
export function filterCompareText(filter: FilterView): string {
  return filter.matchGroup != null && filter.compareValue === '' ? 'leeg veld' : show(filter.compareValue);
}

/** De spans van één filterrij: volgnummer, kolom, vergelijking, waarde en uitkomst. */
export function FilterSummary({ filter }: { filter: FilterView }) {
  return (
    <>
      <span className={styles.sequence}>#{filter.sequenceNumber}</span>
      <span className={styles.code}>{filter.sourceReference}</span>
      <span>
        <Term domain="filterOperator" code={filter.operator} unknownLabel="andere vergelijking" />
      </span>
      <span>{filterCompareText(filter)}</span>
      <span>
        <Term domain="filterOutcome" code={filter.outcome} unknownLabel="Andere uitkomst" />
      </span>
    </>
  );
}
