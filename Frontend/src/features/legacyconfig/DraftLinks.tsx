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
  const entries: Array<{ key: string; label: string; href: string }> = [];
  if (definition !== null && definition.id !== null && definition.revisionId !== null) {
    entries.push({
      key: 'definition',
      label: `Gedeelde beschrijving ${definition.code}`,
      href: revisionDetailHref(definition.id, definition.revisionId),
    });
  }
  if (template !== null && template.id !== null && template.revisionId !== null) {
    entries.push({
      key: 'template',
      label: `Sjabloon ${template.code}`,
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
