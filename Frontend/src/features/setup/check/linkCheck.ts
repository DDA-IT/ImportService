/**
 * NT-10 — hulpfuncties van het scherm "Controleren" (`/setup/links/:linkId/check`): het adres, het opzoeken van
 * koppeling/beschrijving/versies via de bestaande vlagloze leeslijsten (er is geen "zoek op id"-endpoint, zelfde
 * aanpak als het stappenplan, NT-6), de keuze van de te controleren versie en het Nederlandse woord bij een code.
 *
 * Een code wordt nooit als hoofdtekst getoond (V7): een onbekende code krijgt een algemene Nederlandse zin, de code
 * zelf staat enkel onder "Technische details (voor support)".
 */
import * as importLinksApi from '../../../api/importLinks.ts';
import * as setupApi from '../../../api/setup.ts';
import type { DefinitionRow, ImportLinkRow, RevisionRow } from '../../../api/types.ts';
import { DICTIONARY, type TermEntry } from '../../../terms/index.ts';
import { issueBaseCode } from '../../../terms/IssueCodeTerm.tsx';
import { loadAll, loadLinksOf, loadRevisionsOf, LOOKUP_PAGE_SIZE } from '../wizard/lookup.ts';

/** Het adres van het scherm; `definitionId` versnelt het opzoeken, `revisionId` kiest de versie. */
export function linkCheckHref(ids: { linkId: number; definitionId?: number | null; revisionId?: number | null }): string {
  const params = new URLSearchParams();
  if (typeof ids.definitionId === 'number') {
    params.set('definitionId', String(ids.definitionId));
  }
  if (typeof ids.revisionId === 'number') {
    params.set('revisionId', String(ids.revisionId));
  }
  const query = params.toString();
  return `/setup/links/${ids.linkId}/check${query === '' ? '' : `?${query}`}`;
}

export type CheckContext = {
  /** `null` = de koppeling bestaat niet (meer) of hoort niet bij de meegegeven beschrijving. */
  link: ImportLinkRow | null;
  /** `null` als de beschrijving niet teruggevonden werd; enkel nodig voor de naam en de organisatie. */
  definition: DefinitionRow | null;
  revisions: RevisionRow[];
};

/**
 * Zoekt de koppeling, haar beschrijving en alle versies op. Met `definitionHint` wordt enkel onder die beschrijving
 * gezocht; een koppeling die daar niet onder hangt, telt als "niet gevonden" (nooit stil ergens anders gezocht).
 */
export async function loadCheckContext(
  linkId: number,
  definitionHint: number | null,
  signal?: AbortSignal,
): Promise<CheckContext> {
  const links =
    definitionHint !== null
      ? await loadLinksOf(definitionHint, signal)
      : await loadAll((page) => importLinksApi.listImportLinks({ page, size: LOOKUP_PAGE_SIZE }, signal));
  const link = links.find((row) => row.id === linkId) ?? null;
  if (link === null) {
    return { link: null, definition: null, revisions: [] };
  }
  const [definitions, revisions] = await Promise.all([
    loadAll((page) => setupApi.listDefinitions({ page, size: LOOKUP_PAGE_SIZE }, signal)),
    loadRevisionsOf(link.importDefinitionId, signal),
  ]);
  const definition = definitions.find((row) => row.id === link.importDefinitionId) ?? null;
  return { link, definition, revisions };
}

export type RevisionChoice = {
  chosen: RevisionRow | null;
  /** De versies waartussen gekozen kan worden: concepten en de actieve versie, hoogste nummer eerst. */
  choices: RevisionRow[];
  /** Een `revisionId` uit de adresbalk die niet bij deze koppeling hoort; nooit stil vervangen zonder melding. */
  requestedNotFound: boolean;
};

/**
 * Welke versie gecontroleerd wordt: de gevraagde (`?revisionId=`), anders de hoogst genummerde conceptversie,
 * anders de actieve versie. Zonder concept en zonder actieve versie is er niets te controleren.
 */
export function chooseRevision(revisions: readonly RevisionRow[], requestedId: number | null): RevisionChoice {
  const byNumberDesc = [...revisions].sort((a, b) => b.revisionNumber - a.revisionNumber);
  const choices = byNumberDesc.filter((row) => row.status === 'DRAFT' || row.status === 'ACTIVE');
  const fallback =
    byNumberDesc.find((row) => row.status === 'DRAFT') ?? byNumberDesc.find((row) => row.status === 'ACTIVE') ?? null;
  if (requestedId !== null) {
    const requested = revisions.find((row) => row.id === requestedId) ?? null;
    if (requested !== null) {
      const withRequested = choices.some((row) => row.id === requested.id) ? choices : [requested, ...choices];
      return { chosen: requested, choices: withRequested, requestedNotFound: false };
    }
    return { chosen: fallback, choices, requestedNotFound: true };
  }
  return { chosen: fallback, choices, requestedNotFound: false };
}

function lookup(domain: 'issueCode' | 'readinessCheck', code: string): TermEntry | null {
  const entries = DICTIONARY[domain];
  return Object.prototype.hasOwnProperty.call(entries, code) ? (entries[code] ?? null) : null;
}

const CONFIG_FALLBACK: TermEntry = DICTIONARY.readinessCheck.CONFIG_INVALID!;

const UNKNOWN_FALLBACK: TermEntry = {
  label: 'Ander probleem',
  uitleg:
    'Voor deze vaststelling bestaat nog geen Nederlandse uitleg; de technische code staat onder "Technische details".',
};

/** Het Nederlandse woord + uitleg bij een vaststelling van de screening of de proefinlezing; nooit de code zelf. */
export function describeIssueCode(code: string | null | undefined): TermEntry {
  if (code === null || code === undefined || code === '') {
    return UNKNOWN_FALLBACK;
  }
  const base = issueBaseCode(code);
  const known = lookup('issueCode', base);
  if (known !== null) {
    return known;
  }
  return base.startsWith('CONFIG') ? CONFIG_FALLBACK : UNKNOWN_FALLBACK;
}

/** Het Nederlandse woord + uitleg bij een regel van de checklist; valt terug op de vaststellingen (CONFIG-fouten). */
export function describeReadinessCheck(code: string): TermEntry {
  return lookup('readinessCheck', code) ?? describeIssueCode(code);
}
