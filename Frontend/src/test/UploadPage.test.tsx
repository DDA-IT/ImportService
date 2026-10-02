/**
 * B-F1 — `UploadPage` (`/upload`), uploadscherm (scherm 2), zie `docs/decisions.md` 2026-09-23 stap 9.
 *
 * Dekt: normaal (201 + tellers, "—" bij null, multipart zonder handmatige Content-Type), leeg (geen
 * manuele taak), fout met foutcode (409, 413 zonder code), ongeldige input (niets verstuurd), herhaalde
 * actie (200 = bestaande levering; dubbele klik = één POST; herstelroute na netwerkfout met dezelfde
 * referentie), twee fasen + tijdteller, en de deterministische referentie.
 *
 * Sinds `docs/decisions.md` 2026-09-27 ("tweede ontvangstweg"): ook de servermap-bron (bestandslijst
 * laden/tonen, bestand kiezen en versturen naar `.../deliveries/local-source`, server-afgeleide
 * `deliveryReference`, `LOCAL_SOURCE_NOT_CONFIGURED`). De bestandsinput-selector is verscherpt naar
 * `/^Bestand \(CSV\)/` omdat de nieuwe bronkeuze-radioknoppen ook met "Bestand" beginnen.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { TEST_IDENTITY, testIdentityWith } from './testIdentity';
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
  deliveryReference: 'levering.csv#abc123abc123',
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

const LOCAL_SOURCE_FILES = {
  files: [
    { fileName: 'ABP4-2026.csv', byteSize: 734003200, lastModifiedAt: '2026-09-27T08:12:44Z' },
    { fileName: 'oud.csv', byteSize: 512, lastModifiedAt: '2026-01-01T00:00:00Z' },
  ],
  truncated: false,
};

function mockFetch(
  tasks: unknown,
  post: Handler,
  options: { localSourceFiles?: Handler; localSourcePost?: Handler } = {},
) {
  global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    if (url.endsWith('/deliveries/local-source')) {
      return (options.localSourcePost ?? (() => Promise.reject(new Error('geen local-source POST-handler'))))(init);
    }
    if (url.endsWith('/deliveries')) {
      return post(init);
    }
    if (url.endsWith('/local-source/files')) {
      return (options.localSourceFiles ?? (() => Promise.resolve(json(LOCAL_SOURCE_FILES))))(init);
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

function localSourcePosts(): [string, RequestInit][] {
  return vi
    .mocked(global.fetch)
    .mock.calls.filter((call) => call[0]?.toString().endsWith('/deliveries/local-source'))
    .map((call) => [call[0]!.toString(), call[1] as RequestInit]);
}

function renderPage(identity: ActorIdentity = { ...TEST_IDENTITY, username: 'tester' }) {
  return render(
    <ActorProvider identity={identity}>
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
  fireEvent.change(screen.getByLabelText(/^Bestand \(CSV\)/), { target: { files: [file] } });
  await waitFor(() => expect((screen.getByLabelText(/^Referentie/) as HTMLInputElement).value).toContain('#'));
}

function submit() {
  fireEvent.click(screen.getByRole('button', { name: /Uploaden|Herhaal met dezelfde referentie/ }));
}

describe('UploadPage', () => {
  const originalFetch = global.fetch;

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
    expect(result).toHaveTextContent('Levering aangemaakt en gecontroleerd');
    // NT-11a (V7): het Nederlandse woord voor de status; de technische code enkel in de tooltip.
    expect(result).toHaveTextContent('Gecontroleerd');
    expect(result).not.toHaveTextContent('SCREENED');
    expect(screen.getByRole('link', { name: '#55' })).toHaveAttribute('href', '/batches/55');
    // Dubbele artikelen is null = niet vastgesteld: "—", nooit 0.
    const label = screen.getByText('Dubbele artikelen');
    expect(label.closest('div')).toHaveTextContent('—');

    const [[url, init]] = posts() as [[string, RequestInit]];
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
    expect(result).toHaveTextContent('Tegengehouden');
    expect(result).toHaveTextContent('Ander aantal regels dan verwacht');
    expect(result).not.toHaveTextContent('RECORD_COUNT_MISMATCH');
    expect(screen.getByText('Ander aantal regels dan verwacht')).toHaveAttribute(
      'title',
      expect.stringContaining('RECORD_COUNT_MISMATCH'),
    );
  });

  it('leeg: zonder manuele taak een uitleg (en geen taak aanmaken), niet-manuele taak uitgeschakeld', async () => {
    mockFetch(EMPTY_TASKS, () => Promise.reject(new Error('mag niet aangeroepen worden')));
    renderPage();
    expect(await screen.findByTestId('no-manual-task')).toHaveTextContent('Er is nog geen taak');
    expect(screen.getByTestId('no-manual-task')).toHaveTextContent('Inrichting');
    cleanup();

    mockFetch({ ...TASKS, content: [TASKS.content[1]] }, () => Promise.reject(new Error('nee')));
    renderPage();
    const option = await screen.findByRole('option', { name: /LNK-2 — Nachtelijke import \(niet manueel\)/ });
    expect(option).toBeDisabled();
    expect(screen.getByTestId('no-manual-task')).toBeInTheDocument();
  });

  it('NT-6: een taak zonder actieve versie blijft zichtbaar maar uitgeschakeld, met uitleg en link naar Inrichting', async () => {
    const notReady = {
      ...TASKS.content[0],
      id: 7,
      name: 'Nieuwe taak',
      importLinkCode: 'LNK-3',
      importDefinitionId: 30,
      activeRevisionId: null,
    };
    const ready = { ...TASKS.content[0], importDefinitionId: 10, activeRevisionId: 100 };
    mockFetch({ ...TASKS, content: [ready, notReady], totalElements: 2 }, () =>
      Promise.reject(new Error('mag niet aangeroepen worden')),
    );
    renderPage();

    const option = await screen.findByRole('option', {
      name: 'LNK-3 — Nieuwe taak (nog niet klaar: versie niet geactiveerd)',
    });
    expect(option).toBeDisabled();
    // Een klare taak blijft gewoon kiesbaar, zonder die vermelding.
    const readyOption = screen.getByRole('option', { name: 'LNK-1 — Handmatige levering' });
    expect(readyOption).toBeEnabled();

    const note = screen.getByTestId('not-ready-tasks');
    expect(note).toHaveTextContent('nog niet geactiveerd');
    expect(within(note).getByRole('link', { name: 'Inrichting' })).toHaveAttribute('href', '/setup');
    // NT-10: per koppeling een link naar het scherm "Controleren"; enkel voor de koppeling van de niet-klare taak.
    expect(within(note).getByRole('link', { name: 'Controleren (LNK-3)' })).toHaveAttribute(
      'href',
      '/setup/links/1/check?definitionId=30',
    );
    expect(within(note).getAllByRole('link', { name: /^Controleren/ })).toHaveLength(1);
    // Er is wel een manuele taak: de "geen taak"-melding verschijnt niet.
    expect(screen.queryByTestId('no-manual-task')).not.toBeInTheDocument();
  });

  it('NT-6: zonder activeRevisionId-veld (oudere server) wordt een taak niet als "niet klaar" getoond', async () => {
    mockFetch(TASKS, () => Promise.reject(new Error('mag niet aangeroepen worden')));
    renderPage();
    expect(await screen.findByRole('option', { name: 'LNK-1 — Handmatige levering' })).toBeEnabled();
    expect(screen.queryByTestId('not-ready-tasks')).not.toBeInTheDocument();
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
    expect(await screen.findByText('De beschrijving van het bestand klopt niet')).toBeInTheDocument();
    // De code is niet de titel; ze blijft opvraagbaar onder "Technische details (voor support)".
    expect(screen.getByText(/CONFIG_PRICE_FIELD_MISSING · HTTP 409/)).toBeInTheDocument();
  });

  it('fout: 413 zonder code toont "Bestand te groot"', async () => {
    mockFetch(TASKS, () => Promise.resolve(new Response('', { status: 413 })));
    renderPage();
    await fillIn();
    submit();
    expect(await screen.findByText('Bestand te groot')).toBeInTheDocument();
  });

  it('ongeldige input: niets wordt verstuurd (geen taak, geen bestand, negatief aantal, lange referentie)', async () => {
    mockFetch(TASKS, () => Promise.resolve(json(UPLOAD_OK, 201)));
    renderPage();
    await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });

    submit();
    expect(await screen.findByText('Kies een taak.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Taak/), { target: { value: '5' } });
    submit();
    expect(await screen.findByText('Kies een bestand.')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText(/^Bestand \(CSV\)/), { target: { files: [csv()] } });
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
  });

  it('herhaalde actie: 200 meldt een bestaande levering zonder nieuwe controle', async () => {
    mockFetch(TASKS, () => Promise.resolve(json(UPLOAD_OK, 200)));
    renderPage();
    await fillIn();
    submit();
    const result = await screen.findByTestId('upload-result');
    expect(result).toHaveTextContent('Bestaande levering teruggevonden');
    expect(result).toHaveTextContent('niet opnieuw gecontroleerd');
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
    expect(progress).toHaveTextContent('Controleren');
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

  it('zonder MANAGE: uploadknop uitgeschakeld mét reden, niets verstuurd', async () => {
    mockFetch(TASKS, () => Promise.resolve(json(UPLOAD_OK, 201)));
    renderPage(testIdentityWith('catalogImport.read'));
    await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });

    const button = screen.getByRole('button', { name: 'Uploaden' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('title', "U heeft het recht 'Beheren' (catalogImport.manage) niet.");
    expect(screen.getByTestId('permission-reason-manage')).toHaveTextContent(
      "U heeft het recht 'Beheren' (catalogImport.manage) niet.",
    );
    expect(button).toHaveAttribute('aria-describedby', 'upload-permission-reason');
    expect(posts()).toHaveLength(0);
  });

  it('met MANAGE: uploadknop niet uitgeschakeld en geen reden', async () => {
    mockFetch(TASKS, () => Promise.resolve(json(UPLOAD_OK, 201)));
    renderPage(testIdentityWith('catalogImport.read', 'catalogImport.manage'));
    await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });

    expect(screen.getByRole('button', { name: 'Uploaden' })).toBeEnabled();
    expect(screen.queryByTestId('permission-reason-manage')).not.toBeInTheDocument();
  });

  describe('servermap-bron (tweede ontvangstweg, docs/decisions.md 2026-09-27)', () => {
    it('laadt en toont de lijst, en verstuurt een JSON-POST naar .../deliveries/local-source', async () => {
      const localSourcePost = vi.fn((_init?: RequestInit) =>
        Promise.resolve(
          json({ ...UPLOAD_OK, deliveryReference: 'ABP4-2026.csv#deadbeef0001' }, 201),
        ),
      );
      mockFetch(TASKS, () => Promise.reject(new Error('mag niet aangeroepen worden')), { localSourcePost });
      renderPage();
      await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });
      fireEvent.change(screen.getByLabelText(/^Taak/), { target: { value: '5' } });

      fireEvent.click(screen.getByLabelText('Bestand op de server'));
      await screen.findByRole('option', { name: /ABP4-2026\.csv/ });
      expect(screen.getByRole('option', { name: /oud\.csv/ })).toBeInTheDocument();

      fireEvent.change(screen.getByLabelText(/^Kies een bestand uit de servermap/), {
        target: { value: 'ABP4-2026.csv' },
      });
      submit();

      const result = await screen.findByTestId('upload-result');
      expect(result).toHaveTextContent('Levering aangemaakt en gecontroleerd');
      expect(screen.getByTestId('upload-result-reference')).toHaveTextContent('ABP4-2026.csv#deadbeef0001');

      const [[url, init]] = localSourcePosts() as [[string, RequestInit]];
      expect(url).toContain('/tasks/5/deliveries/local-source');
      expect(init.method).toBe('POST');
      expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
      const body = JSON.parse(init.body as string);
      expect(body).toEqual({
        fileName: 'ABP4-2026.csv',
        deliveryReference: null,
        uploadedBy: 'tester',
        expectedRecordCount: null,
        expectedByteSize: null,
      });
      expect(localSourcePost).toHaveBeenCalledTimes(1);
    });

    it('vereist een gekozen bestand uit de lijst (geen vrije tekstinvoer)', async () => {
      mockFetch(TASKS, () => Promise.reject(new Error('mag niet aangeroepen worden')));
      renderPage();
      await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });
      fireEvent.change(screen.getByLabelText(/^Taak/), { target: { value: '5' } });
      fireEvent.click(screen.getByLabelText('Bestand op de server'));
      await screen.findByRole('option', { name: /ABP4-2026\.csv/ });

      submit();
      expect(await screen.findByText('Kies een bestand uit de servermap.')).toBeInTheDocument();
      expect(localSourcePosts()).toHaveLength(0);
    });

    it('lege lijst: duidelijke melding, geen crash', async () => {
      mockFetch(TASKS, () => Promise.reject(new Error('mag niet aangeroepen worden')), {
        localSourceFiles: () => Promise.resolve(json({ files: [], truncated: false })),
      });
      renderPage();
      await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });
      fireEvent.click(screen.getByLabelText('Bestand op de server'));

      expect(await screen.findByTestId('no-local-source-files')).toHaveTextContent(
        'Geen bestanden gevonden in de servermap',
      );
    });

    it('404 LOCAL_SOURCE_NOT_CONFIGURED: duidelijke melding en de bronkeuze "server" wordt uitgeschakeld', async () => {
      mockFetch(TASKS, () => Promise.reject(new Error('mag niet aangeroepen worden')), {
        localSourceFiles: () =>
          Promise.resolve(json({ error: 'not configured', code: 'LOCAL_SOURCE_NOT_CONFIGURED' }, 404)),
      });
      renderPage();
      await screen.findByRole('option', { name: /LNK-1 — Handmatige levering/ });
      fireEvent.click(screen.getByLabelText('Bestand op de server'));

      expect(await screen.findByText('Ontvangstweg niet ingesteld')).toBeInTheDocument();
      expect(screen.getByText(/Werk gewoon via "Bestand van mijn computer"/)).toBeInTheDocument();
      await waitFor(() => expect(screen.getByLabelText('Bestand op de server')).toBeDisabled());
    });
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
