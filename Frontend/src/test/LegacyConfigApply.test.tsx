/**
 * S9 — overnemen (APPLY) vanuit het detail van een ontvangst: de dialoog met verplichte reden en samenvatting, het
 * meesturen van `previewFingerprint` en de gekozen bibliotheek, en de foutafhandeling.
 *
 * Dekt: knop uit mét reden bij een blokkerend rapport of een niet-toepasbare ontvangst; reden verplicht (geen verzoek
 * zonder); het verzoek met fingerprint, reden en keuzes; het resultaat (rapport, configuratiebevindingen, niets
 * geactiveerd); 409 `LEGACY_PREVIEW_STALE` (controle opnieuw laden, nooit automatisch opnieuw versturen, daarna met de
 * nieuwe fingerprint), 409 `LEGACY_APPLY_BLOCKED`, 409 `LEGACY_RECEIPT_NOT_APPLICABLE`, 503 `PRODIS_DB_UNAVAILABLE`,
 * 403 `PERMISSION_DENIED` en 400 `LEGACY_APPLY_REASON_TOO_LONG`; en dat er nooit een secretwaarde in beeld komt.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within, configure } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { LegacyReceiptDetailPage } from '../features/legacyconfig/LegacyReceiptDetailPage';
import {
  SECRET_VALUE,
  applicableReport,
  errorResponse,
  jsonResponse,
  mockApi,
  preview,
  receiptDetail,
  report,
  type Call,
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

const APPLY_RESULT = {
  report: { ...applicableReport(), receiptStatus: 'APPLIED' },
  receiptStatus: 'APPLIED',
  configurationFindings: [
    { code: 'CONFIG_PRICE_FIELD_MISSING', fieldName: 'recordBasePriceField', message: 'the base price field is missing' },
  ],
};

/** Een toepasbare ontvangst; `applyResponse` bepaalt wat APPLY antwoordt, `previews` de opeenvolgende controles. */
function setup(options: { applyResponse?: (call: Call) => Response; previews?: Response[]; detailResponses?: Response[] } = {}) {
  const previews = [...(options.previews ?? [])];
  const details = [...(options.detailResponses ?? [])];
  return mockApi((call) => {
    // Een 403 PERMISSION_DENIED laat de aanmelding de rechten opnieuw lezen (/me).
    if (call.url.endsWith('/me')) {
      return jsonResponse(TEST_IDENTITY);
    }
    if (call.url.includes('/apply')) {
      return (options.applyResponse ?? (() => jsonResponse(APPLY_RESULT)))(call);
    }
    if (call.url.includes('/preview')) {
      return previews.length > 1 ? previews.shift()! : (previews[0] ?? jsonResponse(preview(applicableReport(), 'fp-1')));
    }
    return details.length > 1 ? details.shift()! : (details[0] ?? jsonResponse(receiptDetail()));
  });
}

async function openDialog() {
  const open = await screen.findByRole('button', { name: 'Overnemen…' });
  await waitFor(() => expect(open).toBeEnabled());
  fireEvent.click(open);
  return screen.getByRole('dialog');
}

function confirm(dialog: HTMLElement, reason = 'Overname KLANT01') {
  fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: reason } });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Configuratie overnemen' }));
}

// Onder belasting (volledige suite) kan de asynchrone preview langer dan de standaard 1 s duren.
configure({ asyncUtilTimeout: 10000 });

afterEach(() => cleanup());

