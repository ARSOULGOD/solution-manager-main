import { describe, expect, it } from 'vitest';

import { ApiError } from './ApiError';
import { MUTATION_RETRY, retryDelay, shouldRetry } from './retry';

describe('shouldRetry (G14 — the only retry predicate)', () => {
  it('retries a 500', () => {
    expect(shouldRetry(0, new ApiError({ status: 500 }))).toBe(true);
  });

  it('never retries a 422 — an incoherent request is not transient', () => {
    expect(shouldRetry(0, new ApiError({ status: 422 }))).toBe(false);
  });

  it('never retries a 501 (Not Implemented — retrying cannot help)', () => {
    expect(shouldRetry(0, new ApiError({ status: 501 }))).toBe(false);
  });

  it('never retries a 4xx', () => {
    expect(shouldRetry(0, new ApiError({ status: 404 }))).toBe(false);
    expect(shouldRetry(0, new ApiError({ status: 403 }))).toBe(false);
    expect(shouldRetry(0, new ApiError({ status: 401 }))).toBe(false);
  });

  it('stops after 3 failures even for a retryable status', () => {
    expect(shouldRetry(3, new ApiError({ status: 503 }))).toBe(false);
    expect(shouldRetry(2, new ApiError({ status: 503 }))).toBe(true);
  });

  it('does not retry a non-ApiError (e.g. a thrown network TypeError)', () => {
    expect(shouldRetry(0, new TypeError('network down'))).toBe(false);
  });
});

describe('retryDelay', () => {
  it('grows and is capped at 5s', () => {
    expect(retryDelay(0)).toBe(500);
    expect(retryDelay(1)).toBe(1000);
    expect(retryDelay(2)).toBe(2000);
    expect(retryDelay(10)).toBe(5000);
  });
});

describe('MUTATION_RETRY', () => {
  it('is always false — a mutation is never retried automatically', () => {
    expect(MUTATION_RETRY).toBe(false);
  });
});
