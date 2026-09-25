/**
 * B-F1 — `UploadPage` (`/upload`), uploadscherm (scherm 2), zie `docs/decisions.md` 2026-09-23 stap 9.
 *
 * Dekt: normaal (201 + tellers, "—" bij null, multipart zonder handmatige Content-Type), leeg (geen
 * manuele taak), fout met foutcode (409, 413 zonder code), ongeldige input (niets verstuurd), herhaalde
 * actie (200 = bestaande levering; dubbele klik = één POST; herstelroute na netwerkfout met dezelfde
 * referentie), twee fasen + tijdteller, en de deterministische referentie.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { UploadPage, ElapsedTimer } from '../features/upload/UploadPage';
import { deriveDeliveryReference } from '../features/upload/deliveryReference';

const TASKS = {
  content: [
    {
      id: 5,
      name: 'Handmatige levering',
      active: true,
      triggerType: 'MANUAL',
      preventConcurrentRuns: true,
      importLinkId: 1,
      importLinkCode: 'LNK-1',
      supplierCode: 'SUP1',
      libraryCode: 'LIB1',
      lastRunStartedAt: null,
      lastRunFinishedAt: null,
    },
    {
      id: 6,
      name: 'Nachtelijke import',
      active: true,
      triggerType: 'SCHEDULED',
      preventConcurrentRuns: true,
      importLinkId: 2,
      importLinkCode: 'LNK-2',
      supplierCode: 'SUP2',
      libraryCode: 'LIB2',
      lastRunStartedAt: null,
      lastRunFinishedAt: null,
    },
  ],
  page: 0,
  size: 200,
  totalElements: 2,
  totalPages: 1,
};

const EMPTY_TASKS = { content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 };

const UPLOAD_OK = {
  deliveryId: 9,
  batchId: 55,
  status: 'SCREENED',
  blockedCode: null,
  rawRecordCount: 7,
  validRecordCount: 6,
  rejectedRecordCount: 1,
  duplicateIdentityCount: null,
  newCount: 6,
  changedCount: 0,
  unchangedCount: 0,
  contentMutationCount: 6,
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

type Handler = (init: RequestInit | undefined) => Promise<Response>;

function mockFetch(tasks: unknown, post: Handler) {
  global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url.endsWith('/deliveries')) {
      return post(init);
    }
    if (url.includes('/tasks')) {
      return Promise.resolve(json(tasks));
    }
    return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
  }) as unknown as typeof fetch;
}

function posts(): [string, RequestInit][] {
  return vi
    .mocked(global.fetch)
    .mock.calls.filter((call) => call[0]?.toString().endsWith('/deliveries'))
    .map((call) => [call[0]!.toString(), call[1] as RequestInit]);
}

function renderPage() {
  return render(
    <ActorProvider>
      <MemoryRouter>
        <UploadPage />
      </MemoryRouter>
    </ActorProvider>,
  );
}

function csv(content = 'a;b\n1;2\n', name = 'levering.csv'): File {
  return new File([content], name, { type: 'text/csv' });
}

/** Kiest taak 5 en het bestand, en wacht tot de referentie afgeleid is. */
async function fillIn(file: File = csv()) {
  await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });
  fireEvent.change(screen.getByLabelText(/^Taak/), { target: { value: '5' } });
  fireEvent.change(screen.getByLabelText(/^Bestand/), { target: { files: [file] } });
  await waitFor(() => expect((screen.getByLabelText(/^Referentie/) as HTMLInputElement).value).toContain('#'));
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: /Uploaden|Herhaal met dezelfde referentie/ }));
}