describe('overnemen — knop en dialoog', () => {
  it('zet de knop uit mét reden zolang het rapport blokkeert', async () => {
    const api = setup({ previews: [jsonResponse(preview(report(), 'fp-1'))] });
    renderDetail();

    const button = await screen.findByRole('button', { name: 'Overnemen…' });
    expect(button).toBeDisabled();
    expect(await screen.findByTestId('legacy-apply-blocked-reason')).toHaveTextContent('blokkerende punten');
    expect(button).toHaveAttribute('title', expect.stringContaining('blokkerende punten'));
    fireEvent.click(button);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(api.writes()).toHaveLength(0);
  });

  it('toont de samenvatting en eist een reden: zonder reden gaat er geen verzoek weg', async () => {
    const api = setup();
    renderDetail();
    const dialog = await openDialog();

    expect(within(dialog).getByTestId('legacy-planned')).toHaveTextContent('VROOAM2-SJ');
    expect(within(dialog).getByText(/Er wordt niets geactiveerd/)).toBeInTheDocument();
    const submit = within(dialog).getByRole('button', { name: 'Configuratie overnemen' });
    expect(submit).toBeDisabled();
    expect(within(dialog).getByText('Vul een reden in.')).toBeInTheDocument();
    fireEvent.click(submit);
    expect(api.writes()).toHaveLength(0);
  });

  it('stuurt previewFingerprint, reden en de gekozen bibliotheek mee en toont het resultaat', async () => {
    const api = setup({
      previews: [jsonResponse(preview(report(), 'fp-1')), jsonResponse(preview(applicableReport(), 'fp-lib2'))],
    });
    const { container } = renderDetail();
    await waitFor(() => expect(screen.getByLabelText('Bibliotheek van de koppeling').tagName).toBe('SELECT'));
    fireEvent.change(screen.getByLabelText('Bibliotheek van de koppeling'), { target: { value: 'LIB2' } });
    const dialog = await openDialog();

    confirm(dialog, '  Overname KLANT01  ');

    await waitFor(() => expect(screen.getByTestId('legacy-apply-result')).toBeInTheDocument());
    expect(api.writes()).toHaveLength(1);
    const write = api.writes()[0]!;
    expect(write.method).toBe('POST');
    expect(write.url).toBe('/api/catalog-import/legacy-config/receipts/7/apply');
    expect(write.body).toEqual({ libraryCode: 'LIB2', previewFingerprint: 'fp-lib2', reason: 'Overname KLANT01' });

    // Het resultaat: rapport, bevinding van de nieuwe conceptversie, en uitdrukkelijk niets geactiveerd.
    const result = screen.getByTestId('legacy-apply-result');
    expect(result).toHaveTextContent('Er is niets geactiveerd');
    expect(within(screen.getByTestId('legacy-apply-findings')).getByText('Prijskolom ontbreekt')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(container.textContent).not.toContain(SECRET_VALUE);
    // De ontvangst wordt herladen; het blijft één verzoek tot overnemen.
    await waitFor(() => expect(api.reads('/legacy-config/receipts/7')).not.toHaveLength(0));
  });

  it('stuurt enkel de ingevulde keuzes mee', async () => {
    const api = setup();
    renderDetail();
    await screen.findByTestId('legacy-outcome');
    const dialog = await openDialog();
    confirm(dialog);
    await waitFor(() => expect(api.writes()).toHaveLength(1));
    expect(api.writes()[0]!.body).toEqual({ previewFingerprint: 'fp-1', reason: 'Overname KLANT01' });
  });
});

describe('overnemen — fouten', () => {
  it('409 LEGACY_PREVIEW_STALE: er is niets overgenomen, de controle wordt opnieuw geladen en daarna geldt de nieuwe fingerprint', async () => {
    const api = setup({
      applyResponse: (call) =>
        (call.body as { previewFingerprint: string }).previewFingerprint === 'fp-1'
          ? errorResponse(409, 'LEGACY_PREVIEW_STALE')
          : jsonResponse(APPLY_RESULT),
      previews: [jsonResponse(preview(applicableReport(), 'fp-1')), jsonResponse(preview(applicableReport(), 'fp-2'))],
    });
    renderDetail();
    const dialog = await openDialog();
    confirm(dialog);

    expect(await within(dialog).findByText('De controle vooraf is verouderd')).toBeInTheDocument();
    expect(within(dialog).getByText(/Lees de nieuwe samenvatting/)).toBeInTheDocument();
    // De controle is opnieuw geladen; er is nog niets automatisch opnieuw verstuurd.
    await waitFor(() => expect(api.reads('/preview')).toHaveLength(2));
    expect(api.writes()).toHaveLength(1);
    expect(screen.queryByTestId('legacy-apply-result')).not.toBeInTheDocument();

    // De mens bevestigt opnieuw: nu met de nieuwe fingerprint.
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Configuratie overnemen' })).toBeEnabled());
    fireEvent.click(within(dialog).getByRole('button', { name: 'Configuratie overnemen' }));
    await waitFor(() => expect(screen.getByTestId('legacy-apply-result')).toBeInTheDocument());
    expect(api.writes()).toHaveLength(2);
    expect((api.writes()[1]!.body as { previewFingerprint: string }).previewFingerprint).toBe('fp-2');
  });

  it('409 LEGACY_APPLY_BLOCKED: toont de melding, laadt de controle opnieuw en blokkeert bevestigen', async () => {
    const api = setup({
      applyResponse: () => errorResponse(409, 'LEGACY_APPLY_BLOCKED', 'cannot be applied; blocking: LIBRARY_CODE_NOT_RESOLVED'),
      previews: [jsonResponse(preview(applicableReport(), 'fp-1')), jsonResponse(preview(report(), 'fp-3'))],
    });
    renderDetail();
    const dialog = await openDialog();
    confirm(dialog);

    expect(await within(dialog).findByText('Overnemen is geblokkeerd')).toBeInTheDocument();
    await waitFor(() => expect(api.reads('/preview')).toHaveLength(2));
    await waitFor(() => expect(within(dialog).getByTestId('confirm-blocked-reason')).toHaveTextContent('blokkerende punten'));
    expect(within(dialog).getByRole('button', { name: 'Configuratie overnemen' })).toBeDisabled();
    expect(api.writes()).toHaveLength(1);
  });

  it('409 LEGACY_RECEIPT_NOT_APPLICABLE: toont de melding en laadt de ontvangst opnieuw', async () => {
    const api = setup({
      applyResponse: () => errorResponse(409, 'LEGACY_RECEIPT_NOT_APPLICABLE'),
      detailResponses: [jsonResponse(receiptDetail()), jsonResponse(receiptDetail({ status: 'SUPERSEDED' }))],
    });
    renderDetail();
    const dialog = await openDialog();
    confirm(dialog);

    expect(await within(dialog).findByText('Deze ontvangst kan niet meer overgenomen worden')).toBeInTheDocument();
    await waitFor(() => expect(api.reads('/legacy-config/receipts/7').filter((call) => !call.url.includes('/preview'))).toHaveLength(2));
    // Met de nieuwe status is overnemen niet meer toegestaan.
    await waitFor(() => expect(within(dialog).getByTestId('confirm-blocked-reason')).toHaveTextContent('status "Ontvangen"'));
  });

  it.each([
    [503, 'PRODIS_DB_UNAVAILABLE', 'Prodis-database is niet bereikbaar'],
    [403, 'PERMISSION_DENIED', null],
    [400, 'LEGACY_APPLY_REASON_TOO_LONG', 'Reden is te lang'],
    [409, 'SOURCE_ORGANISATION_CODE_IN_USE', null],
  ])('%i %s: toont de melding in de open dialoog en herhaalt niets', async (status, code, title) => {
    const api = setup({ applyResponse: () => errorResponse(status, code) });
    renderDetail();
    const dialog = await openDialog();
    confirm(dialog);

    const alert = await within(dialog).findByRole('alert');
    if (title !== null) {
      expect(within(alert).getByText(title)).toBeInTheDocument();
    }
    expect(within(alert).getByText(new RegExp(`${code} · HTTP ${status}`))).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(api.writes()).toHaveLength(1);
    // Geen automatische nieuwe controle bij deze fouten.
    expect(api.reads('/preview')).toHaveLength(1);
    expect(screen.queryByTestId('legacy-apply-result')).not.toBeInTheDocument();
  });

  it('sluit de dialoog zonder iets te versturen', async () => {
    const api = setup();
    renderDetail();
    const dialog = await openDialog();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Sluiten zonder over te nemen' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(api.writes()).toHaveLength(0);
  });
});
