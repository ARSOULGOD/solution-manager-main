import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Chip } from './Chip';

describe('Chip', () => {
  it('renders its label', () => {
    renderWithProviders(<Chip label="Healthy" tone="ok" />);

    expect(screen.getByText('Healthy')).not.toBeNull();
  });

  it('renders a leading dot when requested and no icon is given', () => {
    const { container } = renderWithProviders(<Chip label="Docker" tone="neutral" dot />);

    expect(container.querySelector('.MuiChip-icon')).not.toBeNull();
  });

  it('prefers a supplied icon over the dot', () => {
    const { container } = renderWithProviders(
      <Chip label="Healthy" tone="ok" icon={<span data-testid="custom-icon" />} dot />,
    );

    expect(screen.getByTestId('custom-icon')).not.toBeNull();
    expect(container.querySelectorAll('.MuiChip-icon')).toHaveLength(1);
  });

  it('renders the label in the monospace face when mono is set', () => {
    const { container } = renderWithProviders(<Chip label="7" tone="neutral" mono />);
    const label = container.querySelector('.MuiChip-label');

    expect(label).not.toBeNull();
    expect(getComputedStyle(label as Element).fontFamily).toBe('monospace');
  });

  it('leaves the label in the default face otherwise', () => {
    const { container } = renderWithProviders(<Chip label="Healthy" tone="ok" />);
    const label = container.querySelector('.MuiChip-label');

    expect(getComputedStyle(label as Element).fontFamily).not.toBe('monospace');
  });

  it('renders no icon slot when neither icon nor dot is requested', () => {
    const { container } = renderWithProviders(<Chip label="Healthy" tone="ok" />);

    expect(container.querySelector('.MuiChip-icon')).toBeNull();
  });
});
