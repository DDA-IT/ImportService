/**
 * E4 — de conceptbalk van "Bestand en velden" (beslissingslog 2026-10-23 "Nieuwe bewerkervaring", O4/O6): alleen op een
 * concept, de teller en "Toon wijzigingen" (client-side verschil met de basisversie), de reden (PATCH changeReason),
 * Controleren met een testbestand, Activeren (met de R-CASE-03-waarschuwing), de impactbanner voor een gedeelde definitie,
 * de chips Nieuw/Gewijzigd en het inline bewerken van Bestandsopbouw en Drempels.
 *
 * Het resultaatscherm van de proefinlezing (`TrialReadResultView`) heeft zijn eigen tests; hier is het vervangen door een
 * stub die enkel het oordeel toont.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import {
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

vi.mock('../features/setup/check/TrialReadResultView', () => ({
  TrialReadResultView: ({ result }: { result: { verdict: { result: string } } }) => (
    <div data-testid="trial-result">{result.verdict.result}</div>
  ),
}));

const originalFetch = global.fetch;

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  global.fetch = originalFetch;
  vi.restoreAllMocks();
});

function mapping(id: number, code: string, over: Record<string, unknown> = {}) {
  return {
    id,
    revisionId: 100,
    sequenceNumber: id,
    targetFieldCode: code,
    sourceReference: '7',
    valueKind: 'SOURCE_FIELD',
    criticality: 'NON_CRITICAL',
    expectedPosition: null,
    fixedValue: null,
    defaultValue: null,
    required: false,
    maxLength: null,
    decimalScale: null,
    zeroAllowed: false,
    negativeAllowed: false,
    transformKind: 'NONE',
    transformConfig: null,
    valueMap: [],
    steps: [],
    ...over,
  };
}

function filter(id: number, over: Record<string, unknown> = {}) {
  return {
    id,
    revisionId: 100,
    sequenceNumber: id,
    sourceReference: '3',
    operator: 'EQUALS',
    compareValue: 'BENL',
    outcome: 'EXCLUDE',
    matchGroup: null,
    caseSensitive: false,
    trimBeforeCompare: true,
    nullBehaviour: 'EXCLUDE',
    missingColumnBehaviour: 'BLOCK',
    ...over,
  };
}

type Options = { links?: number; draftDetail?: Record<string, unknown>; baseDetail?: Record<string, unknown> };

/**
 * Versie 3 (actief) is de basis van concept versie 4. Verschillen: BRAND nieuw, EAN weg, een extra filter, een ander
 * scheidingsteken en een andere drempel voor verworpen regels = 5 verschillen.
 */
function state(options: Options = {}): SetupState {
  const s = emptySetupState();
  s.organisations = [organisation(1, { code: 'VROOAM', name: 'Vrooam BV' })];
  s.definitions = [
    definition(10, 1, { name: 'Vrooam artikelbestand', activeRevisionId: 99, draftRevisionId: 100, draftRevisionNumber: 4 }),
  ];
  const linkCount = options.links ?? 2;
  s.links = Array.from({ length: linkCount }, (_, index) =>
    link(501 + index, 10, { name: index === 0 ? 'VROOAM' : `VROOAM${index + 1}` }),
  );
  s.tasks = [task(7, 501, 10, { activeRevisionId: 99 })];
  s.revisions[10] = [
    { id: 100, definitionId: 10, revisionNumber: 4, status: 'DRAFT', basedOnRevisionId: 99 },
    { id: 99, definitionId: 10, revisionNumber: 3, status: 'ACTIVE' },
  ];
  s.details[99] = revisionDetail(99, 10, {
    revisionNumber: 3,
    status: 'ACTIVE',
    structureFieldReferenceKind: 'COLUMN_INDEX',
    structureDelimiter: ';',
    maxRejectedSharePercent: 2,
    mappings: [mapping(4001, 'EAN', { revisionId: 99, sourceReference: '5' })],
    filters: [filter(6001, { revisionId: 99 })],
    ...options.baseDetail,
  });
  s.details[100] = revisionDetail(100, 10, {
    revisionNumber: 4,
    status: 'DRAFT',
    basedOnRevisionId: 99,
    changeReason: 'Nieuwe prijskolom',
    structureFieldReferenceKind: 'COLUMN_INDEX',
    structureDelimiter: ',',
    maxRejectedSharePercent: 5,
    identityUppercase: false,
    mappings: [mapping(5001, 'BRAND')],
    filters: [filter(7001), filter(7002, { sourceReference: '40', operator: 'CONTAINS', compareValue: 'X' })],
    ...options.draftDetail,
  });
  return s;
}

