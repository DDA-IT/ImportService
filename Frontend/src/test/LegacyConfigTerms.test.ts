/**
 * S9 — woordenlijst en foutteksten van de legacy-config-migratie.
 *
 * Bewaakt dat elke rapportcode die de backend (nog) kan teruggeven een Nederlandse uitleg heeft in het woordenboek
 * (`legacyReportCode`), en dat elke foutcode van de `/legacy-config`-endpoints een eigen tekst heeft in `errors/codes.ts`.
 * De backendcodes worden uit de bronbestanden gelezen (zelfde patroon als `terms.test.ts`, dat de handleiding leest):
 * een nieuwe constante zonder tekst laat deze test falen, zodat een code nooit ongemerkt als "Ander punt" op het scherm komt.
 */

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/http';
import { CODE_MESSAGES, describe as describeError } from '../errors/codes';
import { reportCodeKey } from '../features/legacyconfig/legacyPolicy';
import { DICTIONARY, term } from '../terms/index';

const SERVICE = resolve(dirname(fileURLToPath(import.meta.url)), '../../../Service/src/main/java/be/dda/catalogimport/service');

/** Constanten van de vorm `static final String NAAM = "NAAM";` — naam en waarde gelijk, zoals alle rapportcodes. */
function reportCodeConstants(file: string): string[] {
  const source = readFileSync(`${SERVICE}/${file}`, 'utf-8');
  return [...source.matchAll(/static final String ([A-Z][A-Z0-9_]+) = "\1";/g)].map((match) => match[1] as string);
}

/** Constanten die de backend nog definieert maar nergens meer in een rapport zet (zie het ontwerp, par. 5). */
const NO_LONGER_REPORTED = new Set([
  'LEGACY_SUBSTRING_NOT_MIGRATED',
  'TRANSFORM_CHAIN_UNSUPPORTED',
  'MAP_MISS_NO_FALLBACK',
  'INCLUDE_FILTER_COMBINATION_UNSUPPORTED',
  'LEGACY_EXCLUDES_NOT_MIGRATED',
]);

/** Rapportcodes die niet als losse constante bestaan (letterlijke tekst in de vertaler of in de apply-service). */
const LITERAL_REPORT_CODES = [
  'DEFINITION',
  'SOURCE_ORGANISATION',
  'DELIMITER',
  'CHARSET',
  'HEADER',
  'IDENTITY_PROFILE',
  'IDENTITY_FIELD',
  'BASE_PRICE',
  'DESCRIPTION',
  'MAPPING',
  'MAP_TABLE',
  'RECORD_FILTER',
  'RECORD_FILTER_GROUP',
  'LIBRARY_CODE',
  'DEFINITION_CODE_OVERRIDDEN',
  'SUCCESSOR_DRAFT',
  'LEGACY_CALC_8_NOT_MIGRATED',
];

