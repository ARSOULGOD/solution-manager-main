import { describe, expect, it, vi } from 'vitest';

import { fireEvent, renderWithProviders, screen } from '../../../test/renderWithProviders';
import { RESTART_STRATEGIES } from '../restartStrategies';


import { StrategyPicker } from './StrategyPicker';

/**
 * Cache's own rollout-gating facts (DECISIONS.md open item 15) — MI-specific
 * data with no home in `SolutionPackage`/`ViewModel` (`strategyAvailability.ts`
 * takes them as their own prop type, independent of `project()`'s output).
 * Restated here rather than imported: the fixture that used to carry these
 * (`CACHE_VIEW_MODEL`) was deleted in `FE-122`.
 */
const CACHE_ROLLOUT_GATING = {
  requiresUniformConfig: true,
  healthMappingDeclared: true,
  healthGateUnsafeReason:
    "This MI's standby reports healthy while processing nothing, so a health gate would advance past a replica that took no traffic (open item 15).",
} as const;

describe('StrategyPicker', () => {
  it('defaults to manual, selected and enabled, permissive model', () => {
    renderWithProviders(
      <StrategyPicker
        strategies={RESTART_STRATEGIES}
        value="manual"
        onChange={() => undefined}
        requiresUniformConfig={false}
        healthMappingDeclared={true}
      />,
    );

    const manual = screen.getByRole('radio', { name: /Manual/ });
    expect(manual).toHaveProperty('checked', true);
    expect(manual).toHaveProperty('disabled', false);
    expect(screen.getByText('Default')).not.toBeNull();
  });

  it('selecting an enabled strategy fires onChange with its key', () => {
    const onChange = vi.fn();
    renderWithProviders(
      <StrategyPicker
        strategies={RESTART_STRATEGIES}
        value="manual"
        onChange={onChange}
        requiresUniformConfig={false}
        healthMappingDeclared={true}
      />,
    );

    fireEvent.click(screen.getByRole('radio', { name: /Restart all/ }));
    expect(onChange).toHaveBeenCalledWith('restart-all');
  });

  it('marks scheduled disabled and unavailable — out of this release', () => {
    renderWithProviders(
      <StrategyPicker
        strategies={RESTART_STRATEGIES}
        value="manual"
        onChange={() => undefined}
        requiresUniformConfig={false}
        healthMappingDeclared={true}
      />,
    );

    const scheduled = screen.getByRole('radio', { name: /Scheduled/ });
    expect(scheduled).toHaveProperty('disabled', true);
    expect(screen.getByText('Out of this release.')).not.toBeNull();
  });

  it('marks high-consequence and full-outage strategies with their confirm-even-when-authorized chip', () => {
    renderWithProviders(
      <StrategyPicker
        strategies={RESTART_STRATEGIES}
        value="manual"
        onChange={() => undefined}
        requiresUniformConfig={false}
        healthMappingDeclared={true}
      />,
    );

    expect(screen.getByText('High-consequence · confirm even when authorized')).not.toBeNull();
    expect(screen.getByText('Full outage · confirm even when authorized')).not.toBeNull();
  });

  it('reproduces the Cache fixture exactly: uniform banner, health-gated unavailable with the standby reason, no-validation suppressed', () => {
    renderWithProviders(
      <StrategyPicker
        strategies={RESTART_STRATEGIES}
        value="manual"
        onChange={() => undefined}
        requiresUniformConfig={CACHE_ROLLOUT_GATING.requiresUniformConfig}
        healthMappingDeclared={CACHE_ROLLOUT_GATING.healthMappingDeclared}
        healthGateUnsafeReason={CACHE_ROLLOUT_GATING.healthGateUnsafeReason}
      />,
    );

    expect(
      screen.getByText(
        'This package requires all replicas to hold identical config, so both rolling strategies are suppressed.',
      ),
    ).not.toBeNull();

    const healthGated = screen.getByRole('radio', { name: /Rolling · health-gated/ });
    expect(healthGated).toHaveProperty('disabled', true);
    expect(screen.getByText(/standby reports healthy while processing nothing/)).not.toBeNull();

    const noValidation = screen.getByRole('radio', { name: /Rolling · no validation/ });
    expect(noValidation).toHaveProperty('disabled', true);
  });

  it('suppresses no strategy when the model is fully permissive except scheduled', () => {
    renderWithProviders(
      <StrategyPicker
        strategies={RESTART_STRATEGIES}
        value="manual"
        onChange={() => undefined}
        requiresUniformConfig={false}
        healthMappingDeclared={true}
      />,
    );

    const radios = screen.getAllByRole('radio');
    const disabledCount = radios.filter((radio) => (radio as HTMLInputElement).disabled).length;
    expect(disabledCount).toBe(1);
  });

  it('always renders the default multi-replica ordering note', () => {
    renderWithProviders(
      <StrategyPicker
        strategies={RESTART_STRATEGIES}
        value="manual"
        onChange={() => undefined}
        requiresUniformConfig={false}
        healthMappingDeclared={true}
      />,
    );

    expect(
      screen.getByText(/Default multi-replica ordering is stage everywhere, then restart/),
    ).not.toBeNull();
  });
});
