import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { EnvChip } from './EnvChip';

describe('EnvChip', () => {
  it.each([
    ['DEV', 'DEV'],
    ['TEST', 'TEST'],
    ['PROD', 'PROD'],
  ] as const)('renders %s with its own label', (env, label) => {
    renderWithProviders(<EnvChip env={env} />);
    expect(screen.getByText(label)).not.toBeNull();
  });

  it('renders an unrecognized environment name with the neutral fallback tone', () => {
    renderWithProviders(<EnvChip env="STAGING" />);
    const chip = screen.getByText('STAGING');
    expect(chip).not.toBeNull();
  });
});
