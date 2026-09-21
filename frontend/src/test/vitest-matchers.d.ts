/**
 * Types the custom `toHaveNoViolations` matcher (jest-axe, wired in
 * setup.ts) onto Vitest's own `Assertion`, the pattern Vitest's docs
 * recommend for a custom `expect.extend()` matcher.
 */
import 'vitest';

declare module 'vitest' {
  interface Matchers<T = unknown> {
    toHaveNoViolations: () => T;
  }
}
