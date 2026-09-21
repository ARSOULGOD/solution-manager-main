import { describe, expect, it, vi } from 'vitest';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { ConfirmDialog, Dialog, HighConsequenceDialog } from './index';

describe('Dialog', () => {
  it('labels the dialog from the title', () => {
    const onClose = vi.fn();
    renderWithProviders(
      <Dialog open title="Restart selected" onClose={onClose}>
        Body
      </Dialog>,
    );

    expect(screen.getByRole('dialog', { name: 'Restart selected' })).not.toBeNull();
    screen.getByRole('button', { name: 'Close' }).click();
    expect(onClose).toHaveBeenCalled();
  });

  it('stays centred at every width when fullScreenBelow is omitted', () => {
    setViewportWidth(320);
    renderWithProviders(
      <Dialog open title="Restart selected" onClose={() => undefined}>
        Body
      </Dialog>,
    );
    expect(document.querySelector('.MuiDialog-paperFullScreen')).toBeNull();
  });

  it('becomes a full-screen sheet below fullScreenBelow (task 108, FE-D29)', () => {
    setViewportWidth(320);
    renderWithProviders(
      <Dialog open title="Restart selected" onClose={() => undefined} fullScreenBelow="sm">
        Body
      </Dialog>,
    );
    expect(document.querySelector('.MuiDialog-paperFullScreen')).not.toBeNull();
  });

  it('stays centred at fullScreenBelow and above', () => {
    setViewportWidth(600);
    renderWithProviders(
      <Dialog open title="Restart selected" onClose={() => undefined} fullScreenBelow="sm">
        Body
      </Dialog>,
    );
    expect(document.querySelector('.MuiDialog-paperFullScreen')).toBeNull();
  });
});

describe('HighConsequenceDialog', () => {
  it('cannot be confirmed without the consequence banner', () => {
    renderWithProviders(
      <HighConsequenceDialog
        open
        title="Restart all"
        consequence="outage"
        confirmLabel="Restart all"
        onConfirm={() => undefined}
        onCancel={() => undefined}
      >
        <p>Every replica will restart.</p>
      </HighConsequenceDialog>,
    );

    expect(document.querySelector('[data-consequence="outage"]')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Restart all' })).not.toBeNull();
  });
});

describe('ConfirmDialog', () => {
  it('renders a body slot and typed confirm button', () => {
    renderWithProviders(
      <ConfirmDialog
        open
        title="Discard draft"
        confirmLabel="Discard"
        onConfirm={() => undefined}
        onCancel={() => undefined}
      >
        Unsaved edits will be lost.
      </ConfirmDialog>,
    );
    expect(screen.getByText('Unsaved edits will be lost.')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Discard' })).not.toBeNull();
  });
});
