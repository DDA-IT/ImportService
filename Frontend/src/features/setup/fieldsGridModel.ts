/**
 * E1 — het model achter het Velden-grid van "Bestand en velden": welke rijen er in de groepen Herkenning van het artikel
 * en Basis staan (en uit welke velden van de versie), wanneer een rij "Ontbreekt", en de chips van een extra veld.
 * Puur, zonder React; één bron voor het leesbeeld en het zijpaneel.
 */

import type { FieldCatalogResponse, MappingView, RevisionDetail } from '../../api/types.ts';
import { DICTIONARY, termLabel } from '../../terms/index.ts';
import { EDITOR_TEXT, MAPPING_TEXT, type IdentityRowCode } from '../../terms/setupEditor.ts';
import type { LegacyRowKey } from './legacyOrigin.ts';

/** De rijen die in E1 via het zijpaneel bewerkt worden (Herkenning en Basis). */
export type CoreRowKey = 'supplier' | 'group' | 'reference' | 'discount' | 'basePrice' | 'description' | 'currency';

export type GridSource =
  | { kind: 'column'; column: string }
  | { kind: 'fixed'; value: string }
  | { kind: 'none' };

export type CoreRow = {
  key: CoreRowKey;
  /** Het woord van het veld op het scherm (Leverancier, Groep, Referentie, Kortingscode, Basisprijs, ...). */
  label: string;
  source: GridSource;
  required: boolean;
  legacyKey: LegacyRowKey | null;
};

function columnSource(column: string | null | undefined): GridSource {
  return column !== null && column !== undefined && column.trim() !== '' ? { kind: 'column', column } : { kind: 'none' };
}

function identityLabel(code: IdentityRowCode): string {
  return EDITOR_TEXT.identityLabels[code];
}

export function identityRows(revision: RevisionDetail): CoreRow[] {
  const fixedSupplier = revision.identitySupplierFixedValue;
  const fixedGroup = revision.identitySupplierGroupFixedValue;
  return [
    {
      key: 'supplier',
      label: identityLabel('SUPPLIER'),
      source:
        fixedSupplier != null && fixedSupplier !== ''
          ? { kind: 'fixed', value: fixedSupplier }
          : columnSource(revision.identitySupplierField),
      required: true,
      legacyKey: { revisionField: 'identitySupplierField' },
    },
    {
      key: 'group',
      label: identityLabel('SUPPLIER_GROUP'),
      source:
        fixedGroup != null && fixedGroup !== ''
          ? { kind: 'fixed', value: fixedGroup }
          : columnSource(revision.identitySupplierGroupField),
      required: true,
      legacyKey: { revisionField: 'identitySupplierGroupField' },
    },
    {
      key: 'reference',
      label: identityLabel('SUPPLIER_REFERENCE'),
      source: columnSource(revision.identitySupplierReferenceField),
      required: true,
      legacyKey: { revisionField: 'identitySupplierReferenceField' },
    },
    {
      key: 'discount',
      label: identityLabel('DISCOUNT_CODE'),
      source: columnSource(revision.identityDiscountCodeField),
      required: false,
      legacyKey: { revisionField: 'identityDiscountCodeField' },
    },
  ];
}

export function basisRows(revision: RevisionDetail): CoreRow[] {
  return [
    {
      key: 'basePrice',
      label: termLabel('fieldKey', 'BASE_PRICE', 'Basisprijs'),
      source: columnSource(revision.recordBasePriceField),
      required: true,
      legacyKey: { revisionField: 'recordBasePriceField' },
    },
    {
      key: 'description',
      label: termLabel('fieldKey', 'DESCRIPTION', 'Omschrijving'),
      source: columnSource(revision.recordDescriptionField),
      required: false,
      legacyKey: { revisionField: 'recordDescriptionField' },
    },
    {
      key: 'currency',
      label: termLabel('fieldKey', 'CURRENCY', 'Valuta'),
      source: columnSource(revision.recordCurrencyField),
      required: false,
      legacyKey: null,
    },
  ];
}

/** Een verplichte rij zonder bron: status "Ontbreekt". */
export function coreRowMissing(row: CoreRow): boolean {
  return row.required && row.source.kind === 'none';
}

/** Heeft een extra veld een bron: een kolom of een vaste waarde. */
export function mappingHasSource(mapping: MappingView): boolean {
  return (
    (mapping.sourceReference ?? '').trim() !== '' ||
    (mapping.fixedValue !== undefined && mapping.fixedValue !== null && mapping.fixedValue !== '')
  );
}

export function mappingMissing(mapping: MappingView): boolean {
  return mapping.required === true && !mappingHasSource(mapping);
}

