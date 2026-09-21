/**
 * FE-102 · the console's only logger (design §20, `FE-D13`, `I6`).
 *
 * Three mechanisms, in order of how much they can be relied on:
 *
 *   1. **The type forbids a payload.** `LogFields` admits only primitives.
 *      There is no overload that takes an object, an `Error`, a config
 *      value, a metric series or a response body — so the largest class of
 *      leak (`logger.error('save failed', { values })`) is not a mistake
 *      you can make and still compile. `I6` says secrets must never reach a
 *      log; a type that cannot carry one is a better guarantee than a
 *      redactor that has to recognise one.
 *   2. **Lint forbids a secret-shaped key.** A primitive is still a leak if
 *      it is `{ password: pw }`. `eslint.config.js`'s `NO_SECRET_LOG_FIELD`
 *      rejects a field named `secret|password|token|credential|…` at the
 *      call site, with `src/test/lint-fixtures/app/logs-secret.ts` proving
 *      it still fires.
 *   3. **Redaction at runtime.** A key assembled at runtime (`[name]: v`)
 *      passes both of the above, so values under a secret-shaped key are
 *      replaced with `[redacted]` here. This is the net, never the plan —
 *      it is a name match, so a credential under an innocent key is not
 *      caught. Same limit `FE-D23`'s scanner records.
 *
 * **Levels.** `debug` and `info` are development-only: the calls sit behind
 * a literal `import.meta.env.DEV`, so Vite folds them and a production build
 * carries neither the call nor the string it would have logged (design §20's
 * "structured `console` in development"). `warn` and `error` always emit —
 * they are what an operator reads out over a support call, and a console
 * that goes silent in production is a console nobody can support.
 *
 * **No shipping, deliberately.** There is no transport, no `fetch`, no
 * buffer, no SaaS SDK. Design §20: *"production error shipping waits on a
 * backend log endpoint — do not invent a SaaS RUM product for air-gap
 * on-prem."* `REQ-003` means an install may have no egress at all, so a
 * logger that tried to phone home would be a broken deployment rather than
 * an observable one. When a backend log endpoint exists, it is added HERE,
 * behind `config.json`, and nowhere else.
 *
 * **The correlation id is the server's.** `logger` never invents one. Call
 * sites pass the id they were given by the response (`FE-D13`:
 * `ApiError.correlationId`, `RequestResult.correlationId`), which is the id
 * the server can actually find in its own logs. A client-generated id is
 * for stitching a support *conversation* (`app/shell/SupportMenu.tsx`) and
 * is never presented as an audit key.
 */

/**
 * What a log field may be. Primitives only — see the header. Widening this
 * to `unknown`, `object`, or `Error` reopens the leak the type closes, and
 * is the one change to this file that needs a decision (`I3`) rather than a
 * commit.
 */
export type LogValue = string | number | boolean | null | undefined;

export type LogFields = Readonly<Record<string, LogValue>>;

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

/**
 * Field names whose value is replaced before it reaches the console.
 * Matched as a **substring**, case-insensitively, so `authToken`,
 * `client_secret` and `apiKeyRef` are all caught.
 *
 * Substring matching deliberately over-redacts: a field named `secretRef`
 * holds a *reference*, which `I6` explicitly permits storing and logging —
 * and it is masked here anyway, because the cost of masking a reference is
 * a slightly less useful log line, while the cost of the regex being too
 * clever is a published credential. A caller that needs the reference in a
 * log line should name the field for what it identifies (`configField`,
 * `bindingName`) rather than for the mechanism.
 */
const SECRET_FIELD =
  /secret|password|passwd|pwd|credential|token|apikey|api_key|privatekey|private_key|authorization|cookie/i;

export const REDACTED = '[redacted]';

/** Exported for the test that proves redaction happens before formatting, not after. */
export function redact(fields: LogFields): Record<string, LogValue> {
  const safe: Record<string, LogValue> = {};
  for (const [key, value] of Object.entries(fields)) {
    safe[key] = SECRET_FIELD.test(key) ? REDACTED : value;
  }
  return safe;
}

/**
 * One line, machine-greppable, with the event key first so a support
 * conversation can quote it: `[sm] error http.request-failed | method=POST …`.
 *
 * Deliberately a string rather than `console.error(msg, object)`: a browser
 * console renders a logged object as a live, expandable reference to the
 * ORIGINAL — so a later mutation shows up in the log, and anything the
 * object still points at is one click away. Formatting to text at the call
 * site severs that.
 */
function format(level: LogLevel, event: string, fields: LogFields): string {
  const pairs = Object.entries(redact(fields))
    .filter(([, value]) => value !== undefined)
    .map(([key, value]) => `${key}=${String(value)}`);
  return pairs.length > 0
    ? `[sm] ${level} ${event} | ${pairs.join(' ')}`
    : `[sm] ${level} ${event}`;
}

/** Exported for tests; `logger` below is what application code uses. */
export function formatLogLine(level: LogLevel, event: string, fields: LogFields = {}): string {
  return format(level, event, fields);
}

export interface Logger {
  /** Development only — folded out of a production build. */
  debug(event: string, fields?: LogFields): void;
  /** Development only — folded out of a production build. */
  info(event: string, fields?: LogFields): void;
  /** Always emitted: an operator may need to read this out on a support call. */
  warn(event: string, fields?: LogFields): void;
  /** Always emitted. Pass the SERVER's correlationId when there is one (`FE-D13`). */
  error(event: string, fields?: LogFields): void;
}

export const logger: Logger = {
  debug(event, fields = {}) {
    // The literal `import.meta.env.DEV` must stay inline here — the same
    // constant-folding rule `app/dev/gallery.routes.ts` documents. Routing
    // it through a variable would ship the call and its arguments.
    if (import.meta.env.DEV) {
      console.debug(format('debug', event, fields));
    }
  },
  info(event, fields = {}) {
    if (import.meta.env.DEV) {
      console.info(format('info', event, fields));
    }
  },
  warn(event, fields = {}) {
    console.warn(format('warn', event, fields));
  },
  error(event, fields = {}) {
    console.error(format('error', event, fields));
  },
};
