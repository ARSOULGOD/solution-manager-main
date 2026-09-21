import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { setViewportWidth } from '../../../test/matchMedia';
import { renderWithProviders } from '../../../test/renderWithProviders';

import { ControlStep } from './ControlStep';

describe('ControlStep', () => {
  it('renders the step number, title and subtitle, unlocked', () => {
    renderWithProviders(
      <ControlStep step={1} title="Metric" subtitle="single-select">
        <div>metric body</div>
      </ControlStep>,
    );
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('Metric')).toBeInTheDocument();
    expect(screen.getByText('· single-select')).toBeInTheDocument();
    expect(screen.getByText('metric body')).toBeInTheDocument();
  });

  it('renders no subtitle when omitted', () => {
    renderWithProviders(
      <ControlStep step={3} title="Series by">
        <div>body</div>
      </ControlStep>,
    );
    expect(screen.queryByText(/·/)).not.toBeInTheDocument();
  });

  it('states the lock reason when locked', () => {
    renderWithProviders(
      <ControlStep step={2} title="Instances" locked lockedReason="Pick a metric first.">
        <div>instances body</div>
      </ControlStep>,
    );
    expect(screen.getByText('Pick a metric first.')).toBeInTheDocument();
    // The body still renders — locked dims it, it does not hide it.
    expect(screen.getByText('instances body')).toBeInTheDocument();
  });

  it('renders no lock reason when unlocked', () => {
    renderWithProviders(
      <ControlStep step={1} title="Metric">
        <div>body</div>
      </ControlStep>,
    );
    expect(screen.getByText('body')).toBeInTheDocument();
    expect(document.querySelector('[data-locked="true"]')).not.toBeInTheDocument();
  });

  it('marks the root with the step number and lock state as data attributes', () => {
    renderWithProviders(
      <ControlStep step={5} title="Group by" locked lockedReason="reason">
        <div>body</div>
      </ControlStep>,
    );
    const root = document.querySelector('[data-control-step="5"]');
    expect(root).toHaveAttribute('data-locked', 'true');
  });
});

describe('ControlStep accordion below lg (task 109, FE-D29)', () => {
  it('at lg and up renders no button semantics and stays always expanded', () => {
    setViewportWidth(1280);
    renderWithProviders(
      <ControlStep step={1} title="Metric">
        <div>metric body</div>
      </ControlStep>,
    );
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.getByText('metric body')).toBeInTheDocument();
  });

  it('below lg renders a labelled, expanded-by-default disclosure button', () => {
    setViewportWidth(320);
    renderWithProviders(
      <ControlStep step={1} title="Metric">
        <div>metric body</div>
      </ControlStep>,
    );
    const toggle = screen.getByRole('button', { name: /Metric/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('metric body')).toBeInTheDocument();
  });

  it('below lg collapsing the step hides its body but keeps the header visible', async () => {
    setViewportWidth(320);
    const user = userEvent.setup();
    renderWithProviders(
      <ControlStep step={1} title="Metric">
        <div>metric body</div>
      </ControlStep>,
    );

    const toggle = screen.getByRole('button', { name: /Metric/ });
    await user.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('metric body')).not.toBeInTheDocument();
    // Same step number/title header, still on screen.
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('Metric')).toBeInTheDocument();

    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('metric body')).toBeInTheDocument();
  });

  it('below lg a locked step keeps its reason visible even when collapsed', async () => {
    setViewportWidth(320);
    const user = userEvent.setup();
    renderWithProviders(
      <ControlStep step={2} title="Instances" locked lockedReason="Pick a metric first.">
        <div>instances body</div>
      </ControlStep>,
    );

    const toggle = screen.getByRole('button', { name: /Instances/ });
    await user.click(toggle);

    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('instances body')).not.toBeInTheDocument();
    // The lock reason is never gated behind expansion — a locked step
    // that loses its reason is worse than a locked step (task spec).
    expect(screen.getByText('Pick a metric first.')).toBeInTheDocument();
  });
});
