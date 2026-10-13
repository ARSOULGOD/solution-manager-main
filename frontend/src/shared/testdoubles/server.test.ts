/**
 * The harness testing itself (task 008 acceptance): an unmocked request
 * fails the test. `server.listen({ onUnhandledRequest: "error" })` runs
 * once per test file via src/test/setup.ts's beforeAll.
 */
import { describe, expect, it } from 'vitest';

describe('MSW node server', () => {
  it('fails a request with no matching handler', async () => {
    await expect(fetch('https://unhandled.example.test/nope')).rejects.toThrow();
  });
});
