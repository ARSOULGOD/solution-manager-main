import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { GroupByControl } from './GroupByControl';

function Probe({
  labelKeys,
  initialValue = [],
}: {
  labelKeys: readonly string[];
  initialValue?: readonly string[];
}) {
  const [value, setValue] = useState<readonly string[]>(initialValue);
  return <GroupByControl value={value} onChange={setValue} labelKeys={labelKeys} />;
}

describe('GroupByControl', () => {
  it('shows a caption and no toggle buttons when the metric has no label keys', () => {
    renderWithProviders(<Probe labelKeys={[]} />);
    expect(screen.getByText('This metric has no label keys to group by.')).not.toBeNull();
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('renders one toggle button per label key', () => {
    renderWithProviders(<Probe labelKeys={['region', 'org']} />);
    expect(screen.getByRole('button', { name: 'region' })).not.toBeNull();
    expect(screen.getByRole('button', { name: 'org' })).not.toBeNull();
  });

  it('clicking a key adds it to the selection', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe labelKeys={['region']} />);
    const button = screen.getByRole('button', { name: 'region' });
    expect(button).toHaveAttribute('aria-pressed', 'false');
    await user.click(button);
    expect(button).toHaveAttribute('aria-pressed', 'true');
  });

  it('clicking a selected key removes it from the selection', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Probe labelKeys={['region']} initialValue={['region']} />);
    const button = screen.getByRole('button', { name: 'region' });
    expect(button).toHaveAttribute('aria-pressed', 'true');
    await user.click(button);
    expect(button).toHaveAttribute('aria-pressed', 'false');
  });

  it('disabled disables every button', () => {
    renderWithProviders(
      <GroupByControl value={[]} onChange={() => {}} labelKeys={['region', 'org']} disabled />,
    );
    expect(screen.getByRole('button', { name: 'region' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'org' })).toBeDisabled();
  });
});
