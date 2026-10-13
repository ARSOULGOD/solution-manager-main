import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { SecretField } from './SecretField';

const modes = ['light', 'dark'] as const;

describe('SecretField a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <SecretField label="Solace password" value="" onChange={() => undefined} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
