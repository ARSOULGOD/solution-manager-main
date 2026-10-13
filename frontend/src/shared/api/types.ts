/**
 * Error, result and HTTP types for the one request path (§6.0.1). Every name
 * here is binding for the rest of the API layer — task 028 (`ApiError.ts`,
 * `errorCodes.ts`, `retry.ts`) re-exports and builds on these rather than
 * redeclaring them, so `http.ts` (this task) never has to import forward
 * from a later task to construct the error it throws.
 */

/** Codes SPECIFIED by `dynamic-metrics` §5.8 — a real body shape exists. */
export type SpecifiedErrorCode =
  | 'SERIES_CAP_EXCEEDED'
  | 'AGGREGATION_NOT_APPLICABLE'
  | 'GROUP_BY_REQUIRES_AGGREGATION'
  | 'STAT_REQUIRES_AGGREGATION'
  | 'METRIC_UNTYPED';

/** Codes NAMED in design §13/§16 but with no body shape anywhere. CONTRACT: TBD (§16 Q9). */
export type TbdErrorCode =
  | 'CONFIG_DIVERGED'
  | 'APPLY_INDETERMINATE'
  | 'VERSION_DRIFT'
  | 'VERSION_UNKNOWN'
  | 'CONFIG_CONFLICT';

export type ErrorCode = SpecifiedErrorCode | TbdErrorCode;

export interface ApiErrorInit {
  status: number;
  /** A known `ErrorCode`, OR any other string the server sends — an unknown code still surfaces, never silently dropped. */
  code?: string | undefined;
  message?: string | undefined;
  correlationId?: string | undefined;
  details?: unknown;
  /** Seconds from the `Retry-After` header on a 429 (`RATE_LIMITED`) response — `undefined` when absent or unparseable. */
  retryAfterSeconds?: number | undefined;
}

/**
 * The one error type every request can throw. `code` is typed as `string`
 * (not the closed `ErrorCode` union) deliberately — an unknown code must
 * still surface rather than being coerced away by the type system; narrow
 * to `ErrorCode` at the point that switches on a KNOWN value (FE-028's
 * `errorCodes.ts`).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code?: string | undefined;
  /** FE-D13: the SERVER's id — always authoritative, never the client-sent one. */
  readonly correlationId?: string | undefined;
  /** CONTRACT: TBD — the 422/409 body shape (§16 Q9). */
  readonly details?: unknown;
  /** Seconds from the `Retry-After` header on a 429 (`RATE_LIMITED`) response — `undefined` when absent or unparseable. */
  readonly retryAfterSeconds?: number | undefined;

  constructor(init: ApiErrorInit) {
    super(init.message ?? `Request failed with status ${init.status}`);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.correlationId = init.correlationId;
    this.details = init.details;
    this.retryAfterSeconds = init.retryAfterSeconds;
  }
}

/** G14. The ONLY retry predicate in the app (canonical copy re-exported by task 028's retry.ts). */
export const shouldRetry = (failureCount: number, error: unknown): boolean =>
  error instanceof ApiError && error.status >= 500 && error.status !== 501 && failureCount < 3;

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

export interface RequestOptions {
  method?: HttpMethod;
  /** JSON-serialisable request body. Never logged (`I6`, `FE-D13`). */
  body?: unknown;
  signal?: AbortSignal;
  headers?: Readonly<Record<string, string>>;
}

/** The CSRF hook (§16 Q3) — mechanism only, no wire contract exists yet. */
export interface CsrfConfig {
  /** Defaults to `X-CSRF-Token`. */
  headerName?: string;
  getToken?: () => string | undefined;
}

/**
 * A successful response paired with the SERVER's correlation id (`FE-D13`).
 * `request()`/`PlaneClient` methods discard this id on success — it only
 * ever surfaced on `ApiError` — because no caller needed it until a
 * mutation that reports its own outcome (a toast, `ToastInput.correlationId`
 * is REQUIRED, never client-fabricated) existed. `requestWithCorrelation`
 * (task 040) is that seam.
 */
export interface RequestResult<T> {
  data: T;
  correlationId: string;
}
