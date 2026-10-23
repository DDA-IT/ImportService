/**
 * E1 — het model achter het Velden-grid van "Bestand en velden": welke rijen er in de groepen Herkenning van het artikel
 * en Basis staan (en uit welke velden van de versie), wanneer een rij "Ontbreekt", en de chips van een extra veld.
 * Puur, zonder React; één bron voor het leesbeeld en het zijpaneel.
 */

import type { MappingView, RevisionDetail } from '../../api/types.ts';
import { termLabel } from '../../terms/index.ts';
import { EDITOR_TEXT, type IdentityRowCode } from '../../terms/setupEditor.ts';
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
