/**
 * EF2 — de kritiek van de kernrijen (Basisprijs, Valuta, Omschrijving) in Bewerken (beslissingslog 2026-10-23 "Editor-API-gaten:
 * ontwerp EB1-EB5", "EB2: keuzes"): Kritiek / Niet kritiek in het zijpaneel (`PUT .../field-criticality/{fieldKey}`), "Standaard
 * herstellen" (`DELETE`), een herkenningsrij blijft vergrendeld op "Kritiek (altijd)", de waarschuwing bij Basisprijs → Niet
 * kritiek, de melding bij `REVISION_EDIT_CONFLICT` en alleen-lezen op een actieve versie.
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

const EDIT_PATH = '/setup/suppliers/1/fields?definitie=10&versie=100&bewerken=1';
const ACTIVE_PATH = '/setup/suppliers/1/fields?definitie=10&versie=99&bewerken=1';

function state(draft: Record<string, unknown> = {}): SetupState {
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
  s.details[99] = revisionDetail(99, 10, {
    revisionNumber: 3,
    status: 'ACTIVE',
    identityUppercase: false,
    recordCurrencyField: 'VALUTA',
    fieldCriticalities: [{ revisionId: 99, fieldKey: 'DESCRIPTION', criticality: 'CRITICAL' }],
  });
  s.details[100] = revisionDetail(100, 10, {
    revisionNumber: 4,
    status: 'DRAFT',
    identityUppercase: false,
    recordCurrencyField: 'VALUTA',
    ...draft,
  });
  return s;
}

type Sent = { method: string; url: string; body: Record<string, unknown> | null };

/** Installeert de server; noteert elk schrijfverzoek (ook PATCH) in volgorde. `extra` mag een antwoord vervangen. */
function serve(s: SetupState, extra?: (sent: Sent) => Response | null): Sent[] {
  const sent: Sent[] = [];
  installSetupServer(s, (url, init) => {
    const method = (init?.method ?? 'GET').toUpperCase();
    if (method === 'GET') {
      return null;
    }
    const entry: Sent = { method, url, body: init?.body === undefined ? null : (JSON.parse(String(init.body)) as Record<string, unknown>) };
    sent.push(entry);
    const custom = extra?.(entry);
    if (custom !== undefined && custom !== null) {
      return custom;
    }
    if (method === 'PATCH' && /\/setup\/revisions\/100$/.test(url)) {
      return jsonResponse({ ...(s.details[100] as Record<string, unknown>), warnings: [] });
    }
    return null;
  });
  return sent;
}

async function openRow(row: string, s: SetupState, extra?: (sent: Sent) => Response | null, path = EDIT_PATH) {
  const sent = serve(s, extra);
  renderRoutes(path);
  // De catalogus (standaard kritiek) is geladen als het grid de kritiek van de rij toont.
  await waitFor(() => expect(screen.getByTestId(`critical-${row}`)).toHaveTextContent(/kritiek/i));
  await screen.findByTestId(`edit-row-${row}`);
  fireEvent.click(screen.getByTestId(`edit-row-${row}`));
  const panel = await screen.findByTestId('field-panel');
  return { sent, panel };
}

