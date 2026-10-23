/**
 * S9-c — de pure kant van het bewerken van de scalaire velden (E6: het oude `RevisionEditForm` is verwijderd; `FieldPanel` en `InlineSectionEditors` gebruiken dit): formulierstaat, "null = ongewijzigd"-diffs, de
 * identiteitsvergelijking (R-REV-X3) en het opbouwen van het `UpdateRevisionRequest`. Geen React, geen
 * state: alles wat het component vroeger via closure las, komt hier als expliciete parameter binnen.
 */

import {
  type IdentityProfileKind,
  type RevisionDetail,
  type RowIssueSeverity,
  type UpdateRevisionRequest,
} from '../../api/types.ts';
import { term } from '../../terms/index.ts';

/**
 * NT-11c (V7): het woord (en de uitleg) van een veld komt uit het woordenboek (`revisionField`); de technische
 * veldnaam staat nooit meer tussen haakjes achter het label. De veldnaam blijft enkel de sleutel van het formulier
 * en het `id` van het invoerelement.
 */
export function fieldWord(name: string): string {
  return term('revisionField', name).label;
}

/**
 * G2c-2 (legacy-config S6): leverancier en groep komen elk uit precies één van een kolom of een vaste waarde.
 * De keuze is formulierstaat (`supplierSource`/`supplierGroupSource`), nooit iets wat de server bewaart.
 */
export const SOURCE_COLUMN = 'COLUMN';
export const SOURCE_FIXED = 'FIXED';

/** Alle bewerkbare velden als tekst/boolean; getallen blijven tekst tot het versturen (nooit stil 0). */
export type FormState = {
  identityProfileKind: string;
  supplierField: string;
  supplierGroupField: string;
  /** `COLUMN` of `FIXED`: waar de leverancier vandaan komt. */
  supplierSource: string;
  supplierFixedValue: string;
  /** `COLUMN` of `FIXED`: waar de groep vandaan komt. */
  supplierGroupSource: string;
  supplierGroupFixedValue: string;
  identityUppercase: boolean;
  supplierReferenceField: string;
  discountCodeField: string;
  basePriceField: string;
  descriptionField: string;
  currencyField: string;
  delimiter: string;
  quoteChar: string;
  charset: string;
  hasHeader: boolean;
  headerLineNumber: string;
  fieldReferenceKind: string;
  expectedColumnCount: string;
  canonicalisationVersion: string;
  creationThresholdSharePercent: string;
  maxCriticalSharePercent: string;
  maxRejectedSharePercent: string;
  bulkIncidentSharePercent: string;
  priceDeviationPercent: string;
  priceDeviationSeverity: string;
  basePriceZeroAllowed: boolean;
  basePriceNegativeAllowed: boolean;
  priceDerivationTolerance: string;
  changeReason: string;
};

/** De tekstvelden en de booleanvelden apart, zodat één setter nooit het verkeerde type kan schrijven. */
export type TextKey = { [K in keyof FormState]: FormState[K] extends string ? K : never }[keyof FormState];
export type FlagKey = { [K in keyof FormState]: FormState[K] extends boolean ? K : never }[keyof FormState];

export function numberText(value: number | null): string {
  return value === null ? '' : String(value);
}

