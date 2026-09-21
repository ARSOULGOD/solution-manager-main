import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../../test/renderWithProviders';

import { MapControl } from './MapControl';

const modes = ['light', 'dark'] as const;

describe('MapControl a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <MapControl
          label="Tags"
          entries={[
            { key: 'region', value: 'us-east-1' },
            { key: 'tier', value: 'gold' },
          ]}
          onChange={() => {}}
        />
        <MapControl label="Empty map" entries={[]} onChange={() => {}} />
        <MapControl
          label="Disabled map"
          entries={[{ key: 'region', value: 'us-east-1' }]}
          onChange={() => {}}
          disabled
        />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
