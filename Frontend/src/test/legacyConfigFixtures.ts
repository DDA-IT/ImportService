/**
 * Gedeelde testgegevens en fetch-naad voor de tests van het overnamescherm (S9, `LegacyConfig*.test.tsx`).
 * Het geheime wachtwoord hieronder staat bewust in de testbundel (`bundle.secret`) om te bewijzen dat het scherm het
 * nooit toont, ook niet als een antwoord het ten onrechte zou bevatten.
 */

import { vi } from 'vitest';
import type {
  LegacyConfigReport,
  LegacyPreviewResult,
  LegacyReceiptDetail,
  LegacyReceiptRow,
  LegacyReportItem,
} from '../api/types';

export const SECRET_VALUE = 'SUPER-GEHEIM-WACHTWOORD-123';

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

export function errorResponse(status: number, code: string, error = 'server message'): Response {
  return jsonResponse({ error, code }, status);
}

export function receiptRow(overrides: Partial<LegacyReceiptRow> = {}): LegacyReceiptRow {
  return {
    id: 7,
    originEnvironment: 'PROD',
    originDossier: 'KLANT01',
    interfaceCode: 'KRAMP_ART',
    bundleVersion: 1,
    contentHash: 'a'.repeat(64),
    definitionContentHash: 'b'.repeat(64),
    legacyDefinitionCode: 'VROOAM2',
    status: 'RECEIVED',
    secretReceived: true,
    secretOutcome: 'STORED',
    exportedAt: '2026-10-21T08:15:00Z',
    receivedAt: '2026-10-21T08:16:00Z',
    receivedBy: 'svc:datasync',
    supersededByReceiptId: null,
    appliedAt: null,
    appliedBy: null,
    ...overrides,
  };
}

export function receiptDetail(overrides: Partial<LegacyReceiptRow> = {}): LegacyReceiptDetail {
  return {
    receipt: receiptRow(overrides),
    secretOutcome: 'STORED',
    credentialRef: '11111111-2222-3333-4444-555555555555',
    bundle: {
      schema: 'legacy-config-bundle',
      version: 1,
      origin: { environment: 'PROD', dossier: 'KLANT01', legacyMaster: 'PRODIS' },
      exportedAt: '2026-10-21T10:15:00+02:00',
      interface: {
        code: 'KRAMP_ART',
        header: {
          direction: 'I',
          importExportType: 'CSV',
          executeAutomatic: true,
          executeEvery: 'D',
          executionTime: '060000',
          info: 'Kramp artikelen',
        },
        details: [
          { sequence: 1, label: 'FTP', value: 'sftp.example.test' },
          { sequence: 2, label: 'FTP_login', value: 'klant01' },
          { sequence: 100, label: 'ArtImp_Importsoort', value: 'VROOAM2' },
          // Vangnet: de backend laat dit nooit mee, maar het scherm toont de waarde dan toch niet.
          { sequence: 101, label: 'API_AUTH_PASSWORD', value: SECRET_VALUE },
        ],
        omittedSecretLabels: ['API_AUTH_PASSWORD'],
        ftpCredentialsRedacted: false,
      },
      definitionStatus: 'PRESENT',
      definition: {
        code: 'VROOAM2',
        description: 'VROOAM catalogus CSV',
        lines: [{}, {}, {}],
        maps: [{}],
        excludes: [],
      },
      // @ts-expect-error: een secret hoort hier niet te staan; de test bewijst dat het nooit getoond wordt.
      secret: { label: 'FTP_password', value: SECRET_VALUE },
    },
  };
}

export const BLOCKING_ITEM: LegacyReportItem = {
  element: 'INDET:ArtImp_BibNr',
  status: 'BLOCKING',
  code: 'LIBRARY_CODE_NOT_RESOLVED',
  target: null,
  message: 'legacy library 12 matches no Prodis library',
};

