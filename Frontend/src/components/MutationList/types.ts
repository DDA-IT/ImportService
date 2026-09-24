/**
 * Het contract van het herbruikbare mutatielijst-component, zie
 * `docs/design/frontend-scherm3-bundel-design.md` §11.1.
 *
 * Dit bestand bevat **alleen** types: het component kent de backend niet en krijgt alles via props
 * (§2 regel 2). De enige imports zijn contracttypes uit `api/types.ts` (type-only, verdwijnt bij het
 * compileren) — nooit een functie uit `api/`, zodat dezelfde component zowel op
 * `GET /bundles/{id}/mutations` als op `GET /batches/{id}/mutations` past (§11.3).
 *
 * Uitgebreid sinds §11.1 met de twee serverzijdige filters die de backend inmiddels levert:
 * `statusReason` (bouwstap C1) en `identityHash` (bouwstap C4, `docs/decisions.md` 2026-09-24). Er
 * blijft **geen** groepering aan de clientkant bestaan (§11.5): de wijzigingsgroep wordt uitsluitend
 * getoond door serverzijdig op `identityHash` te filteren.
 */

import type { ReactNode } from 'react';
import type { MutationActionType, MutationRow, MutationStatus, PageResult } from '../../api/types.ts';

/**
 * De toegepaste filter van de lijst, zonder paginering: alleen ondersteunde, niet-lege velden. Dit is
 * exact wat (samen met `page`/`size`) naar `source.fetchPage` gaat.
 */
export type MutationFilter = {
  status?: MutationStatus;
  batchId?: number;
  actionType?: MutationActionType;
  /** Exacte, hoofdlettergevoelige gelijkheid (C1); blanco = geen filter. */
  statusReason?: string;
  /** Hexadecimale identiteitshash, hoofdletterongevoelig (C4); de sleutel van de wijzigingsgroep. */
  identityHash?: string;
};

/** De filter- en paginatoestand die het component aan zijn bron doorgeeft. */
export type MutationQuery = MutationFilter & {
  /** 0-gebaseerd, zoals de backend. */
  page: number;
  size: number;
};

/**
 * Wat een ouder in de `toolbar`-slot van de lijst te zien krijgt (bouwstap F9, ontwerp §10.4 punt 1).
 *
 * `filter` is **hetzelfde object** waaruit de lijst haar eigen query bouwt — geen kopie, geen tweede
 * filterformulier. Een groepsactie die hierop steunt, kan dus geen andere selectie versturen dan wat de
 * lijst toont.
 *
 * @property listedCount `totalElements` van de lijst met precies deze filter, of `null` zolang die
 *   niet vaststaat (nog aan het laden, of het laatste verzoek faalde). Bewust nooit het getal van een
 *   vorige filter: `useQuery` houdt oude gegevens vast tijdens het herladen.
 * @property reload laadt de huidige pagina opnieuw (bv. na een groepsbeslissing)
 */
export type MutationListToolbarContext = {
  filter: MutationFilter;
  listedCount: number | null;
  reload: () => void;
};

/** De filtervelden die een bron kan ondersteunen; alleen deze verschijnen in de filterbalk. */
export const MUTATION_FILTERS = ['status', 'batchId', 'actionType', 'statusReason', 'identityHash'] as const;
export type MutationFilterName = (typeof MUTATION_FILTERS)[number];

/**
 * Waar de rijen vandaan komen. Het component weet niet of dat een bundel of een batch is en kent geen
 * HTTP-pad; het roept enkel `fetchPage` aan.
 *
 * @property key stabiele identiteit, bv. `bundle:42` of `batch:17` — wisselt de sleutel, dan wordt het
 *   lopende verzoek afgebroken en opnieuw geladen
 * @property supportedFilters filters die deze bron aanvaardt; een niet-ondersteund filter wordt niet
 *   getoond én nooit meegestuurd
 */
export type MutationSource = {
  key: string;
  fetchPage: (query: MutationQuery, signal: AbortSignal) => Promise<PageResult<MutationRow>>;
  supportedFilters: ReadonlyArray<MutationFilterName>;
};

/**
 * Mag deze actie op deze rij? Structureel gelijk aan `features/bundles/bundlePolicy.ts`, met opzet:
 * `components/` mag niet uit `features/` importeren (§2 regel 1), maar een poort uit `bundlePolicy`
 * past hier zonder omzetting in.
 */
export type Gate = { allowed: true } | { allowed: false; reason: string };

/** Eén actie per rij. Het component weet niet wat de actie doet, alleen hoe ze bevestigd wordt. */
export type MutationRowAction = {
  id: string;
  label: string;
  variant: 'primary' | 'danger' | 'neutral';
  reasonRequirement: (row: MutationRow) => 'required' | 'optional';
  gate: (row: MutationRow) => Gate;
  run: (row: MutationRow, input: { actor: string; reason: string | null }) => Promise<unknown>;
  confirmTitle?: (row: MutationRow) => string;
  confirmBody?: (row: MutationRow) => ReactNode;
};

export type MutationListProps = {
  source: MutationSource;
  /** Weggelaten = een zuivere leeslijst (zo gebruikt scherm (2) hetzelfde component, §11.4). */
  rowActions?: readonly MutationRowAction[];
  initialQuery?: Partial<MutationQuery>;
  emptyMessage?: string;
  /** Na een geslaagde rijactie: de ouder ververst zijn eigen gegevens (tellers, andere tabbladen). */
  onAfterAction?: () => void;
  /**
   * Optionele slot boven de tabel, gevoed met de **toegepaste** filter van deze lijst (bouwstap F9).
   * Het component weet niet wat de ouder ermee doet (§11.3); scherm (3) plaatst er de groepsactie.
   */
  toolbar?: (context: MutationListToolbarContext) => ReactNode;
};
