/**
 * NT-14-4 — gedeelde stukjes voor "alle configuratiefouten tegelijk": op welk veld een fout slaat, en de melding dat
 * afhankelijke controles nog niet uitgevoerd konden worden. De codes staan enkel in "Technische details" (V7).
 */
import { DICTIONARY, term } from '../../../terms/index.ts';
import { TechnicalDetails } from '../../../terms/TechnicalDetails.tsx';
import { describeReadinessCheck } from './linkCheck.ts';

/** De code van de checklistregel "sommige controles overgeslagen"; woord en zin staan in het woordenboek `readinessCheck`. */
export const SKIPPED_CODE = 'INFO_CONFIG_CHECKS_SKIPPED';

/**
 * Het Nederlandse veldwoord bij een bevinding: het veld van de beschrijving (`revisionField`) en/of het doelveld of de
 * kolom (`fieldName`; een bekend doelveld met zijn Nederlandse woord, anders de kolomnaam zoals ze is). `null` = niets te melden.
 */
export function describeFindingField(revisionField: string | null | undefined, fieldName: string | null | undefined): string | null {
  const parts: string[] = [];
  if (revisionField !== null && revisionField !== undefined && revisionField !== '') {
    const entry = term('revisionField', revisionField);
    // Een onbekende sleutel tonen we niet als ruwe code: dan enkel de kolom of niets.
    if (entry.uitleg !== '') {
      parts.push(`Veld: ${entry.label}`);
    }
  }
  if (fieldName !== null && fieldName !== undefined && fieldName !== '') {
    const known = Object.prototype.hasOwnProperty.call(DICTIONARY.fieldKey, fieldName);
    parts.push(known ? `Doelveld: ${term('fieldKey', fieldName).label}` : `Kolom: ${fieldName}`);
  }
  return parts.length === 0 ? null : parts.join(' — ');
}

/** De codes uit "Checks depending on [A, B] were not evaluated" (servertekst); leeg als de tekst afwijkt. */
export function parseSkippedCodes(detail: string | null): string[] {
  const match = detail === null ? null : /\[([^\]]*)\]/.exec(detail);
  return match === null || match[1] === undefined
    ? []
    : match[1]
        .split(',')
        .map((code) => code.trim())
        .filter((code) => code !== '');
}

/** Haal de codes van overgeslagen controles: eerst van het gestructureerde veld (NT-14-5), anders uit de detail-tekst. */
export function getSkippedCodes(check: { skippedBecause?: string[] | null; detail?: string | null }): string[] {
  if (check.skippedBecause && check.skippedBecause.length > 0) {
    return check.skippedBecause;
  }
  return parseSkippedCodes(check.detail ?? null);
}

/** De Nederlandse woorden van de overgeslagen controles, zonder dubbels. */
function skippedLabels(codes: readonly string[]): string[] {
  return [...new Set(codes.map((code) => describeReadinessCheck(code).label))];
}

/** De lijst "Dit hangt af van: …" met de codes enkel onder "Technische details". */
export function SkippedCauses({ codes, extraDetails = [] }: { codes: readonly string[]; extraDetails?: { name: string; value: string }[] }) {
  const labels = skippedLabels(codes);
  return (
    <>
      {labels.length > 0 && (
        <>
          <p>Deze controles wachten op het herstel van:</p>
          <ul data-testid="config-skipped-causes">
            {labels.map((label) => (
              <li key={label}>{label}</li>
            ))}
          </ul>
        </>
      )}
      <TechnicalDetails
        items={[
          ...(codes.length === 0 ? [] : [{ name: 'Codes van de overgeslagen controles', value: codes.join(', ') }]),
          ...extraDetails,
        ]}
      />
    </>
  );
}
