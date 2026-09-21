/**
 * Compile-only assertion (`npm run typecheck`) — same pattern as
 * `controls/registry.typecheck.test.ts`. `@ts-expect-error` fails to
 * compile if the next line does NOT produce a type error, so this file
 * only passes `tsc` when `ConfigStepperProps.reached` genuinely rejects
 * `'effective-verified'` — the task's own "must be incapable of marking
 * effective-verified reached" acceptance line, enforced structurally
 * rather than by a runtime check a fixture could route around.
 */
import { describe, expect, it } from 'vitest';

import type { ConfigStepperProps } from './ConfigStepper.types';

describe('ConfigStepper reached (compile-time)', () => {
  it('rejects effective-verified as a reached value', () => {
    // @ts-expect-error — the fifth rung can never be claimed reached.
    const invalid: ConfigStepperProps = { reached: 'effective-verified' };

    expect(invalid).toBeDefined();
  });
});
