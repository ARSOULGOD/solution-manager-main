/**
 * jest-axe ships no bundled types, and @types/jest-axe is pinned to the
 * DefinitelyTyped `jest` namespace (and drags in `@types/jest`'s ambient
 * globals as a dependency) — so this types the two things this repo
 * actually imports from `jest-axe` directly instead. A script-mode file
 * (no top-level import/export): `declare module` here declares a whole
 * new ambient module rather than augmenting one, which is what an
 * untyped package with zero existing type info needs.
 */
declare module 'jest-axe' {
  import type { AxeResults, RunOptions } from 'axe-core';

  export function axe(html: Element | Document | string, options?: RunOptions): Promise<AxeResults>;

  export function configureAxe(options?: RunOptions & { globalOptions?: RunOptions }): typeof axe;

  export const toHaveNoViolations: {
    toHaveNoViolations: (results: AxeResults) => { pass: boolean; message: () => string };
  };
}
