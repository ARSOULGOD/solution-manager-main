import { afterEach, describe, expect, it, vi } from 'vitest';

import { loadConfig, parseRuntimeConfig, RuntimeConfigError } from './loadConfig';

function jsonResponse(body: unknown, init: { ok?: boolean; status?: number } = {}) {
  return {
    ok: init.ok ?? true,
    status: init.status ?? 200,
    statusText: 'status text',
    json: () => Promise.resolve(body),
  } as Response;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('parseRuntimeConfig', () => {
  it('accepts a well-formed config', () => {
    expect(
      parseRuntimeConfig({ apiBasePath: '/api', idleTimeoutMinutes: 15, channel: 'stable' }),
    ).toEqual({ apiBasePath: '/api', idleTimeoutMinutes: 15, channel: 'stable' });
  });

  it('defaults idleTimeoutMinutes to 30 when absent', () => {
    expect(parseRuntimeConfig({ apiBasePath: '/api', channel: 'stable' })).toEqual({
      apiBasePath: '/api',
      idleTimeoutMinutes: 30,
      channel: 'stable',
    });
  });

  it('ignores an unknown extra field', () => {
    expect(
      parseRuntimeConfig({
        apiBasePath: '/api',
        channel: 'stable',
        somethingUnexpected: 'ignored',
      }),
    ).toEqual({ apiBasePath: '/api', idleTimeoutMinutes: 30, channel: 'stable' });
  });

  it('rejects a non-object body', () => {
    expect(() => parseRuntimeConfig('not an object')).toThrow(RuntimeConfigError);
  });

  it('rejects a missing apiBasePath', () => {
    expect(() => parseRuntimeConfig({ channel: 'stable' })).toThrow(RuntimeConfigError);
  });

  it('rejects a missing channel', () => {
    expect(() => parseRuntimeConfig({ apiBasePath: '/api' })).toThrow(RuntimeConfigError);
  });

  it('rejects a non-numeric idleTimeoutMinutes', () => {
    expect(() =>
      parseRuntimeConfig({ apiBasePath: '/api', channel: 'stable', idleTimeoutMinutes: '30' }),
    ).toThrow(RuntimeConfigError);
  });
});

describe('loadConfig', () => {
  it('resolves with the parsed config on a successful fetch', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({ apiBasePath: '/api', channel: 'stable' })),
    );

    await expect(loadConfig()).resolves.toEqual({
      apiBasePath: '/api',
      idleTimeoutMinutes: 30,
      channel: 'stable',
    });
  });

  it('fetches with cache: "no-store"', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ apiBasePath: '/api', channel: 'stable' }));
    vi.stubGlobal('fetch', fetchMock);

    await loadConfig();

    expect(fetchMock).toHaveBeenCalledWith('/config.json', { cache: 'no-store' });
  });

  it('rejects when the file is missing (non-ok response)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({}, { ok: false, status: 404 })));

    await expect(loadConfig()).rejects.toBeInstanceOf(RuntimeConfigError);
  });

  it('rejects when the body is not valid JSON', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        statusText: 'OK',
        json: () => Promise.reject(new Error('malformed')),
      }),
    );

    await expect(loadConfig()).rejects.toBeInstanceOf(RuntimeConfigError);
  });

  it('rejects when the parsed body fails validation', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ channel: 'stable' })));

    await expect(loadConfig()).rejects.toBeInstanceOf(RuntimeConfigError);
  });
});
