/**
 * E5a — het model achter de controle van de instellingen in stap ② van de conceptbalk
 * (`GET /definitions/{d}/revisions/{r}/configuration-check`, beslissingslog 2026-10-23 "E5a"): hoeveel problemen
 * blokkeren, en waar in de editor een regel opgelost wordt (een rij van het Velden-grid of een sectie). Puur, zonder React.
 */

import type { CoreRowKey } from '../fieldsGridModel.ts';
import type { ReadinessCheck, RevisionConfigurationCheck } from '../../../api/types.ts';
import { SECTION_IDS } from '../EditorSections.tsx';
import { SKIPPED_CODE } from './configFindings.tsx';

/** De ids van de elementen waar een sprong naartoe kan; de rijen van het grid (`FieldsGrid`) en de secties (`SECTION_IDS`). */
export const GRID_ROW_ID = (key: CoreRowKey): string => `grid-row-${key}`;
export const GRID_FIELD_ID = (code: string): string => `grid-field-${code}`;

export type JumpTarget = {
  kind: 'row' | 'section';
  /** In volgorde geprobeerd: het eerste element dat bestaat, krijgt de sprong (een rij valt terug op zijn sectie). */
  elementIds: string[];
};

/** De sleutels van `revisionField` die op een kernrij van het grid wijzen (kolom of vaste waarde van die rij). */
const CORE_ROW_BY_REVISION_FIELD: Record<string, CoreRowKey> = {
  supplierField: 'supplier',
  supplierFixedValue: 'supplier',
  supplierGroupField: 'group',
  supplierGroupFixedValue: 'group',
  supplierReferenceField: 'reference',
  discountCodeField: 'discount',
  basePriceField: 'basePrice',
  basePriceZeroAllowed: 'basePrice',
  basePriceNegativeAllowed: 'basePrice',
  descriptionField: 'description',
  currencyField: 'currency',
};

/** Een doelveld (`fieldName`) dat een kernrij is, en niet een extra veld. */
const CORE_ROW_BY_TARGET_FIELD: Record<string, CoreRowKey> = {
  SUPPLIER: 'supplier',
  SUPPLIER_GROUP: 'group',
  SUPPLIER_REFERENCE: 'reference',
  DISCOUNT_CODE: 'discount',
  BASE_PRICE: 'basePrice',
  DESCRIPTION: 'description',
  CURRENCY: 'currency',
};

const FILE_FIELDS = new Set([
  'structureFormat',
  'delimiter',
  'quoteChar',
  'charset',
  'hasHeader',
  'headerLineNumber',
  'fieldReferenceKind',
  'expectedColumnCount',
  'deliverySetKind',
]);

const THRESHOLD_FIELDS = new Set([
  'creationThresholdSharePercent',
  'maxCriticalSharePercent',
  'maxRejectedSharePercent',
  'bulkIncidentSharePercent',
  'priceDeviationPercent',
  'priceDeviationSeverity',
]);

const JUMP_SECTION_IDS = SECTION_IDS;

function has(value: string | null | undefined): value is string {
  return value !== null && value !== undefined && value !== '';
}

/**
 * Waar een probleem opgelost wordt. `revisionField` (een veld van de versie) wijst naar de kernrij, de sectie
 * Bestandsopbouw of Drempels; anders wijst `fieldName` naar de rij van dat doelveld. Zonder bruikbare aanwijzing: `null`
 * (geen sprong; de zin zelf zegt dan wat er mis is).
 */
export function jumpTargetOf(check: Pick<ReadinessCheck, 'revisionField' | 'fieldName'>): JumpTarget | null {
  const revisionField = check.revisionField;
  if (has(revisionField)) {
    const core = Object.prototype.hasOwnProperty.call(CORE_ROW_BY_REVISION_FIELD, revisionField)
      ? CORE_ROW_BY_REVISION_FIELD[revisionField]
      : undefined;
    if (core !== undefined) {
      return { kind: 'row', elementIds: [GRID_ROW_ID(core), JUMP_SECTION_IDS.fields] };
    }
    if (FILE_FIELDS.has(revisionField)) {
      return { kind: 'section', elementIds: [JUMP_SECTION_IDS.file] };
    }
    if (THRESHOLD_FIELDS.has(revisionField)) {
      return { kind: 'section', elementIds: [JUMP_SECTION_IDS.thresholds] };
    }
    // Overige velden van de herkenning (hoofdletters, herkenningsversie, ...) staan bij de velden.
    return { kind: 'section', elementIds: [JUMP_SECTION_IDS.fields] };
  }
  const fieldName = check.fieldName;
  if (has(fieldName)) {
    const core = Object.prototype.hasOwnProperty.call(CORE_ROW_BY_TARGET_FIELD, fieldName)
      ? CORE_ROW_BY_TARGET_FIELD[fieldName]
      : undefined;
    return {
      kind: 'row',
      elementIds: [core !== undefined ? GRID_ROW_ID(core) : GRID_FIELD_ID(fieldName), JUMP_SECTION_IDS.fields],
    };
  }
  return null;
}

export type ConfigurationCheckSummary = {
  problems: ReadinessCheck[];
  /** De regel "sommige controles overgeslagen", als ze er is. */
  skipped: ReadinessCheck | null;
};

export function summarise(result: RevisionConfigurationCheck): ConfigurationCheckSummary {
  return {
    problems: result.checks.filter((check) => check.status === 'PROBLEM'),
    skipped: result.checks.find((check) => check.code === SKIPPED_CODE) ?? null,
  };
}
