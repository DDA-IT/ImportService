/**
 * Redesign van het ontvangstdetail (docs/decisions.md 2026-10-22): het kaartenrooster met teller en toplijn, het ene
 * detailpaneel, de standaardregel voor het open paneel, de lege kaart, het wachtwoord (nooit een waarde), zoeken en
 * filteren boven 8 punten, groeperen per code boven 5 gelijke punten, en de bronnen van het rapport bij een
 * overgenomen of verworpen ontvangst. Onderaan de pure regels uit `legacyPolicy.ts`.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, within, configure } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { LegacyConfigReport, LegacyReceiptDetail, LegacyReportItem } from '../api/types';
import { LegacyReceiptDetailPage } from '../features/legacyconfig/LegacyReceiptDetailPage';
import {
  describeLegacyPart,
  groupItemsByCode,
  isSecretItem,
  summarizeReport,
} from '../features/legacyconfig/legacyPolicy';
import {
  SECRET_VALUE,
  applicableReport,
  jsonResponse,
  mockApi,
  openCard,
  preview,
  receiptDetail,
  report,
} from './legacyConfigFixtures';
import { TEST_IDENTITY } from './testIdentity';

function renderDetail() {
  return render(
    <ActorProvider identity={TEST_IDENTITY}>
      <MemoryRouter initialEntries={['/setup/legacy-config/7']}>
        <Routes>
          <Route path="/setup/legacy-config/:receiptId" element={<LegacyReceiptDetailPage />} />
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

function item(code: string, status: string, element = 'INDET:Test'): LegacyReportItem {
  return { element, status, code, target: null, message: `server text for ${code}` };
}

/** Een rapport met precies deze punten; blokkerend of niet volgt uit de punten. */
function reportWith(items: LegacyReportItem[]): LegacyConfigReport {
  const base = items.some((entry) => entry.status === 'BLOCKING') ? report() : applicableReport();
  return { ...base, items };
}

function serve(reportValue: LegacyConfigReport, detail: LegacyReceiptDetail = receiptDetail()) {
  return mockApi((call) => (call.url.includes('/preview') ? jsonResponse(preview(reportValue)) : jsonResponse(detail)));
}

configure({ asyncUtilTimeout: 10000 });

afterEach(() => cleanup());

const FOUR_BLOCKING = [
  item('LIBRARY_CODE_NOT_RESOLVED', 'BLOCKING', 'INDET:ArtImp_BibNr'),
  item('DEFINITION_NOT_PRESENT', 'BLOCKING', 'PSIMDHFD'),
  item('SUPPLIER_NOT_DECLARED', 'BLOCKING', 'INDET:Supplier'),
  item('LINK_CODE_CONFLICT_MANUAL', 'BLOCKING', 'PLAN:link'),
];
const NINE_WARNINGS = [
  item('SEPARATOR_NOT_DECLARED', 'WARNING', 'INDET:Separator'),
  item('DECIMAL_COMMA_BASE_PRICE', 'WARNING', 'PSIMPDEF#2'),
  item('CALC_ROUNDING_MAY_DIFFER', 'WARNING', 'PSIMPDEF#3'),
  item('MAP_EMPTY', 'WARNING', 'PSIMPDEFMAP#1'),
  item('CHARSET_DEFAULTED_UTF8', 'WARNING', 'INDET:FILE_ENCODING'),
  item('SCHEDULE_NOT_MIGRATED', 'NOT_MIGRATED', 'INHEAD'),
  item('CONNECTION_NOT_MIGRATED', 'NOT_MIGRATED', 'CONNECTION'),
  item('PUBLICATION_PARAMETER_NOT_MIGRATED', 'NOT_MIGRATED', 'INDET:Publicatie'),
  item('INTERFACE_SETTING_NOT_MIGRATED', 'NOT_MIGRATED', 'INDET:Iets'),
];

