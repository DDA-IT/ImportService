/**
 * De toestandspoorten van het schrijfdeel van scherm 1a (S1-F4,
 * `docs/design/revision-successor-design.md` §6). Pure functies, geen React, geen `api/` — spiegel van
 * `RevisionSuccessorService`/`SetupService`, **nooit** de bron van waarheid: een 409 van de server
 * (`REVISION_NOT_CLONEABLE`, `REVISION_NOT_EDITABLE`, `REVISION_NOT_ACTIVATABLE`,
 * `REVISION_DRAFT_ALREADY_EXISTS`, `REVISION_ACTIVATION_CONFLICT`) wordt altijd getoond, ook wanneer
 * deze poort "toegestaan" zei (patroon `bundlePolicy.ts`/`issueCasePolicy.ts`).
 *
 * Drie poorten, elk één regel uit het ontwerp:
 * - **klonen** (E2): alleen vanaf `ACTIVE` of `SUPERSEDED` (O2 — een SUPERSEDED bron geeft
 *   "terugdraaien naar een eerdere configuratie" gratis); `DRAFT` en overige statussen geweigerd.
 * - **bewerken/verwijderen** (E3/E4): alleen op een `DRAFT`. Een bevroren revisie wordt nooit
 *   bijgewerkt — daarvoor bestaat de opvolgrevisie.
 * - **activeren** (E5): alleen vanaf `DRAFT`.
 *
 * De extra regel "hoogstens één open DRAFT per definitie" (O1, 409 `REVISION_DRAFT_ALREADY_EXISTS`)
 * staat bewust NIET in deze poorten: ze hangt niet aan de revisie die de gebruiker aanklikt maar aan de
 * hele definitie, en de revisielijst van dit scherm is gepagineerd — een cliëntcontrole op een
 * onvolledige lijst zou een knop uitschakelen op grond van iets wat de UI niet kan weten. De server
 * blijft daar de enige beoordelaar van.
 *
 * NT-11c (V7): `reason` is gewoon Nederlands (statussen als woord uit het woordenboek); de stabiele servercode staat
 * apart in `code` en komt als "(technische code: X)" in de tooltip van de knop (`gateTitle`), nooit in de zichtbare
 * tekst.
 */

import type { RevisionStatus } from '../../api/types.ts';
import { ALLOWED, denied, type Gate } from '../../actor/gate.ts';
import { term } from '../../terms/index.ts';

function statusWord(status: RevisionStatus): string {
  return term('revisionStatus', status).label;
}

/** O2 — de twee statussen waaruit een opvolgrevisie gekloond mag worden. */
export const CLONEABLE_STATUSES: readonly RevisionStatus[] = ['ACTIVE', 'SUPERSEDED'];

/** E2: mag van deze revisie een opvolger gemaakt worden? */
export function successorGate(status: RevisionStatus): Gate {
  if (CLONEABLE_STATUSES.includes(status)) {
    return ALLOWED;
  }
  if (status === 'DRAFT') {
    return denied(
      'Kan niet: dit is al een concept. Een opvolger wordt gemaakt vanuit een actieve of vervangen versie; ' +
        'pas dit concept zelf aan.',
      'REVISION_NOT_CLONEABLE',
    );
  }
  return denied(
    `Kan niet: een opvolger maken kan alleen vanuit een actieve of vervangen versie (deze versie staat op ` +
      `"${statusWord(status)}").`,
    'REVISION_NOT_CLONEABLE',
  );
}

/** E3/E4: mag deze revisie gewijzigd worden (scalairen wijzigen, mapping/filter verwijderen)? */
export function editGate(status: RevisionStatus): Gate {
  if (status === 'DRAFT') {
    return ALLOWED;
  }
  return denied(
    `Kan niet: alleen een concept is aan te passen (deze versie staat op "${statusWord(status)}"). Maak een ` +
      'opvolger en pas die aan.',
    'REVISION_NOT_EDITABLE',
  );
}

/** E5: mag deze revisie geactiveerd worden? */
export function activateGate(status: RevisionStatus): Gate {
  if (status === 'DRAFT') {
    return ALLOWED;
  }
  if (status === 'ACTIVE') {
    return denied('Kan niet: deze versie is al actief.', 'REVISION_NOT_ACTIVATABLE');
  }
  return denied(
    `Kan niet: activeren kan alleen vanuit een concept (deze versie staat op "${statusWord(status)}").`,
    'REVISION_NOT_ACTIVATABLE',
  );
}
