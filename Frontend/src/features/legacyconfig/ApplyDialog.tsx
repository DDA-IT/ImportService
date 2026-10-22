/**
 * Overnemen van een legacy-configuratie (APPLY). De dialoog toont de samenvatting van de controle vooraf waarop de
 * `previewFingerprint` rust, vraagt een verplichte reden en stuurt die fingerprint mee: de server neemt enkel over wat
 * de mens zag.
 *
 * Foutafhandeling (alles via `ErrorBanner` en `errors/codes.ts`, de dialoog blijft open):
 * - 409 `LEGACY_PREVIEW_STALE`: er is niets overgenomen; de ouder laadt de controle opnieuw (`onStale`). De samenvatting
 *   in de dialoog verandert mee en bevestigen blijft uit zolang ze laadt, zodat de mens de nieuwe samenvatting leest
 *   voor hij opnieuw bevestigt. Er is nooit een automatische herhaling.
 * - 409 `LEGACY_APPLY_BLOCKED`: ook hier laadt de ouder de controle opnieuw (de blokkerende punten staan in het rapport).
 * - 409 `LEGACY_RECEIPT_NOT_APPLICABLE`: de ouder laadt de ontvangst opnieuw (`onNotApplicable`).
 * - 503 `PRODIS_DB_UNAVAILABLE`, 403 en alle andere fouten: enkel de melding.
 */

import { ApiError } from '../../api/http.ts';
import * as legacyConfigApi from '../../api/legacyConfig.ts';
import type { LegacyApplyChoices, LegacyApplyRequest, LegacyApplyResult, LegacyConfigReport, LegacyReceiptRow } from '../../api/types.ts';
import { ConfirmDialog } from '../../components/ConfirmDialog.tsx';
import { ErrorBanner } from '../../errors/ErrorBanner.tsx';
import { useAction } from '../../hooks/useAction.ts';
import { WhatIsThis } from '../../terms/WhatIsThis.tsx';
import { applyGate } from './legacyPolicy.ts';
import { PlannedSummary } from './PlannedSummary.tsx';
import styles from './LegacyConfig.module.css';

export type ApplyDialogProps = {
  receipt: LegacyReceiptRow;
  /** De huidige controle vooraf; `null` terwijl ze (opnieuw) geladen wordt. */
  report: LegacyConfigReport | null;
  previewFingerprint: string | null;
  /** De keuzes waarmee de controle berekend is; ze gaan ongewijzigd mee. */
  choices: LegacyApplyChoices;
  onClose: () => void;
  onApplied: (result: LegacyApplyResult) => void;
  /** De controle vooraf is verouderd of blokkeert: laad ze opnieuw. */
  onStale: () => void;
  /** De ontvangst is niet meer toepasbaar: laad ze opnieuw. */
  onNotApplicable: () => void;
};

export function ApplyDialog({
  receipt,
  report,
  previewFingerprint,
  choices,
  onClose,
  onApplied,
  onStale,
  onNotApplicable,
}: ApplyDialogProps) {
  const runner = useAction(async (body: LegacyApplyRequest) => {
    try {
      return await legacyConfigApi.applyLegacyReceipt(receipt.id, body);
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 409) {
        if (cause.code === 'LEGACY_PREVIEW_STALE' || cause.code === 'LEGACY_APPLY_BLOCKED') {
          onStale();
        } else if (cause.code === 'LEGACY_RECEIPT_NOT_APPLICABLE') {
          onNotApplicable();
        }
      }
      throw cause;
    }
  });

  const gate = applyGate(receipt.status, report);
  const blockedReason = !gate.allowed
    ? gate.reason
    : previewFingerprint === null
      ? 'De controle vooraf wordt geladen…'
      : null;

  async function handleConfirm(input: { actor: string; reason: string | null }) {
    if (!gate.allowed || previewFingerprint === null || input.reason === null) {
      return;
    }
    const result = await runner.execute({ ...choices, previewFingerprint, reason: input.reason });
    if (result !== undefined) {
      onApplied(result);
    }
  }

  return (
    <ConfirmDialog
      open
      title={`Interface ${receipt.interfaceCode} overnemen`}
      body={
        <div className={styles.dialogBody}>
          <p>
            U neemt de configuratie van interface <strong>{receipt.interfaceCode}</strong> uit {receipt.originEnvironment} /{' '}
            {receipt.originDossier} over. Hieronder staat wat er aangemaakt of hergebruikt wordt.
          </p>
          {report !== null ? <PlannedSummary report={report} /> : <p className={styles.muted}>De samenvatting wordt geladen…</p>}
          {report?.planned.detach != null && (
            <p data-testid="legacy-detach-confirm">
              <strong>Let op, uitzondering:</strong> omdat u loskoppelt, wordt
              {report.planned.detach.carriesActive
                ? ' de huidige actieve versie van de gedeelde beschrijving meteen als eigen versie geactiveerd (de koppeling wordt verlegd naar de eigen beschrijving). De nieuwe inhoud blijft een conceptversie.'
                : ' de koppeling verlegd naar de eigen beschrijving; er is geen actieve versie om te activeren en de nieuwe inhoud blijft een conceptversie.'}
            </p>
          )}
          <WhatIsThis>
            <p>
              Overnemen maakt de beschrijving van het bestand als conceptversie aan, samen met de koppeling en een
              handmatige taak. Er wordt niets geactiveerd en er wordt nooit een bestaande koppeling of actieve versie
              gewijzigd. Enige uitzondering: bij loskoppelen wordt de koppeling verlegd en de kopie van de actieve versie
              geactiveerd.
            </p>
            <p>
              Verandert er tussen uw controle en uw bevestiging iets, dan weigert de server het overnemen en toont de
              dialoog de nieuwe samenvatting.
            </p>
          </WhatIsThis>
        </div>
      }
      reasonRequirement="required"
      confirmLabel="Configuratie overnemen"
      cancelLabel="Sluiten zonder over te nemen"
      pending={runner.pending}
      error={runner.error !== null ? <ErrorBanner error={runner.error} /> : undefined}
      confirmBlockedReason={blockedReason}
      onConfirm={handleConfirm}
      onCancel={onClose}
    />
  );
}
