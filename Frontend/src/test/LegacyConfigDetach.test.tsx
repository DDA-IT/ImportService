/**
 * G2a-3 — loskoppelen van een gedeelde beschrijving in het overnamescherm.
 *
 * Dekt: het vinkje enkel bij `SHARED_DEFINITION_DIVERGED` (of zolang gekozen); de preview wordt herberekend met
 * `detachFromShared` en `detachedDefinitionCode`; de samenvatting van `planned.detach` (met en zonder actieve versie);
 * de bevestigingstekst met de uitzondering op "overnemen activeert nooit"; de APPLY-body met de optie; de blokkerende
 * detach-codes met Nederlandse uitleg; en de links naar de eigen beschrijving en haar versies.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within, configure } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import type { LegacyConfigReport, LegacyPlannedDetach, LegacyReportItem } from '../api/types';
import { LegacyReceiptDetailPage } from '../features/legacyconfig/LegacyReceiptDetailPage';
import { applicableReport, jsonResponse, mockApi, openCard, preview, receiptDetail, type Call } from './legacyConfigFixtures';
import { TEST_IDENTITY } from './testIdentity';

configure({ asyncUtilTimeout: 10000 });
afterEach(() => cleanup());

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

const DIVERGED: LegacyReportItem = {
  element: 'DEFINITION',
  status: 'BLOCKING',
  code: 'SHARED_DEFINITION_DIVERGED',
  target: null,
  message: 'diverges from the shared definition',
};

function item(code: string, status: string): LegacyReportItem {
  return { element: 'DEFINITION', status, code, target: null, message: null };
}

/** Een toepasbaar rapport met een afwijkende gedeelde beschrijving, of zonder. */
function divergedReport(): LegacyConfigReport {
  const base = applicableReport();
  return { ...base, outcome: 'BLOCKED', items: [DIVERGED, ...base.items] };
}

function detachedReport(detach: Partial<LegacyPlannedDetach> = {}): LegacyConfigReport {
  const base = applicableReport();
  const planned: LegacyPlannedDetach = {
    fromDefinitionId: 5,
    fromDefinitionCode: 'GEDEELD-1',
    toDefinitionCode: 'LEGACY-KRAMP_ART',
    copiedRevisionId: 31,
    carriesActive: true,
    ...detach,
  };
  return {
    ...base,
    planned: {
      ...base.planned,
      definition: { ...base.planned.definition!, id: 9, code: 'LEGACY-KRAMP_ART', revisionId: 32 },
      link: { ...base.planned.link!, action: 'REPOINT' },
      detach: planned,
    },
    items: [item('DETACH_FROM_SHARED', 'MIGRATED'), ...base.items],
  };
}

function setup(handler: (call: Call) => Response | null) {
  return mockApi((call) => {
    const custom = handler(call);
    if (custom !== null) return custom;
    if (call.url.includes('/preview')) return jsonResponse(preview(applicableReport(), 'fp-plain'));
    return jsonResponse(receiptDetail());
  });
}

const CHECKBOX = 'Loskoppelen tot een eigen beschrijving';

describe('loskoppelen — keuze in de controle vooraf', () => {
  it('toont het vinkje niet zonder afwijkende gedeelde beschrijving', async () => {
    setup(() => null);
    renderDetail();
    await screen.findByTestId('legacy-outcome');
    expect(screen.queryByLabelText(CHECKBOX)).not.toBeInTheDocument();
  });

  it('toont het vinkje bij SHARED_DEFINITION_DIVERGED en rekent de controle opnieuw met de optie', async () => {
    const api = setup((call) => {
      if (!call.url.includes('/preview')) return null;
      const query = new URL(call.url, 'http://x').searchParams;
      if (query.get('detachFromShared') === 'true') {
        return jsonResponse(preview(detachedReport(), query.get('detachedDefinitionCode') === 'MIJN-CODE' ? 'fp-code' : 'fp-detach'));
      }
      return jsonResponse(preview(divergedReport(), 'fp-diverged'));
    });
    renderDetail();

    const box = await screen.findByLabelText(CHECKBOX);
    expect(box).not.toBeChecked();
    expect(screen.queryByLabelText('Code van de eigen beschrijving')).not.toBeInTheDocument();
    fireEvent.click(box);

    await waitFor(() => expect(api.reads('/preview')).toHaveLength(2));
    expect(api.reads('/preview')[1]!.url).toContain('detachFromShared=true');
    // Het vinkje blijft staan nu SHARED_DEFINITION_DIVERGED door DETACH_FROM_SHARED vervangen is.
    expect(await screen.findByLabelText(CHECKBOX)).toBeChecked();

    fireEvent.change(screen.getByLabelText('Code van de eigen beschrijving'), { target: { value: ' MIJN-CODE ' } });
    fireEvent.click(screen.getByRole('button', { name: 'Controle opnieuw uitvoeren' }));
    await waitFor(() => expect(api.reads('/preview')).toHaveLength(3));
    const url = api.reads('/preview')[2]!.url;
    expect(url).toContain('detachFromShared=true');
    expect(url).toContain('detachedDefinitionCode=MIJN-CODE');
  });
});

