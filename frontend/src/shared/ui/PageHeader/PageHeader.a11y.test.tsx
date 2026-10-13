import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';
import { Button } from '../Button';

import { PageHeader } from './PageHeader';

const modes = ['light', 'dark'] as const;

describe('PageHeader a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <PageHeader title="Instances" sub="A sub-line." actions={<Button>Declare</Button>} />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
