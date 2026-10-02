/**
 * S9-d — de pure kant van `DescriptionStep`: constanten, formuliertypes, validatie en het opbouwen van het
 * versieverzoek. Geen React; het verzendverloop blijft in het component. Wat het component vroeger via
 * closure las (`definition`, `actor`, `fourPart`, `byPosition`) komt hier als parameter of wordt uit `form` afgeleid.
 */
import { type CreateRevisionRequest, type IdentityProfileKind } from '../../../api/types.ts';
import { optional } from './stepSubmit.ts';

export const MAX_CODE = 50;
export const MAX_NAME = 200;
export const MAX_FIELD_REFERENCE = 200;
export const MAX_CHARSET = 40;
export const OTHER = 'OTHER';
export const NO_QUOTE = 'NONE';

/** Herkenningsversie standaard 2 (aanname A3, `docs/decisions.md` 2026-09-30). */
export const DEFAULT_CANONICALISATION_VERSION = '2';

/**
 * Tab staat hier bewust niet tussen: de server trimt het scheidingsteken (`SetupService.requireText`) en
 * leest een tab daardoor als "leeg" (400). Zie de ontdekking in het rapport van NT-6.
 */
export const DELIMITER_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: ';', label: '; (puntkomma)' },
  { value: ',', label: ', (komma)' },
  { value: '|', label: '| (verticale streep)' },
  { value: OTHER, label: 'Ander teken…' },
];

export const QUOTE_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: '"', label: '" (dubbel aanhalingsteken)' },
  { value: "'", label: "' (enkel aanhalingsteken)" },
  { value: NO_QUOTE, label: 'Geen aanhalingsteken' },
];

export const CHARSET_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'UTF-8', label: 'UTF-8 (meest gebruikt)' },
  { value: 'windows-1252', label: 'Windows (westers)' },
  { value: 'ISO-8859-1', label: 'Latin-1' },
  { value: 'ISO-8859-15', label: 'Latin-9 (met euroteken)' },
  { value: OTHER, label: 'Andere…' },
];

/**
 * De standaarddrempels van een nieuwe versie, ter info (bron: `ImportDefinitionRevision`, beslissingslog
 * 20/09 "drempels zijn altijd een percentage"). Ze worden **niet** verstuurd; de slotstap toont de werkelijk
 * bewaarde waarden uit de server.
 */
export const DEFAULT_THRESHOLDS: ReadonlyArray<{ key: string; value: string }> = [
  { key: 'creationThresholdSharePercent', value: '1 %' },
  { key: 'maxCriticalSharePercent', value: '1 %' },
  { key: 'maxRejectedSharePercent', value: 'niet ingesteld' },
  { key: 'bulkIncidentSharePercent', value: '1 %' },
];

export type ColumnKey =
  | 'supplierField'
  | 'supplierGroupField'
  | 'supplierReferenceField'
  | 'discountCodeField'
  | 'basePriceField'
  | 'descriptionField'
  | 'currencyField';

export type FormState = {
  definitionCode: string;
  definitionName: string;
  delimiterChoice: string;
  delimiterOther: string;
  quoteChoice: string;
  charsetChoice: string;
  charsetOther: string;
  hasHeader: boolean;
  headerLineNumber: string;
  fieldReferenceKind: 'HEADER_NAME' | 'COLUMN_INDEX';
  identityProfileKind: IdentityProfileKind | '';
  canonicalisationVersion: string;
} & Record<ColumnKey, string>;

export type FormKey = keyof FormState;
/** De velden met vrije tekst (geen keuzelijst met vaste waarden en geen vinkje). */
export type TextKey = { [K in FormKey]: string extends FormState[K] ? K : never }[FormKey];
export type Errors = Partial<Record<FormKey, string>>;

export const INITIAL: FormState = {
  definitionCode: '',
  definitionName: '',
  delimiterChoice: '',
  delimiterOther: '',
  quoteChoice: '"',
  charsetChoice: 'UTF-8',
  charsetOther: '',
  hasHeader: true,
  headerLineNumber: '1',
  fieldReferenceKind: 'HEADER_NAME',
  identityProfileKind: '',
  canonicalisationVersion: DEFAULT_CANONICALISATION_VERSION,
  supplierField: '',
  supplierGroupField: '',
  supplierReferenceField: '',
  discountCodeField: '',
  basePriceField: '',
  descriptionField: '',
  currencyField: '',
};

function isColumnIndex(value: string): boolean {
  return /^\d+$/.test(value) && Number(value) >= 1;
}

export function delimiterValue(form: FormState): string {
  return form.delimiterChoice === OTHER ? form.delimiterOther : form.delimiterChoice;
}

export function charsetValue(form: FormState): string {
  return (form.charsetChoice === OTHER ? form.charsetOther : form.charsetChoice).trim();
}

