import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import type { ValidationEntry } from '../ValidationSummary/collectValidationEntries';

import { OverrideReasonDialog } from './OverrideReasonDialog';

const WARNINGS: readonly ValidationEntry[] = [
  {
    sectionIndex: 0,
    fieldKey: 'cacheSizeMb',
    label: 'Cache size (MB)',
    message: 'Above the range Solace recommends (1–1000000).',
  },
];

describe('OverrideReasonDialog', () => {
  it('lists each warning by label and message', () => {
    renderWithProviders(
      <OverrideReasonDialog
        open
        warnings={WARNINGS}
        onCancel={() => undefined}
        onConfirm={() => undefined}
      />,
    );

    expect(screen.getByText('Cache size (MB)', { exact: false })).not.toBeNull();
    expect(screen.getByText(/Above the range Solace recommends/)).not.toBeNull();
  });

  it('disables "Save & record reason" until the reason is at least a few words', () => {
    renderWithProviders(
      <OverrideReasonDialog
        open
        warnings={WARNINGS}
        onCancel={() => undefined}
        onConfirm={() => undefined}
      />,
    );

    const confirmButton = screen.getByRole('button', { name: 'Save & record reason' });
    expect(confirmButton.hasAttribute('disabled')).toBe(true);

    fireEvent.change(screen.getByLabelText('Reason for override'), { target: { value: 'ok' } });
    expect(confirmButton.hasAttribute('disabled')).toBe(true);
  });

  it('enables confirm once the reason is a few words, and passes it through exactly', () => {
    const onConfirm = vi.fn();
    renderWithProviders(
      <OverrideReasonDialog
        open
        warnings={WARNINGS}
        onCancel={() => undefined}
        onConfirm={onConfirm}
      />,
    );

    fireEvent.change(screen.getByLabelText('Reason for override'), {
      target: { value: 'Verified against the vendor sizing guide' },
    });
    const confirmButton = screen.getByRole('button', { name: 'Save & record reason' });
    expect(confirmButton.hasAttribute('disabled')).toBe(false);

    fireEvent.click(confirmButton);
    expect(onConfirm).toHaveBeenCalledWith('Verified against the vendor sizing guide');
  });

  it('cancel calls onCancel, never onConfirm', () => {
    const onCancel = vi.fn();
    const onConfirm = vi.fn();
    renderWithProviders(
      <OverrideReasonDialog open warnings={WARNINGS} onCancel={onCancel} onConfirm={onConfirm} />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
