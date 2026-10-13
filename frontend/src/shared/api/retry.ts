/**
 * The retry policy (task 028, `FE-D10`, `FE-D20`). `shouldRetry` (the ONLY
 * retry predicate — G14) lives in `types.ts`; re-exported here under the
 * stable `@shared/api/retry` path. This module adds the two things that are
 * genuinely new to task 028: a bounded backoff schedule, and an explicit
 * "never" for mutations.
 */
export { shouldRetry } from './types';

const BASE_DELAY_MS = 500;
const MAX_DELAY_MS = 5000;

/** Bounded exponential backoff for a query retry — 500ms, 1s, 2s, 4s, capped at 5s. */
export function retryDelay(attemptIndex: number): number {
  return Math.min(BASE_DELAY_MS * 2 ** attemptIndex, MAX_DELAY_MS);
}

/**
 * A mutation (save, apply, restart, any POST/PUT/PATCH/DELETE) is NEVER
 * retried automatically. Re-firing a write the operator didn't ask twice
 * for is worse than surfacing the failure — task 029 wires this into
 * QueryClient's `mutations.retry` default.
 */
export const MUTATION_RETRY = false;
