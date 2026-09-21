import { describe, expect, it } from 'vitest';

import { RESTART_STRATEGIES } from '../restartStrategies';

import {
  evaluateStrategyAvailability,
  type StrategyAvailabilityModel,
} from './strategyAvailability';

const manual = RESTART_STRATEGIES.find((s) => s.key === 'manual')!;
const rollingHealthGated = RESTART_STRATEGIES.find((s) => s.key === 'rolling-health-gated')!;
const rollingNoValidation = RESTART_STRATEGIES.find((s) => s.key === 'rolling-no-validation')!;
const restartAll = RESTART_STRATEGIES.find((s) => s.key === 'restart-all')!;
const scheduled = RESTART_STRATEGIES.find((s) => s.key === 'scheduled')!;

const permissive: StrategyAvailabilityModel = {
  requiresUniformConfig: false,
  healthMappingDeclared: true,
};

describe('evaluateStrategyAvailability', () => {
  it('never suppresses manual, restart-all, or an already-available strategy', () => {
    expect(evaluateStrategyAvailability(manual, permissive)).toEqual({ disabled: false });
    expect(evaluateStrategyAvailability(restartAll, permissive)).toEqual({ disabled: false });
    expect(evaluateStrategyAvailability(rollingHealthGated, permissive)).toEqual({
      disabled: false,
    });
    expect(evaluateStrategyAvailability(rollingNoValidation, permissive)).toEqual({
      disabled: false,
    });
  });

  it('suppresses scheduled unconditionally — out of this release', () => {
    const result = evaluateStrategyAvailability(scheduled, permissive);
    expect(result.disabled).toBe(true);
    expect(result.reason).toBeTruthy();
  });

  it('suppresses rolling-health-gated with a stated reason when no health mapping is declared', () => {
    const result = evaluateStrategyAvailability(rollingHealthGated, {
      ...permissive,
      healthMappingDeclared: false,
    });
    expect(result.disabled).toBe(true);
    expect(result.reason).toBeTruthy();
  });

  it('leaves rolling-no-validation alone when only the health mapping is missing', () => {
    const result = evaluateStrategyAvailability(rollingNoValidation, {
      ...permissive,
      healthMappingDeclared: false,
    });
    expect(result).toEqual({ disabled: false });
  });

  it('prefers the MI-specific healthGateUnsafeReason over the generic uniform-config reason', () => {
    const specific = 'a standby reports healthy while processing nothing (open item 15)';
    const result = evaluateStrategyAvailability(rollingHealthGated, {
      requiresUniformConfig: true,
      healthMappingDeclared: true,
      healthGateUnsafeReason: specific,
    });
    expect(result).toEqual({ disabled: true, reason: specific });
  });

  it('falls back to the generic uniform-config reason for rolling-health-gated with no MI-specific reason', () => {
    const result = evaluateStrategyAvailability(rollingHealthGated, {
      requiresUniformConfig: true,
      healthMappingDeclared: true,
    });
    expect(result.disabled).toBe(true);
    expect(result.reason).toBeTruthy();
  });

  it('suppresses rolling-no-validation with a stated reason when uniform config is required', () => {
    const result = evaluateStrategyAvailability(rollingNoValidation, {
      requiresUniformConfig: true,
      healthMappingDeclared: true,
    });
    expect(result.disabled).toBe(true);
    expect(result.reason).toBeTruthy();
  });

  it('reproduces the Cache fixture: health-gated unavailable, no-validation suppressed, both stated', () => {
    const cacheModel: StrategyAvailabilityModel = {
      requiresUniformConfig: true,
      healthMappingDeclared: true,
      healthGateUnsafeReason: 'standby reports healthy while processing nothing (open item 15)',
    };

    const healthGated = evaluateStrategyAvailability(rollingHealthGated, cacheModel);
    const noValidation = evaluateStrategyAvailability(rollingNoValidation, cacheModel);

    expect(healthGated.disabled).toBe(true);
    expect(healthGated.reason).toContain('open item 15');
    expect(noValidation.disabled).toBe(true);
    expect(noValidation.reason).not.toContain('open item 15');
  });
});