describe('kaartenrooster — tellers en toplijn', () => {
  it('telt per kaart: 4 blokkerend, 2 waarschuwingen, 1 niet overgenomen, 1 overgenomen', async () => {
    serve(
      reportWith([
        ...FOUR_BLOCKING,
        item('SEPARATOR_NOT_DECLARED', 'WARNING'),
        item('DECIMAL_COMMA_BASE_PRICE', 'WARNING'),
        item('SCHEDULE_NOT_MIGRATED', 'NOT_MIGRATED', 'INHEAD'),
        item('DEFINITION', 'MIGRATED', 'PSIMDHFD'),
      ]),
    );
    renderDetail();

    const blocking = await screen.findByTestId('legacy-card-blocking');
    expect(within(blocking).getByRole('heading', { name: 'Blokkeert' })).toBeInTheDocument();
    expect(screen.getByTestId('legacy-card-count-blocking')).toHaveTextContent('4');
    // De toplijn is het eerste blokkerende punt, in gewoon Nederlands.
    expect(blocking).toHaveTextContent('Bibliotheek niet eenduidig bepaald');

    expect(screen.getByTestId('legacy-card-count-warnings')).toHaveTextContent('3');
    expect(screen.getByTestId('legacy-card-warnings')).toHaveTextContent('2 let op · 1 niet overgenomen');
    expect(screen.getByTestId('legacy-card-count-migrated')).toHaveTextContent('1');
    expect(screen.getByTestId('legacy-card-count-definition')).toHaveTextContent('3');
    expect(screen.getByTestId('legacy-card-definition')).toHaveTextContent('VROOAM2 · Importsoort aanwezig');
    expect(screen.getByTestId('legacy-card-definition')).toHaveTextContent('3 regels · 1 omzetting · 0 uitsluitingen');
    expect(screen.getByTestId('legacy-card-target')).toHaveTextContent('Koppeling LEGACY-KRAMP_ART');
    expect(screen.getByTestId('legacy-card-target')).toHaveTextContent('Bibliotheek bibliotheek nog niet bepaald');
  });

  it('vermeldt in de wachtwoordkaart de uitkomst en of er een verwijzing is, nooit een waarde', async () => {
    serve(reportWith(FOUR_BLOCKING));
    const { container } = renderDetail();

    const card = await screen.findByTestId('legacy-card-password');
    expect(card).toHaveTextContent('Wachtwoord veilig bewaard · Verwijzing aanwezig');
    await openCard('Wachtwoord');
    const panel = screen.getByTestId('legacy-panel-password');
    expect(within(panel).getByTestId('legacy-omitted-secrets')).toHaveTextContent('API_AUTH_PASSWORD');
    expect(within(panel).getByText('Verwijzing aanwezig')).toBeInTheDocument();
    expect(container.textContent).not.toContain(SECRET_VALUE);
    expect(container.innerHTML).not.toContain(SECRET_VALUE);
  });

  it('toont bij een wachtwoordgerelateerd punt enkel het punt, nooit een waarde', async () => {
    serve(reportWith([item('SECRET_LABEL_OMITTED', 'NOT_MIGRATED', 'INDET:API_AUTH_PASSWORD'), ...FOUR_BLOCKING]));
    const { container } = renderDetail();
    await screen.findByTestId('legacy-card-password');
    await openCard('Wachtwoord');
    expect(within(screen.getByTestId('legacy-panel-password')).getByText('Geheime instelling weggelaten')).toBeInTheDocument();
    expect(container.innerHTML).not.toContain(SECRET_VALUE);
  });
});

