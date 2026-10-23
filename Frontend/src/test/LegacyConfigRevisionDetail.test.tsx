/**
 * S9 (E6: nu via `RevisionReadView`) — het leesbeeld toont (alleen-lezen) de velden die de legacy-config-migratie toevoegde: een vaste leverancier
 * of groep (S6), hoofdletters in de herkenning, het aantal omzettingen en de bewerkingen van een keten (S8a/S8c),
 * "onbekende waarden blijven ongewijzigd" (S8g) en de groep van een filter (S8f). Bewerken valt buiten S9.
 *
 * Dekt ook de bestaande vorm: een revisie zonder de nieuwe velden toont ze niet, en een revisie met een vaste
 * leverancier (kolom `null`) laat zich bewerken zonder dat de lege kolom een verplichtveldfout geeft.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen, configure } from '@testing-library/react';
import { ActorProvider } from '../actor/ActorContext';
import type { RevisionDetail } from '../api/types';
import { RevisionReadView } from '../features/setup/RevisionReadView';
import { buildRequest, initialForm } from '../features/setup/revisionEditRequest';
import { TEST_IDENTITY } from './testIdentity';

const BASE: RevisionDetail = {
  id: 100,
  definitionId: 10,
  revisionNumber: 1,
  status: 'DRAFT',
  basedOnRevisionId: null,
  changeReason: 'Overname uit legacy',
  identityProfileKind: 'THREE_PART',
  identitySupplierField: 'LEV',
  identitySupplierGroupField: 'LEVGRP',
  identitySupplierReferenceField: 'LEVREF',
  identityDiscountCodeField: null,
  structureFormat: 'CSV',
  structureCharset: 'UTF-8',
  structureDelimiter: ';',
  structureQuoteChar: '"',
  structureHasHeader: true,
  structureHeaderLineNumber: 1,
  structureFieldReferenceKind: 'COLUMN_INDEX',
  structureExpectedColumnCount: null,
  accessDeliverySetKind: 'FULL_SNAPSHOT',
  recordBasePriceField: '26',
  recordDescriptionField: null,
  recordCurrencyField: null,
  recordCanonicalisationVersion: 1,
  basePriceZeroAllowed: false,
  basePriceNegativeAllowed: false,
  priceDeviationPercent: 20,
  priceDeviationSeverity: 'WARNING',
  priceDerivationTolerance: 0.01,
  priceAvgShortWindow: 3,
  priceAvgLongWindow: 12,
  priceControlModel: 'DEVIATION',
  creationThresholdAbsolute: 100,
  creationThresholdSharePercent: 30,
  maxCriticalRecords: 0,
  maxRejectedRecords: null,
  maxCriticalSharePercent: 5,
  maxRejectedSharePercent: 10,
  bulkIncidentSharePercent: 15,
  accessVersion: 1,
  accessConfigHash: 'aaaa',
  structureVersion: 1,
  structureConfigHash: 'bbbb',
  recordRulesVersion: 1,
  recordRulesConfigHash: 'cccc',
  compositeConfigHash: 'dddd',
  createdAt: '2026-10-21T08:00:00Z',
  createdBy: 'An Beslisser',
  createdBySubject: 'test-sub',
  updatedAt: null,
  approvedAt: null,
  approvedBy: null,
  approvedBySubject: null,
  mappings: [],
  filters: [],
  fieldCriticalities: [],
  bookmarks: [],
  bookmarkValues: [],
};

const MIGRATED: RevisionDetail = {
  ...BASE,
  identitySupplierField: null,
  identitySupplierGroupField: null,
  identitySupplierFixedValue: '1042',
  identitySupplierGroupFixedValue: 'GRP-A',
  identityUppercase: true,
  mappings: [
    {
      id: 1,
      revisionId: 100,
      sequenceNumber: 1,
      targetFieldCode: 'EAN',
      sourceReference: '5',
      valueKind: 'SOURCE_FIELD',
      criticality: 'CRITICAL',
      valueMap: [
        { source: 'A', target: 'B' },
        { source: 'C', target: 'D' },
      ],
      steps: [
        { kind: 'SUBSTRING', config: 'start=1;length=3' },
        { kind: 'AFFIX', config: 'prefix=X' },
        { kind: 'MAP', config: 'caseSensitive=true;onMiss=KEEP_SOURCE' },
      ],
    },
  ],
  filters: [
    { id: 9, revisionId: 100, sequenceNumber: 1, sourceReference: '3', operator: 'EQUALS', compareValue: 'X', outcome: 'EXCLUDE', matchGroup: 2 },
    { id: 10, revisionId: 100, sequenceNumber: 2, sourceReference: '4', operator: 'EQUALS', compareValue: 'Y', outcome: 'EXCLUDE', matchGroup: null },
  ],
};

function renderView(detail: RevisionDetail) {
  return render(
    <ActorProvider identity={TEST_IDENTITY}>
      <RevisionReadView revision={detail} />
    </ActorProvider>,
  );
}

// Onder belasting (volledige suite) kan de asynchrone preview langer dan de standaard 1 s duren.
configure({ asyncUtilTimeout: 10000 });

afterEach(() => cleanup());

describe('leesbeeld — velden van de legacy-config-migratie', () => {
  it('toont vaste leverancier en groep, hoofdletters, omzettingen, bewerkingen en de groep van een filter', () => {
    renderView(MIGRATED);

    expect(screen.getByTestId('grid-row-supplier')).toHaveTextContent('1042');
    expect(screen.getByTestId('grid-row-group')).toHaveTextContent('GRP-A');
    expect(screen.getByTestId('uppercase-chip')).toHaveTextContent('Hoofdletters negeren: ja');

    const mapping = screen.getByTestId('read-field-1');
    expect(mapping).toHaveTextContent('Deel van de tekst');
    expect(mapping).toHaveTextContent('Voor- en achtervoegsel');
    expect(mapping).toHaveTextContent('Waarde omzetten');

    // De vertalingen en "onbekende waarden blijven ongewijzigd" staan in de sectie Vertalingen.
    const translation = screen.getByTestId('translation-EAN');
    expect(translation).toHaveTextContent('2 waarden');
    expect(translation).toHaveTextContent('onveranderd laten');

    // Een groep is één zin; een losse rij staat erbuiten.
    expect(screen.getByTestId('group-2')).toHaveTextContent('(alle voorwaarden samen)');
    expect(screen.getByTestId('filter-10')).not.toHaveTextContent('alle voorwaarden samen');
  });

  it('toont de nieuwe velden niet voor een revisie zonder die velden (oudere vorm)', () => {
    renderView({ ...BASE, mappings: [{ id: 1, revisionId: 100, sequenceNumber: 1, targetFieldCode: 'EAN', sourceReference: '5', valueKind: 'SOURCE_FIELD', criticality: 'CRITICAL' }] });

    expect(screen.queryByTestId('uppercase-chip')).not.toBeInTheDocument();
    expect(screen.getByTestId('read-field-1')).not.toHaveTextContent('Vertaling');
    expect(screen.queryByTestId('translation-EAN')).not.toBeInTheDocument();
  });
});

describe('bewerkformulier bij een vaste leverancier (kolom null)', () => {
  it('geeft geen verplichtveldfout voor de lege kolom en stuurt die kolom niet mee', () => {
    const form = { ...initialForm(MIGRATED), delimiter: ',' };
    expect(form.supplierField).toBe('');
    const built = buildRequest(form, MIGRATED, false, 'An Beslisser');
    expect('body' in built).toBe(true);
    if ('body' in built) {
      expect(built.body.delimiter).toBe(',');
      expect(built.body.supplierField).toBeNull();
      expect(built.body.supplierGroupField).toBeNull();
    }
  });

  it('blijft een lege kolom weigeren bij een revisie met een kolom', () => {
    const form = { ...initialForm(BASE), supplierField: '' };
    const built = buildRequest(form, BASE, false, 'An Beslisser');
    expect('problem' in built && built.problem).toContain('verplicht');
  });
});
