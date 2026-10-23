/**
 * G2c-3 — de pure kant van de vervangdialoog voor één extra veld (mapping): van `MappingView` naar formulierstaat, van
 * formulierstaat naar de body van `PUT /setup/revisions/{rid}/mappings/{mid}`, de vormvalidatie en het inlezen van
 * geplakte vertaalregels (`docs/design/g2-design.md` §G2c).
 *
 * **De client kent een vaste tabel** (`KIND_KEYS`) met de instellingen per bewerking, overgenomen uit
 * `FieldTransform.of(...)` en `docs/design/legacy-config-s8-design.md`. Ze beslist nooit of een keten klopt: de
 * gesloten volgorde `[SUBSTRING] → [AFFIX|PREFIX|SUFFIX] → [één waardebewerking]` is in de UI enkel afgedwongen door
 * drie vaste vakken; de server (`CONFIG_TRANSFORM_INVALID`, `VALUE_MAP_*`, `CONFIG_BOOKMARK_*`) beslist.
 *
 * **Niets gaat stil verloren.** Instellingen die de client niet kent (bv. de notatie `decimalSeparator=,` van een
 * keten) staan in `extra` en worden letterlijk teruggestuurd; getallen die niet leesbaar zijn blokkeren (nooit 0).
 * Client-validatie is enkel vorm: verplicht, lengtes, het teken `;`, dubbele bronwaarden, leesbare getallen.
 */

import type { CreateMappingRequest, MappingView } from '../../api/types.ts';

export const MAX_TABLE_VALUE_LENGTH = 50;
export const MAX_CONFIG_LENGTH = 1000;
export const CHAIN = 'CHAIN';

/** Een bewerking die enkel zonder keten bestaat: ze heeft geen plaats in de vaste ketenvakken. */
export const SINGLE_ONLY_KINDS = ['NONE', 'FIXED_VALUE', 'CONCAT'] as const;

/** De bewerkingen per ketenvak (1 = uitknippen, 2 = voor-/achtervoegsel, 3 = één waardebewerking). */
export const CHAIN_STAGE_KINDS: readonly (readonly string[])[] = [
  ['SUBSTRING'],
  ['AFFIX', 'PREFIX', 'SUFFIX'],
  ['UPPER', 'MAP', 'MAP_CONTAINS', 'ADD', 'SUBTRACT', 'MULTIPLY', 'DIVIDE', 'PERCENTAGE', 'SPLIT'],
];

/** Alle bewerkingen buiten de keten, in de volgorde van de keuzelijst. */
export const SINGLE_KINDS: readonly string[] = [
  'NONE',
  'FIXED_VALUE',
  'PREFIX',
  'SUFFIX',
  'AFFIX',
  'SUBSTRING',
  'UPPER',
  'SPLIT',
  'MAP',
  'MAP_CONTAINS',
  'CONCAT',
  'ADD',
  'SUBTRACT',
  'MULTIPLY',
  'DIVIDE',
  'PERCENTAGE',
];

/** De instellingssleutels (`transform_config`) per bewerking, in de volgorde waarin ze verstuurd worden. */
export const KIND_KEYS: Record<string, readonly string[]> = {
  NONE: [],
  FIXED_VALUE: ['value'],
  PREFIX: ['prefix'],
  SUFFIX: ['suffix'],
  AFFIX: ['prefix', 'suffix'],
  CONCAT: ['sources', 'separator'],
  SPLIT: ['separator', 'index'],
  SUBSTRING: ['start', 'length'],
  UPPER: [],
  MAP: ['caseSensitive', 'onMiss'],
  MAP_CONTAINS: [],
  ADD: ['operand', 'operandField'],
  SUBTRACT: ['operand', 'operandField'],
  MULTIPLY: ['operand', 'operandField'],
  DIVIDE: ['operand', 'operandField'],
  PERCENTAGE: ['base', 'baseField'],
};

export const ON_MISS_REJECT = 'REJECT';
export const ON_MISS_KEEP_SOURCE = 'KEEP_SOURCE';

export type Stage = {
  /** `''` = geen bewerking in dit ketenvak. */
  kind: string;
  settings: Record<string, string>;
  /** Instellingen die de client niet kent; blijven ongewijzigd behouden (`a=b;c=d`). */
  extra: string;
};

export type TableRow = { source: string; target: string };

