import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../../test/renderWithProviders';

import { YamlControl } from './YamlControl';

describe('YamlControl', () => {
  it('states that the raw editor is disabled', () => {
    renderWithProviders(<YamlControl label="Advanced config" />);

    expect(screen.getByText('The raw YAML editor is disabled in this release.')).toBeInTheDocument();
    expect(screen.getByText('Advanced config')).toBeInTheDocument();
  });

  it('appends any package-supplied helper text to the disabled message', () => {
    renderWithProviders(
      <YamlControl label="Advanced config" helperText="Contact your administrator for changes." />,
    );

    expect(
      screen.getByText(
        'The raw YAML editor is disabled in this release. Contact your administrator for changes.',
      ),
    ).toBeInTheDocument();
  });

  it('renders no editable surface — no textarea, contentEditable, or textbox role', () => {
    const { container } = renderWithProviders(<YamlControl label="Advanced config" />);

    expect(container.querySelectorAll('textarea, input, [contenteditable]')).toHaveLength(0);
    expect(container.querySelector('[contenteditable="true"]')).toBeNull();
    expect(screen.queryByRole('textbox')).toBeNull();
  });
});