type Call = { method: string; url: string; body: Record<string, unknown> | null; form: FormData | null };

function serve(s: SetupState): Call[] {
  const calls: Call[] = [];
  installSetupServer(s, (url, init) => {
    const method = (init?.method ?? 'GET').toUpperCase();
    const isWrite =
      method !== 'GET' && (/\/setup\/revisions\/100(\/activate)?$/.test(url) || /\/revisions\/100\/trial-reads$/.test(url));
    if (!isWrite) {
      return null;
    }
    const raw = init?.body;
    calls.push({
      method,
      url,
      body: typeof raw === 'string' ? (JSON.parse(raw) as Record<string, unknown>) : null,
      form: raw instanceof FormData ? raw : null,
    });
    if (url.endsWith('/trial-reads')) {
      return jsonResponse({ verdict: { result: 'NO_BLOCKER_FOUND' } });
    }
    return jsonResponse({
      id: 100,
      definitionId: 10,
      revisionNumber: 4,
      status: url.endsWith('/activate') ? 'ACTIVE' : 'DRAFT',
    });
  });
  return calls;
}

const DRAFT_PATH = '/setup/suppliers/1/fields?definitie=10&versie=100';

async function open(s: SetupState, path = `${DRAFT_PATH}&bewerken=1`): Promise<Call[]> {
  const calls = serve(s);
  renderRoutes(path);
  await screen.findByTestId('read-filters');
  return calls;
}

/** Enkel de velden die werkelijk meegestuurd zijn (niet `null`), zonder de actor. */
function sentFields(call: Call): Array<[string, unknown]> {
  return Object.entries(call.body ?? {}).filter(([name, value]) => value !== null && name !== 'createdBy');
}

