import { describe, expect, it } from 'vitest';

import { ApiError } from './ApiError';
import { createQueryClient } from './queryClient';

describe('createQueryClient', () => {
  it('disables refetchOnWindowFocus', () => {
    const client = createQueryClient();
    expect(client.getDefaultOptions().queries?.refetchOnWindowFocus).toBe(false);
  });

  it('uses the shared retry predicate — retries a 500, never a 422', () => {
    const client = createQueryClient();
    const retry = client.getDefaultOptions().queries?.retry;
    expect(typeof retry).toBe('function');
    const retryFn = retry as (count: number, error: unknown) => boolean;
    expect(retryFn(0, new ApiError({ status: 500 }))).toBe(true);
    expect(retryFn(0, new ApiError({ status: 422 }))).toBe(false);
  });

  it('keeps previous data across a refetch (dims, never blanks)', () => {
    const client = createQueryClient();
    expect(client.getDefaultOptions().queries?.placeholderData).toBeDefined();
  });

  it('never retries a mutation automatically', () => {
    const client = createQueryClient();
    expect(client.getDefaultOptions().mutations?.retry).toBe(false);
  });

  it('turns structural sharing on', () => {
    const client = createQueryClient();
    expect(client.getDefaultOptions().queries?.structuralSharing).toBe(true);
  });
});
