/**
 * NT-5 — `StatusBadge`/`Term`/`WhatIsThis`/`TechnicalDetails`: Nederlands label als hoofdtekst, uitleg
 * en technische code in de tooltip, geen crash bij een onbekende code.
 */

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatusBadge } from '../components/StatusBadge.tsx';
import { TechnicalDetails } from '../terms/TechnicalDetails.tsx';
import { Term } from '../terms/Term.tsx';
import { WhatIsThis } from '../terms/WhatIsThis.tsx';

describe('StatusBadge met domein', () => {
  it('toont het Nederlandse label, met uitleg en code in de tooltip', () => {
    render(<StatusBadge status="BLOCKED" domain="batchStatus" />);
    const badge = screen.getByText('Tegengehouden');
    expect(badge.getAttribute('title')).toContain('De levering is als geheel onbruikbaar.');
    expect(badge.getAttribute('title')).toContain('BLOCKED');
    expect(screen.queryByText('BLOCKED')).not.toBeInTheDocument();
  });

  it('koppelt de uitleg als verborgen beschrijving via aria-describedby', () => {
    render(<StatusBadge status="REJECTED" domain="mutationStatus" />);
    const badge = screen.getByText('Afgekeurd');
    const describedBy = badge.getAttribute('aria-describedby');
    expect(describedBy).not.toBeNull();
    expect(document.getElementById(describedBy as string)?.textContent).toBe(
      'Afgekeurd; er is altijd een reden vastgelegd.',
    );
  });

  it('kiest het woord per domein: REJECTED is bij een behandelgeval "Afgewezen"', () => {
    render(<StatusBadge status="REJECTED" domain="issueCaseStatus" />);
    expect(screen.getByText('Afgewezen')).toBeInTheDocument();
  });

  it('toont een onbekende status als code, zonder crash', () => {
    render(<StatusBadge status="NIEUWE_STATUS" domain="bundleStatus" />);
    const badge = screen.getByText('NIEUWE_STATUS');
    expect(badge.getAttribute('aria-describedby')).toBeNull();
  });
});

describe('Term, WhatIsThis en TechnicalDetails', () => {
  it('Term toont het label van een doelmodus', () => {
    render(<Term domain="targetMode" code="SIMULATION" />);
    expect(screen.getByText('Proefpublicatie')).toBeInTheDocument();
  });

  it('WhatIsThis is een inklapbaar blok met vaste titel', () => {
    const { container } = render(<WhatIsThis>Uitleg in gewone woorden.</WhatIsThis>);
    expect(screen.getByText('Wat betekent dit?')).toBeInTheDocument();
    expect(container.querySelector('details')).not.toBeNull();
    expect(container.querySelector('details')?.hasAttribute('open')).toBe(false);
  });

  it('TechnicalDetails toont de codes onder een inklapbare titel en niets bij een lege lijst', () => {
    const { container, rerender } = render(
      <TechnicalDetails items={[{ name: 'Status', value: 'SCREENED' }]} />,
    );
    expect(screen.getByText('Technische details (voor support)')).toBeInTheDocument();
    expect(screen.getByText('SCREENED')).toBeInTheDocument();
    rerender(<TechnicalDetails items={[]} />);
    expect(container.querySelector('details')).toBeNull();
  });
});
