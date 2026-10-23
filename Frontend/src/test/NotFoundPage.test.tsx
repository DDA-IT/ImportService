/**
 * UI-13 — 404-pagina: lege staat (mock .empty) met een link naar de werkvoorraad; geen inline styles.
 */

import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { NotFoundPage } from '../routes';

afterEach(() => cleanup());

describe('NotFoundPage', () => {
  it('toont de melding en een link naar de werkvoorraad, zonder inline styles', () => {
    const { container } = render(
      <MemoryRouter>
        <NotFoundPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '404 — Pagina niet gevonden' })).toBeInTheDocument();
    expect(screen.getByText('De pagina die u zoekt, bestaat niet.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Naar Inlezingen' })).toHaveAttribute('href', '/');
    expect(container.querySelector('[style]')).toBeNull();
  });
});
