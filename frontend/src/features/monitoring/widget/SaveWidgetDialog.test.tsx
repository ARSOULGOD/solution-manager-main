import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { SaveWidgetDialog } from './SaveWidgetDialog';
import { buildWidgetConfig } from './widgetConfig';

const DRAFT = buildWidgetConfig(
  {
    title: '',
    mark: 'line',
    query: {
      metric: 'cache.hit_rate',
      selector: { entityType: 'cache-engine', instanceIds: ['cache-prod-01', 'cache-prod-02'] },
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    },
    range: { kind: 'preset', preset: '24h' },
    resolution: 'auto',
  },
  'w_fixed',
);

describe('SaveWidgetDialog', () => {
  it('renders a read-only summary of the metric, mark, instance count and resolution', () => {
    renderWithProviders(<SaveWidgetDialog open draft={DRAFT} onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByText('cache.hit_rate')).toBeInTheDocument();
    expect(screen.getByText('line')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('auto')).toBeInTheDocument();
  });

  it('states the fixed scope and layer — no control to change either', () => {
    renderWithProviders(<SaveWidgetDialog open draft={DRAFT} onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByText('This instance')).toBeInTheDocument();
    expect(screen.getByText('Shared (operator)')).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.queryByRole('radio')).toBeNull();
    expect(
      screen.getByText(/entity, organization and personal scopes are not built/),
    ).toBeInTheDocument();
  });

  it('states the persistence route is invented', () => {
    renderWithProviders(<SaveWidgetDialog open draft={DRAFT} onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.getByText(/does not exist yet/)).toBeInTheDocument();
  });

  it('renders no "create alert" affordance anywhere (`DM-D15`)', () => {
    renderWithProviders(<SaveWidgetDialog open draft={DRAFT} onClose={() => {}} onSubmit={() => {}} />);
    expect(screen.queryByText(/alert/i)).toBeNull();
  });

  it('Save is blocked with a stated reason when the title is empty', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    renderWithProviders(<SaveWidgetDialog open draft={DRAFT} onClose={() => {}} onSubmit={onSubmit} />);

    await user.click(screen.getByRole('button', { name: 'Save widget' }));

    expect(screen.getByText('A title is required.')).toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('Save calls onSubmit with the draft plus the entered title, trimmed', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    renderWithProviders(<SaveWidgetDialog open draft={DRAFT} onClose={() => {}} onSubmit={onSubmit} />);

    await user.type(screen.getByLabelText(/^Widget title/), '  Hit rate — production  ');
    await user.click(screen.getByRole('button', { name: 'Save widget' }));

    expect(onSubmit).toHaveBeenCalledWith({ ...DRAFT, title: 'Hit rate — production' });
  });

  it('Cancel calls onClose without calling onSubmit', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const onSubmit = vi.fn();
    renderWithProviders(<SaveWidgetDialog open draft={DRAFT} onClose={onClose} onSubmit={onSubmit} />);

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('renders a failed save in place, not only as a toast', () => {
    renderWithProviders(
      <SaveWidgetDialog
        open
        draft={DRAFT}
        onClose={() => {}}
        onSubmit={() => {}}
        errorMessage="The dashboard could not be reached."
      />,
    );
    expect(screen.getByText('Save failed')).toBeInTheDocument();
    expect(screen.getByText('The dashboard could not be reached.')).toBeInTheDocument();
  });

  it('disables both actions while submitting', () => {
    renderWithProviders(
      <SaveWidgetDialog open draft={DRAFT} onClose={() => {}} onSubmit={() => {}} submitting />,
    );
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Saving…' })).toBeDisabled();
  });
});
