import { fireEvent } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@shared/api';
import { reflowFloorPx } from '@shared/theme/breakpoints';
import { useToastStore } from '@shared/ui';

import { setViewportWidth } from '../../../test/matchMedia';
import { assertReflow } from '../../../test/reflow';
import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import type { ConfigRepository, SaveConfigResult } from '../configRepository';
import type { ValidationEntry } from '../ValidationSummary/collectValidationEntries';

import { SaveBar } from './SaveBar';

function stubRepository(save: ConfigRepository['save']): ConfigRepository {
  return {
    loadDesigner: () => Promise.reject(new Error('not used in these tests')),
    save,
    history: () => Promise.resolve([]),
    setSecret: () => Promise.reject(new Error('not used in these tests')),
    acceptRuntime: () => Promise.reject(new Error('not used in these tests')),
    restoreRuntime: () => Promise.reject(new Error('not used in these tests')),
  };
}

const PAYLOAD = { 'cache-0': { host: 'broker.example.com' } };

const WARNING: ValidationEntry = {
  sectionIndex: 0,
  fieldKey: 'cacheSizeMb',
  label: 'Cache size (MB)',
  message: 'Above the range Solace recommends (1–1000000).',
};

describe('SaveBar', () => {
  beforeEach(() => {
    useToastStore.setState({ toasts: [] });
  });

  it('disables Save when there are blocking fields, even with unsaved changes', () => {
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={2}
        blockingCount={1}
        warnings={[]}
        onSaved={() => undefined}
      />,
    );

    expect(screen.getByRole('button', { name: 'Save draft' }).hasAttribute('disabled')).toBe(true);
  });

  it('disables Save when there are no unsaved changes', () => {
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={0}
        blockingCount={0}
        warnings={[]}
        onSaved={() => undefined}
      />,
    );

    expect(screen.getByText('No unsaved changes')).not.toBeNull();
    expect(screen.getByRole('button', { name: 'Save draft' }).hasAttribute('disabled')).toBe(true);
  });

  it('enables Save with unsaved, non-blocking changes and sends the payload plus the last-seen generation', async () => {
    const save = vi.fn(() =>
      Promise.resolve<SaveConfigResult>({ generation: 'gen-42', correlationId: 'corr-1' }),
    );
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(save)}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={1}
        blockingCount={0}
        warnings={[]}
        onSaved={() => undefined}
      />,
    );

    expect(screen.getByText('1 unsaved change')).not.toBeNull();

    const saveButton = screen.getByRole('button', { name: 'Save draft' });
    expect(saveButton.hasAttribute('disabled')).toBe(false);
    fireEvent.click(saveButton);

    await waitFor(() => {
      expect(save).toHaveBeenCalledWith('cache-prod-01', { fields: PAYLOAD, generation: 'gen-41' });
    });
  });

  it('shows the recommendation chip for outstanding warnings', () => {
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('must not be called')))}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={1}
        blockingCount={0}
        warnings={[WARNING]}
        onSaved={() => undefined}
      />,
    );

    expect(screen.getByText('1 recommendation')).not.toBeNull();
  });

  it('Save with outstanding warnings opens the override-reason dialog instead of saving immediately', () => {
    const save = vi.fn(() => Promise.reject(new Error('must not be called until confirmed')));
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(save)}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={1}
        blockingCount={0}
        warnings={[WARNING]}
        onSaved={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(screen.getByText('Save with recommendations overridden')).not.toBeNull();
    expect(save).not.toHaveBeenCalled();
  });

  it('confirming the override-reason dialog saves with the typed reason as overrideReason', async () => {
    const save = vi.fn(() =>
      Promise.resolve<SaveConfigResult>({ generation: 'gen-42', correlationId: 'corr-3' }),
    );
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(save)}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={1}
        blockingCount={0}
        warnings={[WARNING]}
        onSaved={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    fireEvent.change(screen.getByLabelText('Reason for override'), {
      target: { value: 'Verified against the vendor sizing guide' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save & record reason' }));

    await waitFor(() => {
      expect(save).toHaveBeenCalledWith('cache-prod-01', {
        fields: PAYLOAD,
        generation: 'gen-41',
        overrideReason: 'Verified against the vendor sizing guide',
      });
    });
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });

  it('canceling the override-reason dialog never calls repository.save', async () => {
    const save = vi.fn(() => Promise.reject(new Error('must not be called')));
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(save)}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={1}
        blockingCount={0}
        warnings={[WARNING]}
        onSaved={() => undefined}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(save).not.toHaveBeenCalled();
  });

  it('on success: calls onSaved and pushes a toast that never implies an MI write, carrying the server correlation id', async () => {
    const onSaved = vi.fn();
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(() =>
          Promise.resolve<SaveConfigResult>({ generation: 'gen-42', correlationId: 'corr-2' }),
        )}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={1}
        blockingCount={0}
        warnings={[]}
        onSaved={onSaved}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => {
      expect(onSaved).toHaveBeenCalledWith({ generation: 'gen-42', correlationId: 'corr-2' });
    });
    const toasts = useToastStore.getState().toasts;
    expect(toasts).toHaveLength(1);
    expect(toasts[0]).toMatchObject({
      title: 'Draft saved',
      detail:
        'Draft saved to the platform record. This wrote nothing to the MI — use Apply for that.',
      correlationId: 'corr-2',
      severity: 'ok',
    });
  });

  it('on a non-409 failure: renders an inline Alert, calls onSaveError, and never calls onSaved', async () => {
    const onSaved = vi.fn();
    const onSaveError = vi.fn();
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new ApiError({ status: 500 })))}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={1}
        blockingCount={0}
        warnings={[]}
        onSaved={onSaved}
        onSaveError={onSaveError}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).not.toBeNull();
    });
    expect(onSaved).not.toHaveBeenCalled();
    expect(onSaveError).toHaveBeenCalledTimes(1);
    expect(useToastStore.getState().toasts).toHaveLength(0);
  });

  it('on a 409: calls onSaveError but renders no inline Alert of its own — task 056 owns that UI', async () => {
    const onSaveError = vi.fn();
    renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new ApiError({ status: 409 })))}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={1}
        blockingCount={0}
        warnings={[]}
        onSaved={() => undefined}
        onSaveError={onSaveError}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() => {
      expect(onSaveError).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('disables Apply while dirty, enables it once the view matches the record, and never calls onApply while disabled', () => {
    const onApply = vi.fn();
    const { rerender } = renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('not used')))}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={1}
        blockingCount={0}
        warnings={[]}
        onSaved={() => undefined}
        onApply={onApply}
      />,
    );

    const applyButton = screen.getByRole('button', { name: 'Apply…' });
    expect(applyButton.hasAttribute('disabled')).toBe(true);
    fireEvent.click(applyButton);
    expect(onApply).not.toHaveBeenCalled();

    rerender(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('not used')))}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={0}
        blockingCount={0}
        warnings={[]}
        onSaved={() => undefined}
        onApply={onApply}
      />,
    );

    const enabledApply = screen.getByRole('button', { name: 'Apply…' });
    expect(enabledApply.hasAttribute('disabled')).toBe(false);
    fireEvent.click(enabledApply);
    expect(onApply).toHaveBeenCalledTimes(1);
  });
});

describe('SaveBar reflow (task 108, FE-D29)', () => {
  it('the worst case — dirty count, a blocking chip and a recommendation chip, both actions — reflows at 320px without horizontal scroll, save ≠ apply unchanged', () => {
    setViewportWidth(reflowFloorPx);
    const { container } = renderWithProviders(
      <SaveBar
        instanceId="cache-prod-01"
        repository={stubRepository(() => Promise.reject(new Error('not used')))}
        payload={PAYLOAD}
        generation="gen-41"
        dirtyCount={3}
        blockingCount={1}
        warnings={[WARNING]}
        onSaved={() => undefined}
        onApply={() => undefined}
      />,
    );

    // The save/apply disabled rule is a rule, not a layout — still holds at 320px.
    expect(screen.getByRole('button', { name: 'Save draft' }).hasAttribute('disabled')).toBe(true);
    expect(screen.getByRole('button', { name: 'Apply…' }).hasAttribute('disabled')).toBe(true);

    const root = container.firstElementChild;
    expect(root).toBeInstanceOf(HTMLElement);
    expect(() => {
      assertReflow(root as HTMLElement, reflowFloorPx);
    }).not.toThrow();
  });
});
