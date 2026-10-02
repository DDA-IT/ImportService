/**
 * S1-F2 — `TemplateListPage` / `TemplateDetailPage` (scherm 1b, sjabloon-/materialisatiewizard,
 * alleen-lezen deel, `docs/decisions.md` 2026-09-27 "scherm 1a/1b").
 *
 * Dekt: sjabloonlijst laadt, revisie kiezen laadt de bookmarkset, de niet-blokkerende `problems`-lijst
 * wordt getoond, een DRAFT-revisie is uitgeschakeld met reden, de materialisatiehistoriek laadt, en de
 * eigen flag-uit-melding verschijnt bij een 404-zonder-code in plaats van de generieke foutmelding.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ActorProvider } from '../actor/ActorContext';
import { TEST_IDENTITY } from './testIdentity';
import { TemplateListPage } from '../features/templates/TemplateListPage';
import { TemplateDetailPage } from '../features/templates/TemplateDetailPage';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

/** Een 404 zonder body/`code` — het patroon van een `@ConditionalOnProperty`-controller die niet bestaat. */
function noCodeNotFoundResponse(): Response {
  return new Response(null, { status: 404 });
}

const TEMPLATES_RESPONSE = {
  content: [
    { id: 1, code: 'TPL-1', name: 'VROOAM-sjabloon', sourceOrganisationId: 5, sourceOrganisationCode: 'ORG-5' },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
};

const REVISIONS_RESPONSE = {
  content: [
    { id: 100, definitionId: 1, revisionNumber: 1, status: 'ACTIVE' },
    { id: 101, definitionId: 1, revisionNumber: 2, status: 'DRAFT' },
  ],
  page: 0,
  size: 50,
  totalElements: 2,
  totalPages: 1,
};

const BOOKMARK_SET_RESPONSE = {
  definitionId: 1,
  revisionId: 100,
  bookmarks: [
    {
      id: 1000,
      revisionId: 100,
      name: 'BESTANDS_PREFIX',
      label: 'Bestandsprefix',
      description: null,
      dataType: 'TEXT',
      valueScope: 'LINK',
      ownerRole: 'admin',
      required: true,
      defaultValue: null,
      allowedValues: null,
      validationPattern: null,
      sortOrder: 1,
      usages: [],
    },
  ],
  problems: [
    { code: 'CONFIG_BOOKMARK_WITHOUT_PLACE', bookmarkName: 'BESTANDS_PREFIX', message: 'Geen configuratieplaats gekoppeld aan deze bookmark.' },
  ],
};

const MATERIALISATIONS_RESPONSE = {
  content: [
    {
      definitionId: 20,
      definitionCode: 'DEF-20',
      definitionName: 'Leverancier X',
      definitionRevisionId: 200,
      definitionRevisionNumber: 1,
      definitionRevisionStatus: 'ACTIVE',
      templateRevisionId: 100,
      templateRevisionNumber: 1,
      templateRevisionStatus: 'ACTIVE',
      importLinkCount: 2,
      shareable: true,
      blockingBookmarkName: null,
    },
  ],
  page: 0,
  size: 50,
  totalElements: 1,
  totalPages: 1,
};

function renderListPage() {
  return render(
    <ActorProvider identity={TEST_IDENTITY}>
      <MemoryRouter initialEntries={['/templates']}>
        <Routes>
          <Route path="/templates" element={<TemplateListPage />} />
          <Route path="/templates/:definitionId" element={<TemplateDetailPage />} />
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

function renderDetailPage(definitionId = '1') {
  return render(
    <ActorProvider identity={TEST_IDENTITY}>
      <MemoryRouter initialEntries={[`/templates/${definitionId}`]}>
        <Routes>
          <Route path="/templates/:definitionId" element={<TemplateDetailPage />} />
        </Routes>
      </MemoryRouter>
    </ActorProvider>,
  );
}

describe('TemplateListPage / TemplateDetailPage (S1-F2)', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    cleanup();
    sessionStorage.clear();
    vi.clearAllMocks();
    global.fetch = originalFetch;
  });

  it('S1-F2.1: sjabloonlijst laadt', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/templates')) {
        return Promise.resolve(jsonResponse(TEMPLATES_RESPONSE));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    renderListPage();

    expect(await screen.findByText('TPL-1')).toBeInTheDocument();
    expect(screen.getByText('VROOAM-sjabloon')).toBeInTheDocument();
  });

  it('S1-F2.2: flag-uit-melding bij een 404 zonder code i.p.v. de generieke foutmelding', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/templates')) {
        return Promise.resolve(noCodeNotFoundResponse());
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    renderListPage();

    expect(
      await screen.findByText(/De sjabloon-\/materialisatiewizard is niet actief op deze omgeving/),
    ).toBeInTheDocument();
    // De generieke fallback-titel van `describe()` mag niet verschijnen.
    expect(screen.queryByText(/De bewerking is geweigerd/)).not.toBeInTheDocument();
  });

  it('S1-F2.3: revisie kiezen laadt de bookmarkset, DRAFT is uitgeschakeld met reden, problems getoond', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/templates/1/revisions/100/bookmarks')) {
        return Promise.resolve(jsonResponse(BOOKMARK_SET_RESPONSE));
      }
      if (url.includes('/templates/1/materialisations')) {
        return Promise.resolve(jsonResponse(MATERIALISATIONS_RESPONSE));
      }
      if (url.includes('/definitions/1/revisions')) {
        return Promise.resolve(jsonResponse(REVISIONS_RESPONSE));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    renderDetailPage();

    expect(await screen.findByText('Versie 1')).toBeInTheDocument();
    const draftButton = screen.getByRole('button', { name: 'Versie 2' });
    expect(draftButton).toBeDisabled();
    // NT-11c: de reden is gewoon Nederlands (geen "DRAFT").
    expect(draftButton).toHaveAttribute('title', 'een concept van een sjabloon is nog niet bruikbaar om uit te materialiseren');
    expect(
      screen.getByText('een concept van een sjabloon is nog niet bruikbaar om uit te materialiseren'),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Versie 1' }));

    // Het label staat in beeld, de technische naam in de tooltip.
    const label = (await screen.findAllByText('Bestandsprefix', { selector: 'span' }))[0]!;
    expect(label).toHaveAttribute('title', 'Technische naam: BESTANDS_PREFIX');
    const problems = await screen.findByTestId('bookmark-problems');
    expect(problems).toHaveAttribute('role', 'alert');
    // De servermelding is Engels; de zichtbare tekst komt uit het woordenboek, de code staat in de tooltip.
    expect(problems).toHaveTextContent('Invulpunt zonder bestemming');
    expect(problems.textContent).not.toContain('CONFIG_BOOKMARK_WITHOUT_PLACE');
    expect(problems.querySelector('li')?.getAttribute('title')).toContain('CONFIG_BOOKMARK_WITHOUT_PLACE');
  });

  it('S1-F2.4: materialisatiehistoriek laadt', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/templates/1/materialisations')) {
        return Promise.resolve(jsonResponse(MATERIALISATIONS_RESPONSE));
      }
      if (url.includes('/definitions/1/revisions')) {
        return Promise.resolve(jsonResponse(REVISIONS_RESPONSE));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    renderDetailPage();

    expect(await screen.findByText('DEF-20')).toBeInTheDocument();
    expect(screen.getByText('Leverancier X')).toBeInTheDocument();
    expect(screen.getByText('2 koppeling(en)')).toBeInTheDocument();
    expect(screen.getByText('deelbaar')).toBeInTheDocument();
  });

  it('S1-F2.5: flag-uit-melding op de bookmarkset i.p.v. de generieke foutmelding', async () => {
    global.fetch = vi.fn((input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/templates/1/revisions/100/bookmarks')) {
        return Promise.resolve(noCodeNotFoundResponse());
      }
      if (url.includes('/templates/1/materialisations')) {
        return Promise.resolve(noCodeNotFoundResponse());
      }
      if (url.includes('/definitions/1/revisions')) {
        return Promise.resolve(jsonResponse(REVISIONS_RESPONSE));
      }
      return Promise.reject(new Error(`Onverwachte URL in test: ${url}`));
    }) as unknown as typeof fetch;

    renderDetailPage();

    await screen.findByText('Versie 1');
    fireEvent.click(screen.getByRole('button', { name: 'Versie 1' }));

    // De materialisatiehistoriek laadt al vanaf het openen van de pagina en kan haar melding dus
    // eerder tonen dan de bookmarksectie (die pas na de klik mount) — wacht tot beide er staan in
    // plaats van te stoppen bij de eerste treffer.
    await waitFor(() => {
      const flagMessages = screen.getAllByText(
        /De sjabloon-\/materialisatiewizard is niet actief op deze omgeving/,
      );
      // Eén keer voor de bookmarksectie, één keer voor de materialisatiehistoriek.
      expect(flagMessages.length).toBe(2);
    });
  });
});