export function quoteValue(form: FormState): string {
  return form.quoteChoice === NO_QUOTE ? '' : form.quoteChoice;
}

/** `definitionSaved` = de beschrijving is al bewaard (vroeger `definition !== null`): dan geen code/naam meer. */
export function validateDescription(form: FormState, definitionSaved: boolean): Errors {
  const fourPart = form.identityProfileKind === 'FOUR_PART_WITH_DISCOUNT_CODE';
  const byPosition = form.fieldReferenceKind === 'COLUMN_INDEX';
  const problems: Errors = {};
  if (!definitionSaved) {
    const code = form.definitionCode.trim();
    const name = form.definitionName.trim();
    if (code === '') problems.definitionCode = 'Vul een code in.';
    else if (code.length > MAX_CODE) problems.definitionCode = `Een code heeft hoogstens ${MAX_CODE} tekens.`;
    if (name === '') problems.definitionName = 'Vul een naam in.';
    else if (name.length > MAX_NAME) problems.definitionName = `Een naam heeft hoogstens ${MAX_NAME} tekens.`;
  }

  const delimiter = delimiterValue(form);
  if (form.delimiterChoice === '') {
    problems.delimiterChoice = 'Kies het teken tussen de kolommen.';
  } else if (delimiter.trim().length !== 1 || delimiter.length !== 1) {
    problems.delimiterChoice = 'Een scheidingsteken is precies één zichtbaar teken.';
  }
  const quote = quoteValue(form);
  if (quote !== '' && quote === delimiter) {
    problems.quoteChoice = 'Het aanhalingsteken moet verschillen van het scheidingsteken.';
  }
  const charset = charsetValue(form);
  if (charset === '') problems.charsetChoice = 'Kies een tekenset.';
  else if (charset.length > MAX_CHARSET) problems.charsetChoice = `Hoogstens ${MAX_CHARSET} tekens.`;

  if (form.hasHeader) {
    const line = form.headerLineNumber.trim();
    if (!/^\d+$/.test(line) || Number(line) < 1) {
      problems.headerLineNumber = 'Vul een geheel getal van 1 of meer in. Er wordt niets op 1 gezet.';
    }
  } else if (form.fieldReferenceKind === 'HEADER_NAME') {
    problems.fieldReferenceKind = 'Zonder kopregel kunnen kolommen enkel op positie herkend worden.';
  }

  if (form.identityProfileKind === '') {
    problems.identityProfileKind = 'Kies hoe een artikel herkend wordt; deze keuze heeft bewust geen standaard.';
  }

  const required: ColumnKey[] = ['supplierField', 'supplierGroupField', 'supplierReferenceField', 'basePriceField'];
  if (fourPart) required.push('discountCodeField');
  const optionalColumns: ColumnKey[] = ['descriptionField', 'currencyField'];
  for (const key of [...required, ...optionalColumns]) {
    const value = form[key].trim();
    if (value === '') {
      if (required.includes(key)) problems[key] = 'Deze kolom is verplicht.';
      continue;
    }
    if (value.length > MAX_FIELD_REFERENCE) {
      problems[key] = `Hoogstens ${MAX_FIELD_REFERENCE} tekens.`;
    } else if (byPosition && !isColumnIndex(value)) {
      problems[key] = 'Vul het volgnummer van de kolom in (1 = de eerste kolom).';
    }
  }

  if (!/^[12]$/.test(form.canonicalisationVersion)) {
    problems.canonicalisationVersion = 'Kies versie 1 of 2.';
  }
  return problems;
}

export function revisionBody(form: FormState, actor: string): CreateRevisionRequest {
  const fourPart = form.identityProfileKind === 'FOUR_PART_WITH_DISCOUNT_CODE';
  return {
    delimiter: delimiterValue(form),
    quoteChar: quoteValue(form),
    charset: charsetValue(form),
    hasHeader: form.hasHeader,
    // Zonder kopregel gaat er geen regelnummer mee: de server laat dan zijn eigen waarde staan.
    headerLineNumber: form.hasHeader ? Number(form.headerLineNumber.trim()) : null,
    fieldReferenceKind: form.fieldReferenceKind,
    expectedColumnCount: null,
    identityProfileKind: form.identityProfileKind as IdentityProfileKind,
    supplierField: form.supplierField.trim(),
    supplierGroupField: form.supplierGroupField.trim(),
    supplierReferenceField: form.supplierReferenceField.trim(),
    // Driedelig = de kortingscode is niet gemapt (`null`), nooit een lege tekst (§14.23.3).
    discountCodeField: fourPart ? form.discountCodeField.trim() : null,
    basePriceField: form.basePriceField.trim(),
    descriptionField: optional(form.descriptionField),
    currencyField: optional(form.currencyField),
    canonicalisationVersion: Number(form.canonicalisationVersion),
    changeReason: null,
    createdBy: actor,
  };
}