/** De bewerkingen van een extra veld als chips: de stappen van de keten, anders het aantal vertalingen. */
export function mappingChips(mapping: MappingView): string[] {
  const steps = (mapping.steps ?? []).map((step) => termLabel('transformKind', step.kind, 'andere bewerking'));
  if (steps.length > 0) {
    return steps;
  }
  const translations = mapping.valueMap?.length ?? 0;
  return translations > 0 ? [EDITOR_TEXT.translationChip(translations)] : [];
}

// --- E2/EF1: extra velden toevoegen, de veldcatalogus en het groeperen van Verkoopprijs 1-5 ---

export type CatalogueGroup = 'prices' | 'references' | 'other';

export type CatalogueCriticality = 'CRITICAL' | 'NON_CRITICAL';

export type CatalogueField = {
  code: string;
  label: string;
  group: CatalogueGroup;
  /** Een korte opmerking onder de naam (bv. dat Prodis het veld beheert). */
  note: string | null;
  /** Alleen een actief veld is kiesbaar voor een nieuwe koppeling; een niet actief veld blijft zichtbaar als het gekoppeld is. */
  active: boolean;
  /** De kritiek die een nieuwe koppeling op dit veld krijgt (EB1; par. 15.1). */
  defaultCriticality: CatalogueCriticality;
  /** `false` = altijd kritiek (een referentieveld): de keuze is vergrendeld. */
  criticalityChangeable: boolean;
};

/** De standaard kritiek van een revisie-eigen veld (Herkenning en Basis). */
export type CatalogueRevisionField = {
  identity: boolean;
  defaultCriticality: CatalogueCriticality;
  criticalityChangeable: boolean;
};

/**
 * De veldcatalogus zoals de editor ze gebruikt. `source = 'fallback'`: `GET /field-catalog` is mislukt en de lijst komt uit het
 * woordenboek (zonder revisievelden); de editor toont dan een melding.
 */
export type FieldCatalogue = {
  source: 'endpoint' | 'fallback';
  fields: CatalogueField[];
  revisionFields: Record<string, CatalogueRevisionField>;
};

/** De velden die al een eigen rij in Herkenning of Basis hebben: ze zijn geen extra veld. */
const CORE_FIELD_CODES: ReadonlySet<string> = new Set([
  'SUPPLIER',
  'SUPPLIER_GROUP',
  'SUPPLIER_REFERENCE',
  'DISCOUNT_CODE',
  'BASE_PRICE',
  'DESCRIPTION',
  'CURRENCY',
]);

const PRICE_CODES: ReadonlySet<string> = new Set(['AKP_PCT', 'VKP1_PCT', 'VKP2_PCT', 'VKP3_PCT', 'VKP4_PCT', 'VKP5_PCT', 'VKP_GROSS_PCT']);
const OTHER_CODES: ReadonlySet<string> = new Set(['BRAND', 'UNIT']);

function asCriticality(value: string): CatalogueCriticality {
  // Een onbekende waarde is nooit "Niet kritiek": fail-safe naar de strengste.
  return value === 'NON_CRITICAL' ? 'NON_CRITICAL' : 'CRITICAL';
}

/**
 * Terugval als `GET /field-catalog` mislukt: de velden van het woordenboek `fieldKey`, met dezelfde standaardregel als de
 * server (par. 15.1): een referentieveld is altijd kritiek (vergrendeld), een prijscomponent standaard kritiek, de rest niet
 * kritiek. Merk en Eenheid beheert Prodis zelf.
 */
export function catalogueFields(): CatalogueField[] {
  return Object.keys(DICTIONARY.fieldKey)
    .filter((code) => !CORE_FIELD_CODES.has(code))
    .map((code) => {
      const group: CatalogueGroup = PRICE_CODES.has(code) ? 'prices' : OTHER_CODES.has(code) ? 'other' : 'references';
      return {
        code,
        label: termLabel('fieldKey', code, 'een ander veld'),
        group,
        note: OTHER_CODES.has(code) ? MAPPING_TEXT.prodisManaged : null,
        active: true,
        defaultCriticality: group === 'other' ? ('NON_CRITICAL' as const) : ('CRITICAL' as const),
        criticalityChangeable: group !== 'references',
      };
    });
}

export function fallbackCatalogue(): FieldCatalogue {
  return { source: 'fallback', fields: catalogueFields(), revisionFields: {} };
}

/**
 * De catalogus uit `GET /field-catalog`: de zeven kernvelden zijn geen extra veld (ze hebben een eigen rij), de volgorde is
 * die van de server (`sortOrder`). Prijscomponent: Prijzen; referentietype: Referenties; de rest Overig. Een veld dat Prodis
 * bezit krijgt de opmerking dat een levering het niet overschrijft. De naam van de server is enkel de terugval voor het woord.
 */
