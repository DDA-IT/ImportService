import { request, type APIRequestContext, type APIResponse } from '@playwright/test';

/** Directe backend (niet via de Vite-proxy): de helpers praten rechtstreeks met poort 8081. */
export const BACKEND = process.env.E2E_BACKEND_URL ?? 'http://localhost:8081';
const API = `${BACKEND}/api/catalog-import`;
const SETUP = `${API}/setup`;

export const ACTOR = 'e2e@example.test';

/** Unieke, base36-code: tijdstempel + willekeur. De database is persistent en gedeeld. */
export function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`.toUpperCase();
}

export async function newApi(): Promise<APIRequestContext> {
  return request.newContext();
}

async function json<T>(res: APIResponse, what: string): Promise<T> {
  if (!res.ok()) {
    throw new Error(`${what} faalde: HTTP ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as T;
}

async function post<T>(api: APIRequestContext, url: string, data: unknown, what: string): Promise<T> {
  return json<T>(await api.post(url, { data }), what);
}

export type Chain = { sfx: string; supplierCode: string; linkId: number; linkCode: string; taskId: number };

/** Bronorganisatie -> definitie -> revisie -> mapping -> activeren -> koppeling -> MANUAL-taak (setup-API, profiel demo). */
export async function createChain(api: APIRequestContext, sfx = uniqueSuffix()): Promise<Chain> {
  const oc = `E2E${sfx}`;
  await post(api, `${SETUP}/source-organisations`, { code: oc, name: `E2E ${sfx}`, type: 'SUPPLIER' }, 'bronorganisatie');
  const def = await post<{ id: number }>(
    api,
    `${SETUP}/definitions`,
    { sourceOrganisationCode: oc, code: `${oc}-CSV`, name: `E2E ${sfx}`, usageType: 'OWN_DEFINITION' },
    'definitie',
  );
  const rev = await post<{ id: number }>(
    api,
    `${SETUP}/definitions/${def.id}/revisions`,
    {
      delimiter: ';',
      hasHeader: true,
      identityProfileKind: 'THREE_PART',
      supplierField: 'leverancier',
      supplierGroupField: 'groep',
      supplierReferenceField: 'referentie',
      basePriceField: 'prijs',
      descriptionField: 'omschrijving',
      currencyField: 'valuta',
      canonicalisationVersion: 2,
      creationThresholdSharePercent: 10,
      maxCriticalSharePercent: 25,
    },
    'revisie',
  );
  await post(api, `${SETUP}/revisions/${rev.id}/mappings`, { targetFieldCode: 'EAN', sourceReference: 'ean', sequenceNumber: 1 }, 'mapping');
  await post(api, `${SETUP}/revisions/${rev.id}/activate`, { approvedBy: 'beheerder@example.test' }, 'activeren');
  const linkCode = `${oc}-LINK`;
  const link = await post<{ id: number }>(
    api,
    `${SETUP}/links`,
    { definitionId: def.id, code: linkCode, name: `E2E ${sfx}`, supplierCode: oc, libraryCode: 'PSARF012' },
    'koppeling',
  );
  const task = await post<{ id: number }>(api, `${SETUP}/tasks`, { linkId: link.id, name: `E2E manuele levering ${sfx}` }, 'taak');
  return { sfx, supplierCode: oc, linkId: link.id, linkCode, taskId: task.id };
}

/** Zelfde kolommen als scripts/scenario/levering-1.csv. Regel 6 heeft een onleesbare prijs (afgewezen). */
export function levering1Csv(): string {
  return [
    'leverancier;groep;referentie;omschrijving;prijs;valuta;ean',
    'SCN;BOOR;S-1;Boormachine 500W;149,50;EUR;5411234600011',
    'SCN;BOOR;S-2;Boormachine 750W;189,00;EUR;5411234600028',
    'SCN;ZAAG;S-3;Handzaag 450mm;24,90;EUR;5411234600035',
    'SCN;ZAAG;S-4;Cirkelzaag 1200W;99,00;EUR;5411234600042',
    'SCN;HAMER;S-5;Klauwhamer 500g;12,50;EUR;5411234600059',
    'SCN;HAMER;S-6;Moker 2kg;abc;EUR;5411234600066',
    'SCN;TANG;S-7;Combinatietang 180mm;15,75;EUR;5411234600073',
    '',
  ].join('\n');
}

