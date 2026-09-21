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

});
