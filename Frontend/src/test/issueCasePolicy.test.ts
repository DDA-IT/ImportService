/**
 * S2-F2 — `issueCasePolicy.ts` — de statusmatrix van §4, over alle vier statussen × drie acties.
 */

import { describe, expect, it } from 'vitest';
import {
  ISSUE_CASE_ACTIONS,
  issueCaseActionGate,
  issueCaseActionLabel,
  issueCaseActionsFor,
  issueCaseTargetStatus,
} from '../features/issuecases/issueCasePolicy.ts';
import { ISSUE_CASE_STATUSES, type IssueCaseStatus } from '../api/types.ts';
import { gateTitle } from '../terms/gateTitle.ts';
import { term } from '../terms/index.ts';

describe('issueCaseActionGate — §4 statusmatrix', () => {
  it('CORRECT/REJECT zijn enkel toegestaan vanuit AWAITING_REVIEW', () => {
    expect(issueCaseActionGate('AWAITING_REVIEW', 'CORRECT')).toEqual({ allowed: true });
    expect(issueCaseActionGate('AWAITING_REVIEW', 'REJECT')).toEqual({ allowed: true });

    for (const status of ISSUE_CASE_STATUSES.filter((s) => s !== 'AWAITING_REVIEW')) {
      for (const action of ['CORRECT', 'REJECT'] as const) {
        const gate = issueCaseActionGate(status, action);
        expect(gate.allowed).toBe(false);
        if (!gate.allowed) {
          // NT-11c: de reden is gewoon Nederlands; de stabiele code staat apart in `code`.
          expect(gate.code).toBe('ISSUE_CASE_TRANSITION_NOT_ALLOWED');
          expect(gate.reason).not.toContain('ISSUE_CASE_TRANSITION_NOT_ALLOWED');
          expect(gate.reason).not.toMatch(/[A-Z]+_[A-Z_]+/);
          expect(gate.reason).toContain(term('issueCaseStatus', status).label);
        }
      }
    }
  });

  it('REOPEN is enkel toegestaan vanuit CORRECTED/REJECTED/AUTO_RESOLVED, nooit vanuit AWAITING_REVIEW', () => {
    const reopenable: IssueCaseStatus[] = ['CORRECTED', 'REJECTED', 'AUTO_RESOLVED'];
    for (const status of reopenable) {
      expect(issueCaseActionGate(status, 'REOPEN')).toEqual({ allowed: true });
    }
    const blocked = issueCaseActionGate('AWAITING_REVIEW', 'REOPEN');
    expect(blocked.allowed).toBe(false);
    if (!blocked.allowed) {
      expect(blocked.code).toBe('ISSUE_CASE_TRANSITION_NOT_ALLOWED');
      expect(blocked.reason).not.toMatch(/[A-Z]+_[A-Z_]+/);
      expect(gateTitle(blocked)).toContain('(technische code: ISSUE_CASE_TRANSITION_NOT_ALLOWED)');
    }
  });

  it('elke geweigerde combinatie draagt een niet-lege reden', () => {
    for (const status of ISSUE_CASE_STATUSES) {
      for (const action of ISSUE_CASE_ACTIONS) {
        const gate = issueCaseActionGate(status, action);
        if (!gate.allowed) {
          expect(gate.reason.length).toBeGreaterThan(0);
        }
      }
    }
  });
});

describe('issueCaseTargetStatus — §4', () => {
  it('CORRECT -> CORRECTED, REJECT -> REJECTED, REOPEN -> AWAITING_REVIEW', () => {
    expect(issueCaseTargetStatus('CORRECT')).toBe('CORRECTED');
    expect(issueCaseTargetStatus('REJECT')).toBe('REJECTED');
    expect(issueCaseTargetStatus('REOPEN')).toBe('AWAITING_REVIEW');
  });
});

describe('issueCaseActionsFor — welke acties zijn zichtbaar per status', () => {
  it('AWAITING_REVIEW toont enkel Corrigeren en Afwijzen', () => {
    expect(issueCaseActionsFor('AWAITING_REVIEW')).toEqual(['CORRECT', 'REJECT']);
  });

  it.each(['CORRECTED', 'REJECTED', 'AUTO_RESOLVED'] as const)('%s toont enkel Heropenen', (status) => {
    expect(issueCaseActionsFor(status)).toEqual(['REOPEN']);
  });
});

describe('issueCaseActionLabel', () => {
  it('levert de Nederlandse knoptekst per actie', () => {
    expect(issueCaseActionLabel('CORRECT')).toBe('Corrigeren');
    expect(issueCaseActionLabel('REJECT')).toBe('Afwijzen');
    expect(issueCaseActionLabel('REOPEN')).toBe('Heropenen');
  });
});