describe('UploadPage', () => {
  const originalFetch = global.fetch;

  beforeEach(() => {
    sessionStorage.setItem('catalogimport.actor', 'tester');
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.clearAllMocks();
    sessionStorage.clear();
    global.fetch = originalFetch;
  });

  it('normaal: uploadt multipart en toont 201-resultaat met tellers, "—" bij null en link naar de batch', async () => {
    mockFetch(TASKS, () => Promise.resolve(json(UPLOAD_OK, 201)));
    renderPage();
    await fillIn();

    submit();

    const result = await screen.findByTestId('upload-result');
    expect(result).toHaveTextContent('Levering aangemaakt en gescreend');
    expect(result).toHaveTextContent('SCREENED');
    expect(screen.getByRole('link', { name: '#55' })).toHaveAttribute('href', '/batches/55');
    // Dubbele identiteit is null = niet vastgesteld: "—", nooit 0.
    const label = screen.getByText('Dubbele identiteit');
    expect(label.closest('div')).toHaveTextContent('—');

    const [[url, init]] = posts();
    expect(url).toContain('/tasks/5/deliveries');
    expect(init.method).toBe('POST');
    const body = init.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect((body.get('file') as File).name).toBe('levering.csv');
    expect(body.get('uploadedBy')).toBe('tester');
    expect(body.get('deliveryReference')).toMatch(/^levering\.csv#[0-9a-f]{12}$/);
    expect(body.has('expectedRecordCount')).toBe(false);
    // Geen handmatige Content-Type: de browser zet multipart mét boundary.
    expect(new Headers(init.headers).has('Content-Type')).toBe(false);
  });

  it('normaal: geeft optionele verwachtingen door', async () => {
    mockFetch(TASKS, () => Promise.resolve(json(UPLOAD_OK, 201)));
    renderPage();
    await fillIn();
    fireEvent.change(screen.getByLabelText(/^Verwacht aantal datalijnen/), { target: { value: '7' } });
    fireEvent.change(screen.getByLabelText(/^Verwachte bestandsgrootte/), { target: { value: '421' } });

    submit();
    await screen.findByTestId('upload-result');

    const body = posts()[0]![1].body as FormData;
    expect(body.get('expectedRecordCount')).toBe('7');
    expect(body.get('expectedByteSize')).toBe('421');
  });

  it('normaal: een geblokkeerde levering toont status en blockedCode', async () => {
    mockFetch(TASKS, () =>
      Promise.resolve(json({ ...UPLOAD_OK, status: 'BLOCKED', blockedCode: 'RECORD_COUNT_MISMATCH' }, 201)),
    );
    renderPage();
    await fillIn();
    submit();
    const result = await screen.findByTestId('upload-result');
    expect(result).toHaveTextContent('BLOCKED');
    expect(result).toHaveTextContent('RECORD_COUNT_MISMATCH');
  });

  it('leeg: zonder manuele taak een uitleg (en geen taak aanmaken), niet-manuele taak uitgeschakeld', async () => {
    mockFetch(EMPTY_TASKS, () => Promise.reject(new Error('mag niet aangeroepen worden')));
    renderPage();
    expect(await screen.findByTestId('no-manual-task')).toHaveTextContent('geen manuele taak');
    cleanup();

    mockFetch({ ...TASKS, content: [TASKS.content[1]] }, () => Promise.reject(new Error('nee')));
    renderPage();
    const option = await screen.findByRole('option', { name: /LNK-2 — Nachtelijke import \(niet manueel\)/ });
    expect(option).toBeDisabled();
    expect(screen.getByTestId('no-manual-task')).toBeInTheDocument();
  });

  it('fout met code: 409 DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT krijgt een Nederlandse uitleg', async () => {
    mockFetch(TASKS, () =>
      Promise.resolve(
        json({ error: 'reused', code: 'DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT' }, 409),
      ),
    );
    renderPage();
    await fillIn();
    submit();

    expect(await screen.findByText('Referentie is al gebruikt voor een ander bestand')).toBeInTheDocument();
    expect(screen.getByText(/DELIVERY_REFERENCE_REUSED_WITH_DIFFERENT_CONTENT · HTTP 409/)).toBeInTheDocument();
    expect(screen.queryByTestId('upload-result')).not.toBeInTheDocument();
    // Een 409 is een antwoord: geen "herhaal"-herstelroute.
    expect(screen.queryByTestId('upload-recovery')).not.toBeInTheDocument();
  });

  it('fout met code: 409 TASK_NOT_MANUAL en een CONFIG_-code via de familie-fallback', async () => {
    mockFetch(TASKS, () => Promise.resolve(json({ error: 'x', code: 'TASK_NOT_MANUAL' }, 409)));
    renderPage();
    await fillIn();
    submit();
    expect(await screen.findByText('Taak is niet manueel')).toBeInTheDocument();
    cleanup();

    mockFetch(TASKS, () => Promise.resolve(json({ error: 'x', code: 'CONFIG_PRICE_FIELD_MISSING' }, 409)));
    renderPage();
    await fillIn();
    submit();
    expect(await screen.findByText('Geweigerd (CONFIG_PRICE_FIELD_MISSING)')).toBeInTheDocument();
  });

  it('fout: 413 zonder code toont "Bestand te groot"', async () => {
    mockFetch(TASKS, () => Promise.resolve(new Response('', { status: 413 })));
    renderPage();
    await fillIn();
    submit();
    expect(await screen.findByText('Bestand te groot')).toBeInTheDocument();
  });

  it('ongeldige input: niets wordt verstuurd (geen taak, geen bestand, negatief aantal, geen actor, lange referentie)', async () => {
    mockFetch(TASKS, () => Promise.resolve(json(UPLOAD_OK, 201)));
    renderPage();
    await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });

    submit();
    expect(await screen.findByText('Kies een taak.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Taak/), { target: { value: '5' } });
    submit();
    expect(await screen.findByText('Kies een bestand.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Bestand/), { target: { files: [csv()] } });
    await waitFor(() => expect((screen.getByLabelText(/^Referentie/) as HTMLInputElement).value).toContain('#'));

    fireEvent.change(screen.getByLabelText(/^Referentie/), { target: { value: '   ' } });
    submit();
    expect(await screen.findByText('Vul een referentie in.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Referentie/), { target: { value: 'x'.repeat(191) } });
    submit();
    expect(await screen.findByText(/niet langer zijn dan 190 tekens/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Referentie/), { target: { value: 'REF-1' } });
    fireEvent.change(screen.getByLabelText(/^Verwacht aantal datalijnen/), { target: { value: '-3' } });
    submit();
    expect(await screen.findByText(/Verwacht aantal datalijnen moet een geheel getal/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Verwacht aantal datalijnen/), { target: { value: '' } });
    fireEvent.change(screen.getByLabelText(/^Verwachte bestandsgrootte/), { target: { value: 'abc' } });
    submit();
    expect(await screen.findByText(/Verwachte bestandsgrootte moet een geheel getal/)).toBeInTheDocument();

    expect(posts()).toHaveLength(0);

    // Zonder actornaam ook geen upload.
    sessionStorage.clear();
    cleanup();
    renderPage();
    await fillIn();
    submit();
    expect(await screen.findByText(/Geüpload door: Vul een naam in\./)).toBeInTheDocument();
    expect(posts()).toHaveLength(0);
  });

  it('herhaalde actie: 200 meldt een bestaande levering zonder nieuwe screening', async () => {
    mockFetch(TASKS, () => Promise.resolve(json(UPLOAD_OK, 200)));
    renderPage();
    await fillIn();
    submit();
    const result = await screen.findByTestId('upload-result');
    expect(result).toHaveTextContent('Bestaande levering teruggevonden');
    expect(result).toHaveTextContent('niet opnieuw gescreend');
  });

  it('herhaalde actie: twee fasen + tijdteller zolang de upload loopt, en een dubbele klik geeft één POST', async () => {
    let resolvePost: (response: Response) => void = () => {};
    mockFetch(
      TASKS,
      () =>
        new Promise<Response>((resolve) => {
          resolvePost = resolve;
        }),
    );
    renderPage();
    await fillIn();

    submit();
    const progress = await screen.findByTestId('upload-progress');
    expect(progress).toHaveTextContent('Uploaden');
    expect(progress).toHaveTextContent('Screenen');
    expect(progress).toHaveTextContent('Laat dit tabblad open');
    expect(screen.getByRole('button', { name: 'Bezig…' })).toBeDisabled();

    const form = screen.getByRole('button', { name: 'Bezig…' }).closest('form')!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(posts()).toHaveLength(1);

    await act(async () => {
      resolvePost(json(UPLOAD_OK, 201));
    });
    expect(await screen.findByTestId('upload-result')).toBeInTheDocument();
    expect(screen.queryByTestId('upload-progress')).not.toBeInTheDocument();
    expect(posts()).toHaveLength(1);
  });

  it('herstelroute: bij een netwerkfout herhaalt de gebruiker met dezelfde referentie', async () => {
    let call = 0;
    mockFetch(TASKS, () => {
      call += 1;
      return call === 1 ? Promise.reject(new TypeError('network')) : Promise.resolve(json(UPLOAD_OK, 200));
    });
    renderPage();
    await fillIn();

    submit();
    expect(await screen.findByText('Geen verbinding met de server')).toBeInTheDocument();
    expect(screen.getByTestId('upload-recovery')).toHaveTextContent('dezelfde referentie');

    const referenceBefore = (screen.getByLabelText(/^Referentie/) as HTMLInputElement).value;
    fireEvent.click(screen.getByRole('button', { name: 'Herhaal met dezelfde referentie' }));
    expect(await screen.findByTestId('upload-result')).toHaveTextContent('Bestaande levering teruggevonden');

    const sent = posts().map(([, init]) => (init.body as FormData).get('deliveryReference'));
    expect(sent).toEqual([referenceBefore, referenceBefore]);
    expect(screen.queryByTestId('upload-recovery')).not.toBeInTheDocument();
  });
});

describe('ElapsedTimer', () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('telt seconden als mm:ss vanaf het mounten', () => {
    vi.useFakeTimers();
    render(<ElapsedTimer />);
    expect(screen.getByTestId('upload-timer')).toHaveTextContent('00:00');
    act(() => {
      vi.advanceTimersByTime(65_000);
    });
    expect(screen.getByTestId('upload-timer')).toHaveTextContent('01:05');
  });
});

describe('deriveDeliveryReference', () => {
  it('is deterministisch: zelfde naam en inhoud geven dezelfde referentie', async () => {
    expect(await deriveDeliveryReference(csv())).toBe(await deriveDeliveryReference(csv()));
  });

  it('een andere inhoud of naam geeft een andere referentie (geen tijdstempel)', async () => {
    const base = await deriveDeliveryReference(csv());
    expect(await deriveDeliveryReference(csv('a;b\n1;3\n'))).not.toBe(base);
    expect(await deriveDeliveryReference(csv('a;b\n1;2\n', 'andere.csv'))).not.toBe(base);
  });

  it('haalt de servergrens van 190 tekens, ook bij een lange bestandsnaam', async () => {
    const reference = await deriveDeliveryReference(csv('x', `${'n'.repeat(300)}.csv`));
    expect(reference.length).toBe(190);
    expect(reference).toMatch(/#[0-9a-f]{12}$/);
  });
});
