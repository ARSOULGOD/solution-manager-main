/**
 * The one request path (task 026, design §13). Every plane client (task
 * 027) composes this rather than calling `fetch` directly.
 *
 * - `credentials: 'same-origin'` so the gateway's httpOnly session cookie
 *   travels (`FE-D8`, design §15) — no client-held token.
 * - The client MAY send a correlation id; the response's id is the one that
 *   ever reaches the UI or a log line (`FE-D13`, correlation.ts).
 * - JSON in/out. Unknown response fields are ignored — this module runs no
 *   schema validator, so nothing is ever stripped or "validated away"
 *   (`DM-D2`); the body is simply cast to the caller's `T`.
 * - **Never logs a request or response body** (`I6`, `FE-D13`) — the one
 *   log call this module makes (network-level failure, no response at all)
 *   carries only method/path/correlationId/error message.
 */
import { logger } from '../observability';

import { CORRELATION_ID_HEADER, generateCorrelationId, readCorrelationId } from './correlation';
import { ApiError, type CsrfConfig, type RequestOptions, type RequestResult } from './types';

let csrfConfig: CsrfConfig = {};

/** Configures the CSRF header hook (§16 Q3). No wire mechanism is specified yet — this only wires the seam. */
export function configureCsrf(config: CsrfConfig): void {
  csrfConfig = config;
}

type UnauthorizedListener = () => void;
const unauthorizedListeners = new Set<UnauthorizedListener>();

/**
 * Fires whenever ANY request gets a 401 — task 030's `localSessionAdapter`
 * subscribes here to implement `AuthAdapter.onSessionExpired`. Kept
 * auth-agnostic and in this lower-level module (rather than the reverse)
 * so `shared/api` never has to import from `shared/auth`.
 */
export function onUnauthorized(listener: UnauthorizedListener): () => void {
  unauthorizedListeners.add(listener);
  return () => unauthorizedListeners.delete(listener);
}

function notifyUnauthorized(): void {
  for (const listener of unauthorizedListeners) {
    listener();
  }
}

function buildHeaders(options: RequestOptions, correlationId: string): Headers {
  const headers = new Headers(options.headers);
  headers.set(CORRELATION_ID_HEADER, correlationId);
  if (options.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const token = csrfConfig.getToken?.();
  if (token) {
    headers.set(csrfConfig.headerName ?? 'X-CSRF-Token', token);
  }
  return headers;
}

interface ParsedErrorBody {
  code?: string | undefined;
  message?: string | undefined;
  details?: unknown;
}

/**
 * Best-effort error-body parse. A non-JSON or empty body must not itself throw.
 *
 * The real backend's error shape is `ProblemBody`
 * (`backend/console-gateway/.../ProblemBody.java`, mirroring
 * `contracts/openapi/console.yaml`'s `Problem` schema) — `{type, title,
 * status, code, correlationId, detail?, instance?}`. It has NO `message`
 * field; `title` is the fixed, human-readable string for `code` (never an
 * exception message, never user input — see that file's header) and is
 * always present, so it is read here as the fallback for `message`. This
 * keeps `ApiError.message` meaningful against the real backend instead of
 * always falling back to the generic `Request failed with status N` in
 * `ApiError`'s constructor. `detail`, when present, is preferred over
 * `title` — it is the more specific of the two when the server sends both.
 */
async function parseErrorBody(response: Response): Promise<ParsedErrorBody> {
  try {
    const text = await response.text();
    if (text.length === 0) {
      return {};
    }
    const body: unknown = JSON.parse(text);
    if (body !== null && typeof body === 'object') {
      const record = body as Record<string, unknown>;
      const message = typeof record['message'] === 'string' ? record['message'] : undefined;
      const detail = typeof record['detail'] === 'string' ? record['detail'] : undefined;
      const title = typeof record['title'] === 'string' ? record['title'] : undefined;
      return {
        code: typeof record['code'] === 'string' ? record['code'] : undefined,
        message: message ?? detail ?? title,
        details: record,
      };
    }
    return {};
  } catch {
    return {};
  }
}

/**
 * Logs only request metadata — never a body (`I6`). The sole log call in
 * this module. `logger`'s field type is primitives-only (task 102), so the
 * `Error` is narrowed to its message here rather than handed over whole: an
 * `Error` thrown by `fetch` can carry a `cause` chain, and a chain is a
 * place a request body can hide.
 */
function logNetworkFailure(
  method: string,
  path: string,
  correlationId: string,
  error: unknown,
): void {
  logger.error('http.request-failed', {
    method,
    path,
    correlationId,
    message: error instanceof Error ? error.message : String(error),
  });
}

/**
 * Same request as `request()`, but returns the server's correlation id
 * alongside the body instead of discarding it (task 040) — the seam a
 * mutation reaches for when it needs that id for something other than an
 * error (e.g. a success toast, where `FE-D13` requires the SERVER's id).
 * `request()` below is a thin wrapper that drops the id, kept as the
 * default so every existing call site is unaffected.
 */
export async function requestWithCorrelation<T>(
  path: string,
  options: RequestOptions = {},
): Promise<RequestResult<T>> {
  const method = options.method ?? 'GET';
  const correlationId = generateCorrelationId();
  const headers = buildHeaders(options, correlationId);

  const init: RequestInit = {
    method,
    credentials: 'same-origin',
    headers,
    ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}),
    ...(options.signal !== undefined ? { signal: options.signal } : {}),
  };

  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (error) {
    // AbortError is the caller's own doing (superseded request) — never logged as a failure.
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    logNetworkFailure(method, path, correlationId, error);
    throw error;
  }

  const responseCorrelationId = readCorrelationId(response, correlationId);

  if (response.status === 401) {
    notifyUnauthorized();
  }

  if (!response.ok) {
    const { code, message, details } = await parseErrorBody(response);
    const retryAfterHeader = response.headers.get('Retry-After');
    const retryAfterSeconds =
      retryAfterHeader !== null && /^\d+$/.test(retryAfterHeader) ? Number(retryAfterHeader) : undefined;
    throw new ApiError({
      status: response.status,
      code,
      message,
      details,
      correlationId: responseCorrelationId,
      retryAfterSeconds,
    });
  }

  // A 204 (or any explicitly empty body) never throws trying to parse JSON.
  if (response.status === 204) {
    return { data: undefined as T, correlationId: responseCorrelationId };
  }
  const text = await response.text();
  if (text.length === 0) {
    return { data: undefined as T, correlationId: responseCorrelationId };
  }
  return { data: JSON.parse(text) as T, correlationId: responseCorrelationId };
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { data } = await requestWithCorrelation<T>(path, options);
  return data;
}

/**
 * Ensures a superseded request is aborted BEFORE the next one for the same
 * logical slot is issued (`dynamic-metrics` §10) — e.g. a widget re-querying
 * on a range change must not let the stale request's response land after
 * the new one's. Callers key by whatever identifies "the same slot" (a
 * query hash, a widget id); `next()` aborts the previous controller
 * synchronously, then returns the new signal to pass into `request()`.
 */
export class RequestCoordinator {
  private readonly controllers = new Map<string, AbortController>();

  next(key: string): AbortSignal {
    this.controllers.get(key)?.abort();
    const controller = new AbortController();
    this.controllers.set(key, controller);
    return controller.signal;
  }

  /** Aborts and forgets every in-flight request this coordinator is tracking. */
  clear(): void {
    for (const controller of this.controllers.values()) {
      controller.abort();
    }
    this.controllers.clear();
  }
}
