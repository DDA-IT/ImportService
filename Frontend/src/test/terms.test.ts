/**
 * NT-5 (V7) — het woordenboek `src/terms` en `docs/handleiding/begrippen.md` (sectie "Woordenlijst voor
 * de schermen") zijn twee weergaven van dezelfde tekst. Deze test houdt ze synchroon: elke
 * woordenboekingang staat (code, Nederlands, uitleg) identiek in de handleiding en omgekeerd.
 */

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { DICTIONARY, TERM_DOMAINS, term, type TermDomain } from '../terms/index.ts';

const BEGRIPPEN = resolve(dirname(fileURLToPath(import.meta.url)), '../../../docs/handleiding/begrippen.md');

type DocRow = { code: string; label: string; uitleg: string };

/** Leest per `<!-- terms:<domein> -->`-marker de tabel eronder als rijen `| Code | Nederlands | Uitleg |`. */
function parseDocTables(markdown: string): Map<string, DocRow[]> {
  const lines = markdown.split(/\r?\n/);
  const result = new Map<string, DocRow[]>();
  for (let i = 0; i < lines.length; i += 1) {
    const marker = /^<!--\s*terms:(\w+)\s*-->\s*$/.exec(lines[i] ?? '');
    if (marker === null) {
      continue;
    }
    const domain = marker[1] as string;
    const rows: DocRow[] = [];
    let j = i + 1;
    // header + scheidingslijn overslaan
    j += 2;
    while (j < lines.length && (lines[j] ?? '').startsWith('|')) {
      const cells = (lines[j] as string)
        .split('|')
        .slice(1, -1)
        .map((cell) => cell.trim());
      const [codeCell, label, uitleg] = cells;
      rows.push({
        code: (codeCell ?? '').replace(/^`|`$/g, ''),
        label: label ?? '',
        uitleg: uitleg ?? '',
      });
      j += 1;
    }
    result.set(domain, rows);
  }
  return result;
}

const docTables = parseDocTables(readFileSync(BEGRIPPEN, 'utf-8'));
const RAW_CODE = /[A-Z]+_[A-Z_]+/;

describe('woordenboek versus begrippen.md', () => {
  it('heeft voor elk domein een tabel in de handleiding', () => {
    for (const domain of TERM_DOMAINS) {
      expect(docTables.has(domain), `tabel voor ${domain} ontbreekt in begrippen.md`).toBe(true);
    }
    for (const domain of docTables.keys()) {
      expect(TERM_DOMAINS as readonly string[], `onbekend domein ${domain} in begrippen.md`).toContain(domain);
    }
  });

  describe.each([...TERM_DOMAINS])('%s', (domain: TermDomain) => {
    const entries = Object.entries(DICTIONARY[domain]);
    const rows = docTables.get(domain) ?? [];

    it('elke woordenboekingang staat identiek in de handleiding', () => {
      for (const [code, entry] of entries) {
        const row = rows.find((candidate) => candidate.code === code);
        expect(row, `${domain}.${code} ontbreekt in begrippen.md`).toBeDefined();
        expect(row?.label, `${domain}.${code} label`).toBe(entry.label);
        expect(row?.uitleg, `${domain}.${code} uitleg`).toBe(entry.uitleg);
      }
    });

    it('elke rij in de handleiding staat in het woordenboek, zonder dubbele codes', () => {
      const codes = rows.map((row) => row.code);
      expect(new Set(codes).size, `dubbele code in ${domain}`).toBe(codes.length);
      for (const row of rows) {
        expect(DICTIONARY[domain][row.code], `${domain}.${row.code} ontbreekt in het woordenboek`).toBeDefined();
      }
      expect(codes.length).toBe(entries.length);
    });

    it('bevat geen ruwe code in label of uitleg en geen lege tekst', () => {
      for (const [code, entry] of entries) {
        expect(entry.label.trim(), `${domain}.${code} label leeg`).not.toBe('');
        expect(entry.uitleg.trim(), `${domain}.${code} uitleg leeg`).not.toBe('');
        expect(RAW_CODE.test(entry.label), `${domain}.${code} label bevat een ruwe code`).toBe(false);
        expect(RAW_CODE.test(entry.uitleg), `${domain}.${code} uitleg bevat een ruwe code`).toBe(false);
      }
    });
  });
});

describe('term()', () => {
  it('geeft label, uitleg en code van een bekende waarde', () => {
    expect(term('batchStatus', 'BLOCKED')).toEqual({
      label: 'Tegengehouden',
      uitleg: 'De levering is als geheel onbruikbaar.',
      code: 'BLOCKED',
    });
  });

  it('onderscheidt BLOCKED en REJECTED per domein', () => {
    expect(term('mutationStatus', 'REJECTED').label).toBe('Afgekeurd');
    expect(term('issueCaseStatus', 'REJECTED').label).toBe('Afgewezen');
    expect(term('validationResult', 'BLOCKING').label).not.toBe(term('batchStatus', 'BLOCKED').label);
  });

  it('toont null als "Nog niet bepaald" voor het eindoordeel', () => {
    expect(term('validationResult', null).label).toBe('Nog niet bepaald');
    expect(term('validationResult', undefined).label).toBe('Nog niet bepaald');
  });

  it('valt voor een onbekende code terug op de code zelf, met lege uitleg, zonder te crashen', () => {
    expect(term('bundleStatus', 'SOMETHING_NEW')).toEqual({ label: 'SOMETHING_NEW', uitleg: '', code: 'SOMETHING_NEW' });
    expect(term('targetMode', 'toString').uitleg).toBe('');
  });
});
