/**
 * "Gebaseerd op herbruikbare importdefinitie <naam>" bij een eigen importdefinitie (R2, mock leveranciersdetail,
 * tab Versies). De API heeft geen veld "gebaseerd op" op een definitie; wel levert `GET /templates/{id}/materialisations`
 * per herbruikbare importdefinitie welke eigen definities er uit voortkwamen. We zoeken enkel onder de herbruikbare
 * importdefinities van dezelfde leverancier of vereniging (een importdefinitie bedient enkel de eigen organisatie, V4 = a),
 * dus hoogstens een handvol verzoeken, en enkel als zo'n herbruikbare bestaat. Die verzoeken staan achter de inrichtings-API-vlag:
 * is die uit of mislukt het opzoeken, dan toont dit niets (geen foutmelding voor een bijzaak).
 */

import { Link } from 'react-router-dom';
import * as templatesApi from '../../api/templates.ts';
import type { DefinitionRow } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { DEFINITIONS_TEXT } from '../../terms/importDefinitions.ts';
import { fetchAllPages } from './setupDashboardModel.ts';
import { definitionHref } from '../templates/definitionRoutes.ts';
import styles from './SetupScreens.module.css';

export function BasedOnReusable({ definitionId, reusables }: { definitionId: number; reusables: readonly DefinitionRow[] }) {
  const key = `based-on-reusable:${definitionId}:${reusables.map((reusable) => reusable.id).join(',')}`;
  const query = useQuery<DefinitionRow | null>(key, async (signal) => {
    for (const reusable of reusables) {
      const rows = await fetchAllPages(
        (page, size, s) => templatesApi.listMaterialisations(reusable.id, { page, size }, s),
        signal,
      );
      if (rows.some((row) => row.definitionId === definitionId)) {
        return reusable;
      }
    }
    return null;
  });
  if (reusables.length === 0 || query.data === null || query.error !== null) {
    return null;
  }
  return (
    <p className={styles.rowMeta} data-testid={`based-on-${definitionId}`}>
      {DEFINITIONS_TEXT.basedOn}{' '}
      <Link to={definitionHref(query.data.id)}>{query.data.name}</Link>
    </p>
  );
}
