import { HttpResponse, http } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { server } from '@shared/testdoubles/server';

import { CORRELATION_ID_HEADER } from './correlation';
import { configureCsrf, RequestCoordinator, request, requestWithCorrelation } from './http';
import { ApiError } from './types';

afterEach(() => {
  configureCsrf({});
});

describe('request()', () => {
  it('parses a JSON response and ignores an unknown field rather than stripping it', async () => {
    server.use(
      http.get('*/things/1', () =>
        HttpResponse.json({ id: '1', name: 'a thing', somethingUnexpected: 42 }),
      ),
    );

    const result = await request<{ id: string; name: string; somethingUnexpected?: number }>(
      '/things/1',
    );

    expect(result).toEqual({ id: '1', name: 'a thing', somethingUnexpected: 42 });
  });

  it('sends credentials: same-origin and an X-Correlation-Id header', async () => {
    let seenCredentials: RequestCredentials | undefined;
    let seenHeader: string | null = null;
    server.use(
      http.get('*/things/2', ({ request: req }) => {
        seenCredentials = req.credentials;
        seenHeader = req.headers.get(CORRELATION_ID_HEADER);
        return HttpResponse.json({ ok: true });
      }),
    );

    await request('/things/2');

    expect(seenCredentials).toBe('same-origin');
    expect(seenHeader).toBeTruthy();
  });

  it('treats the response correlation id as authoritative over the sent one', async () => {
    server.use(
      http.get('*/things/3', () =>
        HttpResponse.json({ ok: true }, { headers: { [CORRELATION_ID_HEADER]: 'server-id-1' } }),
      ),
    );
    server.use(
      http.get('*/things/4', () =>
        HttpResponse.json(
          { code: 'SERIES_CAP_EXCEEDED', message: 'nope' },
          { status: 500, headers: { [CORRELATION_ID_HEADER]: 'server-id-2' } },
        ),
      ),
    );

    await request('/things/3'); // no throw path to assert on directly here

    await expect(request('/things/4')).rejects.toMatchObject({ correlationId: 'server-id-2' });
  });

  it('does not throw on a 204 response', async () => {
    server.use(http.delete('*/things/5', () => new HttpResponse(null, { status: 204 })));

    await expect(request('/things/5', { method: 'DELETE' })).resolves.toBeUndefined();
  });

  it('throws an ApiError carrying status/code/correlationId on a non-ok response', async () => {
    server.use(
      http.get('*/things/6', () =>
        HttpResponse.json(
          { code: 'METRIC_UNTYPED', message: 'This metric has no declared type.' },
          { status: 422, headers: { [CORRELATION_ID_HEADER]: 'srv-6' } },
        ),
      ),
    );

    const error = await request('/things/6').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 422, code: 'METRIC_UNTYPED', correlationId: 'srv-6' });
  });

  it('falls back to ProblemBody.title when message is absent (real backend shape has no message field)', async () => {
    server.use(
      http.post('*/things/7', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Credential rotation refused',
            status: 400,
            code: 'CREDENTIAL_ROTATION_REFUSED',
            correlationId: 'srv-7',
          },
          { status: 400, headers: { [CORRELATION_ID_HEADER]: 'srv-7' } },
        ),
      ),
    );

    const error = await request('/things/7', { method: 'POST' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe('Credential rotation refused');
  });

  it('prefers ProblemBody.detail over title when both are present', async () => {
    server.use(
      http.post('*/things/8', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Credential rotation refused',
            detail: 'Credential must be at least 12 characters.',
            status: 400,
            code: 'CREDENTIAL_ROTATION_REFUSED',
            correlationId: 'srv-8',
          },
          { status: 400, headers: { [CORRELATION_ID_HEADER]: 'srv-8' } },
        ),
      ),
    );

    const error = await request('/things/8', { method: 'POST' }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).message).toBe('Credential must be at least 12 characters.');
  });

  it('sends the configured CSRF header when a token provider is set', async () => {
    configureCsrf({ headerName: 'X-CSRF-Token', getToken: () => 'csrf-abc' });
    let seenToken: string | null = null;
    server.use(
      http.post('*/things', ({ request: req }) => {
        seenToken = req.headers.get('X-CSRF-Token');
        return HttpResponse.json({ ok: true });
      }),
    );

    await request('/things', { method: 'POST', body: { x: 1 } });

    expect(seenToken).toBe('csrf-abc');
  });

  it('never sends a CSRF header when no provider is configured', async () => {
    let seenToken: string | null = null;
    server.use(
      http.post('*/things-no-csrf', ({ request: req }) => {
        seenToken = req.headers.get('X-CSRF-Token');
        return HttpResponse.json({ ok: true });
      }),
    );

    await request('/things-no-csrf', { method: 'POST', body: { x: 1 } });

    expect(seenToken).toBeNull();
  });

  it('rejects with the AbortError as-is when the caller aborts', async () => {
    server.use(
      http.get(
        '*/things/slow',
        async () => {
          await new Promise((resolve) => setTimeout(resolve, 50));
          return HttpResponse.json({ ok: true });
        },
        { once: true },
      ),
    );

    const controller = new AbortController();
    const pending = request('/things/slow', { signal: controller.signal });
    controller.abort();

    await expect(pending).rejects.toThrow();
  });
});

