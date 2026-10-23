/**
 * EF1 — de veldcatalogus uit `GET /field-catalog` (EB1) in de editor van "Bestand en velden": de lijst "Veld toevoegen" en
 * "Nog niet gekoppeld" komen van het endpoint (alleen actieve velden kiesbaar, een niet meer actief veld enkel zichtbaar als
 * het gekoppeld is, groepen Prijzen / Referenties / Overig), de terugval op het woordenboek met melding, de standaard kritiek
 * van een nieuwe koppeling per veld, de vergrendelde keuze bij een referentieveld, `CRITICALITY_INVALID` en de geldende
 * kritiek van de kernrijen Herkenning en Basis.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { emptyMappingForm } from './mappingFixtures';
import {
  catalogEntry,
  defaultFieldCatalog,
  defaultRevisionFields,
  definition,
  emptySetupState,
  installSetupServer,
  jsonResponse,
  link,
  organisation,
  renderRoutes,
  revisionDetail,
  task,
  type SetupState,
} from './setupServer';
import type { FieldCatalogResponse } from '../api/types';
import {
  catalogueFromResponse,
  coreRowCriticality,
  fallbackCatalogue,
  unmappedFields,
  visibleFields,
} from '../features/setup/fieldsGridModel';
import { newMappingTemplate } from '../features/setup/mappingPanelModel';

const originalFetch = global.fetch;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  global.fetch = originalFetch;
});

function mapping(id: number, code: string, over: Record<string, unknown> = {}) {
  return { ...emptyMappingForm({ id, sequenceNumber: id - 5000, targetFieldCode: code }), ...over };
}

type CatalogOverrides = { extra?: Record<string, unknown>[]; replace?: Record<string, Record<string, unknown>> };

/** De standaardcatalogus met extra velden en/of afwijkingen per code (op de velden van `defaultFieldCatalog`). */
function catalog({ extra = [], replace = {} }: CatalogOverrides = {}) {
  const base = defaultFieldCatalog();
  return {
    ...base,
    fields: [
      ...base.fields.map((field) => (replace[field.code] === undefined ? field : { ...field, ...replace[field.code] })),
      ...extra,
    ],
  };
}

function state(
  mappings: Record<string, unknown>[],
  fieldCatalog: unknown | null | undefined,
  detail: Record<string, unknown> = {},
): SetupState {
  const s = emptySetupState();
  s.organisations = [organisation(1, { code: 'VROOAM', name: 'Vrooam BV' })];
  s.definitions = [
    definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: 99, draftRevisionId: 100, draftRevisionNumber: 4 }),
  ];
  s.links = [link(501, 10)];
  s.tasks = [task(7, 501, 10, { activeRevisionId: 99 })];
  s.revisions[10] = [
    { id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT' },
    { id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' },
  ];
  s.details[99] = revisionDetail(99, 10, { revisionNumber: 3, status: 'ACTIVE', identityUppercase: false });
  s.details[100] = revisionDetail(100, 10, {
    revisionNumber: 4,
    status: 'DRAFT',
    identityUppercase: false,
    identitySupplierFixedValue: null,
    identitySupplierGroupFixedValue: null,
    mappings,
    ...detail,
  });
  s.fieldCatalog = fieldCatalog;
  return s;
}

type Call = { method: string; url: string; body: Record<string, unknown> | null };
type Options = { post?: () => Response; put?: () => Response };

async function openEditor(s: SetupState, options: Options = {}, path = '/setup/suppliers/1/fields?definitie=10&versie=100&bewerken=1') {
  const calls: Call[] = [];
  installSetupServer(s, (url, init) => {
    const method = (init?.method ?? 'GET').toUpperCase();
    if (method === 'GET' || !/\/setup\/revisions\/100\/mappings(\/(\d+))?$/.test(url)) {
      return null;
    }
    const body = init?.body === undefined ? null : (JSON.parse(String(init.body)) as Record<string, unknown>);
    calls.push({ method, url, body });
    if (method === 'PUT') {
      return options.put?.() ?? jsonResponse({ ...(body ?? {}), id: 9001, revisionId: 100, sequenceNumber: 1 });
    }
    return options.post?.() ?? jsonResponse({ ...(body ?? {}), id: 9002, revisionId: 100, sequenceNumber: 9 }, 201);
  });
  renderRoutes(path);
  await screen.findByTestId('read-fields');
  return calls;
}

async function openAddList(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByTestId('add-field'));
  return screen.findByRole('dialog', { name: 'Veld toevoegen' });
}

