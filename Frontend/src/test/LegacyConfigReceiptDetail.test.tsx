/**
 * S9 — het detail van een ontvangst (`/setup/legacy-config/:receiptId`): bundel zonder secret, controle vooraf
 * (PREVIEW) met samenvatting, rapport per soort punt en de keuzes die de controle opnieuw berekenen.
 *
 * Dekt: de bundel (instellingen, importsoort), dat een secretwaarde nooit getoond wordt (ook niet uit een geheim
 * label of `bundle.secret`), het rapport blokkerend eerst, de woordenboekuitleg en de terugval voor een onbekende code,
 * de samenvatting van `planned`, herberekenen bij de keuze van een bibliotheekcode (en bij de twee tekstvelden), geen
 * controle voor een niet-toepasbare ontvangst, het ontbreken van `MANAGE` en een 404.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within, configure } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { LegacyReceiptDetailPage } from '../features/legacyconfig/LegacyReceiptDetailPage';
import {
  SECRET_VALUE,
  applicableReport,
  errorResponse,
  jsonResponse,
  mockApi,
  openCard,
  preview,
  receiptDetail,
  report,
  type Call,
} from './legacyConfigFixtures';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';

function renderDetail(identity: ActorIdentity = TEST_IDENTITY, id = 7) {
  return render(
    <ActorProvider identity={identity}>
      <MemoryRouter initialEntries={[`/setup/legacy-config/${id}`]}>
        <Routes>
          <Route path="/setup/legacy-config/:receiptId" element={<LegacyReceiptDetailPage />} />
          <Route path="/setup/legacy-config" element={<div>lijst</div>} />
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

/** Standaard: de ontvangst en een controle die op een bibliotheekkeuze wacht, tot `libraryCode=LIB2` meekomt. */
function defaultHandler(call: Call): Response {
  if (call.url.includes('/preview')) {
    const query = new URL(call.url, 'http://x').searchParams;
    return jsonResponse(
      query.get('libraryCode') === 'LIB2' ? preview(applicableReport(), 'fp-lib2') : preview(report(), 'fp-1'),
    );
  }
  return jsonResponse(receiptDetail());
}

// Onder belasting (volledige suite) kan de asynchrone preview langer dan de standaard 1 s duren.
configure({ asyncUtilTimeout: 10000 });

afterEach(() => cleanup());