/** Bewust niet gesorteerd: het scherm moet zelf blokkerend eerst zetten. */
export const MIXED_ITEMS: LegacyReportItem[] = [
  { element: 'PSIMDHFD', status: 'MIGRATED', code: 'DEFINITION', target: 'import_definition.code=VROOAM2', message: null },
  { element: 'INHEAD', status: 'NOT_MIGRATED', code: 'SCHEDULE_NOT_MIGRATED', target: 'catalog_import_task.triggerType=MANUAL', message: 'legacy planning is not migrated' },
  { element: 'INDET:Separator', status: 'WARNING', code: 'SEPARATOR_NOT_DECLARED', target: 'revision.structureDelimiter=;', message: 'no separator' },
  BLOCKING_ITEM,
  { element: 'PSIMPDEF#40', status: 'WARNING', code: 'LEGACY_CALC_8_NOT_MIGRATED', target: null, message: 'CALC 8 is not supported yet' },
  { element: 'PSIMPDEF#41', status: 'WARNING', code: 'SOMETHING_FROM_THE_FUTURE', target: null, message: 'a brand new server message' },
];

export function report(overrides: Partial<LegacyConfigReport> = {}): LegacyConfigReport {
  return {
    receiptId: 7,
    contentHash: 'a'.repeat(64),
    receiptStatus: 'RECEIVED',
    outcome: 'BLOCKED',
    planned: {
      sourceOrganisation: { action: 'REUSE', id: 3, code: '1042', name: 'Kramp NV' },
      template: { action: 'CREATE', id: null, code: 'VROOAM2-SJ', revisionId: null },
      definition: {
        action: 'CREATE',
        id: null,
        code: 'VROOAM2',
        name: 'VROOAM catalogus CSV',
        revisionAction: 'CREATE',
        basedOnRevision: null,
        revisionId: null,
      },
      link: {
        action: 'CREATE',
        id: null,
        code: 'LEGACY-KRAMP_ART',
        libraryCode: null,
        libraryCandidates: [
          { scopeKey: 'LIB1', catalogName: 'Kramp hoofdcatalogus', legacyNumber: '11', status: 'ACTIVE' },
          { scopeKey: 'LIB2', catalogName: 'Kramp aanvullend', legacyNumber: '13', status: 'ACTIVE' },
        ],
      },
      task: { action: 'CREATE', id: null, name: 'Legacy KRAMP_ART' },
      credential: { credentialRef: '11111111-2222-3333-4444-555555555555', secretOutcome: 'STORED' },
    },
    sharedWith: ['KRAMP_PRIJS'],
    items: MIXED_ITEMS,
    prodisReference: 'VERIFIED',
    ...overrides,
  };
}

/** Een toepasbaar rapport (geen blokkerend punt). */
export function applicableReport(): LegacyConfigReport {
  const base = report();
  return {
    ...base,
    outcome: 'APPLICABLE',
    planned: { ...base.planned, link: { ...base.planned.link!, libraryCode: 'LIB2', libraryCandidates: [] } },
    items: MIXED_ITEMS.filter((item) => item.status !== 'BLOCKING'),
  };
}

export function preview(reportValue: LegacyConfigReport, previewFingerprint = 'fp-1'): LegacyPreviewResult {
  return { report: reportValue, previewFingerprint };
}

export type Call = { url: string; method: string; body: unknown };

/** Registreert elk verzoek en laat een handler het antwoord bepalen; een onbekend pad is een testfout. */
export function mockApi(handler: (call: Call) => Response): { calls: Call[]; reads: (part: string) => Call[]; writes: () => Call[] } {
  const calls: Call[] = [];
  global.fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const call: Call = {
      url,
      method: (init?.method ?? 'GET').toUpperCase(),
      body: init?.body === undefined ? undefined : JSON.parse(String(init.body)),
    };
    calls.push(call);
    return Promise.resolve(handler(call));
  }) as unknown as typeof fetch;
  return {
    calls,
    reads: (part) => calls.filter((call) => call.method === 'GET' && call.url.includes(part)),
    writes: () => calls.filter((call) => call.method !== 'GET'),
  };
}
