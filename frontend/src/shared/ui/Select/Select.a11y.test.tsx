import { axe } from 'jest-axe';
import { describe, expect, it } from 'vitest';

import { renderWithProviders } from '../../../test/renderWithProviders';

import { Select } from './Select';

const options = [
  { value: 'docker', label: 'Docker' },
  { value: 'k8s', label: 'Kubernetes' },
];

const modes = ['light', 'dark'] as const;

describe('Select a11y', () => {
  it.each(modes)('has no violations in %s mode', async (mode) => {
    const { container } = renderWithProviders(
      <div>
        <Select label="Driver" value="docker" onChange={() => {}} options={options} />
        <Select
          label="Driver"
          value="docker"
          onChange={() => {}}
          options={options}
          error="Unsupported driver."
        />
        <Select label="Disabled" value="docker" onChange={() => {}} options={options} disabled />
      </div>,
      { mode },
    );

    expect(await axe(container)).toHaveNoViolations();
  });
});
