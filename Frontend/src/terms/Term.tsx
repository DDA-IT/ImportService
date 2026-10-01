/**
 * Toont één begrip in gewoon Nederlands (NT-5, V7). De uitleg staat als `title` (tooltip) en als
 * visueel verborgen tekst via `aria-describedby`, zodat ze ook voor een schermlezer beschikbaar is; de
 * technische code zit klein in dezelfde tooltip. Een onbekende code toont de code zelf (nooit leeg),
 * tenzij de aanroeper een neutraal Nederlands woord meegeeft (`unknownLabel`, NT-11a): dan staat dat
 * woord op het scherm en blijft de code beschikbaar in de tooltip.
 */

import { useId } from 'react';
import { term, termTooltip, type TermDomain } from './index.ts';
import styles from './Term.module.css';

export type TermProps = {
  domain: TermDomain;
  code: string | null | undefined;
  /** Het woord voor een code die niet in het woordenboek staat (de code zelf blijft in de tooltip). */
  unknownLabel?: string;
  /** De uitleg bij `unknownLabel`; zonder uitleg blijft enkel de technische code in de tooltip. */
  unknownUitleg?: string;
};

export function Term({ domain, code, unknownLabel, unknownUitleg }: TermProps) {
  const descriptionId = useId();
  const looked = term(domain, code);
  // Een bekende ingang heeft altijd een uitleg (bewaakt door `terms.test.ts`); een lege uitleg = onbekend.
  const unknown = looked.uitleg === '';
  const fallbackLabel = unknown && code !== null && code !== undefined ? unknownLabel : undefined;
  const resolved =
    fallbackLabel !== undefined ? { label: fallbackLabel, uitleg: unknownUitleg ?? '', code: looked.code } : looked;
  const hasUitleg = resolved.uitleg !== '';
  return (
    <>
      <span
        className={styles.term}
        title={termTooltip(resolved)}
        aria-describedby={hasUitleg ? descriptionId : undefined}
      >
        {resolved.label}
      </span>
      {hasUitleg && (
        <span id={descriptionId} className={styles.visuallyHidden}>
          {resolved.uitleg}
        </span>
      )}
    </>
  );
}