export type MappingForm = {
  /** De herkomst van de waarde (`SOURCE_FIELD`, `FIXED_VALUE`, ...); E2: het paneel laat kiezen tussen kolom en vaste waarde. */
  valueKind: string;
  /** E2: de kritiek is te wijzigen (`CRITICAL` of `NON_CRITICAL`). */
  criticality: string;
  sourceReference: string;
  expectedPosition: string;
  fixedValue: string;
  defaultValue: string;
  required: boolean;
  maxLength: string;
  decimalScale: string;
  zeroAllowed: boolean;
  negativeAllowed: boolean;
  /** `true` = keten (CHAIN) met drie vaste vakken, anders één bewerking. */
  chain: boolean;
  single: Stage;
  stages: [Stage, Stage, Stage];
  /** De notatie-instellingen op de mapping zelf bij een keten (de ketenstappen dragen de rest). */
  mappingExtra: string;
  rows: TableRow[];
};

export function emptyStage(kind = ''): Stage {
  return { kind, settings: {}, extra: '' };
}

/** Splitst `a=b;c=d` in de bekende instellingen en wat overblijft (`extra`). */
function readConfig(config: string | null | undefined, known: readonly string[]): { settings: Record<string, string>; extra: string } {
  const settings: Record<string, string> = {};
  const extra: string[] = [];
  for (const raw of (config ?? '').split(';')) {
    const pair = raw.trim();
    if (pair === '') {
      continue;
    }
    const at = pair.indexOf('=');
    const key = at > 0 ? pair.slice(0, at).trim() : '';
    if (key !== '' && known.includes(key) && !(key in settings)) {
      settings[key] = pair.slice(at + 1).trim();
    } else {
      extra.push(pair);
    }
  }
  return { settings, extra: extra.join(';') };
}

/** `values=A>1|B>2` (legacy) naar tabelrijen; `null` als de tekst die vorm niet heeft (dan blijft hij in `extra`). */
function parseLegacyValues(text: string): TableRow[] | null {
  const rows: TableRow[] = [];
  for (const entry of text.split('|')) {
    if (entry.trim() === '') {
      continue;
    }
    const at = entry.indexOf('>');
    if (at <= 0) {
      return null;
    }
    rows.push({ source: entry.slice(0, at).trim(), target: entry.slice(at + 1).trim() });
  }
  return rows;
}

function loadStage(kind: string, config: string | null | undefined): Stage {
  const known = KIND_KEYS[kind] ?? [];
  const { settings, extra } = readConfig(config, known);
  return { kind, settings, extra };
}

export type LoadedForm = { form: MappingForm; unsupported: string | null };

/** Vult het formulier uit de huidige mapping. `unsupported` = de mapping is niet veilig terug te geven. */
export function initialMappingForm(mapping: MappingView): LoadedForm {
  let unsupported: string | null = null;
  if (
    mapping.transformKind === undefined ||
    mapping.required === undefined ||
    mapping.zeroAllowed === undefined ||
    mapping.negativeAllowed === undefined
  ) {
    unsupported = 'De server levert niet alle gegevens van dit extra veld mee; wijzigen kan niet veilig.';
  }
  const kind = mapping.transformKind ?? 'NONE';
  const isChain = kind === CHAIN;
  let rows: TableRow[] = (mapping.valueMap ?? []).map((row) => ({ source: row.source, target: row.target }));
  const stages: [Stage, Stage, Stage] = [emptyStage(), emptyStage(), emptyStage()];
  let single = emptyStage('NONE');
  let mappingExtra = '';

  if (isChain) {
    mappingExtra = (mapping.transformConfig ?? '').trim();
    for (const step of mapping.steps ?? []) {
      const stageIndex = CHAIN_STAGE_KINDS.findIndex((kinds) => kinds.includes(step.kind));
      const slot = stages[stageIndex];
      if (stageIndex < 0 || slot === undefined || slot.kind !== '') {
        unsupported = `Een stap van de keten (${step.kind}) past niet in de vaste vakken; wijzigen kan niet veilig.`;
        continue;
      }
      stages[stageIndex] = loadStage(step.kind, step.config);
    }
  } else if (KIND_KEYS[kind] === undefined) {
    unsupported = `De bewerking ${kind} kent deze dialoog niet; wijzigen kan niet veilig.`;
  } else {
    single = loadStage(kind, mapping.transformConfig);
    if (kind === 'MAP' && rows.length === 0) {
      // Legacy `values=` in de configuratie wordt een gewone tabel; de server accepteert enkel één van beide.
      const legacy = readConfig(mapping.transformConfig, ['values', ...KIND_KEYS.MAP!]);
      const parsed = legacy.settings.values === undefined ? null : parseLegacyValues(legacy.settings.values);
      if (parsed !== null) {
        rows = parsed;
        single = { kind, settings: pickKnown(legacy.settings, KIND_KEYS.MAP!), extra: legacy.extra };
      }
    }
  }

  const form: MappingForm = {
    valueKind: mapping.valueKind,
    criticality: mapping.criticality,
    sourceReference: mapping.sourceReference ?? '',
    expectedPosition: mapping.expectedPosition == null ? '' : String(mapping.expectedPosition),
    fixedValue: mapping.fixedValue ?? '',
    defaultValue: mapping.defaultValue ?? '',
    required: mapping.required ?? false,
    maxLength: mapping.maxLength == null ? '' : String(mapping.maxLength),
    decimalScale: mapping.decimalScale == null ? '' : String(mapping.decimalScale),
    zeroAllowed: mapping.zeroAllowed ?? false,
    negativeAllowed: mapping.negativeAllowed ?? false,
    chain: isChain,
    single,
    stages,
    mappingExtra,
    rows,
  };
  return { form, unsupported };
}

