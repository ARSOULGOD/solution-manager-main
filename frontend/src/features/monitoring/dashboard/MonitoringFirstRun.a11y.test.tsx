import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { createConsoleClient } from '@shared/api';

import { renderWithProviders, waitFor, screen } from '../../../test/renderWithProviders';
import { MetricsProvider } from '../MetricsProvider';

import { MonitoringFirstRun } from './MonitoringFirstRun';

const consoleClient = createConsoleClient('/api');
const modes = ['light', 'dark'] as const;

describe('MonitoringFirstRun a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <MetricsProvider consoleClient={consoleClient}>
        <MonitoringFirstRun solutionId="cache" instanceId="cache-prod-01" />
      </MetricsProvider>,
      { mode },
    );
    await waitFor(() => {
      expect(screen.getByText('Cache instance')).toBeInTheDocument();
    });
    expect(await axe(container)).toHaveNoViolations();
  });
});