describe('kaartenrooster — één paneel tegelijk', () => {
  it('opent standaard Blokkeert als er iets blokkeert', async () => {
    serve(reportWith(FOUR_BLOCKING));
    renderDetail();

    const button = await screen.findByRole('button', { name: /^Verbergen Blokkeert$/ });
    expect(button).toHaveAttribute('aria-expanded', 'true');
    const region = screen.getByRole('region', { name: 'Blokkeert' });
    expect(button).toHaveAttribute('aria-controls', region.id);
    expect(within(region).getAllByTestId(/^legacy-item-/)).toHaveLength(4);
  });

  it('opent niets als niets blokkeert', async () => {
    serve(reportWith([item('SEPARATOR_NOT_DECLARED', 'WARNING')]));
    renderDetail();

    await screen.findByTestId('legacy-card-blocking');
    expect(screen.queryByRole('region')).not.toBeInTheDocument();
    for (const button of screen.getAllByRole('button', { name: /^Tonen / })) {
      expect(button).toHaveAttribute('aria-expanded', 'false');
    }
  });

  it('opent maximaal één paneel tegelijk en klapt een open kaart opnieuw dicht', async () => {
    serve(reportWith([...FOUR_BLOCKING, item('SEPARATOR_NOT_DECLARED', 'WARNING')]));
    renderDetail();
    await screen.findByRole('region', { name: 'Blokkeert' });

    const warnings = await openCard('Waarschuwingen');
    expect(warnings).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getAllByRole('region')).toHaveLength(1);
    expect(screen.getByRole('region', { name: 'Waarschuwingen' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^Tonen Blokkeert$/ })).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(warnings);
    expect(screen.queryByRole('region')).not.toBeInTheDocument();
  });

  it('zet een lege kaart uit en legt in de toplijn waarom', async () => {
    serve(reportWith([item('SEPARATOR_NOT_DECLARED', 'WARNING')]));
    renderDetail();

    const card = await screen.findByTestId('legacy-card-blocking');
    expect(within(card).getByRole('button')).toBeDisabled();
    expect(card).toHaveTextContent('Niets blokkeert het overnemen');
    expect(screen.getByTestId('legacy-card-count-blocking')).toHaveTextContent('0');
    fireEvent.click(within(card).getByRole('button'));
    expect(screen.queryByRole('region')).not.toBeInTheDocument();

    const empty = screen.getByTestId('legacy-card-migrated');
    expect(within(empty).getByRole('button')).toBeEnabled();
  });

  it('laat de actieknoppen en de herkomst in de kop staan', async () => {
    serve(reportWith(FOUR_BLOCKING));
    renderDetail();

    await screen.findByTestId('legacy-outcome');
    expect(screen.getByRole('button', { name: 'Overnemen…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Verwerpen…' })).toBeEnabled();
    expect(screen.getByRole('button', { name: /Controle opnieuw uitvoeren met de huidige keuzes/ })).toBeInTheDocument();
    expect(screen.getByTestId('legacy-origin-facts')).toHaveTextContent('Omgeving PROD');
    expect(screen.getByTestId('legacy-prodis-check')).toHaveTextContent('Gecontroleerd in Prodis');
    const live = screen.getByTestId('legacy-outcome').parentElement!;
    expect(live).toHaveAttribute('aria-live', 'polite');
  });

  it('toont Prodis niet bereikbaar als chip in de kop', async () => {
    serve({ ...reportWith(FOUR_BLOCKING), prodisReference: 'UNAVAILABLE' });
    renderDetail();
    expect(await screen.findByTestId('legacy-prodis-check')).toHaveTextContent('Prodis niet bereikbaar');
  });

  it('klapt de keuzes open zolang er iets blokkeert en dicht als niets blokkeert', async () => {
    serve(reportWith(FOUR_BLOCKING));
    const { unmount } = renderDetail();
    expect(await screen.findByLabelText('Andere code voor de importdefinitie')).toBeInTheDocument();
    unmount();
    cleanup();

    serve(reportWith([item('SEPARATOR_NOT_DECLARED', 'WARNING')]));
    renderDetail();
    const toggle = await screen.findByRole('button', { name: /^Tonen Keuzes voor de controle$/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByLabelText('Andere code voor de importdefinitie')).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(screen.getByLabelText('Andere code voor de importdefinitie')).toBeInTheDocument();
  });
});