function pickKnown(settings: Record<string, string>, keys: readonly string[]): Record<string, string> {
  const picked: Record<string, string> = {};
  for (const key of keys) {
    const value = settings[key];
    if (value !== undefined) {
      picked[key] = value;
    }
  }
  return picked;
}

/** De bewerking die de vertaaltabel gebruikt (MAP of MAP_CONTAINS), of `null` als er geen tabel nodig is. */
export function tableKind(form: MappingForm): 'MAP' | 'MAP_CONTAINS' | null {
  const kinds = form.chain ? form.stages.map((stage) => stage.kind) : [form.single.kind];
  for (const kind of kinds) {
    if (kind === 'MAP' || kind === 'MAP_CONTAINS') {
      return kind;
    }
  }
  return null;
}

const INTEGER = /^\d+$/;
const DECIMAL = /^[+-]?\d+([.,]\d+)?$/;

/** Een heel getal ≥ 0, `null` als leeg; `undefined` als het niet leesbaar is (nooit stil 0). */
function parseWhole(text: string): number | null | undefined {
  const trimmed = text.trim();
  if (trimmed === '') {
    return null;
  }
  return INTEGER.test(trimmed) ? Number(trimmed) : undefined;
}

/**
 * De configuratietekst van één bewerking. `label` staat in de meldingen ("Uitknippen: …"). Geeft `null` als er geen
 * instellingen zijn. Enkel vorm: verplicht, `;`, getallen leesbaar, lengte.
 */
function buildConfig(stage: Stage, label: string, problems: string[]): string | null {
  const keys = KIND_KEYS[stage.kind] ?? [];
  const pairs: string[] = [];
  const text = (key: string): string => (stage.settings[key] ?? '').trim();
  const need = (key: string, what: string): void => {
    if (text(key) === '') {
      problems.push(`${label}: vul ${what} in.`);
    }
  };
  const whole = (key: string, what: string, minimum: number, required: boolean): void => {
    const value = text(key);
    if (value === '') {
      if (required) {
        problems.push(`${label}: vul ${what} in.`);
      }
      return;
    }
    if (!INTEGER.test(value) || Number(value) < minimum) {
      problems.push(`${label}: ${what} moet een heel getal van ${minimum} of hoger zijn.`);
    }
  };
  const decimal = (key: string, what: string): void => {
    const value = text(key);
    if (value !== '' && !DECIMAL.test(value)) {
      problems.push(`${label}: ${what} is geen leesbaar getal.`);
    }
  };

  switch (stage.kind) {
    case 'FIXED_VALUE':
      need('value', 'de vaste waarde');
      break;
    case 'PREFIX':
      need('prefix', 'het voorvoegsel');
      break;
    case 'SUFFIX':
      need('suffix', 'het achtervoegsel');
      break;
    case 'AFFIX':
      if (text('prefix') === '' && text('suffix') === '') {
        problems.push(`${label}: vul een voorvoegsel, een achtervoegsel of allebei in.`);
      }
      break;
    case 'CONCAT': {
      const columns = columnsOf(stage.settings.sources ?? '');
      if (columns.length === 0) {
        problems.push(`${label}: vul minstens één kolom in.`);
      }
      if (columns.some((column) => column.includes('|'))) {
        problems.push(`${label}: een kolomnaam mag geen "|" bevatten.`);
      }
      break;
    }
    case 'SPLIT':
      need('separator', 'het scheidingsteken');
      whole('index', 'het deel dat bewaard blijft', 1, true);
      break;
    case 'SUBSTRING':
      whole('start', 'de beginpositie', 1, false);
      whole('length', 'het aantal tekens', 1, false);
      break;
    case 'ADD':
    case 'SUBTRACT':
    case 'MULTIPLY':
    case 'DIVIDE': {
      const hasNumber = text('operand') !== '';
      const hasColumn = text('operandField') !== '';
      if (hasNumber === hasColumn) {
        problems.push(`${label}: vul precies één van "getal" of "kolom" in.`);
      }
      decimal('operand', 'het getal');
      break;
    }
    case 'PERCENTAGE': {
      const hasNumber = text('base') !== '';
      const hasColumn = text('baseField') !== '';
      if (hasNumber === hasColumn) {
        problems.push(`${label}: vul precies één van "basis" of "basiskolom" in.`);
      }
      decimal('base', 'de basis');
      break;
    }
    default:
      break;
  }

  for (const key of keys) {
    let value: string;
    if (key === 'sources') {
      value = columnsOf(stage.settings.sources ?? '').join('|');
    } else if (key === 'caseSensitive') {
      value = stage.settings.caseSensitive === 'true' ? 'true' : '';
    } else if (key === 'onMiss') {
      value = stage.settings.onMiss === ON_MISS_KEEP_SOURCE ? ON_MISS_KEEP_SOURCE : '';
    } else {
      value = text(key);
    }
    if (value.includes(';')) {
      problems.push(`${label}: een ";" kan hier niet bewaard worden; laat het teken weg.`);
    }
    if (value !== '') {
      pairs.push(`${key}=${value}`);
    }
  }
  const extra = stage.extra.trim();
  if (extra !== '') {
    pairs.push(extra);
  }
  const config = pairs.join(';');
  if (config.length > MAX_CONFIG_LENGTH) {
    problems.push(`${label}: de instellingen zijn langer dan ${MAX_CONFIG_LENGTH} tekens.`);
  }
  return config === '' ? null : config;
}

