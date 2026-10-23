/**
 * CD-F — de rij "Standaarden van koppelingen overschrijven bij legacy-overname" in de Gegevens van het
 * importdefinitiedetail (beslissingslog 2026-10-23 "Vlag 'standaarden overschrijven' op de importdefinitie"). Toont Aan of
 * Uit; "Wijzigen" (recht `MANAGE`, anders uitgeschakeld mét reden) vraagt eerst een bevestiging. Staat de vlag aan, dan
 * waarschuwt de rij dat een overname uit legacy btw-code en eenheid van alle koppelingen overschrijft, ook manueel
 * ingestelde waarden; bij meerdere koppelingen staat het aantal erbij. De vlag raakt geen versie en geen gereedheid.
 */
import { useState } from 'react';
import { usePermissionGate } from '../../actor/permissions.ts';
import { setOverwriteCreationDefaults } from '../../api/creationDefaults.ts';
import { PERMISSION_MANAGE } from '../../api/types.ts';
import button from '../../components/Button.module.css';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { OVERWRITE_FLAG_TEXT as TEXT } from '../../terms/creationDefaults.ts';
import styles from '../setup/SetupScreens.module.css';

export function OverwriteFlagRow({
  definitionId,
  enabled,
  linkCount,
  onChanged,
}: {
  definitionId: number;
  enabled: boolean;
  /** Het aantal koppelingen onder de importdefinitie als dat bekend is; boven 1 staat het in de waarschuwing. */
  linkCount: number | null;
  onChanged: () => void;
}) {
  const manageGate = usePermissionGate(PERMISSION_MANAGE);
  const [confirming, setConfirming] = useState(false);
  const { execute, pending, error, reset } = useAction((next: boolean) =>
    setOverwriteCreationDefaults(definitionId, next),
  );

  async function confirm() {
    const result = await execute(!enabled);
    if (result !== undefined) {
      setConfirming(false);
      onChanged();
    }
  }

  return (
    <>
      <dt>{TEXT.label}</dt>
      <dd data-testid="definition-overwrite-flag">
        <span data-testid="definition-overwrite-value">{enabled ? TEXT.on : TEXT.off}</span>{' '}
        <button
          type="button"
          className={`${button.secondary} ${button.small}`}
          disabled={!manageGate.allowed}
          title={manageGate.allowed ? undefined : manageGate.reason}
          data-testid="definition-overwrite-edit"
          onClick={() => {
            reset();
            setConfirming(true);
          }}
        >
          {TEXT.change}
        </button>
        {enabled && (
          <p className={styles.rowMeta} role="note" data-testid="definition-overwrite-warning">
            {linkCount !== null && linkCount > 1 ? TEXT.warningShared(linkCount) : TEXT.warning}
          </p>
        )}
      </dd>
      <ConfirmDialog
        open={confirming}
        title={enabled ? TEXT.confirmOffTitle : TEXT.confirmOnTitle}
        body={
          <>
            {enabled ? TEXT.confirmOffBody : TEXT.confirmOnBody}
            {!enabled && <p>{linkCount !== null && linkCount > 1 ? TEXT.warningShared(linkCount) : TEXT.warning}</p>}
          </>
        }
        reasonRequirement="none"
        confirmLabel={enabled ? TEXT.confirmOff : TEXT.confirmOn}
        variant={enabled ? 'primary' : 'danger'}
        pending={pending}
        error={error !== null ? <ErrorBanner error={error} /> : undefined}
        onConfirm={() => void confirm()}
        onCancel={() => {
          reset();
          setConfirming(false);
        }}
      />
    </>
  );
}