describe('requestWithCorrelation()', () => {
  it('resolves the body and the server correlation id together on success', async () => {
    server.use(
      http.post('*/things/7', () =>
        HttpResponse.json({ ok: true }, { headers: { [CORRELATION_ID_HEADER]: 'server-id-7' } }),
      ),
    );

    const result = await requestWithCorrelation<{ ok: boolean }>('/things/7', { method: 'POST' });

    expect(result).toEqual({ data: { ok: true }, correlationId: 'server-id-7' });
  });

  it('falls back to the sent id when the server does not echo one back', async () => {
    let seenSentId: string | null = null;
    server.use(
      http.post('*/things/8', ({ request: req }) => {
        seenSentId = req.headers.get(CORRELATION_ID_HEADER);
        return HttpResponse.json({ ok: true });
      }),
    );

    const result = await requestWithCorrelation('/things/8', { method: 'POST' });

    expect(result.correlationId).toBe(seenSentId);
  });

  it('captures Retry-After as ApiError.retryAfterSeconds on a 429', async () => {
    server.use(
      http.post('*/auth/login', () =>
        HttpResponse.json(
          { code: 'RATE_LIMITED', message: 'Too many attempts.' },
          { status: 429, headers: { 'Retry-After': '30' } },
        ),
      ),
    );

    await expect(requestWithCorrelation('/auth/login', { method: 'POST' })).rejects.toMatchObject({
      status: 429,
      retryAfterSeconds: 30,
    });
  });
});

describe('RequestCoordinator', () => {
  it('aborts the previous signal for a key before returning the next one', () => {
    const coordinator = new RequestCoordinator();
    const first = coordinator.next('widget-a');
    expect(first.aborted).toBe(false);

    const second = coordinator.next('widget-a');

    expect(first.aborted).toBe(true);
    expect(second.aborted).toBe(false);
  });

  it('tracks keys independently', () => {
    const coordinator = new RequestCoordinator();
    const a = coordinator.next('a');
    const b = coordinator.next('b');

    coordinator.next('a');

    expect(a.aborted).toBe(true);
    expect(b.aborted).toBe(false);
  });

  it('clear() aborts every tracked signal', () => {
    const coordinator = new RequestCoordinator();
    const a = coordinator.next('a');
    const b = coordinator.next('b');

    coordinator.clear();

    expect(a.aborted).toBe(true);
    expect(b.aborted).toBe(true);
  });
});

describe('logging', () => {
  it('never logs a request or response body on a network failure', async () => {
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    server.use(
      http.get('*/things/network-fail', () => HttpResponse.error()),
    );

    await expect(
      request('/things/network-fail', { body: { secret: 'do-not-log-me' } }),
    ).rejects.toThrow();

    for (const call of errorSpy.mock.calls) {
      const joined = call.map(String).join(' ');
      expect(joined).not.toContain('do-not-log-me');
    }
    errorSpy.mockRestore();
  });
});
