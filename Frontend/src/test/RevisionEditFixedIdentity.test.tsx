/**
 * G2c-2 — vaste leverancier/groep, hoofdletters en Prodis-keuzelijst in `RevisionEditForm`
 * (`docs/design/g2-design.md` §G2c). Dekt: wisselen kolom ↔ vaste waarde (juiste PATCH-body, de andere bron
 * `""`), hoofdletters, het bevestigingsvinkje (R-REV-X3) bij een wijziging van vaste waarde of hoofdletters,
 * de keuzelijst, de terugval naar vrije tekst bij 409/503, en de getoonde waarschuwingen.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import { ActorProvider } from '../actor/ActorContext';
import { TEST_IDENTITY } from './testIdentity';
import type { RevisionDetail } from '../api/types';
import { RevisionEditForm } from '../features/setup/RevisionEditForm';
import { buildRequest, identityChanged, initialForm } from '../features/setup/revisionEditRequest';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

type FetchMock = ReturnType<typeof vi.fn>;

function mockFetch(handler: (url: string, init: RequestInit | undefined) => Response): FetchMock {
  const mock = vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
    Promise.resolve(handler(typeof input === 'string' ? input : input.toString(), init)),
  );
  global.fetch = mock as unknown as typeof fetch;
  return mock;
}

function isPatch(args: unknown[]): boolean {
  return ((args[1] as RequestInit | undefined)?.method ?? 'GET').toUpperCase() === 'PATCH';
}

function patchBody(mock: FetchMock): Record<string, unknown> {
  const call = mock.mock.calls.find(isPatch);
  expect(call, 'verwacht een PATCH').toBeDefined();
  return JSON.parse(String((call![1] as RequestInit).body)) as Record<string, unknown>;
}

function patchCount(mock: FetchMock): number {
  return mock.mock.calls.filter(isPatch).length;
}

function calledUrl(mock: FetchMock, part: string): boolean {
  return mock.mock.calls.some((args) => String(args[0]).includes(part));
}

const COLUMN_REVISION = {
  id: 100,
  definitionId: 10,
  revisionNumber: 2,
  status: 'DRAFT',
  basedOnRevisionId: 99,
  changeReason: null,
  identityProfileKind: 'THREE_PART',
  identitySupplierField: 'LEV',
  identitySupplierGroupField: 'LEVGRP',
  identitySupplierReferenceField: 'LEVREF',
  identityDiscountCodeField: null,
  identitySupplierFixedValue: null,
  identitySupplierGroupFixedValue: null,
  identityUppercase: false,
  structureFormat: 'CSV',
  structureCharset: 'UTF-8',
  structureDelimiter: ';',
  structureQuoteChar: '"',
  structureHasHeader: true,
  structureHeaderLineNumber: 1,
  structureFieldReferenceKind: 'HEADER_NAME',
  structureExpectedColumnCount: 12,
  accessDeliverySetKind: 'FULL_SNAPSHOT',
  recordBasePriceField: 'PRIJS',
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
  createdAt: '2026-09-28T08:00:00Z',
  createdBy: 'An Beslisser',
  createdBySubject: 'test-sub',
  updatedAt: '2026-09-28T08:00:00Z',
  approvedAt: null,
  approvedBy: null,
  approvedBySubject: null,
  mappings: [],
  filters: [],
  fieldCriticalities: [],
  bookmarks: [],
  bookmarkValues: [],
} as unknown as RevisionDetail;

const FIXED_REVISION: RevisionDetail = {
  ...COLUMN_REVISION,
  identitySupplierField: null,
  identitySupplierGroupField: null,
  identitySupplierFixedValue: '1234',
  identitySupplierGroupFixedValue: 'GROEP A',
};

const VIEW = {
  id: 100,
  definitionId: 10,
  revisionNumber: 2,
  status: 'DRAFT',
  supplierField: null,
  supplierGroupField: 'LEVGRP',
  supplierFixedValue: '1234',
  identityUppercase: false,
};

function renderForm(revision: RevisionDetail, sourceOrganisationCode?: string) {
  const onUpdated = vi.fn();
  render(
    <ActorProvider identity={TEST_IDENTITY}>
      <RevisionEditForm revision={revision} sourceOrganisationCode={sourceOrganisationCode} onUpdated={onUpdated} />
    </ActorProvider>,
  );
  return { onUpdated };
}

function submit() {
  fireEvent.submit(screen.getByTestId('revision-edit-form'));
}

function radios(testId: string): NodeListOf<HTMLInputElement> {
  return screen.getByTestId(testId).querySelectorAll<HTMLInputElement>('input[type=radio]');
}

/** Prodis geeft op elke referentie-aanroep dezelfde fout; de PATCH slaagt. */
function prodisUnavailable(code: string, status: number) {
  return mockFetch((url) =>
    url.includes('/prodis-reference/') ? jsonResponse({ error: 'unavailable', code }, status) : jsonResponse(VIEW),
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('revisionEditRequest — vaste waarden en hoofdletters (pure kant)', () => {
  it('onaangeroerd: alles null, ook de nieuwe velden', () => {
    const built = buildRequest(initialForm(FIXED_REVISION), FIXED_REVISION, false, 'An');
    if (!('body' in built)) {
      throw new Error('verzoek verwacht');
    }
    expect(built.body.supplierField).toBeNull();
    expect(built.body.supplierFixedValue).toBeNull();
    expect(built.body.supplierGroupField).toBeNull();
    expect(built.body.supplierGroupFixedValue).toBeNull();
    expect(built.body.identityUppercase).toBeNull();
  });

  it('identityChanged ziet een vaste waarde, een bronwissel en hoofdletters', () => {
    const base = initialForm(FIXED_REVISION);
    expect(identityChanged(base, FIXED_REVISION)).toBe(false);
    expect(identityChanged({ ...base, supplierFixedValue: '9999' }, FIXED_REVISION)).toBe(true);
    expect(identityChanged({ ...base, supplierGroupFixedValue: 'ANDERS' }, FIXED_REVISION)).toBe(true);
    expect(identityChanged({ ...base, identityUppercase: true }, FIXED_REVISION)).toBe(true);
    expect(identityChanged({ ...base, supplierSource: 'COLUMN', supplierField: 'LEV' }, FIXED_REVISION)).toBe(true);
    // De verborgen tekst van de niet-gekozen bron telt niet mee.
    expect(identityChanged({ ...base, supplierField: 'IGNORED' }, FIXED_REVISION)).toBe(false);
  });

  it('een lege vaste waarde blokkeert (nooit stil leeg)', () => {
    const form = { ...initialForm(COLUMN_REVISION), supplierSource: 'FIXED', supplierFixedValue: '  ' };
    expect('problem' in buildRequest(form, COLUMN_REVISION, true, 'An')).toBe(true);
  });
});

describe('RevisionEditForm — herkenning: kolom of vaste waarde', () => {
  it('overstappen van kolom naar vaste waarde: waarde + "" op de kolom, met bevestiging', async () => {
    const mock = prodisUnavailable('PRODIS_DB_NOT_CONFIGURED', 409);
    renderForm(COLUMN_REVISION);

    fireEvent.click(radios('revision-supplier-source')[1]!);
    fireEvent.change(screen.getByLabelText(/^Vaste leverancier/), { target: { value: '1234' } });

    // R-REV-X3: zonder vinkje wordt er niets verstuurd.
    submit();
    expect(await screen.findByTestId('revision-edit-validation')).toHaveTextContent('Bevestig');
    expect(patchCount(mock)).toBe(0);

    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submit();
    await screen.findByTestId('revision-edit-saved');
    const body = patchBody(mock);
    expect(body.supplierFixedValue).toBe('1234');
    expect(body.supplierField).toBe('');
    expect(body.supplierGroupField).toBeNull();
    expect(body.supplierGroupFixedValue).toBeNull();
    expect(body.identityUppercase).toBeNull();
    expect(body.acknowledgeIdentityChange).toBe(true);
  });

  it('overstappen van vaste waarde naar kolom: kolom + "" op de vaste waarde', async () => {
    const mock = mockFetch((url) => (url.includes('/prodis-reference/') ? jsonResponse([]) : jsonResponse(VIEW)));
    renderForm(FIXED_REVISION);

    fireEvent.click(radios('revision-group-source')[0]!);
    fireEvent.change(screen.getByLabelText(/^Kolom groep/), { target: { value: 'GRP' } });
    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submit();

    await screen.findByTestId('revision-edit-saved');
    const body = patchBody(mock);
    expect(body.supplierGroupField).toBe('GRP');
    expect(body.supplierGroupFixedValue).toBe('');
    expect(body.supplierField).toBeNull();
    expect(body.supplierFixedValue).toBeNull();
  });

  it('een andere vaste waarde laat het bevestigingsvinkje verschijnen en stuurt enkel dat veld', async () => {
    const mock = mockFetch((url) => (url.includes('/prodis-reference/') ? jsonResponse([]) : jsonResponse(VIEW)));
    renderForm(FIXED_REVISION);
    expect(screen.queryByTestId('identity-change-acknowledge')).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Vaste groep/), { target: { value: 'GROEP B' } });
    expect(screen.getByTestId('identity-change-acknowledge')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submit();

    await screen.findByTestId('revision-edit-saved');
    const body = patchBody(mock);
    expect(body.supplierGroupFixedValue).toBe('GROEP B');
    expect(body.supplierGroupField).toBeNull();
    expect(body.supplierFixedValue).toBeNull();
  });

  it('hoofdletters: een identiteitswijziging met bevestiging, verstuurd als identityUppercase', async () => {
    const mock = mockFetch(() => jsonResponse(VIEW));
    renderForm(COLUMN_REVISION);

    fireEvent.click(screen.getByLabelText(/^Herkenning in hoofdletters/));
    expect(screen.getByTestId('identity-change-acknowledge')).toBeInTheDocument();
    submit();
    expect(await screen.findByTestId('revision-edit-validation')).toHaveTextContent('Bevestig');
    expect(patchCount(mock)).toBe(0);

    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submit();
    await screen.findByTestId('revision-edit-saved');
    const body = patchBody(mock);
    expect(body.identityUppercase).toBe(true);
    expect(body.supplierField).toBeNull();
    expect(body.supplierFixedValue).toBeNull();
  });

  it('toont de waarschuwing PRODIS_REFERENCE_NOT_VERIFIED na bewaren', async () => {
    mockFetch((url) =>
      url.includes('/prodis-reference/')
        ? jsonResponse([])
        : jsonResponse({
            ...VIEW,
            warnings: [
              {
                code: 'PRODIS_REFERENCE_NOT_VERIFIED',
                field: 'supplierFixedValue',
                message: 'supplier 1234 is not verified',
              },
            ],
          }),
    );
    renderForm(FIXED_REVISION);
    fireEvent.change(screen.getByLabelText(/^Vaste leverancier/), { target: { value: '1235' } });
    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submit();

    const warnings = await screen.findByTestId('revision-edit-warnings');
    expect(warnings).toHaveTextContent('Niet gecontroleerd in Prodis');
    expect(warnings).toHaveTextContent('Vaste leverancier');
    expect(warnings.textContent).not.toMatch(/PRODIS_REFERENCE/);
  });

  it('een serverfout SUPPLIER_FIXED_VALUE_NOT_IN_PRODIS krijgt een Nederlandse tekst', async () => {
    mockFetch((_url, init) =>
      init?.method === 'PATCH'
        ? jsonResponse(
            { error: 'supplierFixedValue 1235 does not exist', code: 'SUPPLIER_FIXED_VALUE_NOT_IN_PRODIS' },
            400,
          )
        : jsonResponse([]),
    );
    renderForm(FIXED_REVISION);
    fireEvent.change(screen.getByLabelText(/^Vaste leverancier/), { target: { value: '1235' } });
    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submit();
    expect(await screen.findByText('Deze leverancier bestaat niet in Prodis')).toBeInTheDocument();
  });
});

describe('RevisionEditForm — Prodis-keuzelijst', () => {
  it('toont de leveranciers uit Prodis als suggesties en zoekt op de getypte tekst', async () => {
    const mock = mockFetch((url) =>
      url.includes('/prodis-reference/suppliers?')
        ? jsonResponse([
            { number: '1234', name: 'Acme' },
            { number: '1299', name: 'Beta' },
          ])
        : jsonResponse([]),
    );
    renderForm(FIXED_REVISION);

    await waitFor(() =>
      expect(screen.getByTestId('prodis-options-supplier').querySelectorAll('option')).toHaveLength(2),
    );
    const first = screen.getByTestId('prodis-options-supplier').querySelectorAll('option')[0]!;
    expect(first.getAttribute('value')).toBe('1234');
    expect(first.textContent).toBe('1234 — Acme');

    fireEvent.change(screen.getByLabelText(/^Vaste leverancier/), { target: { value: '12' } });
    await waitFor(() => expect(calledUrl(mock, '/prodis-reference/suppliers?q=12')).toBe(true));
  });

  it('groepen: van de vaste leverancier', async () => {
    const mock = mockFetch((url) =>
      url.includes('/groups')
        ? jsonResponse([{ legacyCode: 'a|b|', groupName: 'GROEP A', discountCode: null }])
        : jsonResponse([]),
    );
    renderForm(FIXED_REVISION, 'ORG-1');
    await waitFor(() =>
      expect(screen.getByTestId('prodis-options-group').querySelectorAll('option')).toHaveLength(1),
    );
    expect(calledUrl(mock, '/prodis-reference/suppliers/1234/groups')).toBe(true);
    expect(calledUrl(mock, '/suppliers/ORG-1/groups')).toBe(false);
  });

  it('groepen: zonder vaste leverancier van de bronorganisatie van de definitie', async () => {
    const mock = mockFetch((url) =>
      url.includes('/groups')
        ? jsonResponse([{ legacyCode: 'a|b|', groupName: 'GROEP A', discountCode: null }])
        : jsonResponse([]),
    );
    renderForm({ ...FIXED_REVISION, identitySupplierField: 'LEV', identitySupplierFixedValue: null }, 'ORG-1');
    await waitFor(() => expect(calledUrl(mock, '/prodis-reference/suppliers/ORG-1/groups')).toBe(true));
  });

  it('zonder leverancier voor de groepen: vrije tekst met uitleg, geen aanroep', () => {
    const mock = mockFetch(() => jsonResponse([]));
    renderForm({ ...FIXED_REVISION, identitySupplierField: 'LEV', identitySupplierFixedValue: null });
    expect(screen.getByTestId('prodis-group-no-supplier')).toBeInTheDocument();
    expect(calledUrl(mock, '/groups')).toBe(false);
  });

  it.each([
    ['PRODIS_DB_NOT_CONFIGURED', 409],
    ['PRODIS_DB_UNAVAILABLE', 503],
  ])('bij %s valt het veld terug op vrije tekst met een melding', async (code, status) => {
    const mock = prodisUnavailable(code, status);
    renderForm(FIXED_REVISION);

    expect(await screen.findByTestId('prodis-unavailable-supplier')).toHaveTextContent('Typ de waarde zelf');
    expect(screen.queryByTestId('prodis-options-supplier')).not.toBeInTheDocument();

    // Vrije tekst werkt gewoon door tot in de PATCH.
    fireEvent.change(screen.getByLabelText(/^Vaste leverancier/), { target: { value: '7777' } });
    fireEvent.click(screen.getByLabelText(/^Ja, ik weet dat elk bestaand artikel/));
    submit();
    await screen.findByTestId('revision-edit-saved');
    expect(patchBody(mock).supplierFixedValue).toBe('7777');
  });
});
