/**
 * Correlation id plumbing for the one request path (task 026, `FE-D13`).
 *
 * The client MAY send an id on every outgoing request so a trace exists even
 * if the server never answers. Once a response arrives, **the server's id is
 * authoritative** — it is what gets surfaced in the UI (toasts, error
 * panels) and in any client-side log line, never the id the client sent.
 */

/** Header name in both directions — the client sends it, the server echoes or replaces it. */
export const CORRELATION_ID_HEADER = 'X-Correlation-Id';

function randomHex(bytes: number): string {
  const arr = new Uint8Array(bytes);
  if (typeof crypto !== 'undefined' && 'getRandomValues' in crypto) {
    crypto.getRandomValues(arr);
  } else {
    for (let i = 0; i < arr.length; i += 1) {
      arr[i] = Math.floor(Math.random() * 256);
    }
  }
  return Array.from(arr, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** Generates an id for an outgoing request. Prefers `crypto.randomUUID`. */
export function generateCorrelationId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  // Fallback for a test/runtime environment without `randomUUID` — still
  // opaque and unique enough to correlate a single request's log lines.
  return `${randomHex(4)}-${randomHex(2)}-${randomHex(2)}-${randomHex(2)}-${randomHex(6)}`;
}

/**
 * Reads the AUTHORITATIVE id off a response. Falls back to the id the
 * client sent only when the server did not echo one back at all — never
 * silently drops the concept of "no id available".
 */
export function readCorrelationId(response: Response, sentId: string): string {
  return response.headers.get(CORRELATION_ID_HEADER) ?? sentId;
}
