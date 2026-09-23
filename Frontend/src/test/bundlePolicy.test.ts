/**
 * T2 — `bundlePolicy.ts` — de volledige matrix uit §9.1 en §9.2, tabelgedreven over alle 7
 * bundelstatussen × 11 mutatiestatussen × 4 actietypes. Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §14.1.
 */

import { describe, expect, it } from 'vitest';
import {
  BUNDLE_ACTIONS,
  bundleActionGate,
  isRevisionStatus,
  mutationDecisionGate,
  type BundleAction,
} from '../features/bundles/bundlePolicy.ts';
import {
  MUTATION_ACTION_TYPES,
  MUTATION_STATUSES,
  PUBLICATION_BUNDLE_STATUSES,
  type PublicationBundleStatus,
} from '../api/types.ts';

const FASE5_STATUSES: readonly PublicationBundleStatus[] = [
  'PUBLISHING',
  'PARTIALLY_PUBLISHED',
  'PUBLISHED',
  'PUBLICATION_FAILED',
];

describe('bundleActionGate — §9.1 statusmatrix', () => {
  it('T2.1: "alles lezen" is altijd toegestaan, voor elke status en elke bundelstatus', () => {
    for (const status of PUBLICATION_BUNDLE_STATUSES) {
      expect(bundleActionGate(status, 'READ')).toEqual({ allowed: true });
    }
  });

  const nonReadActions: readonly BundleAction[] = BUNDLE_ACTIONS.filter((action) => action !== 'READ');

  it('T2.2: ASSEMBLING staat elke actie toe, behalve READ die apart getest is', () => {
    for (const action of nonReadActions) {
      expect(bundleActionGate('ASSEMBLING', action)).toEqual({ allowed: true });
    }
  });

  it('T2.3: FROZEN staat enkel CANCEL toe (en READ); alle andere acties zijn uitgeschakeld met een reden', () => {
    expect(bundleActionGate('FROZEN', 'CANCEL')).toEqual({ allowed: true });

    for (const action of nonReadActions.filter((a) => a !== 'CANCEL')) {
      const gate = bundleActionGate('FROZEN', action);
      expect(gate.allowed).toBe(false);
      if (!gate.allowed) {
        expect(gate.reason.length).toBeGreaterThan(0);
      }
    }
  });

  it('T2.4: CANCELLED staat geen enkele schrijfactie toe, elk met een reden', () => {
    for (const action of nonReadActions) {
      const gate = bundleActionGate('CANCELLED', action);
      expect(gate.allowed).toBe(false);
      if (!gate.allowed) {
        expect(gate.reason.length).toBeGreaterThan(0);
      }
    }
  });

  it('T2.5: elke Fase 5-status is alleen-lezen, elke actie krijgt de statusnaam letterlijk in de reden', () => {
    for (const status of FASE5_STATUSES) {
      for (const action of nonReadActions) {
        const gate = bundleActionGate(status, action);
        expect(gate.allowed).toBe(false);
        if (!gate.allowed) {
          expect(gate.reason).toContain(status);
        }
      }
    }
  });

  it('T2.6: elke bundelstatus × elke actie levert een gate op (geen crash, geen undefined)', () => {
    for (const status of PUBLICATION_BUNDLE_STATUSES) {
      for (const action of BUNDLE_ACTIONS) {
        const gate = bundleActionGate(status, action);
        expect(typeof gate.allowed).toBe('boolean');
        if (!gate.allowed) {
          expect(typeof gate.reason).toBe('string');
        }
      }
    }
  });
});

