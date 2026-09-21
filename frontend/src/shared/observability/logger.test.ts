/**
 * FE-102 · the logger's own tests (design §20, `FE-D13`, `I6`).
 *
 * The acceptance line is *"no secret can reach a log"*, and that is carried
 * by three mechanisms which need three different kinds of proof:
 *
 *   · **The type** — proven by `@ts-expect-error` assertions below. If the
 *     field type is ever widened to accept an object, those lines stop
 *     erroring and `npm run typecheck` fails. A runtime test cannot prove a
 *     compile-time guarantee, so this is the only place it can be checked.
 *   · **The lint rule** — proven by `src/test/lint-fixtures/app/logs-secret.ts`
 *     and asserted by `npm run lint:fixtures:assert`, not here.
 *   · **Runtime redaction** — proven here, including the case the other two
 *     cannot see: a key assembled at runtime.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import { formatLogLine, logger, redact, REDACTED } from './logger';

afterEach(() => {
  vi.restoreAllMocks();
});

describe('redact', () => {
  it.each([
    'password',
    'secret',
    'clientSecret',
    'client_secret',
    'apiKey',
    'api_key',
    'authToken',
    'privateKey',
    'authorization',
    'cookie',
    'CREDENTIAL',
  ])('masks the value under %s', (key) => {
    expect(redact({ [key]: 'hunter2hunter2' })[key]).toBe(REDACTED);
  });

  it('leaves an ordinary field alone', () => {
    expect(redact({ method: 'POST', status: 409 })).toEqual({ method: 'POST', status: 409 });
  });

  it('over-redacts rather than reasoning: even a secretRef is masked', () => {
    // `I6` permits logging a secret REFERENCE — it is the resolved value
    // that may never be logged — so this mask is not required. It is
    // accepted anyway: substring matching that tried to exempt `…Ref` would
    // be a regex making a judgement about intent, and the failure direction
    // there is a published credential. A field named for what it identifies
    // rather than for the mechanism logs fine.
    expect(redact({ secretRef: 'db-password' })['secretRef']).toBe(REDACTED);
    expect(redact({ configField: 'db-password' })['configField']).toBe('db-password');
  });

  it('masks a key assembled at runtime — the case lint cannot see', () => {
    const field = ['pass', 'word'].join('');
    expect(redact({ [field]: 'letmein12' })[field]).toBe(REDACTED);
  });
});

describe('formatLogLine', () => {
  it('puts the level and event first, so a support call can quote it', () => {
    expect(formatLogLine('error', 'http.request-failed', { method: 'POST', status: 500 })).toBe(
      '[sm] error http.request-failed | method=POST status=500',
    );
  });

  it('redacts before formatting, never after', () => {
    const line = formatLogLine('warn', 'auth.rejected', { token: 'abc123def456' });
    expect(line).not.toContain('abc123def456');
    expect(line).toContain(`token=${REDACTED}`);
  });

  it('omits an undefined field rather than printing "undefined"', () => {
    expect(formatLogLine('error', 'route.render-failed', { correlationId: undefined })).toBe(
      '[sm] error route.render-failed',
    );
  });
});

describe('logger', () => {
  it('formats a string, never handing console a live object reference', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    logger.error('widget.render-failed', { title: 'Throughput' });

    expect(spy).toHaveBeenCalledExactlyOnceWith(
      '[sm] error widget.render-failed | title=Throughput',
    );
    // One argument, and it is a string: a browser console renders a logged
    // OBJECT as an expandable live reference, so anything it still points at
    // stays reachable from the log. See the module header.
    expect(spy.mock.calls[0]).toHaveLength(1);
    expect(typeof spy.mock.calls[0]?.[0]).toBe('string');
  });

  it('emits warn and error unconditionally — an operator has to be able to read them out', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    logger.warn('config.stale');
    logger.error('http.request-failed');

    expect(warn).toHaveBeenCalledOnce();
    expect(error).toHaveBeenCalledOnce();
  });

  it('emits debug and info under DEV (Vitest runs with import.meta.env.DEV true)', () => {
    // The production half of this guarantee is not testable from a Vitest
    // run, which is always DEV: it is asserted against the real built bundle
    // by `npm run verify:production` (task 101) and by the DEV-fold rule the
    // module header documents.
    const debug = vi.spyOn(console, 'debug').mockImplementation(() => undefined);
    const info = vi.spyOn(console, 'info').mockImplementation(() => undefined);

    logger.debug('query.dedup', { key: 'metrics' });
    logger.info('boot.config-loaded', { channel: 'stable' });

    expect(debug).toHaveBeenCalledOnce();
    expect(info).toHaveBeenCalledOnce();
  });
});

describe('the field type refuses a payload (I6)', () => {
  it('rejects an object, an Error and an array at compile time', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    // Each of these is a real leak shape: a config value set, a thrown error
    // whose `cause` reaches a response body, and a metric series. If any of
    // these lines stops erroring, `LogValue` has been widened and this test
    // fails the build — which is the point.
    // @ts-expect-error a config value object may never be logged
    logger.error('designer.save-failed', { values: { namespace: 'prod' } });
    // @ts-expect-error an Error carries a cause chain
    logger.error('http.request-failed', { error: new Error('boom') });
    // @ts-expect-error a metric payload may never be logged (design §20)
    logger.error('chart.render-failed', { series: [1, 2, 3] });

    expect(spy).toHaveBeenCalledTimes(3);
  });

  it('accepts the primitives a support call actually needs', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    logger.error('http.request-failed', {
      method: 'POST',
      path: '/cp/auth/login',
      status: 500,
      correlationId: 'srv-abc123',
      retriable: false,
    });

    expect(spy.mock.calls[0]?.[0]).toContain('correlationId=srv-abc123');
  });
});
