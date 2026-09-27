/**
 * T4 — `ConfirmDialog` — bevestigen geblokkeerd zonder verplichte reden; naam alleen-lezen uit de
 * geverifieerde identiteit (5-AUTH); typ-bevestiging eist de exacte referentie. Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §14.1 en §10.5/§10.6 en `docs/design/fase5-auth-design.md` §6.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { ActorProvider, type ActorIdentity } from '../actor/ActorContext';
import { ConfirmDialog } from '../components/ConfirmDialog';

const IDENTITY: ActorIdentity = { username: 'ann.approver', subject: 'sub-1', displayName: 'Ann Approver', permissions: ['catalogImport.read', 'catalogImport.manage', 'catalogImport.approve'] };

function renderDialog(
  props: Partial<React.ComponentProps<typeof ConfirmDialog>> = {},
  identity: ActorIdentity = IDENTITY,
) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();

  render(
    <ActorProvider identity={identity}>
      <ConfirmDialog
        open
        title="Bundel bevriezen"
        reasonRequirement="required"
        onConfirm={onConfirm}
        onCancel={onCancel}
        {...props}
      />
    </ActorProvider>,
  );

  return { onConfirm, onCancel };
}

afterEach(() => {
  cleanup();
});

describe('ConfirmDialog', () => {
  it('T4.1: blokkeert bevestigen zonder verplichte reden', () => {
    const { onConfirm } = renderDialog({ reasonRequirement: 'required' });

    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).toBeDisabled();

    fireEvent.click(confirmButton);
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByText('Vul een reden in.')).toBeInTheDocument();
  });

  it('T4.2: toont de geverifieerde naam alleen-lezen, zonder invoerveld voor de naam', () => {
    renderDialog({ reasonRequirement: 'none' });

    expect(screen.getByTestId('confirm-dialog-actor')).toHaveTextContent('U tekent als Ann Approver (ann.approver)');
    expect(screen.queryByLabelText(/Naam/)).not.toBeInTheDocument();
  });

  it('T4.3: zonder weergavenaam toont de dialoog enkel de gebruikersnaam', () => {
    renderDialog({ reasonRequirement: 'none' }, { username: 'jan.peeters', subject: 's', displayName: null });

    expect(screen.getByTestId('confirm-dialog-actor')).toHaveTextContent('U tekent als jan.peeters');
    expect(screen.getByTestId('confirm-dialog-actor')).not.toHaveTextContent('(');
  });

  it('T4.4: blokkeert bevestigen zolang de typ-bevestiging niet exact klopt', () => {
    const { onConfirm } = renderDialog({
      reasonRequirement: 'required',
      typedConfirmationText: 'BUNDLE-2026-042',
    });

    fireEvent.change(screen.getByLabelText('Reden *'), { target: { value: 'Bevriezen na akkoord' } });
    fireEvent.change(screen.getByLabelText('Typ "BUNDLE-2026-042" om te bevestigen *'), {
      target: { value: 'bundle-2026-042' },
    });

    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).toBeDisabled();
    fireEvent.click(confirmButton);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('T4.5: laat bevestigen toe zodra reden en de exacte typ-bevestiging kloppen; actor = username', () => {
    const { onConfirm } = renderDialog({
      reasonRequirement: 'required',
      typedConfirmationText: 'BUNDLE-2026-042',
    });

    fireEvent.change(screen.getByLabelText('Reden *'), { target: { value: 'Bevriezen na akkoord' } });
    fireEvent.change(screen.getByLabelText('Typ "BUNDLE-2026-042" om te bevestigen *'), {
      target: { value: 'BUNDLE-2026-042' },
    });

    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).not.toBeDisabled();
    fireEvent.click(confirmButton);
    expect(onConfirm).toHaveBeenCalledWith({ actor: 'ann.approver', reason: 'Bevriezen na akkoord' });
  });

  it('T4.6: een optionele reden blokkeert bevestigen niet', () => {
    const { onConfirm } = renderDialog({ reasonRequirement: 'optional' });

    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).not.toBeDisabled();

    fireEvent.click(confirmButton);
    expect(onConfirm).toHaveBeenCalledWith({ actor: 'ann.approver', reason: null });
  });
});