describe('mutationDecisionGate — §9.2 beslisbaarheid per mutatie', () => {
  it('T2.7: bundel niet ASSEMBLING => nooit beslisbaar, voor elke status/actionType/scope', () => {
    const nonAssembling = PUBLICATION_BUNDLE_STATUSES.filter((s) => s !== 'ASSEMBLING');
    for (const bundleStatus of nonAssembling) {
      for (const actionType of MUTATION_ACTION_TYPES) {
        for (const status of MUTATION_STATUSES) {
          for (const scope of ['individual', 'group'] as const) {
            const gate = mutationDecisionGate(bundleStatus, actionType, status, scope);
            expect(gate.allowed).toBe(false);
          }
        }
      }
    }
  });

  it('T2.8: BLOCKED is nooit beslisbaar binnen ASSEMBLING, ongeacht actionType/scope', () => {
    for (const actionType of MUTATION_ACTION_TYPES) {
      for (const scope of ['individual', 'group'] as const) {
        const gate = mutationDecisionGate('ASSEMBLING', actionType, 'BLOCKED', scope);
        expect(gate.allowed).toBe(false);
        if (!gate.allowed) {
          expect(gate.reason).toContain('identiteitsincident');
        }
      }
    }
  });

  it('T2.9: IDENTITY_REFERENCE_INCIDENT is nooit beslisbaar binnen ASSEMBLING (behalve BLOCKED, al gedekt)', () => {
    for (const status of MUTATION_STATUSES.filter((s) => s !== 'BLOCKED')) {
      for (const scope of ['individual', 'group'] as const) {
        const gate = mutationDecisionGate('ASSEMBLING', 'IDENTITY_REFERENCE_INCIDENT', status, scope);
        expect(gate.allowed).toBe(false);
        if (!gate.allowed) {
          expect(gate.reason).toContain('referentiestaat');
        }
      }
    }
  });

  it('T2.10: IMPORT_MARKER is nooit beslisbaar binnen ASSEMBLING (behalve BLOCKED, al gedekt)', () => {
    for (const status of MUTATION_STATUSES.filter((s) => s !== 'BLOCKED')) {
      for (const scope of ['individual', 'group'] as const) {
        const gate = mutationDecisionGate('ASSEMBLING', 'IMPORT_MARKER', status, scope);
        expect(gate.allowed).toBe(false);
      }
    }
  });

  it('T2.11: CREATE/UPDATE + PLANNED/AWAITING_APPROVAL is beslisbaar, geen herziening, in beide scopes', () => {
    for (const actionType of ['CREATE', 'UPDATE'] as const) {
      for (const status of ['PLANNED', 'AWAITING_APPROVAL'] as const) {
        for (const scope of ['individual', 'group'] as const) {
          const gate = mutationDecisionGate('ASSEMBLING', actionType, status, scope);
          expect(gate.allowed).toBe(true);
          if (gate.allowed) {
            expect(gate.isRevision).toBe(false);
          }
        }
      }
    }
  });

  it('T2.12: CREATE/UPDATE + READY_FOR_PUBLICATION/REJECTED is enkel individueel beslisbaar, als herziening', () => {
    for (const actionType of ['CREATE', 'UPDATE'] as const) {
      for (const status of ['READY_FOR_PUBLICATION', 'REJECTED'] as const) {
        expect(isRevisionStatus(status)).toBe(true);

        const individual = mutationDecisionGate('ASSEMBLING', actionType, status, 'individual');
        expect(individual.allowed).toBe(true);
        if (individual.allowed) {
          expect(individual.isRevision).toBe(true);
        }

        const group = mutationDecisionGate('ASSEMBLING', actionType, status, 'group');
        expect(group.allowed).toBe(false);
      }
    }
  });

  it('T2.13: CREATE/UPDATE + overige statussen (EXPIRED, SKIPPED, RECORDED, IN_PROGRESS, PUBLISHED, TECHNICALLY_FAILED) is nooit beslisbaar', () => {
    const remaining = MUTATION_STATUSES.filter(
      (s) =>
        s !== 'BLOCKED' &&
        s !== 'PLANNED' &&
        s !== 'AWAITING_APPROVAL' &&
        !isRevisionStatus(s),
    );
    expect(remaining).toEqual(
      expect.arrayContaining(['EXPIRED', 'SKIPPED', 'RECORDED', 'IN_PROGRESS', 'PUBLISHED', 'TECHNICALLY_FAILED']),
    );

    for (const actionType of ['CREATE', 'UPDATE'] as const) {
      for (const status of remaining) {
        for (const scope of ['individual', 'group'] as const) {
          const gate = mutationDecisionGate('ASSEMBLING', actionType, status, scope);
          expect(gate.allowed).toBe(false);
        }
      }
    }
  });

  it('T2.14: elke combinatie van 7 bundelstatussen × 11 mutatiestatussen × 4 actietypes × 2 scopes levert een gate op', () => {
    let count = 0;
    for (const bundleStatus of PUBLICATION_BUNDLE_STATUSES) {
      for (const status of MUTATION_STATUSES) {
        for (const actionType of MUTATION_ACTION_TYPES) {
          for (const scope of ['individual', 'group'] as const) {
            const gate = mutationDecisionGate(bundleStatus, actionType, status, scope);
            expect(typeof gate.allowed).toBe('boolean');
            count += 1;
          }
        }
      }
    }
    expect(count).toBe(
      PUBLICATION_BUNDLE_STATUSES.length * MUTATION_STATUSES.length * MUTATION_ACTION_TYPES.length * 2,
    );
  });
});
