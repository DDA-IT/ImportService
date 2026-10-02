/**
 * De ene poort (`Gate`) voor "mag deze actie?": toegestaan, of geweigerd met een Nederlandse `reason`. `code` is de
 * stabiele technische code van de weigering (V7: altijd opvraagbaar); ze staat nooit in `reason`.
 */
export type Gate = { allowed: true } | { allowed: false; reason: string; code?: string };

export const ALLOWED: Gate = { allowed: true };

export function denied(reason: string, code?: string): Gate {
  return code === undefined ? { allowed: false, reason } : { allowed: false, reason, code };
}

/**
 * De tooltip (`title`) van een uitgeschakelde knop: de Nederlandse reden, met de technische code klein erbij
 * wanneer die er is ("(technische code: X)"). Zonder weigering (`allowed`) geen tooltip.
 */
export function gateTitle(gate: Gate): string | undefined {
  if (gate.allowed) {
    return undefined;
  }
  return gate.code === undefined ? gate.reason : `${gate.reason} (technische code: ${gate.code})`;
}
