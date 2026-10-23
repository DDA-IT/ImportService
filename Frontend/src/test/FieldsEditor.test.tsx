/**
 * E1 — de nieuwe bewerkervaring van "Bestand en velden" (beslissingslog 2026-10-23 "Nieuwe bewerkervaring ... (O1-O10)"):
 * het Velden-grid (groepen, status Ontbreekt), de schakelaar Bekijken|Bewerken enkel op een concept, het zijpaneel (openen,
 * Escape met bevestiging, focus terug), de zoekende keuzelijst voor een vaste leverancier (Prodis) met de PATCH-body, de
 * identiteitsbevestiging (R-REV-X3), een wijziging van een basiskolom, alleen-lezen op een actieve versie en de terugval
 * als Prodis niet beschikbaar is.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import {
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

const originalFetch = global.fetch;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  global.fetch = originalFetch;
});

const MAPPINGS = [
  {
    id: 5001,
    revisionId: 100,
    sequenceNumber: 1,
    targetFieldCode: 'EAN',
    sourceReference: 'BARCODE',
    valueKind: 'SOURCE_FIELD',
    criticality: 'NON_CRITICAL',
    required: false,
    steps: [{ kind: 'PREFIX', config: 'prefix=0' }],
    valueMap: [],
  },
  {
    id: 5002,
    revisionId: 100,
    sequenceNumber: 2,
    targetFieldCode: 'BRAND',
    sourceReference: '',
    valueKind: 'SOURCE_FIELD',
    criticality: 'CRITICAL',
    required: true,
    steps: [],
    valueMap: [],
  },
];

function state(over: { withDraft?: boolean; detail?: Record<string, unknown> } = {}): SetupState {
  const s = emptySetupState();
  const withDraft = over.withDraft ?? true;
  s.organisations = [organisation(1, { code: 'VROOAM', name: 'Vrooam BV' })];
  s.definitions = [
    definition(10, 1, {
      name: 'Vrooam artikelbestand',
      activeRevisionId: 99,
      draftRevisionId: withDraft ? 100 : null,
      draftRevisionNumber: withDraft ? 4 : null,
    }),
  ];
  s.links = [link(501, 10)];
  s.tasks = [task(7, 501, 10, { activeRevisionId: 99 })];
  s.revisions[10] = [
    ...(withDraft ? [{ id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT' }] : []),
    { id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' },
  ];
  s.details[99] = revisionDetail(99, 10, { revisionNumber: 3, status: 'ACTIVE', identityUppercase: false });
  s.details[100] = revisionDetail(100, 10, {
    revisionNumber: 4,
    status: 'DRAFT',
    identityUppercase: false,
    identitySupplierFixedValue: null,
    identitySupplierGroupFixedValue: null,
    recordDescriptionField: null,
    mappings: MAPPINGS,
    ...over.detail,
  });
  return s;
}

type Server = {
  patches: Record<string, unknown>[];
  urls: string[];
};

type Options = {
  suppliers?: () => Response;
  groups?: () => Response;
  patch?: () => Response;
};

const SUPPLIERS = [
  { number: '06310', name: 'Vrooam BV' },
  { number: '06311', name: 'Vrooam Logistics' },
];

/** Installeert de server met PATCH- en Prodis-antwoorden; geeft de gevangen PATCH-bodies terug. */
function serve(s: SetupState, options: Options = {}): Server {
  const server: Server = { patches: [], urls: [] };
  const calls = installSetupServer(s, (url, init) => {
    if (url.includes('/prodis-reference/suppliers') && url.includes('/groups')) {
      return options.groups?.() ?? jsonResponse([{ legacyCode: '1', groupName: 'GROEP A', discountCode: null }]);
    }
    if (url.includes('/prodis-reference/suppliers')) {
      return options.suppliers?.() ?? jsonResponse(SUPPLIERS);
    }
    if ((init?.method ?? 'GET').toUpperCase() === 'PATCH' && /\/setup\/revisions\/100$/.test(url)) {
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
      server.patches.push(body);
      const custom = options.patch?.();
      if (custom !== undefined) {
        return custom;
      }
      const detail = s.details[100] as Record<string, unknown>;
      if (typeof body.basePriceField === 'string') {
        detail.recordBasePriceField = body.basePriceField;
      }
      return jsonResponse({ id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT', warnings: [] });
    }
    return null;
  });
  server.urls = calls.urls;
  return server;
}

async function openEditor(s: SetupState, options: Options = {}, path = '/setup/suppliers/1/fields?definitie=10&versie=100&bewerken=1') {
  const server = serve(s, options);
  renderRoutes(path);
  await screen.findByTestId('read-fields');
  return server;
}

async function openPanel(label: string): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: label }));
  return screen.findByRole('dialog', { name: label });
}