export function initialForm(revision: RevisionDetail): FormState {
  return {
    identityProfileKind: revision.identityProfileKind,
    // Een vaste leverancier/groep (legacy-config S6) heeft geen kolom: het veld blijft leeg en ongewijzigd.
    supplierField: revision.identitySupplierField ?? '',
    supplierGroupField: revision.identitySupplierGroupField ?? '',
    supplierSource: revision.identitySupplierFixedValue != null ? SOURCE_FIXED : SOURCE_COLUMN,
    supplierFixedValue: revision.identitySupplierFixedValue ?? '',
    supplierGroupSource: revision.identitySupplierGroupFixedValue != null ? SOURCE_FIXED : SOURCE_COLUMN,
    supplierGroupFixedValue: revision.identitySupplierGroupFixedValue ?? '',
    identityUppercase: revision.identityUppercase ?? false,
    supplierReferenceField: revision.identitySupplierReferenceField,
    discountCodeField: revision.identityDiscountCodeField ?? '',
    basePriceField: revision.recordBasePriceField,
    descriptionField: revision.recordDescriptionField ?? '',
    currencyField: revision.recordCurrencyField ?? '',
    delimiter: revision.structureDelimiter,
    quoteChar: revision.structureQuoteChar ?? '',
    charset: revision.structureCharset,
    hasHeader: revision.structureHasHeader,
    headerLineNumber: String(revision.structureHeaderLineNumber),
    fieldReferenceKind: revision.structureFieldReferenceKind,
    expectedColumnCount: numberText(revision.structureExpectedColumnCount),
    canonicalisationVersion: String(revision.recordCanonicalisationVersion),
    creationThresholdSharePercent: numberText(revision.creationThresholdSharePercent),
    maxCriticalSharePercent: numberText(revision.maxCriticalSharePercent),
    maxRejectedSharePercent: numberText(revision.maxRejectedSharePercent),
    bulkIncidentSharePercent: numberText(revision.bulkIncidentSharePercent),
    priceDeviationPercent: numberText(revision.priceDeviationPercent),
    priceDeviationSeverity: revision.priceDeviationSeverity,
    basePriceZeroAllowed: revision.basePriceZeroAllowed,
    basePriceNegativeAllowed: revision.basePriceNegativeAllowed,
    priceDerivationTolerance: numberText(revision.priceDerivationTolerance),
    changeReason: revision.changeReason ?? '',
  };
}

/** `''`/blanco = "niet ingevuld" (`null`), precies zoals `SetupService.optionalText` het leest. */
export function normaliseOptional(text: string): string | null {
  const trimmed = text.trim();
  return trimmed === '' ? null : trimmed;
}

/** Een verplicht tekstveld: `null` = ongewijzigd. Leeg is hier nooit een waarde maar een fout. */
export function diffRequiredText(text: string, current: string): string | null {
  const trimmed = text.trim();
  return trimmed === current.trim() ? null : trimmed;
}

/** Een optioneel tekstveld: `null` = ongewijzigd, `''` = uitdrukkelijk wissen. */
export function diffOptionalText(text: string, current: string | null): string | null {
  const next = normaliseOptional(text);
  if (next === (current === null ? null : current.trim())) {
    return null;
  }
  return next === null ? '' : next;
}

export function diffBoolean(value: boolean, current: boolean): boolean | null {
  return value === current ? null : value;
}

export function diffEnum<T extends string>(value: string, current: string): T | null {
  return value === current ? null : (value as T);
}

export type NumberDiff = { value: number | null; invalid: boolean };

/**
 * Een getalveld: leeg = ongewijzigd (geen enkel getalveld is langs dit pad leeg te maken), onleesbaar =
 * `invalid` (de aanroeper maakt er een cliëntvalidatiefout van, nooit stil 0), gelijk = ongewijzigd.
 */
export function diffNumber(text: string, current: number | null): NumberDiff {
  const trimmed = text.trim();
  if (trimmed === '') {
    return { value: null, invalid: false };
  }
  const parsed = Number(trimmed);
  if (!Number.isFinite(parsed)) {
    return { value: null, invalid: true };
  }
  return { value: parsed === current ? null : parsed, invalid: false };
}

/**
 * Eén identiteitsdeel (leverancier of groep) oud versus nieuw: enkel de gekozen bron telt, de andere is "leeg".
 * Zo is overstappen van kolom naar vaste waarde (of omgekeerd) een wijziging, ook al blijft de verborgen tekst staan.
 */
function sourcePartChanged(
  source: string,
  column: string,
  fixed: string,
  currentColumn: string | null,
  currentFixed: string | null | undefined,
): boolean {
  const nextColumn = source === SOURCE_FIXED ? '' : column.trim();
  const nextFixed = source === SOURCE_FIXED ? fixed.trim() : '';
  return nextColumn !== (currentColumn ?? '').trim() || nextFixed !== (currentFixed ?? '').trim();
}

/**
 * R-REV-X3: dezelfde vergelijking als de server — oud versus nieuw, niet "stond het in het verzoek". Sinds G2c-2
 * tellen ook de vaste leverancier/groep en `identityUppercase` mee (`RevisionFieldRules`: een wijziging daaraan is
 * een identiteitswijziging).
 */