describe('Kritiek van een kernrij wijzigen', () => {
  it('Niet kritiek kiezen en Toepassen stuurt enkel de PUT met de nieuwe kritiek; het grid volgt', async () => {
    const { sent, panel } = await openRow('basePrice', state());
    expect(screen.getByTestId('critical-basePrice')).toHaveTextContent('Kritiek');
    expect(within(panel).getByRole('radio', { name: 'Kritiek' })).toBeChecked();

    fireEvent.click(within(panel).getByRole('radio', { name: 'Niet kritiek' }));
    fireEvent.click(within(panel).getByTestId('panel-apply'));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.method).toBe('PUT');
    expect(sent[0]!.url).toMatch(/\/setup\/revisions\/100\/field-criticality\/BASE_PRICE$/);
    expect(sent[0]!.body).toEqual({ criticality: 'NON_CRITICAL', createdBy: 'An Beslisser' });
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bewaard in het concept');
    await waitFor(() => expect(screen.queryByTestId('field-panel')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('critical-basePrice')).toHaveTextContent('Niet kritiek'));
  });

  it('zonder wijziging van de kritiek stuurt Toepassen niets', async () => {
    const { sent, panel } = await openRow('currency', state());
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    expect(within(panel).getByTestId('panel-problem')).toHaveTextContent('Er is niets gewijzigd.');
    expect(sent).toHaveLength(0);
  });

  it('Omschrijving start op de standaard (niet kritiek) en kan kritiek worden', async () => {
    const { sent, panel } = await openRow('description', state({ recordDescriptionField: 'OMSCHRIJVING' }));
    expect(within(panel).getByRole('radio', { name: 'Niet kritiek' })).toBeChecked();
    fireEvent.click(within(panel).getByRole('radio', { name: 'Kritiek' }));
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.url).toMatch(/\/field-criticality\/DESCRIPTION$/);
    expect(sent[0]!.body).toMatchObject({ criticality: 'CRITICAL' });
  });

  it('een gewijzigde kolom én kritiek: eerst de PATCH, daarna de PUT', async () => {
    const { sent, panel } = await openRow('currency', state());
    fireEvent.change(document.getElementById('panel-currencyField')!, { target: { value: 'MUNT' } });
    fireEvent.click(within(panel).getByRole('radio', { name: 'Niet kritiek' }));
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent.map((call) => call.method)).toEqual(['PATCH', 'PUT']);
    expect(sent[0]!.body).toMatchObject({ recordCurrencyField: 'MUNT' });
    expect(sent[1]!.url).toMatch(/\/field-criticality\/CURRENCY$/);
  });
});

describe('Standaard herstellen', () => {
  const override = { revisionId: 100, fieldKey: 'DESCRIPTION', criticality: 'CRITICAL' };

  it('is er enkel als de versie een aanpassing heeft; Toepassen stuurt dan een DELETE', async () => {
    const { sent, panel } = await openRow(
      'description',
      state({ recordDescriptionField: 'OMSCHRIJVING', fieldCriticalities: [override] }),
    );
    expect(screen.getByTestId('critical-description')).toHaveTextContent('Kritiek');
    expect(within(panel).getByRole('radio', { name: 'Kritiek' })).toBeChecked();

    fireEvent.click(within(panel).getByTestId('panel-criticality-restore'));
    // De keuze springt terug naar de standaard van het veld.
    expect(within(panel).getByRole('radio', { name: 'Niet kritiek' })).toBeChecked();
    fireEvent.click(within(panel).getByTestId('panel-apply'));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.method).toBe('DELETE');
    expect(sent[0]!.url).toMatch(/\/setup\/revisions\/100\/field-criticality\/DESCRIPTION$/);
    expect(await screen.findByTestId('save-toast')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('critical-description')).toHaveTextContent('Niet kritiek'));
  });

  it('zonder aanpassing in deze versie is er geen knop Standaard herstellen', async () => {
    const { panel } = await openRow('basePrice', state());
    expect(within(panel).queryByTestId('panel-criticality-restore')).not.toBeInTheDocument();
  });

  it('terug kiezen op de bestaande aanpassing na Standaard herstellen: niets te bewaren', async () => {
    const { sent, panel } = await openRow(
      'description',
      state({ recordDescriptionField: 'OMSCHRIJVING', fieldCriticalities: [override] }),
    );
    fireEvent.click(within(panel).getByTestId('panel-criticality-restore'));
    fireEvent.click(within(panel).getByRole('radio', { name: 'Kritiek' }));
    // Terug op de bestaande aanpassing: niets te bewaren.
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    expect(within(panel).getByTestId('panel-problem')).toHaveTextContent('Er is niets gewijzigd.');
    expect(sent).toHaveLength(0);
  });
});

describe('Herkenningsrijen', () => {
  it('blijven vergrendeld op "Kritiek (altijd)": geen keuze en geen herstel', async () => {
    const { panel } = await openRow('reference', state());
    const locked = within(panel).getByTestId('panel-criticality-locked');
    expect(locked).toHaveTextContent('Kritiek (altijd)');
    expect(within(panel).queryByRole('radio', { name: 'Niet kritiek' })).not.toBeInTheDocument();
    expect(within(panel).queryByTestId('panel-criticality-restore')).not.toBeInTheDocument();
  });
});

