/**
 * De tooltip (`title`) van een uitgeschakelde knop (NT-11c, V7): de Nederlandse reden, met de stabiele technische
 * code klein erbij wanneer die er is ("(technische code: X)"). Zonder weigering geen tooltip. Zelfde patroon als
 * `gateTitle` in `features/bundles/bundlePolicy.ts` (NT-11b); hier als los, klein hulpje voor de poorten van
 * behandelgevallen en inrichting, zodat die niet van het bundelscherm afhangen.
 */
export type GateLike = { allowed: true } | { allowed: false; reason: string; code?: string };

export function gateTitle(gate: GateLike): string | undefined {
  if (gate.allowed) {
    return undefined;
  }
  return gate.code === undefined ? gate.reason : `${gate.reason} (technische code: ${gate.code})`;
}
