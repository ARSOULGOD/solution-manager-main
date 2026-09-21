import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Tooltip } from './Tooltip';

describe('Tooltip', () => {
  it('makes a non-focusable child reachable by Tab', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Tooltip title="Explains the state">
        <span>Plain, non-interactive text</span>
      </Tooltip>,
    );

    await user.tab();

    expect(document.activeElement?.textContent).toBe('Plain, non-interactive text');
  });

  it('opens on keyboard focus', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Tooltip title="Explains the state">
        <span>Trigger</span>
      </Tooltip>,
    );

    await user.tab();

    expect(await screen.findByRole('tooltip')).toHaveTextContent('Explains the state');
  });

  it('opens on hover', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Tooltip title="Explains the state">
        <span>Trigger</span>
      </Tooltip>,
    );

    await user.hover(screen.getByText('Trigger'));

    expect(await screen.findByRole('tooltip')).toHaveTextContent('Explains the state');
  });
});
