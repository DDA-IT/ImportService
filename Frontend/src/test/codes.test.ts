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
        'BUNDLE_NOT_FROZEN',
        'BATCH_ALREADY_IN_BUNDLE',
        'BATCH_NOT_BUNDLEABLE',
        'BATCH_IN_PUBLICATION_BUNDLE',
        'BATCH_NOT_ACCEPTABLE',
        'BATCH_NOT_RESUMABLE',
        'BATCH_VALIDATION_NOT_ESTABLISHED',
        'BATCH_VALIDATION_BLOCKING',
        'BATCH_HAS_DECIDED_MUTATIONS',
        'MUTATION_NOT_DECIDABLE',
        'MUTATION_BLOCKED_BY_IDENTITY_INCIDENT',
        'IDENTITY_DECISION_NOT_IN_SCOPE',
        'DECISION_FILTER_REQUIRED',
        'DECISION_FILTER_UNKNOWN_FIELD',
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

  describe('T1.1b: authenticatiecodes (fase 5-AUTH)', () => {
    it('vertaalt elke nieuwe foutcode en toont de technische regel', () => {
      const cases: Array<[number, string]> = [
        [401, 'AUTHENTICATION_REQUIRED'],
        [403, 'ACCESS_DENIED'],
        [400, 'ACTOR_FIELD_MISMATCH'],
        [403, 'SYSTEM_ACTOR_FORBIDDEN'],
        [403, 'ACTOR_IDENTITY_INVALID'],
        [403, 'CSRF_TOKEN_INVALID'],
      ];
      for (const [status, code] of cases) {
        expect(CODE_MESSAGES[code], `verwacht een vertaling voor ${code}`).toBeDefined();
        const result = describeError(new ApiError(status, code, 'x', '/bundles/1/freeze'));
        expect(result.title).toBe(CODE_MESSAGES[code]!.title);
        expect(result.technical).toBe(`${code} · HTTP ${status} · /bundles/1/freeze`);
      }
    });

    it('ACTOR_FIELD_MISMATCH zegt dat er niets is opgeslagen', () => {
      expect(CODE_MESSAGES.ACTOR_FIELD_MISMATCH!.explanation).toContain('niets opgeslagen');
    });
  });

  describe('T1.1c: rechtencodes (fase 5-PERM)', () => {
    it('PERMISSION_DENIED (403) en PERMISSION_SOURCE_UNAVAILABLE (503) zijn vertaald en tonen de code', () => {
      const denied = describeError(
        new ApiError(403, 'PERMISSION_DENIED', 'Missing permission catalogImport.approve', '/bundles/1/freeze'),
      );
      expect(denied.title).toBe('Recht ontbreekt');
      expect(denied.technical).toBe('PERMISSION_DENIED · HTTP 403 · /bundles/1/freeze');
      // De servertekst noemt het ontbrekende recht en wordt letterlijk getoond.
      expect(denied.detail).toBe('Missing permission catalogImport.approve');

      const unavailable = describeError(new ApiError(503, 'PERMISSION_SOURCE_UNAVAILABLE', 'x', '/me'));
      expect(unavailable.title).toBe('Rechten tijdelijk niet beschikbaar');
      expect(unavailable.technical).toBe('PERMISSION_SOURCE_UNAVAILABLE · HTTP 503 · /me');
      expect(unavailable.explanation).toContain('niets opgeslagen');
    });
  });

  describe('T1.1d: eigen codes voor upload- en bodyfouten (analyse-opvolging stap 3a)', () => {
    it('413 UPLOAD_TOO_LARGE ziet er uit zoals de vroegere 413 zonder code', () => {
      const withCode = describeError(new ApiError(413, 'UPLOAD_TOO_LARGE', 'too large', '/deliveries'));
      const withoutCode = describeError(new ApiError(413, null, null, '/deliveries'));
      expect(withCode.title).toBe('Bestand te groot');
      expect(withCode.title).toBe(withoutCode.title);
      expect(withCode.explanation).toBe(withoutCode.explanation);
      expect(withCode.whatNow).toBe(withoutCode.whatNow);
      expect(withCode.technical).toBe('UPLOAD_TOO_LARGE · HTTP 413 · /deliveries');
    });

    it('400 REQUEST_BODY_UNREADABLE is vertaald en krijgt niet de generieke weigeringstekst', () => {
      const result = describeError(new ApiError(400, 'REQUEST_BODY_UNREADABLE', 'unreadable', '/bundles/1/decisions'));
      expect(result.title).toBe('Verzoek niet leesbaar');
      expect(result.explanation).toContain('niets opgeslagen');
      expect(result.technical).toBe('REQUEST_BODY_UNREADABLE · HTTP 400 · /bundles/1/decisions');
    });

    it('500 INTERNAL_ERROR valt onder de vaste serverfoutregel, ook met code', () => {
      const result = describeError(new ApiError(500, 'INTERNAL_ERROR', 'An unexpected error occurred', '/x'));
      expect(result.title).toBe('Onverwachte serverfout');
      expect(result.detail).toBeNull();
      expect(result.technical).toBe('INTERNAL_ERROR · HTTP 500 · /x');
    });
  });

  describe('T1.2: onbekende code — familie-fallback', () => {
    // NT-11a (V7): de titel en uitleg van een fallback zijn gewoon Nederlands; de code staat enkel in `technical`.
    it('CONFIG_* krijgt de configuratie-fallback, zonder de code in titel of uitleg', () => {
      const error = new ApiError(409, 'CONFIG_INCOMPLETE', 'Config incomplete', '/import-links/1');
      const result = describeError(error);

      expect(result.title).toBe('De beschrijving van het bestand klopt niet');
      expect(result.explanation).toContain('beschrijving van het bestand');
      expect(result.title).not.toContain('CONFIG_INCOMPLETE');
      expect(result.technical).toContain('CONFIG_INCOMPLETE');
    });

    it('*_NOT_FOUND krijgt de "niet gevonden"-fallback', () => {
      const error = new ApiError(404, 'SUPPLIER_NOT_FOUND', 'Supplier not found', '/suppliers/9');
      const result = describeError(error);

      expect(result.title).toBe('Niet gevonden');
      expect(result.explanation).toBe('Niet gevonden.');
      expect(result.technical).toContain('SUPPLIER_NOT_FOUND');
    });

    it('*_IN_USE krijgt de "al in gebruik"-fallback', () => {
      const error = new ApiError(409, 'DISCOUNT_CODE_IN_USE', 'Discount code in use', '/discounts');
      const result = describeError(error);

      expect(result.title).toBe('Al in gebruik');
      expect(result.explanation).toBe('Die code of combinatie is al in gebruik.');
    });

    it('*_CHANGED* krijgt de "toestand veranderd"-fallback', () => {
      const error = new ApiError(409, 'BUNDLE_STATUS_CHANGED_UNEXPECTEDLY', 'Status changed', '/bundles/42');
      const result = describeError(error);

      expect(result.title).toBe('Intussen veranderd');
      expect(result.explanation).toBe('De toestand is ondertussen veranderd; lees opnieuw.');
    });

    it('overige 409 krijgt de generieke weigeringsfallback', () => {
      const error = new ApiError(409, 'SOME_FUTURE_CODE', 'Some future code', '/bundles/42/whatever');
      const result = describeError(error);

      expect(result.title).toBe('De bewerking is geweigerd');
      expect(result.explanation).toBe('De bewerking is geweigerd in de huidige toestand.');
      expect(result.title).not.toContain('SOME_FUTURE_CODE');
    });

    it('toont de technische regel ook bij een onbekende code', () => {
      const error = new ApiError(409, 'SOME_FUTURE_CODE', 'Some future code', '/bundles/42/whatever');
      const result = describeError(error);

      expect(result.technical).toBe('SOME_FUTURE_CODE · HTTP 409 · /bundles/42/whatever');
    });
  });

  describe('T1.2b: geen ruwe codes in de Nederlandse teksten (NT-11a, V7)', () => {
    // Een ruwe code is een woord met een underscore (BUNDLE_NOT_FOUND) of een enumwaarde in hoofdletters
    // (DRAFT, SCREENED); die horen enkel in de technische regel, nooit in titel, uitleg of "wat nu".
    const RAW_CODE = /\b[A-Z]+_[A-Z_]+\b/;
    const RAW_WORD = /\b[A-Z]{4,}\b/;

    it('geen enkele ingang van CODE_MESSAGES toont een ruwe code', () => {
      for (const [code, entry] of Object.entries(CODE_MESSAGES)) {
        for (const text of [entry.title, entry.explanation, entry.whatNow ?? '']) {
          expect(RAW_CODE.test(text), `${code}: ruwe code in "${text}"`).toBe(false);
          expect(RAW_WORD.test(text), `${code}: enumwaarde in "${text}"`).toBe(false);
        }
      }
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