async function pick(panel: HTMLElement, code: string, label: string): Promise<HTMLElement> {
  fireEvent.click(within(panel).getByTestId(`add-pick-${code}`));
  return screen.findByRole('dialog', { name: `Veld toevoegen: ${label}` });
}

describe('de lijst komt van het endpoint', () => {
  it('toont de velden van de server, met het woordenboek als label en de naam van de server als terugval', async () => {
    const s = state(
      [],
      catalog({ extra: [catalogEntry('NIEUW_VELD', { name: 'Nieuw veld van de server', sortOrder: 70 })] }),
    );
    const calls = await openEditor(s);
    expect(calls).toHaveLength(0);
    const panel = await openAddList();
    // Een veld uit het woordenboek: het woord van het woordenboek, niet de naam van de server.
    expect(within(panel).getByTestId('add-item-EAN')).toHaveTextContent('EAN-barcode');
    // Een veld zonder woordenboekingang: de naam van de server.
    expect(within(panel).getByTestId('add-item-NIEUW_VELD')).toHaveTextContent('Nieuw veld van de server');
    // De zeven kernvelden hebben een eigen rij: geen extra veld.
    for (const core of ['SUPPLIER', 'SUPPLIER_GROUP', 'SUPPLIER_REFERENCE', 'DISCOUNT_CODE', 'BASE_PRICE', 'DESCRIPTION', 'CURRENCY']) {
      expect(within(panel).queryByTestId(`add-item-${core}`)).not.toBeInTheDocument();
    }
    expect(within(panel).queryByTestId('catalogue-fallback')).not.toBeInTheDocument();
  });

  it('"Nog niet gekoppeld" telt de actieve velden zonder koppeling en kan een veld van de server koppelen', async () => {
    const s = state(
      [mapping(5001, 'EAN', { criticality: 'CRITICAL' })],
      catalog({ extra: [catalogEntry('NIEUW_VELD', { name: 'Nieuw veld van de server', sortOrder: 70 })] }),
    );
    await openEditor(s);
    // 15 velden van het woordenboek buiten de kern, min EAN (gekoppeld), plus het nieuwe veld.
    const toggle = await screen.findByTestId('unmapped-toggle');
    expect(toggle).toHaveTextContent('Nog niet gekoppeld (15)');
    fireEvent.click(toggle);
    expect(screen.getByTestId('unmapped-NIEUW_VELD')).toHaveTextContent('Nieuw veld van de server');
    fireEvent.click(screen.getByRole('button', { name: 'Koppelen: Nieuw veld van de server' }));
    expect(await screen.findByRole('dialog', { name: 'Veld toevoegen: Nieuw veld van de server' })).toBeInTheDocument();
  });
});

