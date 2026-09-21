import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { createConsoleClient } from '@shared/api';

import { renderWithProviders, screen, waitFor } from '../../../test/renderWithProviders';
import { MetricsProvider } from '../MetricsProvider';

import { ExplorePage } from './ExplorePage';

const consoleClient = createConsoleClient('/api');
const modes = ['light', 'dark'] as const;

describe('ExplorePage a11y', () => {
  it.each(modes)('has no violations once the catalog has loaded, in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <MetricsProvider consoleClient={consoleClient}>
        <ExplorePage solutionId="cache" instanceId="cache-prod-01" />
      </MetricsProvider>,
      { mode },
    );
    await waitFor(() => expect(screen.getByRole('radio', { name: 'cache.hit_rate' })).not.toBeNull());
    expect(await axe(container)).toHaveNoViolations();
  });
});
