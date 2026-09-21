/**
 * Compile-only assertions (`npm run typecheck`). `@ts-expect-error` itself
 * fails to compile if the next line does NOT produce a type error, so
 * this file only passes tsc when `IconButton` genuinely requires
 * `aria-label` (FE-011 acceptance: "IconButton without aria-label fails
 * typecheck").
 */
import { describe, expect, it } from 'vitest';

import { IconButton } from './Button';

describe('IconButton aria-label requirement (compile-time)', () => {
  it('is a required prop, not an optional one', () => {
    // @ts-expect-error — aria-label is required; omitting it must not compile.
    const missingLabel = <IconButton>{<span aria-hidden>×</span>}</IconButton>;
    // @ts-expect-error — same check with an explicit disabled prop present.
    const missingLabelDisabled = <IconButton disabled>{<span aria-hidden>×</span>}</IconButton>;

    expect(missingLabel).toBeDefined();
    expect(missingLabelDisabled).toBeDefined();
  });
});
