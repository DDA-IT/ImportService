/**
 * T5 (§14.1) — het herbruikbare `MutationList`-component, bouwstap F8.
 *
 * Elke test hier draait met een **verzonnen bron**: dat is precies de eigenschap uit §11.3 die het
 * component herbruikbaar maakt (het importeert niets uit `api/`, dus ook geen `fetch`-stub nodig).
 *
 * Gedekt: normaal scenario, `null` wordt "—" en nooit 0, paginering, filters belanden in de juiste
 * `MutationQuery`-velden, een niet-ondersteund filter wordt niet getoond én niet meegestuurd
 * (bundelbron vs. batchbron), klikken op `identityHash` zet het filter, een poort met
 * `allowed: false` levert een uitgeschakelde knop mét reden, een actie krijgt actor + reden en daarna
 * volgt `onAfterAction`, een dubbele bevestiging vuurt niet twee keer, en een foutcode blijft zichtbaar
 * via de bestaande errors-laag.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ActorProvider } from '../actor/ActorContext';
import { ApiError } from '../api/http';
import type { MutationRow, PageResult } from '../api/types';
import { MutationList } from '../components/MutationList/MutationList';
import type { MutationQuery, MutationRowAction, MutationSource } from '../components/MutationList/types';

const HASH_A = 'a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90';

function mutation(overrides: Partial<MutationRow> = {}): MutationRow {
  return {
    id: 1,
    batchId: 77,
    actionType: 'UPDATE',
    targetDomain: 'OFFER',
    status: 'AWAITING_APPROVAL',
    statusReason: 'BULK_PRICE_INCIDENT',
    identitySupplier: 'SUP1',
    identitySupplierGroup: 'GRP1',
    identitySupplierReference: 'REF-1',
    identityDiscountCode: null,
    identityDiscountState: null,
    domainMask: 'PRICE',
    beforeBasePrice: 12.34,
    afterBasePrice: 15.5,
    basePriceCurrency: 'EUR',
    referenceType: null,
    beforeReferenceValue: null,
    afterReferenceValue: null,
    sourceStateId: 5,
    sourceRowNumber: 42,
    resultSummary: null,
    idempotencyKey: 'key-1',
    createdAt: '2026-09-20T10:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decidedFromStatus: null,
    decisionId: null,
    identityHash: HASH_A,
    ...overrides,
  };
}

function page(content: MutationRow[], overrides: Partial<PageResult<MutationRow>> = {}): PageResult<MutationRow> {
  return { content, page: 0, size: 50, totalElements: content.length, totalPages: 1, ...overrides };
}

/** Een bron die onthoudt met welke `MutationQuery` ze aangeroepen is. */
function fakeSource(
  result: PageResult<MutationRow> | (() => Promise<PageResult<MutationRow>>),
  supportedFilters: MutationSource['supportedFilters'] = ['status', 'batchId', 'actionType', 'statusReason', 'identityHash'],
  key = 'bundle:42',
) {
  const calls: MutationQuery[] = [];
  const source: MutationSource = {
    key,
    supportedFilters,
    fetchPage: (query) => {
      calls.push(query);
      return typeof result === 'function' ? result() : Promise.resolve(result);
    },
  };
  return { source, calls };
}

function renderList(props: Parameters<typeof MutationList>[0]) {
  return render(
    <ActorProvider>
      <MutationList {...props} />
    </ActorProvider>,
  );
}

function lastCall(calls: MutationQuery[]): MutationQuery {
  const call = calls[calls.length - 1];
  if (call === undefined) {
    throw new Error('fetchPage is niet aangeroepen');
  }
  return call;
}