describe('loskoppelen — samenvatting, bevestiging en APPLY', () => {
  async function openDialog() {
    const open = await screen.findByRole('button', { name: 'Overnemen…' });
    await waitFor(() => expect(open).toBeEnabled());
    fireEvent.click(open);
    return screen.getByRole('dialog');
  }

  function detachSetup(detach: Partial<LegacyPlannedDetach> = {}) {
    return setup((call) => {
      if (call.url.includes('/apply')) {
        return jsonResponse({ report: { ...detachedReport(detach), receiptStatus: 'APPLIED' }, receiptStatus: 'APPLIED', configurationFindings: [] });
      }
      if (call.url.includes('/preview')) {
        const query = new URL(call.url, 'http://x').searchParams;
        return jsonResponse(
          query.get('detachFromShared') === 'true'
            ? preview(detachedReport(detach), 'fp-detach')
            : preview(divergedReport(), 'fp-diverged'),
        );
      }
      return null;
    });
  }

  it('toont planned.detach in de samenvatting met de activatie van de actieve versie', async () => {
    detachSetup();
    renderDetail();
    fireEvent.click(await screen.findByLabelText(CHECKBOX));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Overnemen…' })).toBeEnabled());
    await openCard('Wordt overgenomen');

    const detach = await screen.findByTestId('legacy-detach');
    expect(detach).toHaveTextContent('GEDEELD-1');
    expect(detach).toHaveTextContent('LEGACY-KRAMP_ART');
    const planned = screen.getByTestId('legacy-planned');
    expect(planned).toHaveTextContent('actieve versie van de gedeelde beschrijving wordt meteen als eigen versie actief');
    expect(planned).toHaveTextContent('nieuwe inhoud van legacy wordt een conceptversie');
    expect(planned).toHaveTextContent('Wordt verlegd');
  });

  it('zonder actieve versie: geen activatie in de samenvatting', async () => {
    detachSetup({ carriesActive: false, copiedRevisionId: null });
    renderDetail();
    fireEvent.click(await screen.findByLabelText(CHECKBOX));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Overnemen…' })).toBeEnabled());
    await openCard('Wordt overgenomen');
    const planned = await screen.findByTestId('legacy-planned');
    await waitFor(() => expect(planned).toHaveTextContent('heeft geen actieve versie'));
    expect(planned).not.toHaveTextContent('wordt meteen als eigen versie actief');
  });

  it('vermeldt de activatie in de bevestigingstekst en stuurt de optie mee in APPLY', async () => {
    const api = detachSetup();
    renderDetail();
    fireEvent.click(await screen.findByLabelText(CHECKBOX));
    await waitFor(() => expect(api.reads('/preview')).toHaveLength(2));
    const dialog = await openDialog();

    expect(within(dialog).getByTestId('legacy-detach-confirm')).toHaveTextContent('meteen als eigen versie geactiveerd');
    expect(within(dialog).getByText(/Enige uitzondering: bij loskoppelen/)).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'Loskoppelen KRAMP_ART' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Configuratie overnemen' }));

    await waitFor(() => expect(screen.getByTestId('legacy-apply-result')).toBeInTheDocument());
    expect(api.writes()).toHaveLength(1);
    expect(api.writes()[0]!.body).toEqual({
      detachFromShared: true,
      previewFingerprint: 'fp-detach',
      reason: 'Loskoppelen KRAMP_ART',
    });

    // Links: nieuwe conceptversie en de overgenomen actieve versie van de eigen beschrijving.
    const links = within(screen.getByTestId('legacy-draft-links'));
    expect(links.getByRole('link', { name: 'Eigen beschrijving LEGACY-KRAMP_ART, nieuwe conceptversie' })).toHaveAttribute(
      'href',
      '/setup?definitionId=9&revisionId=32',
    );
    const copy = links.getByRole('link', { name: 'Eigen beschrijving LEGACY-KRAMP_ART, overgenomen actieve versie' });
    expect(copy).toHaveAttribute('href', '/setup?definitionId=9&revisionId=31');
  });

  it('stuurt de eigen code mee als die is ingevuld', async () => {
    const api = detachSetup();
    renderDetail();
    fireEvent.click(await screen.findByLabelText(CHECKBOX));
    fireEvent.change(await screen.findByLabelText('Code van de eigen beschrijving'), { target: { value: 'MIJN-CODE' } });
    fireEvent.click(screen.getByRole('button', { name: 'Controle opnieuw uitvoeren' }));
    await waitFor(() => expect(api.reads('/preview')).toHaveLength(3));
    const dialog = await openDialog();
    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'Reden' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Configuratie overnemen' }));
    await waitFor(() => expect(api.writes()).toHaveLength(1));
    expect(api.writes()[0]!.body).toMatchObject({ detachFromShared: true, detachedDefinitionCode: 'MIJN-CODE' });
  });
});

describe('loskoppelen — blokkerende codes', () => {
  it.each([
    ['DETACH_OPEN_BATCH', 'Loskoppelen kan niet: er staat nog een bestand open'],
    ['DETACH_RUN_IN_PROGRESS', 'Loskoppelen kan niet: er loopt een inname'],
    ['DETACHED_DEFINITION_CODE_CONFLICT', 'Code van de eigen beschrijving is al in gebruik'],
    ['DETACHED_DEFINITION_CODE_INVALID', 'Gekozen code van de eigen beschrijving is ongeldig'],
  ])('%s toont een Nederlandse uitleg en blokkeert overnemen', async (code, label) => {
    setup((call) => {
      if (!call.url.includes('/preview')) return null;
      const base = divergedReport();
      return jsonResponse(preview({ ...base, items: [item(code, 'BLOCKING')] }, 'fp-x'));
    });
    renderDetail();

    expect(within(await screen.findByTestId('legacy-panel-blocking')).getByText(label)).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Overnemen…' })).toBeDisabled();
  });
});