export function catalogueFromResponse(response: FieldCatalogResponse): FieldCatalogue {
  const fields = response.fields
    .filter((entry) => !CORE_FIELD_CODES.has(entry.code))
    .map<CatalogueField>((entry) => ({
      code: entry.code,
      label: termLabel('fieldKey', entry.code, entry.name),
      group: entry.priceComponentCode != null ? 'prices' : entry.referenceType != null ? 'references' : 'other',
      note: entry.defaultOwner === 'PRODIS_USER' ? MAPPING_TEXT.prodisManaged : null,
      active: entry.active,
      defaultCriticality: asCriticality(entry.defaultCriticality),
      criticalityChangeable: entry.criticalityChangeable,
    }));
  const revisionFields: Record<string, CatalogueRevisionField> = {};
  for (const field of response.revisionFields) {
    revisionFields[field.fieldKey] = {
      identity: field.identity,
      defaultCriticality: asCriticality(field.defaultCriticality),
      criticalityChangeable: field.criticalityChangeable,
    };
  }
  return { source: 'endpoint', fields, revisionFields };
}

/**
 * De velden die in de lijst "Veld toevoegen" staan: alle actieve, plus een niet actief veld enkel als een bestaande koppeling
 * het gebruikt (anders valt die koppeling uit beeld). Een niet actief veld is nooit kiesbaar.
 */
export function visibleFields(catalogue: FieldCatalogue, revision: RevisionDetail): CatalogueField[] {
  const mapped = new Set(revision.mappings.map((mapping) => mapping.targetFieldCode));
  return catalogue.fields.filter((field) => field.active || mapped.has(field.code));
}

/** De velden van de catalogus zonder rij in deze versie (enkel actieve: een niet actief veld koppel je niet meer). */
export function unmappedFields(revision: RevisionDetail, catalogue: FieldCatalogue = fallbackCatalogue()): CatalogueField[] {
  const mapped = new Set(revision.mappings.map((mapping) => mapping.targetFieldCode));
  return catalogue.fields.filter((field) => field.active && !mapped.has(field.code));
}

/** Het doelveld waarop een kernrij van Herkenning of Basis de kritiek bewaart (`fieldKey` van de overrule). */
const CORE_ROW_FIELD_KEY: Record<CoreRowKey, string> = {
  supplier: 'SUPPLIER',
  group: 'SUPPLIER_GROUP',
  reference: 'SUPPLIER_REFERENCE',
  discount: 'DISCOUNT_CODE',
  basePrice: 'BASE_PRICE',
  description: 'DESCRIPTION',
  currency: 'CURRENCY',
};

/**
 * De geldende kritiek van een kernrij: de overrule van deze versie als die er is, anders de standaard van de catalogus.
 * `null` zolang beide onbekend zijn (geen catalogus en geen overrule): het scherm toont dan "onbekend", geen verzonnen waarde.
 */
export function coreRowCriticality(
  key: CoreRowKey,
  revision: RevisionDetail,
  catalogue: FieldCatalogue | null,
): CatalogueCriticality | null {
  const fieldKey = CORE_ROW_FIELD_KEY[key];
  const override = revision.fieldCriticalities.find((row) => row.fieldKey === fieldKey);
  if (override !== undefined) {
    return asCriticality(override.criticality);
  }
  return catalogue?.revisionFields[fieldKey]?.defaultCriticality ?? null;
}

export const VKP_CODES = ['VKP1_PCT', 'VKP2_PCT', 'VKP3_PCT', 'VKP4_PCT', 'VKP5_PCT'] as const;

function sameMeaning(a: MappingView, b: MappingView): boolean {
  const pick = (mapping: MappingView) =>
    JSON.stringify([
      mapping.valueKind,
      mapping.sourceReference ?? '',
      mapping.fixedValue ?? null,
      mapping.transformKind ?? null,
      mapping.transformConfig ?? null,
      (mapping.steps ?? []).map((step) => [step.kind, step.config ?? null]),
      (mapping.valueMap ?? []).map((entry) => [entry.source, entry.target]),
    ]);
  return pick(a) === pick(b);
}

/**
 * Verkoopprijs 1-5 (%) als één weergavegroep: alleen als alle vijf bestaan met dezelfde bron en bewerking. Enkel weergave
 * (beslissing O8): bewerken gebeurt per rij. `null` als ze niet alle vijf gelijk zijn.
 */
export function vkpGroup(mappings: readonly MappingView[]): MappingView[] | null {
  const found = VKP_CODES.map((code) => mappings.find((mapping) => mapping.targetFieldCode === code));
  const all = found.filter((mapping): mapping is MappingView => mapping !== undefined);
  const first = all[0];
  if (all.length !== VKP_CODES.length || first === undefined) {
    return null;
  }
  return all.every((mapping) => sameMeaning(first, mapping)) ? all : null;
}