describe('niet meer actieve velden', () => {
  const inactive = { replace: { PIM_ID: { active: false }, UNIT: { active: false }, BRAND: { active: false } } };

  it('een niet actief en niet gekoppeld veld is nergens kiesbaar of zichtbaar', async () => {
    const s = state([mapping(5001, 'EAN', { criticality: 'CRITICAL' })], catalog(inactive));
    await openEditor(s);
    const panel = await openAddList();
    expect(within(panel).queryByTestId('add-item-PIM_ID')).not.toBeInTheDocument();
    expect(within(panel).queryByTestId('add-item-UNIT')).not.toBeInTheDocument();
    expect(within(panel).queryByTestId('add-item-BRAND')).not.toBeInTheDocument();
    fireEvent.click(within(panel).getByRole('button', { name: 'Paneel sluiten' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    // 14 niet gekoppelde velden min de drie niet actieve = 11.
    expect(screen.getByTestId('unmapped-toggle')).toHaveTextContent('Nog niet gekoppeld (11)');
  });

  it('een niet actief veld dat een koppeling gebruikt blijft zichtbaar, gemarkeerd als "niet meer actief" en niet kiesbaar', async () => {
    const s = state(
      [mapping(5001, 'EAN', { criticality: 'CRITICAL' }), mapping(5002, 'BRAND', { sourceReference: 'MERK' })],
      catalog(inactive),
    );
    await openEditor(s);
    const panel = await openAddList();
    const brand = within(panel).getByTestId('add-item-BRAND');
    expect(brand).toHaveTextContent('Merk');
    expect(brand).toHaveTextContent('niet meer actief');
    expect(brand).toHaveTextContent('al gekoppeld');
    expect(within(panel).queryByTestId('add-pick-BRAND')).not.toBeInTheDocument();
    // De bestaande koppeling zelf blijft in het grid, ook niet actief.
    expect(screen.getByTestId('read-field-5002')).toBeInTheDocument();
    expect(within(panel).queryByTestId('add-item-UNIT')).not.toBeInTheDocument();
  });
});

describe('groepen', () => {
  it('prijscomponent: Prijzen, referentietype: Referenties, de rest: Overig', async () => {
    const s = state(
      [],
      catalog({
        extra: [
          catalogEntry('EXTRA_PRIJS', { name: 'Extra prijs', priceComponentCode: 'EXTRA', sortOrder: 80 }),
          catalogEntry('EXTRA_REF', { name: 'Extra referentie', referenceType: 'EXTRA', sortOrder: 81 }),
          catalogEntry('EXTRA_TEKST', { name: 'Extra tekst', sortOrder: 82 }),
        ],
      }),
    );
    await openEditor(s);
    const panel = await openAddList();
    const prices = within(panel).getByRole('region', { name: 'Prijzen' });
    const references = within(panel).getByRole('region', { name: 'Referenties' });
    const other = within(panel).getByRole('region', { name: 'Overig' });
    expect(within(prices).getByTestId('add-item-AKP_PCT')).toBeInTheDocument();
    expect(within(prices).getByTestId('add-item-EXTRA_PRIJS')).toBeInTheDocument();
    expect(within(references).getByTestId('add-item-EAN')).toBeInTheDocument();
    expect(within(references).getByTestId('add-item-EXTRA_REF')).toBeInTheDocument();
    expect(within(other).getByTestId('add-item-BRAND')).toHaveTextContent('Wordt in Prodis beheerd');
    expect(within(other).getByTestId('add-item-EXTRA_TEKST')).toBeInTheDocument();
    expect(within(prices).queryByTestId('add-item-EAN')).not.toBeInTheDocument();
    expect(within(other).queryByTestId('add-item-AKP_PCT')).not.toBeInTheDocument();
  });
});

describe('terugval als het endpoint faalt', () => {
  it('toont de lijst van het woordenboek met een melding, in het paneel en in het grid', async () => {
    const s = state([mapping(5001, 'EAN', { criticality: 'CRITICAL' })], null);
    await openEditor(s);
    expect(await screen.findByTestId('grid-catalogue-fallback')).toHaveTextContent('kon niet geladen worden');
    const panel = await openAddList();
    expect(within(panel).getByTestId('catalogue-fallback')).toHaveTextContent('standaardlijst');
    expect(within(panel).getByTestId('add-item-EAN')).toHaveTextContent('al gekoppeld');
    expect(within(panel).getByTestId('add-item-PIM_ID')).toHaveTextContent('PIM-nummer');
    expect(within(panel).getByTestId('add-item-BRAND')).toBeInTheDocument();
  });

  it('de terugval volgt dezelfde standaardregel: referentie vergrendeld kritiek, Prodis-veld niet kritiek', async () => {
    const s = state([], null);
    await openEditor(s);
    let panel = await openAddList();
    panel = await pick(panel, 'PIM_ID', 'PIM-nummer');
    expect(within(panel).getByRole('radio', { name: 'Kritiek (altijd)' })).toBeChecked();
    fireEvent.click(within(panel).getByTestId('panel-back'));
    panel = await screen.findByRole('dialog', { name: 'Veld toevoegen' });
    panel = await pick(panel, 'BRAND', 'Merk');
    expect(within(panel).getByRole('radio', { name: 'Niet kritiek' })).toBeChecked();
  });

  it('de kernrijen tonen een streepje als er geen catalogus en geen aanpassing is', async () => {
    const s = state([], null);
    await openEditor(s, {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    expect(await screen.findByTestId('critical-supplier')).toHaveTextContent('—');
  });
});

describe('standaard kritiek van een nieuwe koppeling', () => {
  it('een prijscomponent start kritiek en blijft instelbaar; de POST-body draagt Kritiek', async () => {
    const s = state([], catalog());
    const calls = await openEditor(s);
    let panel = await openAddList();
    panel = await pick(panel, 'AKP_PCT', 'Aankoopprijs (%)');
    expect(within(panel).getByRole('radio', { name: 'Kritiek' })).toBeChecked();
    expect(within(panel).getByRole('radio', { name: 'Niet kritiek' })).not.toBeDisabled();
    fireEvent.change(within(panel).getByLabelText('Kolomnaam'), { target: { value: 'AKP' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.method).toBe('POST');
    expect(calls[0]!.body).toMatchObject({ targetFieldCode: 'AKP_PCT', criticality: 'CRITICAL' });
  });

  it('een gewoon veld start niet kritiek en kan op Kritiek gezet worden', async () => {
    const s = state([], catalog());
    const calls = await openEditor(s);
    let panel = await openAddList();
    panel = await pick(panel, 'UNIT', 'Eenheid');
    expect(within(panel).getByRole('radio', { name: 'Niet kritiek' })).toBeChecked();
    fireEvent.change(within(panel).getByLabelText('Kolomnaam'), { target: { value: 'EENHEID' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.body).toMatchObject({ targetFieldCode: 'UNIT', criticality: 'NON_CRITICAL' });
  });

  it('de standaard komt van de catalogus, niet uit de client: een afwijkende server wint', async () => {
    const s = state([], catalog({ replace: { UNIT: { defaultCriticality: 'CRITICAL' } } }));
    await openEditor(s);
    let panel = await openAddList();
    panel = await pick(panel, 'UNIT', 'Eenheid');
    expect(within(panel).getByRole('radio', { name: 'Kritiek' })).toBeChecked();
    expect(within(panel).getByRole('radio', { name: 'Niet kritiek' })).not.toBeChecked();
  });

  it('Koppelen uit "Nog niet gekoppeld" gebruikt dezelfde standaard', async () => {
    const s = state([], catalog());
    await openEditor(s);
    fireEvent.click(await screen.findByTestId('unmapped-toggle'));
    fireEvent.click(screen.getByRole('button', { name: 'Koppelen: Verkoopprijs 2 (%)' }));
    const panel = await screen.findByRole('dialog', { name: 'Veld toevoegen: Verkoopprijs 2 (%)' });
    expect(within(panel).getByRole('radio', { name: 'Kritiek' })).toBeChecked();
  });
});

describe('vergrendelde kritiek', () => {
  it('een nieuw referentieveld is "Kritiek (altijd)" met uitleg; de POST-body draagt Kritiek', async () => {
    const s = state([], catalog());
    const calls = await openEditor(s);
    let panel = await openAddList();
    panel = await pick(panel, 'PIM_ID', 'PIM-nummer');
    const locked = within(panel).getByRole('radio', { name: 'Kritiek (altijd)' });
    expect(locked).toBeChecked();
    expect(locked).toBeDisabled();
    expect(within(panel).queryByRole('radio', { name: 'Niet kritiek' })).not.toBeInTheDocument();
    expect(within(panel).getByTestId('critical-locked-hint')).toHaveTextContent('Een referentieveld is altijd kritiek');
    fireEvent.change(within(panel).getByLabelText('Kolomnaam'), { target: { value: 'PIM' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.body).toMatchObject({ targetFieldCode: 'PIM_ID', criticality: 'CRITICAL' });
  });

  it('een bestaande koppeling op een referentieveld is vergrendeld; een oude NON_CRITICAL-waarde wordt niet aangeboden', async () => {
    const s = state([mapping(5001, 'EAN', { sourceReference: 'BARCODE', criticality: 'NON_CRITICAL' })], catalog());
    const calls = await openEditor(s);
    fireEvent.click(await screen.findByRole('button', { name: 'Wijzigen: EAN-barcode' }));
    const panel = await screen.findByRole('dialog', { name: 'Wijzigen: EAN-barcode' });
    expect(within(panel).getByRole('radio', { name: 'Kritiek (altijd)' })).toBeChecked();
    expect(within(panel).queryByRole('radio', { name: 'Niet kritiek' })).not.toBeInTheDocument();
    // Het paneel start vergrendeld op Kritiek: dat telt niet als wijziging.
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    expect(await within(panel).findByTestId('panel-problem')).toHaveTextContent('Er is niets gewijzigd');
    expect(calls).toHaveLength(0);
    fireEvent.change(within(panel).getByLabelText('Kolomnaam'), { target: { value: 'EAN_KOLOM' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.method).toBe('PUT');
    expect(calls[0]!.body).toMatchObject({ targetFieldCode: 'EAN', criticality: 'CRITICAL' });
  });

  it('ook een veld zonder referentietype is vergrendeld als de catalogus criticalityChangeable=false meegeeft', async () => {
    const s = state(
      [],
      catalog({ extra: [catalogEntry('VAST_KRITIEK', { name: 'Vast kritiek veld', defaultCriticality: 'CRITICAL', criticalityChangeable: false, sortOrder: 90 })] }),
    );
    await openEditor(s);
    let panel = await openAddList();
    panel = await pick(panel, 'VAST_KRITIEK', 'Vast kritiek veld');
    expect(within(panel).getByRole('radio', { name: 'Kritiek (altijd)' })).toBeDisabled();
  });

  it('een veld met instelbare kritiek toont geen vergrendeling', async () => {
    const s = state([mapping(5002, 'BRAND', { sourceReference: 'MERK', criticality: 'CRITICAL' })], catalog());
    await openEditor(s);
    fireEvent.click(await screen.findByRole('button', { name: 'Wijzigen: Merk' }));
    const panel = await screen.findByRole('dialog', { name: 'Wijzigen: Merk' });
    expect(within(panel).queryByTestId('critical-locked-hint')).not.toBeInTheDocument();
    expect(within(panel).getByRole('radio', { name: 'Kritiek' })).toBeChecked();
    expect(within(panel).getByRole('radio', { name: 'Niet kritiek' })).not.toBeDisabled();
  });

  it('400 CRITICALITY_INVALID toont de Nederlandse tekst, geen code', async () => {
    const s = state([], catalog());
    const calls = await openEditor(s, {
      post: () => jsonResponse({ error: 'Criticality NON_CRITICAL not allowed', code: 'CRITICALITY_INVALID' }, 400),
    });
    let panel = await openAddList();
    panel = await pick(panel, 'UNIT', 'Eenheid');
    fireEvent.change(within(panel).getByLabelText('Kolomnaam'), { target: { value: 'EENHEID' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(calls).toHaveLength(1));
    const alert = await within(panel).findByRole('alert');
    expect(alert).toHaveTextContent('Deze kritiek is niet toegelaten');
    expect(within(alert).getByText('Kies "Kritiek" voor dit veld.')).toBeInTheDocument();
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();
  });
});

describe('kritiek van de kernrijen (alleen-lezen)', () => {
  function criticalOf(key: string): HTMLElement {
    return screen.getByTestId(`critical-${key}`);
  }

  it('toont de standaard van de catalogus als er geen aanpassing is', async () => {
    const s = state([], catalog());
    await openEditor(s, {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    await waitFor(() => expect(criticalOf('supplier')).not.toHaveTextContent('—'));
    for (const key of ['supplier', 'group', 'reference', 'discount', 'basePrice', 'currency']) {
      expect(within(criticalOf(key)).getByText('Kritiek')).toBeInTheDocument();
    }
    expect(within(criticalOf('description')).getByText('Niet kritiek')).toBeInTheDocument();
  });

  it('een aanpassing van deze versie wint van de standaard', async () => {
    const s = state([], catalog(), {
      fieldCriticalities: [
        { revisionId: 100, fieldKey: 'DESCRIPTION', criticality: 'CRITICAL' },
        { revisionId: 100, fieldKey: 'BASE_PRICE', criticality: 'NON_CRITICAL' },
      ],
    });
    await openEditor(s, {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    await waitFor(() => expect(within(criticalOf('description')).getByText('Kritiek')).toBeInTheDocument());
    expect(within(criticalOf('basePrice')).getByText('Niet kritiek')).toBeInTheDocument();
    expect(within(criticalOf('currency')).getByText('Kritiek')).toBeInTheDocument();
  });

  it('is alleen-lezen: geen keuzeknop in de cel, ook niet in Bewerken', async () => {
    const s = state([], catalog());
    await openEditor(s);
    await waitFor(() => expect(criticalOf('supplier')).not.toHaveTextContent('—'));
    expect(within(criticalOf('supplier')).queryByRole('radio')).not.toBeInTheDocument();
    expect(within(criticalOf('supplier')).queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('het model', () => {
  const response: FieldCatalogResponse = {
    fields: [
      catalogEntry('SUPPLIER'),
      catalogEntry('AKP_PCT', { priceComponentCode: 'AKP', sortOrder: 2 }),
      catalogEntry('EAN', { referenceType: 'EAN', sortOrder: 3 }),
      catalogEntry('BRAND', { defaultOwner: 'PRODIS_USER', sortOrder: 4 }),
      catalogEntry('OUD_VELD', { name: 'Oud veld', active: false, sortOrder: 5 }),
      catalogEntry('RAAR', { defaultCriticality: 'IETS_ANDERS', sortOrder: 6 }),
    ] as FieldCatalogResponse['fields'],
    revisionFields: defaultRevisionFields(),
  };

  it('zet het antwoord om: kern eruit, groepen, Prodis-opmerking, fail-safe bij een onbekende kritiek', () => {
    const catalogue = catalogueFromResponse(response);
    expect(catalogue.source).toBe('endpoint');
    expect(catalogue.fields.map((field) => field.code)).toEqual(['AKP_PCT', 'EAN', 'BRAND', 'OUD_VELD', 'RAAR']);
    const byCode = Object.fromEntries(catalogue.fields.map((field) => [field.code, field]));
    expect(byCode.AKP_PCT).toMatchObject({ group: 'prices', defaultCriticality: 'CRITICAL', criticalityChangeable: true });
    expect(byCode.EAN).toMatchObject({ group: 'references', defaultCriticality: 'CRITICAL', criticalityChangeable: false });
    expect(byCode.BRAND).toMatchObject({ group: 'other', defaultCriticality: 'NON_CRITICAL' });
    expect(byCode.BRAND!.note).not.toBeNull();
    expect(byCode.OUD_VELD).toMatchObject({ active: false, label: 'Oud veld' });
    // Een onbekende waarde wordt nooit stilzwijgend "Niet kritiek".
    expect(byCode.RAAR!.defaultCriticality).toBe('CRITICAL');
  });

  it('visibleFields en unmappedFields: een niet actief veld enkel zichtbaar als het gekoppeld is, nooit "Nog niet gekoppeld"', () => {
    const catalogue = catalogueFromResponse(response);
    const revision = (codes: string[]) =>
      ({ mappings: codes.map((code, index) => mapping(index + 1, code)) }) as unknown as Parameters<typeof visibleFields>[1];
    expect(visibleFields(catalogue, revision([])).map((field) => field.code)).not.toContain('OUD_VELD');
    expect(visibleFields(catalogue, revision(['OUD_VELD'])).map((field) => field.code)).toContain('OUD_VELD');
    expect(unmappedFields(revision(['OUD_VELD']), catalogue).map((field) => field.code)).not.toContain('OUD_VELD');
    expect(unmappedFields(revision(['EAN']), catalogue).map((field) => field.code)).not.toContain('EAN');
  });

  it('coreRowCriticality: aanpassing wint, anders de standaard, anders onbekend (null)', () => {
    const catalogue = catalogueFromResponse(response);
    const revision = (fieldCriticalities: unknown[]) => ({ fieldCriticalities }) as unknown as Parameters<typeof coreRowCriticality>[1];
    expect(coreRowCriticality('description', revision([]), catalogue)).toBe('NON_CRITICAL');
    expect(coreRowCriticality('supplier', revision([]), catalogue)).toBe('CRITICAL');
    expect(
      coreRowCriticality('description', revision([{ revisionId: 1, fieldKey: 'DESCRIPTION', criticality: 'CRITICAL' }]), catalogue),
    ).toBe('CRITICAL');
    expect(coreRowCriticality('description', revision([]), null)).toBeNull();
    expect(coreRowCriticality('description', revision([]), fallbackCatalogue())).toBeNull();
    expect(
      coreRowCriticality('description', revision([{ revisionId: 1, fieldKey: 'DESCRIPTION', criticality: 'NON_CRITICAL' }]), null),
    ).toBe('NON_CRITICAL');
  });

  it('de terugval kent dezelfde standaardregel als de server', () => {
    const fallback = fallbackCatalogue();
    expect(fallback.source).toBe('fallback');
    const byCode = Object.fromEntries(fallback.fields.map((field) => [field.code, field]));
    expect(byCode.EAN).toMatchObject({ defaultCriticality: 'CRITICAL', criticalityChangeable: false });
    expect(byCode.VKP1_PCT).toMatchObject({ defaultCriticality: 'CRITICAL', criticalityChangeable: true });
    expect(byCode.UNIT).toMatchObject({ defaultCriticality: 'NON_CRITICAL', criticalityChangeable: true });
    expect(fallback.fields.every((field) => field.active)).toBe(true);
  });

  it('newMappingTemplate neemt de meegegeven kritiek over en start anders niet kritiek', () => {
    expect(newMappingTemplate(100, 'EAN', 'CRITICAL').criticality).toBe('CRITICAL');
    expect(newMappingTemplate(100, 'UNIT').criticality).toBe('NON_CRITICAL');
  });
});
