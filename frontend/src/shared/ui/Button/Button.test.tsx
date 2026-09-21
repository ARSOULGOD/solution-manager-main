import { fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { renderWithProviders, screen } from '../../../test/renderWithProviders';

import { Button, IconButton } from './Button';

describe('Button', () => {
  it('renders its label and fires onClick', () => {
    const onClick = vi.fn();
    renderWithProviders(<Button onClick={onClick}>Save</Button>);

    const button = screen.getByRole('button', { name: 'Save' });
    fireEvent.click(button);

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('does not fire onClick when disabled', () => {
    const onClick = vi.fn();
    renderWithProviders(
      <Button onClick={onClick} disabled>
        Save
      </Button>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(onClick).not.toHaveBeenCalled();
  });

  it('renders an icon marker for the danger variant with no icon prop supplied', () => {
    renderWithProviders(<Button variant="danger">Delete instance</Button>);

    const button = screen.getByRole('button', { name: 'Delete instance' });
    expect(button.querySelector('svg')).not.toBeNull();
  });

  it('renders an icon marker for the dangerContained variant with no icon prop supplied', () => {
    renderWithProviders(<Button variant="dangerContained">Force raw</Button>);

    const button = screen.getByRole('button', { name: 'Force raw' });
    expect(button.querySelector('svg')).not.toBeNull();
  });

  it('renders no icon for the default contained variant', () => {
    renderWithProviders(<Button>Save</Button>);

    const button = screen.getByRole('button', { name: 'Save' });
    expect(button.querySelector('svg')).toBeNull();
  });

  it('lets a caller override the danger marker with its own icon', () => {
    renderWithProviders(
      <Button variant="danger" icon={<span data-testid="custom-icon" />}>
        Delete
      </Button>,
    );

    expect(screen.getByTestId('custom-icon')).not.toBeNull();
  });
});

describe('IconButton', () => {
  it('renders with its required accessible name and fires onClick', () => {
    const onClick = vi.fn();
    renderWithProviders(
      <IconButton aria-label="Close" onClick={onClick}>
        <span aria-hidden>×</span>
      </IconButton>,
    );

    const button = screen.getByRole('button', { name: 'Close' });
    fireEvent.click(button);

    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('does not fire onClick when disabled', () => {
    const onClick = vi.fn();
    renderWithProviders(
      <IconButton aria-label="Close" onClick={onClick} disabled>
        <span aria-hidden>×</span>
      </IconButton>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Close' }));

    expect(onClick).not.toHaveBeenCalled();
  });

  it('forwards the menu-trigger ARIA wiring (FE-017) same as Button', () => {
    renderWithProviders(
      <IconButton aria-label="More" aria-haspopup="menu" aria-expanded aria-controls="menu-1">
        <span aria-hidden>⋮</span>
      </IconButton>,
    );

    const button = screen.getByRole('button', { name: 'More' });
    expect(button.getAttribute('aria-haspopup')).toBe('menu');
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(button.getAttribute('aria-controls')).toBe('menu-1');
  });
});