describe('rapportcodes van de legacy-config-migratie', () => {
  const translator = reportCodeConstants('LegacyConfigTranslator.java');
  const apply = reportCodeConstants('LegacyConfigApplyService.java');

  it('vindt de constanten in de backend (de test leest echt iets)', () => {
    expect(translator.length).toBeGreaterThan(40);
    expect(apply.length).toBeGreaterThan(20);
  });

  it.each([...new Set([...translator, ...apply, ...LITERAL_REPORT_CODES])].filter((code) => !NO_LONGER_REPORTED.has(code)))(
    '%s heeft een Nederlandse uitleg',
    (code) => {
      const resolved = term('legacyReportCode', reportCodeKey(code));
      expect(resolved.uitleg, `${code} ontbreekt in het woordenboek (legacyReportCode)`).not.toBe('');
    },
  );

  it('geeft alle berekeningscodes dezelfde ingang', () => {
    expect(reportCodeKey('LEGACY_CALC_8_NOT_MIGRATED')).toBe('LEGACY_CALC_NOT_MIGRATED');
    expect(reportCodeKey('LEGACY_CALC_13_NOT_MIGRATED')).toBe('LEGACY_CALC_NOT_MIGRATED');
    expect(reportCodeKey('LEGACY_CALC_VALUE_INVALID')).toBe('LEGACY_CALC_VALUE_INVALID');
  });

  it('heeft een uitleg voor de nieuwe vaststellingen van de screening (S6 en S8g)', () => {
    expect(DICTIONARY.issueCode.MAPPING_VALUE_PASSED_THROUGH).toBeDefined();
    expect(DICTIONARY.issueCode.CONFIG_IDENTITY_SOURCE_CONFLICT).toBeDefined();
  });

  it('heeft een ingang voor elke status, uitkomst, actie en wachtwoorduitkomst', () => {
    for (const code of ['RECEIVED', 'APPLIED', 'SUPERSEDED', 'DISCARDED']) {
      expect(DICTIONARY.legacyReceiptStatus[code], code).toBeDefined();
    }
    for (const code of ['BLOCKING', 'WARNING', 'NOT_MIGRATED', 'MIGRATED']) {
      expect(DICTIONARY.legacyReportStatus[code], code).toBeDefined();
    }
    for (const code of ['STORED', 'DISCARDED_ALREADY_PRESENT', 'DISCARDED_PROTOCOL_UNSUPPORTED', 'DISCARDED_NO_HOST', 'null']) {
      expect(DICTIONARY.legacySecretOutcome[code], code).toBeDefined();
    }
    for (const code of ['CREATE', 'REUSE', 'SUCCESSOR_DRAFT', 'UNCHANGED', 'NONE']) {
      expect(DICTIONARY.legacyAction[code], code).toBeDefined();
    }
    for (const code of ['VERIFIED', 'UNAVAILABLE', 'NOT_CONFIGURED']) {
      expect(DICTIONARY.legacyProdisReference[code], code).toBeDefined();
    }
  });
});

describe('foutcodes van de legacy-config-endpoints', () => {
  const ERROR_CODES: Array<[number, string]> = [
    [400, 'LEGACY_BUNDLE_INVALID'],
    [400, 'LEGACY_BUNDLE_VERSION_UNSUPPORTED'],
    [400, 'LEGACY_BUNDLE_HASH_MISMATCH'],
    [400, 'LEGACY_APPLY_REASON_REQUIRED'],
    [400, 'LEGACY_APPLY_REASON_TOO_LONG'],
    [400, 'LEGACY_DISCARD_REASON_REQUIRED'],
    [400, 'LEGACY_DISCARD_REASON_TOO_LONG'],
    [404, 'LEGACY_RECEIPT_NOT_FOUND'],
    [409, 'SECRETS_NOT_CONFIGURED'],
    [409, 'LEGACY_RECEIPT_NOT_APPLICABLE'],
    [409, 'LEGACY_PREVIEW_STALE'],
    [409, 'LEGACY_APPLY_BLOCKED'],
    [409, 'PRODIS_DB_NOT_CONFIGURED'],
    [413, 'LEGACY_BUNDLE_TOO_LARGE'],
    [503, 'PRODIS_DB_UNAVAILABLE'],
  ];

  it.each(ERROR_CODES)('%i %s heeft een eigen Nederlandse tekst, met de code enkel in de technische regel', (status, code) => {
    expect(CODE_MESSAGES[code], `${code} ontbreekt in errors/codes.ts`).toBeDefined();
    const shown = describeError(new ApiError(status, code, 'dev message', '/legacy-config/receipts/7/apply'));
    expect(shown.title).not.toBe('De bewerking is geweigerd');
    expect(`${shown.title} ${shown.explanation} ${shown.whatNow ?? ''}`).not.toContain(code);
    expect(shown.technical).toBe(`${code} · HTTP ${status} · /legacy-config/receipts/7/apply`);
  });

  it('bevatten geen ruwe code in de teksten', () => {
    for (const [, code] of ERROR_CODES) {
      const entry = CODE_MESSAGES[code]!;
      expect(`${entry.title} ${entry.explanation} ${entry.whatNow ?? ''}`).not.toMatch(/[A-Z]+_[A-Z_]+/);
    }
  });
});
