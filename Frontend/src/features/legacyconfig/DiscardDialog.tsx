/**
 * Verwerpen van een ontvangst (G1a). Enkel een ontvangst met status `RECEIVED` kan verworpen worden; de reden is
 * verplicht en blijft bewaard. Er wordt niets aangemaakt of gewijzigd behalve de ontvangst zelf.
 *
 * Foutafhandeling (alles via `ErrorBanner` en `errors/codes.ts`, de dialoog blijft open):
 * - 409 `LEGACY_RECEIPT_NOT_APPLICABLE`: de ouder laadt de ontvangst opnieuw (`onNotApplicable`).
 * - 403, 400 en alle andere fouten: enkel de melding.
 */

import { ApiError } from '../../api/http.ts';
import * as legacyConfigApi from '../../api/legacyConfig.ts';
import type { LegacyDiscardResult, LegacyReceiptRow } from '../../api/types.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import styles from './LegacyConfig.module.css';

export type DiscardDialogProps = {
  receipt: LegacyReceiptRow;
  onClose: () => void;
  onDiscarded: (result: LegacyDiscardResult) => void;
  /** De ontvangst is niet meer open: laad ze opnieuw. */
  onNotApplicable: () => void;
};

export function DiscardDialog({ receipt, onClose, onDiscarded, onNotApplicable }: DiscardDialogProps) {
  const runner = useAction(async (reason: string) => {
    try {
      return await legacyConfigApi.discardLegacyReceipt(receipt.id, { reason });
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409 && cause.code === 'LEGACY_RECEIPT_NOT_APPLICABLE') {
        onNotApplicable();
      }
      throw cause;
    }
  });

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    if (input.reason === null) {
      return;
    }
    const result = await runner.execute(input.reason);
    if (result !== undefined) {
      onDiscarded(result);
    }
  }

  return (
    <ConfirmDialog
      open
      title={`Ontvangst van interface ${receipt.interfaceCode} verwerpen`}
      body={
        <div className={styles.dialogBody}>
          <p>
            U verwerpt de ontvangst van interface <strong>{receipt.interfaceCode}</strong> uit {receipt.originEnvironment} /{' '}
            {receipt.originDossier}. Ze wordt dan niet overgenomen.
          </p>
          <WhatIsThis>
            Verwerpen wijzigt niets in de inrichting. Stuurt de datasync dezelfde configuratie opnieuw, dan blijft deze
            ontvangst verworpen; een gewijzigde configuratie komt als nieuwe ontvangst binnen.
          </WhatIsThis>
        </div>
      }
      reasonRequirement="required"
      confirmLabel="Ontvangst verwerpen"
      cancelLabel="Sluiten zonder te verwerpen"
      pending={runner.pending}
      error={runner.error !== null ? <ErrorBanner error={runner.error} /> : undefined}
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}
