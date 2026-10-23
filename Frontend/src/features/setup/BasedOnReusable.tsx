/**
 * "Gebaseerd op herbruikbare importdefinitie <naam>" bij een eigen importdefinitie (R2, mock leveranciersdetail,
 * tab Versies). Bron: `DefinitionRow.basedOnDefinitionId` uit de lijst van importdefinities (beslissingslog 2026-10-23
 * "API-uitbreiding voor de inrichtingsschermen", punt 5): geen extra verzoeken.
 *
 * Alleen als het veld ontbreekt (oudere server, `undefined`) valt dit terug op het vroegere opzoeken via
 * `GET /templates/{id}/materialisations` onder de herbruikbare importdefinities van dezelfde leverancier of vereniging
 * (een importdefinitie bedient enkel de eigen organisatie, V4 = a). Die verzoeken staan achter de inrichtings-API-vlag:
 * is die uit of mislukt het opzoeken, dan toont dit niets (geen foutmelding voor een bijzaak). `null` = bewust niet gebaseerd.
 */

import { Link } from 'react-router-dom';
import * as templatesApi from '../../api/templates.ts';
import type { DefinitionRow } from '../../api/types.ts';
import { useQuery } from '../../hooks/useQuery.ts';
import { DEFINITIONS_TEXT } from '../../terms/importDefinitions.ts';
import { fetchAllPages } from './setupDashboardModel.ts';
import { definitionHref } from '../templates/definitionRoutes.ts';
import styles from './SetupScreens.module.css';

export function BasedOnReusable({
  definitionId,
  basedOnDefinitionId,
  reusables,
}: {
  definitionId: number;
  /** `DefinitionRow.basedOnDefinitionId`; `undefined` = de server levert het veld niet (terugval op de materialisaties). */
  basedOnDefinitionId?: number | null;
  reusables: readonly DefinitionRow[];
}) {
  const fallback = basedOnDefinitionId === undefined;
  const key = `based-on-reusable:${definitionId}:${fallback ? reusables.map((reusable) => reusable.id).join(',') : 'field'}`;
  const query = useQuery<DefinitionRow | null>(key, async (signal) => {
    if (!fallback) {
      return null;
    }
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
  const basedOn = fallback
    ? query.error === null
      ? query.data
      : null
    : (reusables.find((reusable) => reusable.id === basedOnDefinitionId) ?? null);
  if (basedOn === null) {
    return null;
  }
  return (
    <p className={styles.rowMeta} data-testid={`based-on-${definitionId}`}>
      {DEFINITIONS_TEXT.basedOn}{' '}
      <Link to={definitionHref(basedOn.id)}>{basedOn.name}</Link>
    </p>
  );
}