describe('rapport in een paneel — zoeken, filteren en groeperen', () => {
  it('toont zoeken en filteren niet bij 8 punten of minder', async () => {
    serve(reportWith(NINE_WARNINGS.slice(0, 8)));
    renderDetail();
    await screen.findByTestId('legacy-card-warnings');
    await openCard('Waarschuwingen');
    expect(screen.getByTestId('legacy-report')).toBeInTheDocument();
    expect(screen.queryByTestId('legacy-report-filter')).not.toBeInTheDocument();
  });

  it('toont zoeken en filteren bij 9 punten en filtert op woord en op soort', async () => {
    serve(reportWith(NINE_WARNINGS));
    renderDetail();
    await screen.findByTestId('legacy-card-warnings');
    await openCard('Waarschuwingen');

    expect(screen.getByTestId('legacy-report-filter')).toBeInTheDocument();
    expect(screen.getAllByTestId(/^legacy-item-/)).toHaveLength(9);

    // Zoeken op het Nederlandse woord.
    fireEvent.change(screen.getByLabelText('Zoek in de punten'), { target: { value: 'planning' } });
    expect(screen.getAllByTestId(/^legacy-item-/)).toHaveLength(1);
    expect(screen.getByTestId('legacy-item-SCHEDULE_NOT_MIGRATED')).toBeInTheDocument();

    // Zoeken op het legacy-element.
    fireEvent.change(screen.getByLabelText('Zoek in de punten'), { target: { value: 'PSIMPDEFMAP' } });
    expect(screen.getAllByTestId(/^legacy-item-/)).toHaveLength(1);
    expect(screen.getByTestId('legacy-item-MAP_EMPTY')).toBeInTheDocument();

    // Zoeken op de code.
    fireEvent.change(screen.getByLabelText('Zoek in de punten'), { target: { value: 'connection_not' } });
    expect(screen.getAllByTestId(/^legacy-item-/)).toHaveLength(1);

    fireEvent.change(screen.getByLabelText('Zoek in de punten'), { target: { value: 'bestaat niet' } });
    expect(screen.queryAllByTestId(/^legacy-item-/)).toHaveLength(0);
    expect(screen.getByText('Geen punten die bij de zoekopdracht passen.')).toBeInTheDocument();

    // Filter op soort.
    fireEvent.change(screen.getByLabelText('Zoek in de punten'), { target: { value: '' } });
    const chip = screen.getByRole('button', { name: 'Wordt niet overgenomen (4)' });
    fireEvent.click(chip);
    expect(chip).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getAllByTestId(/^legacy-item-/)).toHaveLength(4);
    expect(screen.queryByTestId('legacy-group-WARNING')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Alle (9)' }));
    expect(screen.getAllByTestId(/^legacy-item-/)).toHaveLength(9);
  });

  it('groepeert boven 5 gelijke punten per code met ×n en laat de onderdelen openklappen', async () => {
    const calcs = [1, 2, 3, 4, 5, 6].map((n) => item(`LEGACY_CALC_${n}_NOT_MIGRATED`, 'WARNING', `PSIMPDEF#${n + 10}`));
    serve(reportWith([...calcs, item('SEPARATOR_NOT_DECLARED', 'WARNING', 'INDET:Separator')]));
    renderDetail();
    await screen.findByTestId('legacy-card-warnings');
    await openCard('Waarschuwingen');

    const group = screen.getByTestId('legacy-code-group-LEGACY_CALC_NOT_MIGRATED');
    expect(within(group).getByRole('heading', { name: /Berekening nog niet ondersteund/ })).toHaveTextContent('×6');
    // De onderdelen staan in de groep, met hun legacy-onderdeel in gewoon Nederlands.
    expect(within(group).getByTestId('legacy-item-LEGACY_CALC_3_NOT_MIGRATED')).toHaveTextContent('regel 13 van de importsoort');
    expect(within(group).getAllByTestId(/^legacy-item-/)).toHaveLength(6);
    expect(within(group).getByText('Toon de 6 onderdelen')).toBeInTheDocument();
    // Een enkel punt blijft een eigen kaart.
    expect(screen.getByTestId('legacy-item-SEPARATOR_NOT_DECLARED')).toBeInTheDocument();
  });

  it('groepeert niet bij 5 gelijke punten', async () => {
    const calcs = [1, 2, 3, 4, 5].map((n) => item(`LEGACY_CALC_${n}_NOT_MIGRATED`, 'WARNING', `PSIMPDEF#${n}`));
    serve(reportWith(calcs));
    renderDetail();
    await screen.findByTestId('legacy-card-warnings');
    await openCard('Waarschuwingen');
    expect(screen.queryByTestId('legacy-code-group-LEGACY_CALC_NOT_MIGRATED')).not.toBeInTheDocument();
    expect(screen.getAllByTestId(/^legacy-item-/)).toHaveLength(5);
  });

  it('zet de legacy-omschrijving bovenaan het punt en de ruwe code enkel in de technische details', async () => {
    serve(reportWith([item('CHARSET_DEFAULTED_UTF8', 'BLOCKING', 'INDET:FILE_ENCODING')]));
    renderDetail();
    const card = await screen.findByTestId('legacy-item-CHARSET_DEFAULTED_UTF8');
    expect(card).toHaveTextContent('interface-instelling FILE_ENCODING');
    const clone = card.cloneNode(true) as HTMLElement;
    clone.querySelectorAll('details').forEach((element) => element.remove());
    expect(clone.textContent).not.toContain('CHARSET_DEFAULTED_UTF8');
    expect(card.querySelector('details')).toHaveTextContent('INDET:FILE_ENCODING');
  });
});

