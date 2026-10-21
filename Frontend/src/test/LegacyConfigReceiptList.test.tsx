/**
 * S9 — de ontvangstenlijst van de legacy-configuratie (`/setup/legacy-config`, design
 * `docs/design/legacy-config-bundle-design.md` §2).
 *
 * Dekt: rijen met interface, herkomst, status, ontvangen op en het label van `secretOutcome` (nooit een waarde);
 * het statusfilter en het interfacefilter (querystring), paginering, een lege lijst, het ontbreken van `MANAGE`
 * (geen verzoek, reden zichtbaar) en dat het menu-item enkel met `MANAGE` bestaat.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within, configure } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import App from '../App';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { LegacyReceiptListPage } from '../features/legacyconfig/LegacyReceiptListPage';
import { jsonResponse, mockApi, receiptRow } from './legacyConfigFixtures';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';

function renderList(identity: ActorIdentity = TEST_IDENTITY) {
  return render(
    <ActorProvider identity={identity}>
      <MemoryRouter initialEntries={['/setup/legacy-config']}>
        <LegacyReceiptListPage />
      </MemoryRouter>
    </ActorProvider>,
  );
}

function pageOf(content: ReturnType<typeof receiptRow>[], totalElements = content.length, page = 0) {
  return { content, page, size: 50, totalElements, totalPages: Math.ceil(totalElements / 50) };
}

const ROWS = [
  receiptRow({ id: 9, interfaceCode: 'KRAMP_PRIJS', status: 'APPLIED', secretReceived: false, secretOutcome: null }),
  receiptRow({ id: 7, interfaceCode: 'KRAMP_ART', status: 'RECEIVED', secretOutcome: 'STORED' }),
  receiptRow({ id: 5, interfaceCode: 'OLD_ART', status: 'SUPERSEDED', secretOutcome: 'DISCARDED_NO_HOST' }),
];

// Onder belasting (volledige suite) kan de asynchrone preview langer dan de standaard 1 s duren.
configure({ asyncUtilTimeout: 10000 });

afterEach(() => cleanup());

describe('LegacyReceiptListPage', () => {
  it('toont per ontvangst interface, herkomst, status, ontvangen op en het label van het wachtwoord', async () => {
    const api = mockApi(() => jsonResponse(pageOf(ROWS)));
    renderList();

    const link = await screen.findByRole('link', { name: 'KRAMP_ART' });
    expect(link).toHaveAttribute('href', '/setup/legacy-config/7');
    const row = link.closest('tr')!;
    expect(within(row).getByText('PROD / KLANT01')).toBeInTheDocument();
    expect(within(row).getByText('Ontvangen')).toBeInTheDocument();
    expect(within(row).getByText('Wachtwoord veilig bewaard')).toBeInTheDocument();
    expect(within(row).getByText(new Date('2026-10-21T08:16:00Z').toLocaleString('nl-BE'))).toBeInTheDocument();

    const applied = screen.getByRole('link', { name: 'KRAMP_PRIJS' }).closest('tr')!;
    expect(within(applied).getByText('Overgenomen')).toBeInTheDocument();
    expect(within(applied).getByText('Geen wachtwoord ontvangen')).toBeInTheDocument();
    const superseded = screen.getByRole('link', { name: 'OLD_ART' }).closest('tr')!;
    expect(within(superseded).getByText('Vervangen')).toBeInTheDocument();
    expect(within(superseded).getByText('Wachtwoord niet overgenomen: geen server')).toBeInTheDocument();

    // Nooit een ruwe code als zichtbare tekst.
    expect(screen.queryByText(/DISCARDED_NO_HOST/)).not.toBeInTheDocument();
    expect(api.reads('/legacy-config/receipts')).toHaveLength(1);
    expect(api.calls[0]!.url).toBe('/api/catalog-import/legacy-config/receipts?page=0&size=50');
  });

  it('filtert op status en gaat terug naar de eerste pagina', async () => {
    const api = mockApi(() => jsonResponse(pageOf(ROWS, 120)));
    renderList();
    await screen.findByRole('link', { name: 'KRAMP_ART' });

    fireEvent.click(screen.getByRole('button', { name: 'Volgende' }));
    await waitFor(() => expect(api.calls.some((call) => call.url.includes('page=1'))).toBe(true));

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'APPLIED' } });
    await waitFor(() => expect(api.calls.some((call) => call.url.includes('status=APPLIED'))).toBe(true));
    const last = api.calls[api.calls.length - 1]!;
    expect(last.url).toContain('status=APPLIED');
    expect(last.url).toContain('page=0');
  });

  it('filtert op interface', async () => {
    const api = mockApi(() => jsonResponse(pageOf(ROWS)));
    renderList();
    await screen.findByRole('link', { name: 'KRAMP_ART' });

    fireEvent.change(screen.getByLabelText('Interface'), { target: { value: 'KRAMP_ART' } });
    await waitFor(() => expect(api.calls.some((call) => call.url.includes('interface=KRAMP_ART'))).toBe(true));
  });

  it('toont een nette lege toestand', async () => {
    mockApi(() => jsonResponse(pageOf([])));
    renderList();
    expect(await screen.findByText('Geen ontvangsten gevonden.')).toBeInTheDocument();
  });

  it('toont een fout van de server met de bestaande foutweergave', async () => {
    mockApi(() => jsonResponse({ error: 'forbidden', code: 'PERMISSION_DENIED' }, 403));
    renderList();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('zonder MANAGE: enkel de reden, geen verzoek', () => {
    const api = mockApi(() => jsonResponse(pageOf(ROWS)));
    renderList(testIdentityWith('catalogImport.read'));
    expect(screen.getByTestId('legacy-no-permission')).toHaveTextContent("U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(api.calls).toHaveLength(0);
  });
});

describe('menu-item "Overname uit legacy"', () => {
  function renderShell(identity: ActorIdentity) {
    render(
      <ActorProvider identity={identity}>
        <MemoryRouter initialEntries={['/']}>
          <Routes>
            <Route element={<App />}>
              <Route path="/" element={<div />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </ActorProvider>,
    );
  }

  it('staat in de groep Inrichting en bestaat enkel met het recht Beheren', () => {
    renderShell(TEST_IDENTITY);
    expect(screen.getByRole('link', { name: 'Overname uit legacy' })).toHaveAttribute('href', '/setup/legacy-config');
    cleanup();
    renderShell(testIdentityWith('catalogImport.read'));
    expect(screen.queryByRole('link', { name: 'Overname uit legacy' })).not.toBeInTheDocument();
  });
});
