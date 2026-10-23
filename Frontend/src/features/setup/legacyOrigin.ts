/**
 * Zuivere hulpfuncties voor de kolom "Uit legacy" (F2, docs/decisions.md 2026-10-23 B2): een korte Nederlandse
 * beschrijving per legacy-regel en de koppeling van een regel aan een rij van het leesbeeld. Geen React, geen netwerk.
 *
 * CALC-betekenis volgens `LegacyConfigTranslator` (enige bron die de vertaling kent): 1 optellen, 2 aftrekken,
 * 3 vermenigvuldigen, 4 delen, A percentage van een andere kolom, 7 vertaaltabel, 14 vertaaltabel op "bevat",
 * 15 hoofdletters; '' en '0' = geen berekening. Elke andere CALC is "berekening in legacy": de ruwe code staat enkel in
 * de technische details.
 */

import type { LegacyOriginLine, RevisionDetail } from '../../api/types.ts';
import { LEGACY_CALCULATION_TEXT as CALC, LEGACY_ORIGIN_TEXT as TEXT } from '../../terms/legacyOrigin.ts';

/** De CALC van een regel; `''` bij geen berekening (ook `'0'`, zoals de vertaling). */
export function calculationCodeOf(line: LegacyOriginLine): string {
  const code = (line.calculation ?? '').trim();
  return code === '0' ? '' : code;
}

/** Het rekenwoord van een regel, of `null` zonder berekening. Nooit een ruwe code. */
export function describeCalculation(line: LegacyOriginLine): string | null {
  const code = calculationCodeOf(line);
  if (code === '') {
    return null;
  }
  const value = (line.calculationValue ?? '').trim();
  switch (code) {
    case '1':
      return CALC.add(value);
    case '2':
      return CALC.subtract(value);
    case '3':
      return CALC.multiply(value);
    case '4':
      return CALC.divide(value);
    case 'A': {
      const separator = value.indexOf(';');
      const column = (separator < 0 ? value : value.slice(0, separator)).trim();
      return column === '' ? CALC.percentOfColumnUnknown : CALC.percentOfColumn(column);
    }
    case '7':
      return CALC.translation;
    case '14':
      return CALC.translationContains;
    case '15':
      return CALC.uppercase;
    default:
      return CALC.generic;
  }
}

/** Waar de waarde vandaan komt: vaste waarde, XML-pad, positie of kolom; `null` als de regel niets aanwijst. */
export function describeSource(line: LegacyOriginLine): string | null {
  if ((line.fixedValue ?? '') !== '') {
    return TEXT.fixed(line.fixedValue);
  }
  if ((line.xmlElementPath ?? '') !== '') {
    return TEXT.xml(line.xmlElementPath);
  }
  if (line.position > 0) {
    return line.length > 1
      ? TEXT.positionRange(line.position, line.position + line.length - 1)
      : TEXT.positionFrom(line.position);
  }
  if (line.column > 0) {
    return TEXT.column1(line.column);
  }
  return null;
}

/** De korte beschrijving, bv. "regel 12 · kolom 12" of "regel 33 · kolom 7 · × 1.5 · voorvoegsel X". */
export function describeLine(line: LegacyOriginLine): string {
  const parts: string[] = [TEXT.lineNumber(line.number)];
  const source = describeSource(line);
  if (source !== null) {
    parts.push(source);
  }
  const calculation = describeCalculation(line);
  if (calculation !== null) {
    parts.push(calculation);
  }
  if ((line.prefix ?? '').trim() !== '') {
    parts.push(TEXT.prefix(line.prefix.trim()));
  }
  if ((line.suffix ?? '').trim() !== '') {
    parts.push(TEXT.suffix(line.suffix.trim()));
  }
  return parts.join(' · ');
}

/** Een sleutel van een rij in het leesbeeld: een revisieveld of de code van een mapping. */
export type LegacyRowKey = { revisionField: string } | { mappingCode: string };

/** De eerste regel die naar deze rij wijst. */
export function lineForRow(lines: readonly LegacyOriginLine[], key: LegacyRowKey): LegacyOriginLine | null {
  for (const line of lines) {
    if ('revisionField' in key) {
      if (line.targetKind === 'REVISION_FIELD' && line.revisionField === key.revisionField) {
        return line;
      }
    } else if (line.targetKind === 'MAPPING' && line.targetFieldCode === key.mappingCode) {
      return line;
    }
  }
  return null;
}

/** Revisievelden die als rij in het leesbeeld staan: de identiteitsvelden altijd, de recordvelden enkel als ze gevuld zijn. */
function shownRevisionFields(revision: RevisionDetail): Set<string> {
  const shown = new Set<string>([
    'identitySupplierField',
    'identitySupplierGroupField',
    'identitySupplierReferenceField',
    'identityDiscountCodeField',
    'recordBasePriceField',
  ]);
  if (revision.recordDescriptionField !== null) {
    shown.add('recordDescriptionField');
  }
  return shown;
}

export type UncoveredLine = { line: LegacyOriginLine; reason: string };

/**
 * Legacy-regels zonder rij in het leesbeeld: zonder doelveld, of een doel dat in deze versie niet (meer) als rij
 * bestaat. Tonen wat niet landde is de reden van de lijst "Niet overgenomen uit legacy".
 */
export function uncoveredLines(lines: readonly LegacyOriginLine[], revision: RevisionDetail): UncoveredLine[] {
  const revisionFields = shownRevisionFields(revision);
  const mappingCodes = new Set(revision.mappings.map((mapping) => mapping.targetFieldCode));
  const result: UncoveredLine[] = [];
  for (const line of lines) {
    if (line.targetKind === 'NONE') {
      result.push({ line, reason: TEXT.noTarget });
    } else if (line.targetKind === 'REVISION_FIELD' && !revisionFields.has(line.revisionField ?? '')) {
      result.push({ line, reason: TEXT.notInThisVersion });
    } else if (line.targetKind === 'MAPPING' && !mappingCodes.has(line.targetFieldCode ?? '')) {
      result.push({ line, reason: TEXT.notInThisVersion });
    }
  }
  return result;
}

/** De ruwe waarden van een regel, voor "Technische details (voor support)". */
export function technicalLine(line: LegacyOriginLine): string {
  const entries: [string, string | number][] = [
    ['field', line.legacyFieldName],
    ['target', line.targetKind],
    ['targetFieldCode', line.targetFieldCode ?? ''],
    ['revisionField', line.revisionField ?? ''],
    ['column', line.column],
    ['position', line.position],
    ['length', line.length],
    ['prefix', line.prefix],
    ['suffix', line.suffix],
    ['fixedValue', line.fixedValue],
    ['calculation', line.calculation],
    ['calculationValue', line.calculationValue],
    ['fill', line.fill],
    ['xmlElementPath', line.xmlElementPath],
    ['includeExclude', line.includeExclude],
    ['includeExcludeData', line.includeExcludeData],
  ];
  return entries
    .filter(([, value]) => value !== '' && value !== 0)
    .map(([name, value]) => `${name}=${value}`)
    .join('; ');
}
