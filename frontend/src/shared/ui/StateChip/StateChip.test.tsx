import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import { healthVocab } from '../../vocab';

import { StateChip } from './StateChip';

describe('StateChip', () => {
  it('renders the vocabulary entry as text and an icon', () => {
    const { container } = renderWithProviders(<StateChip entry={healthVocab.not_responding} />);

    expect(screen.getByText('Not responding')).not.toBeNull();
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('renders a keyboard-reachable info tip for an entry with a tip', async () => {
    const user = userEvent.setup();
    renderWithProviders(<StateChip entry={healthVocab.inactive_replica} />);

    // Tab past the chip itself (not focusable) to the info tip trigger.
    await user.tab();

    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      /a standby: up and deliberately processing nothing/i,
    );
  });

  it('renders no info tip for an entry with no tip', () => {
    renderWithProviders(<StateChip entry={healthVocab.healthy} />);

    expect(screen.queryByRole('tooltip')).toBeNull();
  });
});
