/**
 * De acties die het dashboard Leveranciers en het leveranciersdetail delen: "Verder inrichten" (NT-6), "Taak toevoegen"
 * (NT-7) en "Leverancier toevoegen". Alle drie vragen het recht Beheren; zonder dat recht staan ze uitgeschakeld mét
 * reden, nooit verborgen (5-PERM).
 */

import { Link } from 'react-router-dom';
import { usePermissionGate } from '../../actor/permissions.ts';
import { PERMISSION_MANAGE } from '../../api/types.ts';
import { SCREEN } from '../../terms/navigation.ts';
import button from '../../components/Button.module.css';
import styles from './SetupScreens.module.css';

/**
 * NT-6 — "Verder inrichten": opent het stappenplan bij de volgende stap voor een onderdeel zonder vervolg
 * (organisatie zonder importdefinitie, importdefinitie zonder koppeling, koppeling zonder taak).
 */
export function ContinueSetupAction({ href, testId, label = 'Verder inrichten' }: { href: string; testId: string; label?: string }) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  if (!manageGate.allowed) {
    return (
      <button type="button" className={`${button.secondary} ${button.small}`} disabled title={manageGate.reason} data-testid={testId}>
        {label}
      </button>
    );
  }
  return (
    <Link className={`${button.secondary} ${button.small}`} to={href} data-testid={testId}>
      {label}
    </Link>
  );
}

/** NT-7 — "Taak toevoegen": opent het stappenplan bij de taakstap van deze koppeling. */
export function AddTaskAction({ href, testId, label = 'Taak toevoegen' }: { href: string; testId: string; label?: string }) {
  return <ContinueSetupAction href={href} testId={testId} label={label} />;
}

/** NT-6 — de knop naar het stappenplan, met de reden naast de knop als het recht Beheren ontbreekt. */
export function NewSupplierButton() {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  if (!manageGate.allowed) {
    return (
      <div className={styles.headActions}>
        <button
          type="button"
          className={button.primary}
          disabled
          title={manageGate.reason}
          aria-describedby="new-supplier-blocked"
          data-testid="new-supplier-button"
        >
          {SCREEN.leverancierToevoegen}
        </button>
        <span id="new-supplier-blocked" className={styles.blockedReason} data-testid="new-supplier-blocked">
          {manageGate.reason}
        </span>
      </div>
    );
  }
  return (
    <div className={styles.headActions}>
      <Link className={button.primary} to="/setup/new" data-testid="new-supplier-button">
        {SCREEN.leverancierToevoegen}
      </Link>
    </div>
  );
}
