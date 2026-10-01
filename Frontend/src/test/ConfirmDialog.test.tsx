/**
 * T4 — `ConfirmDialog` — bevestigen geblokkeerd zonder verplichte reden; naam alleen-lezen uit de
 * geverifieerde identiteit (5-AUTH); typ-bevestiging eist de exacte referentie. Zie
 * `docs/design/frontend-scherm3-bundel-design.md` §14.1 en §10.5/§10.6 en `docs/design/fase5-auth-design.md` §6.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent, within } from '@testing-library/react';
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

  it('T4.7: is een native modale <dialog> (showModal) met open-attribuut en titel als naam', () => {
    renderDialog({ reasonRequirement: 'none' });

    const dialog = screen.getByRole('dialog', { name: 'Bundel bevriezen' });
    expect(dialog.tagName).toBe('DIALOG');
    expect(dialog).toHaveAttribute('open');
  });

  it('T4.8: Escape (cancel-event) annuleert, tenzij er een actie loopt', () => {
    const { onCancel } = renderDialog({ reasonRequirement: 'none' });
    const dialog = screen.getByRole('dialog');

    const event = new Event('cancel', { cancelable: true });
    fireEvent(dialog, event);
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(true);

    cleanup();
    const pendingRun = renderDialog({ reasonRequirement: 'none', pending: true });
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }));
    expect(pendingRun.onCancel).not.toHaveBeenCalled();
  });

  it('T4.9: zet de focus terug naar het element dat focus had bij openen', () => {
    function Host({ open }: { open: boolean }) {
      return (
        <ActorProvider identity={IDENTITY}>
          <button type="button">opener</button>
          <ConfirmDialog open={open} title="T" reasonRequirement="none" onConfirm={vi.fn()} onCancel={vi.fn()} />
        </ActorProvider>
      );
    }
    const { rerender } = render(<Host open={false} />);
    const opener = screen.getByRole('button', { name: 'opener' });
    opener.focus();

    rerender(<Host open />);
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    (screen.getByRole('button', { name: 'Annuleren' })).focus();

    rerender(<Host open={false} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('T4.10: label-ids zijn uniek bij twee dialogen en koppelen aan het eigen veld', () => {
    render(
      <ActorProvider identity={IDENTITY}>
        <ConfirmDialog open title="Eerste" reasonRequirement="required" onConfirm={vi.fn()} onCancel={vi.fn()} />
        <ConfirmDialog open title="Tweede" reasonRequirement="required" onConfirm={vi.fn()} onCancel={vi.fn()} />
      </ActorProvider>,
    );
    const first = screen.getByRole('dialog', { name: 'Eerste' });
    const second = screen.getByRole('dialog', { name: 'Tweede' });
    const a = within(first).getByLabelText(/Reden/);
    const b = within(second).getByLabelText(/Reden/);
    expect(a.id).not.toBe(b.id);
    expect(first.contains(a)).toBe(true);
    expect(second.contains(b)).toBe(true);
  });

  it('T4.6: een optionele reden blokkeert bevestigen niet', () => {
    const { onConfirm } = renderDialog({ reasonRequirement: 'optional' });

    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).not.toBeDisabled();

    fireEvent.click(confirmButton);
    expect(onConfirm).toHaveBeenCalledWith({ actor: 'ann.approver', reason: null });
  });
});
