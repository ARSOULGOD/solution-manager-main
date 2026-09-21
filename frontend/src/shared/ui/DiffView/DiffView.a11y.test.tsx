import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { DiffView } from './DiffView';
import { SECRET_MASK } from './DiffView.types';

const modes = ['light', 'dark'] as const;

describe('DiffView a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <DiffView
        rows={[
          { path: 'cache.ttl', kind: 'change', old: '60s', new: '120s' },
          {
            path: 'broker.password',
            kind: 'change',
            secret: true,
            old: SECRET_MASK,
            new: SECRET_MASK,
          },
        ]}
      />,
      { mode },
    );
    expect(await axe(container)).toHaveNoViolations();
  });
});