export type Upload = { batchId: number; raw: Record<string, unknown> };

export async function uploadCsv(api: APIRequestContext, taskId: number, csv: string, reference: string): Promise<Upload> {
  const res = await api.post(`${API}/tasks/${taskId}/deliveries`, {
    multipart: {
      file: { name: 'levering.csv', mimeType: 'text/csv', buffer: Buffer.from(csv, 'utf-8') },
      deliveryReference: reference,
      uploadedBy: ACTOR,
    },
  });
  const raw = await json<Record<string, unknown>>(res, 'upload');
  const batchId = Number(raw.batchId);
  if (!Number.isFinite(batchId)) throw new Error(`upload gaf geen batchId: ${JSON.stringify(raw)}`);
  return { batchId, raw };
}

export type BatchDto = {
  status: string;
  validationResult: string | null;
  rawRecordCount: number | null;
  validRecordCount: number | null;
  rejectedRecordCount: number | null;
  [k: string]: unknown;
};

export async function getBatch(api: APIRequestContext, batchId: number): Promise<BatchDto> {
  return json<BatchDto>(await api.get(`${API}/batches/${batchId}`), 'batch ophalen');
}

export async function acceptBaseline(api: APIRequestContext, batchId: number): Promise<void> {
  await post(api, `${API}/batches/${batchId}/accept-baseline`, { acceptedBy: ACTOR, reason: 'E2E: eerste levering als baseline' }, 'accept-baseline');
}

export async function createBundle(api: APIRequestContext, reference: string): Promise<{ id: number }> {
  const raw = await post<Record<string, unknown>>(
    api,
    `${API}/bundles`,
    { bundleReference: reference, description: `E2E ${reference}`, targetMode: 'SIMULATION', createdBy: ACTOR },
    'bundel aanmaken',
  );
  const id = Number(raw.id ?? raw.bundleId);
  if (!Number.isFinite(id)) throw new Error(`bundel-respons zonder id: ${JSON.stringify(raw)}`);
  return { id };
}

export async function addBatchToBundle(api: APIRequestContext, bundleId: number, batchId: number): Promise<void> {
  await post(api, `${API}/bundles/${bundleId}/batches`, { batchIds: [batchId], addedBy: ACTOR }, 'batch aan bundel toevoegen');
}

export type MutationDto = {
  id: number;
  status: string;
  actionType: string;
  identityHash: string | null;
  statusReason: string | null;
  decisionId: number | null;
};

export async function bundleMutations(api: APIRequestContext, bundleId: number, query = ''): Promise<MutationDto[]> {
  const page = await json<{ content: MutationDto[] }>(
    await api.get(`${API}/bundles/${bundleId}/mutations?size=200${query}`),
    'bundelmutaties',
  );
  return page.content;
}

export async function batchSummary(api: APIRequestContext): Promise<{
  total: number;
  byValidationResult: { validationResult: string | null; count: number }[];
}> {
  return json(await api.get(`${API}/batches/summary`), 'summary');
}

/**
 * Volledige keten voor de bundeltests: keten -> levering-1 -> accept-baseline -> delta-levering (levering-2: een
 * gewijzigde prijs en een nieuwe regel) -> bundel met de delta-batch.
 */
export async function createBundleScenario(api: APIRequestContext) {
  const chain = await createChain(api);
  const first = await uploadCsv(api, chain.taskId, levering1Csv(), `E2E-${chain.sfx}-1`);
  await acceptBaseline(api, first.batchId);
  const delta = levering1Csv()
    .replace('149,50', '159,50')
    .concat('SCN;TANG;S-8;Buigtang 160mm;17,25;EUR;5411234600080\n');
  const second = await uploadCsv(api, chain.taskId, delta, `E2E-${chain.sfx}-2`);
  const bundleRef = `E2E-BUNDLE-${chain.sfx}`;
  const bundle = await createBundle(api, bundleRef);
  await addBatchToBundle(api, bundle.id, second.batchId);
  return { chain, first, second, bundleId: bundle.id, bundleRef };
}
