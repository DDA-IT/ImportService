/**
 * `term(domain, code)` — het ene opzoekpunt voor woorden en uitleg op de schermen. Nooit een exception:
 * een onbekende code (bv. een latere backenduitbreiding) toont de code zelf als label, met lege uitleg —
 * onbekend wordt getoond, niet weggelaten (zelfde regel als `StatusBadge`).
 */

import { DICTIONARY, NULL_CODE, type TermDomain } from './dictionary.ts';

export { DICTIONARY, NULL_CODE, TERM_DOMAINS } from './dictionary.ts';
export type { TermDomain, TermEntry } from './dictionary.ts';

export type ResolvedTerm = { label: string; uitleg: string; code: string };

/** De tooltiptekst: de uitleg, met de technische code er klein bij. */
export function termTooltip(resolved: ResolvedTerm): string {
  return resolved.uitleg === ''
    ? `Technische code: ${resolved.code}`
    : `${resolved.uitleg} (technische code: ${resolved.code})`;
}

/**
 * Het Nederlandse woord als gewone tekst (voor een plek waar geen `<Term>` kan staan, bv. een hint), met een
 * neutraal woord voor een code die niet in het woordenboek staat — nooit de ruwe code zelf (NT-11c, V7).
 */
export function termLabel(domain: TermDomain, code: string | null | undefined, unknownLabel: string): string {
  const resolved = term(domain, code);
  return resolved.uitleg === '' ? unknownLabel : resolved.label;
}

export function term(domain: TermDomain, code: string | null | undefined): ResolvedTerm {
  const key = code === null || code === undefined ? NULL_CODE : code;
  const entry = Object.prototype.hasOwnProperty.call(DICTIONARY[domain] ?? {}, key)
    ? DICTIONARY[domain][key]
    : undefined;
  if (entry === undefined) {
    return { label: key, uitleg: '', code: key };
  }
  return { label: entry.label, uitleg: entry.uitleg, code: key };
}