/** Eén kolom per regel, getrimd, zonder lege regels. */
export function columnsOf(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line !== '');
}

/** De vorm van de tabelrijen: bron verplicht, ≤ 50 tekens, geen dubbele bron (de server beslist verder). */
export function validateRows(rows: TableRow[], kind: 'MAP' | 'MAP_CONTAINS', problems: string[]): void {
  if (rows.length === 0) {
    problems.push('Vertaaltabel: voeg minstens één rij toe.');
    return;
  }
  const seen = new Set<string>();
  rows.forEach((row, index) => {
    const where = `Vertaaltabel rij ${index + 1}`;
    const source = row.source.trim();
    const target = row.target.trim();
    if (source === '') {
      problems.push(`${where}: de bronwaarde ontbreekt.`);
    } else if (seen.has(source)) {
      problems.push(`${where}: de bronwaarde "${source}" komt meer dan één keer voor.`);
    }
    seen.add(source);
    if (source.length > MAX_TABLE_VALUE_LENGTH) {
      problems.push(`${where}: de bronwaarde is langer dan ${MAX_TABLE_VALUE_LENGTH} tekens.`);
    }
    if (target.length > MAX_TABLE_VALUE_LENGTH) {
      problems.push(`${where}: de nieuwe waarde is langer dan ${MAX_TABLE_VALUE_LENGTH} tekens.`);
    }
    if (kind === 'MAP_CONTAINS' && target === '') {
      problems.push(`${where}: bij omzetten op een deel van de waarde is een nieuwe waarde verplicht.`);
    }
  });
}

export type BuiltMapping = { body: CreateMappingRequest } | { problems: string[] };

/**
 * Bouwt de PUT- of POST-body uit het formulier. Het doelveld komt ongewijzigd uit de mapping, de soort herkomst en de
 * kritiek uit het formulier (zonder wijziging dezelfde als in de mapping); het volgnummer bepaalt de server (bij een PUT
 * dezelfde plaats, bij een POST het volgende).
 */
