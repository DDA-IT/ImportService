/**
 * E2 — het pure model achter het zijpaneel voor een extra veld (mapping): de bewerkingschips als weergave over het
 * bestaande formulier van `mappingReplaceModel.ts` (één bewerking of de keten van drie vaste vakken), en de sjabloon van een
 * nieuw extra veld. De client beslist niet of een keten klopt: ze kiest enkel het vak waarin de server een bewerking toelaat
 * (`CHAIN_STAGE_KINDS`); `CONFIG_TRANSFORM_INVALID` en `VALUE_MAP_*` blijven van de server.
 *
 * Bewerkingen die de chips niet kennen (Splitsen, Samenvoegen, Optellen, Aftrekken, een vaste waarde als bewerking) blijven
 * ongewijzigd in het formulier staan en worden zo teruggestuurd: er gaat niets stil verloren.
 */

import type { MappingView } from '../../api/types.ts';
import { CHAIN_STAGE_KINDS, emptyStage, type MappingForm, type Stage } from './mappingReplaceModel.ts';

export type OpId = 'cut' | 'fix' | 'upper' | 'translate' | 'divide' | 'multiply' | 'percentage';

export type OpDef = {
  id: OpId;
  /** Het ketenvak (0 = uitknippen, 1 = voor-/achtervoegsel, 2 = één waardebewerking). */
  stage: 0 | 1 | 2;
  kinds: readonly string[];
  /** De bewerking die de chip aanzet. */
  defaultKind: string;
};

export const OPS: readonly OpDef[] = [
  { id: 'cut', stage: 0, kinds: ['SUBSTRING'], defaultKind: 'SUBSTRING' },
  { id: 'fix', stage: 1, kinds: ['AFFIX', 'PREFIX', 'SUFFIX'], defaultKind: 'AFFIX' },
  { id: 'upper', stage: 2, kinds: ['UPPER'], defaultKind: 'UPPER' },
  { id: 'translate', stage: 2, kinds: ['MAP', 'MAP_CONTAINS'], defaultKind: 'MAP' },
  { id: 'divide', stage: 2, kinds: ['DIVIDE'], defaultKind: 'DIVIDE' },
  { id: 'multiply', stage: 2, kinds: ['MULTIPLY'], defaultKind: 'MULTIPLY' },
  { id: 'percentage', stage: 2, kinds: ['PERCENTAGE'], defaultKind: 'PERCENTAGE' },
];

const KNOWN_KINDS = new Set<string>(['NONE', ...OPS.flatMap((op) => op.kinds)]);

/** Waar een bewerking in het formulier staat: de enkele bewerking of een ketenvak. */
export type StageWhere = 'single' | 0 | 1 | 2;

type Present = { where: StageWhere; stage: Stage };

function present(form: MappingForm): Present[] {
  if (form.chain) {
    return form.stages.flatMap((stage, index) =>
      stage.kind === '' ? [] : [{ where: index as 0 | 1 | 2, stage }],
    );
  }
  return form.single.kind === 'NONE' ? [] : [{ where: 'single' as const, stage: form.single }];
}

/** De bewerkingen in het formulier die de chips niet kennen. */
export function unsupportedKinds(form: MappingForm): string[] {
  return present(form)
    .map((entry) => entry.stage.kind)
    .filter((kind) => !KNOWN_KINDS.has(kind));
}

export function activeOps(form: MappingForm): Set<OpId> {
  const kinds = present(form).map((entry) => entry.stage.kind);
  return new Set(OPS.filter((op) => kinds.some((kind) => op.kinds.includes(kind))).map((op) => op.id));
}

/** De instellingen van een actieve bewerking (voor de invoervelden onder de chips). */
export function stageOfOp(form: MappingForm, id: OpId): { where: StageWhere; stage: Stage } | null {
  const op = OPS.find((candidate) => candidate.id === id);
  if (op === undefined) {
    return null;
  }
  return present(form).find((entry) => op.kinds.includes(entry.stage.kind)) ?? null;
}

export function withStage(form: MappingForm, where: StageWhere, stage: Stage): MappingForm {
  if (where === 'single') {
    return { ...form, single: stage };
  }
  const stages: [Stage, Stage, Stage] = [form.stages[0], form.stages[1], form.stages[2]];
  stages[where] = stage;
  return { ...form, stages };
}

/**
 * Zet een bewerking aan of uit. Een bestaande keten blijft een keten zolang er een bewerking in staat; zonder keten wordt
 * het een keten vanaf twee bewerkingen. De drie waardebewerkingen van vak 3 sluiten elkaar uit: een nieuwe vervangt de oude.
 */
export function setOp(form: MappingForm, id: OpId, on: boolean): MappingForm {
  const op = OPS.find((candidate) => candidate.id === id);
  if (op === undefined || unsupportedKinds(form).length > 0) {
    return form;
  }
  const slots: [Stage, Stage, Stage] = [emptyStage(), emptyStage(), emptyStage()];
  for (const entry of present(form)) {
    const index = CHAIN_STAGE_KINDS.findIndex((kinds) => kinds.includes(entry.stage.kind));
    if (index >= 0) {
      slots[index] = form.chain ? entry.stage : { ...entry.stage, extra: '' };
    }
  }
  const notation = form.chain ? form.mappingExtra : form.single.extra;
  const current = slots[op.stage];
  if (on) {
    if (op.kinds.includes(current.kind)) {
      return form;
    }
    slots[op.stage] = emptyStage(op.defaultKind);
  } else if (op.kinds.includes(current.kind)) {
    slots[op.stage] = emptyStage();
  } else {
    return form;
  }

  const filled = slots.filter((slot) => slot.kind !== '');
  const first = filled[0];
  if (filled.length === 0) {
    return { ...form, chain: false, stages: slots, mappingExtra: '', single: { ...emptyStage('NONE'), extra: notation } };
  }
  if (form.chain || filled.length >= 2 || first === undefined) {
    return { ...form, chain: true, stages: slots, mappingExtra: notation, single: emptyStage('NONE') };
  }
  return {
    ...form,
    chain: false,
    stages: [emptyStage(), emptyStage(), emptyStage()],
    mappingExtra: '',
    single: { ...first, extra: notation },
  };
}

/** Een leeg extra veld voor "Veld toevoegen": een kolom, niet kritiek, niet verplicht, zonder bewerking. */
export function newMappingTemplate(revisionId: number, targetFieldCode: string): MappingView {
  return {
    id: 0,
    revisionId,
    sequenceNumber: 0,
    targetFieldCode,
    sourceReference: '',
    valueKind: 'SOURCE_FIELD',
    criticality: 'NON_CRITICAL',
    expectedPosition: null,
    fixedValue: null,
    defaultValue: null,
    required: false,
    maxLength: null,
    decimalScale: null,
    zeroAllowed: false,
    negativeAllowed: false,
    transformKind: 'NONE',
    transformConfig: null,
    valueMap: [],
    steps: [],
  };
}