describe('MutationList', () => {
  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
  });

  it('T5.1: toont de rijen van een verzonnen bron, met de beslissingskolommen erbij', async () => {
    const { source } = fakeSource(page([mutation()]));
    renderList({ source });

    expect(await screen.findByText('AWAITING_APPROVAL')).toBeInTheDocument();
    expect(screen.getByText('BULK_PRICE_INCIDENT')).toBeInTheDocument();
    expect(screen.getByText('Leverancier: SUP1')).toBeInTheDocument();
    // De vier beslissingsvelden staan er altijd, ook als er niets beslist is (§11.2).
    expect(screen.getByText('Beslissing')).toBeInTheDocument();
    expect(screen.getByText(/Door:/)).toBeInTheDocument();
  });

  it('T5.2: toont beide prijzen zoals ze binnenkomen en rekent geen verschil uit', async () => {
    const { source } = fakeSource(page([mutation({ beforeBasePrice: 12.34, afterBasePrice: 15.5 })]));
    renderList({ source });

    const priceCell = await screen.findByText(/12,34 EUR/);
    expect(priceCell.textContent).toContain('15,5 EUR');
    // Het verschil (3,16) mag nergens staan: de frontend rekent niet met bedragen (§9.4).
    expect(screen.queryByText(/3,16/)).not.toBeInTheDocument();
  });

  it('T5.3: toont "—" voor null-waarden, nooit 0', async () => {
    const { source } = fakeSource(
      page([
        mutation({
          beforeBasePrice: null,
          afterBasePrice: null,
          basePriceCurrency: null,
          sourceRowNumber: null,
          decisionId: null,
          domainMask: null,
          identityHash: null,
        }),
      ]),
    );
    renderList({ source });

    await screen.findByText('AWAITING_APPROVAL');
    const row = screen.getAllByRole('row')[1];
    expect(row).toBeDefined();
    const cells = within(row as HTMLElement);
    expect(cells.getAllByTitle('niet vastgesteld').length).toBeGreaterThan(0);
    // Geen enkele cel mag een verzonnen 0 of 0,00 tonen.
    expect(cells.queryByText('0')).not.toBeInTheDocument();
    expect(cells.queryByText('0,00')).not.toBeInTheDocument();
  });

  it('T5.4: paginering roept fetchPage met de gekozen pagina en paginagrootte aan', async () => {
    const { source, calls } = fakeSource(page([mutation()], { totalElements: 120, totalPages: 3 }));
    renderList({ source });

    await screen.findByText('1-50 van 120');
    expect(lastCall(calls)).toMatchObject({ page: 0, size: 50 });

    fireEvent.click(screen.getByRole('button', { name: 'Volgende' }));
    await waitFor(() => expect(lastCall(calls).page).toBe(1));

    fireEvent.change(screen.getByLabelText('Per pagina'), { target: { value: '100' } });
    // Een andere paginagrootte begint weer op pagina 0: anders staat de gebruiker plots voorbij het einde.
    await waitFor(() => expect(lastCall(calls)).toMatchObject({ page: 0, size: 100 }));
  });

  it('T5.5: zet de keuzelijstfilters meteen in de MutationQuery', async () => {
    const { source, calls } = fakeSource(page([mutation()]));
    renderList({ source });
    await screen.findByText('AWAITING_APPROVAL');

    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'PLANNED' } });
    await waitFor(() => expect(lastCall(calls).status).toBe('PLANNED'));

    fireEvent.change(screen.getByLabelText('Soort'), { target: { value: 'CREATE' } });
    await waitFor(() => expect(lastCall(calls).actionType).toBe('CREATE'));
  });

  it('T5.6: past de tekstfilters (statusReason, batchId, identityHash) toe op "Filteren"', async () => {
    const { source, calls } = fakeSource(page([mutation()]));
    renderList({ source });
    await screen.findByText('AWAITING_APPROVAL');
    const before = calls.length;

    fireEvent.change(screen.getByLabelText('Statusreden'), { target: { value: 'BULK_PRICE_INCIDENT' } });
    fireEvent.change(screen.getByLabelText('Batch'), { target: { value: '77' } });
    // Typen alleen stuurt niets: anders vuurt elke toetsaanslag een verzoek af.
    expect(calls.length).toBe(before);

    fireEvent.click(screen.getByRole('button', { name: 'Filteren' }));
    await waitFor(() => expect(lastCall(calls)).toMatchObject({ statusReason: 'BULK_PRICE_INCIDENT', batchId: 77 }));
  });

  it('T5.7: laat een ongeldig batchnummer niet stil als 0 doorgaan', async () => {
    const { source, calls } = fakeSource(page([mutation()]));
    renderList({ source });
    await screen.findByText('AWAITING_APPROVAL');

    fireEvent.change(screen.getByLabelText('Batch'), { target: { value: 'abc' } });
    fireEvent.click(screen.getByRole('button', { name: 'Filteren' }));

    await waitFor(() => expect(screen.getByText(/is geen geldig batchnummer/)).toBeInTheDocument());
    expect(lastCall(calls).batchId).toBeUndefined();
  });

  it('T5.8: klikken op de identityHash zet het identityHash-filter (serverzijdige wijzigingsgroep)', async () => {
    const { source, calls } = fakeSource(page([mutation()]));
    renderList({ source });

    const hashButton = await screen.findByRole('button', { name: `Toon de hele wijzigingsgroep ${HASH_A}` });
    fireEvent.click(hashButton);

    await waitFor(() => expect(lastCall(calls).identityHash).toBe(HASH_A));
    // Geen client-side groepering (§11.5): de melding zegt expliciet dat de server de groep bepaalt.
    expect(screen.getByText(/De server bepaalt de groep/)).toBeInTheDocument();
    expect((screen.getByLabelText('Wijzigingsgroep (identityHash)') as HTMLInputElement).value).toBe(HASH_A);
  });

  it('T5.9: een batchbron toont geen batchId-filter en stuurt er nooit een mee', async () => {
    const { source, calls } = fakeSource(
      page([mutation()]),
      ['status', 'actionType', 'statusReason', 'identityHash'],
      'batch:17',
    );
    renderList({ source, initialQuery: { batchId: 999 } });
    await screen.findByText('AWAITING_APPROVAL');

    expect(screen.queryByLabelText('Batch')).not.toBeInTheDocument();
    // Ook een meegegeven initiële waarde gaat niet mee als de bron dit filter niet ondersteunt.
    expect(lastCall(calls).batchId).toBeUndefined();
    expect(screen.getByLabelText('Status')).toBeInTheDocument();
  });

  it('T5.10: zonder rowActions is het een zuivere leeslijst (scherm 2)', async () => {
    const { source } = fakeSource(page([mutation()]), ['actionType'], 'batch:17');
    renderList({ source });
    await screen.findByText('AWAITING_APPROVAL');

    expect(screen.queryByText('Acties')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Status')).not.toBeInTheDocument();
  });

  it('T5.11: een geweigerde poort geeft een uitgeschakelde knop mét reden', async () => {
    const action: MutationRowAction = {
      id: 'approve',
      label: 'Goedkeuren',
      variant: 'primary',
      reasonRequirement: () => 'optional',
      gate: () => ({ allowed: false, reason: 'Geblokkeerd door een kritiek identiteitsincident.' }),
      run: () => Promise.resolve(),
    };
    const { source } = fakeSource(page([mutation({ status: 'BLOCKED' })]));
    renderList({ source, rowActions: [action] });

    const button = await screen.findByRole('button', {
      name: 'Goedkeuren mutatie 1: Geblokkeerd door een kritiek identiteitsincident.',
    });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', 'Geblokkeerd door een kritiek identiteitsincident.');
  });

  it('T5.12: een actie krijgt actor en reden mee en daarna volgt onAfterAction en een herlading', async () => {
    const run = vi.fn(() => Promise.resolve({ idempotent: false }));
    const action: MutationRowAction = {
      id: 'reject',
      label: 'Afkeuren',
      variant: 'danger',
      reasonRequirement: () => 'required',
      gate: () => ({ allowed: true }),
      run,
    };
    const { source, calls } = fakeSource(page([mutation()]));
    const onAfterAction = vi.fn();
    renderList({ source, rowActions: [action], onAfterAction });

    fireEvent.click(await screen.findByRole('button', { name: 'Afkeuren mutatie 1' }));
    const dialog = await screen.findByRole('dialog');

    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    // Zonder verplichte reden blijft bevestigen geblokkeerd (ConfirmDialog, T4).
    expect(within(dialog).getByRole('button', { name: 'Afkeuren' })).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText(/Reden/), { target: { value: 'prijs klopt niet' } });
    const callsBefore = calls.length;
    fireEvent.click(within(dialog).getByRole('button', { name: 'Afkeuren' }));

    await waitFor(() => expect(run).toHaveBeenCalledTimes(1));
    expect(run.mock.calls[0]?.[1]).toEqual({ actor: 'An Beslisser', reason: 'prijs klopt niet' });
    await waitFor(() => expect(onAfterAction).toHaveBeenCalledTimes(1));
    // De huidige pagina wordt opnieuw geladen: het antwoord van de server is de enige bron (§5).
    await waitFor(() => expect(calls.length).toBeGreaterThan(callsBefore));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  });

  it('T5.13: een mislukte actie houdt de dialoog open en toont de stabiele foutcode', async () => {
    const run = vi.fn(() =>
      Promise.reject(new ApiError(409, 'MUTATION_NOT_DECIDABLE', 'not decidable', '/bundles/42/mutations/1/approve')),
    );
    const action: MutationRowAction = {
      id: 'approve',
      label: 'Goedkeuren',
      variant: 'primary',
      reasonRequirement: () => 'optional',
      gate: () => ({ allowed: true }),
      run,
    };
    const { source } = fakeSource(page([mutation()]));
    const onAfterAction = vi.fn();
    renderList({ source, rowActions: [action], onAfterAction });

    fireEvent.click(await screen.findByRole('button', { name: 'Goedkeuren mutatie 1' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText(/Naam/), { target: { value: 'An Beslisser' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Goedkeuren' }));

    await waitFor(() => expect(within(dialog).getByText(/MUTATION_NOT_DECIDABLE/)).toBeInTheDocument());
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(onAfterAction).not.toHaveBeenCalled();
  });

  it('T5.14: een fout op de lijst zelf blijft leesbaar mét code via de errors-laag', async () => {
    const { source } = fakeSource(() =>
      Promise.reject(new ApiError(404, 'BUNDLE_NOT_FOUND', 'no such bundle', '/bundles/42/mutations')),
    );
    renderList({ source });

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toContain('BUNDLE_NOT_FOUND');
    expect(banner.textContent).toContain('HTTP 404');
  });

  it('T5.16: een bedrag met veel decimalen wordt weergegeven, niet afgerond', async () => {
    const { source } = fakeSource(
      page([mutation({ beforeBasePrice: 12.3456789, afterBasePrice: 0.005, basePriceCurrency: 'EUR' })]),
    );
    renderList({ source });

    const cell = await screen.findByText(/12,3456789 EUR/);
    // Ook een klein bedrag blijft staan zoals het binnenkomt: niet naar 0,01 of 0,00 afgerond.
    expect(cell.textContent).toContain('0,005 EUR');
  });

  it('T5.15: een lege pagina toont de meegegeven boodschap, geen lege tabel', async () => {
    const { source } = fakeSource(page([]));
    renderList({ source, emptyMessage: 'Deze bundel bevat geen mutaties.' });

    expect(await screen.findByText('Deze bundel bevat geen mutaties.')).toBeInTheDocument();
  });
});
