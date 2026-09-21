import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { InfoTip } from './InfoTip';

describe('InfoTip', () => {
  it('renders an icon reachable by keyboard', async () => {
    const user = userEvent.setup();
    const { container } = renderWithProviders(<InfoTip tip="Driver is data on the row." />);

    expect(container.querySelector('svg')).not.toBeNull();

    await user.tab();

    expect(await screen.findByRole('tooltip')).toHaveTextContent('Driver is data on the row.');
  });
});
