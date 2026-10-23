/**
 * De kleine leesbouwstenen van het revisiedetail, gedeeld door het leesscherm "Bestand en velden" (`RevisionReadView`,
 * `EditorSections`, `FieldsGrid`, `AdvancedSection`): "—" voor een niet vastgestelde waarde, een label/waarde-regel, het woord
 * bij een doelveld en de vraag of een onbekende waarde ongewijzigd doorgaat. Eén bron voor de woorden, zodat alle
 * schermen hetzelfde zeggen.
 */

import type { ReactNode } from 'react';
import type { MappingView } from '../../api/types.ts';
import { Term } from '../../terms/Term.tsx';
import styles from './revisionDetailParts.module.css';

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

/** Legacy-config S8c: gaat een onbekende waarde ongewijzigd door (`onMiss=KEEP_SOURCE` in een stap)? */
export function keepsSourceOnMiss(mapping: MappingView): boolean {
  return (mapping.steps ?? []).some((step) => (step.config ?? '').split(';').includes('onMiss=KEEP_SOURCE'));
}
