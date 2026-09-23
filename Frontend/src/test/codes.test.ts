/**
 * T1 — `errors/codes.ts` — bekende code, onbekende code per familie-fallback, 400 zonder code,
 * 500 zonder body, netwerkfout.
 * Zie `docs/design/frontend-scherm3-bundel-design.md` §14.1 en §4.
 */

import { describe, it, expect } from 'vitest';
import { ApiError } from '../api/http';
import { describe as describeError, CODE_MESSAGES } from '../errors/codes';

describe('describe(error) — errors/codes.ts', () => {
  describe('T1.1: bekende code', () => {
    it('geeft titel, uitleg en "wat nu" uit CODE_MESSAGES', () => {
      const error = new ApiError(409, 'BUNDLE_HAS_UNDECIDED_MUTATIONS', 'Bundle has undecided mutations', '/bundles/42/freeze');
      const result = describeError(error);

      expect(result.title).toBe(CODE_MESSAGES.BUNDLE_HAS_UNDECIDED_MUTATIONS!.title);
      expect(result.explanation).toBe(CODE_MESSAGES.BUNDLE_HAS_UNDECIDED_MUTATIONS!.explanation);
      expect(result.whatNow).toBe(CODE_MESSAGES.BUNDLE_HAS_UNDECIDED_MUTATIONS!.whatNow);
    });

    it('toont de technische regel altijd, ook bij een bekende code', () => {
      const error = new ApiError(404, 'BUNDLE_NOT_FOUND', 'Bundle not found', '/bundles/999');
      const result = describeError(error);

      expect(result.technical).toBe('BUNDLE_NOT_FOUND · HTTP 404 · /bundles/999');
    });

    it('dekt elke code uit §1.2 in CODE_MESSAGES', () => {
      const codesFromDesignDoc = [
        'BUNDLE_NOT_FOUND',
        'BATCH_NOT_FOUND',
        'BATCH_NOT_IN_BUNDLE',
        'MUTATION_NOT_IN_BUNDLE',
        'BUNDLE_REFERENCE_REUSED_WITH_DIFFERENT_SCOPE',
        'BUNDLE_NOT_ASSEMBLING',
        'BATCH_ALREADY_IN_BUNDLE',
        'BATCH_NOT_BUNDLEABLE',
        'BATCH_VALIDATION_NOT_ESTABLISHED',
        'BATCH_VALIDATION_BLOCKING',
        'BATCH_HAS_DECIDED_MUTATIONS',
        'MUTATION_NOT_DECIDABLE',
        'MUTATION_BLOCKED_BY_IDENTITY_INCIDENT',
        'IDENTITY_DECISION_NOT_IN_SCOPE',
        'DECISION_FILTER_REQUIRED',
        'BUNDLE_EMPTY',
        'BUNDLE_HAS_UNDECIDED_MUTATIONS',
        'SOURCE_STATE_CHANGED_SINCE_SCREENING',
        'BUNDLE_OFFER_CONFLICT',
        'OFFER_ALREADY_IN_ANOTHER_BUNDLE',
        'BUNDLE_CONTENT_CHANGED_DURING_FREEZE',
        'BUNDLE_NOT_CANCELLABLE',
        'BUNDLE_CONTENT_CHANGED_DURING_CANCEL',
      ];

      for (const code of codesFromDesignDoc) {
        expect(CODE_MESSAGES[code], `verwacht een vertaling voor ${code}`).toBeDefined();
      }
    });
  });

  describe('T1.2: onbekende code — familie-fallback', () => {
    it('CONFIG_* krijgt de configuratie-fallback en toont de code letterlijk in de titel', () => {
      const error = new ApiError(409, 'CONFIG_INCOMPLETE', 'Config incomplete', '/import-links/1');
      const result = describeError(error);

      expect(result.title).toBe('Geweigerd (CONFIG_INCOMPLETE)');
      expect(result.explanation).toContain('configuratie van de importdefinitie');
    });

    it('*_NOT_FOUND krijgt de "niet gevonden"-fallback', () => {
      const error = new ApiError(404, 'SUPPLIER_NOT_FOUND', 'Supplier not found', '/suppliers/9');
      const result = describeError(error);

      expect(result.title).toBe('Geweigerd (SUPPLIER_NOT_FOUND)');
      expect(result.explanation).toBe('Niet gevonden.');
    });

    it('*_IN_USE krijgt de "al in gebruik"-fallback', () => {
      const error = new ApiError(409, 'DISCOUNT_CODE_IN_USE', 'Discount code in use', '/discounts');
      const result = describeError(error);

      expect(result.title).toBe('Geweigerd (DISCOUNT_CODE_IN_USE)');
      expect(result.explanation).toBe('Die code of scope is al in gebruik.');
    });

    it('*_CHANGED* krijgt de "toestand veranderd"-fallback', () => {
      const error = new ApiError(409, 'BUNDLE_STATUS_CHANGED_UNEXPECTEDLY', 'Status changed', '/bundles/42');
      const result = describeError(error);

      expect(result.title).toBe('Geweigerd (BUNDLE_STATUS_CHANGED_UNEXPECTEDLY)');
      expect(result.explanation).toBe('De toestand is ondertussen veranderd; lees opnieuw.');
    });

    it('overige 409 krijgt de generieke weigeringsfallback', () => {
      const error = new ApiError(409, 'SOME_FUTURE_CODE', 'Some future code', '/bundles/42/whatever');
      const result = describeError(error);

      expect(result.title).toBe('Geweigerd (SOME_FUTURE_CODE)');
      expect(result.explanation).toBe('De bewerking is geweigerd in de huidige toestand.');
    });

    it('toont de technische regel ook bij een onbekende code', () => {
      const error = new ApiError(409, 'SOME_FUTURE_CODE', 'Some future code', '/bundles/42/whatever');
      const result = describeError(error);

      expect(result.technical).toBe('SOME_FUTURE_CODE · HTTP 409 · /bundles/42/whatever');
    });
  });

  describe('T1.3: 400 zonder code', () => {
    it('toont de Engelse backendMessage letterlijk', () => {
      const error = new ApiError(
        400,
        null,
        'Missing reason: rejecting mutations always requires one',
        '/bundles/42/mutations/10/reject'
      );
      const result = describeError(error);

      expect(result.title).toBe('Ongeldige invoer');
      expect(result.explanation).toBe('Missing reason: rejecting mutations always requires one');
      expect(result.technical).toBe('geen code · HTTP 400 · /bundles/42/mutations/10/reject');
    });
  });

  describe('T1.4: 500 zonder body', () => {
    it('meldt eerlijk dat er geen details zijn', () => {
      const error = new ApiError(500, null, null, '/bundles');
      const result = describeError(error);

      expect(result.title).toBe('Onverwachte serverfout');
      expect(result.explanation).toContain('geen foutdetails');
      expect(result.whatNow).toContain('serverlogboek');
      expect(result.technical).toBe('geen code · HTTP 500 · /bundles');
    });
  });

  describe('T1.5: netwerkfout (status 0)', () => {
    it('meldt eerlijk dat er geen verbinding/details zijn', () => {
      const error = new ApiError(0, null, null, '/bundles');
      const result = describeError(error);

      expect(result.title).toBe('Geen verbinding met de server');
      expect(result.technical).toBe('geen code · HTTP 0 · /bundles');
    });
  });
});