function changedKeys(body: Record<string, unknown>): string[] {
  return Object.entries(body)
    .filter(([name, value]) => value !== null && name !== 'createdBy')
    .map(([name]) => name)
    .sort();
}

describe('Velden-grid', () => {
  it('toont de groepen, de kolommen en de status Oké of Ontbreekt', async () => {
    const s = state({ detail: { identitySupplierReferenceField: '' } });
    await openEditor(s, {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    const fields = screen.getByTestId('read-fields');
    expect(within(fields).getAllByRole('columnheader').map((h) => h.textContent)).toEqual([
      'Veld',
      'Bron',
      'Bewerking',
      'Kritiek',
      'Verplicht',
      'Status',
    ]);

    const identity = screen.getByTestId('read-identity');
    expect(identity).toHaveTextContent('Herkenning van het artikel');
    for (const word of ['Leverancier', 'Groep', 'Referentie', 'Kortingscode']) {
      expect(identity).toHaveTextContent(word);
    }
    expect(within(identity).getByTestId('grid-row-supplier')).toHaveTextContent('← LEV');
    expect(within(identity).getByTestId('grid-row-discount')).toHaveTextContent('Niet gebruikt');
    // Een verplichte rij zonder bron: Ontbreekt; de andere zijn Oké.
    expect(within(identity).getByTestId('grid-row-reference')).toHaveTextContent('Ontbreekt');
    expect(within(identity).getByTestId('grid-row-supplier')).toHaveTextContent('Oké');
    expect(screen.getByTestId('uppercase-chip')).toHaveTextContent('Hoofdletters negeren: nee');

    const basis = screen.getByTestId('read-basis');
    expect(basis).toHaveTextContent('Basis');
    for (const word of ['Basisprijs', 'Omschrijving', 'Valuta']) {
      expect(basis).toHaveTextContent(word);
    }
    expect(within(basis).getByTestId('grid-row-basePrice')).toHaveTextContent('PRIJS');

    const extra = screen.getByTestId('read-extra');
    expect(extra).toHaveTextContent('Extra velden (2)');
    const ean = within(extra).getByTestId('read-field-5001');
    expect(ean).toHaveTextContent('BARCODE');
    expect(ean).toHaveTextContent('Voorvoegsel');
    expect(ean).toHaveTextContent('Oké');
    const brand = within(extra).getByTestId('read-field-5002');
    expect(brand).toHaveTextContent('Kritiek');
    expect(brand).toHaveTextContent('Ontbreekt');
  });
});

describe('schakelaar Bekijken | Bewerken', () => {
  it('staat enkel op een concept, standaard op Bekijken; Bewerken toont de potloden', async () => {
    await openEditor(state(), {}, '/setup/suppliers/1/fields?definitie=10&versie=100');
    expect(screen.getByTestId('mode-view')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('mode-edit')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByRole('button', { name: 'Wijzigen: Basisprijs' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('mode-edit'));
    expect(screen.getByTestId('mode-edit')).toHaveAttribute('aria-pressed', 'true');
    for (const field of ['Leverancier', 'Groep', 'Referentie', 'Kortingscode', 'Basisprijs', 'Omschrijving', 'Valuta']) {
      expect(screen.getByRole('button', { name: `Wijzigen: ${field}` })).toBeInTheDocument();
    }
    // Extra velden hebben sinds E2 ook een potlood (zie FieldsEditorMappings.test.tsx).
    expect(screen.getByRole('button', { name: 'Wijzigen: EAN-barcode' })).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('mode-view'));
    expect(screen.queryByRole('button', { name: 'Wijzigen: Basisprijs' })).not.toBeInTheDocument();
  });

  it('?bewerken=1 zet Bewerken meteen aan', async () => {
    await openEditor(state());
    expect(screen.getByTestId('mode-edit')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Wijzigen: Basisprijs' })).toBeInTheDocument();
  });

  it('een actieve versie is alleen-lezen met Opvolger maken', async () => {
    await openEditor(state({ withDraft: false }), {}, '/setup/suppliers/1/fields?definitie=10&versie=99&bewerken=1');
    expect(screen.getByTestId('frozen-note')).toHaveTextContent('Deze versie is actief en kan niet gewijzigd worden');
    expect(screen.queryByTestId('mode-edit')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Wijzigen:/ })).not.toBeInTheDocument();
    expect(screen.getByTestId('create-successor-99')).toHaveTextContent('Opvolger maken');
  });

  it('een actieve versie met een bestaand concept verwijst naar het concept', async () => {
    await openEditor(state(), {}, '/setup/suppliers/1/fields?definitie=10&versie=99');
    expect(screen.queryByTestId('create-successor-99')).not.toBeInTheDocument();
    const open = screen.getByTestId('open-draft');
    expect(open).toHaveTextContent('Open het concept (versie 4)');
    fireEvent.click(open);
    await waitFor(() => expect(screen.getByTestId('version-select')).toHaveValue('100'));
    expect(await screen.findByTestId('mode-edit')).toBeInTheDocument();
  });
});

describe('zijpaneel', () => {
  it('opent met de focus erin; Escape zonder wijziging sluit en geeft de focus terug aan het potlood', async () => {
    await openEditor(state());
    const opener = await screen.findByRole('button', { name: 'Wijzigen: Basisprijs' });
    opener.focus();
    fireEvent.click(opener);
    const panel = await screen.findByRole('dialog', { name: 'Wijzigen: Basisprijs' });
    expect(panel).toHaveAttribute('aria-labelledby');
    await waitFor(() => expect(panel.contains(document.activeElement)).toBe(true));

    fireEvent.keyDown(panel, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Wijzigen: Basisprijs' })).not.toBeInTheDocument());
    expect(screen.queryByText('Wijzigingen weggooien?')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Wijzigen: Basisprijs' }));
  });

  it('Escape met een wijziging vraagt "Wijzigingen weggooien?"; verder bewerken houdt het paneel, weggooien sluit het', async () => {
    const server = await openEditor(state());
    const panel = await openPanel('Wijzigen: Basisprijs');
    fireEvent.change(within(panel).getByLabelText('Kolom'), { target: { value: 'NIEUWE_PRIJS' } });

    fireEvent.keyDown(panel, { key: 'Escape' });
    expect(await screen.findByText('Wijzigingen weggooien?')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Verder bewerken' }));
    expect(screen.queryByText('Wijzigingen weggooien?')).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Wijzigen: Basisprijs' })).toBeInTheDocument();
    expect(within(panel).getByLabelText('Kolom')).toHaveValue('NIEUWE_PRIJS');

    fireEvent.keyDown(panel, { key: 'Escape' });
    fireEvent.click(await screen.findByRole('button', { name: 'Weggooien' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Wijzigen: Basisprijs' })).not.toBeInTheDocument());
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Wijzigen: Basisprijs' })));
    expect(server.patches).toHaveLength(0);
  });

  it('[Annuleren] sluit zonder iets te versturen', async () => {
    const server = await openEditor(state());
    const panel = await openPanel('Wijzigen: Basisprijs');
    fireEvent.click(within(panel).getByRole('button', { name: 'Annuleren' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Wijzigen: Basisprijs' })).not.toBeInTheDocument());
    expect(server.patches).toHaveLength(0);
  });
});

describe('Basis: kolom wijzigen', () => {
  it('Toepassen stuurt enkel de gewijzigde kolom, toont "Bewaard in het concept" en herlaadt het grid', async () => {
    const server = await openEditor(state());
    const panel = await openPanel('Wijzigen: Basisprijs');
    fireEvent.change(within(panel).getByLabelText('Kolom'), { target: { value: 'NIEUWE_PRIJS' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));

    await waitFor(() => expect(server.patches).toHaveLength(1));
    expect(changedKeys(server.patches[0]!)).toEqual(['basePriceField']);
    expect(server.patches[0]).toMatchObject({ basePriceField: 'NIEUWE_PRIJS', createdBy: 'An Beslisser' });
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bewaard in het concept');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('grid-row-basePrice')).toHaveTextContent('NIEUWE_PRIJS'));
  });

  it('Omschrijving kan leeggemaakt worden: een uitdrukkelijk lege waarde', async () => {
    const server = await openEditor(state({ detail: { recordDescriptionField: 'OMSCHRIJVING' } }));
    const panel = await openPanel('Wijzigen: Omschrijving');
    fireEvent.change(within(panel).getByLabelText('Kolom'), { target: { value: '' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(server.patches).toHaveLength(1));
    expect(changedKeys(server.patches[0]!)).toEqual(['descriptionField']);
    expect(server.patches[0]!.descriptionField).toBe('');
  });

  it('zonder wijziging staat er een melding en wordt er niets verstuurd', async () => {
    const server = await openEditor(state());
    const panel = await openPanel('Wijzigen: Basisprijs');
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    expect(await within(panel).findByTestId('panel-problem')).toHaveTextContent('Er is niets gewijzigd');
    expect(server.patches).toHaveLength(0);
  });

  it('een serverfout blijft in het paneel staan, met de ingevulde waarde', async () => {
    const server = await openEditor(state(), {
      patch: () => jsonResponse({ error: 'kapot', code: 'REVISION_NOT_EDITABLE' }, 409),
    });
    const panel = await openPanel('Wijzigen: Basisprijs');
    fireEvent.change(within(panel).getByLabelText('Kolom'), { target: { value: 'NIEUWE_PRIJS' } });
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(server.patches).toHaveLength(1));
    expect(await within(panel).findByRole('alert')).toBeInTheDocument();
    expect(within(panel).getByLabelText('Kolom')).toHaveValue('NIEUWE_PRIJS');
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();
  });
});

describe('vaste leverancier met de zoekende keuzelijst', () => {
  async function openSupplierPanel() {
    const server = await openEditor(state());
    const panel = await openPanel('Wijzigen: Leverancier');
    fireEvent.click(within(panel).getByRole('radio', { name: 'Vaste waarde' }));
    return { server, panel };
  }

  it('zoekt in Prodis, toont "06310 · Vrooam BV", bewaart de gekozen waarde na de bevestiging', async () => {
    const { server, panel } = await openSupplierPanel();
    const combobox = within(panel).getByRole('combobox');
    expect(combobox).toHaveAttribute('aria-expanded', 'false');
    expect(combobox).toHaveAttribute('aria-controls');

    fireEvent.change(combobox, { target: { value: 'vro' } });
    expect(combobox).toHaveAttribute('aria-expanded', 'true');
    await waitFor(() => expect(server.urls.some((url) => url.includes('/prodis-reference/suppliers?q=vro'))).toBe(true));
    const option = await within(panel).findByRole('option', { name: '06310 · Vrooam BV' });
    fireEvent.click(option);
    expect(combobox).toHaveValue('06310');
    expect(combobox).toHaveAttribute('aria-expanded', 'false');
    expect(within(panel).getByTestId('prodis-chosen-supplier')).toHaveTextContent('06310 · Vrooam BV');

    // De herkenning verandert: Toepassen blijft uit tot het vinkje (standaard uit) aan staat.
    const apply = within(panel).getByTestId('panel-apply');
    const acknowledge = within(panel).getByRole('checkbox', { name: /elk bestaand artikel hierna als nieuw artikel/ });
    expect(acknowledge).not.toBeChecked();
    expect(apply).toBeDisabled();
    fireEvent.click(acknowledge);
    expect(apply).toBeEnabled();
    fireEvent.click(apply);

    await waitFor(() => expect(server.patches).toHaveLength(1));
    expect(server.patches[0]).toMatchObject({
      supplierFixedValue: '06310',
      supplierField: '',
      acknowledgeIdentityChange: true,
    });
    expect(changedKeys(server.patches[0]!)).toEqual(['acknowledgeIdentityChange', 'supplierField', 'supplierFixedValue']);
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bewaard in het concept');
  });

  it('werkt met het toetsenbord: pijltje omlaag, Enter kiest; Escape sluit eerst de lijst en niet het paneel', async () => {
    const { server, panel } = await openSupplierPanel();
    const combobox = within(panel).getByRole('combobox');
    fireEvent.change(combobox, { target: { value: 'vro' } });
    await within(panel).findByRole('option', { name: '06311 · Vrooam Logistics' });

    fireEvent.keyDown(combobox, { key: 'ArrowDown' });
    const first = within(panel).getByRole('option', { name: '06310 · Vrooam BV' });
    expect(combobox).toHaveAttribute('aria-activedescendant', first.id);
    fireEvent.keyDown(combobox, { key: 'ArrowDown' });
    const second = within(panel).getByRole('option', { name: '06311 · Vrooam Logistics' });
    expect(combobox).toHaveAttribute('aria-activedescendant', second.id);
    fireEvent.keyDown(combobox, { key: 'ArrowUp' });
    expect(combobox).toHaveAttribute('aria-activedescendant', first.id);

    fireEvent.keyDown(combobox, { key: 'Escape' });
    expect(combobox).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByRole('dialog', { name: 'Wijzigen: Leverancier' })).toBeInTheDocument();

    fireEvent.keyDown(combobox, { key: 'ArrowDown' });
    fireEvent.keyDown(combobox, { key: 'Enter' });
    expect(combobox).toHaveValue('06310');
    expect(server.patches).toHaveLength(0);
  });

  it('vrije tekst blijft mogelijk, met de melding "Niet gevonden in Prodis"', async () => {
    const { server, panel } = await openSupplierPanel();
    // Een lege zoekopdracht geeft de lijst; voor de getypte waarde is er niets.
    const combobox = within(panel).getByRole('combobox');
    server.urls.length = 0;
    fireEvent.change(combobox, { target: { value: 'ONBEKEND' } });
    await waitFor(() => expect(server.urls.some((url) => url.includes('q=ONBEKEND'))).toBe(true));
    expect(await within(panel).findByTestId('prodis-notfound-value-supplier')).toHaveTextContent(
      'Niet gevonden in Prodis — wordt bij het bewaren gecontroleerd',
    );
    fireEvent.click(within(panel).getByRole('checkbox', { name: /elk bestaand artikel hierna als nieuw artikel/ }));
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(server.patches).toHaveLength(1));
    expect(server.patches[0]).toMatchObject({ supplierFixedValue: 'ONBEKEND' });
  });

  it('is Prodis niet beschikbaar, dan staat er een gewoon tekstveld met de bestaande melding', async () => {
    const server = await openEditor(state(), {
      suppliers: () => jsonResponse({ error: 'uit', code: 'PRODIS_DB_NOT_CONFIGURED' }, 409),
    });
    const panel = await openPanel('Wijzigen: Leverancier');
    fireEvent.click(within(panel).getByRole('radio', { name: 'Vaste waarde' }));
    expect(await within(panel).findByTestId('prodis-unavailable-supplier')).toHaveTextContent(
      'De lijst van Prodis is nu niet beschikbaar',
    );
    expect(within(panel).queryByRole('combobox')).not.toBeInTheDocument();
    fireEvent.change(within(panel).getByLabelText('Vaste leverancier'), { target: { value: '06310' } });
    fireEvent.click(within(panel).getByRole('checkbox', { name: /elk bestaand artikel hierna als nieuw artikel/ }));
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(server.patches).toHaveLength(1));
    expect(server.patches[0]).toMatchObject({ supplierFixedValue: '06310', acknowledgeIdentityChange: true });
  });

  it('een vaste groep zoekt de groepen van de gekozen vaste leverancier', async () => {
    const server = await openEditor(
      state({ detail: { identitySupplierField: null, identitySupplierFixedValue: '06310' } }),
    );
    const panel = await openPanel('Wijzigen: Groep');
    fireEvent.click(within(panel).getByRole('radio', { name: 'Vaste waarde' }));
    await waitFor(() => expect(server.urls.some((url) => url.includes('/suppliers/06310/groups'))).toBe(true));
    fireEvent.click(within(panel).getByRole('combobox'));
    fireEvent.click(await within(panel).findByRole('option', { name: 'GROEP A' }));
    expect(within(panel).getByRole('combobox')).toHaveValue('GROEP A');
  });
});

describe('identiteitsbescherming', () => {
  it('een kolom van de referentie wijzigen vraagt het vinkje; zonder vinkje blijft Toepassen uit', async () => {
    const server = await openEditor(state());
    const panel = await openPanel('Wijzigen: Referentie');
    expect(within(panel).queryByTestId('identity-change-acknowledge')).not.toBeInTheDocument();
    fireEvent.change(within(panel).getByLabelText('Kolom'), { target: { value: 'NIEUWE_REF' } });
    expect(within(panel).getByTestId('identity-change-acknowledge')).toHaveTextContent(
      'Deze wijziging raakt hoe een artikel herkend wordt',
    );
    expect(within(panel).getByTestId('panel-apply')).toBeDisabled();
    fireEvent.click(within(panel).getByRole('checkbox'));
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(server.patches).toHaveLength(1));
    expect(changedKeys(server.patches[0]!)).toEqual(['acknowledgeIdentityChange', 'supplierReferenceField']);
  });

  it('REVISION_CANONICALISATION_CHANGE_BLOCKED: de fout blijft en er wordt geen bevestiging meer aangeboden', async () => {
    const server = await openEditor(state(), {
      patch: () => jsonResponse({ error: 'geblokkeerd', code: 'REVISION_CANONICALISATION_CHANGE_BLOCKED' }, 409),
    });
    const panel = await openPanel('Wijzigen: Referentie');
    fireEvent.change(within(panel).getByLabelText('Kolom'), { target: { value: 'NIEUWE_REF' } });
    fireEvent.click(within(panel).getByRole('checkbox'));
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(server.patches).toHaveLength(1));
    await waitFor(() => expect(within(panel).queryByTestId('identity-change-acknowledge')).not.toBeInTheDocument());
    expect(within(panel).getByRole('alert')).toBeInTheDocument();
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();
  });

  it('de schakelaar "Hoofdletters negeren" opent een paneel met dezelfde bevestiging', async () => {
    const server = await openEditor(state());
    const toggle = screen.getByRole('switch', { name: 'Hoofdletters negeren: nee' });
    expect(toggle).toHaveAttribute('aria-checked', 'false');
    fireEvent.click(toggle);
    const panel = await screen.findByRole('dialog', { name: 'Wijzigen: Hoofdletters negeren' });
    fireEvent.click(within(panel).getByRole('checkbox', { name: /Hoofdletters negeren bij het herkennen/ }));
    expect(within(panel).getByTestId('panel-apply')).toBeDisabled();
    fireEvent.click(within(panel).getByRole('checkbox', { name: /elk bestaand artikel hierna als nieuw artikel/ }));
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(server.patches).toHaveLength(1));
    expect(changedKeys(server.patches[0]!)).toEqual(['acknowledgeIdentityChange', 'identityUppercase']);
    expect(server.patches[0]!.identityUppercase).toBe(true);
  });

  it('een kortingscodekolom zet het herkenningsprofiel mee om (het paar blijft consistent)', async () => {
    const server = await openEditor(state());
    const panel = await openPanel('Wijzigen: Kortingscode');
    fireEvent.click(within(panel).getByRole('radio', { name: 'Kolom uit het bestand' }));
    fireEvent.change(within(panel).getByLabelText('Kolom'), { target: { value: 'KORTING' } });
    fireEvent.click(within(panel).getByRole('checkbox'));
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(server.patches).toHaveLength(1));
    expect(server.patches[0]).toMatchObject({
      discountCodeField: 'KORTING',
      identityProfileKind: 'FOUR_PART_WITH_DISCOUNT_CODE',
    });
  });
});
