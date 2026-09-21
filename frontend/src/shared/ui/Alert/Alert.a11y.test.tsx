import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Alert } from './Alert';
import type { AlertSeverity } from './Alert.types';

const severities: AlertSeverity[] = ['info', 'warn', 'err', 'ok', 'neutral'];
const modes = ['light', 'dark'] as const;

describe('Alert a11y', () => {
  it.each(modes)('has no violations in %s mode across every severity', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        {severities.map((severity) => (
          <Alert key={severity} severity={severity} title={severity}>
            Example alert body text.
          </Alert>
        ))}
        <Alert severity="err" action={<button type="button">Retry</button>}>
          With an action.
        </Alert>
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
