/**
 * The streaming download helper (task 035, `DM-D14`, `FE-D19`, design §13,
 * §15). A same-origin NAVIGATION, never a `fetch()` — the browser's own
 * network stack streams the response straight to disk; this module never
 * holds a byte of the body in JS memory. The session cookie travels
 * because it is a normal same-origin browser request — there is no
 * XHR/fetch object here for `credentials` to even apply to.
 *
 * Gated by a high-consequence confirm at the CALL SITE (task 016's
 * `HighConsequenceDialog`) — a streaming export can strain the deployment
 * (design §15). `startStreamingDownload` itself has no confirm of its
 * own; it assumes the caller already confirmed.
 *
 * ⚠️ **Distinct from `DataTable`'s small client-side CSV export** (task
 * 020): that one builds a `Blob` from rows ALREADY IN MEMORY (one page of
 * a grid) via `URL.createObjectURL` — fine at that size, never for a
 * streaming range export. This module never constructs a `Blob` and never
 * calls `fetch` — different mechanism, different name, different
 * confirmation.
 */
import { assertRelativePath } from './planeClientFactory';

const FALLBACK_CLEANUP_MS = 60_000;

/**
 * Triggers a same-origin, cookie-authenticated, streamed-to-disk download
 * via a hidden `<iframe>` navigation — robust against the SPA's own route
 * changing under the caller (an `<a>` click navigates the CURRENT tab if
 * the server ever answers without a `Content-Disposition: attachment`
 * header; an iframe navigation cannot take the SPA down with it).
 */
export function startStreamingDownload(url: string): void {
  assertRelativePath(url);

  const iframe = document.createElement('iframe');
  iframe.style.display = 'none';
  iframe.setAttribute('aria-hidden', 'true');

  const cleanup = (): void => {
    iframe.remove();
  };
  // A pure attachment response never renders a document into the iframe,
  // so "load" does not fire in every browser — the timeout is the
  // guaranteed cleanup path; "load" (when it does fire) just removes it sooner.
  iframe.addEventListener('load', cleanup);
  const timer = setTimeout(cleanup, FALLBACK_CLEANUP_MS);
  iframe.addEventListener('load', () => {
    clearTimeout(timer);
  });

  iframe.src = url;
  document.body.appendChild(iframe);
}