describe('LegacyReceiptDetailPage — publicatie', () => {
  it('toont herkomst, instellingen en importsoort van de publicatie', async () => {
    mockApi(defaultHandler);
    renderDetail();

    expect(await screen.findByRole('heading', { name: /Ontvangst #7 — interface KRAMP_ART/ })).toBeInTheDocument();
    const origin = screen.getByTestId('legacy-origin-facts');
    expect(within(origin).getByText('PROD')).toBeInTheDocument();
    expect(within(origin).getByText('KLANT01')).toBeInTheDocument();

    await openCard('Wachtwoord');
    expect(within(screen.getByTestId('legacy-password-facts')).getByText('Wachtwoord veilig bewaard')).toBeInTheDocument();

    await openCard('Bestemming en verbinding');
    const iface = screen.getByTestId('legacy-interface-facts');
    expect(within(iface).getByText('Kramp artikelen')).toBeInTheDocument();
    expect(screen.getByText('sftp.example.test')).toBeInTheDocument();
    expect(screen.getByText('klant01')).toBeInTheDocument();

    await openCard('Importsoort');
    const definition = screen.getByTestId('legacy-definition-facts');
    expect(within(definition).getByText('Importsoort aanwezig')).toBeInTheDocument();
    expect(within(definition).getByText('VROOAM catalogus CSV')).toBeInTheDocument();
    expect(within(definition).getByText('3')).toBeInTheDocument();
  });

  it('toont nooit een secretwaarde, ook niet uit een geheim label of een secret in het antwoord', async () => {
    mockApi(defaultHandler);
    const { container } = renderDetail();
    await screen.findByRole('heading', { name: /Ontvangst #7/ });

    // Het geheime label staat enkel bij naam in de wachtwoordkaart, en met een verborgen waarde in de instellingen.
    await openCard('Wachtwoord');
    expect(within(screen.getByTestId('legacy-omitted-secrets')).getByText('API_AUTH_PASSWORD')).toBeInTheDocument();
    expect(container.textContent).not.toContain(SECRET_VALUE);
    expect(container.innerHTML).not.toContain(SECRET_VALUE);
    await openCard('Bestemming en verbinding');
    expect(container.textContent).not.toContain(SECRET_VALUE);
    expect(container.innerHTML).not.toContain(SECRET_VALUE);
    const settingRow = screen.getAllByText('API_AUTH_PASSWORD').map((cell) => cell.closest('tr')).find((row) => row !== null)!;
    expect(within(settingRow).getByText('(verborgen)')).toBeInTheDocument();
  });

  it('toont voor een ontvangst die niet meer toepasbaar is geen controle vooraf en vraagt er geen', async () => {
    const api = mockApi(() => jsonResponse(receiptDetail({ status: 'APPLIED', appliedBy: 'An Beslisser', appliedAt: '2026-10-21T09:00:00Z' })));
    renderDetail();

    expect(await screen.findByTestId('legacy-not-applicable')).toHaveTextContent('kan niet meer overgenomen worden');
    expect(screen.queryByRole('button', { name: /Overnemen/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Verwerpen/ })).not.toBeInTheDocument();
    expect(api.reads('/preview')).toHaveLength(0);
  });

  it('toont een 404 met de bestaande foutweergave', async () => {
    mockApi(() => errorResponse(404, 'LEGACY_RECEIPT_NOT_FOUND'));
    renderDetail();
    expect(await screen.findByText('Ontvangst niet gevonden')).toBeInTheDocument();
  });

  it('zonder MANAGE: enkel de reden, geen verzoek', () => {
    const api = mockApi(defaultHandler);
    renderDetail(testIdentityWith('catalogImport.read'));
    expect(screen.getByTestId('legacy-no-permission')).toHaveTextContent("U heeft het recht 'Beheren'");
    expect(api.calls).toHaveLength(0);
  });
});

describe('LegacyReceiptDetailPage — controle vooraf', () => {
  it('toont het rapport per soort punt, blokkerend eerst, met Nederlandse uitleg', async () => {
    mockApi(defaultHandler);
    renderDetail();

    // Blokkeert is standaard open; de andere soorten staan achter hun eigen kaart (één paneel tegelijk).
    await screen.findByTestId('legacy-report');
    expect(screen.getAllByTestId(/^legacy-group-/).map((group) => group.getAttribute('data-testid'))).toEqual(['legacy-group-BLOCKING']);
    const blocking = screen.getByTestId('legacy-group-BLOCKING');
    expect(within(blocking).getByText('Bibliotheek niet eenduidig bepaald')).toBeInTheDocument();
    expect(within(blocking).getByText(/kies er zelf een uit de lijst/)).toBeInTheDocument();
    expect(within(blocking).getByText('Blokkeert')).toBeInTheDocument();
    expect(within(screen.getByTestId('legacy-outcome')).getByText('Geblokkeerd')).toBeInTheDocument();

    await openCard('Waarschuwingen');
    expect(screen.getAllByTestId(/^legacy-group-/).map((group) => group.getAttribute('data-testid'))).toEqual(['legacy-group-WARNING', 'legacy-group-NOT_MIGRATED']);
    expect(within(screen.getByTestId('legacy-group-NOT_MIGRATED')).getByText('Planning niet overgenomen')).toBeInTheDocument();

    await openCard('Wordt overgenomen');
    expect(within(screen.getByTestId('legacy-group-MIGRATED')).getByText('Importsoort wordt overgenomen')).toBeInTheDocument();
  });

  it('houdt codes uit de zichtbare tekst en zet ze onder de technische details', async () => {
    mockApi(defaultHandler);
    renderDetail();
    await screen.findByTestId('legacy-report');

    const item = screen.getByTestId('legacy-item-LIBRARY_CODE_NOT_RESOLVED');
    const details = item.querySelector('details')!;
    expect(details).toHaveTextContent('LIBRARY_CODE_NOT_RESOLVED');
    expect(details).toHaveTextContent('INDET:ArtImp_BibNr');
    expect(details).toHaveTextContent('legacy library 12 matches no Prodis library');
    // Buiten de details staat de code niet als tekst.
    const clone = item.cloneNode(true) as HTMLElement;
    clone.querySelectorAll('details').forEach((element) => element.remove());
    expect(clone.textContent).not.toContain('LIBRARY_CODE_NOT_RESOLVED');
  });

  it('gebruikt één woordenboekingang voor elke berekeningscode en valt voor een onbekende code terug op de servertekst', async () => {
    mockApi(defaultHandler);
    renderDetail();
    await openCard('Waarschuwingen');

    expect(within(screen.getByTestId('legacy-item-LEGACY_CALC_8_NOT_MIGRATED')).getByText('Berekening nog niet ondersteund')).toBeInTheDocument();
    const unknown = screen.getByTestId('legacy-item-SOMETHING_FROM_THE_FUTURE');
    expect(within(unknown).getByText('Ander punt')).toBeInTheDocument();
    expect(within(unknown).getByText('a brand new server message')).toBeInTheDocument();
  });

  it('toont wat aangemaakt of hergebruikt wordt, met gedeeld met en de Prodis-controle', async () => {
    mockApi(defaultHandler);
    renderDetail();
    await openCard('Wordt overgenomen');
    const planned = await screen.findByTestId('legacy-planned');

    const row = (label: string) => within(planned).getByText(label).closest('div')!;
    expect(row('Leverancier')).toHaveTextContent('Wordt hergebruikt');
    expect(row('Leverancier')).toHaveTextContent('1042 — Kramp NV');
    expect(row('Importdefinitie')).toHaveTextContent('Wordt aangemaakt');
    expect(row('Importdefinitie')).toHaveTextContent('VROOAM2-SJ');
    expect(row('Gedeelde importdefinitie')).toHaveTextContent('Wordt aangemaakt');
    expect(row('Gedeelde importdefinitie')).toHaveTextContent('Versie: Wordt aangemaakt');
    expect(row('Koppeling')).toHaveTextContent('LEGACY-KRAMP_ART');
    expect(row('Koppeling')).toHaveTextContent('Bibliotheek: nog niet bepaald');
    expect(row('Taak')).toHaveTextContent('Legacy KRAMP_ART');
    expect(row('Wachtwoord')).toHaveTextContent('Verwijzing aanwezig');
    expect(row('Wachtwoord')).toHaveTextContent('Wachtwoord veilig bewaard');
    expect(row('Gedeeld met')).toHaveTextContent('KRAMP_PRIJS');
    expect(row('Controle tegen Prodis')).toHaveTextContent('Gecontroleerd in Prodis');
  });

  it('rekent de controle opnieuw bij de keuze van een bibliotheekcode en stuurt libraryCode mee', async () => {
    const api = mockApi(defaultHandler);
    renderDetail();
    await screen.findByTestId('legacy-report');
    // Vóór de controle is het een vrij tekstveld; met kandidaten wordt het een keuzelijst.
    await waitFor(() => expect(screen.getByLabelText('Bibliotheek van de koppeling').tagName).toBe('SELECT'));
    const select = screen.getByLabelText('Bibliotheek van de koppeling');
    expect(api.reads('/preview')).toHaveLength(1);
    expect(api.reads('/preview')[0]!.url).toBe('/api/catalog-import/legacy-config/receipts/7/preview');

    // De kandidaten komen uit libraryCandidates, met naam en legacy-nummer.
    expect(within(select).getByRole('option', { name: 'LIB1 — Kramp hoofdcatalogus, legacy-nummer 11' })).toBeInTheDocument();
    fireEvent.change(select, { target: { value: 'LIB2' } });

    await waitFor(() => expect(api.reads('/preview')).toHaveLength(2));
    expect(api.reads('/preview')[1]!.url).toBe('/api/catalog-import/legacy-config/receipts/7/preview?libraryCode=LIB2');
    // Het nieuwe rapport is toepasbaar; de keuzelijst blijft staan met de gekozen waarde.
    await waitFor(() => expect(within(screen.getByTestId('legacy-outcome')).getByText('Klaar om over te nemen')).toBeInTheDocument());
    expect(screen.getByLabelText('Bibliotheek van de koppeling')).toHaveValue('LIB2');
    expect(screen.queryByTestId('legacy-group-BLOCKING')).not.toBeInTheDocument();
  });

  it('stuurt de andere code en de tekenset mee zodra de controle opnieuw wordt uitgevoerd', async () => {
    const api = mockApi(defaultHandler);
    renderDetail();
    await screen.findByTestId('legacy-report');

    fireEvent.change(screen.getByLabelText('Andere code voor de importdefinitie'), { target: { value: 'VROOAM2B' } });
    fireEvent.change(screen.getByLabelText('Tekenset als de interface er geen opgeeft (optioneel)'), { target: { value: 'UTF-8' } });
    // Nog niets verstuurd: de controle rekent pas na de knop opnieuw.
    expect(api.reads('/preview')).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Controle opnieuw uitvoeren' }));

    await waitFor(() => expect(api.reads('/preview')).toHaveLength(2));
    const query = new URL(api.reads('/preview')[1]!.url, 'http://x').searchParams;
    expect(query.get('definitionCodeOverride')).toBe('VROOAM2B');
    expect(query.get('charsetWhenUndeclared')).toBe('UTF-8');
    expect(query.has('libraryCode')).toBe(false);
  });

  it('toont een fout van de controle met de bestaande foutweergave', async () => {
    mockApi((call) => (call.url.includes('/preview') ? errorResponse(503, 'PRODIS_DB_UNAVAILABLE') : jsonResponse(receiptDetail())));
    renderDetail();
    expect(await screen.findByText('Prodis-database is niet bereikbaar')).toBeInTheDocument();
  });
});
