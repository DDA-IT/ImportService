/**
 * G1b — links naar de versie die de overname aanmaakte of gebruikte: de gedeelde beschrijving van het bestand en het
 * sjabloon. De id's komen uit het rapport van de overname (`planned.definition`, `planned.template`); ontbreekt een
 * versie-id (bv. een koppeling op een ongewijzigde beschrijving), dan staat er geen link.
 */

import { Link } from 'react-router-dom';
import type { LegacyPlanned } from '../../api/types.ts';
import { revisionDetailHref } from '../setup/wizard/wizardTypes.ts';
import styles from './LegacyConfig.module.css';

export function DraftLinks({ planned }: { planned: LegacyPlanned }) {
  const definition = planned.definition;
  const template = planned.template ?? null;
  const detach = planned.detach ?? null;
  const entries: Array<{ key: string; label: string; href: string }> = [];
  if (definition !== null && definition.id !== null && definition.revisionId !== null) {
    entries.push({
      key: 'definition',
      // Na loskoppelen is `planned.definition` de eigen beschrijving met de nieuwe conceptversie.
      label:
        detach !== null ? `Eigen importdefinitie ${definition.code}, nieuwe conceptversie` : `Gedeelde importdefinitie ${definition.code}`,
      href: revisionDetailHref(definition.id, definition.revisionId),
    });
  }
  if (detach !== null && definition !== null && definition.id !== null && detach.copiedRevisionId !== null) {
    entries.push({
      key: 'detached-copy',
      label: `Eigen importdefinitie ${definition.code}, overgenomen actieve versie`,
      href: revisionDetailHref(definition.id, detach.copiedRevisionId),
    });
  }
  if (template !== null && template.id !== null && template.revisionId !== null) {
    entries.push({
      key: 'template',
      label: `Importdefinitie ${template.code}`,
      href: revisionDetailHref(template.id, template.revisionId),
    });
  }
  if (entries.length === 0) {
    return null;
  }
  return (
    <div data-testid="legacy-draft-links">
      <h3 className={styles.subTitle}>Naar de aangemaakte versie</h3>
      <ul className={styles.plainList}>
        {entries.map((entry) => (
          <li key={entry.key}>
            <Link to={entry.href}>{entry.label}</Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
