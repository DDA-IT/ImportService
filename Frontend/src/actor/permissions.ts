/**
 * Rechten in de UI (5-PERM, `docs/design/fase5-perm-design.md` §1 en §4).
 *
 * Business rule: schrijfacties vragen `MANAGE` (upload, hervatten, bundel aanmaken, batches
 * toevoegen/verwijderen) of `APPROVE` (accept-baseline, beslissen, bevriezen, annuleren). Een ontbrekend recht
 * schakelt de knop uit MÉT reden (nooit verborgen). De UI is enkel een spiegel: de server weigert elke actie
 * zonder recht toch met 403 `PERMISSION_DENIED`, en de UI houdt geen eigen hiërarchie bij (`/me` levert de
 * effectieve set).
 */

import { PERMISSION_APPROVE, PERMISSION_MANAGE, PERMISSION_READ, type Permission } from '../api/types';
import { useActor } from './ActorContext';
import { ALLOWED, denied, type Gate } from './gate';

const LABELS: Record<Permission, string> = {
  [PERMISSION_READ]: 'Lezen',
  [PERMISSION_MANAGE]: 'Beheren',
  [PERMISSION_APPROVE]: 'Goedkeuren',
};

/** Vaste reden per recht, bv. "U heeft het recht 'Goedkeuren' (`catalogImport.approve`) niet." */
export function missingPermissionReason(permission: Permission): string {
  return `U heeft het recht '${LABELS[permission]}' (${permission}) niet.`;
}

/**
 * Poort voor één recht. Dezelfde `Gate` als de toestandspoorten, zodat ze samengesteld kan worden:
 * de rechtcheck komt eerst (zoals de server: recht vóór toestand).
 */
export function usePermissionGate(permission: Permission): Gate {
  const { can } = useActor();
  return can(permission) ? ALLOWED : denied(missingPermissionReason(permission));
}

/** Een ontbrekend recht wint van de toestandspoort (recht eerst); heeft de gebruiker het recht, dan geldt `gate`. */
export function withPermission<G extends Gate>(permission: Gate, gate: G): Gate | G {
  return permission.allowed ? gate : permission;
}