export function buildMappingRequest(
  form: MappingForm,
  mapping: MappingView,
  stageLabel: (kind: string) => string,
  actor: string,
): BuiltMapping {
  const problems: string[] = [];

  if (form.valueKind === 'SOURCE_FIELD' && form.sourceReference.trim() === '') {
    problems.push('Vul de kolom in het bronbestand in.');
  }
  if (form.valueKind === 'FIXED_VALUE' && form.fixedValue.trim() === '') {
    problems.push('Vul de vaste waarde in.');
  }
  const numbers: [string, string, string][] = [
    [form.expectedPosition, 'de positie', 'expectedPosition'],
    [form.maxLength, 'de maximale lengte', 'maxLength'],
    [form.decimalScale, 'het aantal decimalen', 'decimalScale'],
  ];
  const parsed: Record<string, number | null> = {};
  for (const [text, what, key] of numbers) {
    const value = parseWhole(text);
    if (value === undefined) {
      problems.push(`Bij ${what} hoort een heel getal (of laat het leeg).`);
      parsed[key] = null;
    } else {
      parsed[key] = value;
    }
  }

  let transformKind: string;
  let transformConfig: string | null;
  let steps: { kind: string; config: string | null }[] = [];
  if (form.chain) {
    transformKind = CHAIN;
    const stageNames = ['Uitknippen', 'Voor- of achtervoegsel', 'Waardebewerking'];
    form.stages.forEach((stage, index) => {
      if (stage.kind === '') {
        return;
      }
      const config = buildConfig(stage, `${stageNames[index]} (${stageLabel(stage.kind)})`, problems);
      steps.push({ kind: stage.kind, config });
    });
    if (steps.length === 0) {
      problems.push('Kies in de keten minstens één bewerking.');
    }
    const mappingExtra = form.mappingExtra.trim();
    if (mappingExtra.length > MAX_CONFIG_LENGTH) {
      problems.push(`De overige instellingen zijn langer dan ${MAX_CONFIG_LENGTH} tekens.`);
    }
    transformConfig = mappingExtra === '' ? null : mappingExtra;
  } else {
    transformKind = form.single.kind;
    transformConfig = buildConfig(form.single, stageLabel(form.single.kind), problems);
    steps = [];
  }

  const table = tableKind(form);
  let valueMap: TableRow[] = [];
  if (table !== null) {
    validateRows(form.rows, table, problems);
    valueMap = form.rows.map((row) => ({ source: row.source.trim(), target: row.target.trim() }));
  }

  if (problems.length > 0) {
    return { problems };
  }
  return {
    body: {
      targetFieldCode: mapping.targetFieldCode,
      sourceReference: form.sourceReference.trim() === '' ? null : form.sourceReference.trim(),
      sequenceNumber: null,
      valueKind: form.valueKind,
      expectedPosition: parsed.expectedPosition ?? null,
      fixedValue: form.fixedValue.trim() === '' ? null : form.fixedValue,
      defaultValue: form.defaultValue.trim() === '' ? null : form.defaultValue,
      required: form.required,
      maxLength: parsed.maxLength ?? null,
      decimalScale: parsed.decimalScale ?? null,
      zeroAllowed: form.zeroAllowed,
      negativeAllowed: form.negativeAllowed,
      transformKind,
      transformConfig,
      criticality: form.criticality,
      createdBy: actor,
      valueMap,
      steps,
    },
  };
}

/** Alle meldingen van het formulier (zonder body te bouwen), voor de lijst in de dialoog. */
export function mappingProblems(form: MappingForm, mapping: MappingView, stageLabel: (kind: string) => string): string[] {
  const built = buildMappingRequest(form, mapping, stageLabel, '');
  return 'problems' in built ? built.problems : [];
}

export type PasteResult = { rows: TableRow[]; errors: string[] };

/**
 * Leest geplakte vertaalregels: één regel per rij, twee kolommen gescheiden door een tab (uit een spreadsheet) of, als er
 * geen tab op de regel staat, door een `;`. Lege regels tellen niet. Elke fout noemt het regelnummer; er wordt nooit
 * stil een regel weggelaten of afgekapt.
 *
 * @param existing de bronwaarden die al in de tabel staan: een dubbele bron is een fout
 */
export function parsePaste(text: string, existing: readonly string[]): PasteResult {
  const rows: TableRow[] = [];
  const errors: string[] = [];
  const seen = new Set(existing.map((source) => source.trim()));
  text.split(/\r?\n/).forEach((line, index) => {
    if (line.trim() === '') {
      return;
    }
    const where = `Regel ${index + 1}`;
    const separator = line.includes('\t') ? '\t' : ';';
    const parts = line.split(separator);
    if (parts.length !== 2) {
      errors.push(`${where}: verwacht twee kolommen (bron en nieuwe waarde), gevonden ${parts.length}.`);
      return;
    }
    const source = (parts[0] ?? '').trim();
    const target = (parts[1] ?? '').trim();
    if (source === '') {
      errors.push(`${where}: de bronwaarde ontbreekt.`);
      return;
    }
    if (source.length > MAX_TABLE_VALUE_LENGTH || target.length > MAX_TABLE_VALUE_LENGTH) {
      errors.push(`${where}: een waarde is langer dan ${MAX_TABLE_VALUE_LENGTH} tekens.`);
      return;
    }
    if (seen.has(source)) {
      errors.push(`${where}: de bronwaarde "${source}" komt al voor.`);
      return;
    }
    seen.add(source);
    rows.push({ source, target });
  });
  return { rows, errors };
}