describe('rapportbron per status', () => {
  it('toont bij een overgenomen ontvangst de kaarten van het bewaarde rapport en vraagt geen controle', async () => {
    const stored: LegacyConfigReport = {
      ...applicableReport(),
      receiptStatus: 'APPLIED',
      items: [item('SEPARATOR_NOT_DECLARED', 'WARNING'), item('DEFINITION', 'MIGRATED', 'PSIMDHFD')],
    };
    const api = mockApi(() =>
      jsonResponse({ ...receiptDetail({ status: 'APPLIED', appliedBy: 'An Beslisser', appliedAt: '2026-10-21T09:00:00Z' }), applyReport: stored }),
    );
    renderDetail();

    expect(await screen.findByTestId('legacy-card-count-warnings')).toHaveTextContent('1');
    expect(screen.getByRole('heading', { name: 'Overgenomen' })).toBeInTheDocument();
    expect(screen.getByTestId('legacy-card-count-migrated')).toHaveTextContent('1');
    expect(within(screen.getByTestId('legacy-card-blocking')).getByRole('button')).toBeDisabled();
    expect(screen.getByTestId('legacy-origin-facts')).toHaveTextContent('An Beslisser');
    expect(screen.queryByLabelText('Andere code voor de importdefinitie')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Overnemen…' })).not.toBeInTheDocument();
    expect(api.reads('/preview')).toHaveLength(0);
  });

  it('toont bij een verworpen ontvangst de auditkaart en laat de rapportkaarten weg', async () => {
    const api = mockApi(() =>
      jsonResponse({
        ...receiptDetail({ status: 'DISCARDED' }),
        discardedAt: '2026-10-22T09:00:00Z',
        discardedBy: 'beheerder@example.test',
        discardedReason: 'Niet nodig',
      }),
    );
    renderDetail();

    const audit = await screen.findByTestId('legacy-discarded-audit');
    expect(within(audit).getByRole('heading', { name: 'Verworpen' })).toBeInTheDocument();
    expect(audit).toHaveTextContent('Niet nodig');
    expect(audit).toHaveTextContent('beheerder@example.test');
    expect(screen.queryByTestId('legacy-not-applicable')).not.toBeInTheDocument();
    expect(screen.queryByTestId('legacy-card-blocking')).not.toBeInTheDocument();
    expect(screen.queryByTestId('legacy-card-warnings')).not.toBeInTheDocument();
    expect(screen.queryByTestId('legacy-card-migrated')).not.toBeInTheDocument();
    // Wachtwoord, importsoort en bestemming komen uit de ontvangen bundel.
    expect(screen.getByTestId('legacy-card-password')).toBeInTheDocument();
    expect(screen.getByTestId('legacy-card-definition')).toBeInTheDocument();
    expect(screen.getByTestId('legacy-card-target')).toBeInTheDocument();
    expect(api.reads('/preview')).toHaveLength(0);
  });
});

describe('legacyPolicy — pure regels', () => {
  it('summarizeReport telt per soort en geeft het eerste blokkerende punt', () => {
    const summary = summarizeReport(
      reportWith([...FOUR_BLOCKING, item('A', 'WARNING'), item('B', 'WARNING'), item('C', 'NOT_MIGRATED'), item('D', 'MIGRATED')]),
    );
    expect(summary.blocking).toHaveLength(4);
    expect(summary.warning).toHaveLength(2);
    expect(summary.notMigrated).toHaveLength(1);
    expect(summary.migrated).toHaveLength(1);
    expect(summary.warningsTotal).toBe(3);
    expect(summary.firstBlocking?.code).toBe('LIBRARY_CODE_NOT_RESOLVED');
    expect(summarizeReport(null).firstBlocking).toBeNull();
  });

  it('groupItemsByCode groepeert pas boven de drempel en houdt de volgorde', () => {
    const six = [1, 2, 3, 4, 5, 6].map((n) => item(`LEGACY_CALC_${n}_NOT_MIGRATED`, 'WARNING'));
    const entries = groupItemsByCode([item('X', 'WARNING'), ...six, item('Y', 'WARNING')]);
    expect(entries.map((entry) => entry.kind)).toEqual(['single', 'group', 'single']);
    expect(groupItemsByCode(six.slice(0, 5)).every((entry) => entry.kind === 'single')).toBe(true);
  });

  it('isSecretItem herkent het geheime label op code en op instelling', () => {
    expect(isSecretItem(item('SECRET_LABEL_OMITTED', 'NOT_MIGRATED'))).toBe(true);
    expect(isSecretItem(item('INTERFACE_SETTING_NOT_MIGRATED', 'NOT_MIGRATED', 'INDET:API_AUTH_PASSWORD'))).toBe(true);
    expect(isSecretItem(item('INTERFACE_SETTING_NOT_MIGRATED', 'NOT_MIGRATED', 'INDET:Separator'))).toBe(false);
  });

  it('describeLegacyPart omschrijft het onderdeel zonder de ruwe code', () => {
    expect(describeLegacyPart('PSIMPDEF#9')).toBe('regel 9 van de importsoort');
    expect(describeLegacyPart('PSIMPDEFMAP#2')).toBe('omzetting 2 van de importsoort');
    expect(describeLegacyPart('PSIMPEXCL#1')).toBe('uitsluiting 1 van de importsoort');
    expect(describeLegacyPart('INDET:FILE_ENCODING')).toBe('interface-instelling FILE_ENCODING');
    expect(describeLegacyPart('PLAN:link')).toBe('de geplande koppeling');
    expect(describeLegacyPart('IETS_ONBEKENDS')).toBe('een onderdeel van legacy');
  });
});
