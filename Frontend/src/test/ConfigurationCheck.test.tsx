/**
 * E5a — de controle van de instellingen zonder bestand in stap ② van de conceptbalk (beslissingslog 2026-10-23 "E5a"):
 * `GET /definitions/{d}/revisions/{r}/configuration-check` wordt automatisch opgehaald bij een concept en na elke wijziging;
 * de stap toont "Instellingen: in orde" of het aantal blokkerende problemen met een zin en een sprong naar de rij of sectie;
 * [Activeren] is uitgeschakeld met een zichtbare reden zolang het antwoord `activatable=false` is; een mislukte oproep
 * blokkeert niets. Een niet-concept haalt de controle niet op.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import {
  activatableCheck,
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
import { visibleTextOf } from './oldWording';

vi.mock('../features/setup/check/TrialReadResultView', () => ({
  TrialReadResultView: ({ result }: { result: { verdict: { result: string } } }) => (
    <div data-testid="trial-result">{result.verdict.result}</div>
  ),
}));

const originalFetch = global.fetch;
const originalScroll = Element.prototype.scrollIntoView;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  global.fetch = originalFetch;
  Element.prototype.scrollIntoView = originalScroll;
  vi.restoreAllMocks();
});

function problem(code: string, over: Record<string, unknown> = {}) {
  return {
    code,
    status: 'PROBLEM',
    subject: { type: 'REVISION', id: 100 },
    detail: 'Engelse servertekst',
    fieldName: null,
    revisionField: null,
    ...over,
  };
}

function failing(checks: unknown[]) {
  return { revisionId: 100, revisionStatus: 'DRAFT', activatable: false, checks };
}

function state(): SetupState {
  const s = emptySetupState();
  s.organisations = [organisation(1, { code: 'VROOAM', name: 'Vrooam BV' })];
  s.definitions = [
    definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: 99, draftRevisionId: 100, draftRevisionNumber: 4 }),
  ];
  s.links = [link(501, 10, { name: 'VROOAM' })];
  s.tasks = [task(7, 501, 10, { activeRevisionId: 99 })];
  s.revisions[10] = [
    { id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT', basedOnRevisionId: null },
    { id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' },
  ];
  s.details[99] = revisionDetail(99, 10, { revisionNumber: 3, status: 'ACTIVE' });
  s.details[100] = revisionDetail(100, 10, { revisionNumber: 4, status: 'DRAFT', structureDelimiter: ',' });
  return s;
}

type Server = { checkUrls: string[]; setBody: (body: unknown) => void; failNext: () => void };

/** Een server waarvan het antwoord van de controle tijdens de test te wisselen is; elke oproep wordt geteld. */
function serve(s: SetupState, initial: unknown): Server {
  let body = initial;
  let failed = false;
  const checkUrls: string[] = [];
  installSetupServer(s, (url, init) => {
    const method = (init?.method ?? 'GET').toUpperCase();
    if (/\/configuration-check$/.test(url)) {
      checkUrls.push(url);
      return failed ? jsonResponse({ error: 'kapot', code: 'INTERNAL_ERROR' }, 500) : jsonResponse(body);
    }
    if (method === 'PATCH' && /\/setup\/revisions\/100$/.test(url)) {
      return jsonResponse({ id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT' });
    }
    return null;
  });
  return {
    checkUrls,
    setBody: (next) => {
      body = next;
    },
    failNext: () => {
      failed = true;
    },
  };
}

const DRAFT_PATH = '/setup/suppliers/1/fields?definitie=10&versie=100';

async function open(server: Server, path = DRAFT_PATH) {
  renderRoutes(path);
  await screen.findByTestId('concept-bar');
  return server;
}

describe('controle van de instellingen in stap ②', () => {
  it('toont "in orde" en laat Activeren open als er geen probleem is', async () => {
    const server = await open(serve(state(), activatableCheck(100)));
    expect(await screen.findByTestId('config-check-summary')).toHaveTextContent('Instellingen: in orde');
    expect(server.checkUrls).toEqual([expect.stringMatching(/\/definitions\/10\/revisions\/100\/configuration-check$/)]);
    expect(screen.getByTestId('concept-activate')).toBeEnabled();
    expect(screen.queryByTestId('concept-activate-blocked')).not.toBeInTheDocument();
    expect(screen.queryByTestId('config-check-problems')).not.toBeInTheDocument();
  });

  it('toont het aantal problemen met per probleem een zin en een sprong naar de rij of de sectie', async () => {
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    await open(
      serve(
        state(),
        failing([
          problem('CONFIG_PRICE_FIELD_MISSING', { revisionField: 'basePriceField' }),
          problem('CONFIG_DELIMITER_MISSING', { revisionField: 'delimiter' }),
        ]),
      ),
    );
    expect(await screen.findByTestId('config-check-summary')).toHaveTextContent('2 problemen blokkeren het activeren');
    const rows = screen.getAllByTestId('config-check-problem');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('De prijskolom ontbreekt');
    expect(rows[0]).toHaveTextContent('Kolom basisprijs');

    // Geen ruwe code als zichtbare tekst; de code staat onder "Technische details".
    expect(visibleTextOf(screen.getByTestId('config-check'))).not.toMatch(/CONFIG_/);
    expect(within(rows[0]!).getByText('CONFIG_PRICE_FIELD_MISSING')).toBeInTheDocument();

    // De sprong naar de rij Basisprijs van het grid.
    expect(within(rows[0]!).getByTestId('config-check-jump')).toHaveTextContent('Naar de rij');
    fireEvent.click(within(rows[0]!).getByTestId('config-check-jump'));
    expect(screen.getByTestId('grid-row-basePrice')).toHaveAttribute('data-check-flash', 'true');
    expect(scroll).toHaveBeenCalledTimes(1);

    // Een veld van de bestandsopbouw: de sprong gaat naar die sectie.
    expect(within(rows[1]!).getByTestId('config-check-jump')).toHaveTextContent('Naar de sectie');
    fireEvent.click(within(rows[1]!).getByTestId('config-check-jump'));
    expect(screen.getByTestId('read-file')).toHaveAttribute('data-check-flash', 'true');
    expect(scroll).toHaveBeenCalledTimes(2);
  });

  it('een probleem op een extra veld springt naar de rij van dat veld; zonder aanwijzing is er geen sprong', async () => {
    Element.prototype.scrollIntoView = vi.fn();
    const s = state();
    s.details[100] = revisionDetail(100, 10, {
      revisionNumber: 4,
      status: 'DRAFT',
      mappings: [
        {
          id: 5001,
          revisionId: 100,
          sequenceNumber: 1,
          targetFieldCode: 'BRAND',
          sourceReference: '7',
          valueKind: 'SOURCE_FIELD',
          criticality: 'NON_CRITICAL',
          required: false,
          transformKind: 'NONE',
          valueMap: [],
          steps: [],
        },
      ],
    });
    await open(serve(s, failing([problem('CONFIG_MAPPING_TARGET_UNKNOWN', { fieldName: 'BRAND' }), problem('CONFIG_INVALID')])));
    const rows = await screen.findAllByTestId('config-check-problem');
    fireEvent.click(within(rows[0]!).getByTestId('config-check-jump'));
    await waitFor(() => expect(document.getElementById('grid-field-BRAND')).toHaveAttribute('data-check-flash', 'true'));
    expect(within(rows[1]!).queryByTestId('config-check-jump')).not.toBeInTheDocument();
  });

  it('toont INFO_CONFIG_CHECKS_SKIPPED als korte noot, niet als probleem', async () => {
    await open(
      serve(
        state(),
        failing([
          problem('CONFIG_DELIMITER_MISSING', { revisionField: 'delimiter' }),
          {
            code: 'INFO_CONFIG_CHECKS_SKIPPED',
            status: 'INFO',
            subject: { type: 'REVISION', id: 100 },
            detail: 'Checks depending on [CONFIG_DELIMITER_MISSING] were not evaluated',
            skippedBecause: ['CONFIG_DELIMITER_MISSING'],
          },
        ]),
      ),
    );
    expect(await screen.findByTestId('config-check-summary')).toHaveTextContent('1 probleem blokkeert het activeren');
    expect(screen.getAllByTestId('config-check-problem')).toHaveLength(1);
    expect(screen.getByTestId('config-check-skipped')).toHaveTextContent('Niet alle controles konden al uitgevoerd worden');
  });

  it('schakelt Activeren uit met een zichtbare reden zolang activatable=false is', async () => {
    await open(serve(state(), failing([problem('CONFIG_PRICE_FIELD_MISSING', { revisionField: 'basePriceField' })])));
    await screen.findByTestId('config-check-summary');
    const activate = screen.getByTestId('concept-activate');
    expect(activate).toBeDisabled();
    expect(screen.getByTestId('concept-activate-blocked')).toHaveTextContent(
      'Activeren kan pas als het probleem in de instellingen opgelost is.',
    );
    fireEvent.click(activate);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('bij een mislukte oproep blijft Activeren mogelijk en staat er een rustige melding', async () => {
    const server = serve(state(), activatableCheck(100));
    server.failNext();
    await open(server);
    expect(await screen.findByTestId('config-check-failed')).toHaveTextContent('kon niet uitgevoerd worden');
    expect(screen.queryByTestId('config-check-summary')).not.toBeInTheDocument();
    expect(screen.getByTestId('concept-activate')).toBeEnabled();
    expect(screen.queryByTestId('concept-activate-blocked')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('concept-activate'));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
  });

  it('haalt de controle opnieuw op na een wijziging', async () => {
    const server = await open(
      serve(state(), failing([problem('CONFIG_DELIMITER_MISSING', { revisionField: 'delimiter' })])),
      `${DRAFT_PATH}&bewerken=1`,
    );
    expect(await screen.findByTestId('config-check-summary')).toHaveTextContent('1 probleem blokkeert het activeren');
    expect(server.checkUrls).toHaveLength(1);

    server.setBody(activatableCheck(100));
    fireEvent.click(await screen.findByTestId('file-change'));
    const form = await screen.findByTestId('file-edit-form');
    fireEvent.change(within(form).getByLabelText('Scheidingsteken'), { target: { value: '|' } });
    fireEvent.click(within(form).getByTestId('inline-save'));

    await waitFor(() => expect(screen.getByTestId('config-check-summary')).toHaveTextContent('Instellingen: in orde'));
    expect(server.checkUrls).toHaveLength(2);
    expect(screen.getByTestId('concept-activate')).toBeEnabled();
  });

  it('haalt de controle niet op bij een versie die geen concept is', async () => {
    const server = serve(state(), activatableCheck(100));
    renderRoutes('/setup/suppliers/1/fields?definitie=10&versie=99');
    await screen.findByTestId('read-filters');
    expect(screen.queryByTestId('concept-bar')).not.toBeInTheDocument();
    expect(screen.queryByTestId('config-check')).not.toBeInTheDocument();
    expect(server.checkUrls).toHaveLength(0);
  });
});