describe('Waarschuwing bij Basisprijs', () => {
  it('verschijnt bij Niet kritiek en verdwijnt bij Kritiek; Valuta en Omschrijving tonen ze niet', async () => {
    const { panel } = await openRow('basePrice', state());
    expect(within(panel).queryByTestId('panel-criticality-warning')).not.toBeInTheDocument();

    fireEvent.click(within(panel).getByRole('radio', { name: 'Niet kritiek' }));
    expect(within(panel).getByTestId('panel-criticality-warning')).toHaveTextContent(
      'Prijsfouten vragen dan geen beoordeling meer; ongeldige regels worden nog steeds verworpen.',
    );

    fireEvent.click(within(panel).getByRole('radio', { name: 'Kritiek' }));
    expect(within(panel).queryByTestId('panel-criticality-warning')).not.toBeInTheDocument();
  });

  it('Valuta op Niet kritiek geeft geen prijswaarschuwing', async () => {
    const { panel } = await openRow('currency', state());
    fireEvent.click(within(panel).getByRole('radio', { name: 'Niet kritiek' }));
    expect(within(panel).queryByTestId('panel-criticality-warning')).not.toBeInTheDocument();
  });
});

describe('Fouten', () => {
  it('REVISION_EDIT_CONFLICT: de Nederlandse melding, het paneel en de keuze blijven staan', async () => {
    const { sent, panel } = await openRow('basePrice', state(), (call) =>
      call.method === 'PUT' ? jsonResponse({ error: 'Gelijktijdig', code: 'REVISION_EDIT_CONFLICT' }, 409) : null,
    );
    fireEvent.click(within(panel).getByRole('radio', { name: 'Niet kritiek' }));
    fireEvent.click(within(panel).getByTestId('panel-apply'));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(await within(panel).findByText(/Iemand anders wijzigde dit tegelijk\. Herlaad en probeer opnieuw\./)).toBeInTheDocument();
    expect(screen.getByTestId('field-panel')).toBeInTheDocument();
    expect(within(panel).getByRole('radio', { name: 'Niet kritiek' })).toBeChecked();
    expect(screen.queryByTestId('save-toast')).not.toBeInTheDocument();
  });

  it('mislukt de PUT na een geslaagde PATCH, dan stuurt een nieuwe poging de PATCH niet opnieuw', async () => {
    let failures = 1;
    const { sent, panel } = await openRow('currency', state(), (call) => {
      if (call.method === 'PUT' && failures > 0) {
        failures -= 1;
        return jsonResponse({ error: 'Gelijktijdig', code: 'REVISION_EDIT_CONFLICT' }, 409);
      }
      return null;
    });
    fireEvent.change(document.getElementById('panel-currencyField')!, { target: { value: 'MUNT' } });
    fireEvent.click(within(panel).getByRole('radio', { name: 'Niet kritiek' }));
    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(sent.map((call) => call.method)).toEqual(['PATCH', 'PUT']));
    await within(panel).findByText(/Iemand anders wijzigde dit tegelijk/);

    fireEvent.click(within(panel).getByTestId('panel-apply'));
    await waitFor(() => expect(sent.map((call) => call.method)).toEqual(['PATCH', 'PUT', 'PUT']));
    await waitFor(() => expect(screen.queryByTestId('field-panel')).not.toBeInTheDocument());
  });
});

describe('Alleen-lezen', () => {
  it('op een actieve versie is er geen potlood en dus geen paneel, ook niet met ?bewerken=1', async () => {
    const sent = serve(state());
    renderRoutes(ACTIVE_PATH);
    await screen.findByTestId('frozen-note');
    expect(screen.queryByTestId('edit-row-basePrice')).not.toBeInTheDocument();
    expect(screen.queryByTestId('field-panel')).not.toBeInTheDocument();
    // De aanpassing van de actieve versie blijft wel zichtbaar in het grid.
    await waitFor(() => expect(screen.getByTestId('critical-description')).toHaveTextContent('Kritiek'));
    expect(sent).toHaveLength(0);
  });
});
