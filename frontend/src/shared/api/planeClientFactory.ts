/**
 * Shared factory behind the three named clients (task 027, design §13,
 * `FE-D19`, `I7`, conflict C5). Not part of the public barrel on its own —
 * `cpClient.ts` / `dpClient.ts` / `consoleClient.ts` each call this with
 * their own prefix so the one security-relevant check here (no absolute
 * URL) exists in exactly one place rather than three copies that could
 * drift apart.
 */
import { request, requestWithCorrelation } from './http';
import type { HttpMethod, RequestOptions, RequestResult } from './types';

export interface PlaneClient {
  get<T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>): Promise<T>;
  post<T>(
    path: string,
    body?: unknown,
    options?: Omit<RequestOptions, 'method' | 'body'>,
  ): Promise<T>;
  put<T>(
    path: string,
    body?: unknown,
    options?: Omit<RequestOptions, 'method' | 'body'>,
  ): Promise<T>;
  patch<T>(
    path: string,
    body?: unknown,
    options?: Omit<RequestOptions, 'method' | 'body'>,
  ): Promise<T>;
  delete<T>(path: string, options?: Omit<RequestOptions, 'method' | 'body'>): Promise<T>;
  /**
   * Same as `post`, but resolves the server's correlation id alongside the
   * body (`FE-D13`) — for a mutation whose UI reports its own outcome
   * (`ToastInput.correlationId` is required, never client-fabricated).
   */
  postWithCorrelation<T>(
    path: string,
    body?: unknown,
    options?: Omit<RequestOptions, 'method' | 'body'>,
  ): Promise<RequestResult<T>>;
  /** Same as `patch`, and for the same reason as `postWithCorrelation` — an edit reports its own outcome. */
  patchWithCorrelation<T>(
    path: string,
    body?: unknown,
    options?: Omit<RequestOptions, 'method' | 'body'>,
  ): Promise<RequestResult<T>>;
  /** Same as `delete`, and for the same reason as `postWithCorrelation` — a removal reports its own outcome. */
  deleteWithCorrelation<T>(
    path: string,
    options?: Omit<RequestOptions, 'method' | 'body'>,
  ): Promise<RequestResult<T>>;
  /**
   * Resolves a path against this client's prefix WITHOUT issuing a
   * request — for a same-origin navigation-style download URL (task 035's
   * streaming CSV export) where the browser, not `fetch`, makes the call.
   * Runs the same absolute-URL guard as every other method.
   */
  resolve(path: string): string;
}

// Matches a scheme ("https://") or a protocol-relative URL ("//host/…").
// The SPA never stores a plane host, so a caller passing either is a bug —
// fail loudly rather than silently issuing a cross-origin request.
const ABSOLUTE_URL_PATTERN = /^([a-z][a-z0-9+.-]*:)?\/\//i;

/**
 * Exported so task 035's `download.ts` — which navigates the browser
 * directly to a URL rather than going through a `PlaneClient` method —
 * can run the identical same-origin guard rather than a second copy.
 */
export function assertRelativePath(path: string): void {
  if (ABSOLUTE_URL_PATTERN.test(path)) {
    throw new Error(
      `Received an absolute URL ("${path}"). The SPA never holds a plane host (FE-D19) — every call must be a path relative to the one gateway origin.`,
    );
  }
}

function joinPath(prefix: string, path: string): string {
  assertRelativePath(path);
  const normalizedPrefix = prefix.endsWith('/') ? prefix.slice(0, -1) : prefix;
  const normalizedPath = path.startsWith('/') ? path : `/${path}`;
  return `${normalizedPrefix}${normalizedPath}`;
}

/** Builds a client whose every call is same-origin, prefixed by `prefix`. */
export function createPlaneClient(prefix: string): PlaneClient {
  function call<T>(
    method: HttpMethod,
    path: string,
    body: unknown,
    options: Omit<RequestOptions, 'method' | 'body'> = {},
  ): Promise<T> {
    return request<T>(joinPath(prefix, path), {
      ...options,
      method,
      ...(body !== undefined ? { body } : {}),
    });
  }

  return {
    get: (path, options) => call('GET', path, undefined, options),
    post: (path, body, options) => call('POST', path, body, options),
    put: (path, body, options) => call('PUT', path, body, options),
    patch: (path, body, options) => call('PATCH', path, body, options),
    delete: (path, options) => call('DELETE', path, undefined, options),
    postWithCorrelation: (path, body, options) =>
      requestWithCorrelation(joinPath(prefix, path), {
        ...options,
        method: 'POST',
        ...(body !== undefined ? { body } : {}),
      }),
    patchWithCorrelation: (path, body, options) =>
      requestWithCorrelation(joinPath(prefix, path), {
        ...options,
        method: 'PATCH',
        ...(body !== undefined ? { body } : {}),
      }),
    deleteWithCorrelation: (path, options) =>
      requestWithCorrelation(joinPath(prefix, path), { ...options, method: 'DELETE' }),
    resolve: (path) => joinPath(prefix, path),
  };
}