export function identityChanged(form: FormState, revision: RevisionDetail): boolean {
  return (
    form.identityProfileKind !== revision.identityProfileKind ||
    sourcePartChanged(
      form.supplierSource,
      form.supplierField,
      form.supplierFixedValue,
      revision.identitySupplierField,
      revision.identitySupplierFixedValue,
    ) ||
    sourcePartChanged(
      form.supplierGroupSource,
      form.supplierGroupField,
      form.supplierGroupFixedValue,
      revision.identitySupplierGroupField,
      revision.identitySupplierGroupFixedValue,
    ) ||
    form.identityUppercase !== (revision.identityUppercase ?? false) ||
    form.supplierReferenceField.trim() !== revision.identitySupplierReferenceField.trim() ||
    normaliseOptional(form.discountCodeField) !==
      (revision.identityDiscountCodeField === null ? null : revision.identityDiscountCodeField.trim())
  );
}

function sourceDiff(
  source: string,
  column: string,
  fixed: string,
  currentColumn: string | null,
  currentFixed: string | null | undefined,
): { field: string | null; fixedValue: string | null } {
  if (source === SOURCE_FIXED) {
    return {
      field: currentColumn !== null ? '' : null,
      fixedValue: diffRequiredText(fixed, currentFixed ?? ''),
    };
  }
  return {
    field: diffRequiredText(column, currentColumn ?? ''),
    fixedValue: currentFixed != null ? '' : null,
  };
}

/**
 * Bouwt het verzoek of geeft een cliëntvalidatieprobleem. Vroeger een closure in het component; `form`,
 * `revision`, `acknowledgeIdentityChange` en `actor` zijn nu parameters (zelfde waarden), en de
 * canonicalisatiediff (vroeger een component-const) wordt hier met dezelfde pure `diffNumber` herberekend.
 */
