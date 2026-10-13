import { act } from 'react';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { OutcomeBadge } from './OutcomeBadge';
import { pushOutcome, useOutcomeStore } from './outcomeStore';
import { Toaster } from './Toaster';
import { pushToast, useToastStore } from './toastStore';

describe('Toaster', () => {
  it('requires a correlation id on every toast and announces politely', () => {
    useToastStore.setState({ toasts: [] });
    renderWithProviders(<Toaster />);
    act(() => {
      pushToast({ title: 'Draft saved', correlationId: 'corr-1' });
    });

    expect(screen.getByText('Draft saved')).not.toBeNull();
    expect(screen.getByText('Correlation id corr-1')).not.toBeNull();
    expect(screen.getByRole('region', { name: 'Notifications' }).getAttribute('aria-live')).toBe(
      'polite',
    );
    expect(document.querySelector('[data-correlation-id="corr-1"]')).not.toBeNull();
  });
});

describe('OutcomeBadge', () => {
  it('records the last mutation with its correlation id', () => {
    renderWithProviders(<OutcomeBadge title="Draft saved" correlationId="corr-9" severity="ok" />);
    expect(screen.getByText('Draft saved')).not.toBeNull();
    expect(screen.getByText('Correlation id corr-9')).not.toBeNull();
  });
});

describe('pushOutcome (FE-021/FE-044)', () => {
  it('records the outcome per resource id and also fires a transient toast', () => {
    useOutcomeStore.setState({ byResource: {} });
    useToastStore.setState({ toasts: [] });

    act(() => {
      pushOutcome('inst-1', { title: 'Config applied', correlationId: 'corr-77', severity: 'ok' });
    });

    expect(useOutcomeStore.getState().byResource['inst-1']).toEqual({
      title: 'Config applied',
      correlationId: 'corr-77',
      severity: 'ok',
    });
    expect(useToastStore.getState().toasts).toHaveLength(1);
    expect(useOutcomeStore.getState().byResource['inst-2']).toBeUndefined();
  });
});
