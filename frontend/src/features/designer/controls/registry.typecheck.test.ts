/**
 * Compile-only assertions (`npm run typecheck`) — same pattern as task
 * 011's `Button.typecheck.test.tsx`. `@ts-expect-error` itself fails to
 * compile if the next line does NOT produce a type error, so this file
 * only passes `tsc` when `ControlRegistry` genuinely requires every
 * `ControlKind` member and rejects any other key (task 047 acceptance:
 * "missing kind fails typecheck").
 */
import { describe, expect, it } from 'vitest';

import { controlRegistry, type ControlRegistry } from './registry';

describe('control registry exhaustiveness (compile-time)', () => {
  it('rejects an object literal missing a ControlKind member', () => {
    const { yaml, ...withoutYaml } = controlRegistry;

    // @ts-expect-error — omitting "yaml" must not compile.
    const incomplete: ControlRegistry = withoutYaml;

    expect(yaml).toBeDefined();
    expect(incomplete).toBeDefined();
  });

  it('rejects a key outside the ControlKind union', () => {
    // @ts-expect-error — a key not in ControlKind must not compile.
    const withExtra: ControlRegistry = { ...controlRegistry, bogus: controlRegistry.text };

    expect(withExtra).toBeDefined();
  });
});
