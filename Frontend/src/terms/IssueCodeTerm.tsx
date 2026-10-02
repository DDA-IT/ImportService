/**
 * Het Nederlandse woord bij een vaststelling van de controle (een `issueCode`, ook als blokkeerreden van een
 * levering). Een code met een veldnaam erachter (`HEADER_FIELD_MISSING:Prijs`) wordt op het deel vóór de
 * dubbele punt opgezocht. Een onbekende code toont "Ander probleem" (een onbekende `CONFIG_`-code: "Fout in
 * de beschrijving van het bestand"); de code zelf staat enkel in de tooltip (V7, NT-11a).
 */

import { Term } from './Term.tsx';
import { DICTIONARY, term } from './index.ts';

const UNKNOWN_LABEL = 'Ander probleem';
const UNKNOWN_UITLEG =
  'Voor deze vaststelling bestaat nog geen Nederlandse uitleg; de technische code staat in de tooltip of onder "Technische details".';
/** Dé bron van de terugvaltekst voor een onbekende `CONFIG`-code: de dictionary-entry `readinessCheck.CONFIG_INVALID`. */
const CONFIG_FALLBACK = DICTIONARY.readinessCheck.CONFIG_INVALID!;
const CONFIG_LABEL = CONFIG_FALLBACK.label;
const CONFIG_UITLEG = CONFIG_FALLBACK.uitleg;

/** Het deel van een code vóór de dubbele punt. */
export function issueBaseCode(code: string): string {
  const separator = code.indexOf(':');
  return separator > 0 ? code.substring(0, separator) : code;
}

/**
 * Woord en uitleg als gewone tekst, voor een plek waar de uitleg zichtbaar moet staan (een melding) en niet enkel
 * in een tooltip; dezelfde terugvallen als `IssueCodeTerm`. Nooit de code zelf.
 */
export function describeIssue(code: string): { label: string; uitleg: string } {
  const base = issueBaseCode(code);
  const known = term('issueCode', base);
  if (known.uitleg !== '') {
    return { label: known.label, uitleg: known.uitleg };
  }
  return base.startsWith('CONFIG')
    ? { label: CONFIG_LABEL, uitleg: CONFIG_UITLEG }
    : { label: UNKNOWN_LABEL, uitleg: UNKNOWN_UITLEG };
}

export function IssueCodeTerm({ code }: { code: string | null | undefined }) {
  if (code === null || code === undefined || code === '') {
    return <>—</>;
  }
  const base = issueBaseCode(code);
  const isConfig = base.startsWith('CONFIG');
  return (
    <Term
      domain="issueCode"
      code={base}
      unknownLabel={isConfig ? CONFIG_LABEL : UNKNOWN_LABEL}
      unknownUitleg={isConfig ? CONFIG_UITLEG : UNKNOWN_UITLEG}
    />
  );
}
