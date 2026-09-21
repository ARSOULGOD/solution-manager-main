import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { SaveWidgetDialog } from './SaveWidgetDialog';
import { buildWidgetConfig } from './widgetConfig';

const DRAFT = buildWidgetConfig(
  {
    title: '',
    mark: 'line',
    query: {
      metric: 'cache.hit_rate',
      selector: { entityType: 'cache-engine', instanceIds: ['cache-prod-01'] },
      seriesBy: 'instance',
      aggregation: { acrossReplicas: 'auto', acrossInstances: 'none' },
    },
    range: { kind: 'preset', preset: '24h' },
    resolution: 'auto',
  },
  'w_fixed',
);

const modes = ['light', 'dark'] as const;

describe('SaveWidgetDialog a11y', () => {
  it.each(modes)('has no violations, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <SaveWidgetDialog open draft={DRAFT} onClose={() => {}} onSubmit={() => {}} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });

  it.each(modes)('has no violations with a stated error, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <SaveWidgetDialog
        open
        draft={DRAFT}
        onClose={() => {}}
        onSubmit={() => {}}
        errorMessage="The dashboard could not be reached."
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