describe('conceptbalk', () => {
  it('staat alleen op een concept', async () => {
    await open(state(), '/setup/suppliers/1/fields?definitie=10&versie=99');
    expect(screen.queryByTestId('concept-bar')).not.toBeInTheDocument();
    expect(screen.queryByTestId('shared-definition-banner')).not.toBeInTheDocument();
    cleanup();
    await open(state(), DRAFT_PATH);
    expect(await screen.findByTestId('concept-bar')).toBeInTheDocument();
  });

  it('toont versie, basisversie en het aantal wijzigingen', async () => {
    await open(state());
    await waitFor(() =>
      expect(screen.getByTestId('concept-title')).toHaveTextContent(
        'Concept · versie 4 · gebaseerd op versie 3 · 5 wijzigingen',
      ),
    );
    expect(screen.getByTestId('concept-bar')).toHaveTextContent(
      'Elke wijziging wordt meteen in het concept bewaard. Het concept is nog niet actief.',
    );
    // Stappen: Aanpassen is de huidige stap.
    expect(screen.getByTestId('concept-step-1')).toHaveAttribute('aria-current', 'step');
  });

  it('Toon wijzigingen klapt de lijst uit met was en nu', async () => {
    await open(state());
    await waitFor(() => expect(screen.getByTestId('concept-title')).toHaveTextContent('5 wijzigingen'));
    expect(screen.queryByTestId('diff-box')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('diff-toggle'));
    const box = await screen.findByTestId('diff-box');
    expect(screen.getByTestId('diff-toggle')).toHaveAttribute('aria-expanded', 'true');
    const entries = within(box).getAllByTestId('diff-entry');
    expect(entries).toHaveLength(5);
    const delimiter = entries.find((entry) => entry.textContent?.includes('Scheidingsteken'))!;
    expect(delimiter).toHaveTextContent('was: ; → nu: ,');
    expect(delimiter).toHaveTextContent('Gewijzigd');
    expect(entries.some((entry) => entry.textContent?.includes('Niet meer in deze versie'))).toBe(true);
    expect(entries.some((entry) => entry.textContent?.includes('Nieuw'))).toBe(true);
  });

  it('een concept zonder verschil toont "Geen wijzigingen"', async () => {
    const s = state();
    s.details[100] = {
      ...(s.details[99] as object),
      id: 100,
      revisionNumber: 4,
      status: 'DRAFT',
      basedOnRevisionId: 99,
      changeReason: null,
    } as never;
    await open(s);
    await waitFor(() => expect(screen.getByTestId('concept-title')).toHaveTextContent('0 wijzigingen'));
    fireEvent.click(screen.getByTestId('diff-toggle'));
    expect(await screen.findByTestId('diff-box')).toHaveTextContent('Geen wijzigingen: dit concept is gelijk aan versie 3.');
  });

  it('chips Nieuw en Gewijzigd op rijen en zinnen, en de strook "Niet meer in deze versie"', async () => {
    await open(state());
    await waitFor(() => expect(screen.getByTestId('diff-chip-BRAND')).toHaveTextContent('Nieuw'));
    expect(screen.getByTestId('removed-fields')).toHaveTextContent('Niet meer in deze versie: ');
    // Het filter op kolom 40 is nieuw; het filter op kolom 3 is ongewijzigd.
    const chips = screen.getAllByTestId('filter-diff-chip');
    expect(chips).toHaveLength(1);
    expect(chips[0]).toHaveTextContent('Nieuw');
    expect(screen.queryByTestId('removed-filters')).not.toBeInTheDocument();
    expect(screen.getByTestId('diff-chip-delimiter')).toHaveTextContent('Gewijzigd');
  });

  it('een filter dat het concept niet meer heeft komt in de strook voor filters', async () => {
    await open(state({ draftDetail: { filters: [] } }));
    expect(await screen.findByTestId('removed-filters')).toHaveTextContent(
      'Niet meer in deze versie: Sla regels over waar kolom 3 is gelijk aan "BENL"',
    );
  });
});

describe('gedeelde definitie', () => {
  it('toont de impactbanner met alle koppelingen', async () => {
    await open(state({ links: 3 }));
    expect(await screen.findByTestId('shared-definition-banner')).toHaveTextContent(
      'Deze importdefinitie wordt gedeeld door 3 koppelingen (VROOAM, VROOAM2, VROOAM3). Activeren van een nieuwe versie geldt voor alle 3.',
    );
  });

  it('bij één koppeling geen banner', async () => {
    await open(state({ links: 1 }));
    await screen.findByTestId('concept-bar');
    await waitFor(() => expect(screen.getByTestId('concept-title')).toHaveTextContent('wijzigingen'));
    expect(screen.queryByTestId('shared-definition-banner')).not.toBeInTheDocument();
  });
});