export function buildRequest(
  form: FormState,
  revision: RevisionDetail,
  acknowledgeIdentityChange: boolean,
  actor: string,
): { body: UpdateRevisionRequest } | { problem: string } {
  const canonicalisationDiff = diffNumber(form.canonicalisationVersion, revision.recordCanonicalisationVersion);
  const numbers: Record<string, NumberDiff> = {
    headerLineNumber: diffNumber(form.headerLineNumber, revision.structureHeaderLineNumber),
    expectedColumnCount: diffNumber(form.expectedColumnCount, revision.structureExpectedColumnCount),
    canonicalisationVersion: canonicalisationDiff,
    creationThresholdSharePercent: diffNumber(
      form.creationThresholdSharePercent,
      revision.creationThresholdSharePercent,
    ),
    maxCriticalSharePercent: diffNumber(form.maxCriticalSharePercent, revision.maxCriticalSharePercent),
    maxRejectedSharePercent: diffNumber(form.maxRejectedSharePercent, revision.maxRejectedSharePercent),
    bulkIncidentSharePercent: diffNumber(form.bulkIncidentSharePercent, revision.bulkIncidentSharePercent),
    priceDeviationPercent: diffNumber(form.priceDeviationPercent, revision.priceDeviationPercent),
    priceDerivationTolerance: diffNumber(form.priceDerivationTolerance, revision.priceDerivationTolerance),
  };
  for (const [name, diff] of Object.entries(numbers)) {
    if (diff.invalid) {
      return { problem: `"${fieldWord(name)}" is geen leesbaar getal. Er is niets verstuurd en niets op 0 gezet.` };
    }
  }

  // Verplichte tekstvelden: leeg is een fout, nooit een lege waarde in het verzoek.
  const requiredFields: Array<[string, string]> = [
    // Leverancier en groep: precies de gekozen bron is verplicht (kolom of vaste waarde, legacy-config S6).
    form.supplierSource === SOURCE_FIXED
      ? ['supplierFixedValue', form.supplierFixedValue]
      : ['supplierField', form.supplierField],
    form.supplierGroupSource === SOURCE_FIXED
      ? ['supplierGroupFixedValue', form.supplierGroupFixedValue]
      : ['supplierGroupField', form.supplierGroupField],
    ['supplierReferenceField', form.supplierReferenceField],
    ['basePriceField', form.basePriceField],
    ['delimiter', form.delimiter],
    ['charset', form.charset],
    ['fieldReferenceKind', form.fieldReferenceKind],
  ];
  for (const [name, value] of requiredFields) {
    if (value.trim() === '') {
      return { problem: `"${fieldWord(name)}" is verplicht en mag niet leeg zijn. Er is niets verstuurd.` };
    }
  }

  if (identityChanged(form, revision) && !acknowledgeIdentityChange) {
    return {
      problem:
        'Deze wijziging raakt hoe een artikel herkend wordt. Bevestig hieronder uitdrukkelijk dat elk ' +
        'bestaand artikel daarna als nieuw artikel terugkomt; er is niets verstuurd.',
    };
  }

  // Overstappen in één verzoek: de nieuwe bron krijgt zijn waarde, de andere bron `""` (= wissen, S6), nooit een
  // stille wissel. Is de andere bron al leeg, dan gaat ze als `null` (ongewijzigd) mee.
  const supplier = sourceDiff(
    form.supplierSource,
    form.supplierField,
    form.supplierFixedValue,
    revision.identitySupplierField,
    revision.identitySupplierFixedValue,
  );
  const group = sourceDiff(
    form.supplierGroupSource,
    form.supplierGroupField,
    form.supplierGroupFixedValue,
    revision.identitySupplierGroupField,
    revision.identitySupplierGroupFixedValue,
  );

  const body: UpdateRevisionRequest = {
    identityProfileKind: diffEnum<IdentityProfileKind>(form.identityProfileKind, revision.identityProfileKind),
    supplierField: supplier.field,
    supplierGroupField: group.field,
    supplierFixedValue: supplier.fixedValue,
    supplierGroupFixedValue: group.fixedValue,
    identityUppercase: diffBoolean(form.identityUppercase, revision.identityUppercase ?? false),
    supplierReferenceField: diffRequiredText(
      form.supplierReferenceField,
      revision.identitySupplierReferenceField,
    ),
    discountCodeField: diffOptionalText(form.discountCodeField, revision.identityDiscountCodeField),
    basePriceField: diffRequiredText(form.basePriceField, revision.recordBasePriceField),
    descriptionField: diffOptionalText(form.descriptionField, revision.recordDescriptionField),
    currencyField: diffOptionalText(form.currencyField, revision.recordCurrencyField),
    delimiter: diffRequiredText(form.delimiter, revision.structureDelimiter),
    quoteChar: diffOptionalText(form.quoteChar, revision.structureQuoteChar),
    charset: diffRequiredText(form.charset, revision.structureCharset),
    hasHeader: diffBoolean(form.hasHeader, revision.structureHasHeader),
    headerLineNumber: numbers.headerLineNumber!.value,
    fieldReferenceKind: diffRequiredText(form.fieldReferenceKind, revision.structureFieldReferenceKind),
    expectedColumnCount: numbers.expectedColumnCount!.value,
    canonicalisationVersion: numbers.canonicalisationVersion!.value,
    creationThresholdSharePercent: numbers.creationThresholdSharePercent!.value,
    maxCriticalSharePercent: numbers.maxCriticalSharePercent!.value,
    maxRejectedSharePercent: numbers.maxRejectedSharePercent!.value,
    bulkIncidentSharePercent: numbers.bulkIncidentSharePercent!.value,
    priceDeviationPercent: numbers.priceDeviationPercent!.value,
    priceDeviationSeverity: diffEnum<RowIssueSeverity>(
      form.priceDeviationSeverity,
      revision.priceDeviationSeverity,
    ),
    basePriceZeroAllowed: diffBoolean(form.basePriceZeroAllowed, revision.basePriceZeroAllowed),
    basePriceNegativeAllowed: diffBoolean(form.basePriceNegativeAllowed, revision.basePriceNegativeAllowed),
    priceDerivationTolerance: numbers.priceDerivationTolerance!.value,
    changeReason: diffOptionalText(form.changeReason, revision.changeReason),
    // Alleen meesturen wanneer de gebruiker werkelijk bevestigde; `null` = geen bevestiging.
    acknowledgeIdentityChange: acknowledgeIdentityChange ? true : null,
    createdBy: actor,
  };
  return { body };
}

/** `true` zodra er minstens één veld (buiten de bevestiging en de actor) werkelijk wijzigt. */
export function hasChanges(body: UpdateRevisionRequest): boolean {
  return Object.entries(body).some(
    ([name, value]) => name !== 'createdBy' && name !== 'acknowledgeIdentityChange' && value !== null,
  );
}
