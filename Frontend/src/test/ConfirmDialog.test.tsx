/**
 * T4 — `ConfirmDialog` — bevestigen geblokkeerd zonder verplichte reden; zonder actor; typ-bevestiging
 * eist de exacte referentie. Zie `docs/design/frontend-scherm3-bundel-design.md` §14.1 en §10.5/§10.6.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import type { ReactNode } from 'react';
import { ActorProvider } from '../actor/ActorContext';
import { ConfirmDialog } from '../components/ConfirmDialog';

function renderDialog(props: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();

  function Wrapper({ children }: { children: ReactNode }) {
    return <ActorProvider>{children}</ActorProvider>;
  }

  render(
    <Wrapper>
      <ConfirmDialog
        open
        title="Bundel bevriezen"
        reasonRequirement="required"
        onConfirm={onConfirm}
        onCancel={onCancel}
        {...props}
      />
    </Wrapper>
  );

  return { onConfirm, onCancel };
}

beforeEach(() => {
  sessionStorage.clear();
});

afterEach(() => {
  cleanup();
});

describe('ConfirmDialog', () => {
  it('T4.1: blokkeert bevestigen zonder verplichte reden', () => {
    const { onConfirm } = renderDialog({ reasonRequirement: 'required' });

    fireEvent.change(screen.getByLabelText('Naam *'), { target: { value: 'Ann Approver' } });
    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).toBeDisabled();

    fireEvent.click(confirmButton);
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByText('Vul een reden in.')).toBeInTheDocument();
  });

  it('T4.2: blokkeert bevestigen zonder actor', () => {
    const { onConfirm } = renderDialog({ reasonRequirement: 'required' });

    fireEvent.change(screen.getByLabelText('Reden *'), { target: { value: 'Foutieve prijs' } });
    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).toBeDisabled();

    fireEvent.click(confirmButton);
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByText('Vul een naam in.')).toBeInTheDocument();
  });

  it('T4.3: weigert een naam gelijk aan "system", hoofdletterongevoelig', () => {
    renderDialog({ reasonRequirement: 'none' });

    fireEvent.change(screen.getByLabelText('Naam *'), { target: { value: 'System' } });
    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    fireEvent.click(confirmButton);

    expect(screen.getByText('De naam "system" is niet toegestaan.')).toBeInTheDocument();
  });

  it('T4.4: blokkeert bevestigen zolang de typ-bevestiging niet exact klopt', () => {
    const { onConfirm } = renderDialog({
      reasonRequirement: 'required',
      typedConfirmationText: 'BUNDLE-2026-042',
    });

    fireEvent.change(screen.getByLabelText('Naam *'), { target: { value: 'Ann Approver' } });
    fireEvent.change(screen.getByLabelText('Reden *'), { target: { value: 'Bevriezen na akkoord' } });
    fireEvent.change(screen.getByLabelText('Typ "BUNDLE-2026-042" om te bevestigen *'), {
      target: { value: 'bundle-2026-042' },
    });

    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).toBeDisabled();
    fireEvent.click(confirmButton);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('T4.5: laat bevestigen toe zodra actor, reden en de exacte typ-bevestiging kloppen', () => {
    const { onConfirm } = renderDialog({
      reasonRequirement: 'required',
      typedConfirmationText: 'BUNDLE-2026-042',
    });

    fireEvent.change(screen.getByLabelText('Naam *'), { target: { value: 'Ann Approver' } });
    fireEvent.change(screen.getByLabelText('Reden *'), { target: { value: 'Bevriezen na akkoord' } });
    fireEvent.change(screen.getByLabelText('Typ "BUNDLE-2026-042" om te bevestigen *'), {
      target: { value: 'BUNDLE-2026-042' },
    });

    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).not.toBeDisabled();
    fireEvent.click(confirmButton);
    expect(onConfirm).toHaveBeenCalledWith({ actor: 'Ann Approver', reason: 'Bevriezen na akkoord' });
  });

  it('T4.6: een optionele reden blokkeert bevestigen niet', () => {
    const { onConfirm } = renderDialog({ reasonRequirement: 'optional' });

    fireEvent.change(screen.getByLabelText('Naam *'), { target: { value: 'Ann Approver' } });
    const confirmButton = screen.getByRole('button', { name: 'Bevestigen' });
    expect(confirmButton).not.toBeDisabled();

    fireEvent.click(confirmButton);
    expect(onConfirm).toHaveBeenCalledWith({ actor: 'Ann Approver', reason: null });
  });

  it('T4.7: toont de actornaam opnieuw en laat die ter plekke wijzigen', () => {
    sessionStorage.setItem('catalogimport.actor', 'Oorspronkelijke Naam');
    renderDialog({ reasonRequirement: 'none' });

    const nameField = screen.getByLabelText('Naam *') as HTMLInputElement;
    expect(nameField.value).toBe('Oorspronkelijke Naam');

    fireEvent.change(nameField, { target: { value: 'Andere Naam' } });
    expect(nameField.value).toBe('Andere Naam');
  });
});
