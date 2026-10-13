import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';
import { Button } from '../Button';

import { Menu } from './Menu';

describe('Menu', () => {
  it('opens from the trigger, supports a caption row, and returns focus on close', async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    renderWithProviders(
      <Menu
        trigger={<Button>More</Button>}
        entries={[
          { kind: 'caption', label: 'Capabilities' },
          { label: 'Edit', onClick: onEdit },
        ]}
      />,
    );

    const trigger = screen.getByRole('button', { name: 'More' });
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');

    await user.click(trigger);
    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByText('Capabilities')).not.toBeNull();
    await user.click(screen.getByRole('menuitem', { name: 'Edit' }));
    expect(onEdit).toHaveBeenCalled();
    expect(trigger).toHaveFocus();
  });

  it('renders a content entry as a free-form panel, not a clickable menuitem', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Menu
        trigger={<Button>Account</Button>}
        entries={[{ kind: 'content', content: <span>Devon Reyes</span> }, { label: 'Sign out' }]}
      />,
    );

    await user.click(screen.getByRole('button', { name: 'Account' }));
    expect(screen.getByText('Devon Reyes')).not.toBeNull();
    expect(screen.queryByRole('menuitem', { name: 'Devon Reyes' })).toBeNull();
  });
});
