/**
 * F4 — de gereedheid van koppelingen zoals de server ze controleert (`GET /import-links/readiness`, B4): een leeshook
 * voor een lijst koppelingen en de badge "Klaar" / "Niet klaar: <eerste probleem in woorden>". De badge gebruikt het
 * Nederlandse woord bij de controle-code (`describeReadinessCheck`); een code staat nooit als hoofdtekst.
 *
 * Een mislukte of nog lopende controle blokkeert nooit: de hook geeft dan `null` en elk scherm valt terug op wat het
 * zonder de controle toonde.
 */
import { useQuery } from '../../hooks/useQuery.ts';
import * as importLinksApi from '../../api/importLinks.ts';
import type { LinkReadinessSummary } from '../../api/types.ts';
import { ToneBadge } from '../../components/StatusBadge.tsx';
import { LINK_READINESS_TEXT } from '../../terms/linkReadiness.ts';
import { describeReadinessCheck } from './check/linkCheck.ts';

/** De uitkomsten per koppeling-id, of `null` zolang ze laden, mislukten of er niets te controleren valt. */
export function useLinkReadiness(linkIds: readonly number[], scope: string): ReadonlyMap<number, LinkReadinessSummary> | null {
  const ids = [...new Set(linkIds)].sort((a, b) => a - b);
  const result = useQuery(`link-readiness:${scope}:${ids.join(',')}`, async (signal) => {
    const summaries = await importLinksApi.listLinkReadiness(ids, signal);
    return new Map(summaries.map((summary) => [summary.linkId, summary] as const));
  });
  return ids.length === 0 || result.error !== null ? null : result.data;
}

/** Het Nederlandse woord van het eerste probleem, of `null` zonder probleem. */
export function firstProblemWord(summary: LinkReadinessSummary): string | null {
  const first = summary.problems[0];
  return first === undefined ? null : describeReadinessCheck(first.code).label;
}

export function LinkReadinessBadge({ summary, testId }: { summary: LinkReadinessSummary; testId?: string }) {
  if (summary.ready) {
    return (
      <span title={LINK_READINESS_TEXT.hint} data-testid={testId}>
        <ToneBadge label={LINK_READINESS_TEXT.ready} tone="ready" />
      </span>
    );
  }
  const problem = firstProblemWord(summary);
  return (
    <span title={LINK_READINESS_TEXT.hint} data-testid={testId}>
      <ToneBadge
        label={problem === null ? LINK_READINESS_TEXT.notReadyUnknown : LINK_READINESS_TEXT.notReady(problem)}
        tone="awaiting"
      />
    </span>
  );
}
