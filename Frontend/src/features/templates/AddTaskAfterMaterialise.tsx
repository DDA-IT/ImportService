/**
 * NT-7 — na een geslaagde materialisatie op de pagina Sjablonen: "Taak toevoegen". Materialiseren maakt nooit een
 * taak (V3 = a); zonder taak kan er geen levering opgeladen worden. De knop opent het stappenplan
 * (`/setup/new?organisationId&definitionId&linkId`) bij de taakstap.
 *
 * De gematerialiseerde beschrijving blijft van de organisatie van het sjabloon (V4 = a). Het antwoord van de
 * materialisatie draagt die organisatie niet, dus wordt ze pas hier — na het slagen — uit de sjabloonlijst
 * nagelezen (`GET /templates`): de pagina laadt er verder niets extra voor.
 */
import { Link } from 'react-router-dom';
import { usePermissionGate } from '../../actor/permissions.ts';
import { PERMISSION_MANAGE } from '../../api/types.ts';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useQuery } from '../../hooks/useQuery.ts';
import { loadAllTemplates } from '../setup/wizard/lookup.ts';
import { wizardHref } from '../setup/wizard/wizardTypes.ts';
import styles from './MaterialiseForm.module.css';

export type AddTaskAfterMaterialiseProps = {
  /** Het sjabloon waaruit gematerialiseerd werd. */
  templateId: number;
  /** De (nieuwe of hergebruikte) beschrijving en de nieuwe koppeling. */
  definitionId: number;
  linkId: number;
  linkCode: string;
};

export function AddTaskAfterMaterialise({ templateId, definitionId, linkId, linkCode }: AddTaskAfterMaterialiseProps) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const templates = useQuery(`template-organisation:${templateId}`, (signal) => loadAllTemplates(signal));
  const organisationId = templates.data?.find((row) => row.id === templateId)?.sourceOrganisationId ?? null;

  return (
    <div className={styles.notice} role="status" data-testid="add-task-after-materialise">
      <p>
        De koppeling <strong>{linkCode}</strong> heeft nog geen taak. Zonder taak kan er geen levering opgeladen
        worden.
      </p>
      {templates.error !== null && <ErrorBanner error={templates.error} />}
      {templates.data !== null && organisationId === null && (
        <p className={styles.validationError}>
          De organisatie van dit sjabloon is niet teruggevonden; maak de taak via Inrichting.
        </p>
      )}
      {!manageGate.allowed ? (
        <button type="button" className={styles.submit} disabled title={manageGate.reason} data-testid="add-task-button">
          Taak toevoegen
        </button>
      ) : organisationId !== null ? (
        <Link
          className={styles.submit}
          to={wizardHref({ organisationId, definitionId, linkId })}
          data-testid="add-task-button"
        >
          Taak toevoegen
        </Link>
      ) : (
        <button type="button" className={styles.submit} disabled data-testid="add-task-button">
          Taak toevoegen
        </button>
      )}
      {!manageGate.allowed && <p className={styles.validationError}>{manageGate.reason}</p>}
    </div>
  );
}