describe('reden van deze versie', () => {
  it('in Bekijken geen potlood', async () => {
    await open(state(), DRAFT_PATH);
    expect(await screen.findByTestId('concept-reason-text')).toHaveTextContent('Nieuwe prijskolom');
    expect(screen.queryByTestId('concept-reason-edit')).not.toBeInTheDocument();
  });

  it('in Bewerken bewaart een PATCH enkel changeReason', async () => {
    const calls = await open(state());
    fireEvent.click(await screen.findByTestId('concept-reason-edit'));
    fireEvent.change(screen.getByTestId('concept-reason-input'), { target: { value: 'Andere prijskolom' } });
    fireEvent.click(screen.getByTestId('concept-reason-save'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.method).toBe('PATCH');
    expect(calls[0]!.url).toMatch(/\/setup\/revisions\/100$/);
    expect(sentFields(calls[0]!)).toEqual([['changeReason', 'Andere prijskolom']]);
  });

  it('Annuleren stuurt niets', async () => {
    const calls = await open(state());
    fireEvent.click(await screen.findByTestId('concept-reason-edit'));
    fireEvent.change(screen.getByTestId('concept-reason-input'), { target: { value: 'x' } });
    fireEvent.click(screen.getByTestId('concept-reason-cancel'));
    expect(calls).toHaveLength(0);
    expect(screen.getByTestId('concept-reason-text')).toHaveTextContent('Nieuwe prijskolom');
  });
});

describe('Controleren en Activeren', () => {
  it('een testbestand zonder blokkade vinkt stap 2 af; bij één koppeling gaat de koppeling mee', async () => {
    const calls = await open(state({ links: 1 }));
    await waitFor(() => expect(screen.getByTestId('concept-title')).toHaveTextContent('wijzigingen'));
    fireEvent.click(screen.getByTestId('concept-trial'));
    const box = await screen.findByTestId('concept-trial-box');

    // Zonder bestand: een melding en geen verzoek.
    fireEvent.click(within(box).getByTestId('concept-trial-start'));
    expect(await screen.findByTestId('concept-trial-validation')).toHaveTextContent('Kies eerst een bestand');
    expect(calls).toHaveLength(0);

    const file = new File(['a;b\n1;2\n'], 'test.csv', { type: 'text/csv' });
    fireEvent.change(within(box).getByTestId('concept-trial-file'), { target: { files: [file] } });
    fireEvent.click(within(box).getByTestId('concept-trial-start'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.url).toMatch(/\/revisions\/100\/trial-reads$/);
    expect((calls[0]!.form!.get('file') as File).name).toBe('test.csv');
    expect(calls[0]!.form!.get('linkId')).toBe('501');
    expect(await screen.findByTestId('trial-result')).toHaveTextContent('NO_BLOCKER_FOUND');
    expect(screen.getByTestId('concept-step-2')).toHaveTextContent('✓');
    expect(screen.getByTestId('concept-step-3')).toHaveAttribute('aria-current', 'step');
  });

  it('bij meer dan één koppeling gaat er geen linkId mee', async () => {
    const calls = await open(state({ links: 2 }));
    await screen.findByTestId('shared-definition-banner');
    fireEvent.click(screen.getByTestId('concept-trial'));
    const box = await screen.findByTestId('concept-trial-box');
    fireEvent.change(within(box).getByTestId('concept-trial-file'), { target: { files: [new File(['x'], 'a.csv')] } });
    fireEvent.click(within(box).getByTestId('concept-trial-start'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.form!.has('linkId')).toBe(false);
  });

  it('Activeren opent de dialoog met de R-CASE-03-waarschuwing en verstuurt approvedBy', async () => {
    const calls = await open(state());
    await screen.findByTestId('concept-bar');
    fireEvent.click(screen.getByTestId('concept-activate'));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByTestId('reopen-rejected-cases-warning')).toHaveTextContent('opnieuw geopend');
    // Zonder geslaagde controle staat er een extra waarschuwing, die het activeren niet tegenhoudt.
    expect(within(dialog).getByTestId('concept-no-trial-warning')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Activeren' }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.url).toMatch(/\/setup\/revisions\/100\/activate$/);
    expect(calls[0]!.body).toEqual({ approvedBy: 'An Beslisser' });
  });
});

describe('Bestandsopbouw bewerken', () => {
  it('Wijzigen toont de velden, het bestandstype is alleen-lezen en Opslaan stuurt enkel het gewijzigde veld', async () => {
    const calls = await open(state());
    fireEvent.click(await screen.findByTestId('file-change'));
    const form = await screen.findByTestId('file-edit-form');
    expect(form).toHaveTextContent('Het bestandstype kan niet gewijzigd worden.');
    // De herkenningsversie staat achter "Geavanceerd", met een waarschuwing.
    expect(within(screen.getByTestId('file-advanced')).getByRole('note')).toHaveTextContent(
      'Wijzig de herkenningsversie alleen',
    );

    fireEvent.change(within(form).getByLabelText('Scheidingsteken'), { target: { value: '|' } });
    fireEvent.click(within(form).getByTestId('inline-save'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0]!.method).toBe('PATCH');
    expect(sentFields(calls[0]!)).toEqual([['delimiter', '|']]);
    await waitFor(() => expect(screen.queryByTestId('file-edit-form')).not.toBeInTheDocument());
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Bestandsopbouw bewaard in het concept');
  });

  it('Opslaan zonder wijziging stuurt niets; Annuleren sluit het formulier', async () => {
    const calls = await open(state());
    fireEvent.click(await screen.findByTestId('file-change'));
    fireEvent.click(screen.getByTestId('inline-save'));
    expect(await screen.findByTestId('inline-problem')).toHaveTextContent('Er is niets gewijzigd.');
    expect(calls).toHaveLength(0);
    fireEvent.click(screen.getByTestId('inline-cancel'));
    expect(screen.queryByTestId('file-edit-form')).not.toBeInTheDocument();
  });

  it('het verwacht aantal kolommen kan hier niet leeg gemaakt worden: hint en melding, geen verzoek', async () => {
    const calls = await open(state({ draftDetail: { structureExpectedColumnCount: 12 } }));
    fireEvent.click(await screen.findByTestId('file-change'));
    const form = await screen.findByTestId('file-edit-form');
    const input = within(form).getByLabelText('Verwacht aantal kolommen');
    expect(input).toHaveAttribute('aria-describedby', 'file-expectedColumnCount-hint');
    expect(form).toHaveTextContent('Kan hier niet leeg gemaakt worden');
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.click(within(form).getByTestId('inline-save'));
    expect(await screen.findByTestId('inline-problem')).toHaveTextContent('kan hier niet leeg gemaakt worden');
    expect(calls).toHaveLength(0);
  });

  it('is alleen-lezen in Bekijken: geen Wijzigen', async () => {
    await open(state(), DRAFT_PATH);
    await screen.findByTestId('read-file');
    expect(screen.queryByTestId('file-change')).not.toBeInTheDocument();
    expect(screen.queryByTestId('thresholds-change')).not.toBeInTheDocument();
  });
});

describe('Drempels bewerken', () => {
  it('staat ingeklapt als niets afwijkt van de basisversie en open als een waarde afwijkt', async () => {
    await open(state({ draftDetail: { maxRejectedSharePercent: 2 } }));
    await waitFor(() => expect(screen.getByTestId('concept-title')).toHaveTextContent('wijzigingen'));
    expect((screen.getByTestId('thresholds-details') as HTMLDetailsElement).open).toBe(false);
    cleanup();

    await open(state());
    await waitFor(() => expect((screen.getByTestId('thresholds-details') as HTMLDetailsElement).open).toBe(true));
    expect(screen.getByTestId('diff-chip-maxRejectedSharePercent')).toHaveTextContent('Gewijzigd');
  });

  it('Wijzigen en Opslaan sturen enkel de gewijzigde drempel', async () => {
    const calls = await open(state());
    fireEvent.click(await screen.findByTestId('thresholds-change'));
    const form = await screen.findByTestId('thresholds-edit-form');
    fireEvent.change(within(form).getByLabelText('Maximum verworpen regels (%)'), { target: { value: '7' } });
    fireEvent.click(within(form).getByTestId('inline-save'));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(sentFields(calls[0]!)).toEqual([['maxRejectedSharePercent', 7]]);
    expect(await screen.findByTestId('save-toast')).toHaveTextContent('Drempels bewaard in het concept');
  });

  it('de drempel voor verworpen regels kan hier niet leeg gemaakt worden', async () => {
    const calls = await open(state());
    fireEvent.click(await screen.findByTestId('thresholds-change'));
    const form = await screen.findByTestId('thresholds-edit-form');
    const input = within(form).getByLabelText('Maximum verworpen regels (%)');
    expect(input).toHaveAttribute('aria-describedby', 'thr-maxRejectedSharePercent-hint');
    expect(form).toHaveTextContent('Kan hier niet leeg gemaakt worden');
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.click(within(form).getByTestId('inline-save'));
    expect(await screen.findByTestId('inline-problem')).toHaveTextContent('kan hier niet leeg gemaakt worden');
    expect(calls).toHaveLength(0);
  });
});
