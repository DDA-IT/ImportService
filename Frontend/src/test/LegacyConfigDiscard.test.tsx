/**
 * G1 — ontvangst verwerpen (G1a) en links naar de aangemaakte conceptversie (G1b) op het detail van een ontvangst.
 *
 * Dekt: knop enkel bij `RECEIVED`; dialoog met verplichte reden (geen verzoek zonder); het verzoek en het herladen na
 * succes; 409 `LEGACY_RECEIPT_NOT_APPLICABLE`, 400 `LEGACY_DISCARD_REASON_REQUIRED` en 403 in de open dialoog; de
 * audit van een verworpen ontvangst; de links naar de gedeelde beschrijving en het sjabloon na APPLY en bij een
 * overgenomen ontvangst (met en zonder versie-id).
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within, configure } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { LegacyReceiptDetailPage } from '../features/legacyconfig/LegacyReceiptDetailPage';
import {
  applicableReport,
  errorResponse,
  jsonResponse,
  mockApi,
  preview,
  receiptDetail,
  type Call,
} from './legacyConfigFixtures';
import { TEST_IDENTITY } from './testIdentity';
import type { LegacyConfigReport, LegacyReceiptDetail } from '../api/types';

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

function setup(options: { discardResponse?: (call: Call) => Response; details?: Response[] } = {}) {
  const details = [...(options.details ?? [])];
  return mockApi((call) => {
    if (call.url.endsWith('/me')) {
      return jsonResponse(TEST_IDENTITY);
    }
    if (call.url.includes('/discard')) {
      return (options.discardResponse ?? (() => jsonResponse({ receiptId: 7, receiptStatus: 'DISCARDED' })))(call);
    }
    if (call.url.includes('/preview')) {
      return jsonResponse(preview(applicableReport(), 'fp-1'));
    }
    return details.length > 1 ? details.shift()! : (details[0] ?? jsonResponse(receiptDetail()));
  });
}

async function openDialog() {
  const open = await screen.findByRole('button', { name: 'Verwerpen…' });
  await waitFor(() => expect(open).toBeEnabled());
  fireEvent.click(open);
  return screen.getByRole('dialog');
}

function confirm(dialog: HTMLElement, reason = 'Niet nodig') {
  fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: reason } });
  fireEvent.click(within(dialog).getByRole('button', { name: 'Ontvangst verwerpen' }));
}

function appliedReport(): LegacyConfigReport {
  const base = applicableReport();
  return {
    ...base,
    receiptStatus: 'APPLIED',
    planned: {
      ...base.planned,
      template: { action: 'CREATE', id: 31, code: 'VROOAM2-SJ', revisionId: 301 },
      definition: { ...base.planned.definition!, action: 'CREATE', id: 32, revisionId: 302 },
    },
  };
}

configure({ asyncUtilTimeout: 10000 });

afterEach(() => cleanup());

describe('verwerpen — knop en dialoog', () => {
  it('toont de knop enkel bij een ontvangst met status RECEIVED', async () => {
    setup({ details: [jsonResponse(receiptDetail({ status: 'APPLIED' }))] });
    renderDetail();
    await screen.findByTestId('legacy-origin-facts');
    expect(screen.queryByRole('button', { name: 'Verwerpen…' })).not.toBeInTheDocument();
  });

  it('eist een reden: zonder reden gaat er geen verzoek weg', async () => {
    const api = setup();
    renderDetail();
    const dialog = await openDialog();
    const submit = within(dialog).getByRole('button', { name: 'Ontvangst verwerpen' });
    expect(submit).toBeDisabled();
    fireEvent.click(submit);
    expect(api.writes()).toHaveLength(0);
  });

  it('stuurt de reden mee, sluit de dialoog en laadt de ontvangst opnieuw', async () => {
    const verworpen: LegacyReceiptDetail = {
      ...receiptDetail({ status: 'DISCARDED' }),
      discardedAt: '2026-10-22T09:00:00Z',
      discardedBy: 'beheerder@example.test',
      discardedReason: 'Niet nodig',
    };
    const api = setup({ details: [jsonResponse(receiptDetail()), jsonResponse(verworpen)] });
    renderDetail();
    const dialog = await openDialog();

    confirm(dialog, '  Niet nodig  ');

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(api.writes()).toHaveLength(1);
    const write = api.writes()[0]!;
    expect(write.method).toBe('POST');
    expect(write.url).toBe('/api/catalog-import/legacy-config/receipts/7/discard');
    expect(write.body).toEqual({ reason: 'Niet nodig' });
    // Na het herladen: geen knop meer, wel de audit.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Verwerpen…' })).not.toBeInTheDocument());
    const audit = await screen.findByTestId('legacy-discarded-audit');
    expect(audit).toHaveTextContent('Reden van het verwerpen');
    expect(audit).toHaveTextContent('Niet nodig');
    expect(audit).toHaveTextContent('beheerder@example.test');
    expect(screen.queryByTestId('legacy-not-applicable')).not.toBeInTheDocument();
  });
});

describe('verwerpen — fouten', () => {
  it('409 LEGACY_RECEIPT_NOT_APPLICABLE: toont de melding en laadt de ontvangst opnieuw', async () => {
    const api = setup({
      discardResponse: () => errorResponse(409, 'LEGACY_RECEIPT_NOT_APPLICABLE'),
      details: [jsonResponse(receiptDetail()), jsonResponse(receiptDetail({ status: 'APPLIED' }))],
    });
    renderDetail();
    const dialog = await openDialog();
    confirm(dialog);

    expect(await within(dialog).findByText('Deze ontvangst kan niet meer overgenomen worden')).toBeInTheDocument();
    await waitFor(() =>
      expect(api.reads('/legacy-config/receipts/7').filter((call) => !call.url.includes('/preview'))).toHaveLength(2),
    );
  });

  it.each([
    [400, 'LEGACY_DISCARD_REASON_REQUIRED', 'Reden ontbreekt'],
    [403, 'PERMISSION_DENIED', null],
  ])('%i %s: toont de melding in de open dialoog en herhaalt niets', async (status, code, title) => {
    const api = setup({ discardResponse: () => errorResponse(status, code) });
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
  });
});

describe('links naar de aangemaakte conceptversie', () => {
  it('toont na een geslaagde overname links naar de gedeelde importdefinitie en de importdefinitie', async () => {
    mockApi((call) => {
      if (call.url.endsWith('/me')) return jsonResponse(TEST_IDENTITY);
      if (call.url.includes('/apply')) {
        return jsonResponse({ report: appliedReport(), receiptStatus: 'APPLIED', configurationFindings: [] });
      }
      if (call.url.includes('/preview')) return jsonResponse(preview(applicableReport(), 'fp-1'));
      return jsonResponse(receiptDetail());
    });
    renderDetail();
    const open = await screen.findByRole('button', { name: 'Overnemen…' });
    await waitFor(() => expect(open).toBeEnabled());
    fireEvent.click(open);
    const dialog = screen.getByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'Overname' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Configuratie overnemen' }));

    const links = await screen.findByTestId('legacy-draft-links');
    expect(within(links).getByRole('link', { name: /VROOAM2$/ })).toHaveAttribute('href', '/setup?definitionId=32&revisionId=302');
    expect(within(links).getByRole('link', { name: /VROOAM2-SJ/ })).toHaveAttribute('href', '/setup?definitionId=31&revisionId=301');
  });

  it('toont op het detail van een overgenomen ontvangst dezelfde links uit het bewaarde rapport', async () => {
    setup({
      details: [jsonResponse({ ...receiptDetail({ status: 'APPLIED' }), applyReport: appliedReport() })],
    });
    renderDetail();

    const links = await screen.findByTestId('legacy-draft-links');
    expect(within(links).getAllByRole('link')).toHaveLength(2);
    expect(within(links).getByRole('link', { name: /VROOAM2-SJ/ })).toHaveAttribute('href', '/setup?definitionId=31&revisionId=301');
  });

  it('toont geen link zonder versie-id (bv. een koppeling op een ongewijzigde importdefinitie)', async () => {
    const base = appliedReport();
    const zonderVersie: LegacyConfigReport = {
      ...base,
      planned: {
        ...base.planned,
        template: { action: 'REUSE', id: 31, code: 'VROOAM2-SJ', revisionId: null },
        definition: { ...base.planned.definition!, revisionId: null },
      },
    };
    setup({ details: [jsonResponse({ ...receiptDetail({ status: 'APPLIED' }), applyReport: zonderVersie })] });
    renderDetail();

    await screen.findByTestId('legacy-origin-facts');
    expect(screen.queryByTestId('legacy-draft-links')).not.toBeInTheDocument();
  });
});
