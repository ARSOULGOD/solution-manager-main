import { axe } from 'jest-axe';
import { act } from 'react';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { OutcomeBadge } from './OutcomeBadge';
import { Toaster } from './Toaster';
import { pushToast, useToastStore } from './toastStore';

const modes = ['light', 'dark'] as const;

describe('Toaster a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    useToastStore.setState({ toasts: [] });
    const { container } = renderWithProviders(
      <div>
        <Toaster />
        <OutcomeBadge title="Draft saved" correlationId="corr-a" severity="ok" />
      </div>,
      { mode },
    );
    act(() => {
      pushToast({ title: 'Draft saved', correlationId: 'corr-a' });
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
